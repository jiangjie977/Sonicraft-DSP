/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import android.content.Context
import android.os.Binder
import android.os.IBinder
import com.jiangjie.Sonicraft.core.audio.AudioFormat
import com.jiangjie.Sonicraft.core.audio.EngineMode
import com.jiangjie.Sonicraft.core.common.DiagDirs
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import com.jiangjie.Sonicraft.privilege.ILoopbackProbe
import com.jiangjie.Sonicraft.privilege.LoopbackProbeUserService
import com.jiangjie.Sonicraft.privilege.PrivilegedClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import roro.stellar.userservice.ServiceMode
import roro.stellar.userservice.StellarUserService
import roro.stellar.userservice.UserServiceArgs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 进程级音频链路管理器。
 *
 * 为什么需要它：原先「建链/停链/参数重放」全部长在 `MainViewModel` 里，而 ViewModel
 * 会随 Activity 销毁。这导致两个后果：
 *  ① 从桌面重开 App 会误判链断并全量重建（已由 [LoopbackChainHolder] 缓解）；
 *  ② **前台服务无法自救** —— 息屏被杀后 `onStartCommand` 只能尝试拉起 Activity
 *     （Android 10+ 后台启动 Activity 受限），没有 Activity 就没有人建链。
 *
 * 本类把「无界面也能建链」这件事单独实现：不依赖 Compose、不依赖 ViewModel，
 * 由 [ForegroundDspService] 在判定「被系统终止」时直接调用。
 *
 * 参数重放只覆盖可序列化的标量项（增益/平衡/宽度/卷积/FDN/限幅/压限/EQ 10 段）；
 * 用户下次打开 App 时，ViewModel 的完整 `applyEqSettings()` 会再对齐一次。
 */
