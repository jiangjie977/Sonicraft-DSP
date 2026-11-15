/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-FileCopyrightText: 2026 qumolangmo
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 部分逻辑移植自 WEcho（GPL-3.0，Copyright (C) 2026 qumolangmo）；
 * 本文件由 Sonicraft 于 2026-11 在 WEcho 基础上移植并修改。
 */

package com.jiangjie.Sonicraft.ui

// U4：设计稿 P4 收藏屏 + P5 我的屏（当前以屏组件形态落地，主页顶部临时切换器切换；
// U5 底部 Tab 框架落地后由导航直接挂载）。数据全部接既有 stateFlow：
// 场景组合=userPresets/builtinPresets，我的 EQ 预设=eqUserPresets，收藏脉冲=favoriteIrs∩irLibrary。

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.compose.runtime.rememberCoroutineScope
import android.media.AudioAttributes
import androidx.compose.ui.platform.LocalContext
import roro.stellar.StellarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiangjie.Sonicraft.MainViewModel
import com.jiangjie.Sonicraft.ScriptDefaults
import com.jiangjie.Sonicraft.ScriptSamples
import com.jiangjie.Sonicraft.core.SysInfo
import com.jiangjie.Sonicraft.core.audio.AudioFormat
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import com.jiangjie.Sonicraft.ui.components.ScAvatar
import com.jiangjie.Sonicraft.ui.components.ScCard
import com.jiangjie.Sonicraft.ui.components.ScCardHeader
import com.jiangjie.Sonicraft.ui.components.ScInset
import com.jiangjie.Sonicraft.ui.components.ScKnob
import com.jiangjie.Sonicraft.ui.components.ScMeter
import com.jiangjie.Sonicraft.ui.components.ScPill
import com.jiangjie.Sonicraft.ui.components.ScPillVariant
import com.jiangjie.Sonicraft.ui.components.ScPowerIcon
import com.jiangjie.Sonicraft.ui.components.ScSoftButton
import com.jiangjie.Sonicraft.ui.components.ScSwitch
import com.jiangjie.Sonicraft.ui.components.scNoRippleClick
import com.jiangjie.Sonicraft.ui.components.ScTextOneLine
import com.jiangjie.Sonicraft.ui.components.ScCompactBreakpoint
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.SolidColor
import com.jiangjie.Sonicraft.ui.theme.ScCardBrush
import com.jiangjie.Sonicraft.ui.theme.ScColors
import com.jiangjie.Sonicraft.ui.theme.ScElevation
import com.jiangjie.Sonicraft.ui.theme.ScPrimaryBrush
import com.jiangjie.Sonicraft.ui.theme.ScRadius
import com.jiangjie.Sonicraft.ui.theme.ScStarBrush

// ---------------------------------------------------------------- P4 收藏屏

@Composable
fun ScFavoritesScreen(viewModel: MainViewModel, onOpenEq: () -> Unit) {
    val userPresets by viewModel.userPresets.collectAsStateWithLifecycle()
    val eqPresets by viewModel.eqUserPresets.collectAsStateWithLifecycle()
    // AutoEq 预设（与详细页里的"库"是同一份数据，这里作为统一入口）
    val autoEqLibrary by viewModel.autoEqLibrary.collectAsStateWithLifecycle()
    val autoEqSource by viewModel.autoEqSource.collectAsStateWithLifecycle()
    val irLibrary by viewModel.irLibrary.collectAsStateWithLifecycle()
    val favoriteIrs by viewModel.favoriteIrs.collectAsStateWithLifecycle()
    val activeIrName by viewModel.activeRecentIrName.collectAsStateWithLifecycle()

    val favEntries = irLibrary.filter { it.name in favoriteIrs }

    Column(
        // U8i：外层 Tab 容器已 padding(16)，此处不再加（原双 16 叠加导致卡比其他页窄 16dp）
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScCardHeader(
            title = "预设与收藏",
            trailing = {
                Text(
                    // 统计口径：内置场景也算"组合"（否则有 4 个内置时仍显示 "0 个组合"），
                    // 同时补上 IR 收藏数 —— 页面的三类内容都要能一眼看到数量
                    text = "${viewModel.builtinPresets.size + userPresets.size} 个组合 · " +
                        "${eqPresets.size} 个 EQ 预设 · ${favEntries.size} 个脉冲",
                    fontSize = 12.sp,
                    color = ScColors.TextSub,
                )
            },
        )

        // ---- 场景组合：整组效果参数快照（内置 + 用户自定义），青绿渐变卡 ----
        var showSaveDialog by remember { mutableStateOf(false) }
        var presetName by remember { mutableStateOf("") }
        // 内置场景默认**折叠**：它们只读、且 4 张大卡会占满首屏，
        // 把用户自己的组合与下方 EQ 预设 / 收藏脉冲挤到很下面（2026-11-14 用户要求）
        var builtinExpanded by rememberSaveable { mutableStateOf(false) }
        ScCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "场景组合",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextMain,
                    )
                    TextButton(onClick = { showSaveDialog = true }) {
                        Text("＋保存当前", fontSize = 12.sp)
                    }
                }
                userPresets.forEach { preset ->
                    ScSceneCard(
                        preset = preset,
                        isUser = true,
                        onApply = { viewModel.applyPreset(preset) },
                        onDelete = { viewModel.deleteUserPreset(preset.name) },
                    )
                }
                // 空态引导：没有自定义组合时，用户不知道「＋保存当前」能干什么
                if (userPresets.isEmpty()) {
                    Text(
                        text = "还没有自己的组合 —— 点右上「＋ 保存当前」，把当前整套效果" +
                            "（增益 / 压限 / 限幅 / 混响 / 宽度 / 低频管家）存成一组，以后一键切回。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
                // ---- 内置场景：折叠条（默认收起），点整行展开 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { builtinExpanded = !builtinExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "内置场景 ×${viewModel.builtinPresets.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextMain,
                    )
                    Text(
                        text = "  只读预设，可一键套用",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (builtinExpanded) "收起 ▴" else "展开 ▾",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                    )
                }
                if (builtinExpanded) {
                    viewModel.builtinPresets.forEach { preset ->
                        ScSceneCard(
                            preset = preset,
                            isUser = false,
                            onApply = { viewModel.applyPreset(preset) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScPill(text = "参数快照一键应用", variant = ScPillVariant.Teal)
                    Text(
                        text = "一键切换整组效果参数（EQ/压限/限幅/混响/宽度/噪声门）",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
            }
        }
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("保存当前参数为预设") },
                text = {
                    TextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        placeholder = { Text("预设名称") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.saveUserPreset(presetName)
                            presetName = ""
                            showSaveDialog = false
                        },
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
                },
            )
        }

        // ---- 我的 EQ 预设：自定义预设（AutoEq 粘贴导入的也在其中） ----
        ScCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "我的 EQ 预设",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextMain,
                    )
                    // AutoEq 是「参量均衡」的能力（曲线修正），这里必须落到 EQ 详情页。
                    // 历史 bug：此处曾连到 onGoToLibrary = { reverbOpen = true }，
                    // 点它会打开「混响」页，与 AutoEq 毫无关系（2026-11-14 修）。
                    TextButton(onClick = onOpenEq) {
                        Text("AutoEq 导入", fontSize = 12.sp)
                    }
                }
                if (eqPresets.isEmpty()) {
                    Text(
                        // 文案与行为对齐：applyAutoEq 只改写「当前 EQ 段」，
                        // 不会自动生成预设条目（要进这个列表必须先「保存当前」）
                        text = "还没有自定义 EQ 预设 —— 在「均衡器」里点「保存当前」才会出现在这里；" +
                            "直接粘贴 AutoEq 曲线只改当前 EQ，不会自动存成预设。",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                    )
                } else {
                    eqPresets.forEach { preset ->
                        val chipShape = RoundedCornerShape(ScRadius.Chip)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(chipShape)
                                .background(ScColors.Inset)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(chipShape)
                                    .background(ScCardBrush),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "≡", fontSize = 12.sp, color = ScColors.Primary)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            ) {
                                Text(
                                    text = preset.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ScColors.TextMain,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "${preset.gains.size} 段" +
                                        (if (preset.freqs.isNotEmpty()) {
                                            " · ${"%.0f".format(preset.freqs.first())}Hz~${"%.1f".format(preset.freqs.last() / 1000f)}kHz"
                                        } else ""),
                                    fontSize = 10.sp,
                                    color = ScColors.TextSub,
                                )
                            }
                            TextButton(onClick = { viewModel.applyEqUserPreset(preset) }) {
                                Text("应用", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---- 我的 AutoEq 预设：与「均衡器」页里的库同一份数据 ----
        ScCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "我的 AutoEq 预设",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextMain,
                    )
                    TextButton(onClick = onOpenEq) {
                        Text("去导入", fontSize = 12.sp)
                    }
                }
                if (autoEqLibrary.isEmpty()) {
                    Text(
                        text = "还没有 AutoEq 预设 —— 在「均衡器」页导入 ParametricEQ / FixedBandEQ / " +
                            "GraphicEQ / 结果 CSV 后会自动出现在这里。",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                    )
                } else {
                    autoEqLibrary.forEach { curve ->
                        val isActive = curve.name == autoEqSource
                        val chipShape = RoundedCornerShape(ScRadius.Chip)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(chipShape)
                                .background(ScColors.Inset)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(chipShape)
                                    .background(ScCardBrush),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "AE", fontSize = 11.sp, color = ScColors.Primary)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            ) {
                                Text(
                                    text = curve.name + if (isActive) "  · 当前" else "",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) ScColors.Primary else ScColors.TextMain,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "${curve.mode} 段 · preamp ${"%+.1f".format(curve.preampDb)} dB",
                                    fontSize = 10.sp,
                                    color = ScColors.TextSub,
                                )
                            }
                            TextButton(onClick = { viewModel.applyAutoEqCurve(curve.name) }) {
                                Text("应用", fontSize = 12.sp)
                            }
                            TextButton(onClick = { viewModel.deleteAutoEqCurve(curve.name) }) {
                                Text("删除", fontSize = 12.sp, color = Color(0xFFE5484D))
                            }
                        }
                    }
                }
            }
        }

        // ---- 收藏的脉冲：脉冲库行点 ☆ 收藏，这里一键装载 ----
        ScCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "收藏的脉冲",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextMain,
                    )
                    Text(
                        text = "${favEntries.size} 项",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
                if (favEntries.isEmpty()) {
                    Text(
                        text = "暂无收藏——在卷积混响详情页的脉冲库列表行点 ☆ 收藏常用脉冲，这里可一键装载。",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                    )
                } else {
                    favEntries.forEach { entry ->
                        val isActive = entry.name == activeIrName
                        val chipShape = RoundedCornerShape(ScRadius.Chip)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(chipShape)
                                .background(ScColors.Inset)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(chipShape)
                                    .background(if (isActive) ScPrimaryBrush else ScCardBrush),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "IR",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) Color.White else ScColors.TextSub,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            ) {
                                Text(
                                    text = entry.file.removePrefix("ir_"),
                                    fontSize = 12.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = ScColors.TextMain,
                                    maxLines = 1,
                                )
                                if (isActive) {
                                    Text(text = "使用中", fontSize = 10.sp, color = ScColors.Primary)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(ScPrimaryBrush)
                                    .clickable { viewModel.loadLibraryIr(entry) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "▶", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 场景卡：青绿渐变底白字，chips 摘要关键效果开关；用户自定义卡带删除。 */
@Composable
private fun ScSceneCard(
    preset: MainViewModel.EffectPreset,
    isUser: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit = {},
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(ScElevation.L1, shape, clip = false)
            .clip(shape)
            .background(ScPrimaryBrush),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = preset.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                if (isUser) {
                    Text(
                        text = "删除",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presetSceneChips(preset).forEach { chip ->
                    Text(
                        text = chip,
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (preset.builtin) "内置场景" else "自定义快照",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Text(
                    text = "一键应用",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White)
                        .clickable(onClick = onApply)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 预设参数 → 场景卡 chips（只列开启的效果，全关显示「直通」） */
private fun presetSceneChips(preset: MainViewModel.EffectPreset): List<String> {
    val p = preset.params
    val chips = mutableListOf<String>()
    if ((p["eq_enabled"] ?: 0f) == 1f) chips.add("EQ")
    if ((p["compressor_enabled"] ?: 0f) == 1f) chips.add("压限")
    if ((p["limiter_enabled"] ?: 0f) == 1f) chips.add("限幅")
    if ((p["reverb_enabled"] ?: 0f) == 1f) chips.add("混响")
    if ((p["stereo_width_enabled"] ?: 0f) == 1f) {
        chips.add("宽 ${(p["stereo_width"]?.times(100))?.toInt() ?: 100}%")
    }
    if ((p["gate_threshold"] ?: 0f) > 0f) chips.add("噪声门")
    return if (chips.isEmpty()) listOf("直通") else chips
}

// ---------------------------------------------------------------- P5 我的屏

@Composable
fun ScProfileScreen(viewModel: MainViewModel) {
    Column(
        // **不要在这里加 verticalScroll**：
        // 外层 Tab 容器（MainScreen）已经提供了滚动，再套一层会触发
        // `IllegalStateException: Vertically scrollable component was measured with an
        // infinity maximum height constraints` —— 同方向嵌套滚动在 Compose 里是硬约束。
        // 表现是进「我的」页即闪退（栈里能看到两层 ScrollNode）。
        //
        // U8i：外层 Tab 容器已 padding(16)，此处不再加（原双 16 叠加导致卡比其他页窄 16dp）
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // U8y：页头下的小「我的」标签退役（页头已有大标题，重复）
        // U8z：设备卡+引擎健康卡迁音效库顶部（ScDeviceHealthCards），我的页自诊断卡始

        // 【顺序重排 2026-11-09】原来「诊断 → Capture → 设置 → 隐私」：把开发者工具
        // 放在最上面，几张日志占掉大半页，用户改个设置得一直往下翻。
        // 现在按**使用频率**排：设置（最常用）→ 隐私 → Capture → 诊断（折叠）→ 说明。
        ScSettingsSection(viewModel)

        // **诊断与取证紧跟「诊断与工具」**（2026-11-09 用户要求上移）：
        // 设置卡的最后一个分组就是「诊断与工具」（测试正弦），同属排障工具；
        // 原来中间隔着「隐私与合规」「Capture 策略」两张卡，两块诊断内容被拆散了。
        ScDiagSection(viewModel)

        PrivacyComplianceSection(viewModel)

        // **Capture 策略只在 Stellar + MP 下显示**（2026-11-09 用户要求按模式分流）：
        // 它列的是「全机 APP 的 MediaProjection 可抓性」（BY_SYSTEM / BY_NONE / BY_ALL），
        // 是走屏幕录制抓取时才需要判断的东西。Loopback 走 AudioMix 副本、按 mix 整体抓，
        // 不做 per-app 判定，这张表在那儿没有决策价值。
        val audioMode by viewModel.audioMode.collectAsStateWithLifecycle()
        if (audioMode == "stellar_mp") {
            ScCapturePolicySection(viewModel)
        }

        // ---- 内录机制说明 ----
        // 标题**放回卡内**（2026-11-09 用户要求）：这是最后一张说明性质的卡，
        // 不像上面那些分组那样成体系，标题留在卡内反而更像一张「通告」。
        // 字号仍用 15sp Bold（与卡外的 ScSectionTitle 同款），只是位置从卡外移到卡内，
        // 视觉层级不变、不会被误读成新的分组。
        // `⚠` 保留：Android 上默认按 emoji 渲染（黄底黑叹号三角），正是想要的警示效果。
        ScCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "⚠ 内录机制说明",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.TextMain,
                )
                Text(
                    text = "内录只 tap 不拦截：处理后声音会与原声叠加产生\"双声\"，且存在 40–80 ms 延迟，" +
                        "这是 Android 机制性结果，并非 bug。部分 App（如 Spotify、Chrome）默认禁止被内录。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * U8z：设备/配对卡 + 引擎健康四格（原「我的」页首两卡，迁音效库顶部、引擎信息卡上方）。
 * U9a：引擎运行中卡退役——Running pill/引擎副文本迁入设备卡；电源图标=启用/停止内录
 * （启停含权限分支，逻辑由调用方经 onToggleCapture 注入）；Stellar 授权移设置卡。
 */
@Composable
fun ScDeviceHealthCards(viewModel: MainViewModel, onToggleCapture: () -> Unit) {
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val privilegeSummary by viewModel.privilegeSummary.collectAsStateWithLifecycle()
    // Stellar 胶囊三态（就绪 / 待授权 / 未安装），点击直达授权或下载页
    val privState by viewModel.privilegeState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val breatheAlpha = rememberInfiniteTransition(label = "stellarBreathe").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "stellarBreatheAlpha",
    ).value

    // ---- 设备/配对卡（U9d：引擎健康四格并入本卡，独立健康卡退役；U9g/U9h 高度收窄对齐下方卡） ----
    ScCard {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        // 窄屏时收紧间距与胶囊列上限，给左侧标题留出足够宽度
        // （360dp 屏幕实测：原 12dp 间距 + 132dp 胶囊 → 左列仅 ~104dp，
        //  「Sonicraft DSP」会被省略成「Sonicraft…」）
        // 360dp 整档也要算紧凑（wm density 540 实测 screenWidthDp 正好 = 360）
        val compactScreen = LocalConfiguration.current.screenWidthDp <= 360
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compactScreen) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // U9b：Z 头像位改为电源启停按钮（开启=星云紫蓝渐变，停止=灰）；U9d 图标加大
            // 建链期间（0.2~4s）呼吸闪烁 + 禁用点击，避免「以为没点上」反复点
            val chainStarting by viewModel.chainStarting.collectAsStateWithLifecycle()
            val pulse = rememberInfiniteTransition(label = "powerPulse").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "powerPulseAlpha",
            ).value
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .alpha(if (chainStarting) pulse else 1f)
                    .clickable(enabled = !chainStarting) { onToggleCapture() },
                contentAlignment = Alignment.Center,
            ) {
                // 高斯模糊光晕：同一图标先画一份模糊副本垫底，
                // 再叠清晰的一份 —— 形成发光效果（Modifier.blur 需 API 31+，本机 36 可用）
                if (chainStarting) {
                    // **加载中：橙色闪烁**（2026-11-11 用户要求）。
                    // 外层 Box 已用 `pulse` 做 1↔0.3 的 alpha 呼吸（600ms），
                    // 这里只需把颜色从「运行星云紫蓝 / 停止灰」切换成琥珀橙，
                    // 并去掉星云画刷（brush 传 null → 整个图标统一 tint）。
                    // chainStarting 落回 false 时自动回到下面原有两个分支，颜色不变。
                    ScPowerIcon(
                        tint = ScColors.Warn,
                        modifier = Modifier.size(46.dp).blur(12.dp).alpha(0.55f),
                    )
                    ScPowerIcon(
                        tint = ScColors.Warn,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (viewModel.isRunning) {
                    ScPowerIcon(
                        tint = ScColors.NebCore,
                        brush = ScStarBrush,
                        modifier = Modifier.size(46.dp).blur(12.dp).alpha(0.55f),
                    )
                    ScPowerIcon(
                        tint = ScColors.NebCore,
                        brush = ScStarBrush,
                        modifier = Modifier.size(36.dp),
                    )
                } else {
                    ScPowerIcon(
                        tint = ScColors.TextSub,
                        modifier = Modifier.size(44.dp).blur(10.dp).alpha(0.45f),
                    )
                    ScPowerIcon(
                        tint = ScColors.TextSub,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                // 单行省略：窄屏机型名很长时不折行（V2408A 截图实测折 2~3 行）
                ScTextOneLine(
                    text = "Sonicraft DSP",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ScColors.TextMain,
                    // 窄屏自动缩字号而不是省略（放得下时仍是 18sp）
                    autoShrink = true,
                )
                ScTextOneLine(
                    text = "${Build.MODEL} · ${SysInfo.romName} · Android ${SysInfo.androidVersion}",
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
                // U9c：引擎格式描述（真实引擎格式常量：全链路 float32 → 32 位）
                val engineFmt = AudioFormat.STEREO_48K
                ScTextOneLine(
                    text = "${engineFmt.sampleRate / 1000}kHz · 32位 · " + when (engineFmt.channelCount) {
                        1 -> "单声道"
                        2 -> "立体声"
                        else -> "多声道"
                    },
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
                // ：当前输出设备角标——一眼看到「音效正往哪出」
                val outLabel = viewModel.currentOutput.collectAsStateWithLifecycle().value.label
                ScTextOneLine(
                    text = "输出：$outLabel",
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
                // 路由信息已下架（2026-11-06 用户要求）：`port=NNN · 命中/未命中`
                // 对普通用户没有意义、反而造成困惑。诊断仍保留在 diag 日志里
                // （`重路由校验: ...` / `takeover_diag`），排查时看日志即可。
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // U9h：IntrinsicSize.Max 让 Running pill 撑到与 Stellar 标签同宽；
                // widthIn 上限防窄屏把左侧信息挤成 1 字宽（2026-11-07 响应式）
                modifier = Modifier.width(IntrinsicSize.Max)
                    .widthIn(max = if (compactScreen) 100.dp else 132.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // U9a：Running pill（自引擎运行中卡迁入，Stellar 标签上方）；U9b 电源图标迁 Z 位
                    ScPill(
                        text = when {
                            // **闪烁期间显示 Loading**（2026-11-06 用户要求）：
                            // 只要电源键还在呼吸闪烁（chainStarting），就不提前显示运行模式，
                            // 避免"显示已就绪但其实还在建链"的误导。
                            chainStarting -> "Loading"
                            // 闪烁结束、引擎 Running 之后，才按实际链路模式显示
                            // 用户要求改中文术语：LOOP → 系统回环、MP+DP → 录制授权。
                            // 只改 UI 文案，内部模式标识（"loopback" / MP / DP）一律不动。
                            engineState is EngineState.Running ->
                                if (viewModel.isLoopbackMode()) "系统回环" else "录制授权"
                            engineState is EngineState.Suspended -> "Suspended"
                            engineState is EngineState.Preparing -> "Loading"
                            engineState is EngineState.Ready -> "Ready"
                            else -> "Idle"
                        },
                        // 配色：Loading 阶段用中性色（避免"绿色=已就绪"的误读）；
                        // 真正 Running 后才用与「Stellar 已配对」一致的 Good 色
                        variant = if (!chainStarting &&
                            (engineState is EngineState.Running ||
                                engineState is EngineState.Suspended)
                        ) {
                            ScPillVariant.Good
                        } else {
                            ScPillVariant.Neutral
                        },
                        showDot = true,
                        elevated = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 路由行已移到左侧信息区（48kHz 下方），此处不再重复
                // Stellar 胶囊：三态 + 可点击直达
                //   已配对 → 绿，不可点
                //   待授权 → 黄，点击拉起 Stellar 客户端（省得去设置页翻）
                //   未安装 → 红呼吸，点击浏览器打开下载页（必需依赖，中性引导）
                val stellarReady = privState.permissionGranted
                val stellarInstalled = privState.stellarInstalled
                ScPill(
                    text = when {
                        stellarReady -> "Stellar 已配对"
                        stellarInstalled -> "Stellar 待授权"
                        else -> "Stellar 未安装"
                    },
                    variant = when {
                        stellarReady -> ScPillVariant.Good
                        stellarInstalled -> ScPillVariant.Warn
                        else -> ScPillVariant.Danger
                    },
                    // U9i：按上方 Running pill 样式包裹（L1 阴影 + 受光高光描边）
                    elevated = true,
                    modifier = Modifier
                        // 仅「未安装」呼吸：持续可见但不刺眼；待授权用静态黄色即可
                        .alpha(if (stellarInstalled) 1f else breatheAlpha)
                        .clickable(enabled = !stellarReady) {
                            if (stellarInstalled) {
                                // 拉起客户端去授权；返回后由 MainScreen 的 ON_RESUME 重检
                                runCatching { StellarHelper.openManager(context) }
                            } else {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(STELLAR_DOWNLOAD_URL))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        },
                )
            }
        }

        // U9d：引擎健康四格并入设备卡（独立健康卡退役；状态字省略——Running pill 已表达）
        // U9e：「引擎健康」小标签退役，分隔线保留
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ScColors.Divider),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ScHealthCell(
                value = "%.3fms".format(stats.lastProcessMs),
                label = "单帧耗时",
                color = ScColors.TextMain,
            )
            ScHealthCell(
                value = "${stats.xrunCount}",
                label = "xRun/欠载",
                color = if (stats.xrunCount == 0L) ScColors.Primary else ScColors.Danger,
            )
            ScHealthCell(value = "${stats.waterMarkFrames}", label = "缓冲水位", color = ScColors.TextMain)
            ScHealthCell(
                value = "%.0f%%".format(stats.dspCpuLoadPercent),
                // 原名「CPU 载荷」歧义大：用户会以为手机 CPU 快跑满。
                // 实际含义是「单帧 DSP 处理耗时 / 实时预算」＝DAW 里的 DSP load。
                label = "DSP 负载",
                    color = ScColors.TextMain,
                )
            }
        }
    }
}

@Composable
private fun ScHealthCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 10.sp, color = ScColors.TextSub)
    }
}

/** 诊断段（M7）：探针文件读取 + 抓 30s 取证（原主页诊断卡整体迁入 P5） */
@Composable
private fun ScDiagSection(viewModel: MainViewModel) {
    // **默认折叠**（2026-11-09 布局梳理）：这块是开发者工具，展开时有几十行日志
    // 加 8 个按钮，能占掉大半屏，把设置挤到需要翻好几屏的位置。
    // 但报障时要能一键导出，所以「导出」留在标题行，不随折叠隐藏。
    var expanded by remember { mutableStateOf(false) }
    // 统一用 ScCard（2026-11-09 用户反馈「边框跟上方不一致」）：
    // Material `Card` 只有 elevation 阴影，没有 ScCard 那层 1dp 高光描边与渐变底，
    // 圆角半径也不同（默认 12dp vs ScRadius.Card）。换过来后整页卡片外观一致。
    // 注意 ScCard 内部已自带 16dp 内边距，所以去掉这里原来的 padding(16.dp)。
    ScCard(modifier = Modifier.fillMaxWidth()) {
        val diag by viewModel.diag.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "诊断与取证", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (expanded) "▾" else "▸",
                        fontSize = 13.sp,
                        color = ScColors.TextSub,
                    )
                }
                // 正式包不可 run-as、本机 logcat 又被 ROM 完全禁用，
                // 必须给用户一条能把日志发出来的路径，否则线上报障无从排查
                TextButton(onClick = { viewModel.exportDiagnostics() }) {
                    Text("导出", fontSize = 13.sp)
                }
            }
            // 收起态给一行摘要：不展开也能看出「有没有异常、要不要点进去」
            if (!expanded) {
                val d0 = diag
                Text(
                    text = if (d0 == null) "展开可读取探针文件、抓 30s 取证、Loopback 探针"
                    else "输出流重建 ${d0.streamErrorCount} 次 · 展开查看详情",
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
            }
            if (expanded) {
            val exportMsg by viewModel.diagnosticsMessage.collectAsStateWithLifecycle()
            exportMsg?.let {
                Text(text = it, fontSize = 11.sp, color = ScColors.TextSub)
            }
            val d = diag
            if (d == null) {
                Text(
                    "点「刷新」读取 files/crash/ 探针文件（stream error 重建 / 捕获统计 / 水位欠载）",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "输出流 error/重建（近期）：${d.streamErrorCount} 次",
                    fontSize = 13.sp,
                )
                if (d.rebuildRecent.isNotEmpty()) {
                    Text(d.rebuildRecent, fontSize = 11.sp)
                }
                Text("捕获累计（最新）：${d.captureLatest}", fontSize = 11.sp)
                Text("水位欠载（最新）：${d.watermarkLatest}", fontSize = 11.sp)
                Text(
                    "判定：error/重建 = 路由切换（微信来电/消息）；zeroReads/errorReads 恒 0 = 捕获健康；underrun/xRun 恒 0 = DSP 无欠载",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { viewModel.captureAudioSample() }) {
                    Text("抓 30s 输入/输出（取证）", fontSize = 13.sp)
                }
                TextButton(onClick = { viewModel.clearCaptureBlocklist() }) {
                    Text("清除禁抓名单", fontSize = 13.sp)
                }

                // ---- Loopback 探针（2026-11-04 P0）：AudioPolicy/AudioMix(LOOP_BACK) 路线验证 ----
                val lbState by viewModel.loopbackProbeState.collectAsStateWithLifecycle()
                val lbLevel by viewModel.loopbackProbeLevel.collectAsStateWithLifecycle()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.startLoopbackProbe() }) {
                        Text("Loopback 探针", fontSize = 13.sp)
                    }
                    // P1：把 Loopback PCM 接进 DSP 引擎（验证链路出声；不做原声静音）
                    TextButton(onClick = { viewModel.startLoopbackCapture() }) {
                        Text("Loopback 引擎", fontSize = 13.sp)
                    }
                    TextButton(onClick = { viewModel.stopLoopbackProbe() }) {
                        Text("停止", fontSize = 13.sp)
                    }
                }
                // 「DP 静音实验」与「按UID电平」已移除：
                // 两者都是 2026-11-05 P2 阶段用来定性「某条抓取路线是否可行」的
                // **一次性实验**——DP 那条甚至硬编码了酷狗包名。结论已经拿到
                // （项目最终走 Loopback + Stellar MP，没有采用按 uid 分 mix 的方案），
                // 实验本身不再有重复执行的价值。
                // 而且「按UID电平」有破坏性副作用：它复用同一个 UserService record，
                // 会 stop 当前主链路，测完必须手动重新点「Loopback 引擎」才能恢复出声。
                // 留在诊断卡里既占地方、又容易被误点。
                lbState?.let { Text(it, fontSize = 11.sp) }
                // 上次进程被强杀遗留的 DP 静音效果（跨进程无法回收，只能重启）
                val residue by viewModel.residueWarning.collectAsStateWithLifecycle()
                residue?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Loopback 电平：${"%.4f".format(lbLevel)}" +
                        if (lbLevel > 0.001f) "（抓到流）" else "（静音）",
                    fontSize = 12.sp,
                    color = if (lbLevel > 0.001f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                val capMsg by viewModel.audioCaptureMsg.collectAsStateWithLifecycle()
                capMsg?.let { Text(it, fontSize = 11.sp) }
            }
            }   // 结束 if (expanded)
        }
    }
}

/** Capture 策略段（2026-11-04 酷狗 opt-out 定性产物）：全机第三方 APP 投影可抓性一览。
 *  数据源 MainViewModel.scanCapturePolicies()（Stellar 特权 dumpsys audio——公开 API
 *  只能读自身）。BY_SYSTEM/BY_NONE=内录抓不到=恒零=只能走自动直出名单（酷狗实锤
 *  uid10514 BY_SYSTEM）；BY_ALL=可抓副本=DSP 链正常前提。 */
private data class CapPolicyRow(val pkg: String, val uid: Int, val policy: Int)

@Composable
private fun ScCapturePolicySection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<CapPolicyRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var showCapturable by remember { mutableStateOf(false) }
    // 同上：与上方设置区的卡片统一外观（ScCard 自带 16dp 内边距）
    ScCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Capture 策略（全机 APP）", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    if (scanning) return@TextButton
                    scanning = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { viewModel.scanCapturePolicies() }
                        rows = res.rows.map { CapPolicyRow(it.first, it.second, it.third) }
                        error = res.error
                        scanning = false
                    }
                }) {
                    Text(if (scanning) "扫描中…" else "扫描", fontSize = 13.sp)
                }
            }
            Text(
                "BY_ALL=内录可抓副本；BY_SYSTEM/BY_NONE=抓不到=播放恒零走自动直出",
                style = MaterialTheme.typography.bodySmall,
            )
            error?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            val r = rows
            if (r == null) {
                Text(
                    "点「扫描」读取全机第三方 APP 的投影抓取策略",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val blocked = r.filter { it.policy != AudioAttributes.ALLOW_CAPTURE_BY_ALL }
                val ok = r.filter { it.policy == AudioAttributes.ALLOW_CAPTURE_BY_ALL }
                if (error == null && blocked.isEmpty()) {
                    Text(
                        "抓不到：无（全机第三方 APP 都可抓）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (blocked.isNotEmpty()) {
                    Text(
                        "抓不到（${blocked.size} 个）——播放时内录恒零，会进自动直出名单：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    blocked.forEach {
                        Text(
                            "  ${it.pkg}  uid=${it.uid}  " +
                                if (it.policy == AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM) "BY_SYSTEM" else "BY_NONE",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { showCapturable = !showCapturable }) {
                    Text(
                        if (showCapturable) "收起可抓列表（${ok.size} 个）" else "展开可抓列表（${ok.size} 个）",
                        fontSize = 13.sp,
                    )
                }
                if (showCapturable) {
                    ok.forEach { Text("  ${it.pkg}  uid=${it.uid}", fontSize = 11.sp) }
                }
            }
        }
    }
}

/**
 * 设备绑定 UI 开关（M14，2026-11-08 用户决定：先隐藏）。
 *
 * 当前产品语义是「切换设备自动恢复该设备/该类型最近一次调整的参数」，绑定属于可选的
 * "固定某套"能力。先隐藏入口、完整保留实现（ViewModel 接口 + 存储 + 解析回落），
 * 若后续有用户反馈需要固定，把本开关改 true 即可恢复。
 */
private const val SHOW_DEVICE_BIND_UI = false

/**
 * Stellar 客户端下载页（2026-11-08，用户指定的 Gitee release 锚点）。
 * 仅在「本机未安装 Stellar」时被拉起：功能依赖该框架，故下载入口属于必需依赖引导，
 * 文案保持中性（「未安装」而非「推荐安装」），避免商店审核的诱导安装条款。
 */
private const val STELLAR_DOWNLOAD_URL =
    "https://gitee.com/su-su2239/Stellar/releases/#release-v1.1(101000)"

/**
 * 小型二选一 chip（2026-11-11，供压缩器「高级」区的检测模式 / 过采样使用）。
 * 与设置页「链路模式」的 chip 同款语言：选中为实心主题色 + 白字。
 */
@Composable
private fun ScAdvChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) Color.White else ScColors.TextSub,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) ScColors.Primary else ScColors.Inset)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * 卡外分组大标题（2026-11-09，参照 wecho 设置页）。
 *
 * wecho 的分组标题在**卡片之外**，字号明显大于行标题，形成「章 / 节」两级层次。
 * Sonicraft 原来把 11sp 的小字标题放进卡内，层级与行标题打架，整块读起来是平的。
 */
