/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = SonicraftPrimary,
    secondary = SonicraftSecondary,
    tertiary = SonicraftTertiary,
)

private val LightColors = lightColorScheme(
    primary = SonicraftPrimary,
    secondary = SonicraftSecondary,
    tertiary = SonicraftTertiary,
    // U3 补：清退 baseline 紫残留槽位（真机彩色槽位实验定位——2026-11-01）：
    // - Slider 未激活轨道走 secondaryContainer（原淡紫 #E6E1F5）
    // - Card 背景（引擎状态卡等）走 surfaceContainerHighest（原淡紫灰）
    // - surfaceVariant/High/Low/Container：文本框底、填充组件底等中性面
    secondaryContainer = ScColors.Divider,
    // U4 补：页面底色槽位（Surface/Scaffold 默认取 surface/background，baseline 粉白紫）
    surface = ScColors.Page,
    background = ScColors.Page,
    onSurface = ScColors.TextMain,
    onBackground = ScColors.TextMain,
    surfaceVariant = ScColors.Inset,
    surfaceContainerHighest = ScColors.CardBottom,
    surfaceContainerHigh = ScColors.Inset,
    surfaceContainer = ScColors.Page,
    surfaceContainerLow = ScColors.Page,
    onSurfaceVariant = ScColors.TextSub,
    outline = ScColors.Divider,
    outlineVariant = ScColors.Inset,
)

private val ScShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), // ScRadius.Chip
    small = RoundedCornerShape(14.dp), // ScRadius.Inset
    medium = RoundedCornerShape(20.dp), // ScRadius.Card
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun SonicraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = ScShapes,
        content = content,
    )
}
