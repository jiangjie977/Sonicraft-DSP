/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import android.content.Context
import android.os.Build
import android.util.Log
import com.jiangjie.Sonicraft.core.common.DiagDirs
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自捕获：把 Java 未捕获异常与 native 信号崩溃写到 app 私有目录
 * （filesDir/crash/），不依赖 logcat —— 某些国产 ROM 会过滤第三方 app 的
 * logcat 输出。
 *
 * 文件可被 `adb pull` 取出（无需 root）。
 * 真实目录在此注入 [DiagDirs.crashDir]，core 层统一从那里读（去硬编码）。
 */
object CrashCapture {

    private const val TAG = "CrashCapture"

    fun install(context: Context) {
        val dir = File(context.filesDir, "crash")
        dir.mkdirs()
        DiagDirs.crashDir = dir.absolutePath
        // 旧版本把崩溃文件写到外部存储，run-as 读不到；启动时迁移到内部存储便于拉取
        runCatching {
            val extDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash")
            extDir.listFiles()?.forEach { f ->
                val d = File(dir, f.name)
                if (!d.exists()) f.copyTo(d)
            }
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = File(dir, "crash_java_$ts.txt")
                FileWriter(f).use { w ->
                    w.write("Time: $ts\n")
                    w.write("Thread: ${t.name}\n")
                    w.write("Android: ${Build.VERSION.SDK_INT} / ${Build.MODEL} / ${Build.FINGERPRINT}\n")
                    w.write("Process: ${android.os.Process.myPid()}\n")
                    w.write("---- stack ----\n")
                    w.write(Log.getStackTraceString(e))
                }
            } catch (ignore: Exception) {
                // 写入失败也不影响默认处理
            }
            default?.uncaughtException(t, e)
        }

        // 旧实现每次启动都 open(O_TRUNC) 一个 crash_native_<ts>.txt，留下大量 0 字节文件。
        // native 侧已改为「崩溃时才 append 写」，这里统一用固定文件名并清理历史空文件。
        runCatching {
            dir.listFiles { f -> f.name.startsWith("crash_native_") }?.forEach { f ->
                if (f.length() == 0L) f.delete()
            }
        }
        try {
            System.loadLibrary("sonicraft-dsp")
            nativeInstall(File(dir, "crash_native.log").absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "native 崩溃捕获安装失败", e)
        }
    }

    @JvmStatic
    private external fun nativeInstall(path: String)
}
