@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.ailife.wftools.pc.core.LayerSelection
import kotlinx.coroutines.*
import kotlin.math.*

val layerMenuCommands = listOf(EditorCommand.COPY,EditorCommand.PASTE,EditorCommand.DUPLICATE,EditorCommand.DELETE,EditorCommand.HIDE,EditorCommand.SHOW,EditorCommand.UP,EditorCommand.DOWN,EditorCommand.TOP,EditorCommand.BOTTOM)
@Composable fun Md3LayerMenu(position: Offset?,dismiss: () -> Unit,commands: CommandDispatcher) {
    if (position != null) Box(Modifier.offset { IntOffset(position.x.roundToInt(),position.y.roundToInt()) }.size(1.dp)) {
        DropdownMenu(true,dismiss,modifier=Modifier.testTag("layer-context-menu")) { layerMenuCommands.forEach { command -> DropdownMenuItem(text = { Text(command.label) },enabled = commands.enabled(command),onClick = { dismiss(); commands.execute(command) }) } }
    }
}
@Composable fun AddLayerPill(doc: DocumentController) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp,vertical = 8.dp)) {
        FilledTonalButton({ open = true },enabled = !doc.editingBlocked,modifier = Modifier.fillMaxWidth().height(40.dp).testTag("add-layer-pill"),shape = RoundedCornerShape(24.dp)) { Icon(Icons.Default.Add,null,Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("添加图层") }
        DropdownMenu(open,{ open = false }) { layerTypes.forEach { (type,label) -> DropdownMenuItem(text = { Text(label) },leadingIcon = { Icon(layerIcon(type),null,Modifier.size(18.dp)) },onClick = { open = false; doc.command { addLayer(type) } }) } }
    }
}
private fun predictedSelection(current: LayerSelection,id: String,order: List<String>,ctrl: Boolean,shift: Boolean): Set<String> {
    val anchor = current.anchor?.takeIf { it in order }
    if(shift && anchor != null) { val a=order.indexOf(anchor);val b=order.indexOf(id);val range=order.subList(min(a,b),max(a,b)+1).toSet();return if(ctrl) current.selected+range else range }
    if(ctrl) return if(id in current.selected) current.selected-id else current.selected+id
    return if(id in current.selected) current.selected else setOf(id)
}
@Composable fun LayerBrowser(doc: DocumentController,commands: CommandDispatcher,modifier: Modifier,showAdd: Boolean = true, drops: DropRegistry? = null) {
    val visible by rememberUpdatedState(doc.visible)
    val listState = rememberLazyListState(); val focus = remember { FocusRequester() }
    val density by rememberUpdatedState(LocalDensity.current.density)
    var menu by remember { mutableStateOf<Offset?>(null) }; var filter by remember { mutableStateOf(false) }
    var dragIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }; var dragGap by remember { mutableStateOf<Int?>(null) }
    fun gapAt(y: Float): Int {
        val items=listState.layoutInfo.visibleItemsInfo
        return items.firstOrNull { y < it.offset+it.size/2f }?.index ?: ((items.lastOrNull()?.index ?: -1)+1).coerceAtMost(visible.size)
    }
    LaunchedEffect(doc.view.selection.primary,visible.map { it.id },dragIds.isNotEmpty()) {
        if(dragIds.isNotEmpty()) return@LaunchedEffect
        val index=visible.indexOfFirst { it.id==doc.view.selection.primary }; if(index<0)return@LaunchedEffect
        val info=listState.layoutInfo;val row=info.visibleItemsInfo.firstOrNull{it.index==index}
        if(row==null || row.offset<info.viewportStartOffset || row.offset+row.size>info.viewportEndOffset)listState.requestScrollToItem(index)
    }
    LaunchedEffect(dragIds.isNotEmpty()) {
        while(dragIds.isNotEmpty()) {
            val info=listState.layoutInfo;val band=42f*density
            val speed=when { dragPoint.y<band -> -((band-dragPoint.y)/band).coerceIn(0f,2f)*13f*density;dragPoint.y>info.viewportEndOffset-band -> ((dragPoint.y-info.viewportEndOffset+band)/band).coerceIn(0f,2f)*13f*density;else->0f }
            if(speed!=0f)try { listState.scrollBy(speed) } catch(cancel: CancellationException) { currentCoroutineContext().ensureActive() }
            dragGap=gapAt(dragPoint.y);delay(16)
        }
    }
    Column(modifier.fillMaxWidth().onGloballyPositioned { drops?.layerList = it.boundsInWindow().dropBounds() }) {
        Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            CompactField(doc.query,{doc.query=it},Modifier.weight(1f),"搜索图层",onDraftChange={doc.query=it},onFocus={doc.focus=if(it)EditorFocus.TEXT else EditorFocus.OTHER})
            Box { WmIconButton(Icons.Default.FilterList,"筛选图层类型",selected=doc.typeFilter.isNotEmpty(),size=36.dp){filter=true}
                DropdownMenu(filter,{filter=false}){ (listOf("" to "全部类型")+doc.view.layers.map{it.type}.distinct().sorted().map{type->type to(layerTypes.firstOrNull{it.first==type}?.second ?: type)}).forEach{(type,label)->DropdownMenuItem(text={Text(label)},onClick={doc.typeFilter=type;filter=false})} }
            }
        }
        if(showAdd) AddLayerPill(doc)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusRequester(focus).onFocusChanged { if(it.hasFocus)doc.focus=EditorFocus.LAYERS }
            .onKeyEvent { event -> if(event.type==KeyEventType.KeyDown && event.key in setOf(Key.DirectionUp,Key.DirectionDown) && !doc.editingBlocked) {
                val order=visible.map{it.id};val delta=if(event.key==Key.DirectionUp)-1 else 1;val ctrl=event.isCtrlPressed;val shift=event.isShiftPressed
                doc.command { nextVisibleSelection(order,selection.primary,delta)?.let{select(it,order,ctrl,shift)} };true
            }else false }.focusable()
            .onPointerEvent(PointerEventType.Press) { event ->
                if(event.buttons.isSecondaryPressed && !doc.editingBlocked) {
                    val point=event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    if(point.x>listState.layoutInfo.viewportSize.width)return@onPointerEvent
                    val row=listState.layoutInfo.visibleItemsInfo.firstOrNull{point.y>=it.offset&&point.y<it.offset+it.size} ?: return@onPointerEvent
                    val id=row.key as? String ?: return@onPointerEvent
                    val order=visible.map{it.id};if(id !in order)return@onPointerEvent
                    focus.requestFocus();doc.focus=EditorFocus.LAYERS
                    if(id !in doc.view.selection.selected)doc.command{select(id,order)}
                    menu=point
                }
            }
            .pointerInput(doc.session.id) {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    if(down.position.x > size.width-10*density || doc.editingBlocked)return@awaitEachGesture
                    val row=listState.layoutInfo.visibleItemsInfo.firstOrNull{down.position.y>=it.offset && down.position.y<it.offset+it.size} ?: return@awaitEachGesture
                    val id=row.key as? String ?: return@awaitEachGesture
                    val order=visible.map{it.id};if(id !in order)return@awaitEachGesture
                    focus.requestFocus();doc.focus=EditorFocus.LAYERS
                    if(!currentEvent.buttons.isPrimaryPressed)return@awaitEachGesture
                    val ctrl=currentEvent.keyboardModifiers.isCtrlPressed;val shift=currentEvent.keyboardModifiers.isShiftPressed
                    val initial=doc.view.selection;val moving=predictedSelection(initial,id,order,ctrl,shift).ifEmpty{setOf(id)}
                    if(id !in initial.selected || ctrl || shift)doc.command{select(id,order,ctrl,shift)}
                    var moved=false
                    try {
                        while(true) {
                            val event=awaitPointerEvent(PointerEventPass.Initial)
                            val change=event.changes.firstOrNull{it.id==down.id} ?: break
                            if(event.type==PointerEventType.Scroll){dragPoint=change.position;continue}
                            if(!change.pressed) {
                                if(moved) {
                                    val full=doc.view.layers.asReversed().map{it.id};val current=visible.map{it.id};val gap=gapAt(change.position.y)
                                    val fullGap=if(gap>=current.size)current.lastOrNull()?.let{full.indexOf(it)+1} ?: full.size else full.indexOf(current[gap]).coerceAtLeast(0)
                                    if(full.containsAll(moving))doc.command("正在调整图层顺序…") { selectAll(full.filter{it in moving && it!=id}+id);moveSelected(documentInsertionIndex(full,moving,fullGap)) }
                                }else if(!ctrl && !shift && initial.selected.size>1 && id in initial.selected)doc.command{select(id,order)}
                                break
                            }
                            if(!moved && (change.position-down.position).getDistance()>viewConfiguration.touchSlop){moved=true;dragIds=moving}
                            if(moved){change.consume();dragPoint=change.position;dragGap=gapAt(change.position.y)}
                        }
                    }finally{dragIds=emptySet();dragGap=null}
                }
            }) {
            LazyColumn(Modifier.fillMaxSize().padding(end=10.dp).testTag("layer-list"),state=listState) {
                items(visible,key={it.id}) { layer ->
                    val interaction=remember(layer.id){MutableInteractionSource()};val hovered by interaction.collectIsHoveredAsState()
                    val selected=layer.id in doc.view.selection.selected
                    Card(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=3.dp).testTag("layer-row-${layer.id}"),shape=RoundedCornerShape(10.dp),colors=CardDefaults.cardColors(containerColor=when{layer.id in dragIds->MaterialTheme.colorScheme.tertiaryContainer;selected&&doc.view.selection.selected.size>1->MaterialTheme.colorScheme.secondaryContainer;selected->MaterialTheme.colorScheme.primaryContainer;else->MaterialTheme.colorScheme.surfaceVariant})) {
                        Row(Modifier.fillMaxWidth().height(52.dp).hoverable(interaction).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            LayerThumbnail(doc,layer,38.dp)
                            Column(Modifier.weight(1f)){Text(layer.attrs["name"]?.ifBlank{null} ?: "图层 ${doc.view.layers.indexOfFirst{it.id==layer.id}+1}",fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text((layerTypes.firstOrNull{it.first==layer.type}?.second ?: layer.type)+(layer.attrs["path"] ?: layer.attrs["text"])?.takeIf{it.isNotBlank()}?.let{" · $it"}.orEmpty(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                            Box(Modifier.size(28.dp)){if(hovered||selected)WmIconButton(Icons.Default.Edit,"编辑图层属性",!doc.editingBlocked,size=28.dp){doc.command{select(layer.id,visible.map{it.id})};doc.requestedInspectorTab="属性"}}
                        }
                    }
                }
                if(visible.isEmpty())item{Text(if(doc.view.layers.isEmpty())"使用“添加图层”开始创作" else "没有匹配的图层",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)}
            }
            val markerColor=MaterialTheme.colorScheme.primary
            if(dragGap!=null)Canvas(Modifier.matchParentSize()) { val info=listState.layoutInfo.visibleItemsInfo;val y=info.firstOrNull{it.index==dragGap}?.offset?.toFloat() ?: info.lastOrNull()?.let{(it.offset+it.size).toFloat()} ?: 0f;drawLine(markerColor,Offset(10.dp.toPx(),y),Offset(size.width-20.dp.toPx(),y),2.dp.toPx()) }
            VerticalScrollbar(rememberScrollbarAdapter(listState),Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical=4.dp).width(8.dp),style=desktopScrollbarStyle())
            Md3LayerMenu(menu,{menu=null},commands)
        }
    }
}
