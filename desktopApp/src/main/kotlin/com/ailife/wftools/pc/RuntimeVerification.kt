package com.ailife.wftools.pc

import com.ailife.wftools.pc.core.WatchDocumentStore
import com.ailife.wftools.pc.render.MediaPlayback
import com.ailife.wftools.pc.render.ThumbnailExporter
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/** Hidden packaged-runtime acceptance path. It never opens a broker or an editor window. */
fun runRuntimeVerification(arguments: List<String>): Int {
    if(arguments.size !in 2..3) { System.err.println("Usage: --verify-runtime <reportDirectory> <sample.watch> [video-file]"); return 2 }
    val directory = Path.of(arguments[0]).toAbsolutePath().normalize()
    val source = Path.of(arguments[1]).toAbsolutePath().normalize()
    val report = linkedMapOf<String, Any?>("startedAt" to Instant.now().toString(), "java.home" to System.getProperty("java.home"),
        "java.version" to System.getProperty("java.version"), "os.arch" to System.getProperty("os.arch"),
        "compose.application.resources.dir" to System.getProperty("compose.application.resources.dir"), "source" to source.toString(),
        "media" to linkedMapOf("requested" to (arguments.size == 3), "status" to if(arguments.size == 3) "pending" else "not_requested"))
    var code = 1
    try {
        Files.createDirectories(directory)
        require(Files.isRegularFile(source)) { "Sample .watch file does not exist: $source" }
        report["sourceSHA256before"] = runtimeSha256(source)
        runBlocking(Dispatchers.IO) {
            val store = WatchDocumentStore(directory.resolve("runtime-workspace"))
            store.open(source).use { session ->
                val project = session.toWatchProject()
                report["layers"] = project.wmLayers.size
                report["title"] = project.title
                report["documentWidth"] = project.width; report["documentHeight"] = project.height
                val thumbnails = ThumbnailExporter.generate(project, session.workDir.toFile())
                require(thumbnails.keys.containsAll(listOf("preview.jpg", "preview_dim.jpg"))) { "Both bright and dim thumbnails are required." }
                report["thumbnails"] = thumbnails.map { (name, bytes) ->
                    require(bytes.isNotEmpty()) { "Empty thumbnail: $name" }
                    Files.write(directory.resolve(name),bytes)
                    mapOf("name" to name,"bytes" to bytes.size)
                }
            }
            arguments.getOrNull(2)?.let { verifyRuntimeMedia(directory,it,report) }
        }
        report["sourceSHA256after"] = runtimeSha256(source)
        check(report["sourceSHA256before"] == report["sourceSHA256after"]) { "Source archive changed during runtime verification." }
        report["status"] = "passed"; code = 0
    } catch(error: Throwable) {
        report["status"] = "failed"; report["error"] = error.toString(); report["stackTrace"] = error.stackTraceToString()
        report["sourceSHA256after"] = runCatching { runtimeSha256(source) }.getOrNull()
        System.err.println(error.stackTraceToString())
    } finally {
        report["finishedAt"] = Instant.now().toString()
        try { Files.createDirectories(directory); Files.writeString(directory.resolve("runtime-verification.json"),GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report)) }
        catch(error: Exception) { System.err.println("Could not write runtime verification report: ${error.message}"); code = 1 }
    }
    return code
}

