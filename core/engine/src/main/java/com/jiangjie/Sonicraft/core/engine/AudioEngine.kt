/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.engine

import android.media.projection.MediaProjection
import com.jiangjie.Sonicraft.core.audio.AudioFormat
import com.jiangjie.Sonicraft.core.audio.EngineMode
import kotlinx.coroutines.flow.StateFlow

/**
 * 音效引擎门面。
 *
 * 职责：编排 DSP 管线，维护单一状态流。实际信号路径（2026-11-02 架构澄清，随
 * AudioSource/AudioSink 死接口清理）：
 * Kotlin 采集层（CaptureRelaySource 内录推送回调）→ JNI pushInput → native 引擎
 * （DspGraph 与 AAudio 输出均在 native 侧）；投影 token 经 [startCapture] 传入。
 */
interface AudioEngine {
    val state: StateFlow<EngineState>
    val stats: StateFlow<EngineStats>
    val currentMode: EngineMode?

    /**
     * 输出流停摆警示（2026-11-03 全静音事故）：看门狗检测到 processCount 5s 不增长
     * 且重建失败时挂非 null 描述，重建成功自动清空。2026-11-03 事故里看门狗每 5s
     * 静默重试全败 22 分钟零日志零感知——此处保证「流在死、在重建、在失败」对 UI 可见。
     */
    val outputStalled: StateFlow<String?>
        get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    /** 准备引擎：创建 DSP 图并自检。成功进入 [EngineState.Ready]。 */
    suspend fun prepare(mode: EngineMode, format: AudioFormat = AudioFormat.STEREO_48K)

    fun start()
    fun suspend()
    fun resume()

    /** Mode B 专用入口：持有 MediaProjection 后接线内录源 + 输出。[excludedUids] 为不捕获的 app uid。 */
    fun startCapture(projection: MediaProjection, excludedUids: IntArray = IntArray(0))

    /**
     * P1 专用入口：用 [LoopbackPcmSource]（特权侧 AudioPolicy/LOOP_BACK）
     * 作为输入源，替代 MediaProjection。可抓 opt-out app（酷狗 ALLOW_CAPTURE_BY_SYSTEM
     * + 0x400）的流。
     * @return false = 源启动失败（调用方回退）
     */
    fun startLoopback(source: LoopbackPcmSource): Boolean = false

    /** 运行时设置噪声门限（RMS）：0 = 禁用，>0 = 输入 RMS 低于该值时输出平滑静音。 */
    fun setNoiseGateThreshold(threshold: Float) {}

    /** 测试模式：引擎输出替换为 440Hz 正弦波（验证输出通路）。 */
    fun setTestTone(enabled: Boolean) {}

    /**
     * 音频取证（M6.1）：开始双端抓 seconds 秒 PCM 到 dir。返回 false = 未运行/失败。
     * 默认空实现（false）。
     */
    fun dumpAudio(dir: String, seconds: Int): Boolean = false

    /** 运行时下发 DSP 节点参数（非旁路型）。默认空实现。 */
    fun setParam(nodeIndex: Int, paramId: Int, value: Float) {}

    /** 运行时切换节点旁路（A/B 标尺）。默认空实现。 */
    fun setBypass(nodeIndex: Int, bypass: Boolean) {}

    /** 空间环绕声像定位自检（非实时；返回多行文本报告）。默认空实现。 */
    fun vsSelfTest(): String = ""

    /**
     * 自定义脚本效果器（2026-11-12 原型）：下发类 C（SCVM）脚本源。非实时线程调用；
     * 旁路状态下即时重载，否则下次引擎重建生效。默认空实现。
     */
    fun setScriptSource(source: String, instance: Int = 0) {}

    /** 自定义脚本效果器：脚本使用的 slider 位图（bit i = slider(i+1) 被引用）。默认 0。 */
    fun scriptSliderMask(instance: Int = 0): Int = 0

