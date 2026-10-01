package com.nikonlink.app.device.ftp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.nikonlink.app.shared.common.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random

/**
 * 本机 FTP 服务器（PRD §4.5 备选架构的落地实现）。
 *
 * **为什么需要它**：尼康相机在 STA 模式下对未注册主机回 InitFail，而注册握手
 * （0x952B/0x935A）必须发生在相机的主机配置向导里 —— 这一步在机型/固件之间差异很大，
 * 是 STA 直连最主要的不确定性来源（见 `docs/STA注册失败-根因分析与优化方案.md`）。
 * 尼康另有一条**官方菜单**「连接到 FTP 服务器」：相机主动把照片**推**到指定服务器，
 * **完全不需要注册**。NikonLink / 帧澈 ZENCHE 走的也是这条路。
 * 本类就是这条路的收图端。
 *
 * **端口**：刻意不用 21。绑定 <1024 的端口在 Android 上需要 root，普通应用只能起
 * 高位端口（默认 [DEFAULT_PORT] = 2121），相机侧在 FTP 配置里填同一个端口即可。
 *
 * **协议范围**：实现尼康机身 FTP 客户端实际会用到的命令子集（USER/PASS/SYST/FEAT/
 * OPTS/TYPE/PWD/CWD/CDUP/MKD/PASV/EPSV/STOR/APPE/LIST/NLST/MLSD/SIZE/MDTM/DELE/
 * NOOP/QUIT），未实现的回 502 而不是断连 —— 相机拿到 502 会跳过该命令继续，
 * 断连则会让整次会话失败。
 *
 * **主动模式（PORT）不支持**：只在控制连接上回 502。全系 Z 机身默认走 PASV/EPSV，
 * 且主动模式要求手机在相机的网络里可被反连，在「相机开热点」拓扑下不可靠。
 *
 * **生命周期**：持有自己的 [scope]（不挂在任何 ViewModel 上），所以切页/后台不会中断
 * 正在进行的推送；只有用户点停止或进程被杀才结束。切到另一种 STA 架构时由 UI 显式停止，
 * 避免留下"看不见却在跑"的状态（架构切换的状态隔离要求）。
 */
@Singleton
class FtpPushServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {

