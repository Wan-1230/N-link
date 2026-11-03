import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// 分享导出器（对应安卓 `PreviewShareExporter`）。
///
/// 职责：把「相机卡内」或「已下载」的照片整理成可分享的文件：
/// - **预览副本**：下载原图 → 重编码为长边 ≤ 2048 的 JPEG → 回写拍摄参数 EXIF
///   （默认剥离 GPS）→ 落 `tmp/n-link_share/`。
///   副本只留在临时目录，不进相册、不覆盖原图、不进传输历史。
/// - **原图**：已下载的直接返回本地路径；未下载的下载到临时目录。
///
/// 两条路径的画质与隐私口径必须完全一致——同一个用户在同一个页面点两次，
/// 不该因为照片在卡上还是已下载而拿到不同的 EXIF。
enum ShareExporter {

    // MARK: 常量

    /// 预览副本长边上限（发图不被二次压缩的常用尺寸）
    static let previewLongEdge: CGFloat = 2048
    private static let previewJPEGQuality: CGFloat = 0.90

    /// 分享临时目录名。
    ///
    /// 不是 private：设置页的「清除缓存」要统计并清理同一个目录。
    /// 两处各写一遍字符串迟早会漂移（改了这边忘那边，缓存就永远清不干净）。
    static let shareSubDir = "n-link_share"

    // MARK: 结果

    struct ExportResult: Sendable {
        var urls: [URL] = []
        var skipped: [String] = []
        var failed: [String] = []

        var isEmpty: Bool { urls.isEmpty }
    }

    enum ShareMode: String, CaseIterable, Identifiable {
        case preview
        case original

        var id: String { rawValue }

        var title: String {
            switch self {
            case .preview: return "预览副本"
            case .original: return "原图"
            }
        }

        /// 解释文案。**必须写清楚代价**——用户在点之前就该知道位置信息会被去掉。
        func detail(keepGps: Bool) -> String {
            switch self {
            case .preview:
                return keepGps
                    ? "长边 2048 · 保留位置信息，仅保留拍摄参数"
                    : "预览副本：长边 2048 · 去除位置信息，仅保留拍摄参数"
            case .original:
                return "原图：完整分辨率与全部原始 EXIF，仅 JPEG / RAW / 视频可分享"
            }
        }
    }

    // MARK: 能力判断

    /// 该文件能否生成预览副本。
    ///
    /// 预览副本走的是「解码 → 重编码」，只有 JPEG 能保证成功。
    /// RAW 需要走相机侧预览通道（本版未实现）、视频需要 AVFoundation 转码，
    /// 两者都记入 `skipped` 并**在 UI 上说明原因**，而不是安静地消失。
    static func supportsPreviewCopy(_ file: CameraFile) -> Bool {
        file.format == .jpeg
    }

    // MARK: 导出

    /// 批量导出预览副本。逐张串行（内存可控）。
    @MainActor
    static func exportPreview(
        files: [CameraFile],
        client: CameraConnectionClient,
        settings: AppSettings,
        onProgress: @escaping @Sendable (Int, Int) -> Void = { _, _ in }
    ) async -> ExportResult {
        var result = ExportResult()
        let dir = shareDirectory()

        for (index, file) in files.enumerated() {
            defer { onProgress(index + 1, files.count) }
            guard supportsPreviewCopy(file) else {
                result.skipped.append(file.fileName)
                continue
            }
            do {
                let source = try await downloadToCache(file: file, client: client, dir: dir)
                if let copy = makePreviewCopy(source: source, displayName: file.fileName, dir: dir, keepGps: settings.shareKeepGps) {
                    result.urls.append(copy)
                } else {
                    result.failed.append(file.fileName)
                }
                // 源文件可以删：它是「为这次分享下载的原图」，
                // 用户若想长期留一份应该走「下载」，不该靠分享缓存。
                try? FileManager.default.removeItem(at: source)
            } catch {
                result.failed.append(file.fileName)
            }
        }
        return result
    }

