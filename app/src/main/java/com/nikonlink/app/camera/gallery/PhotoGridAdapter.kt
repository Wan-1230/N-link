package com.nikonlink.app.camera.gallery

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ItemPhotoDateHeaderBinding
import com.nikonlink.app.databinding.ItemPhotoGridBinding
import com.nikonlink.app.shared.ui.pressEffect
import java.util.Calendar
import java.util.Locale

/**
 * 网格里的一行：要么是日期分组标题，要么是一张照片。
 *
 * 用密封接口而不是两套列表，是为了让 DiffUtil 对「标题 + 照片」这一整条
 * 扁平序列统一计算，避免分组变化时的下标错位。
 */
sealed interface PhotoGridItem {

    /** 日期分组标题行 */
    data class DateHeader(
        /** 分组稳定键（如 20260903 / unknown），DiffUtil 用 */
        val key: String,
        /** 展示用的日期文案（今天 / 昨天 / 2026年9月3日 / 未知日期） */
        val label: String,
        /** 该分组下全部照片的 handle，供整组全选 / 取消全选 */
        val handles: List<Int>
    ) : PhotoGridItem

    /** 一个照片格子 */
    data class Photo(val file: CameraFile) : PhotoGridItem
}

/**
 * 相册风格照片网格适配器（黑白设计语言）
 * 3 列网格 + 右上角黑色对勾 + 左下角格式角标
 * 交互：点击进入全屏预览，长按进入多选模式
 *
 * 性能优化: Bitmap 统一由 ThumbnailCache（内存 LRU + 磁盘）管理，
 * 不再持有自己的解码缓存；缩略图按可见性按需加载（onRequestThumb），
 * 解码全部在后台线程完成，主线程只做 setImageBitmap。
 *
 * Bug修复: 旧版 submit() 在 DiffUtil 计算前就把 selected 换成新集合，
 * 导致新旧内容比较永远相等、不触发重绑定 —— 表现为第二张起无选中反馈。
 * 现在保留 oldSelected 参与比较，并用 payload 对每次勾选/取消播放独立动画。
 *
 * 按日期分组: [submit] 传入 groupByDate = true 时，会在相邻同日的照片前插入
 * [PhotoGridItem.DateHeader] 标题行，标题右侧提供整组全选 / 取消全选。
 * 标题行需要在 3 列网格里占满整行，由调用方给 GridLayoutManager 配
 * SpanSizeLookup（见 [isHeaderAt]）。
 */
