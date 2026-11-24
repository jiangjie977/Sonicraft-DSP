/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 压限默认参数迁移的纯逻辑（可单测）。
 */
package com.jiangjie.Sonicraft.core

/**
 * 压限默认参数一次性迁移。
 *
 * 背景：2026-11-24 之前出厂默认（单段 −20dB/4:1/makeup 0、多段 −11/−22/−20）
 * 在 0 dBFS 下净衰减 11~15 dB，用户反馈「开软件声音变小」。
 * 默认值已改为不损响度的温和值，但**老用户的 prefs 里还存着旧值**，必须迁移。
 *
 * 规则：**只有当前值精确等于旧默认**才替换——用户改过任一项就不动，
 * 避免覆盖精调结果（例如用户把 threshold 调到 −12 又享受默认 ratio 的场景）。
 */
object CompressorMigration {

    /**
     * 计算需要写入的项。
     *
     * @param current 当前 prefs 值
     * @param legacy  旧默认值
     * @param fresh   新默认值
     * @return index → 新值；空 map 表示无需迁移
     */
    fun plan(current: FloatArray, legacy: FloatArray, fresh: FloatArray): Map<Int, Float> {
        require(current.size == legacy.size && legacy.size == fresh.size) {
            "current/legacy/fresh must have the same size"
        }
        val out = LinkedHashMap<Int, Float>()
        for (i in current.indices) {
            if (legacy[i] != fresh[i] && current[i] == legacy[i]) {
                out[i] = fresh[i]
            }
        }
        return out
    }
}
