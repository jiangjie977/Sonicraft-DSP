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
     * 页头开关：压限器页的总开关已移进「多段压限」卡片内，
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

