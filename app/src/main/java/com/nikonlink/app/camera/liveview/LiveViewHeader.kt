package com.nikonlink.app.camera.liveview

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 尼康监看帧头（厂商头 + JPEG）的只读解析。
 *
 * 字段布局按 versioned LiveViewObject 结构逐字段核对：
 * - 0/2 major/minor；8/12 头长度、JPEG 长度（帧头内容从 8 开始）
 * - 16/18 wholeSize（ChangeAfArea 的坐标域）
 * - **20/22 displayWidth/Height、24/26 displayCenterX/Y**（不是 x/y/w/h！）
 * - 28/30 JPEG 宽高；41 focusDrive、42 focusState
 * - 43 AF 模式；44 AF 区域个数；45 选中索引；46 跟踪状态
 * - 48 起每个 AF 区域 4×u16 = (width, height, centerX, centerY)
 * - v1 头 512 字节、42 槽、旋转字节在 403；v2 头 1024 字节、96 槽、旋转在 839
 *
 * **AF 坐标为什么要减 display 区域**：AF 区域记录在 whole 像素域，但监看 JPEG
 * 只呈现 display 区域（可能比 whole 小并带偏移）。回显换算应为
 * `(centerX - displayLeft) / displayWidth`，直接除以 whole 会在有裁切时系统性偏移。
 *
 * 解析失败返回 null，调用方回退旧行为（whole/帧比例 + 只画点击位置）。
 */
object LiveViewHeader {

    enum class AfRole {
        PRIMARY_CONTROL,
        SECONDARY_CONTROL,
        SUBJECT_DETECTION,
        TRACKING,
        UNKNOWN
    }

    data class AfArea(
        val width: Int,
        val height: Int,
        val centerX: Int,
        val centerY: Int,
        val role: AfRole = AfRole.UNKNOWN
    ) {
        val isPlausible: Boolean get() = width > 0 && height > 0
    }

    /**
     * 相对**监看 JPEG 显示内容**的归一化矩形（0..1）。
     * 绘制侧直接乘 ImageView 里的图像矩形，不再关心 whole/display 的像素差。
     */
    data class NormalizedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    data class Info(
        val majorVersion: Int,
        val minorVersion: Int,
        val headerBytes: Int,
        val jpegWidth: Int,
        val jpegHeight: Int,
        val wholeWidth: Int,
        val wholeHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayCenterX: Int,
        val displayCenterY: Int,
        val rotation: Int,
        val focusAbilityState: Int,
        val focusDrive: Int,
        val focusState: Int,
        val afMode: Int,
        val afAreaCount: Int,
        val selectedAfIndex: Int,
        val trackingState: Int,
        val afAreas: List<AfArea>
    ) {
        val displayLeft: Int get() = displayCenterX - displayWidth / 2
        val displayTop: Int get() = displayCenterY - displayHeight / 2

        /** display 区域是否可用；不可用时所有换算退回 whole 域 */
        val hasUsableDisplayArea: Boolean
            get() = displayWidth > 0 && displayHeight > 0 &&
                displayLeft >= 0 && displayTop >= 0 &&
                displayLeft + displayWidth <= wholeWidth &&
                displayTop + displayHeight <= wholeHeight

        /**
         * 相机回显的 AF 区域 → 归一化显示矩形。
         *
         * 优先级为：tracking > subject > primary control >
         * secondary control > 未知。单点 AF 只有一个区域，走 primary 分支。
         */
        fun normalizedRect(area: AfArea): NormalizedRect? {
            if (!area.isPlausible) return null
            val domainW: Int
            val domainH: Int
            val originX: Int
            val originY: Int
            if (hasUsableDisplayArea) {
                domainW = displayWidth
                domainH = displayHeight
                originX = displayLeft
                originY = displayTop
            } else if (wholeWidth > 0 && wholeHeight > 0) {
                domainW = wholeWidth
                domainH = wholeHeight
                originX = 0
                originY = 0
            } else {
                return null
            }
            fun norm(value: Int, origin: Int, size: Int): Float =
                ((value - origin).toFloat() / size).coerceIn(0f, 1f)

            val left = norm(area.centerX - area.width / 2, originX, domainW)
            val top = norm(area.centerY - area.height / 2, originY, domainH)
            val right = norm(area.centerX + area.width / 2, originX, domainW)
            val bottom = norm(area.centerY + area.height / 2, originY, domainH)
            if (right <= left || bottom <= top) return null
            return NormalizedRect(left, top, right, bottom)
        }

        /** 当前应回显的 AF 区域；没有可用区域时返回 null。 */
        fun activeAfArea(): AfArea? {
            val plausible = afAreas.filter { it.isPlausible }
            if (plausible.isEmpty()) return null
            return plausible.firstOrNull { it.role == AfRole.TRACKING }
                ?: plausible.firstOrNull { it.role == AfRole.SUBJECT_DETECTION }
                ?: plausible.firstOrNull { it.role == AfRole.PRIMARY_CONTROL }
                ?: plausible.firstOrNull { it.role == AfRole.SECONDARY_CONTROL }
                ?: plausible.first()
        }

        /**
         * 点击位置（相对监看 JPEG 内容，0..1）→ ChangeAfArea 的 whole 坐标。
         * 与回显换算严格互逆：displayLeft + nx * displayWidth。
         */
        fun afCoordFromNormalized(nx: Float, ny: Float): Pair<Int, Int>? {
            val x: Float
            val y: Float
            val maxX: Int
            val maxY: Int
            if (hasUsableDisplayArea) {
                x = displayLeft + nx.coerceIn(0f, 1f) * displayWidth
                y = displayTop + ny.coerceIn(0f, 1f) * displayHeight
                maxX = wholeWidth
                maxY = wholeHeight
            } else if (wholeWidth > 0 && wholeHeight > 0) {
                x = nx.coerceIn(0f, 1f) * wholeWidth
                y = ny.coerceIn(0f, 1f) * wholeHeight
                maxX = wholeWidth
                maxY = wholeHeight
            } else {
                return null
            }
            return x.coerceIn(0f, (maxX - 1).toFloat()).toInt() to
                y.coerceIn(0f, (maxY - 1).toFloat()).toInt()
        }
    }

