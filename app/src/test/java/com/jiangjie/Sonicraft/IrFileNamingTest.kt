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
 * IR 文件命名规则：中文可读、路径不逃逸、0 字节被挡。
 */
class IrFileNamingTest {

    @Test
    fun `路径分隔符与控制字符被替换`() {
        assertEquals("a_b_c", IrFileNaming.sanitize("a/b\\c"))
        assertEquals("a_b", IrFileNaming.sanitize("a\u0000b"))
    }

    @Test
    fun `中文与空格保留`() {
        assertEquals("教堂 大厅", IrFileNaming.sanitize("教堂 大厅"))
    }

    @Test
    fun `空白名回落为 ir`() {
        assertEquals("ir", IrFileNaming.sanitize(""))
        assertEquals("ir", IrFileNaming.sanitize("   "))
    }

    @Test
    fun `超长名截断到上限`() {
        val long = "音".repeat(200)
        assertEquals(IrFileNaming.MAX_NAME_LEN, IrFileNaming.sanitize(long).length)
    }

    @Test
    fun `显示名去掉 ir 前缀与扩展名`() {
        assertEquals("教堂", IrFileNaming.displayName("ir_教堂.wav"))
        assertEquals("Piano", IrFileNaming.displayName("Piano.irs"))
        assertEquals("NoExt", IrFileNaming.displayName("NoExt"))
    }

    @Test
    fun `纯扩展名不被截成空串`() {
        assertEquals(".wav", IrFileNaming.displayName(".wav"))
    }

    @Test
    fun `隐藏文件与未知扩展名不算候选`() {
        assertFalse(IrFileNaming.isCandidate(".hidden.wav", 1024))
        assertFalse(IrFileNaming.isCandidate("readme.txt", 1024))
        assertFalse(IrFileNaming.isCandidate("noext", 1024))
    }

    @Test
    fun `合法扩展名与最小体积`() {
        assertTrue(IrFileNaming.isCandidate("a.wav", IrFileNaming.MIN_BYTES))
        assertTrue(IrFileNaming.isCandidate("A.IRS", 2048))
        assertFalse(IrFileNaming.isCandidate("a.wav", IrFileNaming.MIN_BYTES - 1))
    }

    @Test
    fun `未知大小为 -1 时放行`() {
        // provider/zip 未提供大小：未知 ≠ 0 字节
        assertTrue(IrFileNaming.isCandidate("a.wav", -1))
    }
}
