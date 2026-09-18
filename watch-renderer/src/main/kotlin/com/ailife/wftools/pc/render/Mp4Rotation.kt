package com.ailife.wftools.pc.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * MP4 旋转元数据的读写。
 *
 * MP4 把每个轨道的旋转存成视频轨 `tkhd` 盒里的一个 3×3 矩阵。桌面播放器（LibVLC）会应用它，
 * 而手表上的 CloX/axmol 忽略它——上游 `ece0bd5` 修的是同一处不一致（ExoPlayer 应用、手表忽略）。
 * WatchMaker 导出时原样打包视频，于是手机竖拍的视频会「编辑器里立着、手表上躺着」。
 *
 * 处理方式与上游一致：**不重编码**地丢掉这个提示，让两个播放器看到同样的解码画面。
 * 只改写那 36 字节矩阵，文件其余部分（含整个 `mdat`）逐字节复制，不做任何转码。
 */
object Mp4Rotation {
    /** 单位矩阵，16.16 定点：a=1,b=0,u=0,c=0,d=1,v=0,x=0,y=0,w=1。 */
    private val IDENTITY = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

    private const val MATRIX_BYTES = 36
    private const val FIXED_POINT_ONE = 65536.0

    /** `moov` 可能很大，但远超这个尺寸就不是正常的表盘视频了，直接放弃处理。 */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    /** 视频轨是否带旋转提示。文件不可读或没有 `moov` 时返回 false。 */
    fun isRotated(file: Path): Boolean = rotationDegrees(file) != 0

