# Sonicraft 待办与排期清单（ROADMAP / BACKLOG）

> **本文件是唯一的"未闭环事项"总表**。专项设计文档（`M4_DSP_ROADMAP.md`、`LOOPBACK_POLICY_PLAN.md`、`SCRIPT_EFFECT_ROADMAP.md`、`UI_RESPONSIVE_PLAN.md` 等）只描述各自模块，**是否已完成以本表状态为准**。
>
> 最后更新：2026-11-23（HEAD `2e934db`）

## 状态图例

| 标记 | 含义 |
|---|---|
| 🔴 **阻塞** | 有明确的外部依赖或未确认前提，无法开工 |
| 🟡 **待做** | 前提已满足，可以随时开始 |
| 🔵 **进行中** | 已开工未闭环 |
| ⚪ **待定** | 需求本身还不明确，需要先讨论 |
| ✅ **已关闭** | 完成或已决策放弃（留档防重复踩） |

---

## P0 · 下一工作日优先（当前仅剩 1 项）

| # | 项 | 状态 | 阻塞点 / 依赖 | 预估 |
|---|---|---|---|---|
| **P0-1** | **OTA 应用内升级** | 🔴 阻塞 | **需你确认 3 件事**：<br>① Gitee 仓库公开还是私有（私有则 API 需 token，不能放客户端）<br>② 是否只发 release 签名包（debug↔release 签名不同，首次切换必须卸载丢数据）<br>③ 自动检查（启动静默）还是纯手动（设置页按钮） | 4~5 轮 |

### P0-1 的详细拆解（确认后即可开工）

| 子项 | 内容 | 备注 |
|---|---|---|
| ① 清单 | `update.json` 放 Gitee 仓库根目录，用 raw 直链读 | 实测 raw 可匿名访问（`200 text/plain`）；注意 CDN 缓存，加 `?t=` 或 `no-cache` |
| ② APK 托管 | Gitee 免费仓库 **Release 不支持自定义附件**（实测 `assets` 只有源码 zip/tar.gz），需放**对象存储**（OSS/COS 免费额度）或 Gitee 仓库直存（会撑大历史） | 三方案见对话记录：A=对象存储(推荐) / B=GitHub Releases(国内慢) / C=Gitee 仓库直存 |
| ③ 检查器 | `UpdateChecker.kt`（约 80 行）：`HttpURLConnection` + `org.json`，**无需新增依赖** | 未认证限流问题因改用 raw 直链而消失 |
| ④ 下载 | 系统 `DownloadManager`（零依赖、断点续传、通知栏进度、App 被杀也能继续） | 目标目录用 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，勿用 `cacheDir` |
| ⑤ 校验 | 下载后算 APK **SHA-256** 与清单里的 `sha256` 比对，不符拒绝安装 | 防篡改 |
| ⑥ 安装 | `FileProvider` + `Intent.ACTION_VIEW`（`application/vnd.android.package-archive`） | 需新增 `INTERNET`（**当前 AndroidManifest 里没有**）与 `REQUEST_INSTALL_PACKAGES` |
| ⑦ UI | 设置页「检查更新」入口 + 发现新版弹窗（版本号/说明/立即更新） | — |
| ⑧ 发版规范 | `versionCode` 每次 +1；tag 用 `vX.Y.Z`；release 说明附 APK 的 SHA-256 | 当前 `versionCode=9` / `0.4.0` |

---

## P1 · 近期（P0 之后）

| # | 项 | 状态 | 说明 | 预估 |
|---|---|---|---|---|
| **P1-1** | **音量基线恢复确认** | 🔴 阻塞 | 已定位：`settings get system sound_effects_enabled` 曾为 **0**（已恢复 1）、`com.dts.dtsxultra` 曾被禁用（已 enable）。**DTS 是纯系统服务、无 launcher，`pm enable` 后必须重启手机才会重新初始化**。需重启后确认音量是否回正常 | 重启 + 实听 |
| **P1-2** | **GitHub Release v0.4.0 附件上传** | 🟡 待做 | Tag `v0.4.0`（`b04666a`）已推，但 **Release 页面的 APK 附件需网页手动上传**：`https://github.com/jiangjie977/Sonicraft-DSP/releases/new?tag=v0.4.0`；附件在 `dist\Sonicraft-0.4.0-release.apk`（9.98 MB） | 你操作 |
| **P1-3** | **ScKnob 滚动误触** | ⚪ 待定 | `ScKnob` 只响应 `detectVerticalDragGestures`，**页面滚动时手指划过旋钮会改值**（VB2 的分频点曾被误拖到 213 Hz 即此因）。全项目 40+ 个旋钮都受影响。修法待定（如加 `pointerInput` 的滚动方向判别 / 长按才响应） | 需先定方案 |
| **P1-4** | **`backup-full-20261116` 分支去留** | ⚪ 待定 | 该分支（`38997ce`）保留了 15 个**已被否决**的实验提交（自适应分频权重 / 环境成分提取 / 环境感+低频分频滑块）。`restore-stage` 与 `master` 均已到 `2c35dad`，该分支仅作历史留档 | 你决定 |

---

