/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import com.jiangjie.Sonicraft.privilege.BackendChoice
import com.jiangjie.Sonicraft.privilege.BackendKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 特权后端仲裁：已授权优先于「先到先得」。
 */
class BackendChoiceTest {

    @Test
    fun `两者都活着且都授权时优先 Stellar`() {
        assertEquals(
            BackendKind.STELLAR,
            BackendChoice.choose(
                stellarAlive = true, stellarGranted = true,
                shizukuAlive = true, shizukuGranted = true,
            ),
        )
    }

    @Test
    fun `Stellar 活着但未授权时让位给已授权的 Shizuku`() {
        // 这是 2026-11-23 修的真实缺陷：只看 isAlive 会死在 code=-3
        assertEquals(
            BackendKind.SHIZUKU,
            BackendChoice.choose(
                stellarAlive = true, stellarGranted = false,
                shizukuAlive = true, shizukuGranted = true,
            ),
        )
    }

    @Test
    fun `两者都未授权时仍优先 Stellar`() {
        assertEquals(
            BackendKind.STELLAR,
            BackendChoice.choose(
                stellarAlive = true, stellarGranted = false,
                shizukuAlive = true, shizukuGranted = false,
            ),
        )
    }

    @Test
    fun `只有 Shizuku 活着时用 Shizuku`() {
        assertEquals(
            BackendKind.SHIZUKU,
            BackendChoice.choose(
                stellarAlive = false, stellarGranted = false,
                shizukuAlive = true, shizukuGranted = true,
            ),
        )
    }

    @Test
    fun `全灭返回 NONE`() {
        assertEquals(
            BackendKind.NONE,
            BackendChoice.choose(
                stellarAlive = false, stellarGranted = false,
                shizukuAlive = false, shizukuGranted = false,
            ),
        )
    }

    @Test
    fun `Shizuku 未授权而 Stellar 已授权时用 Stellar`() {
        assertEquals(
            BackendKind.STELLAR,
            BackendChoice.choose(
                stellarAlive = true, stellarGranted = true,
                shizukuAlive = true, shizukuGranted = false,
            ),
        )
    }
}
