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

// 设备健康 / 诊断 / 设置（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

// ---------------------------------------------------------------- P5 我的屏

@Composable
fun ScProfileScreen(viewModel: MainViewModel) {
    Column(
        // **不要在这里加 verticalScroll**：
        // 外层 Tab 容器（MainScreen）已经提供了滚动，再套一层会触发
        // `IllegalStateException: Vertically scrollable component was measured with an
        // infinity maximum height constraints` —— 同方向嵌套滚动在 Compose 里是硬约束。
        // 表现是进「我的」页即闪退（栈里能看到两层 ScrollNode）。
        // U8i：外层 Tab 容器已 padding(16)，此处不再加（原双 16 叠加导致卡比其他页窄 16dp）
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // U8y：页头下的小「我的」标签退役（页头已有大标题，重复）
        // U8z：设备卡+引擎健康卡迁音效库顶部（ScDeviceHealthCards），我的页自诊断卡始

        // 【顺序重排】原来「诊断 → Capture → 设置 → 隐私」：把开发者工具
        // 放在最上面，几张日志占掉大半页，用户改个设置得一直往下翻。
        // 现在按**使用频率**排：设置（最常用）→ 隐私 → Capture → 诊断（折叠）→ 说明。
        ScSettingsSection(viewModel)

        // **诊断与取证紧跟「诊断与工具」**：
        // 设置卡的最后一个分组就是「诊断与工具」（测试正弦），同属排障工具；
        // 原来中间隔着「隐私与合规」「Capture 策略」两张卡，两块诊断内容被拆散了。
        ScDiagSection(viewModel)

        PrivacyComplianceSection(viewModel)

        // **Capture 策略只在 Stellar + MP 下显示**：
        // 它列的是「全机 APP 的 MediaProjection 可抓性」（BY_SYSTEM / BY_NONE / BY_ALL），
        // 是走屏幕录制抓取时才需要判断的东西。Loopback 走 AudioMix 副本、按 mix 整体抓，
        // 不做 per-app 判定，这张表在那儿没有决策价值。
        val audioMode by viewModel.audioMode.collectAsStateWithLifecycle()
        if (audioMode == "stellar_mp") {
            ScCapturePolicySection(viewModel)
        }

        // ---- 内录机制说明 ----
        // 标题**放回卡内**：这是最后一张说明性质的卡，
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
                    // **加载中：橙色闪烁**。
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
                // 路由命中信息对普通用户无意义，不在界面显示；
                // 排查看 diag 日志（`重路由校验` / `takeover_diag`）。
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // U9h：IntrinsicSize.Max 让 Running pill 撑到与 Stellar 标签同宽；
                // widthIn 上限防窄屏把左侧信息挤成 1 字宽
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
                            // **闪烁期间显示 Loading**：
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
                // 特权胶囊：按当前生效后端显示
                //   已配对 → 绿，不可点
                //   待授权 → 黄，点击拉起对应客户端（Stellar Manager / Shevery）
                //   无后端 → 红呼吸，点击浏览器打开下载页（中性引导）
                val backend = privState.backend
                val backendReady = privState.permissionGranted
                val backendInstalled = backend != BackendKind.NONE
                // 按实际 manager 包名显示（Shevery fork / 官方 Shizuku 同协议）
                val backendLabel = when {
                    privState.managerPackage?.contains("shevery", ignoreCase = true) == true ->
                        "Shevery"
                    privState.managerPackage?.contains("moe.shizuku", ignoreCase = true) == true ->
                        "Shizuku"
                    backend == BackendKind.STELLAR -> "Stellar"
                    else -> "无后端"
                }
                ScPill(
                    text = when {
                        backendReady -> "$backendLabel 已配对"
                        backendInstalled -> "$backendLabel 待授权"
                        else -> "特权框架未就绪"
                    },
                    variant = when {
                        backendReady -> ScPillVariant.Good
                        backendInstalled -> ScPillVariant.Warn
                        else -> ScPillVariant.Danger
                    },
                    // U9i：按上方 Running pill 样式包裹（L1 阴影 + 受光高光描边）
                    elevated = true,
                    modifier = Modifier
                        // 仅「无后端」呼吸：持续可见但不刺眼；待授权用静态黄色即可
                        .alpha(if (backendInstalled) 1f else breatheAlpha)
                        .clickable(enabled = !backendReady) {
                            when (backend) {
                                BackendKind.STELLAR -> {
                                    // 先触发原生授权请求（server 会拉起 Manager 的
                                    // 确认页并写入权限表）；仅拉起 Manager 不会写权限——
                                    // 重装 Manager 清空配置后，UI 的「允许」状态并不可靠。
                                    viewModel.requestStellarPermission()
                                    runCatching { StellarHelper.openManager(context) }
                                }
                                BackendKind.SHIZUKU -> {
                                    // 优先拉起实际 manager；包名缺失时按 Shevery→官方 兜底
                                    val launch = runCatching {
                                        (
                                            privState.managerPackage
                                                ?.let { context.packageManager.getLaunchIntentForPackage(it) }
                                                ?: context.packageManager
                                                    .getLaunchIntentForPackage("com.hamondev.shevery")
                                                ?: context.packageManager
                                                    .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            )?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }.getOrNull()
                                    if (launch != null) {
                                        runCatching { context.startActivity(launch) }
                                    } else {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }
                                }
                                BackendKind.NONE -> runCatching {
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
    // **默认折叠**：这块是开发者工具，展开时有几十行日志
    // 加 8 个按钮，能占掉大半屏，把设置挤到需要翻好几屏的位置。
    // 但报障时要能一键导出，所以「导出」留在标题行，不随折叠隐藏。
    var expanded by remember { mutableStateOf(false) }
    // 统一用 ScCard：
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

                // ---- Loopback 探针：AudioPolicy/AudioMix(LOOP_BACK) 路线验证 ----
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
                // 诊断卡不提供「DP 静音实验 / 按UID电平」：
                // 结论已定（走 Loopback），且后者复用同一 UserService record、
                // 会 stop 主链路，误点代价高。
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

/** Capture 策略段：全机第三方 APP 投影可抓性一览。
 *  数据源 MainViewModel.scanCapturePolicies（Stellar 特权 dumpsys audio——公开 API
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
 * 设备绑定 UI 开关（M14，先隐藏）。
 *
 * 当前产品语义是「切换设备自动恢复该设备/该类型最近一次调整的参数」，绑定属于可选的
 * "固定某套"能力。先隐藏入口、完整保留实现（ViewModel 接口 + 存储 + 解析回落），
 * 若后续有用户反馈需要固定，把本开关改 true 即可恢复。
 */
private const val SHOW_DEVICE_BIND_UI = false

/**
 * Stellar 客户端下载页。
 * 仅在「本机未安装 Stellar」时被拉起：功能依赖该框架，故下载入口属于必需依赖引导，
 * 文案保持中性（「未安装」而非「推荐安装」），避免商店审核的诱导安装条款。
 */
private const val STELLAR_DOWNLOAD_URL =
    "https://gitee.com/su-su2239/Stellar/releases/#release-v1.1(101000)"

/** 双后端兜底：Shizuku 生态下载页（Shevery fork / 官方 Shizuku 同协议） */
private const val SHIZUKU_DOWNLOAD_URL =
    "https://github.com/RikkaApps/Shizuku/releases"

/**
 * 小型二选一 chip。
 * 与设置页「链路模式」的 chip 同款语言：选中为实心主题色 + 白字。
 */
@Composable
internal fun ScAdvChip(text: String, active: Boolean, onClick: () -> Unit) {
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
 * 卡外分组大标题。
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
internal fun ScSettingBlock(
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

    // ============ 界面缩放============
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
                // 副标题去掉：两个 chip 本身已经写明了模式名，
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

            // ---- 配置备份（切换 release/debug 会清数据，靠它免于重调）----
            val ctx = LocalContext.current
            var backupMsg by remember { mutableStateOf<String?>(null) }
            var needRestart by remember { mutableStateOf(false) }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    viewModel.importConfig(uri)
                        .onSuccess { n ->
                            backupMsg = "已导入 $n 项配置。"
                            needRestart = true
                        }
                        .onFailure { backupMsg = "导入失败：${it.message}" }
                }
            }

            ScSettingRow(
                title = "导出配置",
                subtitle = "全部参数写成 JSON，存到应用外部目录",
                showDivider = true,
                onClick = {
                    viewModel.exportConfig()
                        .onSuccess { backupMsg = "已导出：\n$it" }
                        .onFailure { backupMsg = "导出失败：${it.message}" }
                },
                trailing = { Text("›", fontSize = 18.sp, color = ScColors.TextSub) },
            )
            ScSettingRow(
                title = "导入配置",
                subtitle = "从 JSON 恢复全部参数（需重启应用）",
                showDivider = false,
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                trailing = { Text("›", fontSize = 18.sp, color = ScColors.TextSub) },
            )

            backupMsg?.let { msg ->
                AlertDialog(
                    onDismissRequest = { backupMsg = null },
                    title = { Text(if (needRestart) "配置已导入" else "配置备份") },
                    text = { Text(msg) },
                    confirmButton = {
                        TextButton(onClick = {
                            backupMsg = null
                            if (needRestart) {
                                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                                    ?.let { ctx.startActivity(it) }
                                Runtime.getRuntime().exit(0)
                            }
                        }) {
                            Text(if (needRestart) "立即重启" else "好")
                        }
                    },
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