@Composable
internal fun ScSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = ScColors.TextMain,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** 行间分割线：1dp 极淡，条条通到卡片内边距（不做左侧缩进，与 wecho 一致） */
@Composable
private fun ScSettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ScColors.TextSub.copy(alpha = 0.08f)),
    )
}

/**
 * 设置行：左「标题 + 副标题」、右「控件」（wecho `ListTile` 的 Kotlin 版）。
 * 整行可点（传 onClick 时），末尾按需画分割线。
 */
@Composable
private fun ScSettingRow(
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, color = ScColors.TextMain)
                subtitle?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing()
        }
        if (showDivider) ScSettingDivider()
    }
}

/**
 * 设置块：标题在上、内容**整行**在下。
 * 用于滑块、模式 chip 组这类右侧放不下、需要整行宽度的控件。
 */
@Composable
private fun ScSettingBlock(
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = 11.dp)) {
            Text(text = title, fontSize = 14.sp, color = ScColors.TextMain)
            subtitle?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(modifier = Modifier.padding(bottom = 6.dp)) { content() }
        if (showDivider) ScSettingDivider()
    }
}

/** 设置段（原 SettingsCard 设置区整体迁入 P5）：接管/排除/门限/测试正弦 + 应用选择器 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScSettingsSection(viewModel: MainViewModel) {
    val excluded by viewModel.excludedPackages.collectAsStateWithLifecycle()
    val takeover by viewModel.takeoverPackages.collectAsStateWithLifecycle()
    val autoTakeover by viewModel.autoTakeoverEnabled.collectAsStateWithLifecycle()
    val takeoverPrivilegeError by viewModel.takeoverPrivilegeError.collectAsStateWithLifecycle()
    val gateThreshold by viewModel.gateThreshold.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val audioMode by viewModel.audioMode.collectAsStateWithLifecycle()
    val bufferMs by viewModel.bufferMs.collectAsStateWithLifecycle()
    val deviceAdaptive by viewModel.deviceAdaptive.collectAsStateWithLifecycle()
    val currentOutput by viewModel.currentOutput.collectAsStateWithLifecycle()
    var testTone by remember { mutableStateOf(false) }
    var pickerMode by remember { mutableStateOf<AppPickMode?>(null) }
    val running = engineState is EngineState.Running || engineState is EngineState.Suspended

    // ============ 界面缩放（2026-11-09，移植 wecho 定制版）============
    // wecho 是三级：autoScale(按 440dp 基准自动算) × uiScale(粗调) × uiFineScale(微调)。
    // 这里把三者的乘积直接显示出来，用户改滑块就能看到「总 X%」跟着动。
    val uiScale by viewModel.uiScale.collectAsStateWithLifecycle()
    val uiFineScale by viewModel.uiFineScale.collectAsStateWithLifecycle()

    // **拖动只改本地值，松手才提交**：
    // 应用缩放 = 覆盖 LocalDensity = 整棵树重新测量与布局，是一次全局操作。
    // 若在 onValueChange 里直接提交，拖动时每帧都触发全量重排，
    // 输入事件被挤压 → 滑块跟不上手指，用户感受到的就是「卡、不连续」。
    // 改成 pending：拖动期间仅更新本块内的数字（重组范围极小），
    // 松手（onValueChangeFinished）才真正写入 StateFlow 应用一次。
    var pendingScale by remember { mutableStateOf<Float?>(null) }
    var pendingFine by remember { mutableStateOf<Float?>(null) }
    val shownScale = pendingScale ?: uiScale
    val shownFine = pendingFine ?: uiFineScale

    // 与 MainScreen 同口径：**按短边**算自动缩放（横屏时短边=高，不会顶到 1.3 上限）
    val cfg = LocalConfiguration.current
    val minSideDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat()
    val autoScale = (minSideDp / 440f).coerceIn(0.85f, 1.3f)
    // 预览值：拖动时就按新倍率算，用户能看到「松手后会变成多少」
    val shownTotal = (autoScale * shownScale * shownFine).coerceIn(0.68f, 1.69f)
    val totalHint = "总 ${(shownTotal * 100).roundToInt()}%" +
        if (pendingScale != null || pendingFine != null) "（松手生效）" else ""

    ScSectionTitle("界面缩放")
    ScCard {
        Column {
            ScSettingBlock(
                title = "缩放倍率",
                subtitle = "设计基准 440dp · 设备自动 ${(autoScale * 100).roundToInt()}% · " +
                    "$totalHint",
            ) {
                Slider(
                    value = shownScale.coerceIn(0.8f, 1.3f),
                    onValueChange = { pendingScale = it },
                    onValueChangeFinished = {
                        pendingScale?.let { viewModel.setUiScale(it) }
                        pendingScale = null
                    },
                    valueRange = 0.8f..1.3f,
                    // 不设 steps：刻度点会让拖动一格一格吸附
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ScSettingBlock(
                title = "微调",
                subtitle = "在粗调基础上 ±10%，可细抠到 1% 一档",
                showDivider = false,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${(shownFine * 100).roundToInt()}%",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "重置微调",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { viewModel.resetUiFineScale() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Slider(
                        value = shownFine.coerceIn(0.9f, 1.1f),
                        onValueChange = { pendingFine = it },
                        onValueChangeFinished = {
                            pendingFine?.let { viewModel.setUiFineScale(it) }
                            pendingFine = null
                        },
                        valueRange = 0.9f..1.1f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    ScSectionTitle("音频链路")
    ScCard {
        Column {
            ScSettingBlock(
                title = "链路模式",
                // 副标题去掉（2026-11-09 用户要求）：两个 chip 本身已经写明了模式名，
                // 下面再重复一行「Loopback（默认，无弹窗）」是冗余。
            ) {
                // 两个 chip **居中**（原来靠左）：本行只有两项，靠左会让右侧空一大块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    for ((label, mode) in listOf("Loopback" to "loopback", "Stellar + MP" to "stellar_mp")) {
                        val active = audioMode == mode
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) Color.White else ScColors.TextSub,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) ScColors.Primary else ScColors.Inset)
                                .clickable { viewModel.setAudioMode(mode) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            ScSettingBlock(
                title = "输出缓冲",
                subtitle = "当前 ${bufferMs}ms" +
                    if (bufferMs <= 6) "（低延迟，可能偶发爆音）"
                    else if (bufferMs >= 16) "（最稳，延迟略高）"
                    else "（推荐）",
                showDivider = false,
            ) {
                Slider(
                    value = bufferMs.toFloat(),
                    onValueChange = { viewModel.setBufferMs(it.roundToInt()) },
                    valueRange = 4f..24f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // ============ 输出设备 ============
    ScSectionTitle("输出设备")
    ScCard {
        Column {
            ScSettingRow(
                title = "扬声器/耳机自适应",
                subtitle = if (deviceAdaptive) "切换设备自动恢复该设备最近一次调好的参数"
                else "关闭为聚合模式：所有设备共用当前配置",
            ) {
                ScSwitch(
                    checked = deviceAdaptive,
                    onCheckedChange = { viewModel.setDeviceAdaptive(!deviceAdaptive) },
                )
            }
            ScSettingRow(
                title = "当前输出",
                subtitle = "未绑定的设备沿用最近一次参数；同类型多台设备互不干扰",
                showDivider = false,
            ) {
                Text(text = currentOutput.label, fontSize = 12.sp, color = ScColors.Primary)
            }
        }
    }

    // ============ 应用接管（仅 Stellar + MP） ============
    if (audioMode == "stellar_mp") {
        ScSectionTitle("应用接管")
        ScCard {
            Column {
                ScSettingRow(
                    title = "自动接管",
                    subtitle = if (autoTakeover) "播放媒体的原声自动静音，由引擎替代"
                    else "双声模式：原声与引擎输出同时出声",
                    showDivider = autoTakeover && takeoverPrivilegeError != null,
                ) {
                    ScSwitch(
                        checked = autoTakeover,
                        onCheckedChange = { viewModel.toggleAutoTakeover() },
                    )
                }
                // 接管失效时的补救入口（不是常驻按钮，仅在出错时出现）
                if (autoTakeover && takeoverPrivilegeError != null) {
                    ScSettingRow(
                        title = "接管已失效",
                        subtitle = takeoverPrivilegeError,
                        showDivider = true,
                        onClick = viewModel::requestStellarPermission,
                    ) {
                        Text(text = "重新授权 ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                ScSettingRow(
                    title = "手动接管应用",
                    subtitle = if (takeover.isEmpty()) "未选择"
                    else "已接管 ${takeover.size} 个",
                    showDivider = false,
                    onClick = { pickerMode = AppPickMode.TAKEOVER },
                ) {
                    Text(text = "›", fontSize = 18.sp, color = ScColors.TextSub)
                }
            }
        }
    }

    // ============ 抓取控制（公共） ============
    ScSectionTitle("抓取控制")
    ScCard {
        Column {
            ScSettingRow(
                title = "排除应用",
                subtitle = if (excluded.isEmpty()) "未选择"
                else "已排除 ${excluded.size} 个（这些 app 的播放不被捕获）",
                onClick = { pickerMode = AppPickMode.EXCLUDE },
            ) {
                Text(text = "›", fontSize = 18.sp, color = ScColors.TextSub)
            }
            ScSettingRow(
                title = "静音门限",
                subtitle = "见音效库噪声门滑条",
                showDivider = false,
            ) {
                Text(
                    text = if (gateThreshold <= 0f) "禁用" else "%.4f RMS".format(gateThreshold),
                    fontSize = 12.sp,
                    color = ScColors.TextSub,
                )
            }
        }
    }

    // ============ 诊断与工具 ============
    ScSectionTitle("诊断与工具")
    ScCard {
        Column {
            ScSettingRow(
                title = if (testTone) "停止测试正弦" else "测试正弦",
                subtitle = if (testTone) "当前：440Hz 接管输出"
                else if (!running) "需先启动引擎"
                else "验证引擎输出通路",
                showDivider = false,
                onClick = if (running) {
                    {
                        testTone = !testTone
                        viewModel.setTestTone(testTone)
                    }
                } else null,
            ) {
                Text(
                    text = if (testTone) "播放中" else "›",
                    fontSize = if (testTone) 12.sp else 18.sp,
                    color = if (testTone) ScColors.Primary else ScColors.TextSub,
                )
            }
        }
    }


    pickerMode?.let { mode ->
        AppPickDialog(
            viewModel = viewModel,
            mode = mode,
            excluded = excluded,
            takeover = takeover,
            onDismiss = { pickerMode = null },
        )
    }
}

// ---------------------------------------------------------------- 脉冲库内容区（U8k：原 P3 搜索 Tab 整体迁入 P2 卷积详情页）
// 原 ScSearchScreen 独立页退役（底部 Tab 四变三）：脉冲库常驻卷积详情页「脉冲预设」上方，
// 选 IR / 导入 / 收藏无需切 Tab。语义沿用：点按装载 / 星标收藏 / 一键清空。
// U8l：「最近使用」列表删除（用户认定冗余展示；recentIr 队列数据层保留）。

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScIrLibraryContent(viewModel: MainViewModel) {
    val irLibrary by viewModel.irLibrary.collectAsStateWithLifecycle()
    val favoriteIrs by viewModel.favoriteIrs.collectAsStateWithLifecycle()
    val userIrMessage by viewModel.userIrMessage.collectAsStateWithLifecycle()
    val reverbPreset by viewModel.reverbPreset.collectAsStateWithLifecycle()
    val reverbEnabled by viewModel.reverbEnabled.collectAsStateWithLifecycle()
    val activeRecentIrName by viewModel.activeRecentIrName.collectAsStateWithLifecycle()
    // U8l：当前 IR 体检分析（active 行体检 pill）；最近使用列表已删除
    val userIrAnalysis by viewModel.userIrAnalysis.collectAsStateWithLifecycle()

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importZip(uri)
    }
    val filesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }

    var irQuery by remember { mutableStateOf("") }
    val visibleIrLibrary = if (irQuery.isBlank()) irLibrary else irLibrary.filter {
        it.name.contains(irQuery.trim(), ignoreCase = true)
    }
    val rowShape = RoundedCornerShape(ScRadius.Float)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // U8o：脉冲库整区包 HL-1 白卡（标签/搜索/导入导出/列表一体），与页面其他区块视觉统一
        // U8v：卡内间距紧凑化（元素不动，垂直 padding 12→10、行距 10→6），整卡收窄
        ScCard(modifier = Modifier.fillMaxWidth(), elevated = false) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        // 区块头（U8k 迁入 P2 后由整页页头降级为区块头行，对齐「参数」卡头字号）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = "脉冲库",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.TextMain,
                )
                Text(
                    text = if (irLibrary.isEmpty()) {
                        "尚无脉冲文件"
                    } else {
                        val totalMb = irLibrary.sumOf { it.sizeBytes } / 1048576.0
                        "${irLibrary.size} 个文件 · %.1fMB · 点按装载".format(totalMb)
                    },
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        // 凹面搜索框（设计稿 P3 常驻；U3 凹面形态延续；U8v 高度收窄）
        ScInset(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                BasicTextField(
                    value = irQuery,
                    onValueChange = { irQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = ScColors.TextMain),
                    cursorBrush = Brush.verticalGradient(
                        listOf(ScColors.Primary, ScColors.Primary),
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (irQuery.isEmpty()) {
                                Text(
                                    text = "检索脉冲文件…",
                                    fontSize = 13.sp,
                                    color = ScColors.TextSub,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (irQuery.isNotEmpty()) {
                    Text(
                        text = "清除",
                        fontSize = 11.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .clip(rowShape)
                            .clickable { irQuery = "" }
                            .padding(start = 8.dp),
                    )
                }
            }
        }
        // U8p：清空库并入按钮行（原区块头右侧「一键清空」），四按钮统一凸面胶囊样式
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScSoftButton(
                text = "清空库",
                onClick = { viewModel.clearLibraryIr() },
                danger = true,
                modifier = Modifier.weight(1f),
            )
            ScSoftButton(
                text = "导入文件",
                onClick = {
                    filesPicker.launch(
                        arrayOf("audio/wav", "audio/x-wav", "application/octet-stream", "*/*"),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ScSoftButton(
                text = "导入压缩包",
                onClick = {
                    zipPicker.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ScSoftButton(
                text = "导出全部",
                onClick = { viewModel.exportAllToDownloads() },
                modifier = Modifier.weight(1f),
            )
        }
        // 导入进度：批量导入大包/多文件时逐条反馈，
        // 否则用户点完只看到界面不动，容易以为没生效而重复点击
        val irProgress by viewModel.irImportProgress.collectAsStateWithLifecycle()
        if (irProgress.active) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(
                    text = buildString {
                        append("正在导入 ")
                        if (irProgress.total > 0) append("${irProgress.done}/${irProgress.total}") else append("…")
                        if (irProgress.current.isNotBlank()) append("：${irProgress.current}")
                    },
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { irProgress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
        // 空态（U3 虚线卡）/ 无匹配 / 全部文件卡（设计稿 P3）
        if (irLibrary.isEmpty()) {
            ScInset(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                        drawRoundRect(
                            color = ScColors.Divider,
                            topLeft = Offset(1.5f, 1.5f),
                            size = androidx.compose.ui.geometry.Size(size.width - 3f, size.height - 3f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(ScRadius.Inset.toPx()),
                            style = stroke,
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 22.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "专用目录为空",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextSub,
                    )
                    Text(
                        text = "用上方「导入文件 / 导入压缩包」装载脉冲响应（.wav / .irs / .zip）",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
            }
        } else {
            // U8n：全部文件列表可折叠（默认收起，点头行切换；搜索输入时自动展开防「搜不到」错觉）
            var irListExpanded by remember { mutableStateOf(false) }
            val irListShown = irListExpanded || irQuery.isNotBlank()
            // U8o：列表并入外层脉冲库白卡，不再嵌套独立卡
            Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { irListExpanded = !irListExpanded }
                            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = "全部文件 · ${irLibrary.size}",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (irListShown) "收起 ▲" else "展开 ▼",
                            fontSize = 10.sp,
                            color = ScColors.Primary,
                        )
                    }
                    if (irListShown) LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        items(visibleIrLibrary, key = { it.file }) { entry ->
                            val active = reverbPreset == 5 && reverbEnabled &&
                                entry.name == activeRecentIrName
                            val isFav = entry.name in favoriteIrs
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (active) {
                                            Modifier
                                                .padding(horizontal = 6.dp)
                                                .background(ScColors.Inset, rowShape)
                                                .border(
                                                    1.5.dp,
                                                    Brush.linearGradient(
                                                        listOf(ScColors.Primary, ScColors.PrimaryLight),
                                                    ),
                                                    rowShape,
                                                )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { viewModel.loadLibraryIr(entry) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                            ) {
                                // IR 图标位（设计稿 lrow）：激活=青绿渐变底白字，未激活=凹面灰
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(ScRadius.Chip))
                                        .then(
                                            if (active) {
                                                Modifier.background(ScPrimaryBrush)
                                            } else {
                                                Modifier.background(ScColors.Inset)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "IR",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) Color.White else ScColors.TextSub,
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                ) {
                                    Text(
                                        // 完整文件名（含后缀，去 ir_ 旧前缀）——区分 .irs/.wav 来源格式
                                        text = entry.file.removePrefix("ir_") + if (active) "  ✓" else "",
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = ScColors.TextMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // U6 行副文本：文件大小（设计稿 lrow 副文本；通道/时长需逐文件解析，不做）
                                    Text(
                                        text = when {
                                            entry.sizeBytes >= 1048576 -> "%.2fMB".format(entry.sizeBytes / 1048576.0)
                                            else -> "%.0fKB".format(entry.sizeBytes / 1024.0)
                                        },
                                        fontSize = 10.sp,
                                        color = ScColors.TextSub,
                                    )
                                }
                                // U6 体检 pill：仅当前激活 IR 有真实分析数据（userIrAnalysis[1038]=risk），不造假
                                if (active) {
                                    val risk = userIrAnalysis?.getOrNull(1038)?.toInt() ?: 0
                                    ScPill(
                                        text = when {
                                            risk <= 0 -> "体检通过"
                                            else -> "病态警示"
                                        },
                                        variant = when {
                                            risk <= 0 -> ScPillVariant.Good
                                            else -> ScPillVariant.Warn
                                        },
                                    )
                                }
                                // U4 星标收藏
                                Text(
                                    text = if (isFav) "★" else "☆",
                                    color = if (isFav) ScColors.Star else ScColors.TextSub,
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .clip(rowShape)
                                        .clickable { viewModel.toggleIrFavorite(entry.name) }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
            }
            if (visibleIrLibrary.isEmpty()) {
                Text(
                    text = "无匹配「${irQuery.trim()}」的条目",
                    fontSize = 12.sp,
                    color = ScColors.TextSub,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        }
        }
        userIrMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = ScColors.Primary,
            )
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(4000)
                viewModel.consumeUserIrMessage()
            }
        }
    }
}

// ---------------------------------------------------------------- P2 卷积混响详情页
// U5：自 MainScreen SettingsCard 独立成页（设计稿 P2：返回头 + 当前效果卡 +
// 预设横滑 + 参数旋钮卡 + 诊断卡）。语义沿用原卷积区全部功能。

/**
 * 多段压限的单段电平表组（2026-11-08，按用户设计稿）：IN / GR / OUT 三条竖条。
 * IN/OUT 为线性峰值（换算 dB = 20log10），GR 为该段当前增益衰减（负 dB，压得越深条越高）。
 */
@Composable
private fun ScBandMeterGroup(
    inPk: Float,
    grDb: Float,
    outPk: Float,
    accent: Color,
    /**
     * 表体高度（2026-11-09 参数化）：三段动态用 130dp（三张卡并排、需要足够刻度感）；
     * 单段压缩器卡片更矮、表又撑满宽度，130dp 会显得粗笨，故降到 96dp。
     */
    meterHeight: Dp = 130.dp,
) {
    // 三条表的数据：标签 / 表体 / 数值拆成**三行**分别对齐。
    // 原来是三条各自的 Column 并排 + Bottom 对齐，只要任一标签或数值高度有微小差异
    // （字体 padding、字符宽度触发换行等），该条表就会整体错位——实机表现为
    // 「某段的 GR 条比 IN/OUT 条高出一截」。拆成行后同一行由 weight(1f) 强制等宽同位，
    // 结构上就不可能再错位。
    val specs = listOf(
        MeterSpec("IN", inPk, accent, false),
        MeterSpec("GR", grDb, Color(0xFF8E44AD), true),
        MeterSpec("OUT", outPk, accent, false),
    )
    // 间距 8→5dp、左右内边距 6→4dp：8dp 相对每条 ~34dp 的宽度占 23%，
    // 视觉上三条被拉开成三根孤立细条；收紧后仍是清晰的三列，但更像一组
    val gap = Arrangement.spacedBy(5.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // ① 标签行
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            specs.forEach { s ->
                Text(
                    text = s.tag,
                    fontSize = 10.sp,
                    color = ScColors.TextSub,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // ② 表体行
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = gap,
        ) {
            specs.forEach { s ->
                ScBandMeterCanvas(
                    value = s.value,
                    color = s.color,
                    isGr = s.isGr,
                    modifier = Modifier.weight(1f).height(meterHeight),
                )
            }
        }
        // ③ 数值行
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = gap,
        ) {
            specs.forEach { s ->
                Text(
                    text = meterText(s.value, s.isGr),
                    fontSize = 10.sp,
                    color = meterTextColor(s.value, s.isGr),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 三条表的数据描述：打包 tag/value/color/isGr，便于按行遍历。 */
/**
 * VFD **竖向**电平条（2026-11-09，单段压缩器专用）。
 *
 * 从横条转成竖条，为的是与三段动态页的竖表形态**跨卡片一致**：
 * 详情页里两块表都是「三条竖着的电平柱 + 上方标签 + 下方数值」，
 * 不再一块横一块竖。
 *
 * **格数是这次转型的关键约束**：横条时 300dp 宽可容纳 24 格（12.5dp/格）；
 * 竖起来后条宽只剩 ~90dp，同样的 24 格会挤成 5.4dp/格、几乎糊成一条。
 * 所以降到 **16 格 × 136dp**（8.5dp/格），格感才立得住。
 * 代价：GR 从 1dB/格变成 1.5dB/格，IN/OUT 从 2.5dB/格变成 3.75dB/格。
 *
 * 发光方向：从**底部往上**点亮（i=0 最底），符合电平表的通用读法。
 */
@Composable
private fun ScVfdMeterColumn(
    label: String,
    value: Float,
    isGr: Boolean,
    dim: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 格数 16 → 20（2026-11-09 用户反馈「电平条还是矮了，对比旋钮好突兀」）。
     * 柱高从 200 拉到 250dp 与右栏（3 行旋钮 ≈ 285dp，减去标签与数值的 34dp）齐平；
     * 若维持 16 格，每格会胀到 15.6dp、显得粗笨，所以格数同步提到 20
     * （每格 10.5dp，与原来 200dp/16 格的密度一致，观感不变但整体更高）。
     */
    segmentCount: Int = 20,
    barHeight: Dp = 250.dp,
    /**
     * GR 峰值保持（dB，负值，2026-11-09）：只对 isGr 生效，在柱上画一条细横线
     * 标记历史压缩最深处。DSP 侧按 3dB/s 缓慢回落，不会永久钉死。
     */
    peakDb: Float = 0f,
) {
    val norm = if (isGr) {
        (-value / 24f).coerceIn(0f, 1f)
    } else {
        val db = 20f * kotlin.math.log10(value.coerceAtLeast(1e-6f))
        ((db + 60f) / 60f).coerceIn(0f, 1f)
    }
    // 旁路时格子全灭：与顶部「总 GR」的电源指示语义对齐——
    // 那边是标签随开关亮灭，这边让柱子随开关亮灭。
    //
    // 为什么 UI 要管这件事：native 的 `bypass_` 只影响输出那一步，包络/增益/计量
    // 仍照常推进（这是为了解除旁路时增益已在稳态、不产生跳变）。
    // 所以旁路期间 `meter_*` 一直在更新，表会持续跳动——看起来像「关了还在工作」。
    // 数值仍然显示（只是压暗），保留「若开启会是多少」的信息。
    val lit = if (dim) 0 else (norm * segmentCount).toInt()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 标签**常亮**（2026-11-09 用户要求）：VFD 面板上的 IN/GR/OUT 是丝印，
        // 不是发光段——真实设备断电时丝印依然清晰，不随 dim 变暗。
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF8FA3B2),
            maxLines = 1,
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(barHeight)) {
            val gap = 2.dp.toPx()
            val segH = ((size.height - gap * (segmentCount - 1)) / segmentCount)
                .coerceAtLeast(1f)
            for (i in 0 until segmentCount) {
                // **发光方向按表类型分开**（2026-11-09 用户指出 GR 应从上往下）：
                //   IN/OUT —— 从下往上，电平越高柱子越长（读「有多响」）
                //   GR     —— 从**上往下**，压得越深柱子越往下延伸（读「压了多少」）
                // 这与三段动态的竖表一致（ScBandMeterCanvas 里 GR 也是自上而下填充）。
                val y = if (isGr) {
                    i * (segH + gap)                              // i=0 在顶部
                } else {
                    size.height - (i + 1) * segH - i * gap        // i=0 在底部
                }
                if (i < lit) {
                    // 20 格 × 1.2dB：12dB 落在第 10 格、18dB 落在第 15 格
                    // **三档配色保持原样**（2026-11-09 用户要求「点亮段/琥珀/红不能动」）：
                    // 底改成淡灰后，这三色仍是同一套语义色，只靠一层 1px 深色描边
                    // 让亮色在浅底上立住轮廓（否则青绿与淡灰亮度接近、边缘会发虚）。
                    val c = when {
                        isGr && i >= 15 -> Color(0xFFFF5A4D)  // 18~24dB 红
                        isGr && i >= 10 -> Color(0xFFFFC94D)  // 12~18dB 琥珀
                        else -> Color(0xFF4DFFC8)             // 正常 青绿
                    }
                    drawRect(
                        color = c,
                        topLeft = Offset(0f, y),
                        size = Size(size.width, segH),
                    )
                    // 深底上亮色自带明度差，不再需要描边定界
                } else {
                    // 熄灭格：比窗体深一档，仍能看出「一共多少格」
                    drawRect(
                        color = Color(0xFF3A4753),
                        topLeft = Offset(0f, y),
                        size = Size(size.width, segH),
                    )
                }
            }
            // GR 峰值保持线：一条 2px 近白横线，标记历史压缩最深处。
            // 必须画在**同一个 Canvas 里**——另起一层叠层会因 padding/坐标系不同而错位。
            if (isGr && !dim && peakDb < -0.5f) {
                val pn = (-peakDb / 24f).coerceIn(0f, 1f)
                // GR 从上往下填充，峰值线位置也随之为 height*pn（不是 1-pn）
                val py = size.height * pn
                drawRect(
                    color = Color(0xFFE8EFF5),
                    topLeft = Offset(0f, (py - 1f).coerceIn(0f, size.height - 2f)),
                    size = Size(size.width, 2f),
                )
            }
        }
        Text(
            text = meterText(value, isGr),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,   // 等宽：数值宽度恒定，三列对齐稳定
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            // 旁路时数值也压暗：否则会出现「柱子全灭但红字 -14.9dB」，
            // 看起来像在报警，与熄灭的柱自相矛盾。
            color = if (!dim && norm > 0.002f) Color(0xFF4DFFC8) else Color(0xFF6B7C8A),
        )
    }
}

private data class MeterSpec(
    val tag: String,
    val value: Float,
    val color: Color,
    val isGr: Boolean,
)

/**
 * 表体数值文本：GR 归一负零；IN/OUT 低于 -59dB 显示「--」。
 * **带 dB 单位**（2026-11-09 P0）：专业工具从不省略单位——原来裸写 `-29`，
 * 读者要自己判断这是 dB 还是线性值。
 */
private fun meterText(value: Float, isGr: Boolean): String {
    val dB = if (isGr) value else 20f * kotlin.math.log10(value.coerceAtLeast(1e-6f))
    return when {
        isGr -> if (kotlin.math.abs(dB) < 0.05f) "0.0dB" else "%.1fdB".format(dB)
        dB < -59f -> "--"
        // |dB| < 0.5 时 %.0f 会输出 "-0" → 显示成「-0dB」，看着别扭，统一归零
        kotlin.math.abs(dB) < 0.5f -> "0dB"
        else -> "%.0fdB".format(dB)
    }
}

/** 数值颜色：IN/OUT 用次要色；GR 按绿/琥珀/红三档（与条形色谱一致）。 */
private fun meterTextColor(value: Float, isGr: Boolean): Color = if (!isGr) {
    ScColors.TextSub
} else when {
    value > -6f -> Color(0xFF2E8B4F)
    value > -12f -> Color(0xFFC08A20)
    else -> Color(0xFFC0392B)
}

/**
 * 紧凑开关（2026-11-08 手绘）：42×24dp 视觉 + 42×32dp 触摸区。
 *
 * 为什么不用 Material3 的 ScSwitch：它的触摸区约 48dp 高，在「压缩器（单段）」
 * 收起后的标题行里占掉整卡高度的大半（93dp 卡里开关就占了 48dp）。自绘后视觉 24dp，
 * 触摸区 32dp——比 Material 标准的 48dp 小，但仍在可接受的点击范围内
 * （Apple HIG 建议 44pt、Material 48dp，这里取 32dp 是刻意为了紧凑做的取舍）。
 */
@Composable
private fun ScTinySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /**
     * 禁用态：压限器总闸关闭时，详情页两张卡的开关会置灰不可点。
     * 为什么必须禁用而不是"照常可点但静默不生效"：那种情况用户点了看到开关变化、
     * 声音却没变化，会以为坏了。置灰至少把「现在轮不到你」表达出来了。
     */
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 32.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        !enabled -> Color(0xFFE2E7ED)      // 禁用：比关闭态更淡
                        checked -> ScColors.Primary
                        else -> Color(0xFFCCD3DC)
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(20.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(if (enabled) Color.White else Color(0xFFF7F9FB)),
            )
        }
    }
}

