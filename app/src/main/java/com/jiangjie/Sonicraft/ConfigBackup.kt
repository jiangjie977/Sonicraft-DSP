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
 *
 * ## 向后兼容约定（增删改效果器时必须遵守）
 *
 * - **新增键**：无需任何处理。老文件里没有该键 → 导入后保持当前值（默认），不会报错。
 * - **删除键**：老文件里的键变成「孤儿」，写回 prefs 后无人读取，无害。
 * - **重命名键**：**必须**在 [KEY_MIGRATIONS] 里加一条映射，否则老备份里的值会被
 *   写到旧键上、新键退回默认 → 用户设置静默丢失。
 * - **格式变更**：递增 [FORMAT_VERSION]；低版本文件仍可导入（见 [decodeSnapshot]），
 *   只有**高于**当前版本的文件才拒绝。
 */
object ConfigBackup {

    /** 当前格式版本；结构变更时递增（低版本文件依然可读） */
    const val FORMAT_VERSION = 1

    /** 导出/导入都跳过的键（属于同意状态，不允许被配置覆盖） */
    private val SKIP_KEYS = setOf("privacy_accepted", "privacy_accepted_version")

    /**
     * 历史键名 → 当前键名。导入时先重命名再写入。
     *
     * **重命名任何 prefs 键时，必须在这里补一条**，否则用户的旧备份会静默失效。
     * 例：`"vs_center_gain" to "vs_center_db"`。
     */
    private val KEY_MIGRATIONS: Map<String, String> = emptyMap()

    /**
     * 「用户痕迹」键：只有用户真的动过参数，这些键才会被写入 prefs。
     *
     * 用途：判断当前配置是不是**全新空档**（卸载重装、清数据后）。
     * 若为空且有自动备份，说明配置丢了，可以提示恢复。
     *
     * 不能用 `privacy_accepted` 之类判断——那些是 App 自己写的，新装也会有。
     */
    private val USER_TRACE_KEYS = listOf(
        "output_gain", "input_gain", "eq_bands_10", "eq_freq_10",
        "bass_enhance_amount", "bass_enhance_mode", "limiter_ceiling",
        "multiband_param_0", "compressor_ratio", "stereo_width",
    )

    fun shouldSkip(key: String): Boolean = key in SKIP_KEYS

    /** 当前 prefs 是否带有用户自定义痕迹（false = 全新空档，可能是配置丢失） */
    fun hasUserTrace(all: Map<String, *>): Boolean = USER_TRACE_KEYS.any { all.containsKey(it) }

    /** 解析结果：配置值 + 元信息 + 备份时已有的脉冲文件名清单 */
    data class Snapshot(
        val version: Int,
        val appLabel: String?,
        val exportedAt: String?,
        val values: Map<String, Any?>,
        /** 备份时 `pulse/` 目录里的文件名（只存清单，不含音频） */
        val irFiles: List<String>,
        /** 解析时被忽略的条目数（未知类型 / 空值） */
        val ignored: Int,
    )

    /** 导入结果：用于给用户一份可核对的明细，而不是只报一个总数 */
    data class Outcome(
        val written: Int,
        /** 经过「旧键名 → 新键名」迁移的数量 */
        val migrated: Int,
        /** 文件里无法识别、被忽略的条目数 */
        val ignored: Int,
        /** 备份里有、但本机 `pulse/` 目录缺失的脉冲文件名 */
        val missingIrFiles: List<String>,
    )

    /**
     * 编码为 JSON 文本。
     *
     * @param all      `SharedPreferences.all` 的快照
     * @param appLabel 写入元信息的应用标识（如 "0.4.2 (11)"）
     * @param nowIso   导出时间（调用方传入，便于测试）
     * @param irFiles  当前脉冲库文件名清单（只作记录，不含音频数据）
     */
    fun encode(
        all: Map<String, *>,
        appLabel: String,
        nowIso: String,
        irFiles: List<String> = emptyList(),
    ): String {
        val values = JSONObject()
        for ((key, value) in all) {
            if (shouldSkip(key)) continue
            val encoded = encodeValue(value) ?: continue
            values.put(key, encoded)
        }
        val irArray = JSONArray()
        irFiles.sorted().forEach { irArray.put(it) }
        return JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("app", appLabel)
            put("exportedAt", nowIso)
            put("count", values.length())
            put("irFiles", irArray)
            put("values", values)
        }.toString(2)
    }

    /**
     * 解析 JSON 文本。
     *
     * **前向兼容**：`version <= FORMAT_VERSION` 都接受（老文件照常读）；
     * 只有**高于**当前版本的文件才拒绝（未来版本的字段本应用无法正确解释）。
     *
     * @return 解析结果；格式非法或版本过高时返回 null
     */
    fun decodeSnapshot(json: String): Snapshot? = runCatching {
        val root = JSONObject(json)
        val v = root.optInt("version", -1)
        if (v < 0 || v > FORMAT_VERSION) return null
        val values = root.optJSONObject("values") ?: return null

        val out = LinkedHashMap<String, Any?>()
        var ignored = 0
        for (key in values.keys()) {
            if (shouldSkip(key)) continue
            val ob = values.optJSONObject(key)
            if (ob == null) {
                ignored++
                continue
            }
            val decoded = decodeValue(ob)
            if (decoded == null) {
                ignored++
                continue
            }
            out[key] = decoded
        }

        val irFiles = buildList {
            val arr = root.optJSONArray("irFiles") ?: return@buildList
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }

        Snapshot(
            version = v,
            appLabel = root.optString("app").takeIf { it.isNotEmpty() },
            exportedAt = root.optString("exportedAt").takeIf { it.isNotEmpty() },
            values = out,
            irFiles = irFiles,
            ignored = ignored,
        )
    }.getOrNull()

    /** 只要配置值时的便捷入口（测试与简单调用用） */
    fun decode(json: String): Map<String, Any?>? = decodeSnapshot(json)?.values

    /**
     * 应用键名迁移。
     *
     * 目标键已存在时**保留文件里的值覆盖**（导入语义就是"以文件为准"），
     * 因此同一批里出现「新键 + 旧键」时，旧键会覆盖新键——这与逐键写入顺序一致，可接受。
     *
     * @return 迁移后的映射 + 被重命名的条目数
     */
    fun applyMigrations(
        values: Map<String, Any?>,
        table: Map<String, String> = KEY_MIGRATIONS,
    ): Pair<Map<String, Any?>, Int> {
        if (table.isEmpty()) return values to 0
        var migrated = 0
        val out = LinkedHashMap<String, Any?>(values.size)
        for ((key, value) in values) {
            val target = table[key]
            if (target != null) migrated++
            out[target ?: key] = value
        }
        return out to migrated
    }

    /** 供 UI 显示：先报告格式错误，再谈内容 */
    fun describeError(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "不是合法的 JSON 文件"
        val v = root.optInt("version", -1)
        if (v < 0) return "缺少格式版本，可能不是 Sonicraft 配置文件"
        if (v > FORMAT_VERSION) {
            return "该文件由更新版本的应用导出（格式 $v > $FORMAT_VERSION），请升级应用后再导入"
        }
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
