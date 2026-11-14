/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sonicraft UI 设计令牌（U1 批次立项落地）。
 * 唯一来源：docs/ui/UI_DESIGN_PREVIEW.html 设计稿规范（含 ⑥ 星云 AIOS 贴合规范）。
 * 规则：UI 端一律引用本文件令牌，禁止在组件里散写 Color(0x…) 字面量。
 */

object ScColors {
    // ---- 音频功能层（青绿主色，与 EQ 面板定稿同源）----
    val Primary = Color(0xFF166B6B)
    val PrimaryLight = Color(0xFF1C8C8C)
    val PrimaryDark = Color(0xFF0F4F4F)

    // ---- 中性面（轻拟物：面=白→浅灰极缓渐变 ≤4% 亮度差）----
    val Page = Color(0xFFF4F7F8)
    val CardTop = Color(0xFFFFFFFF)
    val CardBottom = Color(0xFFFAFCFC)
    val Inset = Color(0xFFEDF1F3) // HL-2 凹面块底色
    val Divider = Color(0xFFE4E9EC)

    // ---- 文字 ----
    val TextMain = Color(0xFF1A2B2B)
    val TextSub = Color(0xFF7A8A8E)

    // ---- 语义 ----
    val Ok = Color(0xFF2E9E5B)
    val Warn = Color(0xFFE0A63A)
    val Danger = Color(0xFFD9534F)

    /** U4 收藏星（设计稿色板警示金 #E0A63A——星标/高亮警示共用） */
    val Star = Color(0xFFE0A63A)

    // ---- 星云 AIOS 贴合：AI/系统层专用（徽标/播放键/媒体封面/头像/AI 场景卡）----
    // 双强调分工（设计稿规范 ⑥）：星云=AI 层，青绿=音频功能层，杜绝两色打架。
    // 真机采样校准（NX733J 实拍取色）：紫蓝端=「星云AI+」图标 #736FFA/#7F7CFA，
    // 蓝端=「AI视频伪造检测」图标 #4797FE；系统功能激活蓝（QS 卡 #169BF4→#0092F3）不采用，
    // app 功能层保持青绿品牌。系统中性面为纯灰（#F5F5F5/#EBEBEB），app 保留青调轻拟物（EQ 定稿同源）。
    val NebPurple = Color(0xFF8A7EFF)
    val NebCore = Color(0xFF736FFA)
    val NebBlue = Color(0xFF4797FE)
    val NebShadow = Color(0x52736FFA)
}

/** HL-1 内容卡面：白→浅灰 180° 极缓渐变。 */
val ScCardBrush: Brush = Brush.verticalGradient(listOf(ScColors.CardTop, ScColors.CardBottom))

/** 星云渐变（135° 对角，紫蓝→蓝，2026-11-01 真机实采校准），AI 层元素专用。 */
val ScStarBrush: Brush = Brush.linearGradient(
    listOf(ScColors.NebPurple, ScColors.NebCore, ScColors.NebBlue),
)

/** 主按钮/激活胶囊渐变（青绿 135°），音频功能层激活态专用。 */
val ScPrimaryBrush: Brush = Brush.linearGradient(listOf(ScColors.PrimaryLight, ScColors.Primary))

/** 顶部 1px 白高光（轻拟物受光），叠加在描边上模拟。 */
val ScHighlightBorder = Color(0xE6FFFFFF)

object ScRadius {
    val Card = 20.dp // HL-1 内容卡
    val Float = 18.dp // HL-3 浮层
    val Inset = 14.dp // HL-2 凹面块
    val Chip = 10.dp // 图标位/小圆角
}

object ScElevation {
    val L1: Dp = 2.dp // 内容卡基线（星云 AIOS：同层只保留 L1，阴影克制）
    val L2: Dp = 6.dp // 激活态抬升
    val L3: Dp = 12.dp // 浮层（Tab 栏/播放条）
}

/** 星云 AIOS 动效气质：先快后慢（设计稿规范 ⑥）。 */
val ScMotionEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
