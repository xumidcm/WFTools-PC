@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.ailife.wftools.pc.core.EditorLayer
import com.ailife.wftools.pc.platform.chooseAssets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.roundToInt

@Composable fun AssetBrowser(doc: DocumentController,drops: DropRegistry,modifier: Modifier) {
    var selected by remember { mutableStateOf<AssetItem?>(null) };var menu by remember { mutableStateOf<Pair<AssetItem,Offset>?>(null) }
    var delete by remember { mutableStateOf<AssetItem?>(null) };var dragged by remember { mutableStateOf<AssetItem?>(null) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    val listState=rememberLazyListState();val focus=LocalFocusManager.current;val assets by rememberUpdatedState(doc.view.assets)
    LaunchedEffect(assets){selected=selected?.let{old->assets.firstOrNull{it.relative==old.relative}}}
    DisposableEffect(drops){onDispose{drops.library=null}}
    fun importFiles() { chooseAssets(doc.window).takeIf{it.isNotEmpty()}?.let{paths->doc.importExternalAssets(paths,createLayers=false,label="正在导入素材…",onSuccess={doc.notice="已将 ${paths.size} 个素材复制到素材库。"})} }
    fun apply(asset: AssetItem) {
        val attribute=assetTargetAttribute(asset) ?: return;val ids=compatibleAssetSelection(asset,doc.view.layers,doc.view.selection.selected)
        val value=if(attribute=="font")(if(asset.kind=="fonts_bm")"bm:" else "")+asset.relative.substringAfter('/').substringBeforeLast('.') else asset.relative.substringAfter('/')
        if(ids.isNotEmpty())doc.command{setAttribute(ids,attribute,value)}
    }
    Column(modifier.fillMaxWidth().onGloballyPositioned{drops.library=it.boundsInWindow().dropBounds()}) {
        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(::importFiles,enabled=!doc.editingBlocked,modifier=Modifier.height(38.dp)){Icon(Icons.Default.AddPhotoAlternate,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("导入素材")}
            selected?.let{asset->WmIconButton(Icons.Default.AddCircleOutline,"使用素材创建图层",assetTargetAttribute(asset)!=null&&!doc.editingBlocked){doc.createLayerFromAsset(asset)}}
            Spacer(Modifier.weight(1f))
        }
        selected?.let{asset-> if(compatibleAssetSelection(asset,doc.view.layers,doc.view.selection.selected).isNotEmpty())TextButton({apply(asset)},enabled=!doc.editingBlocked,modifier=Modifier.padding(horizontal=8.dp)){Text("应用到选中图层")} }
        Text(if(dragged!=null)"拖到画布释放以创建图层" else "拖入此页仅入库；将卡片拖到画布可创建图层",Modifier.padding(horizontal=12.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned{listBounds=it.boundsInWindow()}.onPointerEvent(PointerEventType.Press){event->
            if(event.buttons.isSecondaryPressed&&!doc.editingBlocked){
                val point=event.changes.firstOrNull()?.position ?: return@onPointerEvent
                if(point.x>listState.layoutInfo.viewportSize.width)return@onPointerEvent
                val row=listState.layoutInfo.visibleItemsInfo.firstOrNull{point.y>=it.offset&&point.y<it.offset+it.size} ?: return@onPointerEvent
                val asset=assets.firstOrNull{it.relative==row.key} ?: return@onPointerEvent
                focus.clearFocus();doc.focus=EditorFocus.OTHER;selected=asset;menu=asset to point
            }
        }.pointerInput(doc.session.id){
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                if(doc.editingBlocked || down.position.x>size.width-10)return@awaitEachGesture
                val row=listState.layoutInfo.visibleItemsInfo.firstOrNull{down.position.y>=it.offset&&down.position.y<it.offset+it.size} ?: return@awaitEachGesture
                val asset=assets.firstOrNull{it.relative==row.key} ?: return@awaitEachGesture
                focus.clearFocus();doc.focus=EditorFocus.OTHER;selected=asset
                if(!currentEvent.buttons.isPrimaryPressed)return@awaitEachGesture
                var moving=false
                try {
                    while(true){
                        val event=awaitPointerEvent(PointerEventPass.Initial);val change=event.changes.firstOrNull{it.id==down.id} ?: break
                        if(event.type==PointerEventType.Scroll)continue
                        if(!change.pressed){
                            if(moving){
                                val x=listBounds.left+change.position.x;val y=listBounds.top+change.position.y;val geometry=drops.preview
                                if(geometry!=null&&geometry.windowBounds.contains(Offset(x,y))){val point=geometry.toDesign(x.toDouble(),y.toDouble());doc.createLayerFromAsset(asset,point?.x ?: 0.0,point?.y ?: 0.0)}
                            }
                            break
                        }
                        if(!moving&&(change.position-down.position).getDistance()>viewConfiguration.touchSlop&&assetTargetAttribute(asset)!=null){moving=true;dragged=asset}
                        if(moving)change.consume()
                    }
                }finally{dragged=null}
            }
        }) {
            LazyColumn(Modifier.fillMaxSize().padding(end=10.dp).testTag("asset-list"),state=listState) {
                items(assets,key={it.relative}){asset->
                    Card(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=3.dp).testTag("asset-card-${asset.relative}"),shape=RoundedCornerShape(10.dp),colors=CardDefaults.cardColors(containerColor=if(selected?.relative==asset.relative)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            val thumb=remember(asset.relative){
                                val font=assetTargetAttribute(asset)=="font"
                                EditorLayer("asset:${asset.relative}",if(font)"text" else if(asset.kind=="videos")"video" else "image",mapOf("path" to (if(asset.kind in setOf("images","videos"))asset.relative.substringAfter('/') else asset.relative),"font" to ((if(asset.kind=="fonts_bm")"bm:" else "")+asset.relative.substringAfter('/').substringBeforeLast('.')),"text" to "Aa 12","text_size" to "32","opacity" to "100","color" to "ffffff","alignment" to "cc","display" to "bd"))
                            }
                            Box(Modifier.semantics{contentDescription="素材预览"}){if(asset.kind=="sfx")Icon(Icons.Default.VolumeUp,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.secondary)else LayerThumbnail(doc,thumb,40.dp)}
                            Column(Modifier.weight(1f)){Text(asset.relative.substringAfterLast('/'),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(asset.relative,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                        }
                    }
                }
                if(assets.isEmpty())item{Text("素材库为空\n导入图片、动画、视频或字体后会在此显示预览。",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)}
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState),Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical=4.dp).width(8.dp),style=desktopScrollbarStyle())
            Box(Modifier.offset{IntOffset(menu?.second?.x?.roundToInt() ?: 0,menu?.second?.y?.roundToInt() ?: 0)}.size(1.dp)) {
                DropdownMenu(menu!=null,{menu=null},modifier=Modifier.testTag("asset-context-menu")){menu?.first?.let{asset->
                    DropdownMenuItem(text={Text("创建图层")},enabled=assetTargetAttribute(asset)!=null&&!doc.editingBlocked,leadingIcon={Icon(Icons.Default.Add,null)},onClick={menu=null;doc.createLayerFromAsset(asset)})
                    DropdownMenuItem(text={Text("复制资源路径")},leadingIcon={Icon(Icons.Default.ContentCopy,null)},onClick={Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(asset.relative),null);menu=null})
                    DropdownMenuItem(text={Text("在资源管理器中显示")},leadingIcon={Icon(Icons.Default.FolderOpen,null)},onClick={runCatching{ProcessBuilder("explorer.exe","/select,",asset.path.toString()).start()}.onFailure{doc.error="无法打开资源管理器：${it.message}"};menu=null})
                    HorizontalDivider()
                    DropdownMenuItem(text={Text("删除素材",color=MaterialTheme.colorScheme.error)},enabled=!doc.editingBlocked,leadingIcon={Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error)},onClick={delete=asset;menu=null})
                }}
            }
        }
    }
    delete?.let{asset->AlertDialog(onDismissRequest={delete=null},title={Text("删除素材？")},text={Text("${asset.relative}\n被图层或字体图集引用的素材会阻止删除；外部原文件不会被删除。")},confirmButton={TextButton({delete=null;doc.deleteLibraryAsset(asset)}){Text("删除")}},dismissButton={TextButton({delete=null}){Text("取消")}})}
}
