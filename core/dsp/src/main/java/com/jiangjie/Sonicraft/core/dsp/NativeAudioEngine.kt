/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.dsp

import kotlin.jvm.JvmStatic

/**
 * 内录接管实时引擎的 Kotlin 句柄：封装 native 侧 AAudio 输出 + 输入环形缓冲 + DspGraph。
 *
 * 采集线程（AudioRecord + AudioPlaybackCapture）通过 [pushInput] 喂入交织 float；
 * 输出在 native AAudio 回调中完成，本对象不直接持有输出缓冲。
 *
 * 注意：库加载在 [DspGraphHandle] 的 companion 已完成（同模块同 .so）；此处再 load 一次
 * 仅为防御性（JVM 对重复 loadLibrary 幂等）。
 */
class NativeAudioEngine private constructor(private var handle: Long) : AutoCloseable {

    val isRunning: Boolean get() = handle != 0L && nativeIsRunning(handle)

    fun start(): Boolean = handle != 0L && nativeStart(handle)

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /** 采集线程调用：写入交织 float 输入（frames = buffer.size / channels） */
    fun pushInput(buffer: FloatArray, frames: Int) {
        if (handle != 0L) nativePushInput(handle, buffer, frames)
    }

    /** 运行时设置噪声门限（输入 RMS）：0 = 禁用 */
    fun setNoiseGateThreshold(threshold: Float) {
        if (handle != 0L) nativeSetNoiseGate(handle, threshold)
    }

    /** 测试模式：输出替换为 440Hz 正弦波 */
    fun setTestTone(enabled: Boolean) {
        if (handle != 0L) nativeSetTestTone(handle, enabled)
    }

    /**
     * 音频取证（M6.1）：开始双端抓 seconds 秒 PCM 到 dir，文件对
     * audio_in_*.f32 / audio_out_*.f32。
     * 返回 false = 引擎未就绪或文件打开失败；已在抓取中则幂等返回 true。
     */
    fun dumpAudio(dir: String, seconds: Int): Boolean {
        return handle != 0L && nativeDumpAudio(handle, dir, seconds)
    }

    /** 运行时下发 DSP 节点参数（非旁路型） */
    fun setParam(nodeIndex: Int, paramId: Int, value: Float) {
        if (handle != 0L) nativeSetParam(handle, nodeIndex, paramId, value)
    }

    /** 运行时切换节点旁路（A/B 标尺） */
    fun setBypass(nodeIndex: Int, bypass: Boolean) {
        if (handle != 0L) nativeSetBypass(handle, nodeIndex, bypass)
    }

    /** 自定义脚本效果器：下发脚本源（非实时；旁路状态下即时重载） */
    fun setScriptSource(source: String, instance: Int = 0) {
        if (handle != 0L) nativeSetScriptI(handle, instance, source)
    }

    /** 自定义脚本效果器：脚本引用 slider 的位图（bit i = slider(i+1)） */
    fun getScriptSliderMask(instance: Int = 0): Int =
        if (handle != 0L) nativeGetScriptSliderMaskI(handle, instance) else 0

    /** 自定义脚本效果器：各 slider 范围（out[2i]=min,[2i+1]=max） */
    fun getScriptSliderRanges(out: FloatArray, instance: Int = 0): Boolean =
        handle != 0L && nativeGetScriptSliderRangesI(handle, instance, out)

    /** 启停某个脚本 lane（0..2）；启用时 native 会补一次 Prepare */
    fun setScriptLaneEnabled(instance: Int, enabled: Boolean) {
        if (handle != 0L) nativeSetScriptLaneEnabled(handle, instance, enabled)
    }

    /** 槽位拓扑：true = 并联（取干声副本、按 wet 累加），false = 串联（主链就地） */
    fun setScriptLaneParallel(instance: Int, parallel: Boolean) {
        if (handle != 0L) nativeSetScriptLaneParallel(handle, instance, parallel)
    }

    /** 并联支路混合比例（0..2，默认 1/3） */
    fun setScriptLaneWet(instance: Int, wet: Float) {
        if (handle != 0L) nativeSetScriptLaneWet(handle, instance, wet)
    }

    /** 槽位处理顺序（拖拽换序）：lane 索引的排列 */
    fun setScriptLaneOrder(a: Int, b: Int, c: Int) {
        if (handle != 0L) nativeSetScriptLaneOrder(handle, a, b, c)
    }

    /** RMS 探针（后台诊断用，不进 UI）：{机架入, 机架出, lane0 入/出, lane1 入/出, lane2 入/出} */
    fun scriptRmsLevels(out: FloatArray): Boolean =
        handle != 0L && nativeGetScriptRmsLevels(handle, out)

