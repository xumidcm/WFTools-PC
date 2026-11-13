# 发布流程

本文件记录维护者打包并发布一个版本的实际步骤。分发包在本地构建，CI 只负责编译与测试。

## 1. 准备

确认工作区干净，主分支已通过全部测试：

```powershell
git status --short           # 应为空
git diff --check             # 应无输出
.\gradlew.bat test           # 全部通过
```

确认 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) 中的依赖版本、`desktopApp/build.gradle.kts` 里的 `packageVersion`，以及包内说明文档中的版本号三者一致。

## 2. 更新版本与说明

- 修改 `desktopApp/build.gradle.kts` 的 `packageVersion`。
- 更新 `packaging/README_WINDOWS.md`、`packaging/README_LINUX.md` 中出现的版本号。
- 在 `CHANGELOG` 或 Release Notes 中写明本版的行为变化与已知限制。

## 3. 构建应用镜像

Compose 的应用镜像必须串行生成，不要在同一个输出目录上并发构建：

```powershell
.\gradlew.bat :desktopApp:createDistributable
```

Linux x64 需要在一台 glibc 桌面环境上执行同样的任务；Windows 与 Linux 的镜像不能互相替代。

## 4. 打包

```powershell
# Windows：便携 ZIP + MSI，产物写入 artifacts/
.\scripts\package-windows.ps1 -AppImage '.\desktopApp\build\compose\binaries\main\app\WFTools-PC' -Version '0.1.6'

# 只复查已有镜像，不复制、不编译 MSI
.\scripts\package-windows.ps1 -AppImage '...' -Version '0.1.6' -ValidateOnly
```

```bash
# Linux：deb + 便携 tar 包
./scripts/package-linux.sh
```

脚本会校验随包运行时、VLC 来源与许可证文件，并生成 `*-SHA256SUMS.txt`。同名产物默认不覆盖，需要重跑时用 `-OutputDirectory` 指定新的子目录。

## 5. 打标签

```powershell
git tag -a v0.1.6 -m "WFTools-PC 0.1.6"
git push origin v0.1.6
```

标签用 `v` 前缀加语义化版本；不要沿用上游 Android 版的 `V6.x` 标签。

## 6. 创建 GitHub Release

1. 选择刚推送的标签，标题用 `WFTools-PC 0.1.6`。
2. 附件至少包含：
   - `WFTools-PC-0.1.6-windows-x64.zip`（便携版）
   - `WFTools-PC-0.1.6-windows-x64.msi`（安装版）
   - `WFTools-PC-0.1.6-linux-x64.deb`
   - `WFTools-PC-0.1.6-linux-x64-portable.tar.gz`
   - `*-SHA256SUMS.txt`
3. 正文写清：本版新增/修复、已知限制、安装方式差异（MSI 只注册**候选**打开方式）。
4. 勾选 “Set as the latest release”。

## 7. 发布前自查

- [ ] 便携 ZIP 完整解压后能启动，视频首帧、音频与文件打开都使用随包运行时。
- [ ] MSI 安装后开始菜单与桌面快捷方式存在，`.watch` 的默认打开方式**未**被强改。
- [ ] 卸载后 `%LOCALAPPDATA%\WFTools-PC\workspace` 中的恢复草稿仍在。
- [ ] 包内 `licenses/` 含 GPL-3.0 全文、第三方声明与 VLC/Java 的原始法律文件。
- [ ] `PACKAGE-MANIFEST.json` 记录了源码版本与随包组件来源。
- [ ] Release 附件与 `*-SHA256SUMS.txt` 一致。
- [ ] README 的截图与徽章链接仍有效。

## 8. 历史清理（仅在本仓库首次公开时做过一次）

仓库早期曾提交过 `app/build/` 产物，导致历史膨胀。历史已用 `git filter-repo` 重写并强制推送。如果今后再次误提交大文件：

1. 先做镜像备份：`git clone --mirror <repo> ../backup.git`
2. `git filter-repo --invert-paths --path <误提交路径>`
3. 重新添加 `origin` 远程后强制推送所有分支与标签
4. 通知协作者重新克隆——重写后的历史无法与旧克隆合并
