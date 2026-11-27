package com.jiangjie.Sonicraft

/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

/*
 * 多段 EQ 数学工具：频点表 / 频响曲线合成 / 预设控制点插值 / 内置预设。
 * 与 native ParametricEqNode 的模式频点表保持一致。
 */
/** 批3 自定义预设（完整快照：模式+增益+Q+频率） */
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
     * 对数均分生成（末段=20k），与 native setBandCount 同口径（早期定制版 对齐）。
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

    /**
     * [skipped] 统计被丢弃的滤波器行（LP/HP/None/OFF）—— 导入后要告诉用户"少了 N 段"，
     * 否则用户以为导入完整，实际低切/高切被静默吞掉。
     */
    data class Result(
        val preampDb: Float,
        val bands: List<Band>,
        val skipped: Int = 0,
        /** 超范围被钳位的段数（频率 20~20k / 增益 ±12 dB / Q 0.3~8） */
        val clamped: Int = 0,
    )

    // 兼容写法：
    //   · ON 可省略（部分工具导出 `Filter 1: PK Fc 100 Hz Gain 3 dB Q 1.4`）
    //   · 类型含 LS/HS（老写法，等价 LSC/HSC）与 PEQ（等价 PK）
    //   · Q 可省略（shelf 用斜率 S 的文件）→ 缺省 0.707
    private val filterRe = Regex(
        "Filter\\s+\\d+:\\s*(?:ON\\s+)?(PK|PEQ|LSC|HSC|LS|HS|LSQ|HSQ|PKQ)\\s+" +
            "Fc\\s+([\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB" +
            "(?:\\s+(Q|S)\\s+([\\d.]+))?",
        RegexOption.IGNORE_CASE,
    )
    // 被跳过的行（有 Filter N 但没有可用的 PK/LS/HS + Gain）
    private val anyFilterRe = Regex("Filter\\s+\\d+:", RegexOption.IGNORE_CASE)
    private val preampRe = Regex("Preamp:\\s*(-?[\\d.]+)\\s*dB", RegexOption.IGNORE_CASE)

    /** 无有效滤波器行时返回 null（调用方提示解析失败） */
    fun parse(text: String): Result? {
        // Preamp 独立预扫描取最后一次出现（若与滤波器行同循环，段数到 31 break 会漏掉文末 Preamp）
        var preamp = 0f
        preampRe.findAll(text).lastOrNull()?.let { m -> preamp = m.groupValues[1].toFloatOrNull() ?: 0f }
        val bands = ArrayList<Band>(16)
        var skipped = 0
        var clamped = 0
        for (raw in text.lineSequence()) {
            val m = filterRe.find(raw)
            if (m == null) {
                if (anyFilterRe.containsMatchIn(raw)) skipped++
                continue
            }
            val type = when (m.groupValues[1].uppercase()) {
                "LSC", "LSQ", "LS" -> 1
                "HSC", "HSQ", "HS" -> 2
                else -> 0
            }
            val f = m.groupValues[2].toFloatOrNull() ?: continue
            val g = m.groupValues[3].toFloatOrNull() ?: continue
            // group4 = 标签(Q/S)，group5 = 数值。
            // shelf 的斜率 S 与 Q 不是同一量纲，不做换算，按 Butterworth 缺省 0.707 处理；
            // 完全没有 Q/S 时同样取 0.707。
            val qLabel = m.groupValues[4].uppercase()
            val qRaw = m.groupValues[5].toFloatOrNull()
            val q = if (qLabel == "Q" && qRaw != null) qRaw else 0.707f
            if (bands.size >= 31) { skipped++; continue }
            val fc = f.coerceIn(20f, 20000f)
            val gc = g.coerceIn(-12f, 12f)
            val qc = q.coerceIn(0.3f, 8f)
            if (fc != f || gc != g || qc != q) clamped++
            bands.add(Band(fc, gc, qc, type))
        }
        return if (bands.isEmpty()) null else Result(preamp, bands, skipped, clamped)
    }

    // ---------------------------------------------------------------- 曲线类格式
    // AutoEq 同一副耳机会导出三种文件，除了参数化（Filter 行）还有：
    //   · `… GraphicEQ.txt`：单行 `GraphicEQ: 20 -0.2; 21 -0.2; …`（约 120 个频点）
    //   · `… .csv`：AutoEq 结果表，含 `equalization` 列（已算好的补偿曲线）
    // 这两类都是"连续曲线"，无法直接变成滤波器，需要按目标段频率取值（定 Q 近似）。

    private val graphicRe = Regex("GraphicEQ:\\s*(.+)", RegexOption.IGNORE_CASE)
    private val pointRe = Regex("([\\d.]+)\\s+(-?[\\d.]+)")

    /** 解析 `GraphicEQ:` 行 → 升序 (频率, 增益) 点列；无该行返回 null */
    fun parseGraphic(text: String): List<Pair<Float, Float>>? {
        val m = graphicRe.find(text) ?: return null
        val pts = pointRe.findAll(m.groupValues[1])
            .mapNotNull { mm ->
                val f = mm.groupValues[1].toFloatOrNull()
                val g = mm.groupValues[2].toFloatOrNull()
                if (f != null && g != null && f > 0f) f to g else null
            }
            .toList()
        return if (pts.size >= 5) pts.sortedBy { it.first } else null
    }

    /**
     * 解析 AutoEq 结果 CSV → 取补偿曲线。
     *
     * 列优先级：`equalization`（AutoEq 已算好的补偿）> `error_smoothed` 取反 > 最后一列。
     * 首行是表头（含 `frequency`），非表头/非数字行自动跳过。
     */
    fun parseCsvCurve(text: String): List<Pair<Float, Float>>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 6) return null
        val header = lines.firstOrNull { it.lowercase().contains("frequency") } ?: return null
        val cols = header.split(',').map { it.trim().lowercase() }
        val freqIdx = cols.indexOfFirst { it.contains("frequenc") }
        if (freqIdx < 0) return null
        var valIdx = cols.indexOf("equalization")
        var negate = false
        if (valIdx < 0) {
            valIdx = cols.indexOf("error_smoothed")
            negate = valIdx >= 0
        }
        if (valIdx < 0) valIdx = cols.lastIndex

        val pts = ArrayList<Pair<Float, Float>>(lines.size)
        var headerSeen = false
        for (ln in lines) {
            if (!headerSeen) {
                if (ln == header) headerSeen = true
                continue
            }
            val cells = ln.split(',')
            if (cells.size <= maxOf(freqIdx, valIdx)) continue
            val f = cells[freqIdx].trim().toFloatOrNull() ?: continue
            val v = cells[valIdx].trim().toFloatOrNull() ?: continue
            if (f <= 0f) continue
            pts.add(f to if (negate) -v else v)
        }
        return if (pts.size >= 5) pts.sortedBy { it.first } else null
    }

    /**
     * 把连续曲线映射成"定 Q 峰值段"（用于 GraphicEQ / CSV）。
     *
     * 每条目标频率上取曲线值作为该段增益（对数插值），Q 用 1.41（与 AutoEq FixedBandEQ 一致）。
     * 这不是最优拟合，但**保形、可解释**：曲线在哪抬就抬、在哪削就削。
     */
    fun curveToBands(
        curve: List<Pair<Float, Float>>,
        freqs: List<Float>,
        q: Float = 1.41f,
    ): List<Band> = freqs.map { f ->
        val raw = EqMath.interpLog(curve, f)
        Band(
            freq = f.coerceIn(20f, 20000f),
            gain = raw.coerceIn(-12f, 12f),
            q = q,
            type = 0,
        )
    }

    /**
     * **最小二乘拟合**：给定目标曲线与各段（频率/Q/类型），求解一组增益，
     * 使「各段串联后的合成响应」在 dB 域最贴近目标曲线。
     *
     * 为什么不用 [curveToBands] 的"直接取值"：各段带宽互相重叠，直接取目标值会在
     * 相邻段之间**叠加过量**（一片 +3 的曲线用 10 段各自 +3，合起来常常 +8）。
     * 拟合会把重叠量自动扣掉，曲线形状与目标一致而整体电平不虚高。
     *
     * 实现：高斯-牛顿迭代（Tikhonov 阻尼 λ=0.05 防病态），
     * 每段对自身增益求数值偏导（步长 0.5 dB），解 (AᵀA+λI)δ = Aᵀr 后阻尼更新并钳位 ±12 dB。
     * 频率/Q/类型保持调用方给的（即**段位置不动**，只求增益）。
     */
    fun fitBandsToCurve(
        curve: List<Pair<Float, Float>>,
        freqs: List<Float>,
        qs: List<Float>? = null,
        types: List<Int>? = null,
        iterations: Int = 6,
        samplePoints: Int = 72,
    ): List<Band> {
        if (curve.size < 2 || freqs.isEmpty() || samplePoints < 8) return emptyList()

        val fLo = maxOf(20f, curve.first().first)
        val fHi = minOf(20000f, curve.last().first)
        if (fHi <= fLo) return emptyList()

        val xs = FloatArray(samplePoints)
        val target = FloatArray(samplePoints)
        val ratio = (fHi / fLo).toDouble()
        for (k in 0 until samplePoints) {
            val t = k.toDouble() / (samplePoints - 1)
            val f = (fLo * Math.pow(ratio, t)).toFloat()
            xs[k] = f
            target[k] = EqMath.interpLog(curve, f)
        }

        val n = freqs.size
        val qList = List(n) { qs?.getOrElse(it) { 1.41f } ?: 1.41f }
        val typeList = List(n) { types?.getOrElse(it) { 0 } ?: 0 }
        val gains = FloatArray(n)
        val eps = 0.5f

        fun bandDb(j: Int, g: Float, f: Float): Float = EqMath.responseAt(
            freqs = listOf(freqs[j]),
            gains = listOf(g),
            f = f,
            q = qList[j],
            qs = listOf(qList[j]),
            types = listOf(typeList[j]),
        )

        fun modelAt(g: FloatArray, f: Float): Float {
            var db = 0f
            for (j in 0 until n) db += bandDb(j, g[j], f)
            return db
        }

        repeat(iterations) {
            val base = FloatArray(samplePoints) { modelAt(gains, xs[it]) }
            val resid = FloatArray(samplePoints) { target[it] - base[it] }

            // 每段一列：对自身增益的数值偏导
            val cols = Array(n) { j ->
                val bumped = gains.copyOf()
                bumped[j] += eps
                FloatArray(samplePoints) { k ->
                    (modelAt(bumped, xs[k]) - base[k]) / eps
                }
            }

            val ata = Array(n) { i -> DoubleArray(n) }
            val atb = DoubleArray(n)
            for (i in 0 until n) {
                for (j in 0 until n) {
                    var acc = 0.0
                    for (k in 0 until samplePoints) acc += (cols[i][k] * cols[j][k]).toDouble()
                    ata[i][j] = acc
                }
                var acc = 0.0
                for (k in 0 until samplePoints) acc += (cols[i][k] * resid[k]).toDouble()
                atb[i] = acc
                ata[i][i] += 0.05
            }

            val delta = solveLinear(ata, atb) ?: return@repeat
            var moved = 0f
            for (j in 0 until n) {
                val next = (gains[j] + delta[j].toFloat()).coerceIn(-12f, 12f)
                moved += kotlin.math.abs(next - gains[j])
                gains[j] = next
            }
            if (moved < 0.01f) return@repeat
        }

        return freqs.mapIndexed { i, f ->
            Band(
                freq = f.coerceIn(20f, 20000f),
                gain = if (gains[i].isFinite()) gains[i] else 0f,
                q = qList[i],
                type = typeList[i],
            )
        }
    }

    /** 高斯消元（部分选主元）；奇异时返回 null（调用方保留上一轮增益） */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
            if (kotlin.math.abs(m[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            }
            val d = m[col][col]
            for (j in col..n) m[col][j] /= d
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col]
                if (factor == 0.0) continue
                for (j in col..n) m[r][j] -= factor * m[col][j]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

}
