/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Process
import com.jiangjie.Sonicraft.core.common.DiagDirs
import java.io.File
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import com.jiangjie.Sonicraft.core.engine.StopReason
import com.jiangjie.Sonicraft.privilege.CommandResult
import com.jiangjie.Sonicraft.privilege.PrivilegedClient
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3.1 自动接管控制器：内录运行中轮询系统活跃播放列表，对「正在播放媒体」的第三方 app
 * 自动静音其原声，引擎内录副本替代——双声变单声。
 *
 * 静音双轨阶梯：
 *  ① APPOPS 轨（主）：uid 级 AppOps PLAY_AUDIO ignore，系统服务层禁止播放，对
 *     AAudio Exclusive/MMAP 低延迟路径同样有效；带 appops get 回读验证
 *     （某些 ROM set 返回 0 但未生效，WEcho/RocoDSP/本 app 三方实测同坑）。
 *  ② DP_SESSION 轨（回退）：① 回读失败时下探——对该 uid 的活跃 audio session 挂
 *     DynamicsProcessing 输入增益 -200dB（WEcho MuteEffectFactory 同款模式）。
 *     已知局限：对部分直播/独占 session 挂载失败（Error -3，2026-10-22 实测），
 *     失败进 30s 冷却；自愈靠 500ms 轮询补挂（替代 WEcho 的反射 listener），
 *     补挂失败则干净放弃，行为退化为无阶梯现状（双声），不会更糟。
 *
 * 铁律（对应 2026-10-21 无声事故与 2026-10-22 用户「通话优先」要求）：
 *  1. 只接管 USAGE_MEDIA / USAGE_GAME；通话/铃声/闹钟/通知等一律不碰。手机通话音频走
 *     modem 直连不经过本链路，铃声/闹钟的 usage 不在白名单，天然隔离。
 *  2. 通话保护（混合 app 正解）：appops 静音是包级全局——微信视频号被接管后其语音通话也会
 *     哑掉。一旦该 uid 出现白名单外音频，500ms 内按轨回滚原声并进入冷却；通话结束后冷却
 *     到期自动恢复接管。DP 轨只静音媒体 session、VOIP 走独立 session 天然不受影响，
 *     但保守起见两轨统一按白名单外 usage 回滚。
 *  3. ignore 是持久化 AppOps，APPOPS 轨接管写入 prefs 留痕；DP 轨 effect 随进程死亡
 *     自动消失，无需留痕。冷启动先回滚上次残留（进程被杀/崩溃兜底）；stopAndRollback
 *     按轨恢复全部。
 *
 * 实测依据（2026-10-22 NX733J）：ignore 后该 app 仍保持 state:started（活跃列表稳定，
 * 无检测振荡），仅 mutedState 变 appOps；**capture 数据流不受影响**（副本照常出声，
 * 这是「接管替代」成立的根本前提）。
 *
 * 禁抓 app 音效轨（2026-11-03 豆包案定稿）：部分 app 对媒体流运行时调
 * setAllowedCapturePolicy(NONE)（豆包朗读流服务端配置驱动，fresh tap 对照实验实锤：
 * 同分钟内豆包投影恒零、酷狗湿声正常）——投影抓不到 → appops 静音 + 引擎无副本 =
 * 「压原声几秒→保险丝回滚直出」的差体验，且永远无音效。对策三件套：
 *  ① 首次接管后 3s 观察窗：接管时电平非零才启动（防 A16 喂零期误标），窗内恒零 →
 *     判定该 app 禁抓 → 回滚 appops 保直出 → 进禁抓名单（包名+时间戳持久化）。
 *  ② 名单命中 → 不走 appops（压原声无意义），直接对该 uid 的 session 挂 DP EQ
 *     效果（人声提亮）——per-session AudioEffect 走 AudioFlinger 效果链，
 *     capturePolicy 管不住（RootlessJamesDSP 对豆包实测挂载生效，2026-11-03）。
 *     原声直出 + EQ 音效，无缝。已知局限：多源并发时观察窗电平含他源声，无法
 *     归因（不判定，退化为现状保险丝行为）；豆包恰逢喂零期接管时电平已零，不启动
 *     观察（交给全局保险丝）——残余误标场景由 24h TTL 重试兜底自愈。
 *  ③ 名单 24h TTL：过期自动移出，下次重新走观察窗判定（服务端开关回摆自动回归
 *     投影轨全链路音效）。
 */
