package dev.nekuz.trackerlens

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InvalidDexException(message: String) : IOException(message)

object DexReader {
    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_TAG = 0x12345678
    private const val CLASS_DEF_SIZE = 32
    private const val MAX_STRING_IDS = 4_000_000
    private const val MAX_TYPE_IDS = 65_536
    private const val MAX_CLASS_DEFS = 65_536
    private const val MAX_DESCRIPTOR_BYTES = 2_048

    fun readClassDescriptors(input: InputStream, onClass: (String) -> Unit): Int {
        val reader = Reader(input)
        val header = ByteBuffer.wrap(reader.readBytes(HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        checkMagic(header)
        if (header.getInt(0x28) != ENDIAN_TAG) throw InvalidDexException("Unsupported byte order")

        val fileSize = header.getInt(0x20)
        if (fileSize < HEADER_SIZE) throw InvalidDexException("Invalid file size")
        val stringIds = section(header, 0x38, 4, MAX_STRING_IDS, fileSize)
        val typeIds = section(header, 0x40, 4, MAX_TYPE_IDS, fileSize)
        val classDefs = section(header, 0x60, CLASS_DEF_SIZE, MAX_CLASS_DEFS, fileSize)
        if (classDefs.count == 0) return 0
        if (stringIds.end > typeIds.offset || typeIds.end > classDefs.offset) {
            throw InvalidDexException("Unexpected section order")
        }

        val stringOffsets = readIntArray(reader, stringIds)
        val typeStrings = readIntArray(reader, typeIds)
        val descriptorOffsets = IntArray(classDefs.count)
        reader.skipTo(classDefs.offset.toLong())
        for (index in 0 until classDefs.count) {
            val typeIndex = reader.readInt()
            reader.skipTo(reader.position + CLASS_DEF_SIZE - 4)
            if (typeIndex !in typeStrings.indices) throw InvalidDexException("Invalid class type")
            val stringIndex = typeStrings[typeIndex]
            if (stringIndex !in stringOffsets.indices) throw InvalidDexException("Invalid type name")
            val offset = stringOffsets[stringIndex]
            if (offset < classDefs.end || offset >= fileSize) throw InvalidDexException("Invalid string offset")
            descriptorOffsets[index] = offset
        }

        descriptorOffsets.sort()
        var previous = -1
        var count = 0
        val buffer = ByteArray(MAX_DESCRIPTOR_BYTES)
        for (offset in descriptorOffsets) {
            if (offset == previous) continue
            previous = offset
            reader.skipTo(offset.toLong())
            reader.skipUleb128()
            onClass(reader.readDescriptor(buffer))
            count++
        }
        return count
    }

    private fun checkMagic(header: ByteBuffer) {
        val valid = header.get(0) == 'd'.code.toByte() &&
            header.get(1) == 'e'.code.toByte() &&
            header.get(2) == 'x'.code.toByte() &&
            header.get(3) == '\n'.code.toByte()
        if (!valid) throw InvalidDexException("Not a DEX file")
    }

    private fun section(header: ByteBuffer, position: Int, itemSize: Int, maxCount: Int, fileSize: Int): Section {
        val count = header.getInt(position)
        val offset = header.getInt(position + 4)
        if (count < 0 || count > maxCount) throw InvalidDexException("Invalid section size")
        if (count == 0) return Section(0, 0, 0)
        if (offset < HEADER_SIZE) throw InvalidDexException("Invalid section offset")
        val end = offset.toLong() + count.toLong() * itemSize
        if (end > fileSize) throw InvalidDexException("Section outside the file")
        return Section(offset, count, end.toInt())
    }

    private fun readIntArray(reader: Reader, section: Section): IntArray {
        val values = IntArray(section.count)
        if (section.count == 0) return values
        reader.skipTo(section.offset.toLong())
        for (index in values.indices) values[index] = reader.readInt()
        return values
    }

    private class Section(val offset: Int, val count: Int, val end: Int)

    private class Reader(input: InputStream) {
        private val stream = BufferedInputStream(input, 64 * 1024)
        var position = 0L
            private set

        fun readByte(): Int {
            val value = stream.read()
            if (value < 0) throw EOFException()
            position++
            return value
        }

        fun readInt(): Int =
            readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)

        fun readBytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = stream.read(bytes, read, size - read)
                if (count < 0) throw EOFException()
                read += count
            }
            position += size
            return bytes
        }

        fun skipTo(target: Long) {
            if (target < position) throw InvalidDexException("Unexpected data order")
            while (position < target) {
                val skipped = stream.skip(target - position)
                if (skipped > 0) position += skipped else readByte()
            }
        }

        fun skipUleb128() {
            repeat(5) {
                if (readByte() and 0x80 == 0) return
            }
            throw InvalidDexException("Invalid string length")
        }

        fun readDescriptor(buffer: ByteArray): String {
            var size = 0
            while (true) {
                val value = readByte()
                if (value == 0) return String(buffer, 0, size, Charsets.ISO_8859_1)
                if (size == buffer.size) throw InvalidDexException("Class name too long")
                buffer[size++] = value.toByte()
            }
        }
    }
}
