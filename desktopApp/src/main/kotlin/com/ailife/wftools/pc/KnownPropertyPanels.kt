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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailife.wftools.pc.platform.chooseAssets
import com.ailife.wftools2.ui.editor.utils.rememberPreviewBitmap
import java.awt.MouseInfo
import java.awt.Robot
import kotlin.math.roundToInt

private enum class KnownControl { TEXT, NUMBER, COLOR, CHOICE, SWITCH, PATH }
private data class KnownField(
    val key: String, val label: String, val control: KnownControl = KnownControl.TEXT,
    val default: String = "", val range: ClosedFloatingPointRange<Float> = 0f..100f,
    val choices: List<Pair<String,String>> = emptyList(), val multiline: Boolean = false,
)
private data class KnownSectionSpec(val component: String, val title: String, val types: Set<String>, val fields: List<KnownField>)

private fun n(key:String,label:String,default:String,range:ClosedFloatingPointRange<Float>) = KnownField(key,label,KnownControl.NUMBER,default,range)
private fun c(key:String,label:String) = KnownField(key,label,KnownControl.COLOR)
private fun t(key:String,label:String,multiline:Boolean=false) = KnownField(key,label,KnownControl.TEXT,multiline=multiline)
private fun d(key:String,label:String,default:String,vararg options:Pair<String,String>) = KnownField(key,label,KnownControl.CHOICE,default=default,choices=options.toList())
private fun sw(key:String,label:String,default:String="n") = KnownField(key,label,KnownControl.SWITCH,default)
private fun p(key:String,label:String) = KnownField(key,label,KnownControl.PATH)
private val recentKnownColors = mutableStateListOf<String>()

private val transformChoices = arrayOf("n" to "不转换", "uc" to "大写", "lc" to "小写")
private val cornerChoices = arrayOf("0" to "全直角","1" to "四角", "2" to "除右下", "3" to "除左下", "4" to "除右上", "5" to "除左上", "6" to "上边", "7" to "下边", "8" to "左边", "9" to "右边", "10" to "左上 + 右下", "11" to "右上 + 左下", "12" to "仅左上", "13" to "仅右上", "14" to "仅左下", "15" to "仅右下", "16" to "全直角")
private val outlineChoices = arrayOf("" to "关闭", "Outline" to "描边", "Glow" to "发光")
private val yesNo = arrayOf("N" to "否", "Y" to "是")

