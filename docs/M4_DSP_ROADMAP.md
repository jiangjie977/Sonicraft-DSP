# M4 DSP 算法 v1 — 实施路线

> 状态：待实施（本文档为路线设计，非实现记录）
> 参照：WEcho V2.5.1（上游源码，C++ native）、
> 万向（某同类 App，纯 Java）两套实现

## 0. 起点与终点

- **起点**：M3 已打通「capture → DspGraph（PassThroughNode 直通）→ AAudio 输出」，实时链路稳定
  （DSP 单块 0.005ms / 预算 2.67ms，水位无漂移）。当前**听到的是内录副本原样**，用户无法分辨处理前后。
- **终点**：用户能明显听出「处理后的声音」，且处理是**可组合、可调、可旁路**的。
- **验证标尺（先行确定，避免做完无法证明）**：现有「测试正弦」按钮升级为 **A/B 开关**——
  DSP 旁路 = 原始副本，旁路关闭 = 处理后声音。**没有这个标尺，M4 无法验收。**

## 1. 关键结论：两套参照的实现路线

| 维度 | WEcho（native） | 万向（纯 Java） | **Sonicraft 决策** |
| --- | --- | --- | --- |
| 链编排 | 编译期固定 `operator>>` 串流（`AudioProcessor.hpp:761-769`） | 编译期固定，Java 层逐个调 | **编译期固定**（DspGraph 已是）+ **预留运行时排序接口** |
| 效果抽象 | `class Effect { run(span<float>); priority(); reset(); }`（`effect.hpp:53-81`） | Kotlin `object` 单例 | 已有的 `DspNode`（`dsp_node.hpp:21-36`）足够，不改 |
| 参数下发 | **X-macro 三处对齐**的 121 项 paramId（`enum.h:51-179`）+ `param_map<std::any>` | SharedPreferences 轮询 + 整链 reload | **paramId X-macro 单一定义源**（Kotlin 侧生成，避免三处手抄） |
| **参数平滑** | **`CrossFader` 三实例交叉淡化**（`crossFader.hpp:48-86`）——重活在 UI 线程，音频线程只做指针交换 + 淡化 | **无平滑**（直接写 static 字段，拖滑块必咔哒） | **必做**，抄 WEcho 的 CrossFader 思路 |
| 第三方音效挂载 | **ScriptEffect：TinyCC 运行时编译用户 C 代码** + `wecho_dsp_c_api.h`（暴露 biquad/delay/convolver/harmonic C API）+ Flutter 正则解析 `PARAM()` 自动生成 UI | 无 | v1 **不做**（见 §5），但节点契约预留 |
| IR 文件 | 内置 IR 编译进 .so（rir_small/medium/large/cathedral.hpp）；`setIrPath` 支持外部路径，失败回退单位冲激旁路 | assets 内 3 个 IR；`.irs` 文件被当 WAV 喂进解码器→**必然失败**（疑似 bug） | 卷积走 FFT/OLA + **加载失败必须回退单位冲激**（抄 WEcho 的容错） |
| 链的必要性 | 前置 12 个「设备模拟」类效果（DeviceSimulation/ChannelBalance/DiffSurrounding…）为真机试听而生 | — | **不抄**：手机外放本就这么设计，只做可证伪的算法 |

**对 M4 最有价值的两条借鉴**：① CrossFader 三实例参数平滑；② IR 加载失败的单位冲激回退。
**明确不抄**：万向的「无参数平滑直写」与「`.irs` 当 WAV 解码」（前者必爆音，后者是 bug）。

## 2. 实施顺序（每步都以「能听出差别」为完成标准）

### 步骤 1：A/B 标尺 + 参数平滑底座（不动算法）
- `DspNode` 增加 `SetParam(param_id, value)` 虚函数（当前只有 `SetBypass`）
- 实现 `CrossFader`-style 平滑：参数变更经 `ParamQueue` 投递，实时线程内**线性 ramp** 到新值
  （先用最简单的「每块向目标逼近」的 one-pole/线性斜坡，不做三实例——v1 参数都是标量，斜坡足够）
