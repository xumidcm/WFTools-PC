package com.ailife.wftools.pc.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.Charset
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

const val WATCH_MIME_TYPE="application/x-wftools-watch"

interface WatchFileAssociation {
    val available:Boolean
    fun isAssociated():Boolean
    fun associate()
    fun unassociate()
}

data class DesktopCommandResult(val exitCode:Int,val output:String)

fun interface DesktopCommandRunner { fun run(arguments:List<String>):DesktopCommandResult }

private object ProcessDesktopCommandRunner:DesktopCommandRunner {
    override fun run(arguments:List<String>):DesktopCommandResult {
        val process=ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output=process.inputStream.bufferedReader().use{it.readText()}
        return DesktopCommandResult(process.waitFor(),output.trim())
    }
}

interface RegistryAccess {
    fun read(key:String,name:String?=null):String?
    fun write(key:String,name:String?=null,value:String,type:String="REG_SZ")
    fun deleteValue(key:String,name:String?=null)
}

internal fun registryAddArguments(key:String,name:String?,value:String,type:String):List<String> = buildList {
    addAll(listOf("reg.exe","add",key))
    addAll(if(name==null)listOf("/ve")else listOf("/v",name))
    addAll(listOf("/t",type,"/d",value.replace("\"","\\\""),"/f"))
}

internal fun decodeRegistryOutput(bytes:ByteArray,charset:Charset=windowsNativeCharset()):String=bytes.toString(charset)

private fun windowsNativeCharset():Charset {
    val name=System.getProperty("native.encoding")?.takeIf(String::isNotBlank)
    return runCatching{name?.let(Charset::forName)}.getOrNull()?:Charset.defaultCharset()
}

private fun Process.readRegistryOutput():String=decodeRegistryOutput(inputStream.readBytes())

internal class RegExeRegistryAccess:RegistryAccess {
    override fun read(key:String,name:String?):String? {
        val args=mutableListOf("reg.exe","query",key)
        args+=if(name==null)listOf("/ve")else listOf("/v",name)
        val process=ProcessBuilder(args).redirectErrorStream(true).start()
        val output=process.readRegistryOutput()
        if(process.waitFor()!=0)return null
        return output.lineSequence().map(String::trim).firstOrNull{Regex("\\sREG_[A-Z0-9_]+\\s").containsMatchIn(" $it ")}
            ?.split(Regex("\\s+"),limit=3)?.getOrNull(2)?.trim()
    }
    override fun write(key:String,name:String?,value:String,type:String) {
        val process=ProcessBuilder(registryAddArguments(key,name,value,type)).redirectErrorStream(true).start()
        val output=process.readRegistryOutput()
        check(process.waitFor()==0){"无法写入 Windows 注册表：$key${name?.let{"（$it）"}.orEmpty()}\n${output.trim().ifBlank{"reg.exe 未返回详细信息。"}}"}
    }
    override fun deleteValue(key:String,name:String?) {
        if(read(key,name)==null)return
        val args=mutableListOf("reg.exe","delete",key)
        args+=if(name==null)listOf("/ve")else listOf("/v",name)
        args+="/f"
        val process=ProcessBuilder(args).redirectErrorStream(true).start()
        val output=process.readRegistryOutput()
        check(process.waitFor()==0){"无法删除 Windows 注册表值：$key${name?.let{"（$it）"}.orEmpty()}\n${output.trim().ifBlank{"reg.exe 未返回详细信息。"}}"}
    }
}

class WindowsWatchFileAssociation(
    private val registry:RegistryAccess=RegExeRegistryAccess(),
    private val launcherPath:()->Path?=::installedLauncherPath,
):WatchFileAssociation {
    private val extension="HKCU\\Software\\Classes\\.watch"
    private val progId="ProgID.WFToolsPC.watch"
    private val progIdKey="HKCU\\Software\\Classes\\$progId"
    private val stateKey="HKCU\\Software\\WFTools-PC\\Association"
    override val available:Boolean get()=launcherPath()?.let(Files::isRegularFile)==true

    override fun isAssociated():Boolean=registry.read(extension,null).equals(progId,true)

    override fun associate() {
        val launcher=requireNotNull(launcherPath()){"仅安装版 WFTools-PC 可以修改文件关联。"}.toAbsolutePath().normalize()
        val previous=registry.read(extension,null)
        if(!previous.equals(progId,true))registry.write(stateKey,"PreviousWatchProgId",previous?.takeIf(String::isNotBlank)?:NO_PREVIOUS)
        registry.write(progIdKey,null,"WFTools-PC WatchMaker 表盘")
        registry.write("$progIdKey\\DefaultIcon",null,"\"${launcher.parent.resolve("WFTools-PC.ico")}\",0")
        registry.write("$progIdKey\\shell\\open\\command",null,"\"$launcher\" \"%1\"")
        registry.write(extension,null,progId)
    }

    override fun unassociate() {
        if(!isAssociated())return
        val previous=registry.read(stateKey,"PreviousWatchProgId")
        if(previous.isNullOrBlank()||previous==NO_PREVIOUS)registry.deleteValue(extension,null)
        else registry.write(extension,null,previous)
        registry.deleteValue(stateKey,"PreviousWatchProgId")
    }

    companion object { private const val NO_PREVIOUS="__WFTools_No_Previous_Association__" }
}