private val knownSections = listOf(
    KnownSectionSpec("WmMarkersParams","刻度",setOf("markers"),listOf(c("color","刻度颜色"),d("shape","刻度形状","Square","Square" to "方形","Circle" to "圆形","Triangle" to "三角形"),n("radius","半径","120",0f..256f),n("m_count","数量","12",1f..120f),n("m_width","宽度","6",0f..100f),n("m_height","高度","20",0f..100f),n("squarify","方圆比例","100",0f..100f))),
    KnownSectionSpec("WmMarkersHmParams","时分刻度",setOf("markers_hm"),listOf(c("color","小时刻度颜色"),c("color2","分钟刻度颜色"),n("radius","半径","120",0f..256f),d("m_hour","小时刻度","Large","None" to "无","Tiny" to "极小","Small" to "小","Medium" to "中","Large" to "大"),d("m_minute","分钟刻度","Small","None" to "无","Tiny" to "极小","Small" to "小","Medium" to "中","Large" to "大"),n("squarify","方圆比例","100",0f..100f))),
    KnownSectionSpec("WmTextRingParams","文字环",setOf("text_ring"),listOf(d("ring_type","内容","1-12","1-12" to "1–12","0-11" to "0–11","0-59" to "0–59","I-XII" to "I–XII","Mon-Sun" to "Mon–Sun","Mo-Su" to "Mo–Su","Jan-Dec" to "Jan–Dec"),c("color","颜色"),t("font","字体 / 表达式"),n("text_size","字号","32",8f..200f),n("radius","半径","120",0f..256f),d("transform","大小写","n",*transformChoices),d("rotated_text","文字方向","n","n" to "直立","y" to "沿圆环旋转"),n("show_every","显示间隔","1",1f..12f),n("angle_start","起始角","0",0f..360f),n("angle_end","终止角","360",0f..360f),n("squarify","方圆比例","0",0f..100f),t("hide_text","隐藏文字（逗号分隔）"))),
    KnownSectionSpec("WmSeriesParams","序列",setOf("series"),listOf(d("series","序列格式","dh24z","dmz" to "分钟补零","dm" to "分钟","dhz" to "小时补零","dh" to "小时","dh24z" to "24小时补零","dh24" to "24小时","ddw" to "星期","dd_ddw" to "日期 + 星期","dnnn" to "月份"),d("orientation","排列方向","v","v" to "垂直","h" to "水平"),d("current_pos","当前项位置","m","m" to "中间","f" to "首项","l" to "末项"),n("spacing","间距","10",0f..100f),c("color","当前项颜色"),n("text_size","当前项字号","40",8f..200f),t("font","当前项字体"),c("color2","其他项颜色"),n("text_size2","其他项字号","24",8f..200f),t("font2","其他项字体"),d("transform","大小写","n",*transformChoices))),
    KnownSectionSpec("WmCalendarParams","日历",setOf("calendar"),listOf(c("color","星期标题颜色"),c("color2","日期 / 当天颜色"),t("font","日期字体"),t("font2","标题字体"),n("text_size","字号","14",6f..60f),n("margin_x","水平间距","8",0f..40f),n("margin_y","垂直间距","8",0f..40f),n("outside_opacity","非当月透明度","60",0f..100f),d("dow_start","每周起始日","1","1" to "星期一","0" to "星期日"),d("no_letters_header","星期标题长度","1","0" to "不显示","1" to "1 个字母","2" to "2 个字母","3" to "3 个字母"),d("transform","大小写","n",*transformChoices))),
    KnownSectionSpec("WmChartParams","图表",setOf("chart"),listOf(d("chart_type","图表类型","line","line" to "折线图","bar" to "柱状图"),t("data_points","数据点（分号分隔）",true),t("min_max","最大值 to 最小值"),t("chart_group","同步分组 ID"),c("color3","折线颜色"),c("color4","标记颜色"),n("line_width","线宽","10",0f..50f),n("marker_size","标记大小","10",0f..50f),n("tension","曲线张力","0",0f..100f),n("bar_thickness","柱宽","50",1f..100f),n("radius","柱角半径","0",0f..50f),d("corner_type","圆角位置","1",*cornerChoices),c("color","顶部颜色"),c("color2","底部颜色"),n("top_opacity","顶部透明度","100",0f..100f),n("bottom_opacity","底部透明度","0",0f..100f),t("lines","参考线语法",true),t("labels","标签语法",true))),
    KnownSectionSpec("WmVideoParams","视频",setOf("video"),listOf(p("path","视频资源"),c("color","染色"))),
    KnownSectionSpec("WmMapParams","地图",setOf("map"),listOf(t("lat","纬度 / 表达式"),t("lon","经度 / 表达式"),n("map_scale","地图比例","100",50f..200f),n("map_zoom","缩放级别","14",1f..20f))),
    KnownSectionSpec("WmGallery2dParams","二维图库",setOf("gallery_2d"),listOf(n("dur_on","每帧停留秒数","5",1f..60f))),
    KnownSectionSpec("WmImageParams","图片",setOf("image"),listOf(p("path","图片资源"),c("color","染色（FFFFFF 为无染色）"))),
    KnownSectionSpec("WmImageGifParams","GIF 帧",setOf("image_gif"),listOf(p("path","帧序列（反引号分隔）"),n("gif_delay","帧延迟 ms","50",10f..2000f))),
    KnownSectionSpec("WmImageCondParams","条件图片",setOf("image_cond"),listOf(p("path","精灵图资源"),t("cond_grid","网格（列x行）"),t("cond_value","帧索引表达式"),c("color","染色"),d("weather_night","昼夜变体","N","N" to "仅日间","Y" to "使用夜间变体"))),
    KnownSectionSpec("WmShapeParams","形状",setOf("shape","rounded"),listOf(d("shape","形状","Square","Square" to "方形","Circle" to "圆形","Triangle" to "三角形","Pentagon" to "五边形","Hexagon" to "六边形","Star" to "星形","Heart" to "心形"),c("color","填充颜色"),n("radius","圆角半径","0",0f..500f),d("corner_type","圆角位置","1",*cornerChoices),d("outline","轮廓效果","",*outlineChoices))),
    KnownSectionSpec("WmProgressParams","进度条",setOf("progress"),listOf(n("pct_complete","完成百分比","50",0f..100f),c("color","前景颜色"),c("color2","渐变终点"),c("color3","轨道背景"),c("color4","间隙颜色"),n("margin","边距","0",0f..50f),n("radius","圆角半径","50",0f..100f),d("corner_type","圆角位置","1",*cornerChoices),d("end_style","端点样式","round","round" to "圆头","flat" to "平头"),n("angle","方向角","90",0f..360f),n("outside_opacity","外部透明度","100",0f..100f))),
    KnownSectionSpec("WmRingParams","圆环",setOf("ring"),listOf(c("color","已填充颜色"),c("color2","未填充颜色"),c("color3","弧外颜色"),n("radius_outer","外半径","100",0f..500f),n("radius_inner","内半径","80",0f..500f),n("radius","端帽圆角","10",0f..250f),n("angle","起始方向","270",0f..360f),n("angle_total","弧跨度","360",0f..360f),n("outside_opacity","外部透明度","100",0f..100f),d("is_clockwise","方向","Y","Y" to "顺时针","N" to "逆时针"))),
    KnownSectionSpec("WmOutlineParams","描边与发光",setOf("text","text_curved","shape","rounded"),listOf(d("outline","轮廓效果","",*outlineChoices),c("o_color","轮廓颜色"),n("o_size","轮廓大小","2",0f..40f),n("o_opacity","轮廓透明度","100",0f..100f))),
    KnownSectionSpec("WmTextParams","文字",setOf("text","text_curved"),listOf(t("text","文字 / 表达式",true),t("font","字体"),n("text_size","字号","24",8f..300f),c("color","亮屏颜色"),c("color_dim","息屏颜色"),d("transform","大小写","n",*transformChoices),sw("stencil","镂空"),d("outline","轮廓效果","",*outlineChoices))),
    KnownSectionSpec("WmTextCurvedParams","曲线文字",setOf("text_curved"),listOf(n("radius","曲线半径","200",0f..500f),d("curve_dir","弯曲方向","Up","Up" to "向上","Down" to "向下"))),
    KnownSectionSpec("WmTextShadowParams","阴影",setOf("text","text_curved","shape","rounded"),listOf(d("shadow","阴影类型","","" to "关闭","Drop" to "投影","Flat" to "长阴影"),c("w_color","阴影颜色"),n("w_distance","阴影距离","0",0f..200f),n("w_angle","阴影角度","135",0f..360f),n("w_opacity","阴影透明度","100",0f..100f))),
    KnownSectionSpec("WmTextAnimParams","文字动画",setOf("text","text_curved"),listOf(t("anim_in","进入动画"),t("anim_out","退出动画"),t("dur_in","进入时长（秒）"),t("dur_on","显示时长（0 为持续）"),t("dur_out","退出时长（秒）"),t("dur_off","隐藏时长（秒）"),t("delay_start","初始延迟（秒）"),t("repeat_count","重复次数（-1 无限）"),d("restart_on_load","加载时重启","N",*yesNo),d("restart_on_bright","亮屏时重启","N",*yesNo),d("restart_text_change","文字变化时重启","N",*yesNo))),
)

