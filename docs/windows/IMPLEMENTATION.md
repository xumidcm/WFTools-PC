# Windows implementation status

Approved scope: Windows x64 `.watch` editor, full upstream preview, mobile Material 3 style, precise keyboard/mouse workflow, per-document windows, Flue device viewport, reliable direct save, portable ZIP and MSI.

Baseline: WFTools 201cde43171b3481cd4ed2ed1e73b20e3daa7400. Flue geometry 2f3a1dfb9050d6a2fd1fafafb00575d0eccb283a. Sample remains outside repository; SHA256 CBD60DC582F1BF7216D6A33D91FDE5BF9DD3D9A6F9259114819FD162E511DD7F.

- [x] Desktop build and responsive Material 3 workspace implementation
- [x] Lossless watch read/write and session isolation
- [x] Flue viewport contract and migrated preview/media implementation
- [x] Keyboard, selection, context menu and drop command implementation
- [x] Desktop Windows/Linux i18n with Chinese/English resources and persisted language choice
- [ ] Single instance routing, crash recovery, packaging and Open With
- [ ] Sample, synthetic cases, UI/DPI/performance and installer verification

No completion is inferred from compilation alone. Track remaining limits and concrete verification evidence here.

## Current evidence

- Private GitHub repository created; branch `codex/windows-watch`, foundation commit `b0f3ace`.
- Approved dependency location changed by user to `D:\DevTools\WFTools-PC`; build.ps1 configures Gradle accordingly and reads old cache without writing it.
- User explicitly authorized system PATH changes. Gradle, WiX and VLC tool directories appended; former machine PATH backed up under the dependency directory.
- LibVLC archive verified against official SHA256: 992D19DBD0B8A7CDE9167D2F7780B1EF6F92ACC8A71ACFA736101A21F35181E1.
- Five viewport JUnit tests passed after first recording five missing-implementation failures. Tests cover four presets, design-aspect separation, inverse transform across DPI and invalid runtime values.
- Initial core suite: 39 passed including copied 312.watch. Spec review subsequently found video import, script recovery/case handling and cross-document expression isolation gaps; fixes and expanded regression suite are in progress. This is not final acceptance.
- Renderer source extracted from upstream without modifying app/. Desktop graphics and media adapters are being compiled/integrated; no visual correctness claim yet.

## 当前阶段（2026-09-12，代码与便携交付验收完成）

- 核心阶段提交 `b91da39` 已推送；后续剪贴板、渲染器和桌面界面改动仍保留在当前工作区，尚未创建新的提交。
- 60 项核心测试通过后，新增跨文档素材剪贴包和系统地区格式回归；当前已执行 66 项全部通过。复制使用持久化资源包，不把大视频放进系统剪贴板文本。替换素材先校验属性和目标类型。
- 图层渲染已真实生成主样本图像，377 层解析完整。修复 Skia 桌面文字不继承 ModulateAlpha 的问题，文字 25% 透明度像素回归通过，LCD 底纹恢复正常。
- GIF 双帧及静态 WebP、中文字体回退、按字节计费的图像缓存、Flue 裁切/逆变换、Lua 重载和会话隔离均有回归。实际 LibVLC 首帧用例从约 66 秒降至约 0.61 秒，修复原因是禁止 vlcj 重复自动扫描全机安装目录。
- 首版视觉被用户否定，已重新对照手机版源码迁移图标操作栏、缩略图卡片、ElevatedCard 属性组、slider/±/fx、色块、九宫格对齐和中文折叠模拟区。用户随后明确要求舒适间距，已提高输入和图层卡片高度。
- 左侧文字缩略图移植 Flue 的独立渲染和 alpha 边界裁切算法；真实字体、位图字体、表达式结果均可见，不继承画布位置/旋转/隐藏状态。已生成 1280×800 的主样本界面供查看。
- 最新串行联合测试：核心、渲染、桌面共 253 项，0 failures、0 errors、5 个按需跳过；覆盖真实 Compose 文本焦点、画布微移、Shift 范围、多选右键、文件冲突、FIFO 与单进程转交。截图/GIF 导出、受保护归档导出、脚本草稿和多选相对滑块回归也已通过。
- 离屏性能参考：主样本打开约 549ms；CPU Skia 离屏帧中位约 29.4ms、P95 约 82.8ms。该数据不是实际显示器/GPU帧率，不能作为物理显示或输入观感的替代证明。设备切换未更换 Lua，文档未产生修改。
- 最终便携 ZIP `WFTools-PC-0.1.0-windows-x64-portable.zip` SHA-256 为 `67E9CDA17998958549E909C8A7DE39EDA5AF6FEE92DF15F328BCBCF7E27C0BCA`，MSI `WFTools-PC-0.1.0-windows-x64.msi` SHA-256 为 `D22EB7487AD931F75A603883C863D5A6D3F3BE37057126305679C6D96841DA73`；来源哈希、PE x64、WiX XSD 和 MSI 数据库校验通过，MSI 为当前用户安装、2 个快捷方式、仅 OpenWith 候选且未改默认关联。实际安装/卸载尚未执行。
- 从最终 ZIP 隔离解压后清除开发环境 Java/VLC 路径，包内 Java 21 成功解析 377 层样本并生成两个缩略图，源文件 SHA-256 前后一致；包内 LibVLC `3.0.23` 实际加载路径为解压目录内的 `app/resources/vlc/libvlc.dll`，视频首帧约 451 ms，声音调用成功。物理扬声器输出仍未验证。
- 主样本原件仍保持 SHA256 `CBD60DC582F1BF7216D6A33D91FDE5BF9DD3D9A6F9259114819FD162E511DD7F`，所有测试使用副本，样本和截图不提交 Git。

