package com.ailife.wftools.agent.provider

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

object AgentImageInput {
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    fun fromFile(path: Path): AgentRequestImage {
        require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_FILE_BYTES.toLong()) { "图片不存在或超过 20 MB" }
        val bytes = Files.readAllBytes(path)
        require(ImageIO.read(bytes.inputStream()) != null) { "无法识别图片内容" }
        val output = ByteArrayOutputStream()
        ImageIO.write(ImageIO.read(bytes.inputStream()), "png", output)
        return AgentRequestImage("image/png", Base64.getEncoder().encodeToString(output.toByteArray()))
    }
    fun fromBytes(bytes: ByteArray): AgentRequestImage {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) { "图片为空或超过 20 MB" }
        val image = ImageIO.read(bytes.inputStream()) ?: error("无法识别图片内容")
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return AgentRequestImage("image/png", Base64.getEncoder().encodeToString(output.toByteArray()))
    }
}


