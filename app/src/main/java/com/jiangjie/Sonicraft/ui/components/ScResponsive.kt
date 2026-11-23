/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jiangjie.Sonicraft.core.common.DiagDirs
import java.io.File
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 多机型响应式公共组件。
 *
 * 原则：**内容驱动，不按容器比例缩放**。机型名长度差异极大
 * （`NX733J` / `V2408A` / `Pixel 9 Pro XL`），任何 `fillMaxWidth(0.6f)` 都会在
 * 某个机型上再次崩；正确做法是「单行 + 省略」，宽度不够时由布局断点切换结构。
 */

/** 断点：小于此宽度时左右结构切换为上下堆叠 */
val ScCompactBreakpoint: Dp = 360.dp

/**
 * 单行文本：卡片内所有信息行都应使用它，杜绝「宽度不足 → 折成 2~3 行 / 竖排」。
 * 本机（Medium 档）文本本来就放得下，外观零变化。
 */
@Composable
fun ScTextOneLine(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    style: TextStyle = TextStyle.Default,
    /** 宽度不足时自动缩小字号（而不是省略号）；min 12sp / 1sp 步进 */
    autoShrink: Boolean = false,
) {
    // 诊断留痕：记录自动缩放最终选中的字号（每个 text+fontSize 组合只写一次）
    val autosizeLogged = remember(text, fontSize) { mutableStateOf(false) }
    Text(
        text = text,
        fontSize = fontSize,
        onTextLayout = { result ->
            if (autoShrink && !autosizeLogged.value) {
                autosizeLogged.value = true
                runCatching {
                    val f = File(DiagDirs.crashDir, "ui_autosize.txt")
                    f.parentFile?.mkdirs()
                    f.appendText(
                        "text=$text max=$fontSize resolved=${result.layoutInput.style.fontSize}\n",
                    )
                }
            }
        },
        fontWeight = fontWeight,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Compose 1.8+ 原生 TextAutoSize：在 [12sp, fontSize] 区间按 1sp 步进
        // 选择「单行能完整放下」的最大字号。放得下时仍是原始 fontSize（外观不变）。
        autoSize = if (autoShrink) {
            TextAutoSize.StepBased(
                minFontSize = 12.sp,
                maxFontSize = fontSize,
                stepSize = 1.sp,
            )
        } else {
            null
        },
        modifier = modifier,
    )
}

/**
 * 自适应左右结构：宽度足够时保持横排（本机外观不变），窄屏时上下堆叠。
 * 用 `BoxWithConstraints.maxWidth` 判定（dp，不看 px/机型），组件内部决策，
 * 因此同一页面可以「这张卡堆叠、另一张卡仍横排」。
 */
@Composable
fun ScAdaptiveRow(
    modifier: Modifier = Modifier,
    stackBelow: Dp = ScCompactBreakpoint,
    left: @Composable RowScope.() -> Unit,
    right: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth < stackBelow) {
            Column {
                // 堆叠时给两块各自完整宽度
                Row(content = left)
                Row(content = right)
            }
        } else {
            Row {
                left()
                right()
            }
        }
    }
}
