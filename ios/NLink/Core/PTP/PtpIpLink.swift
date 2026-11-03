//
//  PtpIpLink.swift
//  NLink
//
//  PTP/IP 链路（Wi-Fi AP / STA 共用）。对应安卓 `com/camerakit/ptpip/PtpIpLink.kt`。
//
//  握手序（逆向实现静态分析 + gphoto ptpip 文档互证 + 本项目真机日志）：
//   1. 命令 socket 连接 host:15740，发 InitCommand = GUID(16) + UTF-16LE 主机名 + 版本 0x00010000；
//   2. 收 InitCommandAck = ConnectionID + 相机 GUID + 相机名；type5 = InitFail（相机拒绝）；
//   3. **等 650ms**（见下）；
//   4. 事件 socket 再连同一端口，**绑定到命令通道同一本地地址**，发 InitEvent(ConnectionID)，
//      收 InitEventAck(type4)；
//   5. **立刻在事件通道发 PING**（缺这一步相机会在 ~4s 后判失败并断开）；
//   6. 起事件读取循环。
//
//  三个「不这么写就坏」的点，全部来自安卓端真机日志：
//   · 650ms 稳定间隔：成功点全部落在 settle ≥650ms 的轮次，0ms 首试从未成功
//     （事件通道建链后 6~12ms 即被相机复位）。
//   · 事件通道绑本地地址：双网手机（Wi-Fi + 蜂窝）上事件 socket 会漂到另一张网卡，
//     相机判为非法连接直接复位。
//   · InitEventAck 后立刻 PING：不发则 ~4s 后被断，且后续连接会被严拒。
//

import Foundation

