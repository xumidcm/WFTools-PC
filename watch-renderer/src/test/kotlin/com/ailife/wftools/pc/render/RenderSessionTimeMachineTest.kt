package com.ailife.wftools.pc.render

import java.time.Duration
import java.time.ZoneId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RenderSessionTimeMachineTest {
    @Test fun `return to now clears time machine offset and restores the wall clock`() = runBlocking {
        val session = RenderSession(java.nio.file.Files.createTempDirectory("time-machine-").toFile(), autoPlay = false, silentMedia = true)
        try {
            session.timeOffsetMillis = -86_400_000L
            session.timeSpeed = 3600.0
            session.resetTimeMachineToNow().join()

            assertEquals(0L, session.timeOffsetMillis)
            assertEquals(1.0, session.timeSpeed, 0.0)
            val rendered = session.frame.now.atZone(ZoneId.systemDefault()).toInstant()
            val difference = kotlin.math.abs(Duration.between(Instant.now(), rendered).toMillis())
            assertTrue("reset clock must be close to the current wall clock", difference < 2_000L)
        } finally {
            session.close()
        }
    }
}
