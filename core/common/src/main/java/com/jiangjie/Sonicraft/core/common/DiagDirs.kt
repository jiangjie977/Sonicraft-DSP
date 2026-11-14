/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft.core.common

/**
 * 诊断文件目录单点（架构评估修复 ）。
 *
 * 历史问题：crash 路径 `/data/data/<pkg>/files/crash` 以字面量硬编码在
 * core 与 app 两层 10+ 处，锚定 applicationId——加 debug suffix、改包名、
 * 多用户/工作资料场景下所有诊断写入会 `runCatching` 静默失效（失明不自知）。
 *
 * 用法：app 层 `CrashCapture.install(context)` 在 Application.onCreate 注入
 * 真实 `filesDir/crash`；core 层（不得依赖 app）一律从这里读。
 * 未注入（单测/早期）时为空串，调用方跳过写入即可。
 */
object DiagDirs {

    /** app 真实崩溃/诊断目录绝对路径；空串 = 未注入，调用方应跳过写诊断 */
    @Volatile
    var crashDir: String = ""
}
