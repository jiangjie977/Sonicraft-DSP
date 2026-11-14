/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * U5：拟物旋钮公共组件（自 EqPanel.kt MiniKnob 抽出，定稿视觉原样保留）。
 * 凹面槽弧 + 白凸盘 + 青绿值弧 + LED 灯珠（BlurMaskFilter 高斯光晕，禁 radialGradient 马赫带）
 * + 盘面大数值；竖向拖动调值，label 点按可外接「点按输入」对话框。
 * `bipolar`：中点基准弧（U8d，平衡类旋钮）——值弧以 norm=0.5 为锚：右调从 12 点向右延伸，
 * 左调从 12 点向左延伸，居中无弧；false = 常规单向弧（从 min 端起画）。
 * `onDoubleTap`：双击盘面回调（可外接重置，如声道平衡回中，U8c；null = 不响应双击）。
 * `textScale`：盘面大数值字号缩放（U8e，默认 1 = 原视觉 discR×0.62；主页大旋钮中文长文本
 * 显得过大时传 ~0.7，不影响 EQ/P2 等其他调用点）。
 * `format`：盘面数值显示格式（null = 内置 log/signed 默认格式，如低切档位传自定义）。
 * `steps`：离散档位模式（U8s，>0 生效）——value=档位索引 0..steps-1，手势按步进跳档
 * （残留量累计，跨一档才回调，避免连续值被调用方量化回写形成对抗导致转不动）；
 * 绘制：槽弧/值弧/中心单 LED 退役，改 steps 个档位 LED 点环（当前档点亮青绿光晕，其余暗点）。
 */
