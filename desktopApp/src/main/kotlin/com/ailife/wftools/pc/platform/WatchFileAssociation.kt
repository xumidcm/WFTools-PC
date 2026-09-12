package com.ailife.wftools.pc.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.Charset

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
) {
    private val extension="HKCU\\Software\\Classes\\.watch"
    private val progId="ProgID.WFToolsPC.watch"
    private val progIdKey="HKCU\\Software\\Classes\\$progId"
    private val stateKey="HKCU\\Software\\WFTools-PC\\Association"
    val available:Boolean get()=launcherPath()?.let(Files::isRegularFile)==true

    fun isAssociated():Boolean=registry.read(extension,null).equals(progId,true)

    fun associate() {
        val launcher=requireNotNull(launcherPath()){"仅安装版 WFTools-PC 可以修改文件关联。"}.toAbsolutePath().normalize()
        val previous=registry.read(extension,null)
        if(!previous.equals(progId,true))registry.write(stateKey,"PreviousWatchProgId",previous?.takeIf(String::isNotBlank)?:NO_PREVIOUS)
        registry.write(progIdKey,null,"WFTools-PC WatchMaker 表盘")
        registry.write("$progIdKey\\DefaultIcon",null,"\"${launcher.parent.resolve("WFTools-PC.ico")}\",0")
        registry.write("$progIdKey\\shell\\open\\command",null,"\"$launcher\" \"%1\"")
        registry.write(extension,null,progId)
    }

    fun unassociate() {
        if(!isAssociated())return
        val previous=registry.read(stateKey,"PreviousWatchProgId")
        if(previous.isNullOrBlank()||previous==NO_PREVIOUS)registry.deleteValue(extension,null)
        else registry.write(extension,null,previous)
        registry.deleteValue(stateKey,"PreviousWatchProgId")
    }

    companion object { private const val NO_PREVIOUS="__WFTools_No_Previous_Association__" }
}

private fun installedLauncherPath():Path? {
    val candidates=buildList {
        System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)?.let{add(Path.of(it))}
        ProcessHandle.current().info().command().orElse(null)?.let{add(Path.of(it))}
        System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)?.let{resources->
            Path.of(resources).toAbsolutePath().normalize().parent?.parent?.resolve("WFTools-PC.exe")?.let(::add)
        }
    }
    return candidates.firstOrNull{it.fileName.toString().equals("WFTools-PC.exe",true)&&Files.isRegularFile(it)}
}
