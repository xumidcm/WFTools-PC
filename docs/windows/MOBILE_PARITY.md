# 手机版控件迁移核对表

本表的“已迁移”只指桌面有可发现、可操作的**一等语义控件**。渲染器支持某个 XML 属性、保存时保留它、或可以在“高级原始属性”里手填，都不算该控件已迁移。

审计真源为上游提交 **201cde43171b3481cd4ed2ed1e73b20e3daa7400**。指定八份文件与本次读取的 app/ 文件逐字一致（git diff 为空）。桌面对照为 HEAD **b91da397e196de64a5789394f42bcc90d6934655** 加本次读取时工作树；桌面仍在开发，修改后应重新勾选，不能用本文状态代替运行验证。本次只审计源码，不运行 Gradle，也不修改功能代码。

## 判定规则与使用方法

- **有**：已找到专用入口、字段语义与对应写入/模拟状态。仍须按最后一节验收。
- **部分**：有一等入口，但范围、默认值、选项、适用类型或交互缺项；不能勾成完成。
- **无**：没有一等入口；包含“仅高级原始字段可编辑”的情况。
- **范围外**：手机版存在，但当前用户明确排除，或仅用于非 WatchMaker 格式。
- 表内“默认”优先记录控件的 getAttr/default 或数值回退；“空”表示 UI 没有设默认值。**提示写着默认值**与**控件实际种入默认值**分开记录，不能从渲染器默认值倒推 UI。
- 参数表中 S = 滑杆（含数值读数）；S+fx = 滑杆及表达式弹窗；E = 有标签/说明的表达式行；D = 固定选项下拉；C = 色块、颜色选择器及表达式入口；T = 文本输入；B = 按钮；SW = 开关。
- 每行开头的“☐”是独立验收项。相同属性在不同图层或 shader 下语义不同，须分别验证。
- 除特别注明，参数行均写入对应 Layer 的属性；预览表全部是运行时状态，**XML 列恒为“无”**。

## 源码索引

正文的“X:行号”均指下表文件的一基行号；范围用于定位完整调用。上游索引冻结在上述提交。

| 代号 | 真源文件 |
|---|---|
| G | [GlobalTab.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/GlobalTab.kt) |
| V | [PreviewTab.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/PreviewTab.kt) |
| P | [WmPropertySection.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/watchmaker/WmPropertySection.kt) |
| W | [WmPropertyWidgets.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/watchmaker/WmPropertyWidgets.kt) |
| T | [WmTextParams.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/watchmaker/WmTextParams.kt) |
| I | [WmImageParams.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/watchmaker/WmImageParams.kt) |
| H | [WmShapeParams.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/watchmaker/WmShapeParams.kt) |
| K | [WmClockParams.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/watchmaker/WmClockParams.kt) |

必要展开来源：G:104 委托格式适配器，不能因此漏掉全局脚本与音频；通用坐标、点击动作位于 P 的调用者。以下文件同样核对上游提交，不把其他格式功能混进 WatchMaker 清单。

| 代号 | 文件及用途 |
|---|---|
| A | [WmFormatAdapter.kt](../../app/src/main/java/com/ailife/wftools2/domain/editor/watchmaker/WmFormatAdapter.kt)，435–518 为 GlobalSection；544–698 为 AudioResourcesDialog |
| B | [PropertyTab.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/tabs/PropertyTab.kt)，通用位置/尺寸/点击动作；440–767 颜色选择器；967–1045 滑杆；1047–1266 点击动作编辑器 |
| M | [WmWatchfaceModels.kt](../../app/src/main/java/com/ailife/wftools2/domain/editor/watchmaker/WmWatchfaceModels.kt)，6–18 项目默认值，69–95 通用图层默认值 |
| S | [WmTagsData.kt](../../app/src/main/java/com/ailife/wftools2/domain/editor/watchmaker/WmTagsData.kt)，15–155 为 WmPreviewState 默认值 |
| R | [WmPreviewController.kt](../../app/src/main/java/com/ailife/wftools2/ui/editor/components/WmPreviewController.kt)，32–50、101–122 为运行控制默认值 |
| X | [WmProjectManager.kt](../../app/src/main/java/com/ailife/wftools2/domain/editor/watchmaker/WmProjectManager.kt)，429–437、457 起为模型与 XML 根属性映射 |

桌面证据：

| 代号 | 文件及当前入口 |
|---|---|
| D | [PropertyPanels.kt](../../desktopApp/src/main/kotlin/com/ailife/wftools/pc/PropertyPanels.kt)：PropertiesPanel 22–119；ProjectPanel 121–138；PreviewSettingsPanel 141 起 |
| C | [WmDesktopControls.kt](../../desktopApp/src/main/kotlin/com/ailife/wftools/pc/WmDesktopControls.kt)：WmNumericControl 53–72；WmColorControl/Dialog 97–123；WmAlignmentControl 124 起 |
| E | [SimulationEditor.kt](../../desktopApp/src/main/kotlin/com/ailife/wftools/pc/SimulationEditor.kt)：通用原始模拟字段 14–53；一等分类控件 68–88；主题 89–94 |
| U | [EditorWorkspace.kt](../../desktopApp/src/main/kotlin/com/ailife/wftools/pc/EditorWorkspace.kt)：顶栏、素材库、预览工具栏 |
| F | [platform/DesktopFiles.kt](../../desktopApp/src/main/kotlin/com/ailife/wftools/pc/platform/DesktopFiles.kt)：当前文件选择过滤器 |
| N | [Main.kt](../../desktopApp/src/main/kotlin/com/ailife/wftools/pc/Main.kt)：CommandDispatcher 保存、复制粘贴与应用入口 |

## 1. 表盘 / Global

适用范围均为当前 WatchMaker 项目；表中 root 表示 Watch 根属性，不是 Layer 属性。

| 验收 ID | 控件 / 存储 | 手机版控件、默认与限制 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ G01 | 表盘名称 / root.name | T；模型新建值 New WatchMaker Face，无长度限制 | 有：D:125 名称字段；本地新建名称已本地化 | G:68–76；M:8 |
| ☐ G02 | 描述 / root.description | 专用单行 T；空 | 有：D:125 | A:439–443 |
| ☐ G03 | 标签 / root.tags | 专用 T；空，逗号分隔提示 | 有：D:125 | A:445–449 |
| ☐ G04 | 背景 / root.bg_color | 专用 T；000000，RRGGBB 提示；上游此处本身不是颜色选择器 | 有：D:131 背景文本 | A:451–455；M:13 |
| ☐ G05 | 原始形状 / root.shape | D：模型 Round / Square / Apple；默认 Round；分别写XML的R / S / Apple | 部分：D:130 只有 R / S，没有 Apple 选项；不要把“设备预览形状”算作原始形状 | A:457–466；X:635–647 |
| ☐ G06 | 作者 / root.author | 专用 T；空 | 有：D:125 | A:468–472 |
| ☐ G07 | 作者链接 / root.web_link | 专用 T；空 | 有：D:125 | A:474–478；X:437 |
| ☐ G08 | 全局脚本 / project.globalScript | 脚本卡片 → 专用弹窗；空。文件通常为 scripts/script.txt，加载也支持嵌入 global_script；不是任意新增 root 字段 | **部分**：D:133–135 只有内联多行输入；下面的脚本编辑器功能尚未齐全 | A:480–483；W:460–507 |
| ☐ G09 | 普通另存为 / .watch 文件 | B，onSaveAs(false) | 有：N 的 SAVE_AS 与原生保存对话框 | G:106–110 |
| ☐ G10 | 受保护另存为 / protection、watch.pxml | WatchMaker 专有 B，onSaveAs(true) | **范围外：用户已排除 pxml/保护编辑与导出，不应补做** | G:112–117 |
| ☐ G11 | 截图 / 导出的图像文件 | B，录制期间禁用，onScreenshot | **无**：自动生成 preview.jpg 不等于用户可用的截图入口 | G:121–127 |
| ☐ G12 | GIF 录制 / 导出的 GIF | B，录制状态文案，录制期间禁用，onStartRecording | **无**：能播放 GIF 不等于能录制预览 | G:129–134 |
| ☐ G13 | 音频资源管理入口 / sfx/ | 音符行及编辑图标 → AudioResourcesDialog | **无独立管理器**；素材库出现文件名不算完整音频管理 | A:486–516 |
| — | ID 编辑、重新生成 ID | 仅 adapter.formatName != WatchMaker 时显示 | 范围外，不记 WatchMaker 缺项 | G:45–65 |
| — | GlobalTab 通用宽高 | 仅非 WatchMaker 显示 | 范围外；桌面 D:128–129 的原始尺寸是桌面增补，不是手机版漏项 | G:78–101 |

