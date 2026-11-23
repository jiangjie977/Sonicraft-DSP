/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.engine

/** 引擎生命周期状态机（单一状态流，UI 只读） */
sealed interface EngineState {
    data object Idle : EngineState
    data object Preparing : EngineState
    /** 已准备（DSP 图已创建且自检通过），等待 start */
    data object Ready : EngineState
    data object Running : EngineState
    data object Suspended : EngineState
    data class Stopped(val reason: StopReason) : EngineState
    data class Failed(val reason: String) : EngineState
}

enum class StopReason {
    USER_REQUESTED,
    SOURCE_ENDED,
    AUDIO_FOCUS_LOST,
    DEVICE_DISCONNECTED,
    /** 投影失效自动止损：AudioRecord read 恒负，capture 已死亡。 */
    CAPTURE_DEAD,
}
