/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.jiangjie.Sonicraft.core.common.DiagDirs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 输出设备感知（M14a，2026-11-08）。
 *
 * 本批**只做感知与取证**：不改任何播放行为、不切配置、不动引擎。
 * 目的是先把「当前声音往哪出」这件事变成可观测事实，为 M14b 的按设备切换提供依据。
 *
 * 设计来源 = WEcho 已落地实现（`AudioDeviceMonitor.kt` / `ConfigApplier.kt`）：
 *  - `AudioDeviceCallback` 常驻 + **500ms debounce**（插拔/蓝牙握手会连发多次）
 *  - 当前输出按**优先级表**选取（蓝牙/有线/U​​SB 优先于内置扬声器）
 *  - 设备键 = `sanitize(productName)`；内置扬声器/听筒固定为 `speaker`
 *  - **空名回落 `headphone`，绝不回落 `speaker`**——WEcho 2026-10-14 事故：
 *    蓝牙握手期 `productName` 为空，若回落成 speaker 会被判成扬声器模式，
 *    出现「切到耳机却用扬声器配置」
 */
@Singleton
class OutputDeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val handler = Handler(Looper.getMainLooper())

    private var callback: AudioDeviceCallback? = null

    /** 上次已落盘的设备键（判等去抖：同一设备反复回调不重复写日志） */
    @Volatile
    private var lastKey: String? = null

    /**
     * 当前输出设备（键 + 展示名）。
     *
     * **必须作为一个对象一次性发出**：早先用两个独立 StateFlow 分别发 key 与 label，
     * 在 `Dispatchers.Main.immediate` 下收集器可能恰好在两次赋值之间被唤醒，读到的是
     * **上一个设备**的 label——实测把扬声器绑定的展示名写成了耳机名（2026-11-08 09:30:19）。
     */
    data class CurrentOutput(val key: String, val label: String)

    private val _current = MutableStateFlow(CurrentOutput(DEVICE_KEY_SPEAKER, LABEL_SPEAKER_CN))

    /** 当前输出（键+名，原子）——业务逻辑统一收这一个流 */
    val current: StateFlow<CurrentOutput> = _current.asStateFlow()

    /** 幂等注册；已注册则只重新评估一次当前输出，不重复挂回调 */
    fun start() {
        if (callback != null) {
            evaluate("re-start")
            return
        }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<AudioDeviceInfo>) {
                schedule("added:${devices.size}")
            }

            override fun onAudioDevicesRemoved(devices: Array<AudioDeviceInfo>) {
                schedule("removed:${devices.size}")
            }
        }
        audioManager.registerAudioDeviceCallback(cb, handler)
        callback = cb
        evaluate("register")
    }

    fun stop() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
        handler.removeCallbacks(debounceRunnable)
    }

    private fun schedule(reason: String) {
        pendingReason = reason
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    @Volatile
    private var pendingReason: String = "callback"

    private val debounceRunnable = Runnable { evaluate(pendingReason) }

    private fun evaluate(source: String) {
        val info = currentOutputDevice()
        val key = deviceKeyOf(info)
        if (key == lastKey) return
        writeDiag(
            "设备变更($source): ${lastKey ?: "-"} → $key | type=${info?.type ?: -1}" +
                " product=${info?.productName?.toString() ?: ""} addr=${info?.address ?: ""}"
        )
        lastKey = key
        // 原子发布：key 与 label 同一次赋值，收集器不可能读到半新半旧的状态
        _current.value = CurrentOutput(
            key = key,
            label = when {
                key == DEVICE_KEY_SPEAKER -> LABEL_SPEAKER_CN
                else -> info?.productName?.toString()?.takeIf { it.isNotBlank() } ?: LABEL_HEADPHONE_CN
            },
        )
    }

    /**
     * 当前输出设备。按优先级取第一个存在的：蓝牙/有线/USB 都优先于内置扬声器——
     * 因为内建扬声器在设备列表里**始终存在**，若先匹配它则永远选不到耳机。
     */
    private fun currentOutputDevice(): AudioDeviceInfo? {
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrDefault(emptyArray())
        for (type in PRIORITY_TYPES) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /**
     * 设备键：内置扬声器/听筒 → `speaker`；其余用 productName 的 sanitize 结果。
     * 空名回落 `headphone`（**禁止回落 speaker**，见类注释里的事故）。
     */
    private fun deviceKeyOf(info: AudioDeviceInfo?): String {
        if (info == null) return DEVICE_KEY_SPEAKER
        return when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> DEVICE_KEY_SPEAKER

            else -> {
                val name = info.productName?.toString()?.takeIf { it.isNotBlank() }
                if (name == null) DEVICE_KEY_HEADPHONE else sanitize(name)
            }
        }
    }

    /** 小写 + 非字母数字转下划线（同 WEcho `ConfigApplier.sanitizeDeviceName`） */
    fun sanitize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { DEVICE_KEY_HEADPHONE }

    /**
     * 当前输出是否为**外放**（内置扬声器 / 听筒）。
     *
     * 供虚拟低音的「按设备自动排斥」使用：虚拟低音是为外放设计的，
     * 耳机上强行开启容易低频浑浊。这里只暴露"是不是外放"这一个语义，
     * **不外泄内部 key 常量**（companion 保持 private）。
     *
     * 注意 `key != DEVICE_KEY_SPEAKER` 一律视为耳机类 —— 包括蓝牙、USB、
     * 有线，以及空名回落。这是类注释里那条事故的反向约定：
     * 宁可把未知设备当耳机（保守），也不能当外放。
     */
    fun isSpeakerOutput(): Boolean = _current.value.key == DEVICE_KEY_SPEAKER

    private fun writeDiag(msg: String) {
        runCatching {
            val f = File(DiagDirs.crashDir, "device_diag.txt")
            f.parentFile?.mkdirs()
            f.appendText("ts=${System.currentTimeMillis()} $msg\n")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val DEVICE_KEY_SPEAKER = "speaker"
        const val DEVICE_KEY_HEADPHONE = "headphone"

        /*** 展示名（UI 角标用，不走 sanitize） */
        const val LABEL_SPEAKER_CN = "扬声器"
        const val LABEL_HEADPHONE_CN = "耳机"

        // BLE 两个类型 API 31+，此处用字面量避免 minSdk 更低时的 NewApi 告警
        const val TYPE_BLE_HEADSET_INT = 26
        const val TYPE_BLE_SPEAKER_INT = 27

        /**
         * 输出设备优先级（媒体输出口径）。
         *
         * **刻意排除 TYPE_BLUETOOTH_SCO**（WEcho 的列表里有，我们不用）：实测蓝牙握手期会
         * 冒出一个 `type=7` 的 SCO 条目，`productName` 是手机自己的机型名（NX733J）、地址全零，
         * 且先于 A2DP 出现在列表里 → 被选成当前输出，造成一次伪切换（连带复位 loopbackRerouted、
         * 空跑 nudge）。SCO 本身只用于通话，媒体播放永远不会走它，排除后 A2DP 就位即正确命中。
         */
        val PRIORITY_TYPES = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            TYPE_BLE_HEADSET_INT,
            TYPE_BLE_SPEAKER_INT,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
    }
}
