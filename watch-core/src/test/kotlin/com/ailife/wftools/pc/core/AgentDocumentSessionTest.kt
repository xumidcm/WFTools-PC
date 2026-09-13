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
            val first = session.replaceAgentDocument("watch.xml", extra, before.revision)
            assertEquals(before.revision + 1, first.revision)
            session.replaceAgentDocument("scripts/script.txt", "var_x = 1", first.revision)
            group.commit()
            assertTrue(session.agentCandidate().watchXml.contains("text=\"new\""))
            assertTrue(session.canUndo)
            assertTrue(session.revision >= before.revision + 2)
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

    @Test fun `agent script validation executes first clock callback`() {
        val store = WatchDocumentStore(temp.newFolder().toPath())
        store.newDocument("Agent").use { session ->
            val before = session.agentCandidate()
            assertThrows(IllegalArgumentException::class.java) {
                session.replaceAgentDocument("scripts/script.txt", "function on_second() error('clock failure') end", before.revision)
            }
            assertEquals(before.revision, session.revision)
        }
    }

    @Test fun `face mirror resource replacements persist into watch archive and undo together`() {
        val store = WatchDocumentStore(temp.newFolder("mirror-work").toPath())
        val source = temp.root.toPath().resolve("mirror.watch")
        java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(source)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("watch.xml"))
            zip.write("<Watch name=\"A\"><Layers/></Watch>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("images/a.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        store.open(source).use { session ->
            val before = session.agentCandidate()
            val group = session.beginAgentUndoGroup()
            session.restoreAgentMirror(before.watchXml.replace("name=\"A\"", "name=\"B\""), "var_x=1",
                mapOf("images/a.png" to byteArrayOf(4, 5, 6), "assets/note.txt" to "hello".toByteArray()))
            group.commit()
            assertArrayEquals(byteArrayOf(4, 5, 6), java.nio.file.Files.readAllBytes(session.workDir.resolve("images/a.png")))
            store.save(session, source)
            java.util.zip.ZipFile(source.toFile()).use { zip ->
                assertArrayEquals(byteArrayOf(4, 5, 6), zip.getInputStream(zip.getEntry("images/a.png")).readAllBytes())
                assertEquals("hello", zip.getInputStream(zip.getEntry("assets/note.txt")).reader().readText())
            }
            session.undo()
            assertEquals(before.watchXml, session.agentCandidate().watchXml)
            assertArrayEquals(byteArrayOf(1, 2, 3), java.nio.file.Files.readAllBytes(session.workDir.resolve("images/a.png")))
        }
    }
}
