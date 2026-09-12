package com.ailife.wftools.pc.render

import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RuntimeIntegrationTest {
    @Test fun changingScriptReplacesGlobalsAndDoesNotAffectOtherDocuments() = runBlocking {
        val dir=Files.createTempDirectory("watch-script-render").toFile()
        RenderSession(dir,false).use { first -> RenderSession(dir,false).use { second ->
            first.prepare(WmWatchfaceProject(globalScript="var_old=42; function on_second(m,s) var_old=99 end"))
            second.prepare(WmWatchfaceProject(globalScript="var_other=7"))
            first.bind(WmWatchfaceProject()).join()
            assertFalse(first.script.has("var_old"));assertFalse(first.script.has("on_second"))
            assertEquals("7",second.script.value("var_other"))
        } }
    }
    @Test fun pausedSimulatorEditsRefreshTimeAndAmbientTagsWithoutDocumentCommands() = runBlocking {
        RenderSession(Files.createTempDirectory("watch-simulation").toFile(),false).use { render ->
            render.prepare(WmWatchfaceProject())
            render.simulation=render.simulation.copy(now=LocalDateTime.of(2025,1,2,10,8),heartRate=98)
            render.isBright=false
            withTimeout(3000){while(render.frame.now.hour!=10 || render.frame.isBright || render.frame.heartRate!=98)delay(20)}
            val held=render.frame.now;delay(150);assertEquals(held,render.frame.now)
            assertEquals("98",render.evaluate("{shr}"))
        }
    }
    @Test fun bundledVlcDecodesFramesAndKeepsSharedVideoPlayingForRemainingOwner() = runBlocking {
        val file=Files.createTempDirectory("watch-video").resolve("colors.avi").toFile()
        file.writeBytes(tinyAvi())
        MediaPlayback().use { media ->
            val started=System.nanoTime()
            val player=media.video(file)
            val prepared=System.nanoTime()
            assertSame(player,media.video(file))
            player.setOwnerPlaying("first",true);player.setOwnerPlaying("second",true)
            val frame=withTimeout(15000){player.frames.filterNotNull().first()}
            val firstFrame=System.nanoTime()
            assertTrue("Preparing a bundled video must not recursively search the host: ${(prepared-started)/1_000_000} ms",prepared-started<5_000_000_000L)
            assertTrue("First decoded frame must arrive promptly: ${(firstFrame-started)/1_000_000} ms",firstFrame-started<8_000_000_000L)
            assertEquals(16,frame.width);assertEquals(16,frame.height)
            assertEquals(255,frame.bytes[3].toInt() and 255)
            assertTrue("decoded red or blue pixel",(frame.bytes[0].toInt() and 255)>200 || (frame.bytes[2].toInt() and 255)>200)
            player.removeOwner("first")
            withTimeout(5000){player.frames.filterNotNull().first{it.sequence>frame.sequence+1}}
            player.removeOwner("second")
        }
    }

    @Test fun failedFactoryInitializationIsNotRetriedDuringClose() {
        val attempts=AtomicInteger()
        val media=MediaPlayback {attempts.incrementAndGet();error("Injected initialization failure")}
        assertThrows(IllegalStateException::class.java){media.video(java.io.File("unused.avi"))}
        media.close();media.close()
        assertEquals(1,attempts.get())
        assertThrows(IllegalStateException::class.java){media.video(java.io.File("unused.avi"))}
        assertEquals(1,attempts.get())
    }

    @Test fun closedUnusedSessionDoesNotInitializeFactoryForLateVideoRequest() {
        val attempts=AtomicInteger()
        val media=MediaPlayback {attempts.incrementAndGet();error("Should never initialize")}
        media.close()
        assertThrows(IllegalStateException::class.java){media.video(java.io.File("unused.avi"))}
        assertEquals(0,attempts.get())
    }

    @Test fun closingSessionWaitsForInFlightCreationAndRejectsSubsequentRequests() = runBlocking {
        val entered=CountDownLatch(1)
        val proceed=CountDownLatch(1)
        val attempts=AtomicInteger()
        val file=Files.createTempDirectory("watch-video-close").resolve("colors.avi").toFile().apply{writeBytes(tinyAvi())}
        val media=MediaPlayback {
            attempts.incrementAndGet();entered.countDown()
            check(proceed.await(5,TimeUnit.SECONDS))
            createBundledMediaPlayerFactory()
        }
        val creating=async(Dispatchers.IO){media.video(file)}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val closeStarted=CountDownLatch(1)
        val closing=async(Dispatchers.IO){closeStarted.countDown();media.close()}
        assertTrue(closeStarted.await(5,TimeUnit.SECONDS))
        proceed.countDown()
        val player=creating.await();closing.await()
        assertThrows(IllegalStateException::class.java){media.video(file)}
        player.setOwnerPlaying("late",true)
        assertNull(player.frames.value)
        assertEquals(1,attempts.get())
        media.close()
    }

    internal fun tinyAvi():ByteArray {
        fun ints(vararg values:Int)=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{values.forEach(::putInt)}.array()
        fun chunk(id:String,data:ByteArray)=id.toByteArray()+ints(data.size)+data+if(data.size%2==1)byteArrayOf(0)else byteArrayOf()
        fun list(id:String,data:ByteArray)=chunk("LIST",id.toByteArray()+data)
        val avih=chunk("avih",ints(100000,7680,0,0,2,0,1,768,16,16,0,0,0,0))
        val strh=chunk("strh","vidsDIB ".toByteArray()+ints(0,0,0,1,10,0,2,768,-1,0)+byteArrayOf(0,0,0,0,16,0,16,0))
        val strf=chunk("strf",ints(40,16,16)+byteArrayOf(1,0,24,0)+ints(0,768,0,0,0,0))
        val movie=(0..1).fold(byteArrayOf()){all,index->all+chunk("00db",ByteArray(768){i->if(i%3==index*2)(-1).toByte() else 0})}
        val data="AVI ".toByteArray()+list("hdrl",avih+list("strl",strh+strf))+list("movi",movie)
        return "RIFF".toByteArray()+ints(data.size)+data
    }
}
