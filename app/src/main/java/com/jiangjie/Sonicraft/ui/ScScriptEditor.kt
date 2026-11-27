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

// 脚本编辑器（从 ScScriptScreens.kt 拆出，纯移动，无逻辑变更）。

@Composable
internal fun ScScriptEditorPage(
    viewModel: MainViewModel,
    startBlank: Boolean = false,
    onBack: () -> Unit,
) {
    val lane by viewModel.scriptLane.collectAsStateWithLifecycle()
    val sources by viewModel.scriptLaneSources.collectAsStateWithLifecycle()
    val labels by viewModel.scriptLaneLabels.collectAsStateWithLifecycle()
    var draft by remember(lane, sources, startBlank) {
        mutableStateOf(if (startBlank) "" else sources.getOrElse(lane) { "" })
    }
    var menuOpen by remember { mutableStateOf(false) }
    var errLine by remember { mutableStateOf(-1) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf(false) }  // 保存后的提示（1.4s 自动消失）
    var codeSp by remember { mutableStateOf(12) }

    // 撤销 / 重做：历史放在 remember 持有的普通对象里。
    // 之前用局部 `var ... by remember { mutableStateOf(listOf) }` + 局部 fun，
    // 在事件 lambda 里读写时容易读到旧快照（表现为"点了没反应"）。
    val history = remember(lane) { EditorHistory() }
    var historyVersion by remember { mutableStateOf(0) }
    val canUndo = remember(historyVersion) { history.canUndo }
    val canRedo = remember(historyVersion) { history.canRedo }

    // 查找/替换、字号、帮助
    var findOpen by remember { mutableStateOf(false) }
    var fontOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replText by remember { mutableStateOf("") }
    var findMsg by remember { mutableStateOf("") }

    val lineCount = draft.count { it == '\n' } + 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScColors.Inset)
            .statusBarsPadding(),
    ) {
        // ---- 顶栏：返回 / 标题 / 运行 / 撤销 / 重做 / 更多 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorBarIcon(glyph = "←", color = ScColors.TextMain, enabled = true) { onBack() }
            Text(
                text = if (startBlank) "新建脚本" else "脚本编辑器",
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = ScColors.TextMain,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            EditorBarIcon(glyph = "▶", color = ScColors.Primary, enabled = true) {
                val err = viewModel.validateScript(draft)
                if (err.isBlank()) {
                    errMsg = null
                    errLine = -1
                    viewModel.applyScriptSource(draft)
                } else {
                    errMsg = err
                    errLine = Regex("""^line (\d+)""")
                        .find(err)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
            }
            EditorBarIcon(
                glyph = "↶",
                color = if (canUndo) ScColors.TextMain else ScColors.TextSub,
                enabled = canUndo,
            ) {
                history.undo(draft)?.let {
                    draft = it
                    errLine = -1
                    historyVersion++
                }
            }
            EditorBarIcon(
                glyph = "↷",
                color = if (canRedo) ScColors.TextMain else ScColors.TextSub,
                enabled = canRedo,
            ) {
                history.redo(draft)?.let {
                    draft = it
                    errLine = -1
                    historyVersion++
                }
            }
            // 保存 / 查找 提到顶栏（原来是 ⋮ 菜单里的二级入口）
            Text(
                text = "保存",
                fontSize = 11.sp,
                color = ScColors.Primary,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ScColors.Inset)
                    .clickable {
                        viewModel.saveScriptDraft(draft)
                        savedHint = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "查找",
                fontSize = 11.sp,
                color = ScColors.Primary,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ScColors.Inset)
                    .clickable { findOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box {
                EditorBarIcon(glyph = "⋮", color = ScColors.TextMain, enabled = true) {
                    menuOpen = true
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("文字大小", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            fontOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("帮助", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = false
                            helpOpen = true
                        },
                    )
                }
            }
        }

        // ---- 代码区 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ScriptColors.Background),
        ) {
            Column(modifier = Modifier.padding(start = 8.dp, top = 10.dp)) {
                for (n in 1..lineCount) {
                    Text(
                        text = "$n",
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF6E7681),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        history.push(draft); historyVersion++
                        draft = it
                        errLine = -1
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = codeSp.sp,
                        lineHeight = (codeSp + 6).sp,
                        color = ScriptColors.Text,
                    ),
                    cursorBrush = SolidColor(ScColors.Primary),
                    visualTransformation = ScriptHighlightTransformation(errLine),
                )
            }
        }

        // ---- 错误横幅（参考样张：浅红底 + 红边 + 右侧「关闭」，整条呼吸提醒）----
        errMsg?.let { msg ->
            val breathe = rememberInfiniteTransition(label = "errBanner")
            val glow by breathe.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "errGlow",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFDECEA).copy(alpha = 0.30f + 0.45f * glow))
                    .border(
                        1.dp,
                        Color(0xFFE5484D).copy(alpha = 0.35f + 0.65f * glow),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFB3261E),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "关闭",
                    fontSize = 12.sp,
                    color = Color(0xFFE5484D),
                    modifier = Modifier
                        .clickable {
                            errMsg = null
                            errLine = -1
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // ---- 保存提示（1.4s 自动消失）----
        if (savedHint) {
            LaunchedEffect(savedHint) {
                delay(1400)
                savedHint = false
            }
            Popup(alignment = Alignment.Center) {
                Text(
                    text = "脚本已保存",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC1F1F1F))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        // ---- 状态栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = labels.getOrElse(lane) { "未选择脚本" },
                fontSize = 11.sp,
                color = ScColors.TextSub,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "$lineCount 行 · ${codeSp}sp", fontSize = 11.sp, color = ScColors.TextSub)
        }

        // ---- 符号键行 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("TAB" to "    ", "(" to "(", ")" to ")", "[" to "[", "]" to "]",
                "," to ",", "." to ".").forEach { (label, insert) ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = ScColors.TextMain,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ScColors.Inset)
                        .clickable {
                            history.push(draft)
                            historyVersion++
                            draft = draft + insert
                        }
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    // ---- 查找 / 替换 ----
    if (findOpen) {
        Dialog(onDismissRequest = { findOpen = false }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("查找 / 替换", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it; findMsg = "" },
                        singleLine = true,
                        label = { Text("查找", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = replText,
                        onValueChange = { replText = it },
                        singleLine = true,
                        label = { Text("替换为", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    if (findMsg.isNotEmpty()) {
                        Text(
                            text = findMsg,
                            fontSize = 11.sp,
                            color = ScColors.Primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScriptPill("查找", Modifier.weight(1f)) {
                            findMsg = if (findText.isEmpty()) {
                                "请输入查找内容"
                            } else {
                                val n = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
                                    .findAll(draft).count()
                                "共找到 $n 处"
                            }
                        }
                        ScriptPill("替换", Modifier.weight(1f)) {
                            if (findText.isNotEmpty()) {
                                history.push(draft); historyVersion++
                                draft = draft.replaceFirst(findText, replText, ignoreCase = true)
                                findMsg = "已替换第 1 处"
                            }
                        }
                        ScriptPill("全部替换", Modifier.weight(1f)) {
                            if (findText.isNotEmpty()) {
                                history.push(draft); historyVersion++
                                val n = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
                                    .findAll(draft).count()
                                draft = draft.replace(
                                    Regex(Regex.escape(findText), RegexOption.IGNORE_CASE),
                                    replText,
                                )
                                findMsg = "已替换 $n 处"
                            }
                        }
                    }
                    Text(
                        text = "关闭",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { findOpen = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // ---- 文字大小 ----
    if (fontOpen) {
        Dialog(onDismissRequest = { fontOpen = false }) {
            ScCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("文字大小", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "当前 ${codeSp}sp",
                        fontSize = 12.sp,
                        color = ScColors.TextSub,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Slider(
                        value = codeSp.toFloat(),
                        onValueChange = { codeSp = it.toInt() },
                        valueRange = 10f..22f,
                        steps = 11,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        text = "关闭",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { fontOpen = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // ---- 帮助 ----
    if (helpOpen) {
        Dialog(onDismissRequest = { helpOpen = false }) {
            ScCard {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("脚本帮助", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = SCRIPT_HELP_TEXT,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = ScColors.TextMain,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "关闭",
                        fontSize = 12.sp,
                        color = ScColors.Primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScColors.Inset)
                            .clickable { helpOpen = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 帮助正文（真内容，随语法更新）。 */
private val SCRIPT_HELP_TEXT = """
【基本结构】
  void init()   { … }   装载时执行一次（可预计算）
  void sample() { … }   每个样本执行一次（实时）
  void block()  { … }   每块执行一次（块处理，配合 blk_/conv/fft）

【样本变量】
  spl0 / spl1   左右声道当前样本，读入处理后写回
  srate         采样率（48000）

【参数声明】（写在脚本头部注释里，UI 自动生成控件）
  // slider1: 0.0, 8.0, 1.0    范围 + 可选默认值
  // toggle1: 静音, default=0
  // select1: 曲线 = 线性|硬削波, default=0
  // list1: 频段 = 低频|中频|高频, default=3   （位掩码）
  // button1: 重置               （按下仅一个样本为 1）

【语法】
  变量/数组：float x = 1.0;  float buf[4800];  if / while / for
  函数：float f(float a) { return a * 2; }   支持递归
  三元：x = c ? a : b;

【内建函数】
  数学：sin cos tan asin acos atan atan2 exp log log10 pow sqrt
        abs floor ceil round min max tanh fmod clamp sign saturate
  双二阶：bq_lp(id,f,q,x)  bq_hp(id,f,q,x)  bq_peak(id,f,q,db,x)
  延迟线：dl_write(id,x)  dl_read(id,samples)  dl_clear(id)   （4 条 × 1s）
  块处理：blk_size()  blk_get(ch,i)  blk_set(ch,i,v)
  FFT：fft_size(n) fft_load(i,v) fft_forward() fft_re(i) fft_im(i)
       fft_set(i,re,im) fft_inverse() fft_out(i)
  卷积：conv_setup(id,taps) conv_load(id,k,v) conv_run(id,ch)
       convfft_setup(taps) convfft_load(k,v) convfft_run(ch)  （长 IR，512 样本延迟）

【.eel 子集】
  slider1:0.5<0<1>gain   @init / @sample   function f(x) ( … )   隐式全局

【提示】
  · 参数超过 8 个同类控件不会显示；数组长度需为常量
  · 逐样本深递归非常耗 CPU，避免在 sample() 里递归
  · ▶ 运行前会编译校验，出错会提示行号并在该行标红
""".trimIndent()

/** 编辑器顶栏图标按钮：统一 40dp 点击区 + 居中，保证一排图标等距、中线一致。 */
@Composable
private fun EditorBarIcon(
    glyph: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            fontSize = 16.sp,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/** 编辑器撤销/重做历史：普通对象 + 显式栈，避免局部 delegate 变量在事件 lambda 里读旧快照。 */
private class EditorHistory {
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(prev: String) {
        if (undoStack.lastOrNull() != prev) {
            undoStack.add(prev)
            if (undoStack.size > 200) undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(current: String): String? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.add(current)
        return prev
    }

    fun redo(current: String): String? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.add(current)
        return next
    }
}


/**
 * 按钮式开关：按下（高亮）= 启用，弹起（灰底）= 禁用。
 * 与滑动开关 [ScTinySwitch] 的区别是横向占位小，适合三个并排放一排。
 */
