@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc.render

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ailife.wftools.pc.core.WatchDocumentStore
import com.ailife.wftools.pc.preview.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PreviewPerformanceTest {
    @Test fun mainSampleOffscreenFrameAndDeviceSwitchMeasurements():Unit=runBlocking {
        assumeTrue(System.getenv("WFT_PERF")=="1")
        val sample=Path.of(System.getProperty("watch.sample"))
        val start=System.nanoTime()
        val store=WatchDocumentStore(Files.createTempDirectory("watch-perf"))
        store.open(sample).use { doc ->
            val openMs=(System.nanoTime()-start)/1e6
            val project=doc.toWatchProject()
            RenderSession(doc.workDir.toFile()).use { render ->
                render.prepare(project)
                val script=render.script
                val durations=mutableListOf<Double>();val switches=mutableListOf<Double>()
                withContext(RenderUiDispatcher) {
                    val device=mutableStateOf(DevicePreviewSettings())
                    val scene=ImageComposeScene(520,540,Density(1f),coroutineContext=coroutineContext){WatchSurface(project,render,device.value)}
                    try {
                        repeat(10){scene.render(System.nanoTime()).close();delay(16)}
                        repeat(120){
                            val frameStart=System.nanoTime();scene.render(frameStart).close();durations+=(System.nanoTime()-frameStart)/1e6
                            delay(16)
                        }
                        repeat(20){index->
                            val (w,h)=DevicePreviewSettings.presets[index%4]
                            val switchStart=System.nanoTime();device.value=DevicePreviewSettings(w,h)
                            scene.render(System.nanoTime()).close();switches+=(System.nanoTime()-switchStart)/1e6
                        }
                    }finally{scene.close()}
                }
                assertSame("device switching must not replace Lua",script,render.script)
                assertFalse(doc.hasUnsavedChanges)
                fun percentile(values:List<Double>,fraction:Double)=values.sorted()[(values.size*fraction).toInt().coerceAtMost(values.lastIndex)]
                val heap=(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024/1024
                val report="""{"kind":"Skia offscreen reference; not a physical monitor measurement","layers":${project.wmLayers.size},"openMs":$openMs,"frameMedianMs":${percentile(durations,.5)},"frameP95Ms":${percentile(durations,.95)},"deviceSwitchMedianMs":${percentile(switches,.5)},"heapMiB":$heap,"luaUnchanged":true,"documentUnmodified":true}"""
                val out=Path.of("build","review");Files.createDirectories(out);Files.writeString(out.resolve("performance.json"),report)
                println(report)
            }
        }
    }
}
