/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege;

/**
 * Loopback PCM 回传回调（P1，2026-11-04）。
 *
 * 只能 oneway：采集线程每 ~20ms 回调一次，若走同步 binder 往返会被 app 侧处理
 * 阻塞，直接导致 AudioRecord FIFO 溢出丢样（听感为周期性爆音）。
 *
 * 数据格式固定 PCM16 小端、交织、2 声道（与引擎 STEREO_48K 对齐由 app 侧重采样）。
 */
oneway interface ILoopbackCallback {
    /** @param data PCM16 交织数据；@param frames 帧数（= data.size / (2 * channels)） */
    void onPcm(in byte[] data, int frames);

    void onError(String msg);
}
