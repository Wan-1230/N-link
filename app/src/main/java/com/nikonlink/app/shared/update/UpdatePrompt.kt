package com.nikonlink.app.shared.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.shared.common.AppEventLogger
import timber.log.Timber

/**
 * 更新弹窗（PRD「夸克网盘更新通道」§4.3 / §4.5）
 *
 * - 「夸克网盘下载（国内推荐）」全宽主按钮置顶（最高曝光位），「GitHub 更新」全宽次按钮，
 *   「查看完整日志 / 稍后」文字按钮；无对应链接时隐藏对应按钮
 * - [showIfNotShowing] 带进程级防重入：启动自动检查与设置页手动检查不会叠出两个弹窗
 * - [openUrl] 含「无浏览器时复制地址」兜底，设置页与弹窗按钮共用
 */
object UpdatePrompt {

    /** 进程内是否已有更新弹窗在展示：防止自动检查 + 手动检查叠加弹窗 */
    @Volatile
    private var showing = false

    /** 展示新版本弹窗；已有弹窗在展示时静默跳过，返回是否真正弹出 */
    fun showIfNotShowing(context: Context, eventLogger: AppEventLogger, result: UpdateResult.Available): Boolean {
        if (showing) return false
        val view = View.inflate(context, R.layout.dialog_update_available, null)
        view.findViewById<TextView>(R.id.tvUpdateVersion).text = "新版本：${result.versionLabel}"

        val meta = buildString {
            result.publishedAtLabel?.let { append("发布于 ").append(it) }
            result.quarkCode?.let {
                if (isNotEmpty()) append(" · ")
                append("提取码：").append(it).append("（打开夸克分享页时输入）")
            }
            if (result.versionUnknown) {
                if (isNotEmpty()) append("\n")
                append("（无法自动判断版本高低，请到发布页确认后再更新）")
            }
        }
        view.findViewById<TextView>(R.id.tvUpdateMeta).apply {
            text = meta
            visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.tvUpdateNotes).text = result.notes.ifBlank { "暂无更新说明。" }

        showing = true
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("发现新版本")
            .setView(view)
            .create()
        dialog.setOnDismissListener { showing = false }

        // 夸克通道：视觉主导位（全宽黑底主按钮），打开分享页
        view.findViewById<Button>(R.id.btnUpdateQuark).apply {
            if (result.quarkUrl == null) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    dialog.dismiss()
                    eventLogger.event(
                        "update_check",
                        "action" to "quark_open",
                        "version" to result.versionLabel
                    )
                    openUrl(context, eventLogger, result.quarkUrl)
                }
            }
        }
        view.findViewById<Button>(R.id.btnUpdateGithub).setOnClickListener {
            dialog.dismiss()
            openUrl(context, eventLogger, result.url)
        }
        view.findViewById<Button>(R.id.btnViewLog).apply {
            if (result.releaseUrl.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    dialog.dismiss()
                    openUrl(context, eventLogger, result.releaseUrl!!)
                }
            }
        }
        view.findViewById<Button>(R.id.btnLater).setOnClickListener { dialog.dismiss() }
        dialog.show()
        return true
    }

    /**
     * 跳转下载：优先浏览器打开；无浏览器等场景退化成对话框展示完整 URL 供复制。
     */
    fun openUrl(context: Context, eventLogger: AppEventLogger, url: String) {
        eventLogger.event("update_check", "action" to "open_url", "url" to url)
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }.onFailure { e ->
            Timber.w(e, "Open update url failed: $url")
        }.isSuccess
        if (opened) return

        MaterialAlertDialogBuilder(context)
            .setTitle("无法打开链接")
            .setMessage("请手动复制以下地址到浏览器打开：\n\n$url")
            .setPositiveButton("确定", null)
            .show()
    }
}
