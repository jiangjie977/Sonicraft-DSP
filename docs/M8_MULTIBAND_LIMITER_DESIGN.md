# M8 多频段压限器设计文档与计划书

状态：**评审通过（2026-10-25 用户拍板），实施中**
范围：新增 MultibandNode（三频段压限），与现有单段 CompressorNode（index 3）**互斥运行**。
拍板记录：三段固定；互斥放 Kotlin 层；**knee 三段独立**；默认参数按 §3.3「母带温和式」起点、实听阶段再调。

---

## 一、工作原理

### 1.1 为什么需要多频段

单段压缩器用**宽带检测**：任一频段超阈值，全频带增益一起下降。
典型缺陷——低频鼓点触发阈值时，人声与镲片被连带压低（泵感、发闷、瞬态糊）。

多频段压限的处理路径：

```
            ┌─ LP4(fc1) ── LP4(fc2) ─ [Low 段检测+增益] ──┐
输入 ───────┤                                            ├─ Σ ── 输出
            └─ HP4(fc1) ─┬─ LP4(fc2) ─ [Mid 段检测+增益] ─┘
                         └─ HP4(fc2) ─ [High 段检测+增益] ─
```

各频段独立检测、独立压低，只罚超纲的那段——这是母带链与听音娱乐链通行的动态处理形态。

### 1.2 分频器：Linkwitz-Riley（LR4）

多段压限的行业默认分频族是 Linkwitz-Riley，而非 Butterworth：

| 族 | 阶数/斜率 | 合成条件 | 合成幅度 |
|---|---|---|---|
| Butterworth 3 阶 | 18 dB/oct | — | **有起伏**（相位差 90°，±3dB 波动）→ 不可用 |
| **LR2** | 12 dB/oct | 两路**反相**合成 | 平坦（需倒相一路） |
| **LR4（选用）** | 24 dB/oct | 两路**同相**合成 | **平坦**（\|LP+HP\|=1 ∀ω） |
| LR8 | 48 dB/oct | 同相 | 平坦，但相位弯曲/群延迟更大 |

选 **LR4** 的理由：
- 两路在各频点幅度各 -6dB@fc，**合成幅度响应全频带平坦**（\|LP4+HP4\|=1 ∀jω）——分频不染色的幅度意义下成立；注意合成相位带旋转（等效 2 阶全通特性，IIR 交叉固有），**时域逐样本恒等不成立**，验证判据用「逐频点幅度平坦 + 宽带 RMS 守恒」（见 §五 9a/9c）；
- 24dB/oct 阻带隔离足以让频段间动力学互不误触发；
- 相位弯曲（分频区群延迟抬升）是 LR4 的固有代价，但两路相位响应一致、无时间错位，对压限应用可接受——业界主流母带多段（IIR 路线）均如此；
- IIR 实现：每路 = 2 个级联 Butterworth 二阶节（biquad），**无预延迟、无预振铃**。

**为什么不选线性相位 FIR 分频**：需要 lookahead 预延迟（数百~2048 样本）且低频段 FIR 长度爆炸，预算与链路延迟均不划算；本链已有卷积承担「重计算」，动态尾段保持轻量（与现有 Comp/Lim 的设计哲学一致）。

### 1.3 频段动力学（每段独立）

沿用项目现有 `CompressorNode` 的**拓扑家族**（保持行为一致性与 harness 参照可复用性）：

- **检测器**：峰值包络跟随——瞬时攻击 + 一阶指数衰减（与 compressor.hpp L73-79 同款）。峰值检测响应快、行为可预期，RMS 平滑但引入检测延迟；不做 RMS 备选（参数不膨胀）。
- **静态曲线**：dB 域下压式 `g_db = T + (level_db − T)/ratio − level_db`（level>T 时），与现有一致。
- **soft knee**：新增可调 knee 宽度（0~12dB，0=硬 knee）。三段抛物线过渡：knee 区内 ratio 线性渐入。多段系统的频段衔接处，硬 knee 更易听出动态突变，soft knee 是多频段尤其需要的。
- **增益平滑**：dB 域一阶分支平滑（attack 降 / release 升两分支，与现有一致）。
- **段增益（makeup 兼音色）**：每段独立 gain_db（−12~+12dB）。多段独立增益天然兼做「频段音量平衡」，是母带多段的核心用法之一。
- **不做检测耦合**（单检测器驱动三段）：耦合会退化为单段行为，违背立项本意；母带级耦合选项对听音场景是参数膨胀。

### 1.4 瞬时过冲与兜底

