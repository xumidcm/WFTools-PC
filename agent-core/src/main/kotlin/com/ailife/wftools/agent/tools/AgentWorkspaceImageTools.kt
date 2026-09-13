package com.ailife.wftools.agent.tools

import com.ailife.wftools.agent.provider.AgentImageInput
import com.ailife.wftools.agent.provider.AgentRequestImage
import com.ailife.wftools.agent.workspace.WorkspaceManager
import com.ailife.wftools.agent.workspace.WorkspacePathPolicy
import java.nio.file.Files

data class WatchAgentImageResult(val path: String, val image: AgentRequestImage)

/** Image access needs a binding, but does not require an installed Linux runtime. */
class AgentWorkspaceImageTools(private val manager: WorkspaceManager, private val watchScope: String) {
    private fun workspace() = manager.bindingFor(watchScope)?.let { manager.get(it.workspaceId) }
        ?: throw WatchAgentToolException("workspace_required", "请先在对话顶部选择工作区")

    fun listFiles(): List<String> {
        val workspace = workspace()
        return buildList {
            for (directory in listOf("workspace", "generated", "previews", "home", "face")) {
                val root = WorkspacePathPolicy.resolveInside(workspace.paths.root, directory)
                if (!Files.isDirectory(root)) continue
                Files.walk(root, 8).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next()
                        if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            val relative = workspace.paths.root.relativize(path).toString().replace('\\', '/')
                            runCatching { WorkspacePathPolicy.resolveInside(workspace.paths.root, relative) }.onSuccess { add(relative) }
                        }
                    }
                }
            }
        }.distinct().sorted().take(300)
    }

    fun readImage(path: String): WatchAgentImageResult {
        val workspace = workspace()
        val requested = WorkspacePathPolicy.sanitizeRelative(path)
        val candidates = buildList {
            // inspect_face reports archive-relative paths such as images/foo.png;
            // resolve those against the bound face mirror first. Explicit
            // face/ paths remain accepted for list_workspace_files round trips.
            if (!requested.startsWith("face/")) add("face/$requested")
            add(requested)
        }.distinct()
        val resolved = candidates.firstNotNullOfOrNull { candidate ->
            val root = candidate.substringBefore('/')
            val allowedWorkspace = root in setOf("workspace", "generated", "previews", "home")
            val allowedFace = candidate.startsWith("face/")
            if ((!allowedWorkspace && !allowedFace) || !isImagePath(candidate)) return@firstNotNullOfOrNull null
            val file = WorkspacePathPolicy.resolveInside(workspace.paths.root, candidate)
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) candidate to file else null
        } ?: throw IllegalArgumentException("图片不存在或不属于当前工作区/表盘包：$path")
        return WatchAgentImageResult(resolved.first, AgentImageInput.fromFile(resolved.second))
    }

    private fun isImagePath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")
    }
}


