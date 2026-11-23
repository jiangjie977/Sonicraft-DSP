/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 部分交互与布局参照本人维护的 wecho 定制版（studio_rack 分支）。
 */

package com.jiangjie.Sonicraft.ui

import android.Manifest

import com.jiangjie.Sonicraft.EqMath
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.jiangjie.Sonicraft.ForegroundDspService
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.TextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiangjie.Sonicraft.MainViewModel
import com.jiangjie.Sonicraft.core.SysInfo
import com.jiangjie.Sonicraft.core.engine.AudioEngine
import com.jiangjie.Sonicraft.core.engine.EngineState
import com.jiangjie.Sonicraft.ui.components.ScAvatar
import com.jiangjie.Sonicraft.ui.components.ScCard
import com.jiangjie.Sonicraft.ui.components.ScCardHeader
import com.jiangjie.Sonicraft.ui.ScDeviceHealthCards
import com.jiangjie.Sonicraft.ui.components.ScKnob
import com.jiangjie.Sonicraft.ui.components.ScMeter
import com.jiangjie.Sonicraft.ui.components.ScTabBar
import com.jiangjie.Sonicraft.ui.components.ScEffectCard
import com.jiangjie.Sonicraft.ui.components.ScEffectIconKind
import com.jiangjie.Sonicraft.ui.components.ScInset
import com.jiangjie.Sonicraft.ui.components.ScPill
import com.jiangjie.Sonicraft.ui.components.ScPillVariant
import com.jiangjie.Sonicraft.ui.theme.ScCardBrush
import com.jiangjie.Sonicraft.ui.theme.ScColors
import com.jiangjie.Sonicraft.ui.theme.ScElevation
import com.jiangjie.Sonicraft.ui.theme.ScPrimaryBrush
import com.jiangjie.Sonicraft.ui.theme.ScRadius
import kotlinx.coroutines.delay

/**
 * 界面缩放层。
 *
 * **为什么不用改 LocalDensity**：改 density 会让 dp 与 sp 一起变，但系统给的
 * `screenWidthDp` 也跟着变 → 算 scale 时读到被自己影响过的值（循环依赖）；
 * 而且 MaterialTheme 的 typography 已经用 sp 定义，双重缩放会失控。
 *
 * wecho 的做法是给子树一块**更大的逻辑画布**（宽 = 实际宽 / scale），
 * 再用 FittedBox 缩回屏幕。Compose 的等价物：
 *   `requiredSize(逻辑尺寸) + graphicsLayer(scale, origin=TopStart)`
 * 子树所有 dp/sp 按比例放大，布局计算仍在「逻辑像素」里完成，不会被二次缩放。
 *
 * 触摸坐标由 graphicsLayer 的变换矩阵自动换算，无需手动映射。
 */
