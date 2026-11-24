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

// 限幅器 / 卷积混响 / 算法混响（FDN）详情页（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

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
                    // 左右 14 → 4dp：
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
                    // 左右 14 → 4dp：
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
 * 卷积混响的**内容**。
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
                            // 四等分 chip：窄屏预设名较长时不折成两行
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
                            // 双击归位到「关」（索引 0）；离散档位不开长按输入
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
                    // ---- 病态 IR 警示 ----
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

