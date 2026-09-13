package com.ailife.wftools.agent.tools

/** The document tool names exposed to a provider. Keep these wire names stable. */
enum class WatchAgentToolName(
    val wireName: String,
    val access: WatchAgentToolAccess,
) {
    INSPECT_FACE("inspect_face", WatchAgentToolAccess.READ_ONLY),
    READ_DOCUMENT("read_document", WatchAgentToolAccess.READ_ONLY),
    SEARCH_KNOWLEDGE("search_knowledge", WatchAgentToolAccess.READ_ONLY),
    VALIDATE_FACE("validate_face", WatchAgentToolAccess.READ_ONLY),
    PREVIEW_FACE("preview_face", WatchAgentToolAccess.READ_ONLY),
    EDIT_DOCUMENT("edit_document", WatchAgentToolAccess.WRITE),
    WRITE_DOCUMENT("write_document", WatchAgentToolAccess.WRITE),
    LIST_WORKSPACE_FILES("list_workspace_files", WatchAgentToolAccess.READ_ONLY),
    READ_IMAGE("read_image", WatchAgentToolAccess.READ_ONLY),
    WRITE_WORKSPACE_FILE("write_workspace_file", WatchAgentToolAccess.WRITE),
    RUN_COMMAND("run_command", WatchAgentToolAccess.WRITE),
    SYNC_FACE_MIRROR("sync_face_mirror", WatchAgentToolAccess.WRITE),
    VALIDATE_FACE_MIRROR("validate_face_mirror", WatchAgentToolAccess.READ_ONLY),
    COMMIT_FACE_MIRROR("commit_face_mirror", WatchAgentToolAccess.WRITE),
    IMPORT_FACE_RESOURCE("import_face_resource", WatchAgentToolAccess.WRITE),
    GENERATE_IMAGE("generate_image", WatchAgentToolAccess.WRITE),
    EDIT_IMAGE("edit_image", WatchAgentToolAccess.WRITE),
    SEARCH_WEB("search_web", WatchAgentToolAccess.READ_ONLY),
    SCRAPE_PAGE("scrape_page", WatchAgentToolAccess.READ_ONLY),
    ;

    val isReadOnly: Boolean get() = access == WatchAgentToolAccess.READ_ONLY
    val isWrite: Boolean get() = access == WatchAgentToolAccess.WRITE

    companion object {
        fun fromWireName(value: String): WatchAgentToolName? =
            entries.firstOrNull { it.wireName == value } ?: if (value == "run_bash") RUN_COMMAND else null
    }
}

enum class WatchAgentToolAccess { READ_ONLY, WRITE }

data class WatchAgentToolParameter(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
)

data class WatchAgentToolContract(
    val name: WatchAgentToolName,
    val description: String,
    val parameters: List<WatchAgentToolParameter> = emptyList(),
) {
    val access: WatchAgentToolAccess get() = name.access
}

/** Versioned schema metadata is intentionally available without a runner instance. */
object WatchAgentToolContracts {
    const val schemaVersion: String = "watch-agent-tools-v1"

