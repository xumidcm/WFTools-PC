package com.ailife.wftools.agent.tools

import com.ailife.wftools.agent.prompt.WatchAgentPrompt
import com.ailife.wftools.agent.workspace.WorkspacePermissionSettings
import com.ailife.wftools.agent.workspace.ProotShellRunner
import com.ailife.wftools.agent.workspace.ShellCommandRequest

/** Executes provider tool calls against a host-owned editor session. */
class WatchAgentToolRunner(
    private val host: WatchAgentToolHost,
    maxParallel: Int = WatchAgentToolScheduler.DEFAULT_MAX_PARALLEL,
    private val permissionProvider: () -> WorkspacePermissionSettings = { WorkspacePermissionSettings() },
    private val workspaceImages: AgentWorkspaceImageTools? = null,
    private val externalTools: AgentExternalTools? = null,
    private val shellRunner: ProotShellRunner? = null,
    private val workspaceId: String = "",
    private val workspaceIdProvider: () -> String = { workspaceId },
    private val bashEnabled: () -> Boolean = { true },
) {
    val promptVersion: String get() = WatchAgentPrompt.promptVersion
    val toolSchemaVersion: String get() = WatchAgentToolContracts.schemaVersion
    val toolContracts: List<WatchAgentToolContract>
        get() {
            val permissions = currentPermissions()
            return (WatchAgentToolContracts.all + WatchAgentToolContracts.mirror + WatchAgentToolContracts.external).filter { it.name != WatchAgentToolName.COMMIT_FACE_MIRROR && permissions.allows(it.access) &&
                (workspaceImages != null || it.name !in setOf(WatchAgentToolName.LIST_WORKSPACE_FILES, WatchAgentToolName.READ_IMAGE)) &&
                (bashEnabled() && shellRunner?.capability(workspaceIdProvider())?.bashAvailable == true || it.name != WatchAgentToolName.RUN_COMMAND) &&
                (externalTools?.imageGenerationAvailable == true || it.name != WatchAgentToolName.GENERATE_IMAGE) &&
                (externalTools?.imageEditingAvailable == true || it.name != WatchAgentToolName.EDIT_IMAGE) &&
                (externalTools?.webSearchAvailable == true || it.name !in setOf(WatchAgentToolName.SEARCH_WEB, WatchAgentToolName.SCRAPE_PAGE)) }
        }

    private val scheduler = WatchAgentToolScheduler(::executeOne, maxParallel)

    fun beginRequestUndoGroup(): WatchAgentUndoGroup? =
        (host as? WatchAgentUndoHost)?.beginUndoGroup()

    suspend fun execute(
        calls: List<WatchAgentToolCall>,
        manageUndoGroup: Boolean = true,
    ): List<WatchAgentToolResult> {
        val undoGroup = if (manageUndoGroup) beginRequestUndoGroup() else null
        return try {
            val results = scheduler.execute(calls)
            if (results.any { it.succeeded && WatchAgentToolName.fromWireName(it.name)?.isWrite == true }) undoGroup?.commit()
            else undoGroup?.rollback()
            results
        } catch (error: Throwable) {
            runCatching { undoGroup?.rollback() }
            throw error
        }
    }

    suspend fun execute(call: WatchAgentToolCall): WatchAgentToolResult = execute(listOf(call)).first()

    private fun executeOne(call: WatchAgentToolCall): WatchAgentToolResult = try {
        val name = call.toolName
        if (name != null && !currentPermissions().allows(name.access)) {
            return failure(call, "permission_denied", "工作区权限不允许执行此工具")
        }
        when (name) {
            WatchAgentToolName.LIST_WORKSPACE_FILES -> success(call, host.currentRevision,
                requireNotNull(workspaceImages) { "未绑定工作区图片工具" }.listFiles())
            WatchAgentToolName.READ_IMAGE -> success(call, host.currentRevision,
                requireNotNull(workspaceImages) { "未绑定工作区图片工具" }.readImage(call.requiredString("path")))
            WatchAgentToolName.WRITE_WORKSPACE_FILE -> success(call, host.currentRevision,
                host.writeWorkspaceFile(call.requiredString("path"), call.arguments["content"]?.toString() ?: throw IllegalArgumentException("缺少参数：content")))
            WatchAgentToolName.GENERATE_IMAGE -> success(call, host.currentRevision,
                requireNotNull(externalTools) { "未配置图片生成服务" }.generateImage(call.imageGenerationRequest()))
            WatchAgentToolName.EDIT_IMAGE -> success(call, host.currentRevision,
                requireNotNull(externalTools) { "未配置图片编辑服务" }.editImage(call.imageEditRequest()))
            WatchAgentToolName.SEARCH_WEB -> success(call, host.currentRevision,
                requireNotNull(externalTools) { "未配置搜索服务" }.searchWeb(call.requiredString("query")))
            WatchAgentToolName.SCRAPE_PAGE -> success(call, host.currentRevision,
                requireNotNull(externalTools) { "未配置网页抓取服务" }.scrapePage(call.requiredString("url")))
            WatchAgentToolName.RUN_COMMAND -> {
                val runner = requireNotNull(shellRunner) { "工作区命令运行器尚未初始化" }
                val command = call.requiredString("command")
                // The editor may have changed while the provider was thinking.
                // Establish a fresh baseline before the command touches face/;
                // otherwise post-command sync can mistake a stale mirror for
                // the command's result and roll the editor backwards.
                host.prepareBashMirror()
                val result = runner.run(ShellCommandRequest(
                    workspaceId = workspaceIdProvider(),
                    command = command,
                    timeoutSeconds = (call.arguments["timeout_seconds"] as? Number)?.toLong() ?: 30L,
                    maxOutputBytes = 256 * 1024,
                    runId = call.id,
                ))
                when (result) {
                    is com.ailife.wftools.agent.workspace.ShellRunResult.RootfsNotInstalled ->
                        failure(call, "rootfs_not_installed", "工作区 ${result.workspaceId} 的 Rootfs 未完成安装或状态尚未刷新")
                    is com.ailife.wftools.agent.workspace.ShellRunResult.WorkspaceNotFound ->
                        failure(call, "workspace_not_found", "工作区 ${result.workspaceId} 不存在或未绑定当前表盘")
                    is com.ailife.wftools.agent.workspace.ShellRunResult.RuntimeUnavailable ->
                        failure(call, "runtime_unavailable", "工作区 ${result.workspaceId} 的命令运行时不可用，请检查本机 PowerShell/Bash 能力")
                    is com.ailife.wftools.agent.workspace.ShellRunResult.Completed -> {
                        val sync = runCatching { host.commitFaceMirror(host.currentRevision) }
                        success(call, host.currentRevision, buildMap {
                            put("command", command)
                            put("stdout", result.stdout)
                            put("exit_code", result.exitCode)
                            put("truncated", result.truncated)
                            put("run_id", result.runId)
                            sync.getOrNull()?.let { put("face_mirror", it) }
                            sync.exceptionOrNull()?.let { put("face_mirror_sync_error", it.message ?: it.javaClass.simpleName) }
                        })
                    }
                    is com.ailife.wftools.agent.workspace.ShellRunResult.DryRun ->
                        success(call, host.currentRevision, mapOf(
                            "command" to command,
                            "stdout" to "",
                            "dry_run" to true,
                            "working_directory" to result.workingDirectory.toString(),
                        ))
                    is com.ailife.wftools.agent.workspace.ShellRunResult.RejectedApproval ->
                        failure(call, "approval_denied", "用户拒绝了本机命令")
                    is com.ailife.wftools.agent.workspace.ShellRunResult.AwaitingApproval ->
                        failure(call, "approval_required", "本机命令仍在等待用户批准")
                    is com.ailife.wftools.agent.workspace.ShellRunResult.InvalidRequest ->
                        failure(call, "invalid_command", result.reason)
                    else -> success(call, host.currentRevision, mapOf(
                        "command" to command,
                        "stdout" to result.toString(),
                    ))
                }
            }
            WatchAgentToolName.SYNC_FACE_MIRROR -> success(call, host.currentRevision, host.syncFaceMirror())
            WatchAgentToolName.VALIDATE_FACE_MIRROR -> success(call, host.currentRevision, host.validateFaceMirror())
            WatchAgentToolName.COMMIT_FACE_MIRROR -> {
                val revision = call.requiredRevision(); checkExpectedRevision(revision)
                success(call, host.currentRevision, host.commitFaceMirror(revision))
            }
            WatchAgentToolName.IMPORT_FACE_RESOURCE -> success(call, host.currentRevision, host.importFaceResource(call.requiredString("path")))
            WatchAgentToolName.INSPECT_FACE -> {
                val result = host.inspectFace(WatchAgentInspectFaceRequest(call.optionalRevision()))
                success(call, result.revision, result)
            }
            WatchAgentToolName.READ_DOCUMENT -> {
                val path = call.documentPath("path")
                val result = host.readDocument(WatchAgentReadDocumentRequest(path, call.optionalRevision()))
                success(call, result.revision, result)
            }
            WatchAgentToolName.SEARCH_KNOWLEDGE -> {
                val result = host.searchKnowledge(
                    WatchAgentSearchKnowledgeRequest(call.requiredString("query")),
                )
                success(call, host.currentRevision, result)
            }
            WatchAgentToolName.VALIDATE_FACE -> {
                val result = host.validateFace(WatchAgentValidateFaceRequest(call.optionalRevision()))
                success(call, result.revision, result)
            }
            WatchAgentToolName.PREVIEW_FACE -> {
                val result = host.previewFace(call.previewRequest())
                success(call, result.revision, result)
            }
            WatchAgentToolName.EDIT_DOCUMENT -> {
                val request = call.editRequest()
                checkExpectedRevision(request.expectedRevision)
                val result = host.editDocument(request)
                success(call, result.revision, result)
            }
            WatchAgentToolName.WRITE_DOCUMENT -> {
                val request = call.writeRequest()
                checkExpectedRevision(request.expectedRevision)
                val result = host.writeDocument(request)
                success(call, result.revision, result)
            }
            null -> failure(call, "unknown_tool", "未知工具：${call.name}")
        }
    } catch (error: WatchAgentToolException) {
        failure(call, error.code, error.message)
    } catch (error: IllegalArgumentException) {
        failure(call, "invalid_arguments", error.message ?: "工具参数无效")
    } catch (error: Throwable) {
        failure(call, "tool_failed", error.displayableMessage())
    }

    private fun checkExpectedRevision(expected: Long) {
        val actual = host.currentRevision
        if (expected != actual) throw staleRevision(expected, actual)
    }

    private fun currentPermissions(): WorkspacePermissionSettings =
        runCatching(permissionProvider).getOrDefault(DENY_ALL_PERMISSIONS)

    private fun success(call: WatchAgentToolCall, revision: Long, content: Any?) =
        WatchAgentToolResult(call.id, call.name, revision, content = content)

    private fun failure(call: WatchAgentToolCall, code: String, message: String) =
        WatchAgentToolResult(
            callId = call.id,
            name = call.name,
            revision = host.currentRevision,
            error = WatchAgentToolError(code, message),
        )
}