下一步：对当前工作区做最终代码审查和窄窗口人工视觉复核；若要验证 MSI 安装、升级、卸载及真实“打开方式”注册，需要在可回退的 Windows 用户环境中逐案确认后执行。当前不把静态 MSI 表检查当成安装行为证明。

## 2026-09-13：同步 Flue Agent 实时编辑框架

- 以 Flue `53ef091c` 为迁移输入，Flue 工作区保持只读。桌面端同步了工具参数包装兼容、成功写入保留、命令后的 `face/` 自动回写、完整资源镜像、会话上下文压缩和 token 记录。
- 表盘 XML、全局脚本与资源变更共同进入一个 Agent 撤销点；镜像未变化时不制造草稿版本，镜像过期时不覆盖用户的新编辑。全局脚本现在校验编译、顶层执行及首次时钟回调；普通素材删除仍保护 `scripts/script.txt`。
- 右栏显示会话 token 与手动压缩入口；流式消息转为历史消息时在一个 Compose 快照内更新，自动跟随长消息底部。
- Windows 原生四模块测试共 375 项：370 通过、5 项按需跳过、0 失败。桌面 UI 全套在一次 30 秒时序超时后，单项与全套重跑均通过。尚未把构建、打包结果等同于真实在线 Provider 或人工输入验收。
- Windows `createDistributable` 与 Linux x64 隔离 `createDistributable` 均成功。Windows 便携 ZIP SHA-256 `96609F8A851284F2A6C58127FADB235E553333F2805E18D135177336D4886D20`，MSI SHA-256 `04BEB20499864F5E7008B92483805704AC67B2D9467B7866F90DCE6678459BBD`；Linux 便携 tar.gz SHA-256 `5F3789D17980C488CC49D57410F4E55A79EF4FB372F77523C700333D819E1161`，DEB SHA-256 `C2AA8C028C664689D6D5D59072CDB27BE2B3EB373A871658E97174810B4B504B`。两个平台的包内均确认包含新 `agent-core`；Windows MSI 只做静态验证，未实际安装。

## 2026-09-13：画布直拖语义、侧栏跟手、原生选择器与表盘市场

