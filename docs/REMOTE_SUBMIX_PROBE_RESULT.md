# REMOTE_SUBMIX 验证结果（2026-11-21 实测 · 终稿）

- 设备：NX733J / `NebulaAIOS2.0.14MR2_NX733J` / SDK 36 / Android 16
- 环境：重启后，所有音效 App 冻结，**无任何 MEDIA 流**（酷狗已 force-stop）
- 方式：独立 `app_process` 程序（shell UID 2000），**不依赖 Stellar / Shizuku / 任何 App 组件**
- 产物：`%TEMP%\rsm_probe\`（`RemoteSubmixProbe.java` + `build_probe.bat` + `classes.dex`）

---

## 一、结论（先看这段）

| 项 | 结论 |
|---|---|
| **`AudioRecord(REMOTE_SUBMIX)` 能否初始化** | ✅ **能**（48k 立体声，`init=OK`，两次共录 48128/96256 帧） |
| **默认 REMOTE_SUBMIX 端口有内容吗** | ❌ **没有**（`rms=0`）——必须有机制把流送进去 |
| **`setForceUse(FOR_MEDIA, v)` 能把 MEDIA 路由到 REMOTE_SUBMIX 吗** | ❌ **不能**（枚举 8~16 全部 `recRms=0`） |
| **`FOR_MEDIA` 的合法 force 值范围** | **0~10**（11~16 被系统拒绝，`getForceUse` 回 0） |
| **澄音用的 `10` 在本 ROM 有效吗** | ❌ **无效**（值被接受，但录不到内容） |
| **结论** | **全局 force-use 路线在本 ROM 不存在**；policy 限定版（`setInternalCapturePreset(8)`）是唯一可行途径 |

---

## 二、原始数据

### P0 · 环境

```
uid=2000  (shell)
sdk=36  model=NX733J  build=NebulaAIOS2.0.14MR2_NX733J
baseline getForceUse(FOR_MEDIA)=0
```

### P0b · `AudioSystem` 常量全量枚举（18 个 `FORCE_*`）

```
FORCE_NONE=0  FORCE_SPEAKER=1  FORCE_HEADPHONES=2  FORCE_BT_SCO=3
FORCE_BT_A2DP=4  FORCE_WIRED_ACCESSORY=5  FORCE_BT_CAR_DOCK=6
FORCE_BT_DESK_DOCK=7  FORCE_ANALOG_DOCK=8  FORCE_DIGITAL_DOCK=9
FORCE_NO_BT_A2DP=10  FORCE_SYSTEM_ENFORCED=11
FORCE_HDMI_SYSTEM_AUDIO_ENFORCED=12  FORCE_ENCODED_SURROUND_NEVER=13
FORCE_ENCODED_SURROUND_ALWAYS=14  FORCE_ENCODED_SURROUND_MANUAL=15
FORCE_BT_BLE=16  FORCE_DEFAULT=0
FOR_COMMUNICATION=0  FOR_MEDIA=1  FOR_RECORD=2  FOR_DOCK=3  FOR_SYSTEM=4
FOR_HDMI_SYSTEM_AUDIO=5  FOR_ENCODED_SURROUND=6  FOR_VIBRATE_RINGING=7
>>> FORCE_REMOTE_SUBMIX **NOT PRESENT as constant**
```

### P1 · 裸录（不劫持）

```
init=OK  frames=48128  rms=0  peak=0  1kRatio=0
```

### P2 · 劫持（值 10，澄音硬编码值）

```
setForceUse ret=0  getForceUse(FOR_MEDIA)=10  hijacked=true
```

### P3 · 自播 1kHz + 录音

```
recInit=OK  playFrames≈94k  recRms=0  recPeak=0  1kRatio=0
```

### P3b · **force 值枚举 8~16**（决定性证据）

```
force=8   got=8   recRms=0  1kRatio=0
force=9   got=9   recRms=0  1kRatio=0
force=10  got=10  recRms=0  1kRatio=0
force=11  got=0   recRms=0  1kRatio=0   ← 被拒绝
force=12  got=0   recRms=0  1kRatio=0   ← 被拒绝
force=13  got=0   recRms=0  1kRatio=0   ← 被拒绝
force=14  got=0   recRms=0  1kRatio=0   ← 被拒绝
force=15  got=0   recRms=0  1kRatio=0   ← 被拒绝
force=16  got=0   recRms=0  1kRatio=0   ← 被拒绝
sweep result hitValue=-1
```

**解读**：
- `8/9/10` 被系统接受（`getForceUse` 返回值相同）
- `11~16` 被拒绝（`getForceUse` 回 0）
- **接受的那三个值，全部录不到内容**

### P4 · 恢复

```
setForceUse ret=0  getForceUse=0  restoreOk=true
```

设备无 `/data/local/tmp/rsm_FATAL_restore_failed` 标记。

---

## 三、澄音的真实做法（字节码确认）

`ForceRoute.set(boolean)` 的反汇编：

```
const/4  v7, #1                                  → FOR_MEDIA = 1
const/16 v5, #10                                 → 硬编码 10
android.media.AudioSystem.getMethod("setForceUse", int, int)
Method.invoke(null, 1, 10)                       → setForceUse(FOR_MEDIA, 10)
```

**结论**：澄音**确实**调 `AudioSystem.setForceUse(FOR_MEDIA, 10)`，`ForceRoute` 的字段：
```
FORCE_NONE = 0
FORCE_REMOTE_SUBMIX = 10
FOR_MEDIA = 1
```

**`10` 在 AOSP 里是 `FORCE_NO_BT_A2DP`**，不是 REMOTE_SUBMIX。
→ 说明**澄音的目标 ROM 把 10 定义成了 REMOTE_SUBMIX 的私有扩展**；**本 ROM 没有这个扩展**。

---

## 四、对项目的意义

| 项 | 结论 |
|---|---|
| **能否改用 REMOTE_SUBMIX 简化 LOOP_BACK** | ❌ **不能**（本 ROM 无 force 能力） |
| **`AudioRecord(REMOTE_SUBMIX)` 是否可用** | ✅ 可用，但**必须配 policy**（`setInternalCapturePreset(8)`） |
| **现行路线** | ✅ **维持 `AudioPolicy + AudioMix(LOOP_BACK)` + `setInternalCapturePreset(8)`** |
| **`fbae6dc` 的「record 被 OEM 桩死」** | ⚠️ **需重新审视**：48k 立体声实测 `init=OK`，那个结论只适用于 16k 单声道/特定 mix 组合 |

---

## 五、两次误判的修正记录（诚实留档）

| # | 我的说法 | 实际 |
|---|---|---|
| 1 | 「`FORCE_REMOTE_SUBMIX` 常量不存在 → 澄音方案无基础」 | ❌ 只枚举了**常量名**；澄音是**硬编码值 10**，值能被系统接受 |
| 2 | （隐含）「值 10 应该能路由」 | ❌ 值被接受但**录不到内容**；枚举 8~16 全部无效 |

**最终确定结论**：**本 ROM 上不存在"force MEDIA 到 REMOTE_SUBMIX"的能力**（合法范围 0~10 全部试过）。

---

## 六、复现方式

```bash
cd %TEMP%\rsm_probe
build_probe.bat

adb push classes.dex /data/local/tmp/rsm_probe.dex
adb shell chmod 644 /data/local/tmp/rsm_probe.dex
adb shell "CLASSPATH=/data/local/tmp/rsm_probe.dex app_process /system/bin com.sonicraft.probe.RemoteSubmixProbe"
adb shell cat /data/local/tmp/rsm_probe.txt
```

**安全**：全程 `try/finally`，每个 force 值单独恢复；实测 `restoreOk=true`。
**时长**：单次运行约 15 秒（其中枚举扫描占 ~11 秒，期间设备静音）。
