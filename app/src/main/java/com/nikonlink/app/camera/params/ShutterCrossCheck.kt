package com.nikonlink.app.camera.params

/**
 * 多样本互验（PRD v2.5 FR-10）。
 *
 * **为什么要互验**：快门次数只有一个真值，但我们的解析是"碰运气读 MakerNote"——
 * 命中第一张能解出来的样张就返回，等于把"解对了"和"碰巧读到个数"混为一谈。
 * 最新几张样张是同一台机身在几秒到几分钟内拍的，读数应当一致或单调递增；
 * 不一致就说明其中至少有一张解错了，这时给一个**区间**比给一个假装的精确值诚实。
 *
 * 纯函数，不碰解析器、不碰协议：喂进 [NikonShutterCountParser.Reading] 列表，吐出结论。
 */
object ShutterCrossCheck {

    /** 一次查询最多互验几张：再多就是拿用户的连接时间换一个更小的概率。 */
    const val MAX_SAMPLES = 3

    data class Verdict(
        /** 对外报出的次数：取各样本的最大值（快门计数只会增加，回退的那张必然是解错或旧档） */
        val count: Int,
        /** 样本间不一致时的区间；null = 各样本一致，可以直接报单值 */
        val range: IntRange?,
        /** 仅机械快门次数（0x0037）；样本里都没写则为 -1 */
        val mechanical: Int,
        /** 每个样本都通过恒等式校验，且彼此一致 */
        val verified: Boolean,
        val sampleCount: Int
    )

    fun decide(readings: List<NikonShutterCountParser.Reading>): Verdict? {
        val usable = readings.filter { it.shutterCount > 0 }
        if (usable.isEmpty()) return null
        val values = usable.map { it.shutterCount }
        val low = values.min()
        val high = values.max()
        val mechanical = usable.map { it.mechanicalCount }.filter { it > 0 }.maxOrMinusOne()
        return Verdict(
            count = high,
            range = if (low != high) low..high else null,
            mechanical = mechanical,
            verified = low == high && usable.all { it.verified },
            sampleCount = usable.size
        )
    }

    private fun List<Int>.maxOrMinusOne(): Int = maxOrNull() ?: -1
}