- **直拖语义变更（用户批准）**：画布与图层列表共用选区，已有选中时选区独占拖动。此前压在其它图层上按下会改拖那个图层，导致侧边栏选中的底层视频图层无法移动。现改为：有选中→拖动始终移动当前选区（含空白处按下）；无选中→直拖按住即选中并移动，先选后拖只选中；单击其它图层改选。`canvasPressAdoptsHit` 是这条规则的单点判据。
- **侧栏分隔条跟手（行为改动已合入，但未被测试证实）**：原实现用 `detectDragGestures` 的 `dragAmount`，该值在分隔条自身坐标系下测量；分隔条随面板移动，理论上会与指针行程相互抵消。改为在窗口坐标（`LayoutCoordinates.localToWindow`）累计，并换用 `awaitEachGesture` 以免除 `detectDragGestures` 的 `touchSlop` 起始死区。`splitterDragDelta` 抽出为纯函数并有单测。
  **重要限制（勿当作已验证）**：离屏 Compose 测试无法区分两种实现。用根绝对坐标打点探测，旧实现得到 `[0.875, 1.0, 1.0, 27.0]`、新实现得到 `[1.0, 1.0, 1.0, 27.0]`，几乎一致——测试框架在每次指针事件之间结算布局，抵消反馈回路无法形成。因此新增的 splitter 用例只覆盖「面板按指针总行程移动、不会卡死或严重缩放」这一粗粒度行为，**它不是用户所报「阻力大、要拖半个屏幕」的复现**。根因解释目前属于推断，必须在真实窗口上人工复核后才能确认或推翻；若人工复核显示无改善，应回退这次改动。
- **Windows 通用项对话框**：`DesktopFiles.kt` 改用 `IFileOpenDialog`/`IFileSaveDialog`（JNA 直调 COM 虚表，不引额外依赖，版本与 vlcj 传递的 `jna-jpms 5.16.0` 对齐）。旧 AWT `FileDialog` 在 Windows 上映射到 `GetOpenFileName`，这是图标显示为 Java 默认咖啡杯、`.watch` 过滤器失效、标题参数被忽略的原因。任何创建/调用失败都回落到原 AWT 路径。Linux 与 macOS 保留系统原生对话框。
- **表盘市场**：只接入 Watchmaker Community v2（实测 `GET /watchfaces` 返回 200，字段含 `category.name`、`pagination.total`、`uploader.username`）。独立窗口支持搜索、排序、屏幕类型与分类筛选、翻页；右上角「在浏览器打开」跳网页版；下载落在 `appData/market/`，随后弹窗按平台能力给出打开编辑 / 在文件管理器中显示 / 另存为… / 复制路径。按用户要求**不判断受保护表盘**，由现有加载器在打开时报 `UnsupportedProtection`。HTTP 用 `HttpURLConnection` + Gson，与既有 Agent 外部工具一致，不引入 OkHttp/Coil。
- **上一轮遗留修复**：搜索条数输入框改为受控并失焦归一化（此前输入 `99` 显示 99 但静默生效 20）；i18n 键 `快速`/`深度` 与 Agent 面板的思考强度下拉冲突，改用 `快速检索`/`深度检索`；`WatchMarket.kt` 曾显式导入 `material3.Text` 从而绕过项目 i18n 层；clamp 常量统一到 `SearchResultCount`。
- **测试**：新增 `SearchSettingsTest`（搜索设置与平台动作）、`NativeFileDialogTest`、`WatchMarketApiTest`（19 项，假 transport 不发网络）、`WatchMarketWindowTest`。直拖新契约的三条断言已实测在旧实现下失败（改回旧 `canvasDragTargets` 后 3 failed），确认为真实回归护栏。`NativeFileDialogTest` 里的 `verifyOptionRoundTrip` 在真实 `IFileDialog` 上做 `SetOptions`/`GetOptions` 往返，用于验证编译器无法检查的 COM 虚表槽位。`clean test` 全量：**440 项，0 失败，5 项按需跳过**。`git diff --check` 干净。
- **顺带修掉的两个 i18n 缺陷**：`market` 子包内 `import androidx.compose.material3.*` 会盖过项目同名的本地化 `Text`，英文界面下市场窗口整屏中文——已改为显式导入 `com.ailife.wftools.pc.Text`，并有 UI 测试锁定；市场屏幕类型筛选原本复用「圆形/方形/矩形」，与属性面板的形状名共用同一批 i18n 键（英文含义不同），已改为「圆屏/方屏/矩形屏」。
## 2026-09-13（续）：Windows 触屏适配

