package com.nikonlink.app.core.imaging.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型注册中心（PRD-AI修图 8.5 模型交付策略）
 *
 * - 清单加载：assets/models.json
 * - 内置模型：assets/models/{id}.tflite 首次使用时复制到私有目录
 * - 按需下载：断点重新下载（整文件）、SHA-256 强制校验、版本号整包替换
 * - 降级链查询：isAvailable(capability)，未就绪时上层回退传统算法
 *
 * 日志来源: ModelRegistry 标签输出下载/校验/加载结果。
 */
@Singleton
class ModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ModelRegistry"
        private const val MANIFEST_ASSET = "models.json"
        private const val BUILTIN_ASSET_DIR = "models"
        private const val PREFS = "edit_models"
        private const val DOWNLOAD_BUFFER = 1024 * 256
    }

    /** 单个模型的交付状态 */
    sealed class ModelState {
        data object NotDownloaded : ModelState()
        data class Downloading(val percent: Int) : ModelState()
        data class Ready(val file: File) : ModelState()
        data class Failed(val reason: String) : ModelState()
    }

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val specsByCapability = mutableMapOf<String, ModelSpec>()
    private val installDir = File(context.filesDir, "edit_models")
    private var initialized = false

    /** 懒加载清单（主线程外调用亦可；幂等） */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        val json = runCatching {
            context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val specs = ModelManifestParser.parse(json)
        specsByCapability.clear()
        specs.forEach { specsByCapability[it.capability] = it }
        initialized = true

        // 已安装模型状态恢复（版本不一致则视为需更新）
        specs.forEach { spec ->
            val installed = installedFile(spec)
            setState(spec, when {
                installed != null && ModelVerifier.verify(installed, spec.sha256) ->
                    ModelState.Ready(installed)
                installed != null -> {
                    installed.delete()
                    ModelState.NotDownloaded
                }
                spec.tier == ModelTier.BUILTIN -> resolveBuiltin(spec)
                else -> ModelState.NotDownloaded
            })
        }
        Timber.tag(TAG).i("Manifest loaded: ${specs.size} models")
    }

    fun specOf(capability: String): ModelSpec? {
        ensureInitialized()
        return specsByCapability[capability]
    }

    /** 降级链查询：该能力的模型是否已就绪（PRD 8.5） */
    fun isAvailable(capability: String): Boolean {
        ensureInitialized()
        val spec = specsByCapability[capability] ?: return false
        return stateOf(spec) is ModelState.Ready
    }

    /** 获取已就绪模型文件；未就绪返回 null（调用方走降级） */
    fun modelFile(capability: String): File? {
        ensureInitialized()
        val spec = specsByCapability[capability] ?: return null
        return (stateOf(spec) as? ModelState.Ready)?.file
    }

    fun stateOf(spec: ModelSpec): ModelState =
        _states.value[spec.capability] ?: ModelState.NotDownloaded

    /**
     * 按需下载模型（PRD 8.5: 首次使用提示下载，支持进度回调）。
     * @return 就绪后的模型文件；失败抛出 IllegalStateException（含中文化原因）
     */
    suspend fun download(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        if (spec.url.isBlank() || spec.url.contains("example")) {
            // 占位地址（模型未发布）：直接失败，上层走传统算法降级
            setState(spec, ModelState.Failed("模型组件尚未发布"))
            throw IllegalStateException("模型组件尚未发布")
        }
        installDir.mkdirs()
        val target = installedFile(spec)
            ?: File(installDir, "${spec.id}_v${spec.version}.tflite")
        val temp = File(installDir, "${spec.id}.downloading")

        setState(spec, ModelState.Downloading(0))
        var connection: HttpURLConnection? = null
        try {
            connection = URL(spec.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("下载失败（HTTP ${connection.responseCode}）")
            }
            val total = if (spec.sizeBytes > 0) spec.sizeBytes
            else connection.contentLengthLong.coerceAtLeast(1L)
            var received = 0L
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { out ->
                    val buf = ByteArray(DOWNLOAD_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        val pct = (received * 100 / total).toInt().coerceIn(0, 100)
                        setState(spec, ModelState.Downloading(pct))
                    }
                }
            }

            // PRD 8.5: 加载前强制校验完整性
            if (!ModelVerifier.verify(temp, spec.sha256)) {
                temp.delete()
                setState(spec, ModelState.Failed("校验失败，请重新下载"))
                throw IllegalStateException("模型校验失败，请重新下载")
            }

            // 版本号整包替换：清掉旧版本文件
            installDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("${spec.id}_v") && f != target) f.delete()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("ver_${spec.id}", spec.version).apply()
            setState(spec, ModelState.Ready(target))
            Timber.tag(TAG).i("Downloaded ${spec.id} v${spec.version} (${received}B)")
            target
        } catch (e: Exception) {
            temp.delete()
            if (stateOf(spec) !is ModelState.Failed) {
                setState(spec, ModelState.Failed(e.message ?: "下载失败"))
            }
            Timber.tag(TAG).w(e, "Download failed: ${spec.id}")
            when (e) {
                is IllegalStateException -> throw e
                else -> throw IllegalStateException("下载失败：${e.message ?: "网络异常"}", e)
            }
        } finally {
            connection?.disconnect()
        }
    }

    /** 内置模型解析：assets 存在则复制到私有目录并校验 */
    private fun resolveBuiltin(spec: ModelSpec): ModelState {
        val assetPath = "$BUILTIN_ASSET_DIR/${spec.id}.tflite"
        return runCatching {
            val exists = runCatching { context.assets.open(assetPath).close() }.isSuccess
            if (!exists) return ModelState.NotDownloaded

            installDir.mkdirs()
            val target = File(installDir, "${spec.id}_v${spec.version}.tflite")
            if (!target.exists() || !ModelVerifier.verify(target, spec.sha256)) {
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                if (!ModelVerifier.verify(target, spec.sha256)) {
                    target.delete()
                    return ModelState.Failed("内置模型校验失败")
                }
            }
            ModelState.Ready(target)
        }.getOrElse {
            Timber.tag(TAG).w(it, "resolveBuiltin failed: ${spec.id}")
            ModelState.NotDownloaded
        }
    }

    private fun installedFile(spec: ModelSpec): File? {
        val file = File(installDir, "${spec.id}_v${spec.version}.tflite")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    private fun setState(spec: ModelSpec, state: ModelState) {
        _states.value = _states.value + (spec.capability to state)
    }
}