class LinuxWatchFileAssociation(
    private val commandRunner:DesktopCommandRunner=ProcessDesktopCommandRunner,
    private val launcherPath:()->Path?=::installedLauncherPath,
    private val homeDirectory:Path=Path.of(System.getProperty("user.home")),
    private val dataHome:Path=linuxXdgDataHome(homeDirectory),
    private val configHome:Path=linuxXdgConfigHome(homeDirectory),
    private val dataDirectories:List<Path> = linuxXdgDataDirectories(),
):WatchFileAssociation {
    private val applicationDirectory=dataHome.resolve("applications")
    private val applicationDirectories=(listOf(dataHome)+dataDirectories).map{it.resolve("applications")}.distinct()
    private val desktopFile=applicationDirectory.resolve(DESKTOP_ID)
    private val mimeDirectory=dataHome.resolve("mime")
    private val mimeFile=mimeDirectory.resolve("packages/wftools-pc-watch.xml")
    private val stateFile=configHome.resolve("WFTools-PC/watch-association.state")
    private val writableMimeApps=listOf(configHome.resolve("mimeapps.list"),applicationDirectory.resolve("mimeapps.list")).distinct()
    override val available:Boolean get()=launcherPath()?.let(Files::isRegularFile)==true

    override fun isAssociated():Boolean=queryDefault()==DESKTOP_ID

    override fun associate() {
        val launcher=requireNotNull(launcherPath()){"仅安装版 WFTools-PC 可以修改文件关联。"}.toAbsolutePath().normalize()
        val previous=queryDefault().takeUnless{it==DESKTOP_ID}
        val oldDesktop=readOrNull(desktopFile);val oldMime=readOrNull(mimeFile);val oldState=readOrNull(stateFile)
        try {
            if(previous!=null || !Files.isRegularFile(stateFile))writeAtomically(stateFile,previous?:NO_PREVIOUS)
            writeAtomically(desktopFile,linuxDesktopEntry(launcher))
            writeAtomically(mimeFile,linuxWatchMimeDefinition())
            runChecked(listOf("update-mime-database",mimeDirectory.toString()))
            runChecked(listOf("update-desktop-database",applicationDirectory.toString()))
            runChecked(listOf("xdg-mime","default",DESKTOP_ID,WATCH_MIME_TYPE))
            check(isAssociated()){ "Linux 桌面环境没有接受 .watch 默认应用设置。" }
        } catch(error:Throwable) {
            val rollbackFailures=mutableListOf<Throwable>()
            listOf(desktopFile to oldDesktop,mimeFile to oldMime,stateFile to oldState).forEach { (path,content) ->
                runCatching{restoreFile(path,content)}.exceptionOrNull()?.let(rollbackFailures::add)
            }
            if(previous!=null)runCatching{runChecked(listOf("xdg-mime","default",previous,WATCH_MIME_TYPE))}.exceptionOrNull()?.let(rollbackFailures::add)
            else writableMimeApps.forEach { path -> runCatching{removeOwnDefault(path)}.exceptionOrNull()?.let(rollbackFailures::add) }
            listOf(
                listOf("update-mime-database",mimeDirectory.toString()),
                listOf("update-desktop-database",applicationDirectory.toString()),
            ).forEach { command -> runCatching{runChecked(command,allowMissingDirectory=true)}.exceptionOrNull()?.let(rollbackFailures::add) }
            rollbackFailures.forEach(error::addSuppressed)
            throw IllegalStateException("无法关联 .watch 文件：${error.message}",error)
        }
    }

    override fun unassociate() {
        val ownsDefault=isAssociated()
        val previous=readOrNull(stateFile)?.trim()?.takeUnless{it.isBlank()||it==NO_PREVIOUS||it==DESKTOP_ID}
        if(ownsDefault && previous!=null && desktopEntryExists(previous)) {
            runChecked(listOf("xdg-mime","default",previous,WATCH_MIME_TYPE))
        } else if(ownsDefault) {
            writableMimeApps.forEach(::removeOwnDefault)
        }
        Files.deleteIfExists(desktopFile)
        Files.deleteIfExists(mimeFile)
        Files.deleteIfExists(stateFile)
        runChecked(listOf("update-mime-database",mimeDirectory.toString()),allowMissingDirectory=true)
        runChecked(listOf("update-desktop-database",applicationDirectory.toString()),allowMissingDirectory=true)
    }

    private fun queryDefault():String? {
        val result=commandRunner.run(listOf("xdg-mime","query","default",WATCH_MIME_TYPE))
        return result.output.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank)
    }

    private fun runChecked(arguments:List<String>,allowMissingDirectory:Boolean=false) {
        if(allowMissingDirectory && arguments.lastOrNull()?.let{Files.isDirectory(Path.of(it))}==false)return
        val result=commandRunner.run(arguments)
        check(result.exitCode==0){"${arguments.first()} 执行失败：${result.output.ifBlank{"退出码 ${result.exitCode}"}}"}
    }

    private fun desktopEntryExists(id:String):Boolean=applicationDirectories.any{Files.isRegularFile(it.resolve(id))}

    private fun removeOwnDefault(path:Path) {
        if(!Files.isRegularFile(path))return
        val original=Files.readAllLines(path)
        var section=""
        val filtered=original.mapNotNull { line ->
            val trimmed=line.trim()
            if(trimmed.startsWith("[")&&trimmed.endsWith("]"))section=trimmed
            if(section.equals("[Default Applications]",true) && trimmed.substringBefore('=',"")==WATCH_MIME_TYPE) {
                val remaining=trimmed.substringAfter('=',"").split(';').map(String::trim).filter{it.isNotBlank()&&it!=DESKTOP_ID}
                if(remaining.isEmpty())null else "$WATCH_MIME_TYPE=${remaining.joinToString(";")};"
            } else line
        }
        if(filtered!=original)writeAtomically(path,filtered.joinToString("\n",postfix="\n"))
    }

    companion object {
        private const val DESKTOP_ID="wftools-pc.desktop"
        private const val NO_PREVIOUS="__WFTools_No_Previous_Association__"
    }
}

