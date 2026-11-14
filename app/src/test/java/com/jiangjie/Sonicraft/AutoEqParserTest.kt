package com.jiangjie.Sonicraft

/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AutoEqParser（ParametricEQ.txt）解析回归 */
class AutoEqParserTest {

    @Test
    fun `标准 AutoEq 导出全文解析`() {
        val text = """
            Preamp: -6.8 dB
            Filter 1: ON PK Fc 31 Hz Gain 2.5 dB Q 0.70
            Filter 2: ON PK Fc 60 Hz Gain 3.0 dB Q 0.70
            Filter 3: ON LSC Fc 105 Hz Gain 5.0 dB Q 0.70
            Filter 4: ON HSC Fc 7500 Hz Gain -4.0 dB Q 0.70
        """.trimIndent()
        val r = AutoEqParser.parse(text)
        assertNotNull(r)
        assertEquals(-6.8f, r!!.preampDb, 1e-6f)
        assertEquals(4, r.bands.size)
        assertEquals(31f, r.bands[0].freq, 1e-6f)
        assertEquals(2.5f, r.bands[0].gain, 1e-6f)
        assertEquals(0.70f, r.bands[0].q, 1e-6f)
        assertEquals(0, r.bands[0].type)
        assertEquals(1, r.bands[2].type) // LSC
        assertEquals(2, r.bands[3].type) // HSC
        assertEquals(-4.0f, r.bands[3].gain, 1e-6f)
    }

    @Test
    fun `旧写法 LSQ_HSQ_PKQ 等价映射`() {
        val r = AutoEqParser.parse(
            "Filter 1: ON LSQ Fc 100 Hz Gain 3 dB Q 0.5\n" +
                "Filter 2: ON HSQ Fc 8000 Hz Gain -2 dB Q 0.5\n" +
                "Filter 3: ON PKQ Fc 1000 Hz Gain 1 dB Q 1.0",
        )
        assertNotNull(r)
        assertEquals(listOf(1, 2, 0), r!!.bands.map { it.type })
    }

    @Test
    fun `LP_HP 行跳过不阻塞其余段`() {
        val r = AutoEqParser.parse(
            "Preamp: -3.0 dB\n" +
                "Filter 1: ON LP Fc 100 Hz Gain 0 dB Q 0.70\n" +
                "Filter 2: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.0\n" +
                "Filter 3: ON HP Fc 10000 Hz Gain 0 dB Q 0.70",
        )
        assertNotNull(r)
        assertEquals(1, r!!.bands.size)
        assertEquals(1000f, r.bands[0].freq, 1e-6f)
        assertEquals(-3.0f, r.preampDb, 1e-6f)
    }

    @Test
    fun `无滤波器行返回 null`() {
        assertNull(AutoEqParser.parse(""))
        assertNull(AutoEqParser.parse("Preamp: -2.0 dB"))
        assertNull(AutoEqParser.parse("随便一段文字"))
    }

    @Test
    fun `越界值钳制到安全域`() {
        val r = AutoEqParser.parse(
            "Filter 1: ON PK Fc 5 Hz Gain 30 dB Q 0.05\n" +
                "Filter 2: ON PK Fc 50000 Hz Gain -30 dB Q 20",
        )
        assertNotNull(r)
        assertEquals(20f, r!!.bands[0].freq, 1e-6f)
        assertEquals(12f, r.bands[0].gain, 1e-6f)
        assertEquals(0.3f, r.bands[0].q, 1e-6f)
        assertEquals(20000f, r.bands[1].freq, 1e-6f)
        assertEquals(-12f, r.bands[1].gain, 1e-6f)
        assertEquals(8f, r.bands[1].q, 1e-6f)
    }

    @Test
    fun `超过 31 段截断且 preamp 取最后一次出现`() {
        val sb = StringBuilder("Preamp: -1.0 dB\n")
        for (i in 1..35) {
            sb.append("Filter $i: ON PK Fc ${100 + i} Hz Gain 1.0 dB Q 1.0\n")
        }
        sb.append("Preamp: -2.5 dB\n")
        val r = AutoEqParser.parse(sb.toString())
        assertNotNull(r)
        assertEquals(31, r!!.bands.size)
        assertTrue(r.bands.all { it.type == 0 })
        assertEquals(-2.5f, r.preampDb, 1e-6f)
    }
}