/**
 * 效果槽卡头：详情页里「一个效果器 = 一张可折叠卡片」的通用头部。
 *
 * 为什么抽出来：压限器详情页现在有两张卡（多段压限 / 压缩器单段），后面每个
 * 「音效库一个卡片 → 详情页多个效果器」的组都会重复这个结构。四件事一旦各写一遍
 * 就容易走样：
 *   1. 开关值 = **该槽记忆 && 组总闸**（总闸关时显示为关）
 *   2. 总闸关时开关**置灰不可点**（不是"能点但静默不生效"）
 *   3. 整行可点 → 折叠；开关自己消费点击 → 点开关不会折叠
 *   4. 统一行高 32dp（两张卡折叠后等高就靠它）
 *
 * [trailing] 用来塞组内特有的小控件（如压限器的「联动 / A|B」），
 * 位置固定在标题与开关之间 —— 开关始终是这一组控件的**收尾**。
 *
 * @param switchEnabled 传组总闸；false 时开关置灰
 */
/**
 * 折叠状态默认跟随开关：开 -> 展开，关 -> 收起。
 *
 * 用户手动点标题行折叠/展开仍然生效；只有开关值变化时才重新同步 ——
 * 所以"关掉开关 → 卡片自动收起"是成立的，而"展开着看参数时不会被无端重置"。
 */
@Composable
internal fun rememberExpandedFollowing(checked: Boolean, key: String): MutableState<Boolean> {
    val state = rememberSaveable(key) { mutableStateOf(checked) }
    LaunchedEffect(checked) { state.value = checked }
    return state
}

@Composable
internal fun ScSlotHeader(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchEnabled: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    /** false = 本卡不显示开关（开关在页头，例如立体声宽度/限幅器） */
    showSwitch: Boolean = true,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // 左右内边距由外层 Column 的 20dp 提供，这里只留纵向 —— 保证与
            // 均衡器页卡头「标题距卡片左 20dp、卡头高 56dp」完全一致。
            .heightIn(min = 56.dp)
            .scNoRippleClick { onToggleExpand() }
            .padding(vertical = 18.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = ScColors.TextMain,
            modifier = Modifier.weight(1f),
        )
        trailing()
        if (showSwitch) {
            ScTinySwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = switchEnabled,
            )
        }
        Text(
            text = if (expanded) "▾" else "▸",
            fontSize = 13.sp,
            color = ScColors.TextSub,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 参数「标签 + 可点数字」。
 * 多段压限每段只放 Attack / Release 两个旋钮，其余 4 个参数用紧凑文本呈现——
 * 点数字弹输入框改值，比再塞 4 个旋钮省空间，也比滑块精确。
 */
@Composable
private fun ScParamValueCell(
    label: String,
    value: Float,
    unit: String,
    decimals: Int,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    // 居中排列：原本 SpaceBetween 把标签推到最左、数值推到最右，
    // 三段并排时颜色上像是「本段的数值 + 下一段的标签」连成一片，很难读。
    // 改成一串居中短语「阈值 −4.4 dB」，每段自成一句，配合竖分割线清晰可辨。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 10.sp, color = ScColors.TextSub, maxLines = 1)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "%.${decimals}f%s".format(value, unit),
            fontSize = 11.sp,
            color = ScColors.TextMain,
            maxLines = 1,
            modifier = Modifier.clickable {
                inputText = "%.${decimals}f".format(value)
                showInput = true
            },
        )
    }
    if (showInput) {
        AlertDialog(
            onDismissRequest = { showInput = false },
            title = { Text(label, fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = {
                        Text("${range.start} ~ ${range.endInclusive}", fontSize = 11.sp)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    inputText.trim().toFloatOrNull()?.let {
                        onValueChange(it.coerceIn(range.start, range.endInclusive))
                    }
                    showInput = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showInput = false }) { Text("取消") }
            },
        )
    }
}

/** 单条竖表。IN/OUT 映射 -60~0 dBFS；GR 映射 0~-24dB（满刻度=压了 24dB）。 */
@Composable
private fun ScBandMeterCanvas(
    value: Float,
    color: Color,
    isGr: Boolean,
    modifier: Modifier = Modifier,
) {
    val dB = if (isGr) value else 20f * kotlin.math.log10(value.coerceAtLeast(1e-6f))
    val norm = if (isGr) {
        (-value / 24f).coerceIn(0f, 1f)
    } else {
        ((dB + 60f) / 60f).coerceIn(0f, 1f)
    }
    // 只画表体：标签与数值移到 ScBandMeterGroup 里按行对齐，
    // 这样三条表的 Canvas 由同一行的 weight 强制等宽等位，不可能再错位。
    Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            // 直角而非圆角（2026-11-08 用户要求）：18dp 宽的窄条上圆角几乎只剩圆边，
            // 观感发软；直角更硬朗、刻度也更容易对齐到边缘。
            drawRect(color = Color(0xFFE4E9EF))

            if (norm > 0.002f) {
                val fillH = h * norm
                // 磷光溢出：填充的**跟随边**外侧画一层柔光 + 一条近白高光，
                // 让连续填充的竖表也带上顶部 VFD 横条那种「发光」感，两者视觉上串起来。
                // 只借 VFD 的「发光」，不借它的「分段」——竖表量程大（IN/OUT 60dB），
                // 离散化会丢掉电平起伏的细腻度。
                if (isGr) {
                    // GR 从上往下填，光从**底边**向下溢出
                    val glowH = 7f.coerceAtMost(h - fillH)
                    if (glowH > 0.5f) {
                        drawRect(
                            color = Color(0xFFD9483B).copy(alpha = 0.42f),
                            topLeft = Offset(0f, fillH),
                            size = Size(w, glowH),
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.75f),
                            topLeft = Offset(0f, (fillH - 2f).coerceAtLeast(0f)),
                            size = Size(w, 2f),
                        )
                    }
                } else {
                    // IN/OUT 从下往上填，光从**顶边**向上溢出
                    val edge = h - fillH
                    val glowH = 7f.coerceAtMost(edge)
                    if (glowH > 0.5f) {
                        drawRect(
                            color = color.copy(alpha = 0.45f),
                            topLeft = Offset(0f, edge - glowH),
                            size = Size(w, glowH),
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.75f),
                            topLeft = Offset(0f, (edge - 2f).coerceAtLeast(0f)),
                            size = Size(w, 2f),
                        )
                    }
                }
                if (isGr) {
                    // GR 按行业惯例分三色：0~6dB 绿（轻压、正常）/
                    // 6~12dB 琥珀（偏重、该留意）/ >12dB 红（压过头）。
                    // 渐变锚在**整条**上而不是填充块内，所以填充到哪就露出哪个区段的颜色，
                    // 用户不读数字也能一眼看出是否压过头。
                    // 刻度满量程 24dB → 6dB=25%、12dB=50%。
                    clipRect(bottom = fillH) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color(0xFF3BA55C),     // 0dB 绿
                                0.25f to Color(0xFF3BA55C),  // -6dB 仍绿
                                0.32f to Color(0xFFE0A12F),  // 过渡到琥珀
                                0.50f to Color(0xFFE0A12F),  // -12dB 琥珀
                                0.58f to Color(0xFFD9483B),  // 过渡到红
                                1f to Color(0xFFB02A1F),     // -24dB 深红
                            ),
                        )
                    }
                } else {
                    // IN/OUT：从底部向上；渐变锚在**整条**坐标上——
                    // 顶部（接近 0dB）最实，底部（低电平）最淡，电平低时整段都浅。
                    clipRect(top = h - fillH) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to color.copy(alpha = 0.95f),
                                0.55f to color.copy(alpha = 0.55f),
                                1f to color.copy(alpha = 0.18f),
                                startY = 0f,
                                endY = h,
                            ),
                        )
                    }
                }
            }

            // 刻度：IN/OUT 每 12dB 一格（0/-12/-24/-36/-48）；GR 每 6dB（0/-6/-12/-18/-24）
            val marks = if (isGr) {
                listOf(0f, -6f, -12f, -18f, -24f)
            } else {
                listOf(0f, -12f, -24f, -36f, -48f)
            }
            marks.forEach { m ->
                val y = if (isGr) {
                    h * (-m / 24f)
                } else {
                    h * (1f - ((m + 60f) / 60f))
                }.coerceIn(0f, h)
                // 贯穿整条宽度（原只画左侧 42%，用户反馈「刻度线没有到头」）；
                // 画满后降低不透明度，避免压过填充色的层次感。
                // GR 的 -6 / -12 两条是**警戒线**：分别对应「开始偏重」和
                // 「压过头」，用与条形一致的半透明彩色标记，比灰线更能提示用户该停在哪。
                val lineColor = when {
                    isGr && m == -6f -> Color(0x8A3BA55C)
                    isGr && m == -12f -> Color(0x8AD9483B)
                    else -> Color(0x28000000)
                }
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (isGr && (m == -6f || m == -12f)) 1.5f else 1f,
                )
            }

            // 直角边框：每条表是一个独立「窗口」而非靠底色区分。画在最后压在填充之上。
            drawRect(
                color = Color(0xFFAAB6C4),
                style = Stroke(width = 1.dp.toPx()),
            )
    }
}

/** 混响预设显示名：native X-macro 单源下发（reverbPresetNames），越界回退 干声/预设 N。 */
internal fun reverbName(preset: Int, names: Array<String>): String =
    names.getOrNull(preset) ?: if (preset == 0) "干声" else "预设 $preset"

/**
 * U7 通用效果详情页框架（对齐 P2 卷积详情页形态）：返回圆钮 + 标题/副文本 + 右上拟物开关。
 * 四个效果详情页共用（参量均衡=大面板 / 多频段压限 / 立体声宽度 / 算法混响 FDN），
 * 内容由调用方注入；调用方外层包 verticalScroll（内容长度差异大）。
 */
@Composable
internal fun ScEffectDetailPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    /**
     * 页头开关（2026-11-11 改为**可空**）：压限器页的总开关已移进「多段压限」卡片内，
     * 页头不再显示；其余三个详情页（参量均衡/立体声宽度/限幅器）仍用页头开关，
     * 照常传值即可。
     * 两个都传 null 才不渲染 —— 只传一个视为不完整，同样不渲染，避免出现「开关点了没反应」。
     */
    enabled: Boolean? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 页头：返回 + 标题/副文本 + 拟物开关（同 P2 phead 形态）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, ScColors.Divider, CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.TextMain,
                )
            }
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.TextMain,
                )
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, fontSize = 11.sp, color = ScColors.TextSub)
                }
            }
            if (enabled != null && onEnabledChange != null) {
                ScSwitch(checked = enabled, onCheckedChange = { onEnabledChange(!enabled) })
            }
        }
        if (enabled == false) {
            Text(
                text = "效果当前旁路——参数可预调，开启后生效。",
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
        }
        // （U9o：A/B 标尺说明行退役——概念保留在实现里（开关即 B/A 档），不向用户展示）
        content()
    }
}

/**
 * U7 参量均衡详情内容：多段 EQ 一体化面板（手绘曲线+选中段旋钮+预设+AutoEq），
 * EqPanel.kt 定稿资产原样搬入，仅外包 ScCard 统一卡面。U9n GEQ 退役，仅 PEQ。
 */
@Composable
internal fun ScEqDetailContent(viewModel: MainViewModel) {
    // 两张同级卡：均衡器 → AutoEq 校正（互相嵌套会成卡中卡，阴影叠加、层级含混）
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    // 组总闸（与「压限器」「低频管家」同构）：子卡开关 = 记忆值 && 总闸
    val eqSectionEnabled by viewModel.eqSectionEnabled.collectAsStateWithLifecycle()
    val eqGate = eqEnabled && eqSectionEnabled
    // 折叠默认跟随开关：开=展开、关=收起；点标题行可手动展开/收起
    val eqExpanded = rememberExpandedFollowing(eqGate, "eq_panel_expanded")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 组总闸关闭 → 整页旁通（与压限器/低频管家同一套提示口径）
        if (!eqSectionEnabled) {
            Text(
                text = "均衡器总开关已关闭——整体旁通。到音效库打开「均衡器」后，" +
                    "两张卡按各自开关恢复。",
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
        }
        ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // 与其他详情页共用同一个卡头组件 —— 标题/开关/箭头的位置与尺寸必然一致
                ScSlotHeader(
                    title = "均衡器",
                    checked = eqGate,
                    onCheckedChange = { viewModel.setEqEnabled(!eqEnabled) },
                    switchEnabled = eqSectionEnabled,
                    expanded = eqExpanded.value,
                    onToggleExpand = { eqExpanded.value = !eqExpanded.value },
                )
                if (eqExpanded.value) {
                    MultiBandEqPanel(
                        viewModel = viewModel,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
            }
        }
        ScAutoEqCard(viewModel = viewModel, sectionEnabled = eqSectionEnabled)
    }
}