private val shaderChoices = listOf("" to "关闭","GradientLinear" to "线性渐变","GradientRadial" to "径向渐变","Rainbow" to "彩虹","RGB" to "RGB 偏移","HSV" to "HSV 偏移","Segment" to "扇区","SegmentBetween" to "角度区间","Radar" to "雷达","Progress" to "进度","ProgressBetween" to "进度区间")
private data class ShaderParam(val label:String,val default:String,val range:ClosedFloatingPointRange<Float>?=null,val color:Boolean=false)
private val shaderParams = mapOf(
    "GradientLinear" to listOf(ShaderParam("起始色","FFFFFF",color=true),ShaderParam("终止色","000000",color=true),ShaderParam("渐变角","0",0f..360f),ShaderParam("渐变比例","100",0f..200f)),
    "GradientRadial" to listOf(ShaderParam("中心色","FFFFFF",color=true),ShaderParam("边缘色","000000",color=true),ShaderParam("半径比例","100",0f..200f)),
    "Rainbow" to listOf(ShaderParam("色相旋转","0",0f..360f)), "RGB" to listOf(ShaderParam("红偏移","0",-100f..100f),ShaderParam("绿偏移","0",-100f..100f),ShaderParam("蓝偏移","0",-100f..100f)),
    "HSV" to listOf(ShaderParam("色相偏移","0",-100f..100f),ShaderParam("饱和度偏移","0",-100f..100f),ShaderParam("明度偏移","0",-100f..100f)),
    "Segment" to listOf(ShaderParam("弧跨度","90",0f..360f),ShaderParam("内部透明度","100",0f..100f),ShaderParam("外部透明度","20",0f..100f)),
    "SegmentBetween" to listOf(ShaderParam("起始角","0",0f..360f),ShaderParam("终止角","180",0f..360f),ShaderParam("内部透明度","100",0f..100f),ShaderParam("外部透明度","10",0f..100f)),
    "Radar" to listOf(ShaderParam("扫描前缘角","0",0f..360f),ShaderParam("前缘透明度","100",0f..100f),ShaderParam("尾部透明度","15",0f..100f)),
    "Progress" to listOf(ShaderParam("填充百分比","50",0f..100f),ShaderParam("方向角","0",0f..360f),ShaderParam("已填透明度","100",0f..100f),ShaderParam("未填透明度","20",0f..100f)),
    "ProgressBetween" to listOf(ShaderParam("起始百分比","0",0f..100f),ShaderParam("终止百分比","50",0f..100f),ShaderParam("方向角","0",0f..360f),ShaderParam("已填透明度","100",0f..100f),ShaderParam("未填透明度","15",0f..100f)),
)

@Composable private fun KnownSection(spec: KnownSectionSpec, content:@Composable ColumnScope.()->Unit) {
    var expanded by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(38.dp).testTag("known-section-${spec.component}").clickable { expanded = !expanded },verticalAlignment=Alignment.CenterVertically) {
            Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null,Modifier.size(20.dp))
            Text(spec.title,Modifier.padding(start=6.dp),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleSmall)
        }
        if(expanded) Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(7.dp),content=content)
        HorizontalDivider(thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
    }
}

