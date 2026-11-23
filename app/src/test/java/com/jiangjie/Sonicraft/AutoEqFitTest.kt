/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线 → 段参数的**拟合**质量回归。
 *
 * 两种映射的差别正是这个测试要锁住的：
 * - 直接取值（[AutoEqParser.curveToBands]）：每段取目标曲线在该频点的值，
 *   相邻段带宽重叠时会**叠加过量**；
 * - 最小二乘（[AutoEqParser.fitBandsToCurve]）：求解使合成响应最贴目标的增益。
 */
class AutoEqFitTest {

    private val freqs = listOf(
        31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
    )

    /** 目标曲线：1000 Hz 处 +6 dB、Q=2 的单个峰值（用同一套 biquad 数学生成） */
    private fun bumpCurve(): List<Pair<Float, Float>> =
        (0..120).map { i ->
            val t = i / 120.0
            val f = (20.0 * Math.pow(1000.0, t)).toFloat()   // 20 Hz → 20 kHz
            f to EqMath.responseAt(listOf(1000f), listOf(6f), f, 2f)
        }

    private fun rmsError(bands: List<AutoEqParser.Band>, curve: List<Pair<Float, Float>>): Double {
        val n = 96
        var acc = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / (n - 1)
            val f = (20.0 * Math.pow(1000.0, t)).toFloat()
            var db = 0f
            for (b in bands) {
                db += EqMath.responseAt(
                    freqs = listOf(b.freq),
                    gains = listOf(b.gain),
                    f = f,
                    q = b.q,
                    qs = listOf(b.q),
                    types = listOf(b.type),
                )
            }
            val d = db - EqMath.interpLog(curve, f)
            acc += (d * d).toDouble()
        }
        return Math.sqrt(acc / n)
    }

    @Test
    fun leastSquaresBeatsPlainSampling() {
        val target = bumpCurve()
        val fitted = AutoEqParser.fitBandsToCurve(
            curve = target,
            freqs = freqs,
            qs = List(freqs.size) { 1.41f },
            types = List(freqs.size) { 0 },
        )
        val plain = AutoEqParser.curveToBands(target, freqs)

        assertTrue("拟合应返回与段数相同的条数", fitted.size == freqs.size)
        assertTrue("段位置/类型应保持调用方给的",
            fitted.map { it.freq } == freqs)
        assertTrue("增益必须落在 ±12 dB 内", fitted.all { it.gain in -12f..12f })

        val eFit = rmsError(fitted, target)
        val ePlain = rmsError(plain, target)
        println("RMS 误差：拟合=%.2f dB, 直接取值=%.2f dB".format(eFit, ePlain))
        assertTrue(
            "拟合误差应优于直接取值（拟合 %.2f vs 直接 %.2f）".format(eFit, ePlain),
            eFit <= ePlain,
        )
    }

    @Test
    fun flatCurveFitsToNearZeroGains() {
        val flat = (0..60).map { i ->
            val f = (20.0 * Math.pow(1000.0, i / 60.0)).toFloat()
            f to 0f
        }
        val fitted = AutoEqParser.fitBandsToCurve(flat, freqs)
        assertTrue("平直目标就该拟合出接近 0 的增益", fitted.all { kotlin.math.abs(it.gain) < 0.5f })
    }
}