    companion object {
        private const val TAG = "FtpPush"

        /** 默认监听端口。选 2121 是因为 <1024 需要 root */
        const val DEFAULT_PORT = 2121

        /** 登录用户名（固定；密码每次安装首次启动时随机生成并持久化） */
        const val USER = "nlink"

        private const val PREFS = "nl_ftp"
        private const val KEY_PASS = "ftp_pass"

        /** 单个数据连接的空闲超时：相机停止发送后这么久就收尾（避免半开连接占着不放手） */
        private const val DATA_IDLE_MS = 30_000

        /** 控制连接最大并发：机身通常 1 条控制 + 数据连接若干，给 4 条余量 */
        private const val MAX_CLIENTS = 4

        /** 图片类扩展名 → 走 MediaStore.Images；其余走 Video / Downloads */
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "tif", "tiff", "nef", "nrw", "dng")
        private val VIDEO_EXT = setOf("mov", "mp4", "m4v", "avi")
    }

    /** 收到的单个文件记录（供 UI 列表展示） */
    data class Received(
        val name: String,
        val bytes: Long,
        val at: Long,
        val savedPath: String?,
    )

    /** 对外状态。UI 只读这一个流，切架构时不会残留对方的状态 */
    sealed interface State {
        data object Stopped : State

        data class Listening(
            /** 相机侧要填的地址，形如 `192.168.1.2` */
            val host: String,
            val port: Int,
            val user: String,
            val pass: String,
        ) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _received = MutableStateFlow<List<Received>>(emptyList())
    val received: StateFlow<List<Received>> = _received.asStateFlow()

    private val _clients = MutableStateFlow(0)
    val clients: StateFlow<Int> = _clients.asStateFlow()

    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /** 持久化密码：每次安装只生成一次，避免用户每次开机都要去相机里重填 */
    val password: String
        get() {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString(KEY_PASS, null)?.let { return it }
            val pool = "abcdefghjkmnpqrstuvwxyz23456789"
            val fresh = (1..6).map { pool[Random.nextInt(pool.length)] }.joinToString("")
            p.edit().putString(KEY_PASS, fresh).apply()
            return fresh
        }

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    /**
     * 启动监听。[port] 允许调用方覆盖，便于端口被占时换一个。
     *
     * 绑定失败（端口占用 / 权限）不抛异常，落到 [State.Failed] 让 UI 显示原因 ——
     * 这一层失败必须可解释，否则用户只看到"按钮没反应"。
     */
    fun start(port: Int = DEFAULT_PORT) {
        if (running) return
        val pass = password
        acceptJob = scope.launch {
            val ss = try {
                ServerSocket(port).apply { reuseAddress = true }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "bind failed on $port")
                _state.value = State.Failed("端口 $port 无法监听：${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            serverSocket = ss
            running = true
            // 监听地址显示用「当前最可能的局域网地址」；真正回给相机的地址在 PASV 里
            // 用控制连接的 localAddress 现算（见 replyPassive），那个才是必然可达的。
            _state.value = State.Listening(
                host = preferredLocalAddress() ?: "未获取到局域网地址",
                port = port,
                user = USER,
                pass = pass,
            )
            Timber.tag(TAG).i("FTP listening on $port")
            try {
                while (isActive) {
                    val client = ss.accept()
                    if (_clients.value >= MAX_CLIENTS) {
                        client.close()
                        continue
                    }
                    scope.launch { handleClient(client, pass) }
                }
            } catch (e: Exception) {
                if (isActive) Timber.tag(TAG).w(e, "accept loop ended")
            } finally {
                runCatching { ss.close() }
                if (serverSocket === ss) {
                    serverSocket = null
                    running = false
                }
            }
        }
    }

    /** 停止监听并断开所有在途连接（在途文件按已收到的部分落盘，不丢已传数据） */
    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _clients.value = 0
        _state.value = State.Stopped
    }

    fun clearHistory() {
        _received.value = emptyList()
    }

    // ───────────────────────── 单客户端会话 ─────────────────────────

    private suspend fun handleClient(socket: Socket, pass: String) = withContext(Dispatchers.IO) {
        _clients.value = _clients.value + 1
        var dataServer: ServerSocket? = null
        try {
            socket.tcpNoDelay = true
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1))
            var loggedIn = false
            var user: String? = null
            var renameFrom: String? = null

            reply(output, "220 N-Link FTP ready")

            while (true) {
                val line = input.readLine() ?: break
                val sep = line.indexOf(' ')
                val cmd = (if (sep < 0) line else line.substring(0, sep)).uppercase(Locale.US)
                val arg = if (sep < 0) "" else line.substring(sep + 1)
                Timber.tag(TAG).v("← $cmd")

                when (cmd) {
                    "USER" -> {
                        user = arg.trim()
                        reply(output, "331 Password required")
                    }

                    "PASS" -> {
                        if (user == USER && arg == pass) {
                            loggedIn = true
                            reply(output, "230 Logged in")
                        } else {
                            reply(output, "530 Login incorrect")
                        }
                    }

                    "SYST" -> reply(output, "215 UNIX Type: L8")
                    "FEAT" -> {
                        // 逐行 reply 而不是一个多行块：机身按行解析，分开发最稳
                        reply(output, "211-Features")
                        reply(output, " PASV")
                        reply(output, " EPSV")
                        reply(output, " SIZE")
                        reply(output, " MDTM")
                        reply(output, " UTF8")
                        reply(output, "211 End")
                    }

                    "OPTS" -> reply(output, "200 OK")
                    "TYPE" -> reply(output, "200 Type set")
                    "MODE" -> reply(output, "200 Mode set")
                    "STRU" -> reply(output, "200 Structure set")
                    "NOOP" -> reply(output, "200 OK")

                    "PWD", "XPWD" -> reply(output, "257 \"/\" is current directory")
                    // 机身会按日期建目录；我们统一扁平落盘到相册，所以这里一律接受
                    "CWD", "XCWD", "CDUP" -> reply(output, "250 OK")
                    "MKD", "XMKD" -> reply(output, "257 \"/\" created")

                    "PASV" -> {
                        dataServer?.let { runCatching { it.close() } }
                        dataServer = replyPassive(socket, output, extended = false)
                    }

                    "EPSV" -> {
                        dataServer?.let { runCatching { it.close() } }
                        dataServer = replyPassive(socket, output, extended = true)
                    }

                    "PORT", "EPRT" -> reply(output, "502 Active mode not supported, use PASV")

                    "STOR", "APPE" -> {
                        if (!loggedIn) {
                            reply(output, "530 Not logged in")
                        } else {
                            dataServer = receiveFile(output, dataServer, arg.trim())
                            dataServer?.let { runCatching { it.close() } }
                            dataServer = null
                        }
                    }

                    "LIST", "NLST", "MLSD" -> {
                        reply(output, "150 Opening data connection")
                        dataServer?.accept()?.use { }
                        runCatching { dataServer?.close() }
                        dataServer = null
                        reply(output, "226 Transfer complete")
                    }

                    "SIZE", "MDTM" -> reply(output, "550 Not available")
                    "DELE" -> reply(output, "250 OK")
                    "RNFR" -> {
                        renameFrom = arg.trim()
                        reply(output, "350 Ready for destination name")
                    }

                    "RNTO" -> {
                        renameFrom = null
                        reply(output, "250 OK")
                    }

                    "ABOR" -> reply(output, "226 Aborted")
                    "QUIT" -> {
                        reply(output, "221 Bye")
                        break
                    }

                    "" -> Unit
                    else -> reply(output, "502 Command not implemented: $cmd")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "client session ended")
        } finally {
            runCatching { dataServer?.close() }
            runCatching { socket.close() }
            _clients.value = max(0, _clients.value - 1)
        }
    }

    /**
     * 回 227/229 并把被动数据端口开好。
     *
     * 关键点：**上报的 IP 取控制连接的 localAddress**，不是遍历网卡猜出来的。
     * 相机是从那条连接找过来的，那个地址对它必然可达；在「手机同时开着热点 + 连着
     * 相机热点 + 蜂窝」的现场，猜网卡几乎必错（我们自己 STA 侧的 `in_subnet=false`
     * 日志就是同一个坑）。
     */
    private fun replyPassive(socket: Socket, output: BufferedWriter, extended: Boolean): ServerSocket? {
        return try {
            val bound = ServerSocket(0, 1, socket.localAddress)
            // accept 也要有超时：相机开了 PASV 却从不来连（配置填错、或它自己超时退出）时，
            // 裸 accept 会把这条控制连接永久挂住 —— 后面相机再来一条又要占一个线程。
            bound.soTimeout = DATA_IDLE_MS
            val port = bound.localPort
            val host = (socket.localAddress as? Inet4Address)?.hostAddress
                ?: socket.localAddress.hostAddress
                ?: return null
            if (extended) {
                reply(output, "229 Entering Extended Passive Mode (|||$port|)")
            } else {
                val octets = host.split(".").mapNotNull { it.toIntOrNull() }
                if (octets.size != 4) {
                    bound.close()
                    return null
                }
                val p1 = port / 256
                val p2 = port % 256
                reply(
                    output,
                    "227 Entering Passive Mode (${octets[0]},${octets[1]},${octets[2]},${octets[3]},$p1,$p2)",
                )
            }
            bound
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "open passive port failed")
            null
        }
    }

    /**
     * 收一个文件：150 → 数据连接读满 → 落盘 → 226。
     *
     * 读满的判据是**数据连接 EOF**（机身写完就关），不是靠文件长度 —— FTP 的 STOR
     * 不带长度，SIZE 命令机身也不一定先问。加一个空闲超时兜底半开连接。
     */
    private suspend fun receiveFile(
        output: BufferedWriter,
        dataServer: ServerSocket?,
        rawName: String,
    ): ServerSocket? {
        if (dataServer == null) {
            reply(output, "425 Use PASV first")
            return null
        }
        val name = sanitize(rawName)
        if (name == null) {
            reply(output, "553 Invalid file name")
            return dataServer
        }
        reply(output, "150 Opening data connection for $name")

        val tmp = File(context.cacheDir, "ftp-in")
        tmp.mkdirs()
        val target = File(tmp, "${System.currentTimeMillis()}-$name")
        var bytes = 0L
        var dataOpened = false
        try {
            val dataSocket = dataServer.accept()
            dataOpened = true
            dataSocket.use { ds ->
                ds.soTimeout = DATA_IDLE_MS
                val input: InputStream = ds.getInputStream()
                BufferedOutputStream(FileOutputStream(target)).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (e: Exception) {
                            // 空闲超时 / 对端重置：已收到的部分照常落盘，不当整单失败
                            break
                        }
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        bytes += n
                    }
                    out.flush()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "receive $name failed after $bytes B")
        }

        val saved = if (bytes > 0) saveToMediaStore(target, name) else null
        if (saved != null) {
            _received.value = (listOf(
                Received(
                    name = name,
                    bytes = bytes,
                    at = System.currentTimeMillis(),
                    savedPath = saved,
                ),
            ) + _received.value).take(20)
            Timber.tag(TAG).i("received $name ($bytes B) -> $saved")
        }
        target.delete()
        // 数据连接压根没建起来时回 425，别谎报 226 —— 相机据此才会重试或换被动端口
        reply(output, if (dataOpened) "226 Transfer complete" else "425 Can't open data connection")
        return dataServer
    }

    /** 去掉路径部分，只留文件名；拒绝空名、`.`/`..` 与非法字符，防目录穿越 */
    private fun sanitize(rawName: String): String? {
        val base = rawName.replace('\\', '/').substringAfterLast('/').trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        if (base.any { it == 0.toChar() || it.code < 32 }) return null
        return base.take(120)
    }

    /**
     * 落盘到系统相册。
     *
     * 保存位置与「相册页下载」保持同一语义（`AppSettings.savePath`）：
     * 系统相册 → `DCIM/N-Link`，Download 目录 → `Download/N-Link`。
     * 这样用户在同一个地方能同时看到下载来的和推送来的照片。
     */
    private fun saveToMediaStore(file: File, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val collection = when {
            VIDEO_EXT.contains(ext) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            IMAGE_EXT.contains(ext) -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val root = if (settings.savePath == AppSettings.SAVE_PATH_DOWNLOAD) {
            Environment.DIRECTORY_DOWNLOADS
        } else {
            Environment.DIRECTORY_DCIM
        }
        val relativePath = "$root/N-Link"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        return try {
            uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$relativePath/$fileName"
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "save to MediaStore failed: $fileName")
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "tif", "tiff" -> "image/tiff"
        "nef" -> "image/x-nikon-nef"
        "nrw" -> "image/x-nikon-nrw"
        "dng" -> "image/x-adobe-dng"
        "mov" -> "video/quicktime"
        "mp4", "m4v" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun reply(output: BufferedWriter, line: String) {
        runCatching {
            output.write(line)
            output.write("\r\n")
            output.flush()
            Timber.tag(TAG).v("→ $line")
        }
    }

    /**
     * 最可能的局域网地址（仅用于 UI 展示"相机该填哪"）。
     *
     * 优先 WiFi 类网卡名（wlan/ap/eth），跳过蜂窝（ccmni/rmnet）与 VPN（tun/ppp）——
     * 与连接层 `IFACE_CLASSIFY` 的判据同源，避免向用户展示一个相机根本到不了的地址。
     */
    fun preferredLocalAddress(): String? = candidateAddresses().firstOrNull()

    fun candidateAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { iface ->
                val n = iface.name.lowercase(Locale.US)
                !n.startsWith("ccmni") && !n.startsWith("rmnet") &&
                    !n.startsWith("tun") && !n.startsWith("ppp") && !n.startsWith("p2p")
            }
            .sortedByDescending { iface ->
                val n = iface.name.lowercase(Locale.US)
                when {
                    n.startsWith("wlan") -> 3
                    n.startsWith("ap") -> 2
                    n.startsWith("eth") -> 1
                    else -> 0
                }
            }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "enumerate interfaces failed")
        emptyList()
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatTime(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

    /** 供进程退出时兜底释放（由 Application 或 Activity 调用，可重复） */
    fun shutdown() {
        stop()
        runCatching { scope.cancel() }
    }
}
