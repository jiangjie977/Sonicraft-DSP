/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 部分交互与布局参照本人维护的 wecho 定制版（studio_rack 分支）。
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
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
import com.jiangjie.Sonicraft.privilege.BackendKind
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState

// 收藏屏（场景组合 + 我的 EQ 预设 + 收藏脉冲）（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

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
        // 把用户自己的组合与下方 EQ 预设 / 收藏脉冲挤到很下面
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
                    // 点它会打开「混响」页，与 AutoEq 毫无关系。
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
