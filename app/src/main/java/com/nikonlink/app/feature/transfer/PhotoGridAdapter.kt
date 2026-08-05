package com.nikonlink.app.feature.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ItemPhotoGridBinding

/**
 * 相册风格照片网格适配器（黑白设计语言）
 * 3 列网格 + 右上角黑色对勾 + 左下角格式角标
 * 交互：点击进入全屏预览，长按进入多选模式
 */
class PhotoGridAdapter(
    private val onItemClick: (CameraFile, Int) -> Unit,
    private val onItemLongClick: (CameraFile) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.GridViewHolder>() {

    private var items: List<CameraFile> = emptyList()
    private var selected: Set<Int> = emptySet()
    private var thumbnails: Map<Int, ByteArray> = emptyMap()
    /** 多选模式：显示对勾容器 */
    var multiSelectMode: Boolean = false

    /** Bitmap 解码缓存，避免滚动时重复解码 */
    private val bitmapCache = LruCache<Int, Bitmap>(64)

    fun submit(newItems: List<CameraFile>, newSelected: Set<Int>, newThumbs: Map<Int, ByteArray>) {
        val oldItems = items
        items = newItems
        selected = newSelected
        thumbnails = newThumbs

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].handle == items[newPos].handle
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos] == items[newPos] &&
                        (oldItems[oldPos].handle in selected) == (items[newPos].handle in selected) &&
                        thumbnails.containsKey(items[newPos].handle)
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemPhotoGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class GridViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundHandle = -1
        private var boundSelected = false

        fun bind(file: CameraFile, position: Int) {
            val handle = file.handle
            val isSelected = handle in selected
            val selectionChanged = boundHandle == handle && boundSelected != isSelected

            binding.checkContainer.visibility =
                if (isSelected || multiSelectMode) View.VISIBLE else View.GONE
            binding.checkImage.setImageResource(
                if (isSelected) R.drawable.ic_check else R.drawable.bg_check_circle_outline
            )
            if (isSelected) {
                binding.checkContainer.animate().cancel()
                binding.checkContainer.alpha = if (selectionChanged) 0.2f else 1f
                binding.checkContainer.scaleX = if (selectionChanged) 0.6f else 1f
                binding.checkContainer.scaleY = if (selectionChanged) 0.6f else 1f
                binding.checkContainer.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180).start()

                binding.viewSelectedMask.visibility = View.VISIBLE
                binding.viewSelectedMask.animate().cancel()
                binding.viewSelectedMask.alpha = if (selectionChanged) 0f else 1f
                binding.viewSelectedMask.animate().alpha(1f).setDuration(180).start()
                binding.root.alpha = 0.92f
            } else {
                binding.checkContainer.animate().cancel()
                binding.checkContainer.alpha = if (multiSelectMode) 0.35f else 0f
                binding.checkContainer.animate().alpha(if (multiSelectMode) 0.55f else 0f)
                    .setDuration(140).withEndAction {
                        if (!boundSelected && !multiSelectMode) {
                            binding.checkContainer.visibility =
                                View.GONE
                        }
                    }.start()
                binding.viewSelectedMask.animate().cancel()
                binding.viewSelectedMask.animate().alpha(0f).setDuration(140)
                    .withEndAction {
                        if (!boundSelected) binding.viewSelectedMask.visibility = View.GONE
                    }.start()
                binding.root.alpha = 1f
            }

            if (selectionChanged) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }

            binding.tvFormatBadge.text = when (file.format) {
                CameraFileFormat.JPEG -> "JPG"
                CameraFileFormat.RAW -> "RAW"
                CameraFileFormat.VIDEO -> "视频"
                else -> "文件"
            }

            // 缩略图实时预览
            val thumbData = thumbnails[handle]
            if (thumbData != null) {
                binding.progressThumb.visibility = View.GONE
                var bmp = bitmapCache.get(handle)
                if (bmp == null) {
                    bmp = BitmapFactory.decodeByteArray(thumbData, 0, thumbData.size)
                    if (bmp != null) bitmapCache.put(handle, bmp)
                }
                if (bmp != null) binding.ivThumb.setImageBitmap(bmp)
            } else {
                binding.progressThumb.visibility = View.VISIBLE
                binding.ivThumb.setImageBitmap(null)
            }

            binding.root.setOnClickListener { onItemClick(file, position) }
            binding.root.setOnLongClickListener {
                onItemLongClick(file)
                true
            }

            boundHandle = handle
            boundSelected = isSelected
        }
    }
}