## P2 · 中期

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| **P2-1** | **v0.4.1 发版** | 🔵 前置 | **OTA 端到端测试的前提**：需要存在一个比当前更新的 release 包。升 `versionCode` + 打 tag + 构建 release + 你上传附件 |
| **P2-2** | **S71 空间环绕收尾** | 🟡 待做 | 已知结论：S4 六段 EQ **不做**（已删除，`kParamCount 58→32`）、S6 频谱 **pass**、S7 已做。剩余项见 `docs/M4_DSP_ROADMAP.md` |
| **P2-3** | **`docs/DEVELOPMENT.md` 的 OTA 章节** | 🟡 待做 | P0-1 落地后补文档 |

---

## 已关闭（留档，防重复踩）

| # | 项 | 结论 | 依据 |
|---|---|---|---|
| ✅ 1 | **REMOTE_SUBMIX 全局 force-use（澄音式）** | **判死**。枚举 `setForceUse(FOR_MEDIA, 8..16)`：8/9/10 被接受但 `recRms=0`，11~16 被系统拒绝；合法范围仅 0~10。澄音硬编码的 `10` 在 AOSP 里是 `FORCE_NO_BT_A2DP`，本 ROM 无 REMOTE_SUBMIX 私有扩展 | `docs/REMOTE_SUBMIX_PROBE_RESULT.md`（`2fad890`） |
| ✅ 2 | **澄音是否走 MP / LOOP_BACK** | **澄音走的是 `AudioPolicy(route flags=0x3) + REMOTE_SUBMIX 录音`，与我们架构相同**，没有黑科技；它的 `setForceUse(10)` 是旧 ROM 兼容残留、在本机无效也不影响 | `dumpsys audio` 实测 |
| ✅ 3 | **虚拟低音 2（自研 7 阶 Chebyshev + 位移/热保护）** | **用户否决，已全部删除**（文件/节点/Kotlin/UI/prefs 共 −2255 行）。客观指标全面优于 wecho 版（谐波频段 +16 dB、THD 0.02% vs 1.31%、有保护）但**主观听感不达预期** | `2c35dad` |
| ✅ 4 | **等响度补偿（LoudnessNode）** | **用户判定"没起作用"，已删除**。实测 `vol=0.20 → 60Hz +6.6dB`、`vol=1.00 → 0.00dB` 完全透明，但用户不认可 | `2c35dad` |
| ✅ 5 | **`TRANSIENT_PROTECT` 假开关** | 已修（补启动下发 + `pushAllVsParams` 重推） | `4c32a7d`（已随 A2 回退删除，但结论有效：**新增参数必须同时接启动链与 pushAll**） |
| ✅ 6 | **`vs_mode` 等 5 个键的类型不匹配（P0）** | 已修并**保留**（`putInt` 存 / `getFloat` 读 → `ClassCastException` → 被 `runCatching` 吞掉 → **整段参数重放静默中断**）。改 `getInt` / `getBoolean` | `a454cfc` |
| ✅ 7 | **0x2 + DP 双声验证** | **用户否决，不再验证**。<br>代码现状保持 `0x2(LOOP_BACK) 优先 + nodp 默认 false（挂 DP）`（`a8e8f18`），0x3 与 DP 相关代码全部保留未删。<br>留档技术结论：`0x3` = `RENDER(0x1) + LOOP_BACK(0x2)` 两个位**同时置位**，是「重定向 + 复制双路」，并非「RENDER 替代 LOOP_BACK」，故双声比 0x2 更严重；0x2 是复制语义、原声仍在，必须靠 session 级 DP 压掉原声 | `a8e8f18` |

---

## 关键教训（写进表里，避免复发）

| # | 教训 | 出处 |
|---|---|---|
| 1 | **客观指标好 ≠ 听感好**。改音功能以用户实听为最终裁定；移植第三方算法前先确认要不要 A/B 盲听 | `feedback_objective_metrics_not_enough.md` |
| 2 | **新增 DSP 节点必须同时接**：`native_audio_engine.cpp` 装配、`AudioEngine.kt` 常量、`AudioChainManager` 启动重放、`EffectGroups` slot、VM 快照/恢复。漏任一处 = 假开关 | `TRANSIENT_PROTECT` / `AutoEQ` 两次事故 |
| 3 | **改 C++ 后必须确认 `.o` 真的重编**。曾因源文件 mtime 未变，Gradle 复用旧产物，装机后节点数仍是旧的（诊断 `graph assembled: 18 nodes` 而代码是 19） | 2026-11-23 VB2/loudness 装机 |
| 4 | **整体音量变小先查系统**：`sound_effects_enabled`、`com.dts.dtsxultra` 是否被禁用，**不要先改 DSP 输出增益** | `project_device_volume_trap.md` |
| 5 | **文档/记忆写入前先查历史结论**。REMOTE_SUBMIX 曾差点白跑一遍（项目里已两次判死记录） | `REMOTE_SUBMIX_RESEARCH_PLAN.md` 第零节 |

---

## 排期建议（顺序，不含日期）

```
① 你确认 OTA 三件事 → P0-1       ← 4~5 轮，当前唯一 P0
② P2-1（发 v0.4.1）              ← OTA 端到端测试前置
③ P1-1（重启验证音量基线）        ← 与 ② 可合并到同一次重启
④ P1-2（GitHub Release 附件）     ← 你网页操作，随时可做
⑤ P1-3 / P1-4（需你先定方案/决定）
⑥ P2-2（S71 收尾）
```

**说明**：② 与 ③ 可以合并——发 v0.4.1 装机时顺带重启一次手机，同时验证 DTS 音量是否恢复。
