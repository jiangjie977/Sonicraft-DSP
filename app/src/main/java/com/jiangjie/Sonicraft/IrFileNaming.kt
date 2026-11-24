/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * IR 文件的命名与候选判定（从 MainViewModel 抽出，纯函数、可单测）。
 */
package com.jiangjie.Sonicraft

/**
 * 脉冲库文件的命名规则。
 *
 * 设计约束（都踩过坑）：
 * - **保留中文与空格**：库列表直接展示文件名，早期把非 ASCII 替换成下划线，中文名全变 `___`；
 * - 只滤路径分隔符与控制字符（`/ \ \0`），否则 `File(dir, name)` 会逃逸到别的目录；
 * - 长度截到 80：ext4 单文件名上限 255 字节，中文 UTF-8 占 3 字节，80 字符已留足余量；
 * - 最小体积 44 字节 = 空 WAV 头长度，用来挡 0 字节与半截文件；`size < 0` 表示来源未提供大小，
 *   放行交由实际复制结果检验（未知 ≠ 0）。
 */
object IrFileNaming {

    /** 可导入扩展名（小写，不含点） */
    val DEFAULT_EXTENSIONS = setOf("wav", "irs", "pcm", "fir", "ir")

    /** 最小可信文件体积（字节）：44 = 空 WAV 头 */
    const val MIN_BYTES = 44L

    /** 落盘名最大长度（字符） */
    const val MAX_NAME_LEN = 80

    /** 是否为可导入候选。 */
    fun isCandidate(
        name: String,
        size: Long,
        extensions: Set<String> = DEFAULT_EXTENSIONS,
    ): Boolean {
        if (name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in extensions && (size < 0L || size >= MIN_BYTES)
    }

    /** 落盘名清洗：滤路径分隔符与控制字符，保中文/空格，截断到 [MAX_NAME_LEN]。 */
    fun sanitize(raw: String): String = buildString {
        for (c in raw) {
            append(if (c == '/' || c == '\\' || c == '\u0000') '_' else c)
        }
    }.trim().take(MAX_NAME_LEN).ifBlank { "ir" }

    /**
     * 显示名：去掉历史 `ir_` 前缀与扩展名。
     *
     * `dot > 0` 而不是 `>= 0`：`.wav` 这种纯扩展名不应被截成空串。
     */
    fun displayName(fileName: String): String {
        val noPrefix = fileName.removePrefix("ir_")
        val dot = noPrefix.lastIndexOf('.')
        return if (dot > 0) noPrefix.substring(0, dot) else noPrefix
    }
}