### 全局脚本编辑器的独立验收项

| 验收 ID | 控件 | 默认 / 行为 | 桌面当前 | 真源 |
|---|---|---|---|---|
| ☐ GS01 | 点击脚本卡片、字符数摘要 | 空时“添加”，非空显示字符数 | 无等价卡片/弹窗；只有内联编辑 | W:483–503 |
| ☐ GS02 | 大型脚本编辑弹窗 | usePlatformDefaultWidth=false，等宽代码区 | 无 | W:536–568、712–739 |
| ☐ GS03 | CHECK 按钮 | 新 WmScriptRuntime 执行当前脚本，显示 lastError | 无显式检查入口；主预览报错不能替代编辑器检查 | W:584–591、619–639 |
| ☐ GS04 | 保存按钮 | onSave(value) | 部分：内联 Ctrl+Enter / 失焦应用 | W:593–596；D:134–135 |
| ☐ GS05 | 自动换行开关 | 默认 true；关闭后横向滚动 | 无 | W:545–553、602–617、678–726 |
| ☐ GS06 | 与软换行同步的行号栏 | 同一垂直滚动位置；一个源码行只编号一次 | 无 | W:510–533、646–675 |
| ☐ GS07 | 未保存关闭确认 | 返回/外部关闭时检查草稿差异；可保存或放弃 | 部分：文档关闭确认存在，脚本专用草稿确认不存在 | W:558–564、742–757 |

### AudioResourcesDialog

这些操作更改 sfx/ 文件，不新增一个名为 audio 的 XML 属性。它们可被 tap_action=play_sound:name 使用。

