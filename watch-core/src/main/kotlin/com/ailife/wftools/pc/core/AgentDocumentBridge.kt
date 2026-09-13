package com.ailife.wftools.pc.core

data class AgentDocumentCandidate(val revision: Long, val watchXml: String, val globalScript: String)

class AgentUndoGroup internal constructor(private val finish: (Boolean) -> Unit) {
    private var done = false
    fun commit() { if (!done) { done = true; finish(true) } }
    fun rollback() { if (!done) { done = true; finish(false) } }
}
