package app.whittle.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replaces strings in a compiled Android XML file (such as an APK's AndroidManifest.xml). Everything else in
 * the file refers to strings by index, so rewriting the string pool with the same order is enough: the pool
 * is decoded, the matching strings are swapped, and the pool and file sizes are recomputed.
 */
object BinaryXmlStrings {
    fun replace(xml: ByteArray, replacements: Map<String, String>): ByteArray {
        val file = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        require(file.getShort(0).toInt() == XML_CHUNK) { "not a compiled XML file" }
        val fileHeaderSize = file.getShort(2).toInt()
        val poolStart = fileHeaderSize
        require(file.getShort(poolStart).toInt() == STRING_POOL_CHUNK) { "the file doesn't start with a string pool" }
        val poolHeaderSize = file.getShort(poolStart + 2).toInt() and 0xFFFF
        val poolSize = file.getInt(poolStart + 4)
        val stringCount = file.getInt(poolStart + 8)
        val styleCount = file.getInt(poolStart + 12)
        val flags = file.getInt(poolStart + 16)
        val stringsStart = file.getInt(poolStart + 20)
        val stylesStart = file.getInt(poolStart + 24)
        val utf8 = flags and UTF8_FLAG != 0

        val strings = List(stringCount) { index ->
            val offset = poolStart + stringsStart + file.getInt(poolStart + poolHeaderSize + index * 4)
            val text = if (utf8) readUtf8(file, offset) else readUtf16(file, offset)
            replacements[text] ?: text
        }
        val encoded = strings.map { if (utf8) encodeUtf8(it) else encodeUtf16(it) }
        val stringData = encoded.fold(ByteArray(0)) { all, bytes -> all + bytes }.padded()
        val styleData = if (styleCount > 0) xml.copyOfRange(poolStart + stylesStart, poolStart + poolSize) else ByteArray(0)

        val newStringsStart = poolHeaderSize + 4 * stringCount + 4 * styleCount
        val newPoolSize = newStringsStart + stringData.size + styleData.size
        val pool = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.put(xml, poolStart, poolHeaderSize)
        pool.putInt(4, newPoolSize)
        pool.putInt(20, newStringsStart)
        pool.putInt(24, if (styleCount > 0) newStringsStart + stringData.size else 0)
        pool.position(poolHeaderSize)
        var offset = 0
        encoded.forEach { bytes ->
            pool.putInt(offset)
            offset += bytes.size
        }
        // Style offsets point into the style data, which moves as one block.
        repeat(styleCount) { index -> pool.putInt(file.getInt(poolStart + poolHeaderSize + (stringCount + index) * 4)) }
        pool.put(stringData)
        pool.put(styleData)

        val rest = xml.copyOfRange(poolStart + poolSize, xml.size)
        val out = ByteBuffer.allocate(fileHeaderSize + newPoolSize + rest.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(xml, 0, fileHeaderSize)
        out.putInt(4, fileHeaderSize + newPoolSize + rest.size)
        out.position(fileHeaderSize)
        out.put(pool.array())
        out.put(rest)
        return out.array()
    }

    private fun readUtf16(buffer: ByteBuffer, start: Int): String {
        var offset = start
        var length = buffer.getShort(offset).toInt() and 0xFFFF
        offset += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or (buffer.getShort(offset).toInt() and 0xFFFF)
            offset += 2
        }
        val chars = CharArray(length) { buffer.getChar(offset + it * 2) }
        return String(chars)
    }

    private fun readUtf8(buffer: ByteBuffer, start: Int): String {
        var offset = start
        offset += if (buffer.get(offset).toInt() and 0x80 != 0) 2 else 1
        var length = buffer.get(offset).toInt() and 0xFF
        if (length and 0x80 != 0) {
            length = ((length and 0x7F) shl 8) or (buffer.get(offset + 1).toInt() and 0xFF)
            offset += 2
        } else {
            offset += 1
        }
        val bytes = ByteArray(length) { buffer.get(offset + it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun encodeUtf16(text: String): ByteArray {
        val length = text.length
        val header = if (length > 0x7FFF) shortArrayOf((0x8000 or (length ushr 16)).toShort(), (length and 0xFFFF).toShort()) else shortArrayOf(length.toShort())
        val buffer = ByteBuffer.allocate(header.size * 2 + length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        header.forEach { buffer.putShort(it) }
        text.forEach { buffer.putChar(it) }
        buffer.putShort(0)
        return buffer.array()
    }

    private fun encodeUtf8(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return utf8Length(text.length) + utf8Length(bytes.size) + bytes + byteArrayOf(0)
    }

    private fun utf8Length(length: Int): ByteArray =
        if (length > 0x7F) byteArrayOf((0x80 or (length ushr 8)).toByte(), (length and 0xFF).toByte()) else byteArrayOf(length.toByte())

    private fun ByteArray.padded(): ByteArray = if (size % 4 == 0) this else copyOf(size + 4 - size % 4)

    private const val XML_CHUNK = 0x0003
    private const val STRING_POOL_CHUNK = 0x0001
    private const val UTF8_FLAG = 0x100
}
