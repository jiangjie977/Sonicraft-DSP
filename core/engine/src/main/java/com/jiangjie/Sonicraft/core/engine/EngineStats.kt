/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.engine

/**
 * 实时负载统计（由 native 侧 PerfProbe 提供，约 4 Hz 采样推送）。
 *
 * @param dspCpuLoadPercent DSP 图每帧耗时占实时预算（128 帧 @48k = 2.67 ms）的百分比
 * @param lastProcessNanos 最近一次 process() 耗时（纳秒）
 * @param maxProcessNanos 历史最大单次处理耗时
 * @param xrunCount 输入欠载（capture 跟不上 playback）累计次数
 * @param waterMarkFrames 输入环形缓冲当前水位（帧），持续逼近上限预示溢出风险
 * @param inputLevel 输入 RMS 电平（0..1）
 * @param framesProcessed 累计处理帧数
 */
data class EngineStats(
    val dspCpuLoadPercent: Float = 0f,
    val lastProcessNanos: Long = 0,
    val maxProcessNanos: Long = 0,
    /** 累计处理耗时（ns）：配合 framesProcessed 算平均，区分偶发尖峰与持续偏高 */
    val totalProcessNanos: Long = 0,
    val xrunCount: Long = 0,
    val waterMarkFrames: Long = 0,
    val inputLevel: Float = 0f,
    val framesProcessed: Long = 0,
    /** 卷积器 Prepare 次数（增长 = 图被重建，预设会被重置为 Dry，混响消失） */
    val convPrepareCount: Long = 0,
    /** 卷积器当前预设（0 = Dry，即无混响） */
    val convPreset: Long = 0,
    /** 卷积器湿声 RMS（0 且预设非 Dry ⇒ 卷积状态坏死） */
    val convWetRms: Float = 0f,
    /** 逐节点平均耗时（ns/次），索引对应节点 0..9 */
    val nodeAvgNanos: LongArray = LongArray(0),
) {
    val lastProcessMs: Float get() = lastProcessNanos / 1_000_000f
}
