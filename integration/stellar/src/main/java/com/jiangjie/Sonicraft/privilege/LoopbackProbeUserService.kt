/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Binder
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

/**
 * Loopback 探针服务（2026-11-04，P0 可行性验证）：运行在 Stellar UserService 进程。
 *
 * 为什么这条路能抓 ALLOW_CAPTURE_BY_SYSTEM + FLAG_NO_MEDIA_PROJECTION：
 *  - 旧的 AudioPlaybackCaptureConfiguration 路线受**单流 capture policy** 约束（酷狗
 *    policy=2 + Flags=0x400 直接否决）；
 *  - AudioPolicy + AudioMix(ROUTE_FLAG_LOOP_BACK) 走 **AudioService 混音层**注册，
 *    shell(2000) 持 CAPTURE_AUDIO_OUTPUT / MODIFY_AUDIO_ROUTING，不受单流 policy 拦；
 *  - LOOP_BACK 语义是**复制**而非重定向 → 原声照常输出，不需要 appops mute。
 *
 * 本 P0 只回传 RMS 电平（不传 PCM），app 进程零改动。P1 再上 SharedMemory 回传。
 */
class LoopbackProbeUserService(
    @Suppress("UNUSED_PARAMETER") private val ctx: Context,
) : ILoopbackProbe.Stub() {

    private val trail = ArrayList<String>()

    @Volatile
    private var level = 0f

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private var registeredPolicy: Any? = null
    private var readThread: Thread? = null

    /** P1：非 null 时读循环把 PCM16 交织数据经 oneway 回调回传 app */
    @Volatile
    private var streamCallback: ILoopbackCallback? = null

    /**
     * 按 uid 分 mix 的目标 uid（<=0 表示普通全局 mix）。
     * startUidProbe 设置后由 start() 一次性消费并复位。
     */
    @Volatile
    private var matchUidFilter = -1

    override fun getServiceUid(): Int = Process.myUid()

    override fun getLevel(): Float = level

    override fun getTrail(): String = synchronized(trail) { trail.joinToString("\n") }

    /**
     * 多档采样率回退（照抄万象 `rateCandidates`：`{preferred, 48000, 44100, 22050}`）。
     *
     * 为什么必须回退：APM 只有在 **mix 格式与播放源可兼容** 时才会把 player 路由进该
     * mix；我们此前死磕 48000，而酷狗实际是 44100 → mix 长期 `Standby: yes`、
     * record 读到 0（2026-11-05 实锤 output 197 Standby）。
     */
    override fun start(sampleRate: Int, excludeUid: Int): String {
        if (running) return "已在运行 uid=${Process.myUid()}"
        // 末位加入 16000：仅它一档允许打开 `allowPrivilegedPlaybackCapture`
        // （框架限制 ≤16kHz）。实验目的：验证「16k 特权 mix 能否把 BY_SYSTEM app
        // 重路由进 submix（=静音原声）」，音质不在乎——真要用于生产时会另起一路
        // 48k LOOP_BACK 取音频，16k 这路只负责"压原声"。
        // ★ 实验：**16k 放首位**，验证「特权捕获能否把 BY_SYSTEM app
        // 重路由进我方 submix」。若成立，就能用 16k 那路只做"压原声"（音质无所谓），
        // 48k 副本另走一路 LOOP_BACK。实验后按结果把顺序改回。
        val candidates = linkedSetOf(sampleRate, 48_000, 44_100, 22_050, 16_000)
            .filter { it in 8_000..192_000 }
        log("采样率候选: $candidates")
        var last = ""
        for (rate in candidates) {
            if (rate != sampleRate) {
                log("↻ 采样率回退：$rate Hz 重试")
                synchronized(trail) { trail.clear() }
            }
            last = startAt(rate, excludeUid)
            if (last.startsWith("捕获中")) return last
        }
        return last
    }

    private fun startAt(sampleRate: Int, excludeUid: Int): String {
        synchronized(trail) { trail.clear() }
        log("服务进程 uid=${Process.myUid()} pid=${Process.myPid()}")

        // 关键前提 1：以 shell(2000) 身份执行后续 AudioService 调用（照抄万象）
        val callingToken = Binder.clearCallingIdentity()
        try {
            // 关键前提 2：hidden API 全豁免（否则反射下文的 audiopolicy 内部类会被拦）
            val exempted = runCatching { ensureHiddenApiExemptions() }.getOrDefault(false)
            log("hiddenApiExemptions=${if (exempted) "✓" else "✗（继续试）"}")

            // ① 加载 hidden 类
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            log("✓ 六个 audiopolicy 内部类加载成功")

            // ② 常量从类反射取（不硬编码，防版本漂移）
            val routeLoopBack = mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val ruleUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            log("常量: ROUTE_FLAG_LOOP_BACK=$routeLoopBack RULE_MATCH_ATTRIBUTE_USAGE=$ruleUsage")

            // ③ AudioMixingRule：**必须同时匹配 USAGE_UNKNOWN**（决定性，2026-11-04 万象
            // 现场实证）。AudioRecord(setInternalCapturePreset(8)) 自身的 AudioAttributes
            // usage = USAGE_UNKNOWN(0)；AudioFlinger 建 record 时用它去匹配已注册 mix 的
            // rule，只有 MEDIA 的 rule 匹配不上 → getInputForAttr 找不到 mix →
            // state=0（Cannot create AudioRecord）。万象的收音专用 Mix 2 正是
            // 「纯 LOOP_BACK + RULE_MATCH_ATTRIBUTE_USAGE AUDIO_USAGE_UNKNOWN + MEDIA」
            val ruleBuilder = ruleBuilderCls.getDeclaredConstructor().newInstance()
            val addMixRule = findMethod(ruleBuilderCls, "addMixRule", Int::class.javaPrimitiveType!!, Any::class.java)
                ?: return finishFail("找不到 addMixRule(int,Object)")
            val ruleUid = runCatching { ruleCls.getField("RULE_MATCH_UID").getInt(null) }.getOrDefault(-1)
            val excludeMixRule = findMethod(ruleBuilderCls, "excludeMixRule", Int::class.javaPrimitiveType!!, Any::class.java)
            val targetUid = matchUidFilter
            matchUidFilter = -1   // 一次性消费，避免影响后续普通启动
            if (targetUid > 0 && ruleUid >= 0) {
                // 按 uid 分 mix（2026-11-05 P2 精细化）：目标是拿到**该 app 独占的副本**，
                // 以判断「DP 静音是否连坐它这一路」——全局电平会被其它在播 app 撑住，
                // 单路失源完全看不出来（网易云静音事故即此）。
                //
                // 实现要点（实测修正）：**纯 uid 规则拿不到数据**（route 生效、output 建成、
                // 但 record 恒零 0.0009）。故叠加 usage 规则——usage 路径是已验证能供数的，
                // 两者是 AND 语义：usage∈{UNKNOWN,MEDIA,GAME} 且 uid=target。
                val usageValues = listOf(
                    AudioAttributes.USAGE_UNKNOWN to "UNKNOWN",
                    AudioAttributes.USAGE_MEDIA to "MEDIA",
                    AudioAttributes.USAGE_GAME to "GAME",
                )
                for ((usage, name) in usageValues) {
                    val attrs = AudioAttributes.Builder().setUsage(usage).build()
                    addMixRule.invoke(ruleBuilder, ruleUsage, attrs)
                    log("  + mixRule USAGE_$name($usage)")
                }
                addMixRule.invoke(ruleBuilder, ruleUid, targetUid)
                log("  + mixRule UID=$targetUid（usage+uid AND）")
            } else {
                val usageValues = listOf(
                    AudioAttributes.USAGE_UNKNOWN to "UNKNOWN",
                    AudioAttributes.USAGE_MEDIA to "MEDIA",
                    AudioAttributes.USAGE_GAME to "GAME",
                )
                for ((usage, name) in usageValues) {
                    val attrs = AudioAttributes.Builder().setUsage(usage).build()
                    addMixRule.invoke(ruleBuilder, ruleUsage, attrs)
                    log("  + mixRule USAGE_$name($usage)")
                }
                // 排除自身（安全底线，2026-11-04 卡死事故教训）：
                // ① 排除调用方 app 的 uid —— 否则 app 自己的 AAudio 输出（USAGE_MEDIA）
                //    也会被 LOOP_BACK 进 mix；record 一旦建失败就是「吃流无消费者」黑洞；
                // ② probe 进程自身（shell 2000）同理。
                if (ruleUid >= 0 && excludeMixRule != null) {
                    if (excludeUid > 0) {
                        runCatching { excludeMixRule.invoke(ruleBuilder, ruleUid, excludeUid) }
                        log("  - excludeMixRule UID=$excludeUid（调用方自身）")
                    }
                    log("  - 不 exclude probe 自身（照抄万象规则）")
                } else {
                    log("  ⚠ 拿不到 excludeMixRule/RULE_MATCH_UID —— 有抓自身输出的风险")
                }
            }
            // ★ 决定性参数（2026-11-05 推断并验证）：`allowPrivilegedPlaybackCapture(true)`。
            // MediaProjection 的 mix 与我们的差异就在这一项——普通 playback-capture mix
            // 会**按 capture policy 过滤参与者**，酷狗(ALLOW_CAPTURE_BY_SYSTEM + 0x400)
            // 被排除出 mix，于是我们的 output 恒为 `Standby: yes`（实测 output 197）。
            // shell(2000) 持 CAPTURE_AUDIO_OUTPUT/CAPTURE_MEDIA_OUTPUT，可置该标志使该路音轨可被捕获。
            // 只在 ≤16 kHz 时才开特权捕获：框架硬限制
            // `Privileged audio capture sample rate 48000 can not be over 16000kHz`
            // ，开在 48k 会直接导致 AudioMix 构建失败。
            if (sampleRate <= 16_000) {
                runCatching {
                    val m = findMethod(
                        ruleBuilderCls,
                        "allowPrivilegedPlaybackCapture",
                        java.lang.Boolean.TYPE,
                    )
                    if (m != null) {
                        m.invoke(ruleBuilder, true)
                        log("  ✓ allowPrivilegedPlaybackCapture(true)（≤16k 才允许）")
                    }
                }.onFailure {
                    log("  ⚠ allowPrivileged 失败: ${it.javaClass.simpleName}: ${it.message}")
                }
            } else {
                log("  · 跳过 allowPrivilegedPlaybackCapture（框架限制 ≤16kHz，本档 $sampleRate）")
            }

            // 目标角色：MIX_ROLE_PLAYERS（1）。MediaProjection 的捕获 mix 同样显式声明，
            // 不声明时部分 ROM 按默认角色处理，player 不会被纳入该 mix。
            runCatching {
                val role = runCatching { mixCls.getField("MIX_ROLE_PLAYERS").getInt(null) }
                    .getOrDefault(1)
                val m = findMethod(
                    ruleBuilderCls,
                    "setTargetMixRole",
                    Int::class.javaPrimitiveType!!,
                )
                if (m != null) {
                    m.invoke(ruleBuilder, role)
                    log("  ✓ setTargetMixRole(MIX_ROLE_PLAYERS=$role)")
                } else {
                    log("  ⚠ 找不到 setTargetMixRole")
                }
            }.onFailure { e ->
                val c = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                log("  ⚠ setTargetMixRole 失败: ${c.javaClass.simpleName}: ${c.message}（继续，非致命）")
            }

            val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)
            log("✓ AudioMixingRule(UNKNOWN+MEDIA) 构建")

            // ④ AudioMix + ⑤ AudioPolicy + ⑥ 注册：按万象实测顺序尝试两种 route flag。
            // 纯 LOOP_BACK 先试；被拒则退 LOOP_BACK|RENDER——万象最终采用的是后者
            // （残留 dump 实锤 "MIX_ROUTE_FLAG_RENDER|MIX_ROUTE_FLAG_LOOP_BACK"），
            // 说明纯 LOOP_BACK 在本机会被系统拒。
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val mixFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()
            val routeRender = runCatching {
                mixCls.getField("ROUTE_FLAG_RENDER").getInt(null)
            }.getOrDefault(-1)
            val combos = ArrayList<Pair<String, Int>>()
            // 对照万象源码后的关键修正：
            //   万象 LoopbackPolicy 的配置表是
            //     ①usage+排除+role+LOOP_BACK（默认）
            //     ⓪usage+排除+role+LOOPBACK|RENDER（**preferRender 时插到最前**）
            //   而它的 `force_render` 偏好正是透传为 preferRender；
            //   并且 `force_render=true` 时它**整段跳过 DP 静音**（muter.releaseAll）。
            //   也就是说：RENDER = 让 APM 把 player 重定向进我方 submix（原声不再出声），
            //   因此不需要任何静音；纯 LOOP_BACK = 只复制、原声仍在，必须靠静音压制。
            // 默认仍以纯 LOOP_BACK 为先（本机历史上被接受过）；需要验证 RENDER 路线时
            // 置 `debug.sonicraft.render=1`，把 RENDER 提到第一位。
            val preferRender = runCatching {
                ProcessBuilder("getprop", "debug.sonicraft.render").start()
                    .inputStream.bufferedReader().use { it.readText().trim() } == "1"
            }.getOrDefault(false)
            if (preferRender && routeRender > 0) {
                combos.add("LOOPBACK|RENDER" to (routeLoopBack or routeRender))
            }
            combos.add("LOOP_BACK" to routeLoopBack)
            if (!preferRender && routeRender > 0) {
                combos.add("LOOPBACK|RENDER" to (routeLoopBack or routeRender))
            }

            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return finishFail("拿不到 AUDIO_SERVICE")
            audioManager = am

            // ⑥★ 读端先行（决定性，2026-11-04 依据 skill 文档 + NX733J 实测）：
            // AOSP LOOP_BACK 标准顺序是「AudioRecord(REMOTE_SUBMIX).startRecording()
            // 先就绪 → 再 registerAudioPolicy」。反过来（先写后读）在 NX733J 上会建立
            // 「无订阅者」submix 通道：policy 注册成功、端口可见（控制面正常），但
            // 数据面被 OEM HAL 桩死 —— 表现正是我们一路遇到的 `output: 0` 与
            // `Cannot create AudioRecord`。这里改为先建 record 并开录，再注册 policy。
            val recBuf = maxOf(
                AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding),
                4096,
            )
            // 系统 Context（万象同款：ActivityThread.currentApplication()）——
            // AudioRecord.Builder.setContext 用它推导 attributionSource。实测万象成功
            // 的 record 显示 mPackageName='anroid'（系统 Context 的包名 "android"），
            // 而我们用 AIDL 注入的 DSP Context 会得到 (uid 10473, pkg Sonicraft)，
            // 与进程 uid 2000 不一致 → AppOps 校验失败。
            // Attribution 只能由 AudioRecord.Builder.setContext 推导
            // （实测：本机 AudioAttributes$Builder 完全没有 attribution 方法）。
            // AppOps 要校验 uid-package 一致性：进程 uid=2000，Context 包名必须能
            // 解析到 2000（= com.android.shell）。当前 UserService 的 Context 是
            // DSP 包（10473）→ 不匹配。故用 createPackageContext 取 shell 包 Context。
            val sysCtx = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
            log("系统 Context(packageName)=${sysCtx?.packageName ?: "null"}")
            val shellCtx = runCatching {
                ctx.createPackageContext(SHELL_PKG, 0)
            }.getOrElse { e ->
                log("createPackageContext($SHELL_PKG) 失败: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            log("shell Context(packageName)=${shellCtx?.packageName ?: "null"}")
            val recordCtx = shellCtx ?: sysCtx
            // 决定性一步：改进程级 attribution（uid=2000 + pkg=com.android.shell），
            // 之后 AudioRecord 不设 context 即用该伪造身份，AppOps 的 uid-package 校验才对得上
            makeShellAttribution()
            // ★ 决定性：把 record 用的 Context 的 attribution 改成 shell 身份
            // （uid=2000 → appops 走 allow 分支，而不是 app uid 的 foreground 分支）
            if (recordCtx != null) {
                patchContextAttribution(recordCtx, SHELL_UID, SHELL_PKG)
            }
            // ★ 万象 record 现场实锤 mPackageName='anroid'（故意拼错的假包名）。
            // AudioRecord.Builder.setContext 内部用 context.getOpPackageName() 生成
            // attribution：真实包名 com.android.shell 会触发 AppOps 的 uid-包名严格校验；
            // 不存在的包名使 getPackageUid 返回 -1 → 走「未知包」分支放行。
            val fakePkgCtx = object : android.content.ContextWrapper(ctx) {
                override fun getPackageName(): String = FAKE_ATTR_PKG
                override fun getOpPackageName(): String = FAKE_ATTR_PKG
                override fun getApplicationContext(): Context = this
            }



            var mix: Any? = null
            var policy: Any? = null
            for ((tag, flags) in combos) {
                val mixBuilder = runCatching {
                    mixBuilderCls.getDeclaredConstructor(ruleCls).newInstance(rule)
                }.getOrNull() ?: return finishFail("AudioMix.Builder 构造失败")
                findMethod(mixBuilderCls, "setRouteFlags", Int::class.javaPrimitiveType!!)
                    ?.invoke(mixBuilder, flags)
                    ?: return finishFail("找不到 setRouteFlags")
                findMethod(mixBuilderCls, "setFormat", AudioFormat::class.java)
                    ?.invoke(mixBuilder, mixFormat)
                val mixResult = runCatching { mixBuilderCls.getMethod("build").invoke(mixBuilder) }
                val mixTry = mixResult.getOrNull()
                if (mixTry == null) {
                    val cause = (mixResult.exceptionOrNull() as? java.lang.reflect.InvocationTargetException)?.cause
                        ?: mixResult.exceptionOrNull()
                    log("✗ [$tag] AudioMix 构建失败: ${cause?.javaClass?.simpleName}: ${cause?.message}")
                    continue
                }
                val policyTry = runCatching {
                    // AudioPolicy.Builder 构造必须传 Context（AOSP：Builder(Context)）；
                    // 无参构造不存在，传 newInstance() 会 NoSuchMethodException——
                    // 这是「两种 route flag 均被拒绝」假象的真因
                    val pb = runCatching {
                        policyBuilderCls.getDeclaredConstructor(Context::class.java).newInstance(ctx)
                    }.getOrElse {
                        policyBuilderCls.getDeclaredConstructor().newInstance()
                    }
                    findMethod(policyBuilderCls, "addMix", mixCls)?.invoke(pb, mixTry)
                        ?: throw IllegalStateException("找不到 addMix")
                    policyBuilderCls.getMethod("build").invoke(pb)
                }.getOrElse { e ->
                    log("✗ [$tag] AudioPolicy 构建失败: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (policyTry == null) continue

                var regMethod = findMethod(am.javaClass, "registerAudioPolicyStatic", policyCls)
                if (regMethod == null) {
                    regMethod = findMethod(am.javaClass, "registerAudioPolicy", policyCls)
                }
                if (regMethod == null) return finishFail("找不到 registerAudioPolicy(Static)")
                val isStatic = java.lang.reflect.Modifier.isStatic(regMethod.modifiers)
                val ret = runCatching {
                    if (isStatic) regMethod.invoke(null, policyTry) else regMethod.invoke(am, policyTry)
                }.getOrElse { e ->
                    log("✗ [$tag] register 抛异常: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                val accepted = when (ret) {
                    is Int -> ret == 0
                    is Boolean -> ret
                    is String -> ret.isNotEmpty()
                    else -> ret != null
                }
                log("register($tag, ${if (isStatic) "static" else "实例"}) → $ret = ${if (accepted) "✓ 接受" else "✗ 拒绝"}")
                if (accepted) {
                    mix = mixTry
                    policy = policyTry
                    log("★ 采用 route flag = $tag")
                    break
                }
            }
            val finalMix = mix ?: return finishFail("两种 route flag 均被系统拒绝（见 trail）")
            val finalPolicy = policy ?: return finishFail("policy 为空")
            registeredPolicy = finalPolicy

            // ⑦ 取 mix 的 registration（仅日志留痕：读端已先行就绪，不再需要 addr tag 绑定）
            val registration = runCatching {
                mixCls.getMethod("getRegistration").invoke(finalMix) as? String
            }.getOrNull()
            log("mix.getRegistration()=${registration ?: "null"}")

            // ⑧ 建 record：此刻 mix 已注册、且已匹配到活跃流（酷狗 MEDIA），
            // AudioFlinger 已为 mix 创建 output 端口 —— 这是 record 能绑定的前提
            // （无流时 output=0，record 必失败；万象实测 output=237 时有流在播）
            val recFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()

            // APM 建「回环输入设备」是异步的：注册返回 0 时端口还没就绪，立刻 build 会
            // 拿到 state=0。原实现是「死等 2s」，实测端口通常几百 ms 就出现 → 白等。
            // 现改为「轮询端口出现（≤1.5s）+ 300ms 落地余量」：端口在即可建，通常 0.5~0.9s。
            // 注意：不能用「固定 300ms」代替（2026-11-06 实测：固定短等待会导致首链
            // record state=1 但数据恒 0，必须靠重建第二条链才有数据）。判据必须是「端口真的出现」。
            runCatching {
                val t0 = android.os.SystemClock.elapsedRealtime()
                while (android.os.SystemClock.elapsedRealtime() - t0 < 1500) {
                    val dump = ProcessBuilder("sh", "-c", "dumpsys media.audio_policy")
                        .start().inputStream.bufferedReader().use { it.readText() }
                    val hit = registration != null &&
                        Regex("""Port ID: \d+; "Remote Submix Out"; \{AUDIO_DEVICE_OUT_REMOTE_SUBMIX, @:([^}]*)\}""")
                            .findAll(dump)
                            .any { it.groupValues[1].contains(registration) }
                    if (hit) {
                        log("端口已就绪（ waited=${android.os.SystemClock.elapsedRealtime() - t0}ms ）")
                        break
                    }
                    Thread.sleep(120)
                }
                Thread.sleep(300)  // 端口出现后再留一点余量，让 sink 落地
            }
            log("开始建 record（失败每 150ms 重试）")

            // ★★ 万象路线（2026-11-05，LoopbackPolicy.java 注册后段）：优先用隐藏 API
            // `AudioPolicy.createAudioRecordSink(mix)`。它把 record 直接绑到**已注册 mix
            // 的 sink 端**，是"正统绑定"；我们此前手工 preset=8 + addr tag 建出的 record
            // 绑不到 sink —— 这正是 mix output 长期 `Standby`、inputLevel 恒零，
            // 以及 DP 静音时副本时有时无（绑定位置不稳定）的根因。
            // 失败 8×500ms 后仍回退手工构造（万象也是这么兜的）。
            var sinkRec: AudioRecord? = null
            // sink 默认关闭（本机 8×state=0 恒失败，白耗 4s；需要复验时 setprop 开启）
            val sinkEnabled = runCatching {
                ProcessBuilder("getprop", "debug.sonicraft.sink").start()
                    .inputStream.bufferedReader().use { it.readText().trim() } == "1"
            }.getOrDefault(false)
            val sinkMethod = if (sinkEnabled) findMethod(policyCls, "createAudioRecordSink", mixCls) else null
            if (sinkMethod != null) {
                for (i in 1..8) {
                    val r = runCatching {
                        sinkMethod.invoke(finalPolicy, finalMix) as? AudioRecord
                    }.getOrNull()
                    if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                        sinkRec = r
                        log("✓ createAudioRecordSink 第 $i 次成功 state=1")
                        break
                    }
                    log("  · sink 第 $i 次: state=${r?.state ?: -1}（+500ms 再试）")
                    runCatching { r?.release() }
                    runCatching { Thread.sleep(500) }
                }
            } else {
                log("  ⚠ 找不到 createAudioRecordSink，直接走手工构造兜底")
            }

            // ★ 手工构造兜底（旧路，万象的 fallback 同款）：
            // 用「旧 5 参构造」而非 Builder（万象 trail 的「自建」就是这条）：
            // Builder.build() 在 state=0 时会先 release 再抛 UnsupportedOperationException
            // ——对象拿不到，无法重试；旧构造只返回 state=0 的对象（不抛），才可能重试到
            // APM 把回环输入设备建好（万象实测第 9 次才 state=1）。
            var rec: AudioRecord? = sinkRec
            for (attempt in 1..8) {
                // sink 已成功就不必再手工构造
                if (rec != null) break
                val r = runCatching {
                    // 三要素合一（全部来自万象成功现场）：
                    // ① attributes 带 addr tag → 绑定到已注册的 mix device
                    // ② Builder.setContext(fakePkgCtx) → attribution 包名 = "anroid"（假包名）
                    // ③ 重试直到 APM 把回环输入设备建好
                    val aaBCls = Class.forName("android.media.AudioAttributes\$Builder")
                    val aaBuilder = aaBCls.getDeclaredConstructor().newInstance()
                    findMethod(aaBCls, "setInternalCapturePreset", Int::class.javaPrimitiveType!!)
                        ?.invoke(aaBuilder, PRESET_REMOTE_SUBMIX)
                    if (!registration.isNullOrEmpty()) {
                        findMethod(aaBCls, "addTag", String::class.java)
                            ?.invoke(aaBuilder, "addr=$registration")
                    }
                    val recAttrs = aaBCls.getMethod("build").invoke(aaBuilder) as AudioAttributes

                    val rbCls = Class.forName("android.media.AudioRecord\$Builder")
                    val rb = rbCls.getDeclaredConstructor().newInstance()
                    findMethod(rbCls, "setAudioAttributes", AudioAttributes::class.java)?.invoke(rb, recAttrs)
                    findMethod(rbCls, "setAudioFormat", AudioFormat::class.java)?.invoke(rb, recFormat)
                    findMethod(rbCls, "setBufferSizeInBytes", Int::class.javaPrimitiveType!!)?.invoke(rb, recBuf)
                    // 用已把 attribution 改成 (2000, com.android.shell) 的 Context
                    // （fakePkgCtx 的 getOpPackageName 覆盖不到 attribution uid，弃用）
                    (recordCtx ?: fakePkgCtx)?.let {
                        findMethod(rbCls, "setContext", Context::class.java)?.invoke(rb, it)
                    }
                    rbCls.getMethod("build").invoke(rb) as? AudioRecord
                }.getOrElse { e ->
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                    log("第 $attempt 次构造异常: ${cause.javaClass.simpleName}: ${cause.message}")
                    null
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    log("✓ 自建第 $attempt 次成功 state=1 sr=${r.sampleRate} ch=${r.channelCount} addr=$registration")
                    break
                }
                log("自建第 $attempt 次: state=${r?.state ?: -1} addr=$registration（+150ms 再试）")
                runCatching { r?.release() }
                runCatching { Thread.sleep(150) }
            }
            val finalRec = rec ?: return finishFail("12 次重试均未拿到 state=1")
            finalRec.startRecording()
            if (finalRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { finalRec.release() }
                return finishFail("startRecording 失败 state=${finalRec.recordingState}")
            }
            record = finalRec
            log("✓ record 已开录（mix 注册之后）")

            // ⑧ 读端已在 ⑥★ 中开录（先于 policy 注册）——policy 注册完成后数据自动
            // 流入该 record，无需 addr tag。这是 NX733J 上唯一能拿到数据面的顺序。
            running = true
            readThread = Thread({
                // P2③：采集线程必须提到音频紧急级。
                // UserService 进程里还跑着 Stellar 的 binder/管理逻辑，默认优先级下
                // read 会被抢占 → 供数中断 → 听感「有节奏的断断续续」（underrun 实锤）。
                runCatching {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
                    )
                }
                // P2③：读粒度固定 2048 帧（≈21ms @48k stereo）而非整块 recBuf。
                // 整块读（3840 帧≈40ms）会让供给呈「大块间隔」形态，引擎侧容易在
                // 块间隙 underrun → 听感断续；21ms 粒度接近引擎块节奏，更平滑。
                val buf = ShortArray(CHUNK_SAMPLES * PCM_CHANNELS)
                val pcm = ByteArray(CHUNK_SAMPLES * PCM_CHANNELS * 2)
                while (running) {
                    val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (n <= 0) {
                        if (n < 0) {
                            streamCallback?.let { cb ->
                                runCatching { cb.onError("read 恒负 $n（record 失效）") }
                            }
                            break
                        }
                        continue
                    }
                    var sum = 0L
                    for (i in 0 until n) {
                        val v = buf[i].toLong()
                        sum += v * v
                    }
                    level = (sqrt(sum.toDouble() / n) / 32768.0).toFloat()
                    // P1：PCM16 小端交织回传。oneway 回调 + 每批 ~20ms，若走同步 binder
                    // 往返会阻塞采集线程导致 FIFO 溢出（听感为周期性爆音）。
                    val cb = streamCallback
                    if (cb != null) {
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            pcm[i shl 1] = (v and 0xFF).toByte()
                            pcm[(i shl 1) + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        val bytes = n * 2
                        runCatching { cb.onPcm(pcm.copyOf(bytes), n / PCM_CHANNELS) }
                    }
                }
            }, "loopback-probe-read").apply {
                isDaemon = true
                start()
            }

            return "捕获中 uid=${Process.myUid()} sr=$sampleRate addr=${registration ?: "-"}（读端先行）"
        } catch (t: Throwable) {
            Log.w("LoopbackProbe", "start 失败", t)
            return finishFail("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            Binder.restoreCallingIdentity(callingToken)
        }
    }

    /**
     * P1：启动 PCM 流式回传。复用 [start] 的全部建立逻辑（policy + mix + record +
     * 等 2s + 重试），只把读循环的输出从「仅 RMS」扩展为「RMS + onPcm」。
     */
    override fun startStream(sampleRate: Int, excludeUid: Int, cb: ILoopbackCallback): String {
        streamCallback = cb
        return start(sampleRate, excludeUid)
    }

    /**
     * 按 uid 分 mix 的电平探针（2026-11-05 P2）：
     * 只注册匹配 [targetUid] 的 LOOP_BACK mix，record 读到的是该 app 独占的副本。
     * 调用方 start 后轮询 [getLevel] 即可拿到「这一路是否还有数据」。
     */
    override fun startUidProbe(sampleRate: Int, targetUid: Int): String {
        if (running) stop()
        matchUidFilter = targetUid
        val st = start(sampleRate, Process.myUid())
        return "targetUid=$targetUid $st"
    }

    // ===== 静音托管（2026-11-05 根治孤儿 DP）=====
    // 效果对象由**本服务进程**持有：服务是 DAEMON 常驻，app 死亡时通过
    // attachClient 的 linkToDeath 立刻释放，不会留下 `0 Clients` 的孤儿效果。

    private val muteEffects =
        java.util.concurrent.ConcurrentHashMap<Int, android.media.audiofx.DynamicsProcessing>()

    override fun muteSessions(sids: IntArray): String = runCatching {
        val wanted = sids.filter { it > 0 }.toSet()
        for ((sid, fx) in muteEffects.toList()) {
            if (sid in wanted) continue
            muteEffects.remove(sid)
            runCatching { fx.enabled = false; fx.release() }
        }
        var added = 0
        for (sid in wanted) {
            val existing = muteEffects[sid]
            if (existing != null) {
                runCatching { if (!existing.enabled) existing.enabled = true }
                continue
            }
            val cfg = android.media.audiofx.DynamicsProcessing.Config
                .Builder(1, 2, false, 1, false, 1, false, 1, false)
                .build()
            val fx = android.media.audiofx.DynamicsProcessing(Int.MAX_VALUE, sid, cfg)
            fx.setInputGainAllChannelsTo(MUTE_GAIN_DB)
            fx.enabled = true
            muteEffects[sid] = fx
            added++
        }
        "ok add=$added total=${muteEffects.size} sids=${wanted.joinToString(",")}"
    }.getOrElse { e -> "fail ${e.javaClass.simpleName}: ${e.message}" }

    override fun unmuteAll(): String {
        val n = muteEffects.size
        for (fx in muteEffects.values) {
            runCatching { fx.enabled = false; fx.release() }
        }
        muteEffects.clear()
        return "ok released=$n"
    }

    override fun attachClient(client: android.os.IBinder) {
        runCatching {
            client.linkToDeath({
                // 客户端进程死亡 → 立即释放全部静音并撤销链路，不留任何残留
                log("客户端进程死亡 → 释放静音 + 停止链路（防孤儿效果）")
                runCatching { unmuteAll() }
                runCatching { stop() }
            }, 0)
            log("已绑定客户端 binder（linkToDeath 就绪）")
        }.onFailure {
            log("attachClient.linkToDeath 失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ===== 16k 特权静音器（双路之一，2026-11-05）=====
    private var silencerPolicy: Any? = null
    private var silencerRecord: AudioRecord? = null
    private var silencerThread: Thread? = null

    /**
     * 实验变体（用 `adb shell setprop debug.sonicraft.silencer N` 切换，免改 AIDL）：
     *  0 = 16k mono + 特权 + RENDER|LOOP_BACK（现状）
     *  1 = 16k mono + **无特权** + RENDER|LOOP_BACK   ← 实验 A：验证特权标志是否挡住 record
     *  2 = 16k mono + 特权 + **仅 RENDER**            ← 实验 B：验证 LOOP_BACK 组合
     *  3 = 16k mono + **无特权** + 仅 LOOP_BACK       ← 实验 C：16k 本身可行性对照
     */
    private fun silencerVariant(): Int = runCatching {
        val p = ProcessBuilder("getprop", "debug.sonicraft.silencer").start()
        p.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() } ?: 0
    }.getOrDefault(0)

    override fun startSilencer16k(excludeUid: Int): String {
        val variant = silencerVariant()
        log("16k静音器变体 variant=$variant（0特权LB|R 1非特权LB|R 2特权仅R 3非特权仅LB）")
        stopSilencer()
        val token = Binder.clearCallingIdentity()
        try {
            ensureHiddenApiExemptions()
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val rbCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mbCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val pbCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val am = audioManager ?: (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?: return "fail 无 AudioManager"
            audioManager = am

            val ruleUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            val ruleUid = runCatching { ruleCls.getField("RULE_MATCH_UID").getInt(null) }.getOrDefault(-1)
            val rb = rbCls.getDeclaredConstructor().newInstance()
            val add = findMethod(rbCls, "addMixRule", Int::class.javaPrimitiveType!!, Any::class.java)
                ?: return "fail 无 addMixRule"
            for (u in intArrayOf(
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
            )) {
                add.invoke(rb, ruleUsage, AudioAttributes.Builder().setUsage(u).build())
            }
            if (ruleUid >= 0 && excludeUid > 0) {
                findMethod(rbCls, "excludeMixRule", Int::class.javaPrimitiveType!!, Any::class.java)
                    ?.let { runCatching { it.invoke(rb, ruleUid, excludeUid) } }
            }
            val wantPrivileged = variant == 0 || variant == 2
            val privilegedOk = if (!wantPrivileged) {
                log("16k: 变体 $variant 不开启特权捕获")
                false
            } else {
                runCatching {
                    findMethod(rbCls, "allowPrivilegedPlaybackCapture", java.lang.Boolean.TYPE)
                        ?.invoke(rb, true)
                }.onFailure {
                    log("16k: allowPrivileged 失败 ${it.javaClass.simpleName}: ${it.message}")
                }.isSuccess
            }
            log("16k: 规则就绪 privileged=$privilegedOk")
            val rule = rbCls.getMethod("build").invoke(rb)

            // 特权捕获还有第二条限制：**必须单声道**
            // 实测报错 `Privileged audio capture channel count 2 can not be over 1`
            // 静音器不消费音频，单声道完全够用。
            val fmt = AudioFormat.Builder().setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val lb = mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val rd = mixCls.getField("ROUTE_FLAG_RENDER").getInt(null)
            val mb = mbCls.getDeclaredConstructor(ruleCls).newInstance(rule)
            val flags = when (variant) {
                2 -> rd          // 仅 RENDER
                3 -> lb          // 仅 LOOP_BACK
                else -> lb or rd // 默认混合
            }
            log("16k: routeFlags=$flags")
            findMethod(mbCls, "setRouteFlags", Int::class.javaPrimitiveType!!)!!.invoke(mb, flags)
            findMethod(mbCls, "setFormat", AudioFormat::class.java)!!.invoke(mb, fmt)
            val mix = runCatching { mbCls.getMethod("build").invoke(mb) }.getOrElse { e ->
                val c = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                return "fail mixBuild ${c.javaClass.simpleName}: ${c.message}"
            }
            log("16k: AudioMix 构建成功")

            val pb = pbCls.getDeclaredConstructor(Context::class.java).newInstance(ctx)
            findMethod(pbCls, "addMix", mixCls)!!.invoke(pb, mix)
            val policy = runCatching { pbCls.getMethod("build").invoke(pb) }.getOrElse { e ->
                val c = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                return "fail policyBuild ${c.javaClass.simpleName}: ${c.message}"
            }
            log("16k: AudioPolicy 构建成功")
            // ★ 读端先行（与主链路同规则，2026-11-04 实测）：NX733J 上必须先有
            // REMOTE_SUBMIX record 在录，APM 才会为该 mix 建回环输入设备；反过来
            // （先注册 policy 再建 record）12 次重试全是 state=0（本轮实锤）。
            val recBuf = maxOf(
                AudioRecord.getMinBufferSize(
                    16_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ),
                4096,
            )
            val shellCtx = runCatching { ctx.createPackageContext(SHELL_PKG, 0) }.getOrNull()
            var rec: AudioRecord? = null
            for (attempt in 1..12) {
                val r = runCatching {
                    val aaB = Class.forName("android.media.AudioAttributes\$Builder")
                        .getDeclaredConstructor().newInstance()
                    findMethod(aaB.javaClass, "setInternalCapturePreset", Int::class.javaPrimitiveType!!)
                        ?.invoke(aaB, PRESET_REMOTE_SUBMIX)
                    val attrs = aaB.javaClass.getMethod("build").invoke(aaB) as AudioAttributes
                    val rb2 = Class.forName("android.media.AudioRecord\$Builder")
                        .getDeclaredConstructor().newInstance()
                    findMethod(rb2.javaClass, "setAudioAttributes", AudioAttributes::class.java)
                        ?.invoke(rb2, attrs)
                    findMethod(rb2.javaClass, "setAudioFormat", AudioFormat::class.java)
                        ?.invoke(rb2, fmt)
                    findMethod(rb2.javaClass, "setBufferSizeInBytes", Int::class.javaPrimitiveType!!)
                        ?.invoke(rb2, recBuf)
                    if (shellCtx != null) {
                        findMethod(rb2.javaClass, "setContext", Context::class.java)?.invoke(rb2, shellCtx)
                    }
                    Class.forName("android.media.AudioRecord\$Builder").getMethod("build").invoke(rb2) as? AudioRecord
                }.getOrElse { null }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    log("16k静音器 record 第 $attempt 次成功（policy 注册前）")
                    break
                }
                runCatching { r?.release() }
                runCatching { Thread.sleep(250) }
            }
            val finalRec = rec ?: return "fail record 未初始化（12 次）"
            finalRec.startRecording()
            log("16k: record 已开录，接着注册 policy")

            val regMethod = findMethod(am.javaClass, "registerAudioPolicyStatic", policyCls)
                ?: findMethod(am.javaClass, "registerAudioPolicy", policyCls)
                ?: return "fail 无 registerAudioPolicy"
            val isStatic = java.lang.reflect.Modifier.isStatic(regMethod.modifiers)
            val ret = if (isStatic) regMethod.invoke(null, policy) else regMethod.invoke(am, policy)
            val accepted = (ret as? Int)?.let { it == 0 } ?: ((ret as? Boolean) ?: false)
            log("16k静音器 register=$ret accepted=$accepted privilegedFlag=$privilegedOk")
            if (!accepted) {
                runCatching { finalRec.stop(); finalRec.release() }
                return "fail register=$ret"
            }
            silencerPolicy = policy
            val registration = runCatching {
                policyCls.getMethod("getRegistration").invoke(policy) as? String
            }.getOrNull()
            log("16k静音器 registration=$registration")
            silencerRecord = finalRec
            silencerThread = Thread({
                // 必须持续读空：mix output 无人消费会堵塞，进而拖垮整条链路
                val buf = ShortArray(512 * 2)
                while (silencerRecord != null) {
                    val n = runCatching { finalRec.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (n < 0) break
                }
            }, "silencer-16k").also {
                it.isDaemon = true
                it.start()
            }
            return "ok reg=$registration privileged=$privilegedOk"
        } catch (e: Exception) {
            // 解包反射异常，否则只会看到 "InvocationTargetException: null"
            val c = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            log("16k: 异常 ${c.javaClass.simpleName}: ${c.message}")
            return "fail ${c.javaClass.simpleName}: ${c.message}"
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * 主动触发 APM 重算路由（REROUTE 实验，2026-11-05）。
     *
     * 手法：`setMode(IN_COMMUNICATION) → setMode(NORMAL)` + `setSpeakerphoneOn` 切换。
     * 这些都会让 AudioPolicyManager 走一次 `updateCallRouting` / `checkOutputForAllStrategies`，
     * 已存在的 player 有机会被重新 `getOutputForAttr` —— 若此时命中我方 RENDER mix，
     * 其 deviceIds 就会切到我们的 submix（即重路由成功）。
     */
    override fun nudgeRouting(): String {
        val am = audioManager ?: (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?: return "fail 无 AudioManager"
        audioManager = am
        val before = runCatching { am.mode }.getOrDefault(-1)
        val out = StringBuilder("before=$before ")
        runCatching {
            // **禁止切 MODE_IN_COMMUNICATION**：该模式会让正在播放的
            // 媒体（酷狗）失去焦点并**暂停**，导致建链时没有播放源 → 重路由无对象 →
            // 用户感知为"启动后没音效"。改用不改变播放状态的通信设备切换来触发 APM
            // 重算路由。
            am.isSpeakerphoneOn = false
            Thread.sleep(150)
            am.isSpeakerphoneOn = true
            out.append("ok after=${am.mode}（不切 mode，避免暂停媒体）")
        }.onFailure { out.append("fail ${it.javaClass.simpleName}: ${it.message}") }
        log("nudgeRouting: $out")
        return out.toString()
    }

    /**
     * 校验 + 强制重建 track。
     * 重路由只在 player 的 AudioTrack 晚于我方 Remote Submix 设备就绪时创建才生效，
     * 已存在的 track 不迁移 → 同版本代码时好时坏。这里用 pause/play 逼它重建。
     */
    override fun ensureRerouted(excludeUid: Int): String {
        val am = audioManager ?: (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?: return "fail 无 AudioManager"
        audioManager = am
        val reg = runCatching {
            Class.forName("android.media.audiopolicy.AudioPolicy")
                .getMethod("getRegistration").invoke(registeredPolicy) as? String
        }.getOrNull().orEmpty()
        if (reg.isEmpty()) return "fail 无 registration（policy 未注册）"

        fun sh(cmd: String): String = runCatching {
            ProcessBuilder("sh", "-c", cmd).start().inputStream.bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")

        // submix 端口在建链后可能晚好几秒才出现 → 轮询 10×1s。
        // 两种 dump 形态都要认：
        //   ① Port ID: N; "Remote Submix Out"; {AUDIO_DEVICE_OUT_REMOTE_SUBMIX, @:<addr>}
        //   ② [sink 1] Device Port ID: N; {AUDIO_DEVICE_OUT_REMOTE_SUBMIX, @:<addr>}
        var port: String? = null
        for (i in 1..10) {
            port = runCatching {
                val dump = sh("dumpsys media.audio_policy")
                val p1 = Regex("""Port ID: (\d+); "Remote Submix Out"; \{AUDIO_DEVICE_OUT_REMOTE_SUBMIX, @:([^}]*)\}""")
                    .findAll(dump).firstOrNull { it.groupValues[2].contains(reg) }?.groupValues?.get(1)
                if (p1 != null) return@runCatching p1
                Regex("""\[sink \d+\] Device Port ID: (\d+); \{AUDIO_DEVICE_OUT_REMOTE_SUBMIX, @:([^}]*)\}""")
                    .findAll(dump).firstOrNull { it.groupValues[2].contains(reg) }?.groupValues?.get(1)
            }.getOrNull()
            if (port != null) break
            runCatching { Thread.sleep(1_000) }
        }
        val foundPort = port ?: return "fail 拿不到我方 submix port（5 次轮询未就绪）"

        fun mediaPlayers(): List<String> = sh("dumpsys audio").lineSequence()
            .filter { it.contains("state:started") && it.contains("usage=USAGE_MEDIA") }
            .filterNot { it.contains("u/pid:$excludeUid/") }
            .toList()

        fun othersRerouted(): Boolean {
            val lines = mediaPlayers()
            if (lines.isEmpty()) return true
            return lines.all { l ->
                Regex("""deviceIds:\[([^\]]*)\]""").find(l)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim() }?.contains(foundPort) == true
            }
        }

        // **必须先有活跃播放器才谈得上“已重路由”**：
        // 没有播放器时原实现直接返回 ok，导致上层误以为 reroute 成立、跳过静音，
        // 结果既没重路由也没压制 → 双声/无声。
        if (mediaPlayers().isEmpty()) {
            return "skip 无活跃媒体会话（无法判定重路由）port=$port"
        }
        if (othersRerouted()) return "ok 已重路由 port=$port"

        // 实测结论：重路由只在 **track 晚于 submix 设备就绪** 时创建才生效。
        // 我们已保证 mix 先注册，所以「先起引擎、再按播放」必然重路由。
        // 只有「音乐比引擎先播」这一种情况需要逼 track 重建，而重建只能靠 pause→play；
        // 该操作必须**确认恢复播放**，否则用户会静音（实测 play 键可能被忽略）。
        log("ensureRerouted: 已有播放未重路由（port=$foundPort）→ pause/play 重建并确认恢复")
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        runCatching { key(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) }
        runCatching { Thread.sleep(600) }
        for (i in 1..3) {
            runCatching { key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }
            runCatching { Thread.sleep(900) }
            if (mediaPlayers().isNotEmpty()) break
        }
        // 最终确认：至少还在播（哪怕没重路由），绝不留下 paused
        val playing = mediaPlayers().isNotEmpty()
        return when {
            othersRerouted() -> "ok 重建后已重路由 port=$foundPort"
            playing -> "fail 仍未重路由但已恢复播放 port=$foundPort（退化为双声）"
            else -> "fail 播放器未能恢复（请手动播放）port=$foundPort"
        }
    }

    override fun stopSilencer() {
        silencerRecord?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        silencerRecord = null
        silencerThread?.interrupt()
        silencerThread = null
        val p = silencerPolicy
        if (p != null) {
            runCatching {
                audioManager?.let { am ->
                    findMethod(am.javaClass, "unregisterAudioPolicy", p.javaClass)?.invoke(am, p)
                }
            }
        }
        silencerPolicy = null
    }

    override fun stop() {
        streamCallback = null
        running = false
        // 停链路同时释放托管静音与 16k 静音器（服务被停时也不留残留）
        runCatching { unmuteAll() }
        runCatching { stopSilencer() }
        runCatching { readThread?.join(300) }
        readThread = null
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        level = 0f
        // 撤销 AudioPolicy（照抄万象：unregisterAudioPolicy）
        unregisterPolicyQuietly()
        log("已停止并撤销 policy")
    }

    // ---- 内部工具 ----

    /** hidden API 全豁免（照抄万象 HiddenApi.ensureSystemAudioAccess） */
    private fun ensureHiddenApiExemptions(): Boolean {
        val vmRuntimeCls = Class.forName("dalvik.system.VMRuntime")
        val getRuntime = vmRuntimeCls.getDeclaredMethod("getRuntime")
        val runtime = getRuntime.invoke(null)
        val setExemptions = vmRuntimeCls.getDeclaredMethod(
            "setHiddenApiExemptions",
            Array<String>::class.java,
        )
        setExemptions.invoke(runtime, arrayOf("L"))
        return true
    }

    /**
     * 把某个 Context（及其 ContextWrapper 链底层的 ContextImpl）的 attribution
     * 强制改成 (uid, pkg) —— 等价于万象 `ShellContext.getAttributionSource()`
     * 硬编码 uid=2000 的效果，但不需要 @SystemApi 编译。
     *
     * 原理：`AudioRecord.Builder.setContext(ctx)` 内部取 `ctx.getAttributionSource()`，
     * 而该值来自 `ContextImpl.mAttributionSource`（Android 12+ 也可能是
     * `mAttributionSourceState`）。AudioFlinger 用这个 attribution 的 uid 去查
     * RECORD_AUDIO appops：
     *   - app uid(10473) → `foreground` 模式 → UserService 进程不是前台 → 拒绝
     *   - shell uid(2000) → `allow` → 通过
     * 这就是「同机同 ROM 同授权，万象能录我们不能」的最后一道门。
     */
    private fun patchContextAttribution(context: Context, uid: Int, pkg: String): Boolean = runCatching {
        val fake = buildAttributionArg(Class.forName("android.content.AttributionSource"))
            ?: return@runCatching false
        val fakeState = buildAttributionArg(Class.forName("android.content.AttributionSourceState"))

        // 剥到最底层的 ContextImpl（ContextWrapper 链）
        var impl: Context = context
        var guard = 0
        while (impl is android.content.ContextWrapper && guard++ < 8) {
            impl = impl.baseContext
        }
        val cls = impl.javaClass
        var patched = 0
        for (name in listOf("mAttributionSource", "mAttributionSourceState")) {
            val f = runCatching { cls.getDeclaredField(name) }.getOrNull() ?: continue
            val value = if (name == "mAttributionSource") fake else fakeState
            if (value == null) continue
            runCatching {
                f.isAccessible = true
                f.set(impl, value)
                patched++
            }
        }
        log("ContextImpl(${impl.javaClass.simpleName}) attribution 改字段数=$patched（target uid=$uid pkg=$pkg）")
        patched > 0
    }.getOrElse { e ->
        log("patchContextAttribution 失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * 伪造进程级 attribution 为 shell 身份（照抄万象 ProcessIdentity.makeShellAttribution）。
     *
     * 为什么必须这么干：`createPackageContext("com.android.shell")` 拿到的 Context 只有
     * **包名**是 shell，其 `getAttributionSource()` 里的 **uid 仍是本 app 的 10473** ——
     * AppOps 校验 uid-package 一致性时直接拒绝（Cannot create AudioRecord）。
     * 万象的做法是构造 `AttributionSource(uid=2000, pkg=com.android.shell, pid)`，然后
     * **反射遍历 ActivityThread 的全部 AttributionSource 字段（static + instance）全部替换**，
     * 使整个进程后续所有 binder 调用都以 shell 身份出现。
     */
    private fun makeShellAttribution() {
        runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val asCls = Class.forName("android.content.AttributionSource")
            val bCls = Class.forName("android.content.AttributionSource\$Builder")
            val builder = bCls.getConstructor(Int::class.javaPrimitiveType!!)
                .newInstance(Process.myUid())
            runCatching { bCls.getMethod("setPackageName", String::class.java).invoke(builder, SHELL_PKG) }
            runCatching {
                bCls.getMethod("setPid", Int::class.javaPrimitiveType!!).invoke(builder, Process.myPid())
            }
            val fake = bCls.getMethod("build").invoke(builder)

            // Android 15 的 ActivityThread 用 AttributionSourceState（Parcelable，public 字段）
            // 而不是 AttributionSource；两者都要覆盖，否则「替换 0 个字段」白干。
            val asStateCls = runCatching {
                Class.forName("android.content.AttributionSourceState")
            }.getOrNull()
            val fakeState = asStateCls?.let { cls ->
                runCatching {
                    val st = cls.getDeclaredConstructor().newInstance()
                    runCatching { cls.getField("uid").set(st, Process.myUid()) }
                    runCatching { cls.getField("packageName").set(st, SHELL_PKG) }
                    runCatching { cls.getField("pid").set(st, Process.myPid()) }
                    st
                }.getOrNull()
            }

            // 诊断：打印 ActivityThread 里所有 Attribution 相关字段（类型+名字）
            val fieldDiag = atCls.declaredFields
                .filter { it.type.name.contains("Attribution") }
                .joinToString { "${it.name}:${it.type.simpleName}" }
            log("ActivityThread attribution 字段: ${fieldDiag.ifEmpty { "无" }}")

            val thread = runCatching {
                atCls.getMethod("currentActivityThread").invoke(null)
            }.getOrNull()
            var changed = 0
            for (f in atCls.declaredFields) {
                val value = when {
                    asCls.isAssignableFrom(f.type) -> fake
                    asStateCls != null && asStateCls.isAssignableFrom(f.type) -> fakeState
                    else -> continue
                }
                if (value == null) continue
                runCatching {
                    f.isAccessible = true
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                        f.set(null, value)
                    } else if (thread != null) {
                        f.set(thread, value)
                    }
                    changed++
                }
            }
            log("✓ 伪造进程 attribution 完成：替换 $changed 个字段（pkg=$SHELL_PKG）")
        }.onFailure { log("✗ 伪造 attribution 失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 按 `setAttributionSource` 的**实际参数类型**构造 attribution 对象。
     * Android 15 可能已从 `AttributionSource` 改为 `AttributionSourceState`
     * （前者构造需 Builder，后者是 Parcelable 字段直填）——先打印签名再按类型分支，
     * 避免像之前那样按旧类型反射静默失败。
     * pkg 必须与 uid 对应（uid 2000 = com.android.shell），否则 AppOps 校验不过。
     */
    private fun buildAttributionArg(cls: Class<*>): Any? = runCatching {
        when (cls.name) {
            "android.content.AttributionSource" -> {
                val bCls = Class.forName("android.content.AttributionSource\$Builder")
                val b = bCls.getConstructor(Int::class.javaPrimitiveType!!).newInstance(Process.myUid())
                bCls.getMethod("setPackageName", String::class.java).invoke(b, SHELL_PKG)
                bCls.getMethod("build").invoke(b)
            }

            "android.content.AttributionSourceState" -> {
                val st = cls.getDeclaredConstructor().newInstance()
                runCatching { cls.getField("uid").set(st, Process.myUid()) }
                runCatching { cls.getField("packageName").set(st, SHELL_PKG) }
                st
            }

            else -> null
        }
    }.getOrNull()

    private fun findMethod(cls: Class<*>, name: String, vararg types: Class<*>): java.lang.reflect.Method? =
        runCatching { cls.getDeclaredMethod(name, *types).apply { isAccessible = true } }.getOrNull()
            ?: runCatching { cls.getMethod(name, *types).apply { isAccessible = true } }.getOrNull()

    private fun log(line: String) {
        synchronized(trail) { trail.add(line) }
        Log.i("LoopbackProbe", line)
    }

    private fun finishFail(msg: String): String {
        log("✗ $msg")
        running = false
        runCatching { record?.release() }
        record = null
        // 注册成功但后续失败（如 record 建不起来）也必须撤销 policy，否则残留 mix
        // 会占住 USAGE_MEDIA 规则，下次注册被判冲突（本次踩坑实锤）。
        // 但保留 5s 供外部 dump 诊断（本机 logcat 不可用，只能靠 dumpsys 看 mix 是否
        // 真的创建了 output 端口——这是判断 record 为何 state=0 的关键证据）。
        // 立即撤销（安全优先）：mix 一旦匹配到流就会 LOOP_BACK，record 失败时
        // 留下「吃流无消费者」黑洞 → 卡死声（2026-11-04 实锤事故）
        unregisterPolicyQuietly()
        return "失败：$msg"
    }

    private fun unregisterPolicyQuietly() {
        val policy = registeredPolicy ?: return
        runCatching {
            val am = audioManager ?: return
            findMethod(am.javaClass, "unregisterAudioPolicy", policy.javaClass)?.invoke(am, policy)
            log("已撤销 AudioPolicy（防残留）")
        }.onFailure { log("撤销 policy 失败: ${it.javaClass.simpleName}") }
        registeredPolicy = null
    }

    // 注：万象的 ShellContext（重写 getAttributionSource 硬编码 uid=2000）无法在本项目
    // 复刻——`Context.getAttributionSource()` 与 `android.content.AttributionSource`
    // 均为 @SystemApi，public SDK 不可见（2026-11-04 编译实测：Unresolved reference）。
    // 需要 framework stub / 系统签名环境才能编译，属构建体系限制。

    private companion object {
        /** MediaRecorder.AudioSource.REMOTE_SUBMIX = 8 */
        const val PRESET_REMOTE_SUBMIX = 8

        /** AudioRecord.SUBMIX_FIXED_VOLUME 的兜底字面值（反射取不到时用） */
        const val SUBMIX_FIXED_VOLUME_FALLBACK = "fixedVolume"

        /** shell 包名（createPackageContext 用） */
        const val SHELL_PKG = "com.android.shell"

        /** shell uid（attribution 必须指向它才能走 appops allow 分支） */
        const val SHELL_UID = 2000

        /** PCM 回传的交织声道数（record 固定 CHANNEL_IN_STEREO） */
        const val PCM_CHANNELS = 2

        /** 静音增益（与 app 侧原常量一致；-200dB ≈ 不可闻） */
        const val MUTE_GAIN_DB = -200f

        /** 每次 read/回传的帧数（2048 帧 ≈ 21ms @48k，P2③ 平滑供给粒度） */
        // 读粒度（帧）：2048@48k ≈ 42.7ms。
    // 曾试降到 1024 以压低延迟，实测**反而更差**（waterMark 1500 → 4300~4600），
    // 说明该指标不是"积压延迟"而是与供给节奏强相关；无收益故回退，保持 2048。
    const val CHUNK_SAMPLES = 2048

        /** 万象 record 现场实锤的假 attribution 包名（故意拼错的 "android"）：
         *  AudioRecord.Builder.setContext 用它生成 attribution，AppOps 因包不存在而走
         *  「未知包」分支放行，绕开 uid-包名严格校验。 */
        const val FAKE_ATTR_PKG = "anroid"
    }
}
