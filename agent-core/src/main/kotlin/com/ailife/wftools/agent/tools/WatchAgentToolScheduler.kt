package com.ailife.wftools.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface WatchAgentToolExecutor {
    fun execute(call: WatchAgentToolCall): WatchAgentToolResult
}

/**
 * Batches independent read tools while making every document write a barrier.
 * [execute] returns the same order as [calls], regardless of completion order.
 * Reads suspend the caller and run on IO; previews and mutations retain the
 * caller's editor dispatcher. Cancellation interrupts blocking read operations.
 */
class WatchAgentToolScheduler(
    private val executor: WatchAgentToolExecutor,
    maxParallel: Int = DEFAULT_MAX_PARALLEL,
) {
    private val maxParallel = maxParallel.coerceIn(1, MAX_PARALLEL)

    @Volatile
    var lastResults: List<WatchAgentToolResult> = emptyList()
        private set

    suspend fun execute(calls: List<WatchAgentToolCall>): List<WatchAgentToolResult> {
        currentCoroutineContext().ensureActive()
        if (calls.isEmpty()) {
            lastResults = emptyList()
            return emptyList()
        }
        // Providers may repeat a completed tool call while merging streamed
        // deltas. Execute each logical call once so a duplicate cannot leave
        // a second UI row waiting forever for a result.
        val uniqueCalls = calls.distinctBy { call ->
            call.id.takeIf(String::isNotBlank) ?: "${call.name}:${call.arguments}"
        }
        val results = ArrayList<WatchAgentToolResult>(calls.size)
        val reads = ArrayList<WatchAgentToolCall>()

        suspend fun flushReads() {
            if (reads.isEmpty()) return
            results += executeReadBatch(reads)
            reads.clear()
        }

        uniqueCalls.forEach { call ->
            currentCoroutineContext().ensureActive()
            // Native preview owns a main-thread renderer and bitmap lifecycle;
            // never run it in the parallel read pool or alongside another read.
            if (call.toolName == WatchAgentToolName.PREVIEW_FACE) {
                flushReads()
                results += executor.execute(call)
            } else if (call.toolName?.isReadOnly == true) {
                reads += call
            } else {
                flushReads()
                results += executor.execute(call)
            }
        }
        flushReads()
        lastResults = results.toList()
        return lastResults
    }

    private suspend fun executeReadBatch(calls: List<WatchAgentToolCall>): List<WatchAgentToolResult> = coroutineScope {
        val permits = Semaphore(maxParallel)
        calls.map { call ->
            async {
                permits.withPermit {
                    runInterruptible(Dispatchers.IO) { executor.execute(call) }
                }
            }
        }.awaitAll() // Result order remains input order. Only reads leave the caller's dispatcher.
    }

    companion object {
        const val DEFAULT_MAX_PARALLEL: Int = 4
        const val MAX_PARALLEL: Int = 4
    }
}


