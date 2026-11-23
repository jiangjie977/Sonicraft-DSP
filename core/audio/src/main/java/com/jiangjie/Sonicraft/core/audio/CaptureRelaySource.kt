/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.jiangjie.Sonicraft.core.common.DiagDirs
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 内录源（Mode B 输入侧）：AudioRecord + AudioPlaybackCapture 捕获本机其他 App 正在
 * 播放的媒体音频。
 *
 * 采集线程把 float 数据推给回调 [push]（最终进入 native 输入环形缓冲）。
 *
 * 前置条件（由调用方保证）：
 *  1. 已授予 RECORD_AUDIO 运行时权限；
 *  2. 已持有有效的 MediaProjection（划掉后台重开后会失效，必须重新走「启动内录接管 → 立即开始」）；
 *  3. 特权框架已 `appops set PROJECT_MEDIA allow`（否则 capture 为空或抛异常）。
 */
class CaptureRelaySource(
    private val sampleRate: Int,
    private val channels: Int,
    private val projection: MediaProjection,
    /** 排除清单：这些 uid 的播放不被捕获（黑名单功能，重启内录生效） */
    private val excludedUids: IntArray = IntArray(0),
    private val push: (FloatArray, Int) -> Unit,
    /** 投影失效回调：read 恒负时采集线程一次性上报，调用方止损 */
    private val onDead: ((String) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private val running = AtomicBoolean(false)
    // P3-1 Suspended 真语义：暂停门控（true=读到的数据丢弃不进 DSP，引擎输出静音；
    // AudioRecord 保持 recording，流活性与恢复即时性不受影响）
    private val paused = AtomicBoolean(false)
    private var job: Job? = null
    private val totalRead = AtomicLong(0)
    private val readCalls = AtomicLong(0)
    private val zeroReads = AtomicLong(0)
    private val lastRead = AtomicLong(0)
    private val partialReads = AtomicLong(0)  // read < 请求帧数（AudioRecord 异常信号）
    private val errorReads = AtomicLong(0)
    // 投影失效判定：连续负 read 计数与一次性上报标志。
    // A16 特权免弹窗投影实测：AudioRecord 建立成功但 read 恒 -2，每秒 ~160 次全错，
    // 原实现只计数不判定 = 僵尸会话被当正常推进，引擎吃空数据 → 接管静音 + 无声。
    private val errorStreak = AtomicLong(0)
    private val deadReported = AtomicBoolean(false)
    // 诊断写入统一走 DiagDirs 单点（app 层注入真实 filesDir/crash）；未注入（空串）跳过。
    // 历史：此处曾硬编码 /data/data/<pkg> 绝对路径（431c2f6 去硬编码批次的漏网文件）。
    private fun writeDiag(name: String, line: String) {
        val dir = DiagDirs.crashDir
        if (dir.isEmpty()) return
        runCatching { File(dir, name).appendText(line) }
    }

    private fun writeReason(msg: String) {
        writeDiag("failed_reason.txt", "ts=${System.currentTimeMillis()} $msg\n")
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true

        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer =
            AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) {
            writeReason("minBuffer<=0 sr=$sampleRate ch=$channels")
            SonicraftLog.e(TAG, "AudioRecord 不支持的参数组合 (sr=$sampleRate ch=$channels)")
            return false
        }

        // 匹配 MEDIA + GAME：短视频/游戏等内容音。故意不匹配 UNKNOWN——该 usage 常被
        // 通知/系统短音使用，误捕会产生"提示音双声"；抖音等主流 app 短视频走 MEDIA/GAME。
        // 注意：努比亚要求 AudioPlaybackCaptureConfiguration 至少匹配一个 usage，否则 build 抛异常。
        // excludeUid(自己)：防回环——不捕获本 app 引擎自己的输出（WEcho 同款做法）。
        // excludeUid(黑名单)：用户排除清单的 app 不被捕获（排除自身的重复 excludeUid 系统会忽略）。
        val captureConfig = try {
            val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .excludeUid(android.os.Process.myUid())
            for (uid in excludedUids) {
                builder.excludeUid(uid)
            }
            builder.build()
        } catch (e: Throwable) {
            writeReason("captureConfig build failed: ${e.javaClass.simpleName}: ${e.message}")
            SonicraftLog.e(TAG, "AudioPlaybackCaptureConfiguration 构建失败（PROJECT_MEDIA 未授权/projection 无效）", e)
            return false
        }

        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setAudioPlaybackCaptureConfig(captureConfig)
                // FIFO 加倍扩容：供给协程被调度/GC 停顿 >minBuffer 时长时，AudioFlinger 会直接
                // 丢样（ring 水位完全无感，表现为偶发数字噪声），更大 FIFO 吸收更长的停顿
                .setBufferSizeInBytes(minBuffer * 8)
                .build()
        } catch (e: Throwable) {
            writeReason("AudioRecord build failed: ${e.javaClass.simpleName}: ${e.message}")
            SonicraftLog.e(TAG, "AudioRecord 创建失败", e)
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            writeReason("AudioRecord not initialized")
            SonicraftLog.e(TAG, "AudioRecord 未初始化")
            audioRecord.release()
            return false
        }

        // L10：record 赋值后的启动段（startRecording/供给协程/诊断
        // 协程）若抛同步异常，调用方拿到 false/异常未必调 stop——AudioRecord+协程
        // 全泄漏（record 已赋值、job 半初始化等中间态无人清理）。兜底自清理后再抛。
        try {
            record = audioRecord
            running.set(true)
            audioRecord.startRecording()

            // 供给粒度 1024 帧（≈21ms）：ring 已扩到 8192 帧，供给无需与 DSP 块对齐
            // （消费侧 OnData 已分块处理）。更大的粒度把 JNI 调用降到 ~47 次/秒，
            // 调度抖动不再打满 ring → 消除 PushInput 丢块导致的哔啵声。
            // 注意：一次 push 不得超过 ring 总容量（8192 帧），否则整块被丢弃。
            val framesPerRead = 1024
            job = CoroutineScope(Dispatchers.IO).launch {
                val buf = FloatArray(framesPerRead * channels)
                while (isActive && running.get()) {
                    val read = runCatching {
                        audioRecord.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    }.getOrDefault(0)
                    lastRead.set(read.toLong())
                    readCalls.incrementAndGet()
                    if (read <= 0) {
                        zeroReads.incrementAndGet()
                        if (read < 0) {
                            errorReads.incrementAndGet()
                            // 投影失效判定：正常场景 read 恒 >= 0（READ_BLOCKING 阻塞等数据，
                            // 源静音返回 0），恒负 = AudioRecord/投影已死亡。连续 30 次负读
                            // （≈200ms 全错）即判死，一次性上报（CAS 防重复触发）。
                            val streak = errorStreak.incrementAndGet()
                            if (streak >= ERROR_STREAK_DEAD &&
                                deadReported.compareAndSet(false, true)
                            ) {
                                val msg = "capture DEAD：read 恒 $read 连续 $streak 次（投影已失效）"
                                writeDiag("capture_diag.txt", "ts=${System.currentTimeMillis()} $msg\n")
                                SonicraftLog.e(TAG, msg)
                                onDead?.invoke(msg)
                            }
                        } else {
                            errorStreak.set(0)   // read=0（无数据）不构成死亡证据
                        }
                        continue
                    }
                    errorStreak.set(0)
                    if (read < buf.size) partialReads.incrementAndGet()
                    totalRead.addAndGet(read.toLong())
                    if (paused.get()) continue // Suspended：读了丢弃（保持时效），引擎输出静音
                    push(buf, read / channels)
                }
            }

            // 诊断：每秒把 AudioRecord.read 统计写文件，便于 adb run-as 拉取判断 capture 是否工作
            // 启动时立即写首行（验证写入路径可用）；append 模式避免截断竞态
            writeDiag("capture_diag.txt", "ts=${System.currentTimeMillis()} START sr=$sampleRate ch=$channels\n")
            CoroutineScope(Dispatchers.IO).launch {
                while (isActive && running.get()) {
                    delay(1000)
                    val txt = "ts=${System.currentTimeMillis()} lastRead=${lastRead.get()} " +
                        "readCalls=${readCalls.get()} zeroReads=${zeroReads.get()} " +
                        "partialReads=${partialReads.get()} errorReads=${errorReads.get()} " +
                        "totalRead=${totalRead.get()}\n"
                    writeDiag("capture_diag.txt", txt)
                }
            }
        } catch (e: Throwable) {
            running.set(false)
            runCatching { job?.cancel() }
            job = null
            runCatching { audioRecord.stop() }
            audioRecord.release()
            record = null
            throw e
        }

        SonicraftLog.i(TAG, "CaptureRelaySource 已启动 sr=$sampleRate ch=$channels")
        return true
    }

    /** Suspended 门控：true=采集数据丢弃（引擎静音），false=正常供给 DSP */
    fun setPaused(v: Boolean) {
        paused.set(v)
        SonicraftLog.i(TAG, "capture paused=$v")
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        runCatching { record?.stop() }
        record?.release()
        record = null
    }

    private companion object {
        const val TAG = "CaptureSource"

        /** 连续负 read 判死阈值（采集粒度 ~6ms/次，30 次 ≈ 200ms 全错，误报率趋零） */
        const val ERROR_STREAK_DEAD = 30
    }
}
