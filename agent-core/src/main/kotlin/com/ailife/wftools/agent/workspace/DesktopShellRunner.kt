package com.ailife.wftools.agent.workspace

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Native desktop shell runner. Commands are confined to the managed workspace. */
data class ShellCommandRequest(
    val workspaceId: String,
    val command: String,
    val timeoutSeconds: Long = 60L,
    val maxOutputBytes: Long = 64L * 1024L,
    val dryRun: Boolean = false,
    val runId: String? = null,
)

enum class BashApproval { ALLOW, DENY, REQUIRE_CONFIRMATION }
fun interface BashApprovalStrategy { fun decide(request: ShellCommandRequest): BashApproval }

sealed interface ShellRunResult {
    data class DryRun(val workspaceId: String, val command: String, val workingDirectory: Path) : ShellRunResult
    data class Completed(val runId: String, val exitCode: Int, val stdout: String, val truncated: Boolean) : ShellRunResult
    data class WorkspaceNotFound(val workspaceId: String) : ShellRunResult
    data class RootfsNotInstalled(val workspaceId: String) : ShellRunResult
    data class RuntimeUnavailable(val workspaceId: String) : ShellRunResult
    data class InvalidRequest(val reason: String) : ShellRunResult
    data object RejectedApproval : ShellRunResult
    data object AwaitingApproval : ShellRunResult
}

interface ProotShellRunner {
    fun run(request: ShellCommandRequest): ShellRunResult
    fun cancel(runId: String): Boolean
    fun capability(workspaceId: String): WorkspaceCapability
}

class DesktopShellRunner(
    private val manager: WorkspaceManager,
    private val approval: BashApprovalStrategy = BashApprovalStrategy { BashApproval.REQUIRE_CONFIRMATION },
    private val windows: Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true),
) : ProotShellRunner {
    private val processes = ConcurrentHashMap<String, Process>()
    override fun capability(workspaceId: String): WorkspaceCapability {
        val workspace = manager.get(workspaceId)
            ?: return WorkspaceCapability(workspaceId, false, false, false, CapabilityReason.WORKSPACE_NOT_FOUND)
        val shell = if (windows) commandAvailable("pwsh") || commandAvailable("powershell") else commandAvailable("bash")
        val python = commandAvailable(if (windows) "python" else "python3")
        return WorkspaceCapability(workspaceId, true, shell, python,
            when { !shell -> CapabilityReason.BASH_NOT_AVAILABLE; !python -> CapabilityReason.PYTHON_NOT_AVAILABLE; else -> CapabilityReason.READY })
    }
    override fun run(request: ShellCommandRequest): ShellRunResult {
        val workspace = manager.get(request.workspaceId) ?: return ShellRunResult.WorkspaceNotFound(request.workspaceId)
        if (request.command.isBlank()) return ShellRunResult.InvalidRequest("命令不能为空")
        if (request.timeoutSeconds !in 1..600) return ShellRunResult.InvalidRequest("超时时间必须为 1–600 秒")
        when (approval.decide(request)) { BashApproval.DENY -> return ShellRunResult.RejectedApproval; BashApproval.REQUIRE_CONFIRMATION -> return ShellRunResult.AwaitingApproval; BashApproval.ALLOW -> Unit }
        val runId = request.runId ?: UUID.randomUUID().toString()
        val cwd = workspace.paths.workspace
        if (request.dryRun) return ShellRunResult.DryRun(request.workspaceId, request.command, cwd)
        val command = if (windows) {
            val shell = if (commandAvailable("pwsh")) "pwsh" else "powershell"
            listOf(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", request.command)
        } else listOf("bash", "-lc", request.command)
        val process = try { ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start() }
            catch (e: Exception) { return ShellRunResult.RuntimeUnavailable(request.workspaceId) }
        processes[runId] = process
        return try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(request.timeoutSeconds)
            var truncated = false
            while (true) {
                while (process.inputStream.available() > 0) {
                    val read = process.inputStream.read(buffer)
                    if (read <= 0) break
                    val remaining = request.maxOutputBytes - out.size()
                    if (remaining > 0) out.write(buffer, 0, minOf(read, remaining.toInt()))
                    if (read > remaining) truncated = true
                }
                if (!process.isAlive) break
                if (System.nanoTime() >= deadline) { killTree(process); return ShellRunResult.Completed(runId, 124, out.toString(StandardCharsets.UTF_8), true) }
                Thread.sleep(10)
            }
            ShellRunResult.Completed(runId, process.exitValue(), out.toString(StandardCharsets.UTF_8), truncated)
        } finally { processes.remove(runId) }
    }
    override fun cancel(runId: String): Boolean = processes.remove(runId)?.let { killTree(it); true } ?: false
    fun cancelAll() { processes.keys.toList().forEach(::cancel) }
    private fun killTree(process: Process) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly() }
    private fun commandAvailable(command: String): Boolean = runCatching { ProcessBuilder(command, "--version").redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
}

