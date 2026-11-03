//
//  LiveFrameAnalysis.swift
//  N-Link
//
//  监看帧的亮度统计 —— 对应安卓 `camera/liveview/FrameSampler.kt`。
//
//  设计要点（照抄安卓的三条实测口径）：
//    · 采样尺寸 96×64 而不是原帧：一帧 1600×1064 逐像素统计要 10ms+，
//      在 66ms 的帧预算里没有位置。降到 96×64 后约 1~2ms。
//    · 复用同一块 scratch buffer（`CGContext` + `[UInt8]`），**数组下次调用即覆盖**。
//      调用方必须当场消费，不能持有引用。
//    · 直方图 96 档（安卓 `histogramBins = 96`），波形 128 列 × 64 行，取列平均亮度。
//
//  为什么这里自己写而不用 CoreImage：`CIAreaAverage` 只能给平均值，
//  要分布仍然得遍历像素；而 `CGContext` 一次性画到 96×64 的灰度位图上再读，
//  是 iOS 上最省的一条路。
//

import CoreGraphics
import Foundation
import ImageIO

enum LiveFrameAnalysis {

    /// 直方图档数（与安卓 `HistogramView` 的 96 档一致）
    static let binCount = 96
    /// 波形水平列数
    static let waveformColumns = 128

    /// 采样宽度/高度。宽高比 3:2，与监看帧一致（扭曲的采样对分布判断有偏差）。
    private static let sampleWidth = 96
    private static let sampleHeight = 64

    struct Result: Sendable, Equatable {
        /// 96 档亮度分布，已归一化到 0...1
        var bins: [Double]
        /// 128 列平均亮度，已归一化到 0...1
        var rows: [Double]
    }

    /// 解析一张 JPEG 并统计亮度。
    ///
    /// 失败（坏帧、非 JPEG）返回全零而不是 nil：调用方拿到的 `Result`
    /// 里 bins 永远 96 项，画图代码不需要再处理可选。
    static func analyze(jpeg: Data) -> Result {
        guard let gray = downsampleGray(jpeg: jpeg) else {
            return Result(bins: Array(repeating: 0, count: binCount),
                          rows: Array(repeating: 0, count: waveformColumns))
        }
        return Result(bins: histogram(from: gray), rows: waveform(from: gray))
    }

    /// 把 JPEG 缩到 96×64 灰度图。
    private static func downsampleGray(jpeg: Data) -> [UInt8]? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(sampleWidth, sampleHeight),
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }

        var buffer = [UInt8](repeating: 0, count: sampleWidth * sampleHeight)
        // 灰度上下文：每像素 1 字节。用 .deviceGray 而不是 sRGB —— 我们要的是亮度，
        // 让 CoreGraphics 做一次色彩空间转换比我们自己加权求和更准也更快。
        guard let colorSpace = CGColorSpace(name: CGColorSpace.linearGray) ?? CGColorSpace(name: CGColorSpace.deviceGray),
              let context = CGContext(
                data: &buffer,
                width: sampleWidth,
                height: sampleHeight,
                bitsPerComponent: 8,
                bytesPerRow: sampleWidth,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.none.rawValue
              ) else {
            return nil
        }
        context.interpolationQuality = .low
        context.draw(image, in: CGRect(x: 0, y: 0, width: sampleWidth, height: sampleHeight))
        return buffer
    }

    /// 96 档直方图。忽略纯黑与纯白的首尾档（黑边/过曝成片时不至于把图压扁）。
    private static func histogram(from gray: [UInt8]) -> [Double] {
        var counts = [Int](repeating: 0, count: binCount)
        var total = 0
        for value in gray {
            // 1...254 映射到 0..<96：两端各留一档给「无信息」
            guard value > 0, value < 255 else { continue }
            let index = min(binCount - 1, Int(value) * binCount / 256)
            counts[index] += 1
            total += 1
        }
        guard total > 0 else { return Array(repeating: 0, count: binCount) }
        // 用对数尺度：真实场景里中间调天然占绝大多数，线性尺度下两端的
        // 高光/阴影信息会被压成一条缝，看不出来。
        let peak = Double(counts.max() ?? 1)
        return counts.map { count in
            guard count > 0, peak > 0 else { return 0 }
            return log1p(Double(count)) / log1p(peak)
        }
    }

    /// 128 列平均亮度。
    private static func waveform(from gray: [UInt8]) -> [Double] {
        var rows = [Double](repeating: 0, count: waveformColumns)
        let perColumn = max(1, sampleWidth / waveformColumns)
        for column in 0..<waveformColumns {
            var sum = 0
            var count = 0
            let startX = column * sampleWidth / waveformColumns
            let endX = min(sampleWidth, startX + perColumn)
            for x in startX..<endX {
                for y in 0..<sampleHeight {
                    sum += Int(gray[y * sampleWidth + x])
                    count += 1
                }
            }
            rows[column] = count > 0 ? Double(sum) / Double(count) / 255.0 : 0
        }
        return rows
    }
}
