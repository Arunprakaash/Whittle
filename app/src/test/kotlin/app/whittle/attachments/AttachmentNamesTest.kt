package app.whittle.attachments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentNamesTest {
    @Test
    fun `attachments go to the uploads folder, inside the chat's project when it has one`() {
        assertEquals("uploads", AttachmentNames.folder(null, "uploads"))
        assertEquals("tips/uploads", AttachmentNames.folder("tips", "uploads"))
        assertEquals("tips/uploads", AttachmentNames.folder("/tips/", "uploads"))
    }

    @Test
    fun `names lose folders and unsafe characters, and are never empty or hidden`() {
        assertEquals("receipt.pdf", AttachmentNames.clean("../../secret/receipt.pdf", "file"))
        assertEquals("ab.png", AttachmentNames.clean("a\u0000b?.png", "file"))
        assertEquals("env", AttachmentNames.clean(".env", "file"))
        assertEquals("file", AttachmentNames.clean("  ", "file"))
        assertEquals("file", AttachmentNames.clean(null, "file"))
        assertEquals("photo.jpg", AttachmentNames.clean("27.jpg", "photo.jpg"))
        assertEquals("2026 plan.pdf", AttachmentNames.clean("2026 plan.pdf", "file"))
    }

    @Test
    fun `a name already used gets a number, so nothing is overwritten`() {
        val taken = setOf("photo.jpg", "photo-2.jpg", "notes")
        assertEquals("photo-3.jpg", AttachmentNames.unique("photo.jpg", taken::contains))
        assertEquals("notes-2", AttachmentNames.unique("notes", taken::contains))
        assertEquals("new.txt", AttachmentNames.unique("new.txt", taken::contains))
    }

    @Test
    fun `sizes read in the units people use`() {
        assertEquals("900 B", AttachmentNames.size(900))
        assertEquals("212 KB", AttachmentNames.size(212 * 1024))
        assertEquals("1.8 MB", AttachmentNames.size(1_887_437))
        assertEquals("20 MB", AttachmentNames.size(20L * 1024 * 1024))
    }

    @Test
    fun `an attachment is an image by its type`() {
        assertTrue(Attachment("uploads/a.jpg", "a.jpg", 1, "image/jpeg").isImage)
        assertFalse(Attachment("uploads/a.pdf", "a.pdf", 1, "application/pdf").isImage)
    }
}