    /** 自定义脚本效果器：各 slider 范围（out[2i]=min,[2i+1]=max）。默认 false。 */
    fun scriptSliderRanges(out: FloatArray, instance: Int = 0): Boolean = false

    /** 自定义脚本效果器：toggle/select/button 控件描述（JSON）。默认空数组。 */
    fun scriptControls(instance: Int = 0): String = "[]"

    /** 自定义脚本效果器：只做编译检查（不生效）。默认无错误。 */
    fun checkScript(source: String): String = ""

    /** 自定义脚本效果器：启停某个 lane（0..2）。默认空实现。 */
    fun setScriptLaneEnabled(instance: Int, enabled: Boolean) = Unit

    /** 自定义脚本效果器：槽位拓扑（true = 并联）。默认空实现。 */
    fun setScriptLaneParallel(instance: Int, parallel: Boolean) = Unit

    /** 自定义脚本效果器：并联支路混合比例（0..2）。默认空实现。 */
    fun setScriptLaneWet(instance: Int, wet: Float) = Unit

    /** 自定义脚本效果器：槽位处理顺序（lane 索引排列）。默认空实现。 */
    fun setScriptLaneOrder(a: Int, b: Int, c: Int) = Unit

    /** 自定义脚本效果器：RMS 探针（后台诊断，不进 UI）。默认无数据。 */
    fun scriptRmsLevels(out: FloatArray): Boolean = false

    /** 自定义脚本效果器：机架级干湿（0 全干 / 1 全湿）。默认空实现。 */
    fun setScriptRackWet(wet: Float) = Unit

    /** 自定义脚本效果器：机架整体延迟（样本）。默认 0。 */
    fun scriptLatency(): Int = 0

    /** 自定义脚本效果器：各 slider 默认值（out[0..7]=值,[8..15]=是否声明）。默认 false。 */
    fun scriptSliderDefaults(out: FloatArray, instance: Int = 0): Boolean = false

    /** 自定义脚本效果器：最近一次编译错误（空 = 无错误）。默认空。 */
    fun scriptError(instance: Int = 0): String = ""

    /**
     * 输出缓冲帧数（用户可调，2026-11-06）。
     * 越大越抗尖峰（不爆音）、延迟越高；frames<=0 恢复默认（2×burst，下限 384 帧）。
     */
    fun setBufferFrames(frames: Int) {}

    /**
     * 加载外部 IR 文件（wav/.irs/.ir/pcm/fir；iir 递归系数不适用卷积架构会被拒绝）
     * 到卷积混响 slot 4（M4c.2）。非实时线程调用（调用方协程内）。
     * 返回 null = 成功（之后 SetParam(CONVOLVER, PRESET, 4) 切换）；
     * 非 null = 失败原因。IR 会被重采样到引擎采样率、截断到 2.7s 上限并做 -80dB 尾部裁剪。
     */
    fun loadImpulseResponse(path: String): String? = "引擎未就绪"

    /**
     * 卷积尾分区上限（低端机负载分档）：各预设有效尾分区钳到 min(t_, limit)，
     * 0 = 不限制。引擎未创建时实现方应暂存，创建后补发。默认空实现。
     */
    fun setTailPartitionLimit(maxParts: Int) {}

    /**
     * 多频段每段计量：9 个 float，[band*3 + 0/1/2] = 入峰值 / GR(dB) / 出峰值，
     * band 0=低 1=中 2=高。null = 引擎未就绪或不支持。
     * 供多段压限 UI 的 IN/GR/OUT 电平表，UI 侧按 ~10fps 轮询即可（块级快照，非采样级）。
     */
    fun multibandMeters(): FloatArray? = null

    /**
     * 单段压缩器计量：{输入峰值, 增益衰减 dB, 输出峰值, GR 峰值保持 dB}。
     * null = 引擎未就绪或无压缩器节点（调用方跳过本次刷新，不清零已有读数）。
     */
    fun compressorMeters(): FloatArray? = null

