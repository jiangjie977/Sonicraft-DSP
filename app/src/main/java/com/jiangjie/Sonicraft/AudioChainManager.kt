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
import com.jiangjie.Sonicraft.core.engine.StopReason
import com.jiangjie.Sonicraft.privilege.ILoopbackProbe
import com.jiangjie.Sonicraft.privilege.LoopbackProbeCallback
import com.jiangjie.Sonicraft.privilege.PrivilegedClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
 * 用户下次打开 App 时，ViewModel 的完整 `applyEqSettings` 会再对齐一次。
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var restoring = false

    init {
        // 双后端：当前后端死亡 → PrivilegeBroker 自动切到另一个。
        // 旧链的 probe 绑在旧后端进程上，必须 stop + detach 后按新后端重建，
        // 否则旧 AudioPolicy 残留（双份 submix）或链路停摆。
        scope.launch {
            privileged.events.collect { ev ->
                diag("特权后端切换 ${ev.from} → ${ev.to}（${ev.reason}）")
                if (ev.allDead) {
                    diag("特权后端全灭：无法自动恢复，等待 UI 提示用户处理")
                    return@collect
                }
                if (!isLoopbackMode()) return@collect
                if (chain.isAlive) {
                    runCatching { chain.probe?.stop() }
                    chain.detach()
                    runCatching { engine.stop(StopReason.CAPTURE_DEAD) }
                    delay(500)
                }
                restoreLoopbackIfNeeded()
            }
        }
    }

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
            // headless 恢复成功必须撤掉开机瞬间挂的「内录授权失效」警示——
            // 重启后 Shevery 服务端晚起（binder 未到 → 首启探测失败挂通知），
            // 晚几秒恢复成功却不撤，用户会看到「已经恢复但通知还说失效」。
            runCatching { KeepAliveJobService.clearWarningNotification(context) }
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

    /** 绑定（或复用）Loopback UserService，最多等 8s（后端由 PrivilegeBroker 决定） */
    private suspend fun bindProbe(): ILoopbackProbe? {
        chain.probe?.let { return it }
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                privileged.bindLoopbackProbe(
                    processSuffix = PrivilegedClient.PROBE_SUFFIX,
                    version = PrivilegedClient.PROBE_VERSION,
                    callback = object : LoopbackProbeCallback {
                        override fun onConnected(probe: ILoopbackProbe) {
                            runCatching { probe.attachClient(clientBinder) }
                            if (cont.isActive) cont.resume(probe)
                        }

                        override fun onDisconnected() {
                            diag("headless restore: 服务断开")
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onFailed(errorCode: Int, message: String) {
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
            // 增益 / 平衡 / 宽度（含中性值旁路判断，与前台同一套语义）
            ParamReplay.applyGainBalanceWidth(engine, prefs)
            // Surround71：旁路 + enabled 参数 + 四参统一走表
            ParamReplay.applyS71(engine, prefs)
            // 虚拟环绕：旁路 + 全量参数统一走表（含 Ambisonic/HRTF/Trim/Mute，headless 此前全缺）
            ParamReplay.applyVs(engine, prefs)
            engine.setBypass(AudioEngine.STEREO_WIDTH_NODE_INDEX, !b("stereo_width_enabled", true))
            // 三个 Gain/Balance 节点的旁路：
            // 前台 applyEqSettings 有 setBypass，headless 此前只发 setParam ——
            // engine.prepare 后它们保持默认 bypass=true，用户调过的输入/输出增益
            // 与平衡会在「系统杀进程自愈」后静默失效。中性值时不启用，与前台语义一致。
            // 低频管家三节点（动态低音 / 低音增强 / 虚拟低音）
            ParamReplay.applyLowfreq(engine, prefs)

            // EQ：段数（eq_mode 即段数）+ 增益/Q/频率/类型/预增益，含 AutoEQ 旁路
            ParamReplay.applyEq(engine, prefs)

            // 卷积混响 + 算法混响（FDN）
            ParamReplay.applyReverb(engine, prefs)

            // 限幅 / 压限 / 多段（限幅器自带 true-peak 安全常量，一并走表）
            ParamReplay.applyLimiter(engine, prefs)
            // 自定义脚本效果器：恢复用户脚本（新节点默认旁路可重载）+ 旁路
            val scriptSrc = prefs.getString("script_effect_source", "") ?: ""
            if (scriptSrc.isNotEmpty()) engine.setScriptSource(scriptSrc)
            engine.setBypass(AudioEngine.SCRIPT_EFFECT_NODE_INDEX, !b("script_effect_enabled", false))
            // 单段压缩器（六参 + 侧链/混合/检测/前瞻/过采样）
            ParamReplay.applyCompressor(engine, prefs)
            // 效果组的旁通重放：遍历 [EffectGroups.all]，
            // 每组每槽都按「总闸 && 该槽记忆」下发。
            // 这样新增一个效果组只需在 EffectGroups 里加一条 spec ——
            // 不必再回来改这里，也就不会出现「加了新组忘了重放」。
            for (group in EffectGroups.all) {
                for (slot in group.slots) {
                    engine.setBypass(slot.nodeIndex, !group.shouldPlay(prefs, slot.key))
                }
            }
            ParamReplay.applyMultiband(engine, prefs)
        }.onFailure { diag("参数重放部分失败: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
