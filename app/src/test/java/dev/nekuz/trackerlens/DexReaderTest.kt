package dev.nekuz.trackerlens

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

object DexFixture {
    fun build(
        defined: List<String>,
        referenced: List<String> = emptyList(),
        magic: String = "dex\n035\u0000",
        endian: Int = 0x12345678,
        mutate: (ByteBuffer) -> Unit = {},
    ): ByteArray {
        val strings = (defined + referenced).sorted()
        val stringIdsOffset = 0x70
        val typeIdsOffset = stringIdsOffset + strings.size * 4
        val classDefsOffset = typeIdsOffset + strings.size * 4
        val dataOffset = classDefsOffset + defined.size * 32
        val data = ByteArrayOutputStream()
        val offsets = strings.map { value ->
            val offset = dataOffset + data.size()
            val bytes = value.toByteArray(Charsets.UTF_8)
            data.write(bytes.size)
            data.write(bytes)
            data.write(0)
            offset
        }
        val size = dataOffset + data.size()
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(Charsets.ISO_8859_1))
        buffer.putInt(0x20, size)
        buffer.putInt(0x24, 0x70)
        buffer.putInt(0x28, endian)
        buffer.putInt(0x38, strings.size)
        buffer.putInt(0x3C, stringIdsOffset)
        buffer.putInt(0x40, strings.size)
        buffer.putInt(0x44, typeIdsOffset)
        buffer.putInt(0x60, defined.size)
        buffer.putInt(0x64, classDefsOffset)
        offsets.forEachIndexed { index, offset -> buffer.putInt(stringIdsOffset + index * 4, offset) }
        strings.indices.forEach { buffer.putInt(typeIdsOffset + it * 4, it) }
        defined.forEachIndexed { index, name ->
            buffer.putInt(classDefsOffset + index * 32, strings.indexOf(name))
        }
        buffer.position(dataOffset)
        buffer.put(data.toByteArray())
        mutate(buffer)
        return buffer.array()
    }
}

class DexReaderTest {
    private fun read(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        val count = DexReader.readClassDescriptors(ByteArrayInputStream(bytes)) { names.add(it) }
        assertEquals(count, names.size)
        return names
    }

    @Test
    fun readsDefinedClasses() {
        val names = listOf("Lcom/example/A;", "Lcom/example/B;", "La;")
        assertEquals(names.sorted(), read(DexFixture.build(names)).sorted())
    }

    @Test
    fun ignoresClassesThatAreOnlyReferenced() {
        val bytes = DexFixture.build(listOf("Lcom/example/A;"), listOf("Lcom/facebook/ads/AdView;"))
        assertEquals(listOf("Lcom/example/A;"), read(bytes))
    }

    @Test
    fun readsEmptyDex() {
        assertEquals(emptyList<String>(), read(DexFixture.build(emptyList())))
    }

    @Test
    fun acceptsNewerDexVersions() {
        assertEquals(1, read(DexFixture.build(listOf("La;"), magic = "dex\n040\u0000")).size)
    }

    @Test
    fun rejectsWrongMagic() {
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;"), magic = "zip\n035\u0000"))
        }
    }

    @Test
    fun rejectsWrongByteOrder() {
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;"), endian = 0x78563412))
        }
    }

    @Test
    fun rejectsTruncatedFile() {
        val bytes = DexFixture.build(listOf("Lcom/example/A;"))
        assertThrows(java.io.IOException::class.java) { read(bytes.copyOf(bytes.size - 4)) }
        assertThrows(java.io.IOException::class.java) { read(bytes.copyOf(16)) }
    }

    @Test
    fun rejectsHugeSectionSizesWithoutAllocating() {
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;")) { it.putInt(0x38, Int.MAX_VALUE) })
        }
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;")) { it.putInt(0x60, -1) })
        }
    }

    @Test
    fun rejectsSectionsOutsideTheFile() {
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;")) { it.putInt(0x64, Int.MAX_VALUE - 8) })
        }
    }

    @Test
    fun rejectsInvalidIndexes() {
        val classDefs = 0x70 + 8
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;")) { it.putInt(classDefs, 99) })
        }
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;")) { it.putInt(0x70 + 4, 99) })
        }
        assertThrows(InvalidDexException::class.java) {
            read(DexFixture.build(listOf("La;")) { it.putInt(0x70, 0x71) })
        }
    }

    @Test
    fun rejectsOverlongClassName() {
        val bytes = DexFixture.build(listOf("L${"a".repeat(100)};"))
        val long = bytes.copyOf(bytes.size + 5000)
        java.util.Arrays.fill(long, bytes.size - 1, long.size, 'a'.code.toByte())
        ByteBuffer.wrap(long).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20, long.size)
        assertThrows(InvalidDexException::class.java) { read(long) }
    }

    @Test
    fun survivesRandomCorruption() {
        val random = java.util.Random(42)
        val original = DexFixture.build(List(50) { "Lcom/example/C$it;" })
        repeat(2000) {
            val bytes = original.copyOf()
            repeat(1 + random.nextInt(8)) {
                bytes[random.nextInt(minOf(bytes.size, 0x200))] = random.nextInt(256).toByte()
            }
            try {
                DexReader.readClassDescriptors(ByteArrayInputStream(bytes)) {}
            } catch (e: java.io.IOException) {
                return@repeat
            }
        }
    }
}
