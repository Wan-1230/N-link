package com.nikonlink.app.camera.params

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Z 卡口常见镜头目录：只按相机已经上报的**焦段 + 两端最大光圈**做唯一匹配。
 *
 * 为什么不用 LensID：机身 0xD0E0 给的是不透明 ID，尼康没有公开映射表，
 * 第三方镜头还可能共用/伪造 ID；按 ID 猜品牌会把适马显示成尼康。
 * 按规格匹配的代价是覆盖范围有限，但命中时品牌/型号是确定正确的。
 *
 * 歧义策略与 [com.nikonlink.app.device.model.CameraModelCatalog] 一致：
 * 多条同规格（例如 35mm f/1.8 有原厂与唯卓仕）无法区分时不猜，返回 null，
 * 由调用方回退到“只显示规格”。
 */
object LensCatalog {

    data class Entry(
        val brand: String,
        val model: String,
        val focalMinX100: Int,
        val focalMaxX100: Int,
        val apMinX100: Int,
        val apMaxX100: Int
    )

    private val ENTRIES = listOf(
        Entry("Nikon", "NIKKOR Z 14-24mm f/2.8 S", 1400, 2400, 280, 280),
        Entry("Nikon", "NIKKOR Z 14-30mm f/4 S", 1400, 3000, 400, 400),
        Entry("Nikon", "NIKKOR Z 17-28mm f/2.8", 1700, 2800, 280, 280),
        Entry("Nikon", "NIKKOR Z 20mm f/1.8 S", 2000, 2000, 180, 180),
        Entry("Nikon", "NIKKOR Z 24mm f/1.8 S", 2400, 2400, 180, 180),
        Entry("Nikon", "NIKKOR Z 24-50mm f/4-6.3", 2400, 5000, 400, 630),
        Entry("Nikon", "NIKKOR Z 24-70mm f/2.8 S", 2400, 7000, 280, 280),
        Entry("Nikon", "NIKKOR Z 24-70mm f/4 S", 2400, 7000, 400, 400),
        Entry("Nikon", "NIKKOR Z 24-120mm f/4 S", 2400, 12000, 400, 400),
        Entry("Nikon", "NIKKOR Z 24-200mm f/4-6.3 VR", 2400, 20000, 400, 630),
        Entry("Nikon", "NIKKOR Z 24-240mm f/4-6.3 VR", 2400, 24000, 400, 630),
        Entry("Nikon", "NIKKOR Z 28-75mm f/2.8", 2800, 7500, 280, 280),
        Entry("Nikon", "NIKKOR Z 28-400mm f/4-8 VR", 2800, 40000, 400, 800),
        Entry("Nikon", "NIKKOR Z 35mm f/1.4", 3500, 3500, 140, 140),
        Entry("Nikon", "NIKKOR Z 35mm f/1.8 S", 3500, 3500, 180, 180),
        Entry("Nikon", "NIKKOR Z 40mm f/2", 4000, 4000, 200, 200),
        Entry("Nikon", "NIKKOR Z 50mm f/1.2 S", 5000, 5000, 120, 120),
        Entry("Nikon", "NIKKOR Z 50mm f/1.8 S", 5000, 5000, 180, 180),
        Entry("Nikon", "NIKKOR Z 58mm f/0.95 S Noct", 5800, 5800, 95, 95),
        Entry("Nikon", "NIKKOR Z 85mm f/1.2 S", 8500, 8500, 120, 120),
        Entry("Nikon", "NIKKOR Z 85mm f/1.8 S", 8500, 8500, 180, 180),
        Entry("Nikon", "NIKKOR Z MC 50mm f/2.8", 5000, 5000, 280, 280),
        Entry("Nikon", "NIKKOR Z MC 105mm f/2.8 VR S", 10500, 10500, 280, 280),
        Entry("Nikon", "NIKKOR Z 70-180mm f/2.8", 7000, 18000, 280, 280),
        Entry("Nikon", "NIKKOR Z 70-200mm f/2.8 VR S", 7000, 20000, 280, 280),
        Entry("Nikon", "NIKKOR Z 100-400mm f/4.5-5.6 VR S", 10000, 40000, 450, 560),
        Entry("Nikon", "NIKKOR Z 180-600mm f/5.6-6.3 VR", 18000, 60000, 560, 630),
        Entry("Nikon", "NIKKOR Z 400mm f/2.8 TC VR S", 40000, 40000, 280, 280),
        Entry("Nikon", "NIKKOR Z 400mm f/4.5 VR S", 40000, 40000, 450, 450),
        Entry("Nikon", "NIKKOR Z 600mm f/4 TC VR S", 60000, 60000, 400, 400),
        Entry("Nikon", "NIKKOR Z 600mm f/6.3 VR S", 60000, 60000, 630, 630),
        Entry("Nikon", "NIKKOR Z 800mm f/6.3 VR S", 80000, 80000, 630, 630),
        Entry("Tamron", "Tamron 35-150mm F/2-2.8 Di III VXD", 3500, 15000, 200, 280),
        Entry("Tamron", "Tamron 70-300mm F/4.5-6.3 Di III RXD", 7000, 30000, 450, 630),
        Entry("Sigma", "Sigma 18-50mm F2.8 DC DN Contemporary", 1800, 5000, 280, 280),
        Entry("Viltrox", "Viltrox AF 16mm F1.8 Z", 1600, 1600, 180, 180),
        Entry("Viltrox", "Viltrox AF 27mm F1.2 Pro Z", 2700, 2700, 120, 120),
        Entry("Viltrox", "Viltrox AF 35mm F1.8 II Z", 3500, 3500, 180, 180),
        Entry("Viltrox", "Viltrox AF 75mm F1.2 Pro Z", 7500, 7500, 120, 120),
        Entry("Laowa", "Laowa 15mm f/4 Wide Angle Macro Z", 1500, 1500, 400, 400),
        Entry("Laowa", "Laowa 25mm f/2.8 2.5-5X Ultra Macro Z", 2500, 2500, 280, 280)
    )

