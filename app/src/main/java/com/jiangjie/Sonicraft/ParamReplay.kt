package com.jiangjie.Sonicraft

import android.content.SharedPreferences
import com.jiangjie.Sonicraft.core.engine.AudioEngine

/**
 * 参数重放表（表驱动）。
 *
 * ## 为什么要有这个文件
 * 「引擎重建后补发参数」原本有**两套手写清单**：前台 `MainViewModel.applyEqSettings()`
 * （334 行）与 headless `AudioChainManager.replaySettings()`（350 行）。逐条比对后发现
 * 两者**各自残缺**，不是同一份逻辑写两遍：
 *
 * | 参数 | 前台 | headless |
 * |---|---|---|
 * | `VS_PARAM_AMBISONIC` / `HRTF_MODEL` / `VBAP_SPREAD` / `AMBI_FOCUS` | 有 | **无** |
 * | `VS_PARAM_SURROUND_GAIN` / `CENTER_GAIN` | 用用户值 | **硬编码 0f** |
 * | `VS_PARAM_BS775` / `DISTANCE` / `HEIGHT_STRENGTH` | **无** | 有 |
 * | `LIMITER_PARAM_TRUE_PEAK` | 有（常量 1f） | **无** |
 *
 * 于是「进程被系统杀掉后自愈」会出现：Ambisonic / 实测 HRIR 丢失、环绕增益被清零、
 * 真峰值检测关闭 —— 全是静默失效，用户只看到「声音不一样了」。
 *
 * ## 约定
 * - **prefs 是唯一真相**：两条路径都从 [SharedPreferences] 读，不再各自持有清单；
 * - **类型必须写对**：`vs_mode` / `vs_room_size` 等是 `Int` 键，`getFloat` 会抛
 *   `ClassCastException` 被外层 `runCatching` 吞掉 → 整段重放静默中断（历史事故）；
 * - 新增参数只改这张表，两条路径自动同时生效。
 *
 * ## 覆盖面（分批）
 * - 第一批：虚拟环绕（VS）+ 限幅器（LIMITER）
 * - 待迁：EQ 段（含 Q/频率/类型/pre-gain）、压限器/多段、混响、增益/平衡/宽度、
 *   低频管家、S71、脚本机架
 */
object ParamReplay {

    private val VS = AudioEngine.VIRTUAL_SURROUND_NODE_INDEX
    private val LIMITER = AudioEngine.LIMITER_NODE_INDEX

    /** 一条「float 直读」参数：节点 + 参数 id + prefs 键 + 缺省值 */
    private data class P(val id: Int, val key: String, val def: Float, val node: Int)

    /** 一条「int 键」参数（必须用 getInt 读，否则 ClassCastException） */
    private data class Pi(val id: Int, val key: String, val def: Int, val node: Int)

    /** 一条「bool → 1f/0f」参数 */
    private data class Pb(val id: Int, val key: String, val def: Boolean, val node: Int)

    // ---- 虚拟环绕：float 直读 ----
    private val VS_FLOATS = listOf(
        P(AudioEngine.VS_PARAM_SURROUND_GAIN, "vs_surround_gain", 0f, VS),
        P(AudioEngine.VS_PARAM_CENTER_GAIN, "vs_center_gain", 0f, VS),
        P(AudioEngine.VS_PARAM_DISTANCE, "vs_distance", 0.5f, VS),
        P(AudioEngine.VS_PARAM_HEIGHT_STRENGTH, "vs_height", 0.5f, VS),
        P(AudioEngine.VS_PARAM_STEREO_EXPAND, "vs_rear_space", 0.5f, VS),
        P(AudioEngine.VS_PARAM_ROOM_WET_DRY, "vs_room_wet", 0.35f, VS),
        P(AudioEngine.VS_PARAM_ENCODE_STRENGTH, "vs_encode_strength", 0.5f, VS),
        P(AudioEngine.VS_PARAM_DIALOG, "vs_dialog", 0f, VS),
        P(AudioEngine.VS_PARAM_BASS_ENHANCER, "vs_bass_enh", 0f, VS),
        P(AudioEngine.VS_PARAM_REAR_CENTER_DELAY_MS, "vs_rear_delay", 5f, VS),
        P(AudioEngine.VS_PARAM_VBAP_SPREAD, "vs_vbap_spread", 0.25f, VS),
        P(AudioEngine.VS_PARAM_AMBI_FOCUS, "vs_ambi_focus", 1f, VS),
    )

