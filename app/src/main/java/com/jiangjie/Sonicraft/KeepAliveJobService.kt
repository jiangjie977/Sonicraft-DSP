/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 夜间保活兜底（2026-10-28 立项）：持久化周期 job——跨 system_server 软重启存活
 * （persisted job 存 /data/system/job/；STICKY service / BOOT 广播 / alarm 在软重
 * 启场景全部失效，昨晚取证实锤）。
 *
 * 每 15 分钟触发：若「上次会话运行中」而引擎未运行（软重启杀掉了一切）→ 重挂警
 * 示通知（普通 notify，无需 FGS）——用户看手机必然看到，点按进 app 即 autoStart
 * 3 秒完整恢复（含参数重放与接管恢复）。引擎运行中则静默退出。
 *
 * 调度时机：内录启动成功 schedule、用户停止 cancel（运行中无需兜底）。
 */
@AndroidEntryPoint
class KeepAliveJobService : JobService() {

    @Inject lateinit var engine: AudioEngine

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = getSharedPreferences(ForegroundDspService.PREFS_NAME, Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, false)
        val engineRunning = engine.state.value is EngineState.Running
        if (!wasRunning || engineRunning) return false

        prefs.edit().putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, true).apply()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, ForegroundDspService.CHANNEL_ID)
                .setContentTitle("Sonicraft 音效已停止")
                .setContentText("设备重启导致内录终止——点按打开自动恢复音效")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(false)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
        )
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 1001
        private const val NOTIF_ID = 1002

        /** 内录启动成功后调度（持久化，跨重启存活）。persisted periodic 下限 15 分钟。 */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, KeepAliveJobService::class.java),
            )
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }

        /** 撤掉警示通知（恢复成功/用户停止时调用——job cancel 不撤已挂出的通知） */
        fun clearWarningNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }
}