- host-harness 自检：参数斜坡不得产生断点（连续性断言）
- **验收**：正弦按钮切旁路/不旁路切换无咔哒

### 步骤 2：10 段图示均衡器（先做 ParametricEQ，不做纯图示）
- RBJ biquad（peak/lowshelf/highshelf/lowpass/highpass），系数在**非实时线程**预计算
- 参数：10 段 × {频率, 增益, Q} + 总开关，每段可旁路
- 参数斜坡增益（30ms），频率/Q 变更走系数重建（在旁路或淡出状态下重建，见步骤 1 的平滑）
- **验收**：抖音里能明显听出低频提升/削减；实时预算占用 <30%

### 步骤 3：限幅器（Look-ahead soft limiter）
- 阈值/释放时间/增益，末级防削波（内录链路里尤其重要：内录源音量忽大忽小）
- 参考 WEcho `LookAheadSoftLimitEffect` / 万向 `OutputControl`
- **验收**：正弦测试按钮在增益拉满时**不削波不爆音**（这是当前直通链路就存在的风险）

### 步骤 4：IR 卷积混响（FFT 频域 + OLA 重叠相加）
- 载入 IR：WAV 解码（16/24/32-bit PCM + float32）、能量归一、线性插值重采样、**上限截断**（万向 15360，抄这个上限防内存爆）
- 块大小与 FFT 长度联动（`N = 2^ceil(log2(irLen + block - 1))`），实时预算预估
- **加载失败必须回退单位冲激**（旁路），并在 UI 提示
- **验收**：加载一个明显「房间感」的 IR，主观差异显著；实时预算 <50%

### 步骤 5：动态范围压缩（作为 EQ 前置的补充）
- 阈值/比率/attack/release，压缩器放在 EQ 之前（动态增益会影响 EQ 输入电平）
- **链序固定为：`ParametricEQ → Compressor → Limiter → Convolver(IR)`**
  （音量/动态在前，卷积混响在后，避免混响尾音被限幅器压掉）

### 步骤 6：立体声宽度（M/S 矩阵）
- 参考万向 `SurroundEngine`（M/S 缩放 + L/R 交叉馈送），低成本高感知
- **最后做**：它是「锦上添花」型效果，放最后不影响前面验收

## 3. 「其他音效插件怎么挂载」——分三级，v1 只做第 0 级

| 级别 | 机制 | 能力 | 工作量 | 风险 | 计划 |
| --- | --- | --- | --- | --- | --- |
| **0（v1）** | 静态节点：编译期把节点加进 `DspGraph`，UI 按节点 id 生成参数面板 | 内置算法全部可用、可旁路可调 | 小 | 无 | **M4 实施** |
| **1（M5）** | 预设（Preset）：一组 (paramId, value) 命名集合，一键切换 + CrossFader 淡入 | 解决「调参太麻烦」 | 小 | 低 | M5 |
| **2（M6+）** | IR/外部文件挂载：从用户存储加载 IR 波形文件（路径参数化） | 自定义空间感 | 中 | 中（文件解析/采样率对齐） | M6 |
| **3（不做）** | 用户代码插件：TinyCC 运行时编译 / dlopen 第三方 .so | 无限扩展 | 极大 | **高（沙箱、安全、ABI、实时线程安全）** | 不排期 |

**v1 为何不做用户代码插件**：WEcho 的 ScriptEffect 需要维护 C API 头、TinyCC 运行时、编译错误回传、
正则解析 UI 生成四套机制，且用户 C 代码跑在实时线程上（任何死循环/分配即爆音）——对一个刚跑通
主链路的项目，这个风险收益比不划算。若日后确需，**先做级别 1（预设）**覆盖 80% 需求。