class PhotoGridAdapter(
    private val cache: ThumbnailCache,
    private val onItemClick: (CameraFile, Int) -> Unit,
    /** 长按回调带 adapterPosition：模块 4.4 滑动多选以它为锚点 */
    private val onItemLongClick: (CameraFile, Int) -> Unit,
    private val onRequestThumb: (CameraFile) -> Unit,
    private val onToggleGroupSelection: (List<Int>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val PAYLOAD_THUMB = "payload_thumb"
        private const val PAYLOAD_MARK = "payload_mark"
        private const val PAYLOAD_DOWNLOADED = "payload_downloaded"

        private const val TYPE_PHOTO = 0
        private const val TYPE_DATE_HEADER = 1

        /** 取不到拍摄时间的文件的分组键与标题 */
        private const val KEY_UNKNOWN_DATE = "unknown"
        private const val UNKNOWN_DATE_LABEL = "未知日期"
    }

    private var items: List<PhotoGridItem> = emptyList()
    private var selected: Set<Int> = emptySet()
    private var loadedThumbs: Set<Int> = emptySet()
    private var marked: Set<Int> = emptySet()
    private var downloaded: Set<Int> = emptySet()

    /** 当前展示的照片列表（不含日期标题）。排序变更后供 Fragment 定位锚点项用 */
    val currentList: List<CameraFile>
        get() = items.mapNotNull { (it as? PhotoGridItem.Photo)?.file }

    /** 多选模式：显示对勾容器 */
    var multiSelectMode: Boolean = false

    /**
     * 快速滑动中（拖拽/惯性滚动）标志。
     *
     * 花屏与动画堆叠的主因：网格高速滚动时 ViewHolder 被疯狂复用，
     * 每个复用都触发一次 150ms 的勾选 AnimatorSet；这些动画在复用瞬间被 cancel，
     * 但 `AnimatorListenerAdapter.onAnimationEnd` 在 cancel 后**仍会被调用**，
     * 于是"上一张照片的取消动画"把"这一张照片刚设好的对勾"又隐藏掉 ——
     * 表现为对勾闪烁、遮罩半透明残留、整块格子发灰（花屏）。
     *
     * 置 true 时：不再启动任何动画，直接把属性设到终值；同时暂停缩略图请求，
     * 滚动停止后由 Fragment 统一为可见项补请求。
     */
    @Volatile
    var fastScrolling: Boolean = false
        private set

    /** 供 Fragment 的滚动监听调用：快速滑动期间关动画 + 暂停缩略图请求。 */
    fun setFastScrolling(fast: Boolean) {
        fastScrolling = fast
    }

    fun submit(
        newItems: List<CameraFile>,
        newSelected: Set<Int>,
        newThumbs: Set<Int>,
        newMarked: Set<Int> = marked,
        newDownloaded: Set<Int> = downloaded,
        groupByDate: Boolean = false
    ) {
        val newFlat = buildItems(newItems, groupByDate)
        val oldItems = items
        // 关键: 先缓存旧状态，再赋值，DiffUtil 才能感知选中变化
        val oldSelected = selected
        val oldThumbs = loadedThumbs
        val oldMarked = marked
        val oldDownloaded = downloaded
        items = newFlat
        selected = newSelected
        loadedThumbs = newThumbs
        marked = newMarked
        downloaded = newDownloaded

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = items.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = oldItems[oldPos]
                val newItem = items[newPos]
                return when {
                    oldItem is PhotoGridItem.Photo && newItem is PhotoGridItem.Photo ->
                        oldItem.file.handle == newItem.file.handle
                    oldItem is PhotoGridItem.DateHeader && newItem is PhotoGridItem.DateHeader ->
                        oldItem.key == newItem.key
                    else -> false
                }
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = oldItems[oldPos]
                val newItem = items[newPos]
                return when {
                    oldItem is PhotoGridItem.Photo && newItem is PhotoGridItem.Photo -> {
                        val h = newItem.file.handle
                        val selChanged = (h in oldSelected) != (h in newSelected)
                        val thumbChanged = newThumbs.contains(h) && !oldThumbs.contains(h)
                        val markChanged = (h in oldMarked) != (h in newMarked)
                        val downloadedChanged = (h in oldDownloaded) != (h in newDownloaded)
                        !selChanged && !thumbChanged && !markChanged && !downloadedChanged &&
                            oldItem.file == newItem.file
                    }
                    oldItem is PhotoGridItem.DateHeader && newItem is PhotoGridItem.DateHeader ->
                        oldItem.label == newItem.label &&
                            oldItem.handles == newItem.handles &&
                            isGroupAllSelected(oldItem, oldSelected) ==
                            isGroupAllSelected(newItem, newSelected)
                    else -> false
                }
            }

            override fun getChangePayload(oldPos: Int, newPos: Int): Any? {
                val oldItem = oldItems[oldPos]
                val newItem = items[newPos]
                return when {
                    oldItem is PhotoGridItem.Photo && newItem is PhotoGridItem.Photo -> {
                        val h = newItem.file.handle
                        val selChanged = (h in oldSelected) != (h in newSelected)
                        val thumbChanged = newThumbs.contains(h) && !oldThumbs.contains(h)
                        val markChanged = (h in oldMarked) != (h in newMarked)
                        val downloadedChanged = (h in oldDownloaded) != (h in newDownloaded)
                        // 多维变化叠加时退化为全量重绑定（null payload）
                        val changed =
                            listOf(selChanged, thumbChanged, markChanged, downloadedChanged).count { it }
                        if (changed > 1) return null
                        when {
                            selChanged -> PAYLOAD_SELECTION
                            thumbChanged -> PAYLOAD_THUMB
                            markChanged -> PAYLOAD_MARK
                            downloadedChanged -> PAYLOAD_DOWNLOADED
                            else -> null
                        }
                    }
                    // 标题行的「全选 / 取消全选」文案随组内选中状态翻转
                    oldItem is PhotoGridItem.DateHeader && newItem is PhotoGridItem.DateHeader ->
                        if (isGroupAllSelected(oldItem, oldSelected) !=
                            isGroupAllSelected(newItem, newSelected)
                        ) PAYLOAD_SELECTION else null
                    else -> null
                }
            }
        }).dispatchUpdatesTo(this)
    }

    /** 该分组是否已整组选中 */
    private fun isGroupAllSelected(header: PhotoGridItem.DateHeader, from: Set<Int>): Boolean =
        header.handles.isNotEmpty() && header.handles.all { it in from }

    /** 该位置是否为日期标题行（供 SpanSizeLookup 判定占满整行） */
    fun isHeaderAt(position: Int): Boolean =
        items.getOrNull(position) is PhotoGridItem.DateHeader

    /** 该位置对应的照片；标题行返回 null（供滚动锚点定位） */
    fun itemAt(position: Int): CameraFile? =
        (items.getOrNull(position) as? PhotoGridItem.Photo)?.file

    /** 照片在扁平序列（含标题行）中的位置，找不到返回 -1 */
    fun indexOfHandle(handle: Int): Int =
        items.indexOfFirst { it is PhotoGridItem.Photo && it.file.handle == handle }

    /**
     * 构造扁平展示序列：按日期分组时在「相邻同日」的照片段前插入标题行。
     *
     * 只合并相邻同日的连续段，不整体重排 —— 这样既尊重用户选定的排序，
     * 又能在按拍摄时间排序（默认）时天然按日成块。
     */
    private fun buildItems(files: List<CameraFile>, groupByDate: Boolean): List<PhotoGridItem> {
        if (!groupByDate) return files.map { PhotoGridItem.Photo(it) }

        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        // 复用的临时 Calendar：单线程顺序执行，避免每个文件都新建实例
        val scratch = Calendar.getInstance()

        val keys = ArrayList<String>(files.size)
        val labels = ArrayList<String>(files.size)
        for (file in files) {
            val millis = file.captureTimeMillis
            if (millis == null) {
                keys += KEY_UNKNOWN_DATE
                labels += UNKNOWN_DATE_LABEL
            } else {
                scratch.timeInMillis = millis
                keys += String.format(
                    Locale.US, "%04d%02d%02d",
                    scratch.get(Calendar.YEAR),
                    scratch.get(Calendar.MONTH) + 1,
                    scratch.get(Calendar.DAY_OF_MONTH)
                )
                labels += formatDateLabel(scratch, today, yesterday)
            }
        }

        val result = ArrayList<PhotoGridItem>(files.size + 8)
        var i = 0
        while (i < files.size) {
            val key = keys[i]
            var j = i + 1
            while (j < files.size && keys[j] == key) j++
            val handles = ArrayList<Int>(j - i)
            for (k in i until j) handles += files[k].handle
            result += PhotoGridItem.DateHeader(key = key, label = labels[i], handles = handles)
            for (k in i until j) result += PhotoGridItem.Photo(files[k])
            i = j
        }
        return result
    }

    /** 日期标题文案：今天 / 昨天 / 同年省略年份 */
    private fun formatDateLabel(cal: Calendar, today: Calendar, yesterday: Calendar): String {
        if (isSameDay(cal, today)) return "今天"
        if (isSameDay(cal, yesterday)) return "昨天"
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return if (year == today.get(Calendar.YEAR)) "${month}月${day}日"
        else "${year}年${month}月${day}日"
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    override fun getItemViewType(position: Int): Int =
        if (items[position] is PhotoGridItem.DateHeader) TYPE_DATE_HEADER else TYPE_PHOTO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DATE_HEADER) {
            DateHeaderViewHolder(
                ItemPhotoDateHeaderBinding.inflate(inflater, parent, false)
            )
        } else {
            GridViewHolder(ItemPhotoGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PhotoGridItem.Photo -> {
                (holder as GridViewHolder).bind(item.file, animate = false)
                // 快速滑动期间不发起缩略图请求：这些请求绝大多数在停下前就已滚出
                // 视野，白白挤占 PTP 通道（相机侧串行处理），反而让停下后真正
                // 需要的那一批排到队尾 → 停在屏幕上却迟迟不出图。
                // 停止滚动后由 Fragment 统一为可见项补请求。
                if (!fastScrolling && !cache.hasInMemory(item.file.handle)) {
                    onRequestThumb(item.file)
                }
            }
            is PhotoGridItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        // 局部刷新: 选中变化播放勾选动画，缩略图变化只更新图片
        when (val item = items[position]) {
            is PhotoGridItem.Photo -> {
                val viewHolder = holder as GridViewHolder
                payloads.forEach { payload ->
                    when (payload) {
                        PAYLOAD_SELECTION -> viewHolder.applySelection(
                            item.file.handle in selected,
                            animate = true
                        )
                        PAYLOAD_THUMB -> viewHolder.applyThumb(item.file)
                        PAYLOAD_MARK -> viewHolder.applyMark(item.file.handle in marked)
                        PAYLOAD_DOWNLOADED -> viewHolder.applyDownloaded(item.file.handle in downloaded)
                    }
                }
            }
            is PhotoGridItem.DateHeader -> {
                val viewHolder = holder as DateHeaderViewHolder
                if (payloads.contains(PAYLOAD_SELECTION)) {
                    viewHolder.applySelection(isGroupAllSelected(item, selected))
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /** 日期分组标题行 */
    inner class DateHeaderViewHolder(
        private val binding: ItemPhotoDateHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.tvSelectGroup.pressEffect()
        }

        fun bind(header: PhotoGridItem.DateHeader) {
            binding.tvDate.text = header.label
            binding.tvSelectGroup.setOnClickListener { onToggleGroupSelection(header.handles) }
            applySelection(isGroupAllSelected(header, selected))
        }

        /** 组内全选时按钮反色，文案切成「取消全选」 */
        fun applySelection(allSelected: Boolean) {
            binding.tvSelectGroup.text =
                itemView.context.getString(
                    if (allSelected) R.string.album_group_deselect_all else R.string.album_group_select_all
                )
            binding.tvSelectGroup.setBackgroundResource(
                if (allSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            binding.tvSelectGroup.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (allSelected) R.color.on_primary else R.color.text_primary
                )
            )
        }
    }

    inner class GridViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentAnimators: AnimatorSet? = null

        /**
         * 当前绑定的 handle。动画结束回调里用它做"身份校验"：
         * ViewHolder 已被复用到别的照片时，旧动画的收尾动作必须作废，
         * 否则会把新照片的 UI 状态改掉（对勾消失/遮罩残留 = 花屏）。
         */
        private var boundHandle: Int = -1

        /** 取消在途动画并摘掉监听器，杜绝 cancel 后的残留回调。 */
        private fun cancelAnimators() {
            currentAnimators?.let { animator ->
                animator.removeAllListeners()
                animator.cancel()
            }
            currentAnimators = null
        }

        /** 把所有可能被动画改写的属性一次性复位，消除复用带来的中间态残影。 */
        private fun resetAnimatableProperties() {
            binding.root.alpha = 1f
            binding.checkContainer.alpha = 1f
            binding.checkContainer.scaleX = 1f
            binding.checkContainer.scaleY = 1f
            binding.viewSelectedMask.alpha = 1f
        }

        fun bind(file: CameraFile, animate: Boolean) {
            cancelAnimators()
            resetAnimatableProperties()
            boundHandle = file.handle
            binding.tvFormatBadge.text = when (file.format) {
                CameraFileFormat.JPEG -> "JPG"
                CameraFileFormat.RAW -> "RAW"
                CameraFileFormat.VIDEO -> "视频"
                else -> "文件"
            }
            applySelection(file.handle in selected, animate)
            applyThumb(file)
            applyMark(file.handle in marked)
            applyDownloaded(file.handle in downloaded)

            binding.root.setOnClickListener { onItemClick(file, adapterPosition) }
            binding.root.setOnLongClickListener {
                onItemLongClick(file, adapterPosition)
                true
            }
        }

        /** F1：星形标记角标（常驻，非多选态也可见） */
        fun applyMark(isMarked: Boolean) {
            binding.ivMarkBadge.visibility = if (isMarked) View.VISIBLE else View.GONE
        }

        /** F2：已下载角标（右下角对勾胶囊） */
        fun applyDownloaded(isDownloaded: Boolean) {
            binding.tvDownloadedBadge.visibility = if (isDownloaded) View.VISIBLE else View.GONE
        }

        /** 选中状态渲染 + 勾选/取消动画（每次切换都触发） */
        fun applySelection(isSelected: Boolean, animate: Boolean) {
            // 取消在途动画并摘监听：cancel 后 onAnimationEnd 仍会回调的时代结束
            cancelAnimators()
            resetAnimatableProperties()
            // 快速滑动中一律走无动画直设终值，动画留到滚动停止后再播
            val shouldAnimate = animate && !fastScrolling
            val handleAtStart = boundHandle

            if (isSelected) {
                binding.checkContainer.visibility = View.VISIBLE
                binding.viewSelectedMask.visibility = View.VISIBLE
                binding.root.alpha = 0.92f
                if (shouldAnimate) {
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
                if (shouldAnimate && binding.checkContainer.visibility == View.VISIBLE) {
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
                                // 身份校验：ViewHolder 已被复用到别的照片时，
                                // 这张旧照片的收尾动作必须作废，否则会把新格子的
                                // 对勾/遮罩改掉（快速滑动花屏的直接来源）
                                if (boundHandle != handleAtStart) return
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

        /** 缩略图渲染：缓存命中直接展示，未命中显示进度占位并等待 payload 刷新 */
        fun applyThumb(file: CameraFile) {
            val bmp = cache.fromMemory(file.handle)
            if (bmp != null) {
                binding.progressThumb.visibility = View.GONE
                binding.ivThumb.setImageBitmap(bmp)
            } else {
                binding.progressThumb.visibility = View.VISIBLE
                binding.ivThumb.setImageBitmap(null)
            }
        }
    }
}
