/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import kotlin.math.roundToInt
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiangjie.Sonicraft.core.audio.AudioFormat
import com.jiangjie.Sonicraft.core.audio.EngineMode
import com.jiangjie.Sonicraft.core.common.DiagDirs
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import com.jiangjie.Sonicraft.core.engine.StopReason
import android.os.IBinder
import android.os.PowerManager
import com.jiangjie.Sonicraft.privilege.ILoopbackProbe
import com.jiangjie.Sonicraft.privilege.LoopbackProbeUserService
import com.jiangjie.Sonicraft.privilege.PrivilegedClient
import roro.stellar.userservice.ServiceMode
import roro.stellar.userservice.StellarUserService
import roro.stellar.userservice.UserServiceArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import org.json.JSONArray

/** Capture 策略扫描结果（2026-11-04 诊断页）：rows=(包名,uid,policy)；
 *  error 非空=扫描失败原因（特权未连/dumpsys 失败/包列表空）——必须上 UI，
 *  此前静默返回空列表导致「点扫描出 0」无法定位。 */
data class CapturePolicyScan(
    val rows: List<Triple<String, Int, Int>>,
    val error: String?,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val engine: AudioEngine,
    private val privileged: PrivilegedClient,
    private val takeover: TakeoverController,
    private val loopbackChain: LoopbackChainHolder,
    private val outputDeviceMonitor: OutputDeviceMonitor,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 允许导入的 IR 扩展名白名单（.iir 是 IIR 滤波器，卷积架构拒绝）。须在 init 前声明 */
    private val irExtensions = setOf("wav", "irs", "pcm", "fir", "ir")

    /**
     * IR 装载互斥（P1，2026-10-27 自检）：loadLibraryIr / setTrimSickHead 重装 /
     * applyEq 恢复链路三个 IO 协程入口可能并发进入 native 装载，交错写 slot 5
     * spec——串行化保证同一时刻只有一次完整装载（native 侧另有 t_eff 关闸防
     * 实时线程读撕裂，双保险）。
     */
    private val irLoadMutex = Mutex()

    // P3-2 启停生命周期互斥：stop 与后台 startCaptureInBackground 并发时，
    // 保证「要么完整启动后停、要么根本没启动」——消除半初始化态（架构评估 6）
    private val lifecycleMutex = kotlinx.coroutines.sync.Mutex()

    init {
        // b 专用脉冲目录：旧根目录 ir_*.wav 一次性迁入 filesDir/pulse/，并按
        // 旧 JSON 记账还原中文显示名。必须先于库列表扫描执行。
        migrateLegacyIrFiles()
        // 低端机负载分档：命中已知低端平台 → 卷积尾分区封顶
        // 32（教堂 2.5s 全尾 118 分区 → ~700ms），批次负载降至 ~27%。中高端不限制。
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.uppercase() else ""
        val hw = Build.HARDWARE.uppercase()
        val lowTierKeys = listOf(
            "MT6761", "MT6762", "MT6765", "MT8168", "QCM6125",
            "SM4250", "SM6115", "TRINKET", "BENGAL", "UMS512",
        )
        if (lowTierKeys.any { soc.contains(it) || hw.contains(it) }) {
            engine.setTailPartitionLimit(32)
            SonicraftLog.i("MainViewModel", "低性能平台命中：卷积尾分区封顶 32（soc=$soc hw=$hw）")
        }
    }

    /** 自动接管开关（M3.1）：内录运行中自动静音正在播媒体的第三方 app 原声 */
    private val _autoTakeoverEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_TAKEOVER, true),
    )
    val autoTakeoverEnabled: StateFlow<Boolean> = _autoTakeoverEnabled.asStateFlow()

    /** 自动接管的特权失败提示（Stellar 未授权等）——静默失效会让人对着双声猜原因 */
    val takeoverPrivilegeError: StateFlow<String?> = takeover.privilegeError

    /** 上次进程被强杀遗留的 DP 静音效果提示（只能重启清除，必须让用户看见） */
    val residueWarning: StateFlow<String?> = takeover.residueWarning

    /** 设备卡展示的路由真相：port 号 + 是否命中我方 submix */
    val routeSummary: StateFlow<String> = takeover.routeSummary

    init {
        // 启动时把持久化的 audio_mode 同步给 UI（否则设置页高亮会一直是默认值）。
        // 注意：**必须用异步调度**。viewModelScope 默认 Main.immediate，在构造函数里
        // launch 会同步执行 lambda，而 `prefs` 字段尚未初始化 → 启动即 NPE 崩溃
        // （2026-11-05 实测崩溃栈 MainViewModel.<init>:132）。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _audioMode.value = prefs.getString(KEY_AUDIO_MODE, MODE_LOOPBACK) ?: MODE_LOOPBACK
            }
        }
        // 引擎停止/失败 → 立即释放接管静音。否则「引擎没输出了、原声又被压着」
        // 就是全静音（2026-11-05 事故同族）。
        viewModelScope.launch {
            engine.state.collect { st ->
                // 只在「明确停止/失败」时释放。**不能用「非 Running 就释放」**：
                // start 过程会经过 Ready/Preparing，那样会把建链前的预静音当场撤销
                // （2026-11-05 实测：预静音成功=1，但随即 DP=0、副本也没了）。
                val ended = st is com.jiangjie.Sonicraft.core.engine.EngineState.Stopped ||
                    st is com.jiangjie.Sonicraft.core.engine.EngineState.Failed
                if (ended) {
                    runCatching { takeover.releaseLoopbackMutesNow() }
                }
            }
        }
    }

    /**
     * 采集断链告警（2026-11-01 事故）：capture 全零断链时保险丝回滚接管保直出。
     * 起判定与状态迁至 TakeoverController（进程级，UI 生死无关），此处转发。
     */
    val captureBrokenWarning: StateFlow<String?> = takeover.captureBrokenWarning

    /** 警示条「重新授权」点击后手动清除 */
    fun clearCaptureBrokenWarning() = takeover.clearCaptureBrokenWarning()

    /**
     * 自身播放权限自愈警示（2026-11-03 全静音事故）：ZTE 后台音频治理把本 app 置
     * PLAY_AUDIO=deny → 输出轨 -inf → 全链死寂。自愈探针自动恢复后挂此横幅（引导
     * 电池白名单）。判定与状态在 TakeoverController（进程级），此处转发。
     */
    val selfAudioWarning: StateFlow<String?> = takeover.selfAudioWarning

    fun clearSelfAudioWarning() = takeover.clearSelfAudioWarning()

    /** 输出流停摆警示（看门狗重建中/失败；引擎层状态，直通转发） */
    val outputStalled: StateFlow<String?> = engine.outputStalled

    /** 用户排除清单（包名）：这些 app 的播放不被捕获。重启内录后生效。 */
    private val _excludedPackages = MutableStateFlow(
        prefs.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()).orEmpty().toSet(),
    )
    val excludedPackages: StateFlow<Set<String>> = _excludedPackages.asStateFlow()

    /** 接管清单（包名）：这些 app 的原声被 AppOps 静音，由引擎内录副本替代（M3，实时生效）。 */
    private val _takeoverPackages = MutableStateFlow(
        prefs.getStringSet(KEY_TAKEOVER_PACKAGES, emptySet()).orEmpty().toSet(),
    )
    val takeoverPackages: StateFlow<Set<String>> = _takeoverPackages.asStateFlow()

    /** 接管操作失败提示（特权框架不可用等），一次性消费 */
    private val _takeoverError = MutableStateFlow<String?>(null)
    val takeoverError: StateFlow<String?> = _takeoverError.asStateFlow()

    /** 噪声门限（输入 RMS）：0 = 禁用。运行时实时生效。 */
    private val _gateThreshold = MutableStateFlow(
        prefs.getFloat(KEY_GATE_THRESHOLD, 0f),
    )
    val gateThreshold: StateFlow<Float> = _gateThreshold.asStateFlow()

    // ---- 参量均衡器控制（A/B 标尺：eqEnabled=false → 节点 bypass 透传） ----
    /** 音效总开关：关 = 原样透传（听原声副本），开 = 应用 EQ 曲线 */
    /**
     * 均衡器**组总闸**（与「压限器」「低频管家」同构：组闸 + 子卡开关两级）。
     *
     * 迁移：旧版本只有 `eq_enabled` 一个开关（既当组闸又当 PEQ 子开关），
     * 因此首次读取时若没有 `eq_section_enabled`，就沿用 `eq_enabled` 的值 ——
     * 老用户不会因为升级突然被"总闸关掉"。
     */
    private val _eqSectionEnabled = MutableStateFlow(
        prefs.getBoolean(
            KEY_EQ_SECTION_ENABLED,
            prefs.getBoolean(KEY_EQ_ENABLED, false),
        ),
    )
    val eqSectionEnabled: StateFlow<Boolean> = _eqSectionEnabled.asStateFlow()

    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(KEY_EQ_ENABLED, false))
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    /** 低音增益（dB），映射到 EQ band 0（31Hz）。运行时实时生效、带 30ms 平滑斜坡 */
    private val _bassGain = MutableStateFlow(prefs.getFloat(KEY_BASS_GAIN, 0f))
    val bassGain: StateFlow<Float> = _bassGain.asStateFlow()

    /** 高音增益（dB），映射到 EQ band 9（16kHz） */
    private val _trebleGain = MutableStateFlow(prefs.getFloat(KEY_TREBLE_GAIN, 0f))
    val trebleGain: StateFlow<Float> = _trebleGain.asStateFlow()

    // ---- 多段 EQ：10/15/31 段模式（图形滑条墙 + 曲线），BASS/TREBLE 快捷绑 band0/末段 ----
    private val _eqMode = MutableStateFlow(prefs.getInt(KEY_EQ_MODE, 10).coerceIn(1, 31))
    val eqMode: StateFlow<Int> = _eqMode.asStateFlow()

    private val _eqBandGains = MutableStateFlow(seedEqBands())
    val eqBandGains: StateFlow<List<Float>> = _eqBandGains.asStateFlow()

    /**
     * 多段增益初始化：优先读当前模式已存值；首次进入（10 段模式无存档）时把历史
     * 低音/高音快捷值并入对应段——否则曲线/滑条显示全平而引擎实际在跑旧
     * bass/treble（状态不同步导致频响曲线与实际不一致）。
     */
    private fun seedEqBands(): List<Float> {
        val mode = _eqMode.value
        val stored = prefs.getString("eq_bands_$mode", null)
        if (stored != null) {
            val parsed = runCatching { stored.split(",").map { it.toFloat() } }.getOrNull()
            if (parsed != null) return List(mode) { i -> parsed.getOrElse(i) { 0f } }
        }
        val gains = MutableList(mode) { 0f }
        if (mode == 10) {
            gains[0] = prefs.getFloat(KEY_BASS_GAIN, 0f)
            gains[9] = prefs.getFloat(KEY_TREBLE_GAIN, 0f)
            persistEqBands(mode, gains)
        }
        return gains
    }

    private fun readEqBands(mode: Int): List<Float> = runCatching {
        prefs.getString("eq_bands_$mode", null)
            ?.split(",")
            ?.map { it.toFloat() }
    }.getOrNull()?.let { stored -> List(mode) { i -> stored.getOrElse(i) { 0f } } }
        ?: List(mode) { 0f }

    private fun persistEqBands(mode: Int, gains: List<Float>) {
        prefs.edit().putString("eq_bands_$mode", gains.joinToString(",") { "%.2f".format(it) }).apply()
    }

    /** 段数切换（10/15/31）：下发 SetParam(93) + 该模式参数重放（各模式独立记忆） */
    // 每段独立 Q（专业逻辑：Q 滑条跟随选中的频点段，各模式独立记忆）
    private val _eqBandQs = MutableStateFlow(readEqQs(_eqMode.value))
    val eqBandQs: StateFlow<List<Float>> = _eqBandQs.asStateFlow()

    private fun readEqQs(mode: Int): List<Float> = runCatching {
        prefs.getString("eq_q_$mode", null)?.split(",")?.map { it.toFloat() }
    }.getOrNull()?.let { stored -> List(mode) { i -> stored.getOrElse(i) { 1f }.coerceIn(0.3f, 8f) } }
        ?: List(mode) { 1f }

    private fun persistEqQs(mode: Int, qs: List<Float>) {
        prefs.edit().putString("eq_q_$mode", qs.joinToString(",") { "%.2f".format(it) }).apply()
    }

    /** 选中段的 Q 调整：下发单个 band Q + 持久化 */
    fun setEqBandQ(band: Int, q: Float) {
        val clamped = q.coerceIn(0.3f, 8f)
        val list = _eqBandQs.value.toMutableList()
        if (band !in list.indices) return
        list[band] = clamped
        _eqBandQs.value = list
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + band, clamped)
        persistEqQs(_eqMode.value, list)
    }

    // ---- 段类型（0=PK 1=LSC 2=HSC）：AutoEQ 耳机修正地基；PEQ 手动编辑仍全 PK ----
    private val _eqBandTypes = MutableStateFlow(readEqTypes(_eqMode.value))
    val eqBandTypes: StateFlow<List<Int>> = _eqBandTypes.asStateFlow()

    private fun readEqTypes(mode: Int): List<Int> = runCatching {
        prefs.getString("eq_type_$mode", null)?.split(",")?.map { it.toInt() }
    }.getOrNull()?.let { stored ->
        List(mode) { i -> stored.getOrElse(i) { 0 }.coerceIn(0, 2) }
    } ?: List(mode) { 0 }

    private fun persistEqTypes(mode: Int, types: List<Int>) {
        prefs.edit().putString("eq_type_$mode", types.joinToString(",")).apply()
    }

    /** 单段类型切换（AutoEQ 应用链路用；UI 不直接暴露） */
    fun setEqBandType(band: Int, type: Int) {
        val t = type.coerceIn(0, 2)
        val list = _eqBandTypes.value.toMutableList()
        if (band !in list.indices) return
        list[band] = t
        _eqBandTypes.value = list
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + band, t.toFloat())
        persistEqTypes(_eqMode.value, list)
    }

    /** EQ 输出实时频谱（64 带 dB，-60..0；手绘区衬底用；引擎未运行返回 null） */
    fun eqSpectrum(): FloatArray? = engine.eqSpectrum()

    fun setEqMode(mode: Int) {
        val m = mode.coerceIn(1, 31)
        if (m == _eqMode.value) return
        prefs.edit().putInt(KEY_EQ_MODE, m).apply()
        _eqMode.value = m
        val bands = readEqBands(m)
        _eqBandGains.value = bands
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, m.toFloat())
        bands.forEachIndexed { i, g ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, i, g)
        }
        _eqBandQs.value = readEqQs(m)
        _eqBandFreqs.value = readEqFreqs(m)
        _eqBandTypes.value = readEqTypes(m)
        bands.indices.forEach { i ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, _eqBandQs.value[i])
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, _eqBandFreqs.value[i])
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + i, _eqBandTypes.value[i].toFloat())
        }
        // BASS/TREBLE 快捷与新模式对齐（band0 / 末段）
        _bassGain.value = bands[0]
        _trebleGain.value = bands[bands.size - 1]
    }

    // ---- 段数自定义（对齐 wecho：1~31 任意段数 + 尾部加段/删选中段）----
    // 注意与 setEqMode 的分工：档位按钮 10/15/31 走 setEqMode（各档独立记忆，
    // 无历史时平直）；自定义段数/增删段直接以调用方给定的四元组整体下发+持久化
    // （native SetParam(93) 会重置频点/Q/增益/类型，所以顺序必须是先 BAND_COUNT
    // 再全量重放，与 applyEqUserPreset 相同）。

    /** 段数变更公共路径：下发 BAND_COUNT（native 重置）→ 全量重放 → 持久化 */
    private fun applyBandCount(
        m: Int,
        freqs: List<Float>,
        gains: List<Float>,
        qs: List<Float>,
        types: List<Int>,
    ) {
        prefs.edit().putInt(KEY_EQ_MODE, m).apply()
        _eqMode.value = m
        _eqBandGains.value = gains
        _eqBandQs.value = qs
        _eqBandFreqs.value = freqs
        _eqBandTypes.value = types
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, m.toFloat())
        gains.forEachIndexed { i, g -> engine.setParam(AudioEngine.EQ_NODE_INDEX, i, g) }
        qs.forEachIndexed { i, q -> engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, q) }
        freqs.forEachIndexed { i, f -> engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, f) }
        types.forEachIndexed { i, t -> engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + i, t.toFloat()) }
        persistEqBands(m, gains)
        persistEqQs(m, qs)
        persistEqTypes(m, types)
        prefs.edit().putString("eq_freq_$m", freqs.joinToString(",") { "%.1f".format(it) }).apply()
        _bassGain.value = gains.firstOrNull() ?: 0f
        _trebleGain.value = gains.lastOrNull() ?: 0f
    }

    /** 自定义段数（1~31，wecho Bands 数字框语义）：重置为该段数默认平直配置 */
    fun setEqBandCountCustom(n: Int) {
        val m = n.coerceIn(1, 31)
        if (m == _eqMode.value) return
        val table = EqMath.freqTable(m)
        applyBandCount(m, table, List(m) { 0f }, List(m) { 1f }, List(m) { 0 })
    }

    /** 尾部加一段（wecho 语义：fc 取末两段几何中点，<2 段取 1k），平直 PK/Q=1 */
    fun addEqBand() {
        val m = _eqMode.value
        if (m >= 31) return
        val oldFreqs = _eqBandFreqs.value
        val newFc = if (oldFreqs.size >= 2) {
            val last = oldFreqs.last().toDouble()
            val prev = oldFreqs[oldFreqs.size - 2].toDouble()
            kotlin.math.sqrt(last * prev).toFloat().coerceIn(20f, 20000f)
        } else {
            1000f
        }
        val freqs = oldFreqs + newFc
        val gains = _eqBandGains.value + 0f
        val qs = _eqBandQs.value + 1f
        val types = _eqBandTypes.value + 0
        applyBandCount(m + 1, freqs, gains, qs, types)
    }

    /** 删除指定段（wecho 红 ✕ 语义，至少保留 1 段） */
    fun removeEqBand(band: Int) {
        val m = _eqMode.value
        if (m <= 1) return
        if (band !in _eqBandGains.value.indices) return
        val freqs = _eqBandFreqs.value.toMutableList().also { it.removeAt(band) }
        val gains = _eqBandGains.value.toMutableList().also { it.removeAt(band) }
        val qs = _eqBandQs.value.toMutableList().also { it.removeAt(band) }
        val types = _eqBandTypes.value.toMutableList().also { it.removeAt(band) }
        applyBandCount(m - 1, freqs, gains, qs, types)
    }

    /** 图形滑条墙实时下发：band 增益（节点内 30ms 平滑）+ 持久化 */
    fun setEqBandGain(band: Int, db: Float) {
        val clamped = db.coerceIn(-12f, 12f)
        val list = _eqBandGains.value.toMutableList()
        if (band !in list.indices) return
        list[band] = clamped
        _eqBandGains.value = list
        engine.setParam(AudioEngine.EQ_NODE_INDEX, band, clamped)
        persistEqBands(_eqMode.value, list)
        // BASS/TREBLE 快捷联动（band0=低音、末段=高音）
        if (band == 0) _bassGain.value = clamped
        if (band == list.size - 1) _trebleGain.value = clamped
    }

    /** 应用内置/自定义预设曲线：控制点按当前模式频点 log 插值 → 逐段下发 + 持久化 */
    fun applyEqPresetCurve(points: List<Pair<Float, Float>>) {
        val freqs = EqMath.freqTable(_eqMode.value)
        val gains = freqs.map { f -> EqMath.interpLog(points, f).coerceIn(-12f, 12f) }
        _eqBandGains.value = gains
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, _eqMode.value.toFloat())
        gains.forEachIndexed { i, g ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, i, g)
        }
        // 预设 = 完整状态快照语义：Q 归位默认 1.0、频率归位表值
        // （否则点「平直」后残留上次调的 Q/频率，「干净起点」语义不彻底）
        val qReset = List(gains.size) { 1.0f }
        _eqBandQs.value = qReset
        _eqBandFreqs.value = freqs
        qReset.forEachIndexed { i, q ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, q)
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, freqs[i])
        }
        persistEqQs(_eqMode.value, qReset)
        prefs.edit()
            .putString("eq_freq_${_eqMode.value}", freqs.joinToString(",") { "%.1f".format(it) })
            .apply()
        persistEqBands(_eqMode.value, gains)
        _bassGain.value = gains[0]
        _trebleGain.value = gains[gains.size - 1]
    }



    // ---- 限幅器控制（末级防削波安全网，默认开启） ----
    /** 限幅器开关：开 = 输出峰值被压在 ceiling 之下；关 = 透传 */
    private val _limiterEnabled = MutableStateFlow(prefs.getBoolean(KEY_LIMITER_ENABLED, true))
    val limiterEnabled: StateFlow<Boolean> = _limiterEnabled.asStateFlow()

    // ---- 自定义脚本效果器（2026-11-12 原型，默认关） ----
    private val _scriptEffectEnabled = MutableStateFlow(prefs.getBoolean("script_effect_enabled", false))
    val scriptEffectEnabled: StateFlow<Boolean> = _scriptEffectEnabled.asStateFlow()

    /** 用户脚本源（类 C）。空 = 使用节点内置软削波示例。 */
    private val _scriptSource = MutableStateFlow(prefs.getString("script_effect_source", "") ?: "")
    val scriptSource: StateFlow<String> = _scriptSource.asStateFlow()

    /** 脚本引用的 slider 位图（bit i = slider(i+1)），引擎编译脚本后回读。 */
    private val _scriptSliderMask = MutableStateFlow(0)
    val scriptSliderMask: StateFlow<Int> = _scriptSliderMask.asStateFlow()

    /** 脚本参数值（slider1..8），持久化到 prefs。 */
    private val _scriptSliderValues = MutableStateFlow(
        FloatArray(8) { i -> prefs.getFloat("script_slider_$i", 0.5f) },
    )
    val scriptSliderValues: StateFlow<FloatArray> = _scriptSliderValues.asStateFlow()

    /** 各 slider 范围（[2i]=min,[2i+1]=max），由脚本 `// sliderN` 声明。 */
    private val _scriptSliderRanges = MutableStateFlow(
        FloatArray(16) { i -> if (i % 2 == 0) 0f else 1f },
    )
    val scriptSliderRanges: StateFlow<FloatArray> = _scriptSliderRanges.asStateFlow()

    /** 脚本最后一次编译错误（空 = 无错误）。 */
    private val _scriptError = MutableStateFlow("")
    val scriptError: StateFlow<String> = _scriptError.asStateFlow()

    /** 脚本声明的 toggle/select/button 控件（native 编译后回读 JSON）。 */
    private val _scriptControls = MutableStateFlow<List<ScriptControl>>(emptyList())
    val scriptControls: StateFlow<List<ScriptControl>> = _scriptControls.asStateFlow()

    /** toggle1..8 的值（0/1，持久化）。 */
    private val _scriptToggleValues = MutableStateFlow(
        FloatArray(8) { i -> prefs.getFloat("script_toggle_$i", 0f) },
    )
    val scriptToggleValues: StateFlow<FloatArray> = _scriptToggleValues.asStateFlow()

    /** select1..8 的选中索引（持久化）。 */
    private val _scriptSelectValues = MutableStateFlow(
        IntArray(8) { i -> prefs.getInt("script_select_$i", 0) },
    )
    val scriptSelectValues: StateFlow<IntArray> = _scriptSelectValues.asStateFlow()

    /** list1..8 的位掩码（多选，持久化）。 */
    private val _scriptListValues = MutableStateFlow(
        IntArray(8) { i -> prefs.getInt("script_list_$i", 0) },
    )
    val scriptListValues: StateFlow<IntArray> = _scriptListValues.asStateFlow()

    /** 各 slider 默认值声明：[0..7]=值，[8..15]=是否声明（0/1）。 */
    private val _scriptSliderDefaults = MutableStateFlow(FloatArray(16))
    val scriptSliderDefaults: StateFlow<FloatArray> = _scriptSliderDefaults.asStateFlow()

    /** 上限（dBFS，负值）：输出峰值不超过该电平 */
    private val _limiterCeiling = MutableStateFlow(prefs.getFloat(KEY_LIMITER_CEILING, -1f))
    val limiterCeiling: StateFlow<Float> = _limiterCeiling.asStateFlow()

    // ---- M4b-2 压缩器控制（宽缓压平动态，消除限幅器单独工作的呼吸感） ----
    /** 压缩器开关（多频段开着时内存态强制 false——prefs 可双存，恢复规则以多频段优先） */
    private val _compressorEnabled =
        MutableStateFlow(
            // 缺省值 true → **false**。原默认让新装用户一上手就处在
            // 「阈值 -20dB / 4:1 / makeup 0dB」的持续压缩下（实测整链低约 12dB），
            // 而开关藏在「多频段压限」详情页、主页卡片只反映 multiband——用户看到
            // 「没开任何效果器，声音却小很多」，无从察觉。效果器默认必须透明。
            prefs.getBoolean(KEY_COMPRESSOR_ENABLED, false) &&
                !prefs.getBoolean(KEY_MULTIBAND_ENABLED, false),
        )
    val compressorEnabled: StateFlow<Boolean> = _compressorEnabled.asStateFlow()

    /** 压缩阈值（dB）：包络超过该电平开始压缩 */
    private val _compressorThreshold =
        MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_THRESHOLD, -20f))
    val compressorThreshold: StateFlow<Float> = _compressorThreshold.asStateFlow()

    /** 压缩比（x:1） */
    private val _compressorRatio = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_RATIO, 4f))
    val compressorRatio: StateFlow<Float> = _compressorRatio.asStateFlow()

    // ---- 界面缩放（2026-11-09，从 wecho 定制版移植）----
    // wecho 是 autoScale(按 440dp 基准自动算) × uiScale(粗调) × uiFineScale(微调)，
    // 实际作用于 UI 的是后两者的乘积；autoScale 由调用方按屏幕宽度实时算。
    /** 缩放粗调：0.8~1.3（对应 wecho 设置页的主滑块） */
    private val _uiScale = MutableStateFlow(prefs.getFloat(KEY_UI_SCALE, 1f))
    val uiScale: StateFlow<Float> = _uiScale.asStateFlow()

    /** 缩放微调：0.9~1.1（对应 wecho 的微调滑块，用于细抠到 1% 一档） */
    private val _uiFineScale = MutableStateFlow(prefs.getFloat(KEY_UI_FINE_SCALE, 1f))
    val uiFineScale: StateFlow<Float> = _uiFineScale.asStateFlow()

    /**
     * 缩放粗调。滑块已改为连续滑动（无 steps），所以在这里量化到 **1% 一档**：
     * 拖动是平滑的，落值仍然整洁，副标题上的「总 X%」不会出现 95.7% 这种。
     */
    fun setUiScale(v: Float) {
        val c = (v * 100f).roundToInt() / 100f
        val clamped = c.coerceIn(0.8f, 1.3f)
        if (clamped == _uiScale.value) return   // 量化后没变 → 不写盘、不触发重组
        _uiScale.value = clamped
        prefs.edit().putFloat(KEY_UI_SCALE, clamped).apply()
    }

    /** 缩放微调。连续滑动 + 量化到 **0.5% 一档**（±10% 范围内足够细）。 */
    fun setUiFineScale(v: Float) {
        val c = (v * 200f).roundToInt() / 200f
        val clamped = c.coerceIn(0.9f, 1.1f)
        if (clamped == _uiFineScale.value) return
        _uiFineScale.value = clamped
        prefs.edit().putFloat(KEY_UI_FINE_SCALE, clamped).apply()
    }

    /** 微调归 100%（wecho 的「重置微调」） */
    fun resetUiFineScale() = setUiFineScale(1f)

    /** 起音（ms，2026-11-09 暴露到 UI） */
    private val _compressorAttack = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_ATTACK, 10f))
    val compressorAttack: StateFlow<Float> = _compressorAttack.asStateFlow()

    /** 释放（ms） */
    private val _compressorRelease = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_RELEASE, 150f))
    val compressorRelease: StateFlow<Float> = _compressorRelease.asStateFlow()

    /** 拐点宽度（dB，0 = 硬拐点） */
    private val _compressorKnee = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_KNEE, 6f))
    val compressorKnee: StateFlow<Float> = _compressorKnee.asStateFlow()

    /** 补偿增益（dB） */
    private val _compressorMakeup = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_MAKEUP, 0f))
    val compressorMakeup: StateFlow<Float> = _compressorMakeup.asStateFlow()

    // ---- 2026-11-11 新增四项（并联 / 检测模式 / 前瞻 / 过采样）----
    /** 并联混合：0 = 全干、1 = 全湿 */
    private val _compressorMix = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_MIX, 1f))
    val compressorMix: StateFlow<Float> = _compressorMix.asStateFlow()

    /** 检测模式：0 = peak、1 = RMS */
    private val _compressorDetect = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_DETECT, 0f))
    val compressorDetect: StateFlow<Float> = _compressorDetect.asStateFlow()

    /** 前瞻（ms） */
    private val _compressorLookahead = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_LOOKAHEAD, 0f))
    val compressorLookahead: StateFlow<Float> = _compressorLookahead.asStateFlow()

    /** 过采样倍率：1 = 关闭、2 = 2x */
    private val _compressorOversample = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_OVERSAMPLE, 1f))
    val compressorOversample: StateFlow<Float> = _compressorOversample.asStateFlow()

    /**
     * 侧链高通截止（Hz，0 = 关闭）。
     *
     * 作用在**检测路径**上：先对信号高通再取绝对值，低频因此不再触发压缩。
     * 不加高通时底鼓会把整段电平压下去、人声跟着抽吸一下。
     * 但 120Hz 不是通用最优：电子乐的低频会完全不受控（动态过大顶限幅器），
     * 男声（85~180Hz 基频）在播客场景下会被削弱检测 → 压不住，所以必须可调。
     */
    private val _compressorSidechainHp = MutableStateFlow(prefs.getFloat(KEY_COMPRESSOR_SIDECHAIN_HP, 120f))
    val compressorSidechainHp: StateFlow<Float> = _compressorSidechainHp.asStateFlow()

    /** 单段压缩器电平表：{输入峰值, 增益衰减 dB, 输出峰值} */
    private val _compressorMeters = MutableStateFlow(List(3) { 0f })
    val compressorMeters: StateFlow<List<Float>> = _compressorMeters.asStateFlow()

    // ---- 多频段压限控制（三段动态，与单段压缩器互斥运行，联动在 Kotlin 层） ----
    /** 多频段开关（开时单段压缩器被引擎层旁路，但单段自身 prefs 不动，关回后可恢复） */
    private val _multibandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_MULTIBAND_ENABLED, false))
    val multibandEnabled: StateFlow<Boolean> = _multibandEnabled.asStateFlow()

    /**
     * 压限器**总闸**：音效库卡片上的开关，语义是「旁通」。
     *
     * 关掉它与「单独关掉某张卡」不同：多段与单段**两个节点同时 bypass**，但两张卡
     * 的开关记忆不变；重新打开时各自回到关闭前的状态（用户要求原文：
     * 「当开启时，详情页原来谁是开启的状态就是开启的，谁是关闭状态的就继续关闭」）。
     *
     * 默认值按旧数据推断（`multiband || compressor`）：老用户升级后总闸不会莫名变关；
     * 全新安装时两者都是 false → 总闸也是关，卡片显示灰色，符合"效果器默认透明"。
     */
    private val _compressorSectionEnabled = MutableStateFlow(
        prefs.getBoolean(
            KEY_COMPRESSOR_SECTION_ENABLED,
            prefs.getBoolean(KEY_MULTIBAND_ENABLED, false) ||
                prefs.getBoolean(KEY_COMPRESSOR_ENABLED, false),
        ),
    )
    val compressorSectionEnabled: StateFlow<Boolean> = _compressorSectionEnabled.asStateFlow()

    /** 三段 20 参（0-17 每段六参 + 18 fc1 + 19 fc2），与 native MultibandNode param_id 对齐 */
    private val _multibandParams: MutableStateFlow<List<Float>> = MutableStateFlow(
        List(MULTIBAND_PARAM_COUNT) { pid ->
            prefs.getFloat(KEY_MULTIBAND_PARAM_PREFIX + pid, MULTIBAND_DEFAULTS[pid])
        },
    )
    val multibandParams: StateFlow<List<Float>> = _multibandParams.asStateFlow()

    // ---- 卷积混响控制（效果性，默认关） ----
    /** 混响开关：开 = 应用当前预设 IR；关 = 干声透传（混响尾冻结，切换无断裂） */
    private val _reverbEnabled = MutableStateFlow(prefs.getBoolean(KEY_REVERB_ENABLED, false))
    val reverbEnabled: StateFlow<Boolean> = _reverbEnabled.asStateFlow()

    /**
     * 混响组**总闸**：音效库「混响」卡片上的开关。
     * 与压限器同一套语义 —— 关 = 两个节点同时旁通、各槽记忆不动，
     * 再开时各自回到原状态。缺省按「任一混响开着」推断，老用户升级不会莫名变关。
     */
    private val _reverbSectionEnabled = MutableStateFlow(
        prefs.getBoolean(
            KEY_REVERB_SECTION_ENABLED,
            prefs.getBoolean(KEY_REVERB_ENABLED, false) ||
                prefs.getBoolean(KEY_FDN_ENABLED, false),
        ),
    )
    val reverbSectionEnabled: StateFlow<Boolean> = _reverbSectionEnabled.asStateFlow()

    /** 混响预设：0=干声, 1=小房间, 2=客厅, 3=大房间, 4=教堂, 5=自定义 IR（脉冲库点选加载后切换） */
    private val _reverbPreset = MutableStateFlow(prefs.getInt(KEY_REVERB_PRESET, 1))
    val reverbPreset: StateFlow<Int> = _reverbPreset.asStateFlow()

    /** 切换混响预设（实时生效，频域 IR 已预计算，仅指针交换）。5=自定义不经此入口 */
    fun setReverbPreset(preset: Int) {
        // 5=自定义 IR：脉冲库无激活条目时引擎没有可用数据，拒绝切换并提示；
        // 有激活条目则放行（与预设 1..4 同等的直接切换，不再弹文件选择器）
        if (preset == AudioEngine.REVERB_PRESET_USER && _activeRecentIrName.value == null) {
            _userIrMessage.value = "自定义 IR 尚未加载：请先在下方脉冲库点选一个脉冲"
            return
        }
        // 旧钳制 (1,3) 是 3 预设时代遗留——教堂(4)被钳回 1，点了永远切小房间
        val clamped = if (preset == AudioEngine.REVERB_PRESET_USER) preset else preset.coerceIn(1, 4)
        _reverbPreset.value = clamped
        prefs.edit().putInt(KEY_REVERB_PRESET, clamped).apply()
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_PRESET,
            clamped.toFloat(),
        )
    }

    /** 卷积混响开关（bypass = !enabled）；与算法混响相互独立，可同时开启 */
    /**
     * 混响组**总闸**（2026-11-11，音效库「混响」卡片那个开关）。
     * 关 = 卷积与算法两个节点同时旁通，各槽记忆不动。
     */
    fun setReverbSectionEnabled(enabled: Boolean) {
        if (_reverbSectionEnabled.value == enabled) return
        _reverbSectionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_REVERB_SECTION_ENABLED, enabled).apply()
        applyReverbBypass()
    }

    /** 按「总闸 × 各自记忆」下发两个混响节点的 bypass（与压限器同一套公式）。 */
    private fun applyReverbBypass() {
        val sec = _reverbSectionEnabled.value
        engine.setBypass(AudioEngine.CONVOLVER_NODE_INDEX, !(sec && _reverbEnabled.value))
        engine.setBypass(
            AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX,
            !(sec && _fdnEnabled.value),
        )
    }

    fun setReverbEnabled(enabled: Boolean) {
        _reverbEnabled.value = enabled
        prefs.edit().putBoolean(KEY_REVERB_ENABLED, enabled).apply()
        applyReverbBypass()
    }

    // ---- M4c.2 混响干/湿电平 ----
    /** 混响湿声电平（0~2，1 = 各预设归一基准；全预设生效，30ms 斜坡）。
     *  历史名 userWetGain 保留（prefs key 与 EffectPreset key 不动，避免迁移）。 */
    private val _userWetGain = MutableStateFlow(prefs.getFloat(KEY_USER_WET_GAIN, 1f))
    val userWetGain: StateFlow<Float> = _userWetGain.asStateFlow()

    fun setUserWetGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 2f)
        _userWetGain.value = clamped
        prefs.edit().putFloat(KEY_USER_WET_GAIN, clamped).apply()
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_WET,
            clamped,
        )
    }

    /** 混响干声电平（0~1.2，1 = 各预设原干声增益；30ms 斜坡）。拉低可做「全湿」。 */
    private val _reverbDry = MutableStateFlow(prefs.getFloat(KEY_REVERB_DRY, 1f))
    val reverbDry: StateFlow<Float> = _reverbDry.asStateFlow()

    /** 混响湿声低切 Hz（0=关，40~400；只作用湿声路径，30ms 斜坡）。 */
    private val _reverbLowCut = MutableStateFlow(prefs.getFloat(KEY_REVERB_LOWCUT, 0f))
    val reverbLowCut: StateFlow<Float> = _reverbLowCut.asStateFlow()

    fun setReverbLowCut(hz: Float) {
        val clamped = if (hz <= 0f) 0f else hz.coerceIn(40f, 400f)
        _reverbLowCut.value = clamped
        prefs.edit().putFloat(KEY_REVERB_LOWCUT, clamped).apply()
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_LOWCUT,
            clamped,
        )
    }

    fun setReverbDry(gain: Float) {
        val clamped = gain.coerceIn(0f, 1.2f)
        _reverbDry.value = clamped
        prefs.edit().putFloat(KEY_REVERB_DRY, clamped).apply()
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_DRY,
            clamped,
        )
    }

    // ---- 算法混响（FDN），与卷积混响相互独立的效果器，可同时开启（M 系列新增混响引擎） ----
    /** 算法混响开关（bypass = !enabled）；与卷积混响独立，二者可同时激活 */
    private val _fdnEnabled = MutableStateFlow(prefs.getBoolean(KEY_FDN_ENABLED, false))
    val fdnEnabled: StateFlow<Boolean> = _fdnEnabled.asStateFlow()

    /** 房间尺寸（0~1）：同时驱动延迟缩放与反馈量 */
    private val _fdnRoomSize = MutableStateFlow(prefs.getFloat(KEY_FDN_ROOM_SIZE, 0.5f))
    val fdnRoomSize: StateFlow<Float> = _fdnRoomSize.asStateFlow()

    /** 湿度/阻尼（0~1）：越大衰减越快 */
    private val _fdnHumidity = MutableStateFlow(prefs.getFloat(KEY_FDN_HUMIDITY, 0.5f))
    val fdnHumidity: StateFlow<Float> = _fdnHumidity.asStateFlow()

    /** 立体声宽度（0~1）：M/S side 增益 */
    private val _fdnStereoWidth = MutableStateFlow(prefs.getFloat(KEY_FDN_STEREO_WIDTH, 0.5f))
    val fdnStereoWidth: StateFlow<Float> = _fdnStereoWidth.asStateFlow()

    /** 湿声比例（0~1） */
    private val _fdnWet = MutableStateFlow(prefs.getFloat(KEY_FDN_WET, 0.3f))
    val fdnWet: StateFlow<Float> = _fdnWet.asStateFlow()

    /** 干声比例（0~1） */
    private val _fdnDry = MutableStateFlow(prefs.getFloat(KEY_FDN_DRY, 0.5f))
    val fdnDry: StateFlow<Float> = _fdnDry.asStateFlow()

    /** 拓扑档位（0=全规格 8+4，1=轻量 4+2） */
    private val _fdnMode = MutableStateFlow(prefs.getFloat(KEY_FDN_MODE, 0f))
    val fdnMode: StateFlow<Float> = _fdnMode.asStateFlow()

    fun setFdnEnabled(enabled: Boolean) {
        _fdnEnabled.value = enabled
        prefs.edit().putBoolean(KEY_FDN_ENABLED, enabled).apply()
        // 走统一旁通：混响组总闸关闭时这里仍应保持旁路
        applyReverbBypass()
    }

    fun setFdnRoomSize(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _fdnRoomSize.value = c
        prefs.edit().putFloat(KEY_FDN_ROOM_SIZE, c).apply()
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_ROOM_SIZE, c)
    }

    fun setFdnHumidity(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _fdnHumidity.value = c
        prefs.edit().putFloat(KEY_FDN_HUMIDITY, c).apply()
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_HUMIDITY, c)
    }

    fun setFdnStereoWidth(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _fdnStereoWidth.value = c
        prefs.edit().putFloat(KEY_FDN_STEREO_WIDTH, c).apply()
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_STEREO_WIDTH, c)
    }

    fun setFdnWet(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _fdnWet.value = c
        prefs.edit().putFloat(KEY_FDN_WET, c).apply()
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_WET, c)
    }

    fun setFdnDry(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _fdnDry.value = c
        prefs.edit().putFloat(KEY_FDN_DRY, c).apply()
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_DRY, c)
    }

    fun setFdnMode(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _fdnMode.value = c
        prefs.edit().putFloat(KEY_FDN_MODE, c).apply()
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_MODE, c)
    }

    // ---- M4c.2 自定义 IR 加载 ----
    /** 自定义 IR 状态提示（一次性消费） */
    private val _userIrMessage = MutableStateFlow<String?>(null)
    val userIrMessage: StateFlow<String?> = _userIrMessage.asStateFlow()

    /** 用户 IR 分析数据（波形包络 + 左右能量，加载/恢复成功后拉取） */
    private val _userIrAnalysis = MutableStateFlow<FloatArray?>(null)
    val userIrAnalysis: StateFlow<FloatArray?> = _userIrAnalysis.asStateFlow()

    private fun refreshUserIrAnalysis() {
        val a = engine.irAnalysis()
        _userIrAnalysis.value = a
        maybeAutoCalWet(a)
    }

    // ---- 批次 2：湿声电平自动校准----
    /**
     * 装载新 IR 后按其形态（波峰因数，native 报告 [1554]）自动设湿声电平：
     * 脉冲型 IR（HRTF/舞台）增强、扩散长尾收敛、同房间形态 ≈1.0 无感迁移。
     * 同一 IR 只校准一次——恢复链路重装不覆盖用户手动微调；开关关闭或
     * 非自定义预设时不动作。建议值 clamp 由 native 保证（0.6~1.5）。
     */
    private fun maybeAutoCalWet(a: FloatArray?) {
        if (!_wetAutoCal.value) return
        if (_reverbPreset.value != AudioEngine.REVERB_PRESET_USER) return
        val name = _activeRecentIrName.value ?: return
        if (name == lastCalIrName) return
        val sug = a?.getOrNull(1554) ?: return
        if (sug <= 0f) return
        lastCalIrName = name
        prefs.edit().putString(KEY_LAST_CAL_IR, name).apply()
        setUserWetGain(sug)
    }

    fun consumeUserIrMessage() {
        _userIrMessage.value = null
    }

    // ---- b 脉冲库：目录即真相（2026-10-25，取代旧「最近使用」LRU 记账） ----
    /** 库条目：name = 显示名（文件名去扩展名），file = pulse/ 内文件名 */
    data class IrLibraryEntry(val name: String, val file: String, val sizeBytes: Long = 0L)

    /** 脉冲库列表：pulse/ 目录扫描结果，按名称排序（目录内容变化后调 refreshIrLibrary） */
    private val _irLibrary = MutableStateFlow<List<IrLibraryEntry>>(emptyList())
    val irLibrary: StateFlow<List<IrLibraryEntry>> = _irLibrary.asStateFlow()

    init {
        // 内部库目录存量搬到外部目录（一次性；/data→/sdcard 跨挂载点 rename 必败，用 copy+delete）
        runCatching {
            if (legacyPulseDir != pulseDir) {
                legacyPulseDir.listFiles()?.forEach { src ->
                    if (src.isFile) {
                        val dst = File(pulseDir, src.name)
                        if (!dst.exists()) {
                            if (!src.renameTo(dst)) {
                                src.copyTo(dst)
                                src.delete()
                            }
                        } else {
                            src.delete()
                        }
                    }
                }
            }
        }
        // 库列表首扫（须在 migrateLegacyIrFiles 之后；第二个 init 块按声明顺序执行）
        refreshIrLibrary()
    }

    /** 当前激活的自定义 IR 显示名（驱动库勾选；切内置预设不清除） */
    private val _activeRecentIrName = MutableStateFlow(prefs.getString(KEY_ACTIVE_IR_NAME, null))
    val activeRecentIrName: StateFlow<String?> = _activeRecentIrName.asStateFlow()

    /** U4 收藏的脉冲（设计稿 P4）：IR 显示名集合，SharedPreferences 持久化 */
    private val _favoriteIrs = MutableStateFlow(
        prefs.getStringSet(KEY_FAVORITE_IRS, null)?.toSet() ?: emptySet(),
    )
    val favoriteIrs: StateFlow<Set<String>> = _favoriteIrs.asStateFlow()

    /** U6 最近装载（设计稿 P3「最近使用」分区）：按装载时间倒序的 IR 显示名， JSONArray 持久化（保序） */
    private val _recentLoadedNames = MutableStateFlow(run {
        val arr = runCatching { JSONArray(prefs.getString(KEY_RECENT_LOADED_IRS, "[]").orEmpty()) }.getOrNull()
        (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf(String::isNotBlank) }
    })
    val recentLoadedNames: StateFlow<List<String>> = _recentLoadedNames.asStateFlow()

    /** 装载成功后记录到「最近使用」队首（去重、截断 8 条） */
    private fun recordRecentLoaded(name: String) {
        val names = (_recentLoadedNames.value - name).toMutableList()
        names.add(0, name)
        val trimmed = names.take(8)
        prefs.edit().putString(KEY_RECENT_LOADED_IRS, JSONArray(trimmed).toString()).apply()
        _recentLoadedNames.value = trimmed
    }

    /** 从「最近使用」移除一个名字（单删/清库时同步） */
    private fun forgetRecentLoaded(name: String?) {
        if (name == null) return
        val names = _recentLoadedNames.value - name
        prefs.edit().putString(KEY_RECENT_LOADED_IRS, JSONArray(names).toString()).apply()
        _recentLoadedNames.value = names
    }

    /** 收藏/取消收藏一个脉冲（按显示名；清库时残留名无副作用——列表取交集） */
    fun toggleIrFavorite(name: String) {
        val next = _favoriteIrs.value.toMutableSet()
        if (!next.add(name)) next.remove(name)
        _favoriteIrs.value = next
        prefs.edit().putStringSet(KEY_FAVORITE_IRS, next).apply()
    }

    /** 病态 IR 自动对齐（方案 A）：前置 >30ms（含设计型延迟）装载时裁除静音前缀，干湿对齐消除双声 */
    private val _trimSickHead = MutableStateFlow(prefs.getBoolean(KEY_TRIM_SICK_HEAD, true))
    val trimSickHead: StateFlow<Boolean> = _trimSickHead.asStateFlow()

    /** 湿声电平自动校准（批次 2，默认开）：装载新 IR 时按形态波峰因数设湿声电平 */
    private val _wetAutoCal = MutableStateFlow(prefs.getBoolean(KEY_WET_AUTO_CAL, true))
    val wetAutoCal: StateFlow<Boolean> = _wetAutoCal.asStateFlow()

    /** 最近一次自动校准的 IR 名（同一 IR 不重复覆盖用户手动微调；持久化） */
    private var lastCalIrName: String? = prefs.getString(KEY_LAST_CAL_IR, null)

    private fun setActiveRecentIr(name: String?) {
        _activeRecentIrName.value = name
        prefs.edit().putString(KEY_ACTIVE_IR_NAME, name).apply()
    }

    /** 文件名 → 显示名：去掉最后一个扩展名；旧命名 ir_ 前缀一并去掉 */
    private fun displayNameOf(fileName: String): String {
        val noPrefix = fileName.removePrefix("ir_")
        val dot = noPrefix.lastIndexOf('.')
        return if (dot > 0) noPrefix.substring(0, dot) else noPrefix
    }

    /**
     * 旧命名乱码判定：ir_ 前缀 + 含连续两个下划线（旧 sanitize 把中文全替 '_'，
     * ≥2 个连续 '_' 即中文残留位；又不在旧 JSON 5 条映射内 → 显示名无法还原）。
     */
    private fun isUnreadableLegacyName(name: String): Boolean =
        name.startsWith("ir_") && name.removePrefix("ir_").contains("__")

    /** 扫描专用目录刷新脉冲库（按显示名排序，重名文件以文件名兜底） */
    private fun refreshIrLibrary() {
        _irLibrary.value = pulseDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.map { IrLibraryEntry(displayNameOf(it.name), it.name, it.length()) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    // ---- b 专用脉冲目录 + 批量导入/导出（2026-10-25，工程阶段朴素 UI） ----

    /** 专用脉冲目录：所有 IR 文件统一存放于此（filesDir/pulse，私有持久） */
    /** 脉冲库目录：外部私有目录（/sdcard/Android/data/<pkg>/files/pulse），
     *  用户可用文件管理器/adb 直接放入文件；外部存储不可用时回退内部 files/pulse。 */
    private val pulseDir: File
        get() = File(
            context.getExternalFilesDir(null)?.absoluteFile ?: context.filesDir,
            "pulse",
        ).apply { mkdirs() }

    /** 旧内部库目录（迁移源）：pulseDir 曾在 files/pulse */
    private val legacyPulseDir: File
        get() = File(context.filesDir, "pulse")

    /** 文件名是否为可导入候选：扩展名白名单 + 非 hidden/系统文件 + 最小体积（防 0 字节）。
     *  size < 0 表示 provider/zip 未提供大小（未知 ≠ 0 字节），放行交由实际复制结果检验。 */
    private fun isIrCandidate(name: String, size: Long): Boolean {
        if (name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in irExtensions && (size < 0L || size >= 44L)
    }

    /**
     * 落盘名清洗：只滤路径分隔符与控制字符，保留中文/空格等（库列表直接展示文件名，
     * 中文名变下划线不可读）。ext4 UTF-8 文件名对 fopen/native 加载无影响。
     */
    private fun sanitizeIrFileName(raw: String): String = buildString {
        for (c in raw) {
            append(if (c == '/' || c == '\\' || c == '\u0000') '_' else c)
        }
    }.trim().take(80).ifBlank { "ir" }

    /** 按文件名解析 IR 实际路径：专用目录优先，旧根目录兜底（迁移异常时不丢加载能力） */
    private fun resolveIrFile(file: String): File {
        val inPulse = File(pulseDir, file)
        if (inPulse.exists()) return inPulse
        val legacy = File(context.filesDir, file)
        if (legacy.exists()) return legacy
        return inPulse
    }

    /**
     * 一次性迁移（幂等）：
     * 1) 旧根目录 ir_* 文件 → pulse/；
     * 2) 按旧 JSON 的 name→file 映射把 sanitize 名（ir_xxx.wav）重命名为原始显示名
     *    （库视图直接展示文件名，中文名必须还原）；
     * 3) active_ir_name 去扩展名（旧值带 .wav，新显示名不含）。
     * 完成后 JSON 记账退役（KEY_RECENT_IR_FILES 不再读写）。
     */
    private fun migrateLegacyIrFiles() {
        runCatching {
            val dir = pulseDir
            val legacy = context.filesDir.listFiles { f -> f.name.startsWith("ir_") }
            legacy?.forEach { f ->
                val dst = File(dir, f.name)
                if (!dst.exists()) f.renameTo(dst)
            }
            // 旧 JSON name→file 映射：把 ir_<safe> 还原为显示名
            val arr = runCatching {
                JSONArray(prefs.getString(KEY_RECENT_IR_FILES, "[]").orEmpty())
            }.getOrNull()
            if (arr != null) {
                var renamedCount = 0
                (0 until arr.length()).forEach { i ->
                    val o = arr.optJSONObject(i) ?: return@forEach
                    val name = o.optString("name")
                    val file = o.optString("file")
                    if (name.isBlank() || file.isBlank()) return@forEach
                    val src = File(dir, file)
                    val dstName = sanitizeIrFileName(name)
                    val dst = File(dir, dstName)
                    if (src.exists() && !dst.exists() && src.renameTo(dst)) renamedCount++
                }
                if (renamedCount > 0) {
                    SonicraftLog.i("MainViewModel", "IR 库迁移：$renamedCount 个文件名还原为显示名")
                }
            }
            // 旧 active_ir_name 带扩展名 → 去掉
            val active = prefs.getString(KEY_ACTIVE_IR_NAME, null)
            if (active != null && active.substringAfterLast('.', "") in irExtensions) {
                val stripped = displayNameOf(active)
                prefs.edit().putString(KEY_ACTIVE_IR_NAME, stripped).apply()
            }
            // 清掉不可还原的旧命名乱码文件（中文名已丢失，无法显示，用户需重导）
            val junk = dir.listFiles { f -> f.isFile && isUnreadableLegacyName(f.name) }
            if (junk?.isNotEmpty() == true) {
                val n = junk.count { it.delete() }
                SonicraftLog.i("MainViewModel", "IR 库迁移：清理 $n 个不可还原的旧命名乱码文件")
            }
        }
    }

    /**
     * 从 SAF 目录树批量导入（自动过滤扩展名/hidden/空文件，同名覆盖）。
     * 导入仅入脉冲目录并刷新库列表，不自动激活——不打断当前听音。
     */
    /** 落盘目标解析：重名同大小 → 重复内容跳过（返回 null）；
     *  重名不同大小 → 追加序号 " (2)" 防跨目录同名互相覆盖。 */
    private fun resolveTarget(baseName: String, size: Long): File? {
        val dst = File(pulseDir, baseName)
        if (!dst.exists()) return dst
        if (size >= 0L && dst.length() == size) return null
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var n = 2
        while (true) {
            val cand = File(pulseDir, "$stem ($n)$ext")
            if (!cand.exists()) return cand
            if (size >= 0L && cand.length() == size) return null
            n++
        }
    }

    /** strict UTF-8 验证：非法序列判负（String(bytes, UTF_8) 是 replace 语义，不能用作判定） */
    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    /**
     * 脉冲导入进度。
     *
     * 原实现是「一次性跑完、只在末尾给一条消息」——导入大压缩包或几十个文件时，
     * 用户面对的是几秒到十几秒的完全无反馈，只能怀疑是不是卡了。
     */
    data class IrImportProgress(
        val active: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
    ) {
        val fraction: Float
            get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _irImportProgress = MutableStateFlow(IrImportProgress())
    val irImportProgress: StateFlow<IrImportProgress> = _irImportProgress.asStateFlow()

    /** 从 SAF 压缩包导入（zip，防 zip slip：只取 basename）。
     *  条目名编码不统一：Windows 中文系统打包（资源管理器/360zip 等）常见 GBK 不置 EFS；
     *  也有打包器写 UTF-8 字节但不置 EFS；规范打包器 UTF-8+EFS——**同一包内可混存**。
     *  Android 自带 ZipFile 对无 EFS 条目的 charset 行为不一致 → 用 zip4j。
     *  逐条目判定：ISO-8859-1 打开（字节↔字符双射）无损拿原始字节 → strict 合法 UTF-8
     *  则按 UTF-8，否则按 GBK（解压数据走字节流，与文件名 charset 无关，单实例即可）。
     *  不能整包比 CJK 计数（85d7da6 旧法，已证实反向误判）：UTF-8 的 CJK 是 3 字节序列，
     *  按 2 字节 GBK 误解会撞出约 1.5 倍合法汉字（实测「SCRS®作品…」19→30），UTF-8 包
     *  必然选错；旧法在 GBK 包能赢只是因为 GBK 字节不是合法 UTF-8、撞不出量。
     *  strict 验证同时覆盖 EFS 置位条目（规范 UTF-8 字节必合法），EFS 仅作旁证。 */
    fun importZip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                var imported = 0
                var skipped = 0
                var deduped = 0
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                val tmp = File(context.cacheDir, "import_pulse_tmp.zip")
                try {
                    input.use { raw -> tmp.outputStream().use { raw.copyTo(it) } }
                    val gbk = Charset.forName("GBK")
                    val zf = net.lingala.zip4j.ZipFile(tmp.absolutePath).also {
                        it.charset = Charset.forName("ISO-8859-1")
                    }
                    // 进度的分母用「非目录条目数」：目录条目不该计入，否则 100% 永远到不了
                    val headers = zf.fileHeaders.filterNot { it.isDirectory }
                    var idx = 0
                    headers.forEach { header ->
                        idx++
                        _irImportProgress.value = IrImportProgress(
                            active = true,
                            total = headers.size,
                            done = idx,
                            current = header.fileName.substringAfterLast('/')
                                .substringAfterLast('\\').take(30),
                        )
                        val rawName = header.fileName.toByteArray(Charsets.ISO_8859_1)
                        val charset = if (isStrictUtf8(rawName)) StandardCharsets.UTF_8 else gbk
                        val full = String(rawName, charset)
                        val base = full.substringAfterLast('/').substringAfterLast('\\')
                        if (!isIrCandidate(base, header.uncompressedSize)) {
                            if (!base.startsWith(".") && !full.contains("__MACOSX")) skipped++
                            return@forEach
                        }
                        val dst = resolveTarget(sanitizeIrFileName(base), header.uncompressedSize)
                        if (dst == null) {
                            deduped++
                            return@forEach
                        }
                        zf.getInputStream(header).use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                        imported++
                    }
                } finally {
                    tmp.delete()
                    // 无论成功/失败/提前 return 都要收掉进度条，否则 UI 会一直停在「正在导入」
                    _irImportProgress.value = IrImportProgress()
                }
                refreshIrLibrary()
                if (imported == 0) {
                    return@runCatching "压缩包内未发现可导入的脉冲文件（跳过 $skipped 个）"
                }
                val extra = buildList {
                    if (skipped > 0) add("跳过 $skipped 个")
                    if (deduped > 0) add("去重 $deduped 个")
                }
                "已从压缩包导入 $imported 个脉冲文件${if (extra.isNotEmpty()) "（${extra.joinToString("，")}）" else ""}"
            }.onSuccess { msg -> _userIrMessage.value = msg }
                .onFailure { e ->
                    SonicraftLog.w("MainViewModel", "zip 导入异常：${e.message}")
                    _userIrMessage.value = "压缩包导入失败：${e.message}"
                }
        }
    }

    /** SAF 多选文件导入：单文件选择不受 Android 11+「禁止进入其他应用 Android/data」限制，
     *  是从 QQ 下载目录等位置导入的最可靠路径。每个文件独立容错。 */
    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                var imported = 0
                var skipped = 0
                var deduped = 0
                var fileIdx = 0
                for (uri in uris) {
                    fileIdx++
                    _irImportProgress.value = IrImportProgress(
                        active = true,
                        total = uris.size,
                        done = fileIdx,
                        current = "第 $fileIdx / ${uris.size} 个文件",
                    )
                    runCatching {
                        val projection = arrayOf(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_SIZE,
                        )
                        var name = "ir_${System.currentTimeMillis()}.irs"
                        var size = -1L
                        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                name = c.getString(0) ?: name
                                size = c.getLong(1)
                            }
                        }
                        if (!isIrCandidate(name, size)) {
                            skipped++
                            return@runCatching
                        }
                        val dst = resolveTarget(sanitizeIrFileName(name), size)
                        if (dst == null) {
                            deduped++
                            return@runCatching
                        }
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            dst.outputStream().use { ins.copyTo(it) }
                            imported++
                        } ?: run {
                            skipped++
                            SonicraftLog.w("MainViewModel", "导入文件：无法打开 $name")
                        }
                    }.onFailure { e ->
                        skipped++
                        SonicraftLog.w("MainViewModel", "导入文件单个失败：${e.message}")
                    }
                }
                refreshIrLibrary()
                if (imported == 0) {
                    return@runCatching "所选文件中没有可导入的脉冲文件（支持 ${irExtensions.joinToString("/")}，跳过 $skipped 个）"
                }
                val extra = buildList {
                    if (skipped > 0) add("跳过 $skipped 个")
                    if (deduped > 0) add("去重 $deduped 个")
                }
                "已导入 $imported 个脉冲文件到专用目录${if (extra.isNotEmpty()) "（${extra.joinToString("，")}）" else ""}"
            }.onSuccess { msg ->
                _irImportProgress.value = IrImportProgress()
                _userIrMessage.value = msg
            }.onFailure { e ->
                _irImportProgress.value = IrImportProgress()
                _userIrMessage.value = "文件导入失败：${e.message}"
            }
        }
    }

    /** 导出：专用目录全部脉冲文件打包 zip，写入系统「下载」目录（MediaStore.Downloads，无需权限）。 */
    fun exportAllToDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val files = pulseDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
                if (files.isEmpty()) return@runCatching "脉冲库为空，无可导出的脉冲文件"
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val displayName = "sonicraft_pulse_$ts.zip"
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法在下载目录创建导出文件")
                try {
                    resolver.openOutputStream(outUri)?.use { raw ->
                        ZipOutputStream(raw.buffered()).use { zip ->
                            files.forEach { f ->
                                zip.putNextEntry(ZipEntry(f.name))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalStateException("无法写入导出文件")
                } catch (e: Exception) {
                    resolver.delete(outUri, null, null)
                    throw e
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
                "已导出 ${files.size} 个脉冲文件到「下载」（$displayName）"
            }.onSuccess { msg -> _userIrMessage.value = msg }
                .onFailure { e -> _userIrMessage.value = "导出失败：${e.message}" }
        }
    }

    /**
     * 长按从库中删除：删 pulse/ 文件 + 刷新列表；若删除的是当前激活 IR，
     * 清除勾选并在「自定义」预设下回退大厅（引擎与 UI 同步）。
     */
    fun removeRecentIr(entry: IrLibraryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val removed = resolveIrFile(entry.file).delete()
                writeDiag("removeRecentIr: ${entry.file} deleted=$removed")
                refreshIrLibrary()
                forgetRecentLoaded(entry.name)
                if (_activeRecentIrName.value == entry.name) {
                    setActiveRecentIr(null)
                    if (_reverbPreset.value == AudioEngine.REVERB_PRESET_USER) {
                        prefs.edit().putInt(KEY_REVERB_PRESET, AudioEngine.REVERB_PRESET_HALL).apply()
                        withContext(Dispatchers.Main) {
                            _reverbPreset.value = AudioEngine.REVERB_PRESET_HALL
                        }
                        engine.setParam(
                            AudioEngine.CONVOLVER_NODE_INDEX,
                            AudioEngine.CONVOLVER_PARAM_PRESET,
                            AudioEngine.REVERB_PRESET_HALL.toFloat(),
                        )
                    }
                    _userIrAnalysis.value = null
                }
                "已移除「${entry.name}」"
            }.onSuccess { msg -> _userIrMessage.value = msg }
                .onFailure { e -> _userIrMessage.value = "移除失败：${e.message}" }
        }
    }

    /**
     * 一键清空脉冲库：删 pulse/ 全部文件 + 刷新；若当前激活自定义 IR，
     * 与单条移除一致地清除勾选并回退大厅（引擎与 UI 同步）。
     */
    fun clearLibraryIr() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val files = pulseDir.listFiles()?.filter { it.isFile }.orEmpty()
                var n = 0
                files.forEach { if (it.delete()) n++ }
                writeDiag("clearLibraryIr: deleted=$n/${files.size}")
                refreshIrLibrary()
                prefs.edit().putString(KEY_RECENT_LOADED_IRS, "[]").apply()
                _recentLoadedNames.value = emptyList()
                if (_activeRecentIrName.value != null) {
                    setActiveRecentIr(null)
                    if (_reverbPreset.value == AudioEngine.REVERB_PRESET_USER) {
                        prefs.edit().putInt(KEY_REVERB_PRESET, AudioEngine.REVERB_PRESET_HALL).apply()
                        withContext(Dispatchers.Main) {
                            _reverbPreset.value = AudioEngine.REVERB_PRESET_HALL
                        }
                        engine.setParam(
                            AudioEngine.CONVOLVER_NODE_INDEX,
                            AudioEngine.CONVOLVER_PARAM_PRESET,
                            AudioEngine.REVERB_PRESET_HALL.toFloat(),
                        )
                    }
                    _userIrAnalysis.value = null
                }
                "已清空脉冲库（删除 $n 个文件）"
            }.onSuccess { msg -> _userIrMessage.value = msg }
                .onFailure { e -> _userIrMessage.value = "清空失败：${e.message}" }
        }
    }

    /** 从脉冲库加载 IR（pulse/ 文件已持久化，不依赖 content Uri 重授权） */
    fun loadLibraryIr(entry: IrLibraryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            // P1（2026-10-27 自检）：装载+切预设是临界区，与其他装载入口互斥
            // （并发重装会交错写 slot 5 spec）；结果回调留在锁外
            irLoadMutex.withLock {
                runCatching {
                    val f = resolveIrFile(entry.file)
                    if (!f.exists()) throw IllegalStateException("文件已被系统清理，请重新选择")
                    val err = engine.loadImpulseResponse(f.absolutePath)
                    if (err == "引擎未就绪") {
                        setActiveRecentIr(entry.name)
                        recordRecentLoaded(entry.name)
                        prefs.edit().putInt(KEY_REVERB_PRESET, AudioEngine.REVERB_PRESET_USER).apply()
                        withContext(Dispatchers.Main) {
                            _reverbPreset.value = AudioEngine.REVERB_PRESET_USER
                        }
                        return@runCatching "IR 已就绪：启动内录后将自动加载"
                    }
                    err?.let { throw IllegalStateException(it) }
                    setActiveRecentIr(entry.name)
                    recordRecentLoaded(entry.name)
                    prefs.edit().putInt(KEY_REVERB_PRESET, AudioEngine.REVERB_PRESET_USER).apply()
                    withContext(Dispatchers.Main) {
                        _reverbPreset.value = AudioEngine.REVERB_PRESET_USER
                    }
                    engine.setParam(
                        AudioEngine.CONVOLVER_NODE_INDEX,
                        AudioEngine.CONVOLVER_PARAM_PRESET,
                        AudioEngine.REVERB_PRESET_USER.toFloat(),
                    )
                    "已加载 ${entry.name}"
                }
            }.onSuccess { msg ->
                _userIrMessage.value = msg
                refreshUserIrAnalysis()
            }.onFailure { e ->
                _userIrMessage.value = "IR 加载失败：${e.message}（保持原预设）"
            }
        }
    }

    /**
     * 病态 IR 自动对齐开关（方案 A，2026-10-26；2026-10-27 范围扩大）：前置
     * 静音 >30ms 的 IR 装载时自动裁除静音前缀（含设计型纯零延迟，病态一律
     * 校正），湿声与干声对齐消除双声。切换后对当前挂着的自定义 IR 立即重装
     * 生效；关闭后警示卡回归。
     */
    fun setTrimSickHead(enabled: Boolean) {
        _trimSickHead.value = enabled
        prefs.edit().putBoolean(KEY_TRIM_SICK_HEAD, enabled).apply()
        engine.setTrimSickHead(enabled)
        if (_reverbPreset.value != AudioEngine.REVERB_PRESET_USER) return
        val activeName = _activeRecentIrName.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // P1：重装载是临界区（preset 已挂 USER，实时线程在跑），与其他装载入口互斥
            irLoadMutex.withLock {
                runCatching {
                    val entry = _irLibrary.value.firstOrNull { it.name == activeName }
                        ?: return@runCatching
                    val f = resolveIrFile(entry.file)
                    if (!f.exists()) return@runCatching
                    val err = engine.loadImpulseResponse(f.absolutePath)
                    err?.let { return@runCatching }
                    engine.setParam(
                        AudioEngine.CONVOLVER_NODE_INDEX,
                        AudioEngine.CONVOLVER_PARAM_PRESET,
                        AudioEngine.REVERB_PRESET_USER.toFloat(),
                    )
                }
            }
            refreshUserIrAnalysis()
        }
    }

    /**
     * 湿声电平自动校准开关（批次 2，2026-10-27，默认开）：装载新 IR 时按其
     * 形态（波峰因数）自动设湿声电平——脉冲型增强、扩散长尾收敛、同房间形态
     * ≈1.0 无感迁移。打开时立即对当前挂着的 IR 应用一次；关闭后湿声电平
     * 全手动（不再自动覆盖）。
     */
    fun setWetAutoCal(enabled: Boolean) {
        _wetAutoCal.value = enabled
        prefs.edit().putBoolean(KEY_WET_AUTO_CAL, enabled).apply()
        if (enabled) {
            // 强制对当前 IR 重新校准一次；prefs 同步清（否则重启后旧记录
            // 又把重校准跳过——2026-10-27 真机迁移验证发现的遗漏）
            lastCalIrName = null
            prefs.edit().remove(KEY_LAST_CAL_IR).apply()
            maybeAutoCalWet(_userIrAnalysis.value)
        }
    }

    // ---- 立体声宽度控制（M/S 矩阵，默认关） ----
    /** 宽度开关：开 = 应用 M/S 矩阵；关 = 透传 */
    private val _stereoWidthEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_STEREO_WIDTH_ENABLED, false),
    )
    val stereoWidthEnabled: StateFlow<Boolean> = _stereoWidthEnabled.asStateFlow()

    /** 宽度系数：0=单声道，1=原样，2=加倍展宽（30ms 斜坡） */
    private val _stereoWidth = MutableStateFlow(prefs.getFloat(KEY_STEREO_WIDTH, 1f))
    val stereoWidth: StateFlow<Float> = _stereoWidth.asStateFlow()

    fun setStereoWidth(width: Float) {
        val clamped = width.coerceIn(0f, 2f)
        _stereoWidth.value = clamped
        prefs.edit().putFloat(KEY_STEREO_WIDTH, clamped).apply()
        engine.setParam(
            AudioEngine.STEREO_WIDTH_NODE_INDEX,
            AudioEngine.STEREO_WIDTH_PARAM_WIDTH,
            clamped,
        )
    }

    fun setStereoWidthEnabled(enabled: Boolean) {
        _stereoWidthEnabled.value = enabled
        prefs.edit().putBoolean(KEY_STEREO_WIDTH_ENABLED, enabled).apply()
        engine.setBypass(AudioEngine.STEREO_WIDTH_NODE_INDEX, !enabled)
    }

    // ---- 虚拟环绕 ----
    // 链路位置在 AutoEq 之后、低频/动态之前：先做耳机校正再空间化，
    // 否则渲染染色会被校正曲线抵消。
    private val _vsEnabled = MutableStateFlow(prefs.getBoolean(KEY_VS_ENABLED, false))
    val vsEnabled: StateFlow<Boolean> = _vsEnabled.asStateFlow()

    private val _vsMode = MutableStateFlow(prefs.getInt(KEY_VS_MODE, 0))
    val vsMode: StateFlow<Int> = _vsMode.asStateFlow()

    private val _vsSurroundGain = MutableStateFlow(prefs.getFloat(KEY_VS_SURROUND_GAIN, 0f))
    val vsSurroundGain: StateFlow<Float> = _vsSurroundGain.asStateFlow()

    private val _vsCenterGain = MutableStateFlow(prefs.getFloat(KEY_VS_CENTER_GAIN, 0f))
    val vsCenterGain: StateFlow<Float> = _vsCenterGain.asStateFlow()

    private val _vsFrontGain = MutableStateFlow(prefs.getFloat(KEY_VS_FRONT_GAIN, 0f))
    val vsFrontGain: StateFlow<Float> = _vsFrontGain.asStateFlow()

    /**
     * 空间环绕声像定位自检：跑一次测量 + 自动校正，返回可展示的多行文本。
     * 非实时（会分配内存、跑 7 个方位 × 2 轮），必须在后台线程调用。
     */
    fun runVirtualSurroundSelfTest(): String = engine.vsSelfTest()

    fun setVsEnabled(enabled: Boolean) {
        _vsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_VS_ENABLED, enabled).apply()
        engine.setBypass(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, !enabled)
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_ENABLED,
            if (enabled) 1f else 0f,
        )
        // 立即落盘当前设备配置：10s 周期快照会把开关状态覆盖成旧值（历史问题）
        runCatching { saveLiveConfig(outputDeviceMonitor.current.value.key) }
    }

    fun setVsMode(mode: Int) {
        val v = mode.coerceIn(0, 3)
        _vsMode.value = v
        prefs.edit().putInt(KEY_VS_MODE, v).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_MODE,
            v.toFloat(),
        )
    }

    fun setVsSurroundGain(db: Float) {
        val v = db.coerceIn(-12f, 12f)
        _vsSurroundGain.value = v
        prefs.edit().putFloat(KEY_VS_SURROUND_GAIN, v).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_SURROUND_GAIN,
            v,
        )
    }

    fun setVsCenterGain(db: Float) {
        val v = db.coerceIn(-12f, 12f)
        _vsCenterGain.value = v
        prefs.edit().putFloat(KEY_VS_CENTER_GAIN, v).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_CENTER_GAIN,
            v,
        )
    }

    // 后处理参数（算法已实现，这里补状态与下发）
    // 参数合理性：读到 NaN/越界一律回落到默认（防止误触滑条把坏值写进 prefs）
    private fun saneFloat(key: String, def: Float, lo: Float, hi: Float): Float {
        val v = prefs.getFloat(key, def)
        return if (v.isFinite()) v.coerceIn(lo, hi) else def
    }

    // 逐声道 trim：7 个值打包成一个数组，避免 14 个 StateFlow 各自拖一份状态。
    // 顺序 FL/FR/C/SL/SR/CS/LFE（与 native 一致），落盘为逗号串。
    private fun loadVsFloats(key: String, count: Int, def: Float, lo: Float, hi: Float): FloatArray {
        val out = FloatArray(count) { def }
        val s = prefs.getString(key, "") ?: ""
        if (s.isNotBlank()) {
            s.split(',').take(count).forEachIndexed { i, t ->
                val v = t.toFloatOrNull()
                if (v != null && v.isFinite()) out[i] = v.coerceIn(lo, hi)
            }
        }
        return out
    }

    private fun saveVsFloats(key: String, arr: FloatArray) =
        prefs.edit().putString(key, arr.joinToString(",")).apply()

    private val _vsDebugRaw = MutableStateFlow(prefs.getBoolean(KEY_VS_DEBUG_RAW, false))
    val vsDebugRaw: StateFlow<Boolean> = _vsDebugRaw.asStateFlow()

    fun setVsDebugRaw(on: Boolean) {
        _vsDebugRaw.value = on
        prefs.edit().putBoolean(KEY_VS_DEBUG_RAW, on).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_DEBUG_RAW,
            if (on) 1f else 0f,
        )
    }

    private val _vsTrims = MutableStateFlow(loadVsFloats(KEY_VS_TRIMS, 7, 0f, -12f, 12f))
    val vsTrims: StateFlow<FloatArray> = _vsTrims.asStateFlow()

    private val _vsMutes = MutableStateFlow(loadVsFloats(KEY_VS_MUTES, 7, 0f, 0f, 1f))
    val vsMutes: StateFlow<FloatArray> = _vsMutes.asStateFlow()

    private val vsTrimIds = intArrayOf(
        AudioEngine.VS_PARAM_TRIM_L, AudioEngine.VS_PARAM_TRIM_R, AudioEngine.VS_PARAM_TRIM_C,
        AudioEngine.VS_PARAM_TRIM_SL, AudioEngine.VS_PARAM_TRIM_SR, AudioEngine.VS_PARAM_TRIM_CS,
        AudioEngine.VS_PARAM_TRIM_LFE,
    )
    private val vsMuteIds = intArrayOf(
        AudioEngine.VS_PARAM_MUTE_L, AudioEngine.VS_PARAM_MUTE_R, AudioEngine.VS_PARAM_MUTE_C,
        AudioEngine.VS_PARAM_MUTE_SL, AudioEngine.VS_PARAM_MUTE_SR, AudioEngine.VS_PARAM_MUTE_CS,
        AudioEngine.VS_PARAM_MUTE_LFE,
    )

    fun setVsTrim(ch: Int, db: Float) {
        if (ch !in 0..6) return
        val v = if (db.isFinite()) db.coerceIn(-12f, 12f) else 0f
        val arr = _vsTrims.value.copyOf()
        arr[ch] = v
        _vsTrims.value = arr
        saveVsFloats(KEY_VS_TRIMS, arr)
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, vsTrimIds[ch], v)
    }

    fun setVsMute(ch: Int, muted: Boolean) {
        if (ch !in 0..6) return
        val arr = _vsMutes.value.copyOf()
        arr[ch] = if (muted) 1f else 0f
        _vsMutes.value = arr
        saveVsFloats(KEY_VS_MUTES, arr)
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            vsMuteIds[ch],
            if (muted) 1f else 0f,
        )
    }

    /** 还原默认：全部 0 dB、全部解除静音 */
    fun resetVsTrims() {
        _vsTrims.value = FloatArray(7)
        _vsMutes.value = FloatArray(7)
        saveVsFloats(KEY_VS_TRIMS, FloatArray(7))
        saveVsFloats(KEY_VS_MUTES, FloatArray(7))
        for (i in 0..6) {
            engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, vsTrimIds[i], 0f)
            engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, vsMuteIds[i], 0f)
        }
    }

    private val _vsRearSpace = MutableStateFlow(saneFloat(KEY_VS_REAR_SPACE, 0.5f, 0f, 1f))
    val vsRearSpace: StateFlow<Float> = _vsRearSpace.asStateFlow()

    fun setVsRearSpace(v0: Float) {
        val v = if (v0.isFinite()) v0.coerceIn(0f, 1f) else _vsRearSpace.value
        _vsRearSpace.value = v
        prefs.edit().putFloat(KEY_VS_REAR_SPACE, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_STEREO_EXPAND, v)
    }

    private val _vsRearGain = MutableStateFlow(saneFloat(KEY_VS_REAR_GAIN, -6f, -12f, 12f))
    val vsRearGain: StateFlow<Float> = _vsRearGain.asStateFlow()

    fun setVsRearGain(db: Float) {
        val v = if (db.isFinite()) db.coerceIn(-12f, 12f) else _vsRearGain.value
        _vsRearGain.value = v
        prefs.edit().putFloat(KEY_VS_REAR_GAIN, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_REAR_GAIN, v)
    }

    private val _vsRoomSize = MutableStateFlow(prefs.getInt(KEY_VS_ROOM_SIZE, 0))
    val vsRoomSize: StateFlow<Int> = _vsRoomSize.asStateFlow()

    private val _vsRoomWet = MutableStateFlow(prefs.getFloat(KEY_VS_ROOM_WET, 0.35f))
    val vsRoomWet: StateFlow<Float> = _vsRoomWet.asStateFlow()

    fun setVsRoomSize(size: Int) {
        val v = size.coerceIn(0, 4)
        _vsRoomSize.value = v
        prefs.edit().putInt(KEY_VS_ROOM_SIZE, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_ROOM_SIZE, v.toFloat())
    }

    fun setVsRoomWet(v0: Float) {
        val v = v0.coerceIn(0f, 1f)
        _vsRoomWet.value = v
        prefs.edit().putFloat(KEY_VS_ROOM_WET, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_ROOM_WET_DRY, v)
    }

    private val _vsEncodeStrength = MutableStateFlow(prefs.getFloat(KEY_VS_ENCODE_STRENGTH, 0.5f))
    val vsEncodeStrength: StateFlow<Float> = _vsEncodeStrength.asStateFlow()

    /**
     * 二级解码开关：关 → 强度 0（信号只走一级解码），开 → 恢复上次强度。
     * 强度 0 时节点内部整段跳过往返，等于一级解码直出。
     */
    fun setVsSecondDecodeEnabled(enabled: Boolean) {
        if (enabled) {
            val restore = if (_vsEncodeStrength.value > 0.01f) _vsEncodeStrength.value else 0.5f
            setVsEncodeStrength(restore)
        } else {
            setVsEncodeStrength(0f)
        }
    }

    fun setVsEncodeStrength(v0: Float) {
        val v = v0.coerceIn(0f, 1f)
        _vsEncodeStrength.value = v
        prefs.edit().putFloat(KEY_VS_ENCODE_STRENGTH, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_ENCODE_STRENGTH, v)
    }

    private val _vsCenterMode = MutableStateFlow(prefs.getInt(KEY_VS_CENTER_MODE, 1))
    val vsCenterMode: StateFlow<Int> = _vsCenterMode.asStateFlow()

    private val _vsDialog = MutableStateFlow(prefs.getFloat(KEY_VS_DIALOG, 0f))
    val vsDialog: StateFlow<Float> = _vsDialog.asStateFlow()

    private val _vsBassEnh = MutableStateFlow(prefs.getFloat(KEY_VS_BASS_ENH, 0f))
    val vsBassEnh: StateFlow<Float> = _vsBassEnh.asStateFlow()

    private val _vsBassEnhMode = MutableStateFlow(prefs.getInt(KEY_VS_BASS_ENH_MODE, 0))
    val vsBassEnhMode: StateFlow<Int> = _vsBassEnhMode.asStateFlow()

    private val _vsRearCenter = MutableStateFlow(prefs.getBoolean(KEY_VS_REAR_CENTER, false))
    val vsRearCenter: StateFlow<Boolean> = _vsRearCenter.asStateFlow()

    private val _vsRearDelay = MutableStateFlow(prefs.getFloat(KEY_VS_REAR_DELAY, 5f))
    val vsRearDelay: StateFlow<Float> = _vsRearDelay.asStateFlow()

    fun setVsCenterMode(mode: Int) {
        val v = mode.coerceIn(0, 2)
        _vsCenterMode.value = v
        prefs.edit().putInt(KEY_VS_CENTER_MODE, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_CENTER_MODE, v.toFloat())
    }

    fun setVsDialog(db: Float) {
        val v = db.coerceIn(-6f, 6f)
        _vsDialog.value = v
        prefs.edit().putFloat(KEY_VS_DIALOG, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_DIALOG, v)
    }

    fun setVsBassEnh(amount: Float) {
        val v = amount.coerceIn(0f, 1f)
        _vsBassEnh.value = v
        prefs.edit().putFloat(KEY_VS_BASS_ENH, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_BASS_ENHANCER, v)
    }

    fun setVsBassEnhMode(mode: Int) {
        val v = mode.coerceIn(0, 1)
        _vsBassEnhMode.value = v
        prefs.edit().putInt(KEY_VS_BASS_ENH_MODE, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_BASS_ENHANCER_MODE, v.toFloat())
    }

    fun setVsRearCenter(enabled: Boolean) {
        _vsRearCenter.value = enabled
        prefs.edit().putBoolean(KEY_VS_REAR_CENTER, enabled).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_REAR_CENTER_ENABLED,
            if (enabled) 1f else 0f,
        )
    }

    fun setVsRearDelay(ms: Float) {
        val v = ms.coerceIn(0f, 15f)
        _vsRearDelay.value = v
        prefs.edit().putFloat(KEY_VS_REAR_DELAY, v).apply()
        engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_REAR_CENTER_DELAY_MS, v)
    }

    fun setVsFrontGain(db: Float) {
        val v = db.coerceIn(-12f, 12f)
        _vsFrontGain.value = v
        prefs.edit().putFloat(KEY_VS_FRONT_GAIN, v).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_FRONT_GAIN,
            v,
        )
    }

    // ---- 动态低音----

    /**
     * 动态低音开关。
     *
     * 与压限器那种「总闸 + 各卡记忆」不同：本效果器只有**一个节点**，
     * 开关就是旁路本身，不需要第二层。用户看到的是一个效果器的开关，
     * 语义与参量均衡/立体声宽度/限幅器完全一致。
     */
    private val _dynamicBassEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_BASS_ENABLED, false))
    val dynamicBassEnabled: StateFlow<Boolean> = _dynamicBassEnabled.asStateFlow()

    /** 总强度 0~1：按比例缩放动态增强与保护 */
    private val _dynamicBassAmount =
        MutableStateFlow(prefs.getFloat(KEY_DYNAMIC_BASS_AMOUNT, 0.35f))
    val dynamicBassAmount: StateFlow<Float> = _dynamicBassAmount.asStateFlow()

    /** 次低频清理截止（Hz，0 = 关闭） */
    private val _dynamicBassSubsonic =
        MutableStateFlow(prefs.getFloat(KEY_DYNAMIC_BASS_SUBSONIC, 30f))
    val dynamicBassSubsonic: StateFlow<Float> = _dynamicBassSubsonic.asStateFlow()

    /** 低音中心频率（分频点，40~150Hz） */
    private val _dynamicBassFocus =
        MutableStateFlow(prefs.getFloat(KEY_DYNAMIC_BASS_FOCUS, 80f))
    val dynamicBassFocus: StateFlow<Float> = _dynamicBassFocus.asStateFlow()

    /** 动态增强深度 0~1 */
    private val _dynamicBassBoostDepth =
        MutableStateFlow(prefs.getFloat(KEY_DYNAMIC_BASS_BOOST_DEPTH, 0.5f))
    val dynamicBassBoostDepth: StateFlow<Float> = _dynamicBassBoostDepth.asStateFlow()

    /** 低频保护阈值 0~1（0 = 关闭；越高越晚介入） */
    private val _dynamicBassProtect =
        MutableStateFlow(prefs.getFloat(KEY_DYNAMIC_BASS_PROTECT, 0.6f))
    val dynamicBassProtect: StateFlow<Float> = _dynamicBassProtect.asStateFlow()

    /**
     * 低频管家**总闸**。
     *
     * 与压限器/混响同一套语义：关 = 两个节点同时旁通、各槽记忆不动。
     * 缺省按「任一槽开着」推断，老用户升级后 `dynamic_bass_enabled=true` 时总闸自动是开。
     */
    private val _lowfreqSectionEnabled = MutableStateFlow(
        prefs.getBoolean(
            KEY_LOWFREQ_SECTION_ENABLED,
            prefs.getBoolean(KEY_DYNAMIC_BASS_ENABLED, false) ||
                prefs.getBoolean(KEY_VIRTUAL_BASS_ENABLED, false),
        ),
    )
    val lowfreqSectionEnabled: StateFlow<Boolean> = _lowfreqSectionEnabled.asStateFlow()

    /** 虚拟低音开关（槽记忆）。与动态低音**互斥**。 */
    private val _virtualBassEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_VIRTUAL_BASS_ENABLED, false))
    val virtualBassEnabled: StateFlow<Boolean> = _virtualBassEnabled.asStateFlow()

    /** 干湿比 0~1：0 = 纯原声（逐位透明）、1 = 纯虚拟低音 */
    private val _virtualBassAmount =
        MutableStateFlow(prefs.getFloat(KEY_VIRTUAL_BASS_AMOUNT, 1.0f))
    val virtualBassAmount: StateFlow<Float> = _virtualBassAmount.asStateFlow()

    /** 谐波注入量 0~2 */
    private val _virtualBassHarmonic =
        MutableStateFlow(prefs.getFloat(KEY_VIRTUAL_BASS_HARMONIC, 1.0f))
    val virtualBassHarmonic: StateFlow<Float> = _virtualBassHarmonic.asStateFlow()

    /** 120~600Hz 直通量 0~1 */
    private val _virtualBassMidGain =
        MutableStateFlow(prefs.getFloat(KEY_VIRTUAL_BASS_MID_GAIN, 0.08f))
    val virtualBassMidGain: StateFlow<Float> = _virtualBassMidGain.asStateFlow()

    /** 600Hz+ 直通量 0~1 */
    private val _virtualBassHighGain =
        MutableStateFlow(prefs.getFloat(KEY_VIRTUAL_BASS_HIGH_GAIN, 0.16f))
    val virtualBassHighGain: StateFlow<Float> = _virtualBassHighGain.asStateFlow()

    /** 包络平滑截止 5~150Hz（越小越"抽吸"，越大越跟手） */
    private val _virtualBassEnvelopeRate =
        MutableStateFlow(prefs.getFloat(KEY_VIRTUAL_BASS_ENVELOPE_RATE, 50f))
    val virtualBassEnvelopeRate: StateFlow<Float> = _virtualBassEnvelopeRate.asStateFlow()

    // ---- 低音增强（2026-11-11，设备无关）----

    /** 低音增强开关（槽记忆）。与动态低音/虚拟低音三者互斥。 */
    private val _bassEnhanceEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BASS_ENHANCE_ENABLED, false))
    val bassEnhanceEnabled: StateFlow<Boolean> = _bassEnhanceEnabled.asStateFlow()

    /** 总强度 0~1（映射 wecho 的 gain 0~10） */
    private val _bassEnhanceAmount =
        MutableStateFlow(prefs.getFloat(KEY_BASS_ENHANCE_AMOUNT, 0.5f))
    val bassEnhanceAmount: StateFlow<Float> = _bassEnhanceAmount.asStateFlow()

    /** 下潜量 0~1（动态增益 + 次谐波） */
    private val _bassEnhanceDeep =
        MutableStateFlow(prefs.getFloat(KEY_BASS_ENHANCE_DEEP, 0.3f))
    val bassEnhanceDeep: StateFlow<Float> = _bassEnhanceDeep.asStateFlow()

    /** 瞬态量 0~1（鼓点打击感） */
    private val _bassEnhancePunch =
        MutableStateFlow(prefs.getFloat(KEY_BASS_ENHANCE_PUNCH, 0.6f))
    val bassEnhancePunch: StateFlow<Float> = _bassEnhancePunch.asStateFlow()

    /**
     * 模式：0 = 经典、1 = 动态（默认，与 wecho 新版一致）。
     * 经典模式只做「低通 60Hz + 按 dB 叠加」，音色更传统；
     * 动态模式是 M/S + 包络 + 瞬态 + 次谐波那一套。
     * **两套参数完全独立存储**，切模式不会互相覆盖。
     */
    private val _bassEnhanceMode =
        MutableStateFlow(prefs.getInt(KEY_BASS_ENHANCE_MODE, 1))
    val bassEnhanceMode: StateFlow<Int> = _bassEnhanceMode.asStateFlow()

    /** 经典模式增益 0~15 dB */
    private val _bassEnhanceClassicGain =
        MutableStateFlow(prefs.getFloat(KEY_BASS_ENHANCE_CLASSIC_GAIN, 6f))
    val bassEnhanceClassicGain: StateFlow<Float> = _bassEnhanceClassicGain.asStateFlow()

    /** 经典模式低通 Q（0.3~2.0，越高低频越"鼓"） */
    private val _bassEnhanceClassicQ =
        MutableStateFlow(prefs.getFloat(KEY_BASS_ENHANCE_CLASSIC_Q, 0.7071f))
    val bassEnhanceClassicQ: StateFlow<Float> = _bassEnhanceClassicQ.asStateFlow()

    /** 经典模式低通中心频率 30~120Hz */
    private val _bassEnhanceClassicCenter =
        MutableStateFlow(prefs.getFloat(KEY_BASS_ENHANCE_CLASSIC_CENTER, 60f))
    val bassEnhanceClassicCenter: StateFlow<Float> =
        _bassEnhanceClassicCenter.asStateFlow()

    // ---- 低频管家：总闸 / 统一旁通 / 两个槽的开关 ----

    fun setLowfreqSectionEnabled(enabled: Boolean) {
        if (_lowfreqSectionEnabled.value == enabled) return
        _lowfreqSectionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_LOWFREQ_SECTION_ENABLED, enabled).apply()
        applyLowfreqBypass()
    }

    /**
     * 低频管家统一旁通下发。
     *
     * **所有**影响这条链的入口（总闸 / 动态低音 / 虚拟低音 / 重放）都走这一个函数。
     * 为什么：本项目历史上反复踩「同一状态散在多处维护、漏改一处」的坑，
     * 加第三层状态后若继续各写各的，必然出现某个入口漏了判断。
     */
    private fun applyLowfreqBypass() {
        val sec = _lowfreqSectionEnabled.value
        val dyn = sec && _dynamicBassEnabled.value
        val enh = sec && _bassEnhanceEnabled.value
        val vir = sec && _virtualBassEnabled.value
        engine.setBypass(AudioEngine.DYNAMIC_BASS_NODE_INDEX, !dyn)
        engine.setBypass(AudioEngine.BASS_ENHANCE_NODE_INDEX, !enh)
        engine.setBypass(AudioEngine.VIRTUAL_BASS_NODE_INDEX, !vir)
        // 可观测性（2026-11-11 P1 修复）：建链后到底有没有重放旁路，
        // 以前只能靠「听不出来」猜。现在 autostart_diag.txt 里可直接回读。
        writeDiag("lowfreqBypass: sec=$sec dyn=$dyn enh=$enh vir=$vir")
    }

    /**
     * 动态低音开关（**槽记忆**）。开它会把虚拟低音的**记忆**也置 false。
     *
     * 为什么这里写 prefs、而压限器的互斥只改内存：压限器保留单段的用户偏好，
     * 重启后由恢复规则消解；但低频管家两个槽是「二选一」，若 prefs 里都是 true，
     * 重放（进程启动时 prefs 是唯一真相）会让两个节点同时出声 —— 互相破坏。
     */
    fun setDynamicBassEnabled(enabled: Boolean) {
        _dynamicBassEnabled.value = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC_BASS_ENABLED, enabled).apply()
        applyLowfreqBypass()
    }

    fun setDynamicBassAmount(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _dynamicBassAmount.value = c
        prefs.edit().putFloat(KEY_DYNAMIC_BASS_AMOUNT, c).apply()
        engine.setParam(
            AudioEngine.DYNAMIC_BASS_NODE_INDEX, AudioEngine.DYNAMIC_BASS_PARAM_AMOUNT, c,
        )
    }

    /** 次低频清理：0 = 关闭；上限 50Hz（再高会切掉可听的低频内容） */
    fun setDynamicBassSubsonic(hz: Float) {
        val c = hz.coerceIn(0f, 50f)
        _dynamicBassSubsonic.value = c
        prefs.edit().putFloat(KEY_DYNAMIC_BASS_SUBSONIC, c).apply()
        engine.setParam(
            AudioEngine.DYNAMIC_BASS_NODE_INDEX, AudioEngine.DYNAMIC_BASS_PARAM_SUBSONIC_HZ, c,
        )
    }

    /** 低音中心频率：同时是分频点与动态增强的作用带中心 */
    fun setDynamicBassFocus(hz: Float) {
        val c = hz.coerceIn(40f, 150f)
        _dynamicBassFocus.value = c
        prefs.edit().putFloat(KEY_DYNAMIC_BASS_FOCUS, c).apply()
        engine.setParam(
            AudioEngine.DYNAMIC_BASS_NODE_INDEX, AudioEngine.DYNAMIC_BASS_PARAM_FOCUS_HZ, c,
        )
    }

    fun setDynamicBassBoostDepth(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _dynamicBassBoostDepth.value = c
        prefs.edit().putFloat(KEY_DYNAMIC_BASS_BOOST_DEPTH, c).apply()
        engine.setParam(
            AudioEngine.DYNAMIC_BASS_NODE_INDEX, AudioEngine.DYNAMIC_BASS_PARAM_BOOST_DEPTH, c,
        )
    }

    fun setDynamicBassProtect(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _dynamicBassProtect.value = c
        prefs.edit().putFloat(KEY_DYNAMIC_BASS_PROTECT, c).apply()
        engine.setParam(
            AudioEngine.DYNAMIC_BASS_NODE_INDEX, AudioEngine.DYNAMIC_BASS_PARAM_PROTECT_LEVEL, c,
        )
    }

    // ---- 虚拟低音（2026-11-11，手机内置扬声器专用）----

    /** 虚拟低音开关（槽记忆）。开它会把动态低音的**记忆**也置 false。 */
    fun setVirtualBassEnabled(enabled: Boolean) {
        _virtualBassEnabled.value = enabled
        prefs.edit().putBoolean(KEY_VIRTUAL_BASS_ENABLED, enabled).apply()
        applyLowfreqBypass()
    }

    /** 干湿比：0 = 纯原声（逐位透明），1 = 纯虚拟低音 */
    fun setVirtualBassAmount(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _virtualBassAmount.value = c
        prefs.edit().putFloat(KEY_VIRTUAL_BASS_AMOUNT, c).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_BASS_NODE_INDEX, AudioEngine.VIRTUAL_BASS_PARAM_AMOUNT, c,
        )
    }

    fun setVirtualBassHarmonic(v: Float) {
        val c = v.coerceIn(0f, 2f)
        _virtualBassHarmonic.value = c
        prefs.edit().putFloat(KEY_VIRTUAL_BASS_HARMONIC, c).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_BASS_NODE_INDEX, AudioEngine.VIRTUAL_BASS_PARAM_HARMONIC, c,
        )
    }

    fun setVirtualBassMidGain(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _virtualBassMidGain.value = c
        prefs.edit().putFloat(KEY_VIRTUAL_BASS_MID_GAIN, c).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_BASS_NODE_INDEX, AudioEngine.VIRTUAL_BASS_PARAM_MID_GAIN, c,
        )
    }

    fun setVirtualBassHighGain(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _virtualBassHighGain.value = c
        prefs.edit().putFloat(KEY_VIRTUAL_BASS_HIGH_GAIN, c).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_BASS_NODE_INDEX, AudioEngine.VIRTUAL_BASS_PARAM_HIGH_GAIN, c,
        )
    }

    fun setVirtualBassEnvelopeRate(hz: Float) {
        val c = hz.coerceIn(5f, 150f)
        _virtualBassEnvelopeRate.value = c
        prefs.edit().putFloat(KEY_VIRTUAL_BASS_ENVELOPE_RATE, c).apply()
        engine.setParam(
            AudioEngine.VIRTUAL_BASS_NODE_INDEX, AudioEngine.VIRTUAL_BASS_PARAM_ENVELOPE_RATE, c,
        )
    }

    // ---- 低音增强（2026-11-11，设备无关）----

    /** 低音增强开关（槽记忆）。开启时把另外两个低频槽的记忆置 false。 */
    fun setBassEnhanceEnabled(enabled: Boolean) {
        _bassEnhanceEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BASS_ENHANCE_ENABLED, enabled).apply()
        applyLowfreqBypass()
    }

    /** 总强度 0~1（映射 wecho 的 gain 0~10） */
    fun setBassEnhanceAmount(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _bassEnhanceAmount.value = c
        prefs.edit().putFloat(KEY_BASS_ENHANCE_AMOUNT, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX, AudioEngine.BASS_ENHANCE_PARAM_AMOUNT, c,
        )
    }

    /** 下潜量 0~1（动态增益 + 次谐波） */
    fun setBassEnhanceDeep(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _bassEnhanceDeep.value = c
        prefs.edit().putFloat(KEY_BASS_ENHANCE_DEEP, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX, AudioEngine.BASS_ENHANCE_PARAM_DEEP, c,
        )
    }

    /** 瞬态量 0~1（鼓点打击感） */
    fun setBassEnhancePunch(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _bassEnhancePunch.value = c
        prefs.edit().putFloat(KEY_BASS_ENHANCE_PUNCH, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX, AudioEngine.BASS_ENHANCE_PARAM_PUNCH, c,
        )
    }

    /** 切换模式：0 = 经典、1 = 动态。两套参数各自保留，不互相覆盖。 */
    fun setBassEnhanceMode(mode: Int) {
        val c = mode.coerceIn(
            AudioEngine.BASS_ENHANCE_MODE_CLASSIC,
            AudioEngine.BASS_ENHANCE_MODE_DYNAMIC,
        )
        _bassEnhanceMode.value = c
        prefs.edit().putInt(KEY_BASS_ENHANCE_MODE, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
            AudioEngine.BASS_ENHANCE_PARAM_MODE, c.toFloat(),
        )
    }

    /** 经典模式增益 0~15 dB */
    fun setBassEnhanceClassicGain(db: Float) {
        val c = db.coerceIn(0f, 15f)
        _bassEnhanceClassicGain.value = c
        prefs.edit().putFloat(KEY_BASS_ENHANCE_CLASSIC_GAIN, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
            AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_GAIN, c,
        )
    }

    /** 经典模式低通 Q 0.3~2.0 */
    fun setBassEnhanceClassicQ(q: Float) {
        val c = q.coerceIn(0.3f, 2.0f)
        _bassEnhanceClassicQ.value = c
        prefs.edit().putFloat(KEY_BASS_ENHANCE_CLASSIC_Q, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
            AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_Q, c,
        )
    }

    /** 经典模式低通中心频率 30~120Hz */
    fun setBassEnhanceClassicCenter(hz: Float) {
        val c = hz.coerceIn(30f, 120f)
        _bassEnhanceClassicCenter.value = c
        prefs.edit().putFloat(KEY_BASS_ENHANCE_CLASSIC_CENTER, c).apply()
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
            AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_CENTER, c,
        )
    }

    // ---- 链序扩展：输入 Gain / Balance / 输出 Gain ----
    /** 输入增益（0~2，1=原样）：效果驱动电平（改变压缩器触发强度，非音量） */
    private val _inputGain = MutableStateFlow(prefs.getFloat(KEY_INPUT_GAIN, 1f))
    val inputGain: StateFlow<Float> = _inputGain.asStateFlow()

    /** 声道偏移（-1=全左，0=居中，+1=全右）：源素材声道失衡校正 */
    private val _balance = MutableStateFlow(prefs.getFloat(KEY_BALANCE, 0f))
    val balance: StateFlow<Float> = _balance.asStateFlow()

    /** 输出增益（0~2，1=原样）：响度补偿（Limiter 之前，超限被限幅器兜住） */
    private val _outputGain = MutableStateFlow(prefs.getFloat(KEY_OUTPUT_GAIN, 1f))
    val outputGain: StateFlow<Float> = _outputGain.asStateFlow()

    fun setInputGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 2f)
        _inputGain.value = clamped
        prefs.edit().putFloat(KEY_INPUT_GAIN, clamped).apply()
        engine.setBypass(AudioEngine.INPUT_GAIN_NODE_INDEX, false)
        engine.setParam(AudioEngine.INPUT_GAIN_NODE_INDEX, AudioEngine.GAIN_PARAM_GAIN, clamped)
    }

    fun setBalance(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        _balance.value = clamped
        prefs.edit().putFloat(KEY_BALANCE, clamped).apply()
        engine.setBypass(AudioEngine.BALANCE_NODE_INDEX, false)
        engine.setParam(AudioEngine.BALANCE_NODE_INDEX, AudioEngine.BALANCE_PARAM_BALANCE, clamped)
    }

    fun setOutputGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 2f)
        _outputGain.value = clamped
        prefs.edit().putFloat(KEY_OUTPUT_GAIN, clamped).apply()
        engine.setBypass(AudioEngine.OUTPUT_GAIN_NODE_INDEX, false)
        engine.setParam(AudioEngine.OUTPUT_GAIN_NODE_INDEX, AudioEngine.GAIN_PARAM_GAIN, clamped)
    }

    // ---- 效果预设：一组 (paramId, value) 命名集合，一键切换 ----
    /**
     * 预设 = 14 项效果参数的快照（key 与 prefs KEY 对齐；Bool 存 1/0，reverb_preset
     * 存 float）。应用 = 逐参调用现有 setter（自动引擎下发 + 持久化 + UI 状态同步）。
     */
    data class EffectPreset(
        val name: String,
        val builtin: Boolean,
        val params: Map<String, Float>,
    )

    /** 内置预设：覆盖典型场景（limiter 各预设都保持开启——安全网不随预设关） */
    val builtinPresets = listOf(
        EffectPreset(
            "原声直通", true, mapOf(
                "eq_enabled" to 0f, "bass_gain" to 0f, "treble_gain" to 0f,
                "compressor_enabled" to 0f, "compressor_threshold" to -20f, "compressor_ratio" to 4f,
                "limiter_enabled" to 1f, "limiter_ceiling" to -1f,
                "reverb_enabled" to 0f, "reverb_preset" to 1f,
                "gate_threshold" to 0f, "stereo_width_enabled" to 0f, "stereo_width" to 1f,
            ),
        ),
        EffectPreset(
            "人声清晰", true, mapOf(
                "eq_enabled" to 1f, "bass_gain" to 0f, "treble_gain" to 4f,
                "compressor_enabled" to 1f, "compressor_threshold" to -24f, "compressor_ratio" to 3f,
                "limiter_enabled" to 1f, "limiter_ceiling" to -1f,
                "reverb_enabled" to 0f, "gate_threshold" to 0f,
                "stereo_width_enabled" to 0f, "stereo_width" to 1f,
            ),
        ),
        EffectPreset(
            "氛围空间", true, mapOf(
                "eq_enabled" to 1f, "bass_gain" to 2f, "treble_gain" to 2f,
                "compressor_enabled" to 1f, "compressor_threshold" to -20f, "compressor_ratio" to 4f,
                "limiter_enabled" to 1f, "limiter_ceiling" to -1f,
                "reverb_enabled" to 1f, "reverb_preset" to 3f,
                "gate_threshold" to 0f,
                "stereo_width_enabled" to 1f, "stereo_width" to 1.4f,
            ),
        ),
        EffectPreset(
            "低音震撼", true, mapOf(
                "eq_enabled" to 1f, "bass_gain" to 8f, "treble_gain" to -2f,
                "compressor_enabled" to 1f, "compressor_threshold" to -20f, "compressor_ratio" to 4f,
                "limiter_enabled" to 1f, "limiter_ceiling" to -1f,
                "reverb_enabled" to 0f,
                "gate_threshold" to 0f,
                "stereo_width_enabled" to 0f, "stereo_width" to 1f,
            ),
        ),
    )

    private fun deserializeUserPresets(json: String): List<EffectPreset> = runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val paramsObj = obj.getJSONObject("params")
            val params = mutableMapOf<String, Float>()
            val keys = paramsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                params[k] = paramsObj.getDouble(k).toFloat()
            }
            EffectPreset(obj.getString("name"), false, params)
        }
    }.getOrDefault(emptyList())

    private fun serializeUserPresets(list: List<EffectPreset>): String = runCatching {
        val arr = org.json.JSONArray()
        for (p in list) {
            val paramsObj = org.json.JSONObject()
            for ((k, v) in p.params) paramsObj.put(k, v.toDouble())
            arr.put(org.json.JSONObject().put("name", p.name).put("params", paramsObj))
        }
        arr.toString()
    }.getOrDefault("[]")

    private val _userPresets = MutableStateFlow(deserializeUserPresets(prefs.getString(KEY_USER_PRESETS_JSON, "[]") ?: "[]"))
    val userPresets: StateFlow<List<EffectPreset>> = _userPresets.asStateFlow()

    private fun persistUserPresets(list: List<EffectPreset>) {
        prefs.edit().putString(KEY_USER_PRESETS_JSON, serializeUserPresets(list)).apply()
    }

    /** 单参数分发到对应 setter（预设应用与快照的统一入口） */
    private fun applyParam(key: String, v: Float) {
        if (key.startsWith(KEY_MULTIBAND_PARAM_PREFIX)) {
            key.removePrefix(KEY_MULTIBAND_PARAM_PREFIX).toIntOrNull()?.let { setMultibandParam(it, v) }
            return
        }
        when (key) {
            "input_gain" -> setInputGain(v)
            "balance" -> setBalance(v)
            "eq_enabled" -> setEqEnabled(v > 0.5f)
            "eq_section_enabled" -> setEqSectionEnabled(v > 0.5f)
            "auto_eq_enabled" -> setAutoEqEnabled(v > 0.5f)
            "script_effect_enabled" -> setScriptEffectEnabled(v > 0.5f)
            "bass_gain" -> setBassGain(v)
            "treble_gain" -> setTrebleGain(v)
            "compressor_enabled" -> setCompressorEnabled(v > 0.5f)
            "compressor_threshold" -> setCompressorThreshold(v)
            "compressor_ratio" -> setCompressorRatio(v)
            "multiband_enabled" -> setMultibandEnabled(v > 0.5f)
            "limiter_enabled" -> setLimiterEnabled(v > 0.5f)
            "limiter_ceiling" -> setLimiterCeiling(v)
            "reverb_enabled" -> setReverbEnabled(v > 0.5f)
            "reverb_preset" -> setReverbPreset(v.toInt())
            "user_wet_gain" -> setUserWetGain(v)
            "reverb_dry" -> setReverbDry(v)
            "reverb_lowcut" -> setReverbLowCut(v)
            "gate_threshold" -> setGateThreshold(v)
            "stereo_width_enabled" -> setStereoWidthEnabled(v > 0.5f)
            "stereo_width" -> setStereoWidth(v)
            "vs_enabled" -> setVsEnabled(v > 0.5f)
            "vs_mode" -> setVsMode(v.toInt())
            "vs_encode_strength" -> setVsEncodeStrength(v)
            "vs_room_size" -> setVsRoomSize(v.toInt())
            "vs_rear_space" -> setVsRearSpace(v)
            "vs_room_wet" -> setVsRoomWet(v)
            "vs_center_mode" -> setVsCenterMode(v.toInt())
            "vs_dialog" -> setVsDialog(v)
            "vs_bass_enh" -> setVsBassEnh(v)
            "vs_bass_enh_mode" -> setVsBassEnhMode(v.toInt())
            "vs_rear_center" -> setVsRearCenter(v > 0.5f)
            "vs_rear_delay" -> setVsRearDelay(v)
            "output_gain" -> setOutputGain(v)
            "fdn_enabled" -> setFdnEnabled(v > 0.5f)
            "fdn_room_size" -> setFdnRoomSize(v)
            "fdn_humidity" -> setFdnHumidity(v)
            "fdn_stereo_width" -> setFdnStereoWidth(v)
            "fdn_wet" -> setFdnWet(v)
            "fdn_dry" -> setFdnDry(v)
            "fdn_mode" -> setFdnMode(v)
            // ---- 低频管家 / 组总闸（2026-11-14 补：设备切换时整组不跟随）----
            "lowfreq_section_enabled" -> setLowfreqSectionEnabled(v > 0.5f)
            "dynamic_bass_enabled" -> setDynamicBassEnabled(v > 0.5f)
            "dynamic_bass_amount" -> setDynamicBassAmount(v)
            "dynamic_bass_subsonic_hz" -> setDynamicBassSubsonic(v)
            "dynamic_bass_focus_hz" -> setDynamicBassFocus(v)
            "dynamic_bass_boost_depth" -> setDynamicBassBoostDepth(v)
            "dynamic_bass_protect_level" -> setDynamicBassProtect(v)
            "bass_enhance_enabled" -> setBassEnhanceEnabled(v > 0.5f)
            "bass_enhance_amount" -> setBassEnhanceAmount(v)
            "bass_enhance_deep" -> setBassEnhanceDeep(v)
            "bass_enhance_punch" -> setBassEnhancePunch(v)
            "bass_enhance_mode" -> setBassEnhanceMode(v.toInt())
            "bass_enhance_classic_gain" -> setBassEnhanceClassicGain(v)
            "bass_enhance_classic_q" -> setBassEnhanceClassicQ(v)
            "bass_enhance_classic_center" -> setBassEnhanceClassicCenter(v)
            "virtual_bass_enabled" -> setVirtualBassEnabled(v > 0.5f)
            "virtual_bass_amount" -> setVirtualBassAmount(v)
            "virtual_bass_harmonic" -> setVirtualBassHarmonic(v)
            "virtual_bass_mid_gain" -> setVirtualBassMidGain(v)
            "virtual_bass_high_gain" -> setVirtualBassHighGain(v)
            "virtual_bass_envelope_rate" -> setVirtualBassEnvelopeRate(v)
            "compressor_section_enabled" -> setCompressorSectionEnabled(v > 0.5f)
            "reverb_section_enabled" -> setReverbSectionEnabled(v > 0.5f)
            // 逐段曲线：键名带索引，用前缀解析（EQ 与 AutoEq 各一套）
            else -> when {
                key.startsWith("eq_gain_") ->
                    key.removePrefix("eq_gain_").toIntOrNull()?.let { setEqBandGain(it, v) }
                key.startsWith("eq_q_") ->
                    key.removePrefix("eq_q_").toIntOrNull()?.let { setEqBandQ(it, v) }
                key.startsWith("eq_fq_") ->
                    key.removePrefix("eq_fq_").toIntOrNull()?.let { setEqBandFreq(it, v) }
                key.startsWith("eq_ty_") ->
                    key.removePrefix("eq_ty_").toIntOrNull()?.let { setEqBandType(it, v.toInt()) }
                key.startsWith("autoeq_gain_") ->
                    key.removePrefix("autoeq_gain_").toIntOrNull()?.let { setAutoEqBandValue("gain", it, v) }
                key.startsWith("autoeq_q_") ->
                    key.removePrefix("autoeq_q_").toIntOrNull()?.let { setAutoEqBandValue("q", it, v) }
                key.startsWith("autoeq_fq_") ->
                    key.removePrefix("autoeq_fq_").toIntOrNull()?.let { setAutoEqBandValue("freq", it, v) }
                key.startsWith("autoeq_ty_") ->
                    key.removePrefix("autoeq_ty_").toIntOrNull()?.let { setAutoEqBandValue("type", it, v) }
            }
        }
    }

    /** 应用预设：逐参调用 setter（各 setter 自带斜坡/引擎下发/持久化/UI 同步） */
    fun applyPreset(preset: EffectPreset) {
        for ((k, v) in preset.params) applyParam(k, v)
    }

    /** 脚本文本在 prefs 里的键（lane0 无后缀，其余带 _N） */
    private fun scriptSourceKey(lane: Int): String =
        if (lane == 0) "script_effect_source" else "script_effect_source_$lane"

    /**
     * 设备配置的**字符串段**。
     *
     * 设备配置主体是 `Map<String, Float>`，装不下脚本源码；这里把文本以
     * `obj.put(key, String)` 的形式混进同一个 JSON —— 回放时按值类型分流。
     */
    private fun snapshotStringParams(): Map<String, String> = buildMap {
        for (lane in 0..2) {
            val src = prefs.getString(scriptSourceKey(lane), "").orEmpty()
            if (src.isNotBlank()) put("script_src_$lane", src)
        }
    }

    /** 回放字符串键：目前只有机架脚本 */
    private fun applyStringParam(key: String, value: String) {
        if (!key.startsWith("script_src_")) return
        val lane = key.removePrefix("script_src_").toIntOrNull() ?: return
        if (lane !in 0..2) return
        prefs.edit().putString(scriptSourceKey(lane), value).commit()
        engine.setScriptSource(value, lane)
        engine.setScriptLaneEnabled(lane, true)
        if (lane == _scriptLane.value) {
            _scriptSource.value = value
            _scriptLaneOn.value = true
        }
        refreshLaneMeta()
    }

    /** 当前参数快照 */
    private fun snapshotParams(): Map<String, Float> = mapOf(
        "input_gain" to _inputGain.value,
        "balance" to _balance.value,
        "eq_enabled" to if (_eqEnabled.value) 1f else 0f,
        "eq_section_enabled" to if (_eqSectionEnabled.value) 1f else 0f,
        "auto_eq_enabled" to if (_autoEqEnabled.value) 1f else 0f,
        // 机架总闸（脚本文本走字符串表，见 snapshotStringParams）
        "script_effect_enabled" to if (_scriptEffectEnabled.value) 1f else 0f,
        // EQ 与 AutoEq 的逐段曲线：之前只存三段快捷增益，切设备后整条曲线丢失
        *buildList {
            for (i in 0 until _eqMode.value) {
                add("eq_gain_$i" to _eqBandGains.value.getOrElse(i) { 0f })
                add("eq_q_$i" to _eqBandQs.value.getOrElse(i) { 0.707f })
                add("eq_fq_$i" to _eqBandFreqs.value.getOrElse(i) { 1000f })
                add("eq_ty_$i" to _eqBandTypes.value.getOrElse(i) { 0 }.toFloat())
            }
            for (i in 0 until _autoEqMode.value) {
                add("autoeq_gain_$i" to _autoEqGains.value.getOrElse(i) { 0f })
                add("autoeq_q_$i" to _autoEqQs.value.getOrElse(i) { 0.707f })
                add("autoeq_fq_$i" to _autoEqFreqs.value.getOrElse(i) { 1000f })
                add("autoeq_ty_$i" to _autoEqTypes.value.getOrElse(i) { 0 }.toFloat())
            }
        }.toTypedArray(),
        // bass_gain / treble_gain 是 band0 / 末段的"快捷镜像"，且回放时 setBassGain 会再写一次段值。
        // 若存独立变量，用 EQ 面板改过 band0 之后就会与曲线不同步 —— 所以直接由段值派生。
        "bass_gain" to _eqBandGains.value.getOrElse(0) { 0f },
        "treble_gain" to _eqBandGains.value.getOrElse((_eqMode.value - 1).coerceAtLeast(0)) { 0f },
        "compressor_enabled" to if (_compressorEnabled.value) 1f else 0f,
        "compressor_threshold" to _compressorThreshold.value,
        "compressor_ratio" to _compressorRatio.value,
        "multiband_enabled" to if (_multibandEnabled.value) 1f else 0f,
        *List(MULTIBAND_PARAM_COUNT) { pid ->
            KEY_MULTIBAND_PARAM_PREFIX + pid to _multibandParams.value[pid]
        }.toTypedArray(),
        "limiter_enabled" to if (_limiterEnabled.value) 1f else 0f,
        "limiter_ceiling" to _limiterCeiling.value,
        "reverb_enabled" to if (_reverbEnabled.value) 1f else 0f,
        "reverb_preset" to _reverbPreset.value.toFloat(),
        "user_wet_gain" to _userWetGain.value,
        "reverb_dry" to _reverbDry.value,
        "reverb_lowcut" to _reverbLowCut.value,
        "gate_threshold" to _gateThreshold.value,
        "stereo_width_enabled" to if (_stereoWidthEnabled.value) 1f else 0f,
        "stereo_width" to _stereoWidth.value,
        "vs_enabled" to if (_vsEnabled.value) 1f else 0f,
        "vs_mode" to _vsMode.value.toFloat(),
        "vs_encode_strength" to _vsEncodeStrength.value,
        "vs_room_size" to _vsRoomSize.value.toFloat(),
        "vs_rear_space" to _vsRearSpace.value,
        "vs_room_wet" to _vsRoomWet.value,
        "vs_center_mode" to _vsCenterMode.value.toFloat(),
        "vs_dialog" to _vsDialog.value,
        "vs_bass_enh" to _vsBassEnh.value,
        "vs_bass_enh_mode" to _vsBassEnhMode.value.toFloat(),
        "vs_rear_center" to if (_vsRearCenter.value) 1f else 0f,
        "vs_rear_delay" to _vsRearDelay.value,
        "output_gain" to _outputGain.value,
        "fdn_enabled" to if (_fdnEnabled.value) 1f else 0f,
        "fdn_room_size" to _fdnRoomSize.value,
        "fdn_humidity" to _fdnHumidity.value,
        "fdn_stereo_width" to _fdnStereoWidth.value,
        "fdn_wet" to _fdnWet.value,
        "fdn_dry" to _fdnDry.value,
        "fdn_mode" to _fdnMode.value,
        // ---- 低频管家（此前缺失：设备切换时整组不跟随）----
        "lowfreq_section_enabled" to if (_lowfreqSectionEnabled.value) 1f else 0f,
        "dynamic_bass_enabled" to if (_dynamicBassEnabled.value) 1f else 0f,
        "dynamic_bass_amount" to _dynamicBassAmount.value,
        "dynamic_bass_subsonic_hz" to _dynamicBassSubsonic.value,
        "dynamic_bass_focus_hz" to _dynamicBassFocus.value,
        "dynamic_bass_boost_depth" to _dynamicBassBoostDepth.value,
        "dynamic_bass_protect_level" to _dynamicBassProtect.value,
        "bass_enhance_enabled" to if (_bassEnhanceEnabled.value) 1f else 0f,
        "bass_enhance_amount" to _bassEnhanceAmount.value,
        "bass_enhance_deep" to _bassEnhanceDeep.value,
        "bass_enhance_punch" to _bassEnhancePunch.value,
        "bass_enhance_mode" to _bassEnhanceMode.value.toFloat(),
        "bass_enhance_classic_gain" to _bassEnhanceClassicGain.value,
        "bass_enhance_classic_q" to _bassEnhanceClassicQ.value,
        "bass_enhance_classic_center" to _bassEnhanceClassicCenter.value,
        "virtual_bass_enabled" to if (_virtualBassEnabled.value) 1f else 0f,
        "virtual_bass_amount" to _virtualBassAmount.value,
        "virtual_bass_harmonic" to _virtualBassHarmonic.value,
        "virtual_bass_mid_gain" to _virtualBassMidGain.value,
        "virtual_bass_high_gain" to _virtualBassHighGain.value,
        "virtual_bass_envelope_rate" to _virtualBassEnvelopeRate.value,
        // ---- 组总闸（子效果的启用受它门控，必须跟着设备走）----
        "compressor_section_enabled" to if (_compressorSectionEnabled.value) 1f else 0f,
        "reverb_section_enabled" to if (_reverbSectionEnabled.value) 1f else 0f,
    )

    /** 保存当前参数为用户预设（同名覆盖） */
    fun saveUserPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val list = _userPresets.value.filter { it.name != trimmed }.toMutableList()
        list.add(EffectPreset(trimmed, false, snapshotParams()))
        persistUserPresets(list)
        _userPresets.value = list
    }

    /** 删除用户预设（内置预设不可删） */
    fun deleteUserPreset(name: String) {
        val list = _userPresets.value.filter { it.name != name }
        persistUserPresets(list)
        _userPresets.value = list
    }

    // ================= ：按输出设备自动切换音效 =================
    // 双模式语义沿用 WEcho：自适应（每个设备各存一套）/ 聚合（全部共用当前配置）。
    // 聚合模式 = 现状行为，本模块整段旁路，改动面为零。

    /** 隐私政策同意标记（**必须与 MainActivity.KEY_PRIVACY_ACCEPTED 一致**，撤回时改写这里） */
    private val KEY_PRIVACY_ACCEPTED = "privacy_accepted"

    private val keyDeviceAdaptive = "device_adaptive_enabled"
    private val devicePresetPrefix = "device_preset_"

    /** 绑定同时记下**展示名**：键是 sanitize 后的（qcy_ailybuds_lite），UI 直接显示不可读 */
    private val deviceLabelPrefix = "device_label_"

    /** **全局最近配置**：设备首次接入（该设备尚无历史）时的兜底 */
    private val keyLastConfig = "last_config"

    /**
     * **每设备最近配置** `device_last_<key>`。
     *
     * 只有全局一份是不够的：A→B→A 往返时，B 上应用过的配置会覆盖全局记录，
     * 切回 A 时拿到的是 B 的参数，A 调好的东西照样丢。必须按设备各存一份。
     */
    private val deviceLastPrefix = "device_last_"

    /**
     * **类型最近配置** `category_last_speaker|headphone`。
     *
     * 用户语义是「扬声器 / 耳机两类各自记住最后一次调整」，设备级只解决同一台；
     * 换一副新耳机（从没出现过）时应命中「耳机这一类」的最后配置，而不是全局。
     */
    private val categoryLastPrefix = "category_last_"

    /** 上次已落盘的配置 JSON（判等去抖，避免滑条拖动时反复写盘）；null = 尚未初始化 */
    @Volatile
    private var lastSavedConfigJson: String? = null

    /** 等引擎 ready 的上限：路由变化后 AAudio 重连实测 1~6s，8s 兜底防呆 */
    private val deviceReadyTimeoutMs = 8_000L

    /** 自适应开关；false = 聚合模式 */
    private val _deviceAdaptive = MutableStateFlow(prefs.getBoolean(keyDeviceAdaptive, true))
    val deviceAdaptive: StateFlow<Boolean> = _deviceAdaptive.asStateFlow()

    /**
     * 当前输出设备（键 + 展示名，**原子发布**）。
     * 不再拆成两个 StateFlow：Main.immediate 下收集器会在两次赋值之间被唤醒，
     * 读到上一个设备的 label（实测把扬声器展示名写成了耳机名）。
     */
    val currentOutput: StateFlow<OutputDeviceMonitor.CurrentOutput> = outputDeviceMonitor.current

    /**
     * 当前输出是不是**耳机类**（非内置扬声器/听筒）。
     *
     * 用于虚拟低音的"按设备自动排斥"：虚拟低音是为手机外放设计的，
     * 耳机上容易低频浑浊。但**不写死禁止** —— 用户坚持要在耳机上试时，
     * 弹一次风险提示后允许开启（详见 `ScScreens` 里的确认对话框）。
     */
    fun isHeadphoneOutput(): Boolean = !outputDeviceMonitor.isSpeakerOutput()

    /** 已绑定预设的设备键（UI 用） */
    private val _boundDeviceKeys = MutableStateFlow<Set<String>>(emptySet())
    val boundDeviceKeys: StateFlow<Set<String>> = _boundDeviceKeys.asStateFlow()

    /** 设备键 → 展示名（绑定列表用；缺失时回落键名） */
    private val _boundDeviceLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val boundDeviceLabels: StateFlow<Map<String, String>> = _boundDeviceLabels.asStateFlow()

    /** 切换互斥 + pending 重放：蓝牙会连发多个设备名，直接丢弃请求会让状态永久不同步 */
    private val deviceSwitchMutex = Mutex()

    @Volatile
    private var pendingDeviceKey: String? = null

    @Volatile
    private var lastDeviceKey: String? = null

    /** 首帧只记录不应用——避免一开 App 就把用户当前配置换成该设备的绑定预设 */
    private var deviceKeySeen = false

    fun setDeviceAdaptive(enabled: Boolean) {
        _deviceAdaptive.value = enabled
        prefs.edit().putBoolean(keyDeviceAdaptive, enabled).apply()
        writeDiag("设备自适应模式: ${if (enabled) "开启（每设备一套）" else "关闭（聚合，共用当前配置）"}")
    }

    /** 把当前配置绑定到指定输出设备（默认当前设备） */
    fun bindCurrentToDevice(key: String = outputDeviceMonitor.current.value.key) {
        bindTo(key, outputDeviceMonitor.current.value.label)
    }

    /** 绑定到当前设备所属**类别**（所有耳机 / 所有扬声器）——新设备首次接入即生效，免去逐个绑 */
    fun bindCurrentDeviceCategory() {
        val cat = deviceCategoryOf(outputDeviceMonitor.current.value.key)
        bindTo("cat_$cat", if (cat == "speaker") "所有扬声器" else "所有耳机")
    }

    private fun bindTo(key: String, label: String) {
        val snap = snapshotParams()
        val obj = org.json.JSONObject()
        snap.forEach { (k, v) -> obj.put(k, v.toDouble()) }
        prefs.edit()
            .putString(devicePresetPrefix + key, obj.toString())
            .putString(deviceLabelPrefix + key, label)
            .apply()
        refreshBoundDeviceKeys()
        writeDiag("设备绑定: $key ← 当前配置（${snap.size} 项，显示名=$label）")
    }

    fun clearDeviceBinding(key: String) {
        prefs.edit()
            .remove(devicePresetPrefix + key)
            .remove(deviceLabelPrefix + key)
            .apply()
        refreshBoundDeviceKeys()
        writeDiag("设备绑定解除: $key")
    }

    private fun refreshBoundDeviceKeys() {
        val keys = prefs.all.keys
            .filter { it.startsWith(devicePresetPrefix) }
            .map { it.removePrefix(devicePresetPrefix) }
            .toSet()
        _boundDeviceKeys.value = keys
        _boundDeviceLabels.value = keys.associateWith { k ->
            prefs.getString(deviceLabelPrefix + k, null)?.takeIf { it.isNotBlank() }
                // speaker 是内置扬声器的固定键，展示名恒为「扬声器」；其余回落键名
                ?: if (k == "speaker") "扬声器" else k
        }
    }

    /**
     * 耳机输出时虚拟低音**默认关闭**（只关闭、不禁止；用户手动开仍可，UI 每次弹风险提示）。
     *
     * **调用时机是硬约束**：必须在「保存上一个设备的配置」**之后**调用，
     * 否则关闭动作会把上一台设备的快照改成 false —— 切回去时用户看到状态丢失
     * （2026-11-14 实测：`device_last_speaker.virtual_bass_enabled` 被写成 0）。
     */
    private fun enforceVirtualBassOnHeadphone(key: String) {
        if (outputDeviceMonitor.isSpeakerOutput()) return
        if (!_virtualBassEnabled.value) return
        setVirtualBassEnabled(false)
        writeDiag("设备=$key 为耳机类：虚拟低音自动关闭（耳机下默认关）")
    }

    /**
     * 设备变化处理（M14b）。
     *
     * 顺序不可颠倒：判等 → 自适应开关 → 抢锁 → 等引擎 ready → 下发。
     * **等 ready 是硬要求**：路由变化会 detach AAudio 流并触发重连，窗口内下发的参数
     * 会被重启吞掉，表现为「切了设备但音效没生效」（WEcho 是同进程同步下发，无此窗口）。
     */
    /**
     * 回填展示名：历史绑定（本功能上线前绑的）只有键没有名字，设备一旦被使用就补记，
     * 免去用户为了「显示可读名字」重新绑定一遍。仅在缺失时写盘，无持续开销。
     */
    private fun backfillDeviceLabel(key: String, label: String) {
        if (!prefs.contains(devicePresetPrefix + key)) return
        val existing = prefs.getString(deviceLabelPrefix + key, null)
        // 内置扬声器展示名恒定；其余用本次**同一次发布**带来的 label（不再回读 flow，
        // 否则可能读到上一个设备的名字——2026-11-08 09:30:19 的竞态）。
        // 与 existing 不同即写：既做首次回填，也能纠正被写坏的历史值。
        val want = if (key == "speaker") "扬声器" else label
        if (want.isBlank() || want == key) return
        if (existing == want) return
        prefs.edit().putString(deviceLabelPrefix + key, want).apply()
        refreshBoundDeviceKeys()
        writeDiag("设备展示名回填: $key → $want（原=${existing ?: "空"}）")
    }

    private suspend fun onOutputDeviceChanged(key: String, label: String) {
        backfillDeviceLabel(key, label)
        // 耳机输出时虚拟低音**默认关闭**（2026-11-11 用户要求）。
        //
        // 放在首帧判断之前：启动时就插着耳机也要落这条规则 ——
        // 否则上次在外放上开着、这次插着耳机启动，会带着一个明显不适合
        // 耳机的效果器开工。
        // **只关闭、不禁止**：用户手动开仍然可以（UI 每次都会弹风险提示）。
        if (!deviceKeySeen) {
            deviceKeySeen = true
            lastDeviceKey = key
            // 首帧没有「上一个设备」可保存，规则直接落
            enforceVirtualBassOnHeadphone(key)
            writeDiag("设备切换: 首帧 $key（只记录，不应用预设）")
            return
        }
        if (key == lastDeviceKey) return
        val from = lastDeviceKey
        writeDiag("设备切换: ${from ?: "-"} → $key")
        lastDeviceKey = key
        // 切换前把「旧设备上的实时配置」按设备落盘：切回来时能拿回它，
        // 而不是被中间设备应用过的参数覆盖
        saveLiveConfig(from, force = true)
        if (!_deviceAdaptive.value) {
            writeDiag("设备切换: 聚合模式，保持当前配置")
            // 此时旧设备配置已保存，规则不会再污染它
            enforceVirtualBassOnHeadphone(key)
            // 聚合模式只是「不换参数」，与「路由是否需要善后」无关：设备切换本身照样让
            // APM 重算路由、把已重路由的 track 打回物理设备。漏掉这步会让 loopbackRerouted
            // 残留 true（该状态为 true 时整条链路不挂静音）→ 原声直出叠加 DSP = 双声。
            // 补：此前在这里直接 return，等于聚合模式完全没有重路由保护。
            recoverLoopRoutingAfterDeviceChange()
            return
        }
        if (!deviceSwitchMutex.tryLock()) {
            pendingDeviceKey = key
            writeDiag("设备切换: $key 正在切换中，记入 pending")
            return
        }
        try {
            applyDevicePreset(key)
            var next = pendingDeviceKey
            while (next != null) {
                pendingDeviceKey = null
                lastDeviceKey = next
                writeDiag("设备切换: 重放 pending → $next")
                applyDevicePreset(next)
                next = pendingDeviceKey
            }
        } finally {
            deviceSwitchMutex.unlock()
        }
        // 先应用新设备的配置，再落「耳机默认关」规则：
        // 顺序颠倒会让规则的结果被新设备预设覆盖（或反过来污染旧设备快照）
        enforceVirtualBassOnHeadphone(key)
        // 与是否绑定预设无关：设备变化本身就会威胁 Loop 重路由
        recoverLoopRoutingAfterDeviceChange()
    }

    /** 设备类别：内置扬声器 vs 其余（有线/USB/蓝牙统一归"耳机"侧） */
    private fun deviceCategoryOf(key: String): String =
        if (key == "speaker") "speaker" else "headphone"

    /**
     * 把当前实时配置存成「最近一次配置」（`last_config`）。
     *
     * 为什么需要：用户可能压根没绑定预设，但换设备时不该丢参数。判等去抖避免滑条拖动时
     * 反复写盘；`force=true` 用于「即将切换设备」这类必须落盘的时刻。
     */
    private fun saveLiveConfig(deviceKey: String?, force: Boolean = false) {
        val snap = snapshotParams()
        val obj = org.json.JSONObject()
        snap.forEach { (k, v) -> obj.put(k, v.toDouble()) }
        // 字符串段（机架脚本文本）：同一个 JSON 里按值类型区分
        val strSnap = snapshotStringParams()
        strSnap.forEach { (k, v) -> obj.put(k, v) }
        val json = obj.toString()
        if (!force && json == lastSavedConfigJson) return
        lastSavedConfigJson = json
        val editor = prefs.edit().putString(keyLastConfig, json)
        if (deviceKey != null) {
            // 同时记三份：该设备 / 该设备所属类型 / 全局
            //  设备级 → 同一台来回切能拿回自己的
            //  类型级 → 换一副没见过的耳机，也能沿用「耳机这一类」的最后配置
            //  全局   → 极端兜底（该类型也还没有记录）
            editor.putString(deviceLastPrefix + deviceKey, json)
            editor.putString(categoryLastPrefix + deviceCategoryOf(deviceKey), json)
        }
        editor.apply()
        writeDiag(
            "最近配置已保存（${snap.size} 项 + ${strSnap.size} 段文本，device=${deviceKey ?: "-"}，" +
                "type=${deviceKey?.let { deviceCategoryOf(it) } ?: "-"}，force=$force）"
        )
    }

    /**
     * 解析该设备该用哪套参数。
     *
     * 定案：用户要求「**永远下发该设备最后一次调整的参数**」，故「最近配置」
     * 升为最高优先；绑定降级为「首次默认」（仅在该设备与该类型都还没有记录时生效），
     * 且绑定 UI 已隐藏，功能保留待后续反馈再放开。
     *
     * 优先级：
     *   ① 该设备最近配置 `device_last_<key>`
     *   ② 该类型最近配置 `category_last_speaker|headphone`
     *   ③ 精确绑定 `device_preset_<key>`（首次默认，UI 暂隐藏）
     *   ④ 类别绑定 `device_preset_cat_<cat>`（首次默认，UI 暂隐藏）
     *   ⑤ 全局最近配置 `last_config`
     *   ⑥ 都没有 → null，调用方保持现状（真·首次）
     */
    private fun resolvePresetJson(key: String): String? {
        val cat = deviceCategoryOf(key)
        val cn = if (cat == "speaker") "扬声器" else "耳机"
        // ① 该设备最近一次配置（往返切换拿回自己的，多设备互不干扰）
        prefs.getString(deviceLastPrefix + key, null)?.let {
            writeDiag("设备切换: $key 沿用该设备最近一次配置")
            return it
        }
        // ② 该类型最近一次配置（该设备从没记录过）
        prefs.getString(categoryLastPrefix + cat, null)?.let {
            writeDiag("设备切换: $key 首次接入 → 沿用「$cn」类型最近一次配置")
            return it
        }
        // ③④ 绑定降级为「首次默认」：仅在该设备与该类型都无记录时生效（UI 已隐藏）
        prefs.getString(devicePresetPrefix + key, null)?.let {
            writeDiag("设备切换: $key 命中精确绑定（作首次默认）")
            return it
        }
        prefs.getString(devicePresetPrefix + "cat_" + cat, null)?.let {
            writeDiag("设备切换: $key 命中类别绑定 cat_$cat（作首次默认）")
            return it
        }
        // ⑤ 全局最近配置（极端兜底：该类型也还没有记录）
        return prefs.getString(keyLastConfig, null)?.also {
            writeDiag("设备切换: $key 首次接入 → 沿用全局最近配置")
        }
    }

    private suspend fun waitEngineUsable(): Boolean = withTimeoutOrNull(deviceReadyTimeoutMs) {
        while (true) {
            val st = engineState.value
            if (st is EngineState.Running || st is EngineState.Preparing) return@withTimeoutOrNull true
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: false

    private fun engineUsableNow(): Boolean {
        val st = engineState.value
        return st is EngineState.Running || st is EngineState.Preparing
    }

    private suspend fun applyDevicePreset(key: String) {
        val json = resolvePresetJson(key)
        if (json == null) {
            writeDiag("设备切换: $key 未绑定预设，保持现状")
            return
        }
        val numeric = LinkedHashMap<String, Float>()
        val strings = LinkedHashMap<String, String>()
        runCatching {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().forEach { k ->
                val v = obj.get(k)
                if (v is String) strings[k] = v else numeric[k] = obj.getDouble(k).toFloat()
            }
        }.getOrNull()
        val params = numeric
        if (params.isEmpty() && strings.isEmpty()) {
            writeDiag("设备切换: $key 预设解析失败，保持现状")
            return
        }
        // 目标参数与当前完全一致（典型场景：未绑定设备回落到「最近配置」，而最近配置就是
        // 切换前刚存的当前状态）→ 不必整轮下发，避免白跑 47 次 setter
        val current = snapshotParams()
        val curStrings = snapshotStringParams()
        if (strings.isEmpty() &&
            params.size == current.size && params.all { (k, v) ->
                val cur = current[k]
                cur != null && kotlin.math.abs(cur - v) < 1e-6f
            } && curStrings.isEmpty()
        ) {
            writeDiag("设备切换: $key 目标配置与当前一致，跳过下发")
            return
        }
        // 最多两次：路由变化会 detach AAudio 流并重连，窗口内下发会被重启吞掉。
        // 第一次下发后若引擎又掉出可用态，说明正好撞上重连，重试一次（覆盖「等 ready」的漏窗）。
        var applied = false
        for (attempt in 1..2) {
            if (!waitEngineUsable()) {
                writeDiag("设备切换: $key 等引擎 ready 超时（${engineState.value}），放弃本次应用")
                return
            }
            // 先回放字符串（脚本文本），再下发数值 —— 脚本源要先于它的控件值就位
            strings.forEach { (k, v) -> applyStringParam(k, v) }
            // 组总闸先下发：子效果的 setter 受总闸门控，顺序颠倒会丢参数
            val ordered = params.entries.sortedBy { if (it.key.contains("section")) 0 else 1 }
            for ((k, v) in ordered) applyParam(k, v)
            if (engineUsableNow()) {
                writeDiag("设备切换: 已应用 $key 的预设（${params.size} 项，第 $attempt 次）")
                applied = true
                break
            }
            writeDiag("设备切换: $key 下发后引擎离开可用态（${engineState.value}），重试一次")
        }
        if (!applied) writeDiag("设备切换: $key 两次下发均未稳定，放弃")
    }

    /**
     * Loop 模式下的设备切换善后（方案 ）——**与是否绑定了预设无关，必须每次都跑**。
     *
     * 设备切换会让 APM 重算路由，已重路由的播放器 track 可能被打回物理设备；
     * `loopbackRerouted` 残留 true 时整条链路不挂静音 → 原声直出叠加 DSP = 双声。
     * 先复位，再用 nudgeRouting 促成一次路由重算（只切 isSpeakerphoneOn、不切 mode，
     * 符合 ADR-03，不会暂停用户音乐），1.2s 后复检一次；后续由轮询持续确认。
     * **不做 keyevent 停播重建**：那会暂停用户正在听的歌，代价高于收益。
     */
    private suspend fun recoverLoopRoutingAfterDeviceChange() {
        if (!isLoopbackMode()) return
        takeover.loopbackRerouted = false
        writeDiag("设备切换: Loop 模式已复位 loopbackRerouted（待复检重路由）")
        runCatching { loopbackProbe?.nudgeRouting() }
            .onSuccess { writeDiag("设备切换: nudgeRouting → $it") }
            .onFailure { writeDiag("设备切换: nudgeRouting 异常 ${it.javaClass.simpleName}") }
        delay(1_200)
        writeDiag("设备切换: Loop 复检 rerouted=${takeover.loopbackRerouted}（轮询会持续确认）")
    }

    init {
        // 引擎启动晚于 VM（Hilt 单例）时，把持久化的门限补给引擎
        if (_gateThreshold.value > 0f) engine.setNoiseGateThreshold(_gateThreshold.value)
    }

    val engineState: StateFlow<EngineState> = engine.state
    val stats = engine.stats

    /** 卷积混响预设显示名（native 下发；索引 = REVERB_PRESET_*，空表时 UI 按序号降级） */
    val reverbPresetNames: Array<String> = engine.convolverPresetNames() ?: emptyArray()

    val privilegeSummary: StateFlow<String> = privileged.state.map { state ->
        when {
            !state.managerInstalled -> "特权框架未安装（Stellar Manager）"
            !state.serviceRunning -> "特权服务未启动"
            !state.permissionGranted -> "特权服务已启动，等待授权（点下方按钮）"
            state.uid == 0 -> "特权服务就绪（Root）"
            state.uid == 2000 -> "特权服务就绪（ADB/Shell）"
            else -> "特权服务就绪（uid=${state.uid}）"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "检测中…")

    val isRunning: Boolean
        get() = engineState.value is EngineState.Running ||
            engineState.value is EngineState.Suspended

    fun canRecord(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 多频段每段计量快照（2026-11-08，供 UI 的 IN/GR/OUT 电平表）。
     * `v` 长度 9：[band*3 + 0/1/2] = 入峰值(线性) / GR(dB) / 出峰值(线性)，band 0=低 1=中 2=高。
     * 用 List 而非 FloatArray 是为了让 StateFlow 的值比较按**内容**判定（数组是引用比较，
     * 每次都算“变了”会引发无意义重组）。
     */
    data class MultibandMeters(val v: List<Float> = List(9) { 0f }) {
        fun inPk(band: Int): Float = v.getOrElse(band * 3) { 0f }
        fun grDb(band: Int): Float = v.getOrElse(band * 3 + 1) { 0f }
        fun outPk(band: Int): Float = v.getOrElse(band * 3 + 2) { 0f }
    }

    private val _multibandMeters = MutableStateFlow(MultibandMeters())
    val multibandMeters: StateFlow<MultibandMeters> = _multibandMeters.asStateFlow()

    /**
     * 刷新一次每段计量。由多段压限页的 LaunchedEffect 按 ~10fps 调用——放在页面作用域里
     * 轮询，离开页面自动停止，不需要在 VM 里管理 Job 生命周期。
     */
    fun refreshMultibandMeters() {
        engine.multibandMeters()?.let { arr ->
            _multibandMeters.value = MultibandMeters(arr.toList())
        }
    }

    /**
     * 特权状态（结构化，2026-11-08）：设备卡「Stellar」胶囊据此显示三态
     * —— 就绪 / 待授权（黄）/ 未安装（红呼吸，点击跳下载）。
     */
    val privilegeState: StateFlow<com.jiangjie.Sonicraft.privilege.PrivilegeState> = privileged.state

    /** onResume 重检：用户从 Stellar 授权页切回来时 binder 未重连，必须主动刷新 */
    fun refreshPrivilege() {
        runCatching { privileged.refresh() }
    }

    /** 向 Stellar 请求本 App 的 stellar 权限（用户在 Stellar 侧确认一次） */
    fun requestStellarPermission() {
        privileged.requestPermission()
    }

    /** 启动内录接管：先尝试 Stellar 免弹窗授权 PROJECT_MEDIA，再准备并启动引擎 */
    /** 系统 consent 弹窗返回的 token 入口：后台启动（主线程不可阻塞，见 tryStartCaptureSilently 注释） */
    fun startCapture(projection: MediaProjection?) {
        if (isRunning || engineState.value is EngineState.Preparing) return
        val token = projection
            ?: tryCreateSilentProjection()
            ?: run {
                writeDiag("无投影 token：免弹窗不可用且无系统弹窗 token")
                context.stopService(Intent(context, ForegroundDspService::class.java))
                return
            }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { privileged.grantMediaProjectionSilently() }
        }
        startCaptureInBackground(token)
    }

    /**
     * ：先尝试 Stellar 免弹窗投影（快速判定，失败立即返回 false 交调用方走系统 consent）。
     * 注意：重活（引擎启动等）全部在后台线程——主线程阻塞会拖住前台服务的 startForeground，
     * 超过 5 秒窗口即 ForegroundServiceDidNotStartInTimeException 闪退（实测踩坑）。
     */
    fun tryStartCaptureSilently(): Boolean {
        if (isRunning || engineState.value is EngineState.Preparing) return true
        if (!privileged.state.value.permissionGranted) return false
        val token = tryCreateSilentProjection() ?: return false
        startCaptureInBackground(token)
        return true
    }

    /**
     * 快速创建免弹窗投影（单次尝试）。Android 15 上 app 进程内反射已被 hidden API
     * 封死（Stub.declaredMethods 仅剩 2 个可见方法，事后豁免无效——首次访问即进 blocked），
     * 因此此路在当前系统上基本恒失败；保留仅供有 root / 策略变化时的快速通路。
     */
    private fun tryCreateSilentProjection(): MediaProjection? =
        runCatching { privileged.grantMediaProjectionSilently() }
            .let {
                runCatching {
                    privileged.createMediaProjectionSilently { detail ->
                        writeDiag("免弹窗失败详情: $detail")
                    }
                }.getOrNull()
            }

    /** 后台执行「授权 → prepare → startCapture → 接管启动」，全程不占主线程 */
    private fun startCaptureInBackground(token: MediaProjection) {
        viewModelScope.launch(Dispatchers.Default) {
            lifecycleMutex.withLock {
                // 双实例竞态防（2026-11-03 三会话泄漏事故）：免弹窗 autoStart 与弹窗
                // launchProjection 两个入口的 isRunning 检查都在 mutex 外，Idle 窗口内
                // 可能双双通过——此处 mutex 内复查。否则第二块把 engine 重新 prepare 后
                // startCapture 再放行，旧 CaptureRelaySource 引用被覆盖泄漏 = 双 read
                // loop 交错心跳、引擎绑僵尸实例（14:05:47 实锤 3 个 START）。
                if (isRunning || engineState.value is EngineState.Preparing) {
                    writeDiag("启动去重：已在运行/准备中，跳过重复启动")
                    return@withLock
                }
                runCatching {
                // 接管轮询先行：capture 启动前就把「检测播放器→appops 静音」跑起来，
                // 否则引擎副本出声到轮询首次静音之间（最多一个轮询周期）原声与
                // 引擎输出并存 = 双声感（夜间恢复实测）。失败由 stopAndRollback 兜底。
                takeover.start(_autoTakeoverEnabled.value)
                engine.prepare(EngineMode.CaptureRelay, AudioFormat.STEREO_48K)
                engine.startCapture(token, excludedUids())
                if (engineState.value != EngineState.Running) {
                    throw IllegalStateException("内录引擎未进入 Running（${engineState.value}）")
                }
                reapplyTakeover()
                applyEqSettings()
                // MP 路径不做重路由校验：MP 下 player 不会进 submix，原声靠 appops
                // PLAY_AUDIO=ignore 压掉，而 MP 捕获不受 appops 影响（ADR-02）。
                // 夜间保活：会话运行标记（进程被杀后 STICKY 重生/开机自启据此判「被终止」）
                // 并清除被终止横幅（恢复成功）
                prefs.edit()
                    .putBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, true)
                    .putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, false)
                    .apply()
                _systemKilledUnacked.value = false
                KeepAliveJobService.clearWarningNotification(context)   // 恢复成功：撤警示通知
                // 夜间保活兜底：持久化周期 job（跨软重启存活，STICKY/BOOT 失效场景重挂警示）
                KeepAliveJobService.schedule(context)
                }.onFailure { error ->
                    writeDiag("启动失败: ${error.javaClass.simpleName} ${error.message}")
                    SonicraftLog.e(TAG, "内录启动失败", error)
                    // 静音先行（本块开头的 takeover.start）已可能挂上静音，启动失败
                    // 必须回滚，否则酷狗等原声被永久静音（2026-11-03 僵尸投影事故伴生坑）
                    runCatching { takeover.stopAndRollback() }
                    runCatching { engine.stop() }
                    context.stopService(Intent(context, ForegroundDspService::class.java))
                }
            }
        }
    }

    /**
     * MP 路径的重路由校验。
     *
     * 规律与 Loopback 相同：mix 的 Remote Submix Out 端口就绪**之前**创建的 AudioTrack
     * 不会被重路由，原声留在扬声器 → 与 DSP 输出叠加成双声。
     * 校验方式：把「所有 Remote Submix Out 端口」与「活跃 MEDIA player 的 deviceIds」
     * 比对；未命中则用 shell 发 pause/play 逼 track 重建（app 侧无 MEDIA_CONTENT_CONTROL）。
     */
    private suspend fun ensureReroutedMp(): String = withContext(Dispatchers.IO) {
        suspend fun sh(vararg c: String): String = runCatching {
            privileged.runCommand(*c).stdout
        }.getOrDefault("")

        val ports = Regex("Port ID: (\\d+); \"Remote Submix Out\"")
            .findAll(sh("dumpsys", "media.audio_policy"))
            .map { it.groupValues[1] }
            .toSet()
        if (ports.isEmpty()) return@withContext "fail 无 Remote Submix Out 端口（mix 未就绪）"

        suspend fun stillNotRerouted(): Boolean {
            val self = android.os.Process.myUid()
            val players = sh("dumpsys", "audio").lineSequence()
                .filter { it.contains("state:started") && it.contains("usage=USAGE_MEDIA") }
                .filterNot { it.contains("u/pid:$self/") }
                .toList()
            if (players.isEmpty()) return false
            return players.any { l ->
                val ids = Regex("""deviceIds:\[([^\]]*)\]""").find(l)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim() } ?: emptyList()
                ids.none { it in ports }
            }
        }

        if (!stillNotRerouted()) return@withContext "ok 已重路由 ports=$ports"
        for (i in 1..2) {
            sh("input", "keyevent", "127")
            delay(600)
            sh("input", "keyevent", "126")
            delay(1_200)
            if (!stillNotRerouted()) return@withContext "ok 第 $i 次重建后已重路由"
        }
        "fail 重建后仍未重路由（保持双声，不静音）"
    }

    private fun writeDiag(msg: String) {
        runCatching {
            val f = java.io.File(DiagDirs.crashDir, "autostart_diag.txt")
            f.parentFile?.mkdirs()
            f.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }
    }

    /**
     * （2026-11-03 审计）：停止回滚专用进程级 scope。原寄宿 viewModelScope——用户
     * 点停止后立刻退出 app，onCleared 取消 scope，回滚若尚未越过挂起点即被取消 =
     * appops ignore 永久残留（ mipop 类事故的同族）。本 scope 无父，回滚动作活过
     * UI 销毁，进程死亡才终止。
     */
    private val stopRollbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun stopCapture() {
        // P3-2 生命周期互斥：与后台启动互斥，等待其完成后再统一回滚（防半初始化态）。
        // stop 异步化：完成信号走 EngineState.Stopped 流（UI 反应不受影响）
        // 诊断埋点：停止路径原先一条日志都没有，事后无法从 diag 判断
        // 「用户点了停止」这个动作，也就无法区分「复用误判」与「启停竞态」。
        writeDiag(
            "停止请求: engineState=${engineState.value}, chain.isAlive=${loopbackChain.isAlive}, " +
                "loopback=${isLoopbackMode()}"
        )
        stopRollbackScope.launch(Dispatchers.Default) {
            lifecycleMutex.withLock {
        // 安全铁律：停内录必须回滚所有接管静音，否则原声永久无声（AppOps 持久化）
        takeover.stopAndRollback()
        rollbackTakeover()
        engine.stop(StopReason.USER_REQUESTED)
        // ★ 2026-11-08 修「保活期间停止后再点电源键启动不了音效」主因：
        // ① probe.stop() 撤掉 shell 侧 AudioPolicy/mix——否则播放器仍被重路由进一个
        //    没人读的 submix（原声被搬走），比"没音效"更糟；
        // ② detach() 把链路从进程级持有者摘掉——否则 LoopbackChainHolder.isAlive 恒为
        //    true，下次 startLoopbackCapture() 走「复用既有链路」分支直接 return，
        //    引擎永远不会 prepare/start（现场：电源键点了没音效、也没有任何报错）；
        // ③ loopbackRerouted 必须复位——它残留 true 时整条链路不挂静音（双声隐患）。
        runCatching { loopbackProbe?.stop() }
            .onFailure { writeDiag("停止链路失败: ${it.javaClass.simpleName}") }
        loopbackChain.detach()
        takeover.loopbackRerouted = false
        context.stopService(Intent(context, ForegroundDspService::class.java))
        // 夜间保活：用户主动停止，清除会话标记与被终止横幅（避免下次开机/重生误报）
        prefs.edit()
            .putBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, false)
            .putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, false)
            .apply()
        _systemKilledUnacked.value = false
        KeepAliveJobService.cancel(context)
        AlarmSentryReceiver.cancel(context)   // alarm 哨兵链一并撤（用户显式停止不唤醒）
        KeepAliveJobService.clearWarningNotification(context)   // 撤警示通知
        writeDiag(
            "停止完成: chain.isAlive=${loopbackChain.isAlive}, engineState=${engineState.value}, " +
                "loopbackRerouted=${takeover.loopbackRerouted}"
        )
            }
        }
    }

    // ---- 合规：撤回隐私同意 / 清除诊断数据 ----

    /**
     * 撤回隐私政策同意：立即停止音效处理（回滚静音 + 停前台服务），由调用方重建 UI。
     * 先停链再写标记——避免标记已落盘但链路还在跑，出现「已撤回却仍在录」的窗口。
     */
    fun revokePrivacyConsent() {
        writeDiag("用户撤回隐私政策同意 → 停止音效处理")
        stopCapture()
        prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, false).apply()
    }

    /**
     * 清除诊断数据：只删 `files/crash` 下的日志与音频样本。
     * **不动音效参数、预设与用户导入的脉冲文件**——那些是用户资产，误删代价高。
     */
    fun clearDiagnosticData() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching {
                var count = 0
                java.io.File(DiagDirs.crashDir).listFiles()?.forEach { f ->
                    if (f.isFile && f.delete()) count++
                }
                count
            }.getOrDefault(0)
            writeDiag("清除诊断数据：已删除 $n 个文件（保留音效参数与脉冲库）")
        }
    }

    /** 诊断导出结果消息（UI 在诊断卡显示，导出后立即给反馈） */
    private val _diagnosticsMessage = MutableStateFlow<String?>(null)
    val diagnosticsMessage: StateFlow<String?> = _diagnosticsMessage.asStateFlow()

    /**
     * 导出诊断：把 `files/crash` 下全部日志打包 zip 写入系统「下载」目录。
     *
     * **为什么必须在 App 内做**：这台 ROM 完全禁用 logcat（`logcat -d` 恒 0 行），
     * 而正式包不可 `run-as` 读私有目录 —— 正式版用户报障时开发者没有任何取证手段
     * 。走 MediaStore.Downloads 无需权限、
     * 无需 FileProvider，用户在文件管理器里能直接找到并发出来。
     */
    fun exportDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val files = java.io.File(DiagDirs.crashDir)
                    .listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
                if (files.isEmpty()) return@runCatching "诊断目录为空，暂无可导出的日志"
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val displayName = "sonicraft_diag_$ts.zip"
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法在下载目录创建导出文件")
                try {
                    resolver.openOutputStream(outUri)?.use { raw ->
                        ZipOutputStream(raw.buffered()).use { zip ->
                            files.forEach { f ->
                                zip.putNextEntry(ZipEntry(f.name))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalStateException("无法写入导出文件")
                } catch (e: Exception) {
                    // 写坏就删掉半成品，避免用户在下载目录看到损坏的 zip
                    resolver.delete(outUri, null, null)
                    throw e
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
                "已导出 ${files.size} 个诊断文件到「下载」（$displayName）"
            }.onSuccess { _diagnosticsMessage.value = it }
                .onFailure { e -> _diagnosticsMessage.value = "导出失败：${e.message}" }
        }
    }

    // ---- 批3：总增益 / 每段频率 / 自定义预设 ----
    // （U9n：GEQ/PEQ 图形模式退役——仅保留参量 PEQ，eqGuiMode/toggleEqGuiMode 删除；
    //   旧设备 prefs 残留 eq_gui_mode 键无人读取，无害）

    private val _eqPreGainDb = MutableStateFlow(prefs.getFloat(KEY_EQ_PREGAIN, 0f))
    val eqPreGainDb: StateFlow<Float> = _eqPreGainDb.asStateFlow()

    fun setEqPreGainDb(db: Float) {
        // ±18dB 对齐 wecho Preamp（native 同步放宽）
        val clamped = db.coerceIn(-18f, 18f)
        _eqPreGainDb.value = clamped
        prefs.edit().putFloat(KEY_EQ_PREGAIN, clamped).apply()
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_PREGAIN_DB, clamped)
    }

    // ---- AutoEq 独立校正（2026-11-14）----
    // 与 PEQ 完全独立：自带段数/增益/Q/频率/类型/预增益与开关，下发到 AUTO_EQ_NODE_INDEX。
    // 这样导入型号校正**不会覆盖**用户手调的 PEQ，两者可各自旁路。
    private val _autoEqEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTOEQ_ENABLED, false))
    val autoEqEnabled: StateFlow<Boolean> = _autoEqEnabled.asStateFlow()

    private val _autoEqMode = MutableStateFlow(prefs.getInt(KEY_AUTOEQ_MODE, 10).coerceIn(1, 31))
    val autoEqMode: StateFlow<Int> = _autoEqMode.asStateFlow()

    private val _autoEqSource = MutableStateFlow(prefs.getString(KEY_AUTOEQ_SOURCE, "").orEmpty())
    val autoEqSource: StateFlow<String> = _autoEqSource.asStateFlow()

    private val _autoEqPreGain = MutableStateFlow(prefs.getFloat(KEY_AUTOEQ_PREGAIN, 0f))
    val autoEqPreGain: StateFlow<Float> = _autoEqPreGain.asStateFlow()

    private val _autoEqGains = MutableStateFlow(readAutoEqFloats(AUTOEQ_GAINS_PREFIX, _autoEqMode.value))
    val autoEqGains: StateFlow<List<Float>> = _autoEqGains.asStateFlow()

    private val _autoEqQs = MutableStateFlow(readAutoEqFloats(AUTOEQ_QS_PREFIX, _autoEqMode.value, 0.707f))
    val autoEqQs: StateFlow<List<Float>> = _autoEqQs.asStateFlow()

    private val _autoEqFreqs = MutableStateFlow(
        readAutoEqFloats(AUTOEQ_FREQS_PREFIX, _autoEqMode.value).let { stored ->
            if (stored.any { it > 0f }) stored else EqMath.freqTable(_autoEqMode.value)
        },
    )
    val autoEqFreqs: StateFlow<List<Float>> = _autoEqFreqs.asStateFlow()

    private val _autoEqTypes = MutableStateFlow(
        runCatching {
            prefs.getString(AUTOEQ_TYPES_PREFIX + _autoEqMode.value, null)
                ?.split(",")?.map { it.trim().toInt() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: List(_autoEqMode.value) { 0 },
    )
    val autoEqTypes: StateFlow<List<Int>> = _autoEqTypes.asStateFlow()

    private fun readAutoEqFloats(prefix: String, mode: Int, default: Float = 0f): List<Float> = runCatching {
        prefs.getString(prefix + mode, null)?.split(",")?.map { it.trim().toFloat() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { stored ->
        List(mode) { i -> stored.getOrElse(i) { default } }
    } ?: List(mode) { default }

    fun setAutoEqEnabled(enabled: Boolean) {
        _autoEqEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTOEQ_ENABLED, enabled).apply()
        applyEqGates()
    }

    /**
     * 用导入结果整体替换 AutoEq 段（**不触碰 PEQ**）。
     * 顺序：段数 → 各段参数 → 预增益 → 取消旁路（段数会重置各段，必须最先发）。
     */
    private fun writeAutoEq(
        mode: Int,
        gains: List<Float>,
        qs: List<Float>,
        freqs: List<Float>,
        types: List<Int>,
        preGainDb: Float,
        sourceName: String,
    ) {
        val n = mode.coerceIn(1, 31)
        val g = List(n) { gains.getOrElse(it) { 0f } }
        val q = List(n) { qs.getOrElse(it) { 0.707f } }
        val f = List(n) { freqs.getOrElse(it) { EqMath.freqTable(n).getOrElse(it) { 1000f } } }
        val t = List(n) { types.getOrElse(it) { 0 } }
        val pre = preGainDb.coerceIn(-18f, 18f)

        _autoEqMode.value = n
        _autoEqGains.value = g
        _autoEqQs.value = q
        _autoEqFreqs.value = f
        _autoEqTypes.value = t
        _autoEqPreGain.value = pre
        _autoEqSource.value = sourceName
        prefs.edit()
            .putInt(KEY_AUTOEQ_MODE, n)
            .putFloat(KEY_AUTOEQ_PREGAIN, pre)
            .putString(KEY_AUTOEQ_SOURCE, sourceName)
            .putString(AUTOEQ_GAINS_PREFIX + n, g.joinToString(",") { "%.2f".format(it) })
            .putString(AUTOEQ_QS_PREFIX + n, q.joinToString(",") { "%.3f".format(it) })
            .putString(AUTOEQ_FREQS_PREFIX + n, f.joinToString(",") { "%.1f".format(it) })
            .putString(AUTOEQ_TYPES_PREFIX + n, t.joinToString(","))
            .apply()

        engine.setParam(AudioEngine.AUTO_EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, n.toFloat())
        for (i in 0 until n) {
            engine.setParam(AudioEngine.AUTO_EQ_NODE_INDEX, i, g[i])
            engine.setParam(AudioEngine.AUTO_EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, q[i])
            engine.setParam(AudioEngine.AUTO_EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, f[i])
            engine.setParam(AudioEngine.AUTO_EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + i, t[i].toFloat())
        }
        engine.setParam(AudioEngine.AUTO_EQ_NODE_INDEX, AudioEngine.EQ_PARAM_PREGAIN_DB, pre)
        setAutoEqEnabled(true)
        // 立刻把「已启用」写进**当前设备**的配置：设备配置是 10s 周期快照 + 切换时回放，
        // 若不立即落盘，导入后紧接着的一次设备回放会把 enabled 覆盖成旧值（实测复现过）。
        runCatching { saveLiveConfig(outputDeviceMonitor.current.value.key) }
        // 导入即入库（同名覆盖）：库就是"我装过的耳机校正"，与机架脚本库同构
        if (sourceName.isNotBlank()) saveCurrentAutoEqToLibrary(sourceName)
    }

    // ---- AutoEq 库（多副耳机）----
    /** 一条校正曲线：名字 + 段数 + 逐段参数 + preamp */
    data class AutoEqCurve(
        val name: String,
        val mode: Int,
        val gains: List<Float>,
        val qs: List<Float>,
        val freqs: List<Float>,
        val types: List<Int>,
        val preampDb: Float,
    )

    private val _autoEqLibrary = MutableStateFlow(readAutoEqLibrary())
    val autoEqLibrary: StateFlow<List<AutoEqCurve>> = _autoEqLibrary.asStateFlow()

    private fun readAutoEqLibrary(): List<AutoEqCurve> = runCatching {
        val raw = prefs.getString(KEY_AUTOEQ_LIBRARY, null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            fun floats(k: String): List<Float> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return List(a.length()) { j -> a.getDouble(j).toFloat() }
            }
            fun ints(k: String): List<Int> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return List(a.length()) { j -> a.getInt(j) }
            }
            AutoEqCurve(
                name = o.optString("name", "未命名"),
                mode = o.optInt("mode", 10),
                gains = floats("gains"),
                qs = floats("qs"),
                freqs = floats("freqs"),
                types = ints("types"),
                preampDb = o.optDouble("preamp", 0.0).toFloat(),
            )
        }
    }.getOrDefault(emptyList())

    private fun persistAutoEqLibrary(list: List<AutoEqCurve>) {
        runCatching {
            val arr = org.json.JSONArray()
            list.forEach { c ->
                val o = org.json.JSONObject()
                o.put("name", c.name)
                o.put("mode", c.mode)
                o.put("preamp", c.preampDb.toDouble())
                o.put("gains", org.json.JSONArray(c.gains.map { it.toDouble() }))
                o.put("qs", org.json.JSONArray(c.qs.map { it.toDouble() }))
                o.put("freqs", org.json.JSONArray(c.freqs.map { it.toDouble() }))
                o.put("types", org.json.JSONArray(c.types))
                arr.put(o)
            }
            prefs.edit().putString(KEY_AUTOEQ_LIBRARY, arr.toString()).apply()
        }
    }

    /** 设备回放用：逐段改 AutoEq（与 writeAutoEq 的整体替换区分开） */
    private fun setAutoEqBandValue(kind: String, i: Int, v: Float) {
        if (i !in 0 until _autoEqMode.value) return
        val n = AudioEngine.AUTO_EQ_NODE_INDEX
        val gains = _autoEqGains.value.toMutableList()
        val qs = _autoEqQs.value.toMutableList()
        val freqs = _autoEqFreqs.value.toMutableList()
        val types = _autoEqTypes.value.toMutableList()
        if (i !in gains.indices || i !in qs.indices || i !in freqs.indices || i !in types.indices) return
        when (kind) {
            "gain" -> {
                gains[i] = v.coerceIn(-12f, 12f)
                engine.setParam(n, i, gains[i])
            }
            "q" -> {
                qs[i] = v.coerceIn(0.3f, 8f)
                engine.setParam(n, AudioEngine.EQ_Q_BASE + i, qs[i])
            }
            "freq" -> {
                freqs[i] = v.coerceIn(20f, 20000f)
                engine.setParam(n, AudioEngine.EQ_FREQ_BASE + i, freqs[i])
            }
            "type" -> {
                types[i] = v.toInt().coerceIn(0, 2)
                engine.setParam(n, AudioEngine.EQ_TYPE_BASE + i, types[i].toFloat())
            }
        }
        _autoEqGains.value = gains
        _autoEqQs.value = qs
        _autoEqFreqs.value = freqs
        _autoEqTypes.value = types
        val m = _autoEqMode.value
        prefs.edit()
            .putString(AUTOEQ_GAINS_PREFIX + m, gains.joinToString(",") { "%.2f".format(it) })
            .putString(AUTOEQ_QS_PREFIX + m, qs.joinToString(",") { "%.3f".format(it) })
            .putString(AUTOEQ_FREQS_PREFIX + m, freqs.joinToString(",") { "%.1f".format(it) })
            .putString(AUTOEQ_TYPES_PREFIX + m, types.joinToString(","))
            .apply()
    }

    /** 把当前 AutoEq 状态存进库（同名覆盖）；无来源名时用「校正 N」 */
    fun saveCurrentAutoEqToLibrary(name: String? = null) {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: _autoEqSource.value.takeIf { it.isNotBlank() }
            ?: "校正 ${_autoEqLibrary.value.size + 1}"
        val curve = AutoEqCurve(
            name = n,
            mode = _autoEqMode.value,
            gains = _autoEqGains.value,
            qs = _autoEqQs.value,
            freqs = _autoEqFreqs.value,
            types = _autoEqTypes.value,
            preampDb = _autoEqPreGain.value,
        )
        // 同名覆盖，其余全部保留（不设上限）
        _autoEqLibrary.value = _autoEqLibrary.value.filter { it.name != n } + curve
        persistAutoEqLibrary(_autoEqLibrary.value)
        _autoEqSource.value = n
        prefs.edit().putString(KEY_AUTOEQ_SOURCE, n).apply()
    }

    /** 从库装载一条曲线（只改 AutoEq，不动 PEQ） */
    fun applyAutoEqCurve(name: String) {
        val c = _autoEqLibrary.value.firstOrNull { it.name == name } ?: return
        writeAutoEq(
            mode = c.mode,
            gains = c.gains,
            qs = c.qs,
            freqs = c.freqs,
            types = c.types,
            preGainDb = c.preampDb,
            sourceName = c.name,
        )
    }

    /**
     * 重命名库条目。成功返回 null，失败返回给用户看的原因。
     * 若改的正是「当前生效」那条，同步更新来源名，避免列表与卡片对不上。
     */
    fun renameAutoEqCurve(oldName: String, newNameRaw: String): String? {
        val newName = newNameRaw.trim()
        if (newName.isEmpty()) return "名字不能为空"
        if (newName == oldName) return null
        if (_autoEqLibrary.value.any { it.name == newName }) return "已有同名条目"
        val idx = _autoEqLibrary.value.indexOfFirst { it.name == oldName }
        if (idx < 0) return "条目不存在"
        _autoEqLibrary.value = _autoEqLibrary.value.toMutableList().also {
            it[idx] = it[idx].copy(name = newName)
        }
        persistAutoEqLibrary(_autoEqLibrary.value)
        if (_autoEqSource.value == oldName) {
            _autoEqSource.value = newName
            prefs.edit().putString(KEY_AUTOEQ_SOURCE, newName).apply()
        }
        return null
    }

    fun deleteAutoEqCurve(name: String) {
        _autoEqLibrary.value = _autoEqLibrary.value.filter { it.name != name }
        persistAutoEqLibrary(_autoEqLibrary.value)
    }

    private val _eqBandFreqs = MutableStateFlow(readEqFreqs(_eqMode.value))
    val eqBandFreqs: StateFlow<List<Float>> = _eqBandFreqs.asStateFlow()

    private fun readEqFreqs(mode: Int): List<Float> = runCatching {
        prefs.getString("eq_freq_$mode", null)?.split(",")?.map { it.toFloat() }
    }.getOrNull()?.let { stored ->
        val table = EqMath.freqTable(mode)
        List(mode) { i -> stored.getOrElse(i) { table.getOrElse(i) { 1000f } }.coerceIn(20f, 20000f) }
    } ?: EqMath.freqTable(mode)

    fun setEqBandFreq(band: Int, hz: Float) {
        val clamped = hz.coerceIn(20f, 20000f)
        val list = _eqBandFreqs.value.toMutableList()
        if (band !in list.indices) return
        list[band] = clamped
        _eqBandFreqs.value = list
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + band, clamped)
        prefs.edit().putString("eq_freq_${_eqMode.value}", list.joinToString(",") { "%.1f".format(it) }).apply()
    }

    private val _eqUserPresets = MutableStateFlow(readEqUserPresets())
    val eqUserPresets: StateFlow<List<EqUserPreset>> = _eqUserPresets.asStateFlow()

    private fun readEqUserPresets(): List<EqUserPreset> = runCatching {
        val arr = org.json.JSONArray(prefs.getString(KEY_EQ_USER_PRESETS, "[]"))
        (0 until arr.length()).map { idx ->
            val ob = arr.getJSONObject(idx)
            fun fa(k: String): List<Float> {
                val a = ob.getJSONArray(k)
                return (0 until a.length()).map { a.getDouble(it).toFloat() }
            }
            EqUserPreset(
                ob.getString("name"), ob.getInt("mode"), fa("gains"), fa("qs"), fa("freqs"),
                // 旧预设无 types 字段→空列表=全 PK（兼容）
                if (ob.has("types")) {
                    val ta = ob.getJSONArray("types")
                    (0 until ta.length()).map { ta.getInt(it).coerceIn(0, 2) }
                } else {
                    emptyList()
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun persistEqUserPresets(list: List<EqUserPreset>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            val ob = org.json.JSONObject()
            ob.put("name", p.name)
            ob.put("mode", p.mode)
            listOf("gains" to p.gains, "qs" to p.qs, "freqs" to p.freqs).forEach { (k, vals) ->
                val a = org.json.JSONArray()
                vals.forEach { a.put(it.toDouble()) }
                ob.put(k, a)
            }
            if (p.types.isNotEmpty()) ob.put("types", org.json.JSONArray(p.types))
            arr.put(ob)
        }
        prefs.edit().putString(KEY_EQ_USER_PRESETS, arr.toString()).apply()
    }

    fun saveEqUserPreset(name: String) {
        val n = name.trim().ifEmpty { "预设 ${_eqUserPresets.value.size + 1}" }
        val p = EqUserPreset(
            n, _eqMode.value,
            _eqBandGains.value.toList(), _eqBandQs.value.toList(), _eqBandFreqs.value.toList(),
            _eqBandTypes.value.toList(),
        )
        _eqUserPresets.value = _eqUserPresets.value.filter { it.name != n } + p
        persistEqUserPresets(_eqUserPresets.value)
    }

    fun deleteEqUserPreset(name: String) {
        _eqUserPresets.value = _eqUserPresets.value.filter { it.name != name }
        persistEqUserPresets(_eqUserPresets.value)
    }

    /** 应用自定义预设：模式+增益+Q+频率全套恢复与下发 */
    fun applyEqUserPreset(p: EqUserPreset) {
        val m = p.mode.coerceIn(1, 31)
        prefs.edit().putInt(KEY_EQ_MODE, m).apply()
        _eqMode.value = m
        _eqBandGains.value = p.gains.take(m)
        _eqBandQs.value = p.qs.take(m)
        _eqBandFreqs.value = p.freqs.take(m)
        _eqBandTypes.value = List(m) { i -> p.types.getOrElse(i) { 0 }.coerceIn(0, 2) }
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, m.toFloat())
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_PREGAIN_DB, _eqPreGainDb.value)
        // AutoEq 独立节点：重建链路后同样要重放，否则重启 App 后校正失效
        run {
            val n = AudioEngine.AUTO_EQ_NODE_INDEX
            engine.setParam(n, AudioEngine.EQ_PARAM_BAND_COUNT, _autoEqMode.value.toFloat())
            _autoEqGains.value.forEachIndexed { i, g -> engine.setParam(n, i, g) }
            _autoEqQs.value.forEachIndexed { i, q -> engine.setParam(n, AudioEngine.EQ_Q_BASE + i, q) }
            _autoEqFreqs.value.forEachIndexed { i, f -> engine.setParam(n, AudioEngine.EQ_FREQ_BASE + i, f) }
            _autoEqTypes.value.forEachIndexed { i, t ->
                engine.setParam(n, AudioEngine.EQ_TYPE_BASE + i, t.toFloat())
            }
            engine.setParam(n, AudioEngine.EQ_PARAM_PREGAIN_DB, _autoEqPreGain.value)
            engine.setBypass(n, !_autoEqEnabled.value)
        }
        p.gains.take(m).forEachIndexed { i, g -> engine.setParam(AudioEngine.EQ_NODE_INDEX, i, g) }
        p.qs.take(m).forEachIndexed { i, q -> engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, q) }
        p.freqs.take(m).forEachIndexed { i, f -> engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, f) }
        _eqBandTypes.value.forEachIndexed { i, t -> engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + i, t.toFloat()) }
        persistEqBands(m, _eqBandGains.value)
        persistEqQs(m, _eqBandQs.value)
        persistEqTypes(m, _eqBandTypes.value)
        prefs.edit().putString("eq_freq_$m", _eqBandFreqs.value.joinToString(",") { "%.1f".format(it) }).apply()
        _bassGain.value = _eqBandGains.value.firstOrNull() ?: 0f
        _trebleGain.value = _eqBandGains.value.lastOrNull() ?: 0f
    }

    // ---- AutoEQ 耳机修正：粘贴 ParametricEQ.txt 一键应用 ----

    /**
     * 解析并应用 AutoEq 导出文本：段数精确落到导入段数（1~31，段数泛化后不再
     * 向上取档留静默段）、逐段覆盖增益/Q/频率/类型、preamp→EQ 总增益。
     * 返回 null=成功，非 null=错误提示。
     */
    /** AutoEq 导入结果：ok=false 时 message 是失败原因 */
    data class AutoEqOutcome(val ok: Boolean, val message: String)

    /**
     * 解析并应用 AutoEq 文本。
     *
     * @param saveAsPreset 非空时，应用成功后把结果存成同名 EQ 预设（文件导入走这条，
     *   让「我的 EQ 预设」成为曲线的统一入口）；粘贴导入传 null，不污染预设库。
     */
    fun applyAutoEq(text: String, saveAsPreset: String? = null): AutoEqOutcome {
        // 三种格式依次尝试：参数化（Filter 行）→ GraphicEQ 单行 → 结果 CSV（equalization 列）
        val parsed = AutoEqParser.parse(text)
        val bands: List<AutoEqParser.Band>
        val preampDb: Float
        val formatNote: String
        var skipped = 0
        var clamped = 0
        if (parsed != null) {
            bands = parsed.bands
            preampDb = parsed.preampDb
            skipped = parsed.skipped
            clamped = parsed.clamped
            formatNote = ""
        } else {
            val curve = AutoEqParser.parseGraphic(text) ?: AutoEqParser.parseCsvCurve(text)
            if (curve == null) {
                return AutoEqOutcome(
                    false,
                    "未识别到 AutoEq 格式 —— 支持 ParametricEQ.txt（Filter 行）、" +
                        "GraphicEQ.txt（GraphicEQ: 频点…）与结果 CSV（含 equalization 列）",
                )
            }
            // 曲线用**最小二乘拟合**到当前各段（段数与位置不动，只求增益）：
            // 直接按点取值会在相邻段重叠处叠加过量（一片 +3 的曲线合起来能到 +8）。
            bands = AutoEqParser.fitBandsToCurve(
                curve = curve,
                freqs = _autoEqFreqs.value,
                qs = _autoEqQs.value,
                types = _autoEqTypes.value,
            ).ifEmpty { AutoEqParser.curveToBands(curve, _autoEqFreqs.value) }
            // 曲线是补偿量：峰值为正时需要预衰减，避免叠加后削波
            val peak = curve.maxOfOrNull { it.second } ?: 0f
            preampDb = if (peak > 0f) -peak else 0f
            formatNote = "，曲线拟合 · 最小二乘"
        }
        val m = bands.size
        val gains = MutableList(m) { i -> bands[i].gain }
        val qs = MutableList(m) { i -> bands[i].q }
        val freqs = MutableList(m) { i -> bands[i].freq }
        val types = MutableList(m) { i -> bands[i].type }
        // 写入**独立的 AutoEq 节点**：不再改动 PEQ 的任何一段，
        // 用户手调的偏好曲线得以保留；两者各自旁路、可分别开关。
        writeAutoEq(
            mode = m,
            gains = gains,
            qs = qs,
            freqs = freqs,
            types = types,
            preGainDb = preampDb,
            sourceName = saveAsPreset?.takeIf { it.isNotBlank() }.orEmpty(),
        )
        val skipNote = if (skipped > 0) "，跳过 $skipped 段（LP/HP/None 不支持）" else ""
        // 超范围钳位提示：AutoEq 偶尔给出 ±12 dB 之外的增益，我们按硬件范围截断
        val clampNote = if (clamped > 0) "，$clamped 处超范围已截断" else ""
        val presetNote = if (saveAsPreset.isNullOrBlank()) "" else "，来源「$saveAsPreset」"
        // 语境提示：AutoEq 曲线是按耳机（Harman 等目标）拟合的，外放上用通常不理想
        val ctxNote = if (!outputDeviceMonitor.isSpeakerOutput()) "" else
            "；当前是扬声器输出 —— AutoEq 曲线为耳机设计，外放效果通常不佳"
        return AutoEqOutcome(
            true,
            "已应用到 AutoEq $m 段（preamp ${"%.1f".format(preampDb)} dB$formatNote）" +
                "$skipNote$clampNote$presetNote，PEQ 未改动$ctxNote",
        )
    }

    // ---- 夜间保活短期方案----
    // 被系统终止未确认：STICKY 重生 / 开机自启 receiver 写入；用户恢复成功或主动停止时清除。
    private val _systemKilledUnacked = MutableStateFlow(
        prefs.getBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, false),
    )
    val systemKilledUnacked: StateFlow<Boolean> = _systemKilledUnacked.asStateFlow()

    fun clearSystemKilledUnacked() {
        prefs.edit().putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, false).apply()
        _systemKilledUnacked.value = false
    }

    // 电池优化豁免状态（未豁免时 UI 引导一键申请；NX733J 实测已 user 级豁免则不显示）
    private val _batteryIgnored = MutableStateFlow(true)
    val batteryIgnored: StateFlow<Boolean> = _batteryIgnored.asStateFlow()

    fun refreshBatteryState() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        _batteryIgnored.value = runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
    }

    /** 一键跳系统「忽略电池优化」确认框（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS） */
    fun requestIgnoreBatteryOptimization() {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 打开 app 自动启用内录接管。
     * 返回 true = 已启动（免弹窗路径成功）；false = 调用方应走系统 consent 弹窗
     * （Android 15 上 app 进程内免弹窗反射已被 hidden API 彻底封死，实测 declaredMethods
     *   只剩 2 个可见方法；本机 consent 体验无感，走它是唯一可行路径）。
     */
    suspend fun autoStartCaptureIfNeeded(): Boolean {
        // 注意：不能只看 isRunning —— 服务进程被杀/令牌失配后，
        // 引擎可能仍是 Running 但链路已断（probe=null），此时提前 return 会让
        // App 启动后永远没有音效。先判断「Running 且链路确实在」才提前返回。
        // 前台服务的 headless 恢复可能正在建链：等它一会儿，避免两边抢绑（code=-2）
        if (loopbackChain.building) {
            withTimeoutOrNull(4_000) {
                while (loopbackChain.building) delay(100)
            }
        }
        val loopbackAlive = loopbackProbe != null
        // 诊断：08:19:09 建链成功 Running，8 秒后又触发一次「首启探测」把链
        // 推倒重建（用户感知几秒断音）。当时守卫三条件里哪一项不成立无法判定——因为
        // 「链在手但引擎不在 Running」这条路径**完全没有日志**。故在决策点整体落盘一次；
        // 调用频率很低（App 打开/自动恢复时），不影响功耗。
        writeDiag(
            "自动启动检查: engineState=${engineState.value}, isRunning=$isRunning, " +
                "loopbackAlive=$loopbackAlive, building=${loopbackChain.building}, " +
                "mode=${if (isLoopbackMode()) "loopback" else "mp"}"
        )
        if (engineState.value is EngineState.Preparing) return true
        if (isRunning && (loopbackAlive || !isLoopbackMode())) return true
        if (isRunning && isLoopbackMode() && !loopbackAlive) {
            writeDiag("引擎 Running 但 Loopback 链路已断（probe=null）→ 重新建链")
        }
        if (loopbackAlive) {
            // 链在手但引擎不可用 → 下面的「首启探测」会全量重建，把在跑的链推倒一次。
            // 这只应在引擎真死（error/CAPTURE_DEAD）时发生，故单独留证便于区分。
            writeDiag("自动启动重建: 链在手但引擎不在可用态（engineState=${engineState.value}）")
        }
        // 模式选择（2026-11-05 定案）：默认 Loopback —— **首启即当作能力探测**。
        // 建不起来/取不到音频时弹窗告知并可切平替（不做热回退）。
        // 只有用户确认过「切 Stellar+MP」之后才直接走 MP 链路。
        val mode = prefs.getString(KEY_AUDIO_MODE, MODE_LOOPBACK) ?: MODE_LOOPBACK
        if (mode == MODE_LOOPBACK) {
            writeDiag("首启探测：audio_mode=loopback，尝试建立 Loopback 链路")
            startLoopbackCapture()
            return true
        }
        if (!canRecord()) return false
        context.startForegroundService(Intent(context, ForegroundDspService::class.java))
        // STICKY 重生场景：进程重建后 Stellar binder 重连可能晚于本调用（实测 1.5s 时
        // permissionGranted 仍 false，免弹窗失败多弹一次 consent）。挂起轮询等就绪
        // （最多 4s，协程内不卡主线程），就绪则免弹窗全自动恢复。
        if (!privileged.state.value.permissionGranted) {
            writeDiag("Stellar binder 未就绪，轮询等待（最多 4s）…")
            kotlinx.coroutines.withTimeoutOrNull(4_000) {
                while (!privileged.state.value.permissionGranted) delay(200)
            }
            if (!privileged.state.value.permissionGranted) {
                writeDiag("自动接管不可用：Stellar 未授权（内录本身不受影响，接管需要特权）")
                return false
            }
            writeDiag("Stellar binder 已就绪，走免弹窗恢复")
        }
        // （2026-11-03 审计）：tryCreateSilentProjection 内部是特权命令（H3 加固后
        // 上限 8s）——原在主线程同步执行，而夜间 STICKY 重生恰是 Stellar binder 最不
        // 稳定场景（FGS 5s 超时闪退事故同款模式漏网面）。移 IO：本函数是 suspend，
        // 调用方（LaunchedEffect）不受阻塞。
        val token = withContext(Dispatchers.IO) { tryCreateSilentProjection() } ?: run {
            writeDiag("免弹窗不可用（hidden API 限制），调用方走系统 consent")
            return false
        }
        startCaptureInBackground(token)
        return true
    }

    /** 切换自动接管（实时：开 → 启动轮询；关 → 停止轮询并回滚自动项） */
    private var lastToggleMs = 0L
    fun toggleAutoTakeover() {
        // 事故：关开关后 prefs 落盘 true（自开）。两条成因同时加固——
        // ① stopAndRollback 主线程执行特权命令（跨进程 binder 可卡数百 ms）卡住
        //    Compose 重组 → 点击丢失/重复 → next=!current 翻回 true：回滚移 IO 线程
        //    + 500ms 防抖；
        // ② apply() 异步落盘，紧跟停内录/进程退出时丢写：改 commit 同步落盘。
        val now = System.currentTimeMillis()
        if (now - lastToggleMs < 500) return
        lastToggleMs = now
        val next = !_autoTakeoverEnabled.value
        _autoTakeoverEnabled.value = next
        prefs.edit().putBoolean(KEY_AUTO_TAKEOVER, next).commit()
        if (next && isRunning) {
            takeover.start(true)
        } else if (!next) {
            viewModelScope.launch(Dispatchers.Default) { takeover.stopAndRollback() }
        }
    }

    /** 切换某 app 的排除状态（重启内录生效） */
    fun toggleExcluded(packageName: String) {
        val next = _excludedPackages.value.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        _excludedPackages.value = next
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, next).apply()
    }

    /**
     * 切换某 app 的接管状态（M3）：加入 = AppOps 静音其原声（实时生效，内录副本替代）；
     * 移除 = 恢复其原声。与排除清单互斥。需要特权框架（Stellar）。
     */
    fun toggleTakeover(packageName: String) {
        val next = _takeoverPackages.value.toMutableSet()
        if (!next.remove(packageName)) {
            // 互斥：从排除清单移除
            val excluded = _excludedPackages.value.toMutableSet()
            if (excluded.remove(packageName)) {
                _excludedPackages.value = excluded
                prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, excluded).apply()
            }
            if (!runAppops(packageName, APPOPS_IGNORE)) {
                _takeoverError.value = "接管失败：特权框架不可用（Stellar 未连接/未授权）"
                return
            }
            next.add(packageName)
        } else {
            runAppops(packageName, APPOPS_ALLOW)
        }
        _takeoverPackages.value = next
        prefs.edit().putStringSet(KEY_TAKEOVER_PACKAGES, next).apply()
    }

    /** 启动内录后统一 re-apply 接管静音（幂等，防清单与 AppOps 状态漂移） */
    fun reapplyTakeover() {
        _takeoverPackages.value.forEach { runAppops(it, APPOPS_IGNORE) }
    }

    /** 停止内录/退出时回滚：恢复全部被接管 app 的原声 */
    fun rollbackTakeover() {
        _takeoverPackages.value.forEach { runAppops(it, APPOPS_ALLOW) }
    }

    fun clearTakeoverError() {
        _takeoverError.value = null
    }

    /** 清除禁抓名单（误判恢复入口，2026-11-04 网易云案）：清 prefs 后所有 app 重新走判定 */
    fun clearCaptureBlocklist() = takeover.clearCaptureBlocklist()

    // DP 静音判定实验（诊断用）：对指定 uid 的活跃 session 挂 -200dB，测是否连坐副本。
    // 结论已定案见 docs/ADR.md ADR-02；全部步骤写 diag（本机 logcat 不可用）。

    /**
     * 读取当前输入电平。
     *
     * **必须优先用引擎实时统计**（每 250ms 更新）：水位日志是 10s 落盘一次的，
     * 建链后 1.6s 复查时读到的仍是建链前的旧数据（恒为 0）→ 误判「无数据」→
     * 触发自动重建第二条链，白花 ~6s（2026-11-06 实测：两条链都是这么被误触发的）。
     * 文件读取仅作为引擎未运行时的兜底。
     */
    private fun lastInputLevel(): Float {
        val live = engineState.value
        if (live is EngineState.Running) {
            val v = engine.stats.value.inputLevel
            if (v > 0f) return v
        }
        return runCatching {
            val f = java.io.File(DiagDirs.crashDir, "watermark_log.txt")
            if (!f.exists()) return@runCatching -1f
            f.readLines().lastOrNull { it.contains("inputLevel=") }
                ?.substringAfter("inputLevel=")?.substringBefore(' ')?.toFloatOrNull() ?: -1f
        }.getOrDefault(-1f)
    }

    private suspend fun sampleInputLevels(n: Int): String {
        val sb = StringBuilder()
        repeat(n) {
            sb.append("%.4f ".format(lastInputLevel()))
            delay(1000)
        }
        return sb.toString().trim()
    }

    // ---- Loopback 探针（2026-11-04 P0）：验证 AudioPolicy/AudioMix(LOOP_BACK) 路线
    // 能否抓到 BY_SYSTEM + FLAG_NO_MEDIA_PROJECTION 的流（酷狗）。app 进程零改动，
    // 只在 UserService 侧跑反射链并回传 RMS 电平。详见 docs/LOOPBACK_POLICY_PLAN.md ----
    private val _loopbackProbeState = MutableStateFlow<String?>(null)
    val loopbackProbeState: StateFlow<String?> = _loopbackProbeState.asStateFlow()
    private val _loopbackProbeLevel = MutableStateFlow(0f)
    val loopbackProbeLevel: StateFlow<Float> = _loopbackProbeLevel.asStateFlow()
    private val _loopbackProbeTrail = MutableStateFlow<String?>(null)
    val loopbackProbeTrail: StateFlow<String?> = _loopbackProbeTrail.asStateFlow()
    // 句柄一律走进程级持有者：ViewModel 重建后仍能拿到活着的链
    private var loopbackProbe: ILoopbackProbe?
        get() = loopbackChain.probe
        set(value) {
            if (value == null) loopbackChain.detach()
            // 非空赋值由 attach(probe, source) 完成（需要 source 一起登记）
        }

    /**
     * 客户端存活令牌：交给 UserService `linkToDeath`。本进程死亡时服务立即
     * 释放托管的静音效果（根治「app 被强杀 → DP 残留 → 用户只能重启」）。
     */
    private val loopbackClientBinder = android.os.Binder()
    private var loopbackPollJob: Job? = null

    /**
     * P1：用 Loopback（特权侧 AudioPolicy/LOOP_BACK）替代 MediaProjection
     * 启动 DSP 引擎，验证「PCM 真能进 DSP 链并出声」。
     *
     * 注意：本方法**不做接管静音**——LOOP_BACK 是复制语义，原声会照常播放，因此
     * 不 mute 时听到的是「原声 + 处理后副本」的双声。P1 只验证链路出声；
     * 原声压制（appops / DP）与禁抓名单语义改造放 P2。
     */
    /**
     * 当前正在播放媒体的全部会话（供建链前预静音）。
     * AAudio 的 `sessionId:-1` 自动跳过（无会话 = 无法挂会话级效果）。
     */
    private suspend fun activeMediaSids(): IntArray = withContext(Dispatchers.IO) {
        val dump = privileged.runCommand("dumpsys", "audio")
        if (dump.exitCode != 0) return@withContext IntArray(0)
        val self = android.os.Process.myUid()
        val uids = Regex("""u/pid:(\d+)/\d+[^\n]*state:started[^\n]*usage=USAGE_MEDIA""")
            .findAll(dump.stdout)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it != self && it >= 10_000 }
            .distinct()
            .toList()
        val sids = linkedSetOf<Int>()
        for (u in uids) {
            Regex("""u/pid:$u/\d+[^\n]*sessionId:(\d+)""").findAll(dump.stdout)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it > 0 }
                .forEach { sids.add(it) }
        }
        sids.toIntArray()
    }

    // ===== D4 编排：Loopback 优先 → MediaProjection 回退 =====
    // 设计依据：Loopback 成功时最干净（无弹窗、无 consent、
    // 双源可同入一条 mix、DP 都不需要挂）；但它依赖 ROM 的 REROUTE 行为，换机可能失效。
    // 故失败后自动回退 MP，并把失败记进 prefs（TTL 24h），避免每次启动都白等 3s。

    /** Loopback 能力失败时间（ms）；TTL 内自动启动直接走 MP */
    private fun loopbackFailedRecently(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_LOOPBACK_FAIL_AT, 0L) < LOOPBACK_FAIL_TTL_MS

    private fun markLoopbackFailed() {
        prefs.edit().putLong(KEY_LOOPBACK_FAIL_AT, System.currentTimeMillis()).apply()
        writeDiag("Loopback 能力标记失败（${LOOPBACK_FAIL_TTL_MS / 3_600_000}h 内自动启动优先走 MP）")
    }

    private fun clearLoopbackFailure() {
        if (prefs.getLong(KEY_LOOPBACK_FAIL_AT, 0L) != 0L) {
            prefs.edit().remove(KEY_LOOPBACK_FAIL_AT).apply()
            writeDiag("Loopback 能力恢复（已清除失败标记）")
        }
    }

    /**
     * 回退 MediaProjection。顺序必须是「先停链路再起 MP」：
     * 若先起 MP 而 loopback 的 record 还在，会出现「吃流无消费者」的黑洞式卡死声。
     */
    /**
     * UI 注入的「发起 MediaProjection 授权」入口（MainScreen 里绑到 projectionLauncher）。
     *
     * 为什么要交给 UI：本机 `IMediaProjectionManager` 的 AIDL 被框架剥离（接口只有
     * `hasProjectionPermission`，`$Stub` 只有 `asBinder/onTransact`，没有 `asInterface`），
     * **Stellar 免弹窗投影恒失败**，只能走系统 consent 弹窗，而它必须由 Activity 发起。
     */
    @Volatile
    var projectionRequester: (() -> Unit)? = null

    /**
     * 正在回退 MP 的标志：回退后同一段 loopback 启动流程还会继续跑
     * `takeover.start(loopback=true)`，那会把 MP 需要的 `appops PLAY_AUDIO=ignore`
     * 改回 allow → MP 模式立刻变双声。回退期间必须跳过 loopback 接管。
     */
    @Volatile
    private var mpFallbackInProgress = false

    // ===== 首次探测 → 不支持则告知并可切换平替模式（2026-11-05 定案）=====
    // 不做运行期热回退（会打断播放、且需要重启链路）。策略：
    //   1) 用户安装后首次启动即尝试 Loopback（这本身就是探测）
    //   2) 建链失败 / 建链成功但取不到音频 → 记录失败 + 弹窗告知原因
    //   3) 用户点确认 → 写入 audio_mode=stellar_mp，走 MP 链路（此后不再尝试 loopback）

    /** 不支持 Loopback 时的提示文案（非 null 时 UI 弹窗） */
    private val _loopbackUnsupported = MutableStateFlow<String?>(null)
    val loopbackUnsupported: StateFlow<String?> = _loopbackUnsupported.asStateFlow()

    /** 用户选择「暂不切换」：仅关闭弹窗，保留当前（原声直出）状态 */
    fun dismissLoopbackUnsupported() {
        _loopbackUnsupported.value = null
    }

    /**
     * 链路启动中：电源键做呼吸动画 + 禁用重复点击。
     * 为什么需要单独标志：建链全过程（绑定 Stellar → 预静音 → 12×250ms 重试 →
     * 6×600ms 电平判定）大约 0.2~4s，这段时间 `engineState` 仍是 Idle，
     * 电源键看起来“没反应”，用户会以为没点上而反复点。
     */
    private val _chainStarting = MutableStateFlow(false)
    val chainStarting: StateFlow<Boolean> = _chainStarting.asStateFlow()

    /**
     * 启动阶段文案（，2026-11-06）：把原来一个 15 秒的「Waiting」黑箱拆成
     * 「建链中 → 校验路由 → 收数据」，让用户知道现在卡在哪一步。
     */
    private val _chainStage = MutableStateFlow<String?>(null)
    val chainStage: StateFlow<String?> = _chainStage.asStateFlow()

    /** 上次「令牌不匹配」自愈时间（60s 内只自愈一次，避免失败循环） */
    private var lastTokenRecoveryAt = 0L

    /** 上次「建链 ok 但无数据」自动重建时间（，60s 内只重建一次） */
    private var lastChainRetryAt = 0L

    /** 当前音频链路模式（UI 可观察，设置页展示/切换用） */
    private val _audioMode = MutableStateFlow(MODE_LOOPBACK)
    val audioMode: StateFlow<String> = _audioMode.asStateFlow()

    /**
     * 输出缓冲（毫秒，用户可调，2026-11-06）。
     * 越小延迟越低但越容易在尖峰时欠载（爆音）；越大越稳。范围 4~24ms。
     */
    private val _bufferMs = MutableStateFlow(DEFAULT_BUFFER_MS)
    val bufferMs: StateFlow<Int> = _bufferMs.asStateFlow()

    fun setBufferMs(ms: Int) {
        val v = ms.coerceIn(4, 24)
        prefs.edit().putInt(KEY_BUFFER_MS, v).apply()
        _bufferMs.value = v
        runCatching { engine.setBufferFrames(v * SAMPLE_RATE_KHZ) }
        writeDiag("用户调整输出缓冲 → ${v}ms（${v * SAMPLE_RATE_KHZ} 帧）")
    }

    /** 当前音频链路模式是否为 Loopback（默认） */
    fun isLoopbackMode(): Boolean {
        val m = prefs.getString(KEY_AUDIO_MODE, MODE_LOOPBACK) ?: MODE_LOOPBACK
        // 顺带同步给 UI（避免在 init 里读 prefs 触发初始化顺序问题）
        if (_audioMode.value != m) _audioMode.value = m
        return m == MODE_LOOPBACK
    }

    /**
     * 设置页手动切换音频链路。
     * 只写偏好与状态，不打断当前播放；下次启动/点电源键时按新模式生效。
     */
    fun setAudioMode(mode: String) {
        val m = if (mode == MODE_STELLAR_MP) MODE_STELLAR_MP else MODE_LOOPBACK
        val prev = prefs.getString(KEY_AUDIO_MODE, MODE_LOOPBACK) ?: MODE_LOOPBACK
        prefs.edit().putString(KEY_AUDIO_MODE, m).apply()
        _audioMode.value = m
        if (m == prev) {
            writeDiag("音频链路模式未变（仍为 $m）")
            return
        }
        // **即刻生效（2026-11-06 用户要求）**：引擎在跑就按新模式重建链路。
        // 此时会有一段 1~3s 的静音（要拆旧链、建新链），期间电源键闪烁 + 胶囊 Loading。
        if (isRunning) {
            writeDiag("用户切换音频链路模式 → $m（引擎在跑，立即重启链路）")
            viewModelScope.launch {
                restartChainWithCurrentMode()
            }
        } else {
            writeDiag("用户切换音频链路模式 → $m（引擎未运行，下次启动生效）")
        }
    }

    /** 按当前 audio_mode 重建链路（模式切换时用）：先停干净，再走对应路径 */
    private suspend fun restartChainWithCurrentMode() {
        runCatching { stopCapture() }
            .onFailure { writeDiag("模式切换：停止失败 ${it.javaClass.simpleName}") }
        runCatching { loopbackProbe?.stop() }
        delay(600)
        if (isLoopbackMode()) {
            writeDiag("模式切换：重建 Loopback 链路")
            startLoopbackCapture()
        } else {
            writeDiag("模式切换：重建 Stellar+MP 链路")
            val req = projectionRequester
            if (req != null) {
                runCatching { req() }
                    .onFailure { writeDiag("模式切换：发起 MP 失败 ${it.javaClass.simpleName}") }
            } else {
                writeDiag("模式切换失败：UI 未注册授权入口")
            }
        }
    }

    /** 用户确认改用 Stellar+MP 平替模式：记下模式并启动 MP 链路 */
    fun confirmUseMpMode() {
        prefs.edit().putString(KEY_AUDIO_MODE, MODE_STELLAR_MP).apply()
        _audioMode.value = MODE_STELLAR_MP
        _loopbackUnsupported.value = null
        writeDiag("用户确认切换为 Stellar+MP 模式（audio_mode=$MODE_STELLAR_MP）")
        val req = projectionRequester
        if (req != null) {
            runCatching { req() }
                .onFailure { writeDiag("切换 MP 失败: ${it.javaClass.simpleName}: ${it.message}") }
        } else {
            writeDiag("切换 MP 失败：UI 未注册授权入口（projectionRequester=null）")
        }
    }

    private fun fallbackToMediaProjection(reason: String) {
        writeDiag("编排回退 → Stellar+MP（原因：$reason）")
        mpFallbackInProgress = true
        viewModelScope.launch {
            // **不要 engine.stop()**：引擎会被随后的 startCapture
            // 复用（prepare + startCapture），先 stop 会造成「引擎已停、capture 已开读」
            // 的错位 —— 表现正是 ok=true 但 inputLevel=0（有声无音效）。
            // 这里只撤 loopback 侧的 policy/录音与静音，让 MP 链路接管同一条引擎。
            runCatching { loopbackProbe?.stop() }
            runCatching { takeover.stopAndRollback() }
            delay(400)
            val req = projectionRequester
            if (req != null) {
                runCatching { req() }
                    .onFailure { writeDiag("回退 MP 发起授权失败: ${it.javaClass.simpleName}: ${it.message}") }
            } else {
                writeDiag("回退 MP 失败：UI 未注册授权入口（projectionRequester=null）")
            }
        }
    }

    fun startLoopbackCapture() {
        // ★ 复用既有链路：
        // Activity 被系统销毁后重新点图标 → ViewModel 重建、旧字段归零，
        // 但链路其实还活着（Binder 在 shell 服务、引擎是进程级单例）。
        // 旧逻辑会全量重建：engine.stop + 撤静音 + 重新注册 policy → 电源键闪、声音断。
        // ★ 2026-11-08 修「停止后再启动无音效」：
        // 原实现只看 holder 两字段非空就 return，但 holder 不反映引擎死没死。用户停止后
        // 链路未 detach（上游已修），或引擎因 error/CAPTURE_DEAD 自行停止时，这里会误判
        // 「已在运行」直接 return → 引擎永远不 prepare/start，且不报错。
        // 判据改为「链在手 且 引擎处于 Running/Preparing」才算可复用——保留 Preparing 是
        // 因为建链瞬间 engineState 可能比 holder 滞后几十毫秒（2026-11-07 的教训）。
        // 注意 Ready 不算可用：那只代表 DSP 图已建但尚未 start，复用它同样没声音。
        val st = engineState.value
        val engineUsable = st is EngineState.Running || st is EngineState.Preparing
        if (!mpFallbackInProgress && isLoopbackMode() && loopbackChain.isAlive && engineUsable) {
            writeDiag("复用既有 Loopback 链路（engineState=$st, chain.isAlive=true, 不重建）")
            _chainStarting.value = false
            _chainStage.value = null
            _loopbackProbeState.value = "已在运行（复用，未重建）"
            // 重新登记客户端死亡回调（旧 Binder 随旧进程/旧 VM 可能已失效）
            runCatching { loopbackChain.probe?.attachClient(loopbackClientBinder) }
            return
        }
        if (isLoopbackMode() && loopbackChain.isAlive) {
            // 链在手但引擎不在可用态：先清掉残留 daemon/record 再全量重建。
            // 不清理会撞「code=-2 验证令牌不匹配」（旧服务进程令牌与本次绑定不同），
            // 表现为「要点好几次才恢复」。
            writeDiag("链路在手但引擎不可用（engineState=$st）→ 清理残留后重建")
            runCatching { loopbackProbe?.stop() }
            loopbackChain.detach()
            takeover.loopbackRerouted = false
        }
        mpFallbackInProgress = false
        takeover.loopbackRerouted = false
        _chainStarting.value = true
        _chainStage.value = "建链中"
        // Loopback 也必须有自己的前台服务：否则息屏后进程是后台优先级，
        // 被 Doze/ROM 回收 → PCM 回调与 AAudio 输出同时消失（夜间断链的直接原因）。
        runCatching {
            context.startForegroundService(
                Intent(context, ForegroundDspService::class.java)
                    .putExtra(ForegroundDspService.EXTRA_KIND, ForegroundDspService.KIND_LOOPBACK),
            )
        }.onFailure { writeDiag("Loopback 前台服务启动失败: ${it.javaClass.simpleName}") }
        // 看门狗：任何异常路径都别把「启动中」卡死（按钮会一直闪且不可点）
        viewModelScope.launch {
            delay(15_000)
            if (_chainStarting.value && engineState.value !is EngineState.Preparing) {
                _chainStarting.value = false
            }
        }
        // 手动触发时**总是**尝试（用户点了就该试）；TTL 只用于提示当前处于已知失败期，
        // 自动启动路径会用 loopbackFailedRecently() 直接走 MP，避免每次白等 3s。
        if (loopbackFailedRecently()) {
            writeDiag("Loopback 处于失败标记期（24h 内），本次仍按手动触发尝试")
        }
        // 与前台服务的 headless 恢复互斥：建链期间不许另一路再绑（否则 code=-2 令牌冲突）
        loopbackChain.building = true
        _loopbackProbeState.value = "绑定 UserService…"
        val args = UserServiceArgs.Builder(LoopbackProbeUserService::class.java)
            .processNameSuffix("loopback_probe")
            // versionCode 必须随服务端代码变化递增——否则 Stellar 复用旧进程
            // （旧 ILoopbackProbe 无 startStream，绑定会报「验证令牌不匹配 code=-2」）
            .versionCode(52L)
            .serviceMode(ServiceMode.DAEMON)
            .build()
        StellarUserService.bindUserService(
            args,
            object : StellarUserService.ServiceCallback {
                override fun onServiceConnected(service: IBinder) {
                    val probe = ILoopbackProbe.Stub.asInterface(service)
                    loopbackProbe = probe
                    // 服务端托管静音：app 进程死亡 → 服务 linkToDeath 立即释放效果
                    runCatching { probe.attachClient(loopbackClientBinder) }
                    takeover.loopbackMuteBridge = object : TakeoverController.LoopbackMuteBridge {
                        override fun mute(sids: IntArray): Boolean {
                            val r = runCatching { probe.muteSessions(sids) }
                                .getOrElse { "throw ${it.javaClass.simpleName}: ${it.message}" }
                            if (!r.startsWith("ok")) {
                                // E4：把服务端返回原文落 diag，否则只看到「调用失败」无从定位
                                writeDiag("muteSessions 失败: $r sids=${sids.joinToString(",")}")
                            }
                            return r.startsWith("ok")
                        }

                        override fun releaseAll() {
                            runCatching { probe.unmuteAll() }
                        }
                    }
                    val source = StellarLoopbackSource(
                        probe = probe,
                        sampleRate = 48_000,
                        excludeUid = android.os.Process.myUid(),
                        onError = { msg -> writeDiag("Loopback源错误：$msg") },
                    )
                    viewModelScope.launch {
                        runCatching {
                            // 建链前必须做的两件清理（详见 docs/ADR.md ADR-02）：
                            // ① 停内录：MP 的 RENDER|LOOP_BACK policy 会独占 MEDIA 流，
                            //    两条链路互斥；② 回滚 appops 残留：它作用于流产生端，
                            //    先于 mix 存在时副本同样静音。
                            // 铁律：必须先建 mix 再 mute（否则 LOOP_BACK 复制到的就是静音）。
                            runCatching { engine.stop(StopReason.USER_REQUESTED) }
                            takeover.stopAndRollback()
                            delay(600)
                            // E1（2026-11-05 实验）：**先静音、后建链**。
                            // 现状是「建链(≈2s) → 轮询 → 等副本电平>0.01 → 才静音」，
                            // 用户能听到 2.5~4s 双声。DP 是会话级效果（不像 appops 作用在
                            // 流产生端），理论上先静音不会让副本失源；若成立双声窗口≈0.3s。
                            // 建链失败时下方 takeover.stopAndRollback 会回滚静音。
                            val preSids = activeMediaSids()
                            if (preSids.isNotEmpty()) {
                                // app 侧建效果（服务端建会 -3，见 preMuteLocal 注释）
                                val n = takeover.preMuteLocal(preSids)
                                writeDiag("预静音(建链前) sids=${preSids.joinToString(",")} 成功=$n")
                            } else {
                                writeDiag("预静音(建链前) 无活跃媒体会话，跳过")
                            }
                            // 16k 特权静音器（实验，默认关）：只有 allowPrivilegedPlaybackCapture
                            // 的 mix 才会把 BY_SYSTEM 流重路由进 submix，但框架限制该标志 ≤16kHz
                            // ——它只负责搬走原声、音质无关，48k 仍靠下面的 LOOP_BACK 复制。
                            // 本机 record 建不起来（白等 3s），复验时 setprop ...silencer16k 1。
                            val useSilencer = runCatching {
                                ProcessBuilder("getprop", "debug.sonicraft.silencer16k")
                                    .start().inputStream.bufferedReader()
                                    .use { it.readText().trim() } == "1"
                            }.getOrDefault(false)
                            if (useSilencer) {
                                val sil = runCatching {
                                    probe.startSilencer16k(android.os.Process.myUid())
                                }.getOrElse { "throw ${it.javaClass.simpleName}: ${it.message}" }
                                writeDiag("16k静音器: $sil")
                            }

                            engine.prepare(EngineMode.CaptureRelay, AudioFormat.STEREO_48K)
                            val ok = engine.startLoopback(source)
                            loopbackChain.building = false  // 建链关键段结束，放开互斥
                            if (ok) {
                                // 链建成 → 登记到进程级持有者，供 ViewModel 重建后复用
                                loopbackChain.attach(probe, source)
                                // 夜间保活：Loopback 也要写会话标记并挂 15min job，
                                // 否则 AlarmSentryReceiver / BootCompletedReceiver 第一道门就 return，
                                // 息屏被回收后不会有任何恢复尝试。
                                prefs.edit()
                                    .putBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, true)
                                    .putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, false)
                                    .apply()
                                _systemKilledUnacked.value = false
                                KeepAliveJobService.clearWarningNotification(context)
                                KeepAliveJobService.schedule(context)
                                // 关键：把用户当前 EQ/混响/宽度等参数下发到引擎。
                                // 漏掉这步会让 DSP 跑默认平直参数 —— 输出等于原声，
                                // 听起来就是「只有原声、没有音效」（2026-11-04 P1 实测）。
                                applyEqSettings()
                                // REROUTE 实验：我方 RENDER mix 注册后，已存在的
                                // track 不会被重路由（酷狗 deviceIds 仍指主输出、mix output
                                // 恒 Standby）。这里主动制造一次路由策略变更，看 APM 会不会
                                // 重新 getOutputForAttr → 命中我们的 mix。
                                runCatching {
                                    val r = probe.nudgeRouting()
                                    writeDiag("重路由触发: $r")
                                }.onFailure { writeDiag("重路由触发异常: ${it.javaClass.simpleName}") }
                                // ★ 立刻做重路由校验（2026-11-05 时序修正）：原先这步排在
                                // 「电平轮询 12×700ms」之后，导致冷启动最多白等 8.4s 才有音效。
                                // 冷启动时播放器的 track 早于我方 mix 就绪，必须靠 pause/play
                                // 重建才能被搬进 submix —— 放在最前面，音效 1~2s 内就能出来。
                                if (!mpFallbackInProgress) {
                                    _chainStage.value = "校验路由"
                                    runCatching {
                                        val rr = probe.ensureRerouted(android.os.Process.myUid())
                                        // ：**必须明确「已重路由」才算成立**；
                                        // skip（无播放器）/ fail 都不算 → 保留静音兜底。
                                        takeover.loopbackRerouted = rr.contains("已重路由")
                                        // 记录我方 submix 端口：轮询里据此判断“该会话是否已被
                                        // 重路由”，命中就释放静音（避免预静音把副本压成 0）
                                        Regex("""port=(\d+)""").find(rr)?.groupValues?.get(1)
                                            ?.toIntOrNull()?.let { takeover.loopbackSubmixPort = it }
                                        takeover.updateRouteSummary(
                                            when {
                                                rr.contains("已重路由") ->
                                                    "路由：port=${takeover.loopbackSubmixPort} · 命中"
                                                rr.startsWith("skip") ->
                                                    "路由：未判定（无活跃播放）"
                                                else -> "路由：未命中（走静音兜底）"
                                            },
                                        )
                                        writeDiag("重路由校验(建链后即触发): $rr")
                                    }.onFailure {
                                        writeDiag("重路由校验异常: ${it.javaClass.simpleName}")
                                    }
                                }
                                // 安全网：预静音已压掉原声，若副本恒 0 用户就是「完全无声」，
                                // 比双声更糟 → 复查仍无数据立即撤销预静音，回到原声直出。
                                // 冷启动 REROUTE 可能晚 2~3s 才生效，故用轮询判定（非单次）。
                                _chainStage.value = "收数据"
                                var lvlAfterStart = 0f
                                // ：判定窗口 12×700ms(8.4s) → 4×600ms(2.4s)。
                                // 数据晚到时不再阻塞 UI 状态，交给轮询/自动重建兜底。
                                for (i in 1..4) {
                                    delay(600)
                                    lvlAfterStart = lastInputLevel()
                                    if (lvlAfterStart >= 0.01f) break
                                }
                                // **必须有媒体在播才做「支持性」判定**：没有音源时电平必然为 0，
                                // 那不是「不支持 Loopback」，只是没东西可抓
                                val hasSource = activeMediaSids().isNotEmpty()
                                if (!hasSource) {
                                    writeDiag("建链后无活跃媒体播放 → 跳过『不支持』判定（有声音时再判）")
                                }
                                // **重建前复核**：重路由常在判定窗口末尾
                                // 才生效（port 校验与数据几乎同时到），直接重建会让整次启动
                                // 变成 12s + 27s。这里先等 1.2s 再读一次，数据到了就当成功。
                                if (hasSource && lvlAfterStart < 0.01f) {
                                    delay(1_200)
                                    val lvlRecheck = lastInputLevel()
                                    if (lvlRecheck >= 0.01f) {
                                        writeDiag("复核：数据稍后到达（inputLevel=$lvlRecheck）→ 不重建")
                                        clearLoopbackFailure()
                                        _loopbackUnsupported.value = null
                                    }
                                }
                                if (hasSource && lvlAfterStart < 0.01f && lastInputLevel() < 0.01f) {
                                    // **先取快照**：下面的 releaseLoopbackMutesNow() 会把
                                    // loopbackRerouted 重置为 false（那是给"引擎停止"用的），
                                    // 若在那之后判断，就永远走不到"跳过重建"分支 ——
                                    // 这正是"划掉 App 重开要起两轮"的真因（2026-11-06 实测：
                                    // 校验已 ok 重路由，却仍然重建第二条链）。
                                    val wasRerouted = takeover.loopbackRerouted
                                    takeover.releaseLoopbackMutesNow()
                                    writeDiag(
                                        "预静音撤销：建链后副本无数据（inputLevel=$lvlAfterStart），" +
                                            "已恢复原声避免全静音",
                                    )
                                    // ★ ：建链 ok 但拿不到数据 = 假成功
                                    // （record 绑上了但 mix 没收到流）。自动重建一次链路，
                                    // 60s 内只重试一次，避免死循环；仍失败则保持原声直出。
                                    val nowMs = System.currentTimeMillis()
                                    // **重路由已成立时不要重建**（2026-11-06 用户实测：
                                    // 自动加载后已有音效，几百毫秒后却又停一次再建一次，
                                    // 电源键闪灰 —— 就是这里误判“无数据”触发的重建）。
                                    if (wasRerouted || takeover.loopbackRerouted) {
                                        writeDiag("跳过自动重建：重路由已成立（轮询会确认数据）")
                                    } else if (nowMs - lastChainRetryAt > 60_000L) {
                                        lastChainRetryAt = nowMs
                                        writeDiag("链路无数据（有音源）→ 自动重建一次（P0-3）")
                                        runCatching { loopbackProbe?.stop() }
                                        delay(800)
                                        startLoopbackCapture()
                                    }
                                    // **注意**：这里只做「防全静音」的回滚，
                                    // **绝不能据此判定设备不支持** —— 电平为 0 可能只是没音源、
                                    // 或 REROUTE 还没生效。能力判定只看通路是否建成（见上方 ok）。
                                } else {
                                    writeDiag(
                                        "预静音保留：建链后副本电平=$lvlAfterStart（进入正常接管）",
                                    )
                                    clearLoopbackFailure()
                                    // 数据确实到了 → 撤掉可能已弹出的「不支持 Loopback」提示
                                    // （冷启动时数据晚到会先误判，2026-11-05 实测）
                                    _loopbackUnsupported.value = null
                                }
                                // 重路由校验已在建链后立即执行（见上方），此处不重复，
                                // 否则会二次 pause/play 打断播放。
                                // 原声压制交接管层的 DP 轨（ADR-02：appops 连坐副本、
                                // RENDER 在本机不成立）。回退 MP 时不能起 loopback 接管，
                                // 否则会把 appops 改回 allow → 双声。
                                if (!mpFallbackInProgress) {
                                    takeover.start(_autoTakeoverEnabled.value, loopback = true)
                                }
                            } else {
                                // 建链失败：立刻撤销预静音，否则「原声被压 + 没有副本」= 全静音
                                runCatching { takeover.releaseLoopbackMutesNow() }
                                writeDiag("建链失败：已撤销预静音")
                                // 同样不做热回退：告知用户并提供平替模式，由用户确认
                                markLoopbackFailed()
                                _loopbackUnsupported.value =
                                    "当前设备无法建立 Loopback 链路（Stellar 未授权或本机策略拒绝）。\n\n" +
                                        "可改用 Stellar + MediaProjection 平替模式（需要一次录屏授权）。"
                            }
                            // 无论成败都把服务端 trail 落盘：mix 参数（allowPrivilegedPlaybackCapture
                            // / setTargetMixRole / route flag）与 record 重试细节只在这里可见
                            runCatching {
                                val t = probe.trail
                                if (!t.isNullOrBlank()) writeDiag("Loopback trail(建链):\n$t")
                            }.onFailure { writeDiag("trail 读取失败: ${it.message}") }
                            _loopbackProbeState.value =
                                if (ok) "Loopback 引擎运行中（P2 接管已开）" else "Loopback 启动失败"
                            writeDiag("LoopbackCapture：ok=$ok takeoverLoopback=true state=${engineState.value}")
                            _chainStarting.value = false
                            // 通路建成即视为能力正常 → 清掉历史上的失败标记，
                            // 避免日志长期显示「处于失败标记期」
                            if (ok) clearLoopbackFailure()
                        }.onFailure {
                            _chainStarting.value = false
                            _loopbackProbeState.value =
                                "异常: ${it.javaClass.simpleName}: ${it.message}"
                            writeDiag("LoopbackCapture 异常：${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                }

                override fun onServiceDisconnected() {
                    loopbackProbe = null
                    takeover.loopbackMuteBridge = null
                    _loopbackProbeState.value = "服务断开（Stellar server 重启？）"
                }

                override fun onServiceStartFailed(errorCode: Int, message: String) {
                    loopbackChain.building = false  // 绑定失败也要放开互斥，否则 headless 恢复会一直等
                    _chainStarting.value = false
                    _loopbackProbeState.value = "启动失败 code=$errorCode $message"
                    writeDiag("LoopbackCapture：启动失败 code=$errorCode $message")
                    // code=-2 = 验证令牌不匹配：Stellar 复用了上一版/残留的 loopback_probe
                    // 进程（今天反复重装必然出现）。自愈：用 shell 杀进程后重试一次；
                    // 60s 内只自愈一次，避免失败循环。
                    if (errorCode == -2 &&
                        System.currentTimeMillis() - lastTokenRecoveryAt > 60_000L
                    ) {
                        lastTokenRecoveryAt = System.currentTimeMillis()
                        writeDiag("旧服务进程（令牌不匹配）→ 清理 loopback_probe 后重试一次")
                        viewModelScope.launch {
                            runCatching { privileged.runCommand("pkill", "-f", "loopback_probe") }
                            delay(1_500)
                            startLoopbackCapture()
                        }
                    }
                }
            },
        )
    }

    fun startLoopbackProbe() {
        _loopbackProbeState.value = "绑定 UserService…"
        val args = UserServiceArgs.Builder(LoopbackProbeUserService::class.java)
            .processNameSuffix("loopback_probe")
            // versionCode 递增才触发 UserService 重启（Stellar 复用旧进程，否则跑旧类）
            .versionCode(52L)
            .serviceMode(ServiceMode.DAEMON)
            .build()
        StellarUserService.bindUserService(
            args,
            object : StellarUserService.ServiceCallback {
                override fun onServiceConnected(service: IBinder) {
                    val probe = ILoopbackProbe.Stub.asInterface(service)
                    // 诊断探针**不碰**进程级链路句柄：这里只是临时探测，
                    // 写进 holder 会在断开时误清掉正在运行的主链路
                    val info = runCatching {
                        val uid = probe.serviceUid
                        // 传本 app uid 供 mix 排除——否则自家 AAudio 输出会被 LOOP_BACK
                        // 进 mix，record 失败即形成黑洞卡死（2026-11-04 事故）
                        val st = probe.start(48_000, android.os.Process.myUid())
                        "uid=$uid $st"
                    }.getOrElse { "调用失败: ${it.javaClass.simpleName}: ${it.message}" }
                    _loopbackProbeState.value = info
                    val trail = runCatching { probe.trail }.getOrNull()
                    _loopbackProbeTrail.value = trail
                    writeDiag("Loopback探针：$info")
                    // trail 全量落盘（本机 logcat 不可用，否则失败时无任何定位信息）
                    trail?.let { writeDiag("Loopback trail:\n$it") }
                    loopbackPollJob?.cancel()
                    loopbackPollJob = viewModelScope.launch {
                        while (isActive) {
                            _loopbackProbeLevel.value =
                                runCatching { probe.level }.getOrDefault(0f)
                            delay(300)
                        }
                    }
                }

                override fun onServiceDisconnected() {
                    loopbackPollJob?.cancel()
                    // 同上：诊断探针断开不影响主链路句柄
                    _loopbackProbeState.value = "服务断开（Stellar server 重启？）"
                }

                override fun onServiceStartFailed(errorCode: Int, message: String) {
                    _loopbackProbeState.value = "启动失败 code=$errorCode $message"
                    writeDiag("Loopback探针：启动失败 code=$errorCode $message")
                }
            },
        )
    }

    fun stopLoopbackProbe() {
        loopbackPollJob?.cancel()
        runCatching { loopbackProbe?.stop() }
        _loopbackProbeLevel.value = 0f
        _loopbackProbeState.value = "已停止"
    }

    /**
     * 全机第三方 APP 投影 Capture 策略扫描（2026-11-04 诊断页数据源）。
     * 公开 AudioManager.getAllowedCapturePolicy() 只能读自身；per-uid policy 藏在
     * AudioService（dumpsys audio「allowed capture policies」段，只列非默认者，
     * 缺省=BY_ALL 可抓）——走 Stellar 特权通道跑 dumpsys。酷狗实锤 BY_SYSTEM
     * （内录恒零=只能自动直出名单）。返回 (包名, uid, policy) 按 包名排序，
     * policy：1=BY_ALL / 2=BY_SYSTEM / 3=BY_NONE。
     */
    fun scanCapturePolicies(): CapturePolicyScan {
        val st = privileged.state.value
        if (!st.serviceRunning) {
            writeDiag("Capture扫描：Stellar 未连接（managerInstalled=${st.managerInstalled} granted=${st.permissionGranted}）")
            return CapturePolicyScan(emptyList(), "Stellar 特权未连接：先开内录（或检查 Stellar 授权）")
        }
        val dump = privileged.runCommand("dumpsys", "audio")
        if (dump.exitCode != 0) {
            writeDiag("Capture扫描：dumpsys exit=${dump.exitCode} err=${dump.stderr.take(60)}")
            return CapturePolicyScan(
                emptyList(),
                "dumpsys audio 失败 exit=${dump.exitCode} ${dump.stderr.trim().take(60)}",
            )
        }
        val policies = Regex("""uid:\s*(\d+)\s+policy:\s*(\d+)""")
            .findAll(dump.stdout)
            .associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        // 包→uid 映射：优先 shell 侧 `pm list packages -U`。2026-11-04 实锤：本机 ZTE
        // ROM 上 App 侧 getInstalledPackages(0) 即使 QUERY_ALL_PACKAGES granted=true 仍
        // 返回 0（诊断页首版「点扫描出 0」根因），shell 身份直查 PMS 不受 Android 11+
        // 包可见性影响，447 包全量（adb 实测格式 package:xxx uid:10xxx）。
        val uidPkg = LinkedHashMap<Int, String>()
        var pkgSource = "pm"
        val listCmd = privileged.runCommand("pm", "list", "packages", "-U")
        if (listCmd.exitCode == 0) {
            Regex("""package:(\S+)\s+uid:(\d+)""").findAll(listCmd.stdout).forEach { m ->
                val uid = m.groupValues[2].toIntOrNull() ?: return@forEach
                val pkg = m.groupValues[1]
                if (uid >= android.os.Process.FIRST_APPLICATION_UID &&
                    uid != android.os.Process.myUid() &&
                    !uidPkg.containsKey(uid)
                ) {
                    uidPkg[uid] = pkg
                }
            }
        }
        if (uidPkg.isEmpty()) {
            pkgSource = "pmapi"
            runCatching {
                context.packageManager.getInstalledApplications(0).forEach { ai ->
                    if (ai.uid >= android.os.Process.FIRST_APPLICATION_UID &&
                        ai.uid != android.os.Process.myUid() &&
                        !uidPkg.containsKey(ai.uid)
                    ) {
                        uidPkg[ai.uid] = ai.packageName
                    }
                }
            }
            runCatching {
                context.packageManager.getInstalledPackages(0).forEach { info ->
                    val ai = info.applicationInfo ?: return@forEach
                    if (!uidPkg.containsKey(ai.uid)) uidPkg[ai.uid] = ai.packageName
                }
            }
        }
        writeDiag(
            "Capture扫描：源=$pkgSource pm exit=${listCmd.exitCode} dumpsys=${dump.stdout.length}字节 " +
                "policies=${policies.entries.joinToString(",") { "${it.key}=${it.value}" }} 包数=${uidPkg.size}",
        )
        if (uidPkg.isEmpty()) {
            return CapturePolicyScan(
                emptyList(),
                "包列表为空——pm list exit=${listCmd.exitCode} 与 PackageManager 均返回 0",
            )
        }
        return CapturePolicyScan(
            uidPkg.map { (uid, pkg) -> Triple(pkg, uid, policies[uid] ?: 1) }
                .sortedBy { it.first },
            null,
        )
    }

    private fun runAppops(pkg: String, mode: String): Boolean {
        val result = privileged.runCommand("cmd", "appops", "set", pkg, "PLAY_AUDIO", mode)
        // （2026-11-03 审计）：手动轨原本只看 exitCode——exitCode 0 但 ROM 未生效的
        // 假成功无检测（自动轨有 verifyIgnored 回读，手动轨却裸奔），且本机 logcat 恒空
        // = 零取证。补回读验证（与自动轨同款）+ 失败落 diag。
        val setOk = result.exitCode == 0
        val verified = setOk && runCatching {
            val v = privileged.runCommand("appops", "get", pkg, "PLAY_AUDIO")
            v.exitCode == 0 && Regex("""PLAY_AUDIO:\s*$mode\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(v.stdout)
        }.getOrDefault(false)
        if (!verified) {
            val why = if (setOk) "set 返回 0 但回读不符（ROM 假成功）" else "set 失败: ${result.stderr.trim()}"
            SonicraftLog.w(TAG, "appops $pkg → $mode 失败: $why")
            writeDiag("手动接管 appops $pkg → $mode 未生效: $why")
        }
        return verified
    }

    /** 设置噪声门限（0 = 禁用），运行时实时生效 */
    fun setGateThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0f, 0.05f)
        _gateThreshold.value = clamped
        prefs.edit().putFloat(KEY_GATE_THRESHOLD, clamped).apply()
        engine.setNoiseGateThreshold(clamped)
    }

    /** 测试模式：引擎输出替换为 440Hz 正弦（仅运行中可用，不持久化） */
    fun setTestTone(enabled: Boolean) {
        engine.setTestTone(enabled)
    }

    // ---- 诊断：探针文件 UI 化（测试机 logcat 恒空，自落盘诊断是唯一取证通道） ----
    /** 诊断快照：三个探针文件尾读摘要 */
    data class DiagSnapshot(
        val streamErrorCount: Int,   // 尾读范围内 stream error（输出流断流重建）行数
        val rebuildRecent: String,   // 最近 3 条 stream error 记录
        val captureLatest: String,   // capture_diag 最新一行（捕获累计统计）
        val watermarkLatest: String, // watermark 最新一行（水位/欠载累计）
        val generatedAt: Long,
    )

    private val _diag = MutableStateFlow<DiagSnapshot?>(null)
    val diag: StateFlow<DiagSnapshot?> = _diag.asStateFlow()

    /** 读探针文件尾部刷新诊断（IO 协程；capture_diag 可达 10MB，尾读 256KB） */
    // ---- M6.1 音频取证：双端抓 PCM（输入=捕获原声 / 输出=处理回放）----
    private val _audioCaptureMsg = MutableStateFlow<String?>(null)
    val audioCaptureMsg: StateFlow<String?> = _audioCaptureMsg.asStateFlow()

    /** 抓 seconds 秒「输入/输出」音频对到 files/crash/（audio_in_*.f32 与 audio_out_*.f32），
     *  宿主机对比分析伪影归属：pre-echo/birdies/带宽截断/噪声底——音源自带 vs 链路引入。 */
    fun captureAudioSample(seconds: Int = 30) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = DiagDirs.crashDir
            val ok = engine.dumpAudio(dir, seconds)
            _audioCaptureMsg.value = if (ok) {
                "已开始抓 ${seconds}s，完成后 audio_in_*.f32 / audio_out_*.f32 落在 crash 目录"
            } else {
                "启动失败：引擎未运行或文件打开失败"
            }
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = java.io.File(DiagDirs.crashDir)
            fun tailLines(name: String, maxLines: Int): List<String> {
                val f = java.io.File(dir, name)
                if (!f.exists()) return emptyList()
                return runCatching {
                    java.io.RandomAccessFile(f, "r").use { raf ->
                        val len = raf.length()
                        val start = (len - 256L * 1024L).coerceAtLeast(0L)
                        raf.seek(start)
                        val bytes = ByteArray((len - start).toInt())
                        raf.readFully(bytes)
                        String(bytes, Charsets.UTF_8)
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .let { if (start > 0L) it.drop(1) else it }  // 首行可能从中间截断
                            .toList()
                            .takeLast(maxLines)
                    }
                }.getOrDefault(emptyList())
            }
            val errors = tailLines("autostart_diag.txt", 400).filter { "stream error" in it }
            val capture = tailLines("capture_diag.txt", 2)
            val watermark = tailLines("watermark_log.txt", 2)
            _diag.value = DiagSnapshot(
                streamErrorCount = errors.size,
                rebuildRecent = errors.takeLast(3).joinToString("\n"),
                captureLatest = capture.lastOrNull() ?: "（无记录）",
                watermarkLatest = watermark.lastOrNull() ?: "（无记录）",
                generatedAt = System.currentTimeMillis(),
            )
        }
    }

    // ---- 参量均衡器控制 ----

    /** 音效总开关（A/B 标尺）。bypass = !enabled：关时节点透传，听到原声副本 */
    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
        applyEqGates()
    }

    /**
     * 组总闸：同时门控 PEQ 与 AutoEq 两个节点（与「低频管家」组闸一致）。
     * 子开关的"记忆值"不被改写 —— 关总闸再开回来，恢复你原来的选择。
     */
    fun setEqSectionEnabled(enabled: Boolean) {
        _eqSectionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_EQ_SECTION_ENABLED, enabled).apply()
        applyEqGates()
    }

    /** 实际生效值 = 组总闸 && 子开关。UI 的开关显示与节点旁路都用它。 */
    private fun applyEqGates() {
        val section = _eqSectionEnabled.value
        engine.setBypass(AudioEngine.EQ_NODE_INDEX, !(section && _eqEnabled.value))
        engine.setBypass(AudioEngine.AUTO_EQ_NODE_INDEX, !(section && _autoEqEnabled.value))
    }

    /** 低音增益（dB）：实时下发到 EQ band 0，节点内部做 30ms 平滑斜坡；联动多段状态 */
    fun setBassGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        _bassGain.value = clamped
        prefs.edit().putFloat(KEY_BASS_GAIN, clamped).apply()
        engine.setParam(AudioEngine.EQ_NODE_INDEX, BAND_BASS, clamped)
        // 反向联动：低音快捷条同步多段滑条墙（band0）
        _eqBandGains.value = _eqBandGains.value.toMutableList().also { it[0] = clamped }
        persistEqBands(_eqMode.value, _eqBandGains.value)
    }

    /** 高音增益（dB）：实时下发到 EQ band 9（多段模式下为末段）；联动多段状态 */
    fun setTrebleGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        _trebleGain.value = clamped
        prefs.edit().putFloat(KEY_TREBLE_GAIN, clamped).apply()
        engine.setParam(AudioEngine.EQ_NODE_INDEX, BAND_TREBLE, clamped)
        // 反向联动：高音快捷条同步多段滑条墙（末段）
        _eqBandGains.value = _eqBandGains.value.toMutableList().also {
            it[it.size - 1] = clamped
        }
        persistEqBands(_eqMode.value, _eqBandGains.value)
    }

    /** 限幅器开关：bypass = !enabled。关时节点透传（ceiling 包络仍推进，无解除回声） */
    fun setLimiterEnabled(enabled: Boolean) {
        _limiterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_LIMITER_ENABLED, enabled).apply()
        engine.setBypass(AudioEngine.LIMITER_NODE_INDEX, !enabled)
    }

    /** 自定义脚本效果器开关（2026-11-12 原型）：bypass = !enabled。 */
    /** 把某槽位的 40 个参数从 prefs 重新灌入 native。
     *  用途：开关打开 / 引擎重建后兜底，避免 native 侧 params_ 仍是初值 0
     *  （gain 类脚本 slider=0 会把信号乘成 0 → "总闸一开就静音"）。 */
    private fun replayLaneParams(lane: Int) {
        val node = AudioEngine.SCRIPT_EFFECT_NODE_INDEX
        fun k(base: String) = if (lane == 0) base else "${base}_$lane"
        val base = lane * 64
        for (i in 0 until 8) {
            engine.setParam(node, base + i, prefs.getFloat(k("script_slider_$i"), 0.5f))
            engine.setParam(node, base + 8 + i, prefs.getFloat(k("script_toggle_$i"), 0f))
            engine.setParam(node, base + 16 + i, prefs.getInt(k("script_select_$i"), 0).toFloat())
            engine.setParam(node, base + 32 + i, prefs.getInt(k("script_list_$i"), 0).toFloat())
        }
    }

    fun setScriptEffectEnabled(enabled: Boolean) {
        _scriptEffectEnabled.value = enabled
        prefs.edit().putBoolean("script_effect_enabled", enabled).apply()
        engine.setBypass(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, !enabled)
        if (enabled) for (l in 0 until 3) replayLaneParams(l)
    }

    /** 当前编辑的脚本 lane（0..2）。 */
    private val _scriptLane = MutableStateFlow(prefs.getInt("script_lane", 0).coerceIn(0, 2))
    val scriptLane: StateFlow<Int> = _scriptLane.asStateFlow()

    /** 当前 lane 的启用状态（关闭时该 lane 直接透传）。 */
    private val _scriptLaneOn = MutableStateFlow(prefs.getBoolean("script_lane_on_0", true))
    val scriptLaneOn: StateFlow<Boolean> = _scriptLaneOn.asStateFlow()

    /** 三个槽位的启用状态（机架页槽位行用）。 */
    private val _scriptLaneOnMap = MutableStateFlow(
        BooleanArray(3) { prefs.getBoolean("script_lane_on_$it", true) },
    )
    val scriptLaneOnMap: StateFlow<BooleanArray> = _scriptLaneOnMap.asStateFlow()

    /** 三个槽位的脚本摘要（首行注释 / desc / 「未选择脚本」）。 */
    private val _scriptLaneLabels = MutableStateFlow(List(3) { laneLabelOf(it) })
    val scriptLaneLabels: StateFlow<List<String>> = _scriptLaneLabels.asStateFlow()

    /** 三个槽位的编译状态（空 = 通过；非空 = 错误串，用于槽位行红色呼吸提示）。 */
    private val _scriptLaneErrors = MutableStateFlow(List(3) { laneErrorOf(it) })
    val scriptLaneErrors: StateFlow<List<String>> = _scriptLaneErrors.asStateFlow()

    /** 机架级干湿（0 = 原来声，1 = 全效果，默认 1）。 */
    private val _scriptRackWet = MutableStateFlow(prefs.getFloat("script_rack_wet", 1f))
    val scriptRackWet: StateFlow<Float> = _scriptRackWet.asStateFlow()

    /** 设置机架干湿并下发。 */
    fun setScriptRackWet(wet: Float) {
        val w = wet.coerceIn(0f, 1f)
        prefs.edit().putFloat("script_rack_wet", w).apply()
        engine.setScriptRackWet(w)
        _scriptRackWet.value = w
    }

    /** 槽位处理顺序（拖拽换序）：列表内容是 lane 索引的排列。 */
    private val _scriptLaneOrder = MutableStateFlow(readLaneOrder())
    val scriptLaneOrder: StateFlow<List<Int>> = _scriptLaneOrder.asStateFlow()

    private fun readLaneOrder(): List<Int> {
        val raw = prefs.getString("script_lane_order", "0,1,2") ?: "0,1,2"
        val v = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (v.size == 3 && v.toSet() == setOf(0, 1, 2)) v else listOf(0, 1, 2)
    }

    /** 拖拽换序：把「位置 from」的槽位移到「位置 to」。 */
    fun moveSlot(fromPos: Int, toPos: Int) {
        val cur = _scriptLaneOrder.value.toMutableList()
        if (fromPos !in cur.indices || toPos !in cur.indices || fromPos == toPos) return
        val lane = cur.removeAt(fromPos)
        cur.add(toPos, lane)
        prefs.edit().putString("script_lane_order", cur.joinToString(",")).apply()
        _scriptLaneOrder.value = cur
        engine.setScriptLaneOrder(cur[0], cur[1], cur[2])
    }

    /** 机架页进入时调用：重算三槽位的摘要 / 启用态 / 编译状态（此时引擎已建好）。 */
    fun refreshRackMeta() {
        refreshLaneMeta()
        startScriptRmsProbe()  // 幂等：探针随页面进入启动
    }

    // ---- 脚本机架 RMS 探针：不进 UI，每秒写一行到 files/crash/script_rms.txt ----
    private val _scriptRms = MutableStateFlow(FloatArray(12))
    val scriptRms: StateFlow<FloatArray> = _scriptRms.asStateFlow()
    private var rmsProbeStarted = false
    // 引擎建图完成前的 setter 是空操作 -> 用探针回读守卫，必要时重放
    private var lanesReplayed = false
    private var replayAttempts = 0

    private fun startScriptRmsProbe() {
        if (rmsProbeStarted) return
        rmsProbeStarted = true
        viewModelScope.launch {
            val dir = com.jiangjie.Sonicraft.core.common.DiagDirs.crashDir
            val f = java.io.File(dir, "script_rms.txt")
            runCatching { if (f.exists()) f.delete() }
            fun fmt(v: Float) = String.format(java.util.Locale.US, "%.4f", v)
            while (true) {
                kotlinx.coroutines.delay(1000)
                val buf = FloatArray(12)
                if (!engine.scriptRmsLevels(buf)) continue
                _scriptRms.value = buf

                // 守卫：节点还没建好时 setter 全被丢弃（p0/par/wet 停在默认值）。
                // 用回读值判断是否真的灌进去了，没进去就重放（最多 20 次，约 20 秒）。
                if (!lanesReplayed && replayAttempts < 20) {
                    val wantSlider = prefs.getFloat("script_slider_0", 0.5f)
                    if (kotlin.math.abs(buf[8] - wantSlider) < 1e-6f) {
                        lanesReplayed = true
                    } else {
                        replayAttempts++
                        replayScriptLanes()
                    }
                }
                runCatching {
                    f.appendText(
                        (System.currentTimeMillis().toString() +
                            " in=" + fmt(buf[0]) + " out=" + fmt(buf[1]) +
                            " l0=" + fmt(buf[2]) + ">" + fmt(buf[3]) +
                            " l1=" + fmt(buf[4]) + ">" + fmt(buf[5]) +
                            " l2=" + fmt(buf[6]) + ">" + fmt(buf[7]) +
                            " p0=" + fmt(buf[8]) + " on=" + fmt(buf[9]) +
                            " par=" + fmt(buf[10]) + " wet=" + fmt(buf[11]) +
                            " lat=" + engine.scriptLatency() + "\n"),
                    )
                }
            }
        }
    }

    /** 三个槽位的拓扑：true = 并联（取机架输入干声副本，按 wet 累加）。 */
    private val _scriptLaneParallel = MutableStateFlow(
        BooleanArray(3) { prefs.getBoolean("script_lane_parallel_$it", false) },
    )
    val scriptLaneParallel: StateFlow<BooleanArray> = _scriptLaneParallel.asStateFlow()

    /** 三个槽位的并联混合比例（默认 1/3，防止叠加过载）。 */
    private val _scriptLaneWet = MutableStateFlow(
        FloatArray(3) { prefs.getFloat("script_lane_wet_$it", 1f / 3f) },
    )
    val scriptLaneWet: StateFlow<FloatArray> = _scriptLaneWet.asStateFlow()

    /** 切换某槽位拓扑（串/并）。 */
    fun setScriptLaneParallel(lane: Int, parallel: Boolean) {
        if (lane < 0 || lane >= 3) return
        prefs.edit().putBoolean("script_lane_parallel_$lane", parallel).apply()
        engine.setScriptLaneParallel(lane, parallel)
        _scriptLaneParallel.value =
            BooleanArray(3) { prefs.getBoolean("script_lane_parallel_$it", false) }
    }

    /** 自定义脚本库（prefs 单字符串：条目用 \\u0002 分隔，条目内 name/code 用 \\u0001 分隔）。 */
    private val _customScripts = MutableStateFlow(readCustomScripts())
    val customScripts: StateFlow<List<ScriptSample>> = _customScripts.asStateFlow()

    private fun readCustomScripts(): List<ScriptSample> {
        val raw = prefs.getString("script_custom_library", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\u0002').mapNotNull { entry ->
            val parts = entry.split('\u0001')
            if (parts.size < 2) null else ScriptSample(parts[0], parts[1])
        }
    }

    /** 把当前槽位脚本存入脚本库（名字取首行注释 / desc，缺省「自定义脚本 N」）。 */
    fun saveCurrentToLibrary(): String? {
        val lane = _scriptLane.value
        val key = if (lane == 0) "script_effect_source" else "script_effect_source_$lane"
        val src = prefs.getString(key, "") ?: ""
        if (src.isBlank()) return null
        var title = ""
        for (raw in src.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("//")) {
                title = t.removePrefix("//").trim()
                break
            }
            if (t.startsWith("desc:")) {
                title = t.removePrefix("desc:").trim()
                break
            }
            break
        }
        if (title.isBlank()) title = "自定义脚本 ${_customScripts.value.size + 1}"
        val entry = title + '\u0001' + src
        val old = prefs.getString("script_custom_library", "") ?: ""
        val merged = if (old.isBlank()) entry else old + '\u0002' + entry
        prefs.edit().putString("script_custom_library", merged).apply()
        _customScripts.value = readCustomScripts()
        return title
    }

    /** 批量删除自定义脚本（按序号集合）。 */
    fun deleteCustoms(indices: Set<Int>) {
        if (indices.isEmpty()) return
        val raw = prefs.getString("script_custom_library", "") ?: ""
        if (raw.isBlank()) return
        val kept = raw.split('\u0002').filterIndexed { i, _ -> i !in indices }
        prefs.edit().putString("script_custom_library", kept.joinToString("\u0002")).apply()
        _customScripts.value = readCustomScripts()
    }

    /** 按序号删除自定义脚本（同名条目只删命中那条）。 */
    fun deleteCustomAt(index: Int) {
        val raw = prefs.getString("script_custom_library", "") ?: ""
        if (raw.isBlank()) return
        val entries = raw.split('\u0002')
        if (index < 0 || index >= entries.size) return
        val kept = entries.filterIndexed { i, _ -> i != index }
        prefs.edit().putString("script_custom_library", kept.joinToString("\u0002")).apply()
        _customScripts.value = readCustomScripts()
    }

    /** 从脚本库删除一条自定义脚本（内置示例不可删）。 */
    fun deleteCustomScript(title: String) {
        val raw = prefs.getString("script_custom_library", "") ?: ""
        if (raw.isBlank()) return
        val kept = raw.split('\u0002').filter { entry ->
            val parts = entry.split('\u0001')
            parts.isEmpty() || parts[0] != title
        }
        prefs.edit().putString("script_custom_library", kept.joinToString("\u0002")).apply()
        _customScripts.value = readCustomScripts()
    }

    /** 设置某槽位并联混合比例（0..2）。 */
    fun setScriptLaneWet(lane: Int, wet: Float) {
        if (lane < 0 || lane >= 3) return
        val w = wet.coerceIn(0f, 2f)
        prefs.edit().putFloat("script_lane_wet_$lane", w).apply()
        engine.setScriptLaneWet(lane, w)
        _scriptLaneWet.value = FloatArray(3) { prefs.getFloat("script_lane_wet_$it", 1f / 3f) }
    }

    private fun laneErrorOf(lane: Int): String {
        val key = if (lane == 0) "script_effect_source" else "script_effect_source_$lane"
        val src = prefs.getString(key, "") ?: ""
        if (src.isBlank()) return ""
        return engine.checkScript(src)
    }

    /** 某槽位当前脚本是否就是这段源码（脚本库列表高亮用）。 */
    fun laneScriptEquals(lane: Int, code: String): Boolean {
        val key = if (lane == 0) "script_effect_source" else "script_effect_source_$lane"
        return (prefs.getString(key, "") ?: "").trim() == code.trim()
    }

    /** 手工重命名槽位显示名（空 = 恢复自动摘要：首行注释 / desc）。 */
    fun renameScriptLane(lane: Int, name: String) {
        if (lane < 0 || lane >= 3) return
        prefs.edit().putString("script_lane_label_$lane", name.trim().take(24)).apply()
        refreshLaneMeta()
    }

    private fun laneLabelOf(lane: Int): String {
        // 用户手工命名优先
        val custom = prefs.getString("script_lane_label_$lane", "") ?: ""
        if (custom.isNotBlank()) return custom
        val key = if (lane == 0) "script_effect_source" else "script_effect_source_$lane"
        val src = prefs.getString(key, "") ?: ""
        if (src.isBlank()) return "未选择脚本"
        for (raw in src.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("//")) {
                val s = t.removePrefix("//").trim()
                if (s.isNotEmpty()) return s.take(28)
                continue
            }
            if (t.startsWith("desc:")) return t.removePrefix("desc:").trim().take(28)
            break
        }
        return "自定义脚本"
    }

    /** 三个槽位的脚本文本（脚本库「选中态」用，避免直接读 prefs 不同步）。 */
    private val _scriptLaneSources = MutableStateFlow(
        List(3) {
            val k = if (it == 0) "script_effect_source" else "script_effect_source_$it"
            prefs.getString(k, "") ?: ""
        },
    )
    val scriptLaneSources: StateFlow<List<String>> = _scriptLaneSources.asStateFlow()

    /** 刷新槽位摘要与启用状态（脚本变更 / 启停 / 切槽位后调用）。 */
    private fun refreshLaneMeta() {
        _scriptLaneLabels.value = List(3) { laneLabelOf(it) }
        _scriptLaneErrors.value = List(3) { laneErrorOf(it) }
        _scriptLaneOnMap.value = BooleanArray(3) { prefs.getBoolean("script_lane_on_$it", true) }
        _scriptLaneSources.value = List(3) {
            val k = if (it == 0) "script_effect_source" else "script_effect_source_$it"
            prefs.getString(k, "") ?: ""
        }
    }

    /** 启停指定 lane（持久化；启用时把该 lane 的脚本重新下发一次）。 */
    fun setScriptLaneOn(lane: Int, on: Boolean) {
        if (lane < 0 || lane >= 3) return
        if (on) {
            // 脚本编译不通过时不允许启用（否则会跑上一版脚本，误导用户）
            val key = if (lane == 0) "script_effect_source" else "script_effect_source_$lane"
            val src = prefs.getString(key, "") ?: ""
            if (src.isNotBlank() && engine.checkScript(src).isNotBlank()) return
        }
        prefs.edit().putBoolean("script_lane_on_$lane", on).apply()
        refreshLaneMeta()
        engine.setScriptLaneEnabled(lane, on)
        if (on) replayLaneParams(lane)
        if (on) {
            val src = prefs.getString(
                if (lane == 0) "script_effect_source" else "script_effect_source_$lane",
                "",
            ) ?: ""
            if (src.isNotBlank()) engine.setScriptSource(src, lane)
        }
        if (lane == _scriptLane.value) {
            _scriptLaneOn.value = on
            refreshScriptSliderMask()
        }
    }

    /** 引擎重建后重放三条 lane（脚本 + 启用状态 + 参数）。 */
    private fun replayScriptLanes() {
        val node = AudioEngine.SCRIPT_EFFECT_NODE_INDEX
        val ord = readLaneOrder()
        engine.setScriptLaneOrder(ord[0], ord[1], ord[2])
        // 干湿与槽位无关：即使三个槽位都空也要下发
        engine.setScriptRackWet(prefs.getFloat("script_rack_wet", 1f))
        for (lane in 0 until 3) {
            fun k(base: String) = if (lane == 0) base else "${base}_$lane"
            val src = prefs.getString(k("script_effect_source"), "") ?: ""
            if (src.isBlank()) continue
            // 拓扑与并联比例先恢复（与脚本顺序无关）
            engine.setScriptLaneParallel(lane, prefs.getBoolean("script_lane_parallel_$lane", false))
            engine.setScriptLaneWet(lane, prefs.getFloat("script_lane_wet_$lane", 1f / 3f))

            // 启动恢复：编译不通过的槽位一律按旁路处理（不跑旧脚本）
            val bad = engine.checkScript(src).isNotBlank()
            val wantOn = prefs.getBoolean("script_lane_on_$lane", true) && !bad
            engine.setScriptLaneEnabled(lane, wantOn)
            // 同上：编译失败不落盘，只影响本次运行的启停
            engine.setScriptSource(src, lane)
            val base = lane * 64
            for (i in 0 until 8) {
                engine.setParam(node, base + i, prefs.getFloat(k("script_slider_$i"), 0.5f))
                engine.setParam(node, base + 8 + i, prefs.getFloat(k("script_toggle_$i"), 0f))
                engine.setParam(
                    node, base + 16 + i, prefs.getInt(k("script_select_$i"), 0).toFloat(),
                )
                engine.setParam(
                    node, base + 32 + i, prefs.getInt(k("script_list_$i"), 0).toFloat(),
                )
            }
        }
    }

    /** lane0 沿用旧键（无后缀），lane1/2 用 `_1`/`_2` 后缀 → 老数据零迁移。 */
    private fun laneKey(base: String): String =
        if (_scriptLane.value == 0) base else "${base}_${_scriptLane.value}"

    /** 参数编码：param_id = lane*64 + local（节点内解码）。 */
    private fun laneParam(localId: Int): Int = _scriptLane.value * 64 + localId

    /** 切换正在编辑的 lane：载入其脚本与控件值，并确保 native 侧该 lane 已启用。 */
    fun switchScriptLane(lane: Int) {
        if (lane < 0 || lane >= 3 || lane == _scriptLane.value) return
        _scriptLane.value = lane
        prefs.edit().putInt("script_lane", lane).apply()
        _scriptLaneOn.value = prefs.getBoolean("script_lane_on_$lane", true)
        refreshLaneMeta()

        val src = prefs.getString(laneKey("script_effect_source"), "") ?: ""
        _scriptSource.value = src
        if (src.isNotBlank()) {
            engine.setScriptLaneEnabled(lane, true)
            engine.setScriptSource(src, lane)
        }
        _scriptSliderValues.value = FloatArray(8) {
            prefs.getFloat(laneKey("script_slider_$it"), 0.5f)
        }
        _scriptToggleValues.value = FloatArray(8) {
            prefs.getFloat(laneKey("script_toggle_$it"), 0f)
        }
        _scriptSelectValues.value = IntArray(8) { prefs.getInt(laneKey("script_select_$it"), 0) }
        _scriptListValues.value = IntArray(8) { prefs.getInt(laneKey("script_list_$it"), 0) }
        refreshScriptSliderMask()
        // 新 lane 的 VM 是新建的，把当前值补发一遍
        for (i in 0 until 8) {
            val node = AudioEngine.SCRIPT_EFFECT_NODE_INDEX
            engine.setParam(node, laneParam(i), _scriptSliderValues.value[i])
            engine.setParam(node, laneParam(8 + i), _scriptToggleValues.value[i])
            engine.setParam(node, laneParam(16 + i), _scriptSelectValues.value[i].toFloat())
            engine.setParam(node, laneParam(32 + i), _scriptListValues.value[i].toFloat())
        }
    }

    /**
     * 下发脚本源（非实时）：写入当前 lane；lane>0 时自动启用该 lane。
     * 空字符串 = 使用节点内置软削波示例（仅 lane0 有意义）。
     */
    fun applyScriptSource(source: String) {
        val lane = _scriptLane.value
        _scriptSource.value = source
        prefs.edit().putString(laneKey("script_effect_source"), source).commit()

        // 编译不通过 → 自动旁路该槽位：避免它继续跑「旧脚本」，
        // 造成「控件变了但声音没变」的误会（错误串同时驱动槽位行红色呼吸）。
        val err = if (source.isBlank()) "" else engine.checkScript(source)
        if (err.isNotBlank()) {
            // 只在运行时旁路，**不写 prefs**：否则脚本修好后槽位永远停在关闭状态
            // （2026-11-14：设备上 lane0 被历史失败写成 false，用户表现为"总闸开着却没效果"）
            engine.setScriptLaneEnabled(lane, false)
            if (lane == _scriptLane.value) _scriptLaneOn.value = false
        } else {
            // 应用成功 → 该槽位自动启用（并回写状态，否则开关显示与实际不一致）
            prefs.edit().putBoolean("script_lane_on_$lane", true).apply()
            engine.setScriptLaneEnabled(lane, true)
            if (lane == _scriptLane.value) _scriptLaneOn.value = true
        }
        refreshLaneMeta()
        if (source.isNotEmpty()) engine.setScriptSource(source, lane)
        refreshScriptSliderMask()
        applyScriptDefaults()
    }

    /** 把脚本声明的默认值写回各控件（应用脚本时调用一次）。 */
    private fun applyScriptDefaults() {
        val defs = _scriptSliderDefaults.value
        val vals = _scriptSliderValues.value.copyOf()
        var changed = false
        for (i in 0 until 8) {
            if (defs[8 + i] > 0.5f) {
                vals[i] = defs[i]
                prefs.edit().putFloat(laneKey("script_slider_$i"), vals[i]).apply()
                engine.setParam(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, laneParam(i), vals[i])
                changed = true
            }
        }
        if (changed) _scriptSliderValues.value = vals
        for (ctl in _scriptControls.value) {
            if (!ctl.hasDefault) continue
            when (ctl.type) {
                "toggle" -> setScriptToggle(ctl.index, ctl.defaultValue > 0.5f)
                "select" -> setScriptSelect(ctl.index, ctl.defaultValue.toInt())
                "list" -> setScriptList(ctl.index, ctl.defaultValue.toInt())
            }
        }
    }

    /** 设置脚本参数 slider（index 0..7 → slider1..8）并下发。 */
    fun setScriptSlider(index: Int, value: Float) {
        if (index < 0 || index >= 8) return
        val arr = _scriptSliderValues.value.copyOf()
        arr[index] = value
        _scriptSliderValues.value = arr
        prefs.edit().putFloat(laneKey("script_slider_$index"), value).apply()
        engine.setParam(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, laneParam(index), value)
    }

    /** 只编译检查脚本（不生效、不保存）：返回错误串，空 = 通过。 */
    fun validateScript(source: String): String = engine.checkScript(source)

    /** 只保存脚本草稿（不重新编译）：编辑中途落盘，重启后仍在。 */
    fun saveScriptDraft(source: String) {
        prefs.edit().putString(laneKey("script_effect_source"), source).apply()
        _scriptSource.value = source
    }

    /** 设置脚本开关控件 toggle（index 0..7 → toggle1..8）并下发。 */
    fun setScriptToggle(index: Int, on: Boolean) {
        if (index < 0 || index >= 8) return
        val arr = _scriptToggleValues.value.copyOf()
        arr[index] = if (on) 1f else 0f
        _scriptToggleValues.value = arr
        prefs.edit().putFloat(laneKey("script_toggle_$index"), arr[index]).apply()
        engine.setParam(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, laneParam(8 + index), arr[index])
    }

    /** 设置脚本选择控件 select（index 0..7 → select1..8，值为选项下标）。 */
    fun setScriptSelect(index: Int, option: Int) {
        if (index < 0 || index >= 8) return
        val arr = _scriptSelectValues.value.copyOf()
        arr[index] = option.coerceAtLeast(0)
        _scriptSelectValues.value = arr
        prefs.edit().putInt(laneKey("script_select_$index"), arr[index]).apply()
        engine.setParam(
            AudioEngine.SCRIPT_EFFECT_NODE_INDEX,
            laneParam(16 + index),
            arr[index].toFloat(),
        )
    }

    /** 触发脚本按钮（index 0..7 → button1..8）：仅对下一个样本生效。 */
    fun pressScriptButton(index: Int) {
        if (index < 0 || index >= 8) return
        engine.setParam(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, laneParam(24 + index), 1f)
    }

    /** 设置脚本多选列表 list（index 0..7 → list1..8，值为位掩码）。 */
    fun setScriptList(index: Int, mask: Int) {
        if (index < 0 || index >= 8) return
        val arr = _scriptListValues.value.copyOf()
        arr[index] = mask
        _scriptListValues.value = arr
        prefs.edit().putInt(laneKey("script_list_$index"), mask).apply()
        engine.setParam(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, laneParam(32 + index), mask.toFloat())
    }

    /** 从引擎回读脚本 slider 位图/范围、控件描述与编译错误（脚本变更 / 切换 lane 后调用）。 */
    fun refreshScriptSliderMask() {
        val lane = _scriptLane.value
        _scriptSliderMask.value = engine.scriptSliderMask(lane)
        val rng = FloatArray(16)
        if (engine.scriptSliderRanges(rng, lane)) _scriptSliderRanges.value = rng
        _scriptControls.value = parseScriptControls(engine.scriptControls(lane))
        val defs = FloatArray(16)
        if (engine.scriptSliderDefaults(defs, lane)) _scriptSliderDefaults.value = defs
        _scriptError.value = engine.scriptError(lane)
    }

    /** 上限（dBFS）：实时下发到 limiter，包络受 attack/release 速率约束天然无突变 */
    fun setLimiterCeiling(db: Float) {
        val clamped = db.coerceIn(-12f, 0f)
        _limiterCeiling.value = clamped
        prefs.edit().putFloat(KEY_LIMITER_CEILING, clamped).apply()
        engine.setParam(AudioEngine.LIMITER_NODE_INDEX, AudioEngine.LIMITER_PARAM_CEILING_DB, clamped)
    }

    /** 压缩阈值（dB）：实时下发 */
    fun setCompressorThreshold(db: Float) {
        val clamped = db.coerceIn(-60f, 0f)
        _compressorThreshold.value = clamped
        prefs.edit().putFloat(KEY_COMPRESSOR_THRESHOLD, clamped).apply()
        engine.setParam(
            AudioEngine.COMPRESSOR_NODE_INDEX,
            AudioEngine.COMPRESSOR_PARAM_THRESHOLD_DB,
            clamped,
        )
    }

    /** 压缩比（x:1）：实时下发 */
    fun setCompressorRatio(ratio: Float) {
        val clamped = ratio.coerceIn(1f, 20f)
        _compressorRatio.value = clamped
        prefs.edit().putFloat(KEY_COMPRESSOR_RATIO, clamped).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_RATIO, clamped)
    }

    /** 起音（ms）：实时下发（2026-11-09 暴露到 UI） */
    fun setCompressorAttack(ms: Float) {
        val clamped = ms.coerceIn(0.1f, 100f)
        _compressorAttack.value = clamped
        prefs.edit().putFloat(KEY_COMPRESSOR_ATTACK, clamped).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_ATTACK_MS, clamped)
    }

    /** 释放（ms）：实时下发 */
    fun setCompressorRelease(ms: Float) {
        val clamped = ms.coerceIn(5f, 1000f)
        _compressorRelease.value = clamped
        prefs.edit().putFloat(KEY_COMPRESSOR_RELEASE, clamped).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_RELEASE_MS, clamped)
    }

    /** 拐点宽度（dB，0 = 硬拐点）：实时下发 */
    fun setCompressorKnee(db: Float) {
        val clamped = db.coerceIn(0f, 24f)
        _compressorKnee.value = clamped
        prefs.edit().putFloat(KEY_COMPRESSOR_KNEE, clamped).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_KNEE_DB, clamped)
    }

    /** 补偿增益（dB）：实时下发 */
    fun setCompressorMakeup(db: Float) {
        val clamped = db.coerceIn(-12f, 12f)
        _compressorMakeup.value = clamped
        prefs.edit().putFloat(KEY_COMPRESSOR_MAKEUP, clamped).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_MAKEUP_DB, clamped)
    }

    // ---- 2026-11-11 四项新参数 ----

    /** 并联混合（0 = 全干、1 = 全湿）：实时下发 */
    fun setCompressorMix(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _compressorMix.value = c
        prefs.edit().putFloat(KEY_COMPRESSOR_MIX, c).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_MIX, c)
    }

    /** 检测模式（0 = peak、1 = RMS）：实时下发 */
    fun setCompressorDetect(mode: Float) {
        val c = if (mode < 0.5f) 0f else 1f
        _compressorDetect.value = c
        prefs.edit().putFloat(KEY_COMPRESSOR_DETECT, c).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_DETECT_MODE, c)
    }

    /** 前瞻（ms）：实时下发（会引入等量延迟） */
    fun setCompressorLookahead(ms: Float) {
        val c = ms.coerceIn(0f, 10f)
        _compressorLookahead.value = c
        prefs.edit().putFloat(KEY_COMPRESSOR_LOOKAHEAD, c).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_LOOKAHEAD_MS, c)
    }

    /** 过采样（1 = 关闭、2 = 2x）：实时下发（改变倍率会重算时间常数并清缓冲） */
    fun setCompressorOversample(f: Float) {
        val c = if (f >= 1.5f) 2f else 1f
        _compressorOversample.value = c
        prefs.edit().putFloat(KEY_COMPRESSOR_OVERSAMPLE, c).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_OVERSAMPLE, c)
    }

    /**
     * 侧链高通（Hz，0 = 关闭）。
     *
     * **这个写入端此前完全缺失**（2026-11-11 补）：只有重放在读 key、没有 setter 写，
     * 导致参数恒为默认 120、用户无法关闭或调整。
     */
    fun setCompressorSidechainHp(hz: Float) {
        val c = hz.coerceIn(0f, 300f)
        _compressorSidechainHp.value = c
        prefs.edit().putFloat(KEY_COMPRESSOR_SIDECHAIN_HP, c).apply()
        engine.setParam(AudioEngine.COMPRESSOR_NODE_INDEX, AudioEngine.COMPRESSOR_PARAM_SIDECHAIN_HP_HZ, c)
    }

    /** 单段压缩器电平表刷新（UI 10fps 轮询，null = 引擎未就绪则保持旧值） */
    fun refreshCompressorMeters() {
        engine.compressorMeters()?.let { _compressorMeters.value = it.toList() }
    }

    /**
     * 压限器**总闸**（2026-11-11，音效库卡片那个开关）。
     *
     * 关 = 多段与单段**同时旁通**，但两张卡的开关记忆不动 —— 这就是用户要的
     * 「关闭表示旁通，再开启时各自回到原来的状态」。
     */
    fun setCompressorSectionEnabled(enabled: Boolean) {
        if (_compressorSectionEnabled.value == enabled) return
        _compressorSectionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_COMPRESSOR_SECTION_ENABLED, enabled).apply()
        applyCompressorBypass()
    }

    /**
     * 按「总闸 × 各自记忆值」统一下发两个节点的 bypass。
     *
     * **所有**影响压限链的入口（总闸 / 多段 / 单段 / 预设应用）都走这一个函数。
     * 为什么不各写各的：本项目历史上反复踩「同一状态散在多处维护、漏改一处」的坑
     * （参数三处定义、重放漏 param 6 等），bypass 条件若散成三份迟早不一致。
     */
    private fun applyCompressorBypass() {
        val sec = _compressorSectionEnabled.value
        // 多段与单段记忆互斥，不会同时为 true；这里仍分别取与，语义更直白
        val mb = sec && _multibandEnabled.value
        val cp = sec && _compressorEnabled.value
        engine.setBypass(AudioEngine.MULTIBAND_NODE_INDEX, !mb)
        engine.setBypass(AudioEngine.COMPRESSOR_NODE_INDEX, !cp)
    }

    /**
     * 多段压限开关（**记忆层**）：开 = 互斥地把单段记忆置 false；
     * 关 = 若用户确实开过单段则恢复其记忆。实际是否出声由总闸决定。
     */
    fun setMultibandEnabled(enabled: Boolean) {
        _multibandEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MULTIBAND_ENABLED, enabled).apply()
        if (enabled) {
            // 互斥：多段开 → 单段记忆关闭（单段 prefs 保留，关回多段时可恢复）
            _compressorEnabled.value = false
        } else {
            // 规则：只恢复用户**确实开过**的单段，不要用"默认 true"把它打开
            if (_compressorEnabled.value || prefs.getBoolean(KEY_COMPRESSOR_ENABLED, false)) {
                _compressorEnabled.value = true
            }
        }
        applyCompressorBypass()
    }

    /** 单段压缩器开关（**记忆层**）：开时互斥把多段记忆置 false。实际是否出声由总闸决定。 */
    fun setCompressorEnabled(enabled: Boolean) {
        _compressorEnabled.value = enabled
        prefs.edit().putBoolean(KEY_COMPRESSOR_ENABLED, enabled).apply()
        if (enabled) {
            _multibandEnabled.value = false
        }
        applyCompressorBypass()
    }

    /** param_id 钳制范围（与 native MultibandNode 同口径；UI 滑条共用） */
    fun multibandParamRange(pid: Int): ClosedFloatingPointRange<Float> =
        if (pid >= 18) {
            if (pid == 18) 50f..500f else 1000f..8000f
        } else {
            MULTIBAND_BAND_RANGES[pid % 6]
        }

    /**
     * 三段参数联动：开启后改任一段的段内参数（0..17，即阈值/比率/
     * Attack/Release/拐点/增益）会**同步到三段同名参数**，用于快速起手——
     * 新用户不必逐一调三个阈值。分频点（18/19）不参与联动。
     *
     * 关闭时为纯独立模式（原行为）。
     */
    private val _multibandLinked = MutableStateFlow(false)
    val multibandLinked: StateFlow<Boolean> = _multibandLinked.asStateFlow()

    fun setMultibandLinked(on: Boolean) { _multibandLinked.value = on }

    /** A/B 快照槽：各存一份 20 参数的完整快照，用于压前/压后对比。 */
    private var multibandSnapshotA: List<Float>? = null
    private var multibandSnapshotB: List<Float>? = null

    /** bit0 = A 已存，bit1 = B 已存（UI 据此灰化未存过的槽） */
    private val _multibandSnapshotMask = MutableStateFlow(0)
    val multibandSnapshotMask: StateFlow<Int> = _multibandSnapshotMask.asStateFlow()

    fun saveMultibandSnapshot(slot: Char) {
        val snap = _multibandParams.value
        if (slot == 'A') multibandSnapshotA = snap else multibandSnapshotB = snap
        _multibandSnapshotMask.value =
            (if (multibandSnapshotA != null) 1 else 0) or (if (multibandSnapshotB != null) 2 else 0)
    }

    fun restoreMultibandSnapshot(slot: Char) {
        // 注意括号：`if (...) a else b ?: return` 的 ?: 只作用于 b，a 仍是可空的
        val snap = (if (slot == 'A') multibandSnapshotA else multibandSnapshotB) ?: return
        snap.forEachIndexed { pid, v -> setMultibandParam(pid, v) }
    }

    /**
     * 自动增益补偿：把某段的 GR 反向加到该段增益上，让压完的响度回到压前。
     * 只做**一次性应用**，不做实时跟踪——实时跟会让增益随音乐抖动，听感更差。
     */
    fun applyAutoGain(band: Int, grDb: Float) {
        if (band !in 0..2) return
        val pid = band * 6 + 5          // 段增益
        val cur = _multibandParams.value.getOrElse(pid) { 0f }
        setMultibandParam(pid, cur - grDb)   // grDb 是负值，减去即补偿
    }

    /** 多频段单参数（param_id 0-19）：钳制（与 native 同口径）→ 实时下发 */
    fun setMultibandParam(paramId: Int, value: Float) {
        if (paramId !in 0 until MULTIBAND_PARAM_COUNT) return
        // 联动模式：段内参数同步到三段（分频点 18/19 不参与）
        if (_multibandLinked.value && paramId < 18) {
            val sub = paramId % 6
            val updated = _multibandParams.value.toMutableList()
            for (band in 0..2) {
                val pid = band * 6 + sub
                val c = value.coerceIn(multibandParamRange(pid))
                updated[pid] = c
                prefs.edit().putFloat(KEY_MULTIBAND_PARAM_PREFIX + pid, c).apply()
                engine.setParam(AudioEngine.MULTIBAND_NODE_INDEX, pid, c)
            }
            _multibandParams.value = updated
            return
        }
        val clamped = value.coerceIn(multibandParamRange(paramId))
        val updated = _multibandParams.value.toMutableList()
        updated[paramId] = clamped
        _multibandParams.value = updated
        prefs.edit().putFloat(KEY_MULTIBAND_PARAM_PREFIX + paramId, clamped).apply()
        engine.setParam(AudioEngine.MULTIBAND_NODE_INDEX, paramId, clamped)
    }

    /** 引擎进入 Running 后把持久化的 EQ/压缩器/限幅器设置补给引擎（引擎未启动时 setParam 无效） */
    private fun applyEqSettings() {
        // 输出缓冲（用户可调，2026-11-06）：每次建链后按偏好重新应用一次
        // （引擎重建流时缓冲会回到默认，必须补发）
        runCatching {
            val ms = prefs.getInt(KEY_BUFFER_MS, DEFAULT_BUFFER_MS).coerceIn(4, 24)
            _bufferMs.value = ms
            engine.setBufferFrames(ms * SAMPLE_RATE_KHZ)
        }
        engine.setTrimSickHead(_trimSickHead.value)
        // 组总闸 && 子开关（PEQ 与 AutoEq 一起）
        applyEqGates()
        engine.setParam(AudioEngine.EQ_NODE_INDEX, BAND_BASS, _bassGain.value)
        engine.setParam(AudioEngine.EQ_NODE_INDEX, BAND_TREBLE, _trebleGain.value)
        // 多段 EQ：段数模式 + 当前模式逐段增益重放（恢复链路补全——此前中间段丢失）
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, _eqMode.value.toFloat())
        _eqBandGains.value.forEachIndexed { i, g ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, i, g)
        }
        _eqBandQs.value = readEqQs(_eqMode.value)
        _eqBandQs.value.forEachIndexed { i, q ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, q)
        }
        _eqBandFreqs.value = readEqFreqs(_eqMode.value)
        _eqBandFreqs.value.forEachIndexed { i, f ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, f)
        }
        val eqTypes = readEqTypes(_eqMode.value)
        _eqBandTypes.value = eqTypes
        eqTypes.forEachIndexed { i, t ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + i, t.toFloat())
        }
        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_PREGAIN_DB, _eqPreGainDb.value)
        // 多频段：先补 20 参再定旁路；多频段开着则强制单段旁路（防重启后双活）
        for (pid in 0 until MULTIBAND_PARAM_COUNT) {
            engine.setParam(AudioEngine.MULTIBAND_NODE_INDEX, pid, _multibandParams.value[pid])
        }
        engine.setBypass(AudioEngine.MULTIBAND_NODE_INDEX, !_multibandEnabled.value)
        engine.setBypass(
            AudioEngine.COMPRESSOR_NODE_INDEX,
            !_compressorEnabled.value || _multibandEnabled.value,
        )
        engine.setParam(
            AudioEngine.COMPRESSOR_NODE_INDEX,
            AudioEngine.COMPRESSOR_PARAM_THRESHOLD_DB,
            _compressorThreshold.value,
        )
        engine.setParam(
            AudioEngine.COMPRESSOR_NODE_INDEX,
            AudioEngine.COMPRESSOR_PARAM_RATIO,
            _compressorRatio.value,
        )
        engine.setBypass(AudioEngine.LIMITER_NODE_INDEX, !_limiterEnabled.value)
        // 自定义脚本效果器（2026-11-12 原型）：重建后先恢复用户脚本（新节点默认旁路，
        // 可即时重载），再按开关设置旁路。
        if (_scriptSource.value.isNotEmpty()) {
            engine.setScriptSource(_scriptSource.value, _scriptLane.value)
        }
        engine.setBypass(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, !_scriptEffectEnabled.value)
        // 三条 lane 全部恢复（脚本 + 启用状态 + 各自参数），保证重启后不丢
        replayScriptLanes()
        refreshScriptSliderMask()
        engine.setParam(
            AudioEngine.LIMITER_NODE_INDEX,
            AudioEngine.LIMITER_PARAM_CEILING_DB,
            _limiterCeiling.value,
        )
        // true-peak 检测默认开（BS.1770 4x 过采样防 DAC 重构过冲穿透 ceiling）；
        // 无 UI 开关——安全网算法补强不增加用户决策负担
        engine.setParam(
            AudioEngine.LIMITER_NODE_INDEX,
            AudioEngine.LIMITER_PARAM_TRUE_PEAK,
            1f,
        )
        // 链序扩展：输入 Gain/Balance/输出 Gain（默认 bypass=true，开启时才生效）
        engine.setBypass(AudioEngine.INPUT_GAIN_NODE_INDEX, _inputGain.value == 1f)
        if (_inputGain.value != 1f) {
            engine.setParam(
                AudioEngine.INPUT_GAIN_NODE_INDEX,
                AudioEngine.GAIN_PARAM_GAIN,
                _inputGain.value,
            )
        }
        engine.setBypass(AudioEngine.BALANCE_NODE_INDEX, _balance.value == 0f)
        if (_balance.value != 0f) {
            engine.setParam(
                AudioEngine.BALANCE_NODE_INDEX,
                AudioEngine.BALANCE_PARAM_BALANCE,
                _balance.value,
            )
        }
        engine.setBypass(AudioEngine.OUTPUT_GAIN_NODE_INDEX, _outputGain.value == 1f)
        if (_outputGain.value != 1f) {
            engine.setParam(
                AudioEngine.OUTPUT_GAIN_NODE_INDEX,
                AudioEngine.GAIN_PARAM_GAIN,
                _outputGain.value,
            )
        }
        // 卷积混响预设下发
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_PRESET,
            _reverbPreset.value.toFloat(),
        )
        // 干/湿电平无条件下发（wet 已全预设生效；dry 同）
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_WET,
            _userWetGain.value,
        )
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_DRY,
            _reverbDry.value,
        )
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_LOWCUT,
            _reverbLowCut.value,
        )
        // 上次使用的是自定义 IR：后台协程重算 spec[4] 后补发切换
        if (_reverbPreset.value == AudioEngine.REVERB_PRESET_USER) {
            writeDiag("applyEq: preset=5 恢复链路进入 wet=${_userWetGain.value}")
            viewModelScope.launch(Dispatchers.IO) {
                // 恢复链路：优先最近列表（filesDir 持久），旧版本迁移兜底
                // cache/user_ir.wav，都没有才回退 HALL
                // 恢复：按 active_ir_name 在脉冲库里找文件（目录即真相，无 JSON 记账）
                val activeName = _activeRecentIrName.value
                val libFile = activeName?.let { name ->
                    File(pulseDir, sanitizeIrFileName("$name.wav"))
                        .takeIf { it.exists() }
                        ?: pulseDir.listFiles()?.firstOrNull { it.isFile && displayNameOf(it.name) == name }
                }
                val legacyCache = File(context.cacheDir, "user_ir.wav")
                val target = when {
                    libFile != null -> libFile
                    legacyCache.exists() -> legacyCache
                    else -> null
                }
                if (target == null) {
                    writeDiag("applyEq: 恢复失败——脉冲库与 cache 均无可用 IR，回退 HALL")
                    setActiveRecentIr(null)
                    withContext(Dispatchers.Main) {
                        _reverbPreset.value = AudioEngine.REVERB_PRESET_HALL
                        engine.setParam(
                            AudioEngine.CONVOLVER_NODE_INDEX,
                            AudioEngine.CONVOLVER_PARAM_PRESET,
                            AudioEngine.REVERB_PRESET_HALL.toFloat(),
                        )
                    }
                    return@launch
                }
                setActiveRecentIr(if (target === libFile) displayNameOf(target.name) else null)
                // P1：恢复装载是临界区（此时实时线程可能在跑、preset 已挂 USER），与其他装载入口互斥
                irLoadMutex.withLock {
                    val load_err = engine.loadImpulseResponse(target.absolutePath)
                    writeDiag(
                        "applyEq: 恢复加载 err=[$load_err] file=${target.name} exists=${target.exists()} " +
                            "size=${target.length()} src=${if (target === libFile) "library" else "legacy-cache"}",
                    )
                    if (load_err == null) {
                        engine.setParam(
                            AudioEngine.CONVOLVER_NODE_INDEX,
                            AudioEngine.CONVOLVER_PARAM_PRESET,
                            AudioEngine.REVERB_PRESET_USER.toFloat(),
                        )
                        if (target === libFile) recordRecentLoaded(displayNameOf(target.name))
                        writeDiag("applyEq: SetParam(4) 已发送")
                        withContext(Dispatchers.Main) { refreshUserIrAnalysis() }
                    } else {
                        withContext(Dispatchers.Main) {
                            _reverbPreset.value = AudioEngine.REVERB_PRESET_HALL
                            engine.setParam(
                                AudioEngine.CONVOLVER_NODE_INDEX,
                                AudioEngine.CONVOLVER_PARAM_PRESET,
                                AudioEngine.REVERB_PRESET_HALL.toFloat(),
                            )
                        }
                    }
                }
            }
        }
        // 虚拟环绕：旁路 + 已保存参数（开关关时也下发，便于下次打开立即生效）
        engine.setBypass(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, !_vsEnabled.value)
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_ENABLED,
            if (_vsEnabled.value) 1f else 0f,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_MODE,
            _vsMode.value.toFloat(),
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_SURROUND_GAIN,
            _vsSurroundGain.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_CENTER_GAIN,
            _vsCenterGain.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_FRONT_GAIN,
            _vsFrontGain.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_DEBUG_RAW,
            if (_vsDebugRaw.value) 1f else 0f,
        )
        for (i in 0..6) {
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                vsTrimIds[i],
                _vsTrims.value[i],
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                vsMuteIds[i],
                _vsMutes.value[i],
            )
        }
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_STEREO_EXPAND,
            _vsRearSpace.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_REAR_GAIN,
            _vsRearGain.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_ROOM_SIZE,
            _vsRoomSize.value.toFloat(),
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_ROOM_WET_DRY,
            _vsRoomWet.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_ENCODE_STRENGTH,
            _vsEncodeStrength.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_CENTER_MODE,
            _vsCenterMode.value.toFloat(),
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_DIALOG,
            _vsDialog.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_BASS_ENHANCER,
            _vsBassEnh.value,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_BASS_ENHANCER_MODE,
            _vsBassEnhMode.value.toFloat(),
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_REAR_CENTER_ENABLED,
            if (_vsRearCenter.value) 1f else 0f,
        )
        engine.setParam(
            AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
            AudioEngine.VS_PARAM_REAR_CENTER_DELAY_MS,
            _vsRearDelay.value,
        )
        engine.setBypass(AudioEngine.STEREO_WIDTH_NODE_INDEX, !_stereoWidthEnabled.value)
        engine.setParam(
            AudioEngine.STEREO_WIDTH_NODE_INDEX,
            AudioEngine.STEREO_WIDTH_PARAM_WIDTH,
            _stereoWidth.value,
        )
        // 算法混响（FDN）参数下发
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_ROOM_SIZE, _fdnRoomSize.value)
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_HUMIDITY, _fdnHumidity.value)
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_STEREO_WIDTH, _fdnStereoWidth.value)
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_WET, _fdnWet.value)
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_DRY, _fdnDry.value)
        engine.setParam(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, AudioEngine.REVERB_PARAM_MODE, _fdnMode.value)
        // 两个混响独立恢复各自旁路（可同时开启）
        engine.setBypass(AudioEngine.CONVOLVER_NODE_INDEX, !_reverbEnabled.value)
        engine.setBypass(AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX, !_fdnEnabled.value)
        // **低频管家三槽（2026-11-11 P1 修复）**：此前漏了这一步。
        //
        // `engine.prepare()` 会把所有节点重置为 bypass=true；前台建链成功后
        // （电源键启停 / MediaProjection / Loopback 三条路径）只调本函数补发参数。
        // 本函数覆盖了 EQ/Multiband/Compressor/Limiter/Gain/Width/两种混响共 10 个节点，
        // 唯独低频管家的 3 个新节点没写 —— 于是「总闸开着、槽开关也开着，
        // 但引擎里三个节点全是旁路」，手动拨一下开关才触发本函数恢复。
        // headless restore 走 AudioChainManager.replaySettings()（表驱动）所以那条正常。
        //
        // 走统一入口而不是在这里再写一遍三行 setBypass，避免又一处状态副本。
        applyLowfreqBypass()
    }

    /** 排除清单包名 → uid（无效包名跳过）。供 startCapture 使用。 */
    private fun excludedUids(): IntArray =
        _excludedPackages.value.mapNotNull { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }
                .getOrNull()
        }.toIntArray()

    // 电平探针注入（保险丝判定在 TakeoverController 的进程级 scope 里执行）。
    // 铁律：必须在类体尾部注入 —— lambda 运行时才解引用，属性必须已初始化。
    // 语义：零电平持续 15s → 回滚接管保直出（宁可直出，不许完全无声）。见 ADR-02。
    init {
        takeover.captureLevelProbe = {
            if (engineState.value is EngineState.Running) engine.stats.value.inputLevel else -1f
        }
    }

    // ：输出设备感知。本批**只落 device_diag.txt 诊断日志**，
    // 不切配置、不动引擎、不碰静音链——先把「声音往哪出」变成可观测事实，
    // 为 的按设备切换提供依据。@Singleton 内部幂等，VM 重建不会重复挂回调。
    init {
        runCatching { outputDeviceMonitor.start() }
        refreshBoundDeviceKeys()
        // ：设备变化 → 按绑定切换音效。收集器随 VM 生命周期（Activity 被回收后失效，
        // 迁到进程级服务留待 之后评估）。
        viewModelScope.launch {
            outputDeviceMonitor.current.collect { cur ->
                runCatching { onOutputDeviceChanged(cur.key, cur.label) }
                    .onFailure {
                        writeDiag("设备切换异常: ${it.javaClass.simpleName} ${it.message?.take(80)}")
                    }
            }
        }
        // 最近配置基线：先读已有值，避免启动后第一次 tick 把同样内容再写一遍
        lastSavedConfigJson = prefs.getString(keyLastConfig, null)
        // 定期快照（10s）：用户可能压根不绑定预设就直接调参，换设备时要能拿回「最近一次」。
        // 用 10s 而不是每次 setter 都写盘——滑条拖动时每帧写 JSON 会是明显的写放大。
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                runCatching { saveLiveConfig(outputDeviceMonitor.current.value.key) }
                    .onFailure { writeDiag("最近配置保存异常: ${it.javaClass.simpleName}") }
            }
        }
        writeDiag(
            "M14b 就绪: 自适应=${_deviceAdaptive.value}, 已绑定设备=${_boundDeviceKeys.value.size} 个, " +
                "最近配置=${lastSavedConfigJson != null}"
        )
    }

    private companion object {
        const val TAG = "MainVM"
        const val PREFS_NAME = "sonicraft_settings"

        /** 输出缓冲（毫秒）偏好键与默认值（默认 8ms ≈ 2×burst） */
        const val KEY_BUFFER_MS = "buffer_ms"
        const val DEFAULT_BUFFER_MS = 8
        /** 采样率（kHz）：毫秒 → 帧数换算用 */
        const val SAMPLE_RATE_KHZ = 48

        /** 音频链路模式：loopback（默认）/ stellar_mp（用户确认后的平替） */
        const val KEY_AUDIO_MODE = "audio_mode"
        const val MODE_LOOPBACK = "loopback"
        const val MODE_STELLAR_MP = "stellar_mp"

        /** D4：Loopback 能力失败时间戳（ms），TTL 内自动启动直接走 MP 回退 */
        const val KEY_LOOPBACK_FAIL_AT = "loopback_fail_at_ms"

        /** D4：失败标记有效期（24h）。过期后重新探测，避免一次偶发失败永久降级 */
        const val LOOPBACK_FAIL_TTL_MS = 24L * 60 * 60 * 1000

        const val KEY_USER_PRESETS_JSON = "user_presets_json"
        const val KEY_FAVORITE_IRS = "favorite_irs"
        const val KEY_INPUT_GAIN = "input_gain"
        const val KEY_BALANCE = "balance"
        const val KEY_OUTPUT_GAIN = "output_gain"
        const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
        const val KEY_TAKEOVER_PACKAGES = "takeover_packages"
        const val KEY_AUTO_TAKEOVER = "auto_takeover"
        const val KEY_GATE_THRESHOLD = "gate_threshold"
        const val KEY_EQ_ENABLED = "eq_enabled"
        /** 均衡器组总闸（两级开关：组闸 + PEQ/AutoEq 子卡） */
        const val KEY_EQ_SECTION_ENABLED = "eq_section_enabled"
        const val KEY_BASS_GAIN = "bass_gain"
        const val KEY_TREBLE_GAIN = "treble_gain"
        const val KEY_EQ_MODE = "eq_mode"
        const val KEY_EQ_PREGAIN = "eq_pregain"
        const val KEY_EQ_USER_PRESETS = "eq_user_presets_json"

    // ---- AutoEq 独立校正（与 PEQ 分开存，互不覆盖）----
    const val KEY_AUTOEQ_ENABLED = "auto_eq_enabled"
    const val KEY_AUTOEQ_MODE = "auto_eq_mode"
    const val KEY_AUTOEQ_PREGAIN = "auto_eq_pregain"
    /** 最近一次导入的来源名（文件名），用于在卡片上显示"当前：HD600" */
    const val KEY_AUTOEQ_SOURCE = "auto_eq_source"
    /** AutoEq 库（多副耳机校正曲线）：JSON 数组，结构与机架脚本库一致（名字 + 全套段参数） */
    const val KEY_AUTOEQ_LIBRARY = "auto_eq_library_json"

    // AutoEq 库**不设条数上限**：条目多寡取决于用户导入的样本，
    // 列表本身有限高 + 滚动 + 搜索，页长不会被撑开。
    private const val AUTOEQ_GAINS_PREFIX = "auto_eq_gains_"
    private const val AUTOEQ_QS_PREFIX = "auto_eq_qs_"
    private const val AUTOEQ_FREQS_PREFIX = "auto_eq_freqs_"
    private const val AUTOEQ_TYPES_PREFIX = "auto_eq_types_"
        const val KEY_LIMITER_ENABLED = "limiter_enabled"
        const val KEY_LIMITER_CEILING = "limiter_ceiling"
        /** 界面缩放（2026-11-09，从 wecho 定制版移植）：主倍率与微调分开存 */
    const val KEY_UI_SCALE = "ui_scale"
    const val KEY_UI_FINE_SCALE = "ui_fine_scale"

    const val KEY_COMPRESSOR_ENABLED = "compressor_enabled"
        const val KEY_COMPRESSOR_THRESHOLD = "compressor_threshold"
        const val KEY_COMPRESSOR_RATIO = "compressor_ratio"
        const val KEY_COMPRESSOR_ATTACK = "compressor_attack_ms"
        const val KEY_COMPRESSOR_RELEASE = "compressor_release_ms"
        const val KEY_COMPRESSOR_KNEE = "compressor_knee_db"
        const val KEY_COMPRESSOR_MAKEUP = "compressor_makeup_db"
    /** 并联混合（2026-11-11，0~1，1 = 全湿） */
    const val KEY_COMPRESSOR_MIX = "compressor_mix"
    /** 检测模式（0 = peak / 1 = RMS） */
    const val KEY_COMPRESSOR_DETECT = "compressor_detect_mode"
    /** 前瞻（ms，0~10） */
    const val KEY_COMPRESSOR_LOOKAHEAD = "compressor_lookahead_ms"
    /** 过采样（1 = 关闭 / 2 = 2x） */
    const val KEY_COMPRESSOR_OVERSAMPLE = "compressor_oversample"
    /**
     * 侧链高通（Hz，0 = 关闭）。2026-11-11 补上写入端。
     *
     * 此前只有 `AudioChainManager` 的**重放**读这个 key、全项目没有写入方，
     * 于是永远读到默认 120 —— 功能实际生效但**完全不可配置**，是「参数散在三处」的
     * 典型事故（想关掉它只能同时改 C++ 默认值和重放默认值）。
     */
    const val KEY_COMPRESSOR_SIDECHAIN_HP = "compressor_sidechain_hp"
        const val KEY_MULTIBAND_ENABLED = "multiband_enabled"
    /**
     * 压限器**总闸**：音效库卡片上的那个开关。
     *
     * 三层语义：
     *     section（总闸）  ← 音效库卡片
     *       ├─ multiband（记忆）  ← 详情页「多段压限」卡
     *       └─ compressor（记忆） ← 详情页「压缩器（单段）」卡
     * 实际生效 = section && 记忆值；关掉总闸即整体旁通，但两张卡的记忆保留，
     * 重新开启时各自回到原来的状态。
     */
    const val KEY_COMPRESSOR_SECTION_ENABLED = "compressor_section_enabled"
        const val KEY_MULTIBAND_PARAM_PREFIX = "multiband_param_"

        /** MultibandNode param_id 总数（3 段×六参 + fc1/fc2），与 native 对齐 */
        const val MULTIBAND_PARAM_COUNT = 20

        /**
         * 母带默认：0-17 每段六参 thr/ratio/atk/rel/knee/gain，18=fc1 200Hz、19=fc2 3.5kHz。
         * 真机实听定案（NX733J，DJ/电音源）：Low 由「持续整形」改为「峰值安全网」——
         * −26dB/2.5:1 持续压缩发闷且无力量，快释放（98ms）产生泵感「POPO」；
         * 高阈值+4:1+10/150ms 只在 kick 峰值动作，低音长音原样通过，实听力量感回归；
         * High 默认实听认可，Mid 未报问题维持 温和式。
         */
        val MULTIBAND_DEFAULTS = floatArrayOf(
            -11f, 4.0f, 10f, 150f, 6f, 0f,   // Low：峰值安全网（M8e 定案）
            -22f, 2.0f, 15f, 150f, 6f, 0f,   // Mid
            -20f, 2.0f, 5f, 120f, 6f, 0f,    // High
            200f, 3500f,
        )
        private val MULTIBAND_BAND_RANGES =
            arrayOf(-60f..0f, 1f..20f, 0.1f..100f, 5f..1000f, 0f..12f, -12f..12f)
        const val KEY_REVERB_ENABLED = "reverb_enabled"
    /** 混响组总闸：音效库「混响」卡片，关 = 卷积+算法同时旁通 */
    const val KEY_REVERB_SECTION_ENABLED = "reverb_section_enabled"
        const val KEY_REVERB_PRESET = "reverb_preset"
        const val KEY_STEREO_WIDTH_ENABLED = "stereo_width_enabled"

    const val KEY_VS_ENABLED = "vs_enabled"
    const val KEY_VS_MODE = "vs_mode"
    const val KEY_VS_SURROUND_GAIN = "vs_surround_gain"
    const val KEY_VS_CENTER_GAIN = "vs_center_gain"
    const val KEY_VS_FRONT_GAIN = "vs_front_gain"
    const val KEY_VS_REAR_GAIN = "vs_rear_gain"
    const val KEY_VS_REAR_SPACE = "vs_rear_space"
    const val KEY_VS_TRIMS = "vs_trims"
    const val KEY_VS_MUTES = "vs_mutes"
    const val KEY_VS_DEBUG_RAW = "vs_debug_raw"
    const val KEY_VS_ROOM_SIZE = "vs_room_size"
    const val KEY_VS_ROOM_WET = "vs_room_wet"
    const val KEY_VS_ENCODE_STRENGTH = "vs_encode_strength"
    const val KEY_VS_CENTER_MODE = "vs_center_mode"
    const val KEY_VS_DIALOG = "vs_dialog"
    const val KEY_VS_BASS_ENH = "vs_bass_enh"
    const val KEY_VS_BASS_ENH_MODE = "vs_bass_enh_mode"
    const val KEY_VS_REAR_CENTER = "vs_rear_center"
    const val KEY_VS_REAR_DELAY = "vs_rear_delay"

    // ---- 动态低音----
    /**
     * 动态低音（耳机专用）。设计见 `docs/M15_DYNAMIC_BASS_DESIGN.md`。
     *
     * 五个参数对应 native `DynamicBassNode` 的 param 0~4。
     * **跨语言契约**：与 `AudioEngine.DYNAMIC_BASS_PARAM_*` 及 C++ 常量三方一致。
     */
    const val KEY_DYNAMIC_BASS_ENABLED = "dynamic_bass_enabled"
    const val KEY_DYNAMIC_BASS_AMOUNT = "dynamic_bass_amount"
    const val KEY_DYNAMIC_BASS_SUBSONIC = "dynamic_bass_subsonic_hz"
    const val KEY_DYNAMIC_BASS_FOCUS = "dynamic_bass_focus_hz"
    const val KEY_DYNAMIC_BASS_BOOST_DEPTH = "dynamic_bass_boost_depth"
    const val KEY_DYNAMIC_BASS_PROTECT = "dynamic_bass_protect_level"

    // ---- 低频管家----
    /**
     * 低频管家**总闸**：音效库「低频管家」卡片上的开关。
     * 关 = 动态低音与虚拟低音同时旁通，两张槽卡记忆不动。
     */
    const val KEY_LOWFREQ_SECTION_ENABLED = "lowfreq_section_enabled"

    /**
     * 虚拟低音（心理声学低频扩展，**手机内置扬声器专用**）。
     *
     * 五个参数对应 native `VirtualBassNode` 的 param 0~4。
     * **跨语言契约**：与 `AudioEngine.VIRTUAL_BASS_PARAM_*` 及 C++ 常量三方一致。
     */
    const val KEY_VIRTUAL_BASS_ENABLED = "virtual_bass_enabled"
    const val KEY_VIRTUAL_BASS_AMOUNT = "virtual_bass_amount"
    const val KEY_VIRTUAL_BASS_HARMONIC = "virtual_bass_harmonic"
    const val KEY_VIRTUAL_BASS_MID_GAIN = "virtual_bass_mid_gain"
    const val KEY_VIRTUAL_BASS_HIGH_GAIN = "virtual_bass_high_gain"
    const val KEY_VIRTUAL_BASS_ENVELOPE_RATE = "virtual_bass_envelope_rate"

    /**
     * 低音增强（移植自 wecho `BassEffect` / UI 里的「低频质感」）。
     *
     * 三个参数：amount（总强度）、deep（下潜：动态增益 + 次谐波）、punch（瞬态）。
     * **与输出设备无关**，耳机与外放都能用。
     */
    const val KEY_BASS_ENHANCE_ENABLED = "bass_enhance_enabled"
    const val KEY_BASS_ENHANCE_AMOUNT = "bass_enhance_amount"
    const val KEY_BASS_ENHANCE_DEEP = "bass_enhance_deep"
    const val KEY_BASS_ENHANCE_PUNCH = "bass_enhance_punch"
    // 双模式：0 = 经典（低通+增益），1 = 动态（M/S+包络+瞬态+次谐波）
    const val KEY_BASS_ENHANCE_MODE = "bass_enhance_mode"
    const val KEY_BASS_ENHANCE_CLASSIC_GAIN = "bass_enhance_classic_gain"
    const val KEY_BASS_ENHANCE_CLASSIC_Q = "bass_enhance_classic_q"
    const val KEY_BASS_ENHANCE_CLASSIC_CENTER = "bass_enhance_classic_center"

    /**
     * **已废弃**（2026-11-11 用户要求「耳机下每次开启都要弹窗」）。
     *
     * 原设计是「确认一次后不再提醒」，但对照
     * `wecho_projectV2.5.1_studio_rack` 的实现后发现理解错了：
     * 那边根本没有 ack 记忆 —— 「关闭提醒」按钮的语义是**不开启**（等同于取消），
     * 每次在耳机上开启都会重新弹。
     *
     * 常量保留只为兼容：老版本写进 prefs 的这个键不会被读，也不会引发迁移问题。
     */
    @Deprecated("耳机下每次都弹窗，不再需要确认记忆")
    const val KEY_VIRTUAL_BASS_HEADPHONE_ACK = "virtual_bass_headphone_ack"
        const val KEY_STEREO_WIDTH = "stereo_width"
        const val KEY_USER_WET_GAIN = "user_wet_gain"
        const val KEY_REVERB_DRY = "reverb_dry"
        const val KEY_REVERB_LOWCUT = "reverb_lowcut"
        // IR 路径参数化：最近使用的 IR 文件（JSON 数组，name+file，≤5 条）
        const val KEY_RECENT_IR_FILES = "recent_ir_files"
        /** U6 最近装载队列（设计稿 P3 最近使用，JSONArray 保序，队首=最新） */
        const val KEY_RECENT_LOADED_IRS = "recent_loaded_irs"
        /** 当前激活的自定义 IR 显示名（最近列表勾选 UI 数据源） */
        const val KEY_ACTIVE_IR_NAME = "active_ir_name"
        /** 病态 IR 自动对齐（方案 A，默认开）：脏前置 >30ms 装载时裁除 */
        const val KEY_TRIM_SICK_HEAD = "trim_sick_head"
        /** 湿声电平自动校准（批次 2，默认开）：装载新 IR 时按形态设湿声电平 */
        const val KEY_WET_AUTO_CAL = "wet_auto_cal"
        /** 最近一次自动校准的 IR 名（同一 IR 不重复覆盖手动微调） */
        const val KEY_LAST_CAL_IR = "last_cal_ir"
        // FDN 算法混响（与卷积混响并列，独立效果器）
        const val KEY_FDN_ENABLED = "fdn_enabled"
        const val KEY_FDN_ROOM_SIZE = "fdn_room_size"
        const val KEY_FDN_HUMIDITY = "fdn_humidity"
        const val KEY_FDN_STEREO_WIDTH = "fdn_stereo_width"
        const val KEY_FDN_WET = "fdn_wet"
        const val KEY_FDN_DRY = "fdn_dry"
        const val KEY_FDN_MODE = "fdn_mode"
        const val APPOPS_IGNORE = "ignore"
        const val APPOPS_ALLOW = "allow"
        // EQ band 索引（与 native ParametricEqNode::kBandFreqs 对齐：band0=31Hz … band9=16kHz）
        const val BAND_BASS = 0
        const val BAND_TREBLE = 9
    }
}
