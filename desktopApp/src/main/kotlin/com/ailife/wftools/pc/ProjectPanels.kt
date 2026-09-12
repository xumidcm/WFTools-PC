package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.nio.file.Files
import com.ailife.wftools.pc.platform.chooseAudioFiles

/** Main/EditorWorkspace can install these hooks without changing the public inspector signature. */
object ProjectPanelIntegration {
    var saveAs: ((DocumentController, protect: Boolean) -> Unit)? = null
    var screenshot: ((DocumentController) -> Unit)? = null
    var recordGif: ((DocumentController) -> Unit)? = null
    var isRecording: ((DocumentController) -> Boolean)? = null
}

@Composable fun KnownProjectPanel(doc: DocumentController, modifier: Modifier) {
    if(doc.globalScriptEditorOpen) GlobalScriptPage(doc,modifier) else ProjectOverview(doc,modifier,onEditScript=doc::openGlobalScriptEditor)
}

@Composable private fun ProjectOverview(doc:DocumentController,modifier:Modifier,onEditScript:()->Unit) {
    val project=doc.view.project
    fun root(key:String,value:String)=doc.command { setRootAttribute(key,value) }
    ScrollableInspector(modifier,14.dp,12.dp) {
        WmParamCard("表盘信息",Modifier.testTag("known-project-basic")) {
            listOf("name" to "名称","description" to "描述","tags" to "标签（逗号分隔）","author" to "作者","web_link" to "作者链接").forEach { (key,label)->
                FieldRow(label,project.rootAttrs[key].orEmpty(),doc,!doc.editingBlocked,fieldModifier=Modifier.testTag("known-project-field-$key")){root(key,it)}
            }
            Column(Modifier.testTag("known-project-field-bg_color")){Text("背景颜色",style=MaterialTheme.typography.bodySmall);DocumentValue(project.background,"RRGGBB",doc,{root("bg_color",it)},Modifier.fillMaxWidth())}
            val shapeValue=when(project.shape.lowercase()){ "round","r","circle","circular"->"R";"apple"->"Apple";else->"S" }
            Column(Modifier.testTag("known-project-field-shape")){Text("表盘形状",style=MaterialTheme.typography.bodySmall);ChoiceControl("表盘形状",listOf("S" to "方形","R" to "圆形","Apple" to "Apple Watch"),shapeValue,{root("shape",it)},enabled=!doc.editingBlocked);Text("切换后画布立即更新，保存时写入表盘文件。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        WmParamCard("设计画布") {
            Text("WatchMaker 项目固定按 512 × 512 设计。设备预览的分辨率与裁切只影响桌面预览，不会改写表盘尺寸。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WmParamCard("全局脚本") {
            Text("在独立编辑页中检查 Lua 语法，再明确保存。检查只编译，不会执行脚本。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(project.globalScript.isBlank())"表盘未包含全局脚本" else "${project.globalScript.length} 个字符",Modifier.testTag("global-script-summary"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
            Button(onEditScript,Modifier.fillMaxWidth().testTag("edit-global-script"),enabled=!doc.editingBlocked){Icon(Icons.Default.Code,null);Spacer(Modifier.width(8.dp));Text("编辑全局脚本")}
        }
        AudioResources(doc)
        ProjectActions(doc)
        WmCollapsible("高级项目属性") { project.rootAttrs.filterKeys { it !in setOf("name","description","tags","author","web_link","shape","bg_color","width","height","global_script") }.toSortedMap().forEach { (key,value)->FieldRow(key,value,doc,!doc.editingBlocked){root(key,it)} } }
    }
}

@Composable private fun GlobalScriptPage(doc:DocumentController,modifier:Modifier) {
    var confirmBack by remember{mutableStateOf(false)}
    var softWrap by remember{mutableStateOf(true)}
    val draft=doc.globalScriptDraft
    val commit=remember(doc){ { doc.saveGlobalScriptDraft() } }
    fun back(){if(doc.hasGlobalScriptDraft)confirmBack=true else doc.globalScriptEditorOpen=false}
    DisposableEffect(doc) { onDispose { if(doc.commitFocusedField===commit)doc.commitFocusedField=null;if(doc.focus==EditorFocus.TEXT)doc.focus=EditorFocus.OTHER } }
    Column(modifier.fillMaxSize().testTag("global-script-page")) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            WmIconButton(Icons.Default.ArrowBack,"返回项目设置",true){back()}
            Text("全局脚本",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton({doc.validateGlobalScriptDraft()},enabled=!doc.busy&&!doc.globalScriptDraftValidating){Text(if(doc.globalScriptDraftValidating)"检查中…" else "检查")}
            Button({doc.saveGlobalScriptDraft()},enabled=!doc.editingBlocked&&doc.hasGlobalScriptDraft){Text("保存")}
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text("Lua / WatchMaker · ${draft.length} 个字符",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("自动换行",style=MaterialTheme.typography.bodySmall);Switch(softWrap,{softWrap=it})}
        val vertical=rememberScrollState();val horizontal=rememberScrollState()
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal=10.dp).background(MaterialTheme.colorScheme.surfaceContainerLow,MaterialTheme.shapes.small).verticalScroll(vertical).padding(8.dp)) {
            Text((1..(draft.count{it=='\n'}+1)).joinToString("\n"),Modifier.widthIn(min=34.dp).padding(end=8.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f).then(if(softWrap)Modifier else Modifier.horizontalScroll(horizontal))) {
                BasicTextField(draft,doc::updateGlobalScriptDraft,(if(softWrap)Modifier.fillMaxWidth() else Modifier.width(1200.dp)).testTag("global-script-editor").onFocusChanged { state->if(state.isFocused){doc.focus=EditorFocus.TEXT;doc.commitFocusedField=commit}else if(doc.commitFocusedField===commit){doc.commitFocusedField=null;if(doc.focus==EditorFocus.TEXT)doc.focus=EditorFocus.OTHER} },enabled=!doc.editingBlocked&&!doc.globalScriptDraftValidating,textStyle=MaterialTheme.typography.bodyMedium.copy(fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurface))
            }
        }
        doc.globalScriptDraftError?.let { Text(it,Modifier.padding(10.dp).testTag("script-error"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
    }
    if(confirmBack) AlertDialog(onDismissRequest={confirmBack=false},modifier=Modifier.testTag("script-unsaved-dialog"),title={Text("放弃未保存的脚本修改？")},text={Text("返回项目设置会丢弃本页尚未保存的内容。")},confirmButton={TextButton({confirmBack=false;doc.discardGlobalScriptDraft(closeEditor=true)}){Text("放弃修改")}},dismissButton={TextButton({confirmBack=false}){Text("继续编辑")}})
}

@Composable private fun AudioResources(doc:DocumentController) {
    var expanded by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf<String?>(null)}
    WmParamCard("音频资源",Modifier.testTag("project-section-audio-resources")) {
        Text("管理 sfx/ 中的声音文件。删除前会再次确认。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({chooseAudioFiles(doc.window).takeIf{it.isNotEmpty()}?.let { paths->doc.assetCommand("正在导入音频…"){importAssets(paths,createLayers=false)} }},enabled=!doc.editingBlocked){Icon(Icons.Default.AudioFile,null);Spacer(Modifier.width(6.dp));Text("添加音频")}
            TextButton({expanded=!expanded}){Text(if(expanded)"收起" else "查看 ${doc.view.assets.count{it.kind=="sfx"}} 项")}
        }
        if(expanded) doc.view.assets.filter{it.kind=="sfx"}.sortedBy{it.relative.lowercase()}.forEach { asset->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){Text(asset.relative.substringAfter('/'),maxLines=1);Text(runCatching{formatBytes(Files.size(asset.path))}.getOrDefault("大小未知"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            WmIconButton(Icons.Default.PlayArrow,"试听",true,size=28.dp){runCatching{Desktop.getDesktop().open(asset.path.toFile())}.onFailure{doc.error="无法试听音频：${it.message}"}}
            WmIconButton(Icons.Default.Delete,"删除音频",!doc.editingBlocked,size=28.dp){deleting=asset.relative}
        } }
        if(expanded&&doc.view.assets.none{it.kind=="sfx"}) Text("还没有音频资源。",style=MaterialTheme.typography.bodySmall)
    }
    deleting?.let { relative->AlertDialog(onDismissRequest={deleting=null},title={Text("删除音频？")},text={Text("将从当前项目移除 $relative。若脚本仍引用它，预览将无法播放该声音。")},confirmButton={TextButton({deleting=null;doc.assetCommand("正在删除音频…"){deleteAsset(relative)}}){Text("删除")}},dismissButton={TextButton({deleting=null}){Text("取消")}}) }
}

@Composable private fun ProjectActions(doc:DocumentController) {
    val saving=ProjectPanelIntegration.saveAs
    val screenshot=ProjectPanelIntegration.screenshot
    val recording=ProjectPanelIntegration.isRecording?.invoke(doc)==true
    WmParamCard("保存与导出",Modifier.testTag("project-section-save-actions")) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({saving?.invoke(doc,false)},Modifier.weight(1f),enabled=saving!=null&&!doc.editingBlocked){Text("另存为")}
            Button({saving?.invoke(doc,true)},Modifier.weight(1f),enabled=saving!=null&&!doc.editingBlocked){Text("受保护另存为")}
        }
        if(saving==null) Text("保存命令尚未接入此面板，请暂时使用“文件 → 另存为”。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    WmParamCard("预览采集",Modifier.testTag("project-section-capture-actions")) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({screenshot?.invoke(doc)},Modifier.weight(1f),enabled=screenshot!=null&&!recording){Text("截图")}
            Button({ProjectPanelIntegration.recordGif?.invoke(doc)},Modifier.weight(1f),enabled=ProjectPanelIntegration.recordGif!=null&&!recording){Text(if(recording)"录制中…" else "录制 GIF")}
        }
        if(screenshot==null||ProjectPanelIntegration.recordGif==null) Text("截图 / GIF 录制后端尚未接入，入口不会伪造成功。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes:Long):String=when{bytes>=1024*1024->"%.1f MB".format(bytes/(1024.0*1024));bytes>=1024->"%.1f KB".format(bytes/1024.0);else->"$bytes B"}
