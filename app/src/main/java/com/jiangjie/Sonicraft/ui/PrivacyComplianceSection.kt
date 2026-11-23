/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiangjie.Sonicraft.MainViewModel
import com.jiangjie.Sonicraft.ui.components.ScCard
import com.jiangjie.Sonicraft.ui.theme.ScColors

/** 弹窗类型（一个 state 管三种全文展示，避免三个布尔量互相打架） */
private enum class ComplianceDoc { POLICY, PERMISSION, LICENSE }

/**
 * 「我的 → 隐私与合规」。
 *
 * 合规依据：
 *  - 工信部要求隐私政策**随时可查阅**，不能只在首启弹一次（否则属「未提供查阅途径」）；
 *  - 用户需有**撤回同意**与**清除数据**的入口；
 *  - 项目以 GPL-3.0-only 分发，**分发 APK 时有义务告知源码获取方式**。
 */
@Composable
fun PrivacyComplianceSection(viewModel: MainViewModel) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<ComplianceDoc?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }

    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("-")
    }

    // 标题**放回卡内**：与「⚠ 内录机制说明」同一处理。
    // 这两张都是页面尾部的说明/合规章节，不与上方那些设置分组并列，
    // 标题在卡内更像一张独立卡片，也不会被误读成又一个分组。
    // 字号仍用 15sp Bold（与卡外 ScSectionTitle 同款），只换位置、不换层级。
    ScCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "隐私与合规",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ScColors.TextMain,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            ComplianceRow("隐私政策", "全文 · 可随时查阅") { doc = ComplianceDoc.POLICY }
            ComplianceRow("权限说明", "逐项用途 · 可跳转系统设置") { doc = ComplianceDoc.PERMISSION }
            Text(
                text = "个人信息收集：无。不收集任何个人信息，不含统计/广告/推送等第三方 SDK。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            ComplianceRow("开源许可", "GPL-3.0-only · 源码获取方式") { doc = ComplianceDoc.LICENSE }
            ComplianceRow("清除诊断数据", "删除日志与音频样本，不动音效参数") {
                confirmClear = true
            }
            ComplianceRow("撤回隐私同意", "立即停止音效处理，返回隐私页") {
                confirmRevoke = true
            }
            Text(
                text = "版本 $version",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    // ---- 全文弹窗 ----
    val docTitle = when (doc) {
        ComplianceDoc.POLICY -> "隐私政策"
        ComplianceDoc.PERMISSION -> "权限说明"
        ComplianceDoc.LICENSE -> "开源许可"
        null -> ""
    }
    if (doc != null) {
        AlertDialog(
            onDismissRequest = { doc = null },
            title = { Text(text = docTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (doc) {
                        ComplianceDoc.POLICY -> {
                            Text(text = PRIVACY_SUMMARY, fontSize = 12.sp)
                            for ((t, b) in PRIVACY_SECTIONS) {
                                Text(
                                    text = t,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(text = b, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }

                        ComplianceDoc.PERMISSION -> {
                            for ((name, why) in PERMISSION_ROWS) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "$name：",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(text = why, fontSize = 12.sp)
                                }
                            }
                            Text(
                                text = "所有权限均可随时在系统设置中撤销。" +
                                    "本应用未声明网络权限，不具备联网能力。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", ctx.packageName, null),
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                            ) { Text(text = "打开系统权限设置", fontSize = 12.sp) }
                        }

                        ComplianceDoc.LICENSE -> {
                            Text(
                                text = "本应用主体以 GNU General Public License v3.0 " +
                                    "（GPL-3.0-only）分发，完整条款见仓库根 LICENSE 文件。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                            // 混合许可必须写清：Stellar 代码是 MPL-2.0，不能笼统说"全项目 GPL"
                            Text(
                                text = "其中「Stellar 特权框架」相关代码（integration/stellar 下的 " +
                                    "roro 与 stellar 包）以 Mozilla Public License 2.0 分发，" +
                                    "条款见 licenses/MPL-2.0.txt；文件级 copyleft，不是 GPL。" +
                                    "第三方组件完整归属见仓库 THIRD-PARTY-NOTICES.md。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                            Text(
                                text = "根据 GPL 第 3 条，你在获得本应用二进制的同时有权获得对应" +
                                    "源代码。获取方式：通过下方邮箱联系开发者索取，" +
                                    "或从项目源码仓库克隆。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                            Text(
                                text = "联系邮箱：＜上架前填写＞",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "本应用不含任何第三方闭源 SDK；音频算法（FFT / 分区卷积 / " +
                                    "EQ / 动态处理）均为本项目自行实现；native 层仅链接系统库 " +
                                    "liblog 与 libaaudio。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                            )
                        }

                        null -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doc = null }) { Text("关闭") }
            },
        )
    }

    // ---- 清除诊断数据确认 ----
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除诊断数据") },
            text = {
                Text(
                    text = "将删除应用私有目录下的诊断日志与音频样本（files/crash）。\n" +
                        "不会删除你的音效参数、预设与导入的脉冲文件。",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearDiagnosticData()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    // ---- 撤回同意确认 ----
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("撤回隐私同意") },
            text = {
                Text(
                    text = "撤回后将立即停止音效处理并返回隐私政策页，下次使用需重新同意。\n" +
                        "你的音效参数与预设会保留。",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    viewModel.revokePrivacyConsent()
                    // 重建 Activity：门控开关用 remember（非 saveable），重建后会重新读 prefs
                    (ctx as? Activity)?.recreate()
                }) { Text("确认撤回") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ComplianceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp)
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "›",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
