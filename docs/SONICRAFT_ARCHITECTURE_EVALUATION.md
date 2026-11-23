# Sonicraft 项目骨架设计评估

> **评测方**：豆包trae评测  
> **评测日期**：2026-10-29  
> **评测范围**：通读源码（Kotlin 外壳 + C++ DSP 内核 + JNI + Stellar 特权集成 + host-harness + CI），**仅评估不改代码**

---

## 总体结论

**主干骨架稳健，属于"实测驱动、分层清晰、可观测性极强"的工程化设计**；但存在 **3 个高危缺陷 + 5 处结构性债务**，其中大部分集中在 **Kotlin 应用层**（而非 C++ DSP 层）。C++ 内核的实时纪律执行度明显高于 Kotlin 层的工程纪律。

| 维度 | 评分 | 判断依据 |
|---|---|---|
| 模块分层 | 8/10 | 依赖方向单向、无循环；但契约层部分空置 |
| 实时线程纪律 | 8/10 | SPSC 无锁 + 预分配 + 参数斜坡，执行到位；个别越界 |
| 并发/内存安全 | 5/10 | JNI 裸指针跨线程读写、缺 happens-before 保护 |
| 生命周期/状态机 | 6/10 | 有状态流，但缺互斥、Suspended 语义空洞 |
| 可测试性 | 5/10 | C++ 侧极强，Kotlin 侧近乎空白 |
| 可维护性 | 5/10 | 2032 行 ViewModel + 1271 行 Composable |
| 可观测性 | 9/10 | 探针文件 + watermark 日志 + 双端取证，行业少见 |

---

## 骨架做得好的地方

### 1. 分层与依赖方向干净

`app → core/{common,audio,engine} → core/dsp(JNI) → integration/{stellar,projection} → tools`，无反向依赖、无循环，模块边界在 `settings.gradle.kts` 一目了然。

### 2. 实时契约被写进接口而不是口头约定

- `dsp_node.hpp` 明确 "process 禁止分配、加锁、系统调用"
- `param_queue.hpp` 用严格的 acquire/release SPSC 队列落地
- `dsp_graph.cpp` 的 planar 缓冲复用到零分配

这是罕见的高质量自律。

### 3. 故障自愈是分层设计的，不是补丁堆叠

- AAudio `errorCallback`（native 侧重建）
- Kotlin 5s `processCount` 看门狗（`DefaultAudioEngine.kt`）
- 接管侧双轨 + 回读验证 + 冷却 + uid 消失超时 + 冷启动回滚（`TakeoverController.kt`）

每层职责明确。

### 4. 可观测性先行

在 ROM 屏蔽 logcat 的环境下把诊断做成产品能力（探针文件、watermark 趋势、audio dump 双端对比），这是这套骨架最值钱的部分，也解释了项目为什么能连克多个"偶发爆音"。

### 5. C++ 回归体系扎实

host-harness 3400+ 行、bit-exact 黄金参照、并发路径 A/B 断言，CI 会真跑。这是骨架里最可信的质量锚点。

---

## 高危问题（建议优先处理）

### ① JNI 侧 `stream_` 裸指针跨线程无保护读写

`native_audio_engine.hpp` 声明为非原子；`GetStats` 在 Kotlin 每 250ms 的线程读它，`RestartOutputStream` 在 error 回调 detach 线程写并置空。存在把已 `close` 的指针交给 `AAudioStream_getXRunCount` 的窗口。同一结构里 `graph_`、`capturing` 等非原子字段也有跨线程读的隐患。

### ② 实时回调线程里仍有文件系统调用

`OnData` 每次进入都调 `BindBigCoresOnce()`，首次会在**回调线程**执行 `fopen` + `fprintf` + `fclose`。虽然只发生一次，但直接违反自家"实时线程禁止系统调用"契约，且这个诊断路径本该搬到启动前。

### ③ 绝对路径硬编码在 C++ 与 Kotlin 双端

至少 7 处（C++ 2 处、Kotlin 5+ 处）硬编码 `/data/data/com.jiangjie.Sonicraft/files/crash`。它锚定 `applicationId`，只要加 debug suffix、改包名、或多用户/工作资料场景就会失效；而所有写入都包在 `runCatching` 里，**失败是静默的** —— 这会让本项目唯一的诊断通道在关键时刻凭空消失。

