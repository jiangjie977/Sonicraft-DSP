/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 内录接管期间的保活前台服务（mediaProjection 类型）。
 *
 * 音频实际由 [com.jiangjie.Sonicraft.core.engine.DefaultAudioEngine] 在本进程内驱动，
 * 此处仅维持前台优先级，防止系统在后台回收 AudioRecord / AAudio。
 *
 * Android 14+ 强制：通过 MediaProjection 捕获音频的前台服务必须声明 mediaProjection 类型，
 * 否则 startForeground 抛 SecurityException 导致进程闪退。
 *
 * 夜间保活短期方案（2026-10-28 立项）：
 *  - [Intent] 为 null 且 prefs 标记上次会话运行中 = START_STICKY 重生（进程曾被系统
 *    杀掉——system_server 软重启/厂商回收，capture token 已失效）→ 写「被终止未确认」
 *    标记，通知升级为警示文案，用户打开 app 时横幅一键恢复；
 *  - [EXTRA_FROM_BOOT]（开机自启 receiver 发起）同款警示——capture 同样无法静默恢复；
 *  - 正常启动/用户主动停止不触发标记（主动 stopCapture 已清「会话运行中」）。
 */
@AndroidEntryPoint
class ForegroundDspService : Service() {

    @Inject lateinit var engine: AudioEngine

