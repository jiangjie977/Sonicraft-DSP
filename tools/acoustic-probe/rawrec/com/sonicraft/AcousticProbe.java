// SPDX-FileCopyrightText: 2026 jiangjie
// SPDX-License-Identifier: GPL-3.0-only
package com.sonicraft;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * 声学探针：同一进程内 AudioTrack 放测试信号 + AudioRecord 录麦克风。
 *
 * 为什么不用系统播放器：播放器会被通知/焦点/UI 状态打断，实测出现过
 * 播 2 秒就静音的假数据。进程内 AudioTrack 全程可控，还能精确记录
 * "回放开始的采样序号"，用于把录音与激励时间轴硬对齐。
 *
 * 输入：48kHz / 16bit / 立体声裸 PCM
 * 输出：48kHz / 16bit / 单声道裸 PCM（麦克风，UNPROCESSED 源）
 *
 * 用法：
 *   CLASSPATH=/data/local/tmp/rec.dex app_process /system/bin \
 *     com.sonicraft.AcousticProbe /data/local/tmp/steps_6db.raw \
 *     /data/local/tmp/rec_6db.raw 2000 2500 48000
 */
public final class AcousticProbe {

    private static final int SRC_UNPROCESSED = 9;
    private static final int SRC_VOICE_RECOGNITION = 6;
    private static final int SRC_MIC = 1;

    public static void main(String[] args) throws Exception {
        String pcmPath = args[0];
        String recPath = args[1];
        int preRollMs = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
        int postRollMs = args.length > 3 ? Integer.parseInt(args[3]) : 2500;
        int sr = args.length > 4 ? Integer.parseInt(args[4]) : 48000;

        byte[] src;
        try (RandomAccessFile rf = new RandomAccessFile(pcmPath, "r")) {
            src = new byte[(int) rf.length()];
            rf.readFully(src);
        }
        int srcFrames = src.length / 4;  // 立体声 16bit
        System.out.println("激励: " + pcmPath + " frames=" + srcFrames
                + " (" + String.format("%.2f", srcFrames / (double) sr) + "s)");

        // ---- 播放器 ----
        int outBuf = AudioTrack.getMinBufferSize(sr,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        outBuf = Math.max(outBuf * 2, sr * 4 / 5);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, sr,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                outBuf, AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            System.out.println("ERROR AudioTrack 初始化失败");
            return;
        }
        track.setVolume(1.0f);
        track.play();  // 必须先 play，否则 write 立即返回 0

        // ---- 录音器 ----
        AudioRecord rec = null;
        int usedSrc = -1;
        int forcedSrc = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        int[] cands = forcedSrc > 0
                ? new int[]{forcedSrc}
                : new int[]{SRC_UNPROCESSED, SRC_VOICE_RECOGNITION, SRC_MIC};
        for (int s : cands) {
            int inBuf = AudioRecord.getMinBufferSize(sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (inBuf <= 0) continue;
            AudioRecord r = new AudioRecord(s, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(inBuf * 4, sr / 5));
            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                rec = r;
                usedSrc = s;
                break;
            }
            r.release();
        }
        if (rec == null) {
            System.out.println("ERROR 录音初始化失败");
            track.release();
            return;
        }
        System.out.println("录音源=" + usedSrc + " sr=" + sr);

        final long[] frames = {0};
        final long[] playStartFrame = {-1};
        final boolean[] done = {false};

        final BufferedOutputStream out = new BufferedOutputStream(
                new FileOutputStream(recPath), 1 << 16);
        final AudioRecord recF = rec;
        Thread recThread = new Thread(() -> {
            byte[] buf = new byte[Math.max(sr / 5 * 2, 4096)];
            while (!done[0]) {
                int n = recF.read(buf, 0, buf.length);
                if (n <= 0) continue;
                try {
                    out.write(buf, 0, n);
                } catch (Exception e) {
                    break;
                }
                frames[0] += n / 2;
            }
        });
        recThread.start();
        rec.startRecording();

        Thread.sleep(preRollMs);
        System.out.println("  预录完成，开始播放 @frame=" + frames[0]);

        Thread playThread = new Thread(() -> {
            playStartFrame[0] = frames[0];
            int chunk = 4096;
            int off = 0;
            while (off < src.length) {
                int n = Math.min(chunk, src.length - off);
                int w = track.write(src, off, n);
                if (w <= 0) break;
                off += w;
            }
        });
        playThread.start();
        playThread.join();
        System.out.println("  播放结束 @frame=" + frames[0]);
        Thread.sleep(postRollMs);

        done[0] = true;
        try {
            recThread.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            rec.stop();
        } catch (Throwable ignored) {
        }
        rec.release();
        track.stop();
        track.release();
        out.flush();
        out.close();

        long total = frames[0];
        System.out.println("完成: 录音 " + total + " frames ("
                + String.format("%.2f", total / (double) sr) + "s)");
        System.out.println("META sr=" + sr + " src=" + usedSrc
                + " rec=" + recPath + " playStart=" + playStartFrame[0]
                + " preRoll=" + preRollMs + "ms");
    }
}
