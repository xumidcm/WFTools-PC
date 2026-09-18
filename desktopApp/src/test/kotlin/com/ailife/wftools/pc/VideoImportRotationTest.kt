package com.ailife.wftools.pc

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Rotation handling at video import.
 *
 * Editors apply the MP4 rotation hint (LibVLC here, ExoPlayer upstream) while the watch's
 * CloX/axmol player ignores it, so a rotated source renders upright in the editor and sideways
 * on the watch. Import therefore drops the hint without re-encoding.
 */
class VideoImportRotationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun video(name: String) = temp.newFile(name).toPath().also { Files.writeString(it, "payload") }

    @Test fun `a rotated h264 source is rewritten into the import set`() {
        val rotated = video("rotated.mp4")
        val stripped = mutableListOf<Pair<Path, Path>>()
        val preparer = VideoImportPreparer(
            codec = { "h264" },
            transcode = { _, _ -> error("已经是 H.264，不允许重编码") },
            isRotated = { it == rotated },
            stripRotation = { source, target ->
                stripped.add(source to target)
                Files.writeString(target, "stripped")
                true
            },
        )

        val prepared = preparer.prepare(listOf(rotated))
        try {
            assertEquals("必须换成去掉旋转标记的副本", listOf(rotated), stripped.map { it.first })
            val result = prepared.paths.single()
            assertNotEquals("不能继续使用原文件", rotated, result)
            assertEquals("stripped", Files.readString(result))
            assertTrue("副本应落在临时目录并随 close 清理", result.fileName.toString().endsWith(".mp4"))
        } finally { prepared.close() }
    }

    @Test fun `an unrotated h264 source is passed through untouched`() {
        val flat = video("flat.mp4")
        val preparer = VideoImportPreparer(
            codec = { "h264" },
            transcode = { _, _ -> error("无旋转不应触发转码") },
            isRotated = { false },
            stripRotation = { _, _ -> error("无旋转不应触发改写") },
        )

        val prepared = preparer.prepare(listOf(flat))
        try { assertEquals(listOf(flat), prepared.paths) } finally { prepared.close() }
    }

    @Test fun `a failed strip falls back to the original instead of a broken copy`() {
        val rotated = video("rotated-fail.mp4")
        val preparer = VideoImportPreparer(
            codec = { "h264" },
            transcode = { _, _ -> error("不应回退到转码") },
            isRotated = { true },
            stripRotation = { _, _ -> false },
        )

        val prepared = preparer.prepare(listOf(rotated))
        try {
            assertEquals("改写失败时必须回退到原文件，不能给出半成品", listOf(rotated), prepared.paths)
        } finally { prepared.close() }
    }

    @Test fun `non video inputs are never inspected for rotation`() {
        val image = video("photo.png")
        val preparer = VideoImportPreparer(
            codec = { error("图片不应被探测编码") },
            transcode = { _, _ -> error("图片不应被转码") },
            isRotated = { error("图片不应被探测旋转") },
            stripRotation = { _, _ -> error("图片不应被改写") },
        )

        val prepared = preparer.prepare(listOf(image))
        try { assertEquals(listOf(image), prepared.paths) } finally { prepared.close() }
    }

    @Test fun `rotation stripping keeps the original file and cleans up on close`() {
        val rotated = video("keep-original.mp4")
        val before = Files.readAllBytes(rotated)
        var copy: Path? = null
        val preparer = VideoImportPreparer(
            codec = { "h264" },
            transcode = { _, _ -> error("不应转码") },
            isRotated = { true },
            stripRotation = { _, target -> copy = target; Files.writeString(target, "copy"); true },
        )

        val prepared = preparer.prepare(listOf(rotated))
        val produced = prepared.paths.single()
        assertArrayEquals("原文件必须保持不变", before, Files.readAllBytes(rotated))
        assertTrue("副本应存在", Files.exists(produced))
        prepared.close()
        assertFalse("关闭后临时副本应被清理", Files.exists(produced))
        assertNotNull(copy)
    }

    @Test fun `mixed batches keep order and only rewrite the rotated videos`() {
        val rotated = video("a.mp4")
        val flat = video("b.mp4")
        val vp9 = video("c.webm")
        val transcoded = mutableListOf<Path>()
        val stripped = mutableListOf<Path>()
        val preparer = VideoImportPreparer(
            codec = { if (it == vp9) "vp9" else "h264" },
            transcode = { source, target ->
                transcoded.add(source)
                Files.writeString(target, "transcoded")
            },
            isRotated = { it == rotated },
            stripRotation = { source, target ->
                stripped.add(source)
                Files.writeString(target, "stripped")
                true
            },
        )

        val prepared = preparer.prepare(listOf(rotated, flat, vp9))
        try {
            assertEquals(listOf(rotated), stripped)
            assertEquals(listOf(vp9), transcoded)
            assertEquals(3, prepared.paths.size)
            assertEquals("未旋转的 H.264 必须原样保留", flat, prepared.paths[1])
        } finally { prepared.close() }
    }
}
