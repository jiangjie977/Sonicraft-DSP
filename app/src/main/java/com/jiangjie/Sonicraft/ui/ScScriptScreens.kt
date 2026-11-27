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

// 脚本机架 + 脚本编辑器（从 ScScreens.kt 拆出，纯移动，无逻辑变更）。

private val SCVM_KEYWORDS = setOf(
    "void", "float", "int", "if", "else", "while", "for",
    "break", "continue", "return", "init", "sample",
)

private val SCVM_BUILTINS = setOf(
    "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "log", "log10",
    "pow", "sqrt", "abs", "floor", "ceil", "round", "min", "max", "tanh",
    "fmod", "clamp", "sign", "saturate",
)

private fun isScriptGlobal(w: String): Boolean =
    w == "spl0" || w == "spl1" || w == "srate" ||
        (w.startsWith("slider") && w.length > 6 && w.substring(6).all { it.isDigit() })

/**
 * 把脚本文本转成带颜色的 AnnotatedString。
 * 逐字符扫描：脚本只有几百行量级，每次输入全量重算足够快。
 */
fun highlightScript(code: String): AnnotatedString {
    val b = AnnotatedString.Builder(code)
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        if (c == '/' && i + 1 < n && code[i + 1] == '/') {
            val end = code.indexOf('\n', i).let { if (it < 0) n else it }
            b.addStyle(SpanStyle(color = ScriptColors.Comment), i, end)
            i = end
            continue
        }
        if (c == '/' && i + 1 < n && code[i + 1] == '*') {
            val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
            b.addStyle(SpanStyle(color = ScriptColors.Comment), i, end)
            i = end
            continue
        }
        if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit())) {
            var j = i
            while (j < n && (code[j].isDigit() || code[j] == '.')) j++
            b.addStyle(SpanStyle(color = ScriptColors.Number), i, j)
            i = j
            continue
        }
        // 标识符：关键字 / 内建函数 / 内置全局量
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val w = code.substring(i, j)
            val color = when {
                w in SCVM_KEYWORDS -> ScriptColors.Keyword
                w in SCVM_BUILTINS -> ScriptColors.Builtin
                isScriptGlobal(w) -> ScriptColors.Global
                else -> null
            }
            if (color != null) b.addStyle(SpanStyle(color = color), i, j)
            i = j
            continue
        }
        i++
    }
    return b.toAnnotatedString()
}

internal class ScriptHighlightTransformation(private val errorLine: Int = -1) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = highlightScript(text.text)
        if (errorLine <= 0) return TransformedText(annotated, OffsetMapping.Identity)
        // 计算出错行的字符区间（行号从 1 开始）
        var line = 1
        var start = -1
        var end = -1
        var i = 0
        while (i <= text.length) {
            if (line == errorLine && start < 0) start = i
            if (i == text.length || text[i] == '\n') {
                if (line == errorLine) {
                    end = i
                    break
                }
                line++
            }
            i++
        }
        if (start < 0 || end < start) return TransformedText(annotated, OffsetMapping.Identity)
        return TransformedText(
            buildAnnotatedString {
                append(annotated)
                addStyle(
                    SpanStyle(color = Color(0xFFFF6B6B), background = Color(0x33E5484D)),
                    start,
                    end,
                )
            },
            OffsetMapping.Identity,
        )
    }
}

