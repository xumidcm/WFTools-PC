package com.ailife.wftools.pc.render

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.ailife.wftools2.domain.editor.watchmaker.*
import com.ailife.wftools2.ui.editor.utils.PreviewBitmapCache
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.*

/** Mutable preview state is isolated from the document and its undo history. */
class RenderSession(val directory:File, autoPlay:Boolean=true,silentMedia:Boolean=false):AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    val media=if(silentMedia)MediaPlayback{createBundledMediaPlayerFactory(listOf("--no-audio"))}else MediaPlayback()
    var script by mutableStateOf(WmScriptRuntime())
        private set
    private val dispatcher=Dispatchers.Default.limitedParallelism(1)
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    var playing by mutableStateOf(autoPlay)
    var visible by mutableStateOf(true)
    var isBright by mutableStateOf(true)
    var interactionMode by mutableStateOf(false)
    var timeSpeed by mutableStateOf(1.0)
    var timeOffsetMillis by mutableStateOf(0L)
    var simulation by mutableStateOf(WmTagsData.WmPreviewState())
    var frame by mutableStateOf(simulation)
        private set
    var variables by mutableStateOf<Map<String,String>>(emptyMap())
        private set
    val tags=mutableStateMapOf<String,String>()
    var error by mutableStateOf<String?>(null)
        private set
    var actionMessage by mutableStateOf<String?>(null)
        private set
    var assetsReady by mutableStateOf(false)
        private set
    private var loadedScript:String?=null
    private var usedTags:Set<String> = emptySet()
    private var clockMillis=System.currentTimeMillis()
    private var lastSimulationTime=simulation.now
    private var elapsedSeconds=0.0
    private var themes:List<WmThemeSet> = emptyList()
    private var manualClock=false
    var themeIndex by mutableStateOf(0)
        private set
    private val regex=Regex("\\{[^{}]+}")
    init {
        RenderAssetLeases.retain(directory)
        script.actionHandler={ action->handleAction(action) }
        scope.launch {
            PreviewBitmapCache.prewarm(directory)
            assetsReady=true
        }
        scope.launch {
            var previous=System.nanoTime()
            while(isActive) {
                val nowNanos=System.nanoTime()
                val elapsed=(nowNanos-previous).coerceAtLeast(0)/1_000_000.0
                previous=nowNanos
                if(playing && visible && !manualClock) {
                    clockMillis+=(elapsed*timeSpeed).toLong()
                    elapsedSeconds+=elapsed/1000
                    if(simulation.stopwatchRunning)simulation=simulation.copy(stopwatchMs=simulation.stopwatchMs+elapsed.toLong())
                    val now=currentTime()
                    val next=sample(now)
                    fun resolve(text:String)=regex.replace(text){WmTagsData.resolveTag(it.value,next)}
                    script.fireMillisecond(elapsed/1000,::resolve)
                    script.fireTimeCallbacks(now.hour,now.minute,now.second,::resolve)
                    if(script.lastError!=null)playing=false
                }
                // Pausing freezes the clock, while explicit simulator edits remain visible.
                if(visible)publish()
                delay(if(visible && playing)16 else 120)
            }
        }
    }
    private fun currentTime()=LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clockMillis+timeOffsetMillis),ZoneId.systemDefault())
    private fun sample(now:LocalDateTime=currentTime())=simulation.copy(now=now,isBright=isBright,elapsedSeconds=elapsedSeconds,variables=script.variables,scriptRuntime=script)
    private fun publish() {
        if(simulation.now!=lastSimulationTime) {
            lastSimulationTime=simulation.now
            clockMillis=simulation.now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val next=sample()
        val resolved=usedTags.associateWith{WmTagsData.resolveTag(it,next)}
        Snapshot.withMutableSnapshot {
            frame=next
            if(variables!=script.variables)variables=script.variables.toMap()
            resolved.forEach{(tag,value)->if(tags[tag]!=value)tags[tag]=value}
            error=script.lastError
        }
    }
    fun bind(project:WmWatchfaceProject):Job {
        val found=project.wmLayers.flatMap{layer->layer.attrs.values.flatMap{regex.findAll(it).map{it.value}.toList()}}.toSet()
        return scope.launch {
            usedTags=found
            if(themes!=project.themeSets){themes=project.themeSets;selectTheme(0)}
            if(loadedScript!=project.globalScript) {
                loadedScript=project.globalScript
                script=WmScriptRuntime().also {it.actionHandler={action->handleAction(action)}}
                script.runScript(project.globalScript){text->regex.replace(text){WmTagsData.resolveTag(it.value,frame)}}
                variables=script.variables.toMap()
            }
            val next=sample()
            val values=found.associateWith{WmTagsData.resolveTag(it,next)}
            Snapshot.withMutableSnapshot {tags.clear();tags.putAll(values);frame=next;error=script.lastError}
        }
    }
    suspend fun prepare(project:WmWatchfaceProject) {
        PreviewBitmapCache.prewarm(directory)
        bind(project).join()
        withContext(scope.coroutineContext){publish();assetsReady=true}
    }
    /** Initializes an export clock without letting the real-time preview loop race it. */
    internal suspend fun prepareForCapture(
        project:WmWatchfaceProject,
        state:WmTagsData.WmPreviewState,
        bright:Boolean,
        selectedThemeIndex:Int,
    ) {
        withContext(dispatcher) {
            manualClock=true
            clockMillis=state.now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            lastSimulationTime=state.now
            elapsedSeconds=state.elapsedSeconds
            timeOffsetMillis=0
            Snapshot.withMutableSnapshot {
                playing=true
                visible=true
                isBright=bright
                simulation=state.copy(isBright=bright)
            }
        }
        prepare(project)
        withContext(dispatcher) {
            selectTheme(selectedThemeIndex)
            clockMillis=state.now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            lastSimulationTime=state.now
            elapsedSeconds=state.elapsedSeconds
            simulation=simulation.copy(now=state.now,isBright=bright,elapsedSeconds=state.elapsedSeconds)
            publish()
        }
    }
    /** Advances one deterministic export frame while preserving this session's Lua and media. */
    internal suspend fun advanceCaptureFrame(
        now:LocalDateTime,
        captureElapsedSeconds:Double,
        deltaMillis:Long,
    ) = withContext(dispatcher) {
        require(deltaMillis>=0)
        clockMillis=now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        elapsedSeconds=captureElapsedSeconds
        val stopwatch=if(simulation.stopwatchRunning)simulation.stopwatchMs+deltaMillis else simulation.stopwatchMs
        simulation=simulation.copy(now=now,isBright=isBright,stopwatchMs=stopwatch,elapsedSeconds=captureElapsedSeconds)
        lastSimulationTime=now
        val next=sample(now)
        fun resolve(text:String)=regex.replace(text){WmTagsData.resolveTag(it.value,next)}
        if(deltaMillis>0) {
            script.fireMillisecond(deltaMillis/1000.0,::resolve)
            script.fireTimeCallbacks(now.hour,now.minute,now.second,::resolve)
        }
        publish()
    }
    fun selectTheme(index:Int) {
        themeIndex=index.coerceIn(0,themes.lastIndex.coerceAtLeast(0))
        themes.getOrNull(themeIndex)?.attrs?.let { attrs ->
            simulation=simulation.copy(accentColor1=attrs["ucolor"] ?: simulation.accentColor1,
                accentColor2=attrs["ucolor2"] ?: simulation.accentColor2,accentColor3=attrs["ucolor3"] ?: simulation.accentColor3)
        }
    }
    fun resetTimeMachineToNow():Job = scope.launch {
        val nowMillis=System.currentTimeMillis()
        val now=LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis),ZoneId.systemDefault())
        timeOffsetMillis=0L
        timeSpeed=1.0
        clockMillis=nowMillis
        lastSimulationTime=now
        simulation=simulation.copy(now=now)
        publish()
    }
    fun evaluate(text:String):String=WmTagsData.evaluatePreview(text,frame)
    fun runTap(action:String) {
        scope.launch {
            if(action.startsWith("lua:"))script.runSnippet(action.removePrefix("lua:"))
            else if(action.startsWith("script:"))script.runSnippet(action.removePrefix("script:"))
            else handleAction(action)
            publish()
        }
    }
    private fun handleAction(action:String) {
        when {
            action.startsWith("play_sound:") -> {
                playSound(action.substringAfter(':'))
            }
            action.startsWith("sfx:") -> playSound(action.substringAfter(':'))
            action=="play_tap" -> java.awt.Toolkit.getDefaultToolkit().beep()
            action=="sw_start_stop" -> simulation=simulation.copy(stopwatchRunning=!simulation.stopwatchRunning)
            action=="sw_reset" -> simulation=simulation.copy(stopwatchMs=0)
            action.startsWith("color_switch_") -> selectTheme((themeIndex+1).mod(themes.size.coerceAtLeast(1)))
            action.isNotBlank() -> actionMessage="模拟操作：$action"
        }
    }
    private fun playSound(name:String) {
        val root=File(directory,"sfx").canonicalFile.toPath()
        val file=(listOf(name)+listOf(".mp3",".ogg",".wav",".m4a").map{name+it})
            .map{root.resolve(it).normalize().toFile()}.firstOrNull{it.toPath().startsWith(root) && it.isFile}
        if(file==null)actionMessage="声音素材缺失：$name" else media.sound(file)
    }
    fun pauseMedia() { scope.launch {media.pauseAll()} }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        scope.cancel()
        media.close()
        RenderAssetLeases.release(directory)
    }
}

/** A thumbnail renderer shares assets with the editor, but owns its own clock/Lua. */
private object RenderAssetLeases {
    private val references=mutableMapOf<String,Int>()
    @Synchronized fun retain(directory:File) {val key=directory.canonicalPath;references[key]=(references[key] ?: 0)+1}
    @Synchronized fun release(directory:File) {
        val key=directory.canonicalPath
        val remaining=(references[key] ?: 1)-1
        if(remaining>0)references[key]=remaining else {
            references.remove(key)
            PreviewBitmapCache.clearDirectory(directory)
            com.ailife.wftools.pc.graphics.Typeface.clearDirectory(directory)
        }
    }
}
