/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 夜间保活短期方案：开机自启恢复警示。
 *
 * 背景（取证结论）：夜间音效丢失根因 = system_server watchdog 软重启（NX733J
 * ROM 慢性病，3 天 5 次），全部 app 进程连同 MediaProjection consent 授权一并
 * 死亡且无法自愈。本 receiver 在「上次会话运行中」的设备开机时：
 *  1. 写入「被系统终止未确认」标记 → 用户打开 app 时顶部横幅提示 + 一键恢复；
 *  2. 拉起前台服务（BOOT_COMPLETED 属 FGS 后台启动豁免场景）——通知常驻提醒
 *     「音效已停止」，点按进 app 恢复；capture token 已随进程死亡失效，无法静默恢复。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(ForegroundDspService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ForegroundDspService.KEY_SESSION_WAS_RUNNING, false)) return

        prefs.edit()
            .putBoolean(ForegroundDspService.KEY_SYSTEM_KILLED_UNACKED, true)
            .apply()
        writeDiag(context, "BOOT_COMPLETED：上次会话运行中，已写被终止标记并拉起警示服务")
        // trae 审阅采纳：Android 15（targetSdk 35）起官方对 BOOT_COMPLETED
        // 启动 mediaProjection 等 6 类 FGS 移除豁免，违规抛
        // ForegroundServiceStartNotAllowedException。本机 NX733J/Android16 实测 105 次
        // BOOT 全放行（电池优化白名单的 FGS 后台启动豁免覆盖，autostart_diag 双侧行
        // 时间戳配对实锤）——但豁免可被用户撤销，撤销后此路径崩溃回归。runCatching
        // 兜底：拉不起就止步于标记+警示通知，恢复交给 job/alarm 哨兵双保险。
        runCatching {
            context.startForegroundService(
                Intent(context, ForegroundDspService::class.java)
                    .putExtra(ForegroundDspService.EXTRA_FROM_BOOT, true),
            )
        }.onFailure {
            writeDiag(context, "BOOT 拉起服务被系统拒绝（FGS 后台启动限制/豁免被撤）：${it.message}")
        }
    }

    private fun writeDiag(context: Context, msg: String) {
        runCatching {
            val f = File(context.filesDir, "crash/autostart_diag.txt")
            f.parentFile?.mkdirs()
            f.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }
    }
}
