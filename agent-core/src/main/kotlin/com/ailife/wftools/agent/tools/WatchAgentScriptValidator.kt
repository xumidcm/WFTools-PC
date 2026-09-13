package com.ailife.wftools.agent.tools

import com.ailife.wftools.pc.core.validateAgentGlobalScript

/** Compiles the persisted WatchMaker global script with the same sandbox used at render time. */
internal fun validateGlobalScript(source: String): WatchAgentDiagnostic? {
    if (source.isBlank()) return null
    return runCatching { validateAgentGlobalScript(source) }
        .getOrElse { it.message ?: "脚本无法执行" }
        ?.let { error ->
            WatchAgentDiagnostic(
                severity = WatchAgentDiagnosticSeverity.ERROR,
                code = "invalid_global_script",
                message = "全局脚本无法编译或执行：$error",
            )
        }
}



