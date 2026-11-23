/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiangjie.Sonicraft.ui.theme.ScCardBrush
import com.jiangjie.Sonicraft.ui.theme.ScColors
import com.jiangjie.Sonicraft.ui.theme.ScElevation
import com.jiangjie.Sonicraft.ui.theme.ScHighlightBorder
import com.jiangjie.Sonicraft.ui.theme.ScMotionEasing
import com.jiangjie.Sonicraft.ui.theme.ScPrimaryBrush
import com.jiangjie.Sonicraft.ui.theme.ScRadius
import com.jiangjie.Sonicraft.ui.theme.ScStarBrush

/**
 * Sonicraft 卡片语言组件库（U1 批次沉淀，可复用资产）。
 * 对应设计稿 docs/ui/UI_DESIGN_PREVIEW.html 的 HL-1/HL-2 层级与胶囊规范。
 */

/**
 * HL-1 内容卡：白渐变面 + L1 阴影 + 顶部受光高光描边，圆角 20。
 * 轻拟物规范：凸面语义 = 可点击/承载内容的容器。
 */
@Composable
fun ScCard(
    modifier: Modifier = Modifier,
    background: Brush = ScCardBrush,
    /**
     * 是否画 L1 投影。
     *
     * **嵌套在另一张卡里时必须传 false**：两层阴影会叠加，内层卡边缘出现一圈灰雾，
     * 看起来像"卡片没对齐"而不是"卡片浮在上面"。关掉后靠 1dp 高光描边 + 渐变底
     * 与父卡区分，反而更干净 —— 这层语义本来就是"卡内分区"，不是"浮起的另一张卡"。
     */
    elevated: Boolean = true,
    /**
     * 是否由卡片自己加 16dp 内边距。
     *
     * **卡片头需要整行可点时必须传 false**：16dp 内边距是"死区"——
     * 点在里面不会触发任何东西，用户表现为"要点好几次才成功"。
     * 传 false 后由调用方给头部/内容各自加内边距，头部就能贴到卡片边缘。
     */
    padded: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(ScRadius.Card)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (elevated) Modifier.shadow(ScElevation.L1, shape, clip = false)
                else Modifier,
            )
            .clip(shape)
            .background(background)
            // 描边始终保留，但**颜色分两种**：
            //   有阴影时用 ScHighlightBorder（0xE6FFFFFF，近乎纯白的高光边）——
            //     它模拟"受光的凸起面"，靠下面的阴影撑出立体感，本身就是白的，几乎看不见
            //   无阴影时改用 ScColors.Divider（0xFFE4E9EC 浅灰）——
            //     高光边失去阴影衬托后与白底完全融为一体，卡片边界会"消失"；
            //     换成可见的灰线才能看出这是一块独立的区域
            .border(
                1.dp,
                if (elevated) ScHighlightBorder else ScColors.Divider,
                shape,
            ),
    ) {
        if (padded) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        } else {
            content()
        }
    }
}

/**
 * HL-2 凹面块：浅灰容纳面（内凹语义 = 展示/容纳，不可按），圆角 14。
 * Compose 无原生内阴影，以「灰面 + 深灰细描边」模拟凹感（设计稿已认可该近似）。
 */
@Composable
fun ScInset(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(ScRadius.Inset)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ScColors.Inset)
            .border(1.dp, Color(0xFFDFE5E8), shape),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

/**
 * U8p 凸面软按钮（胶囊）：白渐变面 + 受光描边 + L1 阴影，轻拟物「可点击」语义。
 * danger=true 时文字警示红（清空库等破坏性操作）。文本单行，宽度由调用方（weight）控制。
 */
@Composable
fun ScSoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(99.dp)
    Box(
        modifier = modifier
            .shadow(ScElevation.L1, shape, clip = false)
            .clip(shape)
            .background(ScCardBrush)
            .border(1.dp, ScHighlightBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) ScColors.Danger else ScColors.TextMain,
            maxLines = 1,
        )
    }
}

