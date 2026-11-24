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

// 共用 UI 组件：电平表 / 拟物开关 / 分组卡头 / 参数值格 / 详情页框架组件。
// 从 ScScreens.kt 拆出（纯移动）；private 提升为 internal，供各详情页文件复用。

/**
 * 多段压限的单段电平表组：IN / GR / OUT 三条竖条。
 * IN/OUT 为线性峰值（换算 dB = 20log10），GR 为该段当前增益衰减（负 dB，压得越深条越高）。
 */
@Composable
internal fun ScBandMeterGroup(
    inPk: Float,
    grDb: Float,
    outPk: Float,
    accent: Color,
    /**
     * 表体高度：三段动态用 130dp（三张卡并排、需要足够刻度感）；
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
 * VFD **竖向**电平条。
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
internal fun ScVfdMeterColumn(
    label: String,
    value: Float,
    isGr: Boolean,
    dim: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 格数 16 → 20。
     * 柱高从 200 拉到 250dp 与右栏（3 行旋钮 ≈ 285dp，减去标签与数值的 34dp）齐平；
     * 若维持 16 格，每格会胀到 15.6dp、显得粗笨，所以格数同步提到 20
     * （每格 10.5dp，与原来 200dp/16 格的密度一致，观感不变但整体更高）。
     */
    segmentCount: Int = 20,
    barHeight: Dp = 250.dp,
    /**
     * GR 峰值保持（dB，负值）：只对 isGr 生效，在柱上画一条细横线
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
        // 标签**常亮**：VFD 面板上的 IN/GR/OUT 是丝印，
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
                // **发光方向按表类型分开**：
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
                    // **三档配色保持原样**：
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
 * **带 dB 单位**：专业工具从不省略单位——原来裸写 `-29`，
 * 读者要自己判断这是 dB 还是线性值。
 */
internal fun meterText(value: Float, isGr: Boolean): String {
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
internal fun meterTextColor(value: Float, isGr: Boolean): Color = if (!isGr) {
    ScColors.TextSub
} else when {
    value > -6f -> Color(0xFF2E8B4F)
    value > -12f -> Color(0xFFC08A20)
    else -> Color(0xFFC0392B)
}

/**
 * 紧凑开关：42×24dp 视觉 + 42×32dp 触摸区。
 *
 * 为什么不用 Material3 的 ScSwitch：它的触摸区约 48dp 高，在「压缩器（单段）」
 * 收起后的标题行里占掉整卡高度的大半（93dp 卡里开关就占了 48dp）。自绘后视觉 24dp，
 * 触摸区 32dp——比 Material 标准的 48dp 小，但仍在可接受的点击范围内
 * （Apple HIG 建议 44pt、Material 48dp，这里取 32dp 是刻意为了紧凑做的取舍）。
 */
@Composable
internal fun ScTinySwitch(
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
internal fun ScParamValueCell(
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
internal fun ScBandMeterCanvas(
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
            // 直角而非圆角：18dp 宽的窄条上圆角几乎只剩圆边，
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

