/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * EQ 参数与用户预设的持久化编解码（从 MainViewModel 抽出）。
 *
 * 分两层：
 *  - `decode*` / `encode*`：**纯函数**（只吃字符串/列表），可直接单测；
 *  - `read*` / `persist*`：薄包装，负责与 SharedPreferences 交互。
 */
package com.jiangjie.Sonicraft

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object EqConfigCodec {

    private const val KEY_BANDS = "eq_bands_"
    private const val KEY_QS = "eq_q_"
    private const val KEY_TYPES = "eq_type_"
    private const val KEY_FREQS = "eq_freq_"
    private const val KEY_USER_PRESETS = "eq_user_presets"

    // ================= 纯编解码（可单测） =================

    /** 段增益：缺省/损坏/长度不足一律降级为 0，不抛异常。 */
    fun decodeBands(raw: String?, mode: Int): List<Float> {
        val stored = parseFloats(raw) ?: return List(mode) { 0f }
        return List(mode) { i -> stored.getOrElse(i) { 0f } }
    }

    /** 段 Q：额外钳制到 0.3~8（历史脏值可能越界）。 */
    fun decodeQs(raw: String?, mode: Int): List<Float> {
        val stored = parseFloats(raw) ?: return List(mode) { 1f }
        return List(mode) { i -> stored.getOrElse(i) { 1f }.coerceIn(0.3f, 8f) }
    }

    /** 段类型：0=PK / 1=LSC / 2=HSC，越界钳制。 */
    fun decodeTypes(raw: String?, mode: Int): List<Int> {
        val stored = parseInts(raw) ?: return List(mode) { 0 }
        return List(mode) { i -> stored.getOrElse(i) { 0 }.coerceIn(0, 2) }
    }

    /** 段频率：缺省回落到该模式的默认频段表，并钳到 20~20000 Hz。 */
    fun decodeFreqs(raw: String?, mode: Int): List<Float> {
        val table = EqMath.freqTable(mode)
        val stored = parseFloats(raw) ?: return table
        return List(mode) { i ->
            stored.getOrElse(i) { table.getOrElse(i) { 1000f } }.coerceIn(20f, 20000f)
        }
    }

    fun encodeBands(gains: List<Float>): String = csv(gains, "%.2f")

    fun encodeQs(qs: List<Float>): String = csv(qs, "%.2f")

    fun encodeTypes(types: List<Int>): String = types.joinToString(",")

    fun encodeFreqs(freqs: List<Float>): String = csv(freqs, "%.1f")

    /** 用户预设 JSON：`types` 为后加字段，缺失时按空列表（全 PK）兼容。 */
    fun decodeUserPresets(json: String?): List<EqUserPreset> = runCatching {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map { idx ->
            val ob = arr.getJSONObject(idx)
            EqUserPreset(
                ob.getString("name"),
                ob.getInt("mode"),
                floatList(ob, "gains"),
                floatList(ob, "qs"),
                floatList(ob, "freqs"),
                if (ob.has("types")) {
                    val ta = ob.getJSONArray("types")
                    (0 until ta.length()).map { ta.getInt(it).coerceIn(0, 2) }
                } else {
                    emptyList()
                },
            )
        }
    }.getOrDefault(emptyList())

    fun encodeUserPresets(list: List<EqUserPreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            val ob = JSONObject()
            ob.put("name", p.name)
            ob.put("mode", p.mode)
            listOf("gains" to p.gains, "qs" to p.qs, "freqs" to p.freqs).forEach { (k, vals) ->
                val a = JSONArray()
                vals.forEach { a.put(it.toDouble()) }
                ob.put(k, a)
            }
            if (p.types.isNotEmpty()) ob.put("types", JSONArray(p.types))
            arr.put(ob)
        }
        return arr.toString()
    }

    // ================= SharedPreferences 包装 =================

    fun readBands(prefs: SharedPreferences, mode: Int): List<Float> =
        decodeBands(prefs.getString(KEY_BANDS + mode, null), mode)

    fun persistBands(prefs: SharedPreferences, mode: Int, gains: List<Float>) {
        prefs.edit().putString(KEY_BANDS + mode, encodeBands(gains)).apply()
    }

    fun readQs(prefs: SharedPreferences, mode: Int): List<Float> =
        decodeQs(prefs.getString(KEY_QS + mode, null), mode)

    fun persistQs(prefs: SharedPreferences, mode: Int, qs: List<Float>) {
        prefs.edit().putString(KEY_QS + mode, encodeQs(qs)).apply()
    }

    fun readTypes(prefs: SharedPreferences, mode: Int): List<Int> =
        decodeTypes(prefs.getString(KEY_TYPES + mode, null), mode)

    fun persistTypes(prefs: SharedPreferences, mode: Int, types: List<Int>) {
        prefs.edit().putString(KEY_TYPES + mode, encodeTypes(types)).apply()
    }

    fun readFreqs(prefs: SharedPreferences, mode: Int): List<Float> =
        decodeFreqs(prefs.getString(KEY_FREQS + mode, null), mode)

    fun persistFreqs(prefs: SharedPreferences, mode: Int, freqs: List<Float>) {
        prefs.edit().putString(KEY_FREQS + mode, encodeFreqs(freqs)).apply()
    }

    fun readUserPresets(prefs: SharedPreferences): List<EqUserPreset> =
        decodeUserPresets(prefs.getString(KEY_USER_PRESETS, "[]"))

    fun persistUserPresets(prefs: SharedPreferences, list: List<EqUserPreset>) {
        prefs.edit().putString(KEY_USER_PRESETS, encodeUserPresets(list)).apply()
    }

    // ================= 内部 =================

    private fun parseFloats(raw: String?): List<Float>? = runCatching {
        raw?.split(",")?.map { it.trim().toFloat() }
    }.getOrNull()

    private fun parseInts(raw: String?): List<Int>? = runCatching {
        raw?.split(",")?.map { it.trim().toInt() }
    }.getOrNull()

    private fun csv(values: List<Float>, fmt: String): String =
        values.joinToString(",") { String.format(java.util.Locale.US, fmt, it) }

    private fun floatList(ob: JSONObject, key: String): List<Float> {
        val a = ob.getJSONArray(key)
        return (0 until a.length()).map { a.getDouble(it).toFloat() }
    }
}
