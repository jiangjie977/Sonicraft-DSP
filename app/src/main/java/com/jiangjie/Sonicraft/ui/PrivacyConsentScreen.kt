/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiangjie.Sonicraft.ui.theme.ScColors

/**
 * 首次启动隐私政策同意页。
 *
 * 为什么必须挡在 [MainScreen] 之前：工信部《App 违法违规收集使用个人信息行为认定方法》
 * 要求「未经用户同意不得收集个人信息、不得申请权限」。本项目在 MainScreen 创建时即会
 * 实例化 MainViewModel → 触发 `autoStartCaptureIfNeeded` → 检查/申请录音权限并建链。
 * 因此**同意前不进入 MainScreen**，从根上保证「同意前零动作」（不建链、不申请权限、
 * 不读音频）。不同意则退出应用，不留后台进程。
 *
 * 版式：**渐变头部**（盾形图标 + 标题 + 副标题）→ **单卡片**（摘要强调条 + 编号分节，
 * 内部滚动）→ **底部固定操作栏**（合规说明 + 双按钮）。
 *
 * 合规要点（不是纯视觉）：
 * - 无倒计时、无默认勾选，「同意」从进入页面起即可点；
 * - 「不同意」与「同意」等高、等宽、同等清晰，不做弱化诱导；
 * - 底部显式写明「点击同意即表示已阅读并同意」，属于明确同意的表达。
 */
@Composable
fun PrivacyConsentScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    val scrollState = rememberScrollState()

    // 强制初始停在顶部：weight + verticalScroll 有时会被焦点/恢复状态带到中间
    LaunchedEffect(Unit) { scrollState.scrollTo(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScColors.Page)
            .systemBarsPadding(),
    ) {
        PrivacyHeader()

        // ---- 单卡片：摘要 + 分节条款，内部滚动 ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(ScColors.CardTop, ScColors.CardBottom),
                        ),
                    )
                    .border(1.dp, ScColors.Divider, RoundedCornerShape(18.dp))
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 18.dp),
            ) {
                SummaryNote()

                PRIVACY_SECTIONS.forEachIndexed { index, (title, body) ->
                    if (index == 0) {
                        Spacer(Modifier.height(16.dp))
                    } else {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            thickness = 1.dp,
                            color = ScColors.Divider.copy(alpha = 0.7f),
                        )
                    }
                    SectionItem(index = index, title = title, body = body)
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "完整版本见「我的 → 隐私与合规」，或仓库根目录的 PRIVACY_POLICY.md",
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    color = ScColors.TextSub.copy(alpha = 0.85f),
                )
            }
        }

        // ---- 底部固定操作栏 ----
        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            thickness = 1.dp,
            color = ScColors.Divider,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ScColors.CardTop)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "点击「同意并继续」，表示你已阅读并同意上述全部条款",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = ScColors.TextSub,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(text = "不同意并退出", fontSize = 13.sp, color = ScColors.TextSub)
                }
                Button(
                    onClick = onAgree,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScColors.Primary),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = "同意并继续",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 渐变头部：盾形图标 + 居中标题 + 定位副标题 */
@Composable
private fun PrivacyHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(ScColors.Primary.copy(alpha = 0.10f), Color.Transparent),
                ),
            )
            .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldIcon(
            modifier = Modifier.size(34.dp),
            tint = ScColors.Primary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "隐私政策与用户协议",
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ScColors.TextMain,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Sonicraft · 本地音频处理工具 · 不联网",
            fontSize = 11.5.sp,
            color = ScColors.TextSub,
            textAlign = TextAlign.Center,
        )
    }
}

/** 摘要强调块：左侧主色竖条 + 浅底，把「全部在本机完成」单独抬出来 */
@Composable
private fun SummaryNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(ScColors.Primary.copy(alpha = 0.06f)),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(ScColors.Primary.copy(alpha = 0.65f)),
        )
        Text(
            text = PRIVACY_SUMMARY,
            modifier = Modifier.padding(start = 11.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = ScColors.TextMain,
        )
    }
}

/** 单节：编号徽章 + 标题 + 正文 */
@Composable
private fun SectionItem(index: Int, title: String, body: String) {
    // 标题原文带「一、二、…」中文序号，这里改用数字徽章，避免重复
    val label = title.substringAfter("、", title)

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(ScColors.Primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = ScColors.Primary,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = ScColors.TextMain,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
                color = ScColors.TextSub,
            )
        }
    }
}

/** 盾形图标（内嵌对勾），Canvas 自绘，风格与项目其他 1.7dp 线稿图标一致 */
@Composable
private fun ShieldIcon(modifier: Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.50f, h * 0.06f)
            lineTo(w * 0.90f, h * 0.22f)
            lineTo(w * 0.90f, h * 0.54f)
            cubicTo(w * 0.90f, h * 0.76f, w * 0.72f, h * 0.90f, w * 0.50f, h * 0.96f)
            cubicTo(w * 0.28f, h * 0.90f, w * 0.10f, h * 0.76f, w * 0.10f, h * 0.54f)
            lineTo(w * 0.10f, h * 0.22f)
            close()
        }
        drawPath(shield, color = tint, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))

        val check = Path().apply {
            moveTo(w * 0.31f, h * 0.50f)
            lineTo(w * 0.44f, h * 0.63f)
            lineTo(w * 0.71f, h * 0.35f)
        }
        drawPath(check, color = tint, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}