/** 脚本机架（Script Rack）：音效库「机架」详情页的主体内容（自设置页迁入）。 */
@Composable
internal fun ScScriptRackSection(
    viewModel: MainViewModel,
    showEditor: Boolean = true,
    showTitle: Boolean = true,
) {
    // ============ 脚本机架（Script Rack / SCVM）============
    val scriptEnabled by viewModel.scriptEffectEnabled.collectAsStateWithLifecycle()
    val scriptSrc by viewModel.scriptSource.collectAsStateWithLifecycle()
    val sliderMask by viewModel.scriptSliderMask.collectAsStateWithLifecycle()
    val sliderValues by viewModel.scriptSliderValues.collectAsStateWithLifecycle()
    val sliderRanges by viewModel.scriptSliderRanges.collectAsStateWithLifecycle()
    val scriptError by viewModel.scriptError.collectAsStateWithLifecycle()
    val scriptControls by viewModel.scriptControls.collectAsStateWithLifecycle()
    val toggleValues by viewModel.scriptToggleValues.collectAsStateWithLifecycle()
    val selectValues by viewModel.scriptSelectValues.collectAsStateWithLifecycle()
    val listValues by viewModel.scriptListValues.collectAsStateWithLifecycle()
    val scriptLane by viewModel.scriptLane.collectAsStateWithLifecycle()
    val laneOn by viewModel.scriptLaneOn.collectAsStateWithLifecycle()
    // 未保存过脚本时回填内置示例（排版规范、可直接编辑），避免出现空白编辑器
    var scriptDraft by remember(scriptLane) {
        mutableStateOf(if (scriptSrc.isBlank()) ScriptDefaults.DEFAULT_SCRIPT else scriptSrc)
    }
    // 保存前校验：出错行用于红色高亮，错误串用于弹窗
    var scriptErrLine by remember { mutableStateOf(-1) }
    var scriptErrDialog by remember { mutableStateOf<String?>(null) }
    // 脚本文件管理：导入（SAF 选择文件）/ 导出（SAF 保存）
    val appCtx = LocalContext.current
    val importScript = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                scriptDraft = text
                viewModel.applyScriptSource(text)
            }
        }
    }
    val exportScript = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                appCtx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(scriptDraft)
                }
            }
        }
    }
    if (showTitle) ScSectionTitle("参数")
    ScCard {
        Column {
        if (showEditor) {
            ScSettingBlock(
                title = "脚本",
                subtitle = "void sample() 读 spl0/spl1 并写回；参数用 slider/toggle/select/list/button 声明",
                showDivider = false,
            ) {
                Column {
                    BasicTextField(
                        value = scriptDraft,
                        onValueChange = {
                            scriptDraft = it
                            scriptErrLine = -1  // 编辑即清除旧错误高亮
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = ScriptColors.Text,
                        ),
                        cursorBrush = SolidColor(ScColors.Primary),
                        visualTransformation = ScriptHighlightTransformation(scriptErrLine),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ScriptColors.Background)
                                    .padding(10.dp),
                            ) { inner() }
                        },
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "保存",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScColors.Inset)
                                .clickable {
                                    val err = viewModel.validateScript(scriptDraft)
                                    if (err.isBlank()) {
                                        scriptErrLine = -1
                                        scriptErrDialog = null
                                        viewModel.saveScriptDraft(scriptDraft)
                                    } else {
                                        scriptErrLine = Regex("""^line (\d+)""")
                                            .find(err)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                                        scriptErrDialog = err
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Text(
                            text = "应用脚本",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScColors.Inset)
                                .clickable { viewModel.applyScriptSource(scriptDraft) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                }
                                scriptErrDialog?.let { msg ->
                                AlertDialog(
                                onDismissRequest = { scriptErrDialog = null },
                                title = { Text("脚本有错误，未保存", fontSize = 15.sp) },
                                text = { Text(msg, fontSize = 12.sp) },
                                confirmButton = {
                                    Text(
                                        text = "知道了",
                                        fontSize = 13.sp,
                                        color = ScColors.Primary,
                                        modifier = Modifier
                                            .clickable { scriptErrDialog = null }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                },
                                )
                                }
                                if (scriptError.isNotEmpty()) {
                        Text(
                            text = "编译错误：$scriptError",
                            color = Color(0xFFE5484D),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (scriptSrc.isNotBlank()) {
                        Text(
                            text = "✓ 脚本已编译",
                            color = Color(0xFF2E9E6B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
            // 脚本文件管理：导入 / 导出 / 示例库
            if (showEditor) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "导入",
                    fontSize = 12.sp,
                    color = ScColors.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ScColors.Inset)
                        .clickable { importScript.launch(arrayOf("text/plain", "*/*")) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    text = "导出",
                    fontSize = 12.sp,
                    color = ScColors.Primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ScColors.Inset)
                        .clickable { exportScript.launch("script.scvm") }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                var sampleMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "示例 ▾",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { sampleMenu = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    DropdownMenu(
                        expanded = sampleMenu,
                        onDismissRequest = { sampleMenu = false },
                    ) {
                        ScriptSamples.forEach { sample ->
                            DropdownMenuItem(
                                text = { Text(sample.title, fontSize = 13.sp) },
                                onClick = {
                                    scriptDraft = sample.code
                                    // 示例即应用：下方控件区立即按新脚本重建
                                    viewModel.applyScriptSource(sample.code)
                                    sampleMenu = false
                                },
                            )
                        }
                    }
                }
            }
            }
            // 脚本参数滑块（sliderN 自动生成）
            for (i in 0 until 8) {
                if ((sliderMask and (1 shl i)) != 0) {
                    ScSettingBlock(
                        title = "参数 slider${i + 1}",
                        subtitle = "%.2f".format(sliderValues[i]),
                        showDivider = false,
                    ) {
                        val lo = sliderRanges[i * 2]
                        val hi = sliderRanges[i * 2 + 1]
                        // 精确输入框：拖动之外也支持键入数值（失去焦点/回车提交）
                        var txt by remember(i, sliderValues[i]) {
                            mutableStateOf("%.2f".format(sliderValues[i]))
                        }
                        val focus = LocalFocusManager.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = sliderValues[i].coerceIn(lo, hi),
                                onValueChange = { viewModel.setScriptSlider(i, it) },
                                valueRange = lo..hi,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = txt,
                                onValueChange = { s ->
                                    txt = s.filter { it.isDigit() || it == '.' || it == '-' }
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        txt.toFloatOrNull()?.let { v ->
                                            viewModel.setScriptSlider(i, v.coerceIn(lo, hi))
                                        }
                                        focus.clearFocus()
                                    },
                                ),
                                modifier = Modifier.width(96.dp).padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            // 脚本声明的高级控件（toggle / select / button）
            for (ctl in scriptControls) {
                when (ctl.type) {
                    "toggle" -> ScSettingBlock(
                        title = ctl.label,
                        subtitle = ctl.name,
                        showDivider = false,
                    ) {
                        ScSwitch(
                            checked = toggleValues[ctl.index] > 0.5f,
                            onCheckedChange = {
                                viewModel.setScriptToggle(ctl.index, toggleValues[ctl.index] <= 0.5f)
                            },
                        )
                    }
                    "select" -> {
                        var expanded by remember(ctl.name) { mutableStateOf(false) }
                        ScSettingBlock(
                            title = ctl.label,
                            subtitle = ctl.options.getOrElse(selectValues[ctl.index]) { ctl.name },
                            showDivider = false,
                        ) {
                            Box {
                                Text(
                                    text = ctl.options.getOrElse(selectValues[ctl.index]) { "选择" } + " ▾",
                                    fontSize = 13.sp,
                                    color = ScColors.Primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ScColors.Inset)
                                        .clickable { expanded = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    ctl.options.forEachIndexed { idx, optName ->
                                        DropdownMenuItem(
                                            text = { Text(optName, fontSize = 13.sp) },
                                            onClick = {
                                                viewModel.setScriptSelect(ctl.index, idx)
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "list" -> ScSettingBlock(
                        title = ctl.label,
                        subtitle = "多选（位掩码 ${listValues[ctl.index]}）",
                        showDivider = false,
                    ) {
                        Column {
                            ctl.options.forEachIndexed { idx, optName ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = optName,
                                        fontSize = 13.sp,
                                        color = ScColors.TextMain,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ScSwitch(
                                        checked = (listValues[ctl.index] and (1 shl idx)) != 0,
                                        onCheckedChange = {
                                            viewModel.setScriptList(
                                                ctl.index,
                                                listValues[ctl.index] xor (1 shl idx),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    "button" -> ScSettingBlock(
                        title = ctl.label,
                        subtitle = "点击触发（仅一个样本）",
                        showDivider = false,
                    ) {
                        Text(
                            text = "触发",
                            fontSize = 12.sp,
                            color = ScColors.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScColors.Inset)
                                .clickable { viewModel.pressScriptButton(ctl.index) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    // ============ 音频链路 ============
}

/**
 * 机架详情页主体（按草图）：
 * 顶部总开关在页头；下面槽位 1/2/3 每行 = 脚本名 / 参数 / 开关；再下面是「脚本库」列表。
 * 参数区（编辑器 + 控件）点某槽位的「参数」后展开，作用于该槽位。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ScRackBody(
    viewModel: MainViewModel,
    onOpenEditor: () -> Unit = {},
    onOpenBlankEditor: () -> Unit = {},
) {
    val lane by viewModel.scriptLane.collectAsStateWithLifecycle()
    val laneOnMap by viewModel.scriptLaneOnMap.collectAsStateWithLifecycle()
    val labels by viewModel.scriptLaneLabels.collectAsStateWithLifecycle()
    val laneSources by viewModel.scriptLaneSources.collectAsStateWithLifecycle()
    val laneErrors by viewModel.scriptLaneErrors.collectAsStateWithLifecycle()
    val laneParallel by viewModel.scriptLaneParallel.collectAsStateWithLifecycle()
    val laneWet by viewModel.scriptLaneWet.collectAsStateWithLifecycle()
    val customScripts by viewModel.customScripts.collectAsStateWithLifecycle()
    val laneOrder by viewModel.scriptLaneOrder.collectAsStateWithLifecycle()
    val rackWet by viewModel.scriptRackWet.collectAsStateWithLifecycle()
    // 拖拽换序（长按槽位标题行）：dragPos = 正在拖的位置，dragDy = 累计位移
    var dragPos by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }
    val rowDensity = LocalDensity.current.density
    val rowHpx = with(LocalDensity.current) { 118.dp.toPx() }
    // 进入机架页时重算编译状态（VM 构造时引擎尚未创建，初始值可能为空）
    LaunchedEffect(Unit) { viewModel.refreshRackMeta() }
    // 长按脚本 → 发送到槽位；发送后目标槽位绿色高亮 0.9s
    var sendTarget by remember { mutableStateOf<com.jiangjie.Sonicraft.ScriptSample?>(null) }
    var flashLane by remember { mutableStateOf(-1) }
    // 槽位手工重命名（长按名称框触发；-1 = 未打开）
    var renameLane by remember { mutableStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    // 多选批量删除（长按自定义脚本进入；selSet 存自定义脚本序号）
    var selMode by remember { mutableStateOf(false) }
    var selSet by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(flashLane) {
        if (flashLane >= 0) {
            delay(900)
            flashLane = -1
        }
    }
    // 槽位行「脚本有错」红色呼吸（与编辑器错误横幅同一节奏）
    val slotBreathe = rememberInfiniteTransition(label = "slotErr")
    val slotGlow by slotBreathe.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "slotGlow",
    )
    var paramsOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var libQuery by remember { mutableStateOf("") }
    var libExpanded by remember { mutableStateOf(true) }  // 脚本库默认展开（长按入口要可见）
    // 脚本库工具：导入 / 导出（SAF）
    val rackCtx = LocalContext.current
    val importScript = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                rackCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) viewModel.applyScriptSource(text)
        }
    }
    val exportScript = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                rackCtx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(laneSources.getOrElse(lane) { "" })
                }
            }
        }
    }

    ScSectionTitle("槽位")
    ScCard {
        Column {
            // 按用户拖拽设定的顺序渲染（顺序即串联链先后）
            for (pos in laneOrder.indices) {
                val i = laneOrder[pos]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    // 标题行：左「槽位 N」，并联时右侧直接给湿声滑杆（不必再进参数弹窗）
                    // 长按标题行可上下拖动换序（松手后按位移量换算目标位置）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = if (dragPos == pos) (dragDy / rowDensity).dp else 0.dp)
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragPos = pos
                                        dragDy = 0f
                                    },
                                    onDrag = { _, d -> dragDy += d.y },
                                    onDragEnd = {
                                        val step = (dragDy / rowHpx).roundToInt()
                                        if (step != 0) {
                                            // 兜底：换序失败也不能拖垮 App
                                            runCatching {
                                                viewModel.moveSlot(
                                                    pos,
                                                    (pos + step).coerceIn(0, laneOrder.size - 1),
                                                )
                                            }
                                        }
                                        dragPos = -1
                                        dragDy = 0f
                                    },
                                    onDragCancel = {
                                        dragPos = -1
                                        dragDy = 0f
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "≡",
                            fontSize = 14.sp,
                            color = ScColors.TextSub,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = "槽位 ${pos + 1}",
                            fontSize = 13.sp,
                            color = ScColors.TextMain,
                            modifier = Modifier.weight(1f),
                        )
                        if (laneParallel.getOrElse(i) { false }) {
                            // 滑块已移到槽位列表下方（避免与拖拽手势抢指针）
                            val w = laneWet.getOrElse(i) { 1f / 3f }
                            Text(
                                text = "并联 ${(w * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = ScColors.TextSub,
                            )
                        }
                    }
                    // 输入框 / 参数 / 开关 同一行，垂直居中对齐
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                        // 脚本选择：点击弹出带搜索的下拉列表框（输入即过滤，命中处标红）
                        var pickerOpen by remember(i) { mutableStateOf(false) }
                        var query by remember(i) { mutableStateOf("") }
                        Box {
                            val slotErr = laneErrors.getOrElse(i) { "" }
                            Text(
                                text = labels.getOrElse(i) { "未选择脚本" } +
                                    if (slotErr.isNotEmpty()) "  ⚠ 已旁路" else "",
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = when {
                                    slotErr.isNotEmpty() -> Color(0xFFB3261E)
                                    lane == i -> ScColors.Primary
                                    else -> ScColors.TextMain
                                },
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        when {
                                            // 脚本编译失败：红色呼吸
                                            slotErr.isNotEmpty() -> Modifier
                                                .background(
                                                    Color(0xFFFDECEA)
                                                        .copy(alpha = 0.28f + 0.42f * slotGlow),
                                                )
                                                .border(
                                                    1.dp,
                                                    Color(0xFFE5484D)
                                                        .copy(alpha = 0.35f + 0.65f * slotGlow),
                                                    RoundedCornerShape(6.dp),
                                                )
                                            // 刚收到「发送到槽位」：绿色高亮 0.9s
                                            flashLane == i -> Modifier
                                                .background(ScColors.Primary.copy(alpha = 0.14f))
                                                .border(
                                                    1.5.dp,
                                                    ScColors.Primary,
                                                    RoundedCornerShape(6.dp),
                                                )
                                            else -> Modifier.border(
                                                1.dp,
                                                ScColors.Inset,
                                                RoundedCornerShape(6.dp),
                                            )
                                        },
                                    )
                                    // 点击 = 选脚本；长按 = 手工重命名该槽位
                                    .combinedClickable(
                                        onClick = { pickerOpen = true },
                                        onLongClick = {
                                            renameLane = i
                                            renameText = labels.getOrElse(i) { "" }
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                            if (pickerOpen) {
                                Dialog(onDismissRequest = { pickerOpen = false }) {
                                    ScCard {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            OutlinedTextField(
                                                value = query,
                                                onValueChange = { query = it },
                                                singleLine = true,
                                                placeholder = {
                                                    Text("搜索脚本", fontSize = 12.sp)
                                                },
                                                textStyle = TextStyle(fontSize = 13.sp),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            val hits = ScriptSamples.filter {
                                                matchesScript(it.title, query)
                                            }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 360.dp)
                                                    .verticalScroll(rememberScrollState()),
                                            ) {
                                                if (hits.isEmpty()) {
                                                    Text(
                                                        text = "无匹配脚本",
                                                        fontSize = 13.sp,
                                                        modifier = Modifier.padding(12.dp),
                                                    )
                                                }
                                                hits.forEach { sample ->
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                viewModel.switchScriptLane(i)
                                                                viewModel.applyScriptSource(sample.code)
                                                                pickerOpen = false
                                                                query = ""
                                                            }
                                                            .padding(
                                                                horizontal = 8.dp,
                                                                vertical = 12.dp,
                                                            ),
                                                    ) {
                                                        Text(
                                                            text = highlightMatch(
                                                                sample.title,
                                                                query,
                                                            ),
                                                            fontSize = 13.sp,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 拓扑：串（主链）/ 并（取机架干声、按 1/3 累加）
                    val isPar = laneParallel.getOrElse(i) { false }
                    Text(
                        text = if (isPar) "并" else "串",
                        fontSize = 11.sp,
                        color = if (isPar) ScColors.Primary else ScColors.TextSub,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { viewModel.setScriptLaneParallel(i, !isPar) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Text(
                        text = "参数",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable {
                                viewModel.switchScriptLane(i)
                                paramsOpen = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Box(modifier = Modifier.padding(start = 10.dp)) {
                        ScSwitch(
                            checked = laneOnMap.getOrElse(i) { true },
                            onCheckedChange = {
                                viewModel.setScriptLaneOn(i, !laneOnMap.getOrElse(i) { true })
                            },
                        )
                    }
                    }
                }
            }
        }
    }

    // 机架干湿：0 = 原声，100% = 全效果（A/B 对比与"半湿"处理）
    ScCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "机架干湿",
                    fontSize = 12.sp,
                    color = ScColors.TextMain,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(rackWet * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = ScColors.Primary,
                )
            }
            Slider(
                value = rackWet.coerceIn(0f, 1f),
                onValueChange = { viewModel.setScriptRackWet(it) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 并联比例：固定在槽位列表下方（作用于当前并联槽位）
    val wetLane = if (laneParallel.getOrElse(lane) { false }) {
        lane
    } else {
        laneParallel.indexOfFirst { it }
    }
    if (wetLane >= 0) {
        val w = laneWet.getOrElse(wetLane) { 1f / 3f }
        ScCard {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "槽位 ${laneOrder.indexOf(wetLane) + 1} 并联比例",
                        fontSize = 12.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(w * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                    )
                }
                Slider(
                    value = w.coerceIn(0f, 1f),
                    onValueChange = { viewModel.setScriptLaneWet(wetLane, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    ScSectionTitle("脚本库")
    ScCard {
        Column {
            // 与卷积混响「脉冲库」一致：标题 + 副标题 + 检索框 + 药丸按钮 + 计数行
            Text(
                text = "${ScriptSamples.size} 个脚本 · 点按装载到槽位 ${lane + 1}",
                fontSize = 11.sp,
                color = ScColors.TextSub,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp),
            )
            BasicTextField(
                value = libQuery,
                onValueChange = { libQuery = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = ScColors.TextMain),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ScColors.Inset)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (libQuery.isEmpty()) {
                            Text("检索脚本…", fontSize = 12.sp, color = ScColors.TextSub)
                        }
                        inner()
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScriptPill("导入", Modifier.weight(1f)) {
                    importScript.launch(arrayOf("text/plain", "*/*"))
                }
                ScriptPill("导出", Modifier.weight(1f)) { exportScript.launch("script.scvm") }
                ScriptPill("编辑", Modifier.weight(1f)) { onOpenEditor() }
                ScriptPill("新建", Modifier.weight(1f)) { onOpenBlankEditor() }
                ScriptPill("存入库", Modifier.weight(1f)) { viewModel.saveCurrentToLibrary() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selMode) {
                        "已选 ${selSet.size} 项"
                    } else {
                        "全部脚本 · ${ScriptSamples.size + customScripts.size}"
                    },
                    fontSize = 11.sp,
                    color = ScColors.TextSub,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (selMode) {
                    Text(
                        text = "删除所选",
                        fontSize = 11.sp,
                        color = Color(0xFFE5484D),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                viewModel.deleteCustoms(selSet)
                                selSet = emptySet()
                                selMode = false
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        text = "完成",
                        fontSize = 11.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                selSet = emptySet()
                                selMode = false
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        text = "管理",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { selMode = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        text = if (libExpanded) "收起 ▲" else "展开 ▼",
                        fontSize = 10.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { libExpanded = !libExpanded }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (libExpanded) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val rowShape = RoundedCornerShape(ScRadius.Chip)
                // 分组渲染：用来源标记而不是「代码相等」判重（自定义常是内置示例的副本）
                val hits = (ScriptSamples.map { false to it } + customScripts.map { true to it })
                    .filter { matchesScript(it.second.title, libQuery) }
                var lastGroup = -1
                hits.forEach { (isCustom, sample) ->
                    val groupId = if (isCustom) 1 else 0
                    if (groupId != lastGroup) {
                        lastGroup = groupId
                        val groupName = if (isCustom) "自定义脚本" else "内置脚本"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "$groupName · " + hits.count { it.first == isCustom },
                                fontSize = 11.sp,
                                color = ScColors.TextSub,
                            )
                        }
                    }
                    val active = laneSources.getOrElse(lane) { "" }.trim() == sample.code.trim()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (active) {
                                    Modifier
                                        .padding(horizontal = 6.dp)
                                        .background(ScColors.Inset, rowShape)
                                        .border(
                                            1.5.dp,
                                            Brush.linearGradient(
                                                listOf(ScColors.Primary, ScColors.PrimaryLight),
                                            ),
                                            rowShape,
                                        )
                                } else {
                                    Modifier
                                },
                            )
                            .combinedClickable(
                                onClick = {
                                    if (selMode) {
                                        if (isCustom) {
                                            val idx = customScripts.indexOfFirst { it === sample }
                                            selSet = if (idx in selSet) selSet - idx else selSet + idx
                                        }
                                    } else {
                                        viewModel.switchScriptLane(lane)
                                        viewModel.applyScriptSource(sample.code)
                                    }
                                },
                                onLongClick = {
                                    if (isCustom) {
                                        selMode = true
                                        selSet = selSet + customScripts.indexOfFirst { it === sample }
                                    } else {
                                        sendTarget = sample
                                    }
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        if (selMode && isCustom) {
                            Text(
                                text = if (customScripts.indexOfFirst { it === sample } in selSet) "☑" else "☐",
                                fontSize = 15.sp,
                                color = if (customScripts.indexOfFirst { it === sample } in selSet) {
                                    ScColors.Primary
                                } else {
                                    ScColors.TextSub
                                },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(rowShape)
                                .then(
                                    if (active) Modifier.background(ScPrimaryBrush)
                                    else Modifier.background(ScColors.Inset),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "SC",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) Color.White else ScColors.TextSub,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        ) {
                            Text(
                                text = sample.title + if (active) "  ✓" else "",
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = ScColors.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    active -> "当前槽位脚本"
                                    isCustom -> "自定义"
                                    else -> "内置示例"
                                    },
                                    fontSize = 10.sp,
                                    color = ScColors.TextSub,
                                    )
                                    }
                                    // 自定义条目：右侧 ✕ 直接删除（内置示例不显示此按钮）
                                    if (isCustom) {
                                    Text(
                                    text = "✕",
                                    fontSize = 15.sp,
                                    color = ScColors.TextSub,
                                    modifier = Modifier
                                    .clickable { viewModel.deleteCustomAt(customScripts.indexOfFirst { it === sample }) }
                                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    )
                                    }
                                    }
                }
            }
        }
    }

    // 参数按钮 → 弹窗：只显示当前槽位脚本的参数控件（滑块 / 开关 / 下拉 / 多选 / 按钮）
    if (paramsOpen) {
        Dialog(onDismissRequest = { paramsOpen = false }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ScScriptRackSection(viewModel, showEditor = false, showTitle = false)
            }
        }
    }

    // 长按槽位名称 → 手工重命名（留空 = 恢复自动摘要）
    if (renameLane >= 0) {
        Dialog(onDismissRequest = { renameLane = -1 }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "重命名槽位 ${renameLane + 1}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "留空并保存 = 恢复自动摘要（脚本首行注释 / desc）",
                        fontSize = 10.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScriptPill("保存", Modifier.weight(1f)) {
                            viewModel.renameScriptLane(renameLane, renameText)
                            renameLane = -1
                        }
                        ScriptPill("恢复自动", Modifier.weight(1f)) {
                            viewModel.renameScriptLane(renameLane, "")
                            renameLane = -1
                        }
                        ScriptPill("取消", Modifier.weight(1f)) { renameLane = -1 }
                    }
                }
            }
        }
    }

    // 长按脚本库条目 → 发送到槽位 1/2/3（会覆盖该槽位现有脚本）
    sendTarget?.let { sample ->
        Dialog(onDismissRequest = { sendTarget = null }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(sample.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "发送到槽位",
                        fontSize = 11.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                    )
                    for (i in 0 until 3) {
                        val occupied = laneSources.getOrElse(i) { "" }.isNotBlank()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.switchScriptLane(i)
                                    viewModel.applyScriptSource(sample.code)
                                    flashLane = i
                                    sendTarget = null
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "槽位 ${i + 1}",
                                fontSize = 13.sp,
                                color = ScColors.TextMain,
                                modifier = Modifier.weight(1f),
                            )
                            if (i == lane) {
                                Text(
                                    text = "当前  ",
                                    fontSize = 11.sp,
                                    color = ScColors.Primary,
                                )
                            }
                            Text(
                                text = if (occupied) "覆盖" else "空",
                                fontSize = 11.sp,
                                color = ScColors.TextSub,
                            )
                        }
                    }
                    // 自定义脚本可删除（内置示例不可删）
                    if (ScriptSamples.none { it.code == sample.code }) {
                        Text(
                            text = "删除此脚本",
                            fontSize = 12.sp,
                            color = Color(0xFFE5484D),
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    viewModel.deleteCustomScript(sample.title)
                                    sendTarget = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        text = "取消",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { sendTarget = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // 编辑脚本 → 弹窗（编辑器 + 应用/保存），作用于当前槽位
    if (editOpen) {
        Dialog(onDismissRequest = { editOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                ScScriptRackSection(viewModel, showTitle = false)
            }
        }
    }
}

/** 脚本库工具按钮（对齐脉冲库的药丸样式）。 */
@Composable
internal fun ScriptPill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 44dp 最小点击高度（Android 无障碍建议值），宽度由调用方 weight 均分
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, ScColors.Inset, RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            color = ScColors.TextMain,
        )
    }
}

/** 下拉列表里把搜索命中的片段标红（参考图样式）。 */
private fun highlightMatch(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val idx = text.indexOf(query, ignoreCase = true)
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = Color(0xFFE5484D)), idx, idx + query.length)
    }
}

/** 脚本搜索用的拼音/英文提示（标题命中哪条用哪条）。 */
private val kScriptPinyin = mapOf(
    "音量" to "yinliang yl jingyin jy volume mute gain",
    "软削波" to "ruanxiaobo rxb qudong qd drive softclip saturation",
    "双二阶" to "shuangerjie sej ditong dt lvbo biquad lowpass",
    "延迟" to "yanchi yc hunhe hh delay dl",
    "块卷积" to "kuaijuanji kj juanji conv convolution",
    "EEL" to "eel yufa yf shili sl",
)

private fun scriptHaystack(title: String): String {
    val extra = kScriptPinyin.entries.firstOrNull { title.contains(it.key) }?.value ?: ""
    return (title + " " + extra).lowercase()
}

private fun subsequenceMatch(hay: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    var i = 0
    for (ch in hay) {
        if (i < needle.length && ch == needle[i]) i++
    }
    return i == needle.length
}

/** 标题子串 / 拼音全拼与首字母 / 字符顺序模糊；多关键词（空格分隔）需全部命中。 */
private fun matchesScript(title: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val toks = scriptHaystack(title).split(' ').filter { it.isNotEmpty() }
    return q.split(' ').filter { it.isNotEmpty() }.all { tok ->
        // 只在「单个拼音词」内做模糊，避免 yl 这种跨词误命中
        title.lowercase().contains(tok) ||
            toks.any { it.contains(tok) || subsequenceMatch(it, tok) }
    }
}

/**
 * 脚本编辑器整页（按截图）：
 * 顶栏（返回 / 应用 / 撤销 / 菜单）+ 行号 + 深色代码区 + 状态栏 + 符号键行。
 */