/** 星云渐变头像/图标位（AI 层）。 */
@Composable
fun ScAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    fontSize: TextUnit = 12.sp,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size)
            .shadow(ScElevation.L1, shape)
            .clip(shape)
            .background(ScStarBrush)
            .border(1.dp, ScHighlightBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

enum class ScPillVariant { Teal, Warn, Good, Neb, Neutral, Danger }

/**
 * 状态胶囊徽章。
 * 语义：Teal=功能态（当前/导入），Warn=警示/待办，Good=健康/生效中，
 * Neb=引擎运行态（星云 AI 层），Neutral=中性说明。
 */
@Composable
fun ScPill(
    text: String,
    modifier: Modifier = Modifier,
    variant: ScPillVariant = ScPillVariant.Neutral,
    showDot: Boolean = false,
    elevated: Boolean = false,
) {
    val (bg: Color, fg: Color, brush: Brush?) = when (variant) {
        ScPillVariant.Teal -> Triple(Color(0xFFE2F0F0), ScColors.Primary, null)
        ScPillVariant.Warn -> Triple(Color(0xFFFDF3DE), Color(0xFFB07A18), null)
        ScPillVariant.Good -> Triple(Color(0xFFE4F4EA), ScColors.Ok, null)
        ScPillVariant.Neb -> Triple(Color.Transparent, Color.White, ScStarBrush)
        ScPillVariant.Neutral -> Triple(Color(0xFFEAEFF1), ScColors.TextSub, null)
        // 强提示（如「特权框架未安装」，点击可跳下载）——比 Warn 更醒目，但仍是浅底深字，不刺眼
        ScPillVariant.Danger -> Triple(Color(0xFFFCE7E7), Color(0xFFC0392B), null)
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .then(
                when {
                    // 渐变底（Neb）或 elevated：L1 阴影 + 受光高光描边包裹（U9i）
                    brush != null || elevated -> Modifier
                        .shadow(
                            ScElevation.L1,
                            shape,
                        )
                        .clip(shape)
                        .background(brush ?: ScCardBrush)
                        .border(1.dp, ScHighlightBorder, shape)
                    else -> Modifier
                        .clip(shape)
                        .background(bg)
                        .border(1.dp, Color(0x14FFFFFF), shape)
                },
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        // U9h：内容水平居中——wrap 宽度时无视觉变化，fillMaxWidth 等宽场景（Running/Stellar 对齐）文字居中
        horizontalArrangement = Arrangement.Center,
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (brush != null) Color.White else fg),
            )
            Box(modifier = Modifier.size(5.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 区块标题行（卡片内首行：左标题右说明/徽章）。 */
@Composable
fun ScCardHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = ScColors.TextMain,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** 电平条（替代散写的 meter：轨道 + 值条），U1 仅样式统一，数据仍由调用方给。 */
@Composable
fun ScMeter(
    level: Float,
    modifier: Modifier = Modifier,
    track: Color = ScColors.Inset,
    fill: Brush = Brush.horizontalGradient(listOf(ScColors.Primary, ScColors.PrimaryLight)),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fill),
        )
    }
}

/**
 * U2 效果卡（设计稿 P1 效果卡网格）：图标位 + 名称 + 副文本。
 * `onToggle` 非空时右上角放拟物开关（设计稿 swx），onClick 交给调用方
 * 语义（如卷积混响卡=打开 P2 详情页）；为空时保持「整卡点击切换开关」。
 * 激活语义（规范）：L2 抬升 + 主色描边，不放大；未激活：L1 + 受光描边。
 * 按压 scale 0.98 + ScMotionEasing（星云动效规范 ⑥ 在此落地，全 app 复用）。
 */
@Composable
fun ScEffectCard(
    title: String,
    subtitle: String,
    icon: ScEffectIconKind,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    comingSoon: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(ScRadius.Card)
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.98f else 1f,
        animationSpec = tween(durationMillis = 250, easing = ScMotionEasing),
        label = "scEffectPress",
    )
    val clickable = if (comingSoon) {
        Modifier
    } else {
        Modifier.pointerInput(onClick) {
            detectTapGestures(
                onPress = {
                    pressed.value = true
                    try {
                        awaitRelease()
                    } finally {
                        pressed.value = false
                    }
                },
                onTap = { onClick() },
            )
        }
    }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            // U8k：统一卡高（对齐卷积卡自然高度），矮内容垂直居中
            .heightIn(min = 70.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (comingSoon) 0.55f else 1f)
            .shadow(if (enabled) ScElevation.L2 else ScElevation.L1, shape, clip = false)
            .clip(shape)
            .background(ScCardBrush)
            .border(
                if (enabled) 1.5.dp else 1.dp,
                if (enabled) ScColors.Primary else ScHighlightBorder,
                shape,
            )
            .then(clickable)
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onToggle != null) Modifier.padding(end = 42.dp) else Modifier,
        ) {
            // 图标位（U6：设计稿线性图标，激活=青绿主色，未激活=次级灰）
            ScEffectIcon(
                kind = icon,
                tint = if (enabled) ScColors.Primary else ScColors.TextSub,
                modifier = Modifier.padding(end = 10.dp),
            )
            // 窄屏两卡并排时（360dp 级），不设 weight 会被右侧开关挤成竖排；
            // 且标题/副标题都不允许折行（2026-11-07 响应式批次 2）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 两卡并排的窄屏：标题自动缩字号而不是省略（10~13sp，1sp 步进）
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 10.sp,
                        maxFontSize = 13.sp,
                        stepSize = 1.sp,
                    ),
                )
                Text(
                    text = if (comingSoon) "敬请期待" else subtitle,
                    fontSize = 10.sp,
                    color = ScColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // U5：右上角拟物开关（设计稿 P1 fx 卡 swx 位；自身消费点击，不影响整卡 onTap）
        if (onToggle != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp),
            ) {
                ScSwitch(checked = enabled, onCheckedChange = onToggle)
            }
        } else if (comingSoon) {
            // U6：敬请期待橙 pill（设计稿 P1 低频增强卡右上角）
            ScPill(
                text = "敬请期待",
                variant = ScPillVariant.Warn,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp),
            )
        }
    }
}

