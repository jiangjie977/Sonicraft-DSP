/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-FileCopyrightText: 2026 qumolangmo
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 部分逻辑移植自 WEcho（GPL-3.0，Copyright (C) 2026 qumolangmo）；
 * 本文件由 Sonicraft 于 2026-11 在 WEcho 基础上移植并修改。
 */
package com.jiangjie.Sonicraft.ui

import com.jiangjie.Sonicraft.ui.components.ScKnob
import com.jiangjie.Sonicraft.ui.theme.ScColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiangjie.Sonicraft.EqMath
import com.jiangjie.Sonicraft.MainViewModel
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * 多段 EQ 一体化面板（U9n GEQ 退役，仅 PEQ）：
 * 上区 = 手绘曲线编辑区（横轴频点、纵轴 ±12dB、1dB 步进网格；手指划过连续设置
 *        各频点增益，跳过的 band 按线性插值填充；曲线实时更新）；
 * 下区 = 选中段迷你旋钮（Q/G/F，Pro-Q 式就近调节）。
 * 曲线与旋钮同源（eqBandGains）实时联动。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MultiBandEqPanel(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqMode by viewModel.eqMode.collectAsStateWithLifecycle()
    val eqBandGains by viewModel.eqBandGains.collectAsStateWithLifecycle()
    val eqBandQs by viewModel.eqBandQs.collectAsStateWithLifecycle()
    val eqPreGainDb by viewModel.eqPreGainDb.collectAsStateWithLifecycle()
    val eqBandFreqs by viewModel.eqBandFreqs.collectAsStateWithLifecycle()
    val eqUserPresets by viewModel.eqUserPresets.collectAsStateWithLifecycle()
    val eqBandTypes by viewModel.eqBandTypes.collectAsStateWithLifecycle()
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    // AutoEq 耳机修正（粘贴 ParametricEQ.txt 一键应用）
    // 成功提示用品牌色、失败用红：此前所有提示都是红色，成功也像报错（2026-11-14 修）
    // 文件导入来源名：非空时应用成功后自动存为该名字的预设（粘贴导入为 null，不污染预设库）
    var draggingBand by remember { mutableStateOf(-1) }
    var selectedBand by remember { mutableStateOf(0) }
    // 手绘防误触锁（参照 wecho GraphicEqPanel 的锁定思路）：锁定后曲线区禁用
    // 手势，滚动页面经过时不会误改增益；只锁手绘，Q/频率/pre-gain 滑条仍可用
    var eqLocked by rememberSaveable { mutableStateOf(false) }
    // 数字精确输入（对齐 wecho 标签点击弹对话框）：q / bandgain / freq / bands
    var numEditField by remember { mutableStateOf<String?>(null) }
    var numEditValue by remember { mutableStateOf("") }
    // EQ 实时频谱衬底（Pro-Q 式双层）：帧循环拉取 2048 floats（前 1024=入口谱、
    // 后 1024=出口谱，逐 bin dB）。引擎 Running 有实测数据，否则 -60 全静默不可见。
    // tick 驱动手绘 Canvas 逐帧重绘（与电平表同模式）
    val specIn = remember { FloatArray(1024) { -60f } }
    val specOut = remember { FloatArray(1024) { -60f } }
    var specTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { }
            viewModel.eqSpectrum()?.let {
                if (it.size >= 2048) {
                    System.arraycopy(it, 0, specIn, 0, 1024)
                    System.arraycopy(it, 1024, specOut, 0, 1024)
                    specTick++
                }
            }
        }
    }
    // 段数变化时夹紧选中段（防越界残留；夹紧而非归 0——＋段后保持指向新末段，
    // 否则「＋段→－段」会误删第 1 段）
    androidx.compose.runtime.LaunchedEffect(eqMode) {
        if (selectedBand >= eqMode) selectedBand = eqMode - 1
    }
    // 曲线/刻度直接用真实段频率（含 AutoEq 导入与自定义段数的任意频率）——
    // 原 freqTable(eqMode) 在导入非档位频率时会画错轴（潜在错位，段数泛化后必现）
    val freqs = eqBandFreqs
    val bands = freqs.size
    // 调试轴控件开关：默认隐藏，连点「选中段 N」8 次切换显隐。
    // 声明在 Column 之外——轴控件行本身在段数 Row 之后，需要同一作用域。
    // 仅 BuildConfig.DEBUG 可开：release 包连点也无效，用户永远看不到开发期旋钮。
    var showAxisDebug by remember { mutableStateOf(false) }
    var axisTapCount by remember { mutableStateOf(0) }
    // 用 debuggable 标志而不是 BuildConfig：本工程未启用 buildConfig 代码生成。
    // release 包 flags 不含 FLAG_DEBUGGABLE → 连点无效，轴控件永不出现。
    val debugCtx = androidx.compose.ui.platform.LocalContext.current
    val debuggable = remember(debugCtx) {
        val flags = debugCtx.applicationInfo.flags
        (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // （U9p：锁定手绘+档位行下沉并入段数行——锁改小锁字符，省一整行）
        // 段数行（对齐 wecho Bands 数字框 + 段增删）：段数任意 1~31（改段数
        // 重置为默认平直），+段在尾部按几何中点加段，−段删除选中段。
        // U9p：锁+档位下沉并入本行，全行标签 SpaceBetween 均匀分配。
        // U9p3：全 clickable Text（TextButton 默认 defaultMinSize 58dp×40dp——
        // 按钮边界≠文字边界，SpaceBetween 均匀分配到按钮、视觉间隙忽大忽小；
        // 40dp 高也是行间大留白元凶）+ 关 48dp 触摸目标强制 + fillMaxWidth。
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "段数 $eqMode",
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { numEditValue = eqMode.toString(); numEditField = "bands" }
                    .padding(vertical = 4.dp),
            )
            Text(
                text = "＋段",
                fontSize = 12.sp,
                color = if (eqMode < 31) Color.Unspecified else Color(0xFFB0B0B0),
                modifier = Modifier
                    .clickable(enabled = eqMode < 31) {
                        selectedBand = eqMode  // 新段在尾部（旧段数=新末段 index）
                        viewModel.addEqBand()
                    }
                    .padding(vertical = 4.dp),
            )
            Text(
                text = "－段",
                fontSize = 12.sp,
                color = if (eqMode > 1) Color.Unspecified else Color(0xFFB0B0B0),
                modifier = Modifier
                    .clickable(enabled = eqMode > 1) { viewModel.removeEqBand(selectedBand) }
                    .padding(vertical = 4.dp),
            )
            // 手绘锁定开关：矢量锁图标（单色跟随 tint——emoji 锁是彩色黄，与面板单色系不合）；
            // 锁定=Lock 深红+红底胶囊高亮、未锁=LockOpen 灰；锁定时曲线不挂手势防误触。
            // 水平 padding 12dp 加宽点击区与胶囊底
            Icon(
                imageVector = if (eqLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (eqLocked) "手绘已锁" else "锁定手绘",
                tint = if (eqLocked) Color(0xFFB00020) else Color(0xFF8A8A8A),
                modifier = Modifier
                    .clickable { eqLocked = !eqLocked }
                    .then(
                        if (eqLocked) Modifier
                            .background(Color(0x1AB00020), RoundedCornerShape(8.dp))
                        else Modifier,
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .size(16.dp),
            )
            listOf(10, 15, 31).forEach { m ->
                Text(
                    text = if (eqMode == m) "● $m" else "$m",
                    fontSize = 12.sp,
                    fontWeight = if (eqMode == m) androidx.compose.ui.text.font.FontWeight.Bold
                    else androidx.compose.ui.text.font.FontWeight.Normal,
                    modifier = Modifier
                        .clickable { viewModel.setEqMode(m) }
                        .padding(vertical = 4.dp),
                )
            }
            Text(
                text = "选中段 ${selectedBand + 1}",
                fontSize = 12.sp,
                modifier = Modifier.clickable(enabled = debuggable) {
                    axisTapCount++
                    if (axisTapCount >= 8) {
                        axisTapCount = 0
                        showAxisDebug = !showAxisDebug
                    }
                },
            )
        }
        }
        // 上区：手绘曲线编辑区（1dB 步进网格；划过连续设置，跳过段线性插值）。
        // 锁定时整个不挂 pointerInput（触摸穿透，滚动不误改）。
        if (showAxisDebug) {
        // 开发期频率轴范围微调（调 X 轴上下限）。**已收进调试模式**：默认不渲染，
        // 连点「选中段 N」8 次打开，release 包永不显示。
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "<", fontSize = 11.sp, modifier = Modifier.clickable { applyAxisTransform((axisLo + axisHi) / 2f, -80f, 1f, 300f) }.padding(horizontal = 10.dp, vertical = 2.dp))
            Text(text = "+", fontSize = 11.sp, modifier = Modifier.clickable { applyAxisTransform((axisLo + axisHi) / 2f, 0f, 0.5f, 300f) }.padding(horizontal = 10.dp, vertical = 2.dp))
            Text(text = "view %.0fHz ~ %.1fkHz (tap=reset)".format(axisLo.toDouble(), (axisHi / 1000f).toDouble()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { axisLo = 20f; axisHi = 20000f })
            Text(text = "-", fontSize = 11.sp, modifier = Modifier.clickable { applyAxisTransform((axisLo + axisHi) / 2f, 0f, 2f, 300f) }.padding(horizontal = 10.dp, vertical = 2.dp))
            Text(text = ">", fontSize = 11.sp, modifier = Modifier.clickable { applyAxisTransform((axisLo + axisHi) / 2f, 80f, 1f, 300f) }.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .then(
                    if (eqLocked) {
                        Modifier
                    } else {
                        Modifier.pointerInput(bands) {
                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            // Pro-Q 式双模交互：按在锚点列附近=抓节点（x→频率、y→增益，
                            // 松手即持久化）；其他区域=手绘划线（命中最近段，1dB 量化+
                            // 跳段插值）。频率/增益经 StateFlow .value 读最新值（手势块
                            // 内 Compose state 是启动快照，不可靠）
                            val fDown = xToFreq(down.position.x, w)
                            val nodeIdx = nearestBand(fDown, viewModel.eqBandFreqs.value)
                            val nodeX = freqToX(viewModel.eqBandFreqs.value.getOrElse(nodeIdx) { 1000f }, w)
                            val grabR = 28.dp.toPx()
                            if (kotlin.math.abs(down.position.x - nodeX) < grabR) {
                                // 节点拖拽模式
                                draggingBand = nodeIdx
                                selectedBand = nodeIdx
                                drag(down.id) { change ->
                                    change.consume()
                                    // U9n GEQ 退役：恒 PEQ，节点拖拽同时调频率+增益
                                    viewModel.setEqBandFreq(
                                        nodeIdx,
                                        xToFreq(change.position.x, w).coerceIn(20f, 20000f),
                                    )
                                    viewModel.setEqBandGain(
                                        nodeIdx,
                                        yToGainContinuous(change.position.y, h),
                                    )
                                }
                            } else {
                                // 手绘划线模式
                                val b1 = nearestBand(fDown, viewModel.eqBandFreqs.value)
                                val g1 = quantGain(curveYToGain(down.position.y, h))
                                draggingBand = b1
                                selectedBand = b1
                                viewModel.setEqBandGain(b1, g1)
                                var lastB = b1
                                var lastY = down.position.y
                                drag(down.id) { change ->
                                    change.consume()
                                    val b2 = nearestBand(xToFreq(change.position.x, w), viewModel.eqBandFreqs.value)
                                    if (b2 == lastB) {
                                        viewModel.setEqBandGain(b2, quantGain(curveYToGain(change.position.y, h)))
                                    } else {
                                        val lo = minOf(lastB, b2)
                                        val hi = maxOf(lastB, b2)
                                        for (bb in lo..hi) {
                                            val t = (bb - lastB).toFloat() / (b2 - lastB).toFloat()
                                            val gy = lastY + (change.position.y - lastY) * t
                                            viewModel.setEqBandGain(bb, quantGain(curveYToGain(gy, h)))
                                        }
                                    }
                                    lastB = b2
                                    lastY = change.position.y
                                }
                            }
                        } finally {
                            draggingBand = -1
                        }
                    }
                }
                }
            ),
        ) {
            val tick = specTick  // 建立逐帧重绘依赖（频谱衬底 30~60fps 刷新）
            drawEqEditor(
                size.width, size.height, freqs, eqBandGains, draggingBand,
                eqBandQs, eqPreGainDb, eqBandTypes,
                selectedBand, specIn, specOut,
            )
        }
        // 对数频率刻度（20~20k 十档；曲线/滑条墙/频谱衬底共用同一真实轴）
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(18.dp),
        ) {
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint().apply {
                    color = 0xFF888888.toInt(); textSize = 20f; isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                logTicks(axisLo, axisHi).forEach { f ->
                    c.nativeCanvas.drawText(
                        EqMath.label(f), freqToX(f, size.width.toFloat()), size.height - 4f, paint,
                    )
                }
            }
        }
        // 阶段 3：选中段迷你旋钮（未锁定时显示——Pro-Q 式就近调节；U9n GEQ 退役后恒显）
        if (selectedBand >= 0 && !eqLocked) {
            Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
                val bandColor = kBandColors[selectedBand % kBandColors.size]
                ScKnob(
                    "Q (点按输入)", eqBandQs.getOrElse(selectedBand) { 1f }, 0.1f, 8f, signed = false,
                    modifier = Modifier.weight(1f), sizeDp = 88,
                    onLabelTap = { numEditValue = "%.2f".format(eqBandQs.getOrElse(selectedBand) { 1f }); numEditField = "q" },
                ) { viewModel.setEqBandQ(selectedBand, it) }
                ScKnob(
                    "G (点按输入)", eqBandGains.getOrElse(selectedBand) { 0f }, -12f, 12f,
                    modifier = Modifier.weight(1.2f), sizeDp = 118,
                    onLabelTap = { numEditValue = "%.1f".format(eqBandGains.getOrElse(selectedBand) { 0f }); numEditField = "bandgain" },
                ) { viewModel.setEqBandGain(selectedBand, it) }
                ScKnob(
                    "F (点按输入)", freqs.getOrElse(selectedBand) { 1000f }, 20f, 20000f,
                    modifier = Modifier.weight(1f), log = true, sizeDp = 88,
                    onLabelTap = { numEditValue = "%.0f".format(freqs.getOrElse(selectedBand) { 1000f }); numEditField = "freq" },
                ) { viewModel.setEqBandFreq(selectedBand, it) }
            }
        }
        // （U9n：GEQ/PEQ 模式切换按钮退役——仅保留参量 PEQ）
        val selFreq = freqs.getOrElse(selectedBand) { 1000f }
        val selType = eqBandTypes.getOrElse(selectedBand) { 0 }
        // 段类型（对齐 wecho PK/LSC/HSC 下拉）：PK=钟形峰、LSC=低架、HSC=高架
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(text = "类型：", fontSize = 13.sp)
            listOf(0 to "PK", 1 to "LSC", 2 to "HSC").forEach { (t, name) ->
                TextButton(onClick = { viewModel.setEqBandType(selectedBand, t) }) {
                    Text(
                        text = if (selType == t) "● $name" else name,
                        fontSize = 12.sp,
                        fontWeight = if (selType == t) androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal,
                    )
                }
            }
        }
        if (selType != 0) {
            Text(
                text = "shelf 类型（LSC/HSC）Q 不生效（RBJ S=1 口径）",
                fontSize = 11.sp,
                color = Color(0xFF8A8A8A),
            )
        }
        Row {
            Text(text = "预设", fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
        }
        // 内置预设：10 个一行铺不下（人声之后被截断）→ U9p5 改横向可滑动。
        // TextButton 保留：滑动行不要求均匀分布，58dp 最小宽反而是充足触摸区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            EqMath.BUILTIN.forEach { (name, points) ->
                TextButton(onClick = { viewModel.applyEqPresetCurve(points) }) {
                    Text(text = name, fontSize = 12.sp)
                }
            }
        }
        // 自定义预设（关键帧：完整快照=模式+增益+Q+频率）
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "\u81ea\u5b9a\u4e49\u9884\u8bbe", fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { presetName = ""; showSaveDialog = true }) {
                Text(text = "\u4fdd\u5b58\u5f53\u524d", fontSize = 12.sp)
            }
        }
        // 自定义预设列表：同样横向可滑动（U9p5，与内置预设行一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            eqUserPresets.forEach { p ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.applyEqUserPreset(p) }) {
                        Text(text = p.name, fontSize = 12.sp)
                    }
                    TextButton(onClick = { viewModel.deleteEqUserPreset(p.name) }) {
                        Text(text = "\u00d7", fontSize = 12.sp)
                    }
                }
            }
        }
        if (showSaveDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveEqUserPreset(presetName)
                        showSaveDialog = false
                    }) { Text(text = "\u4fdd\u5b58") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text(text = "\u53d6\u6d88") }
                },
                title = { Text(text = "\u4fdd\u5b58\u4e3a\u81ea\u5b9a\u4e49\u9884\u8bbe") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        singleLine = true,
                        placeholder = { Text(text = "\u7559\u7a7a\u81ea\u52a8\u547d\u540d\u4e3a\u300c\u9884\u8bbe N\u300d") },
                    )
                },
            )
        }
        // 数字精确输入（Q/总增益/频率/段数标签点击弹出；对齐 wecho _showValueDialog
        // 的 clamp 精确输入。解析失败静默关闭=不改值）
        numEditField?.let { field ->
            val (title, hint) = when (field) {
                "q" -> "输入 Q 值（段 ${selectedBand + 1}）" to "0.3 ~ 8.0"
                "bandgain" -> "输入增益 dB（段 ${selectedBand + 1}）" to "-12.0 ~ 12.0"
                "freq" -> "输入频率 Hz（段 ${selectedBand + 1}）" to "20 ~ 20000"
                else -> "输入段数（重置为默认平直）" to "1 ~ 31"
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { numEditField = null },
                confirmButton = {
                    TextButton(onClick = {
                        val v = numEditValue.toFloatOrNull()
                        when (field) {
                            "q" -> v?.let { viewModel.setEqBandQ(selectedBand, it) }
                            "bandgain" -> v?.let { viewModel.setEqBandGain(selectedBand, it) }
                            "freq" -> v?.let { viewModel.setEqBandFreq(selectedBand, it) }
                            else -> v?.toInt()?.let { viewModel.setEqBandCountCustom(it) }
                        }
                        numEditField = null
                    }) { Text(text = "确定") }
                },
                dismissButton = {
                    TextButton(onClick = { numEditField = null }) { Text(text = "取消") }
                },
                title = { Text(text = title) },
                text = {
                    Column {
                        Text(text = "范围：$hint", fontSize = 12.sp, color = Color(0xFF8A8A8A))
                        androidx.compose.material3.OutlinedTextField(
                            value = numEditValue,
                            onValueChange = { numEditValue = it },
                            singleLine = true,
                        )
                    }
                },
            )
        }
    }
}

