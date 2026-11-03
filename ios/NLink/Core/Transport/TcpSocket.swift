//
//  TcpSocket.swift
//  NLink
//
//  最小 TCP 客户端封装（POSIX socket）。
//
//  为什么不用 `NWConnection`：
//  安卓端 `PtpIpLink` 的行为是真机实证调出来的，其中三条依赖底层 socket 的精确语义：
//   1. **接收窗口必须在 connect 之前设**（`SO_RCVBUF` 要在 SYN 里协商才生效），
//      Network.framework 没有「连接前设缓冲」的入口，只能连上后改，等于无效；
//   2. **事件通道要 bind 到命令通道的同一个本地地址**，防止双网手机漂到其它网卡；
//   3. 读取超时必须是「1 秒空闲轮询」粒度，且要能区分「包边界空闲」（可容忍）
//      与「载荷读到一半超时」（必须继续等，否则流会失步）。
//  POSIX socket 三者都能一一对应，所以这里直接用 BSD API，不加抽象层。
//
//  线程模型：每个 socket 配一条串行队列做阻塞 I/O；`async` 方法只是把阻塞调用
//  投递到那条队列。串行队列本身就是互斥量——这比在 Swift 并发里加锁更贴近原实现。
//

import Foundation

/// POSIX socket 错误（带 errno，便于日志定位）。
struct SocketError: Error, CustomStringConvertible {
    let op: String
    let code: Int32

    var description: String { "\(op) 失败（errno=\(code)：\(String(cString: strerror(code)))）" }
}

final class TcpSocket: @unchecked Sendable {

    /// 读超时（对应 Android `SO_TIMEOUT_MS = 1000`）
    static let readTimeoutMs: Int32 = 1_000

    private var fd: Int32
    private let queue: DispatchQueue
    private(set) var localAddress: String?

    private init(fd: Int32, queue: DispatchQueue, localAddress: String?) {
        self.fd = fd
        self.queue = queue
        self.localAddress = localAddress
    }

    // MARK: - 建链

