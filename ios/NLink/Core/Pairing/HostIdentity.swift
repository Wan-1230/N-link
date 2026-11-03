//
//  HostIdentity.swift
//  NLink
//
//  主机身份持久化 + PC 主机注册（配对）。
//  对应安卓 `com/camerakit/pairing/HostIdentityStore.kt` 与 `HostRegistrar.kt`。
//
//  为什么 GUID 必须持久：STA 模式与主机注册用**同一个** GUID，相机据此识别
//  「这台手机以前连过」，注册一次之后不再弹确认。而 AP 模式恰恰相反——每次
//  握手都要新 GUID，见 CameraConnectionClient.handshakeGuid()。
//

import Foundation
import Security

// 说明：`HostIdentity` 的值类型定义在 PtpModels.swift（与 CameraState 等共享）。
// 本文件只扩展它的持久化与「一次性随机 GUID」两种构造，外加 HostRegistrar。

extension HostIdentity {

    /// 读取或创建持久身份。GUID 由两个 64 位随机数拼成 16 字节，
    /// 与安卓实现同构（UUID.mostSignificantBits + leastSignificantBits）。
    static func loadOrCreate(hostName: String = HostIdentity.defaultHostName) -> HostIdentity {
        let store = UserDefaults.standard
        let keyGuid = "camerakit_host_guid_hex"
        let keyName = "camerakit_host_name"

        var name = store.string(forKey: keyName) ?? hostName
        if name.trimmingCharacters(in: .whitespaces).isEmpty { name = hostName }
        if store.string(forKey: keyName) == nil { store.set(name, forKey: keyName) }

        if let hex = store.string(forKey: keyGuid), let data = Data(hexString: hex), data.count == 16 {
            return HostIdentity(guid: data, hostName: name)
        }
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let guid = Data(bytes)
        store.set(guid.hexString, forKey: keyGuid)
        return HostIdentity(guid: guid, hostName: name)
    }

    /// 一次性随机 GUID（AP 握手用）。
    static func randomGuid() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    static func newRandom(hostName: String? = nil) -> HostIdentity {
        HostIdentity(
            guid: randomGuid(),
            hostName: hostName ?? (UserDefaults.standard.string(forKey: "camerakit_host_name") ?? defaultHostName)
        )
    }
}

/// PC 主机注册（Nikon 私有 0x952B 预备 / 0x935A 确认）。
enum HostRegistrar {

    /// 相机侧确认等待时长。8.5s 是安卓端实测值：太短会把「用户在机身上
    /// 还没来得及按确定」判成配对失败，太长又让失败不可感知。
    ///
    /// 用 `Int` 而不是 `UInt64`：`Clock.sleep(ms:)` 收的是 `Int`，
    /// 这两个值全程只作「等多久」用，没有位宽意义，没必要多套一层 UInt64。
    static let confirmWaitMs: Int = 8_500
    static let maxRetries = 3
    static let retryIntervalMs: Int = 1_500

    /// 完整注册流程。
    ///
    /// - Parameters:
    ///   - session: 已打开的 PTP 会话（必须是独立注册会话，不能与在用会话共用）
    ///   - onAwaitConfirm: 把相机返回的展示字符串交给 UI（「请在相机上确认」）
    /// - Returns: 相机返回的预备字符串（通常是机身序列号或 `密钥：xxxx`）
    static func register(
        session: PtpSession,
        confirmWaitMs: Int = confirmWaitMs,
        retries: Int = maxRetries,
        onAwaitConfirm: (@Sendable (String) async -> Void)? = nil
    ) async throws -> String {
        let prepare = try await session.hostRegisterPrepare()
        await onAwaitConfirm?(prepare)

        // 相机要先把确认界面推给用户，这个延迟不是「等相机响应」，
        // 是「等人按下机身按钮」，所以不能改成轮询。
        // Clock.sleep 会因 Task 取消而抛出 —— 取消注册时直接让它冒泡，
        // 由调用方按「用户取消」处理，而不是继续往下发 confirm 命令。
        try await Clock.sleep(ms: confirmWaitMs)

        var lastError: (any Error)?
        for attempt in 0..<max(1, retries) {
            do {
                try await session.hostRegisterConfirm()
                return prepare
            } catch {
                lastError = error
                if attempt < retries - 1 {
                    try? await Clock.sleep(ms: retryIntervalMs)
                }
            }
        }
        let detail = (lastError as? CameraError)?.message ?? lastError?.localizedDescription ?? "无响应"
        throw CameraError.pairingRejected(detail: "相机未确认主机注册：\(detail)")
    }
}

// MARK: - 十六进制工具

extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(hexString: String) {
        let cleaned = hexString.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard cleaned.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(cleaned.count / 2)
        var index = cleaned.startIndex
        while index < cleaned.endIndex {
            let next = cleaned.index(index, offsetBy: 2)
            guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = Data(bytes)
    }
}
