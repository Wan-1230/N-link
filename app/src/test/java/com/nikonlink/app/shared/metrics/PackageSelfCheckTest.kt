package com.nikonlink.app.shared.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** FR-08a：关于页的自证文案 —— 说了什么、没说什么，都要钉住。 */
class PackageSelfCheckTest {

    private fun describe(
        debuggable: Boolean = false,
        apkBytes: Long = 3_941_780L,
        digest: ByteArray? = byteArrayOf(
            0x37.toByte(), 0x24, 0x02, 0xd5.toByte(), 0x1a, 0x00, 0x7f, 0x09
        )
    ) = PackageSelfCheck.describe(
        versionName = "2.3.1",
        versionCode = 23,
        debuggable = debuggable,
        apkBytes = apkBytes,
        apkDigest = digest,
        installedAtMillis = 0L
    )

    @Test
    fun `摘要取前八位小写十六进制`() {
        val digest = byteArrayOf(0x37.toByte(), 0x24, 0x02, 0xd5.toByte(), 0x1a, 0x00, 0x7f, 0x09)
        assertEquals("372402d5", PackageSelfCheck.fingerprint(digest))
        assertEquals("3724", PackageSelfCheck.fingerprint(digest, 4))
    }

    @Test
    fun `文案带上版本号与构建类型`() {
        val text = describe()
        assertTrue(text.contains("v2.3.1"))
        assertTrue(text.contains("versionCode 23"))
        assertTrue(text.contains("正式包"))
        assertTrue(text.contains("3.8 MB"))
        assertFalse("调试包字样不该出现在正式包里", text.contains("调试包"))
        assertTrue(text.contains("372402d5"))
    }

    @Test
    fun `算不出哈希时直说读不到，不留空也不编一个`() {
        val text = describe(debuggable = true, apkBytes = 0L, digest = null)
        assertTrue(text.contains("本机包 SHA-256 读不到"))
        assertTrue(text.contains("调试包"))
        assertTrue(text.contains("0 B"))
    }

    @Test
    fun `写明三个渠道比的是 version-info 公布的值，并提醒对不上先问怎么装的`() {
        val text = describe()
        assertTrue(text.contains("GitHub Releases / 夸克网盘 / 百度网盘"))
        assertTrue(text.contains("version-info.txt"))
        assertTrue(text.contains("先想「怎么装的」"))
    }

    @Test
    fun `字节数按量级换单位`() {
        assertEquals("1 KB", PackageSelfCheck.formatBytes(1024L))
        assertEquals("1.5 MB", PackageSelfCheck.formatBytes(1024L * 1024 + 512L * 1024))
        assertEquals("99 B", PackageSelfCheck.formatBytes(99L))
    }

    @Test
    fun `sha256 与 JDK 实现对同一份内容给出同一个值`() {
        val file = File.createTempFile("nlink-selfcheck", ".bin").apply {
            writeBytes(ByteArray(200_000) { (it % 251).toByte() })
        }
        try {
            val mine = PackageSelfCheck.sha256(file)!!
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            assertTrue(mine.contentEquals(expected))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `文件不存在时返回 null，不把异常抛给界面`() {
        assertNull(PackageSelfCheck.sha256(File("no/such/file.apk")))
    }
}