/** U7 多频段压限详情内容：单段压缩器 + 三段多频段参数（原主页压限区整体迁入；两者互斥状态机不变） */
@Composable
internal fun ScBandDetailContent(viewModel: MainViewModel) {
    val compressorEnabled by viewModel.compressorEnabled.collectAsStateWithLifecycle()
    val compressorThreshold by viewModel.compressorThreshold.collectAsStateWithLifecycle()
    val compressorRatio by viewModel.compressorRatio.collectAsStateWithLifecycle()
    // 四个新增参数（2026-11-09 全部暴露到 UI）
    val compressorAttack by viewModel.compressorAttack.collectAsStateWithLifecycle()
    val compressorRelease by viewModel.compressorRelease.collectAsStateWithLifecycle()
    val compressorKnee by viewModel.compressorKnee.collectAsStateWithLifecycle()
    val compressorMakeup by viewModel.compressorMakeup.collectAsStateWithLifecycle()
    // 四项新参数
    val compressorMix by viewModel.compressorMix.collectAsStateWithLifecycle()
    val compressorDetect by viewModel.compressorDetect.collectAsStateWithLifecycle()
    val compressorLookahead by viewModel.compressorLookahead.collectAsStateWithLifecycle()
    val compressorOversample by viewModel.compressorOversample.collectAsStateWithLifecycle()
    val compressorSidechainHp by viewModel.compressorSidechainHp.collectAsStateWithLifecycle()
    val multibandParams by viewModel.multibandParams.collectAsStateWithLifecycle()
    // 多段压限的总开关（2026-11-11 从页头下沉到卡片内）
    val multibandEnabled by viewModel.multibandEnabled.collectAsStateWithLifecycle()
    // 压限器**总闸**：音效库卡片上那个开关。关掉 = 整体旁通，
    // 但两张卡的记忆值不动 —— 所以这里显示的是「记忆 && 总闸」而不是记忆本身。
    val sectionEnabled by viewModel.compressorSectionEnabled.collectAsStateWithLifecycle()
    // 折叠状态：两张卡片点标题行展开/收回。
    // 单段压缩器**默认收起**（用户要求）——它默认关闭、且与三段互斥，平时不需要看；
    // 三段动态是主界面，默认展开。
    // 折叠默认跟随各自开关：开 -> 展开，关 -> 收起
    val multibandGate = multibandEnabled && sectionEnabled
    val compressorGate = compressorEnabled && sectionEnabled
    var multibandExpanded by rememberExpandedFollowing(multibandGate, "mb")
    var compressorExpanded by rememberExpandedFollowing(compressorGate, "comp")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                // 折叠态**整卡可点**（2026-11-11 用户要求「点卡片上就能动作」）：
                // 原来 clickable 只挂在标题 Text 上，点卡片上下留白、右侧空白毫无反应，
                // 手感像「没点到」。这里把点击区提到卡片最外层（含 ScCard 的 16dp 内边距）。
                // 展开态**不能加**——否则点旋钮/滑块/分段控件会被外层吞成「折叠」。
                .then(
                    if (!multibandExpanded) Modifier.clickable { multibandExpanded = true }
                    else Modifier,
                ),
        ) {
            Column(
                // 收起时 10dp、展开时 14dp（2026-11-11 统一两卡折叠高度）：
                // 原来这里是固定 14dp，而压缩器收起是 6dp，两者折叠态实测相差
                // 245px vs 210px（12.7dp）。改成同一套规则后只差行高，行高由下面的
                // heightIn(32dp) 锁定 → 两卡折叠高度一致。
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (multibandExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 联动状态 / A-B 快照（2026-11-09 P2/P3）：提前 collect 供标题行使用
                val linked by viewModel.multibandLinked.collectAsStateWithLifecycle()
                var abSlot by remember { mutableStateOf('A') }
                // 折叠头（2026-11-08 用户要求）：点标题行展开/收回
                // 通用卡头（2026-11-11 抽为 ScSlotHeader）；trailing 放组内特有控件。
                // 顺序固定为「标题 … 联动/A|B … 开关 … ▾」—— 开关是这一组控件的收尾。
                ScSlotHeader(
                    title = "多段压限",
                    checked = multibandEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setMultibandEnabled(!multibandEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = multibandExpanded,
                    onToggleExpand = { multibandExpanded = !multibandExpanded },
                ) {
                    // 联动开关（P2，2026-11-09）：开 = 改任一段的段内参数同步三段；关 = 各段独立
                    Text(
                        text = if (linked) "联动" else "独立",
                        fontSize = 10.sp,
                        fontWeight = if (linked) FontWeight.Bold else FontWeight.Normal,
                        color = if (linked) ScColors.Primary else ScColors.TextSub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (linked) ScColors.Primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { viewModel.setMultibandLinked(!linked) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // A/B 快照（P3，2026-11-09）：切到另一槽时，先把当前存回原槽，
                    // 再载入目标槽——用于「压前 / 压后」快速对比。
                    for (slot in listOf('A', 'B')) {
                        Text(
                            text = slot.toString(),
                            fontSize = 11.sp,
                            fontWeight = if (abSlot == slot) FontWeight.Bold else FontWeight.Normal,
                            color = if (abSlot == slot) ScColors.Primary else ScColors.TextSub,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (abSlot == slot) ScColors.Primary.copy(alpha = 0.14f)
                                    else Color.Transparent,
                                )
                                .clickable {
                                    if (abSlot != slot) {
                                        viewModel.saveMultibandSnapshot(abSlot)
                                        viewModel.restoreMultibandSnapshot(slot)
                                        abSlot = slot
                                    }
                                }
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (multibandExpanded) {
                // 旁路提示（2026-11-11 从页头迁入，同日区分两级旁路）：
                // 总闸关 与 本卡关 是两回事，提示要分开写，否则用户不知道该去开哪个。
                if (!sectionEnabled) {
                    Text(
                        text = "压限器总开关已关闭——整体旁通。到音效库打开「压限器」后，" +
                            "两张卡按各自开关恢复。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                } else if (!multibandEnabled) {
                    Text(
                        text = "当前旁路——参数可预调，开启后生效。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
                Text(
                    text = "三段各自跟踪电平并独立压缩，合成幅度平坦、分频点平滑无爆音。",
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
                // 计量数据在此 collect：标题行的总 GR 与三段表共用同一份，
                // 放在 if(multibandExpanded) 内 → 收起时 LaunchedEffect 自动停止，不空转。
                val meters by viewModel.multibandMeters.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    while (true) {
                        viewModel.refreshMultibandMeters()
                        delay(100)
                    }
                }
                // 总 GR：**VFD（真空荧光）风格**。
                //
                // VFD 的视觉特征：深色底 + 离散发光段 + 青绿磷光 + 段间留缝 +
                // 柔和外发光。老式功放/AV 接收机的电平表都是这个样子。
                // 与之前的「浅灰底 + 连续色块填充」是两套完全不同的语言——
                // 连续填充像「进度条」，分段发光像「仪表」。
                //
                // 分段逻辑：满量程 24dB → 24 段，每段 1dB。点亮段按 GR 深度从左往右亮。
                // 颜色仍保留三档警示（前 6 段青绿 / 中 6 段琥珀 / 后 12 段红），
                // 只是把「连续渐变」换成了「离散段」。
                run {
                    val grMax = minOf(meters.grDb(0), meters.grDb(1), meters.grDb(2))
                    val norm = (-grMax / 24f).coerceIn(0f, 1f)
                    // 三段压缩开关（2026-11-09 用户要求）：「总 GR」标签是这块仪表的电源指示——
                    // 开启时点亮成磷光青绿，关闭时保持 VFD 未通电的暗青。这样不读数字也知道
                    // 「这一段到底在工作没有」。
                    val mbOn by viewModel.multibandEnabled.collectAsStateWithLifecycle()
                    // 高亮色：用于数字与光晕
                    val glowColor = when {
                        grMax > -6f -> Color(0xFF4DFFC8)
                        grMax > -12f -> Color(0xFFFFC94D)
                        else -> Color(0xFFFF5A4D)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(3.dp))
                            // 深黑偏蓝的背景——VFD 屏未点亮时的底色
                            .background(Color(0xFF1A2830)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "总 GR",
                                fontSize = 9.sp,
                                fontWeight = if (mbOn) FontWeight.Bold else FontWeight.Normal,
                                color = if (mbOn) Color(0xFF4DFFC8) else Color(0xFF3A5C52),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // 中间：24 段发光条
                            Canvas(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(19.dp),
                            ) {
                                // 16 格 × 1.5dB（2026-11-09 采纳「格感更强」方案）：
                                // 24 格时每格仅 ~11dp、缝 2dp，远看仍像一条连续带；
                                // 减到 16 格 + 缝 3dp 后每格 ~18dp，VFD 的离散感才真正立起来。
                                // 代价是读数从「1 格 = 1dB」变成「1 格 = 1.5dB」，精度略降，
                                // 但右侧有精确数字，格子只承担「粗读 + 观感」职责。
                                val segs = 16
                                val gap = 3.dp.toPx()
                                val segW = ((size.width - gap * (segs - 1)) / segs).coerceAtLeast(1f)
                                val litCount = (norm * segs).toInt()
                                for (i in 0 until segs) {
                                    val x = i * (segW + gap)
                                    val lit = i < litCount
                                    if (lit) {
                                        // 分档按新格宽重算：每格 1.5dB → 12dB 在第 8 格、
                                        // 18dB 在第 12 格
                                        val c = when {
                                            i >= 12 -> Color(0xFFFF5A4D)  // 18~24dB：压过头
                                            i >= 8 -> Color(0xFFFFC94D)   // 12~18dB：偏重
                                            else -> Color(0xFF4DFFC8)     // 0~12dB：正常
                                        }
                                        // 磷光外发光：三层叠加模拟（不用 BlurMaskFilter，
                                        // 它在窄条上会留硬边，之前踩过）。
                                        // 由外到内 alpha 递增、范围递减 → 视觉上像光从段里溢出来。
                                        drawRect(
                                            color = c.copy(alpha = 0.10f),
                                            topLeft = Offset(x - 3f, -3f),
                                            size = Size(segW + 6f, size.height + 6f),
                                        )
                                        drawRect(
                                            color = c.copy(alpha = 0.26f),
                                            topLeft = Offset(x - 1.5f, -1.8f),
                                            size = Size(segW + 3f, size.height + 3.6f),
                                        )
                                        drawRect(
                                            color = c,
                                            topLeft = Offset(x, 0f),
                                            size = Size(segW, size.height),
                                        )
                                    } else {
                                        // 熄灭段：留暗轮廓让「一共多少格」可见。
                                        // 0xFF16221E 在纯黑底上几乎看不出分格，提到 1E2E28
                                        drawRect(
                                            color = Color(0xFF2A3A42),
                                            topLeft = Offset(x, 0f),
                                            size = Size(segW, size.height),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (kotlin.math.abs(grMax) < 0.05f) "0.0 dB"
                                else "%.1f dB".format(grMax),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                // 等宽 + 右对齐 + **固定宽度**（2026-11-09 用户要求）：
                                // 数字是 Row 里除 weight(1f) 之外的固定项，若让它按内容自适应，
                                // 「0.0 dB」→「-14.7 dB」变长时会把电平条挤短，条长随数值跳动，
                                // 显得突兀。`-100.0 dB` 是 9 字符，10sp 等宽约 6dp/字符 → 取 58dp。
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                softWrap = false,
                                color = if (norm > 0.002f) glowColor else Color(0xFF2A423B),
                                modifier = Modifier.width(58.dp),
                            )
                        }
                    }
                }
                // 频点划分条（2026-11-08，按用户设计稿）：三段色块 + 两根可拖竖线，
                // 取代原先两个独立 Slider——拖线即改分频点，颜色区域随分频点实时变化。
                // 横轴固定 20Hz~20kHz 对数刻度：这样色块宽度才真实反映各频段的频率占比，
                // 而不是「两个滑块各占一半」的假象。
                run {
                    // 点击 LOW / MID 色块输入截止频率（2026-11-08 用户要求）：
                    // 0=无 1=低频上限（分频点1） 2=中频上限（分频点2）
                    var splitInput by remember { mutableStateOf(0) }
                    var splitText by remember { mutableStateOf("") }
                    val fMin = 20f
                    val fMax = 20000f
                    val lnMin = kotlin.math.ln(fMin.toDouble())
                    val lnSpan = kotlin.math.ln(fMax.toDouble()) - lnMin
                    fun toNorm(f: Float): Float =
                        ((kotlin.math.ln(f.coerceIn(fMin, fMax).toDouble()) - lnMin) / lnSpan).toFloat()
                    fun toFreq(n: Float): Float =
                        kotlin.math.exp(lnMin + n.coerceIn(0f, 1f) * lnSpan).toFloat()
                    val range1 = viewModel.multibandParamRange(18)
                    val range2 = viewModel.multibandParamRange(19)
                    val n1 = toNorm(multibandParams[18])
                    val n2 = toNorm(multibandParams[19])
                    val cur1 by rememberUpdatedState(multibandParams[18])
                    val cur2 by rememberUpdatedState(multibandParams[19])
                    // 手势挂在整条上、按「离哪根线近」决定拖谁，用**绝对 x 位置**换算。
                    // 前一版给每根线各挂一个 44dp 热区 Box + 相对 drag 累加，实测两个问题：
                    // 拖动方向反、且两根线同时变化（热区互相干扰 + 累加基准随重组漂移）。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            // 只留顶部 6dp：原 vertical=6 让色块下方也留白，
                            // 下面的分频点读数离条太远（用户要求「标签靠上一点」）
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .pointerInput(Unit) {
                                var dragging = 0  // 0=无 1=低/中 2=中/高
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val frac = pos.x / size.width.coerceAtLeast(1)
                                        dragging = if (kotlin.math.abs(frac - toNorm(cur1)) <=
                                            kotlin.math.abs(frac - toNorm(cur2))
                                        ) 1 else 2
                                    },
                                    onDragEnd = { dragging = 0 },
                                    onDragCancel = { dragging = 0 },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val frac = (change.position.x / size.width.coerceAtLeast(1))
                                            .coerceIn(0.02f, 0.98f)
                                        if (dragging == 1) {
                                            val hz = toFreq(frac.coerceAtMost(toNorm(cur2) - 0.04f))
                                            viewModel.setMultibandParam(
                                                18, hz.coerceIn(range1.start, range1.endInclusive),
                                            )
                                        } else if (dragging == 2) {
                                            val hz = toFreq(frac.coerceAtLeast(toNorm(cur1) + 0.04f))
                                            viewModel.setMultibandParam(
                                                19, hz.coerceIn(range2.start, range2.endInclusive),
                                            )
                                        }
                                    },
                                )
                            },
                    ) {
                        // 三段底色
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.weight(n1.coerceAtLeast(0.02f)).fillMaxHeight().background(Color(0xFFF7EFE1)))
                            Box(Modifier.weight((n2 - n1).coerceAtLeast(0.02f)).fillMaxHeight().background(Color(0xFFDDEAF6)))
                            Box(Modifier.weight((1f - n2).coerceAtLeast(0.02f)).fillMaxHeight().background(Color(0xFFEDE2F6)))
                        }
                        // 段名（2026-11-08 调整）：字号 10→13sp + 加粗。
                        // **垂直居中必须靠 Row 的 verticalAlignment**：Row 默认 Top，
                        // 而内部 Box 的 contentAlignment 只在自己高度内生效——Row 已
                        // fillMaxSize 撑满，Box 却是 wrapContent 高，结果文字一直贴顶，
                        // 颜色块下方空出一大截（用户反馈「标签靠得很上」）。
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // LOW / MID 可点：点开输入该段上限截止频率。
                            // clickable 在手指移动超过 touch slop 时会放弃、把事件让给
                            // 外层的拖动手势，所以不会影响拖竖线调频点。
                            // fillMaxHeight 让整块色块高度都可点，而不是只有文字那一小条。
                            Box(
                                Modifier
                                    .weight(n1.coerceAtLeast(0.02f))
                                    .fillMaxHeight()
                                    .clickable {
                                        splitText = "%.0f".format(multibandParams[18])
                                        splitInput = 1
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "LOW", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8A6D3B), maxLines = 1,
                                )
                            }
                            Box(
                                Modifier
                                    .weight((n2 - n1).coerceAtLeast(0.02f))
                                    .fillMaxHeight()
                                    .clickable {
                                        splitText = "%.0f".format(multibandParams[19])
                                        splitInput = 2
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "MID", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF3B6A8A), maxLines = 1,
                                )
                            }
                            Box(Modifier.weight((1f - n2).coerceAtLeast(0.02f)), contentAlignment = Alignment.Center) {
                                Text(
                                    "HIGH", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6B4A8A), maxLines = 1,
                                )
                            }
                        }
                        // 两根分频点**推子**（2026-11-09 用户要求「用推子渲染看看」）。
                        // 纯绘制，不接手势——手势在整条上统一处理（按离哪根近决定拖谁）。
                        // 结构：贯穿全高的细轨道 + 居中的把手（圆角矩形 + 两道防滑横纹），
                        // 这是调音台 fader 的经典形态：轨道表达「轴」，把手表达「可抓」。
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val h = size.height
                            val w = size.width
                            val trackColor = Color(0xFF566276)
                            val handleW = 13.dp.toPx()
                            val handleH = 30.dp.toPx()
                            // 倒角尺寸：2.8dp 足够表达「切了角」，再大就接近菱形
                            val chamfer = 2.8.dp.toPx()
                            val cy = h / 2f
                            listOf(n1, n2).forEach { n ->
                                val x = w * n
                                // ① 轨道：贯穿全高，比原来的 2.5px 细一点（把手才是主体）
                                drawLine(
                                    Color(0xFF566276).copy(alpha = 0.5f),
                                    Offset(x, 0f),
                                    Offset(x, h),
                                    strokeWidth = 1.6f,
                                )
                                // 把手轮廓（2026-11-09 用户要求「外围用小倒角圆弧」）：
                                // 不再是 `drawRoundRect` 的圆角矩形——那样四个角是四分之一圆，
                                // 整体偏「胶囊」。改为**45° 小倒角 + 倒角处二次贝塞尔圆弧**：
                                // 直边是直线，只在切角处用一段短弧过渡，是调音台 fader cap
                                // 常见的工业造型（比圆角硬朗，又不像纯八边形那样扎手）。
                                val cyl = cy - handleH / 2f
                                val cyb = cy + handleH / 2f
                                val cxl = x - handleW / 2f
                                val cxr = x + handleW / 2f
                                val c = chamfer
                                val handlePath = Path().apply {
                                    moveTo(cxl + c, cyl)
                                    lineTo(cxr - c, cyl)
                                    quadraticBezierTo(cxr, cyl, cxr, cyl + c)
                                    lineTo(cxr, cyb - c)
                                    quadraticBezierTo(cxr, cyb, cxr - c, cyb)
                                    lineTo(cxl + c, cyb)
                                    quadraticBezierTo(cxl, cyb, cxl, cyb - c)
                                    lineTo(cxl, cyl + c)
                                    quadraticBezierTo(cxl, cyl, cxl + c, cyl)
                                    close()
                                }
                                // ② 投影：整块路径下移 2px
                                drawPath(
                                    path = Path().apply {
                                        addPath(handlePath)
                                        translate(Offset(0f, 2f))
                                    },
                                    color = Color(0x2E2C3644),
                                )
                                // ③ 把手本体
                                drawPath(path = handlePath, color = Color(0xFFFDFEFF))
                                // ④ 描边
                                drawPath(
                                    path = handlePath,
                                    color = trackColor.copy(alpha = 0.75f),
                                    style = Stroke(width = 1.3f),
                                )
                                // ④ 防滑横纹：两道细线，是 fader cap 最好认的特征
                                for (k in 0..1) {
                                    val yy = cy + (k - 0.5f) * 5.2f
                                    drawLine(
                                        color = trackColor.copy(alpha = 0.65f),
                                        start = Offset(x - handleW * 0.28f, yy),
                                        end = Offset(x + handleW * 0.28f, yy),
                                        strokeWidth = 1.4f,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                    // 分频点输入框：点 LOW 改低频上限（分频点1）、点 MID 改中频上限（分频点2）。
                    // 用 KeyboardType.Number（频点是整数 Hz，不需要小数点/负号）；
                    // 越界自动钳到该参数的有效范围，非法文本忽略。
                    if (splitInput > 0) {
                        val pid = if (splitInput == 1) 18 else 19
                        val r = viewModel.multibandParamRange(pid)
                        AlertDialog(
                            onDismissRequest = { splitInput = 0 },
                            title = {
                                Text(
                                    text = if (splitInput == 1) "低频上限截止频率" else "中频上限截止频率",
                                    fontSize = 15.sp,
                                )
                            },
                            text = {
                                OutlinedTextField(
                                    value = splitText,
                                    onValueChange = { splitText = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    label = {
                                        Text(
                                            "${"%.0f".format(r.start)} ~ ${"%.0f".format(r.endInclusive)} Hz",
                                            fontSize = 11.sp,
                                        )
                                    },
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    splitText.trim().toFloatOrNull()?.let {
                                        viewModel.setMultibandParam(
                                            pid, it.coerceIn(r.start, r.endInclusive),
                                        )
                                    }
                                    splitInput = 0
                                }) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = { splitInput = 0 }) { Text("取消") }
                            },
                        )
                    }
                    // 分频点读数（2026-11-08 用户要求）：两个数值**各自跟随自己的竖线**居中，
                    // 而不是两端对齐——两端对齐时读者要把视线从左边数值拽到右边才知道哪个是哪个。
                    // **关键：这里不能挂任何 pointerInput / clickable**。标签只是 Text，浮在
                    // 手势区之外；一旦给它加可点修饰符就会参与命中测试并拦截拖动，竖线就拽不动了。
                    // 高度 15dp 时 10sp 文字会被裁掉下半截（用户反馈「标签被挡住了」）：
                    // 10sp 字加上字体上下 padding 实际需要约 17~18dp，给 19dp 留余量。
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
                        val w = maxWidth
                        val labelW = 64.dp
                        // 用竖线对应的段色，省掉「低/中」这类文字前缀
                        Text(
                            text = "${"%.0f".format(multibandParams[18])} Hz",
                            fontSize = 10.sp,
                            color = Color(0xFF8A6D3B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .offset(x = (w * n1 - labelW / 2).coerceIn(0.dp, (w - labelW).coerceAtLeast(0.dp)))
                                .width(labelW),
                        )
                        Text(
                            text = "${"%.0f".format(multibandParams[19])} Hz",
                            fontSize = 10.sp,
                            color = Color(0xFF6B4A8A),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .offset(x = (w * n2 - labelW / 2).coerceIn(0.dp, (w - labelW).coerceAtLeast(0.dp)))
                                .width(labelW),
                        )
                    }
                }
                // ==== 每段 IN/GR/OUT 实时电平表（2026-11-08，按设计稿）====
                // 数据来自引擎的块级计量（multiband.hpp band_in_pk_/gain_db_/band_out_pk_），
                // 经 JNI 一次取 9 个 float。轮询放在页面作用域里，离开页面自动停止。
                run {
                    // meters / LaunchedEffect 已上移到作用域顶部（标题行总 GR 也要用）
                    // 参数展开状态**三段共享**（2026-11-09 用户要求）：点任一段的「更多参数」
                    // 三段同时展开/收起。原来每段独立，会出现「只有中间那段变高」的参差，
                    // 破坏三张卡等高的视觉一致性。
                    var paramsExpandedAll by remember { mutableStateOf(false) }
                    val names = listOf("低频段", "中频段", "高频段")
                    val accents = listOf(Color(0xFF8A6D3B), Color(0xFF3B6A8A), Color(0xFF6B4A8A))
                    // **大气重排（2026-11-08 用户要求）**：三段各自成一张浅色底卡，
                    // 边界靠「面」而不是「一根细线」——原来三段靠 1dp 竖线切分，信息密度高
                    // 但观感像表格；现在每段有独立底色、圆角、色点标题，成为三个并列的仪表区块。
                    // height(IntrinsicSize.Min) 必须保留：三张底卡要等高，否则参差不齐。
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        for (b in 0..2) {
                            val base = b * 6
                            val rThreshold = viewModel.multibandParamRange(base + 0)
                            val rAttack = viewModel.multibandParamRange(base + 2)
                            val rRelease = viewModel.multibandParamRange(base + 3)
                            // 各段出厂默认（对齐 multiband.hpp 的 kDefault*）：
                            // 阈值 -26/-22/-20、Attack 20/15/5、Release 180/150/120。
                            // 双击旋钮即回到这些值——比手动拖回精确得多。
                            val defThreshold = floatArrayOf(-26f, -22f, -20f)[b]
                            val defAttack = floatArrayOf(20f, 15f, 5f)[b]
                            val defRelease = floatArrayOf(180f, 150f, 120f)[b]
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    // 段色 13% 做底（2026-11-09 P1）：7% 在白卡上三段几乎同色，
                                    // 主要靠色点区分；13% 后边界一眼可辨，又不至于压过内容
                                    .background(accents[b].copy(alpha = 0.13f))
                                    // 极淡描边（2026-11-09 用户征询）：底卡在白卡上靠色差定界，
                                    // 强光或低亮度下边缘会发虚；加 1dp 段色 28% 的线后轮廓立住，
                                    // 又因为本身是半透明段色、不是灰线，不会退回「表格感」
                                    .border(
                                        width = 1.dp,
                                        color = accents[b].copy(alpha = 0.28f),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .padding(vertical = 10.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // 段标题（色点 + 名称）上移到卡片顶部，替代原先贴在最底的段名
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(accents[b]),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = names[b],
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accents[b],
                                    )
                                }
                                ScBandMeterGroup(
                                    inPk = meters.inPk(b),
                                    grDb = meters.grDb(b),
                                    outPk = meters.outPk(b),
                                    accent = accents[b],
                                )
                                // 两个旋钮竖排：三段并列时每段仅约 1/3 屏宽，横排会挤在一起
                                ScKnob(
                                    label = "Attack",
                                    value = multibandParams[base + 2],
                                    min = rAttack.start,
                                    max = rAttack.endInclusive,
                                    log = true,
                                    signed = false,
                                    sizeDp = 72,
                                    // 盘面随 compact 变大后，0.5 的字号比例显得偏小，提到 0.62
                                    // 带单位后字符变多（10 → 10ms），0.62 会挤，降到 0.55
                                    textScale = 0.55f,
                                    // 标签也跟着放大（默认 10sp 在 72dp 旋钮下像脚注）
                                    labelSize = 13f,
                                    compact = true,
                                    // 双击归位到该段出厂 Attack
                                    resetValue = defAttack,
                                    format = { "%.0fms".format(it) },
                                    onValueChange = { viewModel.setMultibandParam(base + 2, it) },
                                )
                                ScKnob(
                                    label = "Release",
                                    value = multibandParams[base + 3],
                                    min = rRelease.start,
                                    max = rRelease.endInclusive,
                                    log = true,
                                    signed = false,
                                    sizeDp = 72,
                                    // 盘面随 compact 变大后，0.5 的字号比例显得偏小，提到 0.62
                                    // 带单位后字符变多（10 → 10ms），0.62 会挤，降到 0.55
                                    textScale = 0.55f,
                                    // 标签也跟着放大（默认 10sp 在 72dp 旋钮下像脚注）
                                    labelSize = 13f,
                                    compact = true,
                                    // 双击归位到该段出厂 Release
                                    resetValue = defRelease,
                                    format = { "%.0fms".format(it) },
                                    onValueChange = { viewModel.setMultibandParam(base + 3, it) },
                                )
                                // 阈值旋钮（2026-11-08 用户要求）：它是三段里最常动的参数，
                                // 藏在「标签+可点数字」里每次都要点开输入框，不如直接给个旋钮。
                                ScKnob(
                                    label = "阈值",
                                    value = multibandParams[base + 0],
                                    min = rThreshold.start,
                                    max = rThreshold.endInclusive,
                                    signed = false,
                                    sizeDp = 72,
                                    // 带单位后字符变多（10 → 10ms），0.62 会挤，降到 0.55
                                    textScale = 0.55f,
                                    labelSize = 13f,
                                    compact = true,
                                    // 双击归位到该段出厂阈值
                                    resetValue = defThreshold,
                                    format = { "%.0fdB".format(it) },
                                    onValueChange = { viewModel.setMultibandParam(base + 0, it) },
                                )
                                // 自动增益补偿（2026-11-09 P3）：把该段当前 GR 反向加到段增益上，
                                // 只做一次性应用，不实时跟踪（实时跟会让增益随音乐抖动）。
                                // **常显示**（用户要求）：隐式出现/消失会破坏三段卡的等高与一致性；
                                // GR 接近 0 时置灰不可点，位置与尺寸保持不变。
                                run {
                                    val grNow = meters.grDb(b)
                                    val active = grNow < -1.0f
                                    val tint = if (active) accents[b] else ScColors.TextSub
                                    Text(
                                        text = "补偿 %+.0fdB".format(-grNow),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) tint else tint.copy(alpha = 0.38f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (active) tint.copy(alpha = 0.12f)
                                                else Color.Transparent,
                                            )
                                            .clickable(enabled = active) { viewModel.applyAutoGain(b, grNow) }
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                                // 参数折叠（2026-11-09 改为三段共享）：压缩比/拐点/增益改动频率低，
                                // 默认收起省约 60dp；点任一段三段同步展开，保持三等高。
                                Text(
                                    text = if (paramsExpandedAll) "收起 ▴" else "更多参数 ▾",
                                    fontSize = 10.sp,
                                    color = ScColors.TextSub,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clickable { paramsExpandedAll = !paramsExpandedAll },
                                )
                                if (paramsExpandedAll) {
                                    // 参数块：左右留 6dp 避免贴边
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 6.dp, end = 6.dp, top = 2.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        ScParamValueCell(
                                            "压缩比", multibandParams[base + 1], ":1", 2,
                                            viewModel.multibandParamRange(base + 1),
                                        ) { viewModel.setMultibandParam(base + 1, it) }
                                        ScParamValueCell(
                                            "拐点", multibandParams[base + 4], " dB", 1,
                                            viewModel.multibandParamRange(base + 4),
                                        ) { viewModel.setMultibandParam(base + 4, it) }
                                        ScParamValueCell(
                                            "增益", multibandParams[base + 5], " dB", 1,
                                            viewModel.multibandParamRange(base + 5),
                                        ) { viewModel.setMultibandParam(base + 5, it) }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        // 卡片背景回到默认白卡（2026-11-09 用户要求「改成三段动态的卡片背景」）：
        // 之前展开时整卡铺浅灰 #E8EEF4，白色陶瓷旋钮压在上面显得突兀；
        // 现在与「三段动态」卡片一致，都是标准 ScCard 白底。
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                // 折叠态整卡可点（2026-11-11 用户要求「点卡片上就能动作」）：
                // 与三段卡同一套规则 —— 展开态不加，避免吞掉旋钮/滑块的手势。
                .then(
                    if (!compressorExpanded) Modifier.clickable { compressorExpanded = true }
                    else Modifier,
                ),
        ) {
            Column(
                // 收起时压缩纵向内边距（2026-11-08 用户要求「高度太高」）：
                // 展开时保持 14dp 的舒展留白；收起时卡片里只剩标题行和开关，
                // 14dp 上下留白占掉了整卡高度近一半，故收到 6dp。
                // 只留展开态底边距：收起时卡内只有标题行，不需要额外留白
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (compressorExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 折叠头（2026-11-08 用户要求）：点整行展开/收回。
                // ScSwitch 自身会消费点击、不冒泡到这里的 clickable，所以点开关只切开关。
                // 通用卡头（2026-11-11 抽为 ScSlotHeader）：开关值 = 记忆 && 总闸、
                // 总闸关时置灰、整行可点折叠、行高锁 32dp —— 四件事都在组件里统一。
                ScSlotHeader(
                    title = "压缩器（单段）",
                    checked = compressorEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setCompressorEnabled(!compressorEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = compressorExpanded,
                    onToggleExpand = { compressorExpanded = !compressorExpanded },
                )
                if (compressorExpanded) {
                    // 电平表：复用三段那套 IN/GR/OUT 三竖表 + 磷光溢出，
                    // 数据来自 node 3 的 CompressorNode::GetMeters。
                    val meters by viewModel.compressorMeters.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) {
                        while (true) {
                            viewModel.refreshCompressorMeters()
                            delay(100)  // ~10fps，与三段表同频
                        }
                    }
                    // 【2026-11-09 用户要求】去掉内层「面板 + 描边」，把浅灰背景直接铺满整张卡片
                    // （背景改由 ScCard 的 background 参数承担，仅展开时生效；收起仍是白卡）。
                    // 好处：不必再套一层圆角容器，VFD 窗与旋钮共用同一张底，边界更干净。
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                    // 【合并窗 2026-11-09】电平柱与旋钮组共用**同一块深底窗**，中间不断开。
                    // 之前是两个独立 Box 各自带背景、中间留 14dp 白缝，读起来是「两块贴片」；
                    // 合并后是一整块仪表面板——左半显示、右半操作，中间只留 12dp 呼吸。
                    //
                    // 宽度预算（卡片内约 308dp，减去窗外内边距 20 → 288dp）：
                    // 左栏 **128dp**（三柱各 ~30dp）+ 间距 12dp + 右栏 148dp（两列各 74dp）。
                    // 左栏从 118 抬回 128：26dp 宽柱放 `-25dB` 已经贴边，再长一点就溢出；
                    // 右栏降到 148 后每列 74dp，72dp 旋钮仍有 2dp 余量，刚好容得下。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF46545F))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                    // **整组居中**（2026-11-09 用户要求「整体往右移动，与边框居中」）：
                    // 窗内实际宽度（约 305dp）大于「左 128 + 间距 12 + 右 148 = 288dp」，
                    // 原来右栏用 weight(1f) 会吃下全部剩余 → 右栏内部右边空出一大块，
                    // 柱组仍贴左缘，整组看起来偏左。
                    // 改成两栏都定宽 + Row 居中对齐，左右留白自动相等。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // ── 左栏：三条电平柱（无自身背景，落在合并窗里） ──
                        Box(
                            modifier = Modifier.width(128.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                ScVfdMeterColumn(
                                    label = "IN",
                                    value = meters.getOrElse(0) { 0f },
                                    isGr = false,
                                    dim = !compressorEnabled,
                                    modifier = Modifier.weight(1f),
                                )
                                ScVfdMeterColumn(
                                    label = "GR",
                                    value = meters.getOrElse(1) { 0f },
                                    isGr = true,
                                    dim = !compressorEnabled,
                                    modifier = Modifier.weight(1f),
                                    // 峰值保持（meters[3]）：DSP 侧按 3dB/s 回落
                                    peakDb = meters.getOrElse(3) { 0f },
                                )
                                ScVfdMeterColumn(
                                    label = "OUT",
                                    value = meters.getOrElse(2) { 0f },
                                    isGr = false,
                                    dim = !compressorEnabled,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        // ── 右栏：两组旋钮，**纵向**排列（2026-11-09 用户要求「纵向两组」）──
                        // 组 1「静态曲线」阈值 / 压缩比 / 拐点 —— 决定压多少
                        // 组 2「时间补偿」Attack / Release / 增益 —— 决定怎么压
                        // 每组内部竖着排 3 个、两组左右并排：右栏 182dp / 2 列 → 每列 91dp，
                        // 旋钮可从 52dp 回到 72dp（三列时被压窄，盘面数值与标签都挤）。
                        // 右栏：两组旋钮（无自身背景，与左侧三条柱共用同一块合并窗）。
                        // 盘面用 faceDark 深灰蓝——嵌在面板里，而不是白瓷贴在深灰上。
                        //
                        // **间距必须用固定值而非 SpaceEvenly**（2026-11-09 用户反馈「左窄右宽」）：
                        // 左栏三条柱用 spacedBy(10) 正好占满 128dp、贴住窗内左边界；
                        // 右栏若用 SpaceEvenly，两列 72dp 共 144dp 只占 148dp 中的 144，
                        // 右侧会余 1.33dp，与左栏的「贴死」形成可察觉的不对称。
                        // 改成 spacedBy(4)：144 + 4 = 148，同样贴满两侧，两边同宽。
                        // 定宽 148dp（不再 weight）：撑满会把剩余空间全吃掉，
                        // 内容却只有 144+4，导致右侧空一大截。
                        Box(
                            modifier = Modifier.width(148.dp),
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                    // 六个参数旋钮：两行三列。原来只有阈值/压缩比两个，
                    // Attack / Release / 拐点 / 增益 都已存在于引擎（knee 为本次新增），
                    // 只是 UI 没暴露；补齐后单段与三段具备同等调节能力。
                    // 队列间距由外层面板的 spacedBy(4dp) 提供，这里不再自加上边距
                    // 每组内旋钮**竖着排**（Row → Column）：rowIdx 0 = 组 1、1 = 组 2，
                    // 两组由外层 Row 左右并排，于是形成「两列 × 三行」。
                    for (rowIdx in 0..1) {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (rowIdx == 0) {
                                ScKnob(
                                    label = "阈值",
                                    value = compressorThreshold,
                                    min = -60f,
                                    max = 0f,
                                    signed = false,
                                    sizeDp = 72,
                                    textScale = 0.62f,
                                    labelSize = 13f,
                                    faceDark = true,
                                    labelColor = Color(0xFF8FA3B2),
                                    compact = true,
                                    resetValue = -20f,
                                    format = { "%.0fdB".format(it) },
                                    onValueChange = { viewModel.setCompressorThreshold(it) },
                                )
                                ScKnob(
                                    label = "压缩比",
                                    value = compressorRatio,
                                    min = 1f,
                                    max = 20f,
                                    signed = false,
                                    sizeDp = 72,
                                    textScale = 0.62f,
                                    labelSize = 13f,
                                    faceDark = true,
                                    labelColor = Color(0xFF8FA3B2),
                                    compact = true,
                                    resetValue = 4f,
                                    format = { "%.1f:1".format(it) },
                                    onValueChange = { viewModel.setCompressorRatio(it) },
                                )
                                ScKnob(
                                    label = "拐点",
                                    value = compressorKnee,
                                    min = 0f,
                                    max = 24f,
                                    signed = false,
                                    sizeDp = 72,
                                    textScale = 0.62f,
                                    labelSize = 13f,
                                    faceDark = true,
                                    labelColor = Color(0xFF8FA3B2),
                                    compact = true,
                                    resetValue = 6f,
                                    format = { "%.0fdB".format(it) },
                                    onValueChange = { viewModel.setCompressorKnee(it) },
                                )
                            } else {
                                ScKnob(
                                    label = "Attack",
                                    value = compressorAttack,
                                    min = 0.1f,
                                    max = 100f,
                                    log = true,
                                    signed = false,
                                    sizeDp = 72,
                                    textScale = 0.62f,
                                    labelSize = 13f,
                                    faceDark = true,
                                    labelColor = Color(0xFF8FA3B2),
                                    compact = true,
                                    resetValue = 10f,
                                    format = { "%.0fms".format(it) },
                                    onValueChange = { viewModel.setCompressorAttack(it) },
                                )
                                ScKnob(
                                    label = "Release",
                                    value = compressorRelease,
                                    min = 5f,
                                    max = 1000f,
                                    log = true,
                                    signed = false,
                                    sizeDp = 72,
                                    textScale = 0.62f,
                                    labelSize = 13f,
                                    faceDark = true,
                                    labelColor = Color(0xFF8FA3B2),
                                    compact = true,
                                    resetValue = 150f,
                                    format = { "%.0fms".format(it) },
                                    onValueChange = { viewModel.setCompressorRelease(it) },
                                )
                                ScKnob(
                                    label = "增益",
                                    value = compressorMakeup,
                                    min = -12f,
                                    max = 12f,
                                    signed = true,
                                    sizeDp = 72,
                                    textScale = 0.62f,
                                    labelSize = 13f,
                                    faceDark = true,
                                    labelColor = Color(0xFF8FA3B2),
                                    compact = true,
                                    resetValue = 0f,
                                    format = { "%+.0fdB".format(it) },
                                    onValueChange = { viewModel.setCompressorMakeup(it) },
                                )
                            }
                        }
                    }
                        }
                        }   // 结束右栏 Box
                    }       // 结束左右分栏 Row
                    }       // 结束合并窗 Box（电平柱 + 旋钮组共用）

                    // ---- 高级参数：并联 / 前瞻 / 检测模式 / 过采样 ----
                    // 放在合并窗**下方**并默认展开：这四项是本次新增、用户正要看效果；
                    // 但它们不是「每次调音都要动」的参数，所以给一个可折叠的标题行，
                    // 熟悉后可收起，首屏仍保持「电平 + 六旋钮」的主结构。
                    var advOpen by remember { mutableStateOf(true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clickable { advOpen = !advOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "高级",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = ScColors.TextSub,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (advOpen) "▾" else "▸",
                            fontSize = 12.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    if (advOpen) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Top,
                        ) {
                            ScKnob(
                                label = "并联",
                                value = compressorMix * 100f,
                                min = 0f,
                                max = 100f,
                                signed = false,
                                sizeDp = 64,
                                textScale = 0.5f,
                                labelSize = 11f,
                                compact = true,
                                resetValue = 100f,
                                format = { "%.0f%%".format(it) },
                                onValueChange = { viewModel.setCompressorMix(it / 100f) },
                            )
                            ScKnob(
                                label = "前瞻",
                                value = compressorLookahead,
                                min = 0f,
                                max = 10f,
                                signed = false,
                                sizeDp = 64,
                                textScale = 0.5f,
                                labelSize = 11f,
                                compact = true,
                                resetValue = 0f,
                                format = { "%.1f".format(it) },
                                onValueChange = { viewModel.setCompressorLookahead(it) },
                            )
                        }
                        // 两个二选一
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("检测", fontSize = 11.sp, color = ScColors.TextSub)
                            Spacer(modifier = Modifier.width(6.dp))
                            ScAdvChip("Peak", compressorDetect < 0.5f) { viewModel.setCompressorDetect(0f) }
                            Spacer(modifier = Modifier.width(4.dp))
                            ScAdvChip("RMS", compressorDetect >= 0.5f) { viewModel.setCompressorDetect(1f) }
                            Spacer(modifier = Modifier.weight(1f))
                            Text("过采样", fontSize = 11.sp, color = ScColors.TextSub)
                            Spacer(modifier = Modifier.width(6.dp))
                            ScAdvChip("关", compressorOversample < 1.5f) { viewModel.setCompressorOversample(1f) }
                            Spacer(modifier = Modifier.width(4.dp))
                            ScAdvChip("2x", compressorOversample >= 1.5f) { viewModel.setCompressorOversample(2f) }
                        }
                        // 侧链高通（2026-11-11 补 UI）：档位而非连续旋钮 —— 常用值就这几个，
                        // 且「关 / 80 / 120 / 200」四种取舍比拖旋钮更容易理解：
                        //   关   低频也参与触发（低频动态大时反而更稳，电子乐常用）
                        //   80  轻度，只切掉次低频（播客/男声，保住 85~180Hz 的检测）
                        //   120 默认，人声流行/摇滚的通用值
                        //   200 激进，只让中高频触发（低频完全不受控，注意后级限幅器）
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("侧链高通", fontSize = 11.sp, color = ScColors.TextSub)
                            Spacer(modifier = Modifier.width(6.dp))
                            for ((hz, label) in listOf(0f to "关", 80f to "80", 120f to "120", 200f to "200")) {
                                ScAdvChip(
                                    text = label,
                                    active = kotlin.math.abs(compressorSidechainHp - hz) < 1f,
                                ) { viewModel.setCompressorSidechainHp(hz) }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                        // 前瞻会引入等量延迟，这条提示必须常驻 —— 用户开了发现「声画不同步」
                        // 却不知道原因，是最难自证的那种问题
                        if (compressorLookahead > 0.05f) {
                            Text(
                                text = "前瞻会引入约 ${"%.1f".format(compressorLookahead)}ms 输出延迟",
                                fontSize = 10.sp,
                                color = ScColors.TextSub,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    }   // 结束「VFD + 旋钮」视觉单元面板
                }
            }
        }
    }
}

/** U7 立体声宽度详情内容：M/S 矩阵宽度滑块（原主页宽度区迁入；0=单声道 1=原样 2=加倍） */
@Composable
internal fun ScWidthDetailContent(viewModel: MainViewModel) {
    val stereoWidthEnabled by viewModel.stereoWidthEnabled.collectAsStateWithLifecycle()
    val stereoWidth by viewModel.stereoWidth.collectAsStateWithLifecycle()
    val widthExpanded = rememberExpandedFollowing(stereoWidthEnabled, "width_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "宽度：${"%.2f".format(stereoWidth)}×",
                checked = stereoWidthEnabled,
                onCheckedChange = { viewModel.setStereoWidthEnabled(!stereoWidthEnabled) },
                switchEnabled = true,
                expanded = widthExpanded.value,
                onToggleExpand = { widthExpanded.value = !widthExpanded.value },
                showSwitch = false,
            )
            if (widthExpanded.value) {
                Column(
                    modifier = Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Slider(
                        value = stereoWidth,
                        onValueChange = { viewModel.setStereoWidth(it) },
                        valueRange = 0f..2f,
                        enabled = stereoWidthEnabled,
                    )
                    Text(
                        text = "M/S 矩阵：0=单声道，1=原样，2=加倍展宽。拖动实时生效（30ms 平滑无爆音）。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
            }
        }
    }
}

/** U7 算法混响（FDN）详情内容：六参数滑块（原主页 FDN 区迁入） */
@Composable
internal fun ScFdnDetailContent(viewModel: MainViewModel) {
    val fdnRoomSize by viewModel.fdnRoomSize.collectAsStateWithLifecycle()
    val fdnHumidity by viewModel.fdnHumidity.collectAsStateWithLifecycle()
    val fdnStereoWidth by viewModel.fdnStereoWidth.collectAsStateWithLifecycle()
    val fdnWet by viewModel.fdnWet.collectAsStateWithLifecycle()
    val fdnDry by viewModel.fdnDry.collectAsStateWithLifecycle()
    val fdnMode by viewModel.fdnMode.collectAsStateWithLifecycle()
    // **不再套 ScCard**（2026-11-11 用户要求「参数调节滑块区域不用边框包裹」）：
    // 这个函数现在是混响组详情页里的**槽卡内容**，外层槽卡已经有白卡外壳（描边 + 阴影），
    // 里面再包一层 ScCard 就成了卡片套卡片 —— 滑块四周会多出一圈描边和一圈内缩。
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "房间尺寸：${"%.2f".format(fdnRoomSize)}", fontSize = 12.sp, color = ScColors.TextMain)
            Slider(value = fdnRoomSize, onValueChange = viewModel::setFdnRoomSize, valueRange = 0f..1f)
            // 标签从「湿度/阻尼」改为「高频阻尼」：natively
            // `damp = 0.9 - humidity * 0.65`，数值越大 → 阻尼越强 → 高频衰减越快、尾巴越暗。
            // 叫「湿度」在物理上是反的（湿度大应该更"湿"、尾巴更长），而且和右侧已有的
            // 「湿声 / 干声」两个混音比例混在一起，用户会以为是同一类东西。
            Text(text = "高频阻尼：${"%.2f".format(fdnHumidity)}（越大尾巴越暗）", fontSize = 12.sp, color = ScColors.TextMain)
            Slider(value = fdnHumidity, onValueChange = viewModel::setFdnHumidity, valueRange = 0f..1f)
            Text(text = "立体声宽度：${"%.2f".format(fdnStereoWidth)}", fontSize = 12.sp, color = ScColors.TextMain)
            Slider(value = fdnStereoWidth, onValueChange = viewModel::setFdnStereoWidth, valueRange = 0f..1f)
            Text(text = "湿声：${"%.2f".format(fdnWet)}", fontSize = 12.sp, color = ScColors.TextMain)
            Slider(value = fdnWet, onValueChange = viewModel::setFdnWet, valueRange = 0f..1f)
            Text(text = "干声：${"%.2f".format(fdnDry)}", fontSize = 12.sp, color = ScColors.TextMain)
            Slider(value = fdnDry, onValueChange = viewModel::setFdnDry, valueRange = 0f..1f)
            Text(
                text = "拓扑档位：${if (fdnMode >= 0.5f) "轻量 4+2" else "全规格 8+4"}",
                fontSize = 12.sp,
                color = ScColors.TextMain,
            )
            Slider(value = fdnMode, onValueChange = viewModel::setFdnMode, valueRange = 0f..1f, steps = 1)
            // 文案修正：原文两处不准确 ——
            //   ① 「8 梳状」：FDN 里是**延迟线**经反馈矩阵互相耦合；
            //      「梳状（comb）」是 Schroeder 混响结构的说法，混用会误导懂行的人。
            //   ② 「LFO 拍频打散」：不是标准表述。标准说法是「LFO 调制延迟长度、
            //      抑制金属声（metallic artifacts）」，作用是打散模态共振，与"拍频"无关。
            Text(
                text = "反馈延迟网络（FDN）：每声道 8 条延迟线 + 4 级全通扩散（轻量档 4+2），" +
                    "配分数延迟插值、高频阻尼、DC 阻断，并以 LFO 微调延迟长度抑制金属声。",
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
    }
}

/**
 * 低音保护详情内容（2026-11-11，原名「动态低音」）。
 *
 * 改名原因：这个效果器**开/关几乎听不出差别** —— 它实际做的是次低频清理与
 * 低频峰值保护，中间的「动态增强」实测最多 0.65dB（人耳低频可辨阈值 1~3dB）。
 * 所以按实际职责命名为「低音保护」，并把两个**无效且冗余**的增强旋钮
 * （强度 / 动态深度，乘积关系）从界面移除。
 *
 * 设计见 `docs/M15_DYNAMIC_BASS_DESIGN.md`。
 *
 * 四个旋钮 + 一档次低频 chip。刻意**不做**"心理声学谐波扩展"（会让 120~360Hz 出现
 * 谐波、污染人声），所以这里没有"下潜感"旋钮 —— 耳机物理上放不出的频段不会被变出来。
 */
@Composable
internal fun ScDynamicBassDetailContent(viewModel: MainViewModel) {
    val amount by viewModel.dynamicBassAmount.collectAsStateWithLifecycle()
    val subsonic by viewModel.dynamicBassSubsonic.collectAsStateWithLifecycle()
    val focus by viewModel.dynamicBassFocus.collectAsStateWithLifecycle()
    val boostDepth by viewModel.dynamicBassBoostDepth.collectAsStateWithLifecycle()
    val protect by viewModel.dynamicBassProtect.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 只保留「低音频率」与「保护」两个旋钮（2026-11-11 用户反馈
            // 「开与不开都没什么效果」后精简）。
            //
            // 移除的两个是「强度」与「动态深度」：它们在 DSP 里是
            // `max_boost = boost_depth × 12dB × amount` 的**乘积关系**，
            // 调哪个都一样；而且实测增强量最多 0.65dB（人耳低频可辨阈值 1~3dB），
            // 留着只会让人反复调、反复失望。
            // 对应的 prefs 键与 DSP 参数**保留不动**（老用户的值不丢，
            // 未来若要把增强做起来也不必迁移）。
            ScKnob(
                label = "低音频率",
                value = focus,
                min = 40f, max = 150f,
                signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                compact = true, resetValue = 80f,
                format = { "%.0f".format(it) },
                onValueChange = { viewModel.setDynamicBassFocus(it) },
            )
            ScKnob(
                label = "保护",
                value = protect * 100f,
                min = 0f, max = 100f,
                signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                compact = true, resetValue = 60f,
                format = { "%.0f%%".format(it) },
                onValueChange = { viewModel.setDynamicBassProtect(it / 100f) },
            )
        }

        // 次低频清理：档位而非旋钮 —— 只有「关 / 30 / 40 / 50」这几个有意义的取舍，
        // 连续拖动只会让用户纠结 32 和 35 有什么区别
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("次低频清理", fontSize = 11.sp, color = ScColors.TextSub)
            Spacer(modifier = Modifier.width(6.dp))
            for (hz in listOf(0f, 30f, 40f, 50f)) {
                ScAdvChip(
                    text = if (hz == 0f) "关" else "%.0f".format(hz),
                    active = kotlin.math.abs(subsonic - hz) < 0.5f,
                ) { viewModel.setDynamicBassSubsonic(hz) }
                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        Text(
            // 注意：Compose `Text` **不解析 Markdown**，这里不能写 `**粗体**`
            // （2026-11-11 踩过：界面上直接显示了星号）。强调靠文字本身。
            text = "本效果器不做增强，只做两件事：① 次低频清理 —— 切掉耳机/喇叭放不出的" +
                "极低频，把单元行程留给听得到的频段；② 峰值保护 —— 分频点以下的低频超过阈值" +
                "时压住，防止小尺寸单元机械过载。要「听得出低音变多」请用低音增强。",
            fontSize = 11.sp,
            color = ScColors.TextSub,
        )
    }
}

/**
 * 低音增强详情内容。移植自 wecho 的「低频质感」/ `BassEffect`。
 * 作为「低频管家」组里的**槽卡内容**渲染，自身不包 ScCard。
 *
 * 三个旋钮对应 wecho 的三个参数（gain → amount 归一化到 0~1）：
 *   · 强度：EQ（60Hz shelf + 45Hz peak）与动态增益、次谐波的总量
 *   · 下潜：动态增益的深度 + 次谐波注入量 —— 越大越"沉"
 *   · 冲击：一阶差分放大，越大鼓点越"实"
 */
@Composable
internal fun ScBassEnhanceDetailContent(viewModel: MainViewModel) {
    val amount by viewModel.bassEnhanceAmount.collectAsStateWithLifecycle()
    val deep by viewModel.bassEnhanceDeep.collectAsStateWithLifecycle()
    val punch by viewModel.bassEnhancePunch.collectAsStateWithLifecycle()
    val mode by viewModel.bassEnhanceMode.collectAsStateWithLifecycle()
    val classicGain by viewModel.bassEnhanceClassicGain.collectAsStateWithLifecycle()
    val classicQ by viewModel.bassEnhanceClassicQ.collectAsStateWithLifecycle()
    val classicCenter by viewModel.bassEnhanceClassicCenter.collectAsStateWithLifecycle()

    val isClassic = mode == AudioEngine.BASS_ENHANCE_MODE_CLASSIC

    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 模式切换：两套参数**完全独立**，切换不互相覆盖。
        // 经典 = 低通 + 按 dB 叠加（音色传统、低频变厚）；动态 = M/S + 包络 +
        // 瞬态 + 次谐波（自适应、带打击感）。两者听感差别很大。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScAdvChip(
                text = "经典",
                active = isClassic,
                onClick = {
                    viewModel.setBassEnhanceMode(AudioEngine.BASS_ENHANCE_MODE_CLASSIC)
                },
            )
            ScAdvChip(
                text = "动态",
                active = !isClassic,
                onClick = {
                    viewModel.setBassEnhanceMode(AudioEngine.BASS_ENHANCE_MODE_DYNAMIC)
                },
            )
        }

        if (isClassic) {
            // ---- 经典模式：三个旋钮（增益 / 频率 / Q）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ScKnob(
                    label = "增益",
                    value = classicGain,
                    min = 0f, max = 15f,
                    signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                    compact = true, resetValue = 6f,
                    format = { "%.0fdB".format(it) },
                    onValueChange = { viewModel.setBassEnhanceClassicGain(it) },
                )
                ScKnob(
                    label = "频率",
                    value = classicCenter,
                    min = 30f, max = 120f,
                    signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                    compact = true, resetValue = 60f,
                    format = { "%.0f".format(it) },
                    onValueChange = { viewModel.setBassEnhanceClassicCenter(it) },
                )
                ScKnob(
                    label = "Q",
                    value = classicQ,
                    min = 0.3f, max = 2.0f,
                    signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                    compact = true, resetValue = 0.7f,
                    format = { "%.2f".format(it) },
                    onValueChange = { viewModel.setBassEnhanceClassicQ(it) },
                )
            }
            Text(
                text = "经典模式：把「频率」以下的低频单独滤出来，按「增益」叠加回原信号。" +
                    "做法朴素直接，低频厚实、略带糊感。Q 越高，截止点附近越「鼓」。" +
                    "这三个参数与动态模式完全独立，切换不会互相覆盖。",
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
        } else {
            // ---- 动态模式：三个旋钮（强度 / 下潜 / 冲击）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ScKnob(
                    label = "强度",
                    value = amount * 100f,
                    min = 0f, max = 100f,
                    signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                    compact = true, resetValue = 50f,
                    format = { "%.0f%%".format(it) },
                    onValueChange = { viewModel.setBassEnhanceAmount(it / 100f) },
                )
                ScKnob(
                    label = "下潜",
                    value = deep * 100f,
                    min = 0f, max = 100f,
                    signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                    compact = true, resetValue = 30f,
                    format = { "%.0f%%".format(it) },
                    onValueChange = { viewModel.setBassEnhanceDeep(it / 100f) },
                )
                ScKnob(
                    label = "冲击",
                    value = punch * 100f,
                    min = 0f, max = 100f,
                    signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                    compact = true, resetValue = 60f,
                    format = { "%.0f%%".format(it) },
                    onValueChange = { viewModel.setBassEnhancePunch(it / 100f) },
                )
            }
            Text(
                text = "动态模式：低频 EQ（60Hz 搁架 + 45Hz 钟形）叠加动态补偿 —— 音量越小" +
                    "补得越多、越大几乎不动。下潜额外生成低八度次谐波，冲击放大瞬态沿" +
                    "让鼓点更「实」。只叠加增量、不替换原信号，中高频基本不动。",
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
        }
    }
}

/**
 * 虚拟低音详情内容。作为「低频管家」组里的**槽卡内容**渲染，
 * 所以自身不包 ScCard —— 外层槽卡已有白卡外壳。
 *
 * 五个旋钮的措辞都避开了"低频增益"这类误导：这个效果器**不增加真低频**，
 * 它只是把放不出的低频"翻译"成放得出的谐波。
 */
@Composable
internal fun ScVirtualBassDetailContent(viewModel: MainViewModel) {
    val amount by viewModel.virtualBassAmount.collectAsStateWithLifecycle()
    val harmonic by viewModel.virtualBassHarmonic.collectAsStateWithLifecycle()
    val midGain by viewModel.virtualBassMidGain.collectAsStateWithLifecycle()
    val highGain by viewModel.virtualBassHighGain.collectAsStateWithLifecycle()
    val envRate by viewModel.virtualBassEnvelopeRate.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 梅花五点布局：四个塑形旋钮占四角，跟随速度居中心。
        // 为什么用 Box 叠放而不是两行 Row：中心旋钮要落在两行之间的空隙里，
        // 用 Column 排两行 + Box 居中对齐即可自然形成五点结构，不需要手算偏移。
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ScKnob(
                        label = "强度",
                        value = amount * 100f,
                        min = 0f, max = 100f,
                        signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                        compact = true, resetValue = 100f,
                        format = { "%.0f%%".format(it) },
                        onValueChange = { viewModel.setVirtualBassAmount(it / 100f) },
                    )
                    // 三等分占位（宽度 = 旋钮宽度）：三槽等宽 + SpaceEvenly，
                    // 于是左/右列中心精确落在 1/6 与 5/6，与上方三旋钮的第 1、3 只对齐
                    Spacer(Modifier.width(83.dp))
                    ScKnob(
                        label = "谐波量",
                        value = harmonic * 100f,
                        min = 0f, max = 200f,
                        signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                        compact = true, resetValue = 100f,
                        format = { "%.0f%%".format(it) },
                        onValueChange = { viewModel.setVirtualBassHarmonic(it / 100f) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ScKnob(
                        label = "中频直通",
                        value = midGain * 100f,
                        min = 0f, max = 100f,
                        signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                        compact = true, resetValue = 8f,
                        format = { "%.0f%%".format(it) },
                        onValueChange = { viewModel.setVirtualBassMidGain(it / 100f) },
                    )
                    Spacer(Modifier.width(83.dp))
                    ScKnob(
                        label = "高频直通",
                        value = highGain * 100f,
                        min = 0f, max = 100f,
                        signed = false, sizeDp = 83, textScale = 0.5f, labelSize = 14f,
                        compact = true, resetValue = 16f,
                        format = { "%.0f%%".format(it) },
                        onValueChange = { viewModel.setVirtualBassHighGain(it / 100f) },
                    )
                }
            }
            // 中心：跟随速度（速率参数，视觉上作为这一组的枢纽）
            ScKnob(
                label = "跟随速度",
                value = envRate,
                min = 5f, max = 150f,
                signed = false, sizeDp = 86, textScale = 0.5f, labelSize = 14f,
                compact = true, resetValue = 50f,
                format = { "%.0f".format(it) },
                modifier = Modifier.align(Alignment.Center),
                onValueChange = { viewModel.setVirtualBassEnvelopeRate(it) },
            )
        }

        Text(
            text = "把外放放不出的 80~150Hz 翻译成能放出的 160~300Hz 谐波，让大脑" +
                "「补」出低频。强度是干湿比：0 = 纯原声、100% = 只留谐波。" +
                "中/高频直通量控制原声中段与高段保留多少。",
            fontSize = 11.sp,
            color = ScColors.TextSub,
        )
    }
}

/**
 * 低频管家详情：动态低音 + 低音增强 + 虚拟低音。
 *
 * 前两个**与输出设备无关**（分频重构 / M/S+EQ+瞬态），第三个**只适合外放**。
 *
 * **三者可以同时开启**（2026-11-11 取消互斥）：叠加听感交给用户判断。
 * 唯一的设备约束 —— 耳机输出时虚拟低音默认关，且每次开启弹风险提示。
 *
 * 虚拟低音带**按设备的软性排斥**：耳机输出时点开会弹一次风险提示，
 * 用户选「继续使用」本次生效但下次还提示；选「关闭提醒」则记住、不再打扰。
 * **不是硬禁止** —— 用户有最终决定权。
 */
@Composable
internal fun ScLowfreqGroupDetail(viewModel: MainViewModel) {
    val dynamicBassEnabled by viewModel.dynamicBassEnabled.collectAsStateWithLifecycle()
    val virtualBassEnabled by viewModel.virtualBassEnabled.collectAsStateWithLifecycle()
    val sectionEnabled by viewModel.lowfreqSectionEnabled.collectAsStateWithLifecycle()
    val bassEnhanceEnabled by viewModel.bassEnhanceEnabled.collectAsStateWithLifecycle()

    // 折叠默认跟随各自开关：开 -> 展开，关 -> 收起
    val dynGate = dynamicBassEnabled && sectionEnabled
    val beGate = bassEnhanceEnabled && sectionEnabled
    val vbGate = virtualBassEnabled && sectionEnabled
    var dynExpanded by rememberExpandedFollowing(dynGate, "dyn")
    var beExpanded by rememberExpandedFollowing(beGate, "be")
    var vbExpanded by rememberExpandedFollowing(vbGate, "vb")
    var showHeadphoneWarning by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- 槽 1：动态低音（耳机）----
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!dynExpanded) Modifier.clickable { dynExpanded = true } else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (dynExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 标题改名「动态低音」→「低音保护」（2026-11-11 用户反馈）：
                // 这个效果器**开/关几乎听不出差别**，因为它实际在做的是
                // ① 次低频清理（切掉耳机放不出的 30Hz 以下）② 低频峰值保护（防单元过载）。
                // 中间的「动态增强」实测最多只有 +0.65dB，远低于人耳低频可辨阈值
                // （1~3dB），所以它**不是低音增强器**，叫"动态低音"名不副实。
                // 设备无关（分频重构 + 保护，与耳机/外放无关）。
                ScSlotHeader(
                    title = "低音保护",
                    checked = dynamicBassEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setDynamicBassEnabled(!dynamicBassEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = dynExpanded,
                    onToggleExpand = { dynExpanded = !dynExpanded },
                )
                if (dynExpanded) {
                    if (!sectionEnabled) {
                        Text(
                            text = "低频管家总开关已关闭——整体旁通。到音效库打开后，" +
                                "两张卡按各自开关恢复。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    } else if (!dynamicBassEnabled) {
                        Text(
                            text = "当前旁路——参数可预调，开启后生效。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    ScDynamicBassDetailContent(viewModel)
                }
            }
        }

        // ---- 槽 2：低音增强（设备无关，移植自 wecho「低频质感」）----
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!beExpanded) Modifier.clickable { beExpanded = true } else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (beExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScSlotHeader(
                    title = "低音增强",
                    checked = bassEnhanceEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setBassEnhanceEnabled(!bassEnhanceEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = beExpanded,
                    onToggleExpand = { beExpanded = !beExpanded },
                )
                if (beExpanded) {
                    if (!sectionEnabled) {
                        Text(
                            text = "低频管家总开关已关闭——整体旁通。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    } else if (!bassEnhanceEnabled) {
                        Text(
                            text = "当前旁路——参数可预调，开启后生效。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    ScBassEnhanceDetailContent(viewModel)
                }
            }
        }

        // ---- 槽 3：虚拟低音（外放）----
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!vbExpanded) Modifier.clickable { vbExpanded = true } else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (vbExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScSlotHeader(
                    title = "虚拟低音 · 外放",
                    checked = virtualBassEnabled && sectionEnabled,
                    onCheckedChange = {
                        if (virtualBassEnabled) {
                            viewModel.setVirtualBassEnabled(false)
                        } else if (viewModel.isHeadphoneOutput()) {
                            // 耳机下**每次**开启都弹提示（2026-11-11 用户要求）：
                            // 不做「确认一次就不再提醒」的记忆 —— 这个效果器在耳机上的
                            // 副作用是持续的，用户每次开启前都该被提醒一次。
                            showHeadphoneWarning = true
                        } else {
                            // 外放：直接开启，不打扰
                            viewModel.setVirtualBassEnabled(true)
                        }
                    },
                    switchEnabled = sectionEnabled,
                    expanded = vbExpanded,
                    onToggleExpand = { vbExpanded = !vbExpanded },
                )
                if (vbExpanded) {
                    if (!sectionEnabled) {
                        Text(
                            text = "低频管家总开关已关闭——整体旁通。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    } else if (!virtualBassEnabled) {
                        Text(
                            text = "当前旁路——参数可预调，开启后生效。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    ScVirtualBassDetailContent(viewModel)
                }
            }
        }
    }

    // ---- 耳机下的风险提示（2026-11-11，参照 wecho 的交互）----
    if (showHeadphoneWarning) {
        AlertDialog(
            onDismissRequest = { showHeadphoneWarning = false },   // 点外部 = 取消，不开启
            title = { Text("虚拟低音更适合外放", fontSize = 16.sp) },
            text = {
                Text(
                    text = "该算法通过心理声学模拟增强低频效果，更适合小型扬声器外放。" +
                        "耳机使用时易出现低频浑浊、失真，耳机模式请尽量关闭此功能。",
                    fontSize = 13.sp,
                    color = ScColors.TextSub,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setVirtualBassEnabled(true)
                        showHeadphoneWarning = false
                    },
                ) { Text("继续使用") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // 「关闭提醒」= **不开启**（2026-11-11 对齐
                        // `wecho_projectV2.5.1_studio_rack` 的语义）。
                        //
                        // 之前我把它实现成「开启 + 记住不再提示」，那是理解错了：
                        // 那边 `pop(false)` 之后根本不调用 update(enabled, true)。
                        // 文案叫「关闭提醒」，实际作用是取消这次操作。
                        showHeadphoneWarning = false
                    },
                ) { Text("关闭提醒") }
            },
        )
    }
}

/** U7b 限幅器详情内容：输出上限（dBFS）硬钳位——末级防削波安全网 */
@Composable
internal fun ScLimiterDetailContent(viewModel: MainViewModel) {
    val limiterEnabled by viewModel.limiterEnabled.collectAsStateWithLifecycle()
    val limiterCeiling by viewModel.limiterCeiling.collectAsStateWithLifecycle()
    val ceilingExpanded = rememberExpandedFollowing(limiterEnabled, "limiter_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "输出上限：${"%.1f".format(limiterCeiling)} dBFS",
                checked = limiterEnabled,
                onCheckedChange = { viewModel.setLimiterEnabled(!limiterEnabled) },
                switchEnabled = true,
                expanded = ceilingExpanded.value,
                onToggleExpand = { ceilingExpanded.value = !ceilingExpanded.value },
                showSwitch = false,
            )
            if (ceilingExpanded.value) {
                Column(
                    modifier = Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Slider(
                        value = limiterCeiling,
                        onValueChange = viewModel::setLimiterCeiling,
                        valueRange = -12f..0f,
                        enabled = limiterEnabled,
                    )
                    Text(
                        text = "超过上限的采样被硬钳制到天花板，杜绝削波爆音。与压缩器分层工作：" +
                            "压缩器先压平动态范围，限幅器只兜瞬时峰值，明显减少呼吸感。" +
                            "配合输出增益（响度补偿）使用：先补偿响度，再由限幅器收口。" +
                            "look-ahead 2.5ms 预读：EQ 增益拉大或源音量突增时保护听感仍生效。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
            }
        }
    }
    // ---- U9m 静音门限（自音效库列表迁入：门限与输出链收口同属一处） ----
    val gateThreshold by viewModel.gateThreshold.collectAsStateWithLifecycle()
    ScCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "静音门限：${if (gateThreshold <= 0f) "禁用（推荐）" else "%.4f RMS".format(gateThreshold)}",
                fontSize = 12.sp,
                color = ScColors.TextMain,
            )
            Slider(
                value = gateThreshold,
                onValueChange = viewModel::setGateThreshold,
                valueRange = 0f..0.05f,
                enabled = true,
            )
            Text(
                text = "输入电平低于门限时输出平滑静音。注意：流媒体源（抖音等）弱电平人声段常低于门限被切断，" +
                    "与混响叠加会形成「干声断了尾巴还在」的断裂听感——一般保持禁用，仅在源底噪明显时小幅启用。",
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
        }
    }
}

/**
 * 混响组详情：**卷积混响 + 算法混响** 两张槽卡。
 *
 * 结构照搬压限器详情页 —— 槽卡头的四件事（开关 = 记忆 && 组总闸、总闸关时置灰、
 * 整行可点折叠、行高 32dp）全部在 [ScSlotHeader] 里，这里只负责排版。
 * 两个混响**不互斥**：卷积给空间底、算法补尾巴是常见用法。
 */
@Composable
internal fun ScReverbGroupDetail(viewModel: MainViewModel) {
    val reverbEnabled by viewModel.reverbEnabled.collectAsStateWithLifecycle()
    val fdnEnabled by viewModel.fdnEnabled.collectAsStateWithLifecycle()
    val sectionEnabled by viewModel.reverbSectionEnabled.collectAsStateWithLifecycle()
    // 折叠默认跟随各自开关：开 -> 展开，关 -> 收起
    val convGate = reverbEnabled && sectionEnabled
    val fdnGate = fdnEnabled && sectionEnabled
    var convExpanded by rememberExpandedFollowing(convGate, "conv")
    var fdnExpanded by rememberExpandedFollowing(fdnGate, "fdn")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!convExpanded) Modifier.clickable { convExpanded = true } else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(
                    // 左右 14 → 4dp（2026-11-11 用户要求收窄）：
                    // ScCard 自带 16dp，再叠 14dp 会让内层卡片的可用宽度少掉 30dp/侧
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (convExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScSlotHeader(
                    title = "卷积混响",
                    checked = reverbEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setReverbEnabled(!reverbEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = convExpanded,
                    onToggleExpand = { convExpanded = !convExpanded },
                )
                if (convExpanded) {
                    if (!sectionEnabled) {
                        Text(
                            text = "混响总开关已关闭——整体旁通。到音效库打开「混响」后，" +
                                "两张卡按各自开关恢复。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    } else if (!reverbEnabled) {
                        Text(
                            text = "当前旁路——参数可预调，开启后生效。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    ScConvolverContent(viewModel)
                }
            }
        }
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!fdnExpanded) Modifier.clickable { fdnExpanded = true } else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(
                    // 左右 14 → 4dp（2026-11-11 用户要求收窄）：
                    // ScCard 自带 16dp，再叠 14dp 会让内层卡片的可用宽度少掉 30dp/侧
                    start = 20.dp,
                    end = 20.dp,
                    bottom = if (fdnExpanded) 20.dp else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScSlotHeader(
                    title = "算法混响",
                    checked = fdnEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setFdnEnabled(!fdnEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = fdnExpanded,
                    onToggleExpand = { fdnExpanded = !fdnExpanded },
                )
                if (fdnExpanded) {
                    if (!sectionEnabled) {
                        Text(
                            text = "混响总开关已关闭——整体旁通。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    } else if (!fdnEnabled) {
                        Text(
                            text = "当前旁路——参数可预调，开启后生效。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    ScFdnDetailContent(viewModel)
                }
            }
        }
    }
}

/**
 * 卷积混响的**内容**（2026-11-11 抽出，不含页头）。
 *
 * 为什么去掉页头与开关：它现在作为「混响」效果组的一个槽卡渲染，
 * 页头由 ScEffectDetailPage 提供、开关由 ScSlotHeader 提供 —— 同一个开关
 * 若在两处各画一个，会出现两个视觉上不同步的控件。
 */
@Composable
internal fun ScConvolverContent(viewModel: MainViewModel) {
    val reverbEnabled by viewModel.reverbEnabled.collectAsStateWithLifecycle()
    val reverbPreset by viewModel.reverbPreset.collectAsStateWithLifecycle()
    val activeRecentIrName by viewModel.activeRecentIrName.collectAsStateWithLifecycle()
    val trimSickHead by viewModel.trimSickHead.collectAsStateWithLifecycle()
    val wetAutoCal by viewModel.wetAutoCal.collectAsStateWithLifecycle()
    val userIrAnalysis by viewModel.userIrAnalysis.collectAsStateWithLifecycle()
    val userWetGain by viewModel.userWetGain.collectAsStateWithLifecycle()
    val reverbDry by viewModel.reverbDry.collectAsStateWithLifecycle()
    val reverbLowCut by viewModel.reverbLowCut.collectAsStateWithLifecycle()
    val reverbPresetNames = viewModel.reverbPresetNames
    val irChannelCount = userIrAnalysis?.getOrNull(1027)?.toInt() ?: 0
    val pillShape = RoundedCornerShape(99.dp)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // U8k 脉冲库区（原 P3 搜索 Tab 整体迁入）：检索/导入导出/最近使用/全部文件，选 IR 不切 Tab
        ScIrLibraryContent(viewModel = viewModel)

        // 脉冲预设卡（U8r：预设一行等宽 + 自定义 IR 第二行全宽，替代横滑参差排布；选中=青绿渐变白字，未选=凹面灰字）
        ScCard(modifier = Modifier.fillMaxWidth(), elevated = false) {
            Column {
                ScCardHeader(title = "脉冲预设")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
                ) {
                    for (preset in 1..4) {
                        val selected = reverbPreset == preset && reverbEnabled
                        Text(
                            text = reverbName(preset, reverbPresetNames),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else ScColors.TextSub,
                            // 四等分 chip：窄屏预设名较长时不折成两行（2026-11-07 批次 2）
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(pillShape)
                                .then(
                                    if (selected) {
                                        Modifier.background(ScPrimaryBrush)
                                    } else {
                                        Modifier.background(ScColors.Inset)
                                    },
                                )
                                .clickable { viewModel.setReverbPreset(preset) }
                                .padding(horizontal = 2.dp, vertical = 7.dp),
                        )
                    }
                }
                val customSelected = reverbPreset == 5 && reverbEnabled
                Text(
                    text = if (customSelected) "自定义 IR ✓" else "自定义 IR",
                    fontSize = 12.sp,
                    fontWeight = if (customSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (customSelected) Color.White else ScColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                        .clip(pillShape)
                        .then(
                            if (customSelected) {
                                Modifier.background(ScPrimaryBrush)
                            } else {
                                Modifier.background(ScColors.Inset)
                            },
                        )
                        .clickable { viewModel.setReverbPreset(5) }
                        .padding(vertical = 7.dp),
                )
            }
        }

        // 参数卡：三拟物旋钮（ScKnob 公共组件）+ 干湿混合 blend 条（设计稿 P2 参数卡）
        ScCard(modifier = Modifier.fillMaxWidth(), elevated = false) {
            Column(modifier = Modifier.padding(14.dp)) {
                // U6 header；U8t：「平滑斜坡」提示徽章退役（纯实现细节无操作价值，用户拍板不暴露）
                Text(
                    text = "参数",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScColors.TextMain,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                // U8u：三旋钮自适应撑满三等分列宽（上限 104dp），不再固定 64dp
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    @Composable
                    fun KnobSlot(modifier: Modifier, content: @Composable (Int) -> Unit) {
                        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
                            content(maxWidth.value.toInt().coerceAtMost(104))
                        }
                    }
                    // U8w：盘面数值字号压小（textScale 0.75，旋钮撑大后默认字号过大）
                    KnobSlot(modifier = Modifier.weight(1f)) { size ->
                        ScKnob(
                            label = "湿声电平",
                            value = userWetGain,
                            min = 0f,
                            max = 2f,
                            signed = false,
                            sizeDp = size,
                            textScale = 0.75f,
                            // >1.0 后与干声叠加会逼近满刻度 → 过载视觉预警
                            overloadFrom = 1.0f,
                            // 双击归位到归一基准 1.0
                            resetValue = 1.0f,
                            onValueChange = { viewModel.setUserWetGain(it) },
                        )
                    }
                    KnobSlot(modifier = Modifier.weight(1f)) { size ->
                        ScKnob(
                            label = "干声电平",
                            value = reverbDry,
                            min = 0f,
                            max = 1.2f,
                            signed = false,
                            sizeDp = size,
                            textScale = 0.75f,
                            // 双击归位到 1.0 = 使用预设自带干声基准（各预设 kDryGain 不同，
                            // 1.0 表示不额外增减）——与湿声电平口径一致
                            resetValue = 1.0f,
                            onValueChange = { viewModel.setReverbDry(it) },
                        )
                    }
                    val lowCutSteps = floatArrayOf(0f, 80f, 120f, 160f, 200f, 240f, 300f)
                    val lowCutIdx = lowCutSteps.indexOfLast { it <= reverbLowCut + 0.5f }.coerceAtLeast(0)
                    KnobSlot(modifier = Modifier.weight(1f)) { size ->
                        ScKnob(
                            label = "湿声低切",
                            value = lowCutIdx.toFloat(),
                            min = 0f,
                            max = 6f,
                            signed = false,
                            sizeDp = size,
                            steps = 7,
                            textScale = 0.75f,
                            // 双击归位到「关」（索引 0，2026-11-08）；离散档位不开长按输入
                            resetValue = 0f,
                            format = { v ->
                                val i = v.toInt().coerceIn(0, 6)
                                if (i == 0) "关" else "${lowCutSteps[i].toInt()}Hz"
                            },
                            onValueChange = { idx ->
                                viewModel.setReverbLowCut(lowCutSteps[idx.toInt().coerceIn(0, 6)])
                            },
                        )
                    }
                }
                // 干湿混合 blend 条：thumb 位置 = 湿声占比（拉低干声 = 全湿）
                val blend = if (userWetGain + reverbDry > 1e-3f) {
                    userWetGain / (userWetGain + reverbDry)
                } else {
                    0.5f
                }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(pillShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(ScColors.Inset, ScColors.PrimaryLight, ScColors.Primary),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = (maxWidth - 16.dp) * blend)
                            .size(16.dp)
                            .shadow(2.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(text = "干声", fontSize = 10.sp, color = ScColors.TextSub)
                    Text(text = "混合", fontSize = 10.sp, color = ScColors.TextSub)
                    Text(text = "湿声", fontSize = 10.sp, color = ScColors.TextSub)
                }
            }
        }

        // U6 自动校准独立卡（设计稿 P2：标题+校准值 pill+说明文；数据=分析[1554] 校准建议，仅 USER IR 有）
        if (wetAutoCal && reverbPreset == AudioEngine.REVERB_PRESET_USER) {
            userIrAnalysis?.getOrNull(1554)?.takeIf { it > 0f }?.let { sug ->
                ScCard(modifier = Modifier.fillMaxWidth(), elevated = false) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "自动校准",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ScColors.TextMain,
                                modifier = Modifier.weight(1f),
                            )
                            ScPill(text = "已按脉冲建议 ×%.2f".format(sug), variant = ScPillVariant.Teal)
                        }
                        Text(
                            text = "湿声电平已按当前 IR 的波峰因数自动建议（脉冲型增湿 / 扩散型收敛）；拖动旋钮即手动覆盖本建议。",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                }
            }
        }

        // 脉冲诊断卡：波形 / 能量 / 频谱 / 病态警示 / 两个自动化开关（仅装载 IR 后显示）
        if (activeRecentIrName != null && irChannelCount > 0 && userIrAnalysis != null) {
            val analysis = userIrAnalysis!!
            ScCard(modifier = Modifier.fillMaxWidth(), elevated = false) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScCardHeader(title = "脉冲诊断")
                    // ---- 已自动对齐横幅（方案 A：脏前置 >30ms 装载时自动裁除） ----
                    if (analysis.getOrNull(1028)?.toInt() ?: 0 >= 4) {
                        val trimmed = analysis.getOrNull(1041) ?: 0f
                        if (trimmed > 0.5f) {
                            Text(
                                text = "已自动对齐：裁除前置残留 %.0f ms（湿声不再迟到、双声消除；仅裁内存副本，原文件未改）".format(trimmed),
                                fontSize = 11.sp,
                                color = ScColors.Primary,
                            )
                        }
                    }
                    // ---- 病态 IR 警示（2026-10-26 双声实证判据） ----
                    if (analysis.getOrNull(1028)?.toInt() ?: 0 >= 2) {
                        val risk = analysis.getOrNull(1038)?.toInt() ?: 0
                        if (risk > 0) {
                            val pre = analysis[1030]
                            val pulse = analysis[1031]
                            val nanN = analysis[1034].toInt()
                            val infN = analysis[1035].toInt()
                            val clip = analysis[1037]
                            val skew = analysis[1033]
                            val eRatio = analysis[1032]
                            val preNoise = analysis.getOrNull(1040) ?: -100f
                            val lines = buildList {
                                add("前置静音 %.0f ms、主脉冲 @%.0f ms——湿声将迟到同样时长（>30ms 与干声分离成「双声」）。建议换用无前置静音的 IR 或先行修形".format(pre, pulse))
                                if (preNoise > -96f) {
                                    add("前置段含 %.0f dB 噪声底——更像导出残留而非设计预延迟，卷积会把它垫进湿声背景，不建议忽略本提示".format(preNoise))
                                } else {
                                    add("前置静音为纯数字零——更像刻意的预延迟/延迟混响设计；开启「病态 IR 自动对齐」可自动裁除对齐干湿")
                                }
                                if (nanN > 0 || infN > 0) {
                                    add("文件含脏样本：NaN %d 个、Inf %d 个（已自动置零）".format(nanN, infN))
                                }
                                if (clip > 0.0005f) {
                                    add("削波样本占 %.2f%%——IR 波形已削平，混响会发硬".format(clip * 100))
                                }
                                if (irChannelCount >= 2 && abs(skew) > 5f) {
                                    add("左右主脉冲错位 %.0f ms——声像先到一侧".format(skew))
                                }
                                if (irChannelCount >= 2 && abs(eRatio) > 6f) {
                                    add("左右能量差 %.1f dB——声像偏向一侧".format(eRatio))
                                }
                            }
                            Text(
                                text = "病态 IR 警示（风险「%s」）：\n%s".format(
                                    if (risk >= 2) "严重" else "可闻",
                                    lines.joinToString("\n"),
                                ),
                                fontSize = 11.sp,
                                color = if (risk >= 2) ScColors.Danger else ScColors.Warn,
                            )
                        }
                    }
                    // ---- IR 波形包络（L 上半青 / R 下半蓝；mono 只画 L） ----
                    val isMonoIr = (analysis.getOrNull(1027)?.toInt() ?: 0) == 1
                    val waveL = analysis.copyOfRange(3, 3 + 512)
                    val waveR = analysis.copyOfRange(3 + 512, 3 + 1024)
                    var peak = 1e-6f
                    for (v in waveL) peak = maxOf(peak, abs(v))
                    if (!isMonoIr) for (v in waveR) peak = maxOf(peak, abs(v))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    ) {
                        val midY = size.height / 2f
                        drawLine(
                            color = ScColors.Divider,
                            start = Offset(0f, midY),
                            end = Offset(size.width, midY),
                            strokeWidth = 1.dp.toPx(),
                        )
                        val n = waveL.size
                        val amp = size.height / 2f * 0.9f
                        for (i in 0 until n) {
                            val x = i * size.width / (n - 1).coerceAtLeast(1)
                            val aL = (waveL[i] / peak).coerceIn(-1f, 1f) * amp
                            val aR = (waveR[i] / peak).coerceIn(-1f, 1f) * amp
                            drawLine(
                                color = Color(0xFF26A69A),
                                start = Offset(x, midY),
                                end = Offset(x, midY - aL),
                                strokeWidth = 1.dp.toPx(),
                            )
                            if (!isMonoIr) {
                                drawLine(
                                    color = Color(0xFF42A5F5),
                                    start = Offset(x, midY),
                                    end = Offset(x, midY + aR),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(text = "左声道能量", fontSize = 11.sp, color = ScColors.TextSub)
                            Text(
                                text = "%.5f".format(analysis[0]),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ScColors.TextMain,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "右声道能量", fontSize = 11.sp, color = ScColors.TextSub)
                            if (isMonoIr) {
                                Text(
                                    text = "—（单声道文件）",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ScColors.TextMain,
                                )
                            } else {
                                Text(
                                    text = "%.5f".format(analysis[1]),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ScColors.TextMain,
                                )
                            }
                        }
                    }
                    // ---- 每声道频谱（Welch 平均周期图，128 点 dB） ----
                    if (analysis.getOrNull(1028)?.toInt() ?: 0 >= 2) {
                        val specN = analysis.getOrNull(1039)?.toInt()?.coerceIn(0, 4) ?: 0
                        if (specN > 0) {
                            val srcRate = analysis.getOrNull(1029)?.toInt() ?: 48000
                            val chColors = listOf(
                                Color(0xFF26A69A),
                                Color(0xFF42A5F5),
                                Color(0xFFAB47BC),
                                Color(0xFFEF6C00),
                            )
                            val chNames = listOf("L", "R", "CH3", "CH4")
                            Text(
                                text = "频谱（%d 声道：%s；源 %d Hz，纵轴 dB，峰=0dB/底-100dB）".format(
                                    specN,
                                    chNames.take(specN).joinToString("/"),
                                    srcRate,
                                ),
                                fontSize = 11.sp,
                                color = ScColors.TextSub,
                            )
                            val specTextMeasurer = rememberTextMeasurer()
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(128.dp),
                            ) {
                                // U5：深色遗留底色清退（0xFF1E1E1E → 凹面浅灰，对齐白卡体系）
                                drawRect(ScColors.Inset, topLeft = Offset(0f, 0f), size = size)
                                val plotLeft = 36.dp.toPx()
                                val plotRight = size.width
                                val plotTop = 10.dp.toPx()
                                val plotBottom = size.height - 10.dp.toPx()
                                val plotH = plotBottom - plotTop
                                val dbY = { db: Float -> plotTop + (1f - (db + 100f) / 100f) * plotH }
                                val labelColor = ScColors.TextSub
                                for (db in intArrayOf(0, -50, -100)) {
                                    val y = dbY(db.toFloat())
                                    drawLine(
                                        color = ScColors.Divider,
                                        start = Offset(plotLeft, y),
                                        end = Offset(plotRight, y),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                    drawText(
                                        textMeasurer = specTextMeasurer,
                                        text = if (db == 0) "0dB" else db.toString(),
                                        topLeft = Offset(3.dp.toPx(), y - 5.sp.toPx()),
                                        style = TextStyle(fontSize = 9.sp, color = labelColor),
                                    )
                                }
                                for (c in 0 until specN) {
                                    val base = 1042 + c * 128
                                    var prevX = 0f
                                    var prevY = 0f
                                    for (i in 0 until 128) {
                                        val db = analysis.getOrElse(base + i) { -100f }
                                        val x = plotLeft + i * (plotRight - plotLeft) / 127f
                                        val y = dbY(db)
                                        if (i > 0) {
                                            drawLine(
                                                color = chColors[c],
                                                start = Offset(prevX, prevY),
                                                end = Offset(x, y),
                                                strokeWidth = 1.5f.dp.toPx(),
                                            )
                                        }
                                        prevX = x
                                        prevY = y
                                    }
                                }
                            }
                        }
                    }
                    // ---- 两个自动化开关（TextButton → 拟物开关行） ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "病态 IR 自动对齐",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ScColors.TextMain,
                            )
                            Text(
                                text = if (trimSickHead) {
                                    "病态一律校正：裁除前置残留/延迟，干湿同步"
                                } else {
                                    "保持 IR 原样装载"
                                },
                                fontSize = 10.sp,
                                color = ScColors.TextSub,
                            )
                        }
                        ScSwitch(checked = trimSickHead, onCheckedChange = { viewModel.setTrimSickHead(!trimSickHead) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "湿声电平自动校准",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ScColors.TextMain,
                            )
                            Text(
                                text = if (wetAutoCal) {
                                    "换 IR 时按形态波峰因数校准湿声电平"
                                } else {
                                    "湿声电平全手动"
                                },
                                fontSize = 10.sp,
                                color = ScColors.TextSub,
                            )
                        }
                        ScSwitch(checked = wetAutoCal, onCheckedChange = { viewModel.setWetAutoCal(!wetAutoCal) })
                    }
                    Text(
                        text = "卷积混响（两阶段分区：head 时域 + tail 频域批处理）。内置 4 预设或加载 WAV/.irs 文件" +
                            "（PCM16/24/32/float，1-4 声道，自动重采样，超 2731ms 尾音截断）。" +
                            "4 声道 HRTF/交叉 IR 走完整 2×2 交叉卷积（湿声含对侧串扰，空间感成立）。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                }
            }
        }
    }
}


