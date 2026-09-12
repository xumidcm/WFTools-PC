# Windows implementation status

Approved scope: Windows x64 `.watch` editor, full upstream preview, mobile Material 3 style, precise keyboard/mouse workflow, per-document windows, Flue device viewport, reliable direct save, portable ZIP and MSI.

Baseline: WFTools 201cde43171b3481cd4ed2ed1e73b20e3daa7400. Flue geometry 2f3a1dfb9050d6a2fd1fafafb00575d0eccb283a. Sample remains outside repository; SHA256 CBD60DC582F1BF7216D6A33D91FDE5BF9DD3D9A6F9259114819FD162E511DD7F.

- [x] Desktop build and responsive Material 3 workspace implementation
- [x] Lossless watch read/write and session isolation
- [x] Flue viewport contract and migrated preview/media implementation
- [x] Keyboard, selection, context menu and drop command implementation
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
