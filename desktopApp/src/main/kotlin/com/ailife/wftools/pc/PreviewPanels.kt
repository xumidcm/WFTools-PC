package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.preview.*
import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class SimKind { INT, FLOAT, SWITCH, CODE_SWITCH, TEXT, CHOICE, TIME }
private data class SimField(val name:String,val label:String,val kind:SimKind,val min:Float=0f,val max:Float=100f,val choices:List<Pair<String,String>> = emptyList())
private data class SimSection(val title:String,val fields:List<SimField>)
private fun si(n:String,l:String,a:Float,b:Float)=SimField(n,l,SimKind.INT,a,b)
private fun sf(n:String,l:String,a:Float,b:Float)=SimField(n,l,SimKind.FLOAT,a,b)
private fun ss(n:String,l:String)=SimField(n,l,SimKind.SWITCH)
private fun st(n:String,l:String)=SimField(n,l,SimKind.TEXT)
private fun tm(n:String,l:String)=SimField(n,l,SimKind.TIME)
private fun sc(n:String,l:String,vararg options:Pair<String,String>)=SimField(n,l,SimKind.CHOICE,choices=options.toList())
private fun cs(n:String,l:String,off:Pair<String,String>,on:Pair<String,String>)=SimField(n,l,SimKind.CODE_SWITCH,choices=listOf(off,on))

@Composable fun KnownPreviewSettingsPanel(doc:DocumentController,modifier:Modifier) {
    ScrollableInspector(modifier,14.dp,12.dp) {
        PreviewRuntimeControls(doc)
        DevicePreviewControls(doc)
        PreviewSimulationControls(doc)
    }
}

@Composable private fun PreviewRuntimeControls(doc:DocumentController) {
    var autoplay by remember{mutableStateOf(false)}
    var interval by remember{mutableFloatStateOf(2f)}
    LaunchedEffect(autoplay,interval) { while(autoplay) { delay((interval*1000).toLong()); randomizePreview(doc) } }
    WmParamCard("运行、AOD 与时间机器") {
        WmSegmented(listOf("bright" to "亮屏","dim" to "息屏 / AOD"),if(doc.render.isBright)"bright" else "dim",{doc.render.isBright=it=="bright";doc.saveRenderPreferences()})
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(doc.render.playing)"预览正在运行" else "预览已暂停",Modifier.weight(1f));WmIconButton(if(doc.render.playing)Icons.Default.Pause else Icons.Default.PlayArrow,if(doc.render.playing)"暂停" else "继续"){doc.render.playing=!doc.render.playing;doc.saveRenderPreferences()}}
        listOf(listOf(-86400000L to "−1天",-3600000L to "−1时",-300000L to "−5分",-60000L to "−1分"),listOf(60000L to "+1分",300000L to "+5分",3600000L to "+1时",86400000L to "+1天")).forEach { row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){row.forEach{(delta,label)->TextButton({doc.render.timeOffsetMillis+=delta;doc.saveRenderPreferences()},contentPadding=PaddingValues(horizontal=5.dp)){Text(label,style=MaterialTheme.typography.labelSmall)}}}
        }
        Button({doc.render.resetTimeMachineToNow();doc.saveRenderPreferences()},Modifier.fillMaxWidth()){Text("回到现在")}
        WmSegmented(listOf("-3600" to "−3600×","-60" to "−60×","1" to "1×","60" to "60×","3600" to "3600×"),doc.render.timeSpeed.toInt().toString(),{doc.render.timeSpeed=it.toDouble();doc.saveRenderPreferences()})
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动随机模拟值",Modifier.weight(1f));Switch(autoplay,{autoplay=it})}
        if(autoplay) Slider(interval,{interval=it},valueRange=.5f..10f)
        OutlinedButton({randomizePreview(doc)},Modifier.fillMaxWidth()){Icon(Icons.Default.Shuffle,null);Spacer(Modifier.width(6.dp));Text("立即随机")}
        Row(verticalAlignment=Alignment.CenterVertically){Text("动作测试",Modifier.weight(1f));Switch(doc.render.interactionMode,{doc.render.interactionMode=it;doc.saveRenderPreferences()})}
    }
}

