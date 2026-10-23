package com.nikonlink.app.camera.gallery

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.app.R
import com.nikonlink.app.shared.ui.glass.NlGlass
import com.nikonlink.app.shared.ui.pressEffect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 下载队列面板（相册页底部「下载中 n/m」点开）。
 *
 * 列出本批任务及状态；操作：暂停 / 继续、重试失败、清除已结束、全部取消，
 * 单个排队中 / 失败的任务点「移除」。面板打开期间随队列实时刷新。
 */
internal class TransferQueueSheet(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val viewModel: TransferViewModel
) {
    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    private fun captionPx(): Float = context.resources.getDimensionPixelSize(R.dimen.font_caption).toFloat()

    fun show() {
        val sheet = NlGlass.sheet(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val title = TextView(context).apply {
            text = "下载队列"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimensionPixelSize(R.dimen.font_title).toFloat())
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.text_primary))
        }
        val summary = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, captionPx())
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(title)
        container.addView(summary)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnPause = actionButton("暂停")
        val btnRetry = actionButton("重试失败") { viewModel.retryFailedTransfers() }
        val btnClear = actionButton("清除已结束") { viewModel.clearFinishedTransfers() }
        val btnCancel = actionButton("全部取消") {
            viewModel.cancelAll()
            sheet.dismiss()
        }
        listOf(btnPause, btnRetry, btnClear, btnCancel).forEach { actions.addView(it) }
        container.addView(
            android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            }
        )

        val adapter = QueueAdapter { handle -> viewModel.removeTransfer(handle) }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            // 面板最多占半屏，长队列在面板内滚动
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                topMargin = dp(12)
            }
        }
        container.addView(list)
        val empty = TextView(context).apply {
            text = "队列为空"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, captionPx())
            setTextColor(context.getColor(R.color.text_tertiary))
            setPadding(0, dp(24), 0, dp(24))
            visibility = View.GONE
        }
        container.addView(empty)

        val maxListH = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val rowH = dp(52)

        val job = owner.lifecycleScope.launch {
            combine(viewModel.queue, viewModel.transferState) { q, s -> q to s }.collect { (q, state) ->
                val s = TransferQueueSummary.of(q)
                val paused = state is TransferState.Paused
                summary.text = when {
                    q.isEmpty() -> "没有下载任务"
                    else -> buildString {
                        append("共 ${s.total} 项 · 已完成 ${s.completed}")
                        if (s.failed > 0) append(" · 失败 ${s.failed}")
                        if (s.active > 0) append(" · 剩余 ${s.active}")
                        if (paused) append(" · 已暂停")
                    }
                }
                btnPause.text = if (paused) "继续" else "暂停"
                btnPause.isEnabled = s.active > 0
                btnPause.alpha = if (btnPause.isEnabled) 1f else 0.4f
                btnPause.setOnClickListener {
                    if (paused) viewModel.resumeTransfer() else viewModel.pauseTransfer()
                }
                setEnabled(btnRetry, s.failed > 0)
                setEnabled(btnClear, s.finished > 0)
                setEnabled(btnCancel, q.isNotEmpty())
                adapter.submitList(q)
                list.layoutParams = (list.layoutParams as LinearLayout.LayoutParams).apply {
                    height = (q.size * rowH).coerceAtMost(maxListH)
                }
                list.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                empty.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        sheet.setOnDismissListener { job.cancel() }
        sheet.setContentView(container)
        sheet.show()
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    private fun actionButton(label: String, onClick: (() -> Unit)? = null): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, captionPx())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            pressEffect()
            onClick?.let { cb -> setOnClickListener { cb() } }
        }

    private inner class QueueAdapter(
        private val onRemove: (Int) -> Unit
    ) : ListAdapter<TransferTask, QueueAdapter.Holder>(DIFF) {

        inner class Holder(
            root: LinearLayout,
            val name: TextView,
            val status: TextView,
            val remove: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, captionPx())
                setTextColor(context.getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val status = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(context.getColor(R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            texts.addView(name)
            texts.addView(status)
            val remove = TextView(context).apply {
                text = "移除"
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, captionPx())
                setTextColor(context.getColor(R.color.text_secondary))
                contentDescription = "从队列移除"
            }
            root.addView(texts)
            root.addView(remove)
            return Holder(root, name, status, remove)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val task = getItem(position)
            holder.name.text = task.file.fileName
            holder.status.text = when (task.status) {
                TransferTaskStatus.PENDING -> "排队中"
                TransferTaskStatus.DOWNLOADING -> "下载中"
                TransferTaskStatus.COMPLETED -> "已完成"
                TransferTaskStatus.FAILED -> "失败" + (task.failReason?.let { "：$it" } ?: "")
                TransferTaskStatus.CANCELLED -> "已取消"
            }
            holder.status.setTextColor(
                context.getColor(
                    if (task.status == TransferTaskStatus.FAILED) R.color.status_disconnected else R.color.text_secondary
                )
            )
            val removable = task.status == TransferTaskStatus.PENDING ||
                task.status == TransferTaskStatus.FAILED ||
                task.status == TransferTaskStatus.CANCELLED
            holder.remove.visibility = if (removable) View.VISIBLE else View.INVISIBLE
            holder.remove.setOnClickListener { onRemove(task.file.handle) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<TransferTask>() {
            override fun areItemsTheSame(a: TransferTask, b: TransferTask) = a.file.handle == b.file.handle
            override fun areContentsTheSame(a: TransferTask, b: TransferTask) =
                a.status == b.status && a.failReason == b.failReason && a.file.fileName == b.file.fileName
        }
    }
}
