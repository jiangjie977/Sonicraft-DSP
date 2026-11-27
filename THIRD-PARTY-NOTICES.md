# 第三方组件与许可声明

本文件是 Sonicraft 分发时的第三方许可归属声明。**分发 APK 或源码时必须随附本文件。**

## 一、总体许可结构

本项目采用**混合许可**，不是单一许可证：

| 范围 | 许可证 | 文件 |
|---|---|---|
| 项目主体（app / core / 自研 DSP / UI） | **GPL-3.0-only** | `LICENSE`、`licenses/GPL-3.0.txt` |
| `integration/stellar/src/main/java/roro/**` | **MPL-2.0** | `licenses/MPL-2.0.txt` |
| `integration/stellar/src/main/java/com/stellar/**` | **MPL-2.0** | 同上 |
| `integration/stellar/src/main/aidl/com/stellar/**` | **MPL-2.0** | 同上 |

**为什么要分开写**：GPL-3.0 覆盖「更大作品」，但 MPL-2.0 是**文件级** copyleft——MPL 覆盖的文件
必须继续以 MPL-2.0 提供，不能因为被并入 GPL 项目就改称 GPL。两者可以共存（MPL-2.0 与
GPL-3.0 兼容），但声明必须准确，否则属于错误的许可证声明。

## 二、Stellar 特权框架（MPL-2.0）

| 项 | 内容 |
|---|---|
| 名称 | Stellar（Shizuku 的深度定制分支） |
| 上游仓库 | https://github.com/roro2239/Stellar |
| 作者 | roro2239 及贡献者 |
| 许可证 | Mozilla Public License 2.0 |
| 本项目中的位置 | `integration/stellar/src/main/java/roro/**`、`java/com/stellar/**`、`aidl/com/stellar/**` |
| 引入方式 | **源码内嵌（vendored）**，非二进制依赖 |

**义务履行说明（MPL-2.0 §3.2）**：本项目对上述文件若有修改，修改后的文件仍按 MPL-2.0 分发；
由于本项目自身即公开源码分发，源码可得性义务已由仓库本身满足。

**用途**：通过 Stellar 的 `UserService` 机制，在获得用户授权的 ADB/Shell 权限下执行系统级
音频策略操作（建立 LOOP_BACK 音频混音、读取 `dumpsys` 音频快照）。不涉及对 Stellar 上游代码的
再分发以外的商业利用限制。

**本项目对该目录的修改**（MPL-2.0 §3.2 要求可识别修改）：

| 日期 | 文件 | 修改内容 |
|---|---|---|
| 2026-11-08 | `userservice/StellarUserService.kt` | `bindUserService` 的异常捕获由仅 `RemoteException` 扩展为 `SecurityException`（新错误码 `-3`）+ `Exception` 兜底。原实现漏捕获 Binder 权限拒绝（`SecurityException` 是 RuntimeException），未授权时异常逃逸到主线程，导致 Activity 崩溃—重启—再崩溃的循环 |

## 三、上游 GPL-3.0 音频项目（源码派生来源）

本项目对上游 GPL-3.0 音频项目（下称「上游项目」）的使用分两类，版权归属分别说明。

### 3.1 派生自原作者代码（保留版权声明）

| 项 | 内容 |
|---|---|
| 名称 | 上游项目（Flutter + C++ DSP 音频应用） |
| 原始作者 | **Copyright (C) 2026 qumolangmo** |
| 许可证 | **GNU General Public License v3.0**（与本项目同许可证，互相兼容） |
| 引入方式 | **源码派生（移植 + 参数对齐）**，非二进制依赖 |

**派生范围**（仅以下两个 DSP 节点，源自原作者上游源码的实现）：

- 低音增强：`core/dsp/.../bass_enhance.hpp`（动态模式与经典模式均参照上游 `BassEffect` 实现）
- 虚拟低音：`core/dsp/.../virtual_bass.hpp`（默认参数、`Harmonic<3>` 多项式、混合公式逐项对齐）

**义务履行说明（GPL-3.0 §5）**：

- 上述两个文件顶部保留 `SPDX-FileCopyrightText: 2026 qumolangmo`，并注明移植来源与本项目修改日期。
- 本项目以 GPL-3.0 分发，与上游许可证兼容；上游源码可得性义务由本仓库满足。

