package com.nikonlink.app.camera.gallery

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.app.databinding.ItemPhotoGridBinding

/**
 * 相册风格照片网格适配器（黑白设计语言）
 * 3 列网格 + 右上角黑色对勾 + 左下角格式角标
 * 交互：点击进入全屏预览，长按进入多选模式
 *
 * Bug修复: 旧版 submit() 在 DiffUtil 计算前就把 selected 换成新集合，
 * 导致新旧内容比较永远相等、不触发重绑定 —— 表现为第二张起无选中反馈。
 * 现在保留 oldSelected 参与比较，并用 payload 对每次勾选/取消播放独立动画。
 */
class PhotoGridAdapter(
    private val onItemClick: (CameraFile, Int) -> Unit,
    private val onItemLongClick: (CameraFile) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.GridViewHolder>() {

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val PAYLOAD_THUMB = "payload_thumb"
    }

    private var items: List<CameraFile> = emptyList()
    private var selected: Set<Int> = emptySet()
    private var thumbnails: Map<Int, ByteArray> = emptyMap()

    /** 多选模式：显示对勾容器 */
    var multiSelectMode: Boolean = false

    /** Bitmap 解码缓存，避免滚动时重复解码 */
    private val bitmapCache = LruCache<Int, Bitmap>(64)

    fun submit(newItems: List<CameraFile>, newSelected: Set<Int>, newThumbs: Map<Int, ByteArray>) {
        val oldItems = items
        // 关键: 先缓存旧状态，再赋值，DiffUtil 才能感知选中变化
        val oldSelected = selected
        val oldThumbs = thumbnails
        items = newItems
        selected = newSelected
        thumbnails = newThumbs

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].handle == items[newPos].handle
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val h = items[newPos].handle
                val selChanged = (h in oldSelected) != (h in newSelected)
                val thumbChanged = newThumbs.containsKey(h) && !oldThumbs.containsKey(h)
                return !selChanged && !thumbChanged && oldItems[oldPos] == items[newPos]
            }
            override fun getChangePayload(oldPos: Int, newPos: Int): Any? {
                val h = items[newPos].handle
                val selChanged = (h in oldSelected) != (h in newSelected)
                val thumbChanged = newThumbs.containsKey(h) && !oldThumbs.containsKey(h)
                return when {
                    selChanged && thumbChanged -> null // 全量刷新
                    selChanged -> PAYLOAD_SELECTION
                    thumbChanged -> PAYLOAD_THUMB
                    else -> null
                }
            }
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemPhotoGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(items[position], animate = false)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(items[position], animate = false)
            return
        }
        // 局部刷新: 选中变化播放勾选动画，缩略图变化只更新图片
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_SELECTION -> holder.applySelection(items[position].handle in selected, animate = true)
                PAYLOAD_THUMB -> holder.applyThumb(items[position].handle)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    inner class GridViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentAnimators: AnimatorSet? = null

        fun bind(file: CameraFile, animate: Boolean) {
            binding.tvFormatBadge.text = when (file.format) {
                CameraFileFormat.JPEG -> "JPG"
                CameraFileFormat.RAW -> "RAW"
                CameraFileFormat.VIDEO -> "视频"
                else -> "文件"
            }
            applySelection(file.handle in selected, animate)
            applyThumb(file.handle)

            binding.root.setOnClickListener { onItemClick(file, adapterPosition) }
            binding.root.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        /** 选中状态渲染 + 勾选/取消动画（每次切换都触发） */
        fun applySelection(isSelected: Boolean, animate: Boolean) {
            currentAnimators?.cancel()

            if (isSelected) {
                binding.checkContainer.visibility = View.VISIBLE
                binding.viewSelectedMask.visibility = View.VISIBLE
                binding.root.alpha = 0.92f
                if (animate) {
                    // 对勾: 缩放+淡入 0.15s；遮罩: 淡入
                    binding.checkContainer.scaleX = 0.5f
                    binding.checkContainer.scaleY = 0.5f
                    binding.checkContainer.alpha = 0f
                    binding.viewSelectedMask.alpha = 0f
                    currentAnimators = AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.checkContainer, View.SCALE_X, 0.5f, 1f),
                            ObjectAnimator.ofFloat(binding.checkContainer, View.SCALE_Y, 0.5f, 1f),
                            ObjectAnimator.ofFloat(binding.checkContainer, View.ALPHA, 0f, 1f),
                            ObjectAnimator.ofFloat(binding.viewSelectedMask, View.ALPHA, 0f, 1f),
                            ObjectAnimator.ofFloat(binding.root, View.ALPHA, 1f, 0.92f)
                        )
                        duration = 150
                        start()
                    }
                } else {
                    binding.checkContainer.scaleX = 1f
                    binding.checkContainer.scaleY = 1f
                    binding.checkContainer.alpha = 1f
                    binding.viewSelectedMask.alpha = 1f
                }
            } else {
                if (animate && binding.checkContainer.visibility == View.VISIBLE) {
                    // 取消勾选: 对勾淡出缩小后隐藏
                    currentAnimators = AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.checkContainer, View.SCALE_X, 1f, 0.5f),
                            ObjectAnimator.ofFloat(binding.checkContainer, View.SCALE_Y, 1f, 0.5f),
                            ObjectAnimator.ofFloat(binding.checkContainer, View.ALPHA, 1f, 0f),
                            ObjectAnimator.ofFloat(binding.viewSelectedMask, View.ALPHA, 1f, 0f),
                            ObjectAnimator.ofFloat(binding.root, View.ALPHA, 0.92f, 1f)
                        )
                        duration = 150
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                binding.checkContainer.visibility =
                                    if (multiSelectMode) View.INVISIBLE else View.GONE
                                binding.viewSelectedMask.visibility = View.GONE
                            }
                        })
                        start()
                    }
                } else {
                    binding.checkContainer.visibility =
                        if (multiSelectMode) View.INVISIBLE else View.GONE
                    binding.viewSelectedMask.visibility = View.GONE
                    binding.checkContainer.alpha = 1f
                    binding.viewSelectedMask.alpha = 1f
                    binding.root.alpha = 1f
                }
            }
        }

        /** 缩略图渲染（懒加载: 有数据才解码） */
        fun applyThumb(handle: Int) {
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
        }
    }
}
