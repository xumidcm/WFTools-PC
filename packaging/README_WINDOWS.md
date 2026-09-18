# WFTools-PC Windows 版

本目录包含应用、专用 Java 运行时、LibVLC 及其插件，无需另外安装 Java 或 VLC。

完整使用说明见同目录的 [USER_GUIDE.md](USER_GUIDE.md)。

## 便携版

完整解压 ZIP，双击 `WFTools-PC.exe`。不要只复制 EXE；`app`、`runtime` 和其它随包文件必须保留。

## MSI 安装版

MSI 按当前 Windows 用户安装到 `%LOCALAPPDATA%\Programs\WFTools-PC`，创建开始菜单与桌面快捷方式。

安装仅将 WFTools-PC 加入 `.watch` 文件的“打开方式”候选；不会抢占当前默认程序，也不会增加专用的资源管理器菜单。需要时请在 Windows 的“打开方式”中自行选择 WFTools-PC。

卸载会移除安装器创建的程序文件、快捷方式和关联候选。恢复草稿位于 `%LOCALAPPDATA%\WFTools-PC\workspace`，不在程序安装目录内，卸载时保留。

## 文档与保存

每个窗口编辑一个 `.watch` 文档。`Ctrl+S` 保存当前源文件；新建文档需要先选择另存为位置。设备预览外形和尺寸只控制查看方式，不会改写表盘设计尺寸。未修改的文档保存时保持原文件字节不变。

表盘的脚本、未知 XML、未引用资源及元数据会保留。受保护的 `watch.pxml` 需要先在 WatchMaker 中导出未保护的可编辑版本。

## 校验与来源

同一发布目录的 `*-SHA256SUMS.txt` 记录 ZIP/MSI 哈希。包内 `PACKAGE-MANIFEST.json` 记录源码版本、Java 运行时、LibVLC 来源及插件缓存校验。

`licenses` 保存随包组件的许可证和声明；`runtime/legal` 保留 Java 运行时原有法律文件。上游 WFTools 当前快照未提供许可证文件，详见 `licenses/UPSTREAM-LICENSE-STATUS.md`。此包为当前项目的私有交付，不虚构上游授权。

## 打包脚本

先由维护者串行执行 Compose `createDistributable`，再运行：

```powershell
./scripts/package-windows.ps1 -AppImage './desktopApp/build/compose/binaries/main/app/WFTools-PC' -Version '0.1.6'
```

`-ValidateOnly` 只检查已有镜像、工具、来源哈希及插件缓存，不复制、编译 MSI 或运行安装器。

脚本从已校验的 VLC 3.0.23 ZIP 核对源运行库，保留文件修改时间和 `plugins/plugins.dat`。正常打包时，缓存缺失会调用便携版 `vlc-cache-gen.exe` 生成；不会安装 VLC。脚本不调用 Gradle、不运行应用或 MSI。

所有产物写入仓库 `artifacts` 下；同名发布产物默认不覆盖。可以为复验指定不同的 `-OutputDirectory` 子目录。临时目录保留用于检查 WiX 定义和日志。

## 交付验证

1. 先运行核心、渲染与桌面测试，再创建应用镜像。
2. 使用 `-ValidateOnly` 检查 x64 EXE/JVM、完整插件缓存、来源 ZIP SHA256、源 DLL 内容及许可证文件。
3. 完整打包会验证 WiX XML，执行 candle/light，并检查 MSI 数据库中的安装范围、全部 HKCU 注册表行、打开方式命令和两个快捷方式；不会安装 MSI。
4. 独立解压便携 ZIP，在去掉开发环境 Java/VLC 路径的子进程环境下检查启动、视频首帧、音频和文件打开，确认实际使用随包运行时。
5. 最终安装/升级/卸载验证需在可回退的 Windows 用户环境中人工执行，确认默认 `.watch` 关联未变及恢复草稿仍在。静态 MSI 表检查不能代替这一项。