private class UnavailableWatchFileAssociation:WatchFileAssociation {
    override val available=false
    override fun isAssociated()=false
    override fun associate()=error("当前操作系统暂不支持自动修改 .watch 文件关联。")
    override fun unassociate()=Unit
}

fun watchFileAssociation(os:DesktopOperatingSystem=desktopOperatingSystem()):WatchFileAssociation=when(os) {
    DesktopOperatingSystem.WINDOWS -> WindowsWatchFileAssociation()
    DesktopOperatingSystem.LINUX -> LinuxWatchFileAssociation()
    else -> UnavailableWatchFileAssociation()
}

internal fun linuxDesktopEntry(launcher:Path):String {
    val raw=launcher.toAbsolutePath().normalize().toString()
    require('\n' !in raw && '\r' !in raw){"Linux 启动器路径不能包含换行。"}
    val escaped=raw.replace("\\","\\\\").replace("\"","\\\"").replace("$","\\$").replace("`","\\`").replace("%","%%")
    return """[Desktop Entry]
Type=Application
Name=WFTools-PC
Comment=WatchMaker watch face editor
Exec="$escaped" %f
Icon=wftools-pc
Terminal=false
Categories=Graphics;
MimeType=$WATCH_MIME_TYPE;
StartupWMClass=WFTools-PC
"""
}

internal fun linuxWatchMimeDefinition():String="""<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="$WATCH_MIME_TYPE">
    <comment>WFTools-PC WatchMaker watch face</comment>
    <glob pattern="*.watch"/>
  </mime-type>
</mime-info>
"""

private fun readOrNull(path:Path):String?=if(Files.isRegularFile(path))Files.readString(path)else null

private fun writeAtomically(path:Path,content:String) {
    Files.createDirectories(path.parent)
    val temporary=Files.createTempFile(path.parent,".${path.fileName}.",".tmp")
    try {
        Files.writeString(temporary,content)
        try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
        catch(_:AtomicMoveNotSupportedException) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING) }
    }
    finally { Files.deleteIfExists(temporary) }
}

private fun restoreFile(path:Path,content:String?) {if(content==null)Files.deleteIfExists(path)else writeAtomically(path,content)}

internal fun installedLauncherPath():Path? {
    val candidates=buildList {
        System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)?.let{add(Path.of(it))}
        ProcessHandle.current().info().command().orElse(null)?.let{add(Path.of(it))}
        System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)?.let{resources->
            Path.of(resources).toAbsolutePath().normalize().parent?.parent?.resolve("WFTools-PC.exe")?.let(::add)
        }
    }
    val names=when(desktopOperatingSystem()) {
        DesktopOperatingSystem.WINDOWS -> setOf("wftools-pc.exe")
        DesktopOperatingSystem.LINUX -> setOf("wftools-pc","WFTools-PC")
        else -> emptySet()
    }
    return candidates.firstOrNull{it.fileName.toString().lowercase() in names.map(String::lowercase)&&Files.isRegularFile(it)}
}
