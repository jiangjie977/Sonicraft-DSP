# Sonicraft

> Android 上的**旁路式 DSP 音效引擎**：把系统正在播放的音频接进自己的处理链，加完音效再送回输出设备。

Sonicraft 不是音乐播放器。它抓取本机正在播放的音频流（任何播放器），在自有 DSP 图上做实时处理，再输出到当前音频设备——因此对音源 App 无侵入，也不需要它们支持音效接口。

---

## 功能

| 模块 | 说明 |
|---|---|
| **卷积混响** | 分区卷积（非均匀分区 UPC），支持自定义脉冲响应；内置多种空间预设与 FDN 算法混响 |
| **参数均衡器（PEQ）** | 1~31 段可调、对数频率轴、节点拖拽、段类型（PK/LSC/HSC）；实际频响曲线实时精算 |
| **AutoEq 耳机校正** | **独立的校正模块**（不改写你的 EQ）：支持 `ParametricEQ` / `FixedBandEQ` / `GraphicEQ` / 结果 CSV 四种导出；导入前曲线预览；耳机曲线库可装载/多选删除 |
| **多段限幅 / 压缩** | 多段动态处理、单段压缩器（并联混合 / 前瞻 / 2× 过采样 / 侧链高通）与真峰值限幅，防止削波 |
| **低频管家** | 三个可独立开关、也可叠加的低频方案：**低音保护**（次低频清理 + 峰值保护）、**低音增强**（经典低通增益 / 动态自适应双模式）、**虚拟低音**（把小型扬声器放不出的低频翻译成可放出的谐波；耳机输出时默认关闭，手动开启会给出风险提示） |
| **立体声宽度 / 平衡 / 增益** | 全链路 float32 |
| **输出设备自适应** | 换耳机/扬声器时自动恢复该设备最近一次的参数；也支持按设备固定配置 |
| **诊断工具** | 端到端音频 dump、频响/失真取证、前台服务状态可视化 |

## 版本

当前 **v0.3.0**（`versionCode 8`）。逐版变更与升级说明见 [`RELEASE_NOTES.md`](docs/RELEASE_NOTES.md)。

## 系统要求

- Android 10（API 29）及以上
- **需要特权通道**：本项目的音频捕获依赖系统级音频策略能力，需通过
  [Stellar](https://github.com/roro2239/Stellar)（Shizuku 的定制分支）以 ADB / Root 方式授权
- 未授权时可退化为 MediaProjection 录制方案（需一次系统录屏授权），功能受限

## 构建

```bash
# 环境：JDK 21、Android SDK（compileSdk 36 / minSdk 29）、NDK 27.0.12077973、
#       CMake 3.22.1、AGP 8.13.2、Kotlin 2.2.0
./gradlew :app:assembleDebug
```

Windows 下使用 `gradlew.bat`。

产物：`app/build/outputs/apk/debug/app-debug.apk`

**DSP 单元验证**（宿主机自检，不依赖 Android 真机）：

```powershell
.\tools\host-harness\build_host.bat
```

预期输出 `host-harness: ALL PASS`。该脚本按 Windows + Visual Studio 2022 BuildTools +
CMake/Ninja 编写，头部写有开发机的工具链路径；在其它机器上请先按本机环境调整脚本头部再运行。

## 结构

```
app/                 Android UI（Jetpack Compose）、服务、ViewModel
core/audio/          音频格式与引擎接口
core/engine/         引擎状态机与 AAudio 绑定
core/dsp/            native DSP（C++）：FFT、分区卷积、EQ、动态、低频、混响
core/common/         公共工具与诊断目录
integration/stellar/ 特权通道（Stellar UserService 接入）
tools/               主机侧 DSP 自检与取证工具
docs/                设计文档、用户手册、架构决策记录
```

## 隐私

- **无网络权限**：应用未声明 `INTERNET`，技术上无法上传任何数据
- 不含统计、广告、推送等第三方 SDK
- 捕获的音频仅在本机内存与 DSP 中处理，不落盘、不外传（主动抓样除外，存于应用私有目录）
- 详见 [隐私政策](docs/PRIVACY_POLICY.md)

## 许可

**混合许可**：

- 项目主体：**GPL-3.0-only**（`LICENSE`）
- `integration/stellar/` 下的 Stellar 代码：**MPL-2.0**（`licenses/MPL-2.0.txt`）

第三方组件归属与义务说明见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。

## 致谢

本项目在 DSP 算法上**派生自开源项目 WEcho（GPL-3.0，Copyright (C) 2026 qumolangmo）**，在此致谢：

- **低音增强（BassEffect）**：低频质感的多段增益 / 动态 / 次谐波结构
- **虚拟低音**：把小型扬声器放不出的低频翻译为可放出的谐波（默认参数与原式对齐）

界面交互（PEQ 数字精确输入、UI 自适应缩放、设置页层级等）参照本人维护的 wecho studio_rack 定制分支，不属于对原作者代码的复制。

WEcho 的完整许可见其上游发布页；本项目沿用 GPL-3.0 分发，相关义务已在 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 第三节声明。

## 免责声明

本项目为个人音频处理工具，按「现状」提供，不保证音质效果与设备兼容性。
请遵守当地法律法规，不要将其用于侵犯他人隐私或著作权的内容获取。
