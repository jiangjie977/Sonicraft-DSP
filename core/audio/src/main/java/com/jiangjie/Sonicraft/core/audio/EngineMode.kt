/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.audio

/**
 * 引擎工作模式。
 *
 * MVP 主线为 [CaptureRelay]（内录接管）；LocalPlayer 作为零缺陷兜底模式随后补齐。
 */
sealed interface EngineMode {
    /** Mode A：本 App 解码播放，无原声叠加、无内录延迟 */
    data object LocalPlayer : EngineMode

    /**
     * Mode B：MediaProjection 内录接管。
     *
     * 注意：内录只 tap 不拦截，原声不会消失，处理后声音与原声叠加是机制性结果
     * （除非目标 App 提供每应用音量等 OEM 能力）。启动前必须向用户明示。
     */
    data object CaptureRelay : EngineMode
}