    /** 机架级干湿：0 = 全干（透传），1 = 全湿（默认） */
    fun setScriptRackWet(wet: Float) {
        if (handle != 0L) nativeSetScriptRackWet(handle, wet)
    }

    fun getScriptRackWet(): Float =
        if (handle != 0L) nativeGetScriptRackWet(handle) else 1.0f

    /** 脚本机架整体延迟（样本，convfft 为 512） */
    fun scriptLatency(): Int =
        if (handle != 0L) nativeGetScriptLatency(handle) else 0

    /** 只编译检查（不影响正在运行的脚本）：返回错误，空 = 通过 */
    fun checkScript(source: String): String =
        if (handle != 0L) nativeCheckScript(handle, source) else ""

    /** 自定义脚本效果器：toggle/select/button 控件描述（JSON 数组） */
    fun getScriptControls(instance: Int = 0): String =
        if (handle != 0L) nativeGetScriptControlsI(handle, instance) else "[]"

    /** 自定义脚本效果器：各 slider 默认值（out[0..7]=值,[8..15]=是否声明） */
    fun getScriptSliderDefaults(out: FloatArray, instance: Int = 0): Boolean =
        handle != 0L && nativeGetScriptSliderDefaultsI(handle, instance, out)

    /** 自定义脚本效果器：最近一次编译错误（null = 无错误） */
    fun getScriptError(instance: Int = 0): String? {
        if (handle == 0L) return null
        return nativeGetScriptErrorI(handle, instance).ifEmpty { null }
    }

    /**
     * 加载外部 IR 文件（wav/.irs/.ir/pcm/fir；iir 不适用卷积架构）到卷积混响 slot 4。
     * 非实时线程调用（Kotlin 协程）。返回 null = 成功；非 null = 失败原因。
     */
    fun loadImpulseResponse(path: String): String? {
        return if (handle != 0L) nativeLoadImpulseResponse(handle, path) else "引擎未就绪"
    }

    /**
     * 尾分区上限（低端机负载分档）：各预设卷积尾的有效分区数被钳到 min(t_, limit)。
     * 须在 start() 前调用（Prepare 前生效）；0 = 不限制。
     */
    fun setTailPartitionLimit(maxParts: Int) {
        if (handle != 0L) nativeSetTailPartitionLimit(handle, maxParts)
    }

    /** 病态脏前置自动对齐开关（默认开，下次装载生效） */
    fun setTrimSickHead(enabled: Boolean) {
        if (handle != 0L) nativeSetTrimSickHead(handle, enabled)
    }

    /**
     * 多频段每段计量（2026-11-08，供 UI 的 IN/GR/OUT 电平表）。
     * 返回 9 个 float：[band*3 + 0/1/2] = 入峰值(线性) / GR(dB) / 出峰值(线性)，
     * band 0=低 1=中 2=高。null = 引擎未就绪或无多频段节点。
     * 非实时：UI 侧按 ~10fps 轮询。
     */
    fun multibandMeters(): FloatArray? {
        if (handle == 0L) return null
        val out = FloatArray(9)
        return if (nativeMultibandMeters(handle, out)) out else null
    }

    /** 单段压缩器计量（2026-11-09）：{输入峰值, 增益衰减 dB, 输出峰值} */
    fun compressorMeters(): FloatArray? {
        if (handle == 0L) return null
        val out = FloatArray(4)
        return if (nativeCompressorMeters(handle, out)) out else null
    }

    /**
     * 用户 IR 分析数据 v4.1（1556 floats，加载成功后非 null）：
     * [0] energyL [1] energyR [2] frames [3..514] L 包络 [515..1026] R 包络 [1027] 源通道数
     * [1028] 布局版本=4 [1029] 源采样率 [1030] 前置静音 ms [1031] 主脉冲 ms
     * [1032] R/L 能量比 dB [1033] L/R 主脉冲错位 ms [1034] NaN 数 [1035] Inf 数
     * [1036] 峰值 [1037] 削波占比 [1038] 双声风险 0/1/2 [1039] 谱声道数 N
     * [1040] 前置噪声 dB（≤-96 干净=设计型延迟；-96~-60 导出残留不建议忽略）
     * [1041] 装载时自动裁除的前置 ms（0=未裁，UI「已自动对齐」横幅）
     * [1042..1042+N*128) 每声道 128 点 dB 谱（峰 0dB / 底 -100dB）
     * [1554] 湿声电平自动校准建议（批次 2，0.6~1.5；未装载=1）
     */
    fun irAnalysis(): FloatArray? {
        return if (handle != 0L) nativeGetIrAnalysis(handle) else null
    }