// ============ 自定义脚本语法高亮（SCVM，类 C）============

/** 脚本编辑器配色：灰黑底 + 高对比代码色（低亮度背景，长时间看不刺眼） */
object ScriptColors {
    val Background = Color(0xFF1E1E1E)
    val Border = Color(0xFF3A3A3A)
    val Text = Color(0xFFD4D4D4)
    val Comment = Color(0xFF6A9955)
    val Keyword = Color(0xFF569CD6)
    val Number = Color(0xFFB5CEA8)
    val Builtin = Color(0xFFDCDCAA)
    val Global = Color(0xFF9CDCFE)
}

private val SCVM_KEYWORDS = setOf(
    "void", "float", "int", "if", "else", "while", "for",
    "break", "continue", "return", "init", "sample",
)

private val SCVM_BUILTINS = setOf(
    "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "log", "log10",
    "pow", "sqrt", "abs", "floor", "ceil", "round", "min", "max", "tanh",
    "fmod", "clamp", "sign", "saturate",
)

private fun isScriptGlobal(w: String): Boolean =
    w == "spl0" || w == "spl1" || w == "srate" ||
        (w.startsWith("slider") && w.length > 6 && w.substring(6).all { it.isDigit() })

/**
 * 把脚本文本转成带颜色的 AnnotatedString。
 * 逐字符扫描：脚本只有几百行量级，每次输入全量重算足够快。
 */
