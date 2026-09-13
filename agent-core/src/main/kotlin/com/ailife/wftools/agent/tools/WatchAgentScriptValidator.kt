package com.ailife.wftools.agent.tools

import com.ailife.wftools2.domain.editor.watchmaker.WmScriptRuntime

/** Compiles the persisted WatchMaker global script with the same sandbox used at render time. */
internal fun validateGlobalScript(source: String): WatchAgentDiagnostic? {
    if (source.isBlank()) return null
    return runCatching { WmScriptRuntime().validateScript(source) }
        .getOrNull()
        ?.let { error ->
            WatchAgentDiagnostic(
                severity = WatchAgentDiagnosticSeverity.ERROR,
                code = "invalid_global_script",
                message = "全局脚本无法编译或执行：$error",
            )
        }
}