分频+压限后瞬时能量重排可能产生样本过冲。**架构决策：不做 lookahead**——由链路末级现有 `LimiterNode`（index 8，lookahead 2.5ms）兜底。这与现有架构一致（CompressorNode 注释即「允许瞬态轻过冲，末级 Limiter 兜底」），动态尾段保持零延迟。

### 1.5 分频点系数变化的爆音防线

fc1/fc2 参数变化时 biquad 系数直接替换会产生 zipper noise。防线：**block 级系数平滑**——参数命令到达后，新系数与旧系数在 192 帧内线性插值（每块一步），变化代价 O(系数个数)，无逐样本开销。

---

## 二、现状与约束（摸底实证 2026-10-25）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 链序固定：InputGain(0)→Balance(1)→EQ(2)→**Comp(3)**→**多频段(4，M8c 起)**→卷积(5)→FDN(6)→宽度(7)→OutGain(8)→**Lim(9)**；动态尾段钉死 | `AudioEngine.kt` L72-87、`native_audio_engine.cpp` L36-70 |
| 2 | 节点接口：`Process(float* const* channels, int frames)` planar、立体声 2ch@48k、块 192 帧（4ms） | `dsp_graph.cpp` L48-73、`AudioFormat.kt` L25-30 |
| 3 | 参数路由：(node_index, param_id) 二元组，SPSC 队列进实时线程 DrainCommands，每节点 param_id 独立从 0 起 | `dsp_graph.cpp` L35-46、`bridge.cpp` L215-219 |
| 4 | 多频段**链中插入**（index 4，Comp 后卷积前；追加链尾会跑到 Lim 之后违反末级铁律，不可取），后级索引顺移 +1；预设快照按 prefs key 字符串存储、Kotlin 全走命名常量——顺移零迁移 | `native_audio_engine.cpp` 构造段、`AudioEngine.kt` |
| 5 | 项目效果器**全部叠加制，无互斥先例**（卷积+FDN 可同开）；M8 引入首个互斥对（单段 Comp↔多频段，Kotlin 层状态机），互斥联动在 Kotlin 侧 | `MainViewModel.kt` L178 |
| 6 | bypass 机制现成：`setBypass(nodeIndex, bool)`，bypass 时状态照常推进防解除跳变 | `compressor.hpp` L92-97 |
| 7 | harness 惯例：用例内嵌独立参照实现（非金标文件）、逐位回归（7a2 真机块长锁）、`Check()` 断言 | `tools/host-harness/main.cpp` |
| 8 | Kotlin 侧压缩器仅暴露 threshold/ratio 两滑杆，prefs 3 个 key，恢复链路 `applyEqSettings()` 依序补发 | `MainScreen.kt` L486-503、`MainViewModel.kt` L1345-1360 |

---

## 三、架构设计

### 3.1 节点接入

- 新文件 `core/dsp/src/main/cpp/sonicraft/dsp/multiband.hpp`（header-only，随 CompressorNode 惯例），类 `MultibandNode`。
- **index = 9**，graph 构造顺序插在 **Comp(3) 之后、卷积(4) 之前**——与单段压缩同职能位（干声动态塑形），链序铁律（动态处理不越过 Lim）不破坏。
- 实现内部为**级联树**：fc1 分 Low/(Mid+High)，fc2 再分 Mid/High。每通道 8 个 biquad（2 分频点 × LR4 两路 × 2 级），两通道共 16。

### 3.2 互斥运行（单段 ↔ 多频段）

方案：**Kotlin 层状态机互斥，native 两节点独立**（不动引擎层，层间职责不变）：

- `setMultibandEnabled(true)` 内部依序：先 `setBypass(COMP, true)` → 再 `setBypass(MB, false)`；反向同理。两条命令天然经 SPSC 队列按序生效，切换期间最多一帧「双旁路」（静音衔接，无爆音风险）。
- UI：压缩器卡片顶部加模式切换（单段/多频段），切换即走上述联动；单段参数与多频段参数独立持久化、互不覆盖。
- 恢复链路 `applyEqSettings()`：恢复 compressor_enabled 前先查 multiband_enabled——若多频段开着则**跳过单段 unbypass**（防重启后双活）。
- prefs 新 key：`multiband_enabled` + 20 个参数 key（见 3.3）；预设快照/导出同步扩展。

### 3.3 参数表（MultibandNode，param_id 从 0 起）

| id | 参数 | 范围 | 默认 |
|---|---|---|---|
| 0-5 | Low: threshold_db / ratio / attack_ms / release_ms / knee_db / gain_db | −60~0 / 1~20 / 0.1~100 / 5~1000 / 0~12 / −12~+12 | −26 / 2.5 / 20 / 180 / 6 / 0 |
| 6-11 | Mid: 同上六参 | 同上 | −22 / 2.0 / 15 / 150 / 6 / 0 |
| 12-17 | High: 同上六参 | 同上 | −20 / 2.0 / 5 / 120 / 6 / 0 |
| 18 | fc1_hz（Low/Mid 分频点） | 50~500 | 200 |
| 19 | fc2_hz（Mid/High 分频点） | 1000~8000 | 3500 |