    /**
     * 病态脏前置自动对齐开关（方案 A，2026-10-26）：开=装载 IR 时裁除脏前置
     * （-96~-60dB 且 >30ms）静音前缀，干湿对齐；纯零前置（设计型延迟）不裁。
     * 引擎未创建时实现方应暂存，创建后补发。默认开。默认空实现。
     */
    fun setTrimSickHead(enabled: Boolean) {}

    /** 用户 IR 分析数据：[energyL, energyR, frames, L包络(512), R包络(512)]；未加载返回 null */
    fun irAnalysis(): FloatArray? = null

    /**
     * EQ 输出实时频谱（64 带 dB，-60..0，峰值保持）：手绘区衬底用。
     * FFT 在 UI 拉取线程；引擎未运行返回 null。默认空实现（null）。
     */
    fun eqSpectrum(): FloatArray? = null

    /**
     * 卷积混响预设显示名（native X-macro 单源下发；索引与 companion 的 REVERB_PRESET_*
     * 常量对齐）。null/空 = 不可用（native 库未加载），UI 侧需按序号降级显示。
     */
    fun convolverPresetNames(): Array<String>? = null

    fun stop(reason: StopReason = StopReason.USER_REQUESTED)

    companion object {
        /**
         * 运行图节点索引（链序约定，与 NativeAudioEngine 构造顺序一致；2026-11-12
         * 加入脚本效果器后共 14 节点）：
         * 输入Gain(0) → Balance(1) → EQ(2) → 动态低音(3) → 虚拟低音(4) → 低音增强(5) →
         * Comp(6) → 多频段(7) → Conv(8) → 算法混响(9) → Width(10) → 输出Gain(11) →
         * 脚本效果器(12) → Lim(13)
         * 铁律：输入 Gain/Balance 钉死链首（源校正区）、Comp/多频段/输出 Gain/Lim 钉死
         * 链尾（动态尾段）、Lim 绝对末级、效果类节点在两者之间。卷积混响与算法混响并列于
         * 效果区（index 5/6），运行时二者互斥激活（其一旁路）；单段压缩器(3) 与多频段(4)
         * 互斥激活由 Kotlin 层状态机联动。
         */
        const val INPUT_GAIN_NODE_INDEX = 0
        const val BALANCE_NODE_INDEX = 1
        const val EQ_NODE_INDEX = 2

    /** AutoEq 耳机校正节点：与 [EQ_NODE_INDEX] 同为 ParametricEqNode，参数区完全独立 */
    const val AUTO_EQ_NODE_INDEX = 3
        // 多段 EQ：93 参容量（[0,31)增益 [31,62)Q [62,93)频率 93=段数 10/15/31）
        const val EQ_PARAM_BAND_COUNT = 93
        const val EQ_PARAM_PREGAIN_DB = 94  // M9 批3 总增益（dB ±12）
        const val EQ_Q_BASE = 31
        const val EQ_FREQ_BASE = 62
        const val EQ_TYPE_BASE = 95  // M9 AutoEQ 前置：段类型（0=PK 1=LSC 2=HSC，95~125）
        /**
         * 虚拟环绕节点：位于 AutoEq 之后、低频/动态之前。
         * 默认 bypass=true，由 prefs `vs_enabled` 控制；参数 id 见 [VS_PARAM_*]。
         */
        const val VIRTUAL_SURROUND_NODE_INDEX = 4

        const val DYNAMIC_BASS_NODE_INDEX = 5
        const val VIRTUAL_BASS_NODE_INDEX = 6
        const val BASS_ENHANCE_NODE_INDEX = 7
        const val COMPRESSOR_NODE_INDEX = 8
        const val MULTIBAND_NODE_INDEX = 9
        const val CONVOLVER_NODE_INDEX = 10
        const val ALGORITHMIC_REVERB_NODE_INDEX = 11
        const val STEREO_WIDTH_NODE_INDEX = 12
        const val OUTPUT_GAIN_NODE_INDEX = 13

        /**
         * 自定义脚本效果器：位于 OutputGain 之后、Limiter 之前，
         * 保证 Limiter 仍是绝对末级（用户脚本 boost 也会被限幅兜住）。
         * 默认 bypass=true，由 prefs `script_effect_enabled` 控制。
         */
        const val SCRIPT_EFFECT_NODE_INDEX = 14

        /** Limiter 绝对末级（index 15）。 */
        const val LIMITER_NODE_INDEX = 15

        // ---- 虚拟环绕参数 id（与 native `vs::Param` 一一对应，跨语言契约）----
        const val VS_PARAM_ENABLED = 0
        const val VS_PARAM_MODE = 1
        const val VS_PARAM_SURROUND_GAIN = 2
        const val VS_PARAM_CENTER_GAIN = 3
        const val VS_PARAM_REAR_GAIN = 4
        const val VS_PARAM_FRONT_GAIN = 5
        const val VS_PARAM_DISTANCE = 6
        const val VS_PARAM_ENCODE_STRENGTH = 7
        const val VS_PARAM_CENTER_MODE = 8
        const val VS_PARAM_DIALOG = 9
        const val VS_PARAM_DIALOG_ENABLED = 10
        const val VS_PARAM_BASS_ENHANCER = 11
        const val VS_PARAM_BASS_ENHANCER_MODE = 12
        const val VS_PARAM_REAR_CENTER_ENABLED = 13
        const val VS_PARAM_REAR_CENTER_DELAY_MS = 14
        const val VS_PARAM_HRTF_MODEL = 15
        const val VS_PARAM_HRTF_MODE = 16
        const val VS_PARAM_HEIGHT_STRENGTH = 17
        const val VS_PARAM_EARLY_REFLECTION = 18
        const val VS_PARAM_AMBISONIC = 19
        const val VS_PARAM_STEREO_EXPAND = 20
        const val VS_PARAM_HEAD_TRACK = 21
        const val VS_PARAM_HEAD_YAW = 22
        const val VS_PARAM_ROOM_SIZE = 23
        const val VS_PARAM_ROOM_WET_DRY = 24

        // ---- 逐声道 trim（dB，−12~+12）与静音 ----
        // 顺序固定 FL/FR/C/SL/SR/CS/LFE，与 native vs::Param 的 25~38 一一对应
        const val VS_PARAM_TRIM_L = 25
        const val VS_PARAM_TRIM_C = 26
        const val VS_PARAM_TRIM_R = 27
        const val VS_PARAM_TRIM_SL = 28
        const val VS_PARAM_TRIM_SR = 29
        const val VS_PARAM_TRIM_CS = 30
        const val VS_PARAM_TRIM_LFE = 31
        const val VS_PARAM_MUTE_L = 32
        const val VS_PARAM_MUTE_C = 33
        const val VS_PARAM_MUTE_R = 34
        const val VS_PARAM_MUTE_SL = 35
        const val VS_PARAM_MUTE_SR = 36
        const val VS_PARAM_MUTE_CS = 37
        const val VS_PARAM_MUTE_LFE = 38

        /**
         * 低音增强参数 id（与 native `BassEnhanceNode` 常量对齐）。
         * **跨语言契约**：任一侧改动都要同步。
         */
        // 动态模式参数
        const val BASS_ENHANCE_PARAM_AMOUNT = 0
        const val BASS_ENHANCE_PARAM_DEEP = 1
        const val BASS_ENHANCE_PARAM_PUNCH = 2
        // 模式切换与经典模式参数（2026-11-11，对照 wecho_projectV2.5.1 的 BassEffect）
        const val BASS_ENHANCE_PARAM_MODE = 3
        const val BASS_ENHANCE_PARAM_CLASSIC_GAIN = 4
        const val BASS_ENHANCE_PARAM_CLASSIC_Q = 5
        const val BASS_ENHANCE_PARAM_CLASSIC_CENTER = 6

        /** 模式取值（与 C++ `kModeClassic` / `kModeDynamic` 对齐） */
        const val BASS_ENHANCE_MODE_CLASSIC = 0
        const val BASS_ENHANCE_MODE_DYNAMIC = 1

        /**
         * 虚拟低音参数 id（与 native `VirtualBassNode` 常量对齐）。
         * **跨语言契约**：任一侧改动都要同步。
         */
        const val VIRTUAL_BASS_PARAM_AMOUNT = 0
        const val VIRTUAL_BASS_PARAM_HARMONIC = 1
        const val VIRTUAL_BASS_PARAM_MID_GAIN = 2
        const val VIRTUAL_BASS_PARAM_HIGH_GAIN = 3
        const val VIRTUAL_BASS_PARAM_ENVELOPE_RATE = 4

        /**
         * 动态低音参数 id（与 native `DynamicBassNode` 常量对齐）。
         * **跨语言契约**：任一侧改动都要同步，漏改会让参数打到错误 id 上（静默失效）。
         */
        const val DYNAMIC_BASS_PARAM_AMOUNT = 0
        const val DYNAMIC_BASS_PARAM_SUBSONIC_HZ = 1
        const val DYNAMIC_BASS_PARAM_FOCUS_HZ = 2
        const val DYNAMIC_BASS_PARAM_BOOST_DEPTH = 3
        const val DYNAMIC_BASS_PARAM_PROTECT_LEVEL = 4

        /** GainNode 参数 id（输入/输出增益共用，与 native GainNode 常量对齐）。 */
        const val GAIN_PARAM_GAIN = 0

        /** BalanceNode 参数 id（-1=全左，0=居中，+1=全右）。 */
        const val BALANCE_PARAM_BALANCE = 0

        /** StereoWidthNode 参数 id（与 native StereoWidthNode 常量对齐）。 */
        const val STEREO_WIDTH_PARAM_WIDTH = 0

        /** ConvolverNode 参数 id（与 native ConvolverNode 常量对齐）。 */
        const val CONVOLVER_PARAM_PRESET = 0

        /** ConvolverNode 湿声电平参数（0~2，全预设生效，1 = 各预设归一基准）。 */
        const val CONVOLVER_PARAM_WET = 1

        /** ConvolverNode 干声电平参数（0~1.2，乘各预设干声增益，1 = 原表值）。 */
        const val CONVOLVER_PARAM_DRY = 2

        /** ConvolverNode 湿声低切 Hz（0=关，40~400，RBJ highpass Q=0.707）。 */
        const val CONVOLVER_PARAM_LOWCUT = 3

        /** ReverbNode（FDN 算法混响，index 5）参数 id（与 native ReverbNode 常量对齐）。 */
        const val REVERB_PARAM_ROOM_SIZE = 0
        const val REVERB_PARAM_HUMIDITY = 1
        const val REVERB_PARAM_STEREO_WIDTH = 2
        const val REVERB_PARAM_WET = 3
        const val REVERB_PARAM_DRY = 4
        const val REVERB_PARAM_MODE = 5

        /** 混响预设（索引与 native ConvolverNode X-macro 枚举强同步，harness 7p 锁值；
         *  显示名经 [convolverPresetNames] 由 native 下发，勿在此硬编码文案）。
         *  1-4 = 实测 RIR（680ms 完整尾）。 */
        const val REVERB_PRESET_DRY = 0
        const val REVERB_PRESET_SMALL_ROOM = 1
        const val REVERB_PRESET_MEDIUM_ROOM = 2   // 客厅 RIR
        const val REVERB_PRESET_HALL = 3          // 大房间 RIR
        const val REVERB_PRESET_CATHEDRAL = 4     // 教堂 RIR（源 2.5s，截 680ms）
        const val REVERB_PRESET_USER = 5          // 自定义 IR（loadImpulseResponse 成功后可选）

        /** LimiterNode 参数 id（与 native LimiterNode 常量对齐）。 */
        const val LIMITER_PARAM_CEILING_DB = 0
        const val LIMITER_PARAM_RELEASE_MS = 1

        /** LimiterNode true-peak 检测开关（1=开）：BS.1770 式 4x 过采样重构真峰估计，
         *  防 DAC Gibbs 过冲穿透 ceiling。native 默认 0（harness 默认链 bit-exact 不破），
         *  Kotlin 引擎构链后由 applyAllParams 统一下发 1。 */
        const val LIMITER_PARAM_TRUE_PEAK = 2

        /** CompressorNode 参数 id（与 native CompressorNode 常量对齐）。 */
        const val COMPRESSOR_PARAM_THRESHOLD_DB = 0
        const val COMPRESSOR_PARAM_RATIO = 1
        const val COMPRESSOR_PARAM_ATTACK_MS = 2
        const val COMPRESSOR_PARAM_RELEASE_MS = 3
        const val COMPRESSOR_PARAM_MAKEUP_DB = 4
    /** 拐点宽度（dB，0 = 硬拐点，2026-11-09 新增） */
    const val COMPRESSOR_PARAM_KNEE_DB = 5
    /** 侧链高通截止（Hz，0 = 关闭；2026-11-09 加，默认 120，暂未暴露 UI） */
    const val COMPRESSOR_PARAM_SIDECHAIN_HP_HZ = 6
    /** 并联混合（0 = 全干 / 1 = 全湿；2026-11-11） */
    const val COMPRESSOR_PARAM_MIX = 7
    /** 检测模式（0 = peak / 1 = RMS；2026-11-11） */
    const val COMPRESSOR_PARAM_DETECT_MODE = 8
    /** 前瞻（ms，0~10；2026-11-11） */
    const val COMPRESSOR_PARAM_LOOKAHEAD_MS = 9
    /** 过采样（1 = 关闭 / 2 = 2x；2026-11-11） */
    const val COMPRESSOR_PARAM_OVERSAMPLE = 10

        /**
         * MultibandNode（多频段压限，index 4）参数 id（与 native MultibandNode
         * 常量对齐）。每段六参：thr(dB)/ratio(:1)/atk(ms)/rel(ms)/knee(dB)/gain(dB)，
         * 三段独立 knee；+ 两个分频点。knee 区间 [0,12]dB，段增益 [-12,12]dB。
         */
        const val MULTIBAND_PARAM_LOW_THRESHOLD_DB = 0
        const val MULTIBAND_PARAM_LOW_RATIO = 1
        const val MULTIBAND_PARAM_LOW_ATTACK_MS = 2
        const val MULTIBAND_PARAM_LOW_RELEASE_MS = 3
        const val MULTIBAND_PARAM_LOW_KNEE_DB = 4
        const val MULTIBAND_PARAM_LOW_GAIN_DB = 5
        const val MULTIBAND_PARAM_MID_THRESHOLD_DB = 6
        const val MULTIBAND_PARAM_MID_RATIO = 7
        const val MULTIBAND_PARAM_MID_ATTACK_MS = 8
        const val MULTIBAND_PARAM_MID_RELEASE_MS = 9
        const val MULTIBAND_PARAM_MID_KNEE_DB = 10
        const val MULTIBAND_PARAM_MID_GAIN_DB = 11
        const val MULTIBAND_PARAM_HIGH_THRESHOLD_DB = 12
        const val MULTIBAND_PARAM_HIGH_RATIO = 13
        const val MULTIBAND_PARAM_HIGH_ATTACK_MS = 14
        const val MULTIBAND_PARAM_HIGH_RELEASE_MS = 15
        const val MULTIBAND_PARAM_HIGH_KNEE_DB = 16
        const val MULTIBAND_PARAM_HIGH_GAIN_DB = 17
        const val MULTIBAND_PARAM_SPLIT1_HZ = 18
        const val MULTIBAND_PARAM_SPLIT2_HZ = 19
    }
}
