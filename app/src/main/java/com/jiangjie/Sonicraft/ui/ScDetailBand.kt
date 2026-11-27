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

// 多段压限 / 单段压缩器详情页（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

/** U7 多频段压限详情内容：单段压缩器 + 三段多频段参数（原主页压限区整体迁入；两者互斥状态机不变） */
@Composable
internal fun ScBandDetailContent(viewModel: MainViewModel) {
    val compressorEnabled by viewModel.compressorEnabled.collectAsStateWithLifecycle()
    val compressorThreshold by viewModel.compressorThreshold.collectAsStateWithLifecycle()
    val compressorRatio by viewModel.compressorRatio.collectAsStateWithLifecycle()
    val compressorAttack by viewModel.compressorAttack.collectAsStateWithLifecycle()
    val compressorRelease by viewModel.compressorRelease.collectAsStateWithLifecycle()
    val compressorKnee by viewModel.compressorKnee.collectAsStateWithLifecycle()
    val compressorMakeup by viewModel.compressorMakeup.collectAsStateWithLifecycle()
    val compressorMix by viewModel.compressorMix.collectAsStateWithLifecycle()
    val compressorDetect by viewModel.compressorDetect.collectAsStateWithLifecycle()
    val compressorLookahead by viewModel.compressorLookahead.collectAsStateWithLifecycle()
    val compressorOversample by viewModel.compressorOversample.collectAsStateWithLifecycle()
    val compressorSidechainHp by viewModel.compressorSidechainHp.collectAsStateWithLifecycle()
    val multibandParams by viewModel.multibandParams.collectAsStateWithLifecycle()
    // 多段压限的总开关
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
                // 折叠态**整卡可点**：
                // 原来 clickable 只挂在标题 Text 上，点卡片上下留白、右侧空白毫无反应，
                // 手感像「没点到」。这里把点击区提到卡片最外层（含 ScCard 的 16dp 内边距）。
                // 展开态**不能加**——否则点旋钮/滑块/分段控件会被外层吞成「折叠」。
                .then(
                    if (!multibandExpanded) Modifier.clickable { multibandExpanded = true }
                    else Modifier,
                ),
        ) {
            Column(
                // 收起时 10dp、展开时 14dp：
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
                // 联动状态 / A-B 快照：提前 collect 供标题行使用
                val linked by viewModel.multibandLinked.collectAsStateWithLifecycle()
                var abSlot by remember { mutableStateOf('A') }
                // 折叠头：点标题行展开/收回
                // 通用卡头；trailing 放组内特有控件。
                // 顺序固定为「标题 … 联动/A|B … 开关 … ▾」—— 开关是这一组控件的收尾。
                ScSlotHeader(
                    title = "多段压限",
                    checked = multibandEnabled && sectionEnabled,
                    onCheckedChange = { viewModel.setMultibandEnabled(!multibandEnabled) },
                    switchEnabled = sectionEnabled,
                    expanded = multibandExpanded,
                    onToggleExpand = { multibandExpanded = !multibandExpanded },
                ) {
                    // 联动开关（P2）：开 = 改任一段的段内参数同步三段；关 = 各段独立
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
                    // A/B 快照（P3）：切到另一槽时，先把当前存回原槽，
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
                // 旁路提示：
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
                // VFD 的视觉特征：深色底 + 离散发光段 + 青绿磷光 + 段间留缝 +
                // 柔和外发光。老式功放/AV 接收机的电平表都是这个样子。
                // 与之前的「浅灰底 + 连续色块填充」是两套完全不同的语言——
                // 连续填充像「进度条」，分段发光像「仪表」。
                // 分段逻辑：满量程 24dB → 24 段，每段 1dB。点亮段按 GR 深度从左往右亮。
                // 颜色仍保留三档警示（前 6 段青绿 / 中 6 段琥珀 / 后 12 段红），
                // 只是把「连续渐变」换成了「离散段」。
                run {
                    val grMax = minOf(meters.grDb(0), meters.grDb(1), meters.grDb(2))
                    val norm = (-grMax / 24f).coerceIn(0f, 1f)
                    // 三段压缩开关：「总 GR」标签是这块仪表的电源指示——
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
                                // 16 格 × 1.5dB：
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
                                // 等宽 + 右对齐 + **固定宽度**：
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
                // 频点划分条：三段色块 + 两根可拖竖线，
                // 取代原先两个独立 Slider——拖线即改分频点，颜色区域随分频点实时变化。
                // 横轴固定 20Hz~20kHz 对数刻度：这样色块宽度才真实反映各频段的频率占比，
                // 而不是「两个滑块各占一半」的假象。
                run {
                    // 点击 LOW / MID 色块输入截止频率：
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
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.weight(n1.coerceAtLeast(0.02f)).fillMaxHeight().background(Color(0xFFF7EFE1)))
                            Box(Modifier.weight((n2 - n1).coerceAtLeast(0.02f)).fillMaxHeight().background(Color(0xFFDDEAF6)))
                            Box(Modifier.weight((1f - n2).coerceAtLeast(0.02f)).fillMaxHeight().background(Color(0xFFEDE2F6)))
                        }
                        // 段名：字号 10→13sp + 加粗。
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
                        // 两根分频点**推子**。
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
                                // 把手轮廓：
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
                    // 分频点读数：两个数值**各自跟随自己的竖线**居中，
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
                // ==== 每段 IN/GR/OUT 实时电平表====
                // 数据来自引擎的块级计量（multiband.hpp band_in_pk_/gain_db_/band_out_pk_），
                // 经 JNI 一次取 9 个 float。轮询放在页面作用域里，离开页面自动停止。
                run {
                    // meters / LaunchedEffect 已上移到作用域顶部（标题行总 GR 也要用）
                    // 参数展开状态**三段共享**：点任一段的「更多参数」
                    // 三段同时展开/收起。原来每段独立，会出现「只有中间那段变高」的参差，
                    // 破坏三张卡等高的视觉一致性。
                    var paramsExpandedAll by remember { mutableStateOf(false) }
                    val names = listOf("低频段", "中频段", "高频段")
                    val accents = listOf(Color(0xFF8A6D3B), Color(0xFF3B6A8A), Color(0xFF6B4A8A))
                    // **大气重排**：三段各自成一张浅色底卡，
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
                                    // 段色 13% 做底：7% 在白卡上三段几乎同色，
                                    // 主要靠色点区分；13% 后边界一眼可辨，又不至于压过内容
                                    .background(accents[b].copy(alpha = 0.13f))
                                    // 极淡描边：底卡在白卡上靠色差定界，
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
                                // 阈值旋钮：它是三段里最常动的参数，
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
                                // 自动增益补偿：把该段当前 GR 反向加到段增益上，
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
                                // 参数折叠：压缩比/拐点/增益改动频率低，
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
        // 卡片背景回到默认白卡：
        // 之前展开时整卡铺浅灰 #E8EEF4，白色陶瓷旋钮压在上面显得突兀；
        // 现在与「三段动态」卡片一致，都是标准 ScCard 白底。
        ScCard(
            padded = false,
            modifier = Modifier
                .fillMaxWidth()
                // 折叠态整卡可点：
                // 与三段卡同一套规则 —— 展开态不加，避免吞掉旋钮/滑块的手势。
                .then(
                    if (!compressorExpanded) Modifier.clickable { compressorExpanded = true }
                    else Modifier,
                ),
        ) {
            Column(
                // 收起时压缩纵向内边距：
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
                // 折叠头：点整行展开/收回。
                // ScSwitch 自身会消费点击、不冒泡到这里的 clickable，所以点开关只切开关。
                // 通用卡头：开关值 = 记忆 && 总闸、
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
                    // 去掉内层「面板 + 描边」，把浅灰背景直接铺满整张卡片
                    // （背景改由 ScCard 的 background 参数承担，仅展开时生效；收起仍是白卡）。
                    // 好处：不必再套一层圆角容器，VFD 窗与旋钮共用同一张底，边界更干净。
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                    // 【合并窗】电平柱与旋钮组共用**同一块深底窗**，中间不断开。
                    // 之前是两个独立 Box 各自带背景、中间留 14dp 白缝，读起来是「两块贴片」；
                    // 合并后是一整块仪表面板——左半显示、右半操作，中间只留 12dp 呼吸。
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
                    // **整组居中**：
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
                        // ── 右栏：两组旋钮，**纵向**排列──
                        // 组 1「静态曲线」阈值 / 压缩比 / 拐点 —— 决定压多少
                        // 组 2「时间补偿」Attack / Release / 增益 —— 决定怎么压
                        // 每组内部竖着排 3 个、两组左右并排：右栏 182dp / 2 列 → 每列 91dp，
                        // 旋钮可从 52dp 回到 72dp（三列时被压窄，盘面数值与标签都挤）。
                        // 右栏：两组旋钮（无自身背景，与左侧三条柱共用同一块合并窗）。
                        // 盘面用 faceDark 深灰蓝——嵌在面板里，而不是白瓷贴在深灰上。
                        // **间距必须用固定值而非 SpaceEvenly**：
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
                                    textScale = 0.60f,
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
                                    textScale = 0.60f,
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
                                    textScale = 0.60f,
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
                                    textScale = 0.60f,
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
                                    textScale = 0.60f,
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
                                    textScale = 0.60f,
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
                        // 侧链高通：档位而非连续旋钮 —— 常用值就这几个，
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

