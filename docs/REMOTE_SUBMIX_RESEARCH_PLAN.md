# REMOTE_SUBMIX 直录路线 · 验证立项

- 立项日期：2026-11-21
- 计划验证：**2026-11-22（单独测试，不与其它改动混做）**
- 状态：**待验证**（未动任何产品代码）
- 相关文档：`docs/LOOPBACK_POLICY_PLAN.md`（现行 LOOP_BACK 路线）

---

## 零、⚠️ 必须先看：历史结论（本项目已试过两次）

**2026-11-21 补记**：本方案在项目里**不是新想法**，已有两轮失败记录，重新开案前必须对照。

| 时间 | 提交 | 结论 |
|---|---|---|
| 2026-11-05 | `3a0511d` | REROUTE 打通，但「注册后等 2s 再建 record」才勉强成立 |
| 2026-11-05 | `fbae6dc` | 16k 特权静音器（双路方案）——「**mix 可注册、record 被 OEM 桩死**」 |
| — | `pending_verify_checklist.md` | 「**SUBMIX shell 内录备份路线 · 两轮验证判死**」 |

**同时必须区分两种 REMOTE_SUBMIX**（这一点以前混在一起，容易误判）：

| 形态 | 实现 | 本项目状态 |
|---|---|---|
| **① policy 限定版** | 先 `registerAudioPolicy` 注册 AudioMix，再 `AudioRecord.setInternalCapturePreset(8)` 绑该 mix 的端口 | ✅ **在跑**（`LoopbackProbeUserService` 现用，48k 正常） |
| **② 全局 force use 版** | `AudioSystem.setForceUse(FOR_MEDIA, FORCE_REMOTE_SUBMIX)` 劫持全局，再录默认 REMOTE_SUBMIX | ❌ **判死**（这就是澄音用的那种） |

**所以「能不能录 REMOTE_SUBMIX」这个问法本身有歧义**：
- ① 版本我们**已经在用**（内录就是它）
- ② 版本（澄音那样）**在这台 OEM ROM 上失败过**

`LoopbackProbeUserService.kt` 的现状注释也印证：
```
// 48k LOOP_BACK 取音频，16k 这路只负责「压原声」。
// 48k 副本另走一路 LOOP_BACK。实验后按结果把顺序改回。
```
→ 最终**只留下了 48k 的 policy+LOOP_BACK**，16k 特权静音器（其 record 被桩死）被放弃。

---

## 一、为什么立项

现行内录走 `AudioPolicy + AudioMix(ROUTE_FLAG_LOOP_BACK)`，在 `TakeoverController.kt`
里积累了大量**专属于 LOOP_BACK 的兼容处理**：

| 现有坑 | 位置 |
|---|---|
| Android 15 必须 `excludeMixRule`，否则自己的 AAudio 输出被回环 | `LoopbackProbeUserService.kt` |
| 「先建 mix 再 mute」的铁律顺序 | `MainViewModel.kt:4602` |
| DP 挂载连坐 LOOP_BACK 副本（−200 dB 把副本也压了） | `TakeoverController.kt:122/275/1005/1813` |
| 6 个 `audiopolicy` 内部类反射，部分 ROM 被拦 | `LoopbackProbeUserService.kt:107-113` |

对照项目 **澄音音效 1.7.72** 的逆向结论（`D:\OPEN\AUDIOFX\澄音音效_逆向`）：
它**完全不用** AudioPolicy / AudioMix / MediaProjection，而是 `REMOTE_SUBMIX` 直录 +
`app_process` 常驻（`ForceRoute.main` / `hold`）。

如果这条路在我们的设备上可用，可以砍掉上表 4 类坑中的 3 类。

---

## 二、三条路线对比

| 路线 | 机制 | 语义 | 需要 | 版本兼容 | 按 App 筛选 |
|---|---|---|---|---|---|
| **A. AudioPolicy + AudioMix(LOOP_BACK)** | 注册 policy，系统复制指定流 | **复制**（原声照常） | `CAPTURE_AUDIO_OUTPUT` | 差 | ✅ |
| **B. MediaProjection（MP）** | 用户弹窗授权 → AudioPlaybackCapture | 复制 | 同上 + 用户授权 | 好 | 部分 |
| **C. REMOTE_SUBMIX 直录** | `AudioSystem.setForceUse(FOR_MEDIA, FORCE_REMOTE_SUBMIX)` + `AudioRecord(REMOTE_SUBMIX)` | **接管**（原声被抽走） | `CAPTURE_AUDIO_OUTPUT` | **好** | ❌ |

