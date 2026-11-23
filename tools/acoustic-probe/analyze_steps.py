# SPDX-FileCopyrightText: 2026 jiangjie
# SPDX-License-Identifier: GPL-3.0-only
"""
声学录音分析：从"步进正弦"录音反推全链路传递函数 / THD / 电平相关压缩。

物理前提：AW882xx 功放在 SoC 之后，数字内录看不到；本工具分析的是
「数字激励 → 麦克风」的全链路（含 功放 DSP + 喇叭 + 麦克风 + 房间）。
因此：
  - 绝对灵敏度无意义（麦克风增益未知），只输出相对曲线；
  - 喇叭自身频响与功放 DSP 静态 EQ 不可分离（除非有单体的消声室数据）；
  - 但「同一麦克风、同一位置、不同播放电平」的差值，可以干净地归因到
    功放的动态行为（限幅/压缩/扬声器保护）——这是本工具最有价值的部分。

用法：
  python analyze_steps.py --file rec_6dB.wav,-6 --file rec_30dB.wav,-30
  python analyze_steps.py --file rec.wav,-6 --sr-hint 48000 --json out.json
"""
import argparse
import json
import math
import os
import struct
import sys
import wave

import numpy as np

SR_NOMINAL = 48000
TONE_DUR = 0.6
GAP = 0.05
REF_DUR = 1.0
REF_FREQ = 1000.0
LEAD_SIL = 0.3
FADE = 0.02
HARM_MAX = 6
kThdMinSnr = 25.0  # 低于该 SNR 时 THD/压缩结论不可信（基波已接近噪声底）


# ---------- WAV 读取 ----------

def read_wav(path):
    """返回 (mono float64 ndarray, sr)。支持 16/24/32-bit PCM 与 float32。"""
    with wave.open(path, "rb") as w:
        nch = w.getnchannels()
        sw = w.getsampwidth()
        sr = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        data = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif sw == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        v = (b[:, 0].astype(np.int32)
             | (b[:, 1].astype(np.int32) << 8)
             | (b[:, 2].astype(np.int32) << 16))
        v = np.where(v >= (1 << 23), v - (1 << 24), v)
        data = v.astype(np.float64) / float(1 << 23)
    elif sw == 4:
        data = np.frombuffer(raw, dtype="<i4").astype(np.float64) / float(1 << 31)
    else:
        raise ValueError("不支持的位宽: %d" % sw)
    if nch > 1:
        data = data.reshape(-1, nch).mean(axis=1)
    return data, sr


def read_f32_raw(path):
    """app 端 AudioDump 的交织 f32（默认双声道）。"""
    data = np.fromfile(path, dtype="<f4").astype(np.float64)
    if data.size % 2 == 0:
        data = data.reshape(-1, 2).mean(axis=1)
    return data, SR_NOMINAL


# ---------- 采样率统一 ----------

def resample_fft(x, sr_in, sr_out):
    if sr_in == sr_out:
        return x
    n_in = x.size
    n_out = int(round(n_in * sr_out / float(sr_in)))
    spec = np.fft.rfft(x)
    n_bins = n_out // 2 + 1
    if n_bins <= spec.size:
        spec = spec[:n_bins]
    else:
        spec = np.concatenate([spec, np.zeros(n_bins - spec.size, dtype=spec.dtype)])
    y = np.fft.irfft(spec, n=n_out)
    return y * (n_out / float(n_in))


# ---------- 对齐 ----------

def find_ref_onset(x, sr, search_end=4.0):
    """定位 1kHz 参考音起点（时间轴对齐基准）。

    不能用能量阈值：实测环境底噪（-30dBFS 峰值）会先触发阈值，把起点
    误判到播放之前，导致所有分析窗落在静音上、THD 算出几百个百分点。
    改用 1kHz 模板互相关：参考音是激励里的第一个 1kHz 成分（1s 长），
    而扫频序列里的 1kHz 单音远在 4s 之后，搜索窗内无歧义。
    """
    seg_len = int(0.1 * sr)
    if x.size < seg_len * 2:
        return 0
    t = np.arange(seg_len) / sr
    tmpl = np.sin(2.0 * np.pi * REF_FREQ * t) * np.hanning(seg_len)
    end = min(x.size, int(search_end * sr))
    corr = np.abs(np.correlate(x[:end], tmpl, mode="valid"))
    if corr.size == 0 or float(corr.max()) <= 0.0:
        return 0
    idx = np.where(corr > 0.5 * float(corr.max()))[0]
    coarse = int(idx[0]) if idx.size else int(np.argmax(corr))
    # 精修：模板互相关会提前约半个模板（~50ms），改用局部 RMS 找真实上升沿
    a = min(x.size, coarse + int(0.15 * sr))
    b = min(x.size, coarse + int(0.85 * sr))
    if b - a < 100:
        return coarse
    ref_rms = float(np.sqrt(np.mean(x[a:b] ** 2)))
    if ref_rms <= 0:
        return coarse
    win = max(16, int(0.005 * sr))
    csum = np.cumsum(np.concatenate([[0.0], x * x]))
    env = np.sqrt(np.maximum(csum[win:] - csum[:-win], 0.0) / win)
    lo = max(0, coarse - int(0.15 * sr))
    hi = min(env.size, coarse + int(0.40 * sr))
    hit = np.where(env[lo:hi] > 0.3 * ref_rms)[0]
    if hit.size:
        return lo + int(hit[0]) + win // 2
    return coarse