    // ---- 虚拟环绕：int 键 ----
    private val VS_INTS = listOf(
        Pi(AudioEngine.VS_PARAM_MODE, "vs_mode", 0, VS),
        Pi(AudioEngine.VS_PARAM_CENTER_MODE, "vs_center_mode", 1, VS),
        Pi(AudioEngine.VS_PARAM_BASS_ENHANCER_MODE, "vs_bass_enh_mode", 0, VS),
        Pi(AudioEngine.VS_PARAM_ROOM_SIZE, "vs_room_size", 0, VS),
        Pi(AudioEngine.VS_PARAM_HRTF_MODEL, "vs_hrtf_model", 0, VS),
    )

    // ---- 虚拟环绕：bool → 1f/0f ----
    private val VS_BOOLS = listOf(
        Pb(AudioEngine.VS_PARAM_BS775, "vs_bs775", false, VS),
        Pb(AudioEngine.VS_PARAM_DEBUG_RAW, "vs_debug_raw", false, VS),
        Pb(AudioEngine.VS_PARAM_REAR_CENTER_ENABLED, "vs_rear_center", false, VS),
    )

    /** 声道顺序与界面一致：L / C / R / SL / SR / CS / LFE */
    private val VS_TRIM_IDS = intArrayOf(
        AudioEngine.VS_PARAM_TRIM_L, AudioEngine.VS_PARAM_TRIM_C, AudioEngine.VS_PARAM_TRIM_R,
        AudioEngine.VS_PARAM_TRIM_SL, AudioEngine.VS_PARAM_TRIM_SR, AudioEngine.VS_PARAM_TRIM_CS,
        AudioEngine.VS_PARAM_TRIM_LFE,
    )
    private val VS_MUTE_IDS = intArrayOf(
        AudioEngine.VS_PARAM_MUTE_L, AudioEngine.VS_PARAM_MUTE_C, AudioEngine.VS_PARAM_MUTE_R,
        AudioEngine.VS_PARAM_MUTE_SL, AudioEngine.VS_PARAM_MUTE_SR, AudioEngine.VS_PARAM_MUTE_CS,
        AudioEngine.VS_PARAM_MUTE_LFE,
    )

    // ---- 限幅器 ----
    private val LIMITER_FLOATS = listOf(
        P(AudioEngine.LIMITER_PARAM_CEILING_DB, "limiter_ceiling", -1f, LIMITER),
        P(AudioEngine.LIMITER_PARAM_GATE_LOW_DB, "limiter_gate_low", -5f, LIMITER),
        P(AudioEngine.LIMITER_PARAM_GATE_MID_DB, "limiter_gate_mid", -5f, LIMITER),
        P(AudioEngine.LIMITER_PARAM_GATE_HIGH_DB, "limiter_gate_high", -2f, LIMITER),
    )

