//
//  FileSaver.swift
//  N-Link
//
//  落地保存。安卓用 MediaStore + Download 目录；iOS 对应「照片 App」与
//  「文件 App → 我的 iPhone/N-Link」两条落点（见差异文档 §4.1）。
//
//  关键差异：iOS 保存到照片 App 时，原始字节由 `PHAssetCreationRequest` 接收，
//  系统**不会**重新编码，也不会像安卓 MediaStore 那样需要自己拼路径。所以这里
//  没有「按日期分目录」的逻辑——照片 App 的相簿本身就是按日期聚合的。
//

import Foundation
import Photos
import UniformTypeIdentifiers

struct FileSaver: Sendable {

    /// 保存并返回可展示的本地标识。
    func save(temp: URL, file: CameraFile, location: AppSettings.SaveLocation) async throws -> String {
        switch location {
        case .photos:
            return try await saveToPhotos(temp: temp, file: file)
        case .files:
            return try saveToFiles(temp: temp, file: file)
        }
    }

    func describe(_ error: Error) -> String {
        if let saveError = error as? FileSaveError {
            return saveError.errorDescription ?? "保存失败"
        }
        return "保存失败：\(error.localizedDescription)"
    }

    // MARK: - 照片 App

    private func saveToPhotos(temp: URL, file: CameraFile) async throws -> String {
        let status = await requestPhotoAuthorization()
        guard status == .authorized || status == .limited else {
            throw FileSaveError.noPhotoPermission
        }

        // 容量预检：CocoaErrorDomain 的文件系统错误不好读，提前给一句人话
        if let remaining = try? availableBytes(), remaining < file.size {
            throw FileSaveError.notEnoughSpace(remaining: remaining, needed: file.size)
        }

        let _: String = try await withCheckedThrowingContinuation { continuation in
            PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                // 用 addResource 而不是 addAsset(with:data:)：后者会丢掉原始文件的
                // 扩展名与部分元数据（尤其 NEF 这类系统不认识的原始格式）。
                let options = PHAssetResourceCreationOptions()
                options.originalFilename = file.fileName
                options.shouldMoveFile = false
                request.addResource(with: .photo, fileURL: temp, options: options)
            } completionHandler: { success, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if success {
                    continuation.resume(returning: file.fileName)
                } else {
                    continuation.resume(throwing: FileSaveError.writeInterrupted("相册拒绝了这次写入"))
                }
            }
        }
        return "照片 · \(file.fileName)"
    }

    private func requestPhotoAuthorization() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        guard current == .notDetermined else { return current }
        return await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                continuation.resume(returning: status)
            }
        }
    }

    // MARK: - 文件 App

    private func saveToFiles(temp: URL, file: CameraFile) throws -> String {
        let docs = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let folder = docs.appendingPathComponent("N-Link", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

        let destination = uniqueURL(in: folder, fileName: file.fileName)
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.copyItem(at: temp, to: destination)
        return "文件 · \(destination.lastPathComponent)"
    }

    /// 重名时追加 `-2`、`-3`……而不是覆盖。相机卡上的 DSC_0001.NEF 在不同存储卡间会重名。
    private func uniqueURL(in folder: URL, fileName: String) -> URL {
        let base = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        var candidate = folder.appendingPathComponent(fileName)
        var counter = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(base)-\(counter)" : "\(base)-\(counter).\(ext)"
            candidate = folder.appendingPathComponent(name)
            counter += 1
        }
        return candidate
    }

    private func availableBytes() throws -> Int64 {
        let values = try URL(fileURLWithPath: NSHomeDirectory()).resourceValues(
            forKeys: [.volumeAvailableCapacityForImportantUsageKey]
        )
        return values.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }
}
