/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiangjie.Sonicraft.ui.theme.ScColors
import kotlinx.coroutines.delay

/**
 * 首次启动隐私政策同意页。
 *
 * 为什么必须挡在 [MainScreen] 之前：工信部《App 违法违规收集使用个人信息行为认定方法》
 * 要求「未经用户同意不得收集个人信息、不得申请权限」。本项目在 MainScreen 创建时即会
 * 实例化 MainViewModel → 触发 `autoStartCaptureIfNeeded` → 检查/申请录音权限并建链。
 * 因此**同意前不进入 MainScreen**，从根上保证「同意前零动作」（不建链、不申请权限、
 * 不读音频）。不同意则退出应用，不留后台进程。
 *
 * 版式（按设计稿）：标题居中 → **一个卡片**包裹全部条款（内部可滚动）→ 底部双按钮。
 *
 * ## 关于阅读倒计时
 *
 * 设计稿曾要求「5 秒后同意按钮才可用」，但商店的隐私合规检测把「强制等待/倒计时/
 * 无法立即继续」列为风险项，且倒计时并不能证明用户真的读了。**现已移除**：
 * 按钮从进入页面起即可点击。
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
        // ---- 标题：居中 ----
        Text(
            text = "隐私政策与用户协议",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp, bottom = 12.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = ScColors.TextMain,
            textAlign = TextAlign.Center,
        )

        // ---- 一个卡片包裹全部内容，内部滚动 ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ScColors.CardTop)
                    .border(1.dp, ScColors.Divider, RoundedCornerShape(14.dp))
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 摘要（卡片内的小色块，与设计稿一致：正文第一段）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ScColors.Primary.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = PRIVACY_SUMMARY,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = ScColors.TextMain,
                    )
                }

                for ((title, body) in PRIVACY_SECTIONS) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = title,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ScColors.TextMain,
                        )
                        Text(
                            text = body,
                            fontSize = 12.5.sp,
                            lineHeight = 19.5.sp,
                            color = ScColors.TextSub,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "完整版本见「我的 → 隐私与合规」，或仓库根目录的 PRIVACY_POLICY.md",
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
            }
        }

        // ---- 底部固定操作栏 ----
        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            thickness = 1.dp,
            color = ScColors.Divider,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ScColors.CardTop)
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