@Composable
private fun ScUiScaleLayer(totalScale: Float, content: @Composable () -> Unit) {
    if (kotlin.math.abs(totalScale - 1f) < 0.001f) {
        content()
        return
    }
    val base = LocalDensity.current
    // **只乘 density，不动 fontScale**：
    //   dp.toPx = dp * density              → 乘一次 ✓
    //   sp.toPx = sp * fontScale * density  → 也乘一次 ✓
    // 若两个都乘，sp 会变成 totalScale²，字号会过度缩放。
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = base.density * totalScale,
            fontScale = base.fontScale,
        ),
    ) {
        content()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
        viewModel.refreshBatteryState()
    }

    // 通知运行时权限（Android 13+，targetSdk 35 下必须请求）：未授予时所有通知
    //（含 FGS 常驻与夜间保活警示条）均不显示——警示通道的前提
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    var notifPermissionRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (notifPermissionRequested) return@LaunchedEffect
        notifPermissionRequested = true
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 1) 系统 MediaProjection consent 授权
    // Android 14+ 硬性要求：调用 getMediaProjection 时，必须已经有一个 mediaProjection 类型的前台服务在运行，
    // 因此必须在发起授权之前就 startForegroundService。
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projection = mpm.getMediaProjection(result.resultCode, result.data!!)
                ?: run {
                    context.stopService(Intent(context, ForegroundDspService::class.java))
                    return@rememberLauncherForActivityResult
                }
            viewModel.startCapture(projection)
        } else {
            // 用户取消授权，停掉已启动的前台服务
            context.stopService(Intent(context, ForegroundDspService::class.java))
        }
    }

    // 打开 app 自动启用内录接管：先试 Stellar 免弹窗（Android 15 上基本恒失败），
    // 失败则自动发起系统 consent（本机体验无感），全流程仅尝试一次
    var autoStartAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(engineState) {
        // 不再要求 engineState 必须是 Idle —— 服务被杀后引擎状态可能
        // 残留为 Running，那样自动启动永远不会触发（App 启动后一直没音效）。
        // 是否需要真正建链交给 autoStartCaptureIfNeeded 内部判断。
        if (!autoStartAttempted) {
            autoStartAttempted = true
            // 原为 1500ms「避开冷启动首帧」；实测 400ms 已足够（Compose 首帧已完成，
            // 再等只是白白推迟出声）。启动耗时优化：1.5s → 0.4s。
            delay(400)
            if (!viewModel.autoStartCaptureIfNeeded()) {
                // Android 14+ 硬性要求：getMediaProjection 时必须已有 mediaProjection 类型
                // FGS 在跑（与 launchProjection 同款前置）。此前漏了这步，主包被 Stellar
                // 免弹窗路径掩盖；验证包 Stellar 未授权走弹窗路径时崩（SecurityException）。
                context.startForegroundService(Intent(context, ForegroundDspService::class.java))
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    // 发起 MediaProjection 授权前，先启动 mediaProjection 前台服务
    fun launchProjection() {
        context.startForegroundService(Intent(context, ForegroundDspService::class.java))
        // 先尝试 Stellar 免弹窗投影，成功则不发起系统弹窗
        if (!viewModel.tryStartCaptureSilently()) {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    /**
     * 统一启动入口：按 audio_mode 分派。
     * 电源键 / 设备卡 / 重新授权 / 自动启动 都必须走这里，否则会出现
     * 「默认 loopback 但点电源键却拉 MP」的分裂行为。
     */
    fun startAudioByMode() {
        if (viewModel.isLoopbackMode()) viewModel.startLoopbackCapture() else launchProjection()
    }

    // D4：把「发起授权」注册给 ViewModel。Loopback 回退时由它触发
    // （本机免弹窗投影被框架剥离，只能走系统 consent，且必须由 Activity 发起）
    DisposableEffect(Unit) {
        viewModel.projectionRequester = { launchProjection() }
        onDispose { viewModel.projectionRequester = null }
    }

    // 2) RECORD_AUDIO 运行时权限 → 通过后再发起 MediaProjection 弹窗
    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startAudioByMode()
        }
    }

    // ---- U8k 路由（U5 基础上搜索 Tab 退役）：0=音效库(P1) / 1=收藏(P4) / 2=我的(P5)；效果详情页(P2 同层)覆盖 Tab 层；脉冲库迁入 P2 卷积详情页 ----
    var page by remember { mutableStateOf(0) }
    // U7 效果卡详情页化：点卡进详情设参数（同卷积 P2 形态），主页只留卡+开关
    var eqOpen by remember { mutableStateOf(false) }
    var bandOpen by remember { mutableStateOf(false) }
    var widthOpen by remember { mutableStateOf(false) }
    var vsOpen by remember { mutableStateOf(false) }
    // 混响组详情：卷积混响与算法混响合并到同一页，共用一个开合状态
    var reverbOpen by remember { mutableStateOf(false) }
    var limiterOpen by remember { mutableStateOf(false) }
    // 动态低音：替代原来的「低频增强（规划中）」占位卡
    var dynBassOpen by remember { mutableStateOf(false) }
    // 机架：脚本机架从设置页迁入音效库，独立详情页
    var rackOpen by remember { mutableStateOf(false) }
    // 脚本编辑器整页
    var scriptEditorOpen by remember { mutableStateOf(false) }
    // 打开编辑器时是否从空白开始（用户手写新脚本）
    var scriptEditorBlank by remember { mutableStateOf(false) }

    // U8k：详情层接系统返回键（此前 BACK 直接 finish Activity 把整个 App 退出）
    BackHandler(
        enabled = eqOpen || bandOpen || widthOpen || reverbOpen || limiterOpen || dynBassOpen ||
            rackOpen || scriptEditorOpen,
    ) {
        eqOpen = false
        bandOpen = false
        widthOpen = false
        reverbOpen = false
        limiterOpen = false
        dynBassOpen = false
        rackOpen = false
        scriptEditorOpen = false
    }

    if (scriptEditorOpen) {
        ScScriptEditorPage(viewModel, startBlank = scriptEditorBlank) {
            scriptEditorOpen = false
        }
    } else if (eqOpen || bandOpen || widthOpen || reverbOpen || limiterOpen || dynBassOpen ||
        rackOpen || vsOpen
    ) {
        // ---- U7 效果详情页（参量均衡/多频段压限/立体声宽度/算法混响）：同 P2 形态，覆盖 Tab 层 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
            // multibandEnabled 的 collect 已移除：压限器页头不再有开关，
            // 总开关下沉到「多段压限」卡片内、由 ScBandDetailContent 自己 collect。
            // 主页卡片那份仍保留（L666 附近），那边要靠它显示开关状态。
            val stereoWidthEnabled by viewModel.stereoWidthEnabled.collectAsStateWithLifecycle()
    // 低频管家的两张槽卡各自 collect 自己的开关（见 ScLowfreqGroupDetail），
    // 路由层不再需要 dynamicBassEnabled。
    // 混响组总闸：音效库「混响」卡片的开关
    val reverbSectionEnabled by viewModel.reverbSectionEnabled.collectAsStateWithLifecycle()
            val limiterEnabled by viewModel.limiterEnabled.collectAsStateWithLifecycle()
            // 机架总闸：按草图放到详情页页头
            val scriptEnabled by viewModel.scriptEffectEnabled.collectAsStateWithLifecycle()
    // 空间环绕总闸：音效库「空间环绕」卡片的开关
    val vsEnabled by viewModel.vsEnabled.collectAsStateWithLifecycle()
            when {
                eqOpen -> ScEffectDetailPage(
                    // 总开关**下沉到均衡器卡片内**（同压限器处理：卡片标题行右侧），
                    // 所以这里不传 enabled / onEnabledChange —— 页头不再渲染开关。
                    // 副标题传空：页头只留「均衡器」，避免小字顶在两行之间显乱。
                    title = "均衡器",
                    subtitle = "",
                    onBack = { eqOpen = false },
                ) { ScEqDetailContent(viewModel) }
                bandOpen -> ScEffectDetailPage(
                    // 用户要求：页头标题「多频段压限」→「压限器」；
                    // 页头开关**下沉到「多段压限」卡片内**（与单段压缩器的开关同一层级，
                    // 两个开关并列在各自的卡片上，语义更直白）。
                    // 因此这里不再传 enabled / onEnabledChange —— 页头不渲染开关。
                    title = "压限器",
                    subtitle = "多段压限 + 单段压缩器（互斥联动）",
                    onBack = { bandOpen = false },
                ) { ScBandDetailContent(viewModel) }
                widthOpen -> ScEffectDetailPage(
                    title = "立体声宽度",
                    subtitle = "M/S 矩阵 · 0 单声道 / 1 原样 / 2 加倍",
                    enabled = stereoWidthEnabled,
                    onEnabledChange = viewModel::setStereoWidthEnabled,
                    onBack = { widthOpen = false },
                ) { ScWidthDetailContent(viewModel) }
                // 空间环绕：总开关下沉到卡片内（与均衡器一致），页头只留标题
                vsOpen -> ScEffectDetailPage(
                    title = "空间环绕",
                    subtitle = "",
                    onBack = { vsOpen = false },
                ) { ScVsDetailContent(viewModel) }
                limiterOpen -> ScEffectDetailPage(
                    title = "限幅器",
                    subtitle = "末级防削波安全网 · 输出上限钳位",
                    enabled = limiterEnabled,
                    onEnabledChange = viewModel::setLimiterEnabled,
                    onBack = { limiterOpen = false },
                ) { ScLimiterDetailContent(viewModel) }
                // 低频管家：原「动态低音」页升级为**效果组**，
                // 内含 动态低音（耳机）+ 虚拟低音（外放）两张槽卡，互斥。
                // 页头**不再有总开关** —— 总闸在音效库卡片上，与压限器/混响一致。
                dynBassOpen -> ScEffectDetailPage(
                    title = "低频管家",
                    subtitle = "三种低频方案 · 可叠加",
                    onBack = { dynBassOpen = false },
                ) { ScLowfreqGroupDetail(viewModel) }
                // 机架：脚本机架从设置页迁来这里，页头不放开关
                // （总闸在音效库「机架」卡片上，与压限器/混响一致）
                rackOpen -> ScEffectDetailPage(
                    title = "机架",
                    subtitle = "脚本槽位 ×3 · SCVM（类 C / EEL 子集）",
                    enabled = scriptEnabled,
                    onEnabledChange = viewModel::setScriptEffectEnabled,
                    onBack = { rackOpen = false },
                ) {
                    ScRackBody(
                        viewModel = viewModel,
                        onOpenEditor = {
                            scriptEditorBlank = false
                            scriptEditorOpen = true
                        },
                        onOpenBlankEditor = {
                            scriptEditorBlank = true
                            scriptEditorOpen = true
                        },
                    )
                }
                // 混响组：卷积 + 算法两张槽卡在同一页，
                // 页头不放开关（与压限器一致）—— 总闸在音效库的「混响」卡片上
                else -> ScEffectDetailPage(
                    title = "混响",
                    subtitle = "卷积混响 + 算法混响（可叠加）",
                    onBack = { reverbOpen = false },
                ) { ScReverbGroupDetail(viewModel) }
            }
        }
    } else {
    // U6 修正：U5 曾把 ScTabBar 塞进 weight Box 内部——Box 子项层叠导致 Tab 条叠在左上角。
    // 现按设计稿改为 Box 单层：内容滚动列 + Tab 浮层 align 底部（避开手势条）。
    Box(
        modifier = Modifier
            .fillMaxSize()
            // `statusBarsPadding` **必须留在这一层**：
            // 移到缩放层内部的 `BoxWithConstraints` 上会完全失效（页头直接压到状态栏上）。
            // 留在这里的副作用是「缩放 <100% 时页头离状态栏更近」——因为留白按未缩放 px
            // 计算、内容被缩小后整体上移。这是缩小的正常结果，不是重叠。
            .statusBarsPadding()  // 星云贴合：页头不顶状态栏（原版遗留缺陷，U1 验收发现）
    ) {
        // 宽屏（>560dp，平板/折叠屏）内容居中限宽，避免整行被拉得过长；
        // 手机宽度 <=560dp 时 contentWidth == maxWidth，与改版前完全一致。
    // 界面缩放：autoScale(按 440dp 基准自动算)
    // × 粗调 × 微调，最终夹在 0.68~1.69，与 wecho 的 UIScaleManager 同口径。
    val uiScale by viewModel.uiScale.collectAsStateWithLifecycle()
    val uiFineScale by viewModel.uiFineScale.collectAsStateWithLifecycle()
    // **用短边而不是屏宽**：wecho 原公式按屏宽算，
    // 那是竖屏假设。横屏时屏宽 973dp ÷ 440 = 2.2 → 被 clamp 到上限 1.3 →
    // 整个 UI 放大 130%，字号与控件全部撑爆（真机横屏实测）。
    // 改用「短边」：竖屏短边=宽（440 → 100%），横屏短边=高（440 → 同样 100%），
    // 旋转屏幕时缩放不再跳变。
    val cfg = LocalConfiguration.current
    val minSideDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat()
    val autoScale = (minSideDp / 440f).coerceIn(0.85f, 1.3f)
    val totalScale = (autoScale * uiScale * uiFineScale).coerceIn(0.68f, 1.69f)
    ScUiScaleLayer(totalScale = totalScale) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 横屏修复：原固定 560dp 上限只考虑了平板/折叠屏，没考虑**手机横屏**——
            // 本机横屏 973dp 宽被压到 57%，两侧各留 200dp+ 空白（真机截图实证）。
            // 横屏放宽到 840dp：既不让内容被压窄，也不至于在大屏上无限拉伸。
            val contentWidth = minOf(maxWidth, if (maxWidth > maxHeight) 840.dp else 560.dp)
            Column(
                modifier = Modifier
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())  // 内容超屏（EQ 区在底部），必须可滚动
                    .padding(16.dp)
                    .padding(bottom = 92.dp),  // Tab 浮层高度预留：滚动末项不被遮挡
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

            // ---- U1 样板换肤：页头（设计稿 phead：标题 + 引擎状态星云徽标 + 头像）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = when (page) {
                            0 -> "音效库"
                            1 -> "预设与收藏"
                            else -> "我的"
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScColors.TextMain,
                    )
                    Text(
                        // 副标题与实际内容对齐：
                        // "我的"页原写「设备 · 引擎健康 · 设置」——但那两张卡早已迁到音效库顶部，
                        // 页面里实际是设置/隐私/Capture/诊断；首页那个用了半角句点，与其它两页
                        // 的「·」分隔符不一致，一并统一。
                        text = when (page) {
                            0 -> "深度适配 · 定制 DSP 引擎"
                            1 -> "场景组合 · EQ 预设 · 收藏脉冲"
                            else -> "设置 · 隐私 · 诊断"
                        },
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                    )
                }
                // U8x：Z 徽章退役；仅「我的」页在此位显示「星云 AIOS 深度适配」胶囊（自 ScCardHeader 迁入）
                if (page == 2) {
                    ScPill(text = "${SysInfo.romName} 深度适配", variant = ScPillVariant.Teal)
                }
            }

            // ---- 投影失效警示：capture read 恒负 → 引擎自动
            //      止损停机 → TakeoverController 回滚接管挂警示 → 此处呈现 + 一键重新授权。
            //      此前 captureBrokenWarning 无 UI 消费，静音/直出全靠用户耳朵发现。----
            val captureBroken by viewModel.captureBrokenWarning.collectAsStateWithLifecycle()
            if (captureBroken != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ScColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = captureBroken ?: "",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = ScColors.Danger,
                        )
                        TextButton(onClick = {
                            viewModel.clearCaptureBrokenWarning()
                            startAudioByMode()
                        }) {
                            Text("重新授权", fontSize = 13.sp, color = ScColors.Primary)
                        }
                    }
                }
            }

            // ---- 自身播放权限自愈警示：ZTE 后台音频治理把
            //      本 app 置 PLAY_AUDIO=deny → 输出轨 -inf → 全链死寂。自愈探针已自动
            //      恢复，此处告知 + 引导电池白名单。----
            val selfAudioWarn by viewModel.selfAudioWarning.collectAsStateWithLifecycle()
            if (selfAudioWarn != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ScColors.Warn.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selfAudioWarn ?: "",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = ScColors.Warn,
                        )
                        TextButton(onClick = { viewModel.clearSelfAudioWarning() }) {
                            Text("知道了", fontSize = 13.sp, color = ScColors.Primary)
                        }
                    }
                }
            }

            // ---- 输出流停摆警示：看门狗重建失败此前
            //      完全静默（22 分钟零日志零感知）。非 null = 重建未成功，自动重试中。----
            val outputStalledMsg by viewModel.outputStalled.collectAsStateWithLifecycle()
            if (outputStalledMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ScColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                ) {
                    Text(
                        text = outputStalledMsg ?: "",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = ScColors.Danger,
                    )
                }
            }

            // ---- 夜间保活：被系统终止警示横幅已撤除（U9p7：NX733J 专属病灶文案对其他机型
            //      用户有歧义，指示去掉）。系统终止后即普通 Idle 态，用户点电源键
            //      重新开启即可；systemKilledUnacked 标记与服务端检测逻辑保留不动。----

            // ---- Loopback 不可用 → 告知原因并可切换平替模式----
            // 不做运行期热回退（会打断播放）；只在首次探测失败时弹一次，由用户确认
            val lbUnsupported by viewModel.loopbackUnsupported.collectAsStateWithLifecycle()
            lbUnsupported?.let { msg ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLoopbackUnsupported() },
                    title = { Text("Loopback 模式不可用") },
                    text = { Text(msg, style = MaterialTheme.typography.bodySmall) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmUseMpMode() }) {
                            Text("切换到 Stellar+MP")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLoopbackUnsupported() }) {
                            Text("暂不切换")
                        }
                    },
                )
            }

            // ---- 夜间保活：电池优化豁免引导（U9p7：常驻卡片改弹窗——未豁免时首启弹一次，
            //      「稍后再说」本会话不再弹；从系统设置回来 ON_RESUME 自动重检，已豁免即消）----
            val batteryIgnored by viewModel.batteryIgnored.collectAsStateWithLifecycle()
            var batteryDlgDismissed by rememberSaveable { mutableStateOf(false) }
            if (!batteryIgnored && !batteryDlgDismissed) {
                AlertDialog(
                    onDismissRequest = { batteryDlgDismissed = true },
                    title = { Text("电池优化未豁免") },
                    text = {
                        Text(
                            text = "未豁免时厂商省电策略可能在夜间冻结/终止本应用。" +
                                "建议一键豁免，并在系统「电池 → 后台耗电管理」中允许本应用后台运行、" +
                                "在最近任务中锁定后台卡片。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            batteryDlgDismissed = true
                            viewModel.requestIgnoreBatteryOptimization()
                        }) { Text("一键豁免") }
                    },
                    dismissButton = {
                        TextButton(onClick = { batteryDlgDismissed = true }) { Text("稍后再说") }
                    },
                )
            }
            // 从系统设置回来时重检（豁免后对话框消失的依据；原「我已设置，重新检查」按钮的自动化替代）
            DisposableEffect(Unit) {
                val obs = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshBatteryState()
                        // 从 Stellar 授权页切回来时 binder 未重连，
                        // 不主动重检的话「Stellar 待授权」胶囊会一直不变
                        viewModel.refreshPrivilege()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }

            // ---- U4 页面分发：主页(P1)保留启停/引擎/音效区；特权/诊断/设置/说明迁 P5 我的（ScProfileScreen） ----
            when (page) {
                0 -> {
                    // U9a：设备卡（Running pill+电源启停+引擎副文本迁入）+引擎健康卡；引擎运行中卡退役
                    ScDeviceHealthCards(viewModel = viewModel, onToggleCapture = {
                        if (viewModel.isRunning) {
                            viewModel.stopCapture()
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startAudioByMode()
                        } else {
                            recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    })

                    // U9p7：采集断链警示横幅撤除（用户指示「不显示」——蓝牙归因
                    // 已被实测证伪，真因未明前不出猜测性文案）。保险丝回滚逻辑保留在 ViewModel，
                    // 静默回退直出，恢复后静默回归接管。
                    if (engineState is EngineState.Failed) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "⚠ 启动失败：常见为 RECORD_AUDIO / Stellar PROJECT_MEDIA / MediaProjection 未授予，失败原因见日志",
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    P1LibrarySection(
                        viewModel = viewModel,
                        onOpenReverb = { reverbOpen = true },
                        onOpenEq = { eqOpen = true },
                        onOpenBand = { bandOpen = true },
                        onOpenWidth = { widthOpen = true },
                        onOpenLimiter = { limiterOpen = true },
                        onOpenDynBass = { dynBassOpen = true },
        onOpenRack = { rackOpen = true },
        onOpenVs = { vsOpen = true },
                    )
                }
                1 -> ScFavoritesScreen(viewModel = viewModel, onOpenEq = { eqOpen = true })
                2 -> ScProfileScreen(viewModel = viewModel)
            }

            // U4：特权框架卡/实时仪表卡/诊断卡/内录机制说明卡已迁 P5 我的屏（ScProfileScreen/ScDiagSection/ScSettingsSection）
            }   // Column（内容）
        }       // BoxWithConstraints（宽屏限宽）
    }   // 结束 ScUiScaleLayer（界面缩放）
        ScTabBar(
            selected = page,
            onSelect = { page = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
        // 双后端提示：底部浮层（不占内容流，避免污染设备卡布局）
        // 切换提示 6s 自动收起；「均不可用」必须常驻，由用户手动关闭。
        val backendNotice by viewModel.backendNotice.collectAsStateWithLifecycle()
        LaunchedEffect(backendNotice) {
            val n = backendNotice ?: return@LaunchedEffect
            if (!n.contains("均不可用")) {
                delay(6_000)
                viewModel.dismissBackendNotice()
            }
        }
        AnimatedVisibility(
            visible = backendNotice != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = 96.dp), // 避开 Tab 浮层
        ) {
            ScCard {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = backendNotice ?: "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "知道了",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .clickable { viewModel.dismissBackendNotice() }
                            .padding(6.dp),
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun P1LibrarySection(
    viewModel: MainViewModel,
    onOpenReverb: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenBand: () -> Unit,
    onOpenWidth: () -> Unit,
    onOpenLimiter: () -> Unit,
    onOpenDynBass: () -> Unit,
    onOpenRack: () -> Unit,
    onOpenVs: () -> Unit,
) {
    // U4：原「设置」区整体迁 P5 我的屏（ScSettingsSection）；U9m gateThreshold 亦迁限幅器详情页
    val takeoverError by viewModel.takeoverError.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqSectionEnabled by viewModel.eqSectionEnabled.collectAsStateWithLifecycle()
    val bassGain by viewModel.bassGain.collectAsStateWithLifecycle()
    val trebleGain by viewModel.trebleGain.collectAsStateWithLifecycle()
    val limiterEnabled by viewModel.limiterEnabled.collectAsStateWithLifecycle()
    // reverbPreset / activeRecentIrName 的 collect 已删除：
    // 「混响」卡副标题从「卷积 + 算法 · 自定义 IR」压到「卷积 + 算法」，
    // 半宽卡放不下 IR 名字；这些信息在详情页里本来就有。
    // 混响组总闸：音效库「混响」卡片的开关
    val reverbSectionEnabled by viewModel.reverbSectionEnabled.collectAsStateWithLifecycle()
    // U7：fdn*/stereoWidth/compressorThreshold/Ratio/multibandParams 已迁各自详情页组件内收集
    val inputGain by viewModel.inputGain.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val outputGain by viewModel.outputGain.collectAsStateWithLifecycle()
    val stereoWidthEnabled by viewModel.stereoWidthEnabled.collectAsStateWithLifecycle()
    // 低频管家总闸：音效库卡片的开关。
    // 关 = 动态低音与虚拟低音同时旁通，两张槽卡记忆不动。
    val lowfreqSectionEnabled by viewModel.lowfreqSectionEnabled.collectAsStateWithLifecycle()
    // 机架总闸：音效库「机架」卡片的开关
    val scriptEnabled by viewModel.scriptEffectEnabled.collectAsStateWithLifecycle()
    val compressorEnabled by viewModel.compressorEnabled.collectAsStateWithLifecycle()
    val multibandEnabled by viewModel.multibandEnabled.collectAsStateWithLifecycle()
    // 压限器总闸：卡片开关绑它。关 = 多段与单段整体旁通，
    // 详情页两张卡联动显示为关并置灰；再开时各自回到原状态。
    val sectionEnabled by viewModel.compressorSectionEnabled.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    // U8j：外层 Tab 容器已 padding(16)，此处不再加（原双 16 叠加导致音效区卡比引擎卡窄 16dp）
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // ---- U8 增益与平衡（用户手绘稿：三拟物旋钮横排，置于引擎运行卡正下方） ----
        ScCard(modifier = Modifier.fillMaxWidth()) {
            // 响应式：三旋钮原为固定 104dp + 24dp 间距，共需 380dp；
            // 窄屏（360dp 级）会把最后一个旋钮挤成 1 字宽 → 「声道平衡」竖排。
            // 按可用宽度反算旋钮尺寸与间距，本机宽度足够时仍为 104dp（外观零变化）。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val spacing = if (maxWidth < 360.dp) 6.dp else 24.dp
                val knobSize = ((maxWidth - 20.dp - spacing * 2 - 4.dp) / 3f)
                    .coerceIn(72.dp, 104.dp).value.toInt()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                ScKnob(
                    label = "输入增益",
                    value = inputGain,
                    min = 0f,
                    max = 2f,
                    sizeDp = knobSize,
                    textScale = 0.6f,
                    format = { "%.2f×".format(it) },
                    // >1.0 即为正增益，叠加链路易逼近满刻度 → 过载视觉预警
                    overloadFrom = 1.0f,
                    // 双击归位到 0dB
                    resetValue = 1.0f,
                    onValueChange = viewModel::setInputGain,
                )
                ScKnob(
                    label = "输出增益",
                    value = outputGain,
                    min = 0f,
                    max = 2f,
                    sizeDp = knobSize,
                    textScale = 0.6f,
                    format = { "%.2f×".format(it) },
                    // 阈值取 +2dB（10^(2/20) ≈ 1.259）。
                    // 输出级小幅正增益属正常操作（补偿录音偏小的曲目），到 +2dB 才提示，
                    // 避免 1.0x 刚往上一点就亮红框、把正常调整也标成问题。
                    overloadFrom = 1.259f,
                    // 双击归位到 0dB
                    resetValue = 1.0f,
                    onValueChange = viewModel::setOutputGain,
                )
                // 声道平衡：左右标签贴弧条末端（U8f，弧 -225°/45° 端点在左下/右下）
                Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                    ScKnob(
                        label = "声道平衡",
                        value = balance,
                        min = -1f,
                        max = 1f,
                        bipolar = true,
                        sizeDp = knobSize,
                        textScale = 0.6f,
                        format = { bal ->
                            when {
                                bal < -0.05f -> "左 %.0f%%".format(-bal * 100)
                                bal > 0.05f -> "右 %.0f%%".format(bal * 100)
                                else -> "居中"
                            }
                        },
                        onDoubleTap = { viewModel.setBalance(0f) },
                        onValueChange = viewModel::setBalance,
                    )
                    Text(
                        text = "左",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 10.dp, bottom = 19.dp),
                    )
                    Text(
                        text = "右",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 10.dp, bottom = 19.dp),
                    )
                }
                }
            }
        }

        // U4：效果预设按钮行退役——场景组合卡（P4 收藏屏）承载「内置+自定义快照+保存当前」
        // ---- 音效：参量均衡器 ----
        Text(text = "音效", style = MaterialTheme.typography.titleSmall)
        // （U9l：U6 信号链条退役——效果启停状态各卡片开关已表达，单帧耗时首卡健康格已有）
        // ---- 卡片顺序：压限器 → 限幅器 → 混响 → 立体声宽度 → 低频管家 → 参量均衡 → 机架 ----
        // 两列网格后自然成三组语义：动态类（压/限）、空间类（混响/宽度）、频率类（低频/均衡）。
        // 六张短卡全部 weight(1f)，3 行 × 2 列；机架仍是全宽卡收尾。
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 压限器：标题从「多频段压限」改来，开关为组总闸
            ScEffectCard(
                title = "压限器",
                subtitle = "多段 + 单段压缩",
                icon = ScEffectIconKind.BURST,
                enabled = sectionEnabled,
                onClick = onOpenBand,
                onToggle = { viewModel.setCompressorSectionEnabled(!sectionEnabled) },
                modifier = Modifier.weight(1f),
            )
            ScEffectCard(
                title = "限幅器",
                subtitle = "防削波安全网",
                icon = ScEffectIconKind.LIMITER,
                enabled = limiterEnabled,
                onClick = onOpenLimiter,
                onToggle = { viewModel.setLimiterEnabled(!limiterEnabled) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 混响：卷积 + 算法合并为组，开关是总闸（关 = 两个混响节点同时旁通）
            ScEffectCard(
                title = "混响",
                // 副标题压到 8 字：半宽卡放不下「卷积 + 算法 · 自定义 IR」，
                // 自定义 IR 的名字在详情页里能看到，不必挤在卡上
                subtitle = "卷积 + 算法",
                icon = ScEffectIconKind.WAVE,
                enabled = reverbSectionEnabled,
                onClick = onOpenReverb,
                onToggle = { viewModel.setReverbSectionEnabled(!reverbSectionEnabled) },
                modifier = Modifier.weight(1f),
            )
            ScEffectCard(
                title = "立体声宽度",
                subtitle = "M/S 矩阵",
                icon = ScEffectIconKind.PULSE,
                enabled = stereoWidthEnabled,
                onClick = onOpenWidth,
                onToggle = { viewModel.setStereoWidthEnabled(!stereoWidthEnabled) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 低频管家：内含动态低音（耳机）+ 虚拟低音（外放）两张槽卡，开关是**总闸**
            ScEffectCard(
                title = "低频管家",
                subtitle = "耳机 / 外放",
                icon = ScEffectIconKind.BASS,
                enabled = lowfreqSectionEnabled,
                onClick = onOpenDynBass,
                onToggle = { viewModel.setLowfreqSectionEnabled(!lowfreqSectionEnabled) },
                modifier = Modifier.weight(1f),
            )
            ScEffectCard(
                title = "均衡器",
                subtitle = "多段曲线校正",
                icon = ScEffectIconKind.EQ,
                // 库卡片 = **组总闸**（与压限器/低频管家一致）；PEQ 与 AutoEq 是它的两张子卡
                enabled = eqSectionEnabled,
                onClick = onOpenEq,
                onToggle = { viewModel.setEqSectionEnabled(!eqSectionEnabled) },
                modifier = Modifier.weight(1f),
            )
        }
        val vsEnabled = viewModel.vsEnabled.collectAsStateWithLifecycle().value
        // 空间环绕有两个引擎（矩阵环绕 / Surround71），互斥。总闸必须「任一开启即算开」，
        // 否则打开 Surround71 后（矩阵环绕被自动关闭）总闸会显示为关。
        val s71Enabled = viewModel.s71Enabled.collectAsStateWithLifecycle().value
        val vsAnyOn = vsEnabled || s71Enabled
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 空间环绕：与机架同行（两者都是"整条总线级"的处理，语义相近）
            // 副标题压到 8 字以内：半宽卡放不下原来那句「7 声道虚拟布局 · 双耳 HRTF 渲染」
            ScEffectCard(
                title = "空间环绕",
                subtitle = "耳机虚拟环绕",
                icon = ScEffectIconKind.WAVE,
                enabled = vsAnyOn,
                onClick = onOpenVs,
                onToggle = {
                    // 关闭 → 两个引擎都关；开启 → 恢复**上次使用的那个引擎**
                    // （原来固定开矩阵环绕，会导致"在用 Surround71 时关掉再打开却变回矩阵环绕"）
                    if (vsAnyOn) viewModel.closeAllSurroundEngines() else viewModel.openLastSurroundEngine()
                },
                modifier = Modifier.weight(1f),
            )
            // 机架：脚本机架自设置页迁来；开关为整机总闸
            ScEffectCard(
                title = "机架",
                subtitle = "脚本槽位 ×3",
                icon = ScEffectIconKind.WAVE,
                enabled = scriptEnabled,
                onClick = onOpenRack,
                onToggle = { viewModel.setScriptEffectEnabled(!scriptEnabled) },
                modifier = Modifier.weight(1f),
            )
        }
        // （输入增益/声道平衡/输出增益已迁引擎卡下旋钮卡，U8；限幅器已迁详情页，U7b）
        // （U9k：A/B 标尺说明迁各效果详情页头部、峰值保护说明迁限幅器详情——音效库列表底部小字清空）

        // （算法混响 FDN / 立体声宽度参数已迁各自详情页，U7）

        // （U9m：静音门限滑块迁限幅器详情页下方——门限与输出链收口同属一处）

        takeoverError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { viewModel.clearTakeoverError() },
            )
        }
    }
}