**三条都需要 `CAPTURE_AUDIO_OUTPUT`（`signature|privileged`，`pm grant` 无效）**
→ **无论选哪条，冷启动都必须一次 shell 引导（Stellar）**，换路线不能消除这个依赖。

唯一能靠 `pm grant` 彻底摆脱框架的是**权限类功能**（关闭厂商音效 / 读日志），
与内录无关。

---

## 三、验证方案（2026-11-22 执行）

### 目标（只回答三个问题）

1. `AudioSystem.setForceUse(FOR_MEDIA, FORCE_REMOTE_SUBMIX)` 在本机**是否生效**
2. `AudioRecord(REMOTE_SUBMIX)` 能否录到**真实内容**（RMS > 0）
3. **恢复** `FORCE_NONE` 是否可靠（这是最大风险）

### 步骤（一次性测试程序，不进产品代码）

```
① 前置：确认酷狗正在播放（有 MEDIA 流）
② 记录基线：dumpsys audio 的 Force use 字段
③ setForceUse(FOR_MEDIA=1, FORCE_REMOTE_SUBMIX=1)   ← 隐藏 API 反射
④ new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX=8, 48k, FLOAT, STEREO)
   → 采 1 秒 → 计算 RMS / 峰值
⑤ setForceUse(FOR_MEDIA=1, FORCE_NONE=0)             ← 必须 try/finally
⑥ 复查：dumpsys audio 的 Force use 是否回到 none
⑦ 打印四项结果：setOk / rms / peak / restoreOk
```

### 判定标准

| 结果 | 结论 |
|---|---|
| `setOk=true` 且 `rms > 0.001` 且 `restoreOk=true` | ✅ **路线可行**，可评估改造 |
| `rms ≈ 0` | ⚠️ 能劫持但录不到（ROM 限制）→ 路线不可行 |
| `restoreOk=false` | ❌ **不可用**（失败模式太危险：全局无声） |
| `setOk=false` | ❌ 隐藏 API 被拦，路线不可行 |

### 安全护栏（测试时必须）

- **`try/finally` 强制恢复** `FORCE_NONE`
- 测试前记录 `dumpsys audio` 的原始 Force use 值，异常时人工对照恢复
- 全程 ≤ 3 秒，避免长时间静音
- 若设备静音超过 10 秒未恢复：**重启手机**是最终兜底（`setForceUse` 状态不持久化）

---

## 四、如果验证通过，改造范围（预估）

| 层 | 改动 | 工作量 |
|---|---|---|
| shell 侧 | 用 `AudioRecord(REMOTE_SUBMIX)` 替换 policy 反射（约 −150 行） | 中 |
| shell 侧 | 新增 `setForceUse` 劫持 + 恢复守护（`try/finally` + 看门狗） | 中 |
| App 侧 | 去掉 `TakeoverController` 的 mute 逻辑（不再需要压原声） | 中 |
| 回归 | 低延迟路径重验（接管后由我们播回，延迟必须 < 30 ms） | 高 |

**风险最高的两点**：
1. `setForceUse` 未恢复 → **全局无声**
2. 播回延迟劣化 → 音画不同步

---

## 五、明确不做的事

- **不在验证通过前修改任何产品代码**
- 不与 S71 / 空间环绕的任何改动混做（单独一轮）
- 不因为"能摆脱 Stellar"而做——**这条路摆脱不了 Stellar**，收益只在代码复杂度与兼容性

---

## 六、待办勾选

- [ ] 2026-11-22 跑验证程序，记录四项结果
- [ ] 按判定标准给出「可行 / 不可行」结论
- [ ] 若可行：评估是否值得替换 LOOP_BACK（对照第四节工作量）
- [ ] 若不可行：在本文档记录原因，维持 LOOP_BACK