    val all: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(WatchAgentToolName.LIST_WORKSPACE_FILES, "列出当前绑定工作区的文件，包括 workspace、generated、previews 和 home。图片路径可用于 read_image。"),
        WatchAgentToolContract(WatchAgentToolName.READ_IMAGE, "读取当前工作区内的图片、生成图片或预览，返回真实图片供视觉分析。",
            listOf(WatchAgentToolParameter("path", "string", required = true))),
        WatchAgentToolContract(
            WatchAgentToolName.WRITE_WORKSPACE_FILE,
            "将文本安全写入绑定工作区的 workspace/、generated/ 或 home/ 文件，避免通过 Shell 拼接内容时发生转义错误。",
            listOf(
                WatchAgentToolParameter("path", "string", required = true),
                WatchAgentToolParameter("content", "string", required = true),
            ),
        ),
        WatchAgentToolContract(WatchAgentToolName.RUN_COMMAND, "在绑定的桌面工作区中执行本机命令（Windows 使用 PowerShell，Linux 使用 Bash）。",
            listOf(WatchAgentToolParameter("command", "string", required = true), WatchAgentToolParameter("timeout_seconds", "integer"))),
        WatchAgentToolContract(
            WatchAgentToolName.INSPECT_FACE,
            "读取当前表盘版本、图层结构、素材清单以及预览状态。编辑前先调用。",
        ),
        WatchAgentToolContract(
            WatchAgentToolName.READ_DOCUMENT,
            "读取完整 watch.xml 或 scripts/script.txt，返回 revision。",
            listOf(WatchAgentToolParameter("path", "string", required = true)),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.SEARCH_KNOWLEDGE,
            "检索内置 WatchMaker 格式、标签和兼容知识。",
            listOf(WatchAgentToolParameter("query", "string", required = true)),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.VALIDATE_FACE,
            "检查当前 XML 结构、资源、路径和表达式诊断。",
        ),
        WatchAgentToolContract(
            WatchAgentToolName.PREVIEW_FACE,
            "通过宿主原生渲染器预览当前表盘或图层。",
            listOf(
                WatchAgentToolParameter("target", "string"),
                WatchAgentToolParameter("mode", "string", enumValues = listOf("in_context", "isolated")),
                WatchAgentToolParameter("time", "string"),
                WatchAgentToolParameter("battery", "number"),
                WatchAgentToolParameter("display", "string", enumValues = listOf("bright", "dim")),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.EDIT_DOCUMENT,
            "按唯一 old_text 精确替换当前 revision 中的文档片段。",
            listOf(
                WatchAgentToolParameter("path", "string", required = true),
                WatchAgentToolParameter("old_text", "string", required = true),
                WatchAgentToolParameter("new_text", "string", required = true),
                WatchAgentToolParameter("expected_revision", "integer", required = true),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.WRITE_DOCUMENT,
            "以 expected_revision 为基础提交完整文档。",
            listOf(
                WatchAgentToolParameter("path", "string", required = true),
                WatchAgentToolParameter("content", "string", required = true),
                WatchAgentToolParameter("expected_revision", "integer", required = true),
            ),
        ),
    )
    val mirror: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(WatchAgentToolName.SYNC_FACE_MIRROR, "同步当前表盘到绑定工作区 face/ 镜像。"),
        WatchAgentToolContract(WatchAgentToolName.VALIDATE_FACE_MIRROR, "校验 face/ 镜像。"),
        WatchAgentToolContract(WatchAgentToolName.COMMIT_FACE_MIRROR, "按 expected_revision 提交 face/ 镜像回当前表盘。", listOf(WatchAgentToolParameter("expected_revision", "integer", true))),
        WatchAgentToolContract(WatchAgentToolName.IMPORT_FACE_RESOURCE, "导入工作区表盘资源；path 可直接使用 workspace/、generated/ 或 home/ 中的文件，不需要先移动到 images/。桌面宿主会把 Agent 生成的 SVG 自动转换为 PNG，并在结果中返回 resource_path 供 image 图层使用。", listOf(WatchAgentToolParameter("path", "string", true))),
    )
    val external: List<WatchAgentToolContract> = listOf(
        WatchAgentToolContract(
            WatchAgentToolName.GENERATE_IMAGE,
            "调用 OpenAI 兼容图片接口生成表盘素材，结果保存在工作区 generated/。可以自行选择模型、尺寸、质量、透明背景和输出格式；size 支持 auto 或 WIDTHxHEIGHT（宽高为 16 的倍数）。",
            listOf(
                WatchAgentToolParameter("prompt", "string", true),
                WatchAgentToolParameter("model", "string"),
                WatchAgentToolParameter("size", "string", enumValues = emptyList()),
                WatchAgentToolParameter("quality", "string", enumValues = listOf("auto", "low", "medium", "high", "xhigh", "max")),
                WatchAgentToolParameter("background", "string", enumValues = listOf("auto", "transparent", "opaque")),
                WatchAgentToolParameter("output_format", "string", enumValues = listOf("png", "webp", "jpeg")),
                WatchAgentToolParameter("output_compression", "integer"),
                WatchAgentToolParameter("moderation", "string", enumValues = listOf("auto", "low")),
            ),
        ),
        WatchAgentToolContract(
            WatchAgentToolName.EDIT_IMAGE,
            "调用 OpenAI 兼容图片编辑接口修改表盘或工作区里的图片。path 可来自 inspect_face、list_workspace_files 或当前表盘素材，结果保存在 generated/；size 支持 auto 或 WIDTHxHEIGHT（宽高为 16 的倍数）。",
            listOf(
                WatchAgentToolParameter("path", "string", true),
                WatchAgentToolParameter("prompt", "string", true),
                WatchAgentToolParameter("mask_path", "string"),
                WatchAgentToolParameter("model", "string"),
                WatchAgentToolParameter("size", "string", enumValues = emptyList()),
                WatchAgentToolParameter("quality", "string", enumValues = listOf("auto", "low", "medium", "high", "xhigh", "max")),
                WatchAgentToolParameter("background", "string", enumValues = listOf("auto", "transparent", "opaque")),
                WatchAgentToolParameter("output_format", "string", enumValues = listOf("png", "webp", "jpeg")),
                WatchAgentToolParameter("output_compression", "integer"),
                WatchAgentToolParameter("input_fidelity", "string", enumValues = listOf("low", "high")),
            ),
        ),
        WatchAgentToolContract(WatchAgentToolName.SEARCH_WEB, "使用已配置的搜索接口查找外部资料，返回标题、链接和摘要。", listOf(WatchAgentToolParameter("query", "string", true))),
        WatchAgentToolContract(WatchAgentToolName.SCRAPE_PAGE, "抓取指定 HTTP(S) 网页的正文和标题，供 Agent 阅读页面内容。", listOf(WatchAgentToolParameter("url", "string", true))),
    )