**节点契约的扩展预留**（现在就做，成本几乎为零）：`DspNode::Prepare()` 已接收
`(sample_rate, max_frames)`；若将来要运行时增删节点，需要把 `AddNode` 从「配置线程构建期」
扩展为「带 CrossFader 的运行时替换」——这是 WEcho `CrossFader` 的原生用途。v1 只需保证
`nodes_` 容器不在实时线程被修改（当前 `AddNode` 已是配置线程调用，符合）。

## 4. 参数系统设计（X-macro 单一定义源）

仿 WEcho 的三处对齐，但用**代码生成**消除手抄风险：

```
core/dsp/src/main/cpp/sonicraft/dsp/params.xmacro        ← 唯一定义源
  X(PARAM_EQ_ENABLED,   bool)
  X(PARAM_EQ_BAND0_FREQ, float) …（10 段×3 参数）
  X(PARAM_LIMITER_THRESHOLD_DB, float) …

→ C++: enum ParamId { #include "params.xmacro" }           自动展开
→ JNI:  nativeSetParam(int paramId, float/int value)      按类型分发
→ Kotlin: build.gradle 的 generateJavaRes/自定义 task 生成 ParamIds.kt（带注释与量程）
→ Compose: 按 ParamIds 生成参数面板（含 min/max/step/单位）
```

**每个参数必须带元数据**：`(min, max, step, unit, 中文名)`——UI 自动生成，杜绝「UI 与 DSP 各写一套范围」。

## 5. 风险与防线（按今日教训预置）

1. **实时线程分配**：任何 `new/malloc/vector resize` 落在 `Process()` 即爆音。防线：节点内所有缓冲
   在 `Prepare()` 预分配；host-harness 加「重复 Process 不增长分配」的断言。
2. **参数突变咔哒**：防线：所有连续参数必须走斜坡（步骤 1）。
3. **IR/大内存**：IR 上限（15360 采样）、加载失败回退单位冲激（抄 WEcho 容错）。
4. **DFX 许可证**：卷积/限幅等算法均为经典公开算法（RBJ biquad、Schroeder/OLA），**不引入任何
   DFX 专有格式**（这是 M0 选 GPL-3.0 的初衷：规避 LGPL 静态链接与 DFX 专有格式）。
5. **不要重蹈免弹窗覆辙**：不要试图用系统 AudioEffect 挂到 capture 链路上（今日已证 hidden API 封死），
   所有处理留在自有 `DspGraph` 内。

## 6. 里程碑拆分建议

```
M4a  A/B 标尺 + 参数斜坡底座 + ParametricEQ          → 可听出 EQ 差别
M4b  Limiter（防削波）+ Compressor                     → 动态处理
M4c  IR 卷积混响（FFT/OLA）+ 预设切换（M5 前置）        → 可听出空间感差别
M4d  立体声宽度 + host-harness 全面自检 + 真机盲听验证   → 收口
```

每个子里程碑都独立装机、独立验证、独立提交——沿用 M1~M3 已验证有效的节奏。

## 7. 卷积混响升级路线 v2（2026-10-23 重整，参照物：JamesDSP / wecho / JUCE）

> 参照物变更：**弃用第三方 IR 映射实现**（其 4ch 交叉项映射有误、无归一，实现质量不可
> 作基准）。架构参考 **JUCE `dsp::Convolution`**（AGPLv3 只读架构不抄代码）与
> **JamesDSP**（`TwoStageFFTConvolver.c`、`FFTConvolver2x4x2`）；行为对照 wecho。

### 7.0 现状（M4c v1 已完成并验收）

- 全频域 FFT 卷积 + OLA（N=8192，dual-real 非就地 2 次 FFT/块），前台 1.0ms（26%）
- 3 内置合成 IR（去相关 + 干声混合）+ 干声 tap + 节点内 SoftClip 保险丝
- IR 文件加载（WAV/.irs，PCM16/24/32/f32，1-4 声道，extensible，重采样，截断 160ms）
- 文件 IR 专用归一（湿 0.3 / 干 0.75 / 湿峰值 0.6——脉冲型 IR 与干声同相叠加，
  与合成 IR 的 0.5/0.85 分离）