    /** 进程级链路管理器：被系统终止时由本服务直接恢复链路，不依赖 Activity */
    @Inject lateinit var audioChain: AudioChainManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // L11（2026-11-03 审计）：8s 确认回调的 Handler 存字段——原匿名 Handler 无引用，
    // onDestroy 不移除回调：服务销毁后回调仍触发 startForeground = 对已死服务挂
    // 僵尸前台通知。
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        // 声明"活跃媒体会话"：避免系统把后台音频静音（回环模式必须常驻后台）
        runCatching { AppAudioSession.acquire(this) }
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DSP 引擎",
            NotificationManager.IMPORTANCE_LOW,
        )
        // 国产 ROM 桌面角标跟随通知存在（BadgeIconType 在 nubia UI 无效），
        // channel 级禁用角标；旧 channel 属性不可变故换 id 重建
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    /** 启动来源模式（决定前台服务类型位）：MP 走 mediaProjection，Loopback 走 specialUse/mediaPlayback */
    @Volatile
    private var startKind: String = KIND_MP

    /**
     * 解析本次要用的前台服务类型（方案 C，2026-11-07：双类型位 + 可切换实测）。
     *
     * Loopback 用 `specialUse` 而非 `mediaPlayback` 的原因：`targetSdk=35` 下
     * Android 15 限制「从 BOOT_COMPLETED 启动」的 FGS 类型，`mediaPlayback` 与
     * `mediaProjection` 都在禁启名单，`specialUse` 不在。
     * 想对照实测时：`prefs` 里把 `fgs_type_loopback` 设为 `media_playback` 即可。
     */
    private fun resolveFgsType(): Int {
        if (startKind != KIND_LOOPBACK) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val kind = prefs.getString(KEY_FGS_TYPE_LOOPBACK, FGS_TYPE_SPECIAL_USE) ?: FGS_TYPE_SPECIAL_USE
        return if (kind == FGS_TYPE_MEDIA_PLAYBACK) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 类型位必须与「实际用途」一致，否则 Android 14+ 直接抛异常。
        // 未显式传入时按持久化的模式推断（开机/闹钟/STICKY 这些路径没有 extra）。
        val explicitKind = intent?.getStringExtra(EXTRA_KIND)
        startKind = explicitKind ?: run {
            val mode = prefs.getString("audio_mode", "loopback")
            if (mode == "loopback") KIND_LOOPBACK else KIND_MP
        }
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        val fromAlarm = intent?.getBooleanExtra(EXTRA_FROM_ALARM, false) == true
        // START_STICKY 重生特征：intent == null（系统重建，非任何调用方 startService）。
        // 仅当上次会话确在运行中才判「被系统终止」（用户主动停过则 WAS_RUNNING=false）。
        val stickyRevive = intent == null && prefs.getBoolean(KEY_SESSION_WAS_RUNNING, false)
        val terminated = fromBoot || fromAlarm || stickyRevive
        // alarm 哨兵统一补表（幂等）：所有启动路径（用户/BOOT/STICKY/alarm 自身）都
        // 经过这里——软重启清空 alarm 链后，任何一次 service 启动即重建 15min 触发链
        AlarmSentryReceiver.schedule(this)
        if (terminated) {
            prefs.edit().putBoolean(KEY_SYSTEM_KILLED_UNACKED, true).apply()
            writeDiag(
                when {
                    fromBoot -> "开机自启：上次会话运行中，警示通知已挂出"
                    fromAlarm -> "alarm 哨兵拉起：上次会话运行中而引擎未跑，警示通知已挂出"
                    else -> "STICKY 重生（进程曾被系统杀掉，capture token 已失效），警示通知已挂出"
                },
            )
            // 自动恢复（2026-11-07 重构）：
            // Loopback 模式改为**本服务直接恢复链路**（AudioChainManager），不再依赖
            // startActivity —— Android 10+ 后台启动 Activity 受限、息屏时基本必被拒。
            // MP 模式仍走 startActivity（需要用户重新授权录屏，无法静默恢复）。
            if (startKind == KIND_LOOPBACK) {
                writeDiag("auto restore: Loopback 模式 → headless 恢复链路")
                serviceScope.launch {
                    val ok = runCatching { audioChain.restoreLoopbackIfNeeded() }.getOrDefault(false)
                    writeDiag("headless restore 结果: $ok")
                }
            }
            // Activity 拉起仍保留，但只在亮屏时尝试（息屏拉起必失败且会打扰）
            val interactive = runCatching {
                getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
            }.getOrDefault(false)
            if (startKind == KIND_MP && interactive) {
                val launched = runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }.isSuccess
                writeDiag("auto restore attempt: startActivity ${if (launched) "sent" else "rejected"}")
            } else {
                writeDiag("auto restore: 跳过 startActivity（kind=$startKind interactive=$interactive）")
            }
            // auto-restore feedback: swap alert notification back to normal
            // only when engine is Running AND the capture heartbeat is alive.
            // 2026-11-02 修正：旧判定只看引擎 Running——凌晨软重启风暴实证引擎空转
            // （无内录）也会 confirmed，通知回「正常」而实际无声/双声，diag 自欺。
            mainHandler.postDelayed({
                val engineRunning = engine.state.value is EngineState.Running
                val heartbeatAlive = captureHeartbeatAlive()
                if (engineRunning && heartbeatAlive) {
                    writeDiag("auto restore confirmed: engine Running + capture heartbeat alive, notification back to normal")
                    safeStartForeground(buildNotification(terminated = false), resolveFgsType())
                } else {
                    writeDiag(
                        "auto restore NOT confirmed: engine=$engineRunning " +
                            "captureHeartbeat=$heartbeatAlive——警示通知保留，需用户点按授权恢复",
                    )
                    safeStartForeground(
                        buildNotification(terminated = true, awaitingConsent = true),
                        resolveFgsType(),
                    )
                }
            }, AUTO_RESTORE_CONFIRM_MS)
        }

        val notification = buildNotification(terminated)
        // 类型位按模式解析（MP=mediaProjection / Loopback=specialUse 或 mediaPlayback）
        // 走安全启动：MP token 未就绪时自动降级 specialUse，不再闪退（2026-11-09 P0）
        if (!safeStartForeground(notification, resolveFgsType())) {
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 安全启动前台服务（2026-11-09，P0 修复）。
     *
     * **问题**：`mediaProjection` 类型 FGS 要求有**活跃的 projection token**。全新安装 / 清
     * consent 后用户还没授权、token 不存在时，`startForeground` 直接抛 `SecurityException`
     * → 进程闪退（实测 2026-11-08 11:48–11:49 连崩 Java×2 + Native×3，重开 App 才恢复正常）。
     * 权限声明（`FOREGROUND_SERVICE_MEDIA_PROJECTION`）只解决「允许声明」，不解决「token 必须存在」。
     *
     * **降级链**：mediaProjection 失败 → `specialUse`（manifest 已声明、**不要求 token**、
     * 保活能力不变）→ 仍失败则 `stopSelf()`（绝不让异常冒到系统造成闪退）。
     *
     * **额外收益**：Android 15 把 `mediaProjection` 列入「从 BOOT_COMPLETED 启动」的禁启名单，
     * `specialUse` 不在其中——降级后开机自启反而更可靠。
     */
    private fun safeStartForeground(notification: android.app.Notification, fgsType: Int): Boolean {
        val ok = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        }.onFailure {
            writeDiag("FGS 启动失败 type=$fgsType: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (ok) return true

        // 只有 mediaProjection 有降级价值；其他类型失败说明是别的问题，不盲目重试
        if (fgsType != ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) return false
        writeDiag("FGS 降级 specialUse（MP token 未就绪）")
        val fallback = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            writeDiag("FGS 降级 specialUse 也失败: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (!fallback) {
            writeDiag("FGS 两种类型均失败 → stopSelf（避免闪退）")
            stopSelf()
        }
        return fallback
    }

    override fun onDestroy() {
        runCatching { AppAudioSession.release() }
        // L11：8s 确认回调随服务销毁取消，防对已死服务 startForeground
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * capture 心跳是否在跳：读 capture_diag.txt 尾部 4KB，取最后一个毫秒时间戳，
     * 距今 < [CAPTURE_HEARTBEAT_STALE_MS] 视为存活（心跳每秒一行）。
     * 文件最大可到 83MB，严禁整读——tail 读取即可。
     */
    private fun captureHeartbeatAlive(): Boolean {
        // Loopback 模式不写 capture_diag（那是 MP 路径的），用「链在手 + 引擎 Running」当心跳，
        // 否则恢复成功也会被判 NOT confirmed，警示通知一直留着（2026-11-07 实测）
        if (audioChain.isLoopbackMode()) return audioChain.isChainAlive()
        return captureHeartbeatFromDiag()
    }

    private fun captureHeartbeatFromDiag(): Boolean = runCatching {
        val f = File(filesDir, "crash/capture_diag.txt")
        if (!f.exists()) return false
        val tail = f.inputStream().use { ins ->
            val skip = (f.length() - HEARTBEAT_TAIL_BYTES).coerceAtLeast(0)
            ins.skip(skip)
            ins.readBytes().toString(Charsets.UTF_8)
        }
        val lastTs = Regex("ts=(\\d{13})").findAll(tail).lastOrNull()
            ?.groupValues?.get(1)?.toLongOrNull()
        lastTs != null && System.currentTimeMillis() - lastTs < CAPTURE_HEARTBEAT_STALE_MS
    }.getOrDefault(false)

    private fun buildNotification(terminated: Boolean, awaitingConsent: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                when {
                    terminated && awaitingConsent -> "Sonicraft 音效待恢复"
                    terminated -> "Sonicraft 音效已停止"
                    else -> "Sonicraft 运行中"
                },
            )
            .setContentText(
                when {
                    terminated && awaitingConsent ->
                        "引擎已自动恢复，但内录授权已失效——点按通知重新授权"
                    terminated -> "被系统终止，正在自动恢复——无需操作"
                    else -> "内录音效引擎已启动"
                },
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun writeDiag(msg: String) {
        runCatching {
            val f = File(filesDir, "crash/autostart_diag.txt")
            f.parentFile?.mkdirs()
            f.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }
    }

    companion object {
        const val PREFS_NAME = "sonicraft_settings"
        const val KEY_SESSION_WAS_RUNNING = "session_was_running"

        /** 前台服务类型选择（方案 C 实测开关，2026-11-07） */
        const val EXTRA_KIND = "extra_kind"
        const val KIND_MP = "mp"
        const val KIND_LOOPBACK = "loopback"
        const val KEY_FGS_TYPE_LOOPBACK = "fgs_type_loopback"
        const val FGS_TYPE_SPECIAL_USE = "special_use"
        const val FGS_TYPE_MEDIA_PLAYBACK = "media_playback"
        const val KEY_SYSTEM_KILLED_UNACKED = "system_killed_unacked"
        const val EXTRA_FROM_BOOT = "extra_from_boot"
        const val EXTRA_FROM_ALARM = "extra_from_alarm"

        private const val AUTO_RESTORE_CONFIRM_MS = 8_000L
        private const val CAPTURE_HEARTBEAT_STALE_MS = 5_000L
        private const val HEARTBEAT_TAIL_BYTES = 4096
        const val CHANNEL_ID = "sonicraft_dsp_v2"
        private const val NOTIFICATION_ID = 1
    }
}
