package com.nikonlink.app.feature.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.app.databinding.ItemPhotoGridBinding

/**
 * 相册风格照片网格适配器
 * 参考安卓相册悬浮布局：3 列网格 + 右上角选择框 + 实时缩略图预览
 */
class PhotoGridAdapter(
    private val onItemClick: (CameraFile) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.GridViewHolder>() {

    private var items: List<CameraFile> = emptyList()
    private var selected: Set<Int> = emptySet()
    private var thumbnails: Map<Int, ByteArray> = emptyMap()

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
                        newThumbs.containsKey(items[newPos].handle)
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemPhotoGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(items[position], animateSelection = true)
    }

    override fun getItemCount(): Int = items.size

    inner class GridViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundHandle = -1
        private var boundSelected = false

        fun bind(file: CameraFile, animateSelection: Boolean) {
            val handle = file.handle
            val isSelected = handle in selected
            val selectionChanged = animateSelection &&
                    boundHandle == handle &&
                    boundSelected != isSelected

            binding.cbSelect.isChecked = isSelected
            if (isSelected) {
                binding.viewSelectedMask.visibility = android.view.View.VISIBLE
                binding.viewSelectedMask.animate().cancel()
                binding.viewSelectedMask.alpha = if (selectionChanged) 0f else 1f
                binding.viewSelectedMask.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .start()
            } else {
                binding.viewSelectedMask.animate().cancel()
                binding.viewSelectedMask.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction {
                        if (!boundSelected) {
                            binding.viewSelectedMask.visibility = android.view.View.GONE
                        }
                    }
                    .start()
            }

            if (selectionChanged) {
                binding.cbSelect.scaleX = 0.72f
                binding.cbSelect.scaleY = 0.72f
                binding.cbSelect.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .start()
            }

            binding.tvFormatBadge.text = when (file.format) {
                CameraFileFormat.JPEG -> "JPG"
                CameraFileFormat.RAW -> "RAW"
                else -> "IMG"
            }

            // 缩略图实时预览
            val thumbData = thumbnails[handle]
            if (thumbData != null) {
                binding.progressThumb.visibility = android.view.View.GONE
                var bmp = bitmapCache.get(handle)
                if (bmp == null) {
                    bmp = BitmapFactory.decodeByteArray(thumbData, 0, thumbData.size)
                    if (bmp != null) bitmapCache.put(handle, bmp)
                }
                if (bmp != null) {
                    binding.ivThumb.setImageBitmap(bmp)
                }
            } else {
                binding.progressThumb.visibility = android.view.View.VISIBLE
                binding.ivThumb.setImageBitmap(null)
            }

            binding.root.setOnClickListener {
                it.animate().cancel()
                it.scaleX = 0.94f
                it.scaleY = 0.94f
                it.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160)
                    .start()
                onItemClick(file)
            }

            boundHandle = handle
            boundSelected = isSelected
        }
    }
}
