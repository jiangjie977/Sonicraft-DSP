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
    fun `有用户痕迹时判定为已配置`() {
        assertTrue(ConfigBackup.hasUserTrace(mapOf("output_gain" to 1.4f)))
        assertTrue(ConfigBackup.hasUserTrace(mapOf("bass_enhance_mode" to 0)))
        assertTrue(ConfigBackup.hasUserTrace(mapOf("eq_bands_10" to "0,0,0")))
    }

    @Test
    fun `只有同意状态不算用户痕迹`() {
        // 新装机会自动写 privacy_accepted，不能据此认为「已有配置」
        assertFalse(
            ConfigBackup.hasUserTrace(
                mapOf(
                    "privacy_accepted" to true,
                    "privacy_accepted_version" to 1,
                ),
            ),
        )
        assertFalse(ConfigBackup.hasUserTrace(emptyMap<String, Any>()))
        assertFalse(ConfigBackup.hasUserTrace(mapOf("some_unknown_key" to 1f)))
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

    // ---- 后续增删效果器的兼容性 ----

    @Test
    fun `低版本文件仍可导入（前向兼容）`() {
        val json = """{"version":0,"values":{"input_gain":{"t":"f","v":0.9}}}"""
        val snap = ConfigBackup.decodeSnapshot(json)
        assertNotNull("老版本文件不能因为版本号小就被拒", snap)
        assertEquals(0, snap!!.version)
        assertEquals(0.9f, snap.values["input_gain"] as Float, 1e-6f)
        assertNull(ConfigBackup.describeError(json))
    }

    @Test
    fun `高于当前版本的文件被拒绝并提示升级`() {
        val json = """{"version":${ConfigBackup.FORMAT_VERSION + 1},"values":{}}"""
        assertNull(ConfigBackup.decodeSnapshot(json))
        val err = ConfigBackup.describeError(json)
        assertNotNull(err)
        assertTrue("应提示升级应用", err!!.contains("升级"))
    }

    @Test
    fun `脉冲文件清单往返并排序`() {
        val json = ConfigBackup.encode(
            all = mapOf("input_gain" to 1f),
            appLabel = "t",
            nowIso = "now",
            irFiles = listOf("b.irs", "a.irs"),
        )
        val snap = ConfigBackup.decodeSnapshot(json)
        assertNotNull(snap)
        assertEquals(listOf("a.irs", "b.irs"), snap!!.irFiles)
    }

    @Test
    fun `键名迁移表改名并计数`() {
        val (out, migrated) = ConfigBackup.applyMigrations(
            values = mapOf("old_key" to 1.2f, "keep" to true),
            table = mapOf("old_key" to "new_key"),
        )
        assertEquals(1.2f, out["new_key"] as Float, 1e-6f)
        assertFalse("旧键不应残留", "old_key" in out)
        assertTrue("未命中表的键原样保留", "keep" in out)
        assertEquals(1, migrated)
    }

    @Test
    fun `空迁移表原样返回`() {
        val src = mapOf<String, Any?>("a" to 1f, "b" to "x")
        val (out, migrated) = ConfigBackup.applyMigrations(src, emptyMap())
        assertEquals(src, out)
        assertEquals(0, migrated)
    }

    @Test
    fun `无法识别的条目计入 ignored`() {
        val json = """
            {"version":1,"values":{
              "ok":{"t":"f","v":0.5},
              "bad":{"t":"zzz","v":1}
            }}
        """.trimIndent()
        val snap = ConfigBackup.decodeSnapshot(json)
        assertNotNull(snap)
        assertEquals(1, snap!!.ignored)
        assertEquals(1, snap.values.size)
    }
}