    /** 重建 AAudio 输出流（微信来电等路由切换致流死时的看门狗兜底） */
    fun restartOutput(): Boolean {
        return handle != 0L && nativeRestartOutput(handle)
    }

    /**
     * EQ 实时频谱双层（128 floats：0..63=EQ 入口、64..127=EQ 出口，各 64 带 dB
     * -60..0，峰值保持）：手绘区衬底用。FFT 在 UI 拉取线程；引擎未运行返回 null。
     */
    fun eqSpectrum(): FloatArray? {
        return if (handle != 0L) nativeGetEqSpectrum(handle) else null
    }

    /**
     * 设置输出缓冲帧数（用户可调，2026-11-06）。
     * frames<=0 → 恢复默认（2×burst，下限 384 帧 ≈8ms）。
     */
    fun setBufferFrames(frames: Int) {
        if (handle != 0L) nativeSetBufferFrames(handle, frames)
    }

    fun stats(): NativeEngineStats {
        val raw = if (handle != 0L) nativeStats(handle) else null
        if (raw == null || raw.size < 6) return NativeEngineStats()
        return NativeEngineStats(
            lastProcessNanos = raw[0],
            maxProcessNanos = raw[1],
            totalProcessNanos = raw[2],
            processCount = raw[3],
            waterMarkFrames = raw[4],
            inputLevelScaled = raw[5],
            ondataMaxFrames = if (raw.size >= 7) raw[6] else 0L,
            pushDropBlocks = if (raw.size >= 8) raw[7] else 0L,
            underrunBlocks = if (raw.size >= 9) raw[8] else 0L,
            xRunBlocks = if (raw.size >= 10) raw[9] else 0L,
            idleBypassActive = if (raw.size >= 11) raw[10] != 0L else false,
            convPrepareCount = if (raw.size >= 12) raw[11] else 0L,
            convPreset = if (raw.size >= 13) raw[12] else 0L,
            convWetRmsScaled = if (raw.size >= 14) raw[13] else 0L,
            nodeAvgNanos = if (raw.size >= 24) raw.copyOfRange(14, 24) else LongArray(0),
        )
    }

