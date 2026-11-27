/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 部分交互与布局参照作者本人维护的定制分支。
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

// 空间环绕 / 立体声宽度 详情页（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

/**
 * 空间环绕详情内容。
 *
 * 三块：总控（模式/距离）、声场（环绕/中置/前场增益）、说明。
 * 参数全部实时下发（节点内 20ms 平滑），开关走交叉淡化，无爆音。
 */
@Composable
internal fun ScVsDetailContent(viewModel: MainViewModel) {
    val vsEnabled by viewModel.vsEnabled.collectAsStateWithLifecycle()
    val vsMode by viewModel.vsMode.collectAsStateWithLifecycle()
    val s71Enabled by viewModel.s71Enabled.collectAsStateWithLifecycle()
    val s71Upmix by viewModel.s71Upmix.collectAsStateWithLifecycle()
    val s71Externalize by viewModel.s71Externalize.collectAsStateWithLifecycle()
    val s71Trims by viewModel.s71Trims.collectAsStateWithLifecycle()
    val s71Mutes by viewModel.s71Mutes.collectAsStateWithLifecycle()
    val s71BassBoost by viewModel.s71BassBoost.collectAsStateWithLifecycle()
    val s71BassMgmt by viewModel.s71BassMgmt.collectAsStateWithLifecycle()
    val s71RearDelay by viewModel.s71RearDelay.collectAsStateWithLifecycle()

    val vsRearSpace by viewModel.vsRearSpace.collectAsStateWithLifecycle()
    val vsHeight by viewModel.vsHeight.collectAsStateWithLifecycle()
    val vsTransProtect by viewModel.vsTransProtect.collectAsStateWithLifecycle()
    val vsDistance by viewModel.vsDistance.collectAsStateWithLifecycle()
    val vsBs775 by viewModel.vsBs775.collectAsStateWithLifecycle()
    val vsDebugRaw by viewModel.vsDebugRaw.collectAsStateWithLifecycle()
    val vsTrims by viewModel.vsTrims.collectAsStateWithLifecycle()
    val vsMutes by viewModel.vsMutes.collectAsStateWithLifecycle()
    val vsRoomSize by viewModel.vsRoomSize.collectAsStateWithLifecycle()
    val vsRoomWet by viewModel.vsRoomWet.collectAsStateWithLifecycle()
    val vsEncodeStrength by viewModel.vsEncodeStrength.collectAsStateWithLifecycle()
    val vsCenterMode by viewModel.vsCenterMode.collectAsStateWithLifecycle()
    val vsDialog by viewModel.vsDialog.collectAsStateWithLifecycle()
    val vsBassEnh by viewModel.vsBassEnh.collectAsStateWithLifecycle()
    val vsBassEnhMode by viewModel.vsBassEnhMode.collectAsStateWithLifecycle()
    val vsRearCenter by viewModel.vsRearCenter.collectAsStateWithLifecycle()
    val vsRearDelay by viewModel.vsRearDelay.collectAsStateWithLifecycle()
    val vsExpanded = rememberExpandedFollowing(vsEnabled, "vs_card")
    // ---- Ambisonic 解码器（第三个空间化器）----
    val vsAmbisonic by viewModel.vsAmbisonic.collectAsStateWithLifecycle()
    val vsAmbiFocus by viewModel.vsAmbiFocus.collectAsStateWithLifecycle()
    val vsAmbiDecoder by viewModel.vsAmbiDecoder.collectAsStateWithLifecycle()
    val vsVbapSpread by viewModel.vsVbapSpread.collectAsStateWithLifecycle()
    val vsHrtfModel by viewModel.vsHrtfModel.collectAsStateWithLifecycle()
    // 三个空间化器任一开启 → 下方三张「公共接口」卡才显示
    val surroundActive = vsEnabled || s71Enabled || vsAmbisonic
    // 自检在后台线程跑（会分配内存、算 7 个方位 × 2 轮），结果写回 State 再弹窗
    var vsReport by remember { mutableStateOf<String?>(null) }
    var vsTesting by remember { mutableStateOf(false) }

    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "解码器：经典环绕",
                checked = vsEnabled,
                onCheckedChange = { viewModel.setVsEnabled(!vsEnabled) },
                switchEnabled = true,
                expanded = vsExpanded.value,
                onToggleExpand = { vsExpanded.value = !vsExpanded.value },
            )
            if (vsExpanded.value) {
                Column(
                    modifier = Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 三个开关并排（按下=启用，弹起=禁用）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VsButtonToggle(
                            label = "BS.775 等响",
                            checked = vsBs775,
                            enabled = vsEnabled,
                            modifier = Modifier.weight(1f),
                            onCheckedChange = { viewModel.setVsBs775(it) },
                        )
                        VsButtonToggle(
                            label = "瞬态保护",
                            checked = vsTransProtect,
                            enabled = vsEnabled,
                            modifier = Modifier.weight(1f),
                            onCheckedChange = { viewModel.setVsTransProtect(it) },
                        )
                        VsButtonToggle(
                            label = "二级解码",
                            checked = vsEncodeStrength > 0.01f,
                            enabled = vsEnabled,
                            modifier = Modifier.weight(1f),
                            onCheckedChange = { viewModel.setVsSecondDecodeEnabled(it) },
                        )
                    }

                    Text(
                        text = "声场模式",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                    VsChoiceRow(
                        options = listOf("全景声", "LCRS", "立体声", "单声道"),
                        selected = vsMode,
                        enabled = vsEnabled,
                        onSelect = viewModel::setVsMode,
                    )




                    // 三个连续量用旋钮：竖直拖动调值 / 双击复位 / 长按精调
                    // 与上方「声场模式」按钮留出间距，避免旋钮顶部贴住按钮底边
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ScKnob(
                            label = "距离",
                            value = vsDistance,
                            min = 0f,
                            max = 1f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            format = { "%.2f".format(it) },
                            resetValue = 0.5f,
                            onValueChange = viewModel::setVsDistance,
                        )
                        ScKnob(
                            label = "高度感",
                            value = vsHeight,
                            min = 0f,
                            max = 1f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            format = { "%.2f".format(it) },
                            resetValue = 0.5f,
                            onValueChange = viewModel::setVsHeight,
                        )
                        ScKnob(
                            label = "空间感",
                            value = vsRearSpace,
                            min = 0f,
                            max = 1f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            format = { "%.2f".format(it) },
                            resetValue = 0.5f,
                            onValueChange = viewModel::setVsRearSpace,
                        )
                    }

                    // （二级解码开关已并入顶部按钮排）
                    if (vsEncodeStrength > 0.01f) {
                        VsGainRow(
                            label = "下混编码强度",
                            value = vsEncodeStrength,
                            range = 0f..1f,
                            suffix = "",
                            enabled = vsEnabled,
                            onChange = viewModel::setVsEncodeStrength,
                        )
                    }


                }
            }
        }
    }

    // 空间环绕 · 公共接口：以下参数对**所有**环绕模式生效（房间 / 中置 / 对话 /
    // 低音 / 后中置），新增环绕模式时直接复用，不必重复实现。
    // ==================== 解码器：Ambisonic（与经典环绕 / 7.1 环绕 互斥）====================
    // 二阶 HOA：10 声源 → 9 通道球谐 → 12 虚拟扬声器（8 水平 45° 等距 + 4 上方 35°）→ HRTF。
    // 与经典环绕共用同一个 native 节点，只切换解码方式，因此两者不能同时开。
    val ambiExpanded = rememberExpandedFollowing(vsAmbisonic, "ambi_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "解码器：Ambisonic",
                checked = vsAmbisonic,
                onCheckedChange = { viewModel.setAmbisonicEnabled(it) },
                switchEnabled = true,
                expanded = ambiExpanded.value,
                onToggleExpand = { ambiExpanded.value = !ambiExpanded.value },
            )
            if (ambiExpanded.value) {
                Column(
                    modifier = Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 解码方式：同一组 12 只虚拟扬声器，两种算法
                    Text(
                        text = "解码方式",
                        fontSize = 11.5.sp,
                        color = ScColors.TextSub,
                    )
                    VsChoiceRow(
                        options = listOf("HOA（球谐）", "VBAP（摆位）"),
                        selected = if (vsAmbiDecoder == 2) 1 else 0,
                        enabled = vsAmbisonic,
                        onSelect = { viewModel.setVsAmbiDecoder(if (it == 1) 2 else 1) },
                    )
                    // 旋钮自带外发光，紧贴上方按钮会显得"顶住"——额外留 10dp
                    Spacer(modifier = Modifier.height(10.dp))
                    // HOA 只有三个客观参数 → 一行放下；VBAP 多一个扩散 → 两两成行
                    if (vsAmbiDecoder == 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            // VBAP 只点亮 2 只扬声器，加一点扩散能减轻"硬点"
                            AmbiKnob(
                                label = "VBAP 扩散",
                                value = vsVbapSpread,
                                max = 1f,
                                modifier = Modifier.weight(1f),
                                sizeDp = 84,
                                onChange = { viewModel.setVsVbapSpread(it) },
                            )
                            AmbiKnob(
                                label = "前向聚焦",
                                value = vsAmbiFocus,
                                max = 1f,
                                modifier = Modifier.weight(1f),
                                sizeDp = 84,
                                onChange = { viewModel.setAmbiFocus(it) },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            AmbiKnob(
                                label = "高度强度",
                                value = vsHeight,
                                max = 1f,
                                modifier = Modifier.weight(1f),
                                sizeDp = 84,
                                onChange = { viewModel.setVsHeight(it) },
                            )
                            AmbiKnob(
                                label = "环绕声扩展",
                                value = vsRearSpace,
                                max = 2f,
                                modifier = Modifier.weight(1f),
                                sizeDp = 84,
                                onChange = { viewModel.setVsRearSpace(it) },
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            AmbiKnob(
                                label = "前向聚焦",
                                value = vsAmbiFocus,
                                max = 1f,
                                modifier = Modifier.weight(1f),
                                onChange = { viewModel.setAmbiFocus(it) },
                            )
                            AmbiKnob(
                                label = "高度强度",
                                value = vsHeight,
                                max = 1f,
                                modifier = Modifier.weight(1f),
                                onChange = { viewModel.setVsHeight(it) },
                            )
                            AmbiKnob(
                                label = "环绕声扩展",
                                value = vsRearSpace,
                                max = 2f,
                                modifier = Modifier.weight(1f),
                                onChange = { viewModel.setVsRearSpace(it) },
                            )
                        }
                    }
                    ScInfoChip(body = "二阶球谐（9 通道）编码 → 12 虚拟扬声器解码，最大 rE 加窗压制旁瓣。" +
                            "与「经典环绕」互斥：两者共用同一渲染节点，只是解码方式不同。", title = "空间环绕")
                    // 同上：说明按钮与卡片下边框之间留出呼吸
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }

    // ==================== 解码器：7.1 环绕（与经典环绕互斥）====================
    // 独立效果器：7.1 上混 + HRTF 双耳化（算法参考开源项目 Surround71）。
    // 与经典环绕互斥——两个空间化器同时开启会叠加成乱声，VM 层已做自动关闭。
    val s71Expanded = rememberExpandedFollowing(s71Enabled, "s71_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "解码器：7.1 环绕",
                checked = s71Enabled,
                onCheckedChange = { viewModel.setS71Enabled(it) },
                switchEnabled = true,
                expanded = s71Expanded.value,
                onToggleExpand = { s71Expanded.value = !s71Expanded.value },
            )
            if (s71Expanded.value) {
                Column(
                    modifier = Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 旋钮自带外发光，紧贴卡头会显得顶住
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ScKnob(
                            label = "上混强度",
                            value = s71Upmix,
                            min = 0f,
                            max = 1f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            format = { "%.2f".format(it) },
                            resetValue = 0.85f,
                            onValueChange = viewModel::setS71Upmix,
                        )
                        ScKnob(
                            label = "外部化",
                            value = s71Externalize,
                            min = 0f,
                            max = 1f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            format = { "%.2f".format(it) },
                            resetValue = 0.35f,
                            onValueChange = viewModel::setS71Externalize,
                        )
                        ScKnob(
                            label = "后方延迟",
                            value = s71RearDelay,
                            min = 0f,
                            max = 20f,
                            sizeDp = 94,
                            textScale = 0.55f,
                            format = { "%.1f".format(it) },
                            resetValue = 0f,
                            onValueChange = viewModel::setS71RearDelay,
                        )
                    }
                    Text(
                        text = if (s71Enabled) {
                            "当前生效：7.1 环绕（经典环绕已自动关闭）"
                        } else {
                            "当前未启用"
                        },
                        fontSize = 11.sp,
                        color = if (s71Enabled) ScColors.Primary else ScColors.TextSub,
                    )
                    ScInfoChip(body = "立体声 → 7.1 上混 → HRTF 双耳化。算法与参数参考开源项目 Surround71，"
                            + "与上方「经典环绕」互斥（开启任一后另一个自动关闭）。", title = "空间环绕")
                }
            }
        }
    }

    // 三个空间化器全关时，公共接口整体隐藏（它们只对"正在跑的环绕"有意义）
    if (surroundActive) {
    val publicExpanded = rememberExpandedFollowing(false, "vs_public_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "公共接口",
                checked = false,
                onCheckedChange = {},
                switchEnabled = false,
                expanded = publicExpanded.value,
                onToggleExpand = { publicExpanded.value = !publicExpanded.value },
                showSwitch = false,
            )
            if (publicExpanded.value) {
                    // ---- HRTF 模型（影响两个环绕引擎的双耳渲染）----
                    // 0 = 参数化（低算力）；1..5 = 实测 HRIR，557 tap 卷积
                    Text(
                        text = "HRTF 模型（耳机渲染）",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // 两行共用同一个模型下标：不在本行的候选项必须不选中（-1）。
                    // 直接 coerceIn(0,2) 会把 0..2 的模型映射成本行第 0 项，导致两行同时高亮。
                    //
                    // 行距单独给 14dp（放在内层 Column 里，不受外层 spacedBy 影响）：
                    // 按钮高 40dp，默认间距下两行几乎贴在一起，手指容易点到相邻行。
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        VsChoiceRow(
                            options = listOf("参数化", "KEMAR", "K-Hannover"),
                            selected = if (vsHrtfModel in 0..2) vsHrtfModel else -1,
                            enabled = surroundActive,
                            onSelect = viewModel::setVsHrtfModel,
                        )
                        VsChoiceRow(
                            options = listOf("CIPIC", "ARI", "LISTEN"),
                            selected = if (vsHrtfModel in 3..5) vsHrtfModel - 3 else -1,
                            enabled = surroundActive,
                            onSelect = { viewModel.setVsHrtfModel(it + 3) },
                        )
                    }

                    // ---- 房间（RIR 卷积，预设名与卷积器一致：小房间/客厅/音乐厅/大教堂）----
                    Text(
                        text = "环境声场",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    VsChoiceRow(
                        options = listOf("关", "小房间", "客厅", "音乐厅", "大教堂"),
                        selected = vsRoomSize,
                        enabled = surroundActive,
                        onSelect = viewModel::setVsRoomSize,
                    )

                    // ---- 中置模式（关 / 直通 / 混入前置）----
                    Text(
                        text = "中置模式",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // 中置模式三选项 + 后中置开关，同一行四个按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("关闭", "直通", "混入前置").forEachIndexed { index, text ->
                            val active = index == vsCenterMode
                            val shape = RoundedCornerShape(10.dp)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(
                                        when {
                                            !surroundActive -> Color(0xFFF2F4F7)
                                            active -> ScColors.Primary
                                            else -> Color(0xFFF6F9FC)
                                        },
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = when {
                                            !surroundActive -> Color(0xFFE6EBF1)
                                            active -> ScColors.Primary
                                            else -> Color(0xFFD9E1EA)
                                        },
                                        shape = shape,
                                    )
                                    .then(
                                        if (surroundActive) {
                                            Modifier.scNoRippleClick { viewModel.setVsCenterMode(index) }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(vertical = 10.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = text,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        !surroundActive -> ScColors.TextSub.copy(alpha = 0.5f)
                                        active -> Color.White
                                        else -> ScColors.TextSub
                                    },
                                )
                            }
                        }
                        // 7.1 环绕没有「后中置」这一路 —— 该模式下隐藏此开关
                        if (!s71Enabled) {
                            VsButtonToggle(
                                label = "后中置",
                                checked = vsRearCenter,
                                enabled = surroundActive,
                                modifier = Modifier.weight(1f),
                                onCheckedChange = { viewModel.setVsRearCenter(it) },
                            )
                        }
                    }

                    // ---- 低音增强（对数域谐波）：滑条改为最下方旋钮 ----

                    Text(
                        text = "低音增强获取通道",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    VsChoiceRow(
                        options = listOf("取自 LFE", "取自前置"),
                        selected = vsBassEnhMode,
                        enabled = surroundActive,
                        onSelect = viewModel::setVsBassEnhMode,
                    )


                // 房间为「关」时混响量不生效 —— 只用于下方提示文案
                val roomWetEnabled = surroundActive && vsRoomSize > 0
                // 三个连续量用旋钮（与经典环绕卡同一套 ScKnob 样式）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    // 三个旋钮各占 1/3 等宽单元格：混响量外套了 8dp padding
                    // （给 alpha 图层留空），用 SpaceEvenly 的话它的宽度不一样、
                    // 相邻间距会不均匀；改成等宽单元格后间距恒定。
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 房间为「关」时混响量不生效 → 用 alpha 压暗。
                    // 关键：padding 必须放在 alpha 的**内层**。
                    // alpha 会把子树渲染进离屏图层并按图层 bounds 裁剪；
                    // padding 在内层时，图层比旋钮大一圈，外发光落在图层内不会被切。
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .alpha(if (roomWetEnabled) 1f else 0.45f)
                                .padding(8.dp),
                        ) {
                            ScKnob(
                                label = "混响量",
                                value = vsRoomWet,
                                min = 0f,
                                max = 1f,
                                sizeDp = 94,
                                textScale = 0.60f,
                                format = { "%.2f".format(it) },
                                resetValue = 0.3f,
                                onValueChange = viewModel::setVsRoomWet,
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        ScKnob(
                            label = "对话清晰度",
                            value = vsDialog,
                            min = -6f,
                            max = 6f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            bipolar = true,
                            format = { "%+.1f dB".format(it) },
                            resetValue = 0f,
                            onValueChange = viewModel::setVsDialog,
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        ScKnob(
                            label = "低音增强",
                            value = vsBassEnh,
                            min = 0f,
                            max = 1f,
                            sizeDp = 94,
                            textScale = 0.60f,
                            format = { "%.2f".format(it) },
                            resetValue = 0f,
                            onValueChange = viewModel::setVsBassEnh,
                        )
                    }
                }

                // LFE 电平 / 低音管理：仅 Surround71 有这两级（矩阵环绕的 LFE 电平由
                // 「声道 Trim · LFE」与「低音增强」共同承担），因此只在 S71 开启时可调。
                if (s71Enabled) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ScKnob(
                        label = "LFE 电平",
                        value = s71BassBoost,
                        min = 0f,
                        max = 12f,
                        sizeDp = 94,
                        textScale = 0.60f,
                        format = { "%+.1f".format(it) },
                        resetValue = 2f,
                        onValueChange = viewModel::setS71BassBoost,
                    )
                    ScKnob(
                        label = "低音管理",
                        value = s71BassMgmt,
                        min = 0f,
                        max = 1f,
                        sizeDp = 94,
                        textScale = 0.60f,
                        format = { "%.2f".format(it) },
                        resetValue = 0.25f,
                        onValueChange = viewModel::setS71BassMgmt,
                    )
                }

                    if (vsRearCenter && !s71Enabled) {
                        VsGainRow(
                            label = "后中置延迟",
                            value = vsRearDelay,
                            range = 0f..15f,
                            suffix = " ms",
                            enabled = surroundActive,
                            onChange = viewModel::setVsRearDelay,
                        )
                    }

                Spacer(modifier = Modifier.height(16.dp))

                ScInfoChip(body = "以下参数对「所有」环绕模式生效，新增模式无需重复实现。" +
                        "声道 Trim 与声像定位自检同属公共能力（见下方两卡）。", title = "空间环绕")
                // 公共接口块没有内层 Column 的底部内边距，这里补一段，避免说明按钮贴住卡片下边框
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }

    // 公共接口 · 声道 Trim：7 路独立增益 + 静音（所有环绕模式共用同一套 7 声道布局）
    val trimExpanded = rememberExpandedFollowing(false, "vs_trim_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "公共接口 · 声道 Trim",
                checked = false,
                onCheckedChange = {},
                switchEnabled = false,
                expanded = trimExpanded.value,
                onToggleExpand = { trimExpanded.value = !trimExpanded.value },
                showSwitch = false,
            )
            if (trimExpanded.value) {
                Column(modifier = Modifier.padding(bottom = 20.dp)) {
                    // 横向滚动：经典环绕 7 列 / 7.1 环绕 8 列，每列 52dp 固定宽，
                    // 总宽超出屏幕时左右滑动查看（原来 SpaceEvenly 会被压扁到挤在一起）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 按「当前实际生效的解码器」切换声道列表：
                        //   经典环绕 / Ambisonic → L / C / R / SL / SR / CS / LFE（7 路，即 HOA 的 6 声源 + LFE）
                        //   7.1 环绕 → FL / FR / FC / LFE / BL / BR / SL / SR（8 路，顺序同 C++ 枚举）
                        // Ambisonic 的 4 路「上方声源」由 FL/FR/SL/SR 的 >3 kHz 派生，
                        // 电平由「高度强度（上方包围）」控制，不单列 Trim。
                        val s71Mode = s71Enabled && !vsEnabled
                        val names = if (s71Mode) {
                            listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
                        } else {
                            listOf("L", "C", "R", "SL", "SR", "CS", "LFE")
                        }
                        names.forEachIndexed { index, name ->
                            VsTrimColumn(
                                name = name,
                                value = if (s71Mode) {
                                    s71Trims.getOrElse(index) { 0f }
                                } else {
                                    vsTrims.getOrElse(index) { 0f }
                                },
                                muted = if (s71Mode) {
                                    s71Mutes.getOrElse(index) { 0f } > 0.5f
                                } else {
                                    vsMutes.getOrElse(index) { 0f } > 0.5f
                                },
                                enabled = surroundActive,
                                onValue = {
                                    if (s71Mode) viewModel.setS71Trim(index, it)
                                    else viewModel.setVsTrim(index, it)
                                },
                                onMute = {
                                    if (s71Mode) viewModel.setS71Mute(index, it)
                                    else viewModel.setVsMute(index, it)
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            if (s71Enabled && !vsEnabled) viewModel.resetS71Trims() else viewModel.resetVsTrims()
                        }) {
                            Text("还原默认值", fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "仅解码测试（旁通其余环节）",
                            fontSize = 12.sp,
                            color = ScColors.TextMain,
                            modifier = Modifier.weight(1f),
                        )
                        ScTinySwitch(
                            checked = vsDebugRaw,
                            onCheckedChange = { viewModel.setVsDebugRaw(it) },
                        )
                    }
                    Text(
                        text = "← 左右滑动查看更多声道 →",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    ScInfoChip(body = "逐声道校验：把某一路拉到 +12 dB 或静音，即可确认该声道是否真的在工作。" +
                            "LFE 不走双耳渲染，直接混入双耳。\n" +
                            "打开「仅解码测试」后链路只剩：解码 → 声道 trim → 双耳渲染，" +
                            "编解码往返、后处理与房间全部旁通。", title = "空间环绕")
                }
            }
        }
    }

    // 公共接口 · 声像定位自检：验证 7 声道布局的 ITD/ILD，与模式无关，故同属公共能力
    val testExpanded = rememberExpandedFollowing(false, "vs_selftest_card")
    ScCard(modifier = Modifier.fillMaxWidth(), padded = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            ScSlotHeader(
                title = "公共接口 · 声像定位自检",
                checked = false,
                onCheckedChange = {},
                switchEnabled = false,
                expanded = testExpanded.value,
                onToggleExpand = { testExpanded.value = !testExpanded.value },
                showSwitch = false,
            )
            if (testExpanded.value) {
                Column(
                    modifier = Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = (if (vsAmbisonic) {
                            "用带限噪声逐个测 Ambisonic 的 12 个扬声器（8 水平 + 4 上方 35°）的 ITD/ILD，"
                        } else {
                            "用带限噪声测 7 个方位的 ITD/ILD，"
                        }) +
                            "校验左右手性与镜像对称，并把偏差写回渲染器做自动校正。" +
                            "测量方位随解码器切换（经典 7 声道 / Ambisonic 12 扬声器）。",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                    )
                    TextButton(
                        onClick = {
                            if (vsTesting) return@TextButton
                            vsTesting = true
                            Thread {
                                val r = viewModel.runVirtualSurroundSelfTest()
                                vsReport = r.ifBlank { "引擎未就绪，无法自检" }
                                vsTesting = false
                            }.start()
                        },
                    ) {
                        Text(
                            text = if (vsTesting) "自检中…" else "开始自检（含自动校正）",
                            fontSize = 12.sp,
                        )
                    }
                    vsReport?.let { report ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { vsReport = null },
                            title = { Text("声像定位自检", fontSize = 14.sp) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = report,
                                        fontSize = 9.sp,
                                        lineHeight = 13.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        softWrap = false,
                                        color = ScColors.TextMain,
                                    )
                                    Text(
                                        text = (if (vsAmbisonic) {
                                "Ambisonic 布局：8 水平（0/45/…/315°）+ 4 上方（±45°/±135° @35°）\n"
                            } else {
                                "声道：SL/SR 侧后 ±110°　FL/FR 前置 ±30°　C 中置 0°　CS 正后 180°\n"
                            }) +
                                            "ITD_me/th = 实测/期望耳间时延（样本）\n" +
                                            "ILD_me/tg = 实测/目标耳间电平差（dB）\n" +
                                            "LFE 不参与 HRTF，与 C 同为 0°，仅作参考",
                                        fontSize = 10.sp,
                                        color = ScColors.TextSub,
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { vsReport = null }) { Text("好") }
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

/** 0~1 百分比滑条（Ambisonic 的「前向聚焦 / 上方权重」） */
@Composable
private fun AmbiKnob(
    label: String,
    value: Float,
    max: Float,
    modifier: Modifier = Modifier,
    sizeDp: Int = 88,
    onChange: (Float) -> Unit,
) {
    // 百分比参数（前向聚焦 / 高度强度 / 扩散 / 环绕扩展）统一用旋钮
    ScKnob(
        label = label,
        value = value,
        min = 0f,
        max = max,
        modifier = modifier,
        sizeDp = sizeDp,
        textScale = 0.6f,
        format = { "%.2f".format(it) },
        onValueChange = onChange,
    )
}

/** 单个增益滑条：标签 + 数值 + 滑块（范围与单位可配，低音增强用 0~1） */
@Composable
private fun VsGainRow(
    label: String,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = -12f..12f,
    suffix: String = " dB",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, fontSize = 12.sp, color = ScColors.TextMain)
            Text(
                text = if (suffix == " dB") "%+.1f dB".format(value) else "%.2f%s".format(value, suffix),
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
        }
        // 滑条收窄到 78%：右侧留出手势安全区。
        // 否则整页几乎都是滑条，用户想上下滚动页面时会把参数拖走（实测踩过）。
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(0.78f),
        )
    }
}

/** 单路 Trim：竖滑条 + 数值 + 静音 */
@Composable
private fun VsTrimColumn(
    name: String,
    value: Float,
    muted: Boolean,
    enabled: Boolean,
    onValue: (Float) -> Unit,
    onMute: (Boolean) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(190.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 竖滑条：requiredWidth 让布局宽度不受父级约束（graphicsLayer 只改绘制，
            // 用 width 会被父盒子裁成很短一段），再整体旋转 −90°。
            Slider(
                value = value,
                onValueChange = onValue,
                valueRange = -12f..12f,
                enabled = enabled && !muted,
                modifier = Modifier
                    .requiredWidth(190.dp)
                    .rotate(-90f),
            )
        }
        Text(
            text = if (muted) "静音" else "%.1f".format(value),
            fontSize = 10.sp,
            color = if (muted) ScColors.TextSub else ScColors.TextMain,
        )
        ScTinySwitch(
            checked = muted,
            onCheckedChange = onMute,
        )
        Text(text = name, fontSize = 11.sp, color = ScColors.TextSub)
    }
}

