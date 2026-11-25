/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置导出/导入的编解码。
 *
 * 重点是**类型保真**与**同意状态不被覆盖**：
 * SharedPreferences 同名键的类型只有运行时才知道，JSON 数字无法区分 Float/Int/Long，
 * 写错类型会让 `getFloat` 抛 ClassCastException。
 */
class ConfigBackupTest {

    private fun roundTrip(map: Map<String, *>): Map<String, Any?> {
        val json = ConfigBackup.encode(map, appLabel = "test", nowIso = "2026-11-24T00:00:00")
        return ConfigBackup.decode(json) ?: error("decode returned null")
    }

    @Test
    fun `各类型往返保持类型`() {
        val src = mapOf(
            "compressor_enabled" to true,
            "input_gain" to 0.8f,
            "eq_mode" to 10,
            "some_long" to 1234567890123L,
            "eq_bands_10" to "0.00,4.65,-3.22",
            "bound_keys" to setOf("a", "b"),
        )
        val back = roundTrip(src)

        assertEquals(true, back["compressor_enabled"])
        assertTrue(back["compressor_enabled"] is Boolean)

        assertEquals(0.8f, back["input_gain"] as Float, 1e-6f)
        assertTrue("Float 必须保持 Float", back["input_gain"] is Float)

        assertEquals(10, back["eq_mode"])
        assertTrue(back["eq_mode"] is Int)

        assertEquals(1234567890123L, back["some_long"])
        assertEquals("0.00,4.65,-3.22", back["eq_bands_10"])
        assertEquals(setOf("a", "b"), back["bound_keys"])
    }

    @Test
    fun `Float 小数精度不丢`() {
        val back = roundTrip(mapOf("stereo_width" to 1.3946130f))
        assertEquals(1.3946130f, back["stereo_width"] as Float, 1e-6f)
    }

    @Test
    fun `隐私同意状态被跳过`() {
        val back = roundTrip(
            mapOf(
                "privacy_accepted" to true,
                "input_gain" to 1.0f,
            ),
        )
        assertFalse("导入配置不应替用户同意隐私政策", "privacy_accepted" in back)
        assertTrue("input_gain" in back)
    }

    @Test
    fun `版本不符时拒绝解析并给出提示`() {
        val json = """{"version":99,"values":{"a":{"t":"f","v":1.0}}}"""
        assertNull(ConfigBackup.decode(json))
        val err = ConfigBackup.describeError(json)
        assertNotNull(err)
        assertTrue(err!!.contains("99"))
    }

    @Test
    fun `非法 JSON 给出提示`() {
        assertNull(ConfigBackup.decode("not a json"))
        assertNotNull(ConfigBackup.describeError("not a json"))
    }

    @Test
    fun `缺少 values 字段时拒绝`() {
        val json = """{"version":1}"""
        assertNull(ConfigBackup.decode(json))
        assertTrue(ConfigBackup.describeError(json)!!.contains("values"))
    }

    @Test
    fun `未知类型被忽略而不是崩溃`() {
        val json = """
            {"version":1,"values":{
              "ok":{"t":"f","v":0.5},
              "weird":{"t":"zzz","v":"?"}
            }}
        """.trimIndent()
        val back = ConfigBackup.decode(json)
        assertNotNull(back)
        assertEquals(0.5f, back!!["ok"] as Float, 1e-6f)
        assertFalse("未知类型应被跳过", "weird" in back)
    }
}