    private const val AF_AREA_OFFSET = 48
    private const val MAX_AREAS_V2 = 64
    private const val MAX_AREAS_V1 = 42
    private const val ROTATION_OFFSET_V1 = 403
    private const val ROTATION_OFFSET_V2 = 839

    fun parse(data: ByteArray): Info? {
        if (data.size < AF_AREA_OFFSET + 8) return null
        return runCatching {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val major = u16(buf, 0)
            val minor = u16(buf, 2)
            if (major !in 1..2) return null

            val wholeWidth = u16(buf, 16)
            val wholeHeight = u16(buf, 18)
            val displayWidth = u16(buf, 20)
            val displayHeight = u16(buf, 22)
            val displayCenterX = u16(buf, 24)
            val displayCenterY = u16(buf, 26)
            val jpegWidth = u16(buf, 28)
            val jpegHeight = u16(buf, 30)
            if (jpegWidth !in 1..20000 || jpegHeight !in 1..20000) return null
            if (wholeWidth !in 1..20000 || wholeHeight !in 1..20000) return null

            // 8/12 是「头长度 / JPEG 长度」。部分 mock/老固件不带长度前缀时按 major 取默认。
            val declaredHeaderBytes = u32(buf, 8)
            val headerBytes = when (declaredHeaderBytes) {
                512, 1024 -> declaredHeaderBytes
                else -> if (major == 1) 512 else 1024
            }
            val maxAreas = if (major == 2) MAX_AREAS_V2 else MAX_AREAS_V1
            val declaredCount = u8(buf, 44)
            // v1 没有可靠的 count 语义时按满槽读，靠 isPlausible/边界过滤垃圾槽
            val count = when {
                declaredCount in 1..maxAreas -> declaredCount
                major == 1 -> MAX_AREAS_V1
                else -> 0
            }
            val headerEnd = 8 + headerBytes
            val required = AF_AREA_OFFSET + count * 8
            if (required > data.size || required > headerEnd) return null

            val afMode = u8(buf, 43)
            val selectedIndex = u8(buf, 45)
            val tracking = u8(buf, 46)
            val classificationReliable =
                count > 0 && selectedIndex in 0 until count &&
                    selectedIndex <= 34 && tracking in 0..2

            val areas = buildList {
                for (i in 0 until count) {
                    val off = AF_AREA_OFFSET + i * 8
                    val width = u16(buf, off)
                    val height = u16(buf, off + 2)
                    val centerX = u16(buf, off + 4)
                    val centerY = u16(buf, off + 6)
                    add(
                        AfArea(
                            width = width,
                            height = height,
                            centerX = centerX,
                            centerY = centerY,
                            role = classifyRole(
                                afMode = afMode,
                                index = i,
                                selectedIndex = selectedIndex,
                                trackingState = tracking,
                                reliable = classificationReliable
                            )
                        )
                    )
                }
            }

            val rotationOffset = if (major == 1) ROTATION_OFFSET_V1 else ROTATION_OFFSET_V2
            val rotation = if (data.size > rotationOffset) u8(buf, rotationOffset) else 0

            Info(
                majorVersion = major,
                minorVersion = minor,
                headerBytes = headerBytes,
                jpegWidth = jpegWidth,
                jpegHeight = jpegHeight,
                wholeWidth = wholeWidth,
                wholeHeight = wholeHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                displayCenterX = displayCenterX,
                displayCenterY = displayCenterY,
                rotation = rotation,
                focusAbilityState = u8(buf, 40),
                focusDrive = u8(buf, 41),
                focusState = u8(buf, 42),
                afMode = afMode,
                afAreaCount = count,
                selectedAfIndex = selectedIndex,
                trackingState = tracking,
                afAreas = areas
            )
        }.getOrNull()
    }

    /**
     * 区域分类规则：0=OTHER / 1=SUBJECT / 2=AUTO_AREA；跟踪状态 0/1/2。
     * 字段不可信时全部标 UNKNOWN，由 [Info.activeAfArea] 退回「第一个有效区域」。
     */
    private fun classifyRole(
        afMode: Int,
        index: Int,
        selectedIndex: Int,
        trackingState: Int,
        reliable: Boolean
    ): AfRole {
        if (!reliable) return AfRole.UNKNOWN
        return when (afMode) {
            1 -> AfRole.SUBJECT_DETECTION
            2 -> if (index == selectedIndex && trackingState == 2) {
                AfRole.TRACKING
            } else {
                AfRole.SUBJECT_DETECTION
            }
            else -> if (index == selectedIndex) {
                AfRole.PRIMARY_CONTROL
            } else {
                AfRole.SECONDARY_CONTROL
            }
        }
    }

    private fun u16(buf: ByteBuffer, offset: Int): Int =
        buf.getShort(offset).toInt() and 0xFFFF

    private fun u32(buf: ByteBuffer, offset: Int): Int =
        buf.getInt(offset)

    private fun u8(buf: ByteBuffer, offset: Int): Int =
        buf.get(offset).toInt() and 0xFF
}
