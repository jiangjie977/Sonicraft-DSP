package com.jiangjie.Sonicraft

/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

/*
 * M9 多段 EQ 数学工具：频点表 / 频响曲线合成 / 预设控制点插值 / 内置预设。
 * 与 native ParametricEqNode 的模式频点表保持一致。
 */
/** M9 批3 自定义预设（完整快照：模式+增益+Q+频率） */
data class EqUserPreset(
    val name: String,
    val mode: Int,
    val gains: List<Float>,
    val qs: List<Float>,
    val freqs: List<Float>,
    val types: List<Int> = emptyList(),  // 段类型 0=PK 1=LSC 2=HSC；空=旧预设全 PK（兼容）
)

object EqMath {

    val FREQS10 = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val FREQS15 = listOf(
        25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f,
        2500f, 4000f, 6300f, 10000f, 16000f,
    )
    val FREQS31 = listOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f,
        250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f,
        2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
    )

    /**
     * 段数 → 默认频点表：10/15/31 用标准档位表；其余 1~31 任意段数按 20~20k
     * 对数均分生成（末段=20k），与 native setBandCount 同口径（wecho 对齐）。
     */
    fun freqTable(mode: Int): List<Float> = when (mode) {
        10 -> FREQS10
        15 -> FREQS15
        31 -> FREQS31
        else -> {
            val n = mode.coerceIn(1, 31)
            if (n == 1) {
                listOf(20000f)
            } else {
                val logMin = kotlin.math.log10(20.0)
                val logMax = kotlin.math.log10(20000.0)
                List(n) { i ->
                    if (i == n - 1) 20000f
                    else Math.pow(10.0, logMin + (logMax - logMin) * i / (n - 1)).toFloat()
                }
            }
        }
    }

    fun label(f: Float): String = if (f >= 1000f) "%.1fk".format(f / 1000f) else if (f % 1f != 0f) "%.1f".format(f) else "%.0f".format(f)

    fun responseDb(
        freqs: List<Float>,
        gains: List<Float>,
        q: Float = 1f,
        sampleRate: Float = 48000f,
        points: Int = 128,
    ): List<Float> {
        val out = ArrayList<Float>(points)
        val logMin = kotlin.math.ln(20.0) / kotlin.math.ln(10.0)
        val logMax = kotlin.math.ln(20000.0) / kotlin.math.ln(10.0)
        for (p in 0 until points) {
            val f = Math.pow(10.0, logMin + (logMax - logMin) * p / (points - 1))
            var db = 0.0
            for ((i, fc) in freqs.withIndex()) {
                val g = gains.getOrElse(i) { 0f }
                if (g == 0f) continue
                val A = Math.pow(10.0, g / 40.0)
                val w0 = 2 * Math.PI * fc / sampleRate
                val alpha = kotlin.math.sin(w0) / (2 * q)
                val b0 = 1 + alpha * A
                val b1 = -2 * kotlin.math.cos(w0)
                val b2 = 1 - alpha * A
                val a0 = 1 + alpha / A
                val a1 = -2 * kotlin.math.cos(w0)
                val a2 = 1 - alpha / A
                val w = 2 * Math.PI * f / sampleRate
                val cw = kotlin.math.cos(w)
                val sw = kotlin.math.sin(w)
                val c2w = kotlin.math.cos(2 * w)
                val s2w = kotlin.math.sin(2 * w)
                val nr = b0 + b1 * cw + b2 * c2w
                val ni = b1 * sw + b2 * s2w
                val dr = a0 + a1 * cw + a2 * c2w
                val di = a1 * sw + a2 * s2w
                val num = nr * nr + ni * ni
                val den = dr * dr + di * di
                db += 10 * kotlin.math.ln(if (den > 1e-12) num / den else 1.0) / kotlin.math.ln(10.0)
            }
            out.add(db.toFloat())
        }
        return out
    }

    fun interpLog(points: List<Pair<Float, Float>>, f: Float): Float {
        if (points.isEmpty()) return 0f
        val sorted = points.sortedBy { it.first }
        if (f <= sorted.first().first) return sorted.first().second
        if (f >= sorted.last().first) return sorted.last().second
        for (i in 0 until sorted.size - 1) {
            val (f0, g0) = sorted[i]
            val (f1, g1) = sorted[i + 1]
            if (f >= f0 && f <= f1) {
                val t = (Math.log10(f.toDouble()) - Math.log10(f0.toDouble())) /
                    (Math.log10(f1.toDouble()) - Math.log10(f0.toDouble()))
                return (g0 + (g1 - g0) * t).toFloat()
            }
        }
        return 0f
    }


    /** 单频点响应（dB）：面板曲线按 band 索引空间采样用；types 支持段类型（0=PK 1=LSC 2=HSC） */
    fun responseAt(
        freqs: List<Float>,
        gains: List<Float>,
        f: Float,
        q: Float = 1f,
        sampleRate: Float = 48000f,
        qs: List<Float>? = null,
        types: List<Int>? = null,
    ): Float {
        var db = 0.0
        for ((i, fc) in freqs.withIndex()) {
            val g = gains.getOrElse(i) { 0f }
            if (g == 0f) continue
            val bandQ = qs?.getOrElse(i) { q }?.takeIf { it > 0.05f } ?: q
            val type = types?.getOrElse(i) { 0 } ?: 0
            val A = Math.pow(10.0, g / 40.0)
            val w0 = 2 * Math.PI * fc / sampleRate
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            if (type == 0) {
                val alpha = kotlin.math.sin(w0) / (2 * bandQ)
                b0 = 1 + alpha * A; b1 = -2 * kotlin.math.cos(w0); b2 = 1 - alpha * A
                a0 = 1 + alpha / A; a1 = -2 * kotlin.math.cos(w0); a2 = 1 - alpha / A
            } else {
                // RBJ shelf，S=1（与 native 口径一致）
                val sqrtA = kotlin.math.sqrt(A)
                val alpha = kotlin.math.sin(w0) / 2 * kotlin.math.sqrt(2.0)
                val cw = kotlin.math.cos(w0)
                if (type == 1) {
                    b0 = A * ((A + 1) - (A - 1) * cw + 2 * sqrtA * alpha)
                    b1 = 2 * A * ((A - 1) - (A + 1) * cw)
                    b2 = A * ((A + 1) - (A - 1) * cw - 2 * sqrtA * alpha)
                    a0 = (A + 1) + (A - 1) * cw + 2 * sqrtA * alpha
                    a1 = -2 * ((A - 1) + (A + 1) * cw)
                    a2 = (A + 1) + (A - 1) * cw - 2 * sqrtA * alpha
                } else {
                    b0 = A * ((A + 1) + (A - 1) * cw + 2 * sqrtA * alpha)
                    b1 = -2 * A * ((A - 1) + (A + 1) * cw)
                    b2 = A * ((A + 1) + (A - 1) * cw - 2 * sqrtA * alpha)
                    a0 = (A + 1) - (A - 1) * cw + 2 * sqrtA * alpha
                    a1 = 2 * ((A - 1) - (A + 1) * cw)
                    a2 = (A + 1) - (A - 1) * cw - 2 * sqrtA * alpha
                }
            }
            val w = 2 * Math.PI * f / sampleRate
            val cw = kotlin.math.cos(w)
            val sw = kotlin.math.sin(w)
            val c2w = kotlin.math.cos(2 * w)
            val s2w = kotlin.math.sin(2 * w)
            val nr = b0 + b1 * cw + b2 * c2w
            val ni = b1 * sw + b2 * s2w
            val dr = a0 + a1 * cw + a2 * c2w
            val di = a1 * sw + a2 * s2w
            val num = nr * nr + ni * ni
            val den = dr * dr + di * di
            db += 10 * kotlin.math.ln(if (den > 1e-12) num / den else 1.0) / kotlin.math.ln(10.0)
        }
        return db.toFloat()
    }

    /** band 索引空间 s（[0,n-1]）→ 频率（相邻 band 对数插值） */
    fun freqAtS(freqs: List<Float>, s: Float): Float {
        val n = freqs.size
        val i = s.toInt().coerceIn(0, n - 2)
        val t = (s - i).coerceIn(0f, 1f)
        val lf0 = kotlin.math.ln(freqs[i].toDouble())
        val lf1 = kotlin.math.ln(freqs[i + 1].toDouble())
        return kotlin.math.exp(lf0 + (lf1 - lf0) * t).toFloat()
    }

    val BUILTIN: List<Pair<String, List<Pair<Float, Float>>>> = listOf(
        "平直" to emptyList(),
        "低音增强" to listOf(50f to 7f, 120f to 5f, 300f to 1.5f),
        "低音减弱" to listOf(50f to -6f, 120f to -4f),
        "流行" to listOf(80f to 3f, 250f to 1f, 3000f to 1.5f, 8000f to 2f),
        "古典" to listOf(60f to 3f, 4000f to 2f, 10000f to 2f),
        "人声" to listOf(200f to -2.5f, 500f to 1.5f, 3000f to 3f, 8000f to -1f),
        "摇滚" to listOf(70f to 4.5f, 250f to -1f, 3000f to 3f, 6000f to 2f),
        "电子" to listOf(60f to 5f, 1000f to -1.5f, 5000f to 3f, 10000f to 2.5f),
        "爵士" to listOf(100f to 3f, 1500f to 1.5f, 8000f to 1f),
        "明亮" to listOf(3000f to 3.5f, 8000f to 4f, 12000f to 2f),
    )
}

