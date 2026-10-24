package com.nikonlink.app.camera.params

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensCatalogTest {

    @Test
    fun `原厂变焦按规格唯一命中完整型号`() {
        assertEquals(
            "NIKKOR Z 24-70mm f/4 S",
            LensCatalog.displayName(24.0, 70.0, 400, 400)
        )
        assertEquals(
            "NIKKOR Z 100-400mm f/4.5-5.6 VR S",
            LensCatalog.displayName(100.0, 400.0, 450, 560)
        )
    }

    @Test
    fun `定焦与第三方镜头也能命中品牌型号`() {
        assertEquals(
            "NIKKOR Z 50mm f/1.8 S",
            LensCatalog.displayName(50.0, 50.0, 180, 180)
        )
        assertEquals(
            "Tamron 35-150mm F/2-2.8 Di III VXD",
            LensCatalog.displayName(35.0, 150.0, 200, 280)
        )
        assertEquals(
            "Sigma 18-50mm F2.8 DC DN Contemporary",
            LensCatalog.displayName(18.0, 50.0, 280, 280)
        )
        assertEquals(
            "Viltrox AF 75mm F1.2 Pro Z",
            LensCatalog.displayName(75.0, 75.0, 120, 120)
        )
    }

    @Test
    fun `同规格多支镜头不猜品牌`() {
        // NIKKOR Z 35mm f/1.8 S 与 Viltrox AF 35mm F1.8 II Z 规格相同
        assertNull(LensCatalog.displayName(35.0, 35.0, 180, 180))
    }

    @Test
    fun `目录外或数据缺失返回 null 由调用方回退规格`() {
        assertNull(LensCatalog.displayName(12.0, 24.0, 280, 280))
        assertNull(LensCatalog.displayName(null, 70.0, 400, 400))
        assertNull(LensCatalog.displayName(24.0, 70.0, null, 400))
        assertNull(LensCatalog.displayName(0.0, 0.0, 0, 0))
    }

    @Test
    fun `EXIF 型号已含品牌时原样显示`() {
        assertEquals(
            "NIKKOR Z 24-70mm f/4 S",
            LensCatalog.composeDisplayName("NIKON CORPORATION", "NIKKOR Z 24-70mm f/4 S")
        )
        assertEquals(
            "Tamron 35-150mm F/2-2.8 Di III VXD",
            LensCatalog.composeDisplayName("TAMRON", "Tamron 35-150mm F/2-2.8 Di III VXD")
        )
    }

    @Test
    fun `EXIF 型号缺品牌时补 LensMake`() {
        assertEquals(
            "Nikon Z 24-120mm f/4 S",
            LensCatalog.composeDisplayName("NIKON CORPORATION", "Z 24-120mm f/4 S")
        )
        assertEquals(
            "Sigma 56mm F1.4 DC DN",
            LensCatalog.composeDisplayName("SIGMA", "56mm F1.4 DC DN")
        )
        assertEquals("Viltrox", LensCatalog.composeDisplayName("VILTROX", null))
        assertEquals("Z 85mm f/1.8 S", LensCatalog.composeDisplayName(null, "Z 85mm f/1.8 S"))
    }
}
