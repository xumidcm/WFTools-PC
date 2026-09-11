# WFTools-PC

## Register
product

## Users and purpose
柚子皮老师及熟悉手机版 WFTools 的表盘作者，在 Windows 电脑上用键盘和鼠标编辑 WatchMaker `.watch` 文件。工作区需要支持精确修改、完整预览、多文件并行编辑和可靠保存。当前范围只有 Windows 和 `.watch`，不包括 WFF/HSC 转换、市场、AI 或 Android 构建。

## Confirmed design brief
用户于 2026-09-11 明确批准当前实施计划：手机版 Material 3 紫色风格、紧凑键鼠控件、响应式面板、Shift 范围多选、文件和素材拖放、单文档多窗口。设备视口遵循 Flue 2f3a1dfb 的几何规则，独立于文档设计空间和导出尺寸。Ctrl+S 保存原文件。文件关联仅注册 `.watch` 打开方式。

## Product principles
简单、准确、高效。常用操作直接可达，高级内容逐步展开。保留未知格式数据，失败不得损坏原文件。每个窗口隔离撤销、脚本、播放器和草稿。用户样本保持本地且原件只读。

## Visual reference and anti-references
沿用 WFTools 的 Material 3 色彩与控件语义；参考 Watch Face Studio 的分区属性和工具栏、Facer 的图层与时间模拟。避免手机界面整体放大、装饰性仪表盘、过大卡片、依赖长按和用缩小字体解决窄窗口。

## Accessibility
Windows 系统字体、中文回退、清晰焦点与悬停反馈、键盘可操作、100–200% DPI、明暗主题。文本输入框保留自身编辑快捷键。