    override fun close() {
        if (handle != 0L) {
            nativeStop(handle)
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeSetBufferFrames(handle: Long, frames: Int)

    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativePushInput(handle: Long, buffer: FloatArray, frames: Int)
    private external fun nativeSetNoiseGate(handle: Long, threshold: Float)
    private external fun nativeSetTestTone(handle: Long, enabled: Boolean)
    private external fun nativeDumpAudio(handle: Long, dir: String, seconds: Int): Boolean
    private external fun nativeSetParam(handle: Long, nodeIndex: Int, paramId: Int, value: Float)
    private external fun nativeSetBypass(handle: Long, nodeIndex: Int, bypass: Boolean)
    private external fun nativeSetScript(handle: Long, source: String)
    private external fun nativeGetScriptSliderMask(handle: Long): Int
    private external fun nativeGetScriptSliderRanges(handle: Long, out: FloatArray): Boolean
    private external fun nativeGetScriptControls(handle: Long): String
    private external fun nativeCheckScript(handle: Long, source: String): String
    private external fun nativeGetScriptSliderDefaults(handle: Long, out: FloatArray): Boolean
    private external fun nativeGetScriptError(handle: Long): String
    // 多 lane（instance）版本
    private external fun nativeSetScriptLaneEnabled(
        handle: Long, instance: Int, enabled: Boolean,
    )
    private external fun nativeSetScriptLaneParallel(
        handle: Long, instance: Int, parallel: Boolean,
    )
    private external fun nativeSetScriptLaneWet(handle: Long, instance: Int, wet: Float)
    private external fun nativeSetScriptLaneOrder(
        handle: Long, a: Int, b: Int, c: Int,
    )
    private external fun nativeGetScriptRmsLevels(handle: Long, out: FloatArray): Boolean
    private external fun nativeSetScriptRackWet(handle: Long, wet: Float)
    private external fun nativeGetScriptRackWet(handle: Long): Float
    private external fun nativeGetScriptLatency(handle: Long): Int
    private external fun nativeSetScriptI(handle: Long, instance: Int, source: String)
    private external fun nativeGetScriptSliderMaskI(handle: Long, instance: Int): Int
    private external fun nativeGetScriptSliderRangesI(
        handle: Long, instance: Int, out: FloatArray,
    ): Boolean
    private external fun nativeGetScriptControlsI(handle: Long, instance: Int): String
    private external fun nativeGetScriptSliderDefaultsI(
        handle: Long, instance: Int, out: FloatArray,
    ): Boolean
    private external fun nativeGetScriptErrorI(handle: Long, instance: Int): String
    private external fun nativeStats(handle: Long): LongArray?
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("sonicraft-dsp")
        }

        /**
         * 创建引擎。[diagDir] 为诊断文件目录（crash/），由 app 层经 [DiagDirs] 注入，
         * native 侧诊断写（绑核/流重建）将落到该目录；空串 = 不写 native 诊断。
         */
        fun create(
            sampleRate: Int,
            channels: Int,
            maxFrames: Int,
            diagDir: String = "",
        ): NativeAudioEngine {
            val handle = nativeCreate(sampleRate, channels, maxFrames, diagDir)
            check(handle != 0L) { "native AudioEngine 创建失败" }
            return NativeAudioEngine(handle)
        }

        @JvmStatic
        private external fun nativeLoadImpulseResponse(handle: Long, path: String): String?

        /** 尾分区上限（低端机负载分档）：须在 start() 前调用；0 = 不限制 */
        @JvmStatic
        private external fun nativeSetTailPartitionLimit(handle: Long, maxParts: Int)

        @JvmStatic
        private external fun nativeSetTrimSickHead(handle: Long, enabled: Boolean)

        /** 多频段每段计量：out 长度 >= 9 时写入 [band*3+0/1/2] = in/gr/out */
        @JvmStatic
        private external fun nativeMultibandMeters(handle: Long, out: FloatArray): Boolean

        /**
         * 单段压缩器计量：out 长度 >= 3 = {输入峰值, 增益衰减 dB, 输出峰值}。
         *
         * **`@JvmStatic` 不可省**（2026-11-09 踩坑）：companion 内不加它时，Kotlin 会把方法
         * 定义在 `NativeAudioEngine$Companion` 上，JNI 名变成
         * `Java_..._NativeAudioEngine_00024Companion_nativeCompressorMeters`；
         * 而 C++ 侧按 `Java_..._NativeAudioEngine_nativeCompressorMeters` 导出 →
         * **运行时找不到符号**，每次刷新抛 UnsatisfiedLinkError（表现为展开压缩器
         * 卡片即「闪退」，进程被 FGS 拉起后回到桌面）。
         */
        @JvmStatic
        private external fun nativeCompressorMeters(handle: Long, out: FloatArray): Boolean

        /** 用户 IR 分析：[energyL, energyR, frames, L包络(512), R包络(512)] */
        @JvmStatic
        private external fun nativeGetIrAnalysis(handle: Long): FloatArray?

        /** 重建 AAudio 输出流（错误回调失败时的看门狗兜底；非实时线程调用） */
        @JvmStatic
        private external fun nativeRestartOutput(handle: Long): Boolean

        /** EQ 输出实时频谱：64 带 dB（-60..0，峰值保持） */
        @JvmStatic
        private external fun nativeGetEqSpectrum(handle: Long): FloatArray?

        @JvmStatic
        private external fun nativeCreate(
            sampleRate: Int,
            channels: Int,
            maxFrames: Int,
            diagDir: String,
        ): Long
    }
}

data class NativeEngineStats(
    val lastProcessNanos: Long = 0,
    val maxProcessNanos: Long = 0,
    val totalProcessNanos: Long = 0,
    val processCount: Long = 0,
    val waterMarkFrames: Long = 0,
    val inputLevelScaled: Long = 0,
    val ondataMaxFrames: Long = 0,
    /** PushInput 因 ring 满被丢弃的输入块数（非 0 = 供给过冲） */
    val pushDropBlocks: Long = 0,
    /** OnData 欠载补零次数（非 0 = 输出瞬断，每次都是咔哒声） */
    val underrunBlocks: Long = 0,
    /** AAudio 输出流自身 underrun 计数（DSP 超时 → 输出饿 = 可闻噪声） */
    val xRunBlocks: Long = 0,
    /** 空载旁路激活（M9 省电：输入静音 4s 后 DSP 链暂停，信号恢复自动解除） */
    val idleBypassActive: Boolean = false,
    /** 卷积器 Prepare 次数（增长 = 图被重建，预设会被重置为 Dry） */
    val convPrepareCount: Long = 0,
    /** 卷积器当前预设（0 = Dry，即无混响） */
    val convPreset: Long = 0,
    /** 卷积器湿声 RMS ×1e6（0 且预设非 Dry ⇒ 卷积状态坏死） */
    val convWetRmsScaled: Long = 0,
    /** 逐节点平均耗时（ns/次），索引对应节点 0..9（2026-11-06 负载定位用） */
    val nodeAvgNanos: LongArray = LongArray(0),
) {
    /** 输入 RMS 电平（0..1） */
    val inputLevel: Float get() = (inputLevelScaled / 1_000_000f).coerceIn(0f, 1f)
}