- 实时切换 = 频域预计算 + 指针交换（零停顿）

### 7.1 已知限制（按痛点排序）

1. IR 长度 ≤160ms（N=8192 强约束），真实大厅/长 IR 尾音截断
2. 4ch HRTF 只取对角直达项（ch1/ch4），交叉串音丢失
3. 后台 CPU 降频时单块 ~2-3ms，重度负载仍有 xRun 风险
4. ~~44.1k→48k 线性插值，HRTF 高频镜像杂音~~（已解决：阶段 C CatmullRom 4 点）
5. ~~文件 IR 湿声增益 0.3 固定不可调~~（已解决：M4c.2 阶段 D 湿声滑条）

### 7.2 阶段 A：两阶段分区卷积 ✅ 已落地（2026-10-24，131a07a）

> 实现规格定稿（2026-10-23）。主参考 JUCE `dsp::Convolution`（AGPLv3 只读架构），
> JamesDSP `TwoStageFFTConvolver.c` 对照；**不做后台线程**（实时稀疏处理，无锁）。
>
> **落地记录（与规格的三处偏离，均有 harness 实证）**：
> ① head 段改**时域直接卷积**（H=block=128，每块 32k MAC）——比规格的 FFT_512
>   更便宜且无 OLA 对齐风险；② tail 对齐改**全域位置索引**（tail_out 环形按输出
>   位置读写，写领先读恒 ≤2P、覆盖余量=H，与块长/批次相位无关）——修正旧三级
>   架构 FIFO 水位穿透的结构缺陷；③ 发现并修复 **UPC wrap 项**：每批 IFFT 的
>   2P 帧中后 P 帧是下一批的提前贡献，漏掉会丢所有分区的跨批次尾部贡献
>   （overlap 缓冲补齐）。等效 tail 延迟 = H+P = 1152 帧（24ms@48k，扩散场无感知）。
> **验收**：7j 双通道黄金参照 max_err=2.7e-4（32768 帧逐样本）；7e 宽带 decorr
> ratio=0.49；per-block 92µs（预算 3.4%，较全频域 11×）；harness 全 PASS。
> 真机：实听盲测 ✅ 通过（2026-10-24，tail 平移 24ms 不可感）；后台直播 30min xRun=0 待长跑。

**结构**（H=block=192，M=8×H=1536，L=kMaxIrLen）：

```
IR 分两段：head_ir = ir[0..H)（单分区），tail_ir = ir[H..L)（T = ceil((L-H)/M) 分区）
y[n] = head 贡献（i∈[0,H)，输入不延迟） + tail 贡献（i∈[H,L)，输入 u[n]=x[n-H]）
```

- **head 卷积器**：每块直接跑——x_block(192) 补零到 N1=512 → FFT_512 × head_spec →
  IFFT_512 → OLA（head_tail 缓冲 191 帧，head_ir 长 H=block 故卷积长 2H-1）
  成本：2×FFT_512/块（≈现状全频域的 4%）
- **tail 卷积器**（标准 UPC，频域累加器结构）：
  - 延迟输入流 u = x 延迟 H 帧（u_fifo 环形，初始 H 帧静音自然处理边界）
  - u 攒满 M 帧 → 取最近 M 帧 → FFT_2048 = X
  - 输入块频域缓存 X_cache（环形 T 槽轮转）
  - ACC = Σ_{t=0..T-1} X_cache[t] × tail_spec[t] → IFFT_2048 → M 帧 y_tail
  - y_tail 入 FIFO（按全局输出位置对齐消费，g_out/g_tail_cover 双计数器）
  成本：每 M 帧 2×FFT_2048 + T×2048 复乘，摊销 ~41k 单元/块 vs 全频域 213k → **5×**