// ---- 对数频率轴（20~20k，Pro-Q 同款口径）：绘制/命中/刻度全部共用 ----
private var axisLo by androidx.compose.runtime.mutableStateOf(20f)
private var axisHi by androidx.compose.runtime.mutableStateOf(20000f)

private fun xToFreq(x: Float, w: Float): Float {
    val lo = kotlin.math.ln(axisLo.toDouble())
    val span = kotlin.math.ln(axisHi.toDouble()) - lo
    return kotlin.math.exp(lo + span * (x / w).coerceIn(0f, 1f).toDouble()).toFloat()
}

private fun freqToX(f: Float, w: Float): Float {
    val lo = kotlin.math.ln(axisLo.toDouble())
    val span = kotlin.math.ln(axisHi.toDouble()) - lo
    val lf = kotlin.math.ln(f.toDouble().coerceIn(axisLo.toDouble(), axisHi.toDouble()))
    return (w * ((lf - lo) / span)).toFloat()
}

private fun logTicks(lo: Float, hi: Float): List<Float> {
    val oct = listOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val third = listOf(25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f)
    val out = oct.filter { it >= lo && it <= hi }
    return if (out.size < 4) third.filter { it >= lo && it <= hi } else out
}

private fun applyAxisTransform(anchorX: Float, panX: Float, zoom: Float, w: Float) {
    var lo = kotlin.math.ln(axisLo.toDouble())
    var hi = kotlin.math.ln(axisHi.toDouble())
    val span0 = hi - lo
    lo -= (panX / w) * span0
    hi -= (panX / w) * span0
    val fC = xToFreq(anchorX.coerceIn(0f, w), w)
    val newSpan = ((hi - lo) / zoom.coerceIn(0.2f, 5.0f).toDouble()).coerceIn(0.35, 3.0)
    val lfC = kotlin.math.ln(fC.toDouble())
    var nLo = lfC - (lfC - lo) * (newSpan / span0)
    var nHi = nLo + newSpan
    if (nLo < kotlin.math.ln(20.0)) { nLo = kotlin.math.ln(20.0); nHi = nLo + newSpan }
    if (nHi > kotlin.math.ln(20000.0)) { nHi = kotlin.math.ln(20000.0); nLo = nHi - newSpan }
    axisLo = kotlin.math.exp(nLo).toFloat()
    axisHi = kotlin.math.exp(nHi).toFloat()
}

