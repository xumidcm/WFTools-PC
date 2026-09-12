package com.ailife.wftools.pc.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Opt in explicitly; this measures core commands and draft IO, not complete UI latency. */
class CorePerformanceTest {
    @Test fun recordNudgeLatencyAndDraftGrowthOnCopiedSample() {
        assumeTrue("Set WFT_CORE_PERF=1 for the sample benchmark", System.getenv("WFT_CORE_PERF") == "1")
        val configured = System.getProperty("watch.sample", "")
        assumeTrue("Configure -PwatchSamplePath with a copied fixture", configured.isNotBlank())
        val input = Path.of(configured).toAbsolutePath().normalize()
        require(!input.toString().replace('\\', '/').equals("D:/Administrator/Downloads/312.watch", true)) { "Use the verified copied fixture, never the original sample." }
        val original = Files.readAllBytes(input)
        val root = Files.createTempDirectory("watch-core-perf-")
        val fixture = root.resolve("sample.watch"); Files.copy(input, fixture)
        val store = WatchDocumentStore(root.resolve("sessions"))
        val report = mutableListOf<String>()
        store.open(fixture).use { session ->
            val ids = session.layers.map { it.id }; assertTrue(ids.isNotEmpty())
            session.select(ids.first(), ids)
            report += "layers=${ids.size} xmlBytes=${session.state.xml.toByteArray().size} fixtureSha256=${WatchDocumentStore.hashFile(fixture)}"
            for (step in 1..50) {
                val start = System.nanoTime()
                session.nudge(0.1, 0.0)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                if (step in setOf(1, 10, 50)) {
                    val bytes = Files.size(session.home.resolve("draft.json"))
                    report += "step=$step nudgeMs=${String.format(Locale.ROOT, "%.3f", elapsedMs)} draftBytes=$bytes"
                }
            }
            report += "workDir=${session.workDir}"
        }
        assertArrayEquals(original, Files.readAllBytes(fixture))
        assertArrayEquals(original, Files.readAllBytes(input))
        val output = root.resolve("nudge-performance.txt")
        Files.write(output, report)
        println("WFT_CORE_PERF_BEGIN\n${report.joinToString("\n")}\nreport=$output\nWFT_CORE_PERF_END")
    }
}
