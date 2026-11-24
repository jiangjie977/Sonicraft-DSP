/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import com.jiangjie.Sonicraft.core.CompressorMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压限默认参数迁移：只替换「仍等于旧默认」的项，用户精调不动。
 */
class CompressorMigrationTest {

    private val legacy = floatArrayOf(-20f, 4f, 10f, 150f, 0f, 6f)
    private val fresh = floatArrayOf(-3f, 1.4f, 15f, 220f, 2f, 3f)

    @Test
    fun `全部等于旧默认时全部迁移`() {
        val plan = CompressorMigration.plan(legacy, legacy, fresh)
        assertEquals(6, plan.size)
        for (i in fresh.indices) assertEquals(fresh[i], plan[i]!!, 1e-6f)
    }

    @Test
    fun `用户改过的项不迁移`() {
        // 用户把 threshold 调到 -12（其余保持旧默认）
        val current = floatArrayOf(-12f, 4f, 10f, 150f, 0f, 6f)
        val plan = CompressorMigration.plan(current, legacy, fresh)
        assertTrue("被用户改过的 index 0 不应出现在计划里", 0 !in plan)
        assertEquals(5, plan.size)
        assertEquals(1.4f, plan[1]!!, 1e-6f)   // index 1 = ratio
    }

    @Test
    fun `全部已是新默认时无需迁移`() {
        val plan = CompressorMigration.plan(fresh, legacy, fresh)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `旧新默认相同的项不进计划`() {
        val legacy2 = floatArrayOf(-20f, 4f, 10f)
        val fresh2 = floatArrayOf(-3f, 4f, 10f)
        val plan = CompressorMigration.plan(legacy2, legacy2, fresh2)
        assertEquals(1, plan.size)
        assertTrue(1 !in plan && 2 !in plan)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `长度不一致必须报错`() {
        CompressorMigration.plan(floatArrayOf(1f), floatArrayOf(1f, 2f), fresh)
    }
}
