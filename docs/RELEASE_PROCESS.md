# 发布流程：开发目录与发布目录分离

> 目的：让日常开发（含半成品、实验分支、临时文件）与 GitHub 发布彻底隔离。
> 开发目录随便折腾；**只有发布目录能接触 GitHub**。

## 一、两个目录的职责

| 目录 | 角色 | 允许的操作 |
|---|---|---|
| `D:\OPEN\DSP` | **开发目录**（唯一开发入口） | 任意提交、分支实验、`rebase`、`reset`、临时文件 |
| `D:\OPEN\DSP-GITHUB` | **发布镜像**（唯一推送出口） | 只接受 `tools/publish_sync.ps1` 同步进来的快照；禁止手改代码 |

## 二、同步内容规则

### 硬性要求（不可妥协）

**发布镜像里不允许存在任何被排除的文件或文件夹。**

- `robocopy /XD /XF` 只跳过**源侧**；目标里已存在的排除项**不会**被自动删除；
- 因此 `tools/publish_sync.ps1` 在每次同步后**强制剪枝**：删除目标中的排除目录、排除文件、
  以及残留的空目录（只保留镜像自身的 `.git`）；
- 手工操作镜像时同样禁止放入这些内容；一旦发现残留，立即删除并重跑脚本；
- 该规则同时写入项目 skill `dsp-release-sync`，任何自动化流程都必须遵守。

### 会同步（发布必需）

`app/`、`core/`、`integration/`、`tools/`、`docs/`、`scripts/`、`.github/`、`gradle/`、
`*.gradle*`、`gradlew*`、`README.md`、`LICENSE`、`THIRD-PARTY-NOTICES.md`、`CHANGELOG.md`、
`.gitattributes`、`.gitignore`、`licenses/`

### 不同步（开发产物 / 敏感文件 / 本地文件）

| 排除项 | 原因 |
|---|---|
| `build/`、`.cxx/`、`.gradle/`、`.kotlin/` | 构建产物 |
| `.codebuddy/`、`.idea/` | IDE / AI 工具数据 |
| `node_modules/` | 前端依赖 |
| `keystore/`、`*.jks`、`*.keystore`、`keystore.properties` | **签名材料，永不入库** |
| `dist/`、`*.apk` | 历史安装包，用 Release 附件分发 |
| `local.properties` | 本机 SDK 路径 |
| `*.log` | 运行日志 |
| `AGENTS.md`、`README.public.md` | 本地工作文件，不属于发布内容 |

## 三、标准推送流程（顺序不可颠倒）

```
① 开发目录验证
   - Kotlin/UI 改动：gradlew :app:assembleDebug
   - native 改动：tools/host-harness/build_host.bat（ALL PASS）
   - 新增/改动 DSP 节点：python scripts/check_node_replay.py（零退出）

② 开发目录提交
   - git add <本次改动文件>（不要 git add -A）
   - git commit -F <UTF-8 消息文件，放仓库外>

③ 开发目录打 tag（仅发版时）
   - git tag -a vX.Y.Z -m "..."

④ 运行同步脚本（唯一入口）
   - .\tools\publish_sync.ps1 -Version vX.Y.Z
   - 远端未配置时用 -NoPush

⑤ 发布镜像补 tag（仅发版时）
   - cd D:\OPEN\DSP-GITHUB; git tag -a vX.Y.Z -m "..."; git push origin vX.Y.Z

⑥ 校验
   - 镜像内无任何排除项（脚本已强制）
   - GitHub 页面确认最新 commit 与 tag
```

## 四、必须遵守的规则

1. **只在开发目录改代码**。镜像里发现的任何问题，回开发目录改，再同步。
2. **发布镜像单线**：只有 `main`（或 `master`，按远端设置），不建 feature 分支。
3. **历史独立**：镜像是全新首版，不继承开发历史；每个 `sync:` 提交对应一个开发快照。
   **不要从开发目录直接 `git push` 到远端**；推送统一走脚本。
4. **同步前开发目录必须干净**（脚本会二次确认）。
5. **行尾由 `.gitattributes` 统一**：`*.kt` 存 LF、`*.bat/*.cmd/*.ps1` 存 CRLF；`git add` 自动规范化。
6. **签名文件永不入库**（排除列表 + `.gitignore` 双保险）。
7. **发布目录不装 IDE**，避免 `.idea/` 污染。
8. **tag 两处同名**：开发目录与发布目录打同一个 `vX.Y.Z`。

## 五、回滚

| 场景 | 操作 |
|---|---|
| 镜像刚提交、需要撤回 | `git revert <commit>` 后 `git push`，**不要 force push** |
| 开发目录改错了 | 正常 `git revert` 或新提交修复，不要重写已同步过的历史 |
| 镜像内容异常/被污染 | 删除镜像目录，重新 `git init` + 跑脚本；或 `git clone` 远端后重跑 |

## 六、首次初始化（独立首版，无历史）

```powershell
# 1. 清空镜像目录（如有旧内容）
Remove-Item D:\OPEN\DSP-GITHUB -Recurse -Force

# 2. 跑同步脚本（自动 git init + 镜像 + 剪枝 + 首次提交；无远端时用 -NoPush）
.\tools\publish_sync.ps1 -Version vX.Y.Z -NoPush
```

首次执行后：镜像是只有 1 个提交（`sync: ...`）的独立仓库。配好远端后：

```powershell
cd D:\OPEN\DSP-GITHUB
git remote add origin <新仓库地址>
git push -u origin HEAD
```

> 之后日常发布只需重复「跑脚本」。
