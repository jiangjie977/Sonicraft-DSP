/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import com.jiangjie.Sonicraft.core.SilenceFuse
import com.jiangjie.Sonicraft.core.SilenceFuse.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 静音保险判定：宁可双声，绝不留「压掉唯一音源」的静音。
 */
class SilenceFuseTest {

    @Test
    fun `电平正常时返回 FRESH 并应清空计数`() {
        assertEquals(Decision.FRESH, SilenceFuse.evaluate(0.2f, sawAudio = true, streak = 3))
        assertEquals(Decision.FRESH, SilenceFuse.evaluate(0.006f, sawAudio = false, streak = 0))
    }

    @Test
    fun `从未见过数据时不判连坐`() {
        // 建链初期副本本来就没数据，此时释放静音会造成启动双声
        assertEquals(Decision.IGNORE, SilenceFuse.evaluate(0f, sawAudio = false, streak = 0))
        assertEquals(Decision.IGNORE, SilenceFuse.evaluate(0.0049f, sawAudio = false, streak = 5))
    }

    @Test
    fun `见过数据后第一轮归零只计数`() {
        assertEquals(Decision.COUNTING, SilenceFuse.evaluate(0f, sawAudio = true, streak = 0))
    }

    @Test
    fun `连续两轮归零触发释放`() {
        assertEquals(Decision.RELEASE, SilenceFuse.evaluate(0f, sawAudio = true, streak = 1))
    }

    @Test
    fun `单轮掉零后恢复不会误释放`() {
        // 第一轮低 → COUNTING；第二轮恢复 → FRESH（调用方清空 streak）
        assertEquals(Decision.COUNTING, SilenceFuse.evaluate(0.001f, sawAudio = true, streak = 0))
        assertEquals(Decision.FRESH, SilenceFuse.evaluate(0.05f, sawAudio = true, streak = 1))
    }

    @Test
    fun `阈值边界`() {
        // 实现用 `level > LOW_THRESHOLD` 判正常：恰好 0.005 仍算「掉零」
        assertEquals(Decision.RELEASE, SilenceFuse.evaluate(0.005f, sawAudio = true, streak = 1))
        assertEquals(Decision.FRESH, SilenceFuse.evaluate(0.0051f, sawAudio = true, streak = 1))
        assertEquals(Decision.RELEASE, SilenceFuse.evaluate(0.0049f, sawAudio = true, streak = 1))
    }
}
