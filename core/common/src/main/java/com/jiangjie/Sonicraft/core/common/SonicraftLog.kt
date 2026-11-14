/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.common

import android.util.Log

/**
 * 统一日志入口。默认 tag 前缀 `Sonicraft/`，debug 构建自动附带调用类名。
 */
object SonicraftLog {
    private const val PREFIX = "Sonicraft"

    var debugEnabled: Boolean = true

    fun d(tag: String, message: String) {
        if (debugEnabled) Log.d("$PREFIX/$tag", message)
    }

    fun i(tag: String, message: String) {
        if (debugEnabled) Log.i("$PREFIX/$tag", message)
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        Log.w("$PREFIX/$tag", message, tr)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        Log.e("$PREFIX/$tag", message, tr)
    }
}