/**
 * U5 拟物开关（设计稿 swx：42×24 胶囊，选中=青绿渐变轨道+白钮右移，
 * 未选中=凹面灰轨+受光描边；白钮带 1px 投影）。供效果卡右上角与 P2 详情页头使用。
 */
@Composable
fun ScSwitch(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(99.dp)
    Box(
        modifier = modifier
            .size(40.dp, 22.dp)
            .clip(trackShape)
            .then(
                if (checked) {
                    Modifier.background(ScPrimaryBrush)
                } else {
                    Modifier
                        .background(ScColors.Inset)
                        .border(1.dp, Color(0xFFDFE5E8), trackShape)
                },
            )
            .clickable { onCheckedChange() },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .shadow(1.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** U5 Tab 图标种类（设计稿 tabs 四枚 20px 线性 SVG 图标）。 */
enum class ScTabIconKind { GRID, SEARCH, HEART, PERSON }

/** U6 效果卡线性图标种类（设计稿 P1 fx 卡：青绿描边 SVG 线性风格，无底色圆块）。 */
enum class ScEffectIconKind { WAVE, EQ, BURST, PULSE, RINGS, BASS, LIMITER }

/**
 * U9a 电源图标（20dp Canvas 自绘，stroke 1.7dp 圆头规范）：竖线 + 顶部开口圆弧。
 * U9b：`brush` 非空时圆弧用画刷（星云紫蓝渐变，开启态），竖线仍用 tint；null 时全 tint。
 * 音效库设备卡的启停内录按钮用——运行中星云紫蓝/停止灰，颜色由调用方给。
 */
@Composable
fun ScPowerIcon(tint: Color, modifier: Modifier = Modifier, brush: Brush? = null) {
    Canvas(modifier = modifier.size(20.dp)) {
        // 加粗（2026-11-06 用户要求）：1.7dp → 2.6dp
        val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f - 2.6.dp.toPx()
        // 竖线（12 点方向）
        drawLine(
            tint,
            start = Offset(cx, cy - r * 0.95f),
            end = Offset(cx, cy - r * 0.3f),
            strokeWidth = 2.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // 顶部开口圆弧（缺口 80° 朝上）
        drawArc(
            brush ?: androidx.compose.ui.graphics.SolidColor(tint), -40f, 260f, false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2, r * 2),
            style = stroke,
        )
    }
}

/**
 * U6 效果卡线性图标（24dp Canvas 自绘，stroke 1.7dp 圆头，同 ScTabIcon 画法规范）：
 * WAVE=正弦（卷积）/ EQ=三滑杆（参量均衡）/ BURST=星芒（压限）/ PULSE=心电（宽度）/
 * RINGS=同心弧（FDN）/ BASS=点+扩散弧（低频增强）/ LIMITER=钳位线+顶线正弦（限幅器，U7b）。
 */
@Composable
fun ScEffectIcon(
    kind: ScEffectIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        when (kind) {
            ScEffectIconKind.WAVE -> {
                val path = Path()
                val midY = h * 0.5f
                path.moveTo(w * 0.06f, midY)
                path.cubicTo(w * 0.18f, h * 0.12f, w * 0.32f, h * 0.12f, w * 0.44f, midY)
                path.cubicTo(w * 0.56f, h * 0.88f, w * 0.70f, h * 0.88f, w * 0.82f, midY)
                path.lineTo(w * 0.94f, midY)
                drawPath(path, color = tint, style = stroke)
            }
            ScEffectIconKind.EQ -> {
                val ys = listOf(0.22f, 0.5f, 0.78f)
                val dots = listOf(0.62f, 0.34f, 0.7f)
                ys.forEachIndexed { i, y ->
                    val cy = h * y
                    drawLine(tint, Offset(w * 0.08f, cy), Offset(w * 0.92f, cy), stroke.width, StrokeCap.Round)
                    drawCircle(tint, radius = 2.4.dp.toPx(), center = Offset(w * dots[i], cy), style = stroke)
                }
            }
            ScEffectIconKind.BURST -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                val rIn = w * 0.14f
                val rOut = w * 0.4f
                for (i in 0 until 8) {
                    val ang = Math.PI * 2.0 * i / 8.0
                    val dx = Math.cos(ang).toFloat()
                    val dy = Math.sin(ang).toFloat()
                    drawLine(
                        tint,
                        Offset(cx + dx * rIn, cy + dy * rIn),
                        Offset(cx + dx * rOut, cy + dy * rOut),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }
            ScEffectIconKind.PULSE -> {
                val midY = h * 0.5f
                val path = Path().apply {
                    moveTo(w * 0.06f, midY)
                    lineTo(w * 0.3f, midY)
                    lineTo(w * 0.4f, h * 0.2f)
                    lineTo(w * 0.52f, h * 0.8f)
                    lineTo(w * 0.62f, midY)
                    lineTo(w * 0.94f, midY)
                }
                drawPath(path, color = tint, style = stroke)
            }
            ScEffectIconKind.RINGS -> {
                drawArc(
                    tint, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(w * 0.3f, h * 0.3f), size = Size(w * 0.4f, h * 0.4f), style = stroke,
                )
                drawArc(
                    tint, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(w * 0.1f, h * 0.1f), size = Size(w * 0.8f, h * 0.8f), style = stroke,
                )
            }
            ScEffectIconKind.BASS -> {
                drawCircle(tint, radius = 2.6.dp.toPx(), center = Offset(w * 0.3f, h * 0.5f), style = stroke)
                drawArc(
                    tint, startAngle = -55f, sweepAngle = 110f, useCenter = false,
                    topLeft = Offset(w * 0.24f, h * 0.3f), size = Size(w * 0.4f, h * 0.4f), style = stroke,
                )
                drawArc(
                    tint, startAngle = -55f, sweepAngle = 110f, useCenter = false,
                    topLeft = Offset(w * 0.24f, h * 0.14f), size = Size(w * 0.72f, h * 0.72f), style = stroke,
                )
            }
            ScEffectIconKind.LIMITER -> {
                // 钳位线：顶部水平线（天花板）
                drawLine(tint, Offset(w * 0.06f, h * 0.22f), Offset(w * 0.94f, h * 0.22f), strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
                // 正弦：波峰钳在天花板下（限幅语义）
                val path = Path()
                path.moveTo(w * 0.06f, h * 0.58f)
                path.cubicTo(w * 0.18f, h * 0.16f, w * 0.30f, h * 0.16f, w * 0.42f, h * 0.58f)
                path.cubicTo(w * 0.54f, h * 0.94f, w * 0.68f, h * 0.94f, w * 0.80f, h * 0.58f)
                path.lineTo(w * 0.94f, h * 0.58f)
                drawPath(path, color = tint, style = stroke)
            }
        }
    }
}

/**
 * U5 Tab 线性图标（20dp Canvas 自绘，stroke 1.7dp 圆头——对齐设计稿 1.7px SVG 线性风格，
 * 项目未引 material-icons 依赖）。
 */
@Composable
fun ScTabIcon(
    kind: ScTabIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        when (kind) {
            ScTabIconKind.GRID -> {
                val gap = 2.4.dp.toPx()
                val cell = (size.width - gap * 3f) / 2f
                val r = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                listOf(0f, cell + gap).forEach { dx ->
                    listOf(0f, cell + gap).forEach { dy ->
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(gap / 2f + dx, gap / 2f + dy),
                            size = Size(cell, cell),
                            cornerRadius = r,
                            style = stroke,
                        )
                    }
                }
            }
            ScTabIconKind.SEARCH -> {
                val r = size.width * 0.32f
                drawCircle(
                    color = tint,
                    radius = r,
                    center = Offset(size.width * 0.42f, size.width * 0.42f),
                    style = stroke,
                )
                drawLine(
                    color = tint,
                    start = Offset(
                        size.width * 0.42f + r * 0.7071f,
                        size.width * 0.42f + r * 0.7071f,
                    ),
                    end = Offset(size.width * 0.92f, size.width * 0.92f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            ScTabIconKind.HEART -> {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.84f)
                    cubicTo(w * 0.20f, h * 0.62f, w * 0.06f, h * 0.47f, w * 0.06f, h * 0.34f)
                    cubicTo(w * 0.06f, h * 0.20f, w * 0.17f, h * 0.12f, w * 0.28f, h * 0.12f)
                    cubicTo(w * 0.37f, h * 0.12f, w * 0.46f, h * 0.18f, w * 0.5f, h * 0.26f)
                    cubicTo(w * 0.54f, h * 0.18f, w * 0.63f, h * 0.12f, w * 0.72f, h * 0.12f)
                    cubicTo(w * 0.83f, h * 0.12f, w * 0.94f, h * 0.20f, w * 0.94f, h * 0.34f)
                    cubicTo(w * 0.94f, h * 0.47f, w * 0.80f, h * 0.62f, w * 0.5f, h * 0.84f)
                    close()
                }
                drawPath(path, color = tint, style = stroke)
            }
            ScTabIconKind.PERSON -> {
                val w = size.width
                drawCircle(
                    color = tint,
                    radius = w * 0.17f,
                    center = Offset(w * 0.5f, w * 0.33f),
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, w * 0.44f),
                    size = Size(w * 0.68f, w * 0.62f),
                    style = stroke,
                )
            }
        }
    }
}

/**
 * U5 底部 Tab 导航浮层（设计稿 .tabs：HL-3 白面 L3 阴影 圆角 24 高 64，下边距 12；
 * 激活=青绿粗体，未激活=次级灰）。三页：音效库 / 收藏 / 我的（U8k 搜索 Tab 退役，脉冲库迁卷积详情页）。
 */
@Composable
fun ScTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // U8k：搜索 Tab 退役（脉冲库迁入卷积详情页），三页导航
    val tabs = listOf(
        Triple("音效库", ScTabIconKind.GRID, "效果开关与信号链"),
        Triple("预设", ScTabIconKind.HEART, "场景 · 预设 · 收藏"),
        Triple("我的", ScTabIconKind.PERSON, "设备与设置"),
    )
    val shape = RoundedCornerShape(ScRadius.Float)
    Row(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(ScElevation.L3, shape, clip = false)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, ScHighlightBorder, shape),
    ) {
        tabs.forEachIndexed { index, (title, icon, _) ->
            val active = index == selected
            val tint = if (active) ScColors.Primary else ScColors.TextSub
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                // 按压涟漪收进一个**圆角块**里：只给这个 Tab 自身的内缩区域，
                // 不再铺满整条 bar（原先的四角是直角块，用户反馈像"未实现的选中态"）。
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(ScRadius.Inset))
                    .clickable { onSelect(index) },
            ) {
                ScTabIcon(kind = icon, tint = tint)
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = tint,
                )
            }
        }
    }
}

/**
 * 卡片头整行点击：**去掉涟漪**，只保留行为。
 *
 * 为什么不用默认 clickable：卡片头本来就宽（整行），涟漪会铺满一整条灰带，
 * 看起来像"选中/未实现"，用户明确反馈影响观感。这里显式传
 * `indication = null`，并复用同一个 InteractionSource（不创建多余对象）。
 */
fun Modifier.scNoRippleClick(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