@Composable fun KnownPropertiesPanel(doc: DocumentController, drops: DropRegistry, modifier: Modifier, wideEditor: Boolean = false) {
    val selected = doc.view.layers.filter { it.id in doc.view.selection.selected }
    val ids = selected.map { it.id }.toSet()
    var expression by remember { mutableStateOf<Pair<String,String>?>(null) }
    var colorEditor by remember { mutableStateOf<Triple<String,String,String>?>(null) }
    var actionEditor by remember { mutableStateOf(false) }
    DisposableEffect(ids) { onDispose { drops.fields.clear() } }
    fun value(key:String):String = selected.map { it.attrs[key].orEmpty() }.distinct().singleOrNull().orEmpty()
    fun edit(key:String,text:String) = doc.command { setAttribute(ids,key,text) }
    fun editEffect(key:String,text:String) {
        if(key=="shadow"&&text.isNotBlank()) doc.command { setLayerAttributes(selected.associate { layer->layer.id to mapOf("shadow" to text,"w_color" to layer.attrs["w_color"].orEmpty().ifBlank{"FFFFFF"},"w_distance" to layer.attrs["w_distance"].orEmpty().ifBlank{"10"},"w_angle" to layer.attrs["w_angle"].orEmpty().ifBlank{"135"},"w_opacity" to layer.attrs["w_opacity"].orEmpty().ifBlank{"75"}) }) }
        else if(key=="outline"&&text.isNotBlank()) doc.command { setLayerAttributes(selected.associate { layer->layer.id to mapOf("outline" to text,"o_color" to layer.attrs["o_color"].orEmpty().ifBlank{"FFFFFF"},"o_size" to layer.attrs["o_size"].orEmpty().ifBlank{if(text=="Glow")"8" else "2"},"o_opacity" to layer.attrs["o_opacity"].orEmpty().ifBlank{"100"}) }) }
        else edit(key,text)
    }
    fun step(key:String,delta:Float) = doc.command { setLayerAttributes(layers.filter{it.id in ids}.associate { layer->layer.id to mapOf(key to offsetEditorExpression(layer.attrs[key].orEmpty(),delta)) }) }
    Box(modifier) {
        ScrollableInspector(Modifier.fillMaxSize(),12.dp,10.dp) {
        if(selected.isEmpty()) { SectionTitle("图层属性"); Text("在图层列表或预览中选择图层。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant); return@ScrollableInspector }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            LayerThumbnail(doc,selected.first(),40.dp)
            Column { Text(if(selected.size==1) selected.first().attrs["name"].orEmpty().ifBlank { "图层 ${doc.view.layers.indexOfFirst { layer -> layer.id == selected.first().id } + 1}" } else "${selected.size} 个图层",fontWeight=FontWeight.Bold); Text(selected.map { it.type }.distinct().joinToString(),fontSize=12.sp,color=MaterialTheme.colorScheme.secondary) }
        }
        WmParamCard("基础与变换") {
            listOf(n("x","X 位置","0",-256f..256f),n("y","Y 位置","0",-256f..256f),n("width","宽度","100",0f..512f),n("height","高度","100",0f..512f),n("rotation","旋转 °","0",0f..360f),n("opacity","不透明度 %","100",0f..100f),n("anim_scale_x","水平缩放 %","100",0f..1000f),n("anim_scale_y","垂直缩放 %","100",0f..1000f),n("skew_x","水平倾斜 °","0",-90f..90f),n("skew_y","垂直倾斜 °","0",-90f..90f),n("gyro","陀螺响应","0",-20f..20f)).forEach { f ->
                if(f.key !in setOf("width","height") || selected.any { it.type !in setOf("text","text_curved","text_ring","markers","markers_hm","ring","series","calendar") }) {
                    if(selected.size>1&&f.key in setOf("x","y","rotation")) RelativeNumericField(f,doc,ids,::step)
                    else KnownLayerField(f,value(f.key),doc,ids,{ expression=f.key to value(f.key) },{ key,label,initial -> colorEditor=Triple(key,label,initial) },::edit,::step,drops,wideEditor)
                }
            }
            Text("显示模式",style=MaterialTheme.typography.bodySmall)
            ChoiceControl("显示模式",listOf("bd" to "亮屏与息屏","b" to "仅亮屏","d" to "仅息屏","n" to "隐藏"),value("display").ifBlank{"bd"},{edit("display",it)},Modifier.testTag("known-layer-field-display"),!doc.editingBlocked)
            Text("对齐原点",style=MaterialTheme.typography.bodySmall); Box(Modifier.testTag("known-layer-field-alignment")){WmAlignmentControl(value("alignment"),!doc.editingBlocked){edit("alignment",it)}}
            Text("点击动作",style=MaterialTheme.typography.bodySmall)
            ChoiceControl("点击动作",tapActions,value("tap_action"),{edit("tap_action",it)},Modifier.testTag("known-layer-field-tap_action"),!doc.editingBlocked)
            TextButton({actionEditor=true},enabled=!doc.editingBlocked,modifier=Modifier.testTag("edit-tap-action")){Text("编辑点击动作…")}
            FieldRow("自定义动作",value("tap_action"),doc,!doc.editingBlocked,fieldModifier=Modifier.testTag("known-layer-field-tap_action-custom")){edit("tap_action",it)}
        }
        knownSections.filter { section -> selected.all { it.type in section.types } }.forEach { spec -> KnownSection(spec) {
            spec.fields.forEach { field -> KnownLayerField(field,value(field.key),doc,ids,{ expression=field.key to value(field.key) },{ key,label,initial -> colorEditor=Triple(key,label,initial) },::editEffect,::step,drops,wideEditor) }
            if(spec.component=="WmImageGifParams") GifFrameTools(value("path"),doc,!doc.editingBlocked){edit("path",it)}
            if(spec.component in setOf("WmImageParams","WmImageCondParams")) ProjectImageChooser(doc,value("path"),!doc.editingBlocked){edit("path",it)}
            if(spec.component=="WmImageCondParams") ConditionalGrid(value("cond_grid")){edit("cond_grid",it)}
        } }
        if(selected.all { it.type in setOf("image","image_gif","image_cond","text","text_curved","shape","rounded","video","gallery_2d") }) ShaderSection(value("shader"),value={value(it)},doc=doc,ids=ids,onExpression={key->expression=key to value(key)},onColor={ key,label,initial -> colorEditor=Triple(key,label,initial) },edit=::edit,step=::step,drops=drops,wideEditor=wideEditor)
        WmCollapsible("高级 · 原始属性与表达式") { selected.flatMap { it.attrs.keys }.distinct().sorted().forEach { key -> FieldRow(key,value(key),doc,!doc.editingBlocked){edit(key,it)} } }
    }
    }
    if (wideEditor) expression?.let { (key,initial) -> Surface(Modifier.fillMaxSize().testTag("expression-side-panel"), color=MaterialTheme.colorScheme.surface, tonalElevation=3.dp) { ExpressionEditorContent(key,initial,doc,{expression=null}) { edit(key,it); expression=null } } }
    if (wideEditor) colorEditor?.let { (key,label,initial) -> Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.surface, tonalElevation=3.dp) { KnownColorSidePanel(label,initial,{colorEditor=null}) { edit(key,it); colorEditor=null } } }
    if (wideEditor && actionEditor) Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.surface, tonalElevation=3.dp) { ActionSidePanel(value("tap_action"),{actionEditor=false}) { edit("tap_action",it);actionEditor=false } }
    if (!wideEditor) expression?.let { (key,initial) -> ExpressionDialog(key,initial,doc,{expression=null}) { edit(key,it); expression=null } }
    if (!wideEditor && actionEditor) ActionEditorDialog(value("tap_action"),{actionEditor=false}) { edit("tap_action",it);actionEditor=false }
}

