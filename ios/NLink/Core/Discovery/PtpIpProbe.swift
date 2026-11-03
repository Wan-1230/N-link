//
//  PtpIpProbe.swift
//  NLink
//
//  轻量 PTP/IP Init 探测（对应安卓 `com/nikonlink/app/device/ptp/PtpIpProbe.kt`）。
//
//  为什么需要「分级」而不是布尔值：安卓端 v1.3.2 的教训——
//  原来只有 true/false，于是「TCP 连上了但相机休眠没回 PTP 握手」与「这个 IP
//  根本没人」被当成同一件事，整段扫描把它们一起丢掉，这是 STA 搜不到相机的一个
//  直接原因。现在两者分开：前者大概率就是相机，只是需要唤醒。
//
//  另一条：**网段盲扫阶段只做 TCP 连接，不发 InitCommand**。尼康机身同时只允许
//  一个 PTP/IP 客户端，盲扫握手会挤占/污染配对槽，反而让真正的目标连不上。
//

import Foundation

enum PtpIpProbe {

    /// 探测结论。
    enum Result: String, Sendable {
        /// TCP 连通且 PTP/IP Init 握手成功 —— 确定是可用相机
        case ok
        /// TCP 端口开放但没等到 PTP 握手应答（相机休眠/忙/非 PTP 服务占用该端口）
        case tcpOpenNoPtp
        /// 端口明确拒绝（有主机但没开 15740）
        case refused
        /// 连接超时 / 不可达（无主机或不在本网段）
        case timeout
        /// 路由不可达 —— 手机压根不在相机那个网段上
        case noRoute
        /// 其它异常
        case error

        /// 是否值得作为候选展示给用户
        var isCandidate: Bool { self == .ok || self == .tcpOpenNoPtp }
    }

    static let minTimeoutMs = 300
    static let maxTimeoutMs = 6_000

    // MARK: - 完整探测（TCP + PTP/IP Init 握手）

    /// 带结论的探测。
    ///
    /// 超时上限 6s：尼康机身从 Wi-Fi 休眠唤醒并完成 PTP 握手可能耗时数秒，
    /// 旧的 1.5s 上限导致「相机明明在同一网段却永远探不到」。
    /// 网段盲扫请显式传短超时；只有**确认候选**时才用长超时。
    static func probeDetailed(
        host: String,
        port: Int = Ptp.defaultPtpIpPort,
        timeoutMs: Int = 1_200,
        localAddress: String? = nil,
        clientName: String = "N-LinkProbe"
    ) async -> Result {
        // 非 IPv4 / 特殊用途地址（含带 scope id 的 IPv6 链路本地）不探
        guard WifiEndpoint.isProbeCandidate(host) else { return .error }
        let effective = min(max(timeoutMs, minTimeoutMs), maxTimeoutMs)

        do {
            let socket = try TcpSocket.connect(
                host: host,
                port: port,
                connectTimeoutMs: Int32(effective),
                bindLocal: localAddress,
                label: "probe"
            )
            defer { socket.close() }
            let payload = try PtpIpCodec.initCommandPayload(guid: randomGuid(), hostName: clientName)
            try socket.write(PtpIpCodec.packet(type: Ptp.ipInitCommand, payload: payload))
            // 握手阶段超时 = 「端口开放但没回应」，不是「不可达」
            //
            // `try?` 与 `let header` 的二段解包：`readExactly` 返回 `Data?`，
            // `try?` 又把它包成一层可选 → 需要先解 `try?`，再解内层可选。
            guard let headerData = try? socket.readExactly(PtpIpCodec.headerSize, idleOk: false),
                  let header = headerData,
                  let (length, type) = try? PtpIpCodec.packetLength(header: header),
                  type == Ptp.ipInitCommandAck || type == Ptp.ipInitFail else {
                return .tcpOpenNoPtp
            }
            // 读掉载荷保持礼貌（对端要发完才关）
            _ = try? socket.readExactly(length - PtpIpCodec.headerSize, idleOk: false)
            return .ok
        } catch let error as CameraError {
            switch error {
            case .timeout: return .timeout
            case .transportLost: return .timeout
            default: return .error
            }
        } catch let error as SocketError {
            return grade(error.code)
        } catch {
            return .error
        }
    }

    // MARK: - 只做 TCP 连接（扫描阶段用）

    /// 只做 TCP 连接，**不发 PTP/IP 握手**。
    ///
    /// 网段盲扫期间对每个地址发 InitCommand 有两个代价：① 每个候选多花一个握手超时；
    /// ② 挤占相机的唯一 PTP/IP 客户端槽，反而让真正的目标连不上。
    static func tcpScreen(
        host: String,
        port: Int = Ptp.defaultPtpIpPort,
        timeoutMs: Int = 500,
        localAddress: String? = nil
    ) async -> (open: Bool, grade: Result, errno: String?) {
        guard WifiEndpoint.isProbeCandidate(host) else {
            return (false, .error, "not_probe_candidate")
        }
        let effective = min(max(timeoutMs, minTimeoutMs), maxTimeoutMs)
        do {
            let socket = try TcpSocket.connect(
                host: host,
                port: port,
                connectTimeoutMs: Int32(effective),
                bindLocal: localAddress,
                label: "screen"
            )
            socket.close()
            return (true, .ok, nil)
        } catch let error as SocketError {
            return (false, grade(error.code), "errno \(error.code)")
        } catch {
            return (false, .timeout, String(describing: error))
        }
    }

    /// errno → 档位。
    ///
    /// NO_ROUTE 与 TIMEOUT 的区分有实际处置意义：TIMEOUT 是「包发出去了没人回」
    /// （相机休眠、被 AP 隔离、黑洞路由），NO_ROUTE 是「本机根本没有通往这个地址
    /// 的路」——后者重试一万次也不会好，该提示的是「换网/重新扫描」而不是
    /// 「唤醒相机」。
    static func grade(_ code: Int32) -> Result {
        switch code {
        case ECONNREFUSED:
            return .refused
        case ENETUNREACH, EHOSTUNREACH, EHOSTDOWN:
            return .noRoute
        case ETIMEDOUT:
            return .timeout
        default:
            return .error
        }
    }

    private static func randomGuid() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }
}