@Composable private fun DevicePreviewControls(doc:DocumentController) {
    val device=doc.preview.device
    var custom by remember(device.width,device.height){mutableStateOf(!DevicePreviewSettings.presets.contains(device.width to device.height))}
    var inputError by remember{mutableStateOf<String?>(null)}
    fun update(next:DevicePreviewSettings){doc.preview=doc.preview.copy(device=next)}
    WmParamCard("设备预览") {
        Row(Modifier.fillMaxWidth().testTag("device-preview-enabled"),verticalAlignment=Alignment.CenterVertically){Text("启用设备预览",Modifier.weight(1f));Switch(doc.preview.devicePreviewEnabled,{doc.preview=doc.preview.copy(devicePreviewEnabled=it)})}
        Text("关闭时按 WFTools 的 512 × 512 原始画布显示；开启后才应用下列设备外观。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(doc.preview.devicePreviewEnabled) {
            ChoiceControl("设备尺寸",DevicePreviewSettings.presets.map{(w,h)->"$w,$h" to "$w × $h"}+("custom" to "自定义"),if(custom)"custom" else "${device.width},${device.height}",{raw->if(raw=="custom")custom=true else {custom=false;val (w,h)=raw.split(',').map(String::toInt);update(device.copy(width=w,height=h));inputError=null}}, modifier=Modifier.testTag("device-size-choice"))
            if(custom) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Column(Modifier.weight(1f)){Text("宽度",style=MaterialTheme.typography.bodySmall);DocumentValue(device.width.toString(),"设备宽度",doc,{raw->raw.toIntOrNull()?.takeIf{it in 64..2048}?.let{update(device.copy(width=it));inputError=null}?:run{inputError="宽度须为 64–2048 的整数。"}},Modifier.fillMaxWidth(),fieldTag="device-custom-width")}
                Column(Modifier.weight(1f)){Text("高度",style=MaterialTheme.typography.bodySmall);DocumentValue(device.height.toString(),"设备高度",doc,{raw->raw.toIntOrNull()?.takeIf{it in 64..2048}?.let{update(device.copy(height=it));inputError=null}?:run{inputError="高度须为 64–2048 的整数。"}},Modifier.fillMaxWidth(),fieldTag="device-custom-height")}
            }
            Text("画布形状跟随“项目 → 表盘形状”，并随表盘一起保存。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceControl("适配方式",listOf("FIT" to "完整显示","FILL" to "填满视口","VERTICAL" to "适合高度","HORIZONTAL" to "适合宽度","STRETCH" to "拉伸","CUSTOM" to "自定义"),device.preset.name,{update(device.copy(preset=CanvasPreset.valueOf(it)))})
            if(device.preset==CanvasPreset.CUSTOM) {
                @Composable fun control(label:String,current:Double,range:ClosedFloatingPointRange<Float>,apply:(Double)->DevicePreviewSettings)=WmNumericControl(label,current.toString(),doc,range,onPreview={update(apply(it.toDouble()))},onFinish={_,done->done(true)},onCommit={it.toDoubleOrNull()?.let{v->update(apply(v))}})
                control("水平缩放",device.zoomX,.01f..20f){device.copy(zoomX=it)};control("垂直缩放",device.zoomY,.01f..20f){device.copy(zoomY=it)};control("水平偏移",device.offsetX,-16384f..16384f){device.copy(offsetX=it)};control("垂直偏移",device.offsetY,-16384f..16384f){device.copy(offsetY=it)}
            }
            inputError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

private fun randomizePreview(doc:DocumentController) {
    listOf("batteryPercentage" to Random.nextInt(5,101).toString(),"steps" to Random.nextInt(0,20001).toString(),"heartRate" to Random.nextInt(45,181).toString(),"temp" to Random.nextInt(-10,41).toString(),"notificationCount" to Random.nextInt(0,11).toString()).forEach{(name,value)->runCatching{doc.render.simulation=SimulationEditor.replace(doc.render.simulation,name,value)}}
    doc.saveRenderPreferences()
}

@Composable private fun PreviewSimulationControls(doc:DocumentController) {
    val sections=remember { previewSections }
    sections.forEach { section->WmCollapsible(section.title) { section.fields.forEach { field->SimulationField(doc,field) } } }
    WmCollapsible("加速度与陀螺仪") {
        TripleAxisControl(doc,"acceleration",listOf("加速度 X","加速度 Y","加速度 Z"),-20f..20f)
        TripleAxisControl(doc,"gyro",listOf("陀螺仪 X","陀螺仪 Y","陀螺仪 Z"),-20f..20f)
    }
    WmCollapsible("世界地图选点") { WorldLocationPicker(doc) }
    WmParamCard("秒表与重置") {
        Text("已用时间：${formatStopwatch(doc.render.simulation.stopwatchMs)}",style=MaterialTheme.typography.bodyMedium)
        Button({changeSimulation(doc,"stopwatchRunning","false");changeSimulation(doc,"stopwatchMs","0")},Modifier.fillMaxWidth()){Text("停止并重置秒表")}
        OutlinedButton({doc.render.simulation=WmTagsData.WmPreviewState();doc.render.resetTimeMachineToNow();doc.render.playing=true;doc.render.isBright=true;doc.saveRenderPreferences()},Modifier.fillMaxWidth()){Text("重置全部模拟状态")}
    }
    WmCollapsible("高级模拟字段") { SimulationFields(doc,true) }
    if(doc.view.project.themeSets.isNotEmpty()) WmParamCard("表盘主题") { ThemePreviewControl(doc) }
}

@Composable private fun SimulationField(doc:DocumentController,field:SimField) {
    val value=SimulationEditor.values(doc.render.simulation)[field.name].orEmpty()
    fun change(raw:String){try{doc.render.simulation=SimulationEditor.replace(doc.render.simulation,field.name,raw);doc.saveRenderPreferences()}catch(e:Exception){doc.error="${field.label}：${e.message}"}}
    Column(Modifier.fillMaxWidth().testTag("preview-sim-${field.name}")) { when(field.kind) {
        SimKind.INT -> WmNumericControl(field.label,value,doc,field.min..field.max,onPreview={change(it.toInt().toString())},onFinish={_,done->done(true)},onCommit={change(it.toFloatOrNull()?.toInt()?.toString()?:it)})
        SimKind.FLOAT -> WmNumericControl(field.label,value,doc,field.min..field.max,onPreview={change(it.toString())},onFinish={_,done->done(true)},onCommit=::change)
        SimKind.SWITCH -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(field.label,Modifier.weight(1f));Switch(value=="true",{change(it.toString())})}
        SimKind.CODE_SWITCH -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(field.label,Modifier.weight(1f));Switch(value==field.choices[1].first,{change(if(it)field.choices[1].first else field.choices[0].first)})}
        SimKind.CHOICE -> Column{Text(field.label,style=MaterialTheme.typography.bodySmall);ChoiceControl(field.label,field.choices,value,::change)}
        SimKind.TIME -> TimeSimulationField(field.label,value,::change)
        SimKind.TEXT -> FieldRow(field.label,value,doc,onCommit=::change)
    } }
}

private fun changeSimulation(doc:DocumentController,name:String,value:String){runCatching{doc.render.simulation=SimulationEditor.replace(doc.render.simulation,name,value);doc.saveRenderPreferences()}.onFailure{doc.error=it.message}}

@Composable private fun TimeSimulationField(label:String,value:String,onChange:(String)->Unit) {
    val parts=value.split(':');val hour=parts.getOrNull(0)?.padStart(2,'0')?:"00";val minute=parts.getOrNull(1)?.padStart(2,'0')?:"00"
    Column{Text(label,style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ChoiceControl("时",(0..23).map{"%02d".format(it) to "%02d 时".format(it)},hour,{onChange("$it:$minute")},Modifier.weight(1f));ChoiceControl("分",(0..59).map{"%02d".format(it) to "%02d 分".format(it)},minute,{onChange("$hour:$it")},Modifier.weight(1f))}}
}

@Composable private fun TripleAxisControl(doc:DocumentController,name:String,labels:List<String>,range:ClosedFloatingPointRange<Float>) {
    val raw=SimulationEditor.values(doc.render.simulation)[name].orEmpty();val values=raw.split(',').mapNotNull{it.trim().toFloatOrNull()}.let{if(it.size==3)it else listOf(0f,0f,0f)}
    fun change(index:Int,value:Float){val next=values.toMutableList();next[index]=value;changeSimulation(doc,name,next.joinToString(","))}
    labels.forEachIndexed{index,label->Box(Modifier.testTag("preview-sim-$name-$index")){WmNumericControl(label,values[index].toString(),doc,range,onPreview={change(index,it)},onFinish={_,done->done(true)},onCommit={it.toFloatOrNull()?.let{v->change(index,v)}})}}
}

@Composable private fun WorldLocationPicker(doc:DocumentController) {
    val state=doc.render.simulation
    Text("在世界地图上选点会同时改变纬度和经度。桌面版提供常用地点快捷点与精确坐标滑杆。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    listOf("伦敦" to (51.5074 to -0.1278),"北京" to (39.9042 to 116.4074),"东京" to (35.6762 to 139.6503),"纽约" to (40.7128 to -74.0060),"悉尼" to (-33.8688 to 151.2093)).forEach { (label,point)->TextButton({changeSimulation(doc,"latitude",point.first.toString());changeSimulation(doc,"longitude",point.second.toString())}){Text(label)} }
    WmNumericControl("纬度",state.latitude.toString(),doc,-90f..90f,onPreview={changeSimulation(doc,"latitude",it.toString())},onFinish={_,done->done(true)},onCommit={changeSimulation(doc,"latitude",it)})
    WmNumericControl("经度",state.longitude.toString(),doc,-180f..180f,onPreview={changeSimulation(doc,"longitude",it.toString())},onFinish={_,done->done(true)},onCommit={changeSimulation(doc,"longitude",it)})
}

private fun formatStopwatch(ms:Long):String { val cs=ms/10;val h=cs/360000;val m=(cs/6000)%60;val s=(cs/100)%60;return "%02d:%02d:%02d.%02d".format(h,m,s,cs%100) }

private val weatherConditions=listOf("Clear","Partly Cloudy","Cloudy","Overcast","Showers","Rain","Thunderstorm","Snow","Fog","Light Rain","Heavy Rain","Light Snow","Heavy Snow","Hail","Windy").map{it to it}
private val weatherIcons=listOf("01","02","03","04","09","10","11","13","50").flatMap{code->listOf("${code}d" to "${code}d（日间）","${code}n" to "${code}n（夜间）")}
private val previewSections=listOf(
    SimSection("电量与充电",listOf(ss("batteryCharging","手表正在充电"),si("batteryPercentage","手表电量 %",0f,100f),sf("batteryVoltage","电压 V",3f,4.5f),si("batteryCurrentMa","电流 mA",-2000f,2000f),si("batteryTempC","手表电池温度 °C",-20f,60f),si("phoneBatteryPercentage","手机电量 %",0f,100f),ss("phoneCharging","手机正在充电"),si("phoneBatteryTempC","手机电池温度 °C",-20f,60f))),
    SimSection("健康与活动",listOf(si("heartRate","心率",40f,200f),si("maxHeartRate","最大心率",80f,220f),si("stressScore","压力",0f,100f),si("steps","步数",0f,20000f),si("stepGoal","步数目标",1000f,30000f),si("calories","卡路里",0f,1500f),si("calorieGoal","卡路里目标",0f,3000f),sf("distanceKm","距离 km",0f,30f),sf("distanceGoalKm","距离目标 km",.5f,50f),cs("distanceUnit","使用英里","KM" to "公里","MI" to "英里"),si("bloodOxygen","血氧 %",80f,100f),si("flightsClimbed","爬楼层数",0f,100f),si("foodCalories","饮食卡路里",0f,3000f),si("foodCalorieGoal","饮食目标",0f,4000f),si("exerciseMinutes","运动分钟",0f,300f),si("exerciseGoalMinutes","运动目标分钟",0f,600f),si("standHours","站立小时",0f,24f),si("standGoal","站立目标",1f,24f))),
    SimSection("睡眠",listOf(ss("sleepHasData","有睡眠数据"),si("sleepAsleepMin","睡眠分钟",0f,720f),si("sleepRemMin","REM 分钟",0f,360f),si("sleepLightMin","浅睡分钟",0f,600f),si("sleepDeepMin","深睡分钟",0f,360f),si("sleepAwakeMin","清醒分钟",0f,180f),tm("sleepBedtime","入睡时间"),tm("sleepWakeTime","起床时间"))),
    SimSection("天气",listOf(si("temp","当前温度 °C",-30f,50f),si("tempHigh","最高温",-30f,50f),si("tempLow","最低温",-40f,45f),cs("tempUnit","使用华氏温度","C" to "摄氏","F" to "华氏"),si("humidity","湿度 %",0f,100f),si("cloudCover","云量 %",0f,100f),si("windSpeed","风速",0f,60f),si("windDir","风向 °",0f,359f),si("pressure","气压 hPa",950f,1050f),si("precipitation","降水",0f,100f),si("moonPhase","月相",0f,9f),SimField("conditionText","天气状况",SimKind.CHOICE,choices=weatherConditions),SimField("conditionIcon","天气图标",SimKind.CHOICE,choices=weatherIcons),st("weatherLocation","地点"),tm("sunrise","日出"),tm("sunset","日落"))),
    SimSection("传感器与 GPS",listOf(si("compassHeading","指南针 °",0f,359f),sf("latitude","纬度",-90f,90f),sf("longitude","经度",-180f,180f),si("altitudeM","海拔 m",-500f,9000f))),
    SimSection("连接",listOf(ss("wifiConnected","Wi-Fi 已连接"),si("wifiSignalBars","Wi-Fi 信号格",0f,4f),st("wifiIp","Wi-Fi IP"),ss("cellularConnected","蜂窝网络已连接"),si("cellularSignalBars","蜂窝信号格",0f,4f),ss("bluetoothEnabled","蓝牙已开启"),ss("gpsEnabled","GPS 已开启"))),
    SimSection("主题色",listOf(st("accentColor1","强调色 1"),st("accentColor2","强调色 2"),st("accentColor3","强调色 3"))),
    SimSection("设备模式与通知",listOf(si("notificationCount","通知数",0f,20f),st("notificationAppName","通知应用"),st("notificationTitle","通知标题"),st("notificationContent","通知内容"),si("volume","音量",0f,15f),si("screenBrightness","屏幕亮度",0f,255f),ss("is24hMode","24 小时制"),ss("darkMode","深色模式"),ss("lowPower","低电量模式"),cs("alarmTime","已设置闹钟","" to "关闭","07:30" to "开启"),ss("stopwatchRunning","秒表运行"),si("stopwatchMs","秒表毫秒",0f,86400000f))),
    SimSection("设备信息",listOf(st("watchName","手表名"),st("deviceName","设备名"),st("deviceModel","型号"),st("deviceManufacturer","制造商"),st("osVersion","OS 版本"),st("langCode","语言代码"),st("langRegion","语言地区"),st("langFull","语言全名"),st("lastReboot","上次重启"),ss("isRound","圆屏数据标签"),ss("hasCutout","缺口 / 平底屏标签"),ss("isDimLoBit","低位深息屏标签"))),
    SimSection("内存与存储",listOf(sf("usedRamGb","已用 RAM GB",0f,32f),sf("totalRamGb","总 RAM GB",1f,32f),sf("usedStorageGb","已用存储 GB",0f,1024f),sf("totalStorageGb","总存储 GB",8f,1024f))),
)