    private val BY_SPEC: Map<List<Int>, List<Entry>> = ENTRIES.groupBy {
        listOf(it.focalMinX100, it.focalMaxX100, it.apMinX100, it.apMaxX100)
    }

    /**
     * 唯一命中才返回型号；无匹配或同规格多支镜头时返回 null。
     * @param focalMinMm 机身上报的广角端焦距（mm）
     * @param focalMaxMm 长焦端焦距（mm，定焦与广角端相同）
     * @param apMinX100 广角端最大光圈 ×100
     * @param apMaxX100 长焦端最大光圈 ×100（定焦与广角端相同）
     */
    fun displayName(
        focalMinMm: Double?,
        focalMaxMm: Double?,
        apMinX100: Int?,
        apMaxX100: Int?
    ): String? {
        if (focalMinMm == null || focalMaxMm == null || apMinX100 == null || apMaxX100 == null) {
            return null
        }
        if (focalMinMm <= 0 || focalMaxMm <= 0 || apMinX100 <= 0 || apMaxX100 <= 0) return null
        val key = listOf(
            focalMinMm.roundToInt() * 100,
            focalMaxMm.roundToInt() * 100,
            apMinX100,
            apMaxX100
        )
        return BY_SPEC[key]?.singleOrNull()?.model
    }

    /**
     * 用最近一张照片的 EXIF LensMake/LensModel 拼展示名。
     *
     * 为什么需要：0xD0E0–0xD0E6 只给不透明 ID 与规格，规格表不可能覆盖所有副厂/手动镜头；
     * Nikon 也不通过 PTP 暴露镜头名字符串。机身写入照片 EXIF 的 LensModel 才是权威来源
     * （机身写入照片的 EXIF/MakerNote 才是权威来源）。
     *
     * 品牌不重复：模型已含 NIKKOR/Tamron 等字样时原样返回；否则补上规范化品牌。
     * LensMake 常见值有 "NIKON CORPORATION" / "TAMRON" 等，统一成常用写法。
     */
    fun composeDisplayName(make: String?, model: String?): String? {
        val modelText = model?.trim().orEmpty()
        val makeText = make?.trim().orEmpty()
        if (modelText.isBlank()) {
            return makeText.takeIf { it.isNotBlank() }?.let(::normalizeBrand)
        }
        if (makeText.isBlank()) return modelText
        val brand = normalizeBrand(makeText)
        val lowerModel = modelText.lowercase(Locale.ROOT)
        if (lowerModel.contains(brand.lowercase(Locale.ROOT))) return modelText
        if (brand == "Nikon" && lowerModel.contains("nikkor")) return modelText
        return "$brand $modelText"
    }

    private fun normalizeBrand(make: String): String {
        val lower = make.lowercase(Locale.ROOT)
        return when {
            "nikon" in lower -> "Nikon"
            "tamron" in lower -> "Tamron"
            "sigma" in lower -> "Sigma"
            "viltrox" in lower -> "Viltrox"
            "laowa" in lower || "venus optics" in lower -> "Laowa"
            "zeiss" in lower -> "Zeiss"
            "samyang" in lower || "rokinon" in lower -> "Samyang"
            "ttartisan" in lower -> "TTArtisan"
            "7artisans" in lower -> "7Artisans"
            "meike" in lower -> "Meike"
            else -> make.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
