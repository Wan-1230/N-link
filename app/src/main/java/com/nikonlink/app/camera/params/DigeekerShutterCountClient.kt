package com.nikonlink.app.camera.params

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 尼康快门次数查询客户端。
 *
 * 机身 PTP 不提供快门次数时，App 自动把相机照片导出到缓存，
 * 通过 https://nikon.digeeker.com/ 的 EXIF 解析接口读取 MakerNotes.ShutterCount。
 * 接口约定（实测确认）：check -> chunk -> merge -> view。
 */
@Singleton
class DigeekerShutterCountClient @Inject constructor() {

    companion object {
        private const val TAG = "Digeeker"
        private const val API_BASE = "https://api.digeeker.com"
        private const val CHUNK_SIZE = 5 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    /**
     * 上传一张 JPEG/RAW 并解析快门次数。失败或照片不含 MakerNotes 时返回 null。
     */
    suspend fun queryShutterCount(file: File): Int? = withContext(Dispatchers.IO) {
        try {
            val totalChunks = ((file.length() + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
                .coerceAtLeast(1)
            val fileName = file.name

            val checkBody = postJson(
                "/v1/exif/upload/check",
                JSONObject()
                    .put("filename", fileName)
                    .put("file_size", file.length())
                    .put("total_chunks", totalChunks)
            )
            val uploadId = checkBody.optJSONObject("data")?.optString("upload_id").orEmpty()
            if (uploadId.isBlank()) {
                Timber.tag(TAG).w("Digeeker check did not return upload_id")
                return@withContext null
            }

            var offset = 0L
            for (index in 0 until totalChunks) {
                val chunkFile = File.createTempFile("digeeker_chunk_$index", ".bin", file.parentFile).apply {
                    deleteOnExit()
                }
                file.inputStream().use { input ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = input.skip(offset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    chunkFile.outputStream().use { output ->
                        var remaining = minOf(CHUNK_SIZE.toLong(), file.length() - offset)
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                try {
                    postMultipart(
                        "/v1/exif/upload/chunk",
                        uploadId = uploadId,
                        chunkIndex = index,
                        totalChunks = totalChunks,
                        chunkFile = chunkFile
                    )
                } finally {
                    chunkFile.delete()
                }
                offset += CHUNK_SIZE
            }

            val mergeBody = postJson(
                "/v1/exif/upload/merge",
                JSONObject()
                    .put("upload_id", uploadId)
                    .put("filename", fileName)
                    .put("total_chunks", totalChunks)
            )
            val exifId = mergeBody.optJSONObject("data")?.optString("exif_id").orEmpty()
            if (exifId.isBlank()) {
                Timber.tag(TAG).w("Digeeker merge did not return exif_id")
                return@withContext null
            }

            val viewBody = getText("/v1/exif/view/$exifId")
            extractShutterCount(viewBody).also { count ->
                if (count != null) {
                    Timber.tag(TAG).i("Shutter count from digeeker: $count")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Digeeker shutter count query failed")
            null
        }
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val connection = openConnection(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
        }
        val body = readResponse(connection)
        return runCatching { JSONObject(body) }
            .getOrElse { throw IOException("Digeeker invalid JSON: $body") }
    }

    private fun getText(path: String): String {
        val connection = openConnection(path)
        connection.requestMethod = "GET"
        return readResponse(connection)
    }

    private fun postMultipart(
        path: String,
        uploadId: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkFile: File
    ): String {
        val boundary = "----N-Link${UUID.randomUUID()}"
        val connection = openConnection(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "multipart/form-data; boundary=$boundary"
        )
        connection.outputStream.use { output ->
            writeField(output, boundary, "upload_id", uploadId)
            writeField(output, boundary, "chunk_index", chunkIndex.toString())
            writeField(output, boundary, "total_chunks", totalChunks.toString())

            output.write(
                ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"chunk\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n")
                    .toByteArray(Charsets.UTF_8)
            )
            FileInputStream(chunkFile).use { input -> input.copyTo(output) }
            output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
        return readResponse(connection)
    }

    private fun writeField(
        output: java.io.OutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        output.write(
            ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                "$value\r\n")
                .toByteArray(Charsets.UTF_8)
        )
    }

    private fun openConnection(path: String): HttpURLConnection {
        return (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Request-Id", "req-${UUID.randomUUID()}")
            setRequestProperty("X-User-Id", "n-link-android")
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException("Digeeker HTTP $code: ${body.take(200)}")
        }
        return body
    }

    private fun extractShutterCount(body: String): Int? {
        runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data")
            val exif = data?.optJSONObject("exif_data")
            val makerNotes = exif?.optJSONObject("MakerNotes")
            val direct = exif?.optInt("ShutterCount", -1)
            val nested = makerNotes?.optInt("ShutterCount", -1)
            listOf(nested, direct)
                .firstOrNull { it != null && it >= 0 }
                ?.let { return it }
        }
        return Regex("\"ShutterCount\"\\s*:\\s*(\\d+)")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
}
