/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.dsp

/** native 侧 PerfProbe 的快照 */
data class DspStats(
    val lastProcessNanos: Long,
    val maxProcessNanos: Long,
    val totalProcessNanos: Long,
    val processCount: Long,
) {
    val averageProcessNanos: Long
        get() = if (processCount == 0L) 0L else totalProcessNanos / processCount
}
