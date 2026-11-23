# M14 输出设备感知与按设备音效配置（立项）

> 状态：**立项，计划开工 2026-11-08**（方案 2026-11-07 定稿）
> 方案来源：**通 WEcho 已落地实现**（`AudioDeviceMonitor.kt` / `AudioCaptureService` / `ConfigApplier.kt`）
> 关联：`ADR-01`（重路由时机）、`ADR-02`（重路由与静音互斥）、`ADR-03`（禁切 mode）、`ADR-04`（USAGE_MEDIA）
> 前置里程碑：M13（v0.2.1 `00e9fb3`）

---

## 1 目标

换输出设备（扬声器 ↔ 耳机 ↔ 蓝牙）时，**自动切换音效配置，且用户听不出断档**。

沿用 WEcho 的双模式语义，UI 文案一致：

| 模式 | 行为 | 对应 WEcho 开关 |
|---|---|---|
| **聚合模式** | 所有设备共用一套配置 | 「扬声器/耳机自适应」= 关 |
| **自适应模式** | 每个设备各存一套配置 | 「扬声器/耳机自适应」= 开 |

**非目标**：按播放器 App（per-UID）的配置 —— 见 §9。

---

## 2 我方现状（代码事实）

| 事实 | 位置 | 含义 |
|---|---|---|
| 输出流单一、不指定设备 | `native_audio_engine.cpp` L113-143：只有 `AAUDIO_USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`，无 `setDeviceId` | 完全跟随系统路由，App 不参与决策 |
| 无设备感知 | `app/`+`core/` 无 `AudioDeviceCallback`、无 `getDevices(GET_DEVICES_OUTPUTS)` | 插拔耳机 App 不知情 |
| 预设全局单态 | `MainViewModel` 预设快照 | 无「设备 → 配置」绑定 |
| 路由变化会 detach 流 | `AAudioStreamBuilder_setErrorCallback` + 重连 | 切设备本就会重建一次流 |
| 两种录制模式共用同一输出流 | `engine.prepare(EngineMode.CaptureRelay, ...)` | 输出侧逻辑两模式可共用（详见 §5.5） |

---

## 3 参考实现：WEcho 怎么做的（已读代码，非猜测）

| 组件 | 做法 |
|---|---|
| `AudioDeviceMonitor.kt` | `registerAudioDeviceCallback` 常驻；**500ms debounce**（`OUTPUT_CHANGE_DEBOUNCE_MS`）；按优先级表取当前输出（`BT_A2DP > BLE_HEADSET > BLE_SPEAKER > USB_HEADSET > WIRED_HEADSET > WIRED_HEADPHONES > BT_SCO > BUILTIN_SPEAKER`） |
| 设备名判定 | 扬声器/听筒 → `"Speaker"`；其余取 `productName`。**空名不能回落 "Speaker"**，否则蓝牙握手期空名会被误判成扬声器，导致「切耳机却出扬声器配置」（2026-10-14 事故修复），空名回落到 `"Headphone"` |
| `applyOutputConfigIfChanged` | 设备名判等即返回（天然去抖）；**无论是否自适应，物理设备变更一律标记重建播放流** |
| `ConfigApplier.applyConfigForDevice` | 配置键 = `config_<sanitize(设备名)>`（小写、非字母数字转 `_`）；**1 秒内同设备不重复应用**；取到 JSON 后全量下发给 native |
| 二次 recheck | 设备变更后 **1500ms 再查一次**——蓝牙 A2DP 常在连接后延迟出现在设备列表里 |
| Dart VM `_handleOutputDeviceChange` | **防重入锁 + pending 重放**（只重放最后一个请求）。若不重放会出现「UI 已切耳机、`_config`/native 仍停在扬声器」的永久不同步 |

### ⚠️ 关键结论（推翻我上一版方案）

上一版方案我写的是「切换只切参数、不重建流」。**WEcho 的做法相反：必须重建播放流。**

