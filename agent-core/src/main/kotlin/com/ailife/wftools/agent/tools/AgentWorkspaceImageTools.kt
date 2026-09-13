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
            for (directory in listOf("workspace", "generated", "previews", "home")) {
                val root = WorkspacePathPolicy.resolveInside(workspace.paths.root, directory)
                if (!Files.isDirectory(root)) continue
                Files.walk(root, 8).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext() && size < 300) {
                        val path = iterator.next()
                        if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            val relative = workspace.paths.root.relativize(path).toString().replace('\\', '/')
                            runCatching { WorkspacePathPolicy.resolveInside(workspace.paths.root, relative) }.onSuccess { add(relative) }
                        }
                    }
                }
            }
        }
    }

    fun readImage(path: String): WatchAgentImageResult {
        val workspace = workspace()
        val relative = WorkspacePathPolicy.sanitizeRelative(path)
        require(relative.substringBefore('/') in setOf("workspace", "generated", "previews", "home")) { "只能读取工作区文件、生成图片或预览目录" }
        val file = WorkspacePathPolicy.resolveInside(workspace.paths.root, relative)
        return WatchAgentImageResult(relative, AgentImageInput.fromFile(file))
    }
}


