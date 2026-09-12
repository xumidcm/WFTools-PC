@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

internal fun shouldCommitFieldEnter(multiline:Boolean, shiftPressed:Boolean):Boolean = !multiline || !shiftPressed

@Composable fun CompactButton(label: String, enabled: Boolean = true, tooltip: String = label, onClick: () -> Unit) {
    TooltipArea(tooltip = { Surface(shadowElevation = 3.dp, shape = RoundedCornerShape(6.dp)) { Text(tooltip, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) } }) {
        TextButton(onClick, enabled = enabled, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), shape = RoundedCornerShape(8.dp)) { Text(label, maxLines = 1) }
    }
}
@Composable fun CompactField(value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", enabled: Boolean = true, multiline: Boolean = false, onFocus: (Boolean) -> Unit = {},
    onEditorFocus: (Boolean, () -> Unit) -> Unit = { _,_ -> }, onDraftChange: (String) -> Unit = {}, fieldHeight: Dp = if(multiline) 160.dp else 38.dp,
    contentDescription:String?=null,fieldTag:String?=null) {
    var text by remember { mutableStateOf(value) }
    var currentHeight by remember(fieldHeight) { mutableStateOf(fieldHeight) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = value }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val currentCommit by rememberUpdatedState(onCommit)
    val latestValue by rememberUpdatedState(value)
    val commit = remember { { if(text != latestValue) currentCommit(text) } }
    val editorFocus by rememberUpdatedState(onEditorFocus)
    val focusChange by rememberUpdatedState(onFocus)
    DisposableEffect(Unit) { onDispose { if(focused) commit(); editorFocus(false,commit); if(focused) focusChange(false) } }
    Box(modifier.height(currentHeight)) {
    val fieldModifier = Modifier.fillMaxSize()
        .border(1.dp, if (focused) colors.primary else colors.outlineVariant, RoundedCornerShape(6.dp))
        .background(colors.surface, RoundedCornerShape(6.dp))
        .onFocusChanged { state ->
            if (focused && !state.isFocused && text != value) onCommit(text)
            focused = state.isFocused; onFocus(state.isFocused)
            onEditorFocus(state.isFocused, commit)
            if (!state.isFocused) text = value
        }.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && shouldCommitFieldEnter(multiline,event.isShiftPressed)) { if (text != value) onCommit(text); true }
            else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { text = value; true } else false
        }.then(if(contentDescription!=null)Modifier.semantics(mergeDescendants=true){this.contentDescription=contentDescription}else Modifier)
        .then(if(fieldTag!=null)Modifier.testTag(fieldTag)else Modifier)
        .padding(horizontal = 9.dp, vertical = if (multiline) 8.dp else 8.dp)
    BasicTextField(text, { text = it; onDraftChange(it) }, fieldModifier,
        enabled = enabled, textStyle = MaterialTheme.typography.bodyMedium.copy(color = if(enabled) colors.onSurface else colors.onSurfaceVariant),
        cursorBrush = SolidColor(colors.primary), singleLine = !multiline,
        decorationBox = { inner -> Box { if (text.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant, maxLines = 1, style = MaterialTheme.typography.bodyMedium); inner() } })
    if(multiline && enabled) Box(Modifier.align(Alignment.BottomEnd).size(32.dp).testTag("field-resize-handle")
        .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.SE_RESIZE_CURSOR)))
        .pointerInput(fieldHeight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startHeight=currentHeight
                val startScreenY=runCatching{java.awt.MouseInfo.getPointerInfo().location.y}.getOrNull()
                var fallbackDelta=0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    fallbackDelta+=change.positionChange().y
                    val screenDelta=startScreenY?.let{start->runCatching{java.awt.MouseInfo.getPointerInfo().location.y-start}.getOrNull()}?.takeIf{it!=0}?.toFloat()
                    currentHeight=(startHeight.value+(screenDelta?:fallbackDelta)/density.density).dp.coerceIn(72.dp,420.dp)
                    change.consume()
                }
            }
        }) {
        Canvas(Modifier.align(Alignment.BottomEnd).size(18.dp).padding(end=3.dp,bottom=3.dp)) {
            val stroke=1.dp.toPx()
            repeat(3){index->
                val inset=((index+1)*4).dp.toPx()
                drawLine(colors.outline,Offset(size.width-inset,size.height),Offset(size.width,size.height-inset),stroke)
            }
        }
    }
    }
}
@Composable fun FieldRow(label: String, value: String, doc: DocumentController?, enabled: Boolean = true, multiline: Boolean = false,
    fieldModifier: Modifier = Modifier, onDraftChange: (String) -> Unit = {}, onCommit: (String) -> Unit) {
    if (multiline) Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp)); CompactField(value, onCommit, fieldModifier.fillMaxWidth(), enabled = enabled, multiline = true, onFocus = { doc?.focus = if(it) EditorFocus.TEXT else EditorFocus.OTHER }, onEditorFocus = { focused,commit -> if(focused) doc?.commitFocusedField = commit else if(doc?.commitFocusedField === commit) doc.commitFocusedField = null }, onDraftChange = onDraftChange,contentDescription=label)
    } else Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompactField(value, onCommit, fieldModifier.weight(1f), enabled = enabled, onFocus = { doc?.focus = if(it) EditorFocus.TEXT else EditorFocus.OTHER }, onEditorFocus = { focused,commit -> if(focused) doc?.commitFocusedField = commit else if(doc?.commitFocusedField === commit) doc.commitFocusedField = null }, onDraftChange = onDraftChange,contentDescription=label)
    }
}
@Composable fun ChoiceControl(label: String, items: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(onClick = { expanded = true },enabled = enabled,modifier = Modifier.fillMaxWidth().height(32.dp),shape = RoundedCornerShape(8.dp),color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(horizontal = 9.dp),verticalAlignment = Alignment.CenterVertically) {
                Text(items.firstOrNull { it.first == selected }?.second ?: label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Default.ArrowDropDown,null,Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded, { expanded = false }) { items.forEach { (value,text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelected(value) }) } }
    }
}
@Composable fun SectionTitle(label: String) { Text(label, Modifier.padding(top = 12.dp, bottom = 5.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
@Composable fun desktopScrollbarStyle() = ScrollbarStyle(minimalHeight = 24.dp,thickness = 6.dp,shape = RoundedCornerShape(4.dp),hoverDurationMillis = 120,
    unhoverColor = MaterialTheme.colorScheme.outline.copy(alpha = .5f),hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = .75f))
@Composable fun ScrollableInspector(modifier: Modifier, padding: Dp, spacing: Dp, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(end = 10.dp).verticalScroll(scroll).padding(padding),verticalArrangement = Arrangement.spacedBy(spacing),content = content)
        VerticalScrollbar(rememberScrollbarAdapter(scroll),Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(8.dp),style = desktopScrollbarStyle())
    }
}
@Composable fun ErrorBanner(message: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(dismiss, contentPadding = PaddingValues(4.dp)) { Text("知道了") }
        }
    }
}