private val DENY_ALL_PERMISSIONS = WorkspacePermissionSettings(
    readAllowed = false,
    writeAllowed = false,
    attachmentImportAllowed = false,
)

private fun WorkspacePermissionSettings.allows(access: WatchAgentToolAccess): Boolean = when (access) {
    WatchAgentToolAccess.READ_ONLY -> readAllowed
    WatchAgentToolAccess.WRITE -> writeAllowed
}

private fun Throwable.displayableMessage(): String {
    val root = generateSequence(this) { it.cause }.lastOrNull() ?: this
    return when {
        this is ExceptionInInitializerError || root is ExceptionInInitializerError ->
            "工具初始化失败，已保护当前会话。请重试；若持续出现，请检查表盘预览和 Provider 配置。"
        !message.isNullOrBlank() -> message.orEmpty().take(500)
        !root.message.isNullOrBlank() -> root.message.orEmpty().take(500)
        else -> root.javaClass.simpleName
    }
}

private fun WatchAgentToolCall.requiredString(name: String): String =
    arguments[name]?.toString()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("缺少参数：$name")

private fun WatchAgentToolCall.optionalRevision(): Long? =
    arguments["expected_revision"]?.toLongValue()
        ?: arguments["base_revision"]?.toLongValue()

private fun WatchAgentToolCall.requiredRevision(): Long =
    optionalRevision() ?: throw IllegalArgumentException("缺少参数：expected_revision")

