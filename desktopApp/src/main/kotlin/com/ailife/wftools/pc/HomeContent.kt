@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.ailife.wftools.pc.platform.desktopPlatformLabel
import com.ailife.wftools.pc.platform.openExternalUrl
import com.ailife.wftools.pc.platform.watchFileAssociation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.math.roundToInt

internal data class FileAssociationActionResult(val associated:Boolean,val error:String?)

internal fun performFileAssociationAction(
    currentlyAssociated:Boolean,
    associate:()->Unit,
    unassociate:()->Unit,
    readAssociated:()->Boolean,
):FileAssociationActionResult {
    val actionFailure=runCatching{if(currentlyAssociated)unassociate()else associate()}.exceptionOrNull()
    val refreshed=runCatching(readAssociated)
    return FileAssociationActionResult(
        associated=refreshed.getOrDefault(currentlyAssociated),
        error=(actionFailure?:refreshed.exceptionOrNull())?.message ?: if(actionFailure!=null||refreshed.isFailure)"文件关联修改失败" else null,
    )
}

@Composable fun HomeContent(app: EditorApplicationModel,onOpen: () -> Unit,onNew: () -> Unit,modifier: Modifier = Modifier,
    theme: ThemePreference = ThemePreference.SYSTEM,onTheme: (ThemePreference) -> Unit = {},
    openExternal:(String)->Unit=::openExternalUrl) {
    var settings by remember { mutableStateOf(false) }
    var logoClicks by remember { mutableIntStateOf(0) }
    var lastLogoClick by remember { mutableLongStateOf(0L) }
    fun open(url:String,label:String){runCatching{openExternal(url)}.onFailure{app.error="无法打开$label：${it.message}"}}
    Box(modifier.padding(28.dp)) {
        Column(Modifier.fillMaxSize().widthIn(max=960.dp).align(Alignment.Center)) {
            Column(Modifier.fillMaxWidth().testTag("home-fixed-header"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Image(painterResource("icons/wftools.png"),null,Modifier.size(56.dp).testTag("home-logo").clickable {
                        val now=System.currentTimeMillis()
                        logoClicks=if(now-lastLogoClick<=1200L)logoClicks+1 else 1
                        lastLogoClick=now
                        if(logoClicks>=5){logoClicks=0;open("https://www.bilibili.com/video/BV1GJ411x7h7","彩蛋视频")}
                    })
                    Column { Text("WFTools-PC",style=MaterialTheme.typography.headlineMedium);Text("WatchMaker 表盘编辑器",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Card(onClick=onNew,modifier=Modifier.weight(1f).testTag("home-new"),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) { Icon(Icons.Default.Add,null,Modifier.size(30.dp));Column { Text("创建新表盘",fontWeight=FontWeight.Bold);Text("矩形 · 512 × 512",style=MaterialTheme.typography.bodySmall) } }
                    }
                    Card(onClick=onOpen,modifier=Modifier.weight(1f).testTag("home-open"),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) { Icon(Icons.Default.FolderOpen,null,Modifier.size(30.dp));Column { Text("打开表盘",fontWeight=FontWeight.Bold);Text("选择或拖入 .watch 文件",style=MaterialTheme.typography.bodySmall) } }
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("最近打开",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Column(horizontalAlignment=Alignment.End) {
                        FilledTonalButton(onClick=app::continueSession,enabled=app.canContinueSession,modifier=Modifier.testTag("continue-session")) { Icon(Icons.Default.History,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("继续上次的会话") }
                        Text(app.lastActiveDisplayName ?: "没有可继续的表盘",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("home-recent-list"),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(top=4.dp,end=8.dp,bottom=4.dp)) {
                if(app.recentProjects.isEmpty()) item { Text("还没有最近项目。打开一个表盘，或从空白画布开始。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                items(app.recentProjects,key={it.path}) { recent -> RecentProjectRow(app,recent) }
            }
            app.status?.let { LinearProgressIndicator(Modifier.fillMaxWidth());Text(it,style=MaterialTheme.typography.bodySmall) }
            app.error?.let { ErrorBanner(it){app.error=null} }
            HorizontalDivider(Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().testTag("home-fixed-footer").padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(onClick={open("https://bilibili.com/space/1609437970","作者主页")},modifier=Modifier.weight(1f).testTag("developer-profile-link"),shape=RoundedCornerShape(12.dp),color=Color.Transparent) {
                    Row(Modifier.padding(6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Image(painterResource("branding/developer.png"),"开发者头像",Modifier.size(46.dp).clip(CircleShape),contentScale=ContentScale.Crop)
                        Column { Text("开发者 · 柚子皮",Modifier.testTag("developer-attribution"),fontWeight=FontWeight.SemiBold);Text("QQ · 3513903055",style=MaterialTheme.typography.bodySmall);Text(desktopPlatformLabel(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Column(horizontalAlignment=Alignment.End) {
                    TextButton(onClick={settings=true},modifier=Modifier.testTag("home-settings")){Icon(Icons.Default.Settings,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("设置")}
                    TextButton(onClick={open("https://gitlab.com/ailife8881/wftools","上游链接")}) { Text("上游 AILIFE · GitLab",Modifier.testTag("upstream-attribution")) }
                }
            }
        }
    }
    if(settings) HomeSettingsDialog(theme,onTheme,app.canvasInput,{app.canvasInput=it},onDismiss={settings=false})
}

@Composable private fun RecentProjectRow(app:EditorApplicationModel,recent:RecentProject) {
    var menuPosition by remember(recent.path){mutableStateOf<Offset?>(null)}
    var deleteConfirm by remember(recent.path){mutableStateOf(false)}
    Box(Modifier.fillMaxWidth()) {
        Card(onClick={if(recent.draftId!=null)app.recover(recent.draftId)else app.open(listOf(Path.of(recent.path)))},modifier=Modifier.fillMaxWidth().testTag("recent-project-${Path.of(recent.path.replace(':','_')).fileName}").onPointerEvent(PointerEventType.Press){event->
            if(event.buttons.isSecondaryPressed){menuPosition=event.changes.firstOrNull()?.position;event.changes.forEach{it.consume()}}
        },shape=RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(if(recent.draftId!=null)Icons.Default.EditNote else Icons.Default.Watch,null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){Text(recent.title,fontWeight=FontWeight.SemiBold);Text(if(recent.draftId!=null)"未保存草稿 · ${recent.title}" else recent.path,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Icon(Icons.Default.ChevronRight,null,Modifier.size(20.dp))
            }
        }
        menuPosition?.let { position -> Box(Modifier.offset{IntOffset(position.x.roundToInt(),position.y.roundToInt())}.size(1.dp)) {
            DropdownMenu(true,{menuPosition=null},modifier=Modifier.testTag("recent-context-menu")) {
                DropdownMenuItem(text={Text("删除记录")},leadingIcon={Icon(Icons.Default.RemoveCircleOutline,null)},onClick={menuPosition=null;app.removeRecentRecord(recent)})
                DropdownMenuItem(text={Text("删除表盘",color=MaterialTheme.colorScheme.error)},leadingIcon={Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error)},onClick={menuPosition=null;deleteConfirm=true})
            }
        } }
    }
    if(deleteConfirm) AlertDialog(onDismissRequest={deleteConfirm=false},title={Text("删除表盘？")},text={Text(if(recent.draftId!=null)"这会删除未保存草稿，且无法从最近编辑恢复。" else "这会直接删除原始 .watch 文件，操作不可撤销：\n${recent.path}")},confirmButton={TextButton(onClick={deleteConfirm=false;app.deleteRecentWatch(recent)}){Text("删除")}},dismissButton={TextButton(onClick={deleteConfirm=false}){Text("取消")}})
}

@Composable private fun HomeSettingsDialog(currentTheme:ThemePreference,onTheme:(ThemePreference)->Unit,canvasInput:CanvasInputPreferences,onCanvasInput:(CanvasInputPreferences)->Unit,onDismiss:()->Unit) {
    val theme = when(currentTheme){ThemePreference.SYSTEM->"跟随系统";ThemePreference.LIGHT->"浅色";ThemePreference.DARK->"深色"}
    val association=remember{watchFileAssociation()}
    val scope=rememberCoroutineScope()
    var associated by remember{mutableStateOf(false)}
    var associationLoaded by remember{mutableStateOf(false)}
    var associationBusy by remember{mutableStateOf(false)}
    var associationError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(association){associated=withContext(Dispatchers.IO){runCatching{association.isAssociated()}.getOrDefault(false)};associationLoaded=true}
    AlertDialog(onDismissRequest=onDismiss,title={Text("设置")},text={Column(Modifier.widthIn(min=420.dp,max=620.dp).heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("外观",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("跟随系统","浅色","深色").forEach{value->FilterChip(selected=theme==value,onClick={onTheme(when(value){"浅色"->ThemePreference.LIGHT;"深色"->ThemePreference.DARK;else->ThemePreference.SYSTEM})},label={Text(value)})}}
        HorizontalDivider()
        Text("语言",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=DesktopI18n.language()==DesktopLanguage.CHINESE,onClick={DesktopI18n.setLanguage(DesktopLanguage.CHINESE)},label={Text("中文")})
            FilterChip(selected=DesktopI18n.language()==DesktopLanguage.ENGLISH,onClick={DesktopI18n.setLanguage(DesktopLanguage.ENGLISH)},label={Text("English")})
        }
        Text("界面语言只影响 WFTools-PC 文案，不会修改表盘内容。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("画布操作",style=MaterialTheme.typography.titleSmall)
        HomeCanvasInputSwitch("中键拖动画布",canvasInput.middleButtonPan,"home-middle-button-pan-toggle"){onCanvasInput(canvasInput.copy(middleButtonPan=it))}
        HomeCanvasInputSwitch("右键拖动画布",canvasInput.rightButtonPan,"home-right-button-pan-toggle"){onCanvasInput(canvasInput.copy(rightButtonPan=it))}
        HomeCanvasInputSwitch("滚轮缩放选中元素",canvasInput.wheelScalesSelectedLayer,"home-wheel-layer-scale-toggle"){onCanvasInput(canvasInput.copy(wheelScalesSelectedLayer=it))}
        HomeCanvasInputSwitch("自动吸附",canvasInput.snapping,"home-automatic-snapping-toggle"){onCanvasInput(canvasInput.copy(snapping=it))}
        HomeCanvasInputSwitch("变换控制点",canvasInput.showTransformHandles,"home-transform-handles-toggle"){onCanvasInput(canvasInput.copy(showTransformHandles=it))}
        HomeCanvasInputSwitch("显示操作状态提示",canvasInput.showOperationStatus,"home-operation-status-toggle"){onCanvasInput(canvasInput.copy(showOperationStatus=it))}
        Text("鼠标直拖模式：按住图层即可移动，拖动未选中的图层会切换目标。\n先选后拖模式没有选择元素时，左键拖动表盘。选择元素后，可从画布任意位置拖动选区。\n中键或右键拖动：对应开关开启时平移整个表盘。\n滚轮：开关开启时可缩放光标下的选中元素；关闭后始终缩放整个表盘。\n自动吸附会在移动和变换时对齐画布及其他元素；变换控制点可显示八向缩放点和旋转拉杆；操作状态提示只用于显示耗时任务进度。\n空格 + 左键或 Ctrl + 左键：临时拖动画布；Ctrl + 滚轮：始终缩放整个表盘。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("文件格式关联",style=MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.InsertDriveFile,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){Text(".watch 文件",fontWeight=FontWeight.SemiBold);Text(if(associated)"当前由 WFTools-PC 打开；可在此取消关联。" else "将 .watch 文件直接关联到当前安装的 WFTools-PC。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        OutlinedButton(onClick={scope.launch {
            associationBusy=true;associationError=null
            val result=withContext(Dispatchers.IO){performFileAssociationAction(associated,association::associate,association::unassociate,association::isAssociated)}
            associated=result.associated;associationError=result.error
            associationBusy=false
        }},enabled=association.available&&associationLoaded&&!associationBusy,modifier=Modifier.fillMaxWidth().testTag("watch-association-action")) {
            Icon(if(associated)Icons.Default.LinkOff else Icons.Default.Link,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text(when{associationBusy->"正在修改…";!association.available->"仅安装版可修改关联";associated->"取消 .watch 关联";else->"关联 .watch 文件"})
        }
        associationError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }},confirmButton={Button(onClick=onDismiss){Text("完成")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable private fun HomeCanvasInputSwitch(label:String,checked:Boolean,tag:String,onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable{onChecked(!checked)}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f))
        Switch(checked,onCheckedChange=null)
    }
}
