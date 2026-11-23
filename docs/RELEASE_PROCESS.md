# 发布流程：开发目录与发布目录分离

> 目的：让日常开发（含半成品、实验分支、临时文件）与 GitHub 发布彻底隔离。
> 开发目录随便折腾；**只有发布目录能接触 GitHub**。

## 一、两个目录的职责

| 目录 | 角色 | 允许的操作 |
|---|---|---|
| `D:\OPEN\DSP` | **开发目录**（唯一开发入口） | 任意提交、分支实验、`rebase`、`reset`、临时文件 |
| `D:\OPEN\DSP-GITHUB` | **发布镜像**（唯一推送出口） | 只接受 `tools/publish_sync.ps1` 同步进来的快照；禁止手改代码 |

## 二、同步内容规则

### 会同步（发布必需）

`app/`、`core/`、`integration/`、`tools/`、`docs/`、`scripts/`、`gradle/`、
`*.gradle*`、`gradlew*`、`README*`、`LICENSE*`、`THIRD-PARTY-NOTICES.md`、`CHANGELOG.md`、
`.gitattributes`、`.gitignore`

### 不同步（开发产物 / 敏感文件）

| 排除项 | 原因 |
|---|---|
| `build/`、`.cxx/`、`.gradle/`、`.kotlin/` | 构建产物 |
| `.codebuddy/`、`.idea/` | IDE / AI 工具数据 |
| `node_modules/` | 前端依赖 |
| `*.apk`、`*.jks`、`*.keystore`、`local.properties` | 产物与签名，**永不入库** |
| `*.log` | 运行日志 |

## 三、标准推送流程（顺序不可颠倒）

```
① 开发目录验证
   - Kotlin/UI 改动：gradlew :app:assembleDebug
   - native 改动：tools/host-harness/build_host.bat（ALL PASS）
   - 新增/改动 DSP 节点：python scripts/check_node_replay.py（零退出）

② 开发目录提交
   - git add <本次改动文件>（不要 git add -A）
   - git commit -F <UTF-8 消息文件>

③ 开发目录打 tag（仅发版时）
   - git tag -a vX.Y.Z -m "..."

④ 运行同步脚本（唯一入口）
   - .\tools\publish_sync.ps1 -Version vX.Y.Z
   - 脚本会：robocopy 镜像 → git add → 行尾规范化 → commit → push

⑤ 在发布目录补 tag（仅发版时）
   - cd D:\OPEN\DSP-GITHUB; git tag -a vX.Y.Z -m "..."; git push origin vX.Y.Z

⑥ 校验
   - GitHub 页面确认最新 commit 与 tag
```

## 四、必须遵守的规则

1. **只在开发目录改代码**。发布目录里发现的任何问题，回开发目录改，再同步；禁止在镜像里直接编辑。
2. **发布目录单线**：只有 `master`，不建 feature 分支，历史是「发布快照序列」。
3. **开发目录与发布目录的历史是分叉的**（设计如此）：
   - 开发目录保留完整开发历史（含实验提交）；
   - 发布目录每个 `sync:` 提交对应开发目录的一个快照。
   - 因此**不要从开发目录直接 `git push` 到 GitHub**（会因历史分叉被拒）；推送统一走脚本。
4. **同步前开发目录必须干净**（`git status --porcelain` 为空），脚本会二次确认。
5. **行尾由 `.gitattributes` 统一**：`*.kt` 存 LF、`*.bat/*.cmd/*.ps1` 存 CRLF。脚本的 `git add` 会自动规范化，纯行尾差异不会被提交成内容变更。
6. **签名文件永不入库**：`*.jks` / `*.keystore` / `local.properties` 已在排除列表与 `.gitignore` 双保险。
7. **发布目录不装 IDE**，避免 `.idea/` 污染。
8. **tag 两处同名**：开发目录与发布目录打同一个 `vX.Y.Z`，便于对照。

## 五、回滚

| 场景 | 操作 |
|---|---|
| 发布目录刚推送、需要撤回 | 在发布目录 `git revert <commit>` 后 `git push`，**不要 force push** |
| 开发目录改错了 | 正常 `git revert` 或新提交修复，不要重写已同步过的历史 |
| 镜像内容异常 | 删除发布目录，重新 `git clone` GitHub 仓库，再跑一次同步脚本 |

## 六、首次初始化（已完成，留档）

```powershell
cd D:\OPEN\DSP-GITHUB
git clone https://github.com/jiangjie977/Sonicraft-DSP.git .
# 之后每次只需运行 tools/publish_sync.ps1
```
