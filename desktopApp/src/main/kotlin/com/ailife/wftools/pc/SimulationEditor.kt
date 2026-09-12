package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import java.lang.reflect.Modifier as ReflectModifier
import java.time.LocalDateTime

/** Constructor-order fields are explicit JVM data-class storage, excluding derived/runtime state. */
object SimulationEditor {
    private val fields = WmTagsData.WmPreviewState::class.java.declaredFields.filterNot { ReflectModifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
    private val constructor = WmTagsData.WmPreviewState::class.java.declaredConstructors.single { it.parameterCount == fields.size }
    private val excluded = setOf("tagOverride", "variables", "scriptRuntime", "elapsedSeconds")
    val editableNames = fields.map { it.name }.filterNot { it in excluded }
    fun values(state: WmTagsData.WmPreviewState): Map<String, String> = fields.filterNot { it.name in excluded }.associate { field ->
        val value = field.get(state)
        field.name to when(value) { is List<*> -> value.joinToString(","); is Triple<*,*,*> -> "${value.first},${value.second},${value.third}"; else -> value?.toString().orEmpty() }
    }
    fun replace(state: WmTagsData.WmPreviewState, name: String, text: String): WmTagsData.WmPreviewState {
        require(name in editableNames) { "此模拟字段不可编辑。" }
        val args = fields.map { field ->
            if(field.name != name) field.get(state) else when(field.type) {
                Int::class.javaPrimitiveType -> text.toIntOrNull() ?: error("请输入整数。")
                Long::class.javaPrimitiveType -> text.toLongOrNull() ?: error("请输入整数。")
                Float::class.javaPrimitiveType -> text.toFloatOrNull()?.takeIf { it.isFinite() } ?: error("请输入有限数值。")
                Double::class.javaPrimitiveType -> text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("请输入有限数值。")
                Boolean::class.javaPrimitiveType -> text.toBooleanStrictOrNull() ?: error("请输入 true 或 false。")
                String::class.java -> if(name == "alarmTime" && text.isBlank()) null else text
                LocalDateTime::class.java -> runCatching { LocalDateTime.parse(text.replace(' ', 'T')) }.getOrElse { error("时间格式为 2026-09-11T12:30:00。") }
                List::class.java -> if(text.isBlank()) emptyList<Int>() else text.split(',').map { it.trim().toIntOrNull() ?: error("请用逗号分隔整数。") }
                Triple::class.java -> text.split(',').map { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: error("请用逗号分隔三个数值。") }.let { require(it.size == 3) { "请填写 X,Y,Z 三个数值。" }; Triple(it[0],it[1],it[2]) }
                else -> error("不支持此模拟字段类型。")
            }
        }.toTypedArray()
        return constructor.newInstance(*args) as WmTagsData.WmPreviewState
    }
    fun restore(state: WmTagsData.WmPreviewState, values: Map<String, String>): WmTagsData.WmPreviewState = values.entries.fold(state) { current,(key,value) ->
        if(key in editableNames) runCatching { replace(current,key,value) }.getOrDefault(current) else current
    }
}

private val frequentSimulation = linkedMapOf("now" to "模拟时间", "batteryPercentage" to "手表电量 %", "batteryCharging" to "正在充电", "steps" to "步数", "stepGoal" to "步数目标", "heartRate" to "心率", "temp" to "温度", "conditionText" to "天气", "weatherLocation" to "城市")
@Composable fun SimulationFields(doc: DocumentController, advanced: Boolean) {
    val values = SimulationEditor.values(doc.render.simulation)
    val names = if(advanced) SimulationEditor.editableNames.filterNot { it in frequentSimulation } else frequentSimulation.keys.toList()
    names.forEach { name -> FieldRow(frequentSimulation[name] ?: name, values[name].orEmpty(), doc) { text ->
        try { doc.render.simulation = SimulationEditor.replace(doc.render.simulation, name, text); doc.saveRenderPreferences() }
        catch(e: Exception) { doc.error = "${frequentSimulation[name] ?: name}：${e.message}" }
    } }
}
fun DocumentController.saveRenderPreferences() {
    preview = preview.copy(simulation = SimulationEditor.values(render.simulation) + mapOf("_playing" to render.playing.toString(), "_bright" to render.isBright.toString(),
        "_interactive" to render.interactionMode.toString(), "_speed" to render.timeSpeed.toString(), "_offset" to render.timeOffsetMillis.toString(), "_theme" to render.themeIndex.toString()))
}
fun DocumentController.restoreRenderPreferences() {
    val values = preview.simulation
    render.simulation = SimulationEditor.restore(render.simulation, values)
    values["_playing"]?.toBooleanStrictOrNull()?.let { render.playing = it }
    values["_bright"]?.toBooleanStrictOrNull()?.let { render.isBright = it }
    values["_interactive"]?.toBooleanStrictOrNull()?.let { render.interactionMode = it }
    values["_speed"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -3600.0..3600.0 }?.let { render.timeSpeed = it }
    values["_offset"]?.toLongOrNull()?.let { render.timeOffsetMillis = it }
}
@Composable fun SimulationCategories(doc: DocumentController) {
    fun change(name: String,value: String) { try { doc.render.simulation = SimulationEditor.replace(doc.render.simulation,name,value); doc.saveRenderPreferences() } catch(e: Exception) { doc.error = e.message } }
    @Composable fun number(name: String,label: String,range: ClosedFloatingPointRange<Float>) {
        WmNumericControl(label,SimulationEditor.values(doc.render.simulation)[name].orEmpty(),doc,range) { change(name,it) }
    }
    @Composable fun toggle(name: String,label: String) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
            Switch(SimulationEditor.values(doc.render.simulation)[name] == "true",{ change(name,it.toString()) })
        }
    }
    WmCollapsible("电量与充电") { toggle("batteryCharging","手表正在充电"); number("batteryPercentage","手表电量 %",0f..100f); number("phoneBatteryPercentage","手机电量 %",0f..100f); toggle("phoneCharging","手机正在充电") }
    WmCollapsible("健康与活动") { number("steps","步数",0f..20000f); number("stepGoal","步数目标",1000f..30000f); number("heartRate","心率",40f..220f); number("calories","消耗热量",0f..1500f); number("bloodOxygen","血氧 %",80f..100f) }
    WmCollapsible("天气") {
        number("temp","当前温度 °C",-30f..50f); number("humidity","湿度 %",0f..100f)
        FieldRow("天气状态",doc.render.simulation.conditionText,doc) { change("conditionText",it) }
        FieldRow("城市",doc.render.simulation.weatherLocation,doc) { change("weatherLocation",it) }
    }
    WmCollapsible("连接与模式") { toggle("wifiConnected","Wi-Fi 已连接"); toggle("bluetoothEnabled","蓝牙已开启"); toggle("gpsEnabled","GPS 已开启"); toggle("is24hMode","24 小时制"); toggle("lowPower","低电量模式") }
    WmCollapsible("睡眠") { toggle("sleepHasData","有睡眠数据"); number("sleepAsleepMin","睡眠分钟",0f..720f); FieldRow("入睡时间",doc.render.simulation.sleepBedtime,doc) { change("sleepBedtime",it) }; FieldRow("起床时间",doc.render.simulation.sleepWakeTime,doc) { change("sleepWakeTime",it) } }
}
@Composable fun ThemePreviewControl(doc: DocumentController) {
    LaunchedEffect(doc.view.project.themeSets) { doc.render.bind(doc.view.project).join(); doc.preview.simulation["_theme"]?.toIntOrNull()?.let(doc.render::selectTheme) }
    ChoiceControl("表盘主题", doc.view.project.themeSets.mapIndexed { index, set -> index.toString() to (set.attrs["name"] ?: "主题 ${index + 1}") }, doc.render.themeIndex.toString(), { value ->
        doc.render.selectTheme(value.toInt())
        doc.preview = doc.preview.copy(simulation = doc.preview.simulation + ("_theme" to value))
    })
}
