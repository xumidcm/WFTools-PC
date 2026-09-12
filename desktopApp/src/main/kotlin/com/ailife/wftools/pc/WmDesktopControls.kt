@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.ailife.wftools.pc.core.EditorLayer
import com.ailife.wftools2.ui.editor.utils.rememberPreviewBitmap
import kotlin.math.roundToInt

@Composable fun WmIconButton(icon: ImageVector, description: String, enabled: Boolean = true, selected: Boolean = false, size: Dp = 32.dp, onClick: () -> Unit) {
    TooltipArea(tooltip = { Surface(shadowElevation = 3.dp, shape = RoundedCornerShape(6.dp)) { Text(description,Modifier.padding(8.dp),style = MaterialTheme.typography.bodySmall) } }) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            if(selected) FilledTonalIconButton(onClick,Modifier.size(size),enabled = enabled,colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,contentColor = MaterialTheme.colorScheme.primary)) { Icon(icon,description,Modifier.size(18.dp)) }
            else IconButton(onClick,Modifier.size(size),enabled = enabled) { Icon(icon,description,Modifier.size(18.dp)) }
        }
    }
}
@Composable fun WmParamCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier.fillMaxWidth(),shape = RoundedCornerShape(12.dp), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title,style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.Bold)
            content()
        }
    }
}
@Composable fun FxButton(onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick,enabled = enabled,modifier = Modifier.size(26.dp),contentPadding = PaddingValues(0.dp)) { Text("fx",fontFamily = FontFamily.Monospace,fontSize = 12.sp) }
}
@Composable fun DocumentValue(value: String, label: String, doc: DocumentController, onCommit: (String) -> Unit, modifier: Modifier = Modifier,fieldTag:String?=null) {
    CompactField(value,onCommit,modifier,enabled = !doc.editingBlocked,contentDescription=label,fieldTag=fieldTag,
        onFocus = { doc.focus = if(it) EditorFocus.TEXT else EditorFocus.OTHER },
        onEditorFocus = { focused,commit -> if(focused) doc.commitFocusedField = commit else if(doc.commitFocusedField === commit) doc.commitFocusedField = null })
}
@Composable fun WmNumericControl(label: String, raw: String, doc: DocumentController, range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, compact: Boolean = false, onExpression: (() -> Unit)? = null, onStep: ((Float) -> Unit)? = null,
    onPreview: ((Float) -> Unit)? = null, onFinish: ((Float,(Boolean)->Unit) -> Unit)? = null, onCommit: (String) -> Unit) {
    val evaluated = raw.toFloatOrNull() ?: doc.render.evaluate(raw.ifBlank { "0" }).toFloatOrNull() ?: 0f
    var value by remember(raw) { mutableStateOf(evaluated.coerceIn(range)) }
    var sliding by remember { mutableStateOf(false) }
    var awaitingCommit by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun save(number: Float) = onCommit(java.math.BigDecimal.valueOf(number.toDouble()).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
    Column(modifier,verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if(compact) label.substringBefore(' ') else label,Modifier.weight(1f),style = MaterialTheme.typography.bodySmall)
            if(onExpression != null) FxButton(onExpression,!doc.editingBlocked)
        }
        DocumentValue(if(sliding || awaitingCommit) "%.1f".format(java.util.Locale.ROOT,value) else raw.ifBlank { "0" },label,doc,onCommit,Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(28.dp),verticalAlignment = Alignment.CenterVertically) {
            WmIconButton(Icons.Default.Remove,"减小 $label",!doc.editingBlocked,selected = true,size = 24.dp) { focus.clearFocus(); if(onStep != null) onStep(-1f) else onCommit(offsetEditorExpression(raw,-1f)) }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Slider(value,onValueChange = { if(!sliding) focus.clearFocus(); sliding = true; value = it; onPreview?.invoke(it) },onValueChangeFinished = {
                    sliding = false
                    if(onFinish == null) save(value) else { awaitingCommit = true; onFinish(value) { awaitingCommit = false } }
                },valueRange = range,enabled = !doc.editingBlocked,modifier = Modifier.weight(1f).height(26.dp).testTag("slider-$label"))
            }
            WmIconButton(Icons.Default.Add,"增大 $label",!doc.editingBlocked,selected = true,size = 24.dp) { focus.clearFocus(); if(onStep != null) onStep(1f) else onCommit(offsetEditorExpression(raw,1f)) }
        }
    }
}
@Composable fun WmSegmented(options: List<Pair<String,String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().height(32.dp)) {
            options.forEachIndexed { index,(key,label) -> SegmentedButton(selected == key,{ onSelect(key) },shape = SegmentedButtonDefaults.itemShape(index,options.size),icon = {}) { Text(label,style = MaterialTheme.typography.labelSmall,maxLines = 1) } }
        }
    }
}
@Composable fun WmCollapsible(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column {
        Row(Modifier.fillMaxWidth().height(36.dp).clickable { expanded = !expanded },verticalAlignment = Alignment.CenterVertically) {
            Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null,Modifier.size(20.dp))
            Text(title,Modifier.weight(1f).padding(start = 6.dp),style = MaterialTheme.typography.titleSmall,fontWeight = FontWeight.Bold)
        }
        if(expanded) Column(Modifier.padding(bottom = 8.dp),verticalArrangement = Arrangement.spacedBy(6.dp),content = content)
        HorizontalDivider(thickness = .5.dp,color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
    }
}
fun parseUiColor(raw: String): Color? = runCatching {
    val text = raw.trim().removePrefix("#")
    when(text.length) { 6 -> Color((0xff000000L or text.toLong(16)).toInt()); 8 -> Color(text.toLong(16).toInt()); else -> null }
}.getOrNull()
@Composable fun WmColorControl(raw: String, doc: DocumentController, onExpression: () -> Unit, onCommit: (String) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(parseUiColor(raw) ?: MaterialTheme.colorScheme.surfaceVariant).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(6.dp)).clickable { picker = true })
        DocumentValue(raw,"颜色",doc,onCommit,Modifier.weight(1f))
        WmIconButton(Icons.Default.Colorize,"选择颜色",!doc.editingBlocked) { picker = true }
        FxButton(onExpression,!doc.editingBlocked)
    }
    if(picker) WmColorDialog(raw,{ picker = false }) { onCommit(it); picker = false }
}
@Composable private fun WmColorDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var hex by remember { mutableStateOf(initial) }; val color = parseUiColor(hex) ?: Color.White
    fun update(red: Int = (color.red*255).roundToInt(),green: Int = (color.green*255).roundToInt(),blue: Int = (color.blue*255).roundToInt()) { hex = "%02X%02X%02X".format(red,green,blue) }
    AlertDialog(onDismissRequest = onDismiss,title = { Text("选择颜色") },text = {
        Column(Modifier.width(320.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).background(color,RoundedCornerShape(10.dp)).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(10.dp)))
                CompactField(hex,{ hex = it },Modifier.weight(1f),onDraftChange = { hex = it })
            }
            listOf("红" to color.red,"绿" to color.green,"蓝" to color.blue).forEach { (label,v) -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label,Modifier.width(28.dp)); Slider(v,{ value -> when(label) { "红" -> update(red = (value*255).roundToInt()); "绿" -> update(green = (value*255).roundToInt()); else -> update(blue = (value*255).roundToInt()) } },Modifier.weight(1f))
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { listOf("FFFFFF","000000","F44336","FF9800","4CAF50","2196F3","9C27B0").forEach { swatch -> Box(Modifier.size(28.dp).clip(CircleShape).background(parseUiColor(swatch)!!).border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape).clickable { hex = swatch }) } }
            if(parseUiColor(hex) == null) Text("请输入六位 RGB 或八位 ARGB 十六进制颜色。",color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall)
        }
    },confirmButton = { Button({ onSave(hex.removePrefix("#")) },enabled = parseUiColor(hex) != null) { Text("应用") } },dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