共 20 个 param_id；总开关走 `setBypass(9, ...)`（节点惯例，不占 param_id）。Kotlin 镜像常量入 `AudioEngine.kt` companion（`MULTIBAND_PARAM_*`）。默认值即「母带温和式三段」起点，真机实听阶段再调。

---

## 四、实时预算（192 帧 @48kHz，双通道）

| 环节 | 计算 | 每样本 flop |
|---|---|---|
| 分频（16 biquad） | 每 biquad TDF2 ≈ 9 flop | ~144 |
| 检测+曲线+平滑（3 段×2ch） | 每段 ~15 flop | ~90 |
| 求和合成 | 2ch×3 加 | ~6 |
| **合计** | | **~240 flop/样本 ≈ 11.5 MFLOPS** |

对照：当前卷积批次实测 0.71ms/4ms 周期（占空比 ~18%），多频段计算量约为其 8%，预计 maxNs 增量 ~0.06ms（<2% 音频线程）。**预算充裕**；真机 M8e 阶段以 watermark_log 的 maxNs/underrun 实测复核，验收线：maxNs 增量 <0.15ms 且 underrun=0。

---

## 五、验证方案（host-harness，用例编号 9 段——8 已被 stereo_width 占用）

| 用例 | 内容 | 判据 |
|---|---|---|
| 9a 分频平坦性 | 11 频点稳态正弦，三段求和 vs 输入 RMS | 全频带偏差 <0.1dB（fc 附近重点） |
| 9b 频段隔离 | 25Hz 正弦入（距 fc1=200Hz 近 3 倍频程） | Mid/High 段残余 <−40dB |
| 9c 宽带能量守恒 | 白噪+1kHz 正弦混合（threshold=0 不压缩） | RMS 偏差 <0.1dB + NaN/发散守门（IIR 合成带相位旋转，时域逐点恒等不成立——逐频点平坦由 9a 覆盖） |
| 9d 静态曲线 | 单频正弦稳态，扫 threshold/ratio | 各段压缩量 vs 曲线 ±1.5dB（同 6a 口径） |
| 9e bypass 逐位一致 | 超阈值信号 bypass | 逐样本 <1e-6（同 6d，防假阳性） |
| 9f NaN/跳变 | 参数快速扫描 + 静音/满幅交替 | 无 NaN、样本差 <阈值（同 6c） |
| 9g fc 阶跃 dezipper | 分频点 200→2000Hz 阶跃，600Hz 探测信号 | 块间 RMS 突变 <3.5dB（LR4 合成幅度恒等保证能量连续；样本级相位重排为块级系数方案固有行为）+ 阶跃前后 600Hz 归属 Mid→Low 翻转 |
| 9h 块长不变性 | 参数静止下 192/2048 混排 vs 128 基线 | bit-exact（IIR 逐样本同构；fc 平滑期间块长相关，不在检验范围） |

---

## 六、实施计划（逐步实现，每阶段独立提交）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M8a** | `multiband.hpp` 分频器（LR4 级联树 + 每通道独立滤波器组 + fc 块级平滑）+ harness 9a/9b/9c | ALL PASS |
| **M8b** | 频段动力学（检测/曲线/knee/平滑/段增益）+ harness 9d/9e/9f/9g | ALL PASS |
| **M8c** | 引擎接入（index 4 链中插入、后级顺移）+ Kotlin 常量镜像 + JNI 路由验证 + harness 9h | ALL PASS |
| **M8d** | Kotlin UI（模式互斥切换 + 三段折叠面板）+ prefs/恢复链路互斥 + 预设快照扩展 | 装机自测 |
| **M8e** | 真机预算实测（maxNs/水位/underrun）+ 默认参数实听调音（**用户复听确认**）+ README M8 小节收口 + 变更履历（内部记录）| 预算线达标 + 用户确认 |

---

## 七、决策记录（2026-10-25 用户拍板）

1. **频段数 3 段固定**（Low/Mid/High，wecho 式三分频同构）——不做成 2/3/4 可选。✅
2. **互斥层选在 Kotlin**（native 两节点独立）。✅
3. **knee 三段独立**（每段六参组内各含 knee_db，与 §3.3 参数表一致）。✅
4. **默认参数**按 §3.3 起点，M8e 实听再调。✅