    /**
     * 虚拟环绕全量重放。
     *
     * 旁路判据与前台一致：**矩阵环绕 或 Ambisonic 任一开启**即工作
     * （只认 `vs_enabled` 会把「只开 Ambisonic」整节点旁路，实测踩过）。
     */
    fun applyVs(engine: AudioEngine, prefs: SharedPreferences) {
        val ambiOn = prefs.getBoolean("vs_ambisonic_enabled", false)
        val surroundOn = prefs.getBoolean("vs_enabled", false) || ambiOn

        engine.setBypass(AudioEngine.VIRTUAL_SURROUND_NODE_INDEX, !surroundOn)
        engine.setParam(VS, AudioEngine.VS_PARAM_ENABLED, if (surroundOn) 1f else 0f)

        for (p in VS_FLOATS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
        for (p in VS_INTS) engine.setParam(p.node, p.id, prefs.getInt(p.key, p.def).toFloat())
        for (p in VS_BOOLS) engine.setParam(p.node, p.id, if (prefs.getBoolean(p.key, p.def)) 1f else 0f)

        // 解码路径：关掉 Ambisonic 时必须显式发 0，否则节点停在上一轮的 HOA/VBAP
        engine.setParam(
            VS,
            AudioEngine.VS_PARAM_AMBISONIC,
            if (ambiOn) prefs.getInt("vs_ambi_decoder", 1).toFloat() else 0f,
        )

        val trims = loadFloats(prefs, "vs_trims", 7, 0f, -12f, 12f)
        val mutes = loadFloats(prefs, "vs_mutes", 7, 0f, 0f, 1f)
        for (i in 0..6) {
            engine.setParam(VS, VS_TRIM_IDS[i], trims[i])
            engine.setParam(VS, VS_MUTE_IDS[i], mutes[i])
        }
    }

    /**
     * 限幅器全量重放。
     *
     * `TRUE_PEAK` 是安全网常量（BS.1770 4x 过采样防 DAC 重构过冲穿透 ceiling），
     * 无 UI 开关，恒发 1f —— headless 此前漏了这一项，自愈后保护静默关闭。
     */
    fun applyLimiter(engine: AudioEngine, prefs: SharedPreferences) {
        engine.setBypass(AudioEngine.LIMITER_NODE_INDEX, !prefs.getBoolean("limiter_enabled", true))
        for (p in LIMITER_FLOATS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
        engine.setParam(
            LIMITER,
            AudioEngine.LIMITER_PARAM_DC_BLOCK,
            if (prefs.getBoolean("limiter_dc_block", true)) 1f else 0f,
        )
        engine.setParam(LIMITER, AudioEngine.LIMITER_PARAM_TRUE_PEAK, 1f)
    }

    /**
     * trim / mute 以逗号分隔字符串存储（与 `MainViewModel.saveVsFloats` 同一格式）。
     * 越界值按 [lo, hi] 夹紧：老版本可能写入过更大范围。
     */
    private fun loadFloats(
        prefs: SharedPreferences,
        key: String,
        count: Int,
        def: Float,
        lo: Float,
        hi: Float,
    ): FloatArray {
        val out = FloatArray(count) { def }
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isBlank()) return out
        raw.split(',').take(count).forEachIndexed { i, t ->
            val v = t.trim().toFloatOrNull()
            if (v != null && v.isFinite()) out[i] = v.coerceIn(lo, hi)
        }
        return out
    }

    // ================= 第二批：增益 / 平衡 / 宽度 =================

    /**
     * 输入增益 / 平衡 / 输出增益 / 立体声宽度。
     *
     * 旁路语义与前台一致：**中性值旁路**（1× / 0 / 1×）。输出级烘焙了 +3 dB 基准，
     * 旁路时基准仍生效，所以「值不动就不必开节点」。
     */
    fun applyGainBalanceWidth(engine: AudioEngine, prefs: SharedPreferences) {
        val inGain = prefs.getFloat("input_gain", 1f)
        val balance = prefs.getFloat("balance", 0f)
        val outGain = prefs.getFloat("output_gain", 1f)

        engine.setBypass(AudioEngine.INPUT_GAIN_NODE_INDEX, inGain == 1f)
        engine.setBypass(AudioEngine.BALANCE_NODE_INDEX, balance == 0f)
        engine.setBypass(AudioEngine.OUTPUT_GAIN_NODE_INDEX, outGain == 1f)

        engine.setParam(AudioEngine.INPUT_GAIN_NODE_INDEX, AudioEngine.GAIN_PARAM_GAIN, inGain)
        engine.setParam(AudioEngine.BALANCE_NODE_INDEX, AudioEngine.BALANCE_PARAM_BALANCE, balance)
        engine.setParam(AudioEngine.OUTPUT_GAIN_NODE_INDEX, AudioEngine.GAIN_PARAM_GAIN, outGain)
        engine.setParam(
            AudioEngine.STEREO_WIDTH_NODE_INDEX,
            AudioEngine.STEREO_WIDTH_PARAM_WIDTH,
            prefs.getFloat("stereo_width", 1f),
        )
        engine.setBypass(
            AudioEngine.STEREO_WIDTH_NODE_INDEX,
            !prefs.getBoolean("stereo_width_enabled", true),
        )
    }

    // ================= 第二批：压限器（单段压缩） =================

    private val COMPRESSOR_PARAMS = listOf(
        P(AudioEngine.COMPRESSOR_PARAM_THRESHOLD_DB, "compressor_threshold", -20f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(AudioEngine.COMPRESSOR_PARAM_RATIO, "compressor_ratio", 4f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(AudioEngine.COMPRESSOR_PARAM_ATTACK_MS, "compressor_attack_ms", 10f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(AudioEngine.COMPRESSOR_PARAM_RELEASE_MS, "compressor_release_ms", 150f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(AudioEngine.COMPRESSOR_PARAM_KNEE_DB, "compressor_knee_db", 6f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(AudioEngine.COMPRESSOR_PARAM_MAKEUP_DB, "compressor_makeup_db", 0f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(
            AudioEngine.COMPRESSOR_PARAM_SIDECHAIN_HP_HZ, "compressor_sidechain_hp", 120f,
            AudioEngine.COMPRESSOR_NODE_INDEX,
        ),
        P(AudioEngine.COMPRESSOR_PARAM_MIX, "compressor_mix", 1f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(AudioEngine.COMPRESSOR_PARAM_DETECT_MODE, "compressor_detect_mode", 0f, AudioEngine.COMPRESSOR_NODE_INDEX),
        P(
            AudioEngine.COMPRESSOR_PARAM_LOOKAHEAD_MS, "compressor_lookahead_ms", 0f,
            AudioEngine.COMPRESSOR_NODE_INDEX,
        ),
        P(AudioEngine.COMPRESSOR_PARAM_OVERSAMPLE, "compressor_oversample", 1f, AudioEngine.COMPRESSOR_NODE_INDEX),
    )

    /** 单段压缩六参 + 侧链/混合/检测/前瞻/过采样。旁路由 [EffectGroups] 统一处理。 */
    fun applyCompressor(engine: AudioEngine, prefs: SharedPreferences) {
        for (p in COMPRESSOR_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
    }

    // ================= 第二批：多段 =================

    /**
     * 多段参数 0..19。
     *
     * 与旧代码的差别：循环上界改为 `MULTIBAND_PARAM_COUNT - 1`（旧代码写 `0..20`，
     * 多发一个越界 id 21）；并且**无条件**下发（旧代码只在 `prefs.contains` 时才发，
     * 全新安装会漏掉一半参数）。
     */
    fun applyMultiband(engine: AudioEngine, prefs: SharedPreferences) {
        for (i in 0 until AudioEngine.MULTIBAND_PARAM_COUNT) {
            engine.setParam(
                AudioEngine.MULTIBAND_NODE_INDEX,
                i,
                prefs.getFloat("multiband_param_$i", 0f),
            )
        }
    }

    // ================= 第二批：混响（卷积 + FDN） =================

    private val CONVOLVER_PARAMS = listOf(
        P(AudioEngine.CONVOLVER_PARAM_WET, "user_wet_gain", 1f, AudioEngine.CONVOLVER_NODE_INDEX),
        P(AudioEngine.CONVOLVER_PARAM_DRY, "reverb_dry", 1f, AudioEngine.CONVOLVER_NODE_INDEX),
        P(AudioEngine.CONVOLVER_PARAM_LOWCUT, "reverb_lowcut", 0f, AudioEngine.CONVOLVER_NODE_INDEX),
    )

    private val FDN_PARAMS = listOf(
        P(AudioEngine.REVERB_PARAM_ROOM_SIZE, "fdn_room_size", 0.5f, AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX),
        P(AudioEngine.REVERB_PARAM_HUMIDITY, "fdn_humidity", 0.5f, AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX),
        P(AudioEngine.REVERB_PARAM_STEREO_WIDTH, "fdn_stereo_width", 1f, AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX),
        P(AudioEngine.REVERB_PARAM_WET, "fdn_wet", 0.3f, AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX),
        P(AudioEngine.REVERB_PARAM_DRY, "fdn_dry", 1f, AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX),
    )

    /**
     * 卷积混响 + 算法混响（FDN）参数。
     *
     * 旁路由 [EffectGroups.REVERB] 统一处理（两者可同时开）。
     * 卷积的 `preset` / FDN 的 `mode` 是 int 键，必须 `getInt`。
     */
    fun applyReverb(engine: AudioEngine, prefs: SharedPreferences) {
        engine.setParam(
            AudioEngine.CONVOLVER_NODE_INDEX,
            AudioEngine.CONVOLVER_PARAM_PRESET,
            prefs.getInt("reverb_preset", AudioEngine.REVERB_PRESET_SMALL_ROOM).toFloat(),
        )
        for (p in CONVOLVER_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
        for (p in FDN_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
        // fdn_mode 在 prefs 里是 **Float**（实测 <float>，不是 <int>）；
        // 用 getInt 读会抛 ClassCastException 并被外层 runCatching 吞掉 → 整段重放中断。
        engine.setParam(
            AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX,
            AudioEngine.REVERB_PARAM_MODE,
            prefs.getFloat("fdn_mode", 0f),
        )
    }

    // ================= 第二批：低频管家（动态低音 / 低音增强 / 虚拟低音） =================

    private val DYNAMIC_BASS_PARAMS = listOf(
        P(AudioEngine.DYNAMIC_BASS_PARAM_AMOUNT, "dynamic_bass_amount", 0.35f, AudioEngine.DYNAMIC_BASS_NODE_INDEX),
        P(
            AudioEngine.DYNAMIC_BASS_PARAM_SUBSONIC_HZ, "dynamic_bass_subsonic_hz", 30f,
            AudioEngine.DYNAMIC_BASS_NODE_INDEX,
        ),
        P(AudioEngine.DYNAMIC_BASS_PARAM_FOCUS_HZ, "dynamic_bass_focus_hz", 80f, AudioEngine.DYNAMIC_BASS_NODE_INDEX),
        P(
            AudioEngine.DYNAMIC_BASS_PARAM_BOOST_DEPTH, "dynamic_bass_boost_depth", 0.5f,
            AudioEngine.DYNAMIC_BASS_NODE_INDEX,
        ),
        P(
            AudioEngine.DYNAMIC_BASS_PARAM_PROTECT_LEVEL, "dynamic_bass_protect_level", 0.6f,
            AudioEngine.DYNAMIC_BASS_NODE_INDEX,
        ),
    )

    private val BASS_ENHANCE_PARAMS = listOf(
        P(AudioEngine.BASS_ENHANCE_PARAM_AMOUNT, "bass_enhance_amount", 0.5f, AudioEngine.BASS_ENHANCE_NODE_INDEX),
        P(AudioEngine.BASS_ENHANCE_PARAM_DEEP, "bass_enhance_deep", 0.3f, AudioEngine.BASS_ENHANCE_NODE_INDEX),
        P(AudioEngine.BASS_ENHANCE_PARAM_PUNCH, "bass_enhance_punch", 0.6f, AudioEngine.BASS_ENHANCE_NODE_INDEX),
        P(
            AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_GAIN, "bass_enhance_classic_gain", 6f,
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
        ),
        P(
            AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_Q, "bass_enhance_classic_q", 0.7071f,
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
        ),
        P(
            AudioEngine.BASS_ENHANCE_PARAM_CLASSIC_CENTER, "bass_enhance_classic_center", 60f,
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
        ),
    )

    private val VIRTUAL_BASS_PARAMS = listOf(
        P(AudioEngine.VIRTUAL_BASS_PARAM_AMOUNT, "virtual_bass_amount", 0.80f, AudioEngine.VIRTUAL_BASS_NODE_INDEX),
        P(
            AudioEngine.VIRTUAL_BASS_PARAM_HARMONIC, "virtual_bass_harmonic", 1.30f,
            AudioEngine.VIRTUAL_BASS_NODE_INDEX,
        ),
        P(AudioEngine.VIRTUAL_BASS_PARAM_MID_GAIN, "virtual_bass_mid_gain", 0.20f, AudioEngine.VIRTUAL_BASS_NODE_INDEX),
        P(
            AudioEngine.VIRTUAL_BASS_PARAM_HIGH_GAIN, "virtual_bass_high_gain", 0.60f,
            AudioEngine.VIRTUAL_BASS_NODE_INDEX,
        ),
        P(
            AudioEngine.VIRTUAL_BASS_PARAM_ENVELOPE_RATE, "virtual_bass_envelope_rate", 80f,
            AudioEngine.VIRTUAL_BASS_NODE_INDEX,
        ),
    )

    /**
     * 低频管家三个节点的参数。
     *
     * 两个模式键是 int：`bass_enhance_mode` 曾被 `getFloat` 读而抛 ClassCastException，
     * 把整段重放静默中断（历史事故），这里统一 `getInt`。旁路由 [EffectGroups.LOWFREQ] 处理。
     */
    fun applyLowfreq(engine: AudioEngine, prefs: SharedPreferences) {
        for (p in DYNAMIC_BASS_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
        for (p in BASS_ENHANCE_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
        engine.setParam(
            AudioEngine.BASS_ENHANCE_NODE_INDEX,
            AudioEngine.BASS_ENHANCE_PARAM_MODE,
            prefs.getInt("bass_enhance_mode", 1).toFloat(),
        )
        for (p in VIRTUAL_BASS_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
    }

    // ================= 第二批：S71 =================

    private val S71_PARAMS = listOf(
        P(AudioEngine.S71_PARAM_UPMIX, "s71_upmix", 0.85f, AudioEngine.S71_NODE_INDEX),
        P(AudioEngine.S71_PARAM_EXTERNALIZE, "s71_externalize", 0.35f, AudioEngine.S71_NODE_INDEX),
        P(AudioEngine.S71_PARAM_BASS_BOOST_DB, "s71_bass_boost", 2.0f, AudioEngine.S71_NODE_INDEX),
        P(AudioEngine.S71_PARAM_BASS_MANAGEMENT, "s71_bass_mgmt", 0.25f, AudioEngine.S71_NODE_INDEX),
    )

    /**
     * Surround71：旁路 + `enabled` 参数 + 四参。
     *
     * **必须同时下发 `S71_PARAM_ENABLED`**：节点内该参数默认 0，仅解除 bypass 仍会直通，
     * 表现为「开关显示开启却没有效果」（开机初始化缺陷）。
     */
    fun applyS71(engine: AudioEngine, prefs: SharedPreferences) {
        val on = prefs.getBoolean("s71_enabled", false)
        engine.setBypass(AudioEngine.S71_NODE_INDEX, !on)
        engine.setParam(
            AudioEngine.S71_NODE_INDEX,
            AudioEngine.S71_PARAM_ENABLED,
            if (on) 1f else 0f,
        )
        for (p in S71_PARAMS) engine.setParam(p.node, p.id, prefs.getFloat(p.key, p.def))
    }

    // ================= 第三批：多段 EQ（PEQ / AutoEQ） =================

    /**
     * 多段 EQ 全量重放。
     *
     * `eq_mode` 的值**就是段数**（缺省 10，钳到 1~31），与 `EQ_PARAM_BAND_COUNT` 同义。
     * 段数据按键存储（`eq_bands_<mode>` / `eq_q_<mode>` / `eq_type_<mode>` / `eq_freq_<mode>`），
     * 交给 [EqConfigCodec] 解析：缺省或脏值降级为默认，不抛异常。
     *
     * 修掉的历史问题：旧 headless 读的是 `eq_bands_10`（只有增益、且早已过期的历史键），
     * 于是自愈后 EQ 曲线是拿旧数据在放；band_count 还硬编码成 10f，非 10 段模式整段错位。
     */
    fun applyEq(engine: AudioEngine, prefs: SharedPreferences) {
        val mode = prefs.getInt("eq_mode", 10).coerceIn(1, 31)

        // 组总闸（PEQ）
        engine.setBypass(AudioEngine.EQ_NODE_INDEX, !prefs.getBoolean("eq_enabled", false))
        // AutoEQ 与 PEQ 共用节点区但参数独立，旁路必须单独判定（组总闸 && 槽开关）
        engine.setBypass(
            AudioEngine.AUTO_EQ_NODE_INDEX,
            !(prefs.getBoolean("eq_section_enabled", true) &&
                prefs.getBoolean("auto_eq_enabled", false)),
        )

        engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_PARAM_BAND_COUNT, mode.toFloat())

        EqConfigCodec.readBands(prefs, mode).forEachIndexed { i, g ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, i, g)
        }
        EqConfigCodec.readQs(prefs, mode).forEachIndexed { i, q ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_Q_BASE + i, q)
        }
        EqConfigCodec.readFreqs(prefs, mode).forEachIndexed { i, f ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_FREQ_BASE + i, f)
        }
        EqConfigCodec.readTypes(prefs, mode).forEachIndexed { i, t ->
            engine.setParam(AudioEngine.EQ_NODE_INDEX, AudioEngine.EQ_TYPE_BASE + i, t.toFloat())
        }
        engine.setParam(
            AudioEngine.EQ_NODE_INDEX,
            AudioEngine.EQ_PARAM_PREGAIN_DB,
            prefs.getFloat("eq_pregain", 0f),
        )
    }
}
