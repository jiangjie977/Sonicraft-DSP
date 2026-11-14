/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import com.jiangjie.Sonicraft.core.engine.LoopbackPcmSource
import com.jiangjie.Sonicraft.privilege.ILoopbackCallback
import com.jiangjie.Sonicraft.privilege.ILoopbackProbe

/**
 * P1：把特权侧（Stellar UserService / shell uid=2000）的 Loopback PCM
 * 桥接成引擎可用的 [LoopbackPcmSource]。
 *
 * 数据链：UserService 的 AudioRecord(REMOTE_SUBMIX) → PCM16 交织 → oneway AIDL 回调
 *        → 本类转 float → 引擎 pushInput。
 *
 * 为什么需要这层：core/engine 不能依赖 integration/stellar（模块方向），
 * 因此接口留在 engine、实现在 app。
 */
class StellarLoopbackSource(
    private val probe: ILoopbackProbe,
    private val sampleRate: Int,
    /** 要从 mix 排除的 uid（app 自身，防回环） */
    private val excludeUid: Int,
    private val onError: ((String) -> Unit)? = null,
) : LoopbackPcmSource {

    @Volatile
    private var onDead: ((String) -> Unit)? = null

    @Volatile
    private var running = false

    override fun setOnDead(listener: ((String) -> Unit)?) {
        onDead = listener
    }

    override fun start(onPcm: (FloatArray, Int) -> Unit): Boolean {
        val cb = object : ILoopbackCallback.Stub() {
            override fun onPcm(data: ByteArray, frames: Int) {
                // PCM16 小端交织 → float [-1,1]
                val out = FloatArray(frames * CHANNELS)
                var i = 0
                var j = 0
                while (i + 1 < data.size && j < out.size) {
                    val lo = data[i].toInt() and 0xFF
                    val hi = data[i + 1].toInt()          // 有符号，自动符号扩展
                    val s = (hi shl 8) or lo
                    out[j] = s / 32768f
                    i += 2
                    j++
                }
                if (j > 0) onPcm(out, j / CHANNELS)
            }

            override fun onError(msg: String) {
                val dead = onDead
                if (dead != null) dead.invoke(msg) else onError?.invoke(msg)
            }
        }

        val ret = runCatching { probe.startStream(sampleRate, excludeUid, cb) }
            .getOrElse { e ->
                onError?.invoke("startStream 异常: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
        running = !ret.startsWith("失败")
        if (!running) onError?.invoke(ret)
        return running
    }

    override fun stop() {
        running = false
        runCatching { probe.stop() }
    }

    private companion object {
        const val CHANNELS = 2
    }
}
