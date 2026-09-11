package com.ailife.wftools2.domain.editor

/**
 * Common abstractions for Watchface Projects
 */

interface WatchfaceProject {
    val id: String
    val title: String
    val width: Int
    val height: Int
    val layers: List<WatchfaceLayer>
}

interface WatchfaceLayer {
    val type: String
    val x: Float
    val y: Float
    val width: Int
    val height: Int
    val isVisible: Boolean
    val alpha: Int            // 0–255; -1 means fully opaque (format default)
    val scale: Float
    val rotate: Int           // degrees 0–360
    val thumbnailImagePath: String?   // first image asset filename for layer list thumbnail
    val displayText: String          // text content for text-type layers
}

// Result of loading a project
sealed class ProjectLoadResult {
    data class Success(val project: WatchfaceProject, val projectDir: java.io.File) : ProjectLoadResult()
    data class Error(val message: String) : ProjectLoadResult()
}