@Composable
fun ScKnob(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    log: Boolean = false,
    signed: Boolean = true,
    bipolar: Boolean = false,
    sizeDp: Int = 52,
    steps: Int = 0,
    onLabelTap: (() -> Unit)? = null,
    format: ((Float) -> String)? = null,
    onDoubleTap: (() -> Unit)? = null,
    textScale: Float = 1f,
    /**
     * 过载阈值：value 超过它即进入「过载视觉」——外框转红并高斯模糊、
     * 值弧在过载区逐级加浓红、LED 转红。默认 [Float.MAX_VALUE] = 不启用。
     * 用于湿声电平与输入/输出增益这类「>1.0 就可能削波」的旋钮。
     */
    overloadFrom: Float = Float.MAX_VALUE,
    /**
     * 双击归位值。非 null 时双击盘面回到该值；null = 不响应双击。
     * 若同时传了 [onDoubleTap]，以 onDoubleTap 优先（供声道平衡这类「回中 + 额外动作」场景）。
     */
    resetValue: Float? = null,
    /**
     * 紧凑环模式。false = 原观感（22dp 粗弧 + 小盘面）；
     * true = **3.5dp 细弧 + 大得多的盘面**，用于三段动态这类并排多个小旋钮的场景：
     * 粗弧在 40dp 级别会占掉一半直径、盘面被挤得很小，细线后同样的外径下盘面明显变大，
     * 观感更轻、更容易看清数值。
     */
    compact: Boolean = false,
    /**
     * 标签字号（sp，2026-11-08）。默认 10sp。大旋钮（56dp 以上）时 10sp 会显得很小，
     * 调用方按需放大即可——不改成随 sizeDp 联动，因为主页大旋钮（100dp+）的标签
     * 如果跟着放大到 20sp 会完全失衡。
     */
    labelSize: Float = 10f,
    /**
     * 标签颜色（2026-11-09 新增）：默认跟随主题（浅底上的深灰）。
     * 深色底（如单段压缩器的「旋钮窗」）需要传浅色，否则标签在深底上几乎不可见。
     */
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /**
     * 深色盘面（2026-11-09 新增）。false = 默认的白瓷球面（浅色底卡上用）；
     * true = 深灰蓝盘面 + 浅色数值，用于**深色底板**（单段压缩器的旋钮窗）。
     *
     * 为什么需要它：白瓷盘面是为浅色底设计的（中心 FBFCFF 只比底亮一点点）。
     * 放进深灰窗里，白瓷会变成整块区域最亮的元素、抢过旁边的电平表，
     * 同时与窗底形成极端明度落差，看着像「贴上去的白色贴纸」而不是嵌在面板里。
     */
    faceDark: Boolean = false,
    onValueChange: (Float) -> Unit,
) {
    // Canvas 里的尺寸都是像素，compact 模式的弧宽/间隙要按 dp 写、经 density 换算
    val knobDensity = androidx.compose.ui.platform.LocalDensity.current
    val norm = if (log) {
        ((kotlin.math.ln(value.coerceIn(min, max).toDouble()) -
            kotlin.math.ln(min.toDouble())) /
            (kotlin.math.ln(max.toDouble()) - kotlin.math.ln(min.toDouble()))).coerceIn(0.0, 1.0)
    } else {
        ((value - min) / (max - min)).coerceIn(0f, 1f).toDouble()
    }
    // 过载标尺：overloadAmt = 超出阈值的程度（0 刚触线 → 1 顶到 max），
    // 只驱动「盘面光晕强度 + LED 红度」；值弧不参与（恒为品牌青绿）。
    val overloadAmt = if (overloadFrom < max) {
        ((value - overloadFrom) / (max - overloadFrom)).coerceIn(0f, 1f).toFloat()
    } else {
        0f
    }
    // 过载红：越靠右越浓（0.55 → 1.0 alpha），并在 0→1 之间从青绿过渡到红
    val hotColor = Color(0xFFE5453A)
    // 视觉映射加速：线性映射时 1.1（刚过线）只有 0.1，肉眼几乎看不出；
    // 取平方根后 0.1→0.32、0.3→0.55，刚过载就能得到明确提示。
    val overloadVis = kotlin.math.sqrt(overloadAmt)
    // 过载光晕呼吸：只在真正过载时创建动画（不过载的旋钮不占帧循环）。
    // 在 draw 阶段读 .value，因此只触发重绘、不触发重组。
    val glowBreath = if (overloadAmt > 0.01f) {
        rememberInfiniteTransition(label = "knobGlow").animateFloat(
            initialValue = 1f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "knobGlowBreath",
        )
    } else {
        null
    }
    // 在组合阶段读取：赋值给普通 Float，重绘由重组驱动（过载旋钮数量极少，代价可忽略）
    val glowPulse = glowBreath?.value ?: 1f
    val ledColor = if (overloadAmt > 0.01f) {
        androidx.compose.ui.graphics.lerp(Color(0xFF2AA0A0), hotColor, overloadVis)
    } else {
        Color(0xFF2AA0A0)
    }
    val currentValue by rememberUpdatedState(value)
    val doubleTapCallback by rememberUpdatedState(onDoubleTap)
    // pointerInput(Unit) 只创建一次，闭包会捕获首帧的值——所有外部回调/数据都要过
    // rememberUpdatedState，否则拖动到一半参数更新了，回调里读到的还是旧值。
    val changeValue by rememberUpdatedState(onValueChange)
    val resetTarget by rememberUpdatedState(resetValue)
    // 长按输入：与拖动、双击均不冲突——长按有独立的 500ms 阈值，
    // 不参与双击判定；拖动超出 touch slop 会先消费事件，长按自然失效。
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    // U8s：档位手势残留累计器——跨满一档跨度才回调，量化回写不再吃掉 delta
    val stepAcc = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .size(sizeDp.dp)
                .pointerInput(min, max, log, steps) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (steps > 0) {
                                stepAcc.value += (-dragAmount / 220f) * (steps - 1)
                                val whole = kotlin.math.floor(stepAcc.value)
                                if (whole != 0f) {
                                    stepAcc.value -= whole
                                    val next = (currentValue.toInt() + whole.toInt()).coerceIn(0, steps - 1)
                                    onValueChange(next.toFloat())
                                }
                            } else {
                                val span = if (log) {
                                    kotlin.math.ln(max.toDouble()) - kotlin.math.ln(min.toDouble())
                                } else {
                                    (max - min).toDouble()
                                }
                                // 灵敏度：220px 走完全范围，对 72dp 旋钮来说
                                // 拖过整个旋钮（约 180px）只走到 82%，微调手感发滞；compact 提到 160px
                                val sens = if (compact) 160.0 else 220.0
                                val delta = (-dragAmount / sens) * span
                                onValueChange(
                                    if (log) {
                                        kotlin.math.exp(
                                            kotlin.math.ln(
                                                currentValue.coerceIn(min, max).toDouble(),
                                            ) + delta,
                                        ).toFloat()
                                    } else {
                                        (currentValue + delta).toFloat()
                                    },
                                )
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // 独立 pointerInput 与拖动共存：tap 无位移不触发 drag。
                    //   双击 → 归位（优先外部 onDoubleTap，其次 resetValue）
                    //   长按 → 弹数字输入框（精调，避免为「输入一个精确值」去反复微拖）
                    // 两者与单击都不冲突，故没有「单击要等双击超时」的 300ms 延迟问题。
                    detectTapGestures(
                        onDoubleTap = {
                            if (doubleTapCallback != null) {
                                doubleTapCallback?.invoke()
                            } else {
                                resetTarget?.let { changeValue(it) }
                            }
                        },
                        onLongPress = {
                            // 离散档位（steps>0）不弹输入框：值域是档位索引 0..n-1，
                            // 让用户输「3」既不知道对应什么档位，也容易越界。
                            if (steps == 0) {
                                // 必须用 currentValue（rememberUpdatedState），**不能直接读 value**：
                                // pointerInput(Unit) 只在首次组合时创建，闭包捕获的是那一刻的
                                // value——实测弹框会显示应用启动时的旧值（干声 0.9 却读到 1.043）。
                                inputText = fmtKnobNum(currentValue)
                                showInput = true
                            }
                        },
                    )
                },
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val trackR = size.minDimension / 2f - 6f
            // compact：细弧 + 小间隙 → 同样外径下盘面显著变大（用户要求「按钮大一点，
            // 外围圆弧用细线代替」）。默认仍是原来的 22px 粗弧 + 24px 间隙。
            // **注意这里是像素不是 dp**：size.minDimension 本身就是 px，直接写 dp 数值
            // 在 2.5x 屏上只有 1.4dp、几乎看不见，必须经 density 换算。
            val ringW = if (compact) with(knobDensity) { 2.dp.toPx() } else 22f
            val gap = if (compact) with(knobDensity) { 5.dp.toPx() } else 24f
            val discR = trackR - gap
            // 档位模式：槽弧/值弧退役（U8s），改档位 LED 点环；连续模式照旧
            if (steps == 0) {
                // recessed groove (light concave: darker fill)
                drawArc(
                    Color(0xFFE0E4EA), -225f, 270f, false,
                    topLeft = Offset(cx - trackR, cy - trackR),
                    size = Size(trackR * 2, trackR * 2),
                    style = Stroke(ringW, cap = StrokeCap.Round),
                )
                // groove lower-edge highlight (concave lit rim, offset down)
                drawArc(
                    Color(0x80FFFFFF), -217f, 254f, false,
                    topLeft = Offset(cx - trackR + 3.5f, cy - trackR + 3.5f),
                    size = Size((trackR - 3.5f) * 2, (trackR - 3.5f) * 2),
                    style = Stroke(2.5f, cap = StrokeCap.Round),
                )
            }
            // cyan value arc over the groove
            if (steps == 0 && bipolar) {
                // 中点基准弧（U8d）：以 12 点钟（norm=0.5）为锚，向调谐方向延伸
                if (norm > 0.501) {
                    drawArc(
                        Color(0xFF2AA0A0), -90f, (270.0 * (norm - 0.5)).toFloat(), false,
                        topLeft = Offset(cx - trackR, cy - trackR),
                        size = Size(trackR * 2, trackR * 2),
                        style = Stroke(ringW, cap = StrokeCap.Round),
                    )
                } else if (norm < 0.499) {
                    drawArc(
                        Color(0xFF2AA0A0),
                        (-225.0 + 270.0 * norm).toFloat(),
                        (270.0 * (0.5 - norm)).toFloat(), false,
                        topLeft = Offset(cx - trackR, cy - trackR),
                        size = Size(trackR * 2, trackR * 2),
                        style = Stroke(ringW, cap = StrokeCap.Round),
                    )
                }
            } else if (steps == 0 && norm > 0.001) {
                // 值弧恒为品牌青绿（2026-11-08 定稿）：过载只由「盘面光晕 + LED」表达，
                // 弧条本身不变色——用户反馈整条弧变红过于喧宾夺主，且与弧的「数值指示」
                // 语义冲突（弧长已经表达了数值大小，颜色不必重复强调）。
                drawArc(
                    Color(0xFF2AA0A0), -225f, (270f * norm).toFloat(), false,
                    topLeft = Offset(cx - trackR, cy - trackR),
                    size = Size(trackR * 2, trackR * 2),
                    style = Stroke(ringW, cap = StrokeCap.Round),
                )
            }
            // 过载光晕（2026-11-08 三次修正：由「环」改「面」）。
            //
            // 前两版都不自然：STROKE + BlurMaskFilter 画出来是一圈**环状光带**——模糊只
            // 柔化了两端，环的内径与外径仍有明显的硬边衰减，看着像给盘面套了个发光橡皮圈。
            // 真实发光是「物体向外渐隐」，没有内圈边界。
            // 现在改成 FILL 实心圆 + 大半径模糊：中心被随后绘制的盘面整块遮住，露出来的
            // 只有盘面边缘向外自然扩散的一圈柔光，内外都没有硬边。
            if (overloadAmt > 0.01f) {
                // 呼吸系数：0.42 ~ 1.0 往返，驱动红光强度与弥散范围
                val breath = glowPulse
                drawIntoCanvas { cv ->
                    // 外层：半径大、模糊大、透明度低 —— 负责远处的弥散
                    val outer = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(
                            ((55 + 95 * overloadVis) * breath).toInt(), 229, 69, 58,
                        )
                        style = android.graphics.Paint.Style.FILL
                        maskFilter = android.graphics.BlurMaskFilter(
                            22f + 16f * overloadVis,
                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                        )
                        isAntiAlias = true
                    }
                    // 褪光时半径略收，亮时向外推 —— 呼吸不只是明暗，还有扩散感
                    cv.nativeCanvas.drawCircle(cx, cy, discR + 2f + 4f * (1f - breath), outer)
                    // 内层：贴住盘面边缘，透明度高一点 —— 让轮廓处有实感而不发灰
                    val inner = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(
                            ((45 + 75 * overloadVis) * breath).toInt(), 229, 69, 58,
                        )
                        style = android.graphics.Paint.Style.FILL
                        maskFilter = android.graphics.BlurMaskFilter(
                            9f + 6f * overloadVis,
                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                        )
                        isAntiAlias = true
                    }
                    cv.nativeCanvas.drawCircle(cx, cy, discR - 1f, inner)
                }
            }
            // 盘面投影（2026-11-08 增强）：原是 alpha 15% 的**硬边圆**，看起来像贴在
            // 旋钮下方的一块灰斑，不像阴影。改为**双层高斯模糊投影**：
            //   近层：偏移 2px、模糊 3px、alpha 20%  → 接触阴影，让旋钮从卡面上「托起」
            //   远层：偏移 5px、模糊 9px、alpha 12%  → 环境阴影，扩大浮起范围
            // 两层叠加后边界自然消散，没有硬边；也补足了三段浅色底卡上「盘面偏灰、
            // 需要一点体积感」的缺口。
            drawIntoCanvas { cv ->
                val near = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(52, 90, 107, 127)
                    maskFilter = android.graphics.BlurMaskFilter(
                        3f, android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                    isAntiAlias = true
                }
                cv.nativeCanvas.drawCircle(cx, cy + 2f, discR, near)
                val far = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(30, 90, 107, 127)
                    maskFilter = android.graphics.BlurMaskFilter(
                        9f, android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                    isAntiAlias = true
                }
                cv.nativeCanvas.drawCircle(cx, cy + 5f, discR * 1.02f, far)
            }
            // 盘面（2026-11-08 定稿）：**一整块微微凸起的球面**。
            //
            // 用径向渐变模拟球体受光——光源在左上偏外，高光落在左上，亮度沿半径连续
            // 衰减到底缘，这才是球体曲率的表达。
            // 之前是三层叠加（斜向金属条纹 + 底部内阴影弧 + 顶部高光弧），每层都在盘面
            // 边缘额外加一道环，观感更像「平面上贴了金属片」而不是球面，故一并撤掉。
            // 盘面配色（2026-11-08 定稿）：**中性偏冷的浅灰**，不是纯白。
            //
            // 纯白在浅色底卡（段色 7%，亮度约 94%）上「发飘」——盘面中心与背景几乎同亮，
            // 缺实体感；只有靠描边硬撑边界，看起来像贴纸而不是凸起的实体。
            // 现在中心 FBFCFF 只比背景亮一点点（球面高光，够亮但不刺）、边缘 CDD4DE
            // 明显沉下去（球体背光侧），明度差拉开了才有厚度。
            //
            // 选冷灰而非暖灰：值弧是青绿 2AA0A0（冷），盘面同冷调才协调；
            // 而三段底卡是米/蓝/紫混色，只有中性偏冷能同时兼容。
            // 深色盘面：明度整体压在 0x3A~0x7A 区间，比窗底 #46545F 略亮一档，
            // 靠高光与背光的明度差维持球面感（深盘面若与底同亮就变成一块平斑）。
            // 深色盘面（2026-11-09 用户要求「面板色再淡一点」）：
            // 整体提亮一档，但仍保持两端与窗底 (#46545F = 70,84,95) 的关系——
            // 高光明显亮于窗底（形成球面），边缘略暗于窗底（形成下沉边界）。
            // 边缘不能提太多：一旦接近窗底，盘面与窗就糊成一块、失去轮廓。
            val sphereStops = if (faceDark) arrayOf(
                0f to Color(0xFF8A9BA9),    // 高光（原 7A8B99，+16）
                0.38f to Color(0xFF6E7F8D), // 中间调（原 5F707E，+15）
                0.74f to Color(0xFF55656F), // 暗部（原 4A5A67，+11）
                1f to Color(0xFF3E4C58),    // 边缘（原 3A4854，+4，仍暗于窗底）
            ) else arrayOf(
                0f to Color(0xFFFBFCFF),   // 高光：略亮于背景，不刺眼
                0.38f to Color(0xFFEEF1F6), // 中间调
                0.74f to Color(0xFFDCE2EA), // 暗部
                1f to Color(0xFFCDD4DE),    // 边缘：明显下沉，形成实体边界
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = sphereStops,
                    // 圆心偏移 0.34→0.22：高光更靠近中心，曲面更平
                    center = Offset(cx - discR * 0.22f, cy - discR * 0.24f),
                    // 半径 1.45→1.85：盘面只取渐变最平缓的中段，边缘不会突然压暗
                    radius = discR * 1.85f,
                ),
                radius = discR,
                center = Offset(cx, cy),
            )

            // 淡描边：不论坐在什么底色上，旋钮边界都能立住
            drawCircle(
                color = Color(0x1F5A6B7F),
                radius = discR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f),
            )
            // LED 尺寸：compact 模式放大到 5%（原 3.5% 在 72dp 旋钮上只有 2.5dp，
            // 拖动时几乎看不见它在转）
            val ledR = size.width * if (compact) 0.05f else 0.035f
            if (steps > 0) {
                // U8s 档位 LED 点环：steps 个点均布 270° 弧（槽轨道半径上），当前档点亮
                val dotR = size.width * 0.05f
                val litIdx = currentValue.toInt().coerceIn(0, steps - 1)
                for (i in 0 until steps) {
                    val a = Math.toRadians(-225.0 + 270.0 * i / (steps - 1))
                    val px = cx + (trackR * kotlin.math.cos(a)).toFloat()
                    val py = cy + (trackR * kotlin.math.sin(a)).toFloat()
                    if (i == litIdx) {
                        drawIntoCanvas { cv ->
                            val gp = android.graphics.Paint().apply {
                                setColor(0xB32AA0A0.toInt())
                                maskFilter = android.graphics.BlurMaskFilter(dotR * 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                                isAntiAlias = true
                            }
                            cv.nativeCanvas.drawCircle(px, py, dotR * 1.3f, gp)
                        }
                        drawCircle(
                            Brush.radialGradient(
                                0f to Color(0xFF55CDCD), 0.7f to Color(0xFF2AA0A0), 1f to Color(0xFF249494),
                            ),
                            radius = dotR,
                            center = Offset(px, py),
                        )
                        drawCircle(
                            Color(0xD8FFFFFF), radius = dotR * 0.3f,
                            center = Offset(px - dotR * 0.3f, py - dotR * 0.3f),
                        )
                    } else {
                        drawCircle(Color(0xFFD4D9E0), radius = dotR * 0.72f, center = Offset(px, py))
                    }
                }
            } else {
                val angleRad = Math.toRadians(-225.0 + 270.0 * norm)
                val ledDist = discR * 0.66f
                val ledCx = cx + (ledDist * kotlin.math.cos(angleRad)).toFloat()
                val ledCy = cy + (ledDist * kotlin.math.sin(angleRad)).toFloat()
                // gaussian glow via BlurMaskFilter: a small solid circle blurred by
                // Skia = true gaussian falloff (pixel-exact, no stop sampling artifacts).
                drawIntoCanvas { cv ->
                    val gp = android.graphics.Paint().apply {
                        // 过载时灯珠光晕同色转红（ledColor 已按过载量插值）
                        setColor(ledColor.copy(alpha = 0.7f).toArgb())
                        maskFilter = android.graphics.BlurMaskFilter(ledR * 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)   // blur = LED 直径（wecho blurRadius:8 同比例）
                        isAntiAlias = true
                    }
                    cv.nativeCanvas.drawCircle(ledCx, ledCy, ledR * 1.2f, gp)
                }
                // LED body: 正常青绿珠，过载时随 overloadAmt 过渡为红珠
                drawCircle(
                    Brush.radialGradient(
                        0f to androidx.compose.ui.graphics.lerp(Color.White, ledColor, 0.72f),
                        0.7f to ledColor,
                        1f to androidx.compose.ui.graphics.lerp(ledColor, Color(0xFF1A1A1A), 0.15f),
                    ),
                    radius = ledR,
                    center = Offset(ledCx, ledCy),
                )
                // specular highlight
                drawCircle(
                    Color(0xD8FFFFFF), radius = ledR * 0.32f,
                    center = Offset(ledCx - ledR * 0.3f, ledCy - ledR * 0.3f),
                )
            }
            // large dark center value
            drawIntoCanvas { cv ->
                val pt = android.graphics.Paint().apply {
                    // 深盘面用浅字（0xFF2A3038 在深灰上几乎不可见）
                    setColor((if (faceDark) 0xFFE8EFF5 else 0xFF2A3038).toInt())
                    textSize = (discR) * 0.62f * textScale
                    isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                }
                val shown = format?.invoke(value) ?: if (log) {
                    if (value >= 1000f) "%.1fk".format(value / 1000f) else "%.0f".format(value)
                } else {
                    if (signed) "%+.1f".format(value) else "%.1f".format(value)
                }
                cv.nativeCanvas.drawText(shown, cx, cy + (discR) * 0.24f, pt)
            }
        }
        // label doubles as tap-to-type input
        Text(
            text = label,
            fontSize = labelSize.sp,
            color = labelColor,
            modifier = if (onLabelTap != null) {
                Modifier.clickable { onLabelTap!!() }
            } else {
                Modifier
            },
        )
        // 长按输入框：Dialog 独立浮层，不参与 Column 布局。
        // 输入越界自动钳到 [min, max]，非法文本直接忽略（不改变原值）。
        if (showInput) {
            AlertDialog(
                onDismissRequest = { showInput = false },
                title = { Text(label, fontSize = 15.sp) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            singleLine = true,
                            // Decimal 而非 Number：负号与小数点都能输入（EQ 增益、平衡是负值域）
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text("${fmtKnobNum(min)} ~ ${fmtKnobNum(max)}", fontSize = 11.sp) },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        inputText.trim().toFloatOrNull()?.let { changeValue(it.coerceIn(min, max)) }
                        showInput = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showInput = false }) { Text("取消") }
                },
            )
        }
    }
}

/** 旋钮数值的紧凑文本形式（整数不带小数点，其余最多 3 位小数、去尾零）。 */
private fun fmtKnobNum(v: Float): String {
    if (v == v.toInt().toFloat()) return "%.0f".format(v)
    return "%.3f".format(v).trimEnd('0').trimEnd('.')
}
