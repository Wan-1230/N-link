package com.nikonlink.app.device.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ─────────────────────────────────────────────────────────────────────────────
    // v3.1 专项 §2.6：显示层「只清洗、不翻译」的硬回归。
    // 立项起因（2026-10-11 真机 + JVM 实测）：`Character.isLetterOrDigit('\u2161')==false`，
    // 旧实现的 filter 把罗马数字静默删掉，`Z 50Ⅱ` 撞上 Z50 枚举 → 把 Z 50Ⅱ 显示成
    // **另一台相机 Z 50**，且能力表跟着错。下面每条都锚定这个坑。
    // ─────────────────────────────────────────────────────────────────────────────

    /** 罗马数字必须原样展示：机身自报 `Ⅱ` 就显示 `Ⅱ`，不许换成 ASCII 的 `II`。 */
    @Test
    fun `罗马数字代次原样展示，不做字符转写`() {
        assertEquals("Nikon Z 50Ⅱ", CameraModelCatalog.official("Z 50Ⅱ"))
        assertEquals("Nikon Z 50Ⅱ", CameraModelCatalog.official("NIKON Z 50Ⅱ"))
        assertEquals("Nikon Z 6Ⅲ", CameraModelCatalog.official("Z 6Ⅲ"))
        assertEquals("Nikon Z 5Ⅱ", CameraModelCatalog.official("NIKON Z 5_Ⅱ"))   // 下划线清掉，罗马数字留下
        assertEquals("Nikon Z 50Ⅱ", CameraModelCatalog.official("Z50Ⅱ"))          // 补 Z 与数字间的空格
    }

    /**
     * 反方向同样禁止：ASCII `II` 的原文**不许**被"美化"成 `Ⅱ`。
     * 「严格对应原始元数据」优先于「全 App 统一写法」——两种写法都可能真实出现在机身上。
     */
    @Test
    fun `ASCII 字母代次不被转成罗马数字`() {
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("Z 50II"))
        assertEquals("Nikon Z 6III", CameraModelCatalog.official("NIKON Z 6_III"))
    }

    /**
     * 用户前提 1 的正向表述：Z50 是一台独立的相机，绝不能因为"帮它补个代次"而显示成 Z50Ⅱ。
     */
    @Test
    fun `Z50 不被升级成 Z50 二代`() {
        assertEquals("Nikon Z 50", CameraModelCatalog.official("Z 50"))
        assertEquals("Nikon Z 50", CameraModelCatalog.official("NIKON Z 50"))
        assertEquals("Nikon Z 50", CameraModelCatalog.official("Nikon Z50_87654321"))
    }

    /**
     * 机型**身份**（能力表/兼容表按它分派）必须把罗马数字转写成 ASCII 后查表：
     * `Z 50Ⅱ` 与 `Z 50II` 命中同一枚举（同一台相机），而 `Z 50` 命中另一枚举（不同相机）。
     * 旧实现三者全部落到 Z50 —— 那才是"两台相机共用一张能力表"的真 bug。
     */
    @Test
    fun `查表键把罗马数字折成 ASCII 用于识别，但 Z50 与 Z50 二代仍是两个机型`() {
        assertEquals(CameraModel.Z50II, CameraModelCatalog.model("Z 50Ⅱ"))
        assertEquals(CameraModel.Z50II, CameraModelCatalog.model("Z 50II"))
        assertEquals(CameraModel.Z50, CameraModelCatalog.model("Z 50"))
        assertNotEquals(CameraModelCatalog.model("Z 50Ⅱ"), CameraModelCatalog.model("Z 50"))
        assertEquals(CameraModel.Z6III, CameraModelCatalog.model("Z 6Ⅲ"))
        assertEquals(CameraModel.Z6II, CameraModelCatalog.model("Z 6Ⅱ"))
    }

    /**
     * 真机一手取证的原文形态（2026-10-12 `model_raw source=ptpip len=5 chars=Z50_2`）。
     *
     * 机身自报的是阿拉伯数字代次 `Z50_2`，既不是 `Ⅱ` 也不是 `II`。旧实现删掉下划线
     * 得到 `Z502`，再被泛化分支拼成 `Nikon Z 502` —— 用户报的那个错数就是这么来的。
     * `_N → N 个 I` 折回枚举后，显示走既有的官方名 `Z 50II`，与同机型的另两种
     * 自报形态（`Z50II`、`Z 50Ⅱ`）归一。
     */
    @Test
    fun `机身自报的阿拉伯数字代次折回枚举官方名`() {
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("Z50_2"))
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("NIKON Z50_2"))
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("Z 50_2"))
        assertEquals(CameraModel.Z50II, CameraModelCatalog.model("Z50_2"))
        // 同一条规则对 Z 6II 也要成立，而不是只给 Z50 打补丁
        assertEquals("Nikon Z 6II", CameraModelCatalog.official("Z6_2"))
        assertEquals("Nikon Z 7II", CameraModelCatalog.official("Z7_2"))
        // 表里没有的代次按原文泛化，不猜相近机型
        assertEquals("Nikon Z 50III", CameraModelCatalog.official("Z50III_12345678"))
        // 序列号必须优先于代次被剥掉，否则尾巴数字会被当成代次
        assertEquals("Nikon Z 50II", CameraModelCatalog.official("Z50_2_12345678"))
    }

    /** USB 侧 Model 取不到时的兜底：PID 表那条路给的是正确名字，别被覆盖成错的。 */
    @Test
    fun `USB 的 PID 兜底名与机身自报归一结果必须一致`() {
        // 日志里 pid_fallback=Z 50II，而 parsed=Z50_2 —— 两条来源现在收敛到同一个全称
        assertEquals(
            CameraModelCatalog.official("Z 50II"),
            CameraModelCatalog.official("Z50_2")
        )
    }

    /** 带罗马数字的广播名形态（序列号后缀）也要认出来，剥完序列号后罗马数字仍在。 */
    @Test
    fun `罗马数字与序列号同串时各自处理`() {
        assertEquals("Nikon Z 50Ⅱ", CameraModelCatalog.official("Z 50Ⅱ_12345678"))
        assertEquals(CameraModel.Z50II, CameraModelCatalog.model("Z 50Ⅱ_12345678"))
    }

    /** 十六进制 PID 兜底串（日志 B 的 `Nikon Camera (0x452)`）永远装不成型号。 */
    @Test
    fun `USB 兜底串不被当成型号显示`() {
        assertNull(CameraModelCatalog.official("Nikon Camera (0x452)"))
        assertNull(CameraModelCatalog.model("Nikon Camera (0x452)"))
    }

    /**
     * 型号原文的诊断形态：可打印 ASCII 原样、其余换成 `U+XXXX`。
     * 立项起因是日志里那句 `device_model name=Nikon Z 502` —— 只记归一化结果的话，
     * 「机身到底自报了什麼」这条线索在写入日志前就被吃掉了，之后只能靠猜。
     */
    @Test
    fun `原文诊断串把非 ASCII 字符显成码点`() {
        assertEquals("Z 50U+2161", CameraModelCatalog.describeRaw("Z 50Ⅱ"))
        assertEquals("Z 6U+2162", CameraModelCatalog.describeRaw("Z 6Ⅲ"))
        assertEquals("Z 50II", CameraModelCatalog.describeRaw("Z 50II"))   // 纯 ASCII 原样
        assertEquals("Z 502", CameraModelCatalog.describeRaw("Z 502"))     // 若真是 502，就该看见 502
        assertEquals("ZU+0001", CameraModelCatalog.describeRaw("Z\u0001")) // 控制字符不外发
        assertEquals("null", CameraModelCatalog.describeRaw(null))
    }
}
