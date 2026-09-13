package com.ailife.wftools.agent.model

enum class AgentMessageLayout {
    USER_BUBBLE,
    ASSISTANT_FLOW,
    TOOL_STEP,
}

fun agentMessageLayout(message: AgentMessage): AgentMessageLayout = when {
    message.toolName != null || message.role == AgentMessageRole.TOOL -> AgentMessageLayout.TOOL_STEP
    message.role == AgentMessageRole.USER -> AgentMessageLayout.USER_BUBBLE
    else -> AgentMessageLayout.ASSISTANT_FLOW
}

fun shouldRenderMessageBody(message: AgentMessage): Boolean =
    agentMessageLayout(message) != AgentMessageLayout.TOOL_STEP


