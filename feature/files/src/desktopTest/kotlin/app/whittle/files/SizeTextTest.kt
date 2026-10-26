package app.whittle.files

import app.whittle.files.ui.sizeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SizeTextTest {
    @Test
    fun `sizes read as Android writes them short`() {
        assertEquals("512 B", sizeText(512))
        assertEquals("1.8 MB", sizeText(1_800_000))
        assertEquals("526 MB", sizeText(526_400_000))
        assertEquals("1.4 GB", sizeText(1_406_000_000))
    }

    @Test
    fun `a tenth that rounds up to ten drops the tenth`() {
        assertEquals("10 MB", sizeText(9_960_000))
    }
}
