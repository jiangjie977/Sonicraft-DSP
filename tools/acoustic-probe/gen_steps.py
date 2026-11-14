# SPDX-FileCopyrightText: 2026 jiangjie
# SPDX-License-Identifier: GPL-3.0-only
"""
扬声器/功放声学测量激励信号生成（步进正弦法）。

为什么不用扫频：步进正弦每次只出一个频率，分析窗口内是稳态正弦，
可以同时得到「基波幅度（传递函数）+ 谐波（THD）+ 噪声底」，实现简单、
对录音对齐误差不敏感。扫频在高频段停留时间太短，SNR 不够。

输出：48kHz / 16bit PCM WAV，三通道模式（双声道 / 仅左 / 仅右）× 多电平。
每个文件结构：
    [1.0s] 1kHz 参考音（用于录音对齐与电平标定）
    [0.3s] 静音
    逐条 1/3 倍频程正弦，每条 0.6s（含 20ms 淡入淡出），条间 0.05s 静音
    [0.5s] 静音
"""
import math
import os
import struct
import sys
import wave

import numpy as np

SR = 48000
TONE_DUR = 0.6
FADE = 0.02
GAP = 0.05
REF_DUR = 1.0
REF_FREQ = 1000.0
LEAD_SIL = 0.3
TAIL_SIL = 0.5
F_MIN, F_MAX = 50.0, 16000.0
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "signals")


def third_octave_centers(f_min, f_max):
    """1/3 倍频程中心频率（以 1kHz 为基准），筛选到 [f_min, f_max]。"""
    out = []
    k = -60
    while k <= 60:
        f = 1000.0 * (2.0 ** (k / 3.0))
        if f_min <= f <= f_max:
            out.append(round(f, 2))
        k += 1
    return out


def tone(freq, dur, sr, fade):
    n = int(round(dur * sr))
    t = np.arange(n) / sr
    x = np.sin(2.0 * np.pi * freq * t)
    nf = int(round(fade * sr))
    if nf > 0 and n > 2 * nf:
        ramp = 0.5 - 0.5 * np.cos(np.pi * np.arange(nf) / nf)
        x[:nf] *= ramp
        x[-nf:] *= ramp[::-1]
    return x


def build(level_db, freqs):
    parts = []
    parts.append(tone(REF_FREQ, REF_DUR, SR, FADE))
    parts.append(np.zeros(int(LEAD_SIL * SR)))
    for f in freqs:
        parts.append(tone(f, TONE_DUR, SR, FADE))
        parts.append(np.zeros(int(GAP * SR)))
    parts.append(np.zeros(int(TAIL_SIL * SR)))
    x = np.concatenate(parts)
    peak = np.max(np.abs(x))
    if peak > 0:
        x = x / peak * (10.0 ** (level_db / 20.0))
    return x


def write_wav(path, mono, mode):
    """mode: both / left / right"""
    if mode == "both":
        left = right = mono
    elif mode == "left":
        left, right = mono, np.zeros_like(mono)
    else:
        left, right = np.zeros_like(mono), mono
    inter = np.empty(left.size * 2, dtype=np.float64)
    inter[0::2] = left
    inter[1::2] = right
    pcm = np.clip(inter * 32767.0, -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    freqs = third_octave_centers(F_MIN, F_MAX)
    levels = [-6.0, -18.0, -30.0]
    modes = ["both", "left", "right"]
    print("1/3 倍频程中心频率 %d 条：" % len(freqs))
    print("  " + "  ".join("%.0f" % f for f in freqs))
    total_dur = REF_DUR + LEAD_SIL + len(freqs) * (TONE_DUR + GAP) + TAIL_SIL
    print("单文件时长 %.2fs，采样率 %d" % (total_dur, SR))
    made = []
    for lv in levels:
        x = build(lv, freqs)
        for mode in modes:
            name = "steps_%s_%ddB.wav" % (mode, int(abs(lv)))
            path = os.path.join(OUT_DIR, name)
            write_wav(path, x, mode)
            made.append((name, os.path.getsize(path)))
    # 分析用的频率表，analyze 侧读取同一份
    with open(os.path.join(OUT_DIR, "freqs.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join("%.2f" % v for v in freqs))
    print("\n生成 %d 个文件：" % len(made))
    for n, sz in made:
        print("  %-28s %6.2f MB" % (n, sz / 1048576.0))
    print("\n输出目录：%s" % OUT_DIR)


if __name__ == "__main__":
    sys.exit(main())