> 蓝牙 SCO（通话）挂断后设备回切，游离的 AAudio 流可能**卡在旧设备的单声道状态**，重建才能按当前默认路由重新打开立体声，否则表现为「像单声道」的假死。

所以「无感」的正确含义不是「不重建」，而是：**重建极快 + 重建后立刻全量恢复配置 + 去抖防止连环重建**。

---

## 4 我方方案

### 4.1 设备键（与 WEcho 一致）

- key = `sanitize(productName)`，非字母数字转 `_`，全小写
- `BUILTIN_SPEAKER` / `BUILTIN_EARPIECE` → 固定键 `speaker`
- `productName` 为空 → 回落 `headphone`（**不得回落 speaker**，同 WEcho）
- 类别（SPEAKER/WIRED/USB/BT_A2DP/BT_LE）**只用于 UI 展示与默认推荐，不进存储键**

理由：按型号绑定比按类别精确（两副不同耳机可各存一套）；类别级会把所有蓝牙耳机压成一份。

### 4.2 双模式存储

```
device_adaptive_enabled : Boolean          // 对应 WEcho 的 autoOutputSwitch，默认 true
device_preset_<key>     : String           // 该设备的预设快照串（复用现有格式，零新序列化）
```

聚合模式下忽略 `device_preset_*`，全部设备读全局当前预设（= 现状行为，改动面为零）。

### 4.3 切换时序

```
onAudioDevicesAdded / onAudioDevicesRemoved
  → debounce 500ms                     （系统会连发多次，蓝牙尤其）
  → 取当前输出（§3 优先级表）
  → 设备键 == lastAppliedDevice        → return（判等去抖）
  → 标记需要重建输出流
  → 等 engine ready（流重建完成 + 至少一次成功回调）
  → 自适应模式：载入 device_preset_<key> → 全量下发
     聚合模式：跳过，保持现有配置
  → strategy.afterDeviceChanged()       （§5.5 唯一分叉点）
  → 1500ms 后二次 recheck               （兜蓝牙 A2DP 延迟出现）
```

**【等 engine ready】** 是我方必须加的一步：WEcho 在同进程内同步下发，我方引擎
在 error callback 后重建有窗口期，窗口内下发的参数会被重启吞掉。

### 4.4 防重入与重放

`MainViewModel` 层需加：

```kotlin
private var isSwitchingDevice = false
private var pendingDeviceKey: String? = null   // 只保留最后一个
```

锁被占用时不丢弃请求，记入 `pendingDeviceKey`，锁释放后重放一次。
直接抄 WEcho 的结论：丢弃会导致 UI 与 native 永久不同步。

---

## 4.5 参数记忆与自动下发（2026-11-08 增补）

**问题**：只有手动绑定预设时，未绑定设备的用户切换后会丢掉刚调好的参数。

**做法**：三级记忆 + 五级回落，绑定降级为「可选钉住」。

| 存储键 | 粒度 | 用途 |
|---|---|---|
| `device_last_<key>` | 单设备 | 同一台来回切，拿回自己的 |
| `category_last_speaker\|headphone` | 类型（2 个桶） | 从没用过的新耳机/新扬声器首次接入 |
| `last_config` | 全局 | 极端兜底（该类型也无记录） |

解析优先级（2026-11-08 定稿：**永远下发该设备最后一次调整**）：

```
① device_last_<key>          该设备的最近配置
② category_last_<cat>        该类型的最近配置（该设备从无记录）
③ device_preset_<key>        精确绑定（降级为"首次默认"，UI 已隐藏）
④ device_preset_cat_<cat>    类别绑定（同上）
⑤ last_config                全局最近配置
⑥ 都没有 → 保持现状（真·首次）
```

> 原顺序把绑定排在最近配置之前，会：①在用户点过"所有耳机"后彻底挡死设备记忆；
> ②每次切回都覆盖用户在新设备上的调整——正是要解决的"参数丢失"。
> 故绑定降级为首次默认，绑定 UI 由 `SHOW_DEVICE_BIND_UI = false` 隐藏（实现保留，
> 待用户反馈再放开）。