/**
 * AutoEq 耳机修正：标准 ParametricEQ.txt 解析（AutoEq 项目导出格式）。
 * 行样例：`Preamp: -6.8 dB`、`Filter 3: ON LSC Fc 105 Hz Gain 5.0 dB Q 0.70`
 * 段类型 PK/LSC/HSC（LSQ/HSQ/PKQ 为等价旧写法）；LP/HP 类（无 Gain）不支持，跳过。
 */
object AutoEqParser {
    data class Band(val freq: Float, val gain: Float, val q: Float, val type: Int) // type: 0=PK 1=LSC 2=HSC
    data class Result(val preampDb: Float, val bands: List<Band>)

    private val filterRe = Regex(
        "Filter\\s+\\d+:\\s*ON\\s+(PK|LSC|HSC|LSQ|HSQ|PKQ)\\s+Fc\\s+([\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB\\s+Q\\s+([\\d.]+)",
        RegexOption.IGNORE_CASE,
    )
    private val preampRe = Regex("Preamp:\\s*(-?[\\d.]+)\\s*dB", RegexOption.IGNORE_CASE)

    /** 无有效滤波器行时返回 null（调用方提示解析失败） */
    fun parse(text: String): Result? {
        // Preamp 独立预扫描取最后一次出现（若与滤波器行同循环，段数到 31 break 会漏掉文末 Preamp）
        var preamp = 0f
        preampRe.findAll(text).lastOrNull()?.let { m -> preamp = m.groupValues[1].toFloatOrNull() ?: 0f }
        val bands = ArrayList<Band>(16)
        for (raw in text.lineSequence()) {
            val m = filterRe.find(raw) ?: continue
            val type = when (m.groupValues[1].uppercase()) {
                "LSC", "LSQ" -> 1
                "HSC", "HSQ" -> 2
                else -> 0
            }
            val f = m.groupValues[2].toFloatOrNull() ?: continue
            val g = m.groupValues[3].toFloatOrNull() ?: continue
            val q = m.groupValues[4].toFloatOrNull() ?: continue
            if (bands.size >= 31) break
            bands.add(Band(f.coerceIn(20f, 20000f), g.coerceIn(-12f, 12f), q.coerceIn(0.3f, 8f), type))
        }
        return if (bands.isEmpty()) null else Result(preamp, bands)
    }
}