| 验收 ID | 控件 | 手机版范围 / 行为 | 桌面当前 | 真源 |
|---|---|---|---|---|
| ☐ AU01 | 音频列表、名称、文件大小、空态 | mp3 / ogg / wav / m4a，按名字排序 | 部分：通用素材列表可列 sfx；缺音频专用信息布局 | A:580–621、661–667 |
| ☐ AU02 | 添加音频 | OpenDocument audio/*，复制到 sfx/；报告导入错误 | 无：F 的 chooseAssets 过滤器未提供音频，专用入口不存在 | A:551–567、638–652 |
| ☐ AU03 | 试听 | 每行播放按钮；结束/错误释放 MediaPlayer | 无一等试听按钮；运行时 sound() 不算 | A:622–626、686–696 |
| ☐ AU04 | 删除音频 | 每行删除并刷新列表 | 无：当前素材菜单只有复制路径/资源管理器；未来删除须增加引用保护，不能照搬上游直接file.delete | A:627–631；U:275–317 |
| ☐ AU05 | 关闭音频弹窗 | 关闭按钮 / dismiss | 无专用弹窗 | A:655–657 |

这里审计的是sfx文件管理。M中虽有Sounds/Sound/Spoken模型，但A:435–518的GlobalSection未提供这些结构的专用调度/TTS编辑控件，不能把模型存在当作手机版已有编辑界面。

## 2. 属性页通用控件

所有 WatchMaker 图层进入 P:167–199 的类型分派。宽高控件除 text、text_curved、text_ring、markers、markers_hm、ring、series、calendar 外显示；这些例外类型用字号/半径等决定尺寸。

| 验收 ID | XML / 控件 | 手机版类型、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ P01 | x | S+fx；0，−256…256 | 有：D:50；键盘微调是额外入口 | B:233–241；M:83 |
| ☐ P02 | y | S+fx；0，−256…256 | 有：D:51 | B:234–245；M:84 |
| ☐ P03 | width | S+fx；100，0…512；按上述类型隐藏 | 有：D:53–55 | B:235、248–257；M:85 |
| ☐ P04 | height | S+fx；100，0…512；同上 | 有：D:53–55 | B:236、248–262；M:86 |
| ☐ P05 | rotation | S+fx；0°，0…360 | 有：D:58 | B:275–278；M:95 |
| ☐ P06 | opacity | S+fx；100%，0…100，UI 与内部 0…255 alpha 转换 | 有：D:59 | B:279–288；M:89 |
| ☐ P07 | display | D；bd，选项 bd / b / d | 有：D:65；桌面额外 n 隐藏；隐藏值 h/n 均需保留 | P:127；W:109–116 |
| ☐ P08 | alignment | 3×3 锚点选择器；cc；tl/tc/tr/cl/cc/cr/bl/bc/br | 有：D:67、C:124 起 | P:128；W:120–184 |
| ☐ P09 | anim_scale_x | S+fx；100，0…1000 | **部分**：D:98 当前滑杆仅 0…300 | P:130–136 |
| ☐ P10 | anim_scale_y | S+fx；100，0…1000 | **部分**：D:99 当前滑杆仅 0…300 | P:137–143 |
| ☐ P11 | skew_x | S+fx；0°，−90…90 | **无**，仅高级原始字段 | P:144–150 |
| ☐ P12 | skew_y | S+fx；0°，−90…90 | **无**，仅高级原始字段 | P:151–157 |
| ☐ P13 | gyro | S+fx；0，−20…20 | **无**，仅高级原始字段 | P:158–162 |
| ☐ P14 | tap_action | 动作摘要卡片及专用编辑器；空 | **无专用动作配置器**；动作测试开关只负责执行已有动作 | B:291–320 |
| ☐ P15X | 多选相对位移 / x | 增量滑杆 −250…250，步长1；释放归零、不抹平相对位置 | 部分：桌面步进/nudge保相对，基础滑杆仍为绝对赋值 | B:1293–1294；D:25–30、50 |
| ☐ P15Y | 多选相对位移 / y | 增量滑杆 −250…250，步长1；释放归零 | 部分：桌面步进/nudge保相对，基础滑杆仍为绝对赋值 | B:1295–1296；D:25–30、51 |
| ☐ P16 | 多选相对旋转 / rotation | 增量滑杆 −180…180，步长 1 | 无相对旋转滑杆；当前基本旋转为共同绝对值 | B:1297；D:58 |
| ☐ P17 | 多选可见性 / display | SW；首层状态；批量写bd/h | 有等价显示下拉及隐藏命令，桌面隐藏写n；须验证混合值 | B:1307–1310、367–371；D:65 |
| ☐ P18 | 多选opacity | S；首层值；0…100 | 有共同不透明度控件 | B:1312–1321；D:59 |
| ☐ P19 | 多选text | 仅全是text/text_curved/text_ring；单行T，首层值 | 有，桌面多行输入且混合值显示空 | B:1277–1281、1415–1428；D:70–73 |
| ☐ P20 | 多选color | 仅上述文字类型；首层色块及picker，最终写6位RGB | 有主色控件；完整picker见W03 | B:1430–1443、1469；D:86 |
| ☐ P21 | 多选text_size | 仅上述文字类型；回退24，6…120 | 部分：桌面共用1…256，回退40 | B:1405–1406、1446–1452；D:84 |

### 共用控件行为本身

| 验收 ID | 控件契约 | 手机版行为 | 桌面状态 / 差异 | 真源 |
|---|---|---|---|---|
| ☐ W01 | 数值滑杆、原始表达式、fx | 范围跨度≤2 步进0.01；≤10 步进0.1；其余1；长按450ms后连续增减；一次手势一次历史 | 部分：C:53–72 有数值框/滑杆/±/fx，但 ± 固定1、无同等长按；可用明确的桌面键盘替代，需验收历史合并 | W:85–103；B:967–1045 |
| ☐ W02 | 颜色专用行 | 色块、原值、picker、fx；WmColorRow传supportAlpha=false，写6位RGB | 部分：C:97–123 有 RGB/HEX/色块/fx，桌面还接受8位ARGB | W:189–260 |
| ☐ W03 | 颜色选择器 RGB / HSV / 预设 | 三选项卡，RGB/HSV通道、预设、最近颜色及清空、屏幕吸色 | 部分：桌面有RGB和7个色块；无HSV页、最近色、屏幕吸色 | B:440–508、509–620、661–767 |
| ☐ W04 | 参数表达式行 E | 原值摘要、语义说明、编辑图标 → 对应参数表达式弹窗 | 部分：桌面为若干基础字段提供fx；不能用“高级原始属性”替代所有下表字段 | W:386–425 |
| ☐ W05 | 固定选项 / 开关 / 属性卡 | WmDropdownRow、WmSwitchRow、WmParamCard | 基础组件有，实例是否存在按下表核对 | W:293–383；C:37–43、69–94 |
| ☐ W06 | corner_type 共用映射 | 0/16全直角，1四角，2除右下，3除左下，4除右上，5除左上，6上边，7下边，8左边，9右边，10左上+右下，11右上+左下，12左上，13右上，14左下，15右下 | **无**语义角点选择；以下 rounded/progress/chart 各实例均未齐 | W:270–287 |

tap_action 专用编辑器的待办：Expression 多行编辑（B:1135–1142）、App 搜索/列表并写 w_app:label + 反引号分隔包名和 Activity（B:1144–1163）、Preset 分组（B:1078–1096、1168–1185）、Link 写 w_link:URI（B:1188–1202、1254 起）、Audio 列 sfx 并写 play_sound:无扩展名（B:1099–1106、1225–1241）。这些桌面均没有一等编辑器；Android 已安装应用枚举须采用明确的桌面/手表目标方案，不能直接把 Windows 应用当 Android 包名。

Preset的实际值（B:1078–1096）必须保留，不能换成看起来相似的新字符串：

- Apps：tap_launcher、widget_weather、widget_health、widget_calendar、w_agenda、w_fit、w_heart_rate、w_compass、w_keep、m_email、m_calendar、voice_search、w_contacts、w_sports、w_gallery、w_messaging、w_camera、w_dialer、w_music、w_clock、w_calculator、w_payment、w_map、browser、flashlight、socialmedia。
- System：open_settings、battery_status。
- Media & volume：media_play_pause、media_play、media_pause、media_next、media_prev、vol_up、vol_down。
- Watchface：color_switch_next、color_switch_prev、color_switch_select、sw_start_stop、sw_reset。

## 3. text / text_curved

text_curved 先完整调用 WmTextParams，再追加弧形参数；两者还调用阴影和文字动画。text_ring 不调用这些共用文字效果页，见第7节。

| 验收 ID | XML / 控件 | 手机版类型、默认 / 范围 | 桌面当前 | 真源 |
|---|---|---|---|---|
| ☐ T01 | text | 2–4行等宽 T+fx；空，支持标签/表达式 | 有：D:70–73 | T:86–129 |
| ☐ T02 | font | 字体下拉，显示真实字体预览；默认Roboto-Regular；fonts/ TTF/OTF，fonts_bm/ FNT，用 bm:name | 部分：D:74–83 有下拉/导入/fx；选项文字未按各字体显示，默认名称策略不同 | T:133–225 |
| ☐ T03 | 矢量字体导入 / font、fonts/ | 选择TTF/OTF，复制避名后选中 | 有：专用导入按钮+核心事务；原生picker仍为通用素材过滤器 | T:315–332、405–408；D:79–83 |
| ☐ T04 | 位图字体导入 / font、fonts_bm/ | 单独选择FNT和atlas，显示face/size/字数、atlas尺寸与图像，保存重映射 | 部分：核心支持FNT+同目录atlas；无双步骤选择/统计/atlas确认界面 | T:334–394、410–473 |
| ☐ T05 | text_size | S+fx；回退24，8…300 px | **部分**：D:84 回退40、范围1…256 | T:228–238 |
| ☐ T06 | color | C；原值空，亮屏色 | 有主色控件，颜色picker完整性见W02/W03 | T:241；D:86 |
| ☐ T07 | color_dim | C；原值空，息屏色 | **无** | T:242 |
| ☐ T08 | transform | D；原值空；n / uc / lc | **无** | T:243–245 |
| ☐ T09 | stencil | SW；是否等于y（忽略大小写），写y/n | **无** | T:246–247 |
| ☐ T10 | outline | D；空 / Outline / Glow | **无**；不是现有通用“颜色”框 | T:248–251 |
| ☐ T11 | radius，仅text_curved | S+fx；200，0…500 px | **无** | T:487–496 |
| ☐ T12 | curve_dir，仅text_curved | D；Up；Up / Down | **无** | T:497–501 |

### Outline / Glow 与 Shadow（text、text_curved、shape、rounded）

| 验收 ID | XML | 手机版类型、默认 / 范围 | 桌面当前 | 真源 |
|---|---|---|---|---|
| ☐ FX01 | outline | D：空 / Outline / Glow；空 | 无 | T:248–251；H:123–129 |
| ☐ FX02 | o_color | C；空；outline 非空时显示 | 无 | H:296–309 |
| ☐ FX03 | o_size | S+fx；Outline回退2、0…12；Glow回退8、0…40 px | 无 | H:304–316 |
| ☐ FX04 | o_opacity | S+fx；100，0…100% | 无 | H:318–324 |
| ☐ FX05 | shadow | D：空 / Drop / Flat；启用时种入未设默认值 | 无 | T:524–539 |
| ☐ FX06 | w_color | C；空；启用阴影时若未设则ffffff | 无 | T:534–540 |
| ☐ FX07 | w_distance | S+fx；读取回退0，0…200 px；启用时零值种入10 | 无 | T:534、541–547 |
| ☐ FX08 | w_angle | S+fx；135°，0…360 | 无 | T:549–555 |
| ☐ FX09 | w_opacity | S+fx；读取回退100，0…100%；启用时未设种入75 | 无 | T:536、557–563 |

### Text animation（仅text、text_curved）

此页不是 HSC 动画编辑器。以下控件在手机版已经显式出现，即使某些动画尚需另核对运行时支持，也不能以此跳过桌面编辑入口。

| 验收 ID | XML | 手机版类型、默认 / 提示范围 | 桌面当前 | 真源 |
|---|---|---|---|---|
| ☐ TA01 | anim_in | E；空；进入动画类型，无枚举约束 | 无 | T:574 |
| ☐ TA02 | anim_out | E；空；退出动画类型，无枚举约束 | 无 | T:575 |
| ☐ TA03 | dur_in | E；空；提示默认0.5秒 | 无 | T:576 |
| ☐ TA04 | dur_on | E；空；0表示持续可见 | 无 | T:577 |
| ☐ TA05 | dur_out | E；空；提示默认0.5秒 | 无 | T:578 |
| ☐ TA06 | dur_off | E；空；循环前隐藏秒数 | 无 | T:579 |
| ☐ TA07 | delay_start | E；空；初始延迟秒数 | 无 | T:580 |
| ☐ TA08 | repeat_count | E；空；−1表示无限 | 无 | T:581 |
| ☐ TA09 | restart_on_load | D；原值空；N / Y | 无 | T:582–583 |
| ☐ TA10 | restart_on_bright | D；原值空；N / Y | 无 | T:584–585 |
| ☐ TA11 | restart_text_change | D；原值空；N / Y | 无 | T:586–587 |

## 4. image / image_gif / image_cond

| 验收 ID | XML / 操作 | 手机版类型、默认 / 范围 | 适用图层 | 桌面当前 | 真源 |
|---|---|---|---|---|---|
| ☐ I01 | path | 当前图像缩略图+文件名+选择图像弹窗；空 | image、image_cond | 部分：有路径框/替换/缩略图/素材库；缺同一个选择弹窗中的项目图像网格 | I:84–86、265–267、381–475、478–521；D:87–95 |
| ☐ I02 | path + width + height 联动 | 从本地或项目图像选择时读取固有尺寸，三字段一起更新 | image、image_cond | **部分**：现有替换只更新path，未见等价尺寸联动；创建新图层会读尺寸不等于替换联动 | I:85、266、435–440 |
| ☐ I03 | 本地图像导入 | 本地图片选择，转PNG并写images/ | image、image_cond | 有直接文件复制/保留格式的桌面流程，行为差异应验收 | I:321–345 |
| ☐ I04 | 在线资源选择 | Downloader PICTURE入口 | image、image_cond | 范围外：当前不迁移市场/下载器；不是本地资源控件已完成 | I:400–410 |
| ☐ I05 | color | C；空；ffffff为无染色 | image、image_cond | 部分：D:86仅已有color属性时显示image类主色卡 | I:88、294 |
| ☐ GIF01 | path 帧序列 | 反引号分隔；帧数读数、逐帧48dp缩略图、预览弹窗 | image_gif | **无帧列表编辑器**；路径字符串框不算 | I:105、113–118、167–188 |
| ☐ GIF02 | 添加帧 | 多图选择追加；GIF可拆帧，导入缩至512×512；更新path及gif_delay | image_gif | **部分**：可导入/播放单个GIF；不能等同多帧追加、拆帧和顺序编辑 | I:119–159 |
| ☐ GIF03 | 全删帧 | 写path为空 | image_gif | 无专用按钮 | I:116–118 |
| ☐ GIF04 | 帧上移 | 重排path序列，第一帧禁用 | image_gif | 无 | I:182 |
| ☐ GIF05 | 帧下移 | 重排path序列，最后一帧禁用 | image_gif | 无 | I:183 |
| ☐ GIF06 | 删除单帧 | 从path序列移除该项 | image_gif | 无；删除素材文件不是同一操作 | I:184 |
| ☐ GIF07 | 在线连续图片 | Downloader PICTURE_LOOP | image_gif | 范围外 | I:163–165、190–226 |
| ☐ GIF08 | gif_delay | S+fx；50，10…2000 ms | image_gif | **无** | I:240–246 |
| ☐ CI01 | cond_grid 列数 | T；1x1中的列，写“列x行”；UI无数值校验范围 | image_cond | **无** | I:269–281 |
| ☐ CI02 | cond_grid 行数 | T；1x1中的行，写“列x行” | image_cond | **无** | I:282–288 |
| ☐ CI03 | cond_value | E；空；Lua表达式，一基帧索引 | image_cond | **无** | I:290–293 |
| ☐ CI04 | weather_night | D；N；N日间 / Y夜间变体 | image_cond | **无** | I:295–299 |
| ☐ I06 | shader 及动态参数 | 见第6节 | image、image_gif、image_cond | **无** | I:89、248、300 |

## 5. shape / rounded / progress / ring

所有下列“无”均指没有一等控件；不能因高级原始属性支持这些键而打勾。

| 验收 ID | XML / 控件 | 手机版类型、默认 / 范围 | 适用图层 | 桌面当前 | 真源 |
|---|---|---|---|---|---|
| ☐ H01 | shape | D；Square；Square/Circle/Triangle/Pentagon/Hexagon/Star/Heart | shape | 无 | H:86–100 |
| ☐ H02 | color | C；空 | shape、rounded | 有主色；picker缺项见W02/W03 | H:103；D:86 |
| ☐ H03 | radius | S+fx；0，0…500 px | rounded | 无 | H:105–114 |
| ☐ H04 | corner_type | D；1，全部映射见W06 | rounded | 无 | H:115–120 |
| ☐ H05 | outline / shadow / shader | 分别见FX与第6节，不是一个笼统选项 | shape、rounded | 无 | H:123–131；P:176–179 |
| ☐ PR01 | pct_complete | S+fx；50，0…100 | progress | 无 | H:143–153 |
| ☐ PR02 | color | C；空；填充前景 | progress | 部分：已有color时可用通用主色卡 | H:155；D:86 |
| ☐ PR03 | color2 | C；空；填充渐变终点 | progress | 无 | H:156 |
| ☐ PR04 | color3 | C；空；轨道背景 | progress | 无 | H:157 |
| ☐ PR05 | color4 | C；空；间隙色 | progress | 无 | H:158 |
| ☐ PR06 | margin | S+fx；0，0…50 | progress | 无 | H:160–165 |
| ☐ PR07 | radius | S+fx；50，0…100 | progress | 无 | H:167–172 |
| ☐ PR08 | corner_type | D；1，见W06 | progress | 无 | H:174–178 |
| ☐ PR09 | end_style | D；round；round / flat | progress | 无 | H:179–183 |
| ☐ PR10 | angle | S+fx；90°，0…360 | progress | 无 | H:184–189 |
| ☐ PR11 | outside_opacity | S+fx；100，0…100 | progress | 无 | H:191–196 |
| ☐ RI01 | color | C；空；已填充弧 | ring | 部分：已有color时有通用主色 | H:221 |
| ☐ RI02 | color2 | C；空；弧内未填充轨道 | ring | 无 | H:222 |
| ☐ RI03 | color3 | C；空；弧跨度外区域 | ring | 无 | H:223 |
| ☐ RI04 | radius_outer | S+fx；100，0…500 | ring | 无 | H:225–231 |
| ☐ RI05 | radius_inner | S+fx；80，0…500 | ring | 无 | H:232–238 |
| ☐ RI06 | radius 端帽圆角 | S+fx；默认maxCap=(outer−inner)/2且≥0；范围0…max(maxCap,1)，显示值限到maxCap | ring | 无 | H:239–254 |
| ☐ RI07 | angle | S+fx；270°，0…360，从12点方向起 | ring | 无 | H:256–263 |
| ☐ RI08 | angle_total | S+fx；360°，0…360 | ring | 无 | H:264–270 |
| ☐ RI09 | outside_opacity | S+fx；100，0…100 | ring | 无 | H:272–277 |
| ☐ RI10 | is_clockwise | D；Y；Y顺时针 / N逆时针 | ring | 无 | H:279–283 |

注意：P 的实际调用链没有给 progress 或 ring 调用 WmShaderParams / WmTextShadowParams。不要凭 renderer 可解析某些键就把它们追加成“手机版已有控件”。

## 6. Shader 专用控件矩阵

一等入口 shader 是 D，默认为空；选项为空、GradientLinear、GradientRadial、Rainbow、RGB、HSV、Segment、SegmentBetween、Radar、Progress、ProgressBetween（H:354–377）。选择 shader 时必须种入对应 u_* 默认值，而非留空。选择空时不显示参数。

**手机版实际适用图层**：image、image_gif、image_cond、text、text_curved、shape、rounded、gallery_2d。调用证据 I:89/248/300，T:252及485，H:131，K:563。桌面目前**全部缺少一等 shader 控件**，即使渲染器已经支持着色。

| 验收 ID | shader / XML | 参数含义、手机版类型、默认 / 范围 | 桌面 | 真源 |
|---|---|---|---|---|
| ☐ SH00 | shader | D及参数联动；上述11个选项 | 无 | H:354–379 |
| ☐ SH01 | GradientLinear / u_1 | 起始色 C；ffffff | 无 | H:333、392 |
| ☐ SH02 | GradientLinear / u_2 | 终止色 C；000000 | 无 | H:333、393 |
| ☐ SH03 | GradientLinear / u_3 | 渐变角 S+fx；0，0…360 | 无 | H:394–396 |
| ☐ SH04 | GradientLinear / u_4 | 渐变比例 S+fx；100，0…200 | 无 | H:397–399 |
| ☐ SH05 | GradientRadial / u_1 | 中心色 C；ffffff | 无 | H:334、402 |
| ☐ SH06 | GradientRadial / u_2 | 边缘色 C；000000 | 无 | H:334、403 |
| ☐ SH07 | GradientRadial / u_3 | 半径比例 S+fx；100，0…200 | 无 | H:404–406 |
| ☐ SH08 | Rainbow / u_1 | 色相旋转 S+fx；0，0…360 | 无 | H:408–411 |
| ☐ SH09 | RGB / u_1 | 红偏移 S+fx；0，−100…100 | 无 | H:413–416 |
| ☐ SH10 | RGB / u_2 | 绿偏移 S+fx；0，−100…100 | 无 | H:417–419 |
| ☐ SH11 | RGB / u_3 | 蓝偏移 S+fx；0，−100…100 | 无 | H:420–422 |
| ☐ SH12 | HSV / u_1 | 色相偏移 S+fx；0，−100…100 | 无 | H:424–427 |
| ☐ SH13 | HSV / u_2 | 饱和度偏移 S+fx；0，−100…100 | 无 | H:428–430 |
| ☐ SH14 | HSV / u_3 | 明度偏移 S+fx；0，−100…100 | 无 | H:431–433 |
| ☐ SH15 | Segment / u_1 | 弧跨度 S+fx；90，0…360 | 无 | H:435–438 |
| ☐ SH16 | Segment / u_2 | 内部不透明度 S+fx；100，0…100 | 无 | H:439–441 |
| ☐ SH17 | Segment / u_3 | 外部不透明度 S+fx；20，0…100 | 无 | H:442–444 |
| ☐ SH18 | SegmentBetween / u_1 | 起角 S+fx；0，0…360 | 无 | H:446–449 |
| ☐ SH19 | SegmentBetween / u_2 | 终角 S+fx；180，0…360 | 无 | H:450–452 |
| ☐ SH20 | SegmentBetween / u_3 | 内部不透明度 S+fx；100，0…100 | 无 | H:453–455 |
| ☐ SH21 | SegmentBetween / u_4 | 外部不透明度 S+fx；10，0…100 | 无 | H:456–458 |
| ☐ SH22 | Radar / u_1 | 扫描前缘角 S+fx；0，0…360 | 无 | H:460–463 |
| ☐ SH23 | Radar / u_2 | 前缘峰值不透明度 S+fx；100，0…100 | 无 | H:464–466 |
| ☐ SH24 | Radar / u_3 | 尾部/底部不透明度 S+fx；15，0…100 | 无 | H:467–469 |
| ☐ SH25 | Progress / u_1 | 填充百分比 S+fx；50，0…100 | 无 | H:471–474 |
| ☐ SH26 | Progress / u_2 | 方向角 S+fx；0，0…360 | 无 | H:475–477 |
| ☐ SH27 | Progress / u_3 | 已填区域不透明度 S+fx；100，0…100 | 无 | H:478–480 |
| ☐ SH28 | Progress / u_4 | 未填区域不透明度 S+fx；20，0…100 | 无 | H:481–483 |
| ☐ SH29 | ProgressBetween / u_1 | 起百分比 S+fx；0，0…100 | 无 | H:485–488 |
| ☐ SH30 | ProgressBetween / u_2 | 终百分比 S+fx；50，0…100 | 无 | H:489–491 |
| ☐ SH31 | ProgressBetween / u_3 | 方向角 S+fx；0，0…360 | 无 | H:492–494 |
| ☐ SH32 | ProgressBetween / u_4 | 已填区域不透明度 S+fx；100，0…100 | 无 | H:495–497 |
| ☐ SH33 | ProgressBetween / u_5 | 未填区域不透明度 S+fx；15，0…100 | 无 | H:498–500 |

## 7. 刻度、文字环、序列、日历、图表

### markers / markers_hm

| 验收 ID | XML | 手机版类型、默认 / 范围 | 适用 | 桌面 | 真源 |
|---|---|---|---|---|---|
| ☐ MK01 | color | C；空，刻度色 | markers | 部分：已有color时通用主色 | K:40 |
| ☐ MK02 | shape | D；Square；Square/Circle/Triangle | markers | 无 | K:41–47 |
| ☐ MK03 | radius | S+fx；120，0…256 | markers | 无 | K:48–54 |
| ☐ MK04 | m_count | S+fx；12，1…120 | markers | 无 | K:55–60 |
| ☐ MK05 | m_width | S+fx；6，0…100 | markers | 无 | K:61–66 |
| ☐ MK06 | m_height | S+fx；20，0…100 | markers | 无 | K:67–72 |
| ☐ MK07 | squarify | S+fx；100，0…100；0圆/100方 | markers | 无 | K:73–78 |
| ☐ HM01 | color | C；空，小时刻度 | markers_hm | 部分：已有color时通用主色 | K:98 |
| ☐ HM02 | color2 | C；空，分钟刻度 | markers_hm | 无 | K:99 |
| ☐ HM03 | radius | S+fx；120，0…256 | markers_hm | 无 | K:100–106 |
| ☐ HM04 | m_hour | D；Large；None/Tiny/Small/Medium/Large | markers_hm | 无 | K:94–95、107–110 |
| ☐ HM05 | m_minute | D；Small；同上 | markers_hm | 无 | K:111–114 |
| ☐ HM06 | squarify | S+fx；100，0…100 | markers_hm | 无 | K:115–120 |

### text_ring

| 验收 ID | XML | 手机版类型、默认 / 范围 | 桌面 | 真源 |
|---|---|---|---|---|
| ☐ TR01 | ring_type | D；1-12；1-12/0-11/0-59/I-XII/Mon-Sun/Mo-Su/Jan-Dec | 无 | K:139–148 |
| ☐ TR02 | color | C；空 | 部分：已有color时通用主色 | K:149 |
| ☐ TR03 | font | E；空，TTF或bm:name | 有专用字体下拉及fx（比上游E丰富） | K:150；D:74–83 |
| ☐ TR04 | text_size | S+fx；32，8…200 | 部分：桌面共用文字页回退40，1…256 | K:151–157；D:84 |
| ☐ TR05 | radius | S+fx；120，0…256 | 无 | K:158–164 |
| ☐ TR06 | transform | D；n；n/uc/lc | 无 | K:165–169 |
| ☐ TR07 | rotated_text | D；n；n直立/y向外旋转 | 无 | K:170–175 |
| ☐ TR08 | show_every | S+fx；1，1…12 | 无 | K:176–181 |
| ☐ TR09 | angle_start | S+fx；0，0…360 | 无 | K:182–187 |
| ☐ TR10 | angle_end | S+fx；360，0…360 | 无 | K:188–193 |
| ☐ TR11 | squarify | S+fx；0，0…100 | 无 | K:194–199 |
| ☐ TR12 | hide_text | E；空，逗号分隔要隐藏的标签 | 无 | K:200–203 |

### series

| 验收 ID | XML | 手机版类型、默认 / 范围 | 桌面 | 真源 |
|---|---|---|---|---|
| ☐ SE01 | series | D；dh24z；dmz/dm/dhz/dh/dh24z/dh24/ddw/dd_ddw/dnnn | 无 | K:217–227 |
| ☐ SE02 | orientation | D；v；v/h | 无 | K:228–231 |
| ☐ SE03 | current_pos | D；m；m中间/f首项/l末项 | 无 | K:232–235 |
| ☐ SE04 | spacing | S+fx；10，0…100 | 无 | K:236–241 |
| ☐ SE05 | color | C；空，当前项 | 部分：已有color时通用主色 | K:242 |
| ☐ SE06 | text_size | S+fx；40，8…200 | 无：series不会进入D的text*专用卡 | K:243–248 |
| ☐ SE07 | font | E；空，当前项字体 | 无 | K:249 |
| ☐ SE08 | color2 | C；空，其余项 | 无 | K:250 |
| ☐ SE09 | text_size2 | S+fx；24，8…200 | 无 | K:251–256 |
| ☐ SE10 | font2 | E；空，其余项字体 | 无 | K:257 |
| ☐ SE11 | transform | D；n；n/uc/lc | 无 | K:258–261 |

### calendar

| 验收 ID | XML | 手机版类型、默认 / 范围 | 桌面 | 真源 |
|---|---|---|---|---|
| ☐ CA01 | color | C；空，星期标题 | 部分：已有color时通用主色 | K:275 |
| ☐ CA02 | color2 | C；空，日期/当天高亮 | 无 | K:276 |
| ☐ CA03 | font | E；空，日期字体 | 无 | K:277 |
| ☐ CA04 | font2 | E；空，星期标题字体 | 无 | K:278 |
| ☐ CA05 | text_size | S+fx；14，6…60 | 无 | K:279–284 |
| ☐ CA06 | margin_x | S+fx；8，0…40 | 无 | K:285–290 |
| ☐ CA07 | margin_y | S+fx；8，0…40 | 无 | K:291–296 |
| ☐ CA08 | outside_opacity | S+fx；60，0…100 | 无 | K:297–302 |
| ☐ CA09 | dow_start | D；1；1星期一/0星期日 | 无 | K:303–306 |
| ☐ CA10 | no_letters_header | D；1；0无标题/1/2/3个字母 | 无 | K:307–310 |
| ☐ CA11 | transform | D；n；n/uc/lc | 无 | K:311–314 |

### chart

| 验收 ID | XML | 手机版类型、默认 / 范围、条件 | 桌面 | 真源 |
|---|---|---|---|---|
| ☐ CH01 | chart_type | D；line；line/bar | 无 | K:330–335 |
| ☐ CH02 | data_points | E；空；分号分隔数据/标签 | 无 | K:336–339 |
| ☐ CH03 | min_max | E；空；可选最大值to最小值，如100to0 | 无 | K:340 |
| ☐ CH04 | chart_group | E；空；图表同步分组ID | 无 | K:341–344 |
| ☐ CH05 | color3 | C；空；折线色，仅line | 无 | K:345–346 |
| ☐ CH06 | color4 | C；空；marker色，仅line | 无 | K:347 |
| ☐ CH07 | line_width | S+fx；10，0…50，仅line | 无 | K:348–353 |
| ☐ CH08 | marker_size | S+fx；10，0…50，仅line | 无 | K:354–360 |
| ☐ CH09 | tension | S+fx；0，0…100，仅line | 无 | K:361–366 |
| ☐ CH10 | bar_thickness | S+fx；50，1…100，仅bar | 无 | K:367–373 |
| ☐ CH11 | radius | S+fx；0，0…50，仅bar | 无 | K:374–379 |
| ☐ CH12 | corner_type | D；1，见W06，仅bar | 无 | K:380–385 |
| ☐ CH13 | color | C；空；填充顶部色 | 部分：已有color时通用主色 | K:387 |
| ☐ CH14 | color2 | C；空；填充底部色 | 无 | K:388 |
| ☐ CH15 | top_opacity | S+fx；100，0…100 | 无 | K:389–394 |
| ☐ CH16 | bottom_opacity | S+fx；0，0…100 | 无 | K:395–400 |
| ☐ CH17 | lines | E；空；参考线复合语法 | 无 | K:401–404 |
| ☐ CH18 | labels | E；空；标签复合语法 | 无 | K:405–408 |

## 8. video / map / gallery_2d

| 验收 ID | XML / 操作 | 手机版类型、默认 / 范围 | 适用 | 桌面 | 真源 |
|---|---|---|---|---|---|
| ☐ VI01 | path选择/替换 | B、本地video/*选择、当前文件名、H.264转码进度与失败提示 | video | 部分：有导入/替换和LibVLC预览；没有H.264标准化转换流程 | K:421–507 |
| ☐ VI02 | path原始值 | E；空；videos/ 文件名 | video | 有路径及fx | K:509；D:87–95 |
| ☐ VI03 | color | C；空，ffffff无染色 | video | 部分：已有color时主色卡；颜色功能见W02/W03 | K:510 |
| ☐ MAP01 | lat | E；空；纬度或标签 | map | 无 | K:524 |
| ☐ MAP02 | lon | E；空；经度或标签 | map | 无 | K:525 |
| ☐ MAP03 | map_zoom | S+fx；14，1…20 | map | 无 | K:526–531 |
| ☐ MAP04 | map_scale | S+fx；100，50…200 | map | 无 | K:532–537 |
| ☐ GA01 | dur_on | S+fx；5，1…60秒/图片 | gallery_2d | 无 | K:549–562 |
| ☐ GA02 | shader及参数 | 第6节 | gallery_2d | 无 | K:563 |
| — | gallery_2d真实图库预览 | 上游该页自己声明占位 | gallery_2d | 不把上游也没有的真实图库渲染算作控件迁移缺项 | K:550–553 |

K:412、514 注释中的“video/map预览占位”不是当前渲染能力的权威：实际手机版 WmPreview.kt 已调用视频播放和地图图块。因此这里只将其**控件**作为真源，不能依据过期注释声称视频/地图不需迁移。

## 9. 预览页控制（不写 XML）

适用所有 WatchMaker 项目；V:59–65 明确非 WatchMaker 不提供此页。下面所有字段都是 WmPreviewState 或 WmPreviewController，不能错误映射成 Watch / Layer XML。状态默认值来自 S 与 R，而非随机展示示例。

### 运行、时间机器与实时数据开关

| 验收 ID | 控件 / 运行字段（XML：无） | 手机版类型、默认 / 范围 | 桌面 | 真源 |
|---|---|---|---|---|
| ☐ PV01 | isBright | 亮屏/息屏分段；true | 有：D时间机器和预览工具栏 | V:76–90；S:134 |
| ☐ PV02 | renderPaused | 暂停/继续图标；false | 有：render.playing反向语义 | V:94–111；R:110 |
| ☐ PV03 | timeOffsetMs跳转 | −1d、−1h、现在、+1h、+1d、−5m、−1m、+1m、+5m；0 | 部分：当前仅±1小时/现在和精确偏移输入 | V:117–128；D时间机器 |
| ☐ PV04 | timeSpeed | 分段 −3600 / −60 / 1 / 60 / 3600；1 | 有 | V:130–140；R:103 |
| ☐ PV05 | resetTime | “现在”同时归零偏移并恢复timeSpeed=1 | 部分：桌面“现在”重设时间/偏移，没有同样重设倍率 | V:120；R:120–123 |
| ☐ PV06 | autoplay | 自动随机模拟值SW；false | 无 | V:144–146；R:39 |
| ☐ PV07 | autoplayIntervalMs | 自动播放时S；2000ms，0.5…10秒 | 无 | V:147–155；R:40 |
| ☐ PV08 | randomize() | Shuffle now按钮 | 无 | V:157 |
| ☐ PV09 | liveBattery | 分类标题实时SW；true；开启时电量滑杆禁用 | 无；仅手动模拟 | V:167–174；R:32 |
| ☐ PV10 | liveWeather | 实时天气SW；true；开启/首次入页取天气 | 无 | V:68–73、219–226；R:34 |
| ☐ PV11 | refreshWeather() | 自动定位取天气按钮、loading禁用、地点/错误信息 | 无；手填天气不算实时获取 | V:228–247 |
| ☐ PV12 | liveSensors | 传感器实时SW；true | 无 | V:278–282；R:33 |
| ☐ PV13 | liveConnectivity | 连接状态实时SW；true | 无 | V:316–320；R:37 |
| ☐ PV14 | liveDevice | 设备模式实时SW；true | 无 | V:339–343；R:35 |
| ☐ PV15 | liveMemory | 内存/存储实时SW；true | 无 | V:390–394；R:36 |
| ☐ PV16 | stopwatchRunning | 秒表运行SW；false | 无专用SW；高级模拟字段/已有动作执行不算 | V:375；R:49 |
| ☐ PV17 | stopwatchMs | 已用时间读数HH:MM:SS.cc；0 | 无专用读数 | V:376–381、450–458 |
| ☐ PV18 | 秒表重置 | 同时停止并归零 | 无专用按钮 | V:382–386 |
| ☐ PV19 | controller.reset() | 重置全部模拟状态、天气、时间、秒表 | 无专用按钮 | V:403；R:125–134 |

### 手动模拟数据逐项表

“无（高级）”表示只有 E:47–53 的通用字段输入，**不算迁移**。有专用分类但控件形式/范围不同，标“部分”。默认来自 S:25–155；“实时”取值会覆盖相关模拟值，所以默认是样本状态，不代表设备实测。

#### 电量与充电

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS01 | 充电状态 / batteryCharging | SW；false | 有：E:79 | V:173 |
| ☐ PS02 | 手表电量 / batteryPercentage | 整数S；75；0…100；实时开启时禁用 | 有：E:79；整数提交需验 | V:174 |
| ☐ PS03 | 电压 / batteryVoltage | 浮点S；3.805V；3.0…4.5 | 无（高级） | V:175 |
| ☐ PS04 | 电流 / batteryCurrentMa | 整数S；350mA；−2000…2000；步50 | 无（高级） | V:176 |
| ☐ PS05 | 手表电池温度 / batteryTempC | 整数S；36°C；−20…60 | 无（高级） | V:177 |
| ☐ PS06 | 手机电量 / phoneBatteryPercentage | 整数S；82；0…100 | 有：E:79；整数提交需验 | V:179 |
| ☐ PS07 | 手机充电 / phoneCharging | SW；true | 有：E:79 | V:180 |
| ☐ PS08 | 手机电池温度 / phoneBatteryTempC | 整数S；34°C；−20…60 | 无（高级） | V:181 |

#### 健康与活动

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS09 | 心率 / heartRate | 整数S；72；40…200 | 部分：E:80目前40…220，整数提交需验 | V:186 |
| ☐ PS10 | 最大心率 / maxHeartRate | 整数S；180；80…220 | 无（高级） | V:187 |
| ☐ PS11 | 压力 / stressScore | 整数S；35；0…100 | 无（高级） | V:188 |
| ☐ PS12 | 步数 / steps | 整数S；8500；0…20000；步100 | 部分：E:80有滑杆，未实现步100 | V:189 |
| ☐ PS13 | 步数目标 / stepGoal | 整数S；10000；1000…30000；步500 | 部分：E:80有滑杆，未实现步500 | V:190 |
| ☐ PS14 | 卡路里 / calories | 整数S；420；0…1500；步10 | 部分：E:80有滑杆，未实现步10 | V:191 |
| ☐ PS15 | 卡路里目标 / calorieGoal | 整数S；700；0…3000；步50 | 无（高级） | V:192 |
| ☐ PS16 | 距离 / distanceKm | 浮点S；6.2km；0…30 | 无（高级） | V:193 |
| ☐ PS17 | 距离目标 / distanceGoalKm | 浮点S；10km；0.5…50 | 无（高级） | V:194 |
| ☐ PS18 | 英里开关 / distanceUnit | SW；KM；开MI/关KM | 无（高级） | V:195 |
| ☐ PS19 | 血氧 / bloodOxygen | 整数S；98%；80…100 | 有：E:80；整数提交需验 | V:196 |
| ☐ PS20 | 爬楼层数 / flightsClimbed | 整数S；8；0…100 | 无（高级） | V:197 |
| ☐ PS21 | 饮食卡路里 / foodCalories | 整数S；1200；0…3000；步50 | 无（高级） | V:198 |
| ☐ PS22 | 饮食目标 / foodCalorieGoal | 整数S；2000；0…4000；步50 | 无（高级） | V:199 |
| ☐ PS23 | 运动分钟 / exerciseMinutes | 整数S；35；0…300 | 无（高级） | V:200 |
| ☐ PS24 | 运动目标分钟 / exerciseGoalMinutes | 整数S；60；0…600 | 无（高级） | V:201 |
| ☐ PS25 | 站立小时 / standHours | 整数S；7；0…24 | 无（高级） | V:202 |
| ☐ PS26 | 站立目标 / standGoal | 整数S；12；1…24 | 无（高级） | V:203 |

#### 睡眠

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS27 | 有睡眠数据 / sleepHasData | SW；true | 有：E:87 | V:208 |
| ☐ PS28 | 睡眠分钟 / sleepAsleepMin | 整数S；444；0…720；步5 | 部分：E:87有滑杆，未实现步5 | V:209 |
| ☐ PS29 | REM分钟 / sleepRemMin | 整数S；92；0…360；步5 | 无（高级） | V:210 |
| ☐ PS30 | 浅睡分钟 / sleepLightMin | 整数S；251；0…600；步5 | 无（高级） | V:211 |
| ☐ PS31 | 深睡分钟 / sleepDeepMin | 整数S；101；0…360；步5 | 无（高级） | V:212 |
| ☐ PS32 | 清醒分钟 / sleepAwakeMin | 整数S；18；0…180；步5 | 无（高级） | V:213 |
| ☐ PS33 | 入睡时间 / sleepBedtime | TimeRow/24小时TimePicker；23:41；00:00…23:59 | 部分：E:87仅文本，无TimePicker | V:214、623–651 |
| ☐ PS34 | 起床时间 / sleepWakeTime | TimeRow/TimePicker；07:05；00:00…23:59 | 部分：E:87仅文本 | V:215、623–651 |

#### 天气

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS35 | 当前温度 / temp | 整数S；22；−30…50 | 有：E:82；整数提交需验 | V:249 |
| ☐ PS36 | 最高温 / tempHigh | 整数S；25；−30…50 | 无（高级） | V:250 |
| ☐ PS37 | 最低温 / tempLow | 整数S；15；−40…45 | 无（高级） | V:251 |
| ☐ PS38 | 华氏开关 / tempUnit | SW；C；开F/关C | 无（高级） | V:252 |
| ☐ PS39 | 湿度 / humidity | 整数S；65；0…100 | 有：E:82；整数提交需验 | V:253 |
| ☐ PS40 | 云量 / cloudCover | 整数S；40；0…100 | 无（高级） | V:254 |
| ☐ PS41 | 风速 / windSpeed | 整数S；12；0…60 | 无（高级） | V:255 |
| ☐ PS42 | 风向 / windDir | 整数S；225°；0…359 | 无（高级） | V:256 |
| ☐ PS43 | 气压 / pressure | 整数S；1013hPa；950…1050 | 无（高级） | V:257 |
| ☐ PS44 | 降水 / precipitation | 整数S；2；0…100 | 无（高级） | V:258 |
| ☐ PS45 | 月相 / moonPhase | 整数S；5；0…9 | 无（高级） | V:259 |
| ☐ PS46 | 天气状况 / conditionText | D；Partly Cloudy；15个选项见下 | 部分：E:83为自由文本，没有选项列表 | V:260–265、410–426 |
| ☐ PS47 | 天气图标 / conditionIcon | D；02d；18个昼夜图标码见下 | 无（高级） | V:266–270、428–447 |
| ☐ PS48 | 地点 / weatherLocation | T；London | 有：E:84 | V:272 |
| ☐ PS49 | 日出 / sunrise | TimeRow/TimePicker；06:30；00:00…23:59 | 无（高级） | V:273、623–651 |
| ☐ PS50 | 日落 / sunset | TimeRow/TimePicker；18:45；00:00…23:59 | 无（高级） | V:274、623–651 |

#### 传感器与GPS

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS51 | 指南针 / compassHeading | 整数S；45°；0…359 | 无（高级） | V:284 |
| ☐ PS52 | 加速度X/Y / acceleration.first / second | 双轴圆形Joystick；0/9.8；各−20…20（受圆形拖动区域约束） | 无（高级Triple文本不是Joystick） | V:285–291、661–725 |
| ☐ PS53 | 加速度Z / acceleration.third | 浮点S；0；−20…20 | 无（高级） | V:292 |
| ☐ PS54 | 陀螺仪X/Y / gyro.first / second | 双轴Joystick；0/0；各−20…20 | 无（高级） | V:293–299、661–725 |
| ☐ PS55 | 陀螺仪Z / gyro.third | 浮点S；0；−20…20 | 无（高级） | V:300 |
| ☐ PS56 | 世界地图选点 / latitude / longitude | WorldMapPicker；初始51.5074/−0.1278；联动两个坐标 | 无 | V:303–309 |
| ☐ PS57 | 纬度 / latitude | 浮点S；51.5074；−90…90 | 无（高级） | V:310 |
| ☐ PS58 | 经度 / longitude | 浮点S；−0.1278；−180…180 | 无（高级） | V:311 |
| ☐ PS59 | 海拔 / altitudeM | 整数S；32m；−500…9000；步10 | 无（高级） | V:312 |

#### 连接

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS60 | Wi-Fi连接 / wifiConnected | SW；true | 有：E:86 | V:322 |
| ☐ PS61 | Wi-Fi信号格数 / wifiSignalBars | 整数S；3；0…4 | 无（高级） | V:323 |
| ☐ PS62 | Wi-Fi IP / wifiIp | T；192.168.1.1 | 无（高级） | V:324 |
| ☐ PS63 | 蜂窝连接 / cellularConnected | SW；false | 无（高级） | V:325 |
| ☐ PS64 | 蜂窝信号格数 / cellularSignalBars | 整数S；2；0…4 | 无（高级） | V:326 |
| ☐ PS65 | 蓝牙 / bluetoothEnabled | SW；false | 有：E:86 | V:327 |
| ☐ PS66 | GPS开关 / gpsEnabled | SW；true | 有：E:86 | V:328 |

#### 主题色

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS67 | 强调色1 / accentColor1 | T；2196F3 | 无专用单色编辑；主题选择不能替代 | V:333 |
| ☐ PS68 | 强调色2 / accentColor2 | T；FF5722 | 无（高级） | V:334 |
| ☐ PS69 | 强调色3 / accentColor3 | T；4CAF50 | 无（高级） | V:335 |

#### 设备模式与通知

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS70 | 通知数 / notificationCount | 整数S；3；0…20 | 无（高级） | V:345 |
| ☐ PS71 | 通知应用 / notificationAppName | T；Messages | 无（高级） | V:346 |
| ☐ PS72 | 通知标题 / notificationTitle | T；Alice | 无（高级） | V:347 |
| ☐ PS73 | 通知内容 / notificationContent | T；Hey, are you free? | 无（高级） | V:348 |
| ☐ PS74 | 音量 / volume | 整数S；8；0…15 | 无（高级） | V:349 |
| ☐ PS75 | 屏幕亮度 / screenBrightness | 整数S；128；0…255 | 无（高级） | V:350 |
| ☐ PS76 | 24小时制 / is24hMode | SW；false | 有：E:86 | V:351 |
| ☐ PS77 | 深色模式 / darkMode | SW；false | 无专用模拟开关；应用主题切换不等价 | V:352 |
| ☐ PS78 | 低电量模式 / lowPower | SW；false | 有：E:86 | V:353 |
| ☐ PS79 | 已设置闹钟 / alarmTime | SW；默认07:30（开）；关=null，开固定07:30 | 无（高级） | V:354 |

#### 设备信息

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS80 | 手表名 / watchName | T；My Watch | 无（高级） | V:359 |
| ☐ PS81 | 设备名 / deviceName | T；My Phone | 无（高级） | V:360 |
| ☐ PS82 | 型号 / deviceModel | T；Pixel 8 | 无（高级） | V:361 |
| ☐ PS83 | 制造商 / deviceManufacturer | T；Google | 无（高级） | V:362 |
| ☐ PS84 | OS版本 / osVersion | T；15 | 无（高级） | V:363 |
| ☐ PS85 | 语言代码 / langCode | T；en | 无（高级） | V:364 |
| ☐ PS86 | 语言地区 / langRegion | T；US | 无（高级） | V:365 |
| ☐ PS87 | 语言全名 / langFull | T；English | 无（高级） | V:366 |
| ☐ PS88 | 上次重启 / lastReboot | T；2024-01-15 08:30 | 无（高级） | V:367 |
| ☐ PS89 | 圆屏数据标签 / isRound | SW；true | 无专用状态控件；设备视口形状只裁画面，不等同此标签 | V:368 |
| ☐ PS90 | 缺口/平底屏标签 / hasCutout | SW；false | 无（高级） | V:369 |
| ☐ PS91 | 低位深息屏标签 / isDimLoBit | SW；false | 无（高级） | V:370 |

#### 内存与存储

| 验收 ID | 控件 / 状态字段（XML：无） | 手机版控件、默认 / 范围 | 桌面一等控件 | 真源 |
|---|---|---|---|---|
| ☐ PS92 | 已用RAM / usedRamGb | S；3.2GB；0…totalRamGb | 无（高级） | V:396 |
| ☐ PS93 | 总RAM / totalRamGb | S；8GB；1…32 | 无（高级） | V:397 |
| ☐ PS94 | 已用存储 / usedStorageGb | S；48GB；0…totalStorageGb | 无（高级） | V:398 |
| ☐ PS95 | 总存储 / totalStorageGb | S；128GB；8…1024 | 无（高级） | V:399 |

天气状况的15个选项为 Clear、Partly Cloudy、Cloudy、Overcast、Showers、Rain、Thunderstorm、Snow、Fog、Light Rain、Heavy Rain、Light Snow、Heavy Snow、Hail、Windy（V:410–426）。图标选项为01/02/03/04/09/10/11/13/50分别加d或n（V:428–447）。

整数滑杆的提交需要特别回归：手机版 V:565–583 会按 stepPx 取整；桌面 C:53–72 的通用滑杆输出最多两位小数，而 E:21–28 的 Int 字段通过 toIntOrNull 接收。控件“有”只说明入口存在，未确认任意拖动位置都能成功提交，不能跳过这项验收。

## 10. 不应混淆的现有能力与缺口

1. **有 renderer 不等于有控件。** shader、outline、stencil、阴影、弧形文字、刻度、条件图片、图表等即使已能渲染，只要作者必须查文档手填原始键，就仍是上述“无”。
2. **表盘背景与设备形状不是预览状态。** root.bg_color/root.shape 的编辑要入历史和归档；设备 viewport、时间/传感器模拟不能写入归档或使文档变脏。
3. **派生缩略图不是截图按钮。** save 时生成 preview.jpg/preview_dim.jpg 和独立运行库验证导图均不满足 G11；GIF播放不满足 G12。
4. **播放音效不是音频管理器。** 当前 MediaPlayback.sound 与动作测试证明执行端可用；AU01–AU05仍要求用户可发现的管理入口。
5. **全局脚本输入不等于完整脚本编辑器。** D的多行字段覆盖基本写入，GS01–GS07的草稿、行号、换行、检查和关闭确认须单独勾选。
6. **单GIF导入不等于image_gif帧编辑器。** 上游可添加/删除/重排反引号帧序列并设置gif_delay；不能只放通用path输入框。
7. **高级模拟字段不是预览页已迁移。** E的反射表保留了一些手动可达性，但不替代开关、时间选择器、Joystick、地图、实时源切换。
8. **不扩展本次用户范围。** 非WatchMaker ID/尺寸、HSC动画器、在线下载器、受保护pxml导出不应因“手机版也有”而自动加入实现。
9. **保留上游实际条件。** outline参数仅outline非空时出现；shader参数仅shader非空时出现；chart分别切换line/bar参数；text效果不自动套给text_ring、series、calendar。
10. **手机间距不是把控件做大就结束。** 上游GlobalTab为16dp内边距/16dp间距（G:38–43），PreviewTab为16dp/14dp（V:52–57）；WmParamCard的分组语义、fx、色块、图标和锚点网格必须保留。桌面允许键鼠优化，但应保持舒适排列和清楚分组。

## 11. 实现顺序与逐项验收

下面只整理本表缺口，不新增未在真源出现的功能。

- [ ] 第一批：P09–P14、T05–T12、FX01–FX09、SH00–SH33。先补齐通用变换、完整文字/图形效果与shader条件参数。
- [ ] 第二批：GIF01–GIF08、CI01–CI04、PR01–PR11、RI01–RI10及MK/HM/TR/SE/CA/CH。专用参数卡按图层类型分派，不能继续堆到原始属性。
- [ ] 第三批：GS01–GS07、AU01–AU05、G11–G12。明确专用脚本/音频/截图/录制的入口和暂存语义。
- [ ] 第四批：PV03/PV05–PV19与PS表的所有“部分/无”。手动模拟、实时来源及控制组件分别验收；实时手表/手机数据不能用PC数据冒充。

每个字段勾选前，至少满足：

- [ ] 能在对应图层/页面直接找到；标签、单位、提示、选项值与真源一致，或桌面差异有明确理由。
- [ ] 默认空值、缺少属性、表达式、极值均不会被控件初始化自动覆写。
- [ ] 数字拖动、键盘输入、fx编辑都写正确XML键；整数与步长正确；一次手势一条撤销记录。
- [ ] 多选编辑明确“共同绝对值”和“相对增量”；取消弹窗/撤销可恢复原始表达式。
- [ ] 颜色保存6位RGB的兼容性、shader种入默认值、outline/shadow启用默认值、条件参数显隐均有回归。
- [ ] 资源选择保证目录与类型正确；FNT/atlas配套；GIF帧顺序；视频格式差异；音效不会留下悬空引用。
- [ ] 预览控件只改会话/偏好，归档、文档签名与撤销不变。
- [ ] 通过桌面实际控件操作与像素/截图验证后，再把“☐”改为“☑”；编译通过、原始字段可手填或renderer能显示都不足以打勾。
