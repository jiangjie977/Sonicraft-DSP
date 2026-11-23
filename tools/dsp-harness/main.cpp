// 全效果器行业验收 harness（仿 eq-harness：MSVC 直编 native 头文件）
// 用例编号 V*：W=宽度 L=限幅 K=压缩 R=FDN B=多频段 C=卷积
// 用法纪律：①需要参数平滑收敛的节点（width/reverb/convolver wet-dry）逐块
// Process(kFrames)——一次长调用只走一步平滑会测出假衰减（R1 教训）；
// ②逐块循环的块指针必须按块推进；③bypass 默认 true，需要处理路径的用例
// 必须显式 SetBypass(false)（W2/W3 教训）。
// SPDX-License-Identifier: GPL-3.0-only

#include "sonicraft/dsp/compressor.hpp"
#include "sonicraft/dsp/convolver.hpp"
#include "sonicraft/dsp/limiter.hpp"
#include "sonicraft/dsp/multiband.hpp"
#include "sonicraft/dsp/reverb_node.hpp"
#include "sonicraft/dsp/stereo_width.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

using namespace sonicraft::dsp;

static int g_pass = 0, g_fail = 0;

static void report(const char* name, bool ok, const char* detail = nullptr) {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", name,
                detail ? " | " : "", detail ? detail : "");
    if (ok) ++g_pass; else ++g_fail;
}

static constexpr int kSr = 48000;
static constexpr int kFrames = 128;

static unsigned int g_seed = 0x12345678u;
static float frand() {
    g_seed ^= g_seed << 13; g_seed ^= g_seed >> 17; g_seed ^= g_seed << 5;
    return (float)(g_seed & 0xFFFFFF) / (float)(1 << 24) - 0.5f;
}

static bool allFinite(const std::vector<float>& v) {
    for (float x : v) if (!std::isfinite(x)) return false;
    return true;
}

static float peakOf(const std::vector<float>& v, int tail) {
    float m = 0.0f;
    for (int i = (int)v.size() - tail; i < (int)v.size(); ++i)
        if (i >= 0 && std::fabs(v[i]) > m) m = std::fabs(v[i]);
    return m;
}

