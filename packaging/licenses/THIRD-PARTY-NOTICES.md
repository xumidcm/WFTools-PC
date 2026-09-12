# 随包组件声明

- **VLC / LibVLC 3.0.23**：使用 VideoLAN 官方 Windows x64 ZIP，原始 `COPYING.txt` 为 GNU GPL Version 2。`VLC-COPYING-GPL-2.0.txt` 为该文件的原样副本；完整 VLC 目录中的 AUTHORS、README、COPYING 等文件也保留。来源：<https://download.videolan.org/pub/videolan/vlc/3.0.23/win64/vlc-3.0.23-win64.zip>。此包包含整个所选 VLC 运行库，不将全部插件笼统标注为 LGPL。
- **vlcj 4.12.1**：该版本 Maven POM 声明 GPL v3，原始 POM 保存在本目录。`GPL-3.0.txt` 来自 GNU 官方版本文本 <https://www.gnu.org/licenses/gpl-3.0.txt>。项目：<https://github.com/caprica/vlcj>。
- **Java**：专用运行时来自 Compose/jpackage 生成的输入镜像；其 `runtime/legal` 与 `runtime/release` 完整保留，实际版本写入包清单。
- **Roboto-Regular.ttf**：继承自上游的默认表盘字体。已读取字体内部 name 表：`Font data copyright Google 2014`，授权字段为 Apache License 2.0，链接为 <https://www.apache.org/licenses/LICENSE-2.0>；全文随 `Roboto-Apache-2.0.txt` 提供。用户表盘内的独立字体不会进入程序分发包。
- **其它 JVM 依赖**：打包时从输入镜像内每个 JAR 提取现有的 LICENSE、LICENCE、COPYING、NOTICE 及 Maven POM，存放在 `licenses/jars`；JAR 本体内的文件也保留。提取清单写入 `PACKAGE-MANIFEST.json`。未发现内置声明的 JAR 会记录名称，而不会猜测其授权。
- **WiX**：仅用于生成 MSI，工具本体不随应用安装。其实际 `LICENSE.TXT` 原样保存在 `WiX-LICENSE.txt`。

上游 WFTools 的授权核验状态见 `UPSTREAM-LICENSE-STATUS.md`。本声明记录来源与现有文本，不宣称仅附带许可证就已经完成任何对外再分发的全部义务。