private fun Any.toLongValue(): Long = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: throw IllegalArgumentException("revision 必须是整数")
    else -> throw IllegalArgumentException("revision 必须是整数")
}

private fun WatchAgentToolCall.documentPath(name: String): WatchAgentDocumentPath =
    WatchAgentDocumentPath.fromWirePath(requiredString(name))
        ?: throw IllegalArgumentException("不支持的文档路径：${arguments[name]}")

private fun WatchAgentToolCall.editRequest() = WatchAgentEditDocumentRequest(
    path = documentPath("path"),
    oldText = requiredString("old_text"),
    newText = arguments["new_text"]?.toString() ?: "",
    expectedRevision = requiredRevision(),
)

private fun WatchAgentToolCall.writeRequest() = WatchAgentWriteDocumentRequest(
    path = documentPath("path"),
    content = arguments["content"]?.toString() ?: throw IllegalArgumentException("缺少参数：content"),
    expectedRevision = requiredRevision(),
)

private fun WatchAgentToolCall.previewRequest() = WatchAgentPreviewRequest(
    target = arguments["target"]?.toString()?.ifBlank { "face" } ?: "face",
    mode = when (arguments["mode"]?.toString()) {
        null, "in_context" -> WatchAgentPreviewMode.IN_CONTEXT
        "isolated" -> WatchAgentPreviewMode.ISOLATED
        else -> throw IllegalArgumentException("mode 必须是 in_context 或 isolated")
    },
    time = arguments["time"]?.toString(),
    battery = arguments["battery"]?.let { it.toLongValue().toInt() },
    display = when (arguments["display"]?.toString()) {
        null -> null
        "bright" -> WatchAgentDisplayMode.BRIGHT
        "dim" -> WatchAgentDisplayMode.DIM
        else -> throw IllegalArgumentException("display 必须是 bright 或 dim")
    },
    expectedRevision = optionalRevision(),
)