    /// 建立 TCP 连接。
    ///
    /// - Parameters:
    ///   - host: 相机地址
    ///   - port: 默认 15740
    ///   - connectTimeoutMs: 5000
    ///   - receiveBuffer: 4MB —— 大文件连续数据相位不被小窗口掐成一段段等 ACK
    ///   - sendBuffer: 1MB
    ///   - bindLocal: 事件通道要绑到命令通道的本地地址；nil 表示不绑定
    static func connect(
        host: String,
        port: Int,
        connectTimeoutMs: Int32 = 5_000,
        receiveBuffer: Int = 4 * 1024 * 1024,
        sendBuffer: Int = 1 * 1024 * 1024,
        bindLocal: String? = nil,
        bindLocalPort: UInt16 = 0,
        label: String
    ) throws -> TcpSocket {
        let queue = DispatchQueue(label: "nl.tcp.\(label)")

        var hints = addrinfo(
            ai_flags: 0, ai_family: AF_INET, ai_socktype: SOCK_STREAM, ai_protocol: IPPROTO_TCP,
            ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil
        )
        var result: UnsafeMutablePointer<addrinfo>?
        let rc = getaddrinfo(host, String(port), &hints, &result)
        guard rc == 0, let info = result else {
            throw SocketError(op: "解析相机地址 \(host)", code: rc)
        }
        defer { freeaddrinfo(result) }

        let sock = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
        guard sock >= 0 else { throw SocketError(op: "创建 socket", code: errno) }

        // 窗口必须在 connect 之前定：SYN 里协商的窗口缩放才算数。
        // 设不上不致命（部分系统会裁剪），与原实现一致地忽略失败。
        var rcv = Int32(receiveBuffer)
        setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcv, socklen_t(MemoryLayout<Int32>.size))
        var snd = Int32(sendBuffer)
        setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &snd, socklen_t(MemoryLayout<Int32>.size))

        var noDelay: Int32 = 1
        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &noDelay, socklen_t(MemoryLayout<Int32>.size))
        var keepAlive: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, &keepAlive, socklen_t(MemoryLayout<Int32>.size))

        // 事件通道绑定到命令通道同一本地地址（基准实现 forced 语义）
        if let bindLocal, !bindLocal.isEmpty {
            var local = sockaddr_in()
            local.sin_family = sa_family_t(AF_INET)
            local.sin_port = bindLocalPort.bigEndian
            if inet_pton(AF_INET, bindLocal, &local.sin_addr) == 1 {
                let bindRC = withUnsafePointer(to: &local) { ptr in
                    ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        bind(sock, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
                // 绑定失败不致命：照原实现继续连（否则单网设备反而连不上）
                _ = bindRC
            }
        }

        // connect 超时：先设非阻塞 + select 等待可写，再切回阻塞
        let flags = fcntl(sock, F_GETFL, 0)
        _ = fcntl(sock, F_SETFL, flags | O_NONBLOCK)
        let connectRC = withUnsafePointer(to: info.pointee.ai_addr) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Foundation.connect(sock, sa, info.pointee.ai_addrlen)
            }
        }
        if connectRC != 0 && errno != EINPROGRESS {
            let e = errno
            Darwin.close(sock)
            throw SocketError(op: "连接 \(host):\(port)", code: e)
        }
        if connectRC != 0 {
            var pfd = pollfd(fd: sock, events: Int16(POLLOUT), revents: 0)
            let ready = poll(&pfd, 1, connectTimeoutMs)
            if ready <= 0 {
                Darwin.close(sock)
                throw CameraError.timeout("连接相机超时（\(host):\(port)，\(connectTimeoutMs)ms）")
            }
            var soError: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(sock, SOL_SOCKET, SO_ERROR, &soError, &len)
            if soError != 0 {
                Darwin.close(sock)
                throw SocketError(op: "连接 \(host):\(port)", code: soError)
            }
        }
        _ = fcntl(sock, F_SETFL, flags)

        // 读超时 1s（与 Android SO_TIMEOUT 一致）
        var tv = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var own = sockaddr_in()
        var ownLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        var localIP: String?
        // 先只让 getsockname 把本机地址写进 `own`（闭包内不读 own，避免独占访问冲突），
        // 闭包结束后再从 own 里取 sin_addr —— 这样「写」和「读」不重叠。
        let bound = withUnsafeMutablePointer(to: &own) { ptr -> Bool in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                getsockname(sock, sa, &ownLen) == 0
            }
        }
        if bound {
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var addr = own.sin_addr
            if inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil {
                localIP = String(cString: buf)
            }
        }

        return TcpSocket(fd: sock, queue: queue, localAddress: localIP)
    }

    // MARK: - I/O（阻塞实现，调用方必须在自己的队列上跑）

    /// 读取恰好 n 字节。
    ///
    /// - Parameter idleOk: 为 true 时允许「还没读到任何字节就超时」返回 nil（包边界空闲）；
    ///   为 false 时超时后继续等（载荷读到一半，必须保持流同步）。
    /// - Returns: nil 表示边界空闲超时；Data 表示读满。
    func readExactly(_ n: Int, idleOk: Bool) throws -> Data? {
        if n == 0 { return Data() }
        var buffer = [UInt8](repeating: 0, count: n)
        var read = 0
        while read < n {
            let got = buffer.withUnsafeMutableBytes { raw -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return recv(fd, base.advanced(by: read), n - read, 0)
            }
            if got > 0 {
                read += got
                continue
            }
            if got == 0 { throw CameraError.transportLost("相机连接已断开（对端关闭）", nil) }
            let e = errno
            if e == EAGAIN || e == EWOULDBLOCK {
                if idleOk && read == 0 { return nil }
                continue            // 载荷读取中：继续等
            }
            if e == EINTR { continue }
            throw SocketError(op: "接收数据", code: e)
        }
        return Data(buffer)
    }

    /// 写满整块（部分写要循环）。
    func write(_ data: Data) throws {
        guard !data.isEmpty else { return }
        try data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            var sent = 0
            while sent < data.count {
                let n = send(fd, base.advanced(by: sent), data.count - sent, 0)
                if n > 0 { sent += n; continue }
                let e = errno
                if e == EINTR { continue }
                if e == EAGAIN || e == EWOULDBLOCK {
                    var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                    _ = poll(&pfd, 1, 1_000)
                    continue
                }
                throw SocketError(op: "发送数据", code: e)
            }
        }
    }

    func close() {
        let closing = fd
        fd = -1
        if closing >= 0 { shutdown(closing, SHUT_RDWR); Foundation.close(closing) }
    }

    deinit { if fd >= 0 { shutdown(fd, SHUT_RDWR); Foundation.close(fd) } }

    // MARK: - 异步包装

    /// 在 socket 的串行队列上执行一段阻塞 I/O，并以 async 形式返回。
    func run<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { cont in
            queue.async {
                do { cont.resume(returning: try body()) }
                catch { cont.resume(throwing: error) }
            }
        }
    }

    /// 不返回值的版本（写操作常用）。
    func runVoid(_ body: @escaping @Sendable () throws -> Void) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                do { try body(); cont.resume() }
                catch { cont.resume(throwing: error) }
            }
        }
    }
}
