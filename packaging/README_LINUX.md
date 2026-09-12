# WFTools-PC Linux x64 版

构建与自动化验收环境是 Ubuntu 26.04 x64，并已在独立 X11 桌面中验证首页实际渲染。本发行版面向使用 glibc 的 Ubuntu/Debian x64 桌面环境。

## DEB 安装包

使用 apt 安装可以同时补齐 VLC、LibVLC、FFmpeg、XDG 与 MIME 依赖：

```bash
sudo apt install ./WFTools-PC-0.1.4-linux-x64.deb
```

安装后可从应用菜单启动 WFTools-PC，或运行 `wftools-pc`。安装包会注册 `.watch` 的候选 MIME 应用，但不会强制抢占现有默认应用；可在 WFTools-PC 首页设置中关联或取消关联。

## 便携包

解压整个归档后运行：

```bash
./WFTools-PC-0.1.4-linux-x64-portable/run-wftools.sh
```

便携包自带 Java 运行时，但使用发行版提供的 VLC/LibVLC、FFmpeg 和 Noto CJK 中文字体。缺少依赖时，启动器会给出 Ubuntu/Debian 安装命令。不要只复制 `app/bin/WFTools-PC`，应用目录和运行时必须完整保留。

## WSLg

当前 WSLg 的 RDP 窗口转发与 Compose/Skiko 存在兼容问题，可能只显示黑色窗口；同一程序在独立 X11 桌面能够正常渲染。WSLg 不属于本发行版的支持目标，音频、文件拖放和桌面集成请在真实 Linux 桌面环境验证。