**切换中断（物理限制）**：设备切换必然触发 `AAUDIO_ERROR_DISCONNECTED(-899)`，AAudio 流
需重建，无法做到零中断。原生退避已由 `200/400/800/2000/10000ms` 下调为
`50/100/400/1000/5000ms`——单发路由切换（streak 0/1）恢复时间从 200~400ms 降到 50~100ms，
连续失败仍保留阶梯防死循环。

**保存时机**：每 10s 判等快照（拖滑条不写放大）+ **切设备前强制落盘**；一次写入同时更新三份。

**为什么不能只做一层**（都踩过）：
- 只做全局 → A→B→A 往返时 B 的配置覆盖全局，切回 A 拿到 B 的参数
- 只做设备级 → 新耳机首次接入无记录，退到全局会拿到**扬声器**的配置（错类型）

**跳过下发**：目标参数与当前完全一致时不跑 47 次 setter——典型场景正是「未绑定设备回落到
切换前刚存的当前值」，此时下发纯属无意义抖动。

**无感**：全程不暂停播放、不重建流、不发 keyevent；参数经现有命令队列下发，节点侧自带平滑。

---

## 5 与 WEcho 的差异（不能照抄的地方）

| 方面 | WEcho | 我方 | 原因 |
|---|---|---|---|
| 播放角色 | 自成体系播放链路 | loopback 接管 + 固定节点链 | 换设备只换参数快照，**不换拓扑** |
| 空闲时行为 | 未捕获时只记录不应用 | 同样只在引擎 running 时应用 | 避免无意义重建 |
| 抗回环 | — | `ALLOW_CAPTURE_BY_NONE` 不可动 | 双声根因防线 |
| 重路由 | — | **不调 `nudgeRouting`** | ADR-02 / ADR-03 |
| 采样率 | 自带重采样层 | 引擎固定 48k | 蓝牙 44.1k 需先实测重连后实际格式 |

### 5.5 两套录制模式的差异（Loop / MP）

两种模式共用**同一个引擎输出流**（`engine.prepare(EngineMode.CaptureRelay, ...)`），
只有输入源不同：Loop 走特权侧 `AudioPolicy + AudioMix(LOOP_BACK)`，MP 走 MediaProjection。
故输出侧逻辑可共用，**捕获侧必须分叉**：

| 环节 | Loop 模式 | MP 模式 | 是否共用 |
|---|---|---|---|
| 建 / 重建输出流 | 同 | 同 | ✅ 共用 |
| 设备感知 + 配置切换 | 同 | 同 | ✅ 共用 |
| **设备切换后的捕获修复** | **必须做** | 不需要 | ❌ 分叉 |

**为什么 Loop 必须做**：Loop 捕获成立依赖「播放器 track 被重路由进我方 submix」
（ADR-01：只有**晚于** submix 端口就绪创建的 track 才会被搬走）。插拔耳机/连蓝牙会触发
APM 重算路由，已重路由的 track **可能被打回物理设备 → 捕获断流**。

更危险的是 `loopbackRerouted` 一旦置 `true` **不会自行回退**，而它为 `true` 时整条链路
**不挂任何静音**（`syncLoopbackMute` 直接 return）。重路由被打回后原声重新直出，与 DSP
输出叠加即 **双声** —— 正是项目历史最痛的事故类型。

**分叉实现**：

```
OutputRouteCoordinator（共用）
  ├─ debounce 500ms → 取当前设备 → 判等
  ├─ 标记重建输出流 → 等 engine ready
  ├─ 下发设备配置（自适应模式）
  └─ strategy.afterDeviceChanged()          ← 唯一分叉点
        ├─ LoopStrategy:
        │     ① 强制复位 loopbackRerouted = false
        │     ② detectReroute 校验活跃会话 deviceIds 是否命中我方 port
        │     ③ 未命中 → 逼 track 重建（复用 keyevent 127/126 手法）
        │     ④ 仍不命中 → 记 diag，保持「不静音」（不连坐 LOOP_BACK 副本）
        └─ MpStrategy : no-op（捕获走 MediaProjection，与输出路由无关）
```