# ---------- 单音测量 ----------

def measure_tone(x, sr, start, freq, expect_amp):
    n = int(round(TONE_DUR * sr))
    b = start + int(0.12 * TONE_DUR * sr)
    e = start + int(0.88 * TONE_DUR * sr)
    b = max(0, min(b, x.size - 1))
    e = max(b + 64, min(e, x.size))
    seg = x[b:e]
    nfft = 1
    while nfft < seg.size * 2:
        nfft <<= 1
    # Blackman-Harris 4 项窗：旁瓣 -92dB，避免 Hann 泄漏把 THD 底噪抬到 0.1%+
    m = seg.size
    k = 2.0 * np.pi * np.arange(m) / max(m - 1, 1)
    w = (0.35875 - 0.48829 * np.cos(k) + 0.14128 * np.cos(2.0 * k)
         - 0.01168 * np.cos(3.0 * k))
    spec = np.fft.rfft(seg * w, n=nfft)
    mag = np.abs(spec) / (np.sum(w) / 2.0)
    df = sr / float(nfft)

    def peak_at(f):
        k = int(round(f / df))
        if k < 1 or k >= mag.size - 1:
            return 0.0, f
        a, m, c = mag[k - 1], mag[k], mag[k + 1]
        denom = (a - 2.0 * m + c)
        d = 0.5 * (a - c) / denom if abs(denom) > 1e-20 else 0.0
        amp = m - 0.25 * (a - c) * d
        return max(amp, 0.0), (k + d) * df

    fund, f_meas = peak_at(freq)
    harm = []
    for h in range(2, HARM_MAX + 1):
        if freq * h < sr * 0.48:
            hp, _ = peak_at(freq * h)
            harm.append(hp)
    thd = 0.0
    if fund > 1e-12 and harm:
        thd = math.sqrt(sum(v * v for v in harm)) / fund
    # 噪声底：扣除基波与谐波附近 bin 后的中位数
    keep = np.ones(mag.size, dtype=bool)
    for f0 in [freq] + [freq * h for h in range(2, HARM_MAX + 1)]:
        k = int(round(f0 / df))
        keep[max(0, k - 4):min(mag.size, k + 5)] = False
    lo = int(100.0 / df)
    hi = int(min(sr * 0.48, 20000.0) / df)
    band = mag[lo:hi][keep[lo:hi]]
    noise = float(np.median(band)) if band.size else 0.0
    gain_db = 20.0 * math.log10(fund / expect_amp) if fund > 0 and expect_amp > 0 else float("nan")
    snr_db = 20.0 * math.log10(fund / noise) if noise > 0 and fund > 0 else float("nan")
    return {
        "f": freq,
        "f_meas": f_meas,
        "amp": fund,
        "gain_db": gain_db,
        "thd_pct": thd * 100.0,
        "snr_db": snr_db,
    }


def analyze_file(path, level_db, sr_hint=None):
    if path.lower().endswith(".f32"):
        x, sr = read_f32_raw(path)
    else:
        x, sr = read_wav(path)
    if sr_hint and sr != sr_hint:
        x = resample_fft(x, sr, sr_hint)
        sr = sr_hint
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    clipped = peak > 0.995
    onset = find_ref_onset(x, sr)

    here = os.path.dirname(os.path.abspath(__file__))
    fpath = os.path.join(here, "signals", "freqs.txt")
    with open(fpath, "r", encoding="utf-8") as f:
        freqs = [float(line) for line in f if line.strip()]

    expect_amp = 10.0 ** (level_db / 20.0)
    t_cursor = onset + int(round((REF_DUR + LEAD_SIL) * sr))
    rows = []
    for f0 in freqs:
        r = measure_tone(x, sr, t_cursor, f0, expect_amp)
        rows.append(r)
        t_cursor += int(round((TONE_DUR + GAP) * sr))
    return {
        "file": os.path.basename(path),
        "level_db": level_db,
        "sr": sr,
        "peak": peak,
        "clipped": clipped,
        "onset_s": onset / float(sr),
        "rows": rows,
    }


