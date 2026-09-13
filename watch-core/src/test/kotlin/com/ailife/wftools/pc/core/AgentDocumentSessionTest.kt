package com.ailife.wftools.pc.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDocumentSessionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `agent group accepts added layers as one undo and rejects stale revision`() {
        val store = WatchDocumentStore(temp.newFolder().toPath())
        store.newDocument("Agent").use { session ->
            val before = session.agentCandidate()
            val group = session.beginAgentUndoGroup()
            val extra = before.watchXml.replace("</Watch>", "<Layer type=\"text\" text=\"new\"/></Watch>")
            session.replaceAgentDocument("watch.xml", extra, before.revision)
            session.replaceAgentDocument("scripts/script.txt", "var_x = 1", before.revision)
            group.commit()
            assertTrue(session.agentCandidate().watchXml.contains("text=\"new\""))
            assertTrue(session.canUndo)
            assertEquals(before.revision + 1, session.revision)
            assertThrows(IllegalStateException::class.java) { session.replaceAgentDocument("watch.xml", before.watchXml, before.revision) }
            session.undo()
            assertEquals(before.watchXml, session.agentCandidate().watchXml)
            assertEquals(before.globalScript, session.agentCandidate().globalScript)
        }
    }

    @Test fun `invalid candidate and rollback leave document unchanged`() {
        val store = WatchDocumentStore(temp.newFolder().toPath())
        store.newDocument("Agent").use { session ->
            val before = session.agentCandidate()
            val group = session.beginAgentUndoGroup()
            assertThrows(Exception::class.java) { session.replaceAgentDocument("watch.xml", "<broken>", before.revision) }
            group.rollback()
            assertEquals(before.watchXml, session.agentCandidate().watchXml)
            assertEquals(before.revision, session.revision)
            assertFalse(session.canUndo)
        }
    }
}