    /**
     * 视频轨的旋转角度，取自 `tkhd` 矩阵，归一化到 0/90/180/270。
     * 无旋转、无法解析或文件不可读时返回 0。角度与 `ffprobe` 报告的 `rotation` 同向。
     */
    fun rotationDegrees(file: Path): Int = runCatching {
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val offset = matrixOffset(channel, channel.size()) ?: return@use 0
            val matrix = readMatrix(channel, offset)
            angleOf(matrix)
        }
    }.getOrDefault(0)

    /**
     * 把 [source] 复制为 [target] 并去掉视频轨的旋转提示。
     *
     * 返回 true 表示确实改写了矩阵；false 表示源文件本来就没有旋转提示（此时仍会复制文件，
     * 调用方可据此决定是否改用原文件）。矩阵不在 `moov` 里的异常文件返回 false 且不留下半成品。
     */
    fun stripRotation(source: Path, target: Path): Boolean = runCatching {
        FileChannel.open(source, StandardOpenOption.READ).use { input ->
            java.nio.file.Files.newOutputStream(target).use { output ->
                input.transferTo(0, input.size(), java.nio.channels.Channels.newChannel(output))
            }
        }
        FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val offset = matrixOffset(channel, channel.size()) ?: return@use false
            val current = readMatrix(channel, offset)
            if (current.contentEquals(IDENTITY)) return@use false
            channel.write(ByteBuffer.wrap(encodeMatrix(IDENTITY)), offset)
            true
        }
    }.getOrElse { error ->
        runCatching { java.nio.file.Files.deleteIfExists(target) }
        throw error
    }

    /** 纯函数版本，便于用构造出来的 MP4 字节直接测试。返回是否发生了改写。 */
    internal fun stripRotationInPlace(bytes: ByteArray): Boolean {
        val offset = matrixOffsetWithin(bytes) ?: return false
        val current = decodeMatrix(bytes, offset)
        if (current.contentEquals(IDENTITY)) return false
        encodeMatrix(IDENTITY).copyInto(bytes, offset)
        return true
    }

    /** 矩阵在整份文件里的绝对偏移；没有可处理的 `moov`/`trak`/`tkhd` 时返回 null。 */
    internal fun matrixOffsetWithin(bytes: ByteArray): Int? {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size = boxSize(bytes, offset, bytes.size) ?: return null
            val type = boxType(bytes, offset)
            val payload = offset + headerLength(bytes, offset)
            if (type == "moov") return matrixOffsetInMoov(bytes, payload, offset + size)
            offset += size
        }
        return null
    }

    /**
     * 在 `moov` 里找出第一个带非单位矩阵的 `tkhd`。
     *
     * 实践中只有视频轨会带旋转矩阵（音轨的 tkhd 宽高为 0、矩阵为单位阵），因此按「矩阵不是单位阵」
     * 定位即可，不必再解析 `hdlr` 去认轨道类型。
     */
    private fun matrixOffsetInMoov(bytes: ByteArray, start: Int, end: Int): Int? {
        var offset = start
        while (offset + 8 <= end) {
            val size = boxSize(bytes, offset, end) ?: return null
            val header = headerLength(bytes, offset)
            if (boxType(bytes, offset) == "trak") {
                matrixOffsetInTrak(bytes, offset + header, offset + size)?.let { return it }
            }
            offset += size
        }
        return null
    }

    private fun matrixOffsetInTrak(bytes: ByteArray, start: Int, end: Int): Int? {
        var offset = start
        while (offset + 8 <= end) {
            val size = boxSize(bytes, offset, end) ?: return null
            if (boxType(bytes, offset) == "tkhd") {
                val payload = offset + headerLength(bytes, offset)
                // 布局（version 0）：version/flags(4) creation(4) modification(4) trackID(4)
                // reserved(4) duration(4) reserved[2](8) layer(2) alternateGroup(2) volume(2)
                // reserved(2) → 矩阵在这之后，距 payload 40 字节。version 1 的时间字段各宽 8 字节。
                val afterTimes = payload + 4 + (if (bytes[payload].toInt() == 1) 32 else 20)
                val matrix = afterTimes + 16
                if (matrix + MATRIX_BYTES > bytes.size) return null
                return matrix
            }
            offset += size
        }
        return null
    }

    private fun matrixOffset(channel: FileChannel, fileSize: Long): Long? {
        var offset = 0L
        while (offset + 8 <= fileSize) {
            val header = readBytes(channel, offset, 16)
            val size32 = uint(header, 0)
            var size = size32
            var headerLength = 8
            if (size32 == 1L) {
                size = int64(header, 8)
                headerLength = 16
            } else if (size32 == 0L) {
                size = fileSize - offset
            }
            if (size < headerLength || offset + size > fileSize) return null
            val type = String(header, 4, 4, Charsets.US_ASCII)
            val payload = offset + headerLength
            if (type == "moov") {
                val length = size - headerLength
                if (length <= 0 || length > MAX_MOOV_BYTES) return null
                val buffer = readBytes(channel, payload, length.toInt())
                return matrixOffsetInMoov(buffer, 0, buffer.size)?.let { payload + it }
            }
            offset += size
        }
        return null
    }

    /** 从 9 个定点整数里取出旋转角度。`tkhd` 矩阵按 a,b,u,c,d,v,x,y,w 存放。 */
    private fun angleOf(matrix: IntArray): Int {
        if (matrix.size < 9) return 0
        val c = matrix[3] / FIXED_POINT_ONE
        val d = matrix[4] / FIXED_POINT_ONE
        val degrees = Math.toDegrees(atan2(c, d)).roundToInt()
        return ((degrees % 360) + 360) % 360
    }

    private fun readMatrix(channel: FileChannel, offset: Long): IntArray = decodeMatrix(readBytes(channel, offset, MATRIX_BYTES), 0)

    private fun decodeMatrix(bytes: ByteArray, offset: Int): IntArray =
        IntArray(9) { index -> int32(bytes, offset + index * 4) }

    private fun encodeMatrix(matrix: IntArray): ByteArray =
        ByteBuffer.allocate(MATRIX_BYTES).order(ByteOrder.BIG_ENDIAN).apply { matrix.forEach(::putInt) }.array()

    private fun readBytes(channel: FileChannel, offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) break
            position += read
        }
        return buffer.array()
    }

    private fun boxType(bytes: ByteArray, offset: Int): String = String(bytes, offset + 4, 4, Charsets.US_ASCII)

    private fun headerLength(bytes: ByteArray, offset: Int): Int = if (uint(bytes, offset) == 1L) 16 else 8

    /** 盒子总长度；越界或退化时返回 null，避免解析循环失控。 */
    private fun boxSize(bytes: ByteArray, offset: Int, limit: Int): Int? {
        if (offset + 8 > limit) return null
        val size32 = uint(bytes, offset)
        val size = when {
            size32 == 1L -> if (offset + 16 <= limit) int64(bytes, offset + 8) else return null
            size32 == 0L -> (limit - offset).toLong()
            else -> size32
        }
        if (size < headerLength(bytes, offset) || offset + size > limit) return null
        return size.toInt()
    }

    private fun uint(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    private fun int64(bytes: ByteArray, offset: Int): Long =
        (uint(bytes, offset) shl 32) or uint(bytes, offset + 4)

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
}