@Singleton
class TakeoverController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privileged: PrivilegedClient,
    private val engine: AudioEngine,
    // 夜间心跳用它上报「链路是否还在手」：息屏待机是否音效失效的取证，
    // 需要区分「链路断了」与「链路在但 HAL 侧 standby」。
    private val loopbackChain: LoopbackChainHolder,
) {
    /** 静音轨道：uid 级 AppOps（主）/ session 级 DynamicsProcessing（回退） */
    private enum class MuteTrack { APPOPS, DP_SESSION }

    /**
     * P2：Loopback 输入模式。
     *
     * 该模式下输入来自特权侧 `AudioPolicy + AudioMix(LOOP_BACK)`——走 AudioService
     * 混音层，能抓到 `ALLOW_CAPTURE_BY_SYSTEM` + `FLAG_NO_MEDIA_PROJECTION(0x400)` 的流
     * （酷狗实测电平 0.1195）。因此：
     *  - **禁抓名单不适用**：policy=2 不再等于「抓不到」；
     *  - **policy 权威表也不适用**：它描述的是 MediaProjection 的可抓性。
     * 两者在此模式下统一视为「可抓」，一律走接管静音（appops 主 / DP 回退），
     * 否则原声不静音 → 原声 + DSP 双声。
     */
    @Volatile
    private var loopbackMode = false

    /** Loopback 模式下已记过 DP 静音日志的 uid（每轮只记一次，避免 500ms 刷屏） */
    private val dpSilencedNotified: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ===== Loopback 专用：按「会话」静音（2026-11-05 视频模式双声案）=====
    // 同一 uid 可同时有多个音频会话（酷狗：音乐 1457 / 视频 1785），而 uid→单个
    // session 的映射只能盖住其一 → 未被覆盖的会话原声照出 = 双声（视频前台实锤）。
    // 故 loopback 模式按 session 逐个挂 DP，并跟踪 session→uid 以便回收。

    /** sid → 静音效果 */
    private val loopbackMutes = ConcurrentHashMap<Int, DynamicsProcessing>()

    /** sid → 所属 uid（用于只回收该 uid 的会话） */
    private val loopbackSessionUid = ConcurrentHashMap<Int, Int>()

    // ===== DP 连坐保险（2026-11-05 网易云全静音事故）=====
    // 事实现象：挂 DP -200dB 后，部分 app 的原声与 LOOP_BACK 副本**一起**被压掉
    // （网易云：切过去先有声 → 接管挂 DP → 全静音），而酷狗副本能存活。
    // 无论机制差异如何，「全静音」都不可接受 —— 故挂载后 1.5s 复查电平：
    //   电平崩到 ~0 → 判连坐，释放该 uid 全部静音并标记 unfit（退回原声直出）
    //   电平保留    → 判定通过，保留静音
    /** 待验证：sid → 挂载时刻（elapsedRealtime） */
    private val dpVerifyPending = ConcurrentHashMap<Int, Long>()

    /** 挂载前电平基线（一批挂载共用） */
    @Volatile
    private var dpLevelBefore = -1f

    /** 判定 DP 会连坐副本的 uid：不再挂静音（宁可双声，绝不静音） */
    private val dpUnfitUids: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 「等待副本数据」日志去重集（与 dpSilencedNotified 分开，避免吞掉后续接管日志） */
    private val dpWaitNotified: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 挂载后副本连续归零的轮数（uid → streak），用于持续静音监测 */
    private val dpSilenceStreak = ConcurrentHashMap<Int, Int>()

    /** 曾见过副本数据的 uid（建链初期零电平不判连坐，避免误撤预静音） */
    private val dpSawAudio: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 启动时检测到的「上次残留的 DP 静音效果」提示（非 null 时 UI 应提示重启） */
    private val _residueWarning = MutableStateFlow<String?>(null)
    val residueWarning: StateFlow<String?> = _residueWarning.asStateFlow()

    /**
     * 残留 DP 自检（2026-11-05 孤儿效果事故）。
     *
     * App 在「已静音某 app」状态下被 force-stop / 重装时，DynamicsProcessing 可能
     * 不随进程消失（dump 可见 `0 Clients` 的孤儿效果），继续把目标 app 压到 -200dB ——
     * 用户表现为「某个 app 突然没声音，只能重启」。这里在每次启动接管时扫一次
     * AudioFlinger 效果链：**我们此刻还没挂任何效果**，却已经存在 DynamicsProcessing，
     * 那就是上次的残留，必须明确告知用户（无法跨进程回收别人的效果）。
     */
    private fun detectResidualDp() {
        val dump = privileged.runCommand("dumpsys", "media.audio_flinger")
        if (dump.exitCode != 0) return
        val count = Regex("""name:\s*DynamicsProcessing""").findAll(dump.stdout).count()
        if (count > 0 && loopbackMutes.isEmpty() && dpEffects.isEmpty()) {
            _residueWarning.value =
                "检测到 $count 个上次残留的静音效果（可能导致某些 app 无声），建议重启手机清除"
            writeDiag("残留 DP 自检：发现 $count 个孤儿 DynamicsProcessing（上次进程被强杀遗留）")
        } else if (count == 0) {
            _residueWarning.value = null
        }
    }

    /** 引擎停止/失败时立即释放静音（防「引擎没了但原声还压着」） */
    fun releaseLoopbackMutesNow() {
        // 引擎已停/失败：重路由状态不再成立，下次建链重新判定
        loopbackRerouted = false
        if (loopbackMutes.isEmpty() && dpEffects.isEmpty()) return
        releaseLoopbackMutes()
        for (fx in dpEffects.values) runCatching { fx.enabled = false; fx.release() }
        dpEffects.clear()
        dpIsEq.clear()
        dpEffectSessions.clear()
        autoTakenOverUids.clear()
        writeDiag("引擎停止：已释放全部静音效果（防残留）")
    }

    /**
     * 远程静音桥（2026-11-05 根治孤儿 DP）。
     *
     * 静音效果改由 **UserService（shell 常驻）** 持有：app 进程被强杀/崩溃时，
     * 服务通过 linkToDeath 立刻释放，不会留下 `0 Clients` 的孤儿 DynamicsProcessing
     * 把用户的声音一直压着。非 null 时优先走远程，null 时退回本地 DP（旧路径）。
     */
    interface LoopbackMuteBridge {
        fun mute(sids: IntArray): Boolean
        fun releaseAll()
    }

    @Volatile
    var loopbackMuteBridge: LoopbackMuteBridge? = null

    /**
     * 链路级「已重路由」标志（，2026-11-06）。
     *
     * true = 已确认原声被搬进我方 submix（活跃会话 deviceIds 命中我方 Port）→
     * 整条链路**不需要任何静音**，`syncLoopbackMute` 直接返回并释放既有静音。
     * 依据：万象现场 mix 存在时酷狗 deviceIds 指向其 submix、DP 恒 0、单声。
     */
    @Volatile
    var loopbackRerouted: Boolean = false

    /**
     * 我方 mix 的 Remote Submix Out 端口号（-1 = 未知）。
     * 由建链后的重路由校验解析 `port=NNN` 写入；轮询里用它判断
     * 「该会话是否已被重路由到我们这边」，命中则立刻释放静音。
     */
    @Volatile
    var loopbackSubmixPort: Int = -1

    /** 设备卡展示用的「路由真相」文案：port 号 + 是否命中 */
    private val _routeSummary = MutableStateFlow("路由：未建链")
    val routeSummary: StateFlow<String> = _routeSummary.asStateFlow()

    fun updateRouteSummary(text: String) {
        _routeSummary.value = text
    }

    /**
     * 建链前预静音（app 进程内建效果）。
     *
     * 为什么必须 app 侧建：实测该 ROM 上 **shell(2000) 进程创建 DynamicsProcessing 会报
     * `Error: -3 (BAD_VALUE)`，而同一 session 由 app 进程创建成功** —— 会话级效果被
     * 限制为「会话所属 app 才能建」。所以托管到 UserService 的思路在本机行不通，
     * 预静音只能由 app 自己做（这正好也消除了「启动后 2.5~4s 双声」）。
     *
     * 归属记 0（预静音），后续 takeover 正式接管同一 sid 时会「认领」成真实 uid。
     */
    fun preMuteLocal(sids: IntArray): Int {
        var n = 0
        for (sid in sids) {
            if (sid <= 0 || loopbackMutes.containsKey(sid)) continue
            val fx = makeDpMuteEffect(sid) ?: continue
            loopbackMutes[sid] = fx
            loopbackSessionUid[sid] = 0
            n++
        }
        if (n > 0) writeDiag("预静音(app侧)：挂载 $n 个会话 ${sids.joinToString(",")}")
        return n
    }

    /** 释放某 uid 的全部静音（远程托管时直接全量释放，语义更安全） */
    private fun releaseUidMutes(uid: Int) {
        val bridge = loopbackMuteBridge
        if (bridge != null) {
            runCatching { bridge.releaseAll() }
            loopbackMutes.clear()
            loopbackSessionUid.clear()
            return
        }
        for ((sid, owner) in loopbackSessionUid.toList()) {
            if (owner != uid) continue
            loopbackMutes.remove(sid)?.let { fx ->
                runCatching { fx.enabled = false; fx.release() }
            }
            loopbackSessionUid.remove(sid)
        }
    }

    /** 读 native 水位日志的最近 inputLevel（读不到返回 -1） */
    private fun currentInputLevel(): Float = runCatching {
        val f = File(DiagDirs.crashDir, "watermark_log.txt")
        if (!f.exists()) return@runCatching -1f
        f.readLines().lastOrNull { it.contains("inputLevel=") }
            ?.substringAfter("inputLevel=")?.substringBefore(' ')?.toFloatOrNull() ?: -1f
    }.getOrDefault(-1f)

    /** 挂载 1.5s 后复查电平，判定 DP 是否连坐了 LOOP_BACK 副本 */
    private fun verifyDpMute(uid: Int) {
        val pend = dpVerifyPending.filterKeys { loopbackSessionUid[it] == uid }
        if (pend.isEmpty()) return
        val oldest = pend.values.minOrNull() ?: return
        val age = SystemClock.elapsedRealtime() - oldest
        if (age < DP_VERIFY_MS) return

        val lvl = currentInputLevel()
        if (dpLevelBefore < 0.01f) {
            // 挂载时副本本来就没数据（引擎刚启动/源未就绪）→ 无法判定连坐。
            // 保守释放：宁可双声/直出，绝不留下「压掉唯一音源」的静音风险。
            releaseUidMutes(uid)
            writeDiag("DP 保险：uid=$uid 基线为零无法判定连坐 → 保守释放静音（下轮有数据后再试）")
            dpVerifyPending.keys.removeIf { loopbackSessionUid[it] == null || loopbackSessionUid[it] == uid }
            dpLevelBefore = -1f
            return
        }
        if (lvl >= 0f && lvl < 0.005f) {
            releaseUidMutes(uid)
            dpUnfitUids.add(uid)
            writeDiag(
                "DP 保险：uid=$uid 电平 ${"%.4f".format(dpLevelBefore)}→${"%.4f".format(lvl)} " +
                    "→ 判定连坐副本，已释放静音并标记该 uid 退回直出（宁双声不静音）",
            )
        } else {
            writeDiag(
                "DP 保险：uid=$uid 电平 ${"%.4f".format(dpLevelBefore)}→${"%.4f".format(lvl)} " +
                    "→ 副本保留，静音保持",
            )
        }
        dpVerifyPending.keys.removeIf { loopbackSessionUid[it] == null || loopbackSessionUid[it] == uid }
        dpLevelBefore = -1f
    }

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // P3-3 省电：屏幕状态驱动的轮询间隔（亮屏 500ms 响应优先；灭屏 2.5s 省电）
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    // 教训：dumpsys 间歇残缺（ROM freezer 限制，79KB→16KB）时已接管 uid 会
    // 「集体消失」——消失超时回滚误判停播并回滚静音=双声。残缺轮跳过消失判定。
    private var lastHealthyDumpBytes = 0
    // dump 失败后的第一个成功轮不信任空活跃列表（audioserver/播放活动恢复中），跳过消失判定
    private var lastDumpFailed = false

    /** 本会话已自动接管的 uid → 轨道（内存态，回滚按轨分派）。
     * （2026-11-03 审计）：CAPTURE_DEAD 止损 collect 协程与 poll 协程并发读写，
     *  HashMap 无同步 = CME/撕裂读风险，统一 ConcurrentHashMap（弱一致遍历安全）。 */
    private val autoTakenOverUids = ConcurrentHashMap<Int, MuteTrack>()

    /** 自愈探针轮次计数（pollOnce 递增，每 SELF_AUDIT_EVERY_POLLS 轮自检一次） */
    private var selfAuditCounter = 0L

    /** 自身播放权限自愈累计（会话内；高频递增说明系统在持续对抗 → 横幅文案升级） */
    private var selfAudioHealCount = 0L

    /** 自愈探针通道连续失败计数（：成功即清零；首次+每 12 次≈2 分钟写一行 diag） */
    private var selfAuditFailStreak = 0L

    /** DP 轨：uid → 已挂载的效果（静音 DynamicsProcessing / 音效 Equalizer，基类统一
     * release/enable）。：与止损协程并发，用并发容器 */
    private val dpEffects = ConcurrentHashMap<Int, android.media.audiofx.AudioEffect>()

    /** DP 轨挂载失败冷却：sessionId → 冷却到期时间戳（WEcho 同款 30s，避免疯狂重试） */
    private val dpFailedSessions = ConcurrentHashMap<Int, Long>()

    /** EQ 轨停用期（2026-11-04 用户指令）已 restore allow 的名单 uid：去重防每轮空跑
     *  特权命令。setAppopsForUid 本身幂等，此 Set 只省日志与命令量。 */
    private val eqDisabledRestoredUids: MutableSet<Int> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** companion 包（<packageName>.submix，REMOTE_SUBMIX 输出端点）uid。2026-11-04
     *  全新安装前发现：它对输出流的 USAGE_MEDIA 主动播会被接管扫描当普通媒体 app
     *  mute（dumpsys appops 实锤 PLAY_AUDIO ignore）=掐死投影输出链——必须排除。 */
    private val submixUid: Int? by lazy {
        runCatching {
            context.packageManager.getPackageUid("${context.packageName}.submix", 0)
        }.getOrNull()
    }

    /** DP 轨效果种类：uid → true=EQ 音效（禁抓 app）/false（缺省）=静音（appops 回退）。
     *  healDpTracks 补挂与清理按种类分派，混用会静音禁抓 app 的直出原声。 */
    private val dpIsEq = ConcurrentHashMap<Int, Boolean>()

    /** DP 轨挂载时的 audioSession id（AudioEffect 无公开 sessionId getter，检测变化需自记）。
     * 切歌案：切歌/重开换 session，旧效果挂死 session = 音效静默失效。 */
    private val dpEffectSessions = ConcurrentHashMap<Int, Int>()

    /** 禁抓观察窗：uid → 接管时刻（elapsedRealtime ms）。仅 APPOPS 轨、接管时电平非零才启动 */
    private val captureWatch = ConcurrentHashMap<Int, Long>()

    // ===== 2026-11-04 息屏案修复：观察窗回滚验证 + 保险丝拓扑解除 + EQ session 重挂 =====

    /** 疑似禁抓待验证：uid → 回滚时刻（elapsedRealtime ms）。观察窗期满恒零只回滚暂不落名单，
     *  回滚后电平恢复=可抓（误判，撤销）；仍零超阈值=真禁抓（落名单）。
     *  Why: 全局电平无法归因——被我们 mute 的可抓 app 投影无源恒零，被他源消失放大后
     *  与真禁抓不可区分（酷狗 21:24 误标实锤，用户「上一秒有音效下一秒直声」）。
     *  How to apply: runCaptureWatch 窗期满恒零 → 回滚+记入此表；后续轮验证后撤销或落名单。 */
    private val pendingBlockVerify = ConcurrentHashMap<Int, Long>()

    /** 最近一轮 dumpsys 的全量活跃 uid 快照（pollOnce 每轮更新；挂起空转轮也更新）。
     *  供保险丝挂起记录拓扑基线 + EQ session 变化检测。 */
    @Volatile
    private var activeUidsSnapshot: Set<Int> = emptySet()

    /** 保险丝挂起时刻（elapsedRealtime ms）与挂起时活跃拓扑基线——拓扑变化解除挂起用 */
    private var suspendSince = 0L
    private var suspendActiveUids: Set<Int> = emptySet()

    /** 挂起解除防抖：拓扑变化解除需挂起满此时长（避免断续流高频拓扑变化把挂起打穿） */
    private val SUSPEND_MIN_MS = 30_000L

    /** 回滚验证时长：疑似禁抓回滚后电平仍零超此时长才落名单 */
    private val BLOCK_VERIFY_MS = 2_000L


    /** 通话保护冷却：uid → 冷却到期时间戳（到期自动解禁，允许重新接管） */
    private val cooldownUids = ConcurrentHashMap<Int, Long>()

    /** 接管中 uid 的最后活跃时间（uid 从活跃列表消失超时即回滚——微信语音通话场景：
     *  通话时微信媒体停 + VOIP 不入 dumpsys 播放列表，uid 消失导致通话保护分支
     *  （按 usage 触发）永不执行，appops ignore 残留 → 挂断后微信媒体被静音） */
    private val lastSeenUids = ConcurrentHashMap<Int, Long>()

    // 进程级 scope：轮询与保险丝必须与 engine 同生命周期。
    // 教训（2026-11-03 双声）：曾寄宿 viewModelScope，Activity 被回收即取消 →
    // 接管停摆但引擎仍在播 = 双声。详见 docs/ADR.md ADR-02/ADR-08。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** capture 电平探针（MainViewModel 注入）：engine Running 返回 inputLevel(0..1)，非 Running 返回 -1f；null = 不监测 */
    @Volatile var captureLevelProbe: (() -> Float)? = null

    private val _captureBrokenWarning = MutableStateFlow<String?>(null)
    val captureBrokenWarning: StateFlow<String?> = _captureBrokenWarning

    /**
     * 自身播放权限自愈警示（2026-11-03 全静音事故）：ZTE 星云后台音频治理类组件会把
     * 「后台无声播放」的 app 自动置 PLAY_AUDIO=deny(MODE_ERRORED)，引擎输出轨被
     * AudioFlinger 压 -inf 增益 → 静音 → 省电断流(-899) → 重建仍被压 → 全链死寂；
     * appops 跟 uid 走，force-stop/重授权/重启进程均无法解除。自愈探针低频自检并
     * 自动恢复。非 null = 曾发生自愈（用户需知晓 + 引导加白名单）。
     */
    private val _selfAudioWarning = MutableStateFlow<String?>(null)
    val selfAudioWarning: StateFlow<String?> = _selfAudioWarning.asStateFlow()

    fun clearSelfAudioWarning() {
        _selfAudioWarning.value = null
    }

    /** 用户在警示条点「重新授权」后手动清除（重新授权成功会自动回滚接管并清警示） */
    fun clearCaptureBrokenWarning() {
        _captureBrokenWarning.value = null
    }

    /** 保险丝：连续零电平起点（elapsedRealtime ms），0 = 电平正常/未计时 */
    private var zeroInputSince = 0L

    /** 酷狗案：最近一轮 dumpsys 的「非禁抓名单三方活跃 uid」集合。
     *  名单 DP EQ 轨抓不到投影是预期，capture 恒零≠断链——保险丝零电平触发
     *  必须排除「名单流独播/无三方活跃流」，否则豆包停播后必误触发且永不解除。 */
    @Volatile
    private var activeNonBlocklistedUids: Set<Int> = emptySet()

    /** 网易云误判案：capture policy 权威表快照（uid 集合 = policy 非 BY_ALL）。
     *  来源：特权 `dumpsys audio` 的「allowed capture policies」段（只列非默认 policy，
     *  1=BY_ALL 不列 / 2=BY_SYSTEM / 3=BY_NONE）。用途：替代电平猜测——
     *  ① 表内 = 真禁抓（第三方面积抓不到），直接落名单，不浪费观察窗；
     *  ② 表外 = 系统允许抓，恒零只能是「还没开始播」，不挂观察窗（网易云 15:14
     *     刚打开未播即被判禁抓、24h 名单导致双声的根因）。
     *  policyKnown=false（Stellar 半连接/dump 失败）时退回电平观察窗兜底。 */
    @Volatile
    private var policyBlockedUids: Set<Int> = emptySet()

    @Volatile
    private var policyKnown = false

    /** 观察窗内是否见过非零电平（兜底：全程零且从未见过非零 = 疑似未播放，不判禁抓） */
    private val watchSawLevel = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /** 特权命令失败（Stellar 未授权等）时上报，供 UI 提示——静默失效会让人对着双声猜原因 */
    private val _privilegeError = MutableStateFlow<String?>(null)
    val privilegeError: StateFlow<String?> = _privilegeError.asStateFlow()

    /**
     * 采集断链保险丝（2026-11-01 事故）：capture 全零断链（系统侧 mix 脱钩）时接管
     * 静音照常生效 = 原声被压 + 引擎无声 = 完全无声。挂起期间 pollOnce 空转（不新增
     * 接管），已接管项全部回滚保直出——宁可双声，不许完全无声。
     */
    private val _mutingSuspended = MutableStateFlow(false)
    val mutingSuspended: StateFlow<Boolean> = _mutingSuspended.asStateFlow()

    private var pollJob: Job? = null
    private var pollCount = 0L
    /** 上次写夜间心跳的墙钟时间；0 = 本轮还没写过 */
    private var lastHeartbeatMs = 0L
    private val diagFile =
        java.io.File(DiagDirs.crashDir, "takeover_diag.txt")

    // ===== 投影失效自动止损联动（2026-11-03 僵尸投影事故）=====
    // 引擎因 read 恒负自杀停机（Stopped(CAPTURE_DEAD)）时，接管静音若不回滚 =
    // 静音先行已压原声 + 引擎已停 = 完全无声。必须走 stopAndRollback（完全停止
    // 轮询 + 回滚全部静音）而非 suspendMuting——后者挂起态会被保险丝的
    // 「engine 非运行 → 自动 resume」分支解开，导致对已死引擎重新接管。
    // 进程级监听（同 poll scope），UI 生死无关。
    init {
        scope.launch {
            // （2026-11-03 审计）：collect 协程免疫。原实现无 try-catch——一次未捕获
            // 异常（并发容器事故/CME）会让协程静默死亡（SupervisorJob 不重建），此后
            // CAPTURE_DEAD 自动止损永久失效且无日志。map 已换 ConcurrentHashMap 消根源，
            // 此处再兜异常 + 自动重订阅双保险（取消经 delay 挂起点自然终止）。
            while (true) {
                runCatching {
                    engine.state.collect { st ->
                        if (st is EngineState.Stopped && st.reason == StopReason.CAPTURE_DEAD) {
                            runCatching { stopAndRollback() }.onFailure { e ->
                                writeDiag("CAPTURE_DEAD 止损回滚异常已兜住: ${e.message?.take(100)}")
                            }
                            _captureBrokenWarning.value =
                                "内录通道已失效：已恢复各 app 原声（直出、暂无音效）。" +
                                    "请关闭后重新开启内录（走系统授权）以恢复音效。"
                            writeDiag("投影失效自动止损：引擎已停(CAPTURE_DEAD)，接管已回滚保直出")
                            SonicraftLog.e(TAG, "CAPTURE_DEAD：接管已回滚，警示已挂出")
                        }
                    }
                }.onFailure { e ->
                    writeDiag("CAPTURE_DEAD 监听协程异常已重订阅: ${e.message?.take(100)}")
                    SonicraftLog.w(TAG, "engine.state collect 异常，1s 后重订阅", e)
                }
                delay(1000)
            }
        }
    }

    /**
     * 方案 A：主动从 Stellar manager 的 provider 拉取 binder 并注入（软重启后
     * manager 侧被动分发延迟实测 11 分钟+，主动 getBinder 拉取以避免该等待）。
     */
    private fun requestStellarBinder(): Boolean =
        runCatching {
            val uri = android.net.Uri.parse("content://roro.stellar.manager.stellar")
            val reply = context.contentResolver.call(uri, "getBinder", null, android.os.Bundle())
                ?: return@runCatching false
            reply.classLoader = com.stellar.api.BinderContainer::class.java.classLoader
            val container = reply.getParcelable<com.stellar.api.BinderContainer>(
                "roro.stellar.manager.intent.extra.BINDER",
            )
            val binder = container?.binder
            if (binder == null || !binder.pingBinder()) return@runCatching false
            roro.stellar.Stellar.onBinderReceived(binder, "roro.stellar.manager")
            true
        }.getOrDefault(false)

    /** 引擎进入 Running 后调用：先清理上次会话残留，再开始轮询（若用户开启自动接管）。
     * 轮询迁入本类自有 scope（与 engine 同生命周期），不再随 UI 的
     *  viewModelScope 死亡——参数 scope 移除。 */
    fun start(autoEnabled: Boolean, loopback: Boolean = false) {
        loopbackMode = loopback
        dpSilencedNotified.clear()
        writeDiag("takeover.start(auto=$autoEnabled loopback=$loopback)")
        // 历史重复条目迁移（幂等；老版本按次 append，实测堆过 61 条同包名）
        dedupeBlocklistOnce()
        // L12（2026-11-03 审计）：rollbackPrefsRecords 对每个失败 uid 发 set+get 两条
        // 特权命令——原在主线程同步执行，与 2026-11-01「stopAndRollback 卡主线程」同款
        // 事故模式漏网面。移 IO 协程；poll 路径置于协程首位，保证清理先于新增接管
        // （铁律 3 时序不变）。
        if (!autoEnabled) {
            scope.launch(Dispatchers.IO) { runCatching { rollbackPrefsRecords() } }
            return
        }
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            runCatching { rollbackPrefsRecords() } // 铁律 3：冷启动兜底清理崩溃/被杀残留
            // 教训：软重启（system_server 重启）会杀 stellar_server，重连窗口
            // 实测长达数分钟——期间接管轮询全部失败（特权命令「服务未连接」）=接管静音
            // 失效=双声。等通道就绪后再启动轮询，消除盲失败窗口。
            var waited = 0
            while (isActive && waited < 90_000 && !privileged.state.value.serviceRunning) {
                if (waited % 5_000 == 0 && requestStellarBinder()) {
                    writeDiag("主动 getBinder 重连成功（waited=${waited}ms）")
                    SonicraftLog.i(TAG, "Stellar binder 主动重连成功（${waited}ms）")
                    break   // binder 已注入，serviceRunning 将被 refresh 置位
                }
                delay(1000); waited += 1000
            }
            if (!privileged.state.value.serviceRunning) {
                writeDiag("Stellar 通道 90s 未就绪，接管轮询降级启动（依赖轮询内重试）")
                SonicraftLog.w(TAG, "Stellar 通道 90s 未就绪，接管降级启动")
            }
            writeDiag("接管轮询启动（loopback=$loopbackMode auto=$autoEnabled）")
            // 残留 DP 自检放在 IO 协程里（要跑 dumpsys，不能阻塞调用方线程）
            runCatching { detectResidualDp() }
            while (isActive) {
                // 与相邻两处同构的异常免疫——probe 抛异常不能让轮询协程
                // 无痕死亡（SupervisorJob 不重建本协程，284 行历史事故同构）
                runCatching { runFusecheck() }.onFailure { e ->
                    writeDiag("保险丝判定异常已免疫: ${e.message?.take(100)}")
                    SonicraftLog.w(TAG, "runFusecheck 异常", e)
                }
                runCatching { runCaptureWatch() }.onFailure { e ->
                    writeDiag("观察窗判定异常已免疫: ${e.message?.take(100)}")
                }
                // 一次未捕获异常曾可让轮询协程静默死亡（无 try-catch），
                // 接管从此消失且无自愈——单轮异常免疫，diag 留痕继续。
                runCatching { pollOnce() }.onFailure { e ->
                    writeDiag("poll 异常已免疫继续: ${e.message?.take(100)}")
                    SonicraftLog.w(TAG, "pollOnce 异常", e)
                }
                // 省电：重路由已成立时**整条链路不挂静音**（见 syncLoopbackMute），
                // 此时每轮只做「读 dumpsys 快照」——而 runCommand 每次都 fork 一个新进程，
                // 是夜间耗电的主要来源之一。故该状态下大幅降频：
                //   亮屏 2s（仍需较快响应切歌/新 app 开播）、灭屏 10s。
                // 夜间心跳：60s 一行，专为「息屏待机是否音效失效」取证。
                // watermark_log 是 10s 一行且只保留约 8 分钟（实测 47 行），睡一觉就被
                // 冲掉，无法回溯整夜；心跳常驻 **takeover_diag.txt**（本类的 diagFile，
                // 不是 autostart_diag），明早可逐时段判断到底是「链路/引擎断了」还是
                // 「链路在、只是 HAL 侧 standby 不计时」。
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeatMs = nowMs
                    writeDiag(
                        "心跳: engineState=${engine.state.value}, chain.isAlive=${loopbackChain.isAlive}, " +
                            "loopback=$loopbackMode, rerouted=$loopbackRerouted, " +
                            "inputLevel=${"%.4f".format(currentInputLevel())}"
                    )
                }
                val reroutedIdle = loopbackMode && loopbackRerouted
                val nextDelay = if (reroutedIdle) {
                    if (powerManager.isInteractive) POLL_INTERVAL_REROUTED_MS
                    else POLL_INTERVAL_REROUTED_IDLE_MS
                } else {
                    if (powerManager.isInteractive) POLL_INTERVAL_MS else POLL_INTERVAL_IDLE_MS
                }
                delay(nextDelay)
            }
        }
        SonicraftLog.i(TAG, "自动接管轮询已启动（亮屏 ${POLL_INTERVAL_MS}ms / 灭屏 ${POLL_INTERVAL_IDLE_MS}ms）")
    }

    /** 停止内录/退出：停止轮询并按轨恢复全部自动接管项（带回读验证，失败保留记录待重试） */
    fun stopAndRollback() {
        pollJob?.cancel()
        pollJob = null
        _mutingSuspended.value = false
        _captureBrokenWarning.value = null
        zeroInputSince = 0L
        val failed = mutableSetOf<String>()
        for ((uid, track) in autoTakenOverUids.toList()) {
            if (!rollbackUidVerified(uid, track)) failed.add(uid.toString())
        }
        for (effect in dpEffects.values) runCatching { effect.release() }
        // Loopback 按会话静音的效果同样要释放（否则退出后原声仍被压）
        releaseLoopbackMutes()
        dpEffects.clear()
        dpIsEq.clear()
        dpEffectSessions.clear()
        captureWatch.clear()
        autoTakenOverUids.clear()
        dpFailedSessions.clear()
        cooldownUids.clear()
        lastSeenUids.clear()
        if (failed.isNotEmpty()) {
            // 回滚未生效（Stellar 断连等）：保留 prefs 记录，下次 start 的
            // rollbackPrefsRecords 会重试——不留「无人认领」的静音残留
            prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, failed).apply()
            writeDiag("停止回滚失败 uid=$failed，记录保留待冷启动重试")
            SonicraftLog.w(TAG, "停止回滚失败 ${failed.size} 项，记录保留")
        } else {
            prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, emptySet()).apply()
        }
        SonicraftLog.i(TAG, "自动接管已全部回滚")
    }

    /**
     * 保险丝触发：暂停全部静音并回滚接管（保直出）。2026-11-01 实锤：capture 全零
     * 断链期间接管照常生效 → 原声静音 + 引擎无声 = 完全无声，用户无任何可听的声源。
     */
    fun suspendMuting(reason: String) {
        if (!_mutingSuspended.compareAndSet(false, true)) return
        // 息屏案：记录挂起时活跃拓扑基线——拓扑变化解除挂起（防死锁）用
        suspendSince = SystemClock.elapsedRealtime()
        suspendActiveUids = activeUidsSnapshot
        writeDiag("保险丝触发：暂停全部静音（$reason），回滚接管保直出")
        SonicraftLog.w(TAG, "保险丝触发：暂停静音（$reason）")
        val failed = mutableSetOf<String>()
        // 酷狗案：名单 DP EQ 轨（原声直出+per-session 效果）不经投影，
        // capture 断链与否不影响其正确性——豁免回滚与效果清除，保留音效。接管记录
        // 保留：解除后 568 行去重挡住重挂，healDpTracks 持续保活。
        val exemptUids = autoTakenOverUids
            .filterValues { it == MuteTrack.DP_SESSION }
            .keys.filter { isCaptureBlocked(it) }.toSet()
        for ((uid, track) in autoTakenOverUids.toList()) {
            if (uid in exemptUids) continue
            if (!rollbackUidVerified(uid, track)) failed.add(uid.toString())
        }
        for ((uid, effect) in dpEffects.entries) {
            if (uid in exemptUids) continue
            runCatching { effect.release() }
            dpEffects.remove(uid)
            dpIsEq.remove(uid)
            dpEffectSessions.remove(uid)
        }
        captureWatch.clear()
        autoTakenOverUids.entries.removeAll { it.key !in exemptUids }
        lastSeenUids.clear()
        if (failed.isNotEmpty()) {
            prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, failed).apply()
            writeDiag("保险丝回滚失败 uid=$failed，记录保留待重试")
        } else {
            prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, emptySet()).apply()
        }
    }

    /** 采集恢复：解除保险丝，接管轮询继续（恢复接管在 pollOnce 自然发生） */
    fun resumeMuting() {
        if (!_mutingSuspended.compareAndSet(true, false)) return
        writeDiag("保险丝解除：采集恢复，接管轮询继续")
        SonicraftLog.i(TAG, "保险丝解除：恢复接管")
    }

    /** 按轨回滚单个 uid 并回读验证（allow 真实生效才返回 true）——防静音残留无人认领 */
    private fun rollbackUidVerified(uid: Int, track: MuteTrack): Boolean {
        return when (track) {
            MuteTrack.APPOPS -> {
                setAppopsForUid(uid, APPOPS_ALLOW)
                verifyAllowed(uid)
            }
            MuteTrack.DP_SESSION -> {
                dpEffects.remove(uid)?.let { runCatching { it.release() } }
                dpIsEq.remove(uid)
                dpEffectSessions.remove(uid)
                autoTakenOverUids.remove(uid)
                lastSeenUids.remove(uid)
                true
            }
        }
    }

    /**
     * 自身播放权限自愈探针（2026-11-03 全静音事故根治）。
     * 事故链：ZTE 星云后台音频治理把「后台无声播放」的本 app 置 PLAY_AUDIO=deny
     * （MODE_ERRORED，写入窗口内接管轮询被保险丝挂起、App 代码只写 ignore/allow——
     * 外部写入实锤）→ AudioFlinger 对本 app 全部 track 置增益 -inf → 输出流无声 →
     * 省电断流 -899 → 看门狗重建被系统拒绝全败 → 全链哑巴 22 分钟。
     * appops 跟 uid 走：force-stop/重授权/重启进程均无效，唯 set allow 立愈。
     * 策略：低频 appops get 自检；非 allow（deny/ignore 等任何非预期 mode）→ set allow
     * + 回读验证 + diag + 横幅。解除后看门狗（DefaultAudioEngine）下一轮重试自动成功，
     * 全链无需用户干预自动复活。
     */
    private fun runSelfAudit() {
        if (!privileged.state.value.serviceRunning) return
        val pkg = context.packageName
        val r = privileged.runCommand("appops", "get", pkg, "PLAY_AUDIO")
        if (r.exitCode != 0) {
            // （2026-11-03 审计）：通道异常不能静默——Stellar 半连接期（binder 通但
            // 命令失败）探针失效无痕，恰是本探针最该工作的场景。采样留痕防刷盘。
            noteSelfAuditFail("appops get 失败 exit=${r.exitCode} ${r.stderr.trim().take(60)}")
            return
        }
        val mode = Regex("""PLAY_AUDIO:\s*([a-z]+)""", RegexOption.IGNORE_CASE)
            .find(r.stdout)?.groupValues?.get(1)?.lowercase() ?: run {
            // `No operations.` = 本 app 没有任何 PLAY_AUDIO op 记录。
            //
            // 这是**正常状态**，不是通道异常：
            //   · 2026-11-05 起 Loopback 模式改用 REROUTE 搬走原声，**不再写 appops**
            //     （见 README M11「重路由成立即整链路不挂 DP」）；
            //   · 没写过 op 时 `appops get` 返回的就是 `No operations.`，语义上等于
            //     默认 allow（未被任何一方改过）。
            // 原实现把它当"输出无法解析 → 通道异常"，结果每 2 分钟一条假告警，
            // 8h 长跑录得 **260 条/夜**，把真异常彻底淹没。
            if (r.stdout.contains("No operations", ignoreCase = true)) {
                selfAuditFailStreak = 0L // 视为通道正常
                return
            }
            noteSelfAuditFail("输出无法解析: ${r.stdout.trim().take(60)}")
            return
        }
        if (mode == APPOPS_ALLOW) {
            selfAuditFailStreak = 0L // 通道恢复正常
            return
        }
        // 非 allow：deny=MODE_ERRORED（ZTE 治理指纹）/ ignore=接管误伤，一律恢复
        privileged.runCommand("cmd", "appops", "set", pkg, "PLAY_AUDIO", APPOPS_ALLOW)
        val v = privileged.runCommand("appops", "get", pkg, "PLAY_AUDIO")
        val healed = v.exitCode == 0 &&
            Regex("""PLAY_AUDIO:\s*allow""", RegexOption.IGNORE_CASE).containsMatchIn(v.stdout)
        selfAudioHealCount++
        writeDiag(
            "自身 PLAY_AUDIO 自愈：发现 mode=$mode → set allow（回读${if (healed) "通过" else "未通过"}，" +
                "会话第 $selfAudioHealCount 次）",
        )
        SonicraftLog.w(TAG, "自身 PLAY_AUDIO mode=$mode，已自愈（第 $selfAudioHealCount 次）")
        _selfAudioWarning.value =
            "检测到系统修改了本 app 的播放权限（$mode），已自动恢复。" +
                if (selfAudioHealCount >= 3) {
                    "反复出现：请在 系统设置→电池→Sonicraft 设为「不限制」，并将本 app 锁定后台" +
                        "（ZTE 后台音频治理在持续对抗，加白名单后即止）。"
                } else {
                    "若反复出现，请将本 app 加入电池优化白名单/锁定后台。"
                }
    }

    /** ：探针通道异常采样留痕（首次 + 每 12 次≈2 分钟一行，防刷盘） */
    private fun noteSelfAuditFail(detail: String) {
        selfAuditFailStreak++
        if (selfAuditFailStreak == 1L || selfAuditFailStreak % 12L == 0L) {
            writeDiag("自愈探针通道异常 #$selfAuditFailStreak: $detail —— Stellar 半连接？")
        }
    }

    /**
     * 采集断链保险丝判定（2026-11-01 立项 / 2026-11-03 迁入本类进程级 scope）。
     * 病理（11-01 12:25 双端 dump 实锤）：AudioFlinger 喂静音——AudioRecord 满速
     * 读（lastRead=2048、zeroReads=0，字节级心跳全绿）但内容全零，app 侧重建
     * 100% 无效；此时接管静音照常生效 = 原声被压 + 引擎无声 = 完全无声。
     * 策略：零电平持续 15s → suspendMuting 回滚接管保直出（宁可直出，不许无声）；
     * 电平恢复 → 自动 resume 恢复接管。原实现寄宿 viewModelScope collect，
     * UI 回收后恢复监视死亡 → 保险丝永挂 = 早上双声凝固；现随 poll 进程级运行。
     */
    private fun runFusecheck() {
        val probe = captureLevelProbe ?: return
        val level = probe()
        if (level < 0f) { // engine 非 Running：保险丝复位（无接管可保，也无断链可言）
            zeroInputSince = 0L
            if (_mutingSuspended.value || _captureBrokenWarning.value != null) {
                _captureBrokenWarning.value = null
                resumeMuting()
            }
            return
        }
        if (level < BROKEN_CAPTURE_LEVEL) {
            // 酷狗案：名单流独播/无三方活跃流时 capture 恒零是预期
            // （名单 DP EQ 轨抓不到投影），非断链——复位计时，不进入触发累积。
            // 否则豆包停播后保险丝必误触发，挂起期 486 早退不重挂 = 名单音效压死。
            if (activeNonBlocklistedUids.isEmpty()) {
                zeroInputSince = 0L
            } else if (zeroInputSince == 0L) {
                zeroInputSince = SystemClock.elapsedRealtime()
            } else if (FUSE_ENABLED && !_mutingSuspended.value &&
                SystemClock.elapsedRealtime() - zeroInputSince >= BROKEN_CAPTURE_MS
            ) {
                suspendMuting("capture 零电平 15s（read 正常推进）")
                _captureBrokenWarning.value =
                    "采集断链：系统内录通道失效。已恢复各 app 原声（直出、暂无音效），采集恢复后自动回到接管模式。"
                SonicraftLog.w(TAG, "采集断链保险丝触发：回滚接管保直出")
            }
        } else {
            val wasSuspended = _mutingSuspended.value
            zeroInputSince = 0L
            if (wasSuspended) {
                _captureBrokenWarning.value = null
                resumeMuting()
                SonicraftLog.i(TAG, "采集恢复：解除保险丝，接管轮询继续")
            }
        }
    }

    private fun pollOnce() {
        // 自身播放权限自愈探针（2026-11-03 全静音事故）：必须在 _mutingSuspended 早退
        // 之前——事故 deny 恰落在保险丝挂起窗口（15:56:50~15:57:09），探针若被挂起
        // 屏蔽等于关闭唯一自救通道。低频（每 20 轮 ≈10s）控制 shell 开销。
        selfAuditCounter++
        if (selfAuditCounter % SELF_AUDIT_EVERY_POLLS == 0L) runSelfAudit()
        // 采集断链保险丝挂起期间：不新增任何接管（保直出），直到 resumeMuting。
        // 定案：挂起空转此前零 diag 输出，与协程死亡在日志上无法区分
        // （三次误判「轮询静默」，实为挂起空转+灭屏降频叠加的假死）——空转期保心跳留痕
        if (_mutingSuspended.value) {
            pollCount++
            if (pollCount % 10L == 1L) {
                writeDiag("poll#$pollCount 挂起空转（保险丝挂起中，capture 零电平；恢复播放后自动解除）")
            }
            // 息屏案：空转轮必须继续跑 dumpsys——①挂起期活跃快照若冻结，
            // 挂起后开播的 app 永远不被看见（酷狗 09:21 开播无人接管实锤）；②拓扑变化
            // 是比「电平恢复」更可靠的解除信号：断续流（网易云恒零）误触发挂起后电平
            // 永不恢复=死锁，但用户切歌/开新流必然改变拓扑。拓扑变化+满 30s → 解除，
            // 真断链会在解除后 15s 再挂起——宁抖动不死锁。
            val dump = privileged.runCommand("dumpsys", "audio")
            if (dump.exitCode == 0) {
                val active = parseActiveUsages(dump.stdout, Process.myUid()).keys
                activeUidsSnapshot = active
                if (active != suspendActiveUids &&
                    SystemClock.elapsedRealtime() - suspendSince >= SUSPEND_MIN_MS
                ) {
                    suspendActiveUids = active
                    resumeMuting()
                    _captureBrokenWarning.value = null
                    writeDiag("保险丝解除：活跃流拓扑变化（防死锁自愈），恢复正常轮询")
                }
            }
            // 电平恢复解除（防死锁第二通道）：拓扑变化解除的盲区=同一组 app 持续播时
            // 拓扑不变 → 死锁依旧（10:23 酷狗案挂起死锁 6 分钟实锤，用户感知「循环几次
            // 后直接出原声」）。挂起已回滚接管=原声直出，直出流可抓者直声必然进投影
            // =电平恢复 → 满防抖后解除。probe 判决与正常接管都依赖解除后才有机会跑。
            captureLevelProbe?.let { probe ->
                if (probe() >= BROKEN_CAPTURE_LEVEL &&
                    SystemClock.elapsedRealtime() - suspendSince >= SUSPEND_MIN_MS
                ) {
                    _captureBrokenWarning.value = null
                    resumeMuting()
                    writeDiag("保险丝解除：回滚直声进投影（电平恢复），恢复正常轮询")
                }
            }
            return
        }
        // AudioPlaybackConfiguration 的 uid 是隐藏 API（App 进程内反射已被 Android 15 hidden API
        // 封锁），走特权 dumpsys audio 解析活跃播放快照：每行 AudioPlaybackConfiguration 含
        // u/pid:<uid>/<pid>、state:started、usage=USAGE_*（格式实测稳定）
        val dump = privileged.runCommand("dumpsys", "audio")
        if (dump.exitCode != 0) {
            reportCommandFailure("dumpsys audio", dump)
            writeDiag("dumpsys 失败 exit=${dump.exitCode} err=${dump.stderr.take(80)}")
            lastDumpFailed = true
            return
        }
        _privilegeError.value = null // 特权可用，解除 UI 警示

        val activeUsages = parseActiveUsages(dump.stdout, Process.myUid())
        activeUidsSnapshot = activeUsages.keys
        // 每 50 轮（≈25s）刷新 capture policy 权威表，第 0 轮先刷（首轮就绪，避免前 25s
        // 无表退化成电平观察窗而误判）。loopback 模式该表不被使用，跳过省一条 dumpsys。
        if (pollCount % 50L == 0L && !loopbackMode) refreshCapturePolicyTable(dump.stdout)
        // loopback 会话静音兜底清扫（约每 10s）：防 uid 整体停播时效果泄漏
        if (loopbackMode && pollCount % 20L == 0L) sweepLoopbackMutes(dump.stdout)
        // 酷狗案：喂保险丝的「非名单活跃集」。名单 DP EQ 轨抓不到投影，
        // 独播时 capture 恒零是预期而非断链（触发收紧见 runFusecheck）
        activeNonBlocklistedUids = activeUsages.keys
            .filter { it >= Process.FIRST_APPLICATION_UID && !isCaptureBlocked(it) }
            .toSet()
        val dumpHealthy = (activeUsages.isNotEmpty() || dump.stdout.length >= lastHealthyDumpBytes / 2) && !lastDumpFailed
        lastDumpFailed = false   // 成功轮消费冻结标志
        if (activeUsages.isNotEmpty()) lastHealthyDumpBytes = dump.stdout.length

        var takenOver = 0
        var rolledBack = 0
        var dpDescended = 0
        val now = System.currentTimeMillis()
        // 消失超时回滚（按轨分派）：接管中的 uid 若从活跃列表消失超过阈值（微信语音通话时
        // 媒体停、VOIP 不入列表 → uid 消失 → usage 触发的通话保护永不执行 → 静音残留 →
        // 挂断后微信媒体被静音且回滚无从发生）。回滚后不设长冷却：媒体回现时下一轮
        // 即重新接管（~500ms），静音窗口最小化。
        for ((uid, track) in autoTakenOverUids.entries.toList()) {
            if (uid in activeUsages) {
                lastSeenUids[uid] = now
                continue
            }
            if (!dumpHealthy) continue   // 残缺轮：不信任「消失」，保留接管静音
            val goneMs = now - (lastSeenUids[uid] ?: now)
            if (goneMs > GONE_ROLLBACK_MS) {
                // mipop 残留修复：原 rollbackUid 只 set 不验证，「已恢复」
                // 写死文本——set 实际未生效时产生无人认领的 ignore 残留（query-op 实证）。
                // 改验证版：失败保留 prefs 记录，下次 start 的 rollbackPrefsRecords 重试。
                val ok = rollbackUidVerified(uid, track)
                if (ok) {
                    autoTakenOverUids.remove(uid) // DP 轨内已移除，幂等
                    lastSeenUids.remove(uid)
                }
                rolledBack++
                writeDiag("消失超时回滚：uid=$uid 轨=$track 消失 ${goneMs}ms，${if (ok) "已恢复" else "恢复未验证通过，记录保留待重试"}")
                SonicraftLog.w(TAG, "uid 消失超时回滚 uid=$uid track=$track goneMs=$goneMs ok=$ok")
            }
        }
        for ((uid, usages) in activeUsages) {
            // uid=1000 误接管修复：系统域 uid（system_server/systemui 的
            // SoundPool 音效偶标 usage=USAGE_MEDIA + state:started 数秒，NX733J 实锤
            // 100 条「接管→15s 消失回滚」记录，dumpsys 亲见 piid:39 SoundPool
            // u/pid:1000/3020）不接管——AppOps 对 system uid 的行为不受控，接管后必走
            // 15s 消失回滚，期间还可能静音系统提示音。三方媒体源 uid 恒 >= 10000。
            if (uid < Process.FIRST_APPLICATION_UID) continue
            // companion 包（.submix）绝不接管：它是本 App 把副本灌进 REMOTE_SUBMIX 的
            // 输出端点（播 USAGE_MEDIA），mute 它 = 掐死投影输出链（2026-11-04 dumpsys
            // appops 实锤其 PLAY_AUDIO ignore 残留）
            if (uid == submixUid) continue
            // 回滚验证期内不接管（2026-11-04 3s 循环案定因）：pendingBlockVerify 的裁决
            // 需要 2s 验证窗（电平恢复=mute 连坐 / 仍零=真禁抓，双向落名单），而本扫描
            // 同轮（runCaptureWatch 先跑、pollOnce 后跑，diag 实测回滚后 86ms 重接管）
            // 就把 uid 抢回 → runCaptureWatch「已被其他路径接管」守卫杀掉验证 → 观察窗
            // 3s 循环永动（diag #481-#611 实锤，「禁抓判定」结局行零次出现）。
            if (pendingBlockVerify.containsKey(uid)) continue
            // 铁律 2（通话保护，按轨分派）：出现通话/铃声等白名单外音频 → 立即回滚 + 冷却
            if (needsCallProtection(usages)) {
                val track = autoTakenOverUids[uid]
                if (track != null) {
                    rollbackUid(uid, track)
                    cooldownUids[uid] = System.currentTimeMillis() + CALL_COOLDOWN_MS
                    rolledBack++
                    writeDiag(
                        "通话保护：回滚 uid=$uid 轨=$track（白名单外 usage=$usages），" +
                            "冷却 ${CALL_COOLDOWN_MS}ms",
                    )
                    SonicraftLog.w(TAG, "通话保护回滚 uid=$uid track=$track usages=$usages")
                }
                continue
            }
            if (autoTakenOverUids.containsKey(uid)) {
                // 切歌案：切歌/重开会换 audioSession，旧 EQ 挂死 session 上 =
                // 音效静默失效（dpEffects[uid] 在册挡住重挂，酷狗实测「切下一曲没音效」）。
                // session 变了就拆记录 falls through 到下方名单命中分支重挂新 session。
                // 仅 EQ 轨需检测（APPOPS 轨无效果对象）；session 暂不可见（切歌间隙）保守不动。
                val old = dpEffects[uid] ?: continue
                val oldSid = dpEffectSessions[uid] ?: continue
                val sidNow = findSessionForUid(uid) ?: continue
                if (oldSid == sidNow) continue
                writeDiag("DP EQ 重挂：uid=$uid session 变化 $oldSid→$sidNow")
                runCatching { old.release() }
                dpEffects.remove(uid)
                dpIsEq.remove(uid)
                dpEffectSessions.remove(uid)
                autoTakenOverUids.remove(uid)
                lastSeenUids[uid] = now
            }
            if (isCoolingDown(uid)) continue
            // 禁抓名单命中（2026-11-03 豆包案）：该 app 投影抓不到，appops 静音只会
            // 压原声且引擎无副本——直接 DP EQ 轨挂音效，原声直出。挂载失败退化为
            // 直出无双声（未走 appops），30s 冷却后随轮询重试。
            if (isCaptureBlocked(uid)) {
                // 用户指令：EQ 兜底轨停用（频段均衡≠全局自定义 DSP 产品形态）。
                // 名单命中=纯直出：幂等 restore allow（防历史残留压死直出）+ 不挂任何
                // 纯直出。**不再有「等 DP 连坐判决」这回事**（2026-11-11 清理实验残留）：
                // 该实验三次全跳过（候选不可抓 → 基线恒零 → 无有效样本），已整体删除；
                // ADR-02 定案后也不存在可翻转的备用主轨 —— DP mute 与 appops 受同一个
                // capture 复制点影响，没有「换个手段就绕开连坐」的可能。
                setAppopsForUid(uid, APPOPS_ALLOW)
                if (eqDisabledRestoredUids.add(uid)) {
                    writeDiag("禁抓名单命中 uid=$uid 纯直出（EQ 轨已停用）")
                }
                continue
            }
            // P2①：Loopback 链路下 appops 会**连坐副本**——AppOps
            // 作用在流的产生端，LOOP_BACK 复制到的是静音数据（mute 后 inputLevel=9e-5
            // 实锤，与 MediaProjection 完全不同）。故 loopback 模式跳过 appops，
            // 直接走 session 级 DP 静音（AudioEffect 效果链，位置不同，可能不连坐）。
            if (loopbackMode) {
                // Loopback 的静音手段只能是 session 级 DP（appops 会连坐副本，RENDER
                // 在本机不成立）——详见 docs/ADR.md ADR-02。必须对**全部活跃会话**挂，
                // 只挂一个会漏（视频前台双声实锤）。
                setAppopsForUid(uid, APPOPS_ALLOW)
                // ★ 重路由事实检测：**必须在静音分支之前**跑。
                // 原先它埋在 syncLoopbackMute 里，而默认 nodp（不挂 DP）会在这里 continue，
                // 导致 loopbackRerouted 永远为 false → 轮询降频不生效、日志也无从判断。
                detectReroute(dump.stdout, uid)
                // 【2026-11-21 修正】默认挂 DP。
                //
                // 实测结论：
                //   · 0x2（纯 LOOP_BACK）= 复制语义，原声仍在 → 不挂 DP 就是**双声**；
                //   · 0x3（RENDER|LOOP_BACK）= 两个位同时置位 → 重定向 + 复制双路叠加，
                //     双声比 0x2 更严重，**不可用**。
                //   → 唯一可用组合：0x2 + DP 静音（把原声压掉，只剩副本）。
                //
                // 调试：debug.sonicraft.nodp=1 可跳过 DP（仅用于排查，会双声）。
                val noDp = runCatching {
                    ProcessBuilder("getprop", "debug.sonicraft.nodp").start()
                        .inputStream.bufferedReader().use { it.readText().trim() } == "1"
                }.getOrDefault(false)
                if (noDp) {
                    if (dpSilencedNotified.add(uid)) {
                        writeDiag("实验：跳过 DP 静音 uid=$uid（观察 RENDER 是否重路由）")
                    }
                    continue
                }
                // 先确认副本有数据再静音：引擎刚启动/切源瞬间电平为 0，此时压原声
                // 就是「原声没了 + 副本也没有」= 全静音（2026-11-05 网易云实锤）。
                val lvlNow = currentInputLevel()
                if (lvlNow < 0.01f) {
                    if (dpWaitNotified.add(uid)) {
                        writeDiag("Loopback 等待副本数据 uid=$uid（电平=%.4f，暂不静音）".format(lvlNow))
                    }
                    continue
                }
                syncLoopbackMute(dump.stdout, uid)
                if (dpSilencedNotified.add(uid)) {
                    writeDiag("Loopback 接管 uid=$uid 轨=DP_MUTE(-200dB)（按会话静音，副本保留）")
                }
                continue
            }
            if (setAppopsForUid(uid, APPOPS_IGNORE)) {
                // 回读验证：某些 ROM appops set 返回 0 但实际未生效（WEcho/RocoDSP 同坑），
                // 不验证就会退化成「静默失效 = 用户只看到双声」
                if (verifyIgnored(uid)) {
                    autoTakenOverUids[uid] = MuteTrack.APPOPS
                    recordPrefs(uid)
                    takenOver++
                    // 观察窗判据（详见 docs/ADR.md ADR-02）：
                    // - policy 表「可抓」→ 不挂窗（恒零只可能是还没开始播，挂窗必误判落黑名单）
                    // - 其余（未知/禁抓）→ 挂窗期满恒零即回滚到 EQ 轨（直出+EQ）
                    // 铁律：宁可无音效，不可无声。
                    if (policyKnown && uid !in policyBlockedUids) {
                        writeDiag("自动接管 uid=$uid 轨=APPOPS usages=$usages 观察窗=false（policy 表=可抓，恒零视为未播放）")
                        SonicraftLog.i(TAG, "自动接管 uid=$uid 轨=APPOPS（policy 可抓，不挂窗）")
                    } else {
                        captureWatch[uid] = SystemClock.elapsedRealtime()
                        writeDiag("自动接管 uid=$uid 轨=APPOPS usages=$usages 观察窗=true")
                        SonicraftLog.i(TAG, "自动接管 uid=$uid 轨=APPOPS usages=$usages")
                    }
                } else {
                    // 阶梯下探：主轨回读未生效 → 撤销 appops → DP_SESSION 回退轨
                    setAppopsForUid(uid, APPOPS_ALLOW)
                    if (tryDpAttach(uid, eq = false)) {
                        dpDescended++
                        writeDiag("阶梯下探成功 uid=$uid 轨=DP_SESSION usages=$usages")
                        SonicraftLog.i(TAG, "阶梯下探 DP 轨接管 uid=$uid usages=$usages")
                    } else {
                        // 孤儿 mute 堵漏（2026-11-04 死锁案定因）：此分支的 appops ignore
                        // 写已发出（ZTE deny 假阴性=可能实际生效），旧代码不 recordPrefs
                        // → mute 在场但无记录 → 进程死亡后无人认领，跨装机/重启永久残留
                        // （豆包案 11-03 08:07 的 mute 残留至 11-04 全量还原才清）。
                        // 双保险：反向 allow 当场兜底 + recordPrefs 留下次启动再验证。
                        setAppopsForUid(uid, APPOPS_ALLOW)
                        recordPrefs(uid)
                        writeDiag("双轨均失败 uid=$uid（appops 回读未生效 + DP 下探失败），已反向还原+记录待下次启动验证")
                    }
                }
            } else {
                writeDiag("appops ignore 失败 uid=$uid")
            }
        }

        // ★ 轮询级持续静音监测（2026-11-23 补）：原实现只在 muteUid（新会话挂静音）时
        // 检查一次，注释写的「每轮 500ms 盯一次」与实现不符——切歌/蓝牙切换导致副本
        // 掉零时，静音仍保持 = 原声被压 + 副本无数据 = 彻底无声（用户听感：几秒后没声）。
        // 这里每轮对所有已静音 uid 复查：连续 2 轮低于 0.005 → 释放 + 标记直出。
        // 铁律：宁可双声，绝不留「压掉唯一音源」的静音。
        if (loopbackSessionUid.isNotEmpty()) {
            val lvlNow = currentInputLevel()
            // 只监测已认领的真实 uid（预静音归属 0，等 takeover 认领后再管）
            val mutedUids = loopbackSessionUid.values.filter { it > 0 }.toSet()
            if (mutedUids.isNotEmpty()) {
                if (lvlNow > 0.005f) {
                    // 见过副本数据后掉零才值得判定（避免建链初期静音被误撤=启动双声）
                    dpSawAudio.addAll(mutedUids)
                    dpSilenceStreak.clear()
                } else if (lvlNow in 0f..0.0049f) {
                    for (mutedUid in mutedUids) {
                        if (mutedUid !in dpSawAudio) continue
                        val streak = (dpSilenceStreak[mutedUid] ?: 0) + 1
                        dpSilenceStreak[mutedUid] = streak
                        if (streak >= 2) {
                            releaseUidMutes(mutedUid)
                            dpSilenceStreak.remove(mutedUid)
                            dpUnfitUids.add(mutedUid)
                            writeDiag(
                                "DP 保险（轮询）：uid=$mutedUid 副本持续归零（连续 $streak 轮，" +
                                    "电平=${"%.4f".format(lvlNow)}）→ 释放静音并标记直出（宁双声不静音）",
                            )
                        }
                    }
                }
            }
        }

        healDpTracks()

        pollCount++
        if (pollCount % 10L == 1L) {
            writeDiag(
                "poll#$pollCount dump=${dump.stdout.length}B 活跃uid=${activeUsages.size} " +
                    "新增接管=$takenOver DP下探=$dpDescended 通话回滚=$rolledBack " +
                    "已接管=${autoTakenOverUids.size} 冷却中=${cooldownUids.size}",
            )
        }
    }

    /** 通话保护冷却中（到期自动解禁） */
    private fun isCoolingDown(uid: Int): Boolean {
        val until = cooldownUids[uid] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUids.remove(uid)
            writeDiag("冷却到期 uid=$uid，恢复自动接管资格")
            return false
        }
        return true
    }

    /** 按轨回滚单个 uid 的静音（内存态清理统一在此，调用方负责冷却等策略） */
    private fun rollbackUid(uid: Int, track: MuteTrack) {
        when (track) {
            MuteTrack.APPOPS -> setAppopsForUid(uid, APPOPS_ALLOW)
            MuteTrack.DP_SESSION -> {
                dpEffects.remove(uid)?.let { effect -> runCatching { effect.release() } }
                dpIsEq.remove(uid)
            }
        }
        autoTakenOverUids.remove(uid)
        lastSeenUids.remove(uid)
    }

    /**
     * DP_SESSION 轨统一入口：eq=false 挂静音（appops 主轨回读失败的下探）；
     * eq=true 挂 EQ 音效（禁抓 app 名单命中，2026-11-03 豆包案）。
     * 成功记入 DP_SESSION 轨；失败返回 false（静音轨双轨皆弃 / EQ 轨本轮直出）。
     */
    /**
     * Loopback 专用：把该 uid 的**所有**活跃会话都挂上 DP -200dB 静音。
     *
     * 为什么不能只挂一个：同一 app 可同时有多个 AudioTrack 会话（酷狗音乐 1457 /
     * 视频 1785），uid→单 session 只能盖住其一 → 未覆盖的会话原声照出 = 双声
     * （2026-11-05 视频前台实锤）。这里按 sid 集合做增量同步：
     *   新增会话 → 挂 DP；会话消失 → 释放；仍存在 → 重新 enable（防被抢控制权）。
     *
     * @param dumpText 当前轮的 dumpsys audio 输出（复用，避免再跑一次特权命令）
     */
    /**
     * 用本轮 dump 判定「该 uid 的会话是否已被重路由到我方 submix」，并维护
     * `loopbackRerouted` 与设备卡文案（2026-11-07 从 [syncLoopbackMute] 抽出）。
     *
     * 为什么必须独立于静音：默认配置是「不挂 DP」（nodp），静音分支会直接 continue，
     * 若检测埋在里面，`loopbackRerouted` 永远为 false —— 既导致轮询无法降频（耗电），
     * 也让日志失去「到底有没有重路由」这一唯一判据。
     */
    private fun detectReroute(dumpText: String, uid: Int) {
        val port = loopbackSubmixPort
        if (port <= 0 || loopbackRerouted) return
        val routedHere = dumpText.lineSequence()
            .filter { it.contains("u/pid:$uid/") && it.contains("state:started") }
            .any { line ->
                Regex("""deviceIds:\[([^\]]*)\]""").find(line)
                    ?.groupValues?.get(1)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.contains(port.toString()) == true
            }
        if (routedHere) {
            loopbackRerouted = true
            _routeSummary.value = "路由：port=$port · 命中"
            writeDiag("检测到 uid=$uid 已重路由到我方 submix(port=$port) → 释放静音、停止压制")
            runCatching { releaseLoopbackMutes() }
            return
        }
        // 未命中时也刷新文案（原先只在命中时更新，「无活跃播放」会长期滞留）
        val hasPlayerNow = dumpText.lineSequence()
            .any { it.contains("u/pid:$uid/") && it.contains("state:started") }
        _routeSummary.value = if (hasPlayerNow) {
            "路由：port=$port · 未命中"
        } else {
            "路由：无活跃播放"
        }
    }

    private fun syncLoopbackMute(dumpText: String, uid: Int) {
        // ★ （2026-11-06，由万象对照实验定案）：链路已被确认重路由时，
        // **整条链路不挂任何静音**。现场证据：万象 mix 在时酷狗 deviceIds 指向其
        // submix、DP 恒 0、单声；关掉它后 deviceIds 回到主输出 —— 说明「原声被搬走」
        // 完全由重路由完成，再挂 DP 只会连坐自己的副本（实测 0.09 → 0.0 → 断供）。
        // 重路由事实由 detectReroute 统一维护（与静音解耦，见该函数注释）
        detectReroute(dumpText, uid)
        if (loopbackRerouted) {
            if (loopbackSessionUid.isNotEmpty()) releaseLoopbackMutes()
            return
        }
        // 已判定 DP 连坐的 uid：直接直出，不再尝试静音
        if (uid in dpUnfitUids) return

        // ★ 持续静音监测（2026-11-05 全静音事故）：单次 1.5s 复查不足——
        // 实测「挂载瞬间副本还在（0.25），几秒后掉到 1.7E-4」，而那时不再复查，
        // 于是原声被压 + 副本也没有 = 全静音（用户听感：几秒音效后彻底没声）。
        // 这里每轮（500ms）盯一次：连续 2 轮副本归零 → 立即释放并标记 unfit。
        // 远程托管模式下 loopbackMutes 为空，追踪以 loopbackSessionUid 为准
        val hasMute = loopbackSessionUid.values.any { it == uid }
        if (hasMute) {
            val lvlNow = currentInputLevel()
            if (lvlNow in 0f..0.0049f) {
                val streak = (dpSilenceStreak[uid] ?: 0) + 1
                dpSilenceStreak[uid] = streak
                if (streak >= 2) {
                    releaseUidMutes(uid)
                    dpSilenceStreak.remove(uid)
                    dpUnfitUids.add(uid)
                    writeDiag(
                        "DP 保险：uid=$uid 挂载后副本持续归零（连续 ${streak} 轮）→ 判定连坐，" +
                            "已释放静音并标记直出（宁双声不静音）",
                    )
                    return
                }
            } else {
                dpSilenceStreak.remove(uid)
            }
        }
        val sids = Regex("""u/pid:$uid/\d+[^\n]*sessionId:(\d+)""")
            .findAll(dumpText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .toList()
        if (sids.isEmpty()) return

        // ★ 远程托管（推荐路径）：效果由 UserService 持有，app 死亡自动释放
        val bridge = loopbackMuteBridge
        if (bridge != null) {
            if (dpVerifyPending.isEmpty()) dpLevelBefore = currentInputLevel()
            val ok = runCatching { bridge.mute(sids.toIntArray()) }.getOrDefault(false)
            if (ok) {
                val now = SystemClock.elapsedRealtime()
                for (sid in sids) {
                    loopbackSessionUid[sid] = uid
                    dpVerifyPending.putIfAbsent(sid, now)
                }
                writeDiag("Loopback 静音(服务托管) uid=$uid sids=$sids")
            } else {
                writeDiag("Loopback 静音(服务托管) 调用失败 uid=$uid（回退本地）")
            }
            if (ok) {
                verifyDpMute(uid)
                for ((sid, owner) in loopbackSessionUid.toList()) {
                    if (owner == uid && sid !in sids) loopbackSessionUid.remove(sid)
                }
                return
            }
        }

        for (sid in sids) {
            val existing = loopbackMutes[sid]
            if (existing != null) {
                runCatching { if (!existing.enabled) existing.enabled = true }
                // 认领预静音挂上的会话（owner=0 → 真实 uid），后续回滚才能按 uid 释放
                loopbackSessionUid[sid] = uid
                continue
            }
            // 一批挂载前先记基线（用于 1.5s 后判定是否连坐副本）
            if (dpVerifyPending.isEmpty()) dpLevelBefore = currentInputLevel()
            val fx = makeDpMuteEffect(sid) ?: continue
            loopbackMutes[sid] = fx
            loopbackSessionUid[sid] = uid
            dpVerifyPending[sid] = SystemClock.elapsedRealtime()
            writeDiag("Loopback 静音 uid=$uid 新会话 sid=$sid（共存会话=${sids.size}）")
        }

        // 挂载满 1.5s → 复查电平，判定是否连坐（防全静音）
        verifyDpMute(uid)

        // 回收：该 uid 名下、已不在活跃集合中的会话
        for ((sid, owner) in loopbackSessionUid.toList()) {
            if (owner != uid || sid in sids) continue
            loopbackMutes.remove(sid)?.let { fx ->
                runCatching { fx.enabled = false; fx.release() }
            }
            loopbackSessionUid.remove(sid)
            writeDiag("Loopback 静音 uid=$uid 会话消除 sid=$sid")
        }
    }

    /** sid 连续未在活跃列表出现的次数（防切歌间隙误释放 → 短暂双声） */
    private val loopbackMissCount = ConcurrentHashMap<Int, Int>()

    /**
     * 全局清扫（2026-11-05 长跑发现泄漏）：按会话静音后，若目标 app **整体停止播放**
     * （uid 从活跃列表消失），per-uid 的回收分支根本不会被访问 → 效果对象越积越多
     * （实测 3 分钟从 1 涨到 3）。这里按「全量 sessionId 集合」做兜底清扫，
     * 连续 2 次（≈20s）缺席才释放，避免切歌间隙误杀导致瞬间双声。
     */
    private fun sweepLoopbackMutes(dumpText: String) {
        if (loopbackMutes.isEmpty()) return
        val alive = Regex("""sessionId:(\d+)""").findAll(dumpText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
        for ((sid, fx) in loopbackMutes.toList()) {
            if (sid in alive) {
                loopbackMissCount.remove(sid)
                continue
            }
            val miss = (loopbackMissCount[sid] ?: 0) + 1
            if (miss < 2) {
                loopbackMissCount[sid] = miss
                continue
            }
            loopbackMutes.remove(sid)
            loopbackSessionUid.remove(sid)
            loopbackMissCount.remove(sid)
            runCatching { fx.enabled = false; fx.release() }
            writeDiag("Loopback 静音会话清扫 sid=$sid（连续 $miss 次不在活跃列表）")
        }
    }

    private fun releaseLoopbackMutes() {
        // 远程托管时由服务释放（服务侧 unmuteAll 幂等）
        runCatching { loopbackMuteBridge?.releaseAll() }
        for (fx in loopbackMutes.values) {
            runCatching { fx.enabled = false; fx.release() }
        }
        loopbackMutes.clear()
        loopbackSessionUid.clear()
        dpVerifyPending.clear()
        dpUnfitUids.clear()
        dpSilenceStreak.clear()
        dpSawAudio.clear()
        dpLevelBefore = -1f
    }

    private fun tryDpAttach(uid: Int, eq: Boolean): Boolean {
        val sid = findSessionForUid(uid)
        if (sid == null) {
            writeDiag("DP 下探：未找到 uid=$uid 的活跃 session")
            return false
        }
        val cooldownUntil = dpFailedSessions[sid]
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) return false
        val effect = (if (eq) makeEqEffect(sid) else makeDpMuteEffect(sid)) ?: return false
        dpEffects[uid] = effect
        dpIsEq[uid] = eq
        dpEffectSessions[uid] = sid
        autoTakenOverUids[uid] = MuteTrack.DP_SESSION
        lastSeenUids[uid] = System.currentTimeMillis()
        // appops 失败时 verifyIgnored 已设 UI 警示；DP 兜住即解除，避免「实际已静音仍报错」
        if (!eq) _privilegeError.value = null
        return true
    }

    /**
     * EQ 音效挂载（禁抓 app 用，人声提亮 preset）：系统 Equalizer 挂 session（API 9+，
     * 与 DynamicsProcessing 同走 AudioFlinger 效果链，capturePolicy 管不住）。
     * band 数/频程随设备，按中心频率查表映射增益。失败语义与静音版一致
     * （重试 2 次 → 冷却 30s），失败只意味着本轮直出无双声。
     */
    private fun makeEqEffect(sid: Int): android.media.audiofx.Equalizer? {
        var lastError: Exception? = null
        repeat(DP_CREATE_RETRY_COUNT + 1) { attempt ->
            try {
                val eq = android.media.audiofx.Equalizer(0, sid)
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                for (b in 0 until eq.numberOfBands.toInt()) {
                    val centerHz = eq.getCenterFreq(b.toShort()) / 1000  // 毫赫兹→赫兹
                    val gainDb = when {
                        centerHz <= 150 -> EQ_GAIN_LOW_DB
                        centerHz <= 2500 -> EQ_GAIN_MID_DB
                        centerHz <= 5000 -> EQ_GAIN_VOICE_DB
                        else -> EQ_GAIN_HIGH_DB
                    }
                    val mb = (gainDb * 100).toInt().coerceIn(minMb, maxMb)
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
                eq.enabled = true
                if (attempt > 0) {
                    SonicraftLog.i(TAG, "sid=$sid 的 Equalizer 第 $attempt 次重试后挂载成功")
                }
                return eq
            } catch (e: Exception) {
                lastError = e
                runCatching { Thread.sleep(DP_CREATE_RETRY_DELAY_MS) }
            }
        }
        writeDiag(
            "EQ 挂载失败 sid=$sid err=${lastError?.message?.take(80)}，" +
                "冷却 ${DP_FAILED_COOLDOWN_MS}ms",
        )
        SonicraftLog.w(TAG, "EQ 挂载失败 sid=$sid: ${lastError?.message}")
        dpFailedSessions[sid] = System.currentTimeMillis() + DP_FAILED_COOLDOWN_MS
        return null
    }

    /**
     * 禁抓观察窗判定（每轮 poll 调用）：接管满 CAPTURE_WATCH_MS 且全局投影电平仍
     * 恒零（该 uid 仍被接管中）→ 判定该 app 运行时禁抓 → 回滚 appops 保直出 → 进名单。
     * 挂起中（全局保险丝）/已非 APPOPS 轨（消失回滚、通话保护先行处理过）→ 撤观察。
     */
    private fun runCaptureWatch() {
        if (captureWatch.isEmpty() && pendingBlockVerify.isEmpty()) return
        val probe = captureLevelProbe ?: run {
            captureWatch.clear(); pendingBlockVerify.clear(); return
        }
        if (_mutingSuspended.value) {
            captureWatch.clear(); pendingBlockVerify.clear(); return
        }
        val level = probe()
        val now = SystemClock.elapsedRealtime()
        // 兜底判据（网易云误判案）：窗口内是否见过非零。全程零且从未见非零 =
        // 疑似「还没开始播」，不得据此判禁抓（policy 表读不到时的安全网）
        if (level >= BROKEN_CAPTURE_LEVEL) {
            for (uid in captureWatch.keys) watchSawLevel[uid] = true
            for (uid in pendingBlockVerify.keys) watchSawLevel[uid] = true
        }
        // 回滚验证裁决（2026-11-04 网易云/酷狗案）：疑似禁抓已回滚解除 mute——电平恢复
        // =可抓（误判，撤销不落名单，重新接管由轮询自然发生）；仍零超 2s=真禁抓落名单。
        // 全局恒零无法归因（被我们 mute 的可抓 app 投影无源与他源消失叠加后与真禁抓
        // 不可区分，酷狗 21:24 误标实锤）——回滚实测是唯一可靠信号。
        for ((uid, since) in pendingBlockVerify.entries.toList()) {
            if (autoTakenOverUids.containsKey(uid)) {
                pendingBlockVerify.remove(uid)   // 已被其他路径接管，验证终止
                watchSawLevel.remove(uid)
                continue
            }
            if (uid !in activeUidsSnapshot) {
                pendingBlockVerify.remove(uid)   // 不播了，验证无意义
                watchSawLevel.remove(uid)
                continue
            }
            if (level >= BROKEN_CAPTURE_LEVEL) {
                pendingBlockVerify.remove(uid)
                // 断续循环案：电平恢复≠误判。回滚=解除 mute，电平随之恢复，
                // 因果明确=mute 连坐 capture 实锤（酷狗双实证：被 mute 投影恒零、直出
                // 投影有源）。旧裁决「撤销+无冷却」致 3s 周期无限循环：接管 mute→3s 恒零
                // →回滚出声 0.Xs→撤销→0.5s 后重接管（酷狗 09:56/11:12 两次四连实锤，
                // 用户感知「间隔几秒静音出 0.X 秒声音」）。改双向落名单：恢复=mute 连坐、
                // 仍零=真禁抓/断链，全部进名单走 EQ 轨（直出+EQ）。误标代价=24h EQ 轨，
                // 远小于无限循环。
                markCaptureBlocked(uid)
                writeDiag("禁抓判定：uid=$uid 回滚后电平恢复=mute 连坐实锤 → 进名单（TTL 24h）")
                continue
            }
            if (now - since >= BLOCK_VERIFY_MS) {
                pendingBlockVerify.remove(uid)
                // 前置：验证期必须见过非零才谈得上「禁抓」；全程零从未开声 = 未播放
                if (watchSawLevel.remove(uid) == true) {
                    markCaptureBlocked(uid)
                    writeDiag("禁抓判定：uid=$uid 回滚验证 ${BLOCK_VERIFY_MS}ms 仍恒零 → 确认禁抓进名单（TTL 24h）")
                    SonicraftLog.i(TAG, "uid=$uid 回滚验证确认禁抓，进名单走 DP EQ 轨")
                } else {
                    writeDiag("禁抓判定跳过：uid=$uid 验证期恒零且从未见非零（疑似未开声），不落名单")
                }
            }
        }
        for ((uid, since) in captureWatch.entries.toList()) {
            if (autoTakenOverUids[uid] != MuteTrack.APPOPS) {
                captureWatch.remove(uid)
                continue
            }
            if (now - since < CAPTURE_WATCH_MS) continue
            captureWatch.remove(uid)
            watchSawLevel.remove(uid)
            if (level < BROKEN_CAPTURE_LEVEL) {
                rollbackUidVerified(uid, MuteTrack.APPOPS)
                autoTakenOverUids.remove(uid)
                lastSeenUids.remove(uid)
                pendingBlockVerify[uid] = now
                writeDiag("禁抓疑似：uid=$uid 观察窗 ${CAPTURE_WATCH_MS}ms 恒零 → 回滚验证（电平恢复=撤销，仍零 2s=落名单）")
            }
        }
    }

    /**
     * 禁抓判定落名单：包名+时间戳持久化（uid 跨安装可能漂移，按包名匹配更稳）。
     *
     * 修正：**按包名去重**。旧实现每次判定都 append 一条 `pkg|ts`，
     * 同一 app 反复判定会堆出几十条（实测 `capture_blocklist_pkgs` 里 61 条
     * com.kugou.android），既污染 TTL 语义（旧条目先过期、新条目继续生效，行为漂移），
     * 又让集合无界增长。改为「包名 → 最新时间戳」后统一写回，并限制总条数。
     */
    private fun markCaptureBlocked(uid: Int) {
        val pkgs = context.packageManager.getPackagesForUid(uid) ?: return
        val now = System.currentTimeMillis()
        val byPkg = collapseBlocklist(prefs.getStringSet(KEY_CAPTURE_BLOCKLIST, emptySet()).orEmpty())
        for (p in pkgs) if (p != context.packageName) byPkg[p] = now
        prefs.edit().putStringSet(KEY_CAPTURE_BLOCKLIST, byPkg.toEntries()).apply()
    }

    /** pkg → 最新时间戳（同包名多条只保留最新） */
    private fun collapseBlocklist(raw: Set<String>): LinkedHashMap<String, Long> {
        val byPkg = LinkedHashMap<String, Long>()
        for (e in raw) {
            val pkg = e.substringBeforeLast('|')
            val ts = e.substringAfterLast('|').toLongOrNull() ?: 0L
            if (pkg.isBlank()) continue
            byPkg[pkg] = maxOf(byPkg[pkg] ?: 0L, ts)
        }
        return byPkg
    }

    private fun LinkedHashMap<String, Long>.toEntries(): Set<String> =
        entries.toList().takeLast(BLOCKLIST_MAX_SIZE).map { "${it.key}|${it.value}" }.toSet()

    /** 启动时一次性迁移：把历史重复条目收敛成每条包名一行（幂等） */
    private fun dedupeBlocklistOnce() {
        val raw = prefs.getStringSet(KEY_CAPTURE_BLOCKLIST, emptySet()).orEmpty()
        if (raw.isEmpty()) return
        val cleaned = collapseBlocklist(raw).toEntries()
        if (cleaned != raw) {
            prefs.edit().putStringSet(KEY_CAPTURE_BLOCKLIST, cleaned).apply()
            writeDiag("禁抓名单去重迁移：${raw.size} 条 → ${cleaned.size} 条")
        }
    }

    /** 刷新 capture policy 表（网易云误判案根治）：权威数据源替代电平猜测。
     *  每 50 轮（≈25s）随轮询刷新一次。
     *
     * @param dumpText 本轮 poll 已拿到的 `dumpsys audio` 输出。省电：
     *  复用它可以省掉每 50 轮一次的额外特权 fork（夜间 UID 1000 的 CPU 大头正是这些
     *  fork）。传 null 时才自行取一次，兼容其他调用点。 */
    private fun refreshCapturePolicyTable(dumpText: String? = null) {
        val text = dumpText ?: run {
            val d = privileged.runCommand("dumpsys", "audio")
            if (d.exitCode != 0) {
                policyKnown = false
                return
            }
            d.stdout
        }
        policyBlockedUids = Regex("""uid:\s*(\d+)\s+policy:\s*[23]\b""")
            .findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
        policyKnown = true
    }

    /** 手动清空禁抓名单（误判恢复入口） */
    fun clearCaptureBlocklist() {
        prefs.edit().remove(KEY_CAPTURE_BLOCKLIST).apply()
        writeDiag("禁抓名单已手动清空（所有 app 重新走接管判定）")
    }

    /** 名单查询：policy 权威表优先 + 包名匹配 + 24h TTL 惰性清理 */
    private fun isCaptureBlocked(uid: Int): Boolean {
        // P2：Loopback 输入链路能抓一切 MEDIA/GAME 流（含酷狗
        // ALLOW_CAPTURE_BY_SYSTEM + 0x400），禁抓名单与 policy 表都不适用——
        // 一律返回 false 走接管静音，否则原声不静音 = 双声。
        if (loopbackMode) return false
        // 权威源：policy 表明确非 BY_ALL → 真禁抓（不烧观察窗、不靠电平猜）
        if (policyKnown && uid in policyBlockedUids) return true
        val pkgs = context.packageManager.getPackagesForUid(uid) ?: return false
        val entries = prefs.getStringSet(KEY_CAPTURE_BLOCKLIST, emptySet()).orEmpty()
        if (entries.isEmpty()) return false
        val now = System.currentTimeMillis()
        var matched = false
        val kept = mutableSetOf<String>()
        for (e in entries) {
            val pkg = e.substringBeforeLast('|')
            val ts = e.substringAfterLast('|').toLongOrNull() ?: 0L
            if (now - ts >= CAPTURE_BLOCKLIST_TTL_MS) continue // 过期：丢弃（TTL 重试投影轨）
            kept.add(e)
            if (pkg in pkgs) matched = true
        }
        if (kept.size != entries.size) {
            prefs.edit().putStringSet(KEY_CAPTURE_BLOCKLIST, kept).apply()
        }
        return matched
    }

    /**
     * session 发现：uid → 活跃 audio session id。
     * 主路径 dumpsys media.audio_policy（WEcho 同款两条正则，覆盖新旧 ROM 格式）；
     * 降级 activePlaybackConfigurations + 反射（hidden API 封锁下可能失败，失败即放弃 DP 轨）。
     */
    private fun findSessionForUid(uid: Int): Int? {
        // 主路径：必须用 `dumpsys audio` 的**播放会话** sessionId
        // （AudioPlaybackConfiguration.sessionId）。此前用 audio_policy 的
        // "Session ID: N; uid X" —— 那是 port/session 混合编号，拿它挂
        // DynamicsProcessing 会挂到不存在的效果链上：效果创建成功（hasControl=true）
        // 但完全没作用（首轮实验「挂载成功却无静音」的真因）。
        val audio = privileged.runCommand("dumpsys", "audio")
        if (audio.exitCode == 0 && audio.stdout.isNotEmpty()) {
            Regex("""u/pid:$uid/\d+[^\n]*sessionId:(\d+)""").find(audio.stdout)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        val dump = privileged.runCommand("dumpsys", "media.audio_policy")
        if (dump.exitCode == 0 && dump.stdout.isNotEmpty()) {
            for (regex in DP_SESSION_REGEXES) {
                val matches = regex.findAll(dump.stdout).toList()
                if (matches.isEmpty()) continue
                val sid = matches.firstOrNull { it.groupValues[2].toIntOrNull() == uid }
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (sid != null && sid > 0) return sid
                break // 该格式已命中但无此 uid，不再尝试其他格式
            }
        }
        // 降级：WEcho 实测 NX733J 上 AudioPlaybackConfiguration 的隐藏方法反射可用
        return try {
            audioManager.activePlaybackConfigurations.firstOrNull { config ->
                invokeIntMethod(config, "getUid") == uid &&
                    invokeIntMethod(config, "getAudioSessionId") > 0
            }?.let { invokeIntMethod(it, "getAudioSessionId") }?.takeIf { it > 0 }
        } catch (e: Exception) {
            writeDiag("DP 下探：session 降级发现失败 uid=$uid err=${e.message?.take(80)}")
            null
        }
    }

    /** DP 静音效果挂载（WEcho makeMuteEffect 同款）：创建 → -200dB 输入增益 → 启用，重试 2 次 */
    private fun makeDpMuteEffect(sid: Int): DynamicsProcessing? {
        // 数量上限（WEcho MAX_MUTE_EFFECTS 同款）：EQ 轨与 mute 轨同为 DP session 效果，
        // 共占系统 effect 引擎资源，超限继续挂会连锁失败
        if (dpEffects.size >= DP_MAX_MUTE_EFFECTS) {
            writeDiag("DP 挂载跳过 sid=$sid：活跃 DP 效果已达上限 $DP_MAX_MUTE_EFFECTS")
            dpFailedSessions[sid] = System.currentTimeMillis() + DP_FAILED_COOLDOWN_MS
            return null
        }
        var lastError: Exception? = null
        repeat(DP_CREATE_RETRY_COUNT + 1) { attempt ->
            try {
                val effect = DynamicsProcessing(0, sid, null)
                effect.setInputGainAllChannelsTo(DP_MUTE_GAIN_DB)
                effect.enabled = true
                if (attempt > 0) {
                    SonicraftLog.i(TAG, "sid=$sid 的 DP 效果第 $attempt 次重试后挂载成功")
                }
                return effect
            } catch (e: Exception) {
                lastError = e
                if (attempt < DP_CREATE_RETRY_COUNT) {
                    runCatching { Thread.sleep(DP_CREATE_RETRY_DELAY_MS) }
                }
            }
        }
        writeDiag(
            "DP 挂载失败 sid=$sid err=${lastError?.message?.take(80)}，" +
                "冷却 ${DP_FAILED_COOLDOWN_MS}ms",
        )
        SonicraftLog.w(TAG, "DP 挂载失败 sid=$sid: ${lastError?.message}")
        dpFailedSessions[sid] = System.currentTimeMillis() + DP_FAILED_COOLDOWN_MS
        return null
    }

    /**
     * DP 轨轮询自愈（替代 WEcho 的反射 listener）：目标 app 抢回控制权/系统禁用效果时，
     * 500ms 内补挂 -200dB；补挂失败则释放并放弃该 uid（退化为双声现状，不劣于无阶梯）。
     */
    private fun healDpTracks() {
        val iterator = dpEffects.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val healed = runCatching {
                if (!entry.value.enabled) {
                    if (dpIsEq[entry.key] == true) {
                        // EQ 轨：效果参数在 Config 里，只需重新 enable——重打静音增益
                        // 会把禁抓 app 的直出原声压死（2026-11-03 豆包案防御）
                        entry.value.enabled = true
                    } else {
                        (entry.value as? DynamicsProcessing)
                            ?.setInputGainAllChannelsTo(DP_MUTE_GAIN_DB)
                        entry.value.enabled = true
                    }
                }
            }.isSuccess
            if (!healed) {
                runCatching { entry.value.release() }
                iterator.remove()
                dpIsEq.remove(entry.key)
                autoTakenOverUids.remove(entry.key)
                lastSeenUids.remove(entry.key)
                writeDiag("DP 轨自愈失败，放弃 uid=${entry.key}（下轮名单分支重挂）")
                SonicraftLog.w(TAG, "DP 轨自愈失败，放弃 uid=${entry.key}")
            }
        }
    }

    private fun invokeIntMethod(target: Any, methodName: String): Int =
        try {
            target.javaClass.getMethod(methodName).invoke(target) as? Int ?: 0
        } catch (e: Exception) {
            0
        }

    /** uid 可能对应多个包（sharedUserId），全部设置；失败经 reportCommandFailure 上报 */
    private fun setAppopsForUid(uid: Int, mode: String): Boolean {
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        var anyOk = false
        for (pkg in packages) {
            if (pkg == context.packageName) continue // 双保险：绝不操作自己
            val result = privileged.runCommand("cmd", "appops", "set", pkg, "PLAY_AUDIO", mode)
            if (result.exitCode == 0) anyOk = true else {
                reportCommandFailure("appops $pkg $mode", result)
                SonicraftLog.w(TAG, "appops $pkg $mode 失败: ${result.stderr.trim()}")
            }
        }
        return anyOk
    }

    /** appops get 回读：确认 ignore 真实生效（set 返回 0 ≠ 生效，WEcho 实测踩坑） */
    private fun verifyIgnored(uid: Int): Boolean {
        val pkg = context.packageManager.getPackagesForUid(uid)
            ?.firstOrNull { it != context.packageName } ?: return false
        val result = privileged.runCommand("appops", "get", pkg, "PLAY_AUDIO")
        val matched = result.exitCode == 0 &&
            Regex("""PLAY_AUDIO:\s*ignore""", RegexOption.IGNORE_CASE)
                .containsMatchIn(result.stdout)
        if (!matched) {
            writeDiag(
                "appops 回读未生效 uid=$uid pkg=$pkg " +
                    "exit=${result.exitCode} out=${result.stdout.take(80)}",
            )
            _privilegeError.value = "静音未生效（$pkg）：appops 回读为非 ignore"
        }
        return matched
    }

    /** appops get 回读：确认 allow 恢复真实生效（回滚验证——断连时残留会跨重启持久化） */
    private fun verifyAllowed(uid: Int): Boolean {
        val pkg = context.packageManager.getPackagesForUid(uid)
            ?.firstOrNull { it != context.packageName } ?: return true  // 无包可操作 = 视为无需验证
        val result = privileged.runCommand("appops", "get", pkg, "PLAY_AUDIO")
        return result.exitCode == 0 &&
            Regex("""PLAY_AUDIO:\s*allow""", RegexOption.IGNORE_CASE)
                .containsMatchIn(result.stdout)
    }

    private fun recordPrefs(uid: Int) {
        val recorded = prefs.getStringSet(KEY_AUTO_TAKEOVER_UIDS, emptySet())
            .orEmpty().toMutableSet()
        recorded.add(uid.toString())
        prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, recorded).apply()
    }

    private fun rollbackPrefsRecords() {
        val recorded = prefs.getStringSet(KEY_AUTO_TAKEOVER_UIDS, emptySet()).orEmpty()
        if (recorded.isEmpty()) return
        val failed = mutableSetOf<String>()
        for (uidStr in recorded) {
            val uid = uidStr.toIntOrNull() ?: continue
            setAppopsForUid(uid, APPOPS_ALLOW)
            // 回读验证后再清记录：冷启动时 Stellar 可能尚未就绪，set 失败但无条件清
            // 记录会留下「无人认领」的静音残留（appops 持久化，重启手机都清不掉）。
            if (!verifyAllowed(uid)) failed.add(uidStr)
        }
        SonicraftLog.i(TAG, "回滚上次会话残留接管 ${recorded.size} 项（失败 ${failed.size}）")
        if (failed.isNotEmpty()) {
            writeDiag("残留回滚失败 uid=$failed，记录保留待下次重试")
            prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, failed).apply()
        } else {
            prefs.edit().putStringSet(KEY_AUTO_TAKEOVER_UIDS, emptySet()).apply()
        }
    }

    private fun reportCommandFailure(cmd: String, result: CommandResult) {
        val stderr = result.stderr.trim()
        if (stderr.contains("Permission Denial") || result.exitCode != 0) {
            val msg = "$cmd 失败（exit=${result.exitCode}）：${stderr.take(120)}"
            if (_privilegeError.value != msg) {
                _privilegeError.value = msg
                writeDiag("特权命令失败: $msg")
            }
        }
    }

    private fun writeDiag(msg: String) {
        runCatching {
            diagFile.parentFile?.mkdirs()
            diagFile.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }.onFailure { e ->
            // diag 是排障命根子：静默丢行等于伪造「未发生过」（2026-11-04 实锤丢过
            // 「保险丝解除」行，误导时序归因）——失败必须留系统痕迹
            SonicraftLog.e(TAG, "diag 写盘失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Takeover"
        private const val PREFS_NAME = "sonicraft_settings"
        private const val KEY_AUTO_TAKEOVER_UIDS = "auto_takeover_uids"
        private const val POLL_INTERVAL_MS = 500L

    /** 重路由已成立（不需要静音）时的降频间隔（2026-11-07 省电） */
    private const val POLL_INTERVAL_REROUTED_MS = 2_000L
    /**
     * 灭屏 + 重路由成立：10s → 30s（2026-11-08 省电）。
     * 实测一夜 7.5h 的特权服务 CPU 达 184，与其 fork 次数直接相关（每轮一条
     * `dumpsys audio`，输出约 120KB 需解析）。该状态下链路已确认重路由、整条链路
     * 不挂静音，轮询只是为了发现「切歌/新 app 开播」，30s 足够。
     */
    private const val POLL_INTERVAL_REROUTED_IDLE_MS = 30_000L
    /** 夜间心跳间隔：60s 一行写 autostart_diag，用于息屏音效取证 */
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
        /** 灭屏（挂机）时的轮询间隔：dumpsys 跨进程调用是常驻 CPU 源，省电降频 */
        private const val POLL_INTERVAL_IDLE_MS = 2_500L
        private const val CALL_COOLDOWN_MS = 30_000L
        /** 采集断链保险丝：capture 零电平持续 15s 判定断链（时间基准，亮屏 500ms/灭屏 2.5s 一轮判定）；
         *  阈值 0.001（-60dBFS）——2026-11-01 喂静音病态（心跳全绿内容全零）的根治防线 */
        private const val BROKEN_CAPTURE_MS = 15_000L
        private const val BROKEN_CAPTURE_LEVEL = 0.001f
        /** 保险丝总开关（2026-11-04 退役）：挂起回滚连 EQ 轨一起压死=死锁直声
         *  （10:23 挂起死锁 6 分钟实锤，用户感知「循环几次后直接出原声」），且当日
         *  8 次触发全为误触发（mute 连坐 capture 的全局电平盲归因）。酷狗/网易云类
         *  app 由「撤销裁决反转→落名单→EQ 轨」独立兜底；engine 非 Running 真断链
         *  分支不受此开关影响。统一路线重构后重新评估归因职责。 */
        private const val FUSE_ENABLED = false
        /** 接管中 uid 从活跃列表消失的超时回滚阈值（覆盖微信语音通话等 uid 消失场景） */
        private const val GONE_ROLLBACK_MS = 15_000L   // 2026-11-01：5s 会在刷视频的间隙（加载/暂停）误触发回滚=双声一闪，15s 只影响真停播后的恢复速度
        private const val APPOPS_IGNORE = "ignore"
        private const val APPOPS_ALLOW = "allow"

        /** 自身播放权限自愈探针：每 N 轮轮询自检一次（500ms/轮 → ≈10s 一次） */
        private const val SELF_AUDIT_EVERY_POLLS = 20L

        // ===== DP_SESSION 回退轨常量（取值沿用 WEcho MuteEffectFactory 实战值） =====
        private const val DP_MAX_MUTE_EFFECTS = 20  // 同 WEcho：最多同时活跃的 DP 效果数，防系统 effect 引擎上限
        private const val DP_CREATE_RETRY_COUNT = 2
        private const val DP_CREATE_RETRY_DELAY_MS = 100L
        private const val DP_FAILED_COOLDOWN_MS = 30_000L
        private const val DP_MUTE_GAIN_DB = -200f

        /** DP 挂载后复查窗口：满此时间比对电平，判定是否连坐 LOOP_BACK 副本 */
        private const val DP_VERIFY_MS = 1_500L

        // ===== 禁抓名单（2026-11-03 豆包案；2026-11-05 语义收敛） =====
        // **仅描述 MediaProjection 抓不到的 app**（如酷狗 ALLOW_CAPTURE_BY_SYSTEM）。
        // Loopback 输入链路能抓一切 MEDIA/GAME 流，该名单在 loopback 模式下由
        // `isCaptureBlocked()` 直接短路返回，不作为「不可加音效」的判决。
        private const val KEY_CAPTURE_BLOCKLIST = "capture_blocklist_pkgs"

        /** 名单上限（按包名去重后）；超出丢弃最旧条目，防集合无界增长 */
        private const val BLOCKLIST_MAX_SIZE = 200
        /** 禁抓观察窗：接管后投影持续零电平的判定时长（首次朗读被压上限，防全局喂零误标
         *  靠「接管时电平非零才启动」+ 24h TTL；3s 对比原保险丝 15s 体验提升 5 倍） */
        private const val CAPTURE_WATCH_MS = 3_000L
        /** 禁抓名单 TTL：到期移出重新走观察窗判定（服务端开关回摆自动回归投影轨） */
        private const val CAPTURE_BLOCKLIST_TTL_MS = 24 * 60 * 60 * 1000L
        // 人声提亮 preset（TTS 朗读向）：按系统 Equalizer band 中心频率映射（单位 dB，
        // 挂载时 ×100 转毫贝并 clamp 到设备 bandLevelRange）
        private const val EQ_GAIN_LOW_DB = -1.5f   // ≤150Hz 去浑浊
        private const val EQ_GAIN_MID_DB = 2.0f    // ≤2.5kHz 中频主体
        private const val EQ_GAIN_VOICE_DB = 3.0f  // ≤5kHz 人声核心
        private const val EQ_GAIN_HIGH_DB = 2.5f   // >5kHz 亮度

        /**
         * dumpsys media.audio_policy 的 session→uid 映射正则（WEcho 同款，截取前两个捕获组）：
         * 旧格式单条块 "Session Id: 257 UID: 10099 ..."；Android 13+ 单行
         * "Session ID: 257; uid 10099; ..."。捕获组 1=sessionId、2=uid。
         */
        private val DP_SESSION_REGEXES = listOf(
            Regex("""Session Id:\s*(\d+)\s+UID:\s*(\d+)"""),
            Regex("""Session ID:\s*(\d+);\s*uid\s+(\d+)"""),
        )

        /**
         * 只接管媒体/游戏。注意：匹配对象是 dumpsys 文本符号名，不能用 AudioAttributes.USAGE_MEDIA
         * （int 常量，插值成 "usage=1" 永不匹配——实测踩坑）。
         */
        private val ALLOWED_USAGE_NAMES = setOf("USAGE_MEDIA", "USAGE_GAME")

        /**
         * dumpsys audio 输出 → 活跃播放快照（uid → usage 集合）。纯函数：可单测。
         * 行格式：u/pid:<uid>/<pid> ... state:started ... usage=USAGE_*（实测稳定）
         */
        internal fun parseActiveUsages(stdout: String, selfUid: Int): Map<Int, Set<String>> {
            val lineRegex = Regex("""u/pid:(\d+)/\d+.*?state:started.*?usage=(USAGE_\w+)""")
            val result = mutableMapOf<Int, MutableSet<String>>()
            for (line in stdout.lineSequence()) {
                val m = lineRegex.find(line) ?: continue
                val uid = m.groupValues[1].toIntOrNull() ?: continue
                if (uid <= 0 || uid == selfUid) continue // 绝不接管自己
                result.getOrPut(uid) { mutableSetOf() }.add(m.groupValues[2])
            }
            return result
        }

        /** 通话保护判定：出现白名单外 usage（通话/铃声/闹钟等）即需回滚。纯函数：可单测。 */
        internal fun needsCallProtection(usages: Set<String>): Boolean =
            usages.any { it !in ALLOWED_USAGE_NAMES }
    }
}