@Composable private fun RelativeNumericField(field:KnownField,doc:DocumentController,ids:Set<String>,offset:(String,Float)->Unit) {
    var reset by remember{mutableIntStateOf(0)}
    val range=if(field.key=="rotation")-180f..180f else -250f..250f
    key(reset) { WmNumericControl(field.label,"0",doc,range,Modifier.testTag("known-layer-field-${field.key}"),onStep={offset(field.key,it)},onPreview={doc.previewNumeric(ids,field.key,it,"0",relative=true)},onFinish={_,done->doc.commitNumeric{success->done(success);reset++}},onCommit={it.toFloatOrNull()?.takeIf{delta->delta!=0f}?.let{delta->offset(field.key,delta)}}) }
    Text("多选相对增量 · 每个图层保留自己的原值或表达式",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

private val tapActions = listOf("" to "未设置","tap_launcher" to "打开应用列表","widget_weather" to "天气","widget_health" to "健康","widget_calendar" to "日历","w_agenda" to "日程","w_fit" to "健身","w_heart_rate" to "心率","w_compass" to "指南针","w_keep" to "Keep","m_email" to "手机邮件","m_calendar" to "手机日历","voice_search" to "语音搜索","w_contacts" to "联系人","w_sports" to "体育","w_gallery" to "图库","w_messaging" to "消息","w_camera" to "相机","w_dialer" to "拨号","w_music" to "音乐","w_clock" to "时钟","w_calculator" to "计算器","w_payment" to "支付","w_map" to "地图","browser" to "浏览器","flashlight" to "手电筒","socialmedia" to "社交媒体","open_settings" to "系统设置","battery_status" to "电池状态","media_play_pause" to "播放 / 暂停","media_play" to "播放","media_pause" to "暂停","media_next" to "下一首","media_prev" to "上一首","vol_up" to "增大音量","vol_down" to "减小音量","color_switch_next" to "下一主题","color_switch_prev" to "上一主题","color_switch_select" to "选择主题","sw_start_stop" to "秒表开始 / 暂停","sw_reset" to "秒表重置")

@Composable private fun KnownLayerField(field:KnownField,raw:String,doc:DocumentController,ids:Set<String>,openExpression:()->Unit,openColor:(String,String,String)->Unit,edit:(String,String)->Unit,step:(String,Float)->Unit,drops:DropRegistry,wideEditor:Boolean) {
    val shown=raw.ifBlank{field.default}
    when(field.control) {
        KnownControl.NUMBER -> WmNumericControl(field.label,shown,doc,field.range,Modifier.testTag("known-layer-field-${field.key}"),onExpression=openExpression,onStep={step(field.key,it)},onPreview={doc.previewNumeric(ids,field.key,it,field.default.ifBlank{"0"})},onFinish={_,done->doc.commitNumeric(done)}){edit(field.key,it)}
        KnownControl.COLOR -> Box(Modifier.testTag("known-layer-field-${field.key}")){KnownColorControl(field.label,shown.ifBlank{"FFFFFF"},doc,openExpression,wideEditor,{openColor(field.key,field.label,shown.ifBlank{"FFFFFF"})}){edit(field.key,it)}}
        KnownControl.CHOICE -> Column(Modifier.testTag("known-layer-field-${field.key}")){Text(field.label,style=MaterialTheme.typography.bodySmall);ChoiceControl(field.label,field.choices,shown,{edit(field.key,it)},enabled=!doc.editingBlocked)}
        KnownControl.SWITCH -> Row(Modifier.fillMaxWidth().testTag("known-layer-field-${field.key}"),verticalAlignment=Alignment.CenterVertically){Text(field.label,Modifier.weight(1f));Switch(shown.equals("y",true)||shown.equals("true",true),{edit(field.key,if(it)"y" else "n")},enabled=!doc.editingBlocked)}
        KnownControl.PATH -> Row(Modifier.fillMaxWidth().testTag("known-layer-field-${field.key}").onGloballyPositioned { drops.fields[field.key]=PropertyDrop(it.boundsInWindow().dropBounds(),field.key,ids) },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            DocumentValue(raw,field.label,doc,{edit(field.key,it)},Modifier.weight(1f)); WmIconButton(Icons.Default.FolderOpen,"替换${field.label}",!doc.editingBlocked){chooseAssets(doc.window).takeIf{it.isNotEmpty()}?.let { paths->doc.replaceExternalAssets(ids,paths,field.key) }}; FxButton(openExpression,!doc.editingBlocked)
        }
        KnownControl.TEXT -> Column(Modifier.testTag("known-layer-field-${field.key}")){
            if(field.key in setOf("font","font2")) {
                val fonts=doc.view.assets.filter{it.kind in setOf("fonts","fonts_bm")}.map{asset->val name=asset.relative.substringAfter('/').substringBeforeLast('.');(if(asset.kind=="fonts_bm")"bm:$name" else name) to name}.distinct()
                Text(field.label,style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){ChoiceControl(field.label,(listOf(raw to raw.ifBlank{"Roboto-Regular"})+fonts).distinctBy{it.first},raw,{edit(field.key,it)},Modifier.weight(1f),!doc.editingBlocked);WmIconButton(Icons.Default.FolderOpen,"导入字体",!doc.editingBlocked){chooseAssets(doc.window).takeIf{it.isNotEmpty()}?.let{paths->doc.replaceExternalAssets(ids,paths,field.key)}};FxButton(openExpression,!doc.editingBlocked)}
            } else {
                if (field.key == "text") FieldRow(field.label,raw,doc,!doc.editingBlocked,field.multiline,
                    onDraftChange={doc.previewLayerText(ids,it)},onCommit={doc.commitLayerText(ids,it)})
                else FieldRow(field.label,raw,doc,!doc.editingBlocked,field.multiline){edit(field.key,it)}
                TextButton(openExpression,enabled=!doc.editingBlocked,contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)){Text("fx · 编辑表达式",style=MaterialTheme.typography.labelSmall)}
            }
        }
    }
}

@Composable private fun ShaderSection(shader:String,value:(String)->String,doc:DocumentController,ids:Set<String>,onExpression:(String)->Unit,onColor:(String,String,String)->Unit,edit:(String,String)->Unit,step:(String,Float)->Unit,drops:DropRegistry,wideEditor:Boolean) {
    val spec=KnownSectionSpec("WmShaderParams","着色器",emptySet(),emptyList())
    KnownSection(spec) {
        Column(Modifier.testTag("known-layer-field-shader")){Text("着色模式",style=MaterialTheme.typography.bodySmall);ChoiceControl("着色模式",shaderChoices,shader,{next->
            val defaults=shaderParams[next].orEmpty().mapIndexed { index,param->"u_${index+1}" to param.default }.toMap()
            doc.command { setLayerAttributes(ids.associateWith { mapOf("shader" to next)+defaults }) }
        },enabled=!doc.editingBlocked)}
        (1..5).forEach { index ->
            val param=shaderParams[shader]?.getOrNull(index-1)
            if(param!=null) {
                val f=if(param.color)c("u_$index",param.label) else n("u_$index",param.label,param.default,param.range!!)
                KnownLayerField(f,value(f.key),doc,ids,{onExpression(f.key)},{ key,label,initial -> onColor(key,label,initial) },edit,step,drops,wideEditor)
            } else Box(Modifier.testTag("known-layer-field-u_$index")) { Text("参数 $index 不适用于当前模式",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun KnownColorControl(label:String,raw:String,doc:DocumentController,onExpression:()->Unit,wideEditor:Boolean,onOpen:()->Unit,onCommit:(String)->Unit) {
    var dialog by remember{mutableStateOf(false)}
    val clean=raw.removePrefix("#").takeLast(6).uppercase();val color=parseUiColor(clean)?:Color.White
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(color).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(6.dp)).clickable{if(wideEditor)onOpen()else dialog=true});DocumentValue(clean,label,doc,{text->text.removePrefix("#").takeIf{it.matches(Regex("[0-9A-Fa-f]{6}"))}?.uppercase()?.let(onCommit)},Modifier.weight(1f));WmIconButton(Icons.Default.Palette,"选择$label",!doc.editingBlocked,size=28.dp){if(wideEditor)onOpen()else dialog=true};FxButton(onExpression,!doc.editingBlocked)}
    if(dialog) KnownColorDialog(clean,{dialog=false}){next->recentKnownColors.remove(next);recentKnownColors.add(0,next);while(recentKnownColors.size>12)recentKnownColors.removeLast();onCommit(next);dialog=false}
}

@Composable private fun KnownColorDialog(initial:String,onDismiss:()->Unit,onSave:(String)->Unit) {
    var hex by remember{mutableStateOf(initial)};var page by remember{mutableStateOf("RGB")}
    val awt=runCatching{java.awt.Color.decode("#${hex.takeIf{it.matches(Regex("[0-9A-Fa-f]{6}"))}?:"FFFFFF"}")}.getOrDefault(java.awt.Color.WHITE)
    val hsb=java.awt.Color.RGBtoHSB(awt.red,awt.green,awt.blue,null)
    fun rgb(r:Int=awt.red,g:Int=awt.green,b:Int=awt.blue){hex="%02X%02X%02X".format(r.coerceIn(0,255),g.coerceIn(0,255),b.coerceIn(0,255))}
    fun hsv(h:Float=hsb[0],s:Float=hsb[1],v:Float=hsb[2]){val n=java.awt.Color.HSBtoRGB(h,s,v);hex="%06X".format(n and 0xFFFFFF)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("选择颜色")},text={Column(Modifier.width(360.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        WmSegmented(listOf("RGB" to "RGB","HSV" to "HSV","PRESET" to "预设"),page,{page=it})
        Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).background(parseUiColor(hex)?:Color.White,RoundedCornerShape(8.dp)));Spacer(Modifier.width(8.dp));CompactField(hex,{hex=it.uppercase().take(6)},Modifier.weight(1f),onDraftChange={hex=it.uppercase().take(6)})}
        if(page=="RGB")listOf("红" to awt.red,"绿" to awt.green,"蓝" to awt.blue).forEach{(name,value)->Row(verticalAlignment=Alignment.CenterVertically){Text(name,Modifier.width(28.dp));Slider(value/255f,{v->when(name){"红"->rgb(r=(v*255).roundToInt());"绿"->rgb(g=(v*255).roundToInt());else->rgb(b=(v*255).roundToInt())}},Modifier.weight(1f));Text(value.toString(),Modifier.width(34.dp))}}
        if(page=="HSV")listOf("色相" to hsb[0],"饱和" to hsb[1],"明度" to hsb[2]).forEach{(name,value)->Row(verticalAlignment=Alignment.CenterVertically){Text(name,Modifier.width(38.dp));Slider(value,{v->when(name){"色相"->hsv(h=v);"饱和"->hsv(s=v);else->hsv(v=v)}},Modifier.weight(1f))}}
        if(page=="PRESET"){val presets=listOf("FFFFFF","000000","F44336","FF9800","FFEB3B","4CAF50","00BCD4","2196F3","3F51B5","9C27B0")+recentKnownColors;presets.distinct().chunked(8).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{item->Box(Modifier.size(30.dp).clip(CircleShape).background(parseUiColor(item)?:Color.White).border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape).clickable{hex=item})}}};if(recentKnownColors.isNotEmpty())TextButton({recentKnownColors.clear()}){Text("清空最近颜色")}}
        OutlinedButton({runCatching{MouseInfo.getPointerInfo().location.let { point -> Robot().getPixelColor(point.x,point.y) }}.onSuccess{rgb(it.red,it.green,it.blue)}},Modifier.fillMaxWidth()){Icon(Icons.Default.Colorize,null);Spacer(Modifier.width(6.dp));Text("吸取鼠标位置颜色")}
    }},confirmButton={Button({onSave(hex)},enabled=hex.matches(Regex("[0-9A-Fa-f]{6}"))){Text("应用")}},dismissButton={TextButton(onDismiss){Text("取消")}})
}

