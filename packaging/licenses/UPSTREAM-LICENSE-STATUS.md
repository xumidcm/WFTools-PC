# 上游 WFTools 许可证状态

当前参考上游：<https://gitlab.com/ailife8881/wftools>。

核对的上游快照：`201cde43171b3481cd4ed2ed1e73b20e3daa7400`。

## 上游本身的许可证状态

该快照的 Git 根目录与本项目当前根目录均未包含 `LICENSE` 或 `README` 文件；源码搜索也未发现 WFTools 自身的 AGPL/GPL 授权声明。GitLab 项目说明称其免费、开源，但这不能替代明确的许可证文本。

## 本项目获得的授权

本仓库的代码随本仓库以 **GNU 通用公共许可证第 3 版（GPL-3.0）** 分发。因此：

- 本仓库的 `watch-core`、`watch-renderer`、`agent-core`、`desktopApp` 与 `app/` 内的代码，随本仓库以 GPL-3.0 一并分发，见根目录 [`LICENSE`](../../LICENSE)。
- 上游仓库未提供许可证文本；若上游后续补充正式许可证文本，以正式文本为准并同步更新本文件。
- 在对外再分发上游代码的其它用途（例如单独抽取 `app/` 另作它用）时，仍应先确认授权范围，不要仅凭本文件推定。

## 不影响第三方组件

此状态不影响依赖组件已有的许可证声明。VLC、vlcj、Java 和其它随包依赖的原始法律文件分别保留，见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
