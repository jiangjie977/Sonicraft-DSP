package com.jiangjie.Sonicraft

/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 用 **AutoEq 项目真实导出文件**做回归（2026-11-14 下载自
 * `jaakkopasanen/AutoEq` 的 `results/oratory1990/...`）。
 *
 * 与手写样例不同，这些文件能暴露真实世界的细节：段顺序不按频率排、
 * LSC/HSC 出现在首尾、Q 值跨度大（0.55~5.82）、FixedBandEQ 全部 Q=1.41。
 *
 * 目的：一旦解析器改动导致真实文件少段/错位，这里立即红。
 */
class AutoEqRealFilesTest {

    /** results/oratory1990/over-ear/Sennheiser HD 600/Sennheiser HD 600 ParametricEQ.txt */
    private val hd600Parametric = """
        Preamp: -6.3 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
        Filter 2: ON PK Fc 125 Hz Gain -2.7 dB Q 0.55
        Filter 3: ON PK Fc 8445 Hz Gain 3.3 dB Q 1.61
        Filter 4: ON PK Fc 522 Hz Gain 0.7 dB Q 1.02
        Filter 5: ON PK Fc 1298 Hz Gain -1.2 dB Q 2.14
        Filter 6: ON HSC Fc 10000 Hz Gain -3.1 dB Q 0.70
        Filter 7: ON PK Fc 3158 Hz Gain -1.8 dB Q 3.67
        Filter 8: ON PK Fc 2166 Hz Gain 0.9 dB Q 3.32
        Filter 9: ON PK Fc 6639 Hz Gain 2.2 dB Q 5.82
        Filter 10: ON PK Fc 5433 Hz Gain -1.2 dB Q 5.70
    """.trimIndent()

    /** results/oratory1990/over-ear/AKG K371/AKG K371 FixedBandEQ.txt */
    private val k371Fixed = """
        Preamp: -4.8 dB
        Filter 1: ON PK Fc 31 Hz Gain -3.0 dB Q 1.41
        Filter 2: ON PK Fc 62 Hz Gain 1.2 dB Q 1.41
        Filter 3: ON PK Fc 125 Hz Gain -1.4 dB Q 1.41
        Filter 4: ON PK Fc 250 Hz Gain -1.8 dB Q 1.41
        Filter 5: ON PK Fc 500 Hz Gain 0.8 dB Q 1.41
        Filter 6: ON PK Fc 1000 Hz Gain -0.8 dB Q 1.41
        Filter 7: ON PK Fc 2000 Hz Gain -0.3 dB Q 1.41
        Filter 8: ON PK Fc 4000 Hz Gain 3.0 dB Q 1.41
        Filter 9: ON PK Fc 8000 Hz Gain -0.2 dB Q 1.41
        Filter 10: ON PK Fc 16000 Hz Gain 4.8 dB Q 1.41
    """.trimIndent()

    @Test
    fun `HD600 ParametricEQ 逐段解析`() {
        val r = AutoEqParser.parse(hd600Parametric)
        assertNotNull(r)
        assertEquals(-6.3f, r!!.preampDb, 1e-4f)
        assertEquals(10, r.bands.size)
        // 首段是低架（LSC），不是 PK
        assertEquals(1, r.bands[0].type)
        assertEquals(105f, r.bands[0].freq, 1e-4f)
        assertEquals(6.5f, r.bands[0].gain, 1e-4f)
        // 第二段 Q 只有 0.55（真实文件里很少见的小 Q）
        assertEquals(0.55f, r.bands[1].q, 1e-4f)
        assertEquals(-2.7f, r.bands[1].gain, 1e-4f)
        // 末段是峰值（文件里第 6 条是 HSC，段序不按频率排，按文件顺序保留）
        assertEquals(2, r.bands[5].type)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `FixedBandEQ 十段全 PK 且 Q 一致`() {
        val r = AutoEqParser.parse(k371Fixed)
        assertNotNull(r)
        assertEquals(-4.8f, r!!.preampDb, 1e-4f)
        assertEquals(10, r.bands.size)
        assertEquals(0, r.skipped)
        r.bands.forEach { b ->
            assertEquals(0, b.type)
            assertEquals(1.41f, b.q, 1e-3f)
        }
        assertEquals(31f, r.bands.first().freq, 1e-4f)
        assertEquals(-3.0f, r.bands.first().gain, 1e-4f)
        assertEquals(16000f, r.bands.last().freq, 1e-4f)
    }

    @Test
    fun `GraphicEQ 格式当前不支持 明确返回 null`() {
        // AutoEq 同时导出 GraphicEQ（一串 "频率 增益;"），我们只支持参数化格式。
        // 这条断言把这个"已知限制"固化下来：将来支持了会先让这条红，提醒改文案。
        val graphic = "GraphicEQ: 20 -0.2; 21 -0.2; 22 -0.2; 30 -0.5"
        assertNull(AutoEqParser.parse(graphic))
    }
}
