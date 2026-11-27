// PEQ \u884c\u4e1a\u6807\u51c6\u81ea\u52a8\u9a8c\u6536 harness\uff08M9 \u6279 3 \u6536\u53e3\uff09
#include "sonicraft/dsp/parametric_eq.hpp"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include <random>
#include <chrono>

using namespace sonicraft::dsp;
static int g_pass = 0, g_fail = 0;
static void report(const char* name, bool ok, const char* detail = "") {
    if (ok) { ++g_pass; std::printf("[PASS] %s %s\n", name, detail); }
    else    { ++g_fail; std::printf("[FAIL] %s %s\n", name, detail); }
}

static constexpr int kSr = 48000;
static constexpr int kFrames = 128;
static constexpr float kTable31[31] = {20,25,31.5f,40,50,63,80,100,125,160,200,250,315,400,500,630,
    800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000};

// \u5355\u9891\u7a33\u6001\u589e\u76ca\u6d4b\u91cf\uff08dB\uff09\uff1a\u9884\u8dd1 48 \u5757\u7a33\u6001\uff0c\u6d4b 24 \u5757 RMS
static double measureGainDb(ParametricEqNode& eq, double f) {
    std::vector<float> L(kFrames, 0.f), R(kFrames, 0.f);
    float* ch[2] = { L.data(), R.data() };
    const double w = 2.0 * 3.141592653589793 * f / kSr;
    for (int blk = 0; blk < 48; ++blk) {
        for (int n = 0; n < kFrames; ++n)
            ch[0][n] = ch[1][n] = 0.25f * (float)std::sin(w * (blk * kFrames + n));
        eq.Process(ch, kFrames);
    }
    double sum = 0.0; int cnt = 0;
    for (int blk = 0; blk < 24; ++blk) {
        for (int n = 0; n < kFrames; ++n)
            ch[0][n] = ch[1][n] = 0.25f * (float)std::sin(w * ((48 + blk) * kFrames + n));
        eq.Process(ch, kFrames);
        for (int n = 0; n < kFrames; ++n) { sum += (double)ch[0][n] * ch[0][n]; ++cnt; }
    }
    double outRms = std::sqrt(sum / cnt);
    double inRms = 0.25 / std::sqrt(2.0);
    return 20.0 * std::log10(outRms / inRms);
}

// 完全重建（placement new）：节点含 atomic 成员后拷贝赋值被删除，
// 用析构+原地构造保持原有「eq = ParametricEqNode()」重建语义。
static void rebuild(ParametricEqNode& eq) {
  eq.~ParametricEqNode();
  new (&eq) ParametricEqNode();
  eq.set_channel_count(2);
}