### 3.2 作者本人后续开发的部分（本项目版权）

| 项 | 内容 |
|---|---|
| 范围 | 空间环绕（矩阵环绕 / Ambisonic / Surround71）、HRIR 渲染与数据集接入、采集与接管、UI 与配置栈 |
| 归属 | 由本项目作者独立开发，本项目版权（`SPDX-FileCopyrightText: 2026 jiangjie`） |
| 说明 | 不包含上游项目源码；UI 交互方案由作者在自有分支上迭代，文件头不标注第三方版权 |

### 3.3 实测 HRIR 数据集（第三方数据，仅作卷积核）

| 数据集 | 来源 | 用途 |
|---|---|---|
| MIT KEMAR | sound.media.mit.edu/resources/KEMAR.html | 8 方位 × 557 tap 双耳脉冲响应 |
| KEMAR-Hannover | 公开学术数据集 | 同上 |
| CIPIC | 公开学术数据集（UCDavis CIPIC HRTF Database，subject 021） | 8 方位 × 557 tap（水平）；**8 个上方方向 × 256 tap @48kHz（仰角 35°，用于 4+4 只上方扬声器）** |
| ARI | 公开学术数据集（ARI HRTF Database） | 同上 |
| LISTEN | 公开学术数据集（IRCAM LISTEN） | 同上 |

数据仅以常量数组形式内置为卷积核，未修改数值；数据集本身的使用仍受其原始许可条款约束
（学术研究用途）。若需商用分发，须逐一核对各数据集的最新许可声明。

## 四、构建期依赖（Apache-2.0）

以下依赖通过 Gradle 引入，随 APK 分发二进制。均为 Apache-2.0，与本项目 GPL-3.0 兼容：

| 组件 | 许可证 |
|---|---|
| AndroidX Core / Activity / Lifecycle | Apache-2.0 |
| Jetpack Compose（UI / Material3 / Icons） | Apache-2.0 |
| Dagger Hilt（含 hilt-navigation-compose） | Apache-2.0 |
| Kotlinx Coroutines | Apache-2.0 |
| Zip4j 2.11.5 | Apache-2.0 |

Apache-2.0 要求保留版权与许可声明。各库的完整声明随其 AAR 内 `META-INF/` 一同打包，
应用内「我的 → 隐私与合规 → 开源许可」入口也提供了查阅路径。

## 五、明确不存在的第三方内容

以免审核方反复确认，这里逐项声明：

- **无任何闭源/商业 SDK**：无统计、广告、推送、崩溃上报、社交分享、支付 SDK
- **无内置音频素材**：卷积混响的脉冲响应由算法生成（合成 RIR），不采样自任何录音制品
- **无第三方字体/图标资源**：图标为 Canvas 自绘或 Compose Material Icons（Apache-2.0）
- **native 层无第三方二进制库**：`core/dsp` 仅链接 Android 系统库 `liblog`、`libaaudio`；
  FFT、分区卷积（UPC）、EQ、动态处理、FDN 混响全部为自研实现，无 FFTW / Oboe / FFmpeg。
  （注：低音增强与虚拟低音两个节点派生自上游 GPL-3.0 项目，已在第三节声明；其余 DSP 实现为本项目作者开发）
- **无 GPL 传染性第三方库**：项目自身即为 GPL-3.0，不存在「GPL 库污染闭源项目」问题

## 六、获取源码

本项目以 GPL-3.0 分发，依据 GPL-3.0 第 3 条，获得二进制的用户有权获得对应源代码。

- 源码仓库：https://github.com/jiangjie977/Sonicraft-DSP
- 备用获取方式：通过应用内「我的 → 隐私与合规 → 开源许可」所列联系方式，或在仓库提交 Issue 索取

## 七、变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-08 | 初版：补充 Stellar（MPL-2.0）归属声明、构建期依赖清单、缺失内容声明 |
| 2026-11-12 | 补充 上游项目（GPL-3.0，Copyright (C) 2026 qumolangmo）派生来源声明，修正第五节「全部自研」表述；移植文件补充上游版权行；版本升至 0.2.2 |
