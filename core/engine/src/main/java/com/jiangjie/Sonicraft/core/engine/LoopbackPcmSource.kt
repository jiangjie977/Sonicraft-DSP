/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.engine

/**
 * Loopback PCM 源（P1，2026-11-04）：与 CaptureRelaySource 平级的**另一种输入**。
 *
 * 数据来自特权侧（shell uid=2000）的 AudioPolicy + AudioMix(LOOP_BACK)：
 * 它复制本机 MEDIA/GAME 流，因此**不受 per-stream capture policy 约束**——
 * 连 `ALLOW_CAPTURE_BY_SYSTEM` + `FLAG_NO_MEDIA_PROJECTION(0x400)` 的酷狗也能拿到。
 *
 * 设计约束：core/engine **不依赖 integration/stellar**（避免模块反向耦合）。
 * 具体实现（AIDL 调用 + PCM16→float 转换）放在 app 层，注入本接口即可。
 */
interface LoopbackPcmSource {
    /**
     * 开始采集。收到数据后调用 [onPcm]（float 交织、范围 [-1,1]）。
     * @return false = 启动失败（调用方应回退到 MediaProjection 或直出）
     */
    fun start(onPcm: (FloatArray, Int) -> Unit): Boolean

    fun stop()

    /** 源失效回调（record 读恒负等）：调用方据此止损回退 */
    fun setOnDead(listener: ((String) -> Unit)?) {}
}
