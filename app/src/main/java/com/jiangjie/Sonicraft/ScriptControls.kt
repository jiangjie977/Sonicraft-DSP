package com.jiangjie.Sonicraft

import org.json.JSONArray

/**
 * 脚本声明的一个控件（toggle / select / button / list）。
 *
 * 声明方式（脚本头部注释，`..., default=` 可选）：
 *   // toggle1: 开启饱和, default=0
 *   // select1: 模式 = 低通|高通, default=1
 *   // button1: 清空延迟线
 *   // list1: 频段 = 低频|中频|高频, default=3
 */
data class ScriptControl(
    val type: String,
    val index: Int,
    val name: String,
    val label: String,
    val options: List<String> = emptyList(),
    val defaultValue: Float = 0f,
    val hasDefault: Boolean = false,
)

/** 解析 native 返回的控件 JSON（解析失败返回空列表，不影响音频）。 */
fun parseScriptControls(json: String): List<ScriptControl> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val opts = o.optJSONArray("options")
        val name = o.optString("name", "")
        ScriptControl(
            type = o.optString("type", "toggle"),
            index = o.optInt("index", 0),
            name = name,
            label = o.optString("label", "").ifEmpty { name },
            options = if (opts == null) {
                emptyList()
            } else {
                (0 until opts.length()).map { k -> opts.optString(k) }
            },
            defaultValue = o.optDouble("default", 0.0).toFloat(),
            hasDefault = o.has("default"),
        )
    }
}.getOrElse { emptyList() }
