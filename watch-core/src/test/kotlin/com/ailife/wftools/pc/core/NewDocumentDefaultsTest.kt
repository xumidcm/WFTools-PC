package com.ailife.wftools.pc.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class NewDocumentDefaultsTest {
    @Test fun newDocumentAndSavedArchiveAreSquare512() {
        val root=Files.createTempDirectory("watch-new-default-")
        val store=WatchDocumentStore(root.resolve("sessions"))
        store.newDocument().use { session ->
            val project=session.toWatchProject()
            assertEquals("Square",project.shape);assertEquals(512,project.width);assertEquals(512,project.height)
            val saved=root.resolve("new.watch");store.save(session,saved)
            store.open(saved).use { reopened ->
                assertEquals("Square",reopened.toWatchProject().shape)
                assertEquals("S",reopened.toWatchProject().rootAttrs["shape"])
                assertEquals(512,reopened.toWatchProject().width);assertEquals(512,reopened.toWatchProject().height)
            }
        }
    }
}
