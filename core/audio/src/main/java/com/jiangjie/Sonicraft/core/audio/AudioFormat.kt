/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.audio

/**
 * 引擎内部统一音频格式。
 *
 * 设计约束：全链路 float32 / 交织存储 / 立体声。48 kHz 是 Android 设备原生采样率的
 * 事实标准，优先与 AAudio 原生采样率对齐，可完全避免输出侧重采样。
 */
data class AudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
) {
    companion object {
        const val SAMPLE_RATE_48K = 48_000
        const val CHANNELS_STEREO = 2

        val STEREO_48K = AudioFormat(SAMPLE_RATE_48K, CHANNELS_STEREO)

        /**
         * 每声道帧数上限（192 帧 ≈ 4.0 ms @48k）。
         * 与真机 AAudio 回调帧数（实测 192）对齐：引擎 OnData 整回调单块处理，
         * 避免 128+64 分块让 64 帧小块付出全尺寸 FFT 成本（卷积混响的块成本
         * 与帧数无关，小块是纯浪费）。后台噪声排查结论。
         */
        const val FRAMES_PER_BLOCK = 192
    }
}
