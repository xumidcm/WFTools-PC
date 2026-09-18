package com.ailife.wftools.pc.render

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Contracts for MP4 rotation metadata.
 *
 * The fixtures are built in memory rather than checked in: what matters is the `tkhd` matrix
 * layout, and hand-built boxes state that layout explicitly.
 */
class Mp4RotationTest {
    @get:Rule val temp = TemporaryFolder()

    private companion object {
        /** version/flags(4) + times(20) + trackID/reserved/duration(12) + reserved[2](8) + layer/group/volume/reserved(8) + matrix(36) + size(8). */
        const val TKHD_BODY_BYTES = 4 + 20 + 12 + 8 + 8 + 36 + 8
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + payload.size).put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()

    /** tkhd with the given 3x3 matrix, version 0. Layout matches the ISO base media format. */
    private fun tkhd(matrix: IntArray, width: Int = 1920, height: Int = 1080): ByteArray {
        val body = ByteBuffer.allocate(TKHD_BODY_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)                 // version 0 + flags
            putInt(0); putInt(0)      // creation / modification
            putInt(1)                 // track id
            putInt(0)                 // reserved
            putInt(0)                 // duration
            putInt(0); putInt(0)      // reserved[2]
            putShort(0); putShort(0)  // layer / alternate group
            putShort(0); putShort(0)  // volume / reserved
            matrix.forEach(::putInt)
            putInt(width shl 16); putInt(height shl 16)
        }.array()
        return box("tkhd", body)
    }

    private val identity = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

    /**
     * Matrix for a given rotation, in the layout a,b,u,c,d,v,x,y,w.
     *
     * These values were taken from real files produced by
     * `ffmpeg -display_rotation <deg>` and cross-checked with `ffprobe -show_entries
     * stream_side_data=rotation`: a 90° file carries a=0, b=-1, c=+1, d=0. Getting the sign
     * backwards silently swaps 90 and 270, so the convention is pinned here.
     */
    private fun rotated(degrees: Int): IntArray = when (degrees) {
        90 -> intArrayOf(0, -0x00010000, 0, 0x00010000, 0, 0, 0, 0, 0x40000000)
        180 -> intArrayOf(-0x00010000, 0, 0, 0, -0x00010000, 0, 0, 0, 0x40000000)
        270 -> intArrayOf(0, 0x00010000, 0, -0x00010000, 0, 0, 0, 0, 0x40000000)
        else -> identity
    }

    /** moov/trak/tkhd plus a fake mdat so the file has the usual shape. */
    private fun mp4(matrix: IntArray): ByteArray {
        val moov = box("moov", box("trak", tkhd(matrix)))
        val mdat = box("mdat", ByteArray(64) { 7 })
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        return ftyp + mdat + moov
    }

    private fun write(name: String, bytes: ByteArray): Path = temp.newFile(name).toPath().also { Files.write(it, bytes) }

    @Test fun `a rotation matrix is reported in the same direction as ffprobe`() {
        // ffprobe reports 90 for the matrix a=0,b=1,c=-1,d=0; the sign convention must match.
        listOf(0, 90, 180, 270).forEach { degrees ->
            val file = write("rot-$degrees.mp4", mp4(rotated(degrees)))
            assertEquals("$degrees 度必须被识别", degrees, Mp4Rotation.rotationDegrees(file))
            assertEquals(degrees != 0, Mp4Rotation.isRotated(file))
        }
    }

    @Test fun `a file without rotation reads as zero`() {
        val file = write("flat.mp4", mp4(identity))
        assertEquals(0, Mp4Rotation.rotationDegrees(file))
        assertFalse(Mp4Rotation.isRotated(file))
    }

    @Test fun `stripping rotation rewrites only the matrix and keeps every other byte`() {
        val original = mp4(rotated(90))
        val source = write("strip-src.mp4", original)
        val target = temp.newFile("strip-dst.mp4").toPath()

        assertTrue("带旋转的文件应被改写", Mp4Rotation.stripRotation(source, target))

        assertEquals("文件长度不能变（不重编码、不重打包）", original.size.toLong(), Files.size(target))
        assertEquals(0, Mp4Rotation.rotationDegrees(target))
        // 只有矩阵里取值不同的字节允许变化，且必须全部落在 36 字节矩阵内。
        val actual = Files.readAllBytes(target)
        val differences = original.indices.filter { original[it] != actual[it] }
        assertTrue("矩阵必须有字节被改写", differences.isNotEmpty())
        assertTrue("不允许改写矩阵以外的任何字节", differences.size <= 36)

        val matrixOffset = Mp4Rotation.matrixOffsetWithin(original)
        assertNotNull(matrixOffset)
        assertTrue(
            "所有改动都必须落在 tkhd 矩阵区间内",
            differences.all { it >= matrixOffset!! && it < matrixOffset!! + 36 },
        )
        // mdat 是纯载荷，必须逐字节保留。
        assertArrayEquals(original.copyOfRange(0, matrixOffset!!), actual.copyOfRange(0, matrixOffset!!))
    }

    @Test fun `stripping an unrotated file reports that nothing changed`() {
        val source = write("already-flat.mp4", mp4(identity))
        val target = temp.newFile("already-flat-out.mp4").toPath()
        assertFalse(Mp4Rotation.stripRotation(source, target))
    }

    @Test fun `every rotation is normalised away`() {
        listOf(90, 180, 270).forEach { degrees ->
            val source = write("normalise-$degrees.mp4", mp4(rotated(degrees)))
            val target = temp.newFile("normalise-out-$degrees.mp4").toPath()
            assertTrue(Mp4Rotation.stripRotation(source, target))
            assertEquals("$degrees 度应被清除", 0, Mp4Rotation.rotationDegrees(target))
        }
    }

    @Test fun `malformed input is rejected instead of looping or throwing`() {
        val truncated = write("truncated.mp4", byteArrayOf(0, 0, 0, 100, 'm'.code.toByte(), 'o'.code.toByte()))
        assertEquals(0, Mp4Rotation.rotationDegrees(truncated))
        assertFalse(Mp4Rotation.isRotated(truncated))

        val empty = write("empty.mp4", ByteArray(0))
        assertEquals(0, Mp4Rotation.rotationDegrees(empty))

        val notMp4 = write("text.mp4", "this is not an mp4 at all".toByteArray())
        assertEquals(0, Mp4Rotation.rotationDegrees(notMp4))
    }

    @Test fun `a box claiming an impossible size does not run away`() {
        // size 0 on the first box means "to end of file"; a bogus huge size must be refused.
        val bogus = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x7FFFFFFF).put("moov".toByteArray(Charsets.US_ASCII)).put(ByteArray(12)).array()
        val file = write("bogus.mp4", bogus)
        assertEquals(0, Mp4Rotation.rotationDegrees(file))
        assertFalse(Mp4Rotation.isRotated(file))
    }

    @Test fun `the pure byte transform agrees with the file based path`() {
        val bytes = mp4(rotated(270))
        val expected = bytes.copyOf()
        assertTrue(Mp4Rotation.stripRotationInPlace(expected))
        val offset = Mp4Rotation.matrixOffsetWithin(bytes)
        assertNotNull(offset)
        val changed = bytes.indices.filter { bytes[it] != expected[it] }
        assertTrue("应有字节被改写", changed.isNotEmpty())
        assertTrue("只能改写矩阵区间", changed.all { it >= offset!! && it < offset!! + 36 })

        val source = write("pure-src.mp4", bytes)
        val target = temp.newFile("pure-dst.mp4").toPath()
        assertTrue(Mp4Rotation.stripRotation(source, target))
        assertArrayEquals("两条路径必须产生同样的结果", expected, Files.readAllBytes(target))
    }

    @Test fun `a missing file is treated as unrotated rather than crashing`() {
        val missing = temp.root.toPath().resolve("does-not-exist.mp4")
        assertEquals(0, Mp4Rotation.rotationDegrees(missing))
        assertFalse(Mp4Rotation.isRotated(missing))
    }
}
