package com.jiangjie.Sonicraft

import android.content.SharedPreferences
import com.jiangjie.Sonicraft.core.engine.AudioEngine

/**
 * 效果组描述表。
 *
 * ## 要解决的事
 * 「音效库一个卡片 → 点开详情页多个效果器」这套结构（压限器是第一个）后面会反复出现。
 * 如果每个组都把「总闸 / 各槽开关 / 旁通下发 / 重启重放」各写一遍，必然漏一处 ——
 * 本项目已经在这类问题上踩过三次（参数三处定义、重放漏 param 6、sidechain 死参数）。
 *
 * ## 因此把结构描述成数据
 * ```
 * EffectGroupSpec  一个音效库卡片
 *   ├─ sectionPrefKey   总闸（关 = 整组旁通，各槽记忆不动）
 *   └─ slots[]          组内每个效果 = 一个 DSP 节点 + 一个开关记忆
 * ```
 * 实际出声 = `总闸 && 该槽记忆`。这个公式只写一次（见 [shouldPlay]），
 * 重放、旁通计算、默认值迁移全部复用它。
 *
 * ## 接入一个新组要做的事
 * 1. 在 [EffectGroups] 里加一条 spec（槽的 key 要与 prefs 命名一致：`{key}_enabled`）
 * 2. 详情页按 spec 渲染槽卡片（卡头可复用 `ScSlotHeader`）
 * 3. 音效库卡片绑 `sectionEnabled`
 * 4. `AudioChainManager` 的重放会**自动**覆盖它（遍历 [EffectGroups.all]）
 *
 * ## 没有做成什么
 * 刻意**没有**做通用的「状态控制器类」。当前只有一个使用者，把 StateFlow 也抽走会让
 * 现有 20 多处 `viewModel.multibandEnabled` 全得改，收益不抵风险。
 * 等第二个组真正落地时，再决定要不要把状态也收进控制器。
 */
data class EffectSlotSpec(
    /** 槽标识，同时决定 prefs key：`{key}_enabled` */
    val key: String,
    /** 对应的 DSP 节点索引（[AudioEngine] 里的常量） */
    val nodeIndex: Int,
) {
    val enabledPrefKey: String get() = "${key}_enabled"
}

data class EffectGroupSpec(
    /** 组标识，同时决定总闸 prefs key：`{id}_section_enabled` */
    val id: String,
    /** 组内效果槽；顺序即详情页卡片顺序 */
    val slots: List<EffectSlotSpec>,
    /**
     * 槽之间是否互斥（同一时刻最多一个出声）。
     * 压限器为 true：多段与单段同时开会叠两次压缩，属于误用。
     */
    val exclusive: Boolean = false,
) {
    val sectionPrefKey: String get() = "${id}_section_enabled"

    /**
     * 读总闸。**缺省值按「任一槽开着就是开」推断**：
     * 老用户升级后不会莫名变关；全新安装时各槽都 false → 总闸也关
     * （与「效果器默认透明」的既有约定一致）。
     */
    fun readSection(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(
            sectionPrefKey,
            slots.any { prefs.getBoolean(it.enabledPrefKey, false) },
        )

    fun readSlot(prefs: SharedPreferences, slotKey: String): Boolean =
        prefs.getBoolean("${slotKey}_enabled", false)

    /** 该槽当前是否应该出声：总闸 && 该槽记忆。重放与旁通计算共用这一个公式。 */
    fun shouldPlay(prefs: SharedPreferences, slotKey: String): Boolean =
        readSection(prefs) && readSlot(prefs, slotKey)
}

/** 全部效果组。新增组只需在这里加一条。 */
object EffectGroups {

    /** 压限器：多段压限 + 单段压缩器（互斥） */
    val COMPRESSOR = EffectGroupSpec(
        id = "compressor",
        slots = listOf(
            EffectSlotSpec("multiband", AudioEngine.MULTIBAND_NODE_INDEX),
            EffectSlotSpec("compressor", AudioEngine.COMPRESSOR_NODE_INDEX),
        ),
        exclusive = true,
    )

    /**
     * 混响：卷积混响 + 算法混响（FDN）。
     *
     * `exclusive = false`：两者叠加是常见做法（卷积给空间底、算法补尾巴），
     * 不像压限器的「多段 + 单段」那样属于误用。
     *
     * 槽 key 沿用既有 prefs（`reverb_enabled` / `fdn_enabled`），所以不能按
     * 效果名取，必须与历史 key 对齐 —— 这也是 [EffectSlotSpec] 的 key
     * 同时充当 prefs 前缀的原因。
     */
    val REVERB = EffectGroupSpec(
        id = "reverb",
        slots = listOf(
            EffectSlotSpec("reverb", AudioEngine.CONVOLVER_NODE_INDEX),
            EffectSlotSpec("fdn", AudioEngine.ALGORITHMIC_REVERB_NODE_INDEX),
        ),
    )

    /**
     * 低频管家：动态低音 + 低音增强 + 虚拟低音。
     *
     * **三者可同时开启**。原判定互斥的理由是
     * 「虚拟低音丢弃 <120Hz，会让另外两个白做」—— 但那是设计者推断，
     * 不是用户用法；叠加听感如何应由用户判断，工具不该替他决定。
     *
     * 唯一的设备相关约束在 UI 层：耳机输出时虚拟低音**默认关**，
     * 且每次开启弹风险提示（见 `ScLowfreqGroupDetail`）。
     *
     * 槽 key 沿用既有 `dynamic_bass_enabled`（老用户升级后记忆不丢）。
     */
    val LOWFREQ = EffectGroupSpec(
        id = "lowfreq",
        slots = listOf(
            // 动态低音：LR4 分频重构 + 动态增强 + 单元保护（**设备无关**）
            EffectSlotSpec("dynamic_bass", AudioEngine.DYNAMIC_BASS_NODE_INDEX),
            // 低音增强：M/S + EQ + 瞬态 + 次谐波（**设备无关**，移植自 wecho「低频质感」）
            EffectSlotSpec("bass_enhance", AudioEngine.BASS_ENHANCE_NODE_INDEX),
            // 虚拟低音：心理声学谐波翻译（**只适合外放**）
            EffectSlotSpec("virtual_bass", AudioEngine.VIRTUAL_BASS_NODE_INDEX),
        ),
        // **取消互斥**：三者可同时开启。
        // 之前判定互斥的理由是「虚拟低音丢弃 <120Hz，会让另外两个白做」——
        // 但那是设计者推断，不是用户用法。叠加听感如何应由用户判断，
        // 工具不该替用户决定。设备相关约束只保留一条：
        // 耳机输出时虚拟低音**默认关**，且每次开启弹风险提示（见 ScLowfreqGroupDetail）。
        exclusive = false,
    )

    val all: List<EffectGroupSpec> = listOf(COMPRESSOR, REVERB, LOWFREQ)
}
