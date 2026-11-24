/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.jiangjie.Sonicraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EQ 参数编解码的容错与往返。
 *
 * 重点不是「正产能读回来」，而是**历史脏数据不能崩、不能污染**：
 * 段数变化、截断、越界值都必须降级为默认。
 */
class EqConfigCodecTest {

    @Test
    fun `缺省输入回落到默认值`() {
        assertEquals(List(10) { 0f }, EqConfigCodec.decodeBands(null, 10))
        assertEquals(List(10) { 1f }, EqConfigCodec.decodeQs(null, 10))
        assertEquals(List(10) { 0 }, EqConfigCodec.decodeTypes(null, 10))
        assertEquals(EqMath.freqTable(10), EqConfigCodec.decodeFreqs(null, 10))
    }

    @Test
    fun `段数缩减时保留已有前缀其余补默认`() {
        // 历史 31 段数据，用户切到 10 段模式
        val raw = (0 until 31).joinToString(",") { "%.2f".format(it.toFloat()) }
        val bands = EqConfigCodec.decodeBands(raw, 10)
        assertEquals(10, bands.size)
        assertEquals(0f, bands[0], 1e-6f)
        assertEquals(9f, bands[9], 1e-6f)
    }

    @Test
    fun `段数扩张时缺少的段补默认`() {
        val bands = EqConfigCodec.decodeBands("1.00,2.00", 10)
        assertEquals(10, bands.size)
        assertEquals(1f, bands[0], 1e-6f)
        assertEquals(2f, bands[1], 1e-6f)
        assertEquals(0f, bands[2], 1e-6f)
    }

    @Test
    fun `脏字符串整体降级不抛异常`() {
        assertEquals(List(5) { 0f }, EqConfigCodec.decodeBands("abc,def", 5))
        assertEquals(List(5) { 1f }, EqConfigCodec.decodeQs("1.0,,2.0", 5))
        assertEquals(List(5) { 0 }, EqConfigCodec.decodeTypes("x,y", 5))
    }

    @Test
    fun `Q 与类型越界值被钳制`() {
        val qs = EqConfigCodec.decodeQs("0.1,99,2.0", 3)
        assertEquals(0.3f, qs[0], 1e-6f)
        assertEquals(8f, qs[1], 1e-6f)
        assertEquals(2f, qs[2], 1e-6f)

        val types = EqConfigCodec.decodeTypes("-3,7,1", 3)
        assertEquals(0, types[0])
        assertEquals(2, types[1])
        assertEquals(1, types[2])
    }

    @Test
    fun `频率越界被钳制到 20-20000`() {
        val freqs = EqConfigCodec.decodeFreqs("1,99999,440", 3)
        assertEquals(20f, freqs[0], 1e-3f)
        assertEquals(20000f, freqs[1], 1e-3f)
        assertEquals(440f, freqs[2], 1e-3f)
    }

    @Test
    fun `用户预设往返一致`() {
        val src = listOf(
            EqUserPreset(
                name = "流行",
                mode = 10,
                gains = listOf(1f, -2.5f),
                qs = listOf(1.1f, 2.2f),
                freqs = listOf(100f, 1000f),
                types = listOf(0, 1),
            ),
        )
        val json = EqConfigCodec.encodeUserPresets(src)
        val back = EqConfigCodec.decodeUserPresets(json)
        assertEquals(1, back.size)
        assertEquals("流行", back[0].name)
        assertEquals(10, back[0].mode)
        assertEquals(src[0].gains, back[0].gains)
        assertEquals(src[0].types, back[0].types)
    }

    @Test
    fun `旧预设缺少 types 字段时按全 PK 兼容`() {
        val legacy = """[{"name":"旧","mode":10,"gains":[0.0],"qs":[1.0],"freqs":[100.0]}]"""
        val back = EqConfigCodec.decodeUserPresets(legacy)
        assertEquals(1, back.size)
        assertTrue("缺 types 应解析为空列表（全 PK）", back[0].types.isEmpty())
    }

    @Test
    fun `空数组与空串都返回空列表`() {
        assertTrue(EqConfigCodec.decodeUserPresets("[]").isEmpty())
        assertTrue(EqConfigCodec.decodeUserPresets("").isEmpty())
    }
}