actor PtpIpLink: PtpLink {

    private enum Timeout {
        static let connect: Int32 = 5_000
        static let handshake: TimeInterval = 15
        static let eventPing: TimeInterval = 1.5
        static let keepaliveDrain: TimeInterval = 3
    }

    // MARK: - 对外状态

    nonisolated let events: AsyncStream<PtpCameraEvent>
    private let eventContinuation: AsyncStream<PtpCameraEvent>.Continuation

    private let endpoint: CameraEndpoint
    private let hostGuid: Data
    private let hostName: String
    private let eventSettleMs: Int
    private let log: @Sendable (String) -> Void
    private let onTransportLost: @Sendable (String) -> Void

    private var cmdSocket: TcpSocket?
    private var evtSocket: TcpSocket?

    private var nextTransId: Int32 = 1
    private var lastRx: TimeInterval = 0
    private var lostNotified = false
    private var eventTask: Task<Void, Never>?

    private let openFlag = OpenFlag()

    private(set) var connectionId: Int32 = -1
    private(set) var cameraName: String = ""

    /// `nonisolated`：协议要求同步可读。openFlag 内部用 NSLock，跨隔离域安全。
    nonisolated var isOpen: Bool { openFlag.value }
    nonisolated func lastReceiveAt() -> TimeInterval { lastRxSnapshot.value }

    private let lastRxSnapshot = TimeIntervalBox()

    init(
        endpoint: CameraEndpoint,
        hostGuid: Data,
        hostName: String,
        eventSettleMs: Int = 650,
        log: @escaping @Sendable (String) -> Void = { _ in },
        onTransportLost: @escaping @Sendable (String) -> Void = { _ in }
    ) {
        self.endpoint = endpoint
        self.hostGuid = hostGuid
        self.hostName = hostName
        self.eventSettleMs = eventSettleMs
        self.log = log
        self.onTransportLost = onTransportLost

        var cont: AsyncStream<PtpCameraEvent>.Continuation!
        self.events = AsyncStream(bufferingPolicy: .bufferingNewest(64)) { cont = $0 }
        self.eventContinuation = cont
    }

    // MARK: - 建链

    func open() async throws {
        let deadline = Clock.now() + Timeout.handshake
        do {
            let cmd = try TcpSocket.connect(
                host: endpoint.host,
                port: endpoint.port,
                connectTimeoutMs: Timeout.connect,
                label: "ptpip-cmd"
            )
            cmdSocket = cmd
            log("PTP/IP 命令通道已连接 \(cmd.localAddress ?? "-") -> \(endpoint.host):\(endpoint.port)")

            try await send(cmd, type: Ptp.ipInitCommand,
                           payload: try PtpIpCodec.initCommandPayload(guid: hostGuid, hostName: hostName))

            let ack = try await awaitPacket(cmd, deadline: deadline)
            switch ack.type {
            case Ptp.ipInitFail:
                throw CameraError.cameraRejected(code: try PtpIpCodec.parseInitFail(ack.payload))
            case Ptp.ipInitCommandAck:
                let parsed = try PtpIpCodec.parseInitCommandAck(ack.payload)
                connectionId = parsed.connectionId
                cameraName = parsed.cameraName.isEmpty ? endpoint.displayName : parsed.cameraName
                log("PTP/IP init-command-ack connection=\(connectionId) camera=\(cameraName)")
            default:
                throw CameraError.protocolViolation(
                    detail: "初始化响应无效（type=\(ack.type)）", causeDescription: nil
                )
            }

            // 稳定间隔：所有轮次统一。真机统计里成功的每一轮都 ≥650ms。
            if eventSettleMs > 0 {
                log("PTP/IP 事件通道稳定等待 \(eventSettleMs)ms")
                try await Clock.sleep(ms: eventSettleMs)
            }

            let evt = try TcpSocket.connect(
                host: endpoint.host,
                port: endpoint.port,
                connectTimeoutMs: Timeout.connect,
                bindLocal: cmd.localAddress,
                label: "ptpip-evt"
            )
            evtSocket = evt
            log("PTP/IP 事件通道已连接 local=\(evt.localAddress ?? "-")")

            try await send(evt, type: Ptp.ipInitEvent,
                           payload: PtpIpCodec.initEventPayload(connectionId: connectionId))
            let ev = try await awaitPacket(evt, deadline: deadline)
            guard ev.type == Ptp.ipInitEventAck else {
                throw CameraError.protocolViolation(
                    detail: "相机没有接受 PTP/IP 事件通道（type=\(ev.type)）", causeDescription: nil
                )
            }
            log("PTP/IP init-event-ack")

            // 必须发：缺这一握手的相机会在数秒后判定连接失败并断开。
            // Pong 不强制——发出去即满足相机侧握手预期，回不回都继续（旧栈同款容错）。
            do {
                try await send(evt, type: Ptp.ipPing, payload: Data())
                if let pong = try await readPacketOrNull(evt) {
                    log(pong.type == Ptp.ipPong
                        ? "PTP/IP 事件通道 PING→PONG ✓"
                        : "PTP/IP 事件通道 PING 已发，Pong 未回（got=\(pong.type)）——按旧栈容错继续")
                } else {
                    log("PTP/IP 事件通道 PING 已发，Pong 未回（空闲超时）——按旧栈容错继续")
                }
            } catch {
                log("PTP/IP 事件通道 PING 异常（容错继续）：\(error)")
            }

            openFlag.value = true
            lastRx = Clock.now()
            lastRxSnapshot.value = lastRx
            startEventLoop(evt)
        } catch {
            await closeSockets()
            if let camErr = error as? CameraError { throw camErr }
            throw CameraError.transportLost("PTP/IP 握手失败：\(error)", error)
        }
    }

    // MARK: - 事务

    func transact(
        opcode: UInt16,
        params: [UInt32],
        dataOut: Data?,
        timeoutMs: TimeInterval,
        sink: (@Sendable (Data) -> Void)?
    ) async throws -> PtpResult {
        guard openFlag.value, let cmd = cmdSocket else {
            throw CameraError.notConnected("PTP/IP 链路未打开")
        }
        let transId = nextTransId
        nextTransId &+= 1
        let deadline = Clock.now() + timeoutMs

        // dataPhaseInfo：无数据相位=1、有数据相位=2。
        // 恒写 1 会让相机忽略数据相位 —— SetDevicePropValue 等写入类命令会全部失效。
        try await send(cmd, type: Ptp.ipCmdRequest, payload: PtpIpCodec.cmdRequestPayload(
            dataOut: dataOut == nil ? 1 : 2,
            opcode: opcode,
            transId: transId,
            params: params
        ))
        if let dataOut {
            // data-out 序列：StartData(总长) → EndData(完整载荷)，不发中间 Data 包。
            try await send(cmd, type: Ptp.ipStartData,
                           payload: PtpIpCodec.startDataPayload(transId: transId, totalLength: UInt64(dataOut.count)))
            try await send(cmd, type: Ptp.ipEndData,
                           payload: PtpIpCodec.dataPayload(transId: transId, data: dataOut))
        }

        var chunks: [Data] = []
        var total = 0
        var dataExpected: UInt64?
        var dataEnded = false

        while true {
            let pkt = try await readPacketOrNull(cmd)
            if pkt == nil {
                if Clock.now() > deadline {
                    await sendCancel(cmd, transId: transId)
                    throw CameraError.timeout(
                        "PTP/IP 读取超时（opcode=0x\(String(opcode, radix: 16, uppercase: false))，tx=\(transId)）"
                    )
                }
                continue
            }
            guard let pkt else { continue }

            switch pkt.type {
            case Ptp.ipCmdResponse:
                let resp = try PtpIpCodec.parseCmdResponse(pkt.payload)
                guard resp.transId == transId else {
                    // 超时取消后相机可能补发迟到响应；事务号不符一律忽略
                    log("PTP/IP 事务编号不匹配 expect=\(transId) got=\(resp.transId)，忽略该响应")
                    continue
                }
                // 完整性校验：响应 OK 但数据相位不完整 → 直接判失败。
                // 这是监看黑屏、相册坏图的源头防御，不能让截断数据冒充成功。
                if resp.code == Ptp.rcOk, let expect = dataExpected, (!dataEnded || total != Int(expect)) {
                    throw CameraError.protocolViolation(
                        detail: "数据不完整：\(total) / \(expect) 字节（opcode=0x\(String(opcode, radix: 16))）",
                        causeDescription: nil
                    )
                }
                let aggregated: Data?
                if sink == nil, !chunks.isEmpty {
                    var out = Data(capacity: total)
                    for c in chunks { out.append(c) }
                    aggregated = out
                } else {
                    aggregated = nil
                }
                return PtpResult(code: resp.code, params: resp.params, dataIn: aggregated)

            case Ptp.ipStartData:
                let t = try PtpIpCodec.payloadTransId(pkt.payload)
                guard t == transId else {
                    throw CameraError.protocolViolation(
                        detail: "数据事务编号不匹配 expect=\(transId) got=\(t)", causeDescription: nil
                    )
                }
                // 载荷 = transId + 总长，**不是数据**
                dataExpected = try PtpIpCodec.payloadStartDataLen(pkt.payload)
                log("PTP/IP StartData 总长=\(dataExpected ?? 0)")

            case Ptp.ipData, Ptp.ipEndData:
                let t = try PtpIpCodec.payloadTransId(pkt.payload)
                guard t == transId else {
                    throw CameraError.protocolViolation(
                        detail: "数据事务编号不匹配 expect=\(transId) got=\(t)", causeDescription: nil
                    )
                }
                let data = PtpIpCodec.payloadData(pkt.payload)
                if !data.isEmpty {
                    total += data.count
                    if let sink {
                        sink(data)
                    } else {
                        guard total <= Ptp.maxPacketBytes else {
                            throw CameraError.protocolViolation(
                                detail: "data-in 超过聚合上限，请改用流式读取", causeDescription: nil
                            )
                        }
                        chunks.append(data)
                    }
                }
                if pkt.type == Ptp.ipEndData { dataEnded = true }

            case Ptp.ipEvent:
                let (code, params) = try PtpIpCodec.parseEvent(pkt.payload)
                eventContinuation.yield(PtpCameraEvent(code: code, params: params))

            case Ptp.ipPing:
                try await send(cmd, type: Ptp.ipPong, payload: Data())

            case Ptp.ipPong:
                break      // 保活响应，lastRx 已更新

            case Ptp.ipCancel:
                let t = try? PtpIpCodec.payloadTransId(pkt.payload)
                log("相机取消事务 tx=\(t.map(String.init) ?? "-")")

            default:
                log("PTP/IP 未知包类型 \(pkt.type)，忽略")
            }
        }
    }

    // MARK: - 保活

    func sendKeepalive() async {
        guard openFlag.value, let cmd = cmdSocket else { return }
        do {
            try await send(cmd, type: Ptp.ipPing, payload: Data())
        } catch {
            await transportLost("keepalive ping 失败：\(error)")
            return
        }
        // 短窗排空命令通道：消费 Pong/Event（Pong 只能在这里读到），刷新 lastRx
        let deadline = Clock.now() + Timeout.keepaliveDrain
        while openFlag.value, Clock.now() < deadline {
            do {
                guard let pkt = try await readPacketOrNull(cmd) else { break }
                switch pkt.type {
                case Ptp.ipPong:
                    log("keepalive pong ok")
                case Ptp.ipEvent:
                    let (code, params) = try PtpIpCodec.parseEvent(pkt.payload)
                    eventContinuation.yield(PtpCameraEvent(code: code, params: params))
                case Ptp.ipPing:
                    try await send(cmd, type: Ptp.ipPong, payload: Data())
                default:
                    log("keepalive 排空忽略 type=\(pkt.type)")
                }
            } catch {
                log("keepalive 排空异常：\(error)")
                break
            }
        }
    }

    // MARK: - 关闭

    func close(graceful: Bool) async {
        let wasOpen = openFlag.value
        openFlag.value = false
        await closeSockets()
        if !graceful, wasOpen, !lostNotified {
            lostNotified = true
            onTransportLost("PTP/IP connection closed")
        }
        eventContinuation.finish()
    }

    private func closeSockets() async {
        eventTask?.cancel()
        eventTask = nil
        let cmd = cmdSocket
        let evt = evtSocket
        cmdSocket = nil
        evtSocket = nil
        // socket 的 close 不是阻塞调用，但仍投到各自队列上执行以避开与读循环的竞态
        await cmd?.runVoid { cmd?.close() }
        await evt?.runVoid { evt?.close() }
        openFlag.value = false
    }

    // MARK: - 事件循环

    private func startEventLoop(_ socket: TcpSocket) {
        eventTask = Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            while await self.isOpen {
                do {
                    guard let pkt = try await self.readPacketOrNull(socket) else { continue }
                    switch pkt.type {
                    case Ptp.ipEvent:
                        let (code, params) = try PtpIpCodec.parseEvent(pkt.payload)
                        await self.emit(PtpCameraEvent(code: code, params: params))
                    case Ptp.ipPing:
                        try await self.send(socket, type: Ptp.ipPong, payload: Data())
                    case Ptp.ipInitEventAck:
                        break      // 重复 ack，忽略
                    default:
                        await self.logLine("PTP/IP 事件通道未知包类型 \(pkt.type)")
                    }
                } catch {
                    if await self.isOpen {
                        await self.transportLost("PTP/IP 事件通道断开：\(error)")
                    }
                    return
                }
            }
        }
    }

    private func emit(_ event: PtpCameraEvent) { eventContinuation.yield(event) }
    private func logLine(_ line: String) { log(line) }

    // MARK: - 底层 I/O

    private func send(_ socket: TcpSocket, type: UInt32, payload: Data) async throws {
        let frame = PtpIpCodec.packet(type: type, payload: payload)
        do {
            try await socket.runVoid { try socket.write(frame) }
        } catch {
            await transportLost("PTP/IP socket failure：\(error)")
            throw CameraError.transportLost("PTP/IP socket failure：\(error)", error)
        }
    }

    private func sendCancel(_ socket: TcpSocket, transId: Int32) async {
        let frame = PtpIpCodec.packet(type: Ptp.ipCancel, payload: PtpIpCodec.cancelPayload(transId: transId))
        try? await socket.runVoid { try socket.write(frame) }
    }

    /// 读一个包；只在「包边界」（读头前）允许空闲超时返回 nil。
    /// 载荷读到一半超时会在 socket 层内部继续等，保持流同步。
    private func readPacketOrNull(_ socket: TcpSocket) async throws -> PtpIpCodec.Packet? {
        let packet: PtpIpCodec.Packet?
        do {
            packet = try await socket.run { () -> PtpIpCodec.Packet? in
                guard let header = try socket.readExactly(PtpIpCodec.headerSize, idleOk: true) else { return nil }
                let (length, type) = try PtpIpCodec.packetLength(header: header)
                guard let payload = try socket.readExactly(length - PtpIpCodec.headerSize, idleOk: false) else {
                    throw CameraError.protocolViolation(
                        detail: "载荷读取意外中断", causeDescription: nil
                    )
                }
                return PtpIpCodec.Packet(type: type, payload: payload)
            }
        } catch let codec as PtpIpCodec.CodecError {
            throw CameraError.protocolViolation(detail: codec.description, causeDescription: nil)
        } catch let socketError as SocketError {
            await transportLost("PTP/IP 读取失败：\(socketError)")
            throw CameraError.transportLost("PTP/IP 读取失败：\(socketError)", socketError)
        }
        if packet != nil {
            lastRx = Clock.now()
            lastRxSnapshot.value = lastRx
        }
        return packet
    }

    private func awaitPacket(_ socket: TcpSocket, deadline: TimeInterval) async throws -> PtpIpCodec.Packet {
        while true {
            if let pkt = try await readPacketOrNull(socket) { return pkt }
            if Clock.now() > deadline {
                throw CameraError.timeout("PTP/IP 握手超时")
            }
        }
    }

    private func transportLost(_ detail: String) async {
        guard !lostNotified else { return }
        lostNotified = true
        openFlag.value = false
        onTransportLost(detail)
        await closeSockets()
    }
}

// MARK: - 非隔离的小状态容器

/// 跨隔离域读取的布尔量（只有「开/关」两种值，读写原子性由锁保证）。
private final class OpenFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var storage = false

    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return storage }
        set { lock.lock(); defer { lock.unlock() }; storage = newValue }
    }
}

private final class TimeIntervalBox: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: TimeInterval = 0

    var value: TimeInterval {
        get { lock.lock(); defer { lock.unlock() }; return storage }
        set { lock.lock(); defer { lock.unlock() }; storage = newValue }
    }
}
