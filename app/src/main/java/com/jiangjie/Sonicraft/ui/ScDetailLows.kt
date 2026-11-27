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

// 低频管家 + 算法混响(FDN) 详情页：动态低音/低音增强/虚拟低音/低频组（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

/** U7 算法混响（FDN）详情内容：六参数滑块（原主页 FDN 区迁入） */
@Composable
internal fun ScFdnDetailContent(viewModel: MainViewModel) {
    val fdnRoomSize by viewModel.fdnRoomSize.collectAsStateWithLifecycle()
    val fdnHumidity by viewModel.fdnHumidity.collectAsStateWithLifecycle()
    val fdnStereoWidth by viewModel.fdnStereoWidth.collectAsStateWithLifecycle()
    val fdnWet by viewModel.fdnWet.collectAsStateWithLifecycle()
    val fdnDry by viewModel.fdnDry.collectAsStateWithLifecycle()
    val fdnMode by viewModel.fdnMode.collectAsStateWithLifecycle()
    // **不再套 ScCard**：
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
            ScInfoChip(body = "反馈延迟网络（FDN）：每声道 8 条延迟线 + 4 级全通扩散（轻量档 4+2），" +
                    "配分数延迟插值、高频阻尼、DC 阻断，并以 LFO 微调延迟长度抑制金属声。", title = "低频管家")
    }
}

/**
 * 低音保护详情内容。
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
            // 只保留「低音频率」与「保护」两个旋钮（用户反馈
            // 「开与不开都没什么效果」后精简）。
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

        ScInfoChip(body = "本效果器不做增强，只做两件事：① 次低频清理 —— 切掉耳机/喇叭放不出的" +
                "极低频，把单元行程留给听得到的频段；② 峰值保护 —— 分频点以下的低频超过阈值" +
                "时压住，防止小尺寸单元机械过载。要「听得出低音变多」请用低音增强。", title = "低频管家")
    }
}

/**
 * 低音增强详情内容。移植自上游项目的「低频质感」/ `BassEffect`。
 * 作为「低频管家」组里的**槽卡内容**渲染，自身不包 ScCard。
 *
 * 三个旋钮对应 早期定制版 的三个参数（gain → amount 归一化到 0~1）：
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
            ScInfoChip(body = "经典模式：把「频率」以下的低频单独滤出来，按「增益」叠加回原信号。" +
                    "做法朴素直接，低频厚实、略带糊感。Q 越高，截止点附近越「鼓」。" +
                    "这三个参数与动态模式完全独立，切换不会互相覆盖。", title = "低频管家")
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
            ScInfoChip(body = "动态模式：低频 EQ（60Hz 搁架 + 45Hz 钟形）叠加动态补偿 —— 音量越小" +
                    "补得越多、越大几乎不动。下潜额外生成低八度次谐波，冲击放大瞬态沿" +
                    "让鼓点更「实」。只叠加增量、不替换原信号，中高频基本不动。", title = "低频管家")
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


        ScInfoChip(body = "把外放放不出的 80~150Hz 翻译成能放出的 160~300Hz 谐波，让大脑" +
                "「补」出低频。强度是干湿比：0 = 纯原声、100% = 只留谐波。" +
                "中/高频直通量控制原声中段与高段保留多少。", title = "低频管家")
    }
}

/**
 * 低频管家详情：动态低音 + 低音增强 + 虚拟低音。
 *
 * 前两个**与输出设备无关**（分频重构 / M/S+EQ+瞬态），第三个**只适合外放**。
 *
 * **三者可以同时开启**：叠加听感交给用户判断。
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
                // 标题改名「动态低音」→「低音保护」：
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

        // ---- 槽 2：低音增强（设备无关，移植自上游项目的「低频质感」）----
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
                            // 耳机下**每次**开启都弹提示：
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

    // ---- 耳机下的风险提示----
    if (showHeadphoneWarning) {
        AlertDialog(
            onDismissRequest = { showHeadphoneWarning = false },   // 点外部 = 取消，不开启
            title = { Text("虚拟低音更适合外放", fontSize = 16.sp) },
            text = {
                ScInfoChip(body = "该算法通过心理声学模拟增强低频效果，更适合小型扬声器外放。" +
                        "耳机使用时易出现低频浑浊、失真，耳机模式请尽量关闭此功能。", title = "低频管家")
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
                        // 「关闭提醒」= **不开启**（对齐
                        // `早期定制版工程` 的语义）。
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

