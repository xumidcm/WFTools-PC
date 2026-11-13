# 贡献指南 / Contributing

感谢你愿意改进 WFTools-PC。本文说明如何报告问题、准备环境、提交改动。

> **In short (English):** JDK 21 + the bundled Gradle wrapper, four-space Kotlin formatting, `com.ailife.wftools...` packages, JUnit 4 tests for every new business rule, Conventional Commit subjects or short imperative Chinese, and a PR that states the behaviour change and the verification commands you ran. All contributions are accepted under GPL-3.0.

## 行为准则

- 讨论对事不对人。批评代码，不要评判作者。
- 不要求贡献者提供设备、付费服务或私有表盘样本。
- 不接受上传无授权的付费表盘、字体或音视频素材。
- 维护者可以关闭偏离项目范围（见 [`PRODUCT.md`](PRODUCT.md)）的提案。

## 报告问题

提 issue 前先搜索现有 issue。请使用对应模板：

- **Bug**：[`.github/ISSUE_TEMPLATE/bug_report.yml`](.github/ISSUE_TEMPLATE/bug_report.yml)
- **功能建议**：[`.github/ISSUE_TEMPLATE/feature_request.yml`](.github/ISSUE_TEMPLATE/feature_request.yml)

一份好的 bug 报告包含：

1. WFTools-PC 版本号（设置或包名中可见）与获取方式（MSI / 便携 ZIP / deb / 自行构建）。
2. 操作系统与版本、DPI 缩放、显示语言。
3. 复现步骤，尽量最小化。
4. 期望结果与实际结果。
5. 相关日志或截图。

**不要上传有版权的表盘。** 需要样本时，请构造一个最小 `.watch`（例如纯色背景加一个文本图层）复现问题，或先取得作者许可。无法公开的样本可以只描述结构。

安全问题请不要开公开 issue，见 [`SECURITY.md`](SECURITY.md)。

## 开发环境

- **JDK 21**，工具链已固定在 21。
- 使用仓库自带 wrapper：`./gradlew`（Linux/macOS）或 `.\gradlew.bat`（Windows PowerShell）。
- 不需要单独安装 Gradle，也不需要 Android SDK——`app/` 不在默认构建里。

常用命令：

```powershell
.\gradlew.bat build                              # 编译全部模块
.\gradlew.bat test                               # 全部 JVM 单元与 UI 测试
.\gradlew.bat :watch-core:test                   # 单个模块
.\gradlew.bat :desktopApp:run                    # 从源码启动
.\gradlew.bat :desktopApp:createDistributable    # 生成应用镜像
```

不要对同一个输出目录或 Gradle 缓存并发构建。需要隔离产物时用 `-Pwftools.buildPlatform=<name>`，输出会落到 `.build/<name>/`。

## 代码风格

- 四空格缩进，Kotlin 官方格式，包名 `com.ailife.wftools...`。
- 类与 composable 用 `PascalCase`，函数/属性/测试方法用 `camelCase`，常量用 `UPPER_SNAKE_CASE`，测试文件名以 `Test.kt` 结尾。
- 公共 API 写 KDoc，说明约定与副作用，而不是复述签名。
- **JVM 业务逻辑不得依赖 Android `Context`/`Uri`**，用 `Path`/`File`。
- **保留未知的归档与 XML 数据。** 读不懂的内容原样保留，不要丢弃或规范化。
- **保存必须原子化。** 先写临时文件、校验、再替换；失败时原文件必须保持可用。
- 依赖版本只改 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)，不要在模块里硬编码版本号。
- 提交前运行 `git diff --check`，不要留下行尾空白。

## 测试要求

- 使用已配置好的 JUnit 4；桌面 UI 用 Compose Desktop 测试。
- 每条新的桌面业务规则都要有一个针对性的回归测试；修复 bug 时先写能复现的测试。
- 测试名描述行为或被测类，例如 `RenderSessionTimeMachineTest`。
- UI 改动请更新相关 UI 测试，或在 PR 描述中附截图。
- 提 PR 前请在本地跑通 `.\gradlew.bat test`。

## 提交规范

短祈使句，中文或 Conventional Commit 前缀都可以：

```
fix: 修复另存为后图层缩略图不刷新
feat(desktop): 支持批量导入时合并撤销
docs: 补充 Linux 打包说明
```

保持提交聚焦，一个提交做一件事；不要混入格式化整个文件之类的无关改动。

## Pull Request

PR 描述里请写清：

- **行为变化**：改了什么，用户能观察到什么不同。
- **验证方式**：跑过的命令与结果（例如 `.\gradlew.bat test` → 全部通过）。
- **关联 issue**：`Closes #123`。
- **UI 截图**：界面改动必附。
- **兼容性**：是否影响既有 `.watch` 文件的读写结果。

只提交经过检查的源码和配置，排除 `build/`、`.gradle/`、日志和本地样本。

## 打包相关改动

改动 `scripts/` 或 `packaging/` 前，请确认：

- 新增的随包组件要在 [`packaging/licenses/THIRD-PARTY-NOTICES.md`](packaging/licenses/THIRD-PARTY-NOTICES.md) 登记来源与许可证，并附原始许可证文本。
- 不要为了让校验通过而放宽哈希校验或来源检查。
- `package-windows.ps1 -ValidateOnly` 应能在不构建 MSI 的情况下通过。

## 许可证

本项目以 **GPL-3.0** 发布。提交贡献即表示你同意以相同许可证授权你的改动，并确认你有权提交这些代码。不要引入与本许可证不兼容的代码或素材。