fun highlightScript(code: String): AnnotatedString {
    val b = AnnotatedString.Builder(code)
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        // 行注释
        if (c == '/' && i + 1 < n && code[i + 1] == '/') {
            val end = code.indexOf('\n', i).let { if (it < 0) n else it }
            b.addStyle(SpanStyle(color = ScriptColors.Comment), i, end)
            i = end
            continue
        }
        // 块注释
        if (c == '/' && i + 1 < n && code[i + 1] == '*') {
            val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
            b.addStyle(SpanStyle(color = ScriptColors.Comment), i, end)
            i = end
            continue
        }
        // 数字
        if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit())) {
            var j = i
            while (j < n && (code[j].isDigit() || code[j] == '.')) j++
            b.addStyle(SpanStyle(color = ScriptColors.Number), i, j)
            i = j
            continue
        }
        // 标识符：关键字 / 内建函数 / 内置全局量
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val w = code.substring(i, j)
            val color = when {
                w in SCVM_KEYWORDS -> ScriptColors.Keyword
                w in SCVM_BUILTINS -> ScriptColors.Builtin
                isScriptGlobal(w) -> ScriptColors.Global
                else -> null
            }
            if (color != null) b.addStyle(SpanStyle(color = color), i, j)
            i = j
            continue
        }
        i++
    }
    return b.toAnnotatedString()
}