- **共享判据**：新增 `watch-renderer/.../pc/input/TouchInput.kt`，集中放触屏常量与纯函数（`TouchTargets`、`isFromTouch`/`isTouchOnly`、`touchTargetDp`、`splitterThicknessDp`、`isLongPress`）。各处适配都从这里取判据，避免魔法数字散落。
- **画布手势**（`WatchPreview.kt` / `CanvasEditing.kt`）：触屏没有右键、中键与悬停。长按（500ms、位移不超过 `touchHoldSlopDp`）是上下文菜单的唯一入口，复用既有 `openContextMenu` 回调；长按成立后这一手势转为画布平移，抬手时若手指没有再移动才弹菜单。长按完成不会记录点按时间戳，因此不会与双击文字编辑互相吞掉。控制点命中半径触屏放宽到 44dp 目标（`transformHandleHitRadiusDp`），双击落点容差同理放宽；触摸移动**不再伪造悬停**（`hoveredHandle` 只由鼠标设置）。长按判定的抖动容差、双击窗口与命中探针都抽成可单测的纯函数。桌面 Compose 的多指支持参差，因此**只用单指长按**，不依赖双指手势。
- **列表与素材面板**（`LayerBrowser.kt` / `AssetBrowser.kt`）：触摸长按呼出右键菜单，位移超过 `touchSlop` 即转为既有的拖动排序；触屏下图层编辑按钮不再依赖悬停而常显，命中区按 `touchTargetDp` 放大（外层 Box 尺寸同步，否则约束会把按钮压回 28dp）；滚动条加宽。素材面板修补了一个新引入的副作用：长按弹菜单后抬手不再继续执行「应用到选中图层」。
- **分隔条与控件**（`EditorWorkspace.kt` / `EditorWidgets.kt`）：分隔条在触摸按压后加宽到 28dp（Java AWT 没有可靠的「本机有无触摸屏」查询，因此按事件判断而非平台查询）；多行输入框右下角拉杆在触屏下放大，并且触摸时**不再用 `MouseInfo` 采样屏幕坐标**（手指按住时鼠标可能停在别处，采到的是鼠标而不是手指），只用局部位移。
- **测试**：新增 `TouchInputTest`，并在 `CanvasEditingTest` 增加触屏手势契约（长按/平移/双击仲裁、与 `isLongPress` 的一致性、命中半径与探针）。`clean test` 全量：**455 项，0 失败，5 项按需跳过**；`git diff --check` 干净。
- **未验证项（重要）**：本机没有触摸屏，**所有触屏行为均未在真实触摸设备上验证**，只验证了纯函数与编译。以下属于推断，需在带触摸屏的 Windows 设备上逐项人工复核：
  1. Compose Desktop 在 Windows 上是否真的把触摸上报为 `PointerType.Touch`。若 Skiko/AWT 把触摸合成为鼠标事件（JetBrains 运行时存在相关已知问题），则 `isTouchOnly()` 恒为 false，全部触屏分支都不会触发——此时需要改走 AWT 层的触摸识别，属新的工作量。
  2. `withTimeoutOrNull` 取消 `awaitPointerEvent` 后，按下、移动、抬起事件是否仍完整送达同一手势（长按后继续拖动是否顺畅）。
  3. 真机上的长按时长与抖动容差是否合适（当前 500ms / 11dp）。
  4. 手指操作下控制点 44dp 命中半径是否过大（小图层上相邻控制点会相邻竞争，按最近者取胜）。

## 2026-09-13（续二）：同步上游 V6.2 的视频旋转修复

上游新增 5 个提交（`201cde4..941a5e1`，标签 `V6.2`），全部落在 Android `app/` 模块。逐条评估后**只有 1 个需要在桌面端落地**：