@Composable private fun KnownColorSidePanel(label: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var hex by remember(initial) { mutableStateOf(initial.removePrefix("#").takeLast(6).uppercase()) }
    val color = parseUiColor(hex) ?: Color.White
    Surface(Modifier.fillMaxSize().testTag("color-side-panel"), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择$label", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onDismiss) { Text("取消") }
                Button({ onSave(hex) }, enabled = hex.matches(Regex("[0-9A-Fa-f]{6}"))) { Text("应用") }
            }
            Box(Modifier.fillMaxWidth().height(72.dp).background(color, RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
            Text("RGB 十六进制", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompactField(hex, { hex = it.removePrefix("#").take(6).uppercase() }, Modifier.fillMaxWidth(), onDraftChange = { hex = it.removePrefix("#").take(6).uppercase() })
            val awt = runCatching { java.awt.Color.decode("#${hex.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) } ?: "FFFFFF"}") }.getOrDefault(java.awt.Color.WHITE)
            listOf("红" to awt.red, "绿" to awt.green, "蓝" to awt.blue).forEach { (name, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.width(28.dp))
                    Slider(value / 255f, { next ->
                        val channel = (next * 255).roundToInt()
                        val rgb = when (name) { "红" -> java.awt.Color(channel, awt.green, awt.blue); "绿" -> java.awt.Color(awt.red, channel, awt.blue); else -> java.awt.Color(awt.red, awt.green, channel) }
                        hex = "%02X%02X%02X".format(rgb.red, rgb.green, rgb.blue)
                    }, Modifier.weight(1f))
                    Text(value.toString(), Modifier.width(34.dp))
                }
            }
            if (parseUiColor(hex) == null) Text("请输入六位 RGB 十六进制颜色。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ActionSidePanel(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var group by remember { mutableStateOf(quickActionGroups.keys.first()) }
    Surface(Modifier.fillMaxSize().testTag("action-side-panel"), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("编辑点击动作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onDismiss) { Text("取消") }
                Button({ onSave(text) }) { Text("保存") }
            }
            WmSegmented(quickActionGroups.keys.map { it to it }, group, { group = it })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                quickActionGroups[group].orEmpty().forEach { (value, label) -> TextButton({ text = value }) { Text(label) } }
            }
            CompactField(text, { text = it }, Modifier.fillMaxWidth(), multiline = true, onDraftChange = { text = it }, fieldHeight = 180.dp)
        }
    }
}

@Composable private fun GifFrameTools(raw:String,doc:DocumentController,enabled:Boolean,onChange:(String)->Unit) {
    val frames=raw.split('`').filter(String::isNotBlank)
    var preview by remember{mutableStateOf<String?>(null)}
    var choosing by remember{mutableStateOf(false)}
    Text("${frames.size} 帧",style=MaterialTheme.typography.labelMedium)
    frames.forEachIndexed { index,name -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        val asset=doc.view.assets.firstOrNull{it.kind=="images"&&it.relative.substringAfter('/')==name}
        val bitmap=rememberPreviewBitmap(asset?.path?.toString(),48,48)
        if(bitmap!=null) Image(bitmap,name,Modifier.size(42.dp).clickable{preview=name},contentScale=ContentScale.Crop)
        Spacer(Modifier.width(6.dp));Text(name,Modifier.weight(1f),maxLines=1)
        WmIconButton(Icons.Default.ArrowUpward,"上移",enabled&&index>0,size=26.dp){val list=frames.toMutableList();val item=list.removeAt(index);list.add(index-1,item);onChange(list.joinToString("`"))}
        WmIconButton(Icons.Default.ArrowDownward,"下移",enabled&&index<frames.lastIndex,size=26.dp){val list=frames.toMutableList();val item=list.removeAt(index);list.add(index+1,item);onChange(list.joinToString("`"))}
        WmIconButton(Icons.Default.Delete,"移除帧",enabled,size=26.dp){onChange(frames.filterIndexed{i,_->i!=index}.joinToString("`"))}
    } }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Box { TextButton({choosing=true},enabled=enabled){Text("添加项目图片")};DropdownMenu(choosing,{choosing=false}){doc.view.assets.filter{it.kind=="images"}.forEach{asset->val name=asset.relative.substringAfter('/');DropdownMenuItem({Text(name)},onClick={choosing=false;onChange((frames+name).joinToString("`"))})}} }
        TextButton({onChange("")},enabled=enabled&&frames.isNotEmpty()){Text("清空帧序列")}
    }
    Text("添加本地多帧需要核心导入接口返回最终避名后的资源名；当前可先从素材页导入，再在这里追加。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    preview?.let { name->val asset=doc.view.assets.firstOrNull{it.relative.substringAfter('/')==name};val bitmap=rememberPreviewBitmap(asset?.path?.toString(),512,512);AlertDialog(onDismissRequest={preview=null},title={Text(name)},text={if(bitmap!=null)Image(bitmap,name,Modifier.sizeIn(maxWidth=512.dp,maxHeight=512.dp),contentScale=ContentScale.Fit)},confirmButton={TextButton({preview=null}){Text("关闭")}}) }
}

