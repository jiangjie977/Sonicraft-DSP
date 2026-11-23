/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.dsp

import kotlin.jvm.JvmStatic

/**
 * native DSP 图的 Kotlin 句柄。
 *
 * 线程约束：process() 仅限实时线程调用；pushCommand()/stats() 可跨线程。
 * 所有 native 方法无参数名校验，签名必须与 `sonicraft/jni/bridge.cpp` 保持一致。
 */
class DspGraphHandle private constructor(private var handle: Long) : AutoCloseable {

    val nodeCount: Int
        get() = if (handle == 0L) 0 else nativeNodeCount(handle)

    /** 就地处理交织缓冲（float32，长度 >= frames * channels） */
    fun process(buffer: FloatArray, frames: Int) {
        requireOpen()
        nativeProcess(handle, buffer, frames)
    }

    /** 投递参数/旁路命令（SPSC 队列满时丢弃，调用方需自行节流） */
    fun pushCommand(nodeIndex: Int, paramId: Int, value: Float, isBypass: Boolean) {
        if (handle == 0L) return
        nativePushCommand(handle, nodeIndex, paramId, value, isBypass)
    }

    fun setNodeBypass(nodeIndex: Int, bypass: Boolean) {
        pushCommand(nodeIndex, paramId = 0, value = if (bypass) 1f else 0f, isBypass = true)
    }

    fun stats(): DspStats {
        requireOpen()
        val raw = nativeStats(handle)
        return DspStats(
            lastProcessNanos = raw[0],
            maxProcessNanos = raw[1],
            totalProcessNanos = raw[2],
            processCount = raw[3],
        )
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun requireOpen() = check(handle != 0L) { "DspGraphHandle 已关闭" }

    private external fun nativeProcess(handle: Long, buffer: FloatArray, frames: Int)
    private external fun nativePushCommand(
        handle: Long,
        nodeIndex: Int,
        paramId: Int,
        value: Float,
        isBypass: Boolean,
    )
    private external fun nativeStats(handle: Long): LongArray
    private external fun nativeNodeCount(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("sonicraft-dsp")
        }

        fun create(sampleRate: Int, channels: Int, maxFrames: Int): DspGraphHandle {
            val handle = nativeCreate(sampleRate, channels, maxFrames)
            check(handle != 0L) { "native DspGraph 创建失败" }
            return DspGraphHandle(handle)
        }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, channels: Int, maxFrames: Int): Long
        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        /** 卷积混响预设显示名（native X-macro 单源；索引 = ConvPreset 枚举 = REVERB_PRESET_*）。
         *  首访触发 JNI；库已由 init 块加载，null 仅可能是 OOM 级异常，UI 侧按空表降级。 */
        val CONVOLVER_PRESET_NAMES: Array<String> by lazy { nativeConvolverPresetNames() ?: emptyArray() }

        @JvmStatic
        private external fun nativeConvolverPresetNames(): Array<String>?
    }
}
