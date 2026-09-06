package com.nikonlink.app.capture

import com.nikonlink.app.device.ptp.PtpConstants

/**
 * B 门 / 长曝光链路的纯决策核心（可单测）。
 *
 * 参照实现：
 * - digiCamControl（NikonBase.cs）：B 门 = 快门值 0x500D 写 0xFFFFFFFF（需 M/S 档）+ 触发拍摄；
 *   收门 = 恢复原快门值（Nikon 模型：改快门值即结束曝光）。
 * - 0x9207 InitiateCaptureRecInMedia(0xFFFFFFFF, 0x0000)：无 AF 拍摄到卡，USB/WiFi 双通道通用，
 *   快门处于 Bulb 时该命令即开启曝光（digiCamControl Live View 拍照同款入口）。
 * - ZRelay `0xA200 BulbReleaseBusy`：收门时机身忙于写卡，需等待重试而非报错。
 *
 * 失效根因对照（v1.0.2 反馈）：旧链路裸发 0x100F InitiateOpenCapture——Z 系无线协议普遍不支持该
 * 操作码、且机身快门不在 Bulb 档时必然失败，失败又无任何反馈，用户感知为「点了没反应」。
 */
object BulbPolicy {

    /** 0x500D ExposureTime 的 B 门档 raw 值（digiCamControl：SetProperty 0xFFFFFFFF） */
    const val SHUTTER_BULB_RAW: Int = 0xFFFFFFFF.toInt()

    /** DeviceBusy / BulbReleaseBusy 的最大重试次数（与录像链路口径一致） */
    const val MAX_BUSY_RETRIES = 3

    /** busy 重试的等待间隔 (ms) */
    const val BUSY_RETRY_DELAY_MS = 500L

    /** 哪些响应码属于「稍等即可恢复」的忙态，可重试 */
    fun isBusy(code: Int): Boolean =
        code == PtpConstants.RESPONSE_DEVICE_BUSY ||
            code == PtpConstants.RESPONSE_NIKON_BULB_BUSY

    /** 通道抽象：标准 0x100E 的参数布局双通道不同（WiFi 带 storage/格式参数，USB 只带 storage） */
    enum class Channel { WIFI, USB }

    /** 一条开启曝光的候选命令 */
    data class StartCommand(
        val opcode: Int,
        val params: List<Int>,
        /** true = 0x100F OpenCapture 路径，收门必须走 0x1010 TerminateOpenCapture */
        val usesOpenCapture: Boolean,
        /** 供日志与提示区分策略名 */
        val label: String
    )

    /**
     * 开启曝光的多级尝试序列（逐级降级，任一级成功即进入曝光态）：
     * ① 0x9207 存储参数=0xFFFFFFFF（gphoto2 capture2 语义：no-AF + 存到卡）；
     * ② 0x9207 存储参数=真实存储 ID——v1.0.2 真机反馈"存储 ID 无效"：部分机身把
     *    该参数按**存储 ID** 解释且不接受 0xFFFFFFFF，需用 GetStorageIDs 的实际值重试；
     * ③ 0x100E 标准单张拍摄（老机型兜底）；
     * ④ 0x100F InitiateOpenCapture（仅部分 USB 会话支持，保留兜底）。
     */
    fun startPlan(channel: Channel, storageIds: List<Int> = emptyList()): List<StartCommand> = buildList {
        add(
            StartCommand(
                opcode = PtpConstants.OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                params = listOf(-1, 0),
                usesOpenCapture = false,
                label = "0x9207 RecInMedia(自动存储)"
            )
        )
        storageIds.filter { it != 0 && it != -1 }.forEach { id ->
            add(
                StartCommand(
                    opcode = PtpConstants.OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                    params = listOf(id, 0),
                    usesOpenCapture = false,
                    label = "0x9207 RecInMedia(存储=0x${id.toString(16)})"
                )
            )
        }
        add(
            StartCommand(
                opcode = PtpConstants.OP_INITIATE_CAPTURE,
                params = if (channel == Channel.WIFI) listOf(0, 0) else listOf(0),
                usesOpenCapture = false,
                label = "0x100E InitiateCapture"
            )
        )
        add(
            StartCommand(
                opcode = PtpConstants.OP_INITIATE_OPEN_CAPTURE,
                params = listOf(0),
                usesOpenCapture = true,
                label = "0x100F OpenCapture"
            )
        )
    }

    /** 一条收门命令 */
    data class StopCommand(
        val opcode: Int,
        val params: List<Int>,
        val label: String
    )

    /**
     * 收门命令降级序列（配合「恢复切档前快门值」之后使用）：
     * ① 0x920C(0,0) TerminateCapture —— gphoto2 `_put_Nikon_Bulb` 收门的权威实现
     *    （`ptp_nikon_terminatecapture(params, 0, 0)`），是 0x9207 长曝光的官方配套结束命令；
     * ② 0x1010(0) TerminateOpenCapture —— 0x100F 路径的配套，保留兜底。
     * v1.0.2 只有 0x1010 一条路，Z 系无线普遍不支持 → 收门必败 → 定时曝光远超设定值。
     */
    fun stopPlan(): List<StopCommand> = listOf(
        StopCommand(
            opcode = PtpConstants.OP_NIKON_TERMINATE_CAPTURE,
            params = listOf(0, 0),
            label = "0x920C TerminateCapture"
        ),
        StopCommand(
            opcode = PtpConstants.OP_TERMINATE_OPEN_CAPTURE,
            params = listOf(0),
            label = "0x1010 TerminateOpenCapture"
        )
    )

    /**
     * 失败原因中文化。[shutterSwitched] = 本次是否已把机身快门切到了 Bulb 档
     * （失败时要回告用户机身状态已被改动 / 或因档位不对被拒）。
     */
    fun describeRejection(code: Int, shutterSwitched: Boolean): String = when {
        code == PtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED ->
            "当前通道不支持远程 B 门，请改用 USB 有线连接后重试"
        code == PtpConstants.RESPONSE_ACCESS_DENIED ->
            "相机拒绝 B 门：请确认拨盘在 M/S 档、未回放照片且未连接电脑"
        code == PtpConstants.RESPONSE_STORE_FULL ->
            "存储卡已满，无法开始曝光"
        code == PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW ->
            "相机未处于实时取景状态，请先开启监看后重试"
        shutterSwitched ->
            "开启曝光失败：${PtpConstants.describeResponseCode(code)}，机身快门已切到 Bulb，请结束或手动恢复"
        else ->
            "开启曝光失败：${PtpConstants.describeResponseCode(code)}"
    }
}