@Composable private fun ProjectImageChooser(doc:DocumentController,current:String,enabled:Boolean,onChange:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    val images=doc.view.assets.filter{it.kind=="images"}
    TextButton({expanded=!expanded},enabled=enabled&&images.isNotEmpty()){Text(if(expanded)"收起项目图片" else "从项目图片中选择")}
    if(expanded) images.chunked(3).forEach { row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{asset->val name=asset.relative.substringAfter('/');val bitmap=rememberPreviewBitmap(asset.path.toString(),64,64);Surface(onClick={onChange(name)},enabled=enabled,modifier=Modifier.weight(1f).height(82.dp),color=if(name==current)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(horizontalAlignment=Alignment.CenterHorizontally){if(bitmap!=null)Image(bitmap,name,Modifier.size(58.dp),contentScale=ContentScale.Crop);Text(name,maxLines=1,style=MaterialTheme.typography.labelSmall)}}};repeat(3-row.size){Spacer(Modifier.weight(1f))}}}
}

@Composable private fun ConditionalGrid(raw:String,onChange:(String)->Unit) {
    val parts=raw.ifBlank{"1x1"}.split('x','X');val columns=parts.getOrNull(0).orEmpty().ifBlank{"1"};val rows=parts.getOrNull(1).orEmpty().ifBlank{"1"}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f)){Text("列数",style=MaterialTheme.typography.bodySmall);CompactField(columns,{onChange("${it}x$rows")},Modifier.fillMaxWidth())};Column(Modifier.weight(1f)){Text("行数",style=MaterialTheme.typography.bodySmall);CompactField(rows,{onChange("${columns}x$it")},Modifier.fillMaxWidth())}}
}

