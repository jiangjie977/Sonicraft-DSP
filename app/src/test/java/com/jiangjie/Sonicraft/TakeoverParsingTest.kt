/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-4 测试金字塔：TakeoverController 的 dumpsys 解析纯函数测试。
 * 样本取自 NX733J 真实 dumpsys audio 活跃播放快照的格式特征。
 */
class TakeoverParsingTest {

    private val selfUid = 10541

    @Test
    fun `解析媒体与游戏 usage 为活跃快照`() {
        val dump = """
            AudioPlaybackConfiguration:
              u/pid:10099/1234 state:started usage=USAGE_MEDIA
              u/pid:10102/5678 state:started usage=USAGE_GAME
        """.trimIndent()

        val result = TakeoverController.Companion.parseActiveUsages(dump, selfUid)

        assertEquals(setOf("USAGE_MEDIA"), result[10099])
        assertEquals(setOf("USAGE_GAME"), result[10102])
        assertEquals(2, result.size)
    }

    @Test
    fun `混合 app 的多 usage 归并为单 uid 集合`() {
        val dump = """
            u/pid:10099/1 state:started usage=USAGE_MEDIA
            u/pid:10099/1 state:started usage=USAGE_ASSISTANT
        """.trimIndent()

        val result = TakeoverController.Companion.parseActiveUsages(dump, selfUid)

        // 微信型混合 app：媒体 + 通话类 usage 同 uid → 通话保护应触发
        assertEquals(setOf("USAGE_MEDIA", "USAGE_ASSISTANT"), result[10099])
        assertTrue(TakeoverController.needsCallProtection(result[10099] ?: emptySet()))
    }

    @Test
    fun `排除自己 uid 与非法 uid`() {
        val dump = """
            u/pid:$selfUid/1 state:started usage=USAGE_MEDIA
            u/pid:0/2 state:started usage=USAGE_MEDIA
            u/pid:10099/3 state:started usage=USAGE_MEDIA
        """.trimIndent()

        val result = TakeoverController.Companion.parseActiveUsages(dump, selfUid)

        assertEquals(setOf(10099), result.keys)
    }

    @Test
    fun `非 started 状态不进快照`() {
        val dump = "u/pid:10099/1 state:idle usage=USAGE_MEDIA"

        val result = TakeoverController.Companion.parseActiveUsages(dump, selfUid)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `空输出与无关文本返回空快照`() {
        assertTrue(TakeoverController.Companion.parseActiveUsages("", selfUid).isEmpty())
        assertTrue(
            TakeoverController.Companion.parseActiveUsages("no playback here", selfUid).isEmpty(),
        )
    }

    @Test
    fun `通话保护判定 白名单内外边界`() {
        assertTrue(
            TakeoverController.needsCallProtection(setOf("USAGE_VOICE_COMMUNICATION")),
        )
        assertTrue(TakeoverController.needsCallProtection(setOf("USAGE_NOTIFICATION_RINGTONE")))
        assertFalse(TakeoverController.needsCallProtection(setOf("USAGE_MEDIA")))
        assertFalse(TakeoverController.needsCallProtection(setOf("USAGE_MEDIA", "USAGE_GAME")))
    }
}

/**
 * 被测纯函数位于 TakeoverController 的 private companion——测试通过同名包内
 * 的访问桥接（companion 的 private 对同文件可见，跨文件用 internal 桥）。
 * 这里用反射桥接以保持被测代码零改动（不扩大可见性）。
 */