@Singleton
class AudioChainManager @Inject constructor(
    private val engine: AudioEngine,
    private val chain: LoopbackChainHolder,
    private val privileged: PrivilegedClient,
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences("sonicraft_settings", Context.MODE_PRIVATE)
    private val diagFile = File(DiagDirs.crashDir, "autostart_diag.txt")
    private val clientBinder: IBinder = Binder()

    @Volatile
    private var restoring = false

    /** 最近一次绑定失败码（-2 = 令牌不匹配） */
    @Volatile
    private var lastBindError = 0

    private fun diag(msg: String) {
        runCatching {
            diagFile.parentFile?.mkdirs()
            diagFile.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }
    }

    fun isLoopbackMode(): Boolean =
        (prefs.getString("audio_mode", "loopback") ?: "loopback") == "loopback"

    /** 链路是否真的在跑（Binder 在手 + 引擎 Running） */
    fun isChainAlive(): Boolean =
        chain.isAlive && engine.state.value is EngineState.Running

    /**
     * 无界面恢复 Loopback 链路（供前台服务在「被系统终止」时调用）。
     *
     * @return true = 已在跑或恢复成功；false = 不适用/失败
     */
    suspend fun restoreLoopbackIfNeeded(): Boolean {
        if (!isLoopbackMode()) {
            diag("headless restore: 跳过（当前不是 Loopback 模式）")
            return false
        }
        // 前台正在建链时**必须让路**：重复 bindUserService 会拿到 code=-2 令牌不匹配
        if (chain.building) {
            diag("headless restore: 前台正在建链，等待其完成（≤12s）")
            val waited = withTimeoutOrNull(12_000) {
                while (chain.building) kotlinx.coroutines.delay(200)
                true
            }
            if (waited != null && isChainAlive()) {
                diag("headless restore: 前台已建链完成，跳过")
                return true
            }
        }
        // 另一种「正在跑」：ViewModel 那边刚开始建链（Preparing），等它一会儿，
        // 不要抢着再绑一次（会造成 Stellar 重复绑定 + 令牌竞争）
        if (engine.state.value is EngineState.Preparing) {
            diag("headless restore: 引擎 Preparing，等待其完成（≤10s）")
            val done = withTimeoutOrNull(10_000) {
                while (engine.state.value is EngineState.Preparing) {
                    kotlinx.coroutines.delay(200)
                }
                true
            }
            if (done != null && isChainAlive()) {
                diag("headless restore: 链路已由前台建链完成，跳过")
                return true
            }
        }
        if (isChainAlive()) {
            diag("headless restore: 跳过（链路已在运行）")
            return true
        }
        if (restoring) return false
        restoring = true
        // 让前台路径等待（否则两边抢绑 → code=-2）
        chain.building = true
        return try {
            var probe = bindProbe()
            // code=-2（令牌不匹配）：旧 loopback_probe 进程仍持有上一版令牌。
            // 与前台路径同款自愈：清进程后重试一次。
            if (probe == null && lastBindError == -2) {
                diag("headless restore: 令牌不匹配 → 清理 loopback_probe 后重试一次")
                runCatching { privileged.runCommand("pkill", "-f", "loopback_probe") }
                kotlinx.coroutines.delay(1_500)
                probe = bindProbe()
            }
            if (probe == null) {
                diag("headless restore: 绑定 UserService 超时/失败（err=$lastBindError）")
                return false
            }
            val source = StellarLoopbackSource(
                probe = probe,
                sampleRate = 48_000,
                excludeUid = android.os.Process.myUid(),
                onError = { msg -> diag("headless restore 源错误: $msg") },
            )
            engine.prepare(EngineMode.CaptureRelay, AudioFormat.STEREO_48K)
            val ok = engine.startLoopback(source)
            if (!ok) {
                diag("headless restore: engine.startLoopback 返回 false")
                return false
            }
            chain.attach(probe, source)
            replaySettings()
            prefs.edit()
                .putBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, true)
                .putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, false)
                .apply()
            diag("headless restore: 成功（已重放用户参数）")
            true
        } catch (t: Throwable) {
            diag("headless restore 异常: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            restoring = false
            chain.building = false
        }
    }

    /** 绑定（或复用）Loopback UserService，最多等 8s */
    private suspend fun bindProbe(): ILoopbackProbe? {
        chain.probe?.let { return it }
        val args = UserServiceArgs.Builder(LoopbackProbeUserService::class.java)
            .processNameSuffix("loopback_probe")
            .versionCode(52L)
            .serviceMode(ServiceMode.DAEMON)
            .build()
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                StellarUserService.bindUserService(
                    args,
                    object : StellarUserService.ServiceCallback {
                        override fun onServiceConnected(service: IBinder) {
                            val probe = ILoopbackProbe.Stub.asInterface(service)
                            runCatching { probe.attachClient(clientBinder) }
                            if (cont.isActive) cont.resume(probe)
                        }

                        override fun onServiceDisconnected() {
                            diag("headless restore: 服务断开")
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceStartFailed(errorCode: Int, message: String) {
                            // code=-2 = 验证令牌不匹配（Stellar 复用旧 loopback_probe 进程），
                            // 落日志便于判断是否需要 pkill 后重试
                            lastBindError = errorCode
                            diag("headless restore: 服务启动失败 code=$errorCode $message")
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }
    }

    /**
     * 参数重放（标量项）。键名与 `MainViewModel` 持久化一致；读不到就用默认值跳过，
     * 因此不会把引擎写成异常状态。
     */
    private fun replaySettings() {
        fun f(key: String, def: Float) = prefs.getFloat(key, def)
        fun b(key: String, def: Boolean) = prefs.getBoolean(key, def)

        runCatching {
            // 增益 / 平衡 / 宽度
            engine.setParam(AudioEngine.INPUT_GAIN_NODE_INDEX, AudioEngine.GAIN_PARAM_GAIN, f("input_gain", 1f))
            engine.setParam(AudioEngine.OUTPUT_GAIN_NODE_INDEX, AudioEngine.GAIN_PARAM_GAIN, f("output_gain", 1f))
            engine.setParam(AudioEngine.BALANCE_NODE_INDEX, AudioEngine.BALANCE_PARAM_BALANCE, f("balance", 0f))
            engine.setParam(
                AudioEngine.STEREO_WIDTH_NODE_INDEX,
                AudioEngine.STEREO_WIDTH_PARAM_WIDTH,
                f("stereo_width", 1f),
            )
            engine.setBypass(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, !b("vs_enabled", false))
            // Surround71 环绕（与矩阵环绕互斥，prefs 里两者不会同时为 true）
            engine.setBypass(AudioEngine.S71_NODE_INDEX, !b("s71_enabled", false))
            engine.setParam(
                AudioEngine.S71_NODE_INDEX,
                AudioEngine.S71_PARAM_ENABLED,
                if (b("s71_enabled", false)) 1f else 0f,
            )
            engine.setParam(
                AudioEngine.S71_NODE_INDEX,
                AudioEngine.S71_PARAM_UPMIX,
                prefs.getFloat("s71_upmix", 0.85f),
            )
            engine.setParam(
                AudioEngine.S71_NODE_INDEX,
                AudioEngine.S71_PARAM_EXTERNALIZE,
                prefs.getFloat("s71_externalize", 0.35f),
            )
            engine.setParam(
                AudioEngine.S71_NODE_INDEX,
                AudioEngine.S71_PARAM_BASS_BOOST_DB,
                prefs.getFloat("s71_bass_boost", 2.0f),
            )
            engine.setParam(
                AudioEngine.S71_NODE_INDEX,
                AudioEngine.S71_PARAM_BASS_MANAGEMENT,
                prefs.getFloat("s71_bass_mgmt", 0.25f),
            )
            // 必须同时下发 enabled：节点内该参数默认 0，仅解除 bypass 仍会直通，
            // 表现为"开关显示开启却没有效果、手动切一次才生效"（开机初始化缺陷）。
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_ENABLED,
                if (b("vs_enabled", false)) 1f else 0f,
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_MODE,
                f("vs_mode", 0f),
            )
            engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_SURROUND_GAIN, 0f)
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_BS775,
                if (b("vs_bs775", false)) 1f else 0f,
            )
            engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_CENTER_GAIN, 0f)
            engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_FRONT_GAIN, 0f)
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_DISTANCE,
                f("vs_distance", 0.5f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_HEIGHT_STRENGTH,
                f("vs_height", 0.5f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_STEREO_EXPAND,
                f("vs_rear_space", 0.5f),
            )
            engine.setParam(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, AudioEngine.VS_PARAM_REAR_GAIN, 0f)
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_ROOM_SIZE,
                f("vs_room_size", 0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_ROOM_WET_DRY,
                f("vs_room_wet", 0.35f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_ENCODE_STRENGTH,
                f("vs_encode_strength", 0.5f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_CENTER_MODE,
                f("vs_center_mode", 1f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_DIALOG,
                f("vs_dialog", 0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_BASS_ENHANCER,
                f("vs_bass_enh", 0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_BASS_ENHANCER_MODE,
                f("vs_bass_enh_mode", 0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_REAR_CENTER_ENABLED,
                f("vs_rear_center", 0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_REAR_CENTER_DELAY_MS,
                f("vs_rear_delay", 5f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_SURROUND_NODE_INDEX,
                AudioEngine.VS_PARAM_ENABLED,
                if (b("vs_enabled", false)) 1f else 0f,
            )
            engine.setBypass(AudioEngine.STEREO_WIDTH_NODE_INDEX, !b("stereo_width_enabled", true))
            // 三个 Gain/Balance 节点的旁路（2026-11-11 双路径一致性检查检出）：
            // 前台 applyEqSettings 有 setBypass，headless 此前只发 setParam ——
            // engine.prepare() 后它们保持默认 bypass=true，用户调过的输入/输出增益
            // 与平衡会在「系统杀进程自愈」后静默失效。中性值时不启用，与前台语义一致。
            engine.setBypass(AudioEngine.INPUT_GAIN_NODE_INDEX, f("input_gain", 1f) == 1f)
            engine.setBypass(AudioEngine.BALANCE_NODE_INDEX, f("balance", 0f) == 0f)
            engine.setBypass(AudioEngine.OUTPUT_GAIN_NODE_INDEX, f("output_gain", 1f) == 1f)

            // 动态低音：五个参数 + 旁路。
            // 任何一处漏重放都会导致"重启后静默回默认"——本项目已中过两次
            // （重放只发 param 0、compressor_sidechain_hp 无写入端），所以逐个列全。
            engine.setParam(
                AudioEngine.DYNAMIC_BASS_NODE_INDEX,
                AudioEngine.DYNAMIC_BASS_PARAM_AMOUNT, f("dynamic_bass_amount", 0.35f),
            )
            engine.setParam(
                AudioEngine.DYNAMIC_BASS_NODE_INDEX,
                AudioEngine.DYNAMIC_BASS_PARAM_SUBSONIC_HZ, f("dynamic_bass_subsonic_hz", 30f),
            )
            engine.setParam(
                AudioEngine.DYNAMIC_BASS_NODE_INDEX,
                AudioEngine.DYNAMIC_BASS_PARAM_FOCUS_HZ, f("dynamic_bass_focus_hz", 80f),
            )
            engine.setParam(
                AudioEngine.DYNAMIC_BASS_NODE_INDEX,
                AudioEngine.DYNAMIC_BASS_PARAM_BOOST_DEPTH, f("dynamic_bass_boost_depth", 0.5f),
            )
            engine.setParam(
                AudioEngine.DYNAMIC_BASS_NODE_INDEX,
                AudioEngine.DYNAMIC_BASS_PARAM_PROTECT_LEVEL, f("dynamic_bass_protect_level", 0.6f),
            )
            // 注：动态低音/虚拟低音的 bypass 由下面的表驱动循环统一处理
            // （EffectGroups.all 遍历），这里只重放各自的**参数**。

            // 低音增强：三个参数逐个列全。
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_AMOUNT, f("bass_enhance_amount", 0.5f),
            )
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_DEEP, f("bass_enhance_deep", 0.3f),
            )
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_PUNCH, f("bass_enhance_punch", 0.6f),
            )
            // 双模式：模式 + 经典模式三个参数。
            // **两套参数都要重放**（不只是当前模式那套）——否则切模式后
            // 另一套参数会回默认，用户会觉得"切回去音色变了"。
            // **必须用 getInt 而不是 f()**：
            // `bass_enhance_mode` 在 prefs 里是 Int（setBassEnhanceMode 用 putInt），
            // 而 `f()` = prefs.getFloat —— Android 对 Int 键调 getFloat 会抛
            // ClassCastException，被外层 runCatching 吞掉 →
            // **整个重放块静默中断**，后面所有参数都不下发。
            // 这类错误没有日志、没有崩溃，只有"某些参数重启后回默认"的症状。
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_MODE,
                prefs.getInt("bass_enhance_mode", 1).toFloat(),
            )
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_GAIN,
                f("bass_enhance_classic_gain", 6f),
            )
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_Q,
                f("bass_enhance_classic_q", 0.7071f),
            )
            engine.setParam(
                AudioEngine.BASS_ENHANCE_NODE_INDEX,
                AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_CENTER,
                f("bass_enhance_classic_center", 60f),
            )

            // 虚拟低音：五个参数逐个列全。
            engine.setParam(
                AudioEngine.VIRTUAL_BASS_NODE_INDEX,
                AudioEngine.VIRTUAL_BASS_PARAM_AMOUNT, f("virtual_bass_amount", 1.0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_BASS_NODE_INDEX,
                AudioEngine.VIRTUAL_BASS_PARAM_HARMONIC, f("virtual_bass_harmonic", 1.0f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_BASS_NODE_INDEX,
                AudioEngine.VIRTUAL_BASS_PARAM_MID_GAIN, f("virtual_bass_mid_gain", 0.08f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_BASS_NODE_INDEX,
                AudioEngine.VIRTUAL_BASS_PARAM_HIGH_GAIN, f("virtual_bass_high_gain", 0.16f),
            )
            engine.setParam(
                AudioEngine.VIRTUAL_BASS_NODE_INDEX,
                AudioEngine.VIRTUAL_BASS_PARAM_ENVELOPE_RATE,
                f("virtual_bass_envelope_rate", 50f),
            )

            // EQ：段数固定 10，曲线从 CSV 解析
            engine.setBypass(AudioEngine.EQ_NODE_INDEX, !b("eq_enabled", false))
            prefs.getString("eq_bands_10", null)?.split(',')?.forEachIndexed { i, s ->
                s.trim().toFloatOrNull()?.let { engine.setParam(AudioEngine.EQ_NODE_INDEX, i, it) }
            }
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, 10f)

            // 卷积混响
            engine.setParam(
                AudioEngine.CONVOLVER_NODE_INDEX,
                AudioEngine.CONVOLVER_PARAM_PRESET,
                prefs.getInt("reverb_preset", AudioEngine.REVERB_PRESET_SMALL_ROOM).toFloat(),
            )
            engine.setParam(
                AudioEngine.CONVOLVER_NODE_INDEX,
                AudioEngine.CONVOLVER_PARAM_WET,
                f("user_wet_gain", 1f),
            )
            engine.setBypass(AudioEngine.CONVOLVER_NODE_INDEX, !b("reverb_enabled", false))

            // 算法混响（FDN）
            val rv = AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX
            engine.setParam(rv, AudioEngine.REVERB_PARAM_ROOM_SIZE, f("fdn_room_size", 0.5f))
            engine.setParam(rv, AudioEngine.REVERB_PARAM_HUMIDITY, f("fdn_humidity", 0.5f))
            engine.setParam(rv, AudioEngine.REVERB_PARAM_STEREO_WIDTH, f("fdn_stereo_width", 1f))
            engine.setParam(rv, AudioEngine.REVERB_PARAM_WET, f("fdn_wet", 0.3f))
            engine.setParam(rv, AudioEngine.REVERB_PARAM_DRY, f("fdn_dry", 1f))
            engine.setParam(rv, AudioEngine.REVERB_PARAM_MODE, f("fdn_mode", 0f))
            engine.setBypass(rv, !b("fdn_enabled", false))

            // 限幅 / 压限 / 多段
            engine.setParam(AudioEngine.LIMITER_NODE_INDEX, 0, f("limiter_ceiling", -1f))
            engine.setBypass(AudioEngine.LIMITER_NODE_INDEX, !b("limiter_enabled", true))
            // 自定义脚本效果器（2026-11-12 原型）：恢复用户脚本（新节点默认旁路可重载）+ 旁路
            val scriptSrc = prefs.getString("script_effect_source", "") ?: ""
            if (scriptSrc.isNotEmpty()) engine.setScriptSource(scriptSrc)
            engine.setBypass(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, !b("script_effect_enabled", false))
            // 单段压缩器：**全参数重放**
            // 原来只发了 param 0（threshold，且默认值写成 0f 应为 -20f），
            // ratio/attack/release/makeup 在重启后全部回到 node 内默认值——用户调过的
            // 压缩比会静默丢失。这里按 prefs 逐一重放六参。
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_THRESHOLD_DB, f("compressor_threshold", -20f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_RATIO, f("compressor_ratio", 4f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_ATTACK_MS, f("compressor_attack_ms", 10f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_RELEASE_MS, f("compressor_release_ms", 150f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_KNEE_DB, f("compressor_knee_db", 6f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_MAKEUP_DB, f("compressor_makeup_db", 0f),
            )
            // 新增四项 + 侧链高通（param 6 此前也漏了重放）：
            // 这五个参数若漏重放，重启后会静默回默认。
            // 侧链高通原先更是**只有重放没有写入端**（永远读到默认 120），
            // 已补 `setCompressorSidechainHp` 与 UI 档位，此处是它的持久化读端。
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_SIDECHAIN_HP_HZ, f("compressor_sidechain_hp", 120f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_MIX, f("compressor_mix", 1f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_DETECT_MODE, f("compressor_detect_mode", 0f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_LOOKAHEAD_MS, f("compressor_lookahead_ms", 0f),
            )
            engine.setParam(
                AudioEngine.COMPRESSOR_NODE_INDEX,
                AudioEngine.COMPRESSOR_PARAM_OVERSAMPLE, f("compressor_oversample", 1f),
            )
            // 效果组的旁通重放（2026-11-11 **表驱动**）：遍历 [EffectGroups.all]，
            // 每组每槽都按「总闸 && 该槽记忆」下发。
            // 这样新增一个效果组只需在 EffectGroups 里加一条 spec ——
            // 不必再回来改这里，也就不会出现「加了新组忘了重放」。
            for (group in EffectGroups.all) {
                for (slot in group.slots) {
                    engine.setBypass(slot.nodeIndex, !group.shouldPlay(prefs, slot.key))
                }
            }
            for (i in 0..20) {
                val key = "multiband_param_$i"
                if (prefs.contains(key)) {
                    engine.setParam(AudioEngine.MULTIBAND_NODE_INDEX, i, prefs.getFloat(key, 0f))
                }
            }
        }.onFailure { diag("参数重放部分失败: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