**可复用的现成件**：
- `MainViewModel.ensureReroutedMp()`（L1623-1657）—— 已在用 `input keyevent 127/126`
  逼播放器重建 track，抽成 `ensureRerouted()` 即可两模式共用
- `TakeoverController.detectReroute()`（L1200）—— dumpsys `deviceIds` 命中判定

**新增不变量**：设备变更时必须强制复位 `loopbackRerouted = false`，否则省电降频
（`POLL_INTERVAL_REROUTED_MS` = 2s / 灭屏 10s）会一直以为链路仍重路由着，
既不复检也不报错。

---

## 6 分期与验收

| 期 | 内容 | 验收 |
|---|---|---|
| **M14a** 感知层 | `OutputDeviceMonitor`（500ms debounce + 优先级表 + 空名回落）+ 诊断日志 | 插拔有线/连断蓝牙，日志键正确、无抖动误触发 |
| **M14b** 双模式 + 切换 | 存储 + 重建流标记 + ready 等待 + 防重入/重放 + 1500ms recheck + **Loop/MP 捕获策略分叉（§5.5）** | ①有线插拔自动切预设，**无爆音、无单声道假死**；②蓝牙 A2DP 连接 1.5s 内生效；③蓝牙 SCO 通话回切后立体声正常；④聚合模式下切设备配置不变；⑤**Loop 模式**插拔耳机后不出现双声、捕获不断流；⑥Loop 模式设备切换后 `loopbackRerouted` 被正确复位并复检 |
| **M14c** UI | 「扬声器/耳机自适应」开关（文案与 WEcho 一致）+ 当前设备角标 + 每设备绑定/解绑 | 真机逐项点验，重启后绑定不丢 |

---

## 7 风险

| 风险 | 对策 |
|---|---|
| 重建流窗口内下发被吞 | 等 ready 再下发（§4.3） |
| 蓝牙连发多设备名 → 连环重建 | 500ms debounce + 判等 + 1s 去重 |
| 蓝牙 A2DP 延迟出现在列表 | 1500ms 二次 recheck |
| SCO 回切单声道假死 | 一律重建流（WEcho 实证） |
| **Loop 模式设备切换后重路由失效 → 双声** | §5.5 分叉策略：复位 + 复检 + 逼重建，仍失败则保持不静音并记 diag |
| 误触重路由/静音链 | 切换路径不碰 appops / DP / `nudgeRouting` |
| 蓝牙 44.1k 与 48k 不匹配 | M14a 阶段实测重连后 `getSampleRate()`，有问题在 M14b 前处理 |

---

## 8 工作量

| 项 | 人日 |
|---|---|
| M14a 感知 + 日志 | 0.5 |
| M14b 双模式 + 重建/ready/防重入/recheck | 1.5 |
| M14b 附带：Loop/MP 捕获策略分叉（§5.5） | 0.5 |
| M14c UI | 0.5 |
| 真机验证（有线/蓝牙A2DP/SCO回切/Loop 插拔不双声/重启） | 0.5 |
| **合计** | **3.5** |

---

## 9 待决策

用户表述里同时出现「耳机与扬声器」（设备维度）与「不同播放器」（应用维度）：

- **设备维度** ← 本方案
- **应用维度**（per-UID）：复用 `TakeoverController` 按应用接管基础，另立 M15 ——
  **2026-11-08 用户明确否决，不做**（M15 撤销）
- **二维**：配置空间膨胀，UI 复杂度显著上升

结论：M14 只做设备维度；应用维度待拍板后另立里程碑。