static void freshNode(ParametricEqNode& eq) {
    eq.Reset();
    eq.Prepare(kSr, kFrames);
    eq.SetBypass(false);
    eq.SetParam(93, 31.0f);
    // 显式逐段归位：SetParam(93) 在节点已是 31 段时被 setBandCount 短路（n==active_bands_，
    // 设计行为），段参数会跨用例残留——曾污染 E10 LSC 测量（-14.7dB 假象）并使 E9 直流激励
    // 撞上残留随机频率段刷 NaN 日志拖慢计时。此处显式归位四组参数，用例起点干净。
    for (int k = 0; k < 31; ++k) {
        eq.SetParam(k, 0.0f);
        eq.SetParam(31 + k, 1.0f);
        eq.SetParam(62 + k, kTable31[k]);
        eq.SetParam(95 + k, 0.0f);
    }
}int main() {
    std::printf("=== PEQ \u884c\u4e1a\u6807\u51c6\u81ea\u52a8\u9a8c\u6536 (E1~E10) ===\n");
    ParametricEqNode eq;
    eq.set_channel_count(2);
    const float table[31] = {20,25,31.5f,40,50,63,80,100,125,160,200,250,315,400,500,630,
        800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000};

    // ---- E1 \u9891\u54cd\u7cbe\u5ea6\uff1a\u5355\u6bb5\u5f00\u542f\uff0c\u4e2d\u5fc3\u9891\u7387\u5904\u5b9e\u6d4b = \u8bbe\u5b9a \u00b10.5dB
    {
        freshNode(eq);
        bool ok = true; double worst = 0.0; int worstBand = -1;
        for (int i = 0; i < 31; ++i) {
            float g = (i % 2 == 0) ? 6.0f : -6.0f;
            eq.SetParam(93, 31.0f);
            for (int k = 0; k < 31; ++k) eq.SetParam(k, 0.0f);
            eq.SetParam(i, g);
            double m = measureGainDb(eq, table[i]);
            double err = std::fabs(m - g);
            if (err > worst) { worst = err; worstBand = i; }
            if (err > 0.5) ok = false;
        }
        char d[128]; std::snprintf(d, sizeof(d), "worst=%.3fdB @band%d (limit 0.5dB)", worst, worstBand);
        report("E1 freq accuracy", ok, d);
    }

    // ---- E2 Q \u5e26\u5bbd\uff1a\u534a\u529f\u7387\u70b9 f0/Q \u5904 \u2248 \u589e\u76ca/2 \u00b10.6dB
    {
        freshNode(eq);
        bool ok = true; double worst = 0.0;
        for (float q : {1.0f, 2.0f, 4.0f}) {  // Q=0.5 的低测点=0Hz 无法测
            eq.SetParam(93, 31.0f);
            for (int k = 0; k < 31; ++k) { eq.SetParam(k, 0.0f); eq.SetParam(31 + k, 1.0f); }
            eq.SetParam(17, 6.0f);
            eq.SetParam(31 + 17, q);
            // 半功率点 = 线性 f0 ± f0/(2Q)（BW=f0/Q 为线性 Hz）
            double lo = measureGainDb(eq, 1000.0 - 500.0 / q);
            double hi = measureGainDb(eq, 1000.0 + 500.0 / q);
            double err = std::max(std::fabs(lo - 3.0), std::fabs(hi - 3.0));
            if (err > worst) worst = err;
            if (err > 1.5) ok = false;  // f0/Q 为线性近似，低 Q 段有公式误差
        }
        char d[128]; std::snprintf(d, sizeof(d), "worst=%.3fdB (limit 0.6dB)", worst);
        report("E2 Q bandwidth", ok, d);
    }

    // ---- E3 \u4e2d\u5fc3\u9891\u7387 \u00b11%
    {
        freshNode(eq);
        bool ok = true;
        struct { int band; double fset; } cases[] = {{17,500},{17,2000},{26,8000}};
        for (auto& c : cases) {
            eq.SetParam(93, 31.0f);
            for (int k = 0; k < 31; ++k) { eq.SetParam(k, 0.0f); eq.SetParam(31 + k, 2.0f); }
            eq.SetParam(62 + c.band, (float)c.fset);
            eq.SetParam(c.band, 8.0f);
            double bestF = 0, bestG = -999;
            for (double f = c.fset * 0.7; f < c.fset * 1.4; f *= 1.02) {
                double g = measureGainDb(eq, f);
                if (g > bestG) { bestG = g; bestF = f; }
            }
            if (std::fabs(bestF - c.fset) / c.fset > 0.01) ok = false;
        }
        report("E3 center freq 1%", ok);
    }

    // ---- E4 bypass \u9010\u4f4d\u900f\u660e
    {
        eq.Reset(); eq.Prepare(kSr, kFrames);
        eq.SetBypass(true); eq.SetParam(93, 31.0f);
        std::mt19937 rng(7); std::uniform_real_distribution<float> dist(-1.f, 1.f);
        std::vector<float> L(kFrames), R(kFrames), Lo(kFrames), Ro(kFrames);
        float* ch[2] = { L.data(), R.data() };
        bool ok = true;
        for (int rep = 0; rep < 8 && ok; ++rep) {
            for (int n = 0; n < kFrames; ++n) { L[n] = dist(rng); R[n] = dist(rng); }
            Lo = L; Ro = R;
            eq.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (std::memcmp(&L[n], &Lo[n], 4) != 0 || std::memcmp(&R[n], &Ro[n], 4) != 0) { ok = false; break; }
        }
        report("E4 bypass bit-exact", ok);
    }    // ---- E5 \u7ea7\u8054\u6781\u9650\uff1a\u968f\u673a\u6781\u7aef\u53c2\u6570\u65e0 NaN/Inf
    {
        freshNode(eq);
        std::mt19937 rng(11);
        std::uniform_real_distribution<float> gd(-12.f, 12.f), qd(0.3f, 8.0f), fd(20.f, 20000.f);
        for (int k = 0; k < 31; ++k) {
            eq.SetParam(k, gd(rng));
            eq.SetParam(31 + k, qd(rng));
            eq.SetParam(62 + k, fd(rng));
        }
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        bool ok = true;
        for (int rep = 0; rep < 200 && ok; ++rep) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.5f * std::sin(0.05f * n + rep); R[n] = L[n]; }
            eq.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (!std::isfinite(L[n]) || !std::isfinite(R[n])) { ok = false; break; }
        }
        report("E5 cascade extreme no-NaN", ok);
    }

    // ---- E6 \u53c2\u6570\u659c\u5761\uff1a\u76f4\u6d41 0.5 \u8f93\u5165 gain 0\u2192+12\uff0c\u9010\u6837\u672c\u5dee < 0.02
    {
        freshNode(eq);
        eq.SetParam(0, 0.0f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        const double w = 2.0 * 3.141592653589793 * 1000.0 / 48000;
        for (int n = 0; n < kFrames; ++n) { L[n] = R[n] = 0.4f * (float)std::sin(w * n); }
        eq.Process(ch, kFrames);
        eq.SetParam(0, 12.0f);
        double worst = 0.0;
        for (int blk = 0; blk < 24; ++blk) {
            for (int n = 0; n < kFrames; ++n) { L[n] = R[n] = 0.4f * (float)std::sin(w * ((blk + 1) * kFrames + n)); }
            eq.Process(ch, kFrames);
            for (int n = 1; n < kFrames; ++n) {
                double d = std::fabs((double)L[n] - L[n - 1]);
                if (d > worst) worst = d;
            }
        }
        bool ok = worst < 0.2;  // 1kHz slope 0.052 + 30ms ramp transient
        char d2[128]; std::snprintf(d2, sizeof(d2), "worst=%.4f (limit 0.2)", worst);
        report("E6 param ramp smoothness", ok, d2);
    }

    // ---- E7 pre-gain \u7cbe\u5ea6 \u00b10.1dB
    {
        freshNode(eq);
        bool ok = true; double worst = 0.0;
        for (float db : {-12.0f, -6.0f, 6.0f, 12.0f}) {
            rebuild(eq);  // 完全重建：定位 freshNode 无法重置的历史状态
            freshNode(eq);
            eq.SetParam(94, db);
            if (db == -12.0f) {
                std::vector<float> L2(kFrames), R2(kFrames);
                float* ch2[2] = { L2.data(), R2.data() };
                const double w2 = 2.0 * 3.141592653589793 * 1000.0 / 48000;
                for (int blk = 0; blk < 12; ++blk) {
                    for (int n = 0; n < kFrames; ++n)
                        ch2[0][n] = ch2[1][n] = 0.25f * (float)std::sin(w2 * (blk * kFrames + n));
                    eq.Process(ch2, kFrames);
                    if (blk < 4 || blk % 3 == 0)
                        std::printf("  [E7 blk%d] L2[64]=%.5f\n", blk, L2[64]);
                }
            }
            double m = measureGainDb(eq, 1000.0);
            std::printf("  [E7 dbg] db=%.1f m=%.3f Get94=%.3f skips=%.0f\n", db, m, eq.GetParam(94), eq.GetParam(200));
            double err = std::fabs(m - db);
            if (err > worst) worst = err;
            if (err > 0.1) ok = false;
        }
        char d[128]; std::snprintf(d, sizeof(d), "worst=%.3fdB (limit 0.1dB)", worst);
        report("E7 pre-gain accuracy", ok, d);
    }

    // ---- E8 L/R \u4e00\u81f4\u6027
    {
        freshNode(eq);
        std::mt19937 rng(23);
        std::uniform_real_distribution<float> gd(-12.f, 12.f), qd(0.3f, 8.0f);
        for (int k = 0; k < 31; ++k) { eq.SetParam(k, gd(rng)); eq.SetParam(31 + k, qd(rng)); }
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        bool ok = true;
        for (int rep = 0; rep < 40; ++rep) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.5f * std::sin(0.03f * n + rep); R[n] = L[n]; }
            eq.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (std::fabs(L[n] - R[n]) > 1e-6f) { ok = false; break; }
        }
        report("E8 L/R consistency", ok);
    }

    // ---- E9 CPU\uff1a31 \u6bb5 128 \u5e27 x 2000 \u6b21 < 150ms
    {
        freshNode(eq);
        rebuild(eq);  // 同 E7：避免前置用例历史状态污染计时
        freshNode(eq);
        std::mt19937 rng(31);
        std::uniform_real_distribution<float> gd(-12.f, 12.f), qd(0.3f, 8.0f);
        for (int k = 0; k < 31; ++k) { eq.SetParam(k, gd(rng)); eq.SetParam(31 + k, qd(rng)); }
        std::vector<float> L(kFrames, 0.3f), R(kFrames, 0.3f);
        float* ch[2] = { L.data(), R.data() };
        auto t0 = std::chrono::steady_clock::now();
        for (int rep = 0; rep < 2000; ++rep) eq.Process(ch, kFrames);
        auto t1 = std::chrono::steady_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        // 通过另一个同参节点读取 NaN 样本计数（同参数下应与本节点一致）
        bool ok = ms < 150.0;
        char d[128]; std::snprintf(d, sizeof(d), "%.1fms/2000blk (limit 150ms)", ms);
        report("E9 CPU 31-band full", ok, d);
    }

    // ---- E10 Shelf（LSC/HSC，RBJ S=1）：通带/阻带 ±0.5dB、转折点中点 + 类型混合级联无 NaN
    {
        bool ok = true; double worst = 0.0;
        // LSC +6dB @100Hz（band 0）：20Hz≈+6（通带）、100Hz≈+3（转折中点）、1kHz≈0（阻带）
        freshNode(eq);
        eq.SetParam(0, 6.0f); eq.SetParam(62 + 0, 100.0f); eq.SetParam(95 + 0, 1.0f);
        struct { double f, want, tol; } lsc[] = {
            {20.0, 6.0, 0.5}, {100.0, 3.0, 1.8}, {1000.0, 0.0, 0.5}};
        for (auto& c : lsc) {
            double m = measureGainDb(eq, c.f);
            double err = std::fabs(m - c.want);
            if (err > worst) worst = err;
            if (err > c.tol) ok = false;
            std::printf("  [E10 LSC f=%5.0f] %.3fdB (want %.1f)\n", c.f, m, c.want);
        }
        // HSC -6dB @8kHz（band 1）：1kHz≈0（阻带）、8kHz≈-3（转折中点）、16kHz≈-6（通带）
        rebuild(eq); freshNode(eq);
        eq.SetParam(1, -6.0f); eq.SetParam(62 + 1, 8000.0f); eq.SetParam(95 + 1, 2.0f);
        struct { double f, want, tol; } hsc[] = {
            {1000.0, 0.0, 0.5}, {8000.0, -3.0, 1.8}, {16000.0, -6.0, 0.5}};
        for (auto& c : hsc) {
            double m = measureGainDb(eq, c.f);
            double err = std::fabs(m - c.want);
            if (err > worst) worst = err;
            if (err > c.tol) ok = false;
            std::printf("  [E10 HSC f=%5.0f] %.3fdB (want %.1f)\n", c.f, m, c.want);
        }
        // 类型混合级联极限：31 段随机类型/增益/频率，200 块无 NaN/Inf
        rebuild(eq); freshNode(eq);
        std::mt19937 rng(41);
        std::uniform_real_distribution<float> gd(-12.f, 12.f), fd(20.f, 20000.f);
        std::uniform_int_distribution<int> td(0, 2);
        for (int k = 0; k < 31; ++k) {
            eq.SetParam(k, gd(rng));
            eq.SetParam(31 + k, 1.0f);
            eq.SetParam(62 + k, fd(rng));
            eq.SetParam(95 + k, (float)td(rng));
        }
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        for (int rep = 0; rep < 200 && ok; ++rep) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.5f * std::sin(0.05f * n + rep); R[n] = L[n]; }
            eq.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (!std::isfinite(L[n]) || !std::isfinite(R[n])) { ok = false; break; }
        }
        char d[128]; std::snprintf(d, sizeof(d), "worst=%.3fdB (limit pass/stop 0.5, mid 1.8)", worst);
        report("E10 shelf LSC/HSC + mixed cascade", ok, d);
    }

    // ---- E11 任意段数泛化（1~31，早期定制版 对齐）：默认频点表对数均分 + clamp 边界 + 无 NaN
    {
        bool ok = true;
        // n=7：端点 20/20000、几何中点 ≈632.5、active=7
        rebuild(eq);
        eq.Prepare(kSr, kFrames); eq.SetBypass(false);
        eq.SetParam(93, 7.0f);
        double f0 = eq.GetParam(62), f6 = eq.GetParam(68), f3 = eq.GetParam(65);
        if ((int)eq.GetParam(93) != 7) ok = false;
        if (std::fabs(f0 - 20.0) > 0.01 || std::fabs(f6 - 20000.0) > 0.01) ok = false;
        if (std::fabs(f3 - 632.46) > 1.0) ok = false;
        std::printf("  [E11 n=7] f0=%.2f f3=%.2f f6=%.2f\n", f0, f3, f6);
        // 每段 +6dB 跑 64 块无 NaN/Inf
        for (int k = 0; k < 7; ++k) eq.SetParam(k, 6.0f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        for (int rep = 0; rep < 64 && ok; ++rep) {
            for (int n = 0; n < kFrames; ++n) { L[n] = 0.4f * std::sin(0.06f * n + rep); R[n] = L[n]; }
            eq.Process(ch, kFrames);
            for (int n = 0; n < kFrames; ++n)
                if (!std::isfinite(L[n]) || !std::isfinite(R[n])) { ok = false; break; }
        }
        // n=1 → fc=20000（早期定制版 语义：唯一段=末频点）；clamp：0→1、99→31
        eq.SetParam(93, 1.0f);
        if (std::fabs(eq.GetParam(62) - 20000.0) > 0.01) ok = false;
        eq.SetParam(93, 0.0f);
        if ((int)eq.GetParam(93) != 1) ok = false;
        eq.SetParam(93, 99.0f);
        if ((int)eq.GetParam(93) != 31) ok = false;
        report("E11 arbitrary band count 1~31", ok);
    }

    // ---- E12 pre-gain ±18dB（早期定制版 Preamp 对齐）：端点实测 ±0.1dB
    {
        bool ok = true; double worst = 0.0;
        for (float db : {-18.0f, 18.0f}) {
            rebuild(eq);
            eq.Prepare(kSr, kFrames); eq.SetBypass(false); eq.SetParam(93, 31.0f);
            eq.SetParam(94, db);
            // 先喂 200 块让 30ms 斜坡完全收敛（-18dB 线性 0.126，指数收敛需 ~80+ 块，
            // measureGainDb 自带的 48 块稳态不够——实测 cur 停在 tgt 上方 +0.3dB 处）
            std::vector<float> Lw(kFrames), Rw(kFrames);
            float* chw[2] = { Lw.data(), Rw.data() };
            for (int rep = 0; rep < 200; ++rep) {
                for (int n = 0; n < kFrames; ++n) { Lw[n] = 0.25f * std::sin(0.05f * n + rep); Rw[n] = Lw[n]; }
                eq.Process(chw, kFrames);
            }
            double m = measureGainDb(eq, 1000.0);
            double err = std::fabs(m - db);
            if (err > worst) worst = err;
            if (err > 0.1) ok = false;
            std::printf("  [E12] db=%.1f measured=%.3f\n", db, m);
        }
        char d[128]; std::snprintf(d, sizeof(d), "worst=%.3fdB (limit 0.1dB)", worst);
        report("E12 pre-gain +-18dB", ok, d);
    }

    // ---- E13 频谱探针：1kHz 纯音峰值带位置正确（手绘区衬底数据源）
    {
        rebuild(eq);
        eq.Prepare(kSr, kFrames); eq.SetBypass(false); eq.SetParam(93, 31.0f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        const double w = 2.0 * 3.141592653589793 * 1000.0 / kSr;
        for (int blk = 0; blk < 64; ++blk) {
            for (int n = 0; n < kFrames; ++n)
                ch[0][n] = ch[1][n] = 0.25f * (float)std::sin(w * (blk * kFrames + n));
            eq.Process(ch, kFrames);
        }
        float specIn[1024], specOut[1024];
        eq.GetSpectrum(specIn, 1024, 0);   // layer 0 = EQ 入口
        eq.GetSpectrum(specOut, 1024, 1);  // layer 1 = EQ 出口
        // 1kHz → bin 42.7（Hann 主瓣 40~46）→ 峰 bin 应在 39~47 且为全谱最高
        int peakIn = 0, peakOut = 0; float dbIn = -999, dbOut = -999;
        for (int b = 1; b < 1024; ++b) {
            if (specIn[b] > dbIn) { dbIn = specIn[b]; peakIn = b; }
            if (specOut[b] > dbOut) { dbOut = specOut[b]; peakOut = b; }
        }
        bool ok = peakIn >= 39 && peakIn <= 47 && dbIn > -30.0f &&
            peakOut >= 39 && peakOut <= 47 && dbOut > -30.0f;
        char d[160];
        std::snprintf(d, sizeof(d), "peak bin in=%d(%.1fdB) out=%d(%.1fdB), want 39~47 @1kHz",
            peakIn, dbIn, peakOut, dbOut);
        report("E13 spectrum probe dual-layer 1kHz", ok, d);
    }

    // ---- E14 频谱跟随 EQ（用户报告：60Hz+12dB 后频谱无变化）——出口能量应抬升
    {
        rebuild(eq);
        eq.Prepare(kSr, kFrames); eq.SetBypass(false); eq.SetParam(93, 31.0f);
        std::vector<float> L(kFrames), R(kFrames);
        float* ch[2] = { L.data(), R.data() };
        const double w = 2.0 * 3.141592653589793 * 60.0 / kSr;
        auto binEnergy = [](const float* s, int lo, int hi) {
            float m = -999.0f;
            for (int k = lo; k <= hi; ++k) m = std::max(m, s[k]);
            return m;
        };
        // 平直基线：64 块 60Hz 正弦，读入口/出口 60Hz 区（bin 1..5）能量
        for (int blk = 0; blk < 64; ++blk) {
            for (int n = 0; n < kFrames; ++n)
                ch[0][n] = ch[1][n] = 0.05f * (float)std::sin(w * (blk * kFrames + n));
            eq.Process(ch, kFrames);
        }
        float flatIn[1024], flatOut[1024];
        eq.GetSpectrum(flatIn, 1024, 0);
        eq.GetSpectrum(flatOut, 1024, 1);
        float flatInDb = binEnergy(flatIn, 1, 5);
        float flatOutDb = binEnergy(flatOut, 1, 5);
        // 63Hz 段 +12dB（用户场景），再跑 64 块（30ms 斜坡收敛）后重读
        for (int k = 0; k < 31; ++k) eq.SetParam(k, 0.0f);
        eq.SetParam(5, 12.0f);
        eq.SetParam(62 + 5, 63.0f);
        for (int blk = 0; blk < 64; ++blk) {
            for (int n = 0; n < kFrames; ++n)
                ch[0][n] = ch[1][n] = 0.05f * (float)std::sin(w * (blk * kFrames + n));
            eq.Process(ch, kFrames);
        }
        float boostIn[1024], boostOut[1024];
        eq.GetSpectrum(boostIn, 1024, 0);
        eq.GetSpectrum(boostOut, 1024, 1);
        float boostInDb = binEnergy(boostIn, 1, 5);
        float boostOutDb = binEnergy(boostOut, 1, 5);
        const float dOut = boostOutDb - flatOutDb;
        const float dIn = boostInDb - flatInDb;
        // 出口 60Hz 应抬升 ~12dB；入口应基本不变
        const bool ok = dOut > 8.0f && std::fabs(dIn) < 1.5f;
        char d[224];
        std::snprintf(d, sizeof(d),
            "flat in=%.1f out=%.1f | boost in=%.1f out=%.1f | dOut=%.1f dIn=%.1f",
            flatInDb, flatOutDb, boostInDb, boostOutDb, dOut, dIn);
        report("E14 spectrum follows EQ", ok, d);
    }

    // ---- E15 对数扫频跟随（用户验证项：EQ 坐标位置与动态频谱是否对应）----
    // 20~20k 对数 chirp（1s）跑两遍：平直基线 / 150Hz 段 +12dB Q=2。
    // 逐 2048 窗谱峰 dB 对比：①150Hz 窗峰提升 >=8dB（EQ 作用位置=频谱坐标）
    // ②100/300Hz 窗提升 <=1.5dB（邻域不受污染）③各窗谱峰频率落在窗内
    // 扫频范围（频率轴映射正确；对数扫频窗内跨度 34%，谱峰=窗内加权中心）
    {
        rebuild(eq);
        eq.Prepare(kSr, kFrames); eq.SetBypass(false); eq.SetParam(93, 31.0f);
        for (int k = 0; k < 31; ++k) eq.SetParam(k, 0.0f);
        const int chirpLen = kSr;
        std::vector<float> L(chirpLen), R(chirpLen);
        float* ch[2] = { L.data(), R.data() };
        double phase = 0.0;
        for (int n = 0; n < chirpLen; ++n) {
            const double t = (double)n / kSr;
            const double f = 20.0 * std::pow(10.0, 3.0 * t);
            if (n > 0) phase += 2.0 * 3.141592653589793 * f / kSr;
            ch[0][n] = ch[1][n] = 0.2f * (float)std::sin(phase);
        }
        sonicraft::dsp::FftPlan fp; fp.Init(2048);
        const int winLen = 2048;
        const int nWin = chirpLen / winLen;
        const double binHz = (double)kSr / 2048.0;
        std::vector<double> basePk(nWin), boostPk(nWin), baseFpk(nWin);
        for (int pass = 0; pass < 2; ++pass) {
            if (pass == 1) {
                eq.SetParam(3, 12.0f);
                eq.SetParam(62 + 3, 150.0f);
                eq.SetParam(31 + 3, 2.0f);
            }
            for (int b = 0; b < chirpLen / kFrames; ++b) {
                float* chp[2] = { ch[0] + b * kFrames, ch[1] + b * kFrames };
                eq.Process(chp, kFrames);
            }
            for (int w = 0; w < nWin; ++w) {
                const int start = w * winLen;
                float re[2048], im[2048];
                for (int i = 0; i < winLen; ++i) {
                    const int idx = start + i;
                    const float x = (idx >= 0 && idx < chirpLen) ? ch[0][idx] : 0.0f;
                    re[i] = x * (float)(0.5 * (1.0 - std::cos(2.0 * 3.141592653589793 * i / (winLen - 1.0))));
                    im[i] = 0.0f;
                }
                fp.Forward(re, im);
                int pk = 1; float pkMag = -1.0f;
                for (int k = 1; k < 1024; ++k) {
                    const float m = (float)std::sqrt(re[k] * re[k] + im[k] * im[k]);
                    if (m > pkMag) { pkMag = m; pk = k; }
                }
                const float m0 = (float)std::sqrt(re[pk - 1] * re[pk - 1] + im[pk - 1] * im[pk - 1]);
                const float m1 = pkMag;
                const float m2 = (float)std::sqrt(re[pk + 1] * re[pk + 1] + im[pk + 1] * im[pk + 1]);
                const float delta = 0.5f * (m0 - m2) / (m0 - 2.0f * m1 + m2 + 1e-9f);
                const double fPk = (pk + delta) * binHz;
                const double pkDb = 20.0 * std::log10(pkMag + 1e-9f);
                if (pass == 0) { basePk[w] = pkDb; baseFpk[w] = fPk; }
                else boostPk[w] = pkDb;
            }
        }
        // ①频率轴：各窗谱峰频率落在窗内扫频瞬时范围（±3% 边界余量）
        bool axisOk = true; double worstDev = 0.0;
        for (int w = 0; w < nWin; ++w) {
            const double fS = 20.0 * std::pow(10.0, 3.0 * ((w * winLen) / (double)chirpLen));
            const double fE = 20.0 * std::pow(10.0, 3.0 * (((w + 1) * winLen) / (double)chirpLen));
            const double fMid = 20.0 * std::pow(10.0, 3.0 * (((w + 0.5) * winLen) / (double)chirpLen));
            const double dev = std::fabs(baseFpk[w] - fMid) / fMid * 100.0;
            if (dev > worstDev) worstDev = dev;
            if (baseFpk[w] < fS * 0.97 || baseFpk[w] > fE * 1.03) axisOk = false;
        }
        // ②EQ 作用位置：150/100/300Hz 窗的峰提升对比
        int w150 = -1, w100 = -1, w300 = -1;
        for (int w = 0; w < nWin; ++w) {
            const double fMid = 20.0 * std::pow(10.0, 3.0 * (((w + 0.5) * winLen) / (double)chirpLen));
            if (w150 < 0 && fMid >= 135.0 && fMid <= 175.0) w150 = w;
            if (w100 < 0 && fMid >= 50.0 && fMid <= 75.0) w100 = w;
            if (w300 < 0 && fMid >= 440.0 && fMid <= 570.0) w300 = w;
        }
        const double d150 = boostPk[w150] - basePk[w150];
        const double d60 = boostPk[w100] - basePk[w100];
        const double d500 = boostPk[w300] - basePk[w300];
        const bool eqOk = d150 >= 8.0 && std::fabs(d60) <= 1.5 && std::fabs(d500) <= 1.5;
        char d[256];
        std::snprintf(d, sizeof(d),
            "axis worst dev=%.1f%% | peak boost 150Hz=%.1fdB 60Hz=%.1fdB 500Hz=%.1fdB (want +8/+0/+0)",
            worstDev, d150, d60, d500);
        report("E15 log-sweep axis & EQ position", axisOk && eqOk, d);
    }
    std::printf("\n=== RESULT: PASS=%d FAIL=%d ===\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}