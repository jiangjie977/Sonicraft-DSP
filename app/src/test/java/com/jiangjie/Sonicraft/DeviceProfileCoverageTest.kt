package com.jiangjie.Sonicraft

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备配置覆盖守卫。
 *
 * 背景：输出设备切换依赖两份**手写白名单** ——
 *   · `snapshotParams()`：切换前把当前配置存成该设备的快照；
 *   · `applyParam()`：切回该设备时按快照逐项回放。
 * 新增效果器时只改其一、或两个都忘，编译与运行都正常，只有用户切设备时才发现
 * "设置没跟过来"（2026-11-14 低频管家整组漏登记就是这么发生的）。
 *
 * 这个测试不看运行结果，只做静态交叉校验：
 *   1. 每个 `<X>Enabled` 的 StateFlow 都必须在 `snapshotParams` 里登记（显式例外见 [NOT_DEVICE_FOLLOWED]）；
 *   2. 快照里的每个键都必须在 `applyParam` 里有一条回放分支（否则"存了不还原"）。
 *
 * 断言失败时按提示补键即可；若是**故意**不跟设备（全局行为开关），加进 [NOT_DEVICE_FOLLOWED] 并写原因。
 */
class DeviceProfileCoverageTest {

    private val vmSource: String by lazy {
        val f = File("src/main/java/com/jiangjie/Sonicraft/MainViewModel.kt")
        assertTrue(
            "找不到 MainViewModel.kt（测试工作目录=${File(".").absolutePath}）",
            f.exists(),
        )
        f.readText()
    }

    /** 故意不随设备切换的开关：全局行为，跟设备无关。 */
    private val notDeviceFollowed = setOf(
        "auto_takeover_enabled",       // 接管策略是全局行为
        "virtual_bass_headphone_ack",  // 耳机风险确认标记，不是音效参数
        // 机架脚本文本已随字符串段跟随设备（script_src_N），总闸也已登记，
        // 所以这里不再需要 script_effect_enabled 的例外。
    )

    /** 取某个顶层函数的函数体（按缩进 4 的 `    }` 结束）。 */
    private fun functionBody(signature: String): String {
        val start = vmSource.indexOf(signature)
        assertTrue("源码里找不到函数：$signature", start >= 0)
        var i = vmSource.indexOf('\n', start)
        while (i >= 0) {
            val next = vmSource.indexOf('\n', i + 1)
            if (next < 0) break
            val line = vmSource.substring(i + 1, next)
            if (line == "    }") return vmSource.substring(start, next)
            i = next
        }
        return vmSource.substring(start)
    }

    @Test
    fun everyEffectSwitchIsRegisteredInDeviceSnapshot() {
        val snapshot = functionBody("private fun snapshotParams()")
        val snapshotKeys = Regex("\"([a-z0-9_]+)\"\\s+to")
            .findAll(snapshot)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("snapshotParams 解析失败，键数=${snapshotKeys.size}", snapshotKeys.size > 30)

        // 所有 *Enabled 的 StateFlow → 下划线命名
        val enabledFlows = Regex("private val _(\\w+?Enabled)\\b")
            .findAll(vmSource)
            .map { camelToSnake(it.groupValues[1]) }
            .toSet()
        assertTrue("未解析到任何 *Enabled 状态，正则可能失效", enabledFlows.isNotEmpty())

        val missing = enabledFlows
            .filter { it !in snapshotKeys && it !in notDeviceFollowed }
            .sorted()

        assertTrue(
            "以下效果器开关没有登记进设备快照（切换设备时会不跟随）：\n  " +
                missing.joinToString("\n  ") +
                "\n请在 snapshotParams() 里补键 + applyParam() 里补分支；" +
                "若确实不该跟随设备，加进 DeviceProfileCoverageTest.notDeviceFollowed 并写明原因。",
            missing.isEmpty(),
        )
    }

    @Test
    fun everySnapshotKeyHasApplyBranch() {
        val snapshot = functionBody("private fun snapshotParams()")
        val snapshotKeys = Regex("\"([a-z0-9_]+)\"\\s+to")
            .findAll(snapshot)
            .map { it.groupValues[1] }
            .toSet()

        val applyBody = functionBody("private fun applyParam(key: String, v: Float)")
        val applyKeys = Regex("\"([a-z0-9_]+)\"\\s*->")
            .findAll(applyBody)
            .map { it.groupValues[1] }
            .toSet()

        // 带前缀的批量参数（multiband_param_*）用 startsWith 处理，不做逐键比对
        val prefixed = snapshotKeys.filter { it.startsWith("multiband_param_") }
        val missing = snapshotKeys
            .filter { it !in applyKeys && it !in prefixed }
            .sorted()

        assertTrue(
            "以下键会被存进设备快照，但 applyParam() 没有回放分支（存了不还原）：\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty(),
        )
    }

    private fun camelToSnake(s: String): String =
        s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}
