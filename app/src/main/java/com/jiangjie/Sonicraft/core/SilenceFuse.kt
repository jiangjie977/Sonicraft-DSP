/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 静音保险判定（纯逻辑，可脱离设备单测）。
 */
package com.jiangjie.Sonicraft.core

/**
 * 接管静音的安全阀。
 *
 * 背景：Loopback 接管后原声被 DP/appops 压住，只有 DSP 副本出声。
 * 一旦副本持续归零（切歌、蓝牙切换、重路由被打回），原声被压 + 副本无数据 = **彻底无声**。
 * 铁律：**宁可双声，绝不留「压掉唯一音源」的静音**。
 *
 * 实现约束（踩过坑）：
 * - 只有**曾经见过副本数据**的 uid 才判连坐——建链初期本来就零电平，
 *   那时释放静音会让启动瞬间出现双声窗口；
 * - 必须连续 [RELEASE_STREAK] 轮判定，单轮抖动（缓冲切换）不触发。
 */
object SilenceFuse {

    /** 低于该电平视为「副本掉零」（≈ -46 dBFS） */
    const val LOW_THRESHOLD = 0.005f

    /** 连续多少轮归零才释放静音 */
    const val RELEASE_STREAK = 2

    enum class Decision {
        /** 电平正常：应清空 streak，并标记该 uid 见过数据 */
        FRESH,

        /** 见过数据 + 连续归零，但未达阈值：streak + 1 */
        COUNTING,

        /** 达到阈值：释放静音并标记直出 */
        RELEASE,

        /** 从未见过数据（建链初期）：不判，也不累计 */
        IGNORE,
    }

    /**
     * @param level     当前副本输入电平（0~1）
     * @param sawAudio  该 uid 是否曾经出现过有效电平
     * @param streak    已连续归零的轮数
     */
    fun evaluate(level: Float, sawAudio: Boolean, streak: Int): Decision {
        if (level > LOW_THRESHOLD) return Decision.FRESH
        if (!sawAudio) return Decision.IGNORE
        return if (streak + 1 >= RELEASE_STREAK) Decision.RELEASE else Decision.COUNTING
    }
}