/** 频率 → 最近段（对数距离；非均分段位下命中按频率邻近而非画位均分） */
private fun nearestBand(f: Float, freqs: List<Float>): Int {
    var best = 0
    var bestD = Float.MAX_VALUE
    val lf = Math.log10(f.toDouble())
    for (i in freqs.indices) {
        val d = kotlin.math.abs(Math.log10(freqs[i].toDouble()) - lf).toFloat()
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

/** 手绘区 y→增益（±12dB 全高，量化 1dB 步进）——划线模式用 */
private fun curveYToGain(y: Float, h: Float): Float {
    val t = (y / h).coerceIn(0f, 1f)
    return ((1f - 2f * t) * 12f).roundToInt().toFloat()
}

private fun quantGain(g: Float): Float = g.roundToInt().toFloat().coerceIn(-12f, 12f)

/** 节点拖拽 y→增益（连续值，不量化——Pro-Q 式顺滑拖点） */
private fun yToGainContinuous(y: Float, h: Float): Float =
    ((1f - 2f * (y / h).coerceIn(0f, 1f)) * 12f).coerceIn(-12f, 12f)

private fun gainToY(g: Float, h: Float): Float = h / 2 - (g / 12f) * (h / 2)

/** 段色板（Pro-Q 式每段一色，8 色循环） */
private val kBandColors = listOf(
    Color(0xFF4CAF50), Color(0xFFAB47BC), Color(0xFFFDD835), Color(0xFF42A5F5),
    Color(0xFFFF7043), Color(0xFFEC407A), Color(0xFF26C6DA), Color(0xFF9CCC65),
)

/** 频谱 area（平滑填充，Pro-Q 式灰雾）：spec 逐 bin dB（2048 点 FFT 直出），
 *  衬于底部 55% 高度。对数轴 160 点采样直读 bin（与轴天然对齐，无分带错位）；
 *  采样值取「本点 bin 到下点 bin」区间的 max（低频 1 bin 保细节、高频宽窗防
 *  漏峰——固定宽 max 窗会把低频谷全填平）；3-tap + Catmull-Rom 双层平滑 */
private fun DrawScope.drawSpectrumArea(spec: FloatArray, w: Float, h: Float, color: Color) {
    val binHz = 48000f / 2048f
    // 对数轴 160 点采样：先算各点 bin，采样值=区间 [bin_p, bin_{p+1}) 的 max
    val bins = IntArray(161)
    for (p in 0..160) {
        val f = (20.0 * Math.pow(10.0, 3.0 * p / 160.0)).toFloat()
        bins[p] = (f / binHz).toInt().coerceIn(1, 1023)
    }
    val sampled = FloatArray(161)
    for (p in 0..160) {
        val hi = maxOf(bins[p], if (p < 160) bins[p + 1] - 1 else bins[p])
        var db = spec[bins[p]]
        for (k in bins[p] + 1..hi) db = maxOf(db, spec[k])
        sampled[p] = db
    }
    // 3-tap 预平滑（消采样点域毛刺）
    val smooth = FloatArray(161)
    for (i in 0..160) {
        val p = sampled.getOrElse(i - 1) { sampled[i] }
        val c = sampled[i]
        val nx = sampled.getOrElse(i + 1) { sampled[i] }
        smooth[i] = 0.25f * p + 0.5f * c + 0.25f * nx
    }
    val pts = ArrayList<Offset>(161)
    for (p in 0..160) {
        val f = (20.0 * Math.pow(10.0, 3.0 * p / 160.0)).toFloat()
        // 显示范围 -60~+6dB（66dB 跨度）：0dB 恰映射到 h/2（0dB 网格线），
        // +6dB 顶到 0.45h——大能量低频/EQ 提升段可越过 0dB 线，不再贴线削顶
        val t = ((smooth[p] + 60f) / 66f).coerceIn(0f, 1f)
        pts.add(Offset(freqToX(f, w), h - t * h * 0.55f))
    }
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts.getOrElse(i - 1) { pts[i] }
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts.getOrElse(i + 2) { pts[i + 1] }
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    path.lineTo(w, h)
    path.lineTo(0f, h)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawEqEditor(
    w: Float,
    h: Float,
    freqs: List<Float>,
    gains: List<Float>,
    draggingBand: Int,
    bandQs: List<Float>,
    preGainDb: Float,
    bandTypes: List<Int>,
    selectedBand: Int,
    spectrumIn: FloatArray,
    spectrumOut: FloatArray,
) {
    val n = freqs.size
    fun fx(f: Float): Float = freqToX(f, w)
    // 显示量程动态自适应：±12 基准，响应超界时按 3dB 步进扩展（防大增益贴顶裁剪）
    val respMax = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f) + preGainDb.coerceAtLeast(0f) + 3f
    val rangeDb = maxOf(12, kotlin.math.ceil((respMax + 1.5f) / 3f).toInt() * 3)
    val rangeF = rangeDb.toFloat()
    fun responseY(f: Float, gs: List<Float>, ts: List<Int>): Float {
        val db = EqMath.responseAt(freqs, gs, f, qs = bandQs, types = ts) + preGainDb
        return (h / 2 - (db / rangeF) * (h / 2)).coerceIn(1f, h - 1f)
    }
    // ---- 频谱双层衬底（Pro-Q 式）：入口灰雾（EQ 前）/ 出口青（EQ 后），
    //      两层之差直观呈现 EQ 修正量；衬于网格与曲线之下 ----
    drawSpectrumArea(spectrumIn, w, h, Color(0x2E8A8A8A))
    drawSpectrumArea(spectrumOut, w, h, Color(0x3D4A4A4A))
    // ---- 双层图例（顶部居中）：指明看哪层——EQ 调整只影响青层（EQ 后）----
    val lgx = w * 0.34f
    drawRect(Color(0x5F9E9E9E), Offset(lgx, 4f), Size(14f, 10f))
    drawRect(Color(0x5F4A4A4A), Offset(lgx + 84f, 4f), Size(14f, 10f))
    drawIntoCanvas { c ->
        val p = android.graphics.Paint().apply {
            color = 0xFF8A8A8A.toInt(); textSize = 18f; isAntiAlias = true
        }
        c.nativeCanvas.drawText("EQ前", lgx + 18f, 13f, p)
        c.nativeCanvas.drawText("EQ后", lgx + 102f, 13f, p)
    }
    // ---- 横向网格：1dB 细格（淡）+ 3dB 主格（较深）+ 左缘 dB 标签 ----
    for (dbv in -rangeDb..rangeDb) {
        val y = h / 2 - (dbv / rangeF) * (h / 2)
        val major = dbv % 3 == 0
        drawLine(
            if (dbv == 0) Color(0xFF6A6A6A) else if (major) Color(0xFFBDBDBD) else Color(0xFFE4E4E4),
            Offset(0f, y), Offset(w, y),
            if (dbv == 0) 2f else 1f,
        )

    }
    // ---- 对数纵刻度细线（20/50/.../20k）----
    for (f in logTicks(axisLo, axisHi)) drawLine(Color(0x1E000000), Offset(fx(f), 0f), Offset(fx(f), h), 1f)
    // ---- 段竖线（真实频率位+段色；选中段加深——Q/增益/频率滑条操纵对象常显）----
    for (i in 0 until n) {
        val cx = fx(freqs[i])
        val col = kBandColors[i % kBandColors.size]
        if (i == selectedBand) {
            drawLine(col.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, h), 2f)
        } else {
            drawLine(Color(0x1A000000), Offset(cx, 0f), Offset(cx, h), 1f)
        }
    }
    // ---- 主曲线：精确级联响应（含逐段 Q/类型/级联互作 + pre-gain），对数轴 160 点 ----
    var prev: Offset? = null
    for (p in 0..159) {
        val f = (20.0 * Math.pow(10.0, 3.0 * p / 159.0)).toFloat()
        val cur = Offset(fx(f), responseY(f, gains, bandTypes))
        prev?.let { drawLine(Color(0xFFB25E12), it, cur, 4f) }
        prev = cur
    }
    // ---- 选中段单段响应填充（Pro-Q 式彩色峰形：填充+描边同色）----
    if (selectedBand in 0 until n) {
        val col = kBandColors[selectedBand % kBandColors.size]
        val sg = List(n) { i -> if (i == selectedBand) gains.getOrElse(i) { 0f } else 0f }
        val st = List(n) { i -> if (i == selectedBand) bandTypes.getOrElse(i) { 0 } else 0 }
        val path = androidx.compose.ui.graphics.Path()
        var prevS: Offset? = null
        for (p in 0..159) {
            val f = (20.0 * Math.pow(10.0, 3.0 * p / 159.0)).toFloat()
            val pt = Offset(fx(f), responseY(f, sg, st))
            if (p == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            prevS?.let { drawLine(col, it, pt, 2f) }
            prevS = pt
        }
        // 闭合到 0dB 基准线（h/2）而非图底——填充永远夹在「曲线与 0dB 线之间」
        // （Pro-Q 口径：峰在上方填峰、谷在下方填谷、穿越 0dB 时跟随曲线）
        path.lineTo(fx(20000f), h / 2f)
        path.lineTo(fx(20f), h / 2f)
        path.close()
        drawPath(path, col.copy(alpha = 0.28f))
    }
    // ---- 拖动光晕 ----
    if (draggingBand >= 0) {
        val cx = fx(freqs.getOrElse(draggingBand) { 1000f })
        val cy = (h / 2 - (gains.getOrElse(draggingBand) { 0f } / rangeF) * (h / 2)).coerceIn(1f, h - 1f)
        drawCircle(
            Brush.radialGradient(
                colors = listOf(Color(0x66888888), Color(0x00888888)),
                center = Offset(cx, cy), radius = 56f,
            ),
            radius = 56f, center = Offset(cx, cy),
        )
    }
    // ---- 段锚点（Pro-Q 式彩色节点：段色圆点，选中段大点白芯）----
    for (i in 0 until n) {
        val cx = fx(freqs[i])
        val cy = (h / 2 - (gains.getOrElse(i) { 0f } / rangeF) * (h / 2)).coerceIn(1f, h - 1f)
        val col = kBandColors[i % kBandColors.size]
        if (i == selectedBand) {
            drawCircle(col, 9f, Offset(cx, cy))
            drawCircle(Color.White, 3.5f, Offset(cx, cy))
        } else if (i != draggingBand) {
            drawCircle(col.copy(alpha = 0.55f), 5f, Offset(cx, cy))
        }
    }
}

/**
 * AutoEq 参数导入预览曲线。
 *
 * 用 [EqMath.responseAt] 按真实双二阶算 PK/LSC/HSC 各段叠加后的幅频响应，
 * 横轴 20 Hz–20 kHz 对数、纵轴按峰值对称取 6 dB 网格 —— 形状可直接与 AutoEq 网页上的图对照。
 */
@Composable
private fun EqResponseChart(
    freqs: List<Float>,
    gains: List<Float>,
    qs: List<Float>,
    types: List<Int>,
    modifier: Modifier = Modifier,
    /** 第二条级联曲线（AutoEq）：与主曲线相加 —— 两级串联的幅频响应就是 dB 相加 */
    extra: List<com.jiangjie.Sonicraft.AutoEqParser.Band> = emptyList(),
) {
    val samples = remember(freqs, gains, qs, types, extra) {
        val n = 120
        val lo = kotlin.math.ln(20.0)
        val hi = kotlin.math.ln(20000.0)
        List(n) { i ->
            val f = kotlin.math.exp(lo + (hi - lo) * i / (n - 1)).toFloat()
            EqMath.responseAt(freqs, gains, f, qs = qs, types = types) +
                if (extra.isEmpty()) 0f else EqMath.responseAt(
                    extra.map { it.freq }, extra.map { it.gain }, f,
                    qs = extra.map { it.q }, types = extra.map { it.type },
                )
        }
    }
    val peak = samples.maxOrNull() ?: 0f
    val dip = samples.minOrNull() ?: 0f
    // 纵向范围：至少 ±6 dB，向上取 6 的倍数，保证上下对称好看
    val span = maxOf(6f, kotlin.math.ceil(maxOf(kotlin.math.abs(peak), kotlin.math.abs(dip)) / 6f) * 6f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padV = 8.dp.toPx()

        // 背景
        drawRoundRect(
            color = ScColors.Inset,
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
        // 水平网格（-span, -span/2, 0, +span/2, +span）
        val gridColor = Color(0x1A000000)
        for (i in 0..4) {
            val y = padV + (h - padV * 2) * i / 4f
            drawLine(
                color = if (i == 2) Color(0x33000000) else gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (i == 2) 1.2f else 0.8f,
            )
        }
        // 垂直网格（100 / 1k / 10k）
        for (f in listOf(100f, 1000f, 10000f)) {
            val t = (kotlin.math.ln(f.toDouble()) - kotlin.math.ln(20.0)) /
                (kotlin.math.ln(20000.0) - kotlin.math.ln(20.0))
            val x = (w * t).toFloat()
            drawLine(color = gridColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 0.8f)
        }
        // 曲线
        val path = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { i, db ->
            val x = w * i / (samples.size - 1).toFloat()
            val y = padV + (h - padV * 2) * (1f - ((db + span) / (2 * span))).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = ScColors.Primary, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}


/**
 * 参量均衡的「实际频响曲线」卡片（2026-11-14）。
 *
 * 单独成卡、放在 EQ 面板卡**外面**（由 `ScEqDetailContent` 同级渲染）——
 * 放进面板里会形成"卡中卡"，阴影叠加、层级含混。
 * 曲线按段类型/频率/Q 用 [EqMath.responseAt] 精算，随参数实时更新。
 */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ScAutoEqCard(viewModel: MainViewModel) {
    val enabled by viewModel.autoEqEnabled.collectAsStateWithLifecycle()
    val mode by viewModel.autoEqMode.collectAsStateWithLifecycle()
    val preGain by viewModel.autoEqPreGain.collectAsStateWithLifecycle()
    val source by viewModel.autoEqSource.collectAsStateWithLifecycle()
    val autoEqFreqs by viewModel.autoEqFreqs.collectAsStateWithLifecycle()
    val autoEqGains by viewModel.autoEqGains.collectAsStateWithLifecycle()
    val autoEqQs by viewModel.autoEqQs.collectAsStateWithLifecycle()
    val autoEqTypes by viewModel.autoEqTypes.collectAsStateWithLifecycle()
    val library by viewModel.autoEqLibrary.collectAsStateWithLifecycle()
    // 卡片内曲线 = PEQ + AutoEq 合成响应（实际听到的总响应）
    val peqFreqs by viewModel.eqBandFreqs.collectAsStateWithLifecycle()
    val peqGains by viewModel.eqBandGains.collectAsStateWithLifecycle()
    val peqQs by viewModel.eqBandQs.collectAsStateWithLifecycle()
    val peqTypes by viewModel.eqBandTypes.collectAsStateWithLifecycle()
    val autoEqBands = remember(enabled, autoEqGains, autoEqQs, autoEqFreqs, autoEqTypes) {
        if (!enabled) emptyList() else autoEqFreqs.indices.map { i ->
            com.jiangjie.Sonicraft.AutoEqParser.Band(
                freq = autoEqFreqs[i],
                gain = autoEqGains.getOrElse(i) { 0f },
                q = autoEqQs.getOrElse(i) { 0.707f },
                type = autoEqTypes.getOrElse(i) { 0 },
            )
        }
    }

    var showAutoEqDialog by remember { mutableStateOf(false) }
    var autoEqText by remember { mutableStateOf("") }
    var autoEqMsg by remember { mutableStateOf<String?>(null) }
    var autoEqOk by remember { mutableStateOf<Boolean?>(null) }
    var autoEqFileName by remember { mutableStateOf<String?>(null) }
    // 库折叠：默认展开；条目多时限高滚动，不拉长页面
    var libExpanded by remember { mutableStateOf(true) }
    // 库多选（对齐机架脚本库：长按进选择，顶部出现删除/完成）
    // 折叠默认跟随开关：开=展开、关=收起；点标题行可手动展开/收起
    val autoEqExpanded = rememberExpandedFollowing(enabled, "autoeq_card_expanded")
    var libSelectMode by remember { mutableStateOf(false) }
    var libSelected by remember { mutableStateOf(setOf<String>()) }

    val autoEqCtx = androidx.compose.ui.platform.LocalContext.current
    val pickAutoEqFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val txt = runCatching {
                autoEqCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (txt.isNullOrBlank()) {
                autoEqOk = false
                autoEqMsg = "读取文件失败（可能不是文本文件或编码不支持）"
            } else {
                // SAF 的 lastPathSegment 是 document id，必须查 DISPLAY_NAME 才是文件名
                val displayName = runCatching {
                    autoEqCtx.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                }.getOrNull()
                autoEqFileName = (displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "AutoEq")
                    .substringBeforeLast('.').ifBlank { "AutoEq" }
                autoEqText = txt
                autoEqMsg = null
                autoEqOk = null
                showAutoEqDialog = true
            }
        }
    }

    com.jiangjie.Sonicraft.ui.components.ScCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // 头部**占满整行**（含左右内边距）都可点：不依赖点标题文字
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { autoEqExpanded.value = !autoEqExpanded.value }
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "AutoEq 校正",
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = ScColors.TextMain,
                )
                Spacer(Modifier.weight(1f))
                com.jiangjie.Sonicraft.ui.components.ScSwitch(
                    checked = enabled,
                    // ScSwitch 的回调无参：用当前值取反（与其它页面写法一致）
                    onCheckedChange = { viewModel.setAutoEqEnabled(!enabled) },
                )
                Text(
                    text = if (autoEqExpanded.value) "▾" else "▸",
                    fontSize = 13.sp,
                    color = ScColors.TextSub,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (autoEqExpanded.value) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(text = "导入 AutoEq 结果", fontSize = 12.sp, color = ScColors.TextMain)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    pickAutoEqFile.launch(
                        arrayOf(
                            "text/plain",
                            "text/csv",
                            "text/comma-separated-values",
                            "application/octet-stream",
                        ),
                    )
                }) {
                    Text(text = "从文件导入", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    autoEqText = ""
                    autoEqMsg = null
                    autoEqFileName = null
                    showAutoEqDialog = true
                }) {
                    Text(text = "粘贴导入", fontSize = 12.sp)
                }
            }
            autoEqMsg?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = if (autoEqOk == false) Color(0xFFB00020) else ScColors.Primary,
                )
            }

            // ---- 实际频响曲线（从独立卡并入本卡）：PEQ + AutoEq 合成响应 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "实际频响曲线",
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = ScColors.TextMain,
                )
                Text(
                    text = if (enabled) "PEQ + AutoEq" else "仅 PEQ（AutoEq 关）",
                    fontSize = 10.sp,
                    color = ScColors.TextSub,
                )
            }
            EqResponseChart(
                freqs = peqFreqs,
                gains = peqGains,
                qs = peqQs,
                types = peqTypes,
                extra = autoEqBands,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
            Text(
                text = "20 Hz — 20 kHz · PEQ 与 AutoEq 两级串联（dB 相加）· 不含预增益",
                fontSize = 10.sp,
                color = ScColors.TextSub,
            )

            // ---- 库（与「机架脚本库」同构：计数 + 列表 + 点按装载 + 删除）----
            if (library.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { libExpanded = !libExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (libSelectMode) "已选 ${libSelected.size} 项" else
                                "库 · ${library.size}/${viewModel.autoEqLibraryMax} 条" +
                                    " · 点按装载 / 长按多选",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                        Text(
                            text = if (libExpanded) " ▾" else " ▸",
                            fontSize = 11.sp,
                            color = ScColors.TextSub,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (libSelectMode) {
                        Text(
                            text = "删除所选",
                            fontSize = 11.sp,
                            color = Color(0xFFE5484D),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    libSelected.forEach { viewModel.deleteAutoEqCurve(it) }
                                    libSelected = emptySet()
                                    libSelectMode = false
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Text(
                            text = "完成",
                            fontSize = 11.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    libSelected = emptySet()
                                    libSelectMode = false
                                }
                                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                        )
                    } else {
                        Text(
                            text = "存当前入库",
                            fontSize = 11.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { viewModel.saveCurrentAutoEqToLibrary() }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                if (libExpanded) {
                    // 库列表限高 + 内部滚动：条目变多时不允许无限拉长整页
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 216.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                library.forEach { item ->
                    val checked = item.name in libSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (checked) Color(0xFFE6F4F4) else ScColors.Inset)
                            .combinedClickable(
                                onClick = {
                                    if (libSelectMode) {
                                        libSelected = if (checked) libSelected - item.name
                                        else libSelected + item.name
                                    } else {
                                        viewModel.applyAutoEqCurve(item.name)
                                    }
                                },
                                onLongClick = {
                                    libSelectMode = true
                                    libSelected = libSelected + item.name
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        // 缩略曲线：一眼认出是哪副耳机（点按装载前先看形状）
                        EqResponseChart(
                            freqs = item.freqs,
                            gains = item.gains,
                            qs = item.qs,
                            types = item.types,
                            modifier = Modifier
                                .width(64.dp)
                                .height(34.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                fontSize = 12.sp,
                                color = ScColors.TextMain,
                            )
                            Text(
                                text = "${item.mode} 段 · preamp ${"%+.1f".format(item.preampDb)} dB" +
                                    if (item.name == source) " · 当前" else "",
                                fontSize = 10.sp,
                                color = ScColors.TextSub,
                            )
                        }
                        if (!libSelectMode) {
                            Text(
                                text = "删除",
                                fontSize = 11.sp,
                                color = Color(0xFFE5484D),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { viewModel.deleteAutoEqCurve(item.name) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    }
                }
                }
            }
            if (showAutoEqDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showAutoEqDialog = false },
                    confirmButton = {
                        TextButton(onClick = {
                            val res = viewModel.applyAutoEq(autoEqText, saveAsPreset = autoEqFileName)
                            autoEqOk = res.ok
                            autoEqMsg = res.message
                            if (res.ok) {
                                showAutoEqDialog = false
                                autoEqFileName = null
                            }
                        }) { Text(text = "应用") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAutoEqDialog = false }) { Text(text = "取消") }
                    },
                    title = {
                        Text(
                            text = autoEqFileName?.let { "AutoEq 导入 · $it" } ?: "粘贴 AutoEq ParametricEQ.txt",
                            fontSize = 15.sp,
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = autoEqText,
                                onValueChange = { autoEqText = it },
                                placeholder = {
                                    Text(
                                        text = "支持三种 AutoEq 导出：\n" +
                                            "· ParametricEQ.txt —— Filter 1: ON PK Fc 31 Hz Gain 2.5 dB Q 0.70\n" +
                                            "· GraphicEQ.txt —— GraphicEQ: 20 -0.2; 21 -0.2; …\n" +
                                            "· 结果 CSV —— 取其中 equalization 列",
                                        fontSize = 11.sp,
                                    )
                                },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val previewInfo = remember(autoEqText, autoEqFreqs) {
                                val p = com.jiangjie.Sonicraft.AutoEqParser.parse(autoEqText)
                                if (p != null) {
                                    "预览：${p.bands.size} 段（参数化）" +
                                        " · preamp ${"%+.1f".format(p.preampDb)} dB" +
                                        if (p.skipped > 0) " · 跳过 ${p.skipped} 段" else ""
                                } else {
                                    val curve = com.jiangjie.Sonicraft.AutoEqParser.parseGraphic(autoEqText)
                                        ?: com.jiangjie.Sonicraft.AutoEqParser.parseCsvCurve(autoEqText)
                                    if (curve == null) {
                                        null
                                    } else {
                                        val bands = com.jiangjie.Sonicraft.AutoEqParser
                                            .curveToBands(curve, autoEqFreqs)
                                        val peak = curve.maxOfOrNull { it.second } ?: 0f
                                        val pre = if (peak > 0f) -peak else 0f
                                        "预览：曲线映射到 ${bands.size} 段（Q=1.41）" +
                                            " · preamp ${"%+.1f".format(pre)} dB"
                                    }
                                }
                            }
                            if (previewInfo != null) {
                                Text(text = previewInfo, fontSize = 11.sp, color = ScColors.TextSub)
                            } else if (autoEqText.isNotBlank()) {
                                Text(
                                    text = "未识别到 AutoEq 格式 —— 支持 ParametricEQ.txt、GraphicEQ.txt 与结果 CSV",
                                    fontSize = 11.sp,
                                    color = Color(0xFFB00020),
                                )
                            }
                        }
                    },
                )
            }
            }
            }
        }
    }
}
