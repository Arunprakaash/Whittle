package app.whittle.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BinaryXmlStringsTest {
    @Test
    fun `strings are swapped in a utf16 pool and the file stays consistent`() {
        val xml = compiledXml(listOf("package", "app.whittle.exported.placeholder", "label"), utf8 = false)
        val out = BinaryXmlStrings.replace(xml, mapOf("app.whittle.exported.placeholder" to "app.whittle.apps.habits.a1b2c3d4"))
        assertEquals(listOf("package", "app.whittle.apps.habits.a1b2c3d4", "label"), readPool(out, utf8 = false))
        assertEquals(out.size, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertContentEquals(TRAILER, out.copyOfRange(out.size - TRAILER.size, out.size))
    }

    @Test
    fun `utf8 pools and long replacements work too`() {
        val xml = compiledXml(listOf("WhittleExportedAppLabelPlaceholder", "x"), utf8 = true)
        val label = "Little by Little — Habit Tracker".repeat(5)
        val out = BinaryXmlStrings.replace(xml, mapOf("WhittleExportedAppLabelPlaceholder" to label))
        assertEquals(listOf(label, "x"), readPool(out, utf8 = true))
    }

    private val TRAILER = byteArrayOf(0x02, 0x01, 0x10, 0x00, 0x08, 0x00, 0x00, 0x00)

    /** A minimal compiled XML file: file header, a string pool, then one opaque chunk that must survive. */
    private fun compiledXml(strings: List<String>, utf8: Boolean): ByteArray {
        val encoded = strings.map { text ->
            if (utf8) {
                val bytes = text.toByteArray()
                byteArrayOf(text.length.toByte(), bytes.size.toByte()) + bytes + byteArrayOf(0)
            } else {
                val b = ByteBuffer.allocate(2 + text.length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(text.length.toShort()); text.forEach { b.putChar(it) }; b.putShort(0); b.array()
            }
        }
        val data = encoded.fold(ByteArray(0)) { a, b -> a + b }.let { if (it.size % 4 == 0) it else it.copyOf(it.size + 4 - it.size % 4) }
        val poolSize = 28 + 4 * strings.size + data.size
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(1); pool.putShort(28); pool.putInt(poolSize); pool.putInt(strings.size); pool.putInt(0)
        pool.putInt(if (utf8) 0x100 else 0); pool.putInt(28 + 4 * strings.size); pool.putInt(0)
        var offset = 0
        encoded.forEach { pool.putInt(offset); offset += it.size }
        pool.put(data)
        val total = 8 + poolSize + TRAILER.size
        val file = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        file.putShort(3); file.putShort(8); file.putInt(total); file.put(pool.array()); file.put(TRAILER)
        return file.array()
    }

    private fun readPool(xml: ByteArray, utf8: Boolean): List<String> {
        val b = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.getInt(8 + 8)
        val stringsStart = b.getInt(8 + 20)
        return List(count) { i ->
            val o = 8 + stringsStart + b.getInt(8 + 28 + i * 4)
            if (utf8) {
                // Two lengths (characters, then bytes), each one or two bytes long.
                var at = o + if (b.get(o).toInt() and 0x80 != 0) 2 else 1
                val twoBytes = b.get(at).toInt() and 0x80 != 0
                val len8 = if (twoBytes) ((b.get(at).toInt() and 0x7F) shl 8) or (b.get(at + 1).toInt() and 0xFF) else b.get(at).toInt()
                at += if (twoBytes) 2 else 1
                String(ByteArray(len8) { b.get(at + it) })
            } else {
                val len = b.getShort(o).toInt()
                String(CharArray(len) { b.getChar(o + 2 + it * 2) })
            }
        }
    }
}
