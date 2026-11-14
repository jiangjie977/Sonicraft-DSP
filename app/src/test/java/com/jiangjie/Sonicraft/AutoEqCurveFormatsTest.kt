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

/**
 * AutoEq **曲线类**格式回归（GraphicEQ / 结果 CSV）、曲线→段的映射、以及超范围钳位计数。
 *
 * 素材取自 AutoEq 真实导出（Sennheiser HD 600 / AKG K371），字段值与文件一致。
 */
class AutoEqCurveFormatsTest {

    @Test
    fun `GraphicEQ 单行按分号解析为升序点列`() {
        val txt = "GraphicEQ: 20 -8.6; 100 -7.1; 1000 -6.0; 5000 -2.5; 10000 -3.0; " +
            "16000 -4.4; 20000 -6.6; 30 -8.4; 300 -6.9; 3000 -5.1"
        val pts = AutoEqParser.parseGraphic(txt)
        assertNotNull(pts)
        assertEquals(10, pts!!.size)
        // 必须升序（后面 interpLog 依赖有序）
        assertTrue(pts[0].first < pts[1].first)
        assertEquals(20f, pts.first().first, 1e-4f)
        assertEquals(-8.6f, pts.first().second, 1e-4f)
    }

    @Test
    fun `曲线按目标频率映射成定 Q 段`() {
        val curve = listOf(20f to -6f, 100f to -2f, 1000f to 0f, 10000f to 3f, 20000f to 3f)
        val freqs = EqMath.freqTable(10)
        val bands = AutoEqParser.curveToBands(curve, freqs)
        assertEquals(10, bands.size)
        bands.forEach { b ->
            assertEquals(0, b.type)
            assertEquals(1.41f, b.q, 1e-4f)
            assertTrue("增益应被钳到 ±12", b.gain in -12f..12f)
        }
        // 目标频率与传入一致（不改段位置）
        assertEquals(freqs[0], bands[0].freq, 1e-4f)
    }

    @Test
    fun `结果 CSV 取 equalization 列`() {
        val csv = """
            frequency,raw,smoothed,error,error_smoothed,equalization,parametric_eq,target
            20.00,-7.88,-7.89,-11.31,-11.32,6.00,6.29,3.43
            100.00,-6.00,-6.10,-9.00,-9.10,4.50,4.80,3.00
            1000.00,-5.00,-5.10,-8.00,-8.10,3.00,3.20,2.10
            5000.00,-4.00,-4.10,-6.00,-6.10,1.50,1.60,0.10
            10000.00,-3.00,-3.10,-4.00,-4.10,-0.50,-0.40,-1.00
            20000.00,-2.00,-2.10,-2.00,-2.10,-2.50,-2.40,-3.00
        """.trimIndent()
        val pts = AutoEqParser.parseCsvCurve(csv)
        assertNotNull(pts)
        assertEquals(6, pts!!.size)
        assertEquals(6.00f, pts.first().second, 1e-3f)   // equalization 列，未取反
        assertEquals(-2.50f, pts.last().second, 1e-3f)
    }

    @Test
    fun `CSV 无 equalization 列时用 error_smoothed 取反`() {
        val csv = """
            frequency,raw,error_smoothed
            20.00,-7.88,-11.32
            100.00,-6.00,-9.10
            1000.00,-5.00,-8.10
            5000.00,-4.00,-6.10
            10000.00,-3.00,-4.10
            20000.00,-2.00,-2.10
        """.trimIndent()
        val pts = AutoEqParser.parseCsvCurve(csv)
        assertNotNull(pts)
        assertEquals(11.32f, pts!!.first().second, 1e-3f)   // 取反后为正
    }

    @Test
    fun `非 AutoEq 文本一律返回 null`() {
        assertNull(AutoEqParser.parseGraphic("随便一段文字"))
        assertNull(AutoEqParser.parseCsvCurve("a,b,c\n1,2,3"))
    }

    @Test
    fun `超范围增益计入 clamped 计数`() {
        val txt = """
            Preamp: -3.0 dB
            Filter 1: ON PK Fc 100 Hz Gain 18.0 dB Q 1.00
            Filter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.00
            Filter 3: ON PK Fc 20 Hz Gain 3.0 dB Q 12.0
        """.trimIndent()
        val r = AutoEqParser.parse(txt)
        assertNotNull(r)
        assertEquals(3, r!!.bands.size)
        // 第 1 段增益 18 → 12；第 3 段 Q 12 → 8：两处钳位
        assertEquals(2, r.clamped)
        assertEquals(12f, r.bands[0].gain, 1e-4f)
        assertEquals(8f, r.bands[2].q, 1e-4f)
    }
}
