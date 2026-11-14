/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * 夜间保活 alarm 哨兵（2026-11-03 立项）：[KeepAliveJobService]（persisted periodic）
 * 在 Doze 深睡期会被 JobScheduler defer 到维护窗口——凌晨簇整夜死的时间段恰是 job
 * 触发盲区。本哨兵用 setExactAndAllowWhileIdle 补 Doze 下的准点触发（allowWhileIdle
 * 每 15min 窗口强制放行），与 job 形成双保险：alarm 管「准点」，job 管「跨软重启」
 * （alarm 由 system_server 内存持有，软重启即清空——10-28 取证在案）。
 *
 * 每 15 分钟触发：若「上次会话运行中」而引擎未运行 → startForegroundService 拉起
 * （EXTRA_FROM_ALARM，与 BOOT 同款 terminated 语义：警示通知 + 静默恢复尝试 + 8s
 * 心跳确认）。Android 12+ 从 alarm 触发 FGS 需 exact alarm 权限豁免后台启动限制，
 * USE_EXACT_ALARM（API 33+，sideload 安装即授；31/32 用 SCHEDULE_EXACT_ALARM 默认
 * 授予）——权限缺失时降级挂警示通知（与 job 同款），不丢提示。
 *
 * 链条维护：receiver 每次触发先无条件续下一发（alarm 跨进程死亡存活——kill 级回收
 * 后链仍在；force-stop 会清 alarm，但那是用户主动划掉，本就不该复活）；
 * ForegroundDspService.onStartCommand 统一补 schedule（幂等），覆盖 BOOT/STICKY/
 * 恢复成功后链条重建。用户显式停止时 MainViewModel cancel（与 job 同点）。
 */
@AndroidEntryPoint
class AlarmSentryReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: AudioEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENTRY_TICK) return
        // 链条自续优先：无论判定结果如何，下一发必须排上（alarm 链断 = 哨兵失效）
        schedule(context)

        val prefs = context.getSharedPreferences(ForegroundDspService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, false)) return
        if (engine.state.value is EngineState.Running) return

        writeDiag(context, "alarm 哨兵触发：上次会话运行中而引擎未跑，拉起前台服务")
        val started = runCatching {
            context.startForegroundService(
                Intent(context, ForegroundDspService::class.java)
                    .putExtra(ForegroundDspService.EXTRA_FROM_ALARM, true),
            )
        }.isSuccess
        if (!started) {
            // exact alarm 权限被系统/用户撤销 → alarm 触发本身可能降级或 FGS 拉起被拒
            // （ForegroundServiceStartNotAllowedException）——降级挂警示通知兜底
            writeDiag(context, "alarm 哨兵 FGS 拉起被拒，降级挂警示通知")
            postWarningNotification(context)
        }
    }

    private fun postWarningNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(
            WARNING_NOTIF_ID,
            NotificationCompat.Builder(context, ForegroundDspService.CHANNEL_ID)
                .setContentTitle("Sonicraft 音效已停止")
                .setContentText("设备重启导致内录终止——点按打开自动恢复音效")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(false)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
        )
    }

    private fun writeDiag(context: Context, msg: String) {
        runCatching {
            val f = File(context.filesDir, "crash/autostart_diag.txt")
            f.parentFile?.mkdirs()
            f.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }
    }

    companion object {
        const val ACTION_SENTRY_TICK = "com.jiangjie.Sonicraft.ACTION_ALARM_SENTRY"
        private const val REQUEST_CODE = 2001

        /** 与 KeepAliveJobService 警示通知同 ID：恢复成功时 clearWarningNotification 一并撤掉 */
        private const val WARNING_NOTIF_ID = 1002
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /** 排下一发（幂等，同 requestCode 覆盖）。exact 不可用时降级非 exact（Doze 弹性窗口） */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = pendingIntent(context)
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            val exactGranted = if (android.os.Build.VERSION.SDK_INT >= 31) {
                am.canScheduleExactAlarms()
            } else {
                true
            }
            if (exactGranted) {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_SENTRY_TICK).setComponent(
                ComponentName(context, AlarmSentryReceiver::class.java),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
