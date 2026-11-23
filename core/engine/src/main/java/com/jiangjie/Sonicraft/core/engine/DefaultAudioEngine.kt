/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.engine

import android.media.projection.MediaProjection
import com.jiangjie.Sonicraft.core.audio.AudioFormat
import com.jiangjie.Sonicraft.core.audio.CaptureRelaySource
import com.jiangjie.Sonicraft.core.audio.EngineMode
import com.jiangjie.Sonicraft.core.common.DiagDirs
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import com.jiangjie.Sonicraft.core.dsp.DspGraphHandle
import com.jiangjie.Sonicraft.core.dsp.NativeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎实现。
 *  - prepare()：创建 DSP 图并做直通自检（验证 JNI/CMake 链路）。
 *  - start()：按 currentMode 接线音频 IO；CaptureRelay 模式创建 native AAudio 引擎 +
 *    内录源，并启动 4 Hz 状态轮询。
 *  - stop()：释放所有原生资源。
 *
 * 实时线程约束：native 侧 AAudio 回调内零分配零锁；Java 采集线程只做 AudioRecord.read
 * 与一次 JNI 推送，无重活。
 */
@Singleton
class DefaultAudioEngine @Inject constructor() : AudioEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(EngineStats())
    override val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    /** 输出流停摆警示（接口 [AudioEngine.outputStalled]）：非 null = 看门狗重建中/失败 */
    private val _outputStalled = MutableStateFlow<String?>(null)
    override val outputStalled: StateFlow<String?> = _outputStalled.asStateFlow()

    private var graph: DspGraphHandle? = null
    private var nativeEngine: NativeAudioEngine? = null
    private var captureSource: CaptureRelaySource? = null

    /** P1：Loopback 输入源（特权侧 AudioPolicy/LOOP_BACK），与 captureSource 互斥 */
    private var loopbackSource: LoopbackPcmSource? = null
    private var captureProjection: MediaProjection? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * （2026-11-03 审计）：native 句柄全调用点互斥。stop() 会 close+置 null——
     * 原判空快照（`nativeEngine?.x()` 两步：读字段→调用）挡不住「读后删」竞态，
     * UAF 窗口随看门狗每 250ms 开合一次；H1 修复消除了死锁型长窗口，残余短窗口
     * 仍可 SIGSEGV。所有 native 调用（含 close/nativeEngine 写）统一经 nativeLock。
     */
    private val nativeLock = Any()

    /** native 句柄持锁快照调用：null = 引擎已销毁/未创建 */
    private inline fun <T> withNative(block: (NativeAudioEngine) -> T): T? =
        synchronized(nativeLock) { nativeEngine?.let(block) }

    /** 引擎未创建时暂存的尾分区上限（低端机分档在 startCapture 时补发） */
    @Volatile
    private var pendingTailLimit: Int = 0

    /** 引擎未创建时暂存的病态 IR 自动对齐开关（默认开，与 native 默认一致） */
    @Volatile
    private var pendingTrimSickHead: Boolean = true

    /** 看门狗重建连续失败计数（成功即清零；采样写诊断防刷盘） */
    private var rebuildFailStreak: Long = 0L

    /** EQ 参数诊断采样计数（：每 20 条落盘 1 条，避免拖滑条主线程频密 IO） */
    private val eqDiagCount = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    override var currentMode: EngineMode? = null
        private set

    override suspend fun prepare(mode: EngineMode, format: AudioFormat) {
        _state.value = EngineState.Preparing
        runCatching {
            val handle = DspGraphHandle.create(
                sampleRate = format.sampleRate,
                channels = format.channelCount,
                maxFrames = AudioFormat.FRAMES_PER_BLOCK,
            )
            // 直通图就地处理后必须逐样本一致，验证 JNI/CMake 链路可用
            val frames = AudioFormat.FRAMES_PER_BLOCK
            val buffer = FloatArray(frames * format.channelCount) { 0.5f }
            val expected = buffer.copyOf()
            handle.process(buffer, frames)
            check(buffer.contentEquals(expected)) { "DSP 图直通自检失败" }
            handle
        }.onSuccess { handle ->
            graph?.close()
            graph = handle
            currentMode = mode
            _state.value = EngineState.Ready
            SonicraftLog.i(TAG, "prepare 完成 mode=$mode")
        }.onFailure { error ->
            _state.value = EngineState.Failed(error.message ?: "prepare 失败")
            SonicraftLog.e(TAG, "prepare 失败", error)
        }
    }

    override fun start() {
        if (_state.value != EngineState.Ready) {
            SonicraftLog.w(TAG, "start 被忽略：当前状态 ${_state.value}")
            return
        }
        when (currentMode) {
            EngineMode.CaptureRelay -> {
                // CaptureRelay 必须通过 startCapture(projection) 启动（需要 MediaProjection token）
                SonicraftLog.w(TAG, "CaptureRelay 需通过 startCapture(projection) 启动")
            }
            EngineMode.LocalPlayer -> {
                // 占位：LocalPlayer 在后续里程碑接入解码管线
                SonicraftLog.w(TAG, "LocalPlayer 尚未实现，暂以 Idle 处理")
            }
            null -> SonicraftLog.w(TAG, "start 被忽略：未指定模式")
        }
    }

    override fun startCapture(projection: MediaProjection, excludedUids: IntArray) {
        if (_state.value != EngineState.Ready) {
            SonicraftLog.w(TAG, "startCapture 被忽略：当前状态 ${_state.value}")
            return
        }
        val fmt = AudioFormat.STEREO_48K
        val engine = NativeAudioEngine.create(
            fmt.sampleRate,
            fmt.channelCount,
            AudioFormat.FRAMES_PER_BLOCK,
            DiagDirs.crashDir,
        )
        // 低端机分档：尾分区上限须在 native Prepare 前（engine.start() 前）下发
        if (pendingTailLimit > 0) {
            engine.setTailPartitionLimit(pendingTailLimit)
            SonicraftLog.i(TAG, "补发尾分区上限 $pendingTailLimit（引擎创建时暂存）")
        }
        // 病态 IR 自动对齐开关：默认与 native 一致（true），暂存值仅在用户改过时补发
        if (!pendingTrimSickHead) {
            engine.setTrimSickHead(false)
            SonicraftLog.i(TAG, "补发病态 IR 自动对齐=关（引擎创建时暂存）")
        }
        val source = CaptureRelaySource(
            sampleRate = fmt.sampleRate,
            channels = fmt.channelCount,
            projection = projection,
            excludedUids = excludedUids,
            // 投影失效自动止损（2026-11-03 僵尸投影事故）：read 恒负时由采集线程直接
            // 停引擎——state 变 Stopped(CAPTURE_DEAD)，TakeoverController 进程级监听
            // 该状态后回滚接管保直出。若不自动停机，僵尸会话会一直空转且保险丝要等
            // 15s 零电平才触发（2026-11-03 14:05~14:07 实锤：用户听静音 2 分钟）。
            onDead = { reason ->
                val st = _state.value
                if (st is EngineState.Running || st is EngineState.Suspended) {
                    SonicraftLog.e(TAG, "capture 失效自动止损：$reason")
                    stop(StopReason.CAPTURE_DEAD)
                }
            },
            push = { buffer, frames -> engine.pushInput(buffer, frames) },
        )

        if (!source.start()) {
            engine.close()
            projection.stop()
            _state.value = EngineState.Failed("内录源启动失败（请确认已授权 RECORD_AUDIO / PROJECT_MEDIA / MediaProjection）")
            return
        }
        if (!engine.start()) {
            source.stop()
            engine.close()
            projection.stop()
            _state.value = EngineState.Failed("AAudio 输出启动失败")
            return
        }
        captureProjection = projection
        synchronized(nativeLock) { nativeEngine = engine }
        captureSource = source
        // 水位趋势探针：每次启动重置日志，用于长跑验证 capture/输出时钟漂移
        runCatching {
            File(DiagDirs.crashDir, "watermark_log.txt").delete()
        }
        startStatsPolling()
        _state.value = EngineState.Running
        SonicraftLog.i(TAG, "CaptureRelay 已启动")
    }

    /**
     * P1：用 Loopback 源替代 MediaProjection 启动引擎。
     * 与 [startCapture] 的唯一差别是输入源与 projection 生命周期（此处没有 projection）。
     */
    override fun startLoopback(source: LoopbackPcmSource): Boolean {
        if (_state.value != EngineState.Ready) {
            SonicraftLog.w(TAG, "startLoopback 被忽略：当前状态 ${_state.value}")
            return false
        }
        val fmt = AudioFormat.STEREO_48K
        val engine = NativeAudioEngine.create(
            fmt.sampleRate,
            fmt.channelCount,
            AudioFormat.FRAMES_PER_BLOCK,
            DiagDirs.crashDir,
        )
        if (pendingTailLimit > 0) engine.setTailPartitionLimit(pendingTailLimit)
        if (!pendingTrimSickHead) engine.setTrimSickHead(false)

        // 源失效自动止损（同 capture 的 CAPTURE_DEAD 语义）
        source.setOnDead { reason ->
            val st = _state.value
            if (st is EngineState.Running || st is EngineState.Suspended) {
                SonicraftLog.e(TAG, "loopback 失效自动止损：$reason")
                stop(StopReason.CAPTURE_DEAD)
            }
        }

        if (!source.start { buffer, frames -> engine.pushInput(buffer, frames) }) {
            engine.close()
            _state.value = EngineState.Failed(
                "Loopback 源启动失败（Stellar UserService 未连接 / 授权缺失 / AudioPolicy 注册被拒）",
            )
            return false
        }
        if (!engine.start()) {
            source.stop()
            engine.close()
            _state.value = EngineState.Failed("AAudio 输出启动失败")
            return false
        }
        synchronized(nativeLock) { nativeEngine = engine }
        loopbackSource = source
        runCatching { File(DiagDirs.crashDir, "watermark_log.txt").delete() }
        startStatsPolling()
        _state.value = EngineState.Running
        SonicraftLog.i(TAG, "Loopback 已启动")
        return true
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastWatermarkLogTs = 0L
            var lastProcessCount = 0L
            var stallRounds = 0
            while (isActive) {
                val s = withNative { it.stats() } ?: break
                // 看门狗：processCount 连续 20 轮（5s）不增长 = 输出流停摆（微信来电
                // 等路由切换致 AAudio 流 error，native errorCallback 重建失败时的兜底）
                // → 重建输出流。capture 源与 projection 不动（token 一次性，引擎级重启
                // 需重新授权，不可行）。
                if (s.processCount == lastProcessCount) {
                    stallRounds++
                    if (stallRounds >= 20) {
                        stallRounds = 0
                        val ok = withNative { it.restartOutput() } == true
                        if (ok) {
                            SonicraftLog.w(TAG, "看门狗：输出流停摆 5s，已重建输出流")
                            _outputStalled.value = null
                            lastProcessCount = withNative { it.stats() }?.processCount ?: lastProcessCount
                        } else {
                            // 全静音事故：重建失败此前完全静默（事故里连续
                            // 失败 22 分钟零日志零感知）。留痕 + 挂 UI 警示；自愈探针
                            // 解除系统压制（如 PLAY_AUDIO deny）后此处自动恢复。
                            rebuildFailStreak++
                            if (rebuildFailStreak == 1L || rebuildFailStreak % 4L == 0L) {
                                runCatching {
                                    File(DiagDirs.crashDir, "autostart_diag.txt").appendText(
                                        "ts=${System.currentTimeMillis()} 看门狗重建失败" +
                                            " #$rebuildFailStreak（流被系统拒绝/压制，持续重试中）\n",
                                    )
                                }
                            }
                            _outputStalled.value =
                                "输出流停摆，自动重建未成功（第 $rebuildFailStreak 次）。" +
                                    "系统可能压制了本 app 的播放，恢复后自动回到音效。"
                        }
                    }
                } else {
                    if (rebuildFailStreak > 0L) {
                        runCatching {
                            File(DiagDirs.crashDir, "autostart_diag.txt").appendText(
                                "ts=${System.currentTimeMillis()} 看门狗重建成功 " +
                                    "（此前连败 $rebuildFailStreak 次）\n",
                            )
                        }
                        rebuildFailStreak = 0L
                        _outputStalled.value = null
                    }
                    stallRounds = 0
                    lastProcessCount = s.processCount
                }
                // 实时预算：128 帧 @48k = 2.6667 ms = 2_666_667 ns。
                // U9f 修复单位错位：原式 lastProcessNanos / 2666.6667f 是「纳秒 ÷ 毫秒数值」，
                // 实为千分数却被当百分比显示，UI 放大 1000 倍（单帧 0.232ms 显示 87%，真实 8.7%）。
                // 正确：ns ÷ 26_666.667 = 占预算百分比。
                // 口径注：真机 AAudio 回调 192 帧（预算 4ms）时本值偏保守（偏高 ~1.5 倍），方向安全。
                // 实时预算按**真机 192 帧 @48k = 4ms** 计算（2026-11-05 校准）。
                // 旧值用 128 帧（2.67ms）作预算，数值天然偏高约 1.5 倍，会让用户误以为
                // “CPU 快跑满了”。换算：percent = 耗时ns / 4_000_000 * 100。
                val cpuPercent = (s.lastProcessNanos / 40_000f).coerceAtLeast(0f)
                _stats.value = EngineStats(
                    dspCpuLoadPercent = cpuPercent,
                    lastProcessNanos = s.lastProcessNanos,
                    maxProcessNanos = s.maxProcessNanos,
                    totalProcessNanos = s.totalProcessNanos,
                    waterMarkFrames = s.waterMarkFrames,
                    inputLevel = s.inputLevel,
                    framesProcessed = s.processCount,
                    convPrepareCount = s.convPrepareCount,
                    convPreset = s.convPreset,
                    convWetRms = s.convWetRmsScaled / 1_000_000f,
                    nodeAvgNanos = s.nodeAvgNanos,
                )
                // 每 10s 追加一条样本：观察水位趋势 + 数据完整性计数器（丢块/欠载）
                val now = System.currentTimeMillis()
                if (now - lastWatermarkLogTs >= 10_000) {
                    lastWatermarkLogTs = now
                    runCatching {
                        // 逐节点耗时 Top3（ns→µs），用于定位“单帧超预算”的大头
                        val nodeTop = s.nodeAvgNanos.withIndex()
                            .filter { it.value > 0 }
                            .sortedByDescending { it.value }
                            .take(3)
                            .joinToString(",") { "${it.index}:${it.value / 1000}us" }
                        File(DiagDirs.crashDir, "watermark_log.txt")
                            .appendText(
                                "$now waterMark=${s.waterMarkFrames} inputLevel=${s.inputLevel} " +
                                    "ondataMax=${s.ondataMaxFrames} pushDrop=${s.pushDropBlocks} " +
                                    "underrun=${s.underrunBlocks} xRun=${s.xRunBlocks} " +
                                    "idleBypass=${if (s.idleBypassActive) 1 else 0} maxNs=${s.maxProcessNanos} " +
                                    // 卷积混响探针：
                                    // cvPrep 增长⇒图被重建（预设会被重置为 Dry）
                                    // cvPreset=0 ⇒ 当前就是 Dry（无混响）
                                    // cvWet=0 且 cvPreset≠0 ⇒ 卷积状态坏死（NaN/denormal）
                                    "cvPrep=${s.convPrepareCount} cvPreset=${s.convPreset} " +
                                    "cvWet=${"%.4f".format(s.convWetRmsScaled / 1_000_000f)} " +
                                    // avgNs = 累计耗时 / 处理次数：区分「偶发尖峰」与「持续偏高」
                                    "avgNs=${if (s.processCount > 0) s.totalProcessNanos / s.processCount else 0L} " +
                                    "top=[$nodeTop]\n",
                            )
                    }
                }
                delay(250)
            }
        }
    }

    override fun setNoiseGateThreshold(threshold: Float) {
        withNative { it.setNoiseGateThreshold(threshold) }
    }

    override fun setTestTone(enabled: Boolean) {
        withNative { it.setTestTone(enabled) }
    }

    override fun dumpAudio(dir: String, seconds: Int): Boolean {
        return withNative { it.dumpAudio(dir, seconds) } ?: false
    }

    override fun setParam(nodeIndex: Int, paramId: Int, value: Float) {
        // 诊断：EQ 参数下发链路取证。调用方是 Compose 滑块回调（主线程），逐条
        // appendText 等于帧率级主线程磁盘 IO（架构评估 ）——降为每 20 条采样
        // 1 条（首条必写），链路连通性证据保留、拖动路径零阻塞。
        if (nodeIndex == com.jiangjie.Sonicraft.core.engine.AudioEngine.EQ_NODE_INDEX) {
            val n = eqDiagCount.incrementAndGet()
            if (n == 1L || n % 20L == 0L) {
                runCatching {
                    java.io.File(DiagDirs.crashDir, "eq_diag.txt").appendText(
                        "ts=${System.currentTimeMillis()} setParam(#$n) id=$paramId v=$value ne=${nativeEngine != null}\n",
                    )
                }
            }
        }
        withNative { it.setParam(nodeIndex, paramId, value) }
    }

    override fun setBufferFrames(frames: Int) {
        runCatching { nativeEngine?.setBufferFrames(frames) }
    }

    override fun vsSelfTest(): String =
        withNative { it.runVsSelfTest() } ?: "engine not ready"

    override fun setBypass(nodeIndex: Int, bypass: Boolean) {
        if (nodeIndex == com.jiangjie.Sonicraft.core.engine.AudioEngine.EQ_NODE_INDEX) {
            runCatching {
                java.io.File(DiagDirs.crashDir, "eq_diag.txt").appendText(
                    "ts=${System.currentTimeMillis()} setBypass=$bypass ne=${nativeEngine != null}\n",
                )
            }
        }
        withNative { it.setBypass(nodeIndex, bypass) }
    }

    override fun setScriptSource(source: String, instance: Int) {
        withNative { it.setScriptSource(source, instance) }
    }

    override fun scriptSliderMask(instance: Int): Int =
        withNative { it.getScriptSliderMask(instance) } ?: 0

    override fun scriptSliderRanges(out: FloatArray, instance: Int): Boolean =
        withNative { it.getScriptSliderRanges(out, instance) } ?: false

    override fun scriptControls(instance: Int): String =
        withNative { it.getScriptControls(instance) } ?: "[]"

    override fun checkScript(source: String): String =
        withNative { it.checkScript(source) } ?: ""

    override fun setScriptLaneEnabled(instance: Int, enabled: Boolean) {
        withNative { it.setScriptLaneEnabled(instance, enabled) }
    }

    override fun setScriptLaneParallel(instance: Int, parallel: Boolean) {
        withNative { it.setScriptLaneParallel(instance, parallel) }
    }

    override fun setScriptLaneWet(instance: Int, wet: Float) {
        withNative { it.setScriptLaneWet(instance, wet) }
    }

    override fun setScriptLaneOrder(a: Int, b: Int, c: Int) {
        withNative { it.setScriptLaneOrder(a, b, c) }
    }

    override fun scriptRmsLevels(out: FloatArray): Boolean =
        withNative { it.scriptRmsLevels(out) } ?: false

    override fun setScriptRackWet(wet: Float) {
        withNative { it.setScriptRackWet(wet) }
    }

    override fun scriptLatency(): Int = withNative { it.scriptLatency() } ?: 0

    override fun scriptSliderDefaults(out: FloatArray, instance: Int): Boolean =
        withNative { it.getScriptSliderDefaults(out, instance) } ?: false

    override fun scriptError(instance: Int): String =
        withNative { it.getScriptError(instance) ?: "" } ?: ""

    override fun convolverPresetNames(): Array<String>? =
        DspGraphHandle.CONVOLVER_PRESET_NAMES.takeIf { it.isNotEmpty() }

    override fun multibandMeters(): FloatArray? = withNative { it.multibandMeters() }

    // 单段压缩器计量：接口方法必须在**这一层**委托，
    // 只在 NativeAudioEngine（底层 JNI 包装）上加方法是够不到的——
    // AudioEngine 接口的 compressorMeters 默认返回 null，UI 会一直读到 null
    // → 电平表恒为「-- 0.0dB --」（本次实机发现的问题）。
    override fun compressorMeters(): FloatArray? = withNative { it.compressorMeters() }

    override fun setTailPartitionLimit(maxParts: Int) {
        val ok = withNative {
            it.setTailPartitionLimit(maxParts)
            true
        } ?: false
        if (!ok) {
            // 引擎尚未创建（内录未启动）：暂存，startCapture 创建引擎后、start() 前补发
            pendingTailLimit = maxParts
        }
    }

    override fun setTrimSickHead(enabled: Boolean) {
        val ok = withNative {
            it.setTrimSickHead(enabled)
            true
        } ?: false
        if (!ok) {
            // 引擎尚未创建（内录未启动）：暂存，startCapture 创建引擎后、start() 前补发
            pendingTrimSickHead = enabled
        }
    }

    override fun loadImpulseResponse(path: String): String? {
        // ：native 调用经锁。哨兵区分「无 native」与「native 返回 null=成功」——
        // 语义：null = 成功，非空 = 失败原因。此前 `r ?: "引擎未就绪"` 把 native 的
        // 成功（null）误转成错误串，导致加载成功却永远不发 SetParam(4)（2026-10-23
        // 自定义 IR「不生效」根因，诊断打点定位：native err=[null] 而上层收到未就绪）。
        val r = withNative { it.loadImpulseResponse(path) ?: LOAD_OK_SENTINEL } ?: run {
            runCatching {
                java.io.File(DiagDirs.crashDir, "autostart_diag.txt")
                    .appendText("ts=${System.currentTimeMillis()} engine.loadIR: hasNative=false state=${_state.value}\n")
            }
            return "引擎未就绪"
        }
        val result = if (r == LOAD_OK_SENTINEL) null else r
        runCatching {
            java.io.File(DiagDirs.crashDir, "autostart_diag.txt")
                .appendText("ts=${System.currentTimeMillis()} engine.loadIR: state=${_state.value} err=[$result]\n")
        }
        return result
    }

    override fun irAnalysis(): FloatArray? {
        return withNative { it.irAnalysis() }
    }

    override fun eqSpectrum(): FloatArray? {
        return withNative { it.eqSpectrum() }
    }

    override fun suspend() {
        if (_state.value == EngineState.Running) {
            // P3-1 Suspended 真语义：采集门控（数据丢弃=引擎输出静音，原声正常出声），
            // 不再是只改 StateFlow 的空洞状态（架构评估 6）
            captureSource?.setPaused(true)
            _state.value = EngineState.Suspended
        }
    }

    override fun resume() {
        if (_state.value == EngineState.Suspended) {
            captureSource?.setPaused(false)
            _state.value = EngineState.Running
        }
    }

    override fun stop(reason: StopReason) {
        statsJob?.cancel()
        statsJob = null
        captureSource?.stop()
        captureSource = null
        // P1：Loopback 源同样要停（其 stop 会撤销 AudioPolicy + release record）
        runCatching { loopbackSource?.stop() }
        loopbackSource = null
        // 僵尸 projection（CAPTURE_DEAD 场景）的 stop 可能向上抛系统层异常，兜住
        // 保证后续 native/graph 资源照常释放
        runCatching { captureProjection?.stop() }
        captureProjection = null
        // ：close 与置 null 必须持锁——否则看门狗/UI 线程读走句柄后 close 即 UAF
        synchronized(nativeLock) {
            nativeEngine?.close()
            nativeEngine = null
        }
        graph?.close()
        graph = null
        currentMode = null
        // L13（2026-11-03 审计）：停止时清停摆横幅与失败计数——否则红条跨会话残留，
        // 下次启动引擎被误读为「仍在重建失败中」
        rebuildFailStreak = 0L
        _outputStalled.value = null
        _stats.value = EngineStats()
        _state.value = EngineState.Stopped(reason)
        SonicraftLog.i(TAG, "stop reason=$reason")
    }

    private companion object {
        const val TAG = "Engine"

        /** ：loadIR 哨兵——native 成功返回 null，需与「无 native」（withNative 返回 null）区分 */
        const val LOAD_OK_SENTINEL = "\u0000load_ok"
    }
}
