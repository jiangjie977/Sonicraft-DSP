package com.jiangjie.Sonicraft

/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AutoEq 解析的兼容性回归（2026-11-14 完善）。
 *
 * 真实世界的 ParametricEQ.txt 并不总是 AutoEq 官方那一种写法：
 *   · 有的工具省略 `ON`
 *   · shelf 段用斜率 `S 1.0` 代替 `Q`
 *   · 老版本用 `LS`/`HS` 而不是 `LSC`/`HSC`
 *   · 带低切/高切（`LP`/`HP`）或 `None` 的行——本应用 EQ 不支持，必须**计数并告知**而不是静默丢弃
 */
class AutoEqParserCompatTest {

    @Test
    fun `省略 ON 也能解析`() {
        val r = AutoEqParser.parse("Filter 1: PK Fc 100 Hz Gain 3.0 dB Q 1.40")
        assertNotNull(r)
        assertEquals(1, r!!.bands.size)
        assertEquals(100f, r.bands[0].freq, 1e-4f)
        assertEquals(3.0f, r.bands[0].gain, 1e-4f)
        assertEquals(1.40f, r.bands[0].q, 1e-4f)
    }

    @Test
    fun `省略 Q 时取 Butterworth 缺省 0_707`() {
        val r = AutoEqParser.parse("Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB S 1.0")
        assertNotNull(r)
        assertEquals(1, r!!.bands.size)
        assertEquals(0.707f, r.bands[0].q, 1e-4f)
        assertEquals(1, r.bands[0].type) // LSC
    }

    @Test
    fun `老写法 LS_HS 映射为 shelf 类型`() {
        val r = AutoEqParser.parse(
            """
            Filter 1: ON LS Fc 105 Hz Gain 5.0 dB Q 0.70
            Filter 2: ON HS Fc 8000 Hz Gain -3.0 dB Q 0.70
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals(2, r!!.bands.size)
        assertEquals(1, r.bands[0].type)
        assertEquals(2, r.bands[1].type)
    }

    @Test
    fun `LP_HP_None 行被跳过并计数`() {
        val r = AutoEqParser.parse(
            """
            Preamp: -5.0 dB
            Filter 1: ON PK Fc 60 Hz Gain 4.0 dB Q 0.70
            Filter 2: ON LP Fc 30 Hz
            Filter 3: ON HP Fc 12000 Hz
            Filter 4: ON None Fc 1000 Hz Gain 2.0 dB Q 1.00
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals(1, r!!.bands.size)
        assertEquals(3, r.skipped)
        assertEquals(-5.0f, r.preampDb, 1e-4f)
    }

    @Test
    fun `超过 31 段时多余段计入跳过`() {
        val sb = StringBuilder()
        for (i in 1..35) sb.append("Filter $i: ON PK Fc ${20 + i * 100} Hz Gain 1.0 dB Q 0.70\n")
        val r = AutoEqParser.parse(sb.toString())
        assertNotNull(r)
        assertEquals(31, r!!.bands.size)
        assertEquals(4, r.skipped)
    }
}
