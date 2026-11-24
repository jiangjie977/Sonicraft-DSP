/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 链路增益审计：输入 0dBFS 正弦，逐节点测「默认参数」与「真机实际参数」下的峰值/RMS 变化。
 *
 * 目的：回答「哪个效果器吃了多少 dB」。输入峰值 1.0 = 0dBFS，输出按 20log10(out/in) 记。
 * 稳态正弦只对线性/动态节点有意义；时间类（卷积/混响/环绕）不在此工具范围。
 *
 * 真机实际参数（2026-11-24 从 NX733J prefs 读取）：
 *   单段：enabled=false, thr=-3, ratio=1.4, atk=15, rel=220, knee=3, makeup=+2
 *   多段：enabled=true, Low(-3,2,10,120,6,0) Mid(-3,2.5,8,150,6,0.5) High(-14,2,5,110,6,0)
 *   其他：input_gain=0.8, output_gain=1.4, stereo_width=1.3946, limiter_ceiling=-0.451
 */
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "sonicraft/dsp/balance_node.hpp"
#include "sonicraft/dsp/compressor.hpp"
#include "sonicraft/dsp/gain_node.hpp"
#include "sonicraft/dsp/limiter.hpp"
#include "sonicraft/dsp/multiband.hpp"
#include "sonicraft/dsp/parametric_eq.hpp"
#include "sonicraft/dsp/stereo_width.hpp"