- `29f21a4`（Momoi 更名 sawaaa）——桌面端四个模块 grep `momoi|sawaaa|sawamarket` 命中 0 处，桌面市场是独立实现的 Watchmaker Community v2，无对应物。
- `92dcd43`（截图抓到工具栏）——根因是 PixelCopy 抓窗口表面；桌面端截图走 `ImageComposeScene` 离屏渲染，与窗口位置无关。
- `3ad686c`（保存静默失败 / 慢速存储导出）——桌面端已有更强保障：`task`/`exportCapture`/`fileExport` 的 catch 全部写 `doc.error` 并由 `ErrorBanner` 显示，`WatchDocumentStore.save` 失败一律抛异常，`mutex` + `busy` + `reservedSaveIdentity` 已阻止并发与跨窗口同路径写入，导出本就是临时文件 + `ATOMIC_MOVE`。`watchdog 兜底` 与 `FUSE 小写入` 是 Android 专有形态。
- `941a5e1`（Android 版号 6.2）——桌面端独立版本号。
- **`ece0bd5`（视频图层旋转 90°）——已落地**：编辑器播放器应用 MP4 旋转元数据而手表播放器忽略，导致同一文件「编辑器里立着、手表上躺着」。桌面端用 LibVLC（同样会应用旋转），且 `VideoImportPreparer` 对已是 H.264 的文件直接放行、不做任何旋转处理；手机竖拍视频绝大多数正是 H.264 + rotation=90/270，会原样进项目并原样导出，因此**桌面端存在同构缺陷**。

落地方式（`watch-renderer/.../render/Mp4Rotation.kt` + `desktopApp/.../VideoImportPreparer.kt`）：在导入阶段读取视频轨 `tkhd` 矩阵里的旋转角，对带旋转的 H.264 源**只改写那 36 字节矩阵**、文件其余部分逐字节复制（含整个 `mdat`），**不重编码、不重新打包、不依赖 ffmpeg**。选择自己解析而非调用外部工具，是因为本机实测发现随包 VLC 的转码链路在当前环境不可用（详见下条），而矩阵改写是纯字节操作，可靠且可离线验证。没有旋转标记的源文件原样放行；改写失败回退原文件，绝不产出半成品。

验证与诚实标注：
- `Mp4RotationTest`（9 项）覆盖 0/90/180/270、无旋转、逐字节「只改矩阵」、畸形盒子（截断/空文件/伪 MP4/超大 size）、纯函数与文件路径一致、缺失文件不崩。
- 旋转角符号约定**用真实 ffmpeg 产物交叉验证**：`ffmpeg -display_rotation {90,180,270}` 生成的文件经 `ffprobe` 读出 0/90/180/270，本实现读数与之逐一相符。自造矩阵夹具一度把 90/270 写反（符号搞反会把两者对调），是这条真实文件对照把它抓出来的；该对照探针含故意的断言失败，验证完已删除，未留在仓库。
- **未实测**：LibVLC 在桌面端 vmem 回调路径上是否真的把旋转应用到了像素。依据是 OBS/libvlc 社区证据与 VLC 既有行为（上游 ExoPlayer 亦为应用方），属高置信推断。即便 LibVLC 不应用旋转，本次改动也只是把提示去掉、让播放器与手表一致，不引入新的不一致。真机复核方式：导入一段手机竖拍 H.264 视频，对比编辑器画面与导出文件在手表上的方向。
- 顺带发现（**既有问题，非本次引入**）：本机随包 VLC 的 `codecName` 探测返回空、转码报「输出编码不是 H.264」。用改动前构建的便携版做 A/B，两者失败位置完全一致，故与本轮改动无关；`--verify-runtime` 的视频分支在当前环境跑不通。

## 其余未验证项


- `IFileOpenDialog` 的真实人工交互（外观、多选、覆盖确认只问一次）与市场真实联网端到端流程，均需在带显示器的 Windows 会话中逐项人工确认。市场接口是第三方服务且无版本契约，字段解析全部按可选处理。
- `EditorRedContractsTest > layer drag keeps its stable source id…` 在多次全套运行中出现过两次失败（伴随 Gradle daemon 被外部停止），随后单独重跑 3 次与完整 `clean test` 多次均通过。判定为既有的时序敏感用例；本次未改动 `LayerBrowser.kt` 的排序逻辑（只加了触摸长按分支）。