private val quickExpressionGroups = linkedMapOf(
    "时间" to listOf("{dh}" to "小时", "{dm}" to "分钟", "{ds}" to "秒", "{d24h}" to "24 小时"),
    "日期" to listOf("{Ddd}" to "星期", "{DMM}" to "月份", "{Dyyyy}" to "年份"),
    "电量" to listOf("{bl}" to "手表电量", "{blp}" to "电量百分比", "{bv}" to "电压"),
    "健康" to listOf("{steps}" to "步数", "{hr}" to "心率", "{cals}" to "卡路里"),
    "睡眠" to listOf("{slp}" to "睡眠百分比", "{slt}" to "睡眠时长"),
    "天气" to listOf("{wt}" to "当前温度", "{wth}" to "最高温度", "{wtl}" to "最低温度", "{wcond}" to "天气状况"),
    "传感器" to listOf("{accx}" to "加速度 X", "{gyrox}" to "陀螺 X"),
    "罗盘与 GPS" to listOf("{cdeg}" to "罗盘角度", "{lat}" to "纬度", "{lon}" to "经度"),
    "秒表" to listOf("{sw_h}" to "秒表小时", "{sw_m}" to "秒表分钟", "{sw_s}" to "秒表秒"),
    "网络与设备" to listOf("{pws}" to "Wi-Fi 信号", "{bt}" to "蓝牙", "{dn}" to "设备名称"),
)

@Composable private fun ExpressionEditorContent(key:String,initial:String,doc:DocumentController,onDismiss:()->Unit,onApply:(String)->Unit) {
    var text by remember(key,initial){mutableStateOf(initial)}
    var group by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { Text("编辑：$key 表达式",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton(onDismiss){Text("取消")};Button({onApply(text)}){Text("应用") } }
        Text("表达式 / 脚本",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        CompactField(text,{text=it},Modifier.fillMaxWidth(),multiline=true,onDraftChange={text=it},fieldHeight=190.dp)
        Text("预览：${doc.render.evaluate(text)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
        HorizontalDivider()
        Text("插入变量",style=MaterialTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            quickExpressionGroups.forEach { (name,tokens) ->
                val expanded=group==name
                Surface(onClick={group=if(expanded)null else name},modifier=Modifier.fillMaxWidth().testTag("expression-group-$name"),color=Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(name,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                        Icon(if(expanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null,Modifier.size(20.dp))
                    }
                }
                if(expanded) Column(Modifier.fillMaxWidth().padding(start=12.dp,bottom=6.dp)) {
                    tokens.forEach { (token,label) ->
                        TextButton(onClick={text+=token},modifier=Modifier.fillMaxWidth().testTag("expression-token-$token"),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text(label,Modifier.weight(1f));Text(token,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable private fun ExpressionDialog(key:String,initial:String,doc:DocumentController,onDismiss:()->Unit,onApply:(String)->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("编辑 $key 表达式")},text={ExpressionEditorContent(key,initial,doc,onDismiss,onApply)},confirmButton={},dismissButton={})
}

private val quickActionGroups = linkedMapOf(
    "应用" to listOf("tap_launcher" to "打开应用列表", "open_settings" to "系统设置", "browser" to "浏览器"),
    "设备" to listOf("battery_status" to "电池状态", "w_clock" to "时钟", "w_camera" to "相机"),
    "媒体" to listOf("media_play_pause" to "播放 / 暂停", "media_next" to "下一首", "vol_up" to "增大音量"),
    "表达式" to listOf("lua:" to "Lua 脚本", "script:" to "脚本动作"),
)
@Composable private fun ActionEditorDialog(initial:String,onDismiss:()->Unit,onApply:(String)->Unit) {
    var text by remember(initial){mutableStateOf(initial)}
    var group by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("编辑点击动作")},text={Column(Modifier.widthIn(min=360.dp,max=620.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        WmSegmented(quickActionGroups.keys.map{it to it},group ?: quickActionGroups.keys.first(),{group=it})
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) { quickActionGroups[group ?: quickActionGroups.keys.first()].orEmpty().forEach { (value,label) -> TextButton({text=value}){Text(label)} } }
        CompactField(text,{text=it},Modifier.fillMaxWidth(),multiline=true,onDraftChange={text=it},fieldHeight=130.dp)
    }},confirmButton={Button({onApply(text)}){Text("保存")}},dismissButton={TextButton(onDismiss){Text("取消")}})
}
