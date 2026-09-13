package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.WmScriptRuntime

/** Checks syntax, top-level execution, and the first clock callback in the render sandbox. */
fun validateAgentGlobalScript(source: String): String? {
    if (source.isBlank()) return null
    val runtime = WmScriptRuntime()
    runtime.validateScript(source)?.let { return it }
    if (!runtime.runScript(source)) return runtime.lastError ?: "脚本执行失败"
    runtime.fireTimeCallbacks(0, 0, 0)
    return runtime.lastError
}
