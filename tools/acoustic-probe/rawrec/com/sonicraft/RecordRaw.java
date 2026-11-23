// SPDX-FileCopyrightText: 2026 jiangjie
// SPDX-License-Identifier: GPL-3.0-only
package com.sonicraft;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;

/**
 * 裸 AudioRecord 录音器（app_process 运行，shell uid）。
 *
 * 目的：声学测量需要**未经处理**的 PCM。系统录音机默认关不掉 AGC/降噪，
 * 且多用 AAC 编码（会污染 THD 测量），所以直接起一个 AudioRecord：
 *   - 优先 AUDIO_SOURCE_UNPROCESSED(9)：系统承诺不做 AGC/NS/AEC
 *   - 可用 --source 1997 走 ECHO_REFERENCE（功放前电信号回采）
 *
 * 输出：PCM16 小端裸流，交错声道，采样率由设备协商后打印。
 * 每秒打印峰值/RMS，便于现场判断是否削波（削波数据必须重录）。
 *
 * 用法：
 *   CLASSPATH=/data/local/tmp/rec.dex app_process /system/bin \
 *       com.sonicraft.RecordRaw /data/local/tmp/rec.raw 20 48000 9
 */
public final class RecordRaw {

    // MediaRecorder.AudioSource 常量（UNPROCESSED/ECHO_REFERENCE 在部分 SDK 中隐藏）
    private static final int SRC_UNPROCESSED = 9;
    private static final int SRC_VOICE_RECOGNITION = 6;
    private static final int SRC_MIC = 1;
    private static final int SRC_ECHO_REFERENCE = 1997;
    private static final int DEV_TYPE_ECHO_REFERENCE = 1999;

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "/data/local/tmp/rec.raw";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int srWanted = args.length > 2 ? Integer.parseInt(args[2]) : 48000;
        int srcWanted = args.length > 3 ? Integer.parseInt(args[3]) : SRC_UNPROCESSED;

        int[] candidates = srcWanted == SRC_ECHO_REFERENCE
                ? new int[]{SRC_ECHO_REFERENCE, SRC_UNPROCESSED, SRC_MIC}
                : new int[]{SRC_UNPROCESSED, SRC_VOICE_RECOGNITION, SRC_MIC};

        AudioRecord rec = null;
        int usedSr = srWanted;
        int usedSrc = -1;
        for (int src : candidates) {
            int minBuf = AudioRecord.getMinBufferSize(srWanted,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                System.out.println("  getMinBufferSize 失败，sr=" + srWanted);
                continue;
            }
            int bufBytes = Math.max(minBuf * 4, srWanted * 2 / 5);
            try {
                rec = new AudioRecord(src, srWanted, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            } catch (Throwable t) {
                System.out.println("  构造异常 src=" + src + " : " + t);
                rec = null;
                continue;
            }
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                usedSrc = src;
                break;
            }
            System.out.println("  src=" + src + " 初始化失败 state=" + rec.getState());
            rec.release();
            rec = null;
        }
        if (rec == null) {
            System.out.println("ERROR 所有音频源均不可用");
            return;
        }
        usedSr = rec.getSampleRate();

        AudioManager am = null;
        try {
            // app_process 下无 Context，用反射拿系统 AudioManager 不可行；
            // 回声参考路由此处仅尽力而为，失败不影响主路。
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Object ctx = at.getMethod("getSystemContext").invoke(thread);
            am = (AudioManager) ctx.getClass().getMethod("getSystemService", String.class)
                    .invoke(ctx, "audio");
        } catch (Throwable ignored) {
            // 忽略：无 Context 时跳过设备优选
        }
        if (am != null) {
            String outSr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            System.out.println("  系统输出: sr=" + outSr + " frames=" + frames);
            if (usedSrc == SRC_ECHO_REFERENCE) {
                for (AudioDeviceInfo di : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (di.getType() == DEV_TYPE_ECHO_REFERENCE) {
                        boolean ok = rec.setPreferredDevice(di);
                        System.out.println("  ECHO_REFERENCE 优选设备: " + ok
                                + " (" + di.getProductName() + ")");
                    }
                }
            }
        }

        System.out.println("使用音频源=" + usedSrc + " 采样率=" + usedSr
                + " 声道=1 位深=16bit 时长=" + seconds + "s");
        System.out.println("输出=" + path);
        if (usedSrc != SRC_UNPROCESSED) {
            System.out.println("警告：未能使用 UNPROCESSED，该源可能带 AGC/降噪");
        }

        try {
            rec.startRecording();
        } catch (Throwable t) {
            System.out.println("ERROR startRecording: " + t);
            rec.release();
            return;
        }

        int bufBytes = Math.max(rec.getBufferSizeInFrames() * 2, usedSr * 2 / 5);
        byte[] buf = new byte[bufBytes];
        long totalBytes = (long) seconds * usedSr * 2L;
        long written = 0;
        double secPeak = 0.0;
        double secSumSq = 0.0;
        long secCount = 0;
        int secIndex = 0;
        long clipCount = 0;
        try (BufferedOutputStream out = new BufferedOutputStream(
                new FileOutputStream(path), 1 << 16)) {
            while (written < totalBytes) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    System.out.println("  read 返回 " + n + "，中止");
                    break;
                }
                out.write(buf, 0, n);
                written += n;
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    double a = Math.abs(v) / 32768.0;
                    if (a > secPeak) secPeak = a;
                    if (a > 0.997) clipCount++;
                    secSumSq += a * a;
                    secCount++;
                }
                if (secCount >= usedSr) {
                    double rms = Math.sqrt(secSumSq / secCount);
                    System.out.println(String.format("  [%2ds] peak=%.3f(%.1f dBFS) rms=%.4f%s",
                            secIndex, secPeak, 20 * Math.log10(Math.max(secPeak, 1e-9)),
                            rms, secPeak > 0.99 ? "  **削波**" : ""));
                    secIndex++;
                    secPeak = 0.0;
                    secSumSq = 0.0;
                    secCount = 0;
                }
                if (clipCount > usedSr * 0.01) {
                    System.out.println("  削波样本过多，提前停止（需降低音量或拉远距离重录）");
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("ERROR 写文件: " + t);
        } finally {
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
            rec.release();
        }
        System.out.println("完成：写入 " + written + " 字节（" +
                String.format("%.2f", written / (double) (usedSr * 2)) + "s）");
        System.out.println("META sr=" + usedSr + " src=" + usedSrc
                + " bytes=" + written + " clip=" + clipCount);
    }
}