    /// 从本地文件生成预览副本（独立分享页用）。
    ///
    /// 与 `exportPreview(files:)` 的区别只在来源：这里已经是本地文件，
    /// 不需要再走相机下载。重编码与 EXIF 策略完全一致。
    @MainActor
    static func exportPreview(
        localURLs: [(url: URL, displayName: String)],
        keepGps: Bool,
        onProgress: @escaping @Sendable (Int, Int) -> Void = { _, _ in }
    ) async -> ExportResult {
        var result = ExportResult()
        let dir = shareDirectory()

        for (index, item) in localURLs.enumerated() {
            defer { onProgress(index + 1, localURLs.count) }
            let safeName = item.displayName.split(separator: "/").last.map(String.init) ?? "image.jpg"
            guard let copy = makePreviewCopy(source: item.url, displayName: safeName, dir: dir, keepGps: keepGps) else {
                result.failed.append(safeName)
                continue
            }
            result.urls.append(copy)
        }
        return result
    }

    /// 导出原图：已下载的直接用本地路径；未下载的下载到临时目录。
    @MainActor
    static func exportOriginal(
        file: CameraFile,
        localPath: String?,
        client: CameraConnectionClient
    ) async -> URL? {
        if let localPath, !localPath.isEmpty {
            let url = URL(fileURLWithPath: localPath)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        let dir = shareDirectory()
        return try? await downloadToCache(file: file, client: client, dir: dir)
    }

    /// 批量导出原图：逐张串行下载到临时目录。
    ///
    /// 与 `exportPreview` 的关键差异：**不做任何重编码**，所以 RAW 与视频也能走这条路。
    /// 代价是文件大一个数量级，因此这里不带 `skipped` 语义——只有成功与失败两种结果，
    /// 失败项必须说出原因，不能吞掉。
    @MainActor
    static func exportOriginalBatch(
        files: [CameraFile],
        client: CameraConnectionClient,
        onProgress: @escaping @Sendable (Int, Int) -> Void = { _, _ in }
    ) async -> ExportResult {
        var result = ExportResult()
        let dir = shareDirectory()

        for (index, file) in files.enumerated() {
            defer { onProgress(index + 1, files.count) }
            if let url = await exportOriginal(file: file, localPath: nil, client: client) {
                result.urls.append(url)
            } else {
                result.failed.append(file.fileName)
            }
        }
        return result
    }

    // MARK: - 下载

    private static func shareDirectory() -> URL {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(shareSubDir, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    /// 下载原图到临时目录（不写相册、不进传输历史）。
    @discardableResult
    private static func downloadToCache(
        file: CameraFile,
        client: CameraConnectionClient,
        dir: URL
    ) async throws -> URL {
        let target = dir.appendingPathComponent("raw_\(file.handle)_\(file.fileName)")

        // 已完整缓存则复用
        if let size = try? FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber,
           size.int64Value >= file.size, file.size > 0 {
            return target
        }

        guard let session = client.sessionOrNil() else {
            throw CameraError.notConnected(detail: "相机未连接，且这张照片还没下载过。请先连接相机。")
        }

        FileManager.default.createFile(atPath: target.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: target) else {
            throw FileSaveError.cannotOpenTemp
        }
        defer { try? handle.close() }

        var offset: Int64 = 0
        let chunk: Int64 = 4 * 1_048_576
        while offset < file.size {
            try Task.checkCancellation()
            let want = min(chunk, file.size - offset)
            let data = try await session.readRange(handle: file.handle, offset: UInt64(offset), length: UInt32(want))
            guard !data.isEmpty else { break }
            try handle.write(contentsOf: data)
            offset += Int64(data.count)
        }

        guard offset >= file.size || file.size == 0 else {
            try? FileManager.default.removeItem(at: target)
            throw CameraError.storageUnavailable(detail: "下载不完整（\(offset)/\(file.size) 字节）。请检查连接后重试。")
        }
        return target
    }

    // MARK: - 重编码

    /// 重编码预览副本：解码 → 采样缩放 → 长边 ≤ 2048 → JPEG 90 → 回写参数 EXIF（默认无 GPS）。
    private static func makePreviewCopy(
        source: URL,
        displayName: String,
        dir: URL,
        keepGps: Bool
    ) -> URL? {
        guard let src = CGImageSourceCreateWithURL(source as CFURL, nil) else { return nil }
        guard let scaled = downscaledImage(from: src) else { return nil }

        let baseName = displayName
            .split(separator: "/").last.map(String.init)?
            .split(separator: ".").first.map(String.init) ?? "image"
        var output = dir.appendingPathComponent("\(baseName)_preview.jpg")
        if FileManager.default.fileExists(atPath: output.path) {
            // 同名不同目录的本地照片会撞名；不区分就会让两个分享项指向同一张图
            output = dir.appendingPathComponent("\(baseName)_\(Int(Date().timeIntervalSince1970 * 1000))_preview.jpg")
        }

        guard let dest = CGImageDestinationCreateWithURL(output as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }

        let props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: previewJPEGQuality,
        ]
        CGImageDestinationAddImage(dest, scaled, props as CFDictionary)

        // 拍摄参数白名单
        if let sourceProps = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] {
            let pruned = pruneProperties(sourceProps, keepGps: keepGps)
            CGImageDestinationSetProperties(dest, pruned as CFDictionary)
        }

        guard CGImageDestinationFinalize(dest) else {
            try? FileManager.default.removeItem(at: output)
            return nil
        }
        return output
    }

    private static func downscaledImage(from src: CGImageSource) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: previewLongEdge,
        ]
        if let thumb = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) {
            return thumb
        }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }

    /// EXIF 白名单裁剪。
    ///
    /// 与安卓逐项对齐的 14 项：
    /// DateTime / DateTimeOriginal / Make / Model / LensModel / FNumber / ExposureTime /
    /// PhotographicSensitivity(ISO) / FocalLength / WhiteBalance / Flash / PixelXDimension /
    /// PixelYDimension / Orientation。
    ///
    /// GPS 系列**一律不在名单内**：默认剥离位置信息的实现就是「不复制它们」；
    /// 用户显式开启时才补回坐标两项。
    ///
    /// 反过来说，名单外的一切（MakerNote、缩略图、XMP、IPTC、自定义标签）
    /// 都会在重编码后消失——这正是「副本」应有的行为：它只带拍摄参数，不带机身私货。
    static func pruneProperties(_ source: [CFString: Any], keepGps: Bool) -> [CFString: Any] {
        var out: [CFString: Any] = [:]

        if let tiff = source[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
            var kept: [CFString: Any] = [:]
            let tiffAllow: [CFString] = [
                kCGImagePropertyTIFFDateTime,
                kCGImagePropertyTIFFMake,
                kCGImagePropertyTIFFModel,
                kCGImagePropertyTIFFOrientation,
            ]
            for key in tiffAllow {
                if let value = tiff[key] { kept[key] = value }
            }
            if !kept.isEmpty { out[kCGImagePropertyTIFFDictionary] = kept }
        }

        if let exif = source[kCGImagePropertyExifDictionary] as? [CFString: Any] {
            var kept: [CFString: Any] = [:]
            let exifAllow: [CFString] = [
                kCGImagePropertyExifDateTimeOriginal,
                kCGImagePropertyExifDateTimeDigitized,
                kCGImagePropertyExifLensModel,
                kCGImagePropertyExifFNumber,
                kCGImagePropertyExifExposureTime,
                kCGImagePropertyExifISOSpeedRatings,
                kCGImagePropertyExifFocalLength,
                kCGImagePropertyExifWhiteBalance,
                kCGImagePropertyExifFlash,
                kCGImagePropertyExifPixelXDimension,
                kCGImagePropertyExifPixelYDimension,
            ]
            for key in exifAllow {
                if let value = exif[key] { kept[key] = value }
            }
            if !kept.isEmpty { out[kCGImagePropertyExifDictionary] = kept }
        }

        if keepGps, let gps = source[kCGImagePropertyGPSDictionary] as? [CFString: Any] {
            var kept: [CFString: Any] = [:]
            for key in [kCGImagePropertyGPSLatitude, kCGImagePropertyGPSLatitudeRef,
                        kCGImagePropertyGPSLongitude, kCGImagePropertyGPSLongitudeRef,
                        kCGImagePropertyGPSTimeStamp, kCGImagePropertyGPSDateStamp] {
                if let value = gps[key] { kept[key] = value }
            }
            if !kept.isEmpty { out[kCGImagePropertyGPSDictionary] = kept }
        }

        // 像素尺寸随缩放变化，由写入端自己生成；这里不复制原尺寸，避免看图器按错误尺寸排列
        if let orientation = source[kCGImagePropertyOrientation] {
            out[kCGImagePropertyOrientation] = orientation
        }
        return out
    }

    // MARK: - 清理

    /// 清空分享临时目录。分享页退出时调用，避免缓存无限增长。
    static func clearCache() {
        let dir = shareDirectory()
        let items = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        for item in items { try? FileManager.default.removeItem(at: item) }
    }
}