### ④ 主线程同步文件写

`DefaultAudioEngine.setParam` 每下发一次 EQ 参数就 `appendText` 一次探针；调用方是 Compose 滑块回调（主线程）。拖 EQ 曲线时等于以帧率级别做主线程磁盘 IO。

---

## 结构性债务（不致命，但会随时间放大）

### 1. 节点索引三处硬编码，无任何校验

链序同时存在于 ① C++ 构造顺序（`native_audio_engine.cpp` 构造函数）、② Kotlin `NODE_INDEX` 常量（`AudioEngine.kt` companion）、③ UI 调用点。加一个节点或调整顺序，编译期不会报错，运行期表现为"参数打到了错误模块"（静默音色异常）。这是这套骨架最脆的接缝。

### 2. 契约层名存实亡

`AudioSource` / `AudioSink` 两个接口**没有任何实现**：`CaptureRelaySource` 不是 `AudioSource` 的子类（自己 `start()` + 回调 push），输出由 `NativeAudioEngine` 私有 AAudio 直连。也就是说 `AudioEngine` 注释里宣称的 "Source → DspGraph → AudioSink 三段编排" 在 Mode B 实际是被跳过的。同时存在**两套 DspGraph**：`prepare()` 造的自检图用完即弃，真正跑的是 native 内部那张。

### 3. 参数通道无回读，UI 与 DSP 可永久分叉

队列满时 `Push` 返回 false 被丢弃（`param_queue.hpp` + `native_audio_engine.cpp` 不检查返回值），且没有任何"实际生效值"回读。快速拖动 + 批量下发（EQ 31 段 × 3 参）时存在丢命令导致 UI 显示与听感不一致的可能。

### 4. 测试金字塔倒挂

C++ 有 9a–9i / 7a2 / 10a–10c 这一整套高质量回归；Kotlin 侧在 `app/src/test` 下只有 1 个 `AutoEqParserTest`，而且 CI **只跑 `assembleDebug`，连 `test` 任务都没执行**，也没有 lint/detekt。最容易出事故的逻辑（接管回滚、通话保护、prefs 迁移、EQ 段数切换）零覆盖 —— 而这些恰恰是历史上造成"13 个 app 无声"的地方。

### 5. 接管轮询与省电目标冲突

`TakeoverController` 每 500ms 跑一次跨进程 `dumpsys audio` 并全量正则解析。M10 省电专项只治理了 DSP 链空转，这条常驻的系统调用链没有进入视野。

### 6. 状态机语义不完整

- `Suspended` 只改 StateFlow，不暂停 AudioRecord/DSP（`DefaultAudioEngine.kt`）
- `stop()` 与后台 `startCaptureInBackground()` 之间无互斥锁，快速"停 → 启"或启动中途停止，可能走到「prefs 标记已停、引擎却进入 Running」的半初始化态

---

## 一致性方面

- **文档漂移**：README「M5 链序」仍写 **8 节点**，实际已是 10 节点（含多频段 + FDN）；README 称 `core/audio` 提供 Source/Sink 契约，实际未接线。对一个"文档即设计"的项目，这类漂移会侵蚀文档的可信度。
- **`rir_sonic.hpp` 55236 行**内置 RIR 数据以头文件形式被 `convolver.hpp` include，NDK 与 harness 各编一遍，编译成本高，宜拆为独立 `.cpp` 或资源文件。
- **过度 `runCatching` + 静默吞异常**：全项目大量 `runCatching { ... }` 无日志分支，故障可见性完全依赖那几个探针文件 —— 这又把"硬编码路径 + 静默失败"的风险放大了。

---

## 一句话总结

**DSP 内核与实时骨架是 A 级水准（契约先行 + 位精确回归 + 探针取证），应用层骨架是 B 级（功能极强但耦合过重、并发与测试纪律薄弱）。**

项目当前能稳定运行，靠的是作者个人的深度理解与密集实测，而不是骨架本身的强约束 —— 这意味着**交接成本和后续改动风险偏高**。

最大的三个抓手是：

1. 消除 JNI 指针跨线程竞态
2. 把节点索引变成单一可信源（启动期断言）
3. 把 Kotlin 侧的关键状态机纳入 CI

---

*（本评估基于对项目源码的通读，未做任何修改。）*
