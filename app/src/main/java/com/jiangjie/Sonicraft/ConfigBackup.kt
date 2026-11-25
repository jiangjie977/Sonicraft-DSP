/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 全量配置的导出/导入（纯函数，可单测）。
 */
package com.jiangjie.Sonicraft

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 `SharedPreferences` 快照编码成自描述 JSON，并解析回来。
 *
 * 为什么需要「类型标记」：SharedPreferences 里同名键的类型是运行时才知道的，
 * 而 JSON 的数字无法区分 Float/Int/Long——直接 `put` 会写错类型（例如把
 * `0.8f` 变成 `Double`），读取端 `getFloat` 会抛 `ClassCastException`。
 * 因此每个值都编码成 `{"t":"f","v":0.8}` 形式。
 *
 * 为什么跳过 `privacy_accepted`：导入配置不应替用户"同意隐私政策"。
 */
object ConfigBackup {

    /** 当前格式版本；未来字段变更时据此做兼容迁移 */
    const val FORMAT_VERSION = 1

    /** 导出/导入都跳过的键（属于同意状态，不允许被配置覆盖） */
    private val SKIP_KEYS = setOf("privacy_accepted")

    fun shouldSkip(key: String): Boolean = key in SKIP_KEYS

    /**
     * 编码为 JSON 文本。
     *
     * @param all      `SharedPreferences.all` 的快照
     * @param appLabel 写入元信息的应用标识（如 "0.4.2 (11)"）
     * @param nowIso   导出时间（调用方传入，便于测试）
     */
    fun encode(all: Map<String, *>, appLabel: String, nowIso: String): String {
        val values = JSONObject()
        for ((key, value) in all) {
            if (shouldSkip(key)) continue
            val encoded = encodeValue(value) ?: continue
            values.put(key, encoded)
        }
        return JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("app", appLabel)
            put("exportedAt", nowIso)
            put("count", values.length())
            put("values", values)
        }.toString(2)
    }

    /**
     * 解析 JSON 文本。
     *
     * @return key → 值（Boolean/Float/Int/Long/String/Set<String>）；格式非法或版本不支持时返回 null
     */
    fun decode(json: String): Map<String, Any?>? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("version", -1) != FORMAT_VERSION) return null
        val values = root.optJSONObject("values") ?: return null
        val out = LinkedHashMap<String, Any?>()
        for (key in values.keys()) {
            if (shouldSkip(key)) continue
            val ob = values.optJSONObject(key) ?: continue
            val decoded = decodeValue(ob) ?: continue
            out[key] = decoded
        }
        out
    }.getOrNull()

    /** 供 UI 显示：解析出的条目数（不做事就先报告格式错误） */
    fun describeError(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "不是合法的 JSON 文件"
        val v = root.optInt("version", -1)
        if (v < 0) return "缺少格式版本，可能不是 Sonicraft 配置文件"
        if (v != FORMAT_VERSION) return "格式版本 $v 与本应用支持的 $FORMAT_VERSION 不一致"
        if (root.optJSONObject("values") == null) return "缺少 values 字段"
        return null
    }

    // ---------------- 内部 ----------------

    private fun encodeValue(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> {
            val arr = JSONArray()
            value.forEach { arr.put(it as? String ?: return null) }
            JSONObject().put("t", "set").put("v", arr)
        }
        else -> null
    }

    private fun decodeValue(ob: JSONObject): Any? = when (ob.optString("t")) {
        "b" -> ob.optBoolean("v")
        "f" -> ob.optDouble("v").toFloat()
        "i" -> ob.optInt("v")
        "l" -> ob.optLong("v")
        "s" -> ob.optString("v")
        "set" -> {
            val arr = ob.optJSONArray("v") ?: return null
            (0 until arr.length()).map { arr.optString(it) }.toSet()
        }
        else -> null
    }
}