namespace {

constexpr int kSr = 48000;
constexpr int kBlock = 512;
constexpr double kPi = 3.14159265358979323846;

struct Result {
    double peak_db;
    double rms_db;
};

template <typename ProcessFn>
Result Measure(ProcessFn process, double freq = 1000.0, int blocks = 300, int tail = 120) {
    std::vector<float> l(kBlock), r(kBlock);
    double phase = 0.0;
    const double w = 2.0 * kPi * freq / static_cast<double>(kSr);

    double peak = 0.0;
    double sum_sq = 0.0;
    long long n = 0;

    for (int b = 0; b < blocks; ++b) {
        for (int i = 0; i < kBlock; ++i) {
            const float v = static_cast<float>(std::sin(phase));
            phase += w;
            if (phase > 2.0 * kPi) phase -= 2.0 * kPi;
            l[i] = v;
            r[i] = v;
        }
        float* ch[2] = {l.data(), r.data()};
        process(ch, kBlock);
        if (b >= blocks - tail) {
            for (int i = 0; i < kBlock; ++i) {
                const double a = std::fabs(static_cast<double>(l[i]));
                if (a > peak) peak = a;
                sum_sq += static_cast<double>(l[i]) * static_cast<double>(l[i]);
                ++n;
            }
        }
    }

    const double rms = n > 0 ? std::sqrt(sum_sq / static_cast<double>(n)) : 0.0;
    Result out;
    out.peak_db = peak > 1e-12 ? 20.0 * std::log10(peak) : -240.0;
    out.rms_db = rms > 1e-12 ? 20.0 * std::log10(rms) : -240.0;
    return out;
}

void Report(const char* name, const Result& res, const char* note = "") {
    std::printf("%-34s peak %+7.2f dB   rms %+7.2f dB   %s\n", name, res.peak_db, res.rms_db, note);
}

struct CompParams {
    float threshold_db;
    float ratio;
    float attack_ms;
    float release_ms;
    float makeup_db;
    float knee_db;
    float sidechain_hp;
    float mix;
};

Result RunCompressor(const CompParams& p) {
    sonicraft::dsp::CompressorNode node;
    node.set_channel_count(2);
    node.Prepare(kSr, kBlock);
    node.Reset();
    node.SetParam(0, p.threshold_db);
    node.SetParam(1, p.ratio);
    node.SetParam(2, p.attack_ms);
    node.SetParam(3, p.release_ms);
    node.SetParam(4, p.makeup_db);
    node.SetParam(5, p.knee_db);
    node.SetParam(6, p.sidechain_hp);
    node.SetParam(7, p.mix);
    node.SetParam(8, 0.0f);
    node.SetParam(9, 0.0f);
    node.SetBypass(false);
    return Measure([&](float* const* ch, int frames) { node.Process(ch, frames); });
}

Result RunMultiband(const float params[20]) {
    sonicraft::dsp::MultibandNode node;
    node.set_channel_count(2);
    node.Prepare(kSr, kBlock);
    node.Reset();
    for (int i = 0; i < 20; ++i) node.SetParam(i, params[i]);
    node.SetBypass(false);
    return Measure([&](float* const* ch, int frames) { node.Process(ch, frames); });
}

Result RunGain(float gain) {
    sonicraft::dsp::GainNode node;
    node.set_channel_count(2);
    node.Prepare(kSr, kBlock);
    node.Reset();
    node.SetParam(0, gain);
    node.SetBypass(false);
    return Measure([&](float* const* ch, int frames) { node.Process(ch, frames); });
}

Result RunStereoWidth(float width) {
    sonicraft::dsp::StereoWidthNode node;
    node.set_channel_count(2);
    node.Prepare(kSr, kBlock);
    node.Reset();
    node.SetParam(0, width);
    node.SetBypass(false);
    return Measure([&](float* const* ch, int frames) { node.Process(ch, frames); });
}

Result RunLimiter(float ceiling_db) {
    sonicraft::dsp::LimiterNode node;
    node.set_channel_count(2);
    node.Prepare(kSr, kBlock);
    node.Reset();
    node.SetParam(0, ceiling_db);
    node.SetParam(1, 60.0f);
    node.SetParam(2, 1.0f);
    node.SetBypass(false);
    return Measure([&](float* const* ch, int frames) { node.Process(ch, frames); });
}

// 默认（代码出厂）参数
const CompParams kCompDefault = {-3.0f, 1.4f, 15.0f, 220.0f, 2.0f, 3.0f, 120.0f, 1.0f};
// 真机实际参数（2026-11-24 读取）
const CompParams kCompActual = {-3.0f, 1.4f, 15.0f, 220.0f, 2.0f, 3.0f, 120.0f, 1.0f};

const float kMbDefault[20] = {
    -3.0f, 2.0f, 10.0f, 120.0f, 6.0f, 0.0f,
    -3.0f, 2.5f, 8.0f, 150.0f, 6.0f, 0.5f,
    -14.0f, 2.0f, 5.0f, 110.0f, 6.0f, 0.0f,
    200.0f, 3500.0f,
};

const float kMbActual[20] = {
    -3.0f, 2.0f, 10.0f, 120.0f, 6.0f, 0.0f,
    -3.0f, 2.5f, 8.0f, 150.0f, 6.0f, 0.5f,
    -14.0f, 2.0f, 5.0f, 110.0f, 6.0f, 0.0f,
    200.0f, 3500.0f,
};

}  // namespace

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    std::printf("input: 0 dBFS 1kHz sine (peak 1.0, rms -3.01 dBFS), 48kHz, block %d\n", kBlock);
    std::printf("delta is relative to the input peak/rms\n\n");

    Report("[baseline] pass-through", Measure([](float* const*, int) {}), "(input itself)");

    std::printf("\n--- factory defaults ---\n");
    Report("input_gain (1.0)", RunGain(1.0f));
    Report("PEQ (flat)", [] { sonicraft::dsp::ParametricEqNode n; n.set_channel_count(2); n.Prepare(kSr, kBlock); n.Reset(); n.SetBypass(false); return Measure([&](float* const* ch, int f) { n.Process(ch, f); }); }(), "");
    Report("compressor (DEFAULT)", RunCompressor(kCompDefault), "-3dB 1.4:1 makeup +2 (new)");
    Report("multiband (DEFAULT)", RunMultiband(kMbDefault), "1kHz -> Mid -3dB 2.5:1 +0.5 (new)");
    Report("output_gain (1.0)", RunGain(1.0f));
    Report("limiter (ceiling -1dB)", RunLimiter(-1.0f), "peak only");

    std::printf("\n--- NX733J actual (2026-11-24 prefs) ---\n");
    Report("input_gain (0.8)", RunGain(0.8f), "-1.94 dB");
    Report("compressor (ACTUAL, DISABLED)", RunCompressor(kCompActual), "-3dB 1.4:1 makeup +2 (switch=off)");
    Report("multiband (ACTUAL, ENABLED)", RunMultiband(kMbActual), "Low-3/2 Mid-3/2.5 High-14/2");
    Report("stereo_width (1.3946)", RunStereoWidth(1.3946f), "mid/side gain");
    Report("output_gain (1.4)", RunGain(1.4f), "+2.92 dB");
    Report("limiter (ceiling -0.451)", RunLimiter(-0.4511f), "peak only");

    // 真机整链（多段开 + input/output gain + limiter）
    {
        sonicraft::dsp::GainNode inGain;
        inGain.set_channel_count(2);
        inGain.Prepare(kSr, kBlock);
        inGain.Reset();
        inGain.SetParam(0, 0.8f);
        inGain.SetBypass(false);

        sonicraft::dsp::MultibandNode mb;
        mb.set_channel_count(2);
        mb.Prepare(kSr, kBlock);
        mb.Reset();
        for (int i = 0; i < 20; ++i) mb.SetParam(i, kMbActual[i]);
        mb.SetBypass(false);

        sonicraft::dsp::StereoWidthNode sw;
        sw.set_channel_count(2);
        sw.Prepare(kSr, kBlock);
        sw.Reset();
        sw.SetParam(0, 1.3946f);
        sw.SetBypass(false);

        sonicraft::dsp::GainNode outGain;
        outGain.set_channel_count(2);
        outGain.Prepare(kSr, kBlock);
        outGain.Reset();
        outGain.SetParam(0, 1.4f);
        outGain.SetBypass(false);

        sonicraft::dsp::LimiterNode lim;
        lim.set_channel_count(2);
        lim.Prepare(kSr, kBlock);
        lim.Reset();
        lim.SetParam(0, -0.4511f);
        lim.SetParam(1, 60.0f);
        lim.SetParam(2, 1.0f);
        lim.SetBypass(false);

        const Result r = Measure([&](float* const* ch, int frames) {
            inGain.Process(ch, frames);
            mb.Process(ch, frames);
            sw.Process(ch, frames);
            outGain.Process(ch, frames);
            lim.Process(ch, frames);
        });
        Report("FULL CHAIN (actual)", r, "in0.8 -> MB -> width -> out1.4 -> lim");
    }

    std::printf("\nnote: convolver / reverb / virtualsurround / s71 / dynamic_bass / bass_enhance\n");
    std::printf("      are content-dependent (time or low-frequency shaping); steady sine not representative.\n");
    return 0;
}
