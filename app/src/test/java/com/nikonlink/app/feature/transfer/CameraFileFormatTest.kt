package com.nikonlink.app.feature.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFileFormatTest {

    @Test
    fun `jpeg files are classified by format code and extension`() {
        assertEquals(CameraFileFormat.JPEG, classifyFormat(0x3801, "DSC_0001.JPG"))
        assertEquals(CameraFileFormat.JPEG, classifyFormat(0x0000, "DSC_0002.jpg"))
    }

    @Test
    fun `raw files are classified by nikon extension`() {
        assertEquals(CameraFileFormat.RAW, classifyFormat(0xB103, "DSC_0001.NEF"))
        assertEquals(CameraFileFormat.RAW, classifyFormat(0x0000, "DSC_0001.nef"))
        assertEquals(CameraFileFormat.RAW, classifyFormat(0x0000, "DSC_0001.NRW"))
    }

    @Test
    fun `movies and unknown objects are excluded from photo list`() {
        assertEquals(CameraFileFormat.VIDEO, classifyFormat(0x300D, "MOV_0001.MOV"))
        assertEquals(CameraFileFormat.OTHER, classifyFormat(0x0000, "README.TXT"))
    }
}
