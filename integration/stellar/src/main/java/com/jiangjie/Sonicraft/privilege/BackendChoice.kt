/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 特权后端选择（纯逻辑，可单测）。
 */
package com.jiangjie.Sonicraft.privilege

/**
 * 决定当前该用哪个特权后端。
 *
 * 优先级（踩过的坑都在这几条里）：
 * 1. **已授权优先于「先到先得」**——只看 `isAlive` 会让「Stellar 活着但没授权」
 *    一直占位，绑定报 `code=-3`，而旁边已授权的 Shevery 永远用不上；
 * 2. 两者都未授权时仍优先 Stellar（等用户回 Stellar Manager 授权，行为可预期）；
 * 3. 都不可用 → `NONE`，由 UI 弹「特权框架未就绪」。
 */
object BackendChoice {

    fun choose(
        stellarAlive: Boolean,
        stellarGranted: Boolean,
        shizukuAlive: Boolean,
        shizukuGranted: Boolean,
    ): BackendKind = when {
        stellarAlive && stellarGranted -> BackendKind.STELLAR
        shizukuAlive && shizukuGranted -> BackendKind.SHIZUKU
        stellarAlive -> BackendKind.STELLAR
        shizukuAlive -> BackendKind.SHIZUKU
        else -> BackendKind.NONE
    }
}