- **主输出**：当前块 = SoftClip(dry·x + wet·(y_head[n] + tail_fifo 对齐消费))
- **复杂度守恒断言**：head/tail 分区数与 N1/N2 由 H/M/L 推导，Prepare 一次算死

**状态与切换**：

- spec 结构：head_spec[5][2][N1] + tail_spec[5][2][T][N2]（preset 0-4 全部预计算，
  指针交换切换语义不变；preset 4 加载时只重算 [4] 区）
- 切换/Reset：head_tail(512)、u_fifo、X_cache、tail FIFO、双计数器全清零
  （清零后 ~M 帧过渡：tail 贡献淡出——IR 切换本来就该清尾，可接受）
- 5 条铁律延续：非就地频域、指针交换、dry/wet 查表、SoftClip 位置不变、
  harness 恒等/去相关断言保持

**上限提升** ✅ 已完成（2026-10-24，A 落地后）：kMaxIrLen 7680 → 32768
（~680ms@48k；实际架构 H=128/P=1024 → tail 分区 T=32，X_cache ~1MB +
tail_spec ~6MB 全 preset，per-block 92→139µs 仍为预算 5.2%）。
验收：harness 新增 7k 长 IR 解析端到端（320ms 三脉冲 IR，首/尾分区峰位
与峰值精确、零泄漏）ALL PASS；2s（96k 帧）再评估

**验收**：harness 全 PASS（恒等 max_err≈0、混排块长、去相关）+ 大厅 320ms 合成 IR
恢复（重命名回 15360 长度或新增长版）+ 2s 文件 IR + 后台直播 30min xRun=0 +
per-block 耗时 ≤0.5ms（前台）

**风险与对策**：对齐索引（g_out/g_tail_cover/u_fifo）是主要出错点——实现顺序：
先 head（恒等近似全频域的前 H 部分）→ 再 tail（7g 恒等测试会立刻暴露对齐错误）→
逐段提交，任何一步不过不进下一步。

### 7.3 阶段 B：2×2 交叉卷积（与 A 一起做，原 M4c.3）

- 参考：JamesDSP `FFTConvolver2x4x2`（segmentsLL/LR/RL/RR 四段频域）
- 分区架构天然支持多通道 IR 分段；4ch 文件从对角项升级为完整交叉
- 验收：奥菲斯 HRTF 交叉生效，与对角版 A/B 对比空间感

### 7.4 阶段 C：重采样质量升级 ✅ 已完成（2026-10-24 确认补记）

- 实现：convolver.hpp 文件 IR 加载路径已用 CatmullRom 4 点三次样条
  （参考 JUCE `Interpolators`），镜像衰减 -26dB → -50dB 级，越界索引用边界钳制
- 验收：44.1k HRTF 高频沙沙声消失

### 7.5 阶段 D：文件 IR 湿声增益滑条 ✅ 已完成（M4c.2 阶段 D）

- 实际实现：UI 滑条 0~2（userWetGain 持久化），CONVOLVER_PARAM_WET 下发，
  节点内 30ms 斜坡；干声不再注入 IR，湿尾独立缩放

### 7.6 排除项

- 多频段限幅（后续单独讨论）
- 卷积 wet 独立 EQ（无需求）

### 7.7 实施顺序与依赖

```
C（重采样，当天完成）→ D（wet 滑条，小活）→ A（分区重构主工程）→ B（交叉卷积）
```

### 7.8 M4c.2 已完成部分（2026-10-23，从本路线移入已完成）

- WAV 解析（PCM16/24/32/f32，1-4 声道，WAVE_FORMAT_EXTENSIBLE SubFormat 正确解析
  ——曾因「fseek 跳尾后回读 SubFormat」顺序 bug 导致 extensible 文件全灭，已修）
- 线性插值重采样 + 截断 + 归一（阶段 C 将升级插值器）
- 4ch 对角映射（L=ch0, R=ch3；交叉项待阶段 B）
- SAF 文件选择 + cache 拷贝 + IO 协程加载 + 重启恢复（cache 失效回退大厅）