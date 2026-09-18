package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.preview.CanvasOperationMode

@Composable fun EditorOverflow(expanded: Boolean,dismiss: () -> Unit,doc: DocumentController,commands: CommandDispatcher,theme: ThemePreference,onTheme: (ThemePreference)->Unit,canvasInput:CanvasInputPreferences,onCanvasInput:(CanvasInputPreferences)->Unit) {
    var page by remember(expanded) { mutableStateOf("root") }
    DropdownMenu(expanded,dismiss) {
        if(page != "root") { DropdownMenuItem(text = { Text("返回") },leadingIcon = { Icon(Icons.Default.ChevronLeft,null) },onClick = { page = "root" }); HorizontalDivider() }
        when(page) {
            "root" -> {
                listOf("file" to "文件","edit" to "编辑","layers" to "图层").forEach { (key,label) -> DropdownMenuItem(text = { Text(label) },trailingIcon = { Icon(Icons.Default.ChevronRight,null) },onClick = { page = key }) }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("画布设置") },modifier = Modifier.testTag("workspace-settings-entry"),leadingIcon = { Icon(Icons.Default.Tune,null) },onClick = { page = "canvas" })
                DropdownMenuItem(text = { Text("外观设置") },leadingIcon = { Icon(Icons.Default.Palette,null) },onClick = { page = "theme" })
            }
            "file" -> listOf(EditorCommand.NEW,EditorCommand.OPEN,EditorCommand.SAVE,EditorCommand.SAVE_AS,EditorCommand.CLOSE).forEach { command -> DropdownMenuItem(text = { Text(command.label) },enabled = commands.enabled(command),onClick = { dismiss(); commands.execute(command) }) }
            "edit" -> listOf(EditorCommand.UNDO,EditorCommand.REDO,EditorCommand.COPY,EditorCommand.PASTE,EditorCommand.DUPLICATE,EditorCommand.DELETE,EditorCommand.SELECT_ALL).forEach { command -> DropdownMenuItem(text = { Text(command.label) },enabled = commands.enabled(command),onClick = { dismiss(); commands.execute(command) }) }
            "layers" -> {
                DropdownMenuItem(text = { Text("添加图层") },leadingIcon = { Icon(Icons.Default.Add,null) },onClick = { page = "add" })
                listOf(EditorCommand.HIDE,EditorCommand.SHOW,EditorCommand.UP,EditorCommand.DOWN,EditorCommand.TOP,EditorCommand.BOTTOM).forEach { command -> DropdownMenuItem(text = { Text(command.label) },enabled = commands.enabled(command),onClick = { dismiss(); commands.execute(command) }) }
            }
            "add" -> layerTypes.forEach { (type,label) -> DropdownMenuItem(text = { Text(label) },leadingIcon = { Icon(layerIcon(type),null,Modifier.size(18.dp)) },onClick = { dismiss(); doc.command { addLayer(type) } }) }
            "canvas" -> {
                Text("操作方式",Modifier.padding(horizontal = 16.dp,vertical = 6.dp),style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.primary)
                listOf(CanvasOperationMode.DIRECT_DRAG to "鼠标直拖",CanvasOperationMode.SELECT_THEN_DRAG to "先选后拖").forEach { (mode,label) -> DropdownMenuItem(text = { Text(label) },leadingIcon = { RadioButton(doc.preview.operationMode == mode,null) },onClick = { doc.preview = doc.preview.copy(operationMode = mode) }) }
                Text(if(doc.preview.operationMode==CanvasOperationMode.DIRECT_DRAG)"侧边栏选中的图层优先：按住画布任意位置拖动都移动当前选区；没有选中时按住某个图层即可选中并移动。" else "与鼠标直拖相同，但没有选中时按住图层只选中、不移动。",Modifier.widthIn(max=270.dp).padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                DropdownMenuItem(text={Text("中键拖动画布")},modifier=Modifier.testTag("editor-middle-button-pan-toggle"),trailingIcon={Checkbox(canvasInput.middleButtonPan,null)},onClick={onCanvasInput(canvasInput.copy(middleButtonPan=!canvasInput.middleButtonPan))})
                DropdownMenuItem(text={Text("右键拖动画布")},modifier=Modifier.testTag("editor-right-button-pan-toggle"),trailingIcon={Checkbox(canvasInput.rightButtonPan,null)},onClick={onCanvasInput(canvasInput.copy(rightButtonPan=!canvasInput.rightButtonPan))})
                DropdownMenuItem(text={Text("滚轮缩放选中元素")},modifier=Modifier.testTag("editor-wheel-layer-scale-toggle"),trailingIcon={Checkbox(canvasInput.wheelScalesSelectedLayer,null)},onClick={onCanvasInput(canvasInput.copy(wheelScalesSelectedLayer=!canvasInput.wheelScalesSelectedLayer))})
                HorizontalDivider()
                DropdownMenuItem(text={Text("自动吸附")},modifier=Modifier.testTag("automatic-snapping-toggle"),trailingIcon={Checkbox(canvasInput.snapping,null)},onClick={onCanvasInput(canvasInput.copy(snapping=!canvasInput.snapping))})
                DropdownMenuItem(text={Text("变换控制点")},modifier=Modifier.testTag("transform-handles-toggle"),trailingIcon={Checkbox(canvasInput.showTransformHandles,null)},onClick={onCanvasInput(canvasInput.copy(showTransformHandles=!canvasInput.showTransformHandles))})
                Text(if(canvasInput.showTransformHandles) "显示八向缩放点与旋转拉杆" else "使用简洁选框，仅选择与移动",Modifier.widthIn(max = 270.dp).padding(horizontal = 16.dp,vertical = 6.dp),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenuItem(text={Text("显示操作状态提示")},modifier=Modifier.testTag("operation-status-toggle"),trailingIcon={Checkbox(canvasInput.showOperationStatus,null)},onClick={onCanvasInput(canvasInput.copy(showOperationStatus=!canvasInput.showOperationStatus))})
            }
            "theme" -> ThemePreference.entries.forEach { choice -> DropdownMenuItem(text = { Text(when(choice) { ThemePreference.SYSTEM -> "跟随系统"; ThemePreference.LIGHT -> "浅色"; ThemePreference.DARK -> "深色" }) },leadingIcon = { RadioButton(theme == choice,null) },onClick = { onTheme(choice) }) }
        }
    }
}
