package com.ailife.wftools.pc.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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

    @Test fun `linux document workspace is private when POSIX permissions are available`() {
        val root=Files.createTempDirectory("watch-private-workspace-")
        if(!Files.getFileStore(root).supportsFileAttributeView("posix"))return
        val store=WatchDocumentStore(root.resolve("sessions"))
        store.newDocument().use { session ->
            assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(store.workspaceRoot))
            assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(session.home))
        }
    }

    @Test fun `linux default workspace follows XDG data home`() {
        val home=Path.of("/home/tester")
        assertEquals(Path.of("/custom/data/WFTools-PC/workspace"),WatchDocumentStore.defaultWorkspace(mapOf("XDG_DATA_HOME" to "/custom/data"),home,"Linux"))
        assertEquals(home.resolve(".local/share/WFTools-PC/workspace"),WatchDocumentStore.defaultWorkspace(emptyMap(),home,"Linux"))
    }
}
