package com.nikonlink.app.core.imaging.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 模型交付（PRD-AI修图 8.5）
 *
 * 分发策略：APK 内置 ~15MB（一键增强开箱即用）+ 高级模型包 ~100MB 按需下载。
 * 清单来源：assets/models.json；下载后强制 SHA-256 校验，按版本号整包替换。
 */

/** 分发层级 */
enum class ModelTier {
    /** 随 APK 内置（assets/models/ 下） */
    @SerializedName("builtin") BUILTIN,

    /** 首次使用时按需下载 */
    @SerializedName("on_demand") ON_DEMAND
}

/** 模型能力标识（对应 PRD 8.2 模型清单的「对应功能」） */
object ModelCapability {
    const val AUTO_ENHANCE = "auto_enhance"      // Image-Adaptive LUT
    const val SCENE_CLASSIFY = "scene_classify"  // EfficientNet-Lite2
    const val DENOISE = "denoise"                // NAFNet-w64
    const val DETAIL_RESTORE = "detail_restore"  // Real-ESRGAN_x4plus
    const val FACE_DETECT = "face_detect"        // SCRFD-10M
    const val FACE_PARSE = "face_parse"           // BiSeNet
    const val LOW_LIGHT = "low_light"            // RetinexFormer
    const val DEHAZE = "dehaze"                  // MPRA-Net
}

/**
 * 单个模型规格。
 * url/sha256 为发布占位：模型文件上传 OSS/CDN 后由运维回填（PRD Q1 已定模型来源，
 * 法务审查通过后方可发布真实地址）。
 */
data class ModelSpec(
    val id: String,
    val capability: String,
    val version: Int,
    val url: String,
    val sha256: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    val tier: ModelTier,
    @SerializedName("display_name") val displayName: String = id
)

/** 清单文件结构（assets/models.json） */
data class ModelManifest(val models: List<ModelSpec>)

/**
 * 清单解析器：纯 JVM 实现，可被单元测试覆盖。
 * 解析失败返回空清单（调用方走传统算法降级链，不崩溃）。
 */
object ModelManifestParser {

    fun parse(json: String): List<ModelSpec> {
        return runCatching {
            Gson().fromJson(json, ModelManifest::class.java)
                ?.models
                ?.filter { it.id.isNotBlank() && it.capability.isNotBlank() && it.version > 0 }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }
}

/**
 * SHA-256 校验（PRD 8.5: 加载前强制校验完整性）。
 * 纯 JVM 实现，可被单元测试覆盖。
 */
object ModelVerifier {

    fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /** 流式校验，避免大模型文件整体读入内存 */
    fun sha256Hex(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 256)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: java.io.File, expectedSha256: String): Boolean {
        return runCatching { sha256Hex(file).equals(expectedSha256, ignoreCase = true) }
            .getOrDefault(false)
    }
}