/** Uses the production media owner and its actual JNA binding, never a developer-runtime fallback. */
private suspend fun verifyRuntimeMedia(directory:Path,videoArgument:String,report:MutableMap<String,Any?>) {
    val timings=linkedMapOf<String,Long>()
    val result=linkedMapOf<String,Any?>("requested" to true,"status" to "running","source" to videoArgument,"timingsMillis" to timings)
    report["media"]=result
    val started=System.nanoTime()
    var media:MediaPlayback?=null
    try {
        val video=Path.of(videoArgument).toAbsolutePath().normalize()
        result["source"]=video.toString()
        require(Files.isRegularFile(video)){"Video fixture does not exist: $video"}
        result["sourceSHA256before"]=runtimeSha256(video)

        // A missing packaged property is a verification failure. In development,
        // MediaPlayback can choose WFTOOLS_VLC_HOME or D:, which cannot prove that
        // the delivered portable distribution contains its own working runtime.
        val resourceProperty=System.getProperty("compose.application.resources.dir")
        require(!resourceProperty.isNullOrBlank()){ "Packaged resources directory is missing; developer VLC fallback is forbidden in media verification." }
        val resources=Path.of(resourceProperty).toRealPath()
        val expectedLibrary=resources.resolve("vlc/libvlc.dll").toRealPath()
        require(expectedLibrary.startsWith(resources)){"Packaged VLC library resolves outside the delivered resources directory."}
        result["expectedLibVlc"]=expectedLibrary.toString()
        result["pluginsCachePresent"]=Files.isRegularFile(resources.resolve("vlc/plugins/plugins.dat"))

        var phase=System.nanoTime()
        val owner=MediaPlayback().also { media=it }
        timings["sessionCreate"]=(System.nanoTime()-phase)/1_000_000
        phase=System.nanoTime()
        val player=owner.video(video.toFile())
        timings["initializeAndCreatePlayer"]=(System.nanoTime()-phase)/1_000_000

        // Query the library already registered by VLCJ's direct native binding.
        // Looking up a new NativeLibrary by name could load another DLL and would
        // not establish which runtime decoded the frame below.
        val binding=Class.forName("uk.co.caprica.vlcj.binding.lib.LibVlc")
        val nativeLibrary=Class.forName("com.sun.jna.Native")
            .getMethod("getNativeLibrary",Class::class.java).invoke(null,binding)
        val actualLibrary=(nativeLibrary.javaClass.getMethod("getFile").invoke(nativeLibrary) as File).toPath().toRealPath()
        result["loadedLibVlc"]=actualLibrary.toString()
        result["packagedRuntime"]=actualLibrary==expectedLibrary
        check(actualLibrary==expectedLibrary){"VLC loaded from $actualLibrary instead of packaged library $expectedLibrary"}
        result["vlcVersion"]=binding.getMethod("libvlc_get_version").invoke(null)?.toString()

        phase=System.nanoTime()
        player.setOwnerPlaying("runtime-verification",true)
        timings["playCall"]=(System.nanoTime()-phase)/1_000_000
        phase=System.nanoTime()
        val frame=withTimeout(15_000){player.frames.filterNotNull().first()}
        timings["firstFrameWait"]=(System.nanoTime()-phase)/1_000_000
        timings["firstFrameFromStart"]=(System.nanoTime()-started)/1_000_000
        check(frame.width>0 && frame.height>0 && frame.bytes.size.toLong()==frame.width.toLong()*frame.height*4){"Decoded video frame has invalid dimensions or pixel storage."}
        result["video"]=linkedMapOf("width" to frame.width,"height" to frame.height,"sequence" to frame.sequence,"pixelBytes" to frame.bytes.size)
        player.removeOwner("runtime-verification")

        val silence=Files.createTempFile(directory,"runtime-silence-",".wav")
        Files.write(silence,runtimeSilentWav())
        phase=System.nanoTime()
        owner.sound(silence.toFile())
        timings["soundCall"]=(System.nanoTime()-phase)/1_000_000
        delay(250)
        result["sound"]=linkedMapOf("fixture" to silence.toString(),"status" to "call_succeeded","physicalOutputVerified" to false)
        result["sourceSHA256after"]=runtimeSha256(video)
        check(result["sourceSHA256before"]==result["sourceSHA256after"]){"Video fixture changed during verification."}
        result["status"]="passed"
    } catch(error:Throwable) {
        result["status"]="failed";result["error"]=error.toString()
        throw error
    } finally {
        val phase=System.nanoTime()
        try {media?.close()} catch(error:Throwable) {
            result["status"]="failed";result["closeError"]=error.toString()
            throw error
        } finally {
            timings["close"]=(System.nanoTime()-phase)/1_000_000
            timings["total"]=(System.nanoTime()-started)/1_000_000
        }
    }
}

private fun runtimeSilentWav():ByteArray {
    val sampleRate=8_000
    val dataBytes=sampleRate/5*2 // 200 ms, mono PCM16, zero samples.
    return ByteBuffer.allocate(44+dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII));putInt(36+dataBytes)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII));putInt(16)
        putShort(1);putShort(1);putInt(sampleRate);putInt(sampleRate*2);putShort(2);putShort(16)
        put("data".toByteArray(Charsets.US_ASCII));putInt(dataBytes)
    }.array()
}

private fun runtimeSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input -> val buffer = ByteArray(64*1024); while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer,0,count) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
