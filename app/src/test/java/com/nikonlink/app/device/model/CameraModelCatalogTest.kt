package com.nikonlink.app.device.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-36：设备页「相机全称」的归一化规则回归。
 *
 * 覆盖的都是**真实会出现的写法**，不是随手造的样例：
 * - `NIKON Z 50II`：PTP `GetDeviceInfo`.Model 的机身自报形态（见 `UsbPtpProtocol` 注释里那句
 *   「@return 型号字符串（如 "NIKON Z 50II"）」，以及 v2.6 真机日志 `usb_open ok=true model=Z 50II`）；
 * - `Z6III_12345678`：BLE 广播 / mDNS 服务名形态（见 `WifiScanner` 注释）；
 * - `Nikon Camera (0x0FFF)`：USB 产品 ID 表没覆盖时的旧兜底串——它必须被判成「认不出」，
 *   否则设备页就还在显示十六进制 PID（用户报的「显示的不是官方相机名称」）。
 */
class CameraModelCatalogTest {

    @Test
    fun `机身自报的全大写串归一化成官方全称`() {
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("NIKON Z 50II"))
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("Nikon Z 50II"))
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("  nikon z_50ii  "))
    }

    /**
     * 尼康在 PTP / EXIF 里习惯用下划线写罗马数字代次（`Z 6_III`、`Z 5_II`），
     * 而机身铭牌与官网写的是 `Z 6III`、`Z 5II`。这条就是把「不是官方名称」改过来的核心。
     */
    @Test
    fun `下划线罗马数字代次折成官方写法`() {
        assertEquals("Nikon Z 6III", CameraModelCatalog.official("NIKON Z 6_III"))
        assertEquals("Nikon Z 6II", CameraModelCatalog.official("NIKON Z 6_II"))
        assertEquals("Nikon Z 7II", CameraModelCatalog.official("NIKON Z 7_II"))
        // 表里没有的代次（Z 5II）不能错归到相近的老机型，也不能退回品牌名
        assertEquals("Nikon Z 5II", CameraModelCatalog.official("NIKON Z 5_II"))
    }

    @Test
    fun `广播名与服务名剥掉序列号后缀后仍能认出机型`() {
        assertEquals("Nikon Z 6III", CameraModelCatalog.official("Z6III_12345678"))
        assertEquals("Nikon Z 8", CameraModelCatalog.official("Z8_00123456"))
        assertEquals("Nikon Z 50", CameraModelCatalog.official("Nikon Z50_87654321"))
    }

    /**
     * 关键回归：短型号绝不能抢长型号的判定（`BleManager.parseCameraModel` 当年就是
     * 靠 contains 顺序勉强躲开这个问题）。归一化后 `Z 50` 不能变成 `Z 5`、
     * `Z 6II` 不能变成 `Z 6`。
     */
    @Test
    fun `相近型号不互相误判`() {
        assertEquals("Nikon Z 50", CameraModelCatalog.official("NIKON Z 50"))
        assertEquals("Nikon Z 5", CameraModelCatalog.official("NIKON Z 5"))
        assertEquals("Nikon Z 6II", CameraModelCatalog.official("NIKON Z 6II"))
        assertEquals("Nikon Z 6", CameraModelCatalog.official("NIKON Z 6"))
        assertEquals("Nikon Z fc", CameraModelCatalog.official("NIKON Z fc"))
        assertEquals("Nikon Zf", CameraModelCatalog.official("NIKON Zf"))
    }

    /**
     * 认不出就返回 null —— 调用方据此回落到下一个来源。
     * 这条是「宁可暂时没名字，也不显示错名字」的硬约束：IP、MAC、十六进制 PID、
     * 路由器名、无权限占位串一律不许装成相机型号。
     */
    @Test
    fun `非型号串一律判不出来`() {
        // 旧版设备页出现过的两种「不是官方名称」的取值
        assertNull(CameraModelCatalog.official("Nikon Camera (0x0FFF)"))
        assertNull(CameraModelCatalog.official("192.168.1.1"))
        // 配对记录里那列历史上存的是 IP；BLE 地址同理
        assertNull(CameraModelCatalog.official("AA:BB:CC:DD:EE:FF"))
        // 不是尼康的网卡名/路由器名
        assertNull(CameraModelCatalog.official("TP-LINK_5G_88A1"))
        assertNull(CameraModelCatalog.official("NIKON_9431ABCD"))   // 热点 SSID，不是机型
        // 空值与系统占位
        assertNull(CameraModelCatalog.official(null as String?))
        assertNull(CameraModelCatalog.official(""))
        assertNull(CameraModelCatalog.official("   "))
        assertNull(CameraModelCatalog.official("<unknown ssid>"))
    }

    @Test
    fun `model 只返回认得出的机型，官方名与之成对`() {
        assertEquals(CameraModel.Z6III, CameraModelCatalog.model("NIKON Z 6_III"))
        assertEquals(CameraModel.Z50II, CameraModelCatalog.model("Z50II"))
        assertEquals(CameraModel.Z8, CameraModelCatalog.model("Z8_00123456"))
        assertNull(CameraModelCatalog.model("NIKON Z 5_II"))       // 表里没有：交给 official 的泛化分支
        assertEquals("Nikon Z 9", CameraModelCatalog.official(CameraModel.Z9))
        assertNull(CameraModelCatalog.official(null as String?))
        assertNull(CameraModelCatalog.official(CameraModel.UNKNOWN))
    }
}
