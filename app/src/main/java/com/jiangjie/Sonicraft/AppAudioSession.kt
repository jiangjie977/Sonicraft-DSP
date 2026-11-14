package com.jiangjie.Sonicraft

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * 让系统把本 App 认定为「合法的媒体播放者」。
 *
 * 背景（2026-11-13 实测）：系统回环模式下本 App 必须常驻后台（前台留给被采集的 App），
 * 部分系统会对"没有播放身份的后台播放"做静音处理：
 *   `background playback would be muted for com.jiangjie.Sonicraft, level: partial`
 * 表现就是：刷下一个视频时偶发无声、切走再切回来又有声。
 *
 * 这里**只**建一个活跃的 MediaSession 并置为 PLAYING，向系统声明"我是一个正在播放的媒体应用"。
 * 刻意**不申请音频焦点（AUDIOFOCUS_GAIN）**：一旦持有焦点，被采集的 App（抖音等）
 * 会在失去焦点时暂停播放，反而采集不到声音。
 */
object AppAudioSession {
    private var session: MediaSession? = null

    fun acquire(context: Context) {
        if (session == null) {
            session = runCatching {
                MediaSession(context, "SonicraftDsp").apply {
                    setPlaybackState(
                        PlaybackState.Builder()
                            .setState(
                                PlaybackState.STATE_PLAYING,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                                1.0f,
                            )
                            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                            .build(),
                    )
                    isActive = true
                }
            }.getOrNull()
        } else {
            runCatching { session?.isActive = true }
        }
    }

    fun release() {
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
    }
}