private class ScriptHighlightTransformation(private val errorLine: Int = -1) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = highlightScript(text.text)
        if (errorLine <= 0) return TransformedText(annotated, OffsetMapping.Identity)
        // 计算出错行的字符区间（行号从 1 开始）
        var line = 1
        var start = -1
        var end = -1
        var i = 0
        while (i <= text.length) {
            if (line == errorLine && start < 0) start = i
            if (i == text.length || text[i] == '\n') {
                if (line == errorLine) {
                    end = i
                    break
                }
                line++
            }
            i++
        }
        if (start < 0 || end < start) return TransformedText(annotated, OffsetMapping.Identity)
        return TransformedText(
            buildAnnotatedString {
                append(annotated)
                addStyle(
                    SpanStyle(color = Color(0xFFFF6B6B), background = Color(0x33E5484D)),
                    start,
                    end,
                )
            },
            OffsetMapping.Identity,
        )
    }
}

/** 脚本机架（Script Rack）：音效库「机架」详情页的主体内容（自设置页迁入）。 */
@Composable
internal fun ScScriptRackSection(
    viewModel: MainViewModel,
    showEditor: Boolean = true,
    showTitle: Boolean = true,
) {
    // ============ 脚本机架（Script Rack / SCVM）============
    val scriptEnabled by viewModel.scriptEffectEnabled.collectAsStateWithLifecycle()
    val scriptSrc by viewModel.scriptSource.collectAsStateWithLifecycle()
    val sliderMask by viewModel.scriptSliderMask.collectAsStateWithLifecycle()
    val sliderValues by viewModel.scriptSliderValues.collectAsStateWithLifecycle()
    val sliderRanges by viewModel.scriptSliderRanges.collectAsStateWithLifecycle()
    val scriptError by viewModel.scriptError.collectAsStateWithLifecycle()
    val scriptControls by viewModel.scriptControls.collectAsStateWithLifecycle()
    val toggleValues by viewModel.scriptToggleValues.collectAsStateWithLifecycle()
    val selectValues by viewModel.scriptSelectValues.collectAsStateWithLifecycle()
    val listValues by viewModel.scriptListValues.collectAsStateWithLifecycle()
    val scriptLane by viewModel.scriptLane.collectAsStateWithLifecycle()
    val laneOn by viewModel.scriptLaneOn.collectAsStateWithLifecycle()
    // 未保存过脚本时回填内置示例（排版规范、可直接编辑），避免出现空白编辑器
    var scriptDraft by remember(scriptLane) {
        mutableStateOf(if (scriptSrc.isBlank()) ScriptDefaults.DEFAULT_SCRIPT else scriptSrc)
    }
    // 保存前校验：出错行用于红色高亮，错误串用于弹窗
    var scriptErrLine by remember { mutableStateOf(-1) }
    var scriptErrDialog by remember { mutableStateOf<String?>(null) }
    // 脚本文件管理：导入（SAF 选择文件）/ 导出（SAF 保存）
    val appCtx = LocalContext.current
    val importScript = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                scriptDraft = text
                viewModel.applyScriptSource(text)
            }
        }
    }
    val exportScript = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                appCtx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(scriptDraft)
                }
            }
        }
    }
    if (showTitle) ScSectionTitle("参数")
    ScCard {
        Column {
        if (showEditor) {
            ScSettingBlock(
                title = "脚本",
                subtitle = "void sample() 读 spl0/spl1 并写回；参数用 slider/toggle/select/list/button 声明",
                showDivider = false,
            ) {
                Column {
                    BasicTextField(
                        value = scriptDraft,
                        onValueChange = {
                            scriptDraft = it
                            scriptErrLine = -1  // 编辑即清除旧错误高亮
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = ScriptColors.Text,
                        ),
                        cursorBrush = SolidColor(ScColors.Primary),
                        visualTransformation = ScriptHighlightTransformation(scriptErrLine),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ScriptColors.Background)
                                    .padding(10.dp),
                            ) { inner() }
                        },
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "保存",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScColors.Inset)
                                .clickable {
                                    val err = viewModel.validateScript(scriptDraft)
                                    if (err.isBlank()) {
                                        scriptErrLine = -1
                                        scriptErrDialog = null
                                        viewModel.saveScriptDraft(scriptDraft)
                                    } else {
                                        scriptErrLine = Regex("""^line (\d+)""")
                                            .find(err)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                                        scriptErrDialog = err
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Text(
                            text = "应用脚本",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScColors.Inset)
                                .clickable { viewModel.applyScriptSource(scriptDraft) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                }
                                scriptErrDialog?.let { msg ->
                                AlertDialog(
                                onDismissRequest = { scriptErrDialog = null },
                                title = { Text("脚本有错误，未保存", fontSize = 15.sp) },
                                text = { Text(msg, fontSize = 12.sp) },
                                confirmButton = {
                                    Text(
                                        text = "知道了",
                                        fontSize = 13.sp,
                                        color = ScColors.Primary,
                                        modifier = Modifier
                                            .clickable { scriptErrDialog = null }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                },
                                )
                                }
                                if (scriptError.isNotEmpty()) {
                        Text(
                            text = "编译错误：$scriptError",
                            color = Color(0xFFE5484D),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (scriptSrc.isNotBlank()) {
                        Text(
                            text = "✓ 脚本已编译",
                            color = Color(0xFF2E9E6B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
            // 脚本文件管理：导入 / 导出 / 示例库
            if (showEditor) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "导入",
                    fontSize = 12.sp,
                    color = ScColors.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ScColors.Inset)
                        .clickable { importScript.launch(arrayOf("text/plain", "*/*")) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    text = "导出",
                    fontSize = 12.sp,
                    color = ScColors.Primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ScColors.Inset)
                        .clickable { exportScript.launch("script.scvm") }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                var sampleMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "示例 ▾",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { sampleMenu = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    DropdownMenu(
                        expanded = sampleMenu,
                        onDismissRequest = { sampleMenu = false },
                    ) {
                        ScriptSamples.forEach { sample ->
                            DropdownMenuItem(
                                text = { Text(sample.title, fontSize = 13.sp) },
                                onClick = {
                                    scriptDraft = sample.code
                                    // 示例即应用：下方控件区立即按新脚本重建
                                    viewModel.applyScriptSource(sample.code)
                                    sampleMenu = false
                                },
                            )
                        }
                    }
                }
            }
            }
            // 脚本参数滑块（sliderN 自动生成）
            for (i in 0 until 8) {
                if ((sliderMask and (1 shl i)) != 0) {
                    ScSettingBlock(
                        title = "参数 slider${i + 1}",
                        subtitle = "%.2f".format(sliderValues[i]),
                        showDivider = false,
                    ) {
                        val lo = sliderRanges[i * 2]
                        val hi = sliderRanges[i * 2 + 1]
                        // 精确输入框：拖动之外也支持键入数值（失去焦点/回车提交）
                        var txt by remember(i, sliderValues[i]) {
                            mutableStateOf("%.2f".format(sliderValues[i]))
                        }
                        val focus = LocalFocusManager.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = sliderValues[i].coerceIn(lo, hi),
                                onValueChange = { viewModel.setScriptSlider(i, it) },
                                valueRange = lo..hi,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = txt,
                                onValueChange = { s ->
                                    txt = s.filter { it.isDigit() || it == '.' || it == '-' }
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        txt.toFloatOrNull()?.let { v ->
                                            viewModel.setScriptSlider(i, v.coerceIn(lo, hi))
                                        }
                                        focus.clearFocus()
                                    },
                                ),
                                modifier = Modifier.width(96.dp).padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            // 脚本声明的高级控件（toggle / select / button）
            for (ctl in scriptControls) {
                when (ctl.type) {
                    "toggle" -> ScSettingBlock(
                        title = ctl.label,
                        subtitle = ctl.name,
                        showDivider = false,
                    ) {
                        ScSwitch(
                            checked = toggleValues[ctl.index] > 0.5f,
                            onCheckedChange = {
                                viewModel.setScriptToggle(ctl.index, toggleValues[ctl.index] <= 0.5f)
                            },
                        )
                    }
                    "select" -> {
                        var expanded by remember(ctl.name) { mutableStateOf(false) }
                        ScSettingBlock(
                            title = ctl.label,
                            subtitle = ctl.options.getOrElse(selectValues[ctl.index]) { ctl.name },
                            showDivider = false,
                        ) {
                            Box {
                                Text(
                                    text = ctl.options.getOrElse(selectValues[ctl.index]) { "选择" } + " ▾",
                                    fontSize = 13.sp,
                                    color = ScColors.Primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ScColors.Inset)
                                        .clickable { expanded = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    ctl.options.forEachIndexed { idx, optName ->
                                        DropdownMenuItem(
                                            text = { Text(optName, fontSize = 13.sp) },
                                            onClick = {
                                                viewModel.setScriptSelect(ctl.index, idx)
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "list" -> ScSettingBlock(
                        title = ctl.label,
                        subtitle = "多选（位掩码 ${listValues[ctl.index]}）",
                        showDivider = false,
                    ) {
                        Column {
                            ctl.options.forEachIndexed { idx, optName ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = optName,
                                        fontSize = 13.sp,
                                        color = ScColors.TextMain,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ScSwitch(
                                        checked = (listValues[ctl.index] and (1 shl idx)) != 0,
                                        onCheckedChange = {
                                            viewModel.setScriptList(
                                                ctl.index,
                                                listValues[ctl.index] xor (1 shl idx),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    "button" -> ScSettingBlock(
                        title = ctl.label,
                        subtitle = "点击触发（仅一个样本）",
                        showDivider = false,
                    ) {
                        Text(
                            text = "触发",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScColors.Inset)
                                .clickable { viewModel.pressScriptButton(ctl.index) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    // ============ 音频链路 ============
}

/**
 * 机架详情页主体（按草图 2026-11-13）：
 * 顶部总开关在页头；下面槽位 1/2/3 每行 = 脚本名 / 参数 / 开关；再下面是「脚本库」列表。
 * 参数区（编辑器 + 控件）点某槽位的「参数」后展开，作用于该槽位。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ScRackBody(
    viewModel: MainViewModel,
    onOpenEditor: () -> Unit = {},
    onOpenBlankEditor: () -> Unit = {},
) {
    val lane by viewModel.scriptLane.collectAsStateWithLifecycle()
    val laneOnMap by viewModel.scriptLaneOnMap.collectAsStateWithLifecycle()
    val labels by viewModel.scriptLaneLabels.collectAsStateWithLifecycle()
    val laneSources by viewModel.scriptLaneSources.collectAsStateWithLifecycle()
    val laneErrors by viewModel.scriptLaneErrors.collectAsStateWithLifecycle()
    val laneParallel by viewModel.scriptLaneParallel.collectAsStateWithLifecycle()
    val laneWet by viewModel.scriptLaneWet.collectAsStateWithLifecycle()
    val customScripts by viewModel.customScripts.collectAsStateWithLifecycle()
    val laneOrder by viewModel.scriptLaneOrder.collectAsStateWithLifecycle()
    val rackWet by viewModel.scriptRackWet.collectAsStateWithLifecycle()
    // 拖拽换序（长按槽位标题行）：dragPos = 正在拖的位置，dragDy = 累计位移
    var dragPos by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }
    val rowDensity = LocalDensity.current.density
    val rowHpx = with(LocalDensity.current) { 118.dp.toPx() }
    // 进入机架页时重算编译状态（VM 构造时引擎尚未创建，初始值可能为空）
    LaunchedEffect(Unit) { viewModel.refreshRackMeta() }
    // 长按脚本 → 发送到槽位；发送后目标槽位绿色高亮 0.9s
    var sendTarget by remember { mutableStateOf<com.jiangjie.Sonicraft.ScriptSample?>(null) }
    var flashLane by remember { mutableStateOf(-1) }
    // 槽位手工重命名（长按名称框触发；-1 = 未打开）
    var renameLane by remember { mutableStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    // 多选批量删除（长按自定义脚本进入；selSet 存自定义脚本序号）
    var selMode by remember { mutableStateOf(false) }
    var selSet by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(flashLane) {
        if (flashLane >= 0) {
            delay(900)
            flashLane = -1
        }
    }
    // 槽位行「脚本有错」红色呼吸（与编辑器错误横幅同一节奏）
    val slotBreathe = rememberInfiniteTransition(label = "slotErr")
    val slotGlow by slotBreathe.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "slotGlow",
    )
    var paramsOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var libQuery by remember { mutableStateOf("") }
    var libExpanded by remember { mutableStateOf(true) }  // 脚本库默认展开（长按入口要可见）
    // 脚本库工具：导入 / 导出（SAF）
    val rackCtx = LocalContext.current
    val importScript = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                rackCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) viewModel.applyScriptSource(text)
        }
    }
    val exportScript = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                rackCtx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(laneSources.getOrElse(lane) { "" })
                }
            }
        }
    }

    ScSectionTitle("槽位")
    ScCard {
        Column {
            // 按用户拖拽设定的顺序渲染（顺序即串联链先后）
            for (pos in laneOrder.indices) {
                val i = laneOrder[pos]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    // 标题行：左「槽位 N」，并联时右侧直接给湿声滑杆（不必再进参数弹窗）
                    // 长按标题行可上下拖动换序（松手后按位移量换算目标位置）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = if (dragPos == pos) (dragDy / rowDensity).dp else 0.dp)
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragPos = pos
                                        dragDy = 0f
                                    },
                                    onDrag = { _, d -> dragDy += d.y },
                                    onDragEnd = {
                                        val step = (dragDy / rowHpx).roundToInt()
                                        if (step != 0) {
                                            // 兜底：换序失败也不能拖垮 App
                                            runCatching {
                                                viewModel.moveSlot(
                                                    pos,
                                                    (pos + step).coerceIn(0, laneOrder.size - 1),
                                                )
                                            }
                                        }
                                        dragPos = -1
                                        dragDy = 0f
                                    },
                                    onDragCancel = {
                                        dragPos = -1
                                        dragDy = 0f
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "≡",
                            fontSize = 14.sp,
                            color = ScColors.TextSub,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = "槽位 ${pos + 1}",
                            fontSize = 13.sp,
                            color = ScColors.TextMain,
                            modifier = Modifier.weight(1f),
                        )
                        if (laneParallel.getOrElse(i) { false }) {
                            // 滑块已移到槽位列表下方（避免与拖拽手势抢指针）
                            val w = laneWet.getOrElse(i) { 1f / 3f }
                            Text(
                                text = "并联 ${(w * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = ScColors.TextSub,
                            )
                        }
                    }
                    // 输入框 / 参数 / 开关 同一行，垂直居中对齐
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                        // 脚本选择：点击弹出带搜索的下拉列表框（输入即过滤，命中处标红）
                        var pickerOpen by remember(i) { mutableStateOf(false) }
                        var query by remember(i) { mutableStateOf("") }
                        Box {
                            val slotErr = laneErrors.getOrElse(i) { "" }
                            Text(
                                text = labels.getOrElse(i) { "未选择脚本" } +
                                    if (slotErr.isNotEmpty()) "  ⚠ 已旁路" else "",
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = when {
                                    slotErr.isNotEmpty() -> Color(0xFFB3261E)
                                    lane == i -> ScColors.Primary
                                    else -> ScColors.TextMain
                                },
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        when {
                                            // 脚本编译失败：红色呼吸
                                            slotErr.isNotEmpty() -> Modifier
                                                .background(
                                                    Color(0xFFFDECEA)
                                                        .copy(alpha = 0.28f + 0.42f * slotGlow),
                                                )
                                                .border(
                                                    1.dp,
                                                    Color(0xFFE5484D)
                                                        .copy(alpha = 0.35f + 0.65f * slotGlow),
                                                    RoundedCornerShape(6.dp),
                                                )
                                            // 刚收到「发送到槽位」：绿色高亮 0.9s
                                            flashLane == i -> Modifier
                                                .background(ScColors.Primary.copy(alpha = 0.14f))
                                                .border(
                                                    1.5.dp,
                                                    ScColors.Primary,
                                                    RoundedCornerShape(6.dp),
                                                )
                                            else -> Modifier.border(
                                                1.dp,
                                                ScColors.Inset,
                                                RoundedCornerShape(6.dp),
                                            )
                                        },
                                    )
                                    // 点击 = 选脚本；长按 = 手工重命名该槽位
                                    .combinedClickable(
                                        onClick = { pickerOpen = true },
                                        onLongClick = {
                                            renameLane = i
                                            renameText = labels.getOrElse(i) { "" }
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                            if (pickerOpen) {
                                Dialog(onDismissRequest = { pickerOpen = false }) {
                                    ScCard {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            OutlinedTextField(
                                                value = query,
                                                onValueChange = { query = it },
                                                singleLine = true,
                                                placeholder = {
                                                    Text("搜索脚本", fontSize = 12.sp)
                                                },
                                                textStyle = TextStyle(fontSize = 13.sp),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            val hits = ScriptSamples.filter {
                                                matchesScript(it.title, query)
                                            }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 360.dp)
                                                    .verticalScroll(rememberScrollState()),
                                            ) {
                                                if (hits.isEmpty()) {
                                                    Text(
                                                        text = "无匹配脚本",
                                                        fontSize = 13.sp,
                                                        modifier = Modifier.padding(12.dp),
                                                    )
                                                }
                                                hits.forEach { sample ->
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                viewModel.switchScriptLane(i)
                                                                viewModel.applyScriptSource(sample.code)
                                                                pickerOpen = false
                                                                query = ""
                                                            }
                                                            .padding(
                                                                horizontal = 8.dp,
                                                                vertical = 12.dp,
                                                            ),
                                                    ) {
                                                        Text(
                                                            text = highlightMatch(
                                                                sample.title,
                                                                query,
                                                            ),
                                                            fontSize = 13.sp,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 拓扑：串（主链）/ 并（取机架干声、按 1/3 累加）
                    val isPar = laneParallel.getOrElse(i) { false }
                    Text(
                        text = if (isPar) "并" else "串",
                        fontSize = 11.sp,
                        color = if (isPar) ScColors.Primary else ScColors.TextSub,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { viewModel.setScriptLaneParallel(i, !isPar) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Text(
                        text = "参数",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable {
                                viewModel.switchScriptLane(i)
                                paramsOpen = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Box(modifier = Modifier.padding(start = 10.dp)) {
                        ScSwitch(
                            checked = laneOnMap.getOrElse(i) { true },
                            onCheckedChange = {
                                viewModel.setScriptLaneOn(i, !laneOnMap.getOrElse(i) { true })
                            },
                        )
                    }
                    }
                }
            }
        }
    }

    // 机架干湿：0 = 原声，100% = 全效果（A/B 对比与"半湿"处理）
    ScCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "机架干湿",
                    fontSize = 12.sp,
                    color = ScColors.TextMain,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(rackWet * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = ScColors.Primary,
                )
            }
            Slider(
                value = rackWet.coerceIn(0f, 1f),
                onValueChange = { viewModel.setScriptRackWet(it) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 并联比例：固定在槽位列表下方（作用于当前并联槽位）
    val wetLane = if (laneParallel.getOrElse(lane) { false }) {
        lane
    } else {
        laneParallel.indexOfFirst { it }
    }
    if (wetLane >= 0) {
        val w = laneWet.getOrElse(wetLane) { 1f / 3f }
        ScCard {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "槽位 ${laneOrder.indexOf(wetLane) + 1} 并联比例",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(w * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                    )
                }
                Slider(
                    value = w.coerceIn(0f, 1f),
                    onValueChange = { viewModel.setScriptLaneWet(wetLane, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    ScSectionTitle("脚本库")
    ScCard {
        Column {
            // 与卷积混响「脉冲库」一致：标题 + 副标题 + 检索框 + 药丸按钮 + 计数行
            Text(
                text = "${ScriptSamples.size} 个脚本 · 点按装载到槽位 ${lane + 1}",
                fontSize = 11.sp,
                color = ScColors.TextSub,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp),
            )
            BasicTextField(
                value = libQuery,
                onValueChange = { libQuery = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = ScColors.TextMain),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ScColors.Inset)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (libQuery.isEmpty()) {
                            Text("检索脚本…", fontSize = 12.sp, color = ScColors.TextSub)
                        }
                        inner()
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScriptPill("导入", Modifier.weight(1f)) {
                    importScript.launch(arrayOf("text/plain", "*/*"))
                }
                ScriptPill("导出", Modifier.weight(1f)) { exportScript.launch("script.scvm") }
                ScriptPill("编辑", Modifier.weight(1f)) { onOpenEditor() }
                ScriptPill("新建", Modifier.weight(1f)) { onOpenBlankEditor() }
                ScriptPill("存入库", Modifier.weight(1f)) { viewModel.saveCurrentToLibrary() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selMode) {
                        "已选 ${selSet.size} 项"
                    } else {
                        "全部脚本 · ${ScriptSamples.size + customScripts.size}"
                    },
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (selMode) {
                    Text(
                        text = "删除所选",
                        fontSize = 11.sp,
                        color = Color(0xFFE5484D),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                viewModel.deleteCustoms(selSet)
                                selSet = emptySet()
                                selMode = false
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        text = "完成",
                        fontSize = 11.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                selSet = emptySet()
                                selMode = false
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        text = "管理",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { selMode = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        text = if (libExpanded) "收起 ▲" else "展开 ▼",
                        fontSize = 10.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { libExpanded = !libExpanded }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (libExpanded) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val rowShape = RoundedCornerShape(ScRadius.Chip)
                // 分组渲染：用来源标记而不是「代码相等」判重（自定义常是内置示例的副本）
                val hits = (ScriptSamples.map { false to it } + customScripts.map { true to it })
                    .filter { matchesScript(it.second.title, libQuery) }
                var lastGroup = -1
                hits.forEach { (isCustom, sample) ->
                    val groupId = if (isCustom) 1 else 0
                    if (groupId != lastGroup) {
                        lastGroup = groupId
                        val groupName = if (isCustom) "自定义脚本" else "内置脚本"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "$groupName · " + hits.count { it.first == isCustom },
                                fontSize = 11.sp,
                                color = ScColors.TextSub,
                            )
                        }
                    }
                    val active = laneSources.getOrElse(lane) { "" }.trim() == sample.code.trim()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (active) {
                                    Modifier
                                        .padding(horizontal = 6.dp)
                                        .background(ScColors.Inset, rowShape)
                                        .border(
                                            1.5.dp,
                                            Brush.linearGradient(
                                                listOf(ScColors.Primary, ScColors.PrimaryLight),
                                            ),
                                            rowShape,
                                        )
                                } else {
                                    Modifier
                                },
                            )
                            .combinedClickable(
                                onClick = {
                                    if (selMode) {
                                        if (isCustom) {
                                            val idx = customScripts.indexOfFirst { it === sample }
                                            selSet = if (idx in selSet) selSet - idx else selSet + idx
                                        }
                                    } else {
                                        viewModel.switchScriptLane(lane)
                                        viewModel.applyScriptSource(sample.code)
                                    }
                                },
                                onLongClick = {
                                    if (isCustom) {
                                        selMode = true
                                        selSet = selSet + customScripts.indexOfFirst { it === sample }
                                    } else {
                                        sendTarget = sample
                                    }
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        if (selMode && isCustom) {
                            Text(
                                text = if (customScripts.indexOfFirst { it === sample } in selSet) "☑" else "☐",
                                fontSize = 15.sp,
                                color = if (customScripts.indexOfFirst { it === sample } in selSet) {
                                    ScColors.Primary
                                } else {
                                    ScColors.TextSub
                                },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(rowShape)
                                .then(
                                    if (active) Modifier.background(ScPrimaryBrush)
                                    else Modifier.background(ScColors.Inset),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "SC",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) Color.White else ScColors.TextSub,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        ) {
                            Text(
                                text = sample.title + if (active) "  ✓" else "",
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = ScColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    active -> "当前槽位脚本"
                                    isCustom -> "自定义"
                                    else -> "内置示例"
                                    },
                                    fontSize = 10.sp,
                                    color = ScColors.TextSub,
                                    )
                                    }
                                    // 自定义条目：右侧 ✕ 直接删除（内置示例不显示此按钮）
                                    if (isCustom) {
                                    Text(
                                    text = "✕",
                                    fontSize = 15.sp,
                                    color = ScColors.TextSub,
                                    modifier = Modifier
                                    .clickable { viewModel.deleteCustomAt(customScripts.indexOfFirst { it === sample }) }
                                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    )
                                    }
                                    }
                }
            }
        }
    }

    // 参数按钮 → 弹窗：只显示当前槽位脚本的参数控件（滑块 / 开关 / 下拉 / 多选 / 按钮）
    if (paramsOpen) {
        Dialog(onDismissRequest = { paramsOpen = false }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ScScriptRackSection(viewModel, showEditor = false, showTitle = false)
            }
        }
    }

    // 长按槽位名称 → 手工重命名（留空 = 恢复自动摘要）
    if (renameLane >= 0) {
        Dialog(onDismissRequest = { renameLane = -1 }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "重命名槽位 ${renameLane + 1}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "留空并保存 = 恢复自动摘要（脚本首行注释 / desc）",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScriptPill("保存", Modifier.weight(1f)) {
                            viewModel.renameScriptLane(renameLane, renameText)
                            renameLane = -1
                        }
                        ScriptPill("恢复自动", Modifier.weight(1f)) {
                            viewModel.renameScriptLane(renameLane, "")
                            renameLane = -1
                        }
                        ScriptPill("取消", Modifier.weight(1f)) { renameLane = -1 }
                    }
                }
            }
        }
    }

    // 长按脚本库条目 → 发送到槽位 1/2/3（会覆盖该槽位现有脚本）
    sendTarget?.let { sample ->
        Dialog(onDismissRequest = { sendTarget = null }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(sample.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "发送到槽位",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                    )
                    for (i in 0 until 3) {
                        val occupied = laneSources.getOrElse(i) { "" }.isNotBlank()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.switchScriptLane(i)
                                    viewModel.applyScriptSource(sample.code)
                                    flashLane = i
                                    sendTarget = null
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "槽位 ${i + 1}",
                                fontSize = 13.sp,
                                color = ScColors.TextMain,
                                modifier = Modifier.weight(1f),
                            )
                            if (i == lane) {
                                Text(
                                    text = "当前  ",
                                    fontSize = 11.sp,
                                    color = ScColors.Primary,
                                )
                            }
                            Text(
                                text = if (occupied) "覆盖" else "空",
                                fontSize = 11.sp,
                                color = ScColors.TextSub,
                            )
                        }
                    }
                    // 自定义脚本可删除（内置示例不可删）
                    if (ScriptSamples.none { it.code == sample.code }) {
                        Text(
                            text = "删除此脚本",
                            fontSize = 12.sp,
                            color = Color(0xFFE5484D),
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    viewModel.deleteCustomScript(sample.title)
                                    sendTarget = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        text = "取消",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { sendTarget = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // 编辑脚本 → 弹窗（编辑器 + 应用/保存），作用于当前槽位
    if (editOpen) {
        Dialog(onDismissRequest = { editOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                ScScriptRackSection(viewModel, showTitle = false)
            }
        }
    }
}

/** 脚本库工具按钮（对齐脉冲库的药丸样式）。 */
@Composable
private fun ScriptPill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 44dp 最小点击高度（Android 无障碍建议值），宽度由调用方 weight 均分
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, ScColors.Inset, RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            color = ScColors.TextMain,
        )
    }
}

/** 下拉列表里把搜索命中的片段标红（参考图样式）。 */
private fun highlightMatch(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val idx = text.indexOf(query, ignoreCase = true)
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = Color(0xFFE5484D)), idx, idx + query.length)
    }
}

/** 脚本搜索用的拼音/英文提示（标题命中哪条用哪条）。 */
private val kScriptPinyin = mapOf(
    "音量" to "yinliang yl jingyin jy volume mute gain",
    "软削波" to "ruanxiaobo rxb qudong qd drive softclip saturation",
    "双二阶" to "shuangerjie sej ditong dt lvbo biquad lowpass",
    "延迟" to "yanchi yc hunhe hh delay dl",
    "块卷积" to "kuaijuanji kj juanji conv convolution",
    "EEL" to "eel yufa yf shili sl",
)

private fun scriptHaystack(title: String): String {
    val extra = kScriptPinyin.entries.firstOrNull { title.contains(it.key) }?.value ?: ""
    return (title + " " + extra).lowercase()
}

private fun subsequenceMatch(hay: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    var i = 0
    for (ch in hay) {
        if (i < needle.length && ch == needle[i]) i++
    }
    return i == needle.length
}

/** 标题子串 / 拼音全拼与首字母 / 字符顺序模糊；多关键词（空格分隔）需全部命中。 */
private fun matchesScript(title: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val toks = scriptHaystack(title).split(' ').filter { it.isNotEmpty() }
    return q.split(' ').filter { it.isNotEmpty() }.all { tok ->
        // 只在「单个拼音词」内做模糊，避免 yl 这种跨词误命中
        title.lowercase().contains(tok) ||
            toks.any { it.contains(tok) || subsequenceMatch(it, tok) }
    }
}

/**
 * 脚本编辑器整页（按截图 2026-11-13）：
 * 顶栏（返回 / 应用 / 撤销 / 菜单）+ 行号 + 深色代码区 + 状态栏 + 符号键行。
 */
@Composable
internal fun ScScriptEditorPage(
    viewModel: MainViewModel,
    startBlank: Boolean = false,
    onBack: () -> Unit,
) {
    val lane by viewModel.scriptLane.collectAsStateWithLifecycle()
    val sources by viewModel.scriptLaneSources.collectAsStateWithLifecycle()
    val labels by viewModel.scriptLaneLabels.collectAsStateWithLifecycle()
    var draft by remember(lane, sources, startBlank) {
        mutableStateOf(if (startBlank) "" else sources.getOrElse(lane) { "" })
    }
    var menuOpen by remember { mutableStateOf(false) }
    var errLine by remember { mutableStateOf(-1) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf(false) }  // 保存后的提示（1.4s 自动消失）
    var codeSp by remember { mutableStateOf(12) }

    // 撤销 / 重做：历史放在 remember 持有的普通对象里。
    // 之前用局部 `var ... by remember { mutableStateOf(listOf()) }` + 局部 fun，
    // 在事件 lambda 里读写时容易读到旧快照（表现为"点了没反应"）。
    val history = remember(lane) { EditorHistory() }
    var historyVersion by remember { mutableStateOf(0) }
    val canUndo = remember(historyVersion) { history.canUndo }
    val canRedo = remember(historyVersion) { history.canRedo }

    // 查找/替换、字号、帮助
    var findOpen by remember { mutableStateOf(false) }
    var fontOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replText by remember { mutableStateOf("") }
    var findMsg by remember { mutableStateOf("") }

    val lineCount = draft.count { it == '\n' } + 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScColors.Inset)
            .statusBarsPadding(),
    ) {
        // ---- 顶栏：返回 / 标题 / 运行 / 撤销 / 重做 / 更多 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorBarIcon(glyph = "←", color = ScColors.TextMain, enabled = true) { onBack() }
            Text(
                text = if (startBlank) "新建脚本" else "脚本编辑器",
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = ScColors.TextMain,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            EditorBarIcon(glyph = "▶", color = ScColors.Primary, enabled = true) {
                val err = viewModel.validateScript(draft)
                if (err.isBlank()) {
                    errMsg = null
                    errLine = -1
                    viewModel.applyScriptSource(draft)
                } else {
                    errMsg = err
                    errLine = Regex("""^line (\d+)""")
                        .find(err)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
            }
            EditorBarIcon(
                glyph = "↶",
                color = if (canUndo) ScColors.TextMain else ScColors.TextSub,
                enabled = canUndo,
            ) {
                history.undo(draft)?.let {
                    draft = it
                    errLine = -1
                    historyVersion++
                }
            }
            EditorBarIcon(
                glyph = "↷",
                color = if (canRedo) ScColors.TextMain else ScColors.TextSub,
                enabled = canRedo,
            ) {
                history.redo(draft)?.let {
                    draft = it
                    errLine = -1
                    historyVersion++
                }
            }
            // 保存 / 查找 提到顶栏（原来是 ⋮ 菜单里的二级入口）
            Text(
                text = "保存",
                fontSize = 11.sp,
                color = ScColors.Primary,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ScColors.Inset)
                    .clickable {
                        viewModel.saveScriptDraft(draft)
                        savedHint = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "查找",
                fontSize = 11.sp,
                color = ScColors.Primary,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ScColors.Inset)
                    .clickable { findOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box {
                EditorBarIcon(glyph = "⋮", color = ScColors.TextMain, enabled = true) {
                    menuOpen = true
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("文字大小", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            fontOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("帮助", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            helpOpen = true
                        },
                    )
                }
            }
        }

        // ---- 代码区 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ScriptColors.Background),
        ) {
            Column(modifier = Modifier.padding(start = 8.dp, top = 10.dp)) {
                for (n in 1..lineCount) {
                    Text(
                        text = "$n",
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF6E7681),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        history.push(draft); historyVersion++
                        draft = it
                        errLine = -1
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = codeSp.sp,
                        lineHeight = (codeSp + 6).sp,
                        color = ScriptColors.Text,
                    ),
                    cursorBrush = SolidColor(ScColors.Primary),
                    visualTransformation = ScriptHighlightTransformation(errLine),
                )
            }
        }

        // ---- 错误横幅（参考样张：浅红底 + 红边 + 右侧「关闭」，整条呼吸提醒）----
        errMsg?.let { msg ->
            val breathe = rememberInfiniteTransition(label = "errBanner")
            val glow by breathe.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "errGlow",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFDECEA).copy(alpha = 0.30f + 0.45f * glow))
                    .border(
                        1.dp,
                        Color(0xFFE5484D).copy(alpha = 0.35f + 0.65f * glow),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFB3261E),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "关闭",
                    fontSize = 12.sp,
                    color = Color(0xFFE5484D),
                    modifier = Modifier
                        .clickable {
                            errMsg = null
                            errLine = -1
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // ---- 保存提示（1.4s 自动消失）----
        if (savedHint) {
            LaunchedEffect(savedHint) {
                delay(1400)
                savedHint = false
            }
            Popup(alignment = Alignment.Center) {
                Text(
                    text = "脚本已保存",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC1F1F1F))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        // ---- 状态栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = labels.getOrElse(lane) { "未选择脚本" },
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "$lineCount 行 · ${codeSp}sp", fontSize = 11.sp, color = ScColors.TextSub)
        }

        // ---- 符号键行 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("TAB" to "    ", "(" to "(", ")" to ")", "[" to "[", "]" to "]",
                "," to ",", "." to ".").forEach { (label, insert) ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = ScColors.TextMain,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ScColors.Inset)
                        .clickable {
                            history.push(draft)
                            historyVersion++
                            draft = draft + insert
                        }
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    // ---- 查找 / 替换 ----
    if (findOpen) {
        Dialog(onDismissRequest = { findOpen = false }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("查找 / 替换", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it; findMsg = "" },
                        singleLine = true,
                        label = { Text("查找", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = replText,
                        onValueChange = { replText = it },
                        singleLine = true,
                        label = { Text("替换为", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    if (findMsg.isNotEmpty()) {
                        Text(
                            text = findMsg,
                            fontSize = 11.sp,
                            color = ScColors.Primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScriptPill("查找", Modifier.weight(1f)) {
                            findMsg = if (findText.isEmpty()) {
                                "请输入查找内容"
                            } else {
                                val n = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
                                    .findAll(draft).count()
                                "共找到 $n 处"
                            }
                        }
                        ScriptPill("替换", Modifier.weight(1f)) {
                            if (findText.isNotEmpty()) {
                                history.push(draft); historyVersion++
                                draft = draft.replaceFirst(findText, replText, ignoreCase = true)
                                findMsg = "已替换第 1 处"
                            }
                        }
                        ScriptPill("全部替换", Modifier.weight(1f)) {
                            if (findText.isNotEmpty()) {
                                history.push(draft); historyVersion++
                                val n = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
                                    .findAll(draft).count()
                                draft = draft.replace(
                                    Regex(Regex.escape(findText), RegexOption.IGNORE_CASE),
                                    replText,
                                )
                                findMsg = "已替换 $n 处"
                            }
                        }
                    }
                    Text(
                        text = "关闭",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { findOpen = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // ---- 文字大小 ----
    if (fontOpen) {
        Dialog(onDismissRequest = { fontOpen = false }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("文字大小", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "当前 ${codeSp}sp",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Slider(
                        value = codeSp.toFloat(),
                        onValueChange = { codeSp = it.toInt() },
                        valueRange = 10f..22f,
                        steps = 11,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        text = "关闭",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { fontOpen = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // ---- 帮助 ----
    if (helpOpen) {
        Dialog(onDismissRequest = { helpOpen = false }) {
            ScCard {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("脚本帮助", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = SCRIPT_HELP_TEXT,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "关闭",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { helpOpen = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 帮助正文（真内容，随语法更新）。 */
private val SCRIPT_HELP_TEXT = """
【基本结构】
  void init()   { … }   装载时执行一次（可预计算）
  void sample() { … }   每个样本执行一次（实时）
  void block()  { … }   每块执行一次（块处理，配合 blk_/conv/fft）

【样本变量】
  spl0 / spl1   左右声道当前样本，读入处理后写回
  srate         采样率（48000）

【参数声明】（写在脚本头部注释里，UI 自动生成控件）
  // slider1: 0.0, 8.0, 1.0    范围 + 可选默认值
  // toggle1: 静音, default=0
  // select1: 曲线 = 线性|硬削波, default=0
  // list1: 频段 = 低频|中频|高频, default=3   （位掩码）
  // button1: 重置               （按下仅一个样本为 1）

【语法】
  变量/数组：float x = 1.0;  float buf[4800];  if / while / for
  函数：float f(float a) { return a * 2; }   支持递归
  三元：x = c ? a : b;

【内建函数】
  数学：sin cos tan asin acos atan atan2 exp log log10 pow sqrt
        abs floor ceil round min max tanh fmod clamp sign saturate
  双二阶：bq_lp(id,f,q,x)  bq_hp(id,f,q,x)  bq_peak(id,f,q,db,x)
  延迟线：dl_write(id,x)  dl_read(id,samples)  dl_clear(id)   （4 条 × 1s）
  块处理：blk_size()  blk_get(ch,i)  blk_set(ch,i,v)
  FFT：fft_size(n) fft_load(i,v) fft_forward() fft_re(i) fft_im(i)
       fft_set(i,re,im) fft_inverse() fft_out(i)
  卷积：conv_setup(id,taps) conv_load(id,k,v) conv_run(id,ch)
       convfft_setup(taps) convfft_load(k,v) convfft_run(ch)  （长 IR，512 样本延迟）

【.eel 子集】
  slider1:0.5<0<1>gain   @init / @sample   function f(x) ( … )   隐式全局

【提示】
  · 参数超过 8 个同类控件不会显示；数组长度需为常量
  · 逐样本深递归非常耗 CPU，避免在 sample() 里递归
  · ▶ 运行前会编译校验，出错会提示行号并在该行标红
""".trimIndent()

/** 编辑器顶栏图标按钮：统一 40dp 点击区 + 居中，保证一排图标等距、中线一致。 */
@Composable
private fun EditorBarIcon(
    glyph: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            fontSize = 16.sp,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/** 编辑器撤销/重做历史：普通对象 + 显式栈，避免局部 delegate 变量在事件 lambda 里读旧快照。 */
private class EditorHistory {
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(prev: String) {
        if (undoStack.lastOrNull() != prev) {
            undoStack.add(prev)
            if (undoStack.size > 200) undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(current: String): String? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.add(current)
        return prev
    }

    fun redo(current: String): String? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.add(current)
        return next
    }
}
