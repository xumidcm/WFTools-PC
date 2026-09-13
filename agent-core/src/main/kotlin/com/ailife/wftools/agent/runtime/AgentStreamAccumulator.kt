package com.ailife.wftools.agent.runtime

/**
 * Keeps provider chunks off the persistence hot path. The UI receives bounded
 * snapshots while the complete text remains available for the final commit.
 */
internal class AgentStreamAccumulator(
    private val minPublishIntervalMs: Long = 50L,
) {
    private val lock = Any()
    private val content = StringBuilder()
    private var lastPublishMs: Long? = null

    init {
        require(minPublishIntervalMs >= 0L)
    }

    fun append(delta: String, nowMs: Long): String? = synchronized(lock) {
        content.append(delta)
        val last = lastPublishMs
        if (last == null || nowMs - last >= minPublishIntervalMs) {
            lastPublishMs = nowMs
            content.toString()
        } else null
    }

    fun snapshot(): String = synchronized(lock) { content.toString() }

    fun reset() = synchronized(lock) {
        content.setLength(0)
        lastPublishMs = null
    }
}