@Composable fun WmAlignmentControl(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(listOf("tl" to "◤","tc" to "▲","tr" to "◥"),listOf("cl" to "◄","cc" to "●","cr" to "►"),listOf("bl" to "◣","bc" to "▼","br" to "◢")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { row.forEach { (key,glyph) ->
                Surface(onClick = { onSelect(key) },enabled = enabled,modifier = Modifier.size(23.dp),shape = RoundedCornerShape(5.dp),color = if(value.ifBlank { "cc" } == key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Text(glyph,fontSize = 11.sp,color = if(value == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } }
        }
    }
}
fun layerIcon(type: String): ImageVector = when(type) {
    "text","text_curved","text_ring" -> Icons.Default.TextFields
    "image","image_cond","gallery_2d" -> Icons.Default.Image
    "image_gif" -> Icons.Default.GifBox
    "video" -> Icons.Default.Movie
    "shape","rounded" -> Icons.Default.Category
    "calendar" -> Icons.Default.CalendarMonth
    "map" -> Icons.Default.Map
    "progress","chart","series" -> Icons.Default.BarChart
    else -> Icons.Default.Layers
}
@Composable fun LayerThumbnail(doc: DocumentController, layer: EditorLayer, size: Dp = 40.dp) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val bitmap = com.ailife.wftools.pc.render.rememberLayerThumbnail(layer,doc.render,(size.value*density*2).roundToInt().coerceIn(16,256),doc.view.revision)
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),contentAlignment = Alignment.Center) {
        if(bitmap != null) Image(bitmap,null,Modifier.fillMaxSize(),contentScale = ContentScale.Fit)
        else Icon(layerIcon(layer.type),null,Modifier.size(size*.55f),tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