// U4：ScScreens.kt 的 ScSettingsSection（P5 设置段）也要用，同包跨文件可见
enum class AppPickMode { EXCLUDE, TAKEOVER }

@Composable
internal fun AppPickDialog(
    viewModel: MainViewModel,
    mode: AppPickMode,
    excluded: Set<String>,
    takeover: Set<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 按「有启动入口」过滤（预装媒体 app 如抖音极速版带 FLAG_SYSTEM，不能按系统标记排除）
    val apps = remember {
        runCatching {
            context.packageManager.getInstalledPackages(0)
                .mapNotNull { info ->
                    val appInfo = info.applicationInfo ?: return@mapNotNull null
                    // 跳过自身（防回环已在引擎侧强制排除；接管自身 = 自静音事故）
                    if (appInfo.packageName == context.packageName) return@mapNotNull null
                    (appInfo.loadLabel(context.packageManager) as? String)?.let { it to appInfo.packageName }
                }
                .filter { (_, pkg) -> context.packageManager.getLaunchIntentForPackage(pkg) != null }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (mode == AppPickMode.TAKEOVER) "选择要接管（静音原声）的应用"
                else "选择要排除（不捕获）的应用",
            )
        },
        text = {
            if (apps.isEmpty()) {
                Text("未找到可选择的第三方应用")
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(apps, key = { it.second }) { (label, pkg) ->
                        val checked = if (mode == AppPickMode.TAKEOVER) pkg in takeover else pkg in excluded
                        val conflict = if (mode == AppPickMode.TAKEOVER) pkg in excluded else pkg in takeover
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (mode == AppPickMode.TAKEOVER) viewModel.toggleTakeover(pkg)
                                    else viewModel.toggleExcluded(pkg)
                                },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column {
                                Text(label, fontSize = 14.sp)
                                if (conflict) {
                                    Text(
                                        text = "已${if (mode == AppPickMode.TAKEOVER) "在排除清单（勾选将自动移除）" else "在接管清单（勾选将自动移除）"}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (mode == AppPickMode.TAKEOVER) "完成（实时生效）" else "完成（重启内录后生效）")
            }
        },
    )
}