    fun find(name: String): WatchAgentToolContract? =
        WatchAgentToolName.fromWireName(name)?.let(::find)

    fun find(name: WatchAgentToolName): WatchAgentToolContract? =
        (all + mirror + external).firstOrNull { it.name == name }
}

data class WatchAgentToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
) {
    val toolName: WatchAgentToolName?
        get() = WatchAgentToolName.fromWireName(name)
}

typealias WatchAgentCall = WatchAgentToolCall

data class WatchAgentToolError(
    val code: String,
    val message: String,
)

data class WatchAgentToolResult(
    val callId: String,
    val name: String,
    val revision: Long,
    val content: Any? = null,
    val error: WatchAgentToolError? = null,
) {
    val succeeded: Boolean get() = error == null
}

typealias WatchAgentResult = WatchAgentToolResult

enum class WatchAgentDocumentPath(val wirePath: String) {
    WATCH_XML("watch.xml"),
    GLOBAL_SCRIPT("scripts/script.txt"),
    ;

    companion object {
        fun fromWirePath(value: String): WatchAgentDocumentPath? =
            entries.firstOrNull { it.wirePath == value }
    }
}

data class WatchAgentInspectFaceRequest(val expectedRevision: Long? = null)

data class WatchAgentReadDocumentRequest(
    val path: WatchAgentDocumentPath,
    val expectedRevision: Long? = null,
)

data class WatchAgentSearchKnowledgeRequest(val query: String)

data class WatchAgentValidateFaceRequest(val expectedRevision: Long? = null)

enum class WatchAgentPreviewMode { IN_CONTEXT, ISOLATED }
enum class WatchAgentDisplayMode { BRIGHT, DIM }

data class WatchAgentPreviewRequest(
    val target: String = "face",
    val mode: WatchAgentPreviewMode = WatchAgentPreviewMode.IN_CONTEXT,
    val time: String? = null,
    val battery: Int? = null,
    val display: WatchAgentDisplayMode? = null,
    val expectedRevision: Long? = null,
)

data class WatchAgentEditDocumentRequest(
    val path: WatchAgentDocumentPath,
    val oldText: String,
    val newText: String,
    val expectedRevision: Long,
) {
    val baseRevision: Long get() = expectedRevision
}

data class WatchAgentWriteDocumentRequest(
    val path: WatchAgentDocumentPath,
    val content: String,
    val expectedRevision: Long,
) {
    val baseRevision: Long get() = expectedRevision
}