private fun WatchAgentToolCall.imageGenerationRequest() = WatchAgentImageGenerationRequest(
    prompt = requiredString("prompt"),
    model = optionalText("model"),
    size = optionalSize(),
    quality = optionalEnum("quality", setOf("auto", "low", "medium", "high", "xhigh", "max")),
    background = optionalEnum("background", setOf("auto", "transparent", "opaque")),
    outputFormat = optionalEnum("output_format", setOf("png", "webp", "jpeg")),
    outputCompression = optionalInt("output_compression", 0..100),
    moderation = optionalEnum("moderation", setOf("auto", "low")),
)

private fun WatchAgentToolCall.imageEditRequest() = WatchAgentImageEditRequest(
    path = requiredString("path"),
    prompt = requiredString("prompt"),
    maskPath = optionalText("mask_path"),
    model = optionalText("model"),
    size = optionalSize(),
    quality = optionalEnum("quality", setOf("auto", "low", "medium", "high", "xhigh", "max")),
    background = optionalEnum("background", setOf("auto", "transparent", "opaque")),
    outputFormat = optionalEnum("output_format", setOf("png", "webp", "jpeg")),
    outputCompression = optionalInt("output_compression", 0..100),
    inputFidelity = optionalEnum("input_fidelity", setOf("low", "high")),
)

private fun WatchAgentToolCall.optionalText(name: String): String? =
    arguments[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

private fun WatchAgentToolCall.optionalEnum(name: String, allowed: Set<String>): String? =
    optionalText(name)?.also { require(it in allowed) { "$name 必须是 ${allowed.joinToString("、")}" } }

private fun WatchAgentToolCall.optionalInt(name: String, range: IntRange): Int? =
    arguments[name]?.let { value ->
        val number = value.toLongValue().toInt()
        require(number.toLong() == value.toLongValue() && number in range) { "$name 必须是 ${range.first}–${range.last} 的整数" }
        number
    }

private fun WatchAgentToolCall.optionalSize(): String? =
    optionalText("size")?.also { value ->
        if (value == "auto") return@also
        val match = Regex("^(\\d{2,4})x(\\d{2,4})$").matchEntire(value)
            ?: throw IllegalArgumentException("size 必须是 auto 或 WIDTHxHEIGHT")
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        require(width in 16..4096 && height in 16..4096 && width % 16 == 0 && height % 16 == 0) {
            "size 的宽高必须是 16 的倍数且位于 16–4096"
        }
    }


