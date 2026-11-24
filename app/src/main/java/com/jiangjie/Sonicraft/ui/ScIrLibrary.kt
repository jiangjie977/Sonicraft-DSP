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

// 脉冲库内容区（选 IR / 导入 / 收藏）（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

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

