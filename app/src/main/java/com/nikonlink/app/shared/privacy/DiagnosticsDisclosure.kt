package com.nikonlink.app.shared.privacy

/**
 * 诊断导出的**字段声明**（PRD v2.5 FR-08d）。
 *
 * 导出文件里真正有的字段是哪些，不能靠"我以为没记"。这份清单是照着代码里
 * `eventLogger.event(...)` 实际写出的 key 数出来的，新增字段时必须同步改这里 ——
 * 因为它同时也是给用户看的"这份东西里有没有我的隐私"的答案。
 *
 * 唯一真源在这里：指标块自己不再重复声明字段范围。
 */
object DiagnosticsDisclosure {

    /** [handoffs] 来自 `AppSettings.dataHandoffs()`；纯拼装，不读 Android 状态，便于单测。 */
    fun text(handoffs: List<Pair<DataHandoff, Boolean>>): String = buildString {
        appendLine("===== 导出内容声明（v2.5 FR-08d）=====")
        appendLine("本文件由三部分组成：① 本声明；② 指标基线；③ 原始事件日志（环形，最多 3 × 512KB）。")
        appendLine("崩溃记录 `crash.log` **不在**导出范围内。")
        appendLine()
        appendLine("【日志部分包含】")
        appendLine("  · 手机机型（Build.MODEL）与 ROM 家族")
        appendLine("  · 相机型号（USB 通道下取自 GetDeviceInfo）")
        appendLine("  · 相机 / 网关的 IP 地址与端口（`host=` / `port=`）—— 局域网地址，不是公网出口 IP")
        appendLine("  · 照片与视频的文件名、对象句柄、字节数、保存到相册后的路径（`name=` / `path=`）")
        appendLine("  · 连接分阶段耗时、失败原因码、重试次数、设置项变更")
        appendLine()
        appendLine("【日志部分不包含】")
        appendLine("  · GPS 坐标、WiFi SSID / BSSID / 密码、蓝牙 MAC、相机序列号")
        appendLine("  · 账号标识（QQ / 手机号 / 用户名）、任何照片内容本身（不打包图片）")
        appendLine()
        appendLine("【注意】文件名与保存路径会间接暴露拍摄日期与卡内编号，介意时请勿外发；")
        appendLine("      局域网 IP 会暴露家庭网段规划。需要的话可以先只发「指标基线」那一段。")
        appendLine()
        appendLine("【已登记的数据交接授权】（未授权 = 本机从未向该接收方传过数据）")
        if (handoffs.isEmpty()) appendLine("  （无）")
        handoffs.forEach { (handoff, granted) ->
            appendLine("  ${handoff.disclosureLine(granted)}")
        }
        appendLine()
    }
}