# ---------- 报告 ----------

def print_report(rep):
    print("\n=== %s  (标称 %+.0f dBFS, sr=%d, 峰值 %.3f%s) ==="
          % (rep["file"], rep["level_db"], rep["sr"], rep["peak"],
             "  **[疑似削波]**" if rep["clipped"] else ""))
    print("  对齐起点 %.3fs" % rep["onset_s"])
    # 相对曲线：以 1kHz 附近（或中频段中位数）为 0dB 参考
    mid = [r["gain_db"] for r in rep["rows"]
           if 400.0 <= r["f"] <= 2500.0 and not math.isnan(r["gain_db"])]
    ref = float(np.median(mid)) if mid else 0.0
    print("  参考点（400-2500Hz 中位增益）%.1f dB → 下表为相对值" % ref)
    print("  %-9s %-9s %-9s %-8s %-8s" % ("频率Hz", "相对dB", "THD%", "SNR dB", "基波"))
    for r in rep["rows"]:
        rel = r["gain_db"] - ref if not math.isnan(r["gain_db"]) else float("nan")
        # SNR 不足时谐波是噪声算出来的假值（实测见过 612%），必须标掉
        thd_txt = ("%-.2f" % r["thd_pct"]) if r["snr_db"] >= kThdMinSnr else "  n/a"
        print("  %-9.0f %-9.1f %-9s %-8.1f %-8.5f"
              % (r["f"], rel, thd_txt, r["snr_db"], r["amp"]))
    valid = [r["gain_db"] - ref for r in rep["rows"] if not math.isnan(r["gain_db"])]
    if valid:
        print("  曲线范围：%.1f ~ %.1f dB（峰谷差 %.1f dB）"
              % (min(valid), max(valid), max(valid) - min(valid)))


def print_compare(reps):
    base = reps[0]
    print("\n" + "=" * 74)
    print("电平相关压缩分析（基准 = %s @ %+.0f dBFS）"
          % (base["file"], base["level_db"]))
    print("  增益已按播放电平归一化：线性链路下 Δ≈0；Δ<0 = 更响那次反而少 = 被压住")
    print("=" * 74)
    for other in reps[1:]:
        d_lvl = other["level_db"] - base["level_db"]
        print("\n--- %s @ %+.0f dBFS，激励差 %+.0f dB ---"
              % (other["file"], other["level_db"], d_lvl))
        print("  %-9s %-10s %-10s" % ("频率Hz", "实测ΔdB", "压缩量dB"))
        compressed = []
        for rb, ro in zip(base["rows"], other["rows"]):
            if math.isnan(rb["gain_db"]) or math.isnan(ro["gain_db"]):
                continue
            # 任一端 SNR 不足则该带结论无效，跳过（低电平低频率段常见）
            if rb["snr_db"] < kThdMinSnr or ro["snr_db"] < kThdMinSnr:
                print("  %-9.0f %-10s %-10s" % (rb["f"], "无信号", "—"))
                continue
            d_meas = ro["gain_db"] - rb["gain_db"]
            comp = -d_meas  # >0 = 更响时被压住
            flag = " <<" if comp > 3.0 else ""
            print("  %-9.0f %-10.1f %-10.1f%s" % (rb["f"], d_meas, comp, flag))
            if comp > 3.0:
                compressed.append((rb["f"], comp))
        if compressed:
            worst = max(compressed, key=lambda t: t[1])
            print("  → 明显被压缩的频带 %d 个，最深 %.0fHz 处 %.1f dB"
                  % (len(compressed), worst[0], worst[1]))
        else:
            print("  → 无可测压缩（该电平差下仍线性）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", action="append", required=True,
                    metavar="PATH,LEVEL_DB",
                    help="录音文件与对应播放电平，可多次指定")
    ap.add_argument("--sr-hint", type=int, default=None)
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    reps = []
    for item in args.file:
        if "," not in item:
            print("参数格式应为 PATH,LEVEL_DB：%s" % item)
            return 2
        path, lv = item.rsplit(",", 1)
        reps.append(analyze_file(path.strip(), float(lv), args.sr_hint))

    for rep in reps:
        print_report(rep)
    if len(reps) > 1:
        print_compare(reps)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(reps, f, ensure_ascii=False, indent=1)
        print("\nJSON 已写入 %s" % args.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
