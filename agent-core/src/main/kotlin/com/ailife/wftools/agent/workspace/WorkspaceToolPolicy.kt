package com.ailife.wftools.agent.workspace

enum class BashApprovalMode {
    EACH_COMMAND,
    HIGH_RISK_ONLY,
    AUTOMATIC,
}

enum class BashRisk {
    LOW,
    HIGH,
}

class WorkspaceToolPolicy(
    private val mode: BashApprovalMode = BashApprovalMode.EACH_COMMAND,
) : BashApprovalStrategy {
    override fun decide(request: ShellCommandRequest): BashApproval = when (mode) {
        BashApprovalMode.EACH_COMMAND -> BashApproval.REQUIRE_CONFIRMATION
        BashApprovalMode.HIGH_RISK_ONLY ->
            if (classify(request.command) == BashRisk.HIGH) BashApproval.REQUIRE_CONFIRMATION else BashApproval.ALLOW
        BashApprovalMode.AUTOMATIC -> BashApproval.ALLOW
    }

    fun classify(command: String): BashRisk =
        if (HIGH_RISK_PATTERNS.any { it.containsMatchIn(command) }) BashRisk.HIGH else BashRisk.LOW

    companion object {
        private val HIGH_RISK_PATTERNS = listOf(
            Regex("(^|[;&|])\\s*rm\\s+-[a-zA-Z]*r"),
            Regex("(^|[;&|])\\s*(mkfs|fdisk|dd)\\b"),
            Regex("\\b(shutdown|reboot|poweroff)\\b"),
            Regex("/proc|/sys|/dev/"),
            Regex("(curl|wget)[^\\n|]*\\|\\s*(sh|bash)"),
            Regex(":\\(\\)\\s*\\{"),
        )
    }
}