// 生成两声道测试信号
static void fillStereo(std::vector<float>& L, std::vector<float>& R) {
    for (int n = 0; n < (int)L.size(); ++n) {
        L[n] = 0.4f * std::sin(0.05f * n);
        R[n] = 0.2f * std::cos(0.03f * n);
    }
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    std::printf("=== M12 DSP 全效果器行业验收 ===\n");

    // ================= W. 立体声宽度 =================
    {
        // W1: w=1 恒等（Prepare 后初值即 1）
        StereoWidthNode w1; w1.set_channel_count(2);
        w1.Prepare(kSr, kFrames); w1.SetBypass(false);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        fillStereo(L, R);
        w1.Process(ch, kFrames);
        float err = 0.0f;
        for (int n = 0; n < kFrames; ++n)
            err = std::max(err, std::fabs(L[n] - 0.4f * std::sin(0.05f * n)) +
                                std::fabs(R[n] - 0.2f * std::cos(0.03f * n)));
        report("W1 width=1 identity", err < 1e-5f);
    }
    {
        // W2: w=0 单声道化（30ms 平滑收敛：150 块 = 400ms）
        StereoWidthNode w2; w2.set_channel_count(2);
        w2.Prepare(kSr, kFrames); w2.SetBypass(false);
        w2.SetParam(StereoWidthNode::kParamWidth, 0.0f);
        const int blocks = 150;
        std::vector<float> L(blocks * kFrames), R(blocks * kFrames);
        fillStereo(L, R);
        for (int b = 0; b < blocks; ++b) {
            float* chp[2] = { L.data() + b * kFrames, R.data() + b * kFrames };
            w2.Process(chp, kFrames);
        }
        float err = 0.0f;
        for (int i = (int)L.size() - kFrames; i < (int)L.size(); ++i) {
            const float mid = 0.5f * (0.4f * std::sin(0.05f * i) + 0.2f * std::cos(0.03f * i));
            err = std::max(err, std::fabs(L[i] - mid) + std::fabs(R[i] - mid));
        }
        report("W2 width=0 mono fold", err < 1e-4f);
    }
    {
        // W3: w=2 side 加倍
        StereoWidthNode w3; w3.set_channel_count(2);
        w3.Prepare(kSr, kFrames); w3.SetBypass(false);
        w3.SetParam(StereoWidthNode::kParamWidth, 2.0f);
        const int blocks = 150;
        std::vector<float> L(blocks * kFrames), R(blocks * kFrames);
        fillStereo(L, R);
        for (int b = 0; b < blocks; ++b) {
            float* chp[2] = { L.data() + b * kFrames, R.data() + b * kFrames };
            w3.Process(chp, kFrames);
        }
        float err = 0.0f;
        for (int i = (int)L.size() - kFrames; i < (int)L.size(); ++i) {
            const float a = 0.4f * std::sin(0.05f * i), b = 0.2f * std::cos(0.03f * i);
            err = std::max(err, std::fabs(L[i] - (1.5f * a - 0.5f * b)) +
                                std::fabs(R[i] - (1.5f * b - 0.5f * a)));
        }
        report("W3 width=2 side x2", err < 1e-4f);
    }

    // ================= L. 限幅器（look-ahead 峰值；逐样本实现，长调用合法）=====
    {
        // L1: ceiling 精度
        LimiterNode lim; lim.set_channel_count(2);
        lim.Prepare(kSr, kFrames); lim.SetBypass(false);
        lim.SetParam(0, -1.0f);
        lim.SetParam(1, 60.0f);
        const float ceilLin = std::pow(10.0f, -1.0f / 20.0f);
        std::vector<float> L(kSr), R(kSr);
        float* ch[2] = { L.data(), R.data() };
        for (int n = 0; n < kSr; ++n)
            ch[0][n] = ch[1][n] = 1.5f * std::sin(2.0f * 3.14159265f * 440.0f * n / kSr);
        lim.Process(ch, kSr);
        const float pk = peakOf(L, 4800);
        bool ok = pk <= ceilLin + 0.02f && pk > ceilLin - 0.3f;
        char d[128];
        std::snprintf(d, sizeof(d), "steady peak=%.4f ceiling=%.4f", pk, ceilLin);
        report("L1 limiter ceiling accuracy", ok, d);
    }
    {
        // L2: look-ahead 瞬态（静音突跳，全程含 attack 段不超 ceiling）
        LimiterNode lim; lim.set_channel_count(2);
        lim.Prepare(kSr, kFrames); lim.SetBypass(false);
        lim.SetParam(0, -1.0f);
        lim.SetParam(1, 60.0f);
        const float ceilLin = std::pow(10.0f, -1.0f / 20.0f);
        std::vector<float> L(kSr), R(kSr);
        float* ch[2] = { L.data(), R.data() };
        for (int n = 0; n < kSr; ++n)
            ch[0][n] = ch[1][n] =
                (n < kSr / 2) ? 0.0f : 1.5f * std::sin(2.0f * 3.14159265f * 880.0f * n / kSr);
        lim.Process(ch, kSr);
        float pk = 0.0f;
        for (int n = kSr / 2; n < kSr; ++n) pk = std::max(pk, std::fabs(L[n]));
        bool ok = pk <= ceilLin + 0.02f;
        char d[128];
        std::snprintf(d, sizeof(d), "transient peak=%.4f ceiling=%.4f", pk, ceilLin);
        report("L2 limiter look-ahead transient", ok, d);
    }
    {
        // L3: bypass bit-exact
        LimiterNode lim; lim.set_channel_count(2);
        lim.Prepare(kSr, kFrames); lim.SetBypass(true);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        g_seed = 0xABCDu;
        for (int n = 0; n < kFrames; ++n) { L[n] = frand(); R[n] = frand(); }
        const std::vector<float> Lin = L, Rin = R;
        lim.Process(ch, kFrames);
        bool ok = true;
        for (int n = 0; n < kFrames; ++n)
            if (L[n] != Lin[n] || R[n] != Rin[n]) { ok = false; break; }
        report("L3 limiter bypass bit-exact", ok);
    }

    // ================= K. 压缩器（feed-forward dB 域）=================
    {
        // K1: 静态增益削减曲线——-10dBFS 正弦 4:1 @ -20dB 阈 → 稳态 -17.5dBFS
        CompressorNode cmp; cmp.set_channel_count(2);
        cmp.Prepare(kSr, kFrames); cmp.SetBypass(false);
        cmp.SetParam(0, -20.0f); cmp.SetParam(1, 4.0f);
        cmp.SetParam(2, 10.0f); cmp.SetParam(3, 150.0f); cmp.SetParam(4, 0.0f);
        const int len = kSr * 2;
        std::vector<float> L(len), R(len);
        float* ch[2] = { L.data(), R.data() };
        const float amp = std::pow(10.0f, -10.0f / 20.0f);
        for (int n = 0; n < len; ++n)
            ch[0][n] = ch[1][n] = amp * std::sin(2.0f * 3.14159265f * 220.0f * n / kSr);
        const int blocks = len / kFrames;
        for (int b = 0; b < blocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            cmp.Process(chp, kFrames);
        }
        const float pk = peakOf(L, kSr / 2);
        const float outDb = 20.0f * std::log10(pk + 1e-9f);
        const float want = -20.0f + 10.0f / 4.0f;
        bool ok = std::fabs(outDb - want) < 1.2f;
        char d[128];
        std::snprintf(d, sizeof(d), "steady out=%.2fdB want=%.2fdB", outDb, want);
        report("K1 compressor static curve 4:1", ok, d);
    }
    {
        // K2: 参数阶跃无爆音
        CompressorNode cmp; cmp.set_channel_count(2);
        cmp.Prepare(kSr, kFrames); cmp.SetBypass(false);
        cmp.SetParam(0, -20.0f); cmp.SetParam(1, 4.0f);
        cmp.SetParam(2, 10.0f); cmp.SetParam(3, 150.0f);
        const int blocks = 40;
        std::vector<float> L(blocks * kFrames), R(blocks * kFrames);
        float* ch[2] = { L.data(), R.data() };
        for (int n = 0; n < (int)L.size(); ++n)
            ch[0][n] = ch[1][n] = 0.5f * std::sin(2.0f * 3.14159265f * 220.0f * n / kSr);
        float prevRms = -1.0f, worstJump = 0.0f;
        for (int b = 0; b < blocks; ++b) {
            if (b == 20) cmp.SetParam(0, -40.0f);
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            cmp.Process(chp, kFrames);
            float s = 0.0f;
            for (int n = 0; n < kFrames; ++n) s += L[b * kFrames + n] * L[b * kFrames + n];
            const float rms = std::sqrt(s / kFrames);
            if (prevRms >= 0.0f && prevRms > 1e-6f)
                worstJump = std::max(worstJump,
                    std::fabs(20.0f * std::log10(rms / prevRms)));
            prevRms = rms;
        }
        bool ok = worstJump < 6.0f && allFinite(L);
        char d[128];
        std::snprintf(d, sizeof(d), "worst block RMS jump=%.2fdB", worstJump);
        report("K2 compressor step smoothness", ok, d);
    }

    // ================= R. FDN 混响 =================
    {
        // R1: dry 直通精度（wet=0 dry=1；平滑收敛 60 块）
        ReverbNode rev; rev.set_channel_count(2);
        rev.Prepare(kSr, kFrames); rev.SetBypass(false);
        rev.SetParam(3, 0.0f); rev.SetParam(4, 1.0f);
        const int blocks = 60;
        std::vector<float> L(blocks * kFrames), R(blocks * kFrames);
        float* ch[2] = { L.data(), R.data() };
        for (int n = 0; n < (int)L.size(); ++n)
            ch[0][n] = ch[1][n] = 0.5f * std::sin(2.0f * 3.14159265f * 1000.0f * n / kSr);
        for (int b = 0; b < blocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            rev.Process(chp, kFrames);
        }
        float pk = 0.0f;
        for (int i = (int)L.size() - kFrames; i < (int)L.size(); ++i)
            pk = std::max(pk, std::fabs(L[i]));
        const float outDb = 20.0f * std::log10(pk + 1e-9f);
        bool ok = std::fabs(outDb - (-6.02f)) < 0.5f;
        char d[96];
        std::snprintf(d, sizeof(d), "dry passthrough=%.2fdB want=-6.02", outDb);
        report("R1 FDN dry passthrough", ok, d);
    }
    {
        // R2: 湿声衰减（冲激 → 分窗 RMS 不增长即可，衰减率随 roomSize）
        ReverbNode rev; rev.set_channel_count(2);
        rev.Prepare(kSr, kFrames); rev.SetBypass(false);
        rev.SetParam(0, 0.5f); rev.SetParam(1, 0.4f);
        rev.SetParam(3, 1.0f); rev.SetParam(4, 0.0f);
        const int blocks = kSr * 2 / kFrames;
        std::vector<float> L(blocks * kFrames, 0.0f), R(blocks * kFrames, 0.0f);
        float* ch[2] = { L.data(), R.data() };
        L[0] = 1.0f;
        for (int b = 0; b < blocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            rev.Process(chp, kFrames);
        }
        float r1 = 0, r2 = 0;
        for (int i = 0; i < 4800; ++i) r1 += L[i] * L[i];
        for (int i = 4800; i < 9600; ++i) r2 += L[i] * L[i];
        r1 = std::sqrt(r1 / 4800); r2 = std::sqrt(r2 / 4800);
        bool ok = r1 > 1e-5f && r2 <= r1 + 1e-6f;
        char d[128];
        std::snprintf(d, sizeof(d), "wet rms first=%.6f second=%.6f (non-growing)", r1, r2);
        report("R2 FDN wet response", ok, d);
    }
    {
        // R3: 长稳（in-place 白噪声 20000 块）
        ReverbNode rev; rev.set_channel_count(2);
        rev.Prepare(kSr, kFrames); rev.SetBypass(false);
        rev.SetParam(0, 0.8f); rev.SetParam(1, 0.2f);
        rev.SetParam(3, 0.8f); rev.SetParam(4, 0.2f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        bool ok = true; float worst = 0.0f;
        for (int b = 0; b < 20000 && ok; ++b) {
            for (int n = 0; n < kFrames; ++n) { L[n] = frand(); R[n] = frand(); }
            rev.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n) {
                if (!std::isfinite(L[n]) || !std::isfinite(R[n])) { ok = false; break; }
                worst = std::max(worst, std::fabs(L[n]));
            }
        }
        char d[96];
        std::snprintf(d, sizeof(d), "20000 blocks, worst |L|=%.3f", worst);
        report("R3 FDN long-run stability", ok, d);
    }

    // ================= B. 多频段压限 =================
    {
        // B1: 合成平坦（三段不压缩，LR4 分频合成 RMS 守恒）
        MultibandNode mb; mb.set_channel_count(2);
        mb.Prepare(kSr, kFrames); mb.SetBypass(false);
        mb.SetParam(18, 200.0f); mb.SetParam(19, 2000.0f);
        for (int s = 0; s < 3; ++s) {
            mb.SetParam(s * 6 + 0, 0.0f);
            mb.SetParam(s * 6 + 5, 0.0f);
        }
        const int blocks = 60;
        std::vector<float> L(blocks * kFrames), R(blocks * kFrames);
        std::vector<float> Lin(blocks * kFrames), Rin(blocks * kFrames);
        float* ch[2] = { L.data(), R.data() };
        g_seed = 0x777u;
        for (int n = 0; n < (int)L.size(); ++n) {
            Lin[n] = L[n] = frand() * 0.6f; Rin[n] = R[n] = frand() * 0.6f;
        }
        for (int b = 0; b < blocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            mb.Process(chp, kFrames);
        }
        double si = 0, so = 0;
        for (int i = blocks * kFrames / 2; i < blocks * kFrames; ++i) {
            si += Lin[i] * Lin[i]; so += L[i] * L[i];
        }
        const float rmsIn = std::sqrt((float)(si / (blocks * kFrames / 2)));
        const float rmsOut = std::sqrt((float)(so / (blocks * kFrames / 2)));
        const float dev = std::fabs(20.0f * std::log10((rmsOut + 1e-9f) / (rmsIn + 1e-9f)));
        bool ok = dev < 1.5f && allFinite(L);
        char d[128];
        std::snprintf(d, sizeof(d), "rms in=%.4f out=%.4f dev=%.2fdB", rmsIn, rmsOut, dev);
        report("B1 multiband synthesis flatness", ok, d);
    }
    {
        // B4: 每段计量准确性。
        // 三段阈值都压到 -40dB、比率 8:1，喂 0.5 幅度 100Hz 正弦（落在低段）→ 低段必然
        // 深度压缩。断言三件事：① in_peak > 0（分段后确有信号）；② GR 明显为负（真的在压）；
        // ③ out_peak < in_peak（输出被压下来了）。三者同时成立才能确认计量链路正确——
        // 只看 in_peak 有值是不够的（可能是没压缩时的直通）。
        MultibandNode mb; mb.set_channel_count(2);
        mb.Prepare(kSr, kFrames); mb.SetBypass(false);
        mb.SetParam(18, 200.0f); mb.SetParam(19, 2000.0f);
        for (int s = 0; s < 3; ++s) {
            mb.SetParam(s * 6 + 0, -40.0f);  // threshold
            mb.SetParam(s * 6 + 1, 8.0f);    // ratio
            mb.SetParam(s * 6 + 2, 5.0f);    // attack
            mb.SetParam(s * 6 + 3, 120.0f);  // release
        }
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        double ph = 0.0;
        for (int b = 0; b < 40; ++b) {
            for (int n = 0; n < kFrames; ++n) {
                const float v = 0.5f * static_cast<float>(std::sin(ph));
                ph += 2.0 * 3.14159265358979 * 100.0 / kSr;
                if (ph > 2.0 * 3.14159265358979) ph -= 2.0 * 3.14159265358979;
                L[n] = v; R[n] = v;
            }
            mb.Process(ch, kFrames);
        }
        const float inPk = mb.DiagnBandInPeak(0);
        const float outPk = mb.DiagnBandOutPeak(0);
        const float gr = mb.DiagnBandGrDb(0);
        bool ok = inPk > 0.05f && gr < -3.0f && outPk < inPk;
        char d4b[160];
        std::snprintf(d4b, sizeof(d4b), "low band: in=%.3f out=%.3f gr=%.1fdB", inPk, outPk, gr);
        report("B4 multiband per-band metering", ok, d4b);
    }
    {
        // B2: 长稳（in-place 白噪声 10000 块，三段动力学全开）
        MultibandNode mb; mb.set_channel_count(2);
        mb.Prepare(kSr, kFrames); mb.SetBypass(false);
        mb.SetParam(18, 150.0f); mb.SetParam(19, 1800.0f);
        for (int s = 0; s < 3; ++s) {
            mb.SetParam(s * 6 + 0, -30.0f); mb.SetParam(s * 6 + 1, 3.0f);
            mb.SetParam(s * 6 + 2, 5.0f);  mb.SetParam(s * 6 + 3, 120.0f);
        }
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        bool ok = true;
        for (int b = 0; b < 10000 && ok; ++b) {
            for (int n = 0; n < kFrames; ++n) { L[n] = frand(); R[n] = frand(); }
            mb.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (!std::isfinite(L[n]) || !std::isfinite(R[n])) { ok = false; break; }
        }
        report("B2 multiband long-run stability", ok);
    }

    // ================= C. 卷积混响（2x2 交叉矩阵 UPC）=================
    {
        // C1: head 段保真（64 样本 IR < h_=128 纯 head 零延迟；delta 输入 → 输出=IR）
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        std::vector<float> ir(64);
        for (int i = 0; i < 64; ++i)
            ir[i] = 0.5f * std::exp(-i / 14.0f) * (i % 2 ? -1.0f : 1.0f);
        conv.SetIrSlot2x2(1, ir.data(), ir.data(), ir.data(), ir.data(), 64, 4);
        conv.SetParam(0, 1.0f);
        conv.SetParam(1, 1.0f); conv.SetParam(2, 0.0f);
        const int warmBlocks = 150;
        const int len = (warmBlocks + 4) * kFrames;
        std::vector<float> L(len, 0.0f), R(len, 0.0f);
        float* ch[2] = { L.data(), R.data() };
        for (int b = 0; b < warmBlocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            conv.Process(chp, kFrames);
        }
        L[warmBlocks * kFrames] = 1.0f;   // 单声道冲激（L=1, R=0）：断言参照为单路径 IR
        float* chImp[2] = { ch[0] + warmBlocks * kFrames, ch[1] + warmBlocks * kFrames };
        conv.Process(chImp, kFrames);
        const int off = warmBlocks * kFrames;
        float err = 0.0f;
        for (int i = 0; i < 64; ++i) err = std::max(err, std::fabs(L[off + i] - ir[i]));
        bool ok = err < 1e-4f;
        char d[96];
        std::snprintf(d, sizeof(d), "err=%.2e out=%.4f %.4f %.4f ir0=%.3f", err, L[off], L[off+1], L[off+2], ir[0]);
        report("C1 convolver head fidelity", ok, d);
    }
    {
        // C2: tail 延迟恒定（1200 样本 IR：head 零延迟、tail 出现在 +P=1024 处）
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        const int irLen = 1200;
        std::vector<float> ir(irLen, 0.0f);
        g_seed = 0xBEEFu;
        for (int i = 128; i < irLen; ++i)
            ir[i] = 0.3f * frand() * std::exp(-(i - 128) / 400.0f);
        conv.SetIrSlot2x2(1, ir.data(), ir.data(), ir.data(), ir.data(), irLen, 4);
        conv.SetParam(0, 1.0f);
        conv.SetParam(1, 1.0f); conv.SetParam(2, 0.0f);
        const int warmBlocks = 150;
        const int playBlocks = 24;   // 覆盖 tail 1024+1200（约 18 块）
        const int len = (warmBlocks + playBlocks) * kFrames;
        std::vector<float> L(len, 0.0f), R(len, 0.0f);
        float* ch[2] = { L.data(), R.data() };
        for (int b = 0; b < warmBlocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            conv.Process(chp, kFrames);
        }
        L[warmBlocks * kFrames] = 1.0f;   // 单声道冲激（L=1, R=0）：断言参照为单路径 IR
        for (int b = warmBlocks; b < warmBlocks + playBlocks; ++b) {
            float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
            conv.Process(chp, kFrames);
        }
        const int off = warmBlocks * kFrames;
        float headErr = 0.0f, tailErr = 0.0f, tailMatch = 0.0f;
        for (int i = 0; i < 128; ++i) headErr = std::max(headErr, std::fabs(L[off + i] - ir[i]));
        for (int i = 128; i < 1024; ++i)
            tailErr = std::max(tailErr, std::fabs(L[off + i] - 0.0f));
        for (int i = 128; i < irLen - 128; ++i)
            tailMatch = std::max(tailMatch, std::fabs(L[off + 1024 + i] - ir[i]));
        bool ok = headErr < 1e-4f && tailErr < 1e-3f && tailMatch < 5e-2f;
        char d[192];
        std::snprintf(d, sizeof(d), "head=%.2e tailgap=%.2e tailmatch=%.2e",
            headErr, tailErr, tailMatch);
        report("C2 convolver tail partition alignment", ok, d);
    }
    {
        // C3: 长稳（in-place 白噪声 8000 块，内置 SmallRoom 预设）
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        conv.SetParam(0, 1.0f);
        conv.SetParam(1, 1.0f); conv.SetParam(2, 0.3f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        bool ok = true;
        for (int b = 0; b < 8000 && ok; ++b) {
            for (int n = 0; n < kFrames; ++n) { L[n] = frand(); R[n] = frand(); }
            conv.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (!std::isfinite(L[n]) || !std::isfinite(R[n])) { ok = false; break; }
        }
        report("C3 convolver long-run stability", ok);
    }
    {
        // C5: wet 哨兵不得误触发。
        // 哨兵判据：曾经产出过湿声 → 现在连续 200 块湿声恒零、而干声仍有电平 → Reset 自愈。
        // 正常信号下这个判据绝不能成立，一旦误触发就会周期性清掉卷积状态，
        // 用户听到的是混响断续——比原缺陷更糟。所以这里跑 600 块（3 倍阈值）白噪声，
        // 断言 resets==0 且湿声仍在。
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        conv.SetParam(0, 1.0f);  // SmallRoom 预设
        conv.SetParam(1, 1.0f);  // wet 全开
        conv.SetParam(2, 0.3f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        for (int b = 0; b < 600; ++b) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.2f * frand(); R[n] = 0.2f * frand(); }
            conv.Process(ch, kFrames);
        }
        const int resets = conv.DiagnWetSentinelResets();
        const float wetRms = conv.DiagnWetRms();
        bool ok = (resets == 0) && (wetRms > 1e-3f);
        char d5[144];
        std::snprintf(d5, sizeof(d5), "resets=%d wetRms=%.5f (expect resets=0, wet>0)", resets, wetRms);
        report("C5 wet sentinel no false trigger", ok, d5);
    }
    {
        // C6: 教堂预设输出健康度诊断。
        // 三项分开看，避免把不同病因混为一谈：
        //   ① 全零输入时输出应恒 0 —— 非零说明 IR 有 DC 或状态泄漏（会持续推着输出偏移）
        //   ② 白噪声下的峰值 —— 超过 SoftClip 的 0.9 线性上限就进入 tanh 压缩区
        //   ③ 削波样本占比 —— 低频大信号持续压缩会产生可闻的「啵啵」调制
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        conv.SetParam(0, 4.0f);  // 教堂（kPresetCathedral）
        conv.SetParam(1, 1.0f);  // wet 1.0 = 归一基准
        conv.SetParam(2, 1.0f);  // dry 1.0（实际再乘 kDryGain=0.75）
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };

        float maxSilent = 0.0f;
        for (int b = 0; b < 200; ++b) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.0f; R[n] = 0.0f; }
            conv.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n) maxSilent = std::max(maxSilent, std::fabs(L[n]));
        }

        conv.Reset();
        float peak = 0.0f;
        long long over = 0;
        double dc = 0.0;
        long long cnt = 0;
        for (int b = 0; b < 400; ++b) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.3f * frand(); R[n] = 0.3f * frand(); }
            conv.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n) {
                const float a = std::fabs(L[n]);
                peak = std::max(peak, a);
                if (a > 0.9f) ++over;
                dc += L[n];
                ++cnt;
            }
        }
        char d6[224];
        std::snprintf(d6, sizeof(d6), "silentMax=%.2e peak=%.3f over0.9=%.1f%% dc=%.2e",
                      maxSilent, peak, 100.0 * static_cast<double>(over) / static_cast<double>(cnt),
                      dc / static_cast<double>(cnt));
        // 判据：静音必须真的静音；峰值不得贴到 SoftClip 天花板（否则持续非线性）
        report("C6 cathedral headroom diagnostic",
               maxSilent < 1e-6f && peak < 0.99f, d6);
    }
    {
        // C7: 分区卷积块边界连续性。
        // 若 overlap-add 在 tail 批次边界有错位，输出会在固定周期处出现不连续，
        // 听感就是规律的「啵啵」。做法：跑连续正弦，统计「跨边界」与「边界内」的
        // 一阶差分均值之比。稳态连续信号的比值应接近 1；显著 >1 即有跳变。
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        conv.SetParam(0, 4.0f);  // 教堂
        conv.SetParam(1, 1.0f);
        conv.SetParam(2, 0.0f);  // 只留湿声，避免干声掩盖边界跳变
        std::vector<float> out;
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        const int blocks = 200;
        out.reserve(static_cast<std::size_t>(blocks) * kFrames);
        double ph = 0.0;
        for (int b = 0; b < blocks; ++b) {
            for (int n = 0; n < kFrames; ++n) {
                const float v = 0.3f * static_cast<float>(std::sin(ph));
                ph += 2.0 * 3.14159265358979 * 1000.0 / kSr;
                if (ph > 2.0 * 3.14159265358979) ph -= 2.0 * 3.14159265358979;
                L[n] = v; R[n] = v;
            }
            conv.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n) out.push_back(L[n]);
        }
        // 丢掉前 40 块（状态收敛），再统计
        const int start = 40 * kFrames;
        const int cand[] = {128, 192, 256, 512, 768, 1024, 1536, 2048};
        float worstRatio = 1.0f;
        int worstPeriod = 0;
        for (int pi = 0; pi < 8; ++pi) {
            const int per = cand[pi];
            double bs = 0.0, is = 0.0;
            long long bc = 0, ic = 0;
            for (int n = start + 1; n < (int)out.size(); ++n) {
                const double d = std::fabs(static_cast<double>(out[n]) - out[n - 1]);
                if (n % per == 0) { bs += d; ++bc; } else { is += d; ++ic; }
            }
            if (bc == 0 || ic == 0) continue;
            const float ratio = static_cast<float>((bs / bc) / (is / ic + 1e-12));
            if (ratio > worstRatio) { worstRatio = ratio; worstPeriod = per; }
        }
        char d7[160];
        std::snprintf(d7, sizeof(d7), "worst boundary/inner diff ratio=%.2f at period=%d (expect ~1.0)",
                      worstRatio, worstPeriod);
        report("C7 partition boundary continuity", worstRatio < 1.5f, d7);
    }
    {
        // C9: 满刻度输入下的教堂 headroom。
        // C6 用 0.3 幅度白噪声得出「不削波」，但真实音乐峰值接近满刻度。教堂恰好是
        // 干声增益最低（0.75）+ 湿声能量最持久（2.5s IR）的组合，叠加后可能反复把
        // 输出推进 SoftClip 的 tanh 压缩区 —— tanh 在 0.9~1.0 区间压缩很陡，偶发触发
        // 听起来就是「滋啦」。这里用 0.9 幅度模拟音乐峰值，统计进入非线性区的比例。
        ConvolverNode conv; conv.set_channel_count(2);
        conv.Prepare(kSr, kFrames); conv.SetBypass(false);
        conv.SetParam(0, 4.0f);  // 教堂
        conv.SetParam(1, 1.0f);  // wet 1.0
        conv.SetParam(2, 1.0f);  // dry 1.0（实际再乘 0.75）
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        float peak = 0.0f;
        long long over = 0;
        long long cnt = 0;
        for (int b = 0; b < 400; ++b) {
            for (int n = 0; n < kFrames; ++n) {
                L[n] = 0.9f * frand();
                R[n] = 0.9f * frand();
            }
            conv.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n) {
                const float a = std::fabs(L[n]);
                peak = std::max(peak, a);
                if (a > 0.9f) ++over;
                ++cnt;
            }
        }
        char d9[176];
        std::snprintf(d9, sizeof(d9), "peak=%.3f over0.9=%.2f%% (full-scale input 0.9)",
                      peak, 100.0 * static_cast<double>(over) / static_cast<double>(cnt));
        // 判据：进入 tanh 压缩区的样本不得超过 1%（否则偶发可闻失真）
        report("C9 cathedral headroom at full scale", 100.0 * over / cnt < 1.0, d9);
    }
    {
        // C10: 四个预设的分区边界连续性横向对比。
        // C7 只测了教堂、且只看平均比值。但用户实测「大房间连续 3-5 分钟无异常、只有教堂有」，
        // 而教堂的 tail 分区数（80）是大房间（38）的两倍以上 —— 若 overlap-add 有随分区数累积
        // 的误差，就会出现这种「只有最长 IR 才听得出」的现象。
        // 这里对四个预设都跑一遍，同时看「平均比值」与「最坏单点跳变 / 内部 99.9 分位」。
        const char* pname[] = {"?", "小房间", "客厅", "大房间", "教堂"};
        for (int preset = 1; preset <= 4; ++preset) {
            ConvolverNode cv; cv.set_channel_count(2);
            cv.Prepare(kSr, kFrames); cv.SetBypass(false);
            cv.SetParam(0, static_cast<float>(preset));
            cv.SetParam(1, 1.0f);
            cv.SetParam(2, 0.0f);  // 纯湿声，避免干声掩盖边界跳变
            std::vector<float> out;
            std::vector<float> L(kFrames), R(kFrames);
            float* chp[2] = { L.data(), R.data() };
            const int blocks = 240;
            out.reserve(static_cast<std::size_t>(blocks) * kFrames);
            double ph = 0.0;
            for (int b = 0; b < blocks; ++b) {
                for (int n = 0; n < kFrames; ++n) {
                    const float v = 0.3f * static_cast<float>(std::sin(ph));
                    ph += 2.0 * 3.14159265358979 * 1000.0 / kSr;
                    if (ph > 2.0 * 3.14159265358979) ph -= 2.0 * 3.14159265358979;
                    L[n] = v; R[n] = v;
                }
                cv.Process(chp, kFrames);
                for (int n = 0; n < kFrames; ++n) out.push_back(L[n]);
            }
            const int start = 40 * kFrames;
            // 内部与边界的差分集合（period 固定 1536 = tail 批次长）
            std::vector<double> inner, boundary;
            for (int n = start + 1; n < (int)out.size(); ++n) {
                const double d = std::fabs(static_cast<double>(out[n]) - out[n - 1]);
                if (n % 1536 == 0) boundary.push_back(d); else inner.push_back(d);
            }
            double bs = 0.0, is = 0.0;
            for (double v : boundary) bs += v;
            for (double v : inner) is += v;
            const double avg = (bs / std::max<size_t>(1, boundary.size())) /
                               (is / std::max<size_t>(1, inner.size()) + 1e-12);
            double maxB = 0.0;
            for (double v : boundary) maxB = std::max(maxB, v);
            std::vector<double> srt = inner;
            std::sort(srt.begin(), srt.end());
            const double p999 = srt.empty() ? 0.0 : srt[static_cast<std::size_t>(srt.size() * 0.999)];
            char d10[176];
            std::snprintf(d10, sizeof(d10),
                          "avgRatio=%.2f maxBoundaryDiff=%.4f innerP99.9=%.4f maxInnerRatio=%.2f",
                          avg, maxB, p999, maxB / (p999 + 1e-12));
            char label[64];
            std::snprintf(label, sizeof(label), "C10 boundary continuity [%s]", pname[preset]);
            report(label, maxB < p999 * 3.0, d10);
        }
    }

    {
        // B3: 多频段相位一致性（crossover 处群延迟不应随频率跳变）。
        // 做法：同一套分频参数，分别喂 100Hz 与 6kHz 正弦（覆盖低/高频带，
        // 6k 落在 mid-high 分频点之上），用互相关求样本延迟；
        // 断言两者延迟差 ≤ 1 样本 —— 若 crossover 相位不连续，此处会明显分离。
        // B3: 分频点相位是否导致抵消 —— 用**对数扫频的幅度平坦度**判定。
        // 理由：LR4 最小相位分频的群延迟本就随频率变化（实测 100Hz≈201、
        // 6kHz≈67 样本），拿群延迟跳变当判据会把正常斜率误判成缺陷（前两版踩过）。
        // 而「相位不连续」的真实危害是相邻频带抵消 → 扫频幅度在分频点塌陷，
        // 这条指标直接命中后果本身。
        const float f1 = 100.0f, f2 = 10000.0f;
        const int blocks = 60;                       // 60×2048 ≈ 2.56s
        const int total = blocks * kFrames;
        std::vector<float> L(total), R(total, 0.0f);
        {
            const double T = (double)total / kSr;
            const double k = std::log((double)f2 / f1);
            double ph = 0.0;
            for (int n = 0; n < total; ++n) {
                const double t = (double)n / kSr;
                const double f = f1 * std::exp(k * t / T);       // 指数扫频
                L[n] = 0.3f * (float)std::sin(ph);
                ph += 2.0 * 3.14159265358979 * f / kSr;
            }
        }
        std::vector<float> in = L;
        {
            MultibandNode mb; mb.set_channel_count(2);
            mb.Prepare(kSr, kFrames); mb.SetBypass(false);
            mb.SetParam(18, 200.0f); mb.SetParam(19, 2000.0f);
            for (int s = 0; s < 3; ++s) {
                mb.SetParam(s * 6 + 0, 0.0f);
                mb.SetParam(s * 6 + 5, 0.0f);
            }
            for (int b = 0; b < blocks; ++b) {
                float* chp[2] = { L.data() + b * kFrames, R.data() + b * kFrames };
                mb.Process(chp, kFrames);
            }
        }
        // 分段 RMS 比值（dB）：跳过首尾各 10% 以避开扫频起止瞬态
        const int segs = 24;
        const int s0 = total / 10, s1 = total - total / 10;
        const int segLen = (s1 - s0) / segs;
        double worst = 0.0;
        for (int s = 0; s < segs; ++s) {
            double ai = 0, ao = 0;
            const int b0 = s0 + s * segLen;
            for (int n = b0; n < b0 + segLen; ++n) {
                ai += (double)in[n] * in[n];
                ao += (double)L[n] * L[n];
            }
            const double db = 10.0 * std::log10((ao + 1e-12) / (ai + 1e-12));
            worst = std::max(worst, std::fabs(db));
        }
        char db3[160];
        std::snprintf(db3, sizeof(db3), "sweep 100Hz-10kHz worst dev=%.2f dB over %d segs",
                      worst, segs);
        report("B3 multiband crossover phase (no cancellation)", worst <= 2.0, db3);
    }

    // ================= 补测=================
    {
        // C4: wet/dry 线性精度。
        // Dry 预设 head=δ（LL[0]=RR[0]=1）、tail 全零，故输出应为 dry_g·x + wet_g·x。
        // 取 dry=0.5 / wet=0.5（dry_base=1）→ 期望输出 ≈ 输入本身。若混音权重实现
        // 有误（例如把 wet 当成 1-wet、或 dry 未乘基准），这里会立刻偏掉。
        // 叠加原理：y(wet=1,dry=0) + y(wet=0,dry=1) == y(wet=1,dry=1)。
        // 不依赖各预设的 kDryGain 取值（Dry 预设的 dry_gain 其实是 0，首版假设错了）。
        // 输入幅度取 0.02，使输出端 SoftClip 工作在近线性区。
        const int blocks = 80;
        auto runCase = [&](float wet, float dry, std::vector<float>& L, std::vector<float>& R) {
            ConvolverNode cv; cv.set_channel_count(2);
            cv.Prepare(kSr, kFrames); cv.SetBypass(false);
            cv.SetParam(0, 1.0f);  // SmallRoom 预设
            cv.SetParam(1, wet);
            cv.SetParam(2, dry);
            for (int b = 0; b < blocks; ++b) {
                float* chp[2] = { L.data() + b * kFrames, R.data() + b * kFrames };
                cv.Process(chp, kFrames);
            }
        };
        auto makeInput = [&]() {
            std::vector<float> L(blocks * kFrames), R(blocks * kFrames);
            for (int i = 0; i < (int)L.size(); ++i) {
                L[i] = 0.02f * std::sin(0.05f * i);
                R[i] = 0.01f * std::cos(0.03f * i);
            }
            return std::make_pair(L, R);
        };
        auto a1 = makeInput();
        auto b1 = makeInput();
        auto c1 = makeInput();
        runCase(1.0f, 0.0f, a1.first, a1.second);   // 仅湿
        runCase(0.0f, 1.0f, b1.first, b1.second);   // 仅干
        runCase(1.0f, 1.0f, c1.first, c1.second);   // 干+湿
        float err = 0.0f;
        for (int i = (blocks - 20) * kFrames; i < (int)a1.first.size(); ++i) {
            err = std::max(err, std::fabs(c1.first[i] - (a1.first[i] + b1.first[i])));
            err = std::max(err, std::fabs(c1.second[i] - (a1.second[i] + b1.second[i])));
        }
        char d4[128];
        std::snprintf(d4, sizeof(d4), "superposition max err=%.2e (amplitude 0.02)", err);
        report("C4 convolver wet/dry mix linearity", err < 5e-4f, d4);
    }
    {
        // L4: 真峰值（inter-sample）。正弦取 fs/4 时采样点可能全部落在低幅处，
        // 采样峰值会低估真实峰值。这里用 4 倍线性插值估计 inter-sample 峰值，
        // 断言不超过 ceiling + 余量（限幅器应带 look-ahead 处理段间峰值）。
        LimiterNode lim; lim.set_channel_count(2);
        lim.Prepare(kSr, kFrames); lim.SetBypass(false);
        lim.SetParam(0, -1.0f);
        lim.SetParam(1, 60.0f);
        const float ceilLin = std::pow(10.0f, -1.0f / 20.0f);
        const int n = kSr;
        std::vector<float> L(n), R(n);
        float* ch[2] = { L.data(), R.data() };
        for (int i = 0; i < n; ++i) {
            const float s = 1.5f * std::sin(2.0f * 3.14159265f * 0.25f * i);  // fs/4
            ch[0][i] = ch[1][i] = s;
        }
        lim.Process(ch, n);
        float tp = 0.0f;
        for (int i = 0; i + 1 < n; ++i) {
            const float a = std::fabs(L[i]), b2 = std::fabs(L[i + 1]);
            tp = std::max(tp, a);
            for (int k = 1; k < 4; ++k) {
                const float t = 0.25f * static_cast<float>(k);
                tp = std::max(tp, std::fabs(a + (b2 - a) * t));
            }
        }
        bool ok = tp <= ceilLin + 0.06f && tp > ceilLin - 0.35f;
        char d5[128];
        std::snprintf(d5, sizeof(d5), "true-peak(x4)=%.4f ceiling=%.4f", tp, ceilLin);
        report("L4 limiter true-peak (inter-sample)", ok, d5);
    }

    std::printf("\n=== RESULT: PASS=%d FAIL=%d ===\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
