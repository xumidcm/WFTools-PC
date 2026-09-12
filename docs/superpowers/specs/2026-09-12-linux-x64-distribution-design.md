# Linux x64 发行设计

## 目标

为 WFTools-PC 增加 Ubuntu/Debian x64 发行链，在保持 Windows MSI 行为不变的前提下交付：

- Debian 安装包 `WFTools-PC-0.1.4-linux-x64.deb`；
- 便携应用归档 `WFTools-PC-0.1.4-linux-x64-portable.tar.gz`；
- 对应的打包清单和 SHA-256 校验文件。

首个已验证环境为 WSL2 Ubuntu 26.04 x64。Debian 系发行版仍需满足安装包声明的本机媒体依赖；本次不宣称已在未实际运行的发行版上验证。

## 平台边界

保留共享 JVM/Compose Desktop 编辑器、渲染器和文档核心。Windows/Linux 差异集中到平台服务：

- 操作系统识别与界面平台名称；
- `.watch` 文件关联；
- 在文件管理器中定位素材；
- 已安装启动器路径；
- LibVLC 文件名、搜索路径和 VLC 命令行程序。

Windows 继续使用注册表、`explorer.exe`、`.dll`、`vlc.exe` 和原有固定 MSI UpgradeCode。Linux 使用 XDG MIME、`gio`/`xdg-open`、`libvlc.so` 和 `vlc`。

## Linux 文件关联

Linux 设置页保留“关联／取消关联”按钮。关联操作在当前用户范围创建或更新：

- `~/.local/share/applications/wftools-pc.desktop`；
- `~/.local/share/mime/packages/wftools-pc-watch.xml`；
- `application/x-wftools-watch` 的默认应用。

修改前记录原默认应用；取消关联时只移除 WFTools-PC 自己创建的文件，并恢复仍然存在的原默认项。所有命令参数使用列表传递，不拼接 shell 命令。

## 媒体依赖

采用已确认的方案 A：Linux 包不复制发行版的动态库，而是使用系统提供的 VLC/LibVLC 和 FFmpeg。

`.deb` 声明 VLC、LibVLC 插件与 FFmpeg 运行依赖，通过 apt 安装时自动补齐。便携 `.tar.gz` 自带精简 Java 运行时和应用文件，但启动器会检查 `libvlc.so`、`vlc`、`ffmpeg` 与 `ffprobe`，缺失时显示明确的 Ubuntu/Debian 安装命令并退出，避免运行到视频功能时才失败。

视频导入继续硬性转换为 H.264。Linux 使用系统 `vlc` 执行与 Windows 相同的转码参数，并在输出后通过 LibVLC 重新检查编码。

## 构建和打包

`desktopApp` 根据当前操作系统启用对应格式：Windows 生成 MSI，Linux 生成 DEB。两者使用各自图标和平台元数据。

新增 Bash 构建入口及 Linux 打包脚本。打包脚本：

1. 只接受 Linux x64 的 Compose `createDistributable` 输出；
2. 创建不会覆盖已有产物的独立临时目录；
3. 生成便携启动器、Linux 使用说明和 `PACKAGE-MANIFEST.json`；
4. 生成 `.tar.gz`；
5. 生成或整理 `.deb`，并校验架构、依赖、桌面入口和 MIME 声明；
6. 输出 SHA-256。

生成物保存在 `artifacts/package-<时间戳>-linux-x64`，不加入 Git。

## 环境补全

在 WSL Ubuntu 26.04 安装 JDK 21、VLC/LibVLC、FFmpeg、XDG、桌面文件校验和 Debian 打包工具。Linux 构建使用 WSL 内独立的 Gradle 用户目录，不复用 Windows 原生缓存中的平台二进制。

## 测试与验收

- 纯逻辑测试覆盖平台识别、Linux VLC 定位、Linux 文件关联文件内容和恢复规则；
- Windows 原有文件关联、媒体定位和 MSI 测试保持通过；
- Windows 原生全量测试保持通过；
- Linux 环境全量运行 `watch-core`、`watch-renderer`、`desktopApp` 测试；
- 校验 DEB 架构、版本、依赖、桌面入口、MIME、归档清单和 SHA-256；
- 在 WSLg 启动 Linux 可分发应用，确认窗口实际出现；
- 媒体物理声音、不同 Linux 发行版兼容性仍需在相应真实环境单独验证，不以 WSLg 启动代替。