/** 一行互斥选项（中置模式 / 低音增强来源） */
@Composable
private fun VsChoiceRow(
    options: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, text ->
            val active = index == selected
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(
                        when {
                            !enabled -> Color(0xFFF2F4F7)
                            active -> ScColors.Primary
                            else -> Color(0xFFF6F9FC)
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = when {
                            !enabled -> Color(0xFFE6EBF1)
                            active -> ScColors.Primary
                            else -> Color(0xFFD9E1EA)
                        },
                        shape = shape,
                    )
                    .then(
                        if (enabled) {
                            Modifier.scNoRippleClick { onSelect(index) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    maxLines = 1,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        !enabled -> ScColors.TextSub.copy(alpha = 0.5f)
                        active -> Color.White
                        else -> ScColors.TextSub
                    },
                )
            }
        }
    }
}


@Composable
internal fun VsButtonToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Color(0xFFF2F4F7)
                    checked -> ScColors.Primary
                    else -> Color(0xFFE7ECF2)
                },
            )
            .then(
                if (enabled) {
                    Modifier.scNoRippleClick { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            maxLines = 1,
            fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
            color = when {
                !enabled -> ScColors.TextSub.copy(alpha = 0.5f)
                checked -> Color.White
                else -> ScColors.TextSub
            },
        )
    }
}


