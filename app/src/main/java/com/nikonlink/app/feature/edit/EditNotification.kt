package com.nikonlink.app.feature.edit

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nikonlink.app.NikonLinkApp
import com.nikonlink.app.R
import timber.log.Timber

/**
 * 传输完成通知（PRD-AI修图 5.1 入口③: 通知 Action「修图」直达编辑器）
 *
 * 日志来源: EditNotif 标签输出通知发送结果。
 */
object EditNotification {

    private const val TAG = "EditNotif"

    /** 与连接通知/其他通知的 ID 错开 */
    private const val ID_OFFSET = 1000

    /**
     * 单张照片传输完成后发送：内容点击与「修图」Action 均直达编辑器。
     * @param savedUri 已保存文件的 MediaStore Uri 字符串
     */
    fun postTransferComplete(
        context: Context,
        savedUri: String,
        fileName: String,
        fileHandle: Int
    ) {
        // Android 13+ 未授予通知权限时静默跳过（不阻断传输流程）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        runCatching {
            val editIntent = Intent(context, EditActivity::class.java).apply {
                putExtra(EditViewModel.EXTRA_SOURCE_URI, savedUri)
                putExtra(EditViewModel.EXTRA_SOURCE_NAME, fileName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                fileHandle,
                editIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NikonLinkApp.CHANNEL_TRANSFER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("已保存: $fileName")
                .setContentText("照片已保存到相册，可立即进行 AI 修图")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .addAction(0, "修图", pending)
                .build()

            NotificationManagerCompat.from(context)
                .notify(ID_OFFSET + (fileHandle and 0xFFFF), notification)
            Timber.tag(TAG).d("Transfer-complete notification posted: $fileName")
        }.onFailure {
            Timber.tag(TAG).w(it, "Post transfer notification failed")
        }
    }
}
