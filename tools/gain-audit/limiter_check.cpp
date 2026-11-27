/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 分段限幅器的离线验证：
 *   ① 分频求和是否平坦（不触发限幅时各频点增益应 ≈0 dB）
 *   ② 大信号下输出峰值是否真的不超过 ceiling
 *   ③ 低频大峰时高频是否被"抽吸"（分段限幅的核心收益）
 */
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "sonicraft/dsp/limiter.hpp"

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kRate = 48000;
constexpr int kBlock = 192;

struct Res {
  double in_rms;
  double out_rms;
  double out_peak;
};

Res Run(sonicraft::dsp::LimiterNode& node, double freq, float amp, int blocks) {
  std::vector<float> l(kBlock), r(kBlock);
  double sum_in = 0.0, sum_out = 0.0, peak = 0.0;
  double phase = 0.0;
  const double w = 2.0 * kPi * freq / kRate;
  int counted = 0;
  for (int b = 0; b < blocks; ++b) {
    for (int i = 0; i < kBlock; ++i) {
      const float v = amp * static_cast<float>(std::sin(phase));
      phase += w;
      l[i] = v;
      r[i] = v;
    }
    float* ch[2] = {l.data(), r.data()};
    node.Process(ch, kBlock);
    if (b > 20) {
      for (int i = 0; i < kBlock; ++i) {
        sum_in += static_cast<double>(amp) * amp * 0.5;
        sum_out += static_cast<double>(ch[0][i]) * ch[0][i];
        peak = std::max(peak, static_cast<double>(std::fabs(ch[0][i])));
      }
      counted += kBlock;
    }
  }
  return {std::sqrt(sum_in / counted), std::sqrt(sum_out / counted), peak};
}

double GainDb(const Res& r) { return 20.0 * std::log10((r.out_rms + 1e-12) / (r.in_rms + 1e-12)); }
double Db(double lin) { return 20.0 * std::log10(lin + 1e-12); }

sonicraft::dsp::LimiterNode MakeNode(float ceiling_db) {
  sonicraft::dsp::LimiterNode n;
  n.set_channel_count(2);
  n.Prepare(kRate, kBlock);
  n.SetBypass(false);
  n.SetParam(sonicraft::dsp::LimiterNode::kParamCeilingDb, ceiling_db);
  n.SetParam(sonicraft::dsp::LimiterNode::kParamTruePeak, 0.0f);
  return n;
}

/** 用 Goertzel 提取单一频点的峰值幅度 */
double GoertzelPeak(sonicraft::dsp::LimiterNode& n, double low_freq, float low_amp,
                    double probe_freq, float probe_amp, int blocks) {
  std::vector<float> l(kBlock), r(kBlock);
  double p1 = 0.0, p2 = 0.0;
  const double w_probe = 2.0 * kPi * probe_freq / kRate;
  const double coeff = 2.0 * std::cos(w_probe);
  double s1 = 0.0, s2 = 0.0;
  long counted = 0;
  for (int b = 0; b < blocks; ++b) {
    for (int i = 0; i < kBlock; ++i) {
      const float lo = low_amp * static_cast<float>(std::sin(p1));
      const float hi = probe_amp * static_cast<float>(std::sin(p2));
      p1 += 2.0 * kPi * low_freq / kRate;
      p2 += w_probe;
      l[i] = lo + hi;
      r[i] = lo + hi;
    }
    float* ch[2] = {l.data(), r.data()};
    n.Process(ch, kBlock);
    if (b > 80) {
      for (int i = 0; i < kBlock; ++i) {
        const double v = ch[0][i];
        const double s0 = v + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
        ++counted;
      }
    }
  }
  return std::sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / (counted * 0.5);
}

}  // namespace

int main() {
  std::printf("=== 1) 分频平坦度（小信号，不触发限幅；应全部 ≈ 0 dB）===\n");
  const double freqs[] = {50, 100, 150, 300, 600, 1200, 3000, 8000};
  for (double f : freqs) {
    auto n = MakeNode(0.0f);
    const Res r = Run(n, f, 0.02f, 120);
    std::printf("  %6.0f Hz   gain=%+6.2f dB\n", f, GainDb(r));
  }

  std::printf("\n=== 2) 限幅保底（ceiling = -1 dBFS）===\n");
  {
    auto n = MakeNode(-1.0f);
    const Res r = Run(n, 200.0, 4.0f, 200);
    std::printf("  输入 +12 dBFS @200Hz -> 输出峰值 %+6.2f dBFS（必须 <= -1.00）\n", Db(r.out_peak));
  }
  {
    auto n = MakeNode(-1.0f);
    const Res r = Run(n, 80.0, 4.0f, 200);
    std::printf("  输入 +12 dBFS @80Hz  -> 输出峰值 %+6.2f dBFS（必须 <= -1.00）\n", Db(r.out_peak));
  }

  std::printf("\n=== 3) 低频大峰对高频的影响（分段的收益）===\n");
  {
    auto n = MakeNode(-1.0f);
    const double mag = GoertzelPeak(n, 80.0, 1.2f, 4000.0, 0.05f, 260);
    std::printf("  4 kHz 峰值：输入 0.0500 -> 输出 %.4f（%+.2f dB；越接近 0 = 高频越没被低频压掉）\n",
                mag, 20.0 * std::log10((mag + 1e-12) / 0.05));
  }

  std::printf("\n=== 4) +9 dB 的响度与削顶（粉噪 −20 dBFS RMS）===\n");
  {
    // 简易粉噪：一阶低通叠加白噪，频谱重心接近流行乐
    auto make_src = [](double rms_db, double gain_db, std::vector<float>* out) {
      const double gain = std::pow(10.0, gain_db / 20.0);
      const int total = 48000 * 6;
      out->assign(total, 0.0f);
      uint32_t seed = 0x9E3779B9u;
      double lp = 0.0, sum = 0.0;
      std::vector<double> tmp(total, 0.0);
      for (int i = 0; i < total; ++i) {
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        const double w = static_cast<double>(seed & 0xFFFFFFu) / 8388608.0 - 1.0;
        lp = 0.92 * lp + 0.08 * w;     // 低频偏重的粉噪近似
        tmp[i] = 0.6 * lp + 0.4 * w;
        sum += tmp[i] * tmp[i];
      }
      const double rms = std::sqrt(sum / total);
      const double norm = std::pow(10.0, rms_db / 20.0) / (rms + 1e-12) * gain;
      for (int i = 0; i < total; ++i) (*out)[i] = static_cast<float>(tmp[i] * norm);
    };

    auto run = [&](bool limiter_on, double rms_db, double gain_db) {
      sonicraft::dsp::LimiterNode n;
      n.set_channel_count(2);
      n.Prepare(kRate, kBlock);
      n.SetBypass(!limiter_on);
      n.SetParam(sonicraft::dsp::LimiterNode::kParamCeilingDb, -1.0f);
      n.SetParam(sonicraft::dsp::LimiterNode::kParamGateLowDb, -5.0f);
      n.SetParam(sonicraft::dsp::LimiterNode::kParamGateMidDb, -5.0f);
      n.SetParam(sonicraft::dsp::LimiterNode::kParamGateHighDb, -2.0f);
      n.SetParam(sonicraft::dsp::LimiterNode::kParamDcBlock, 1.0f);
      std::vector<float> src;
      make_src(rms_db, gain_db, &src);
      std::vector<float> l(kBlock), r(kBlock);
      double out_sum = 0.0, peak = 0.0;
      long n_over = 0, cnt = 0;
      size_t pos = 0;
      while (pos + kBlock <= src.size()) {
        for (int i = 0; i < kBlock; ++i) { l[i] = src[pos + i]; r[i] = src[pos + i] * 0.9f; }
        pos += kBlock;
        float* ch[2] = {l.data(), r.data()};
        n.Process(ch, kBlock);
        for (int i = 0; i < kBlock; ++i) {
          out_sum += 0.5 * (ch[0][i] * ch[0][i] + ch[1][i] * ch[1][i]);
          const double a = std::fabs(ch[0][i]);
          if (a > peak) peak = a;
          if (a > 1.0) ++n_over;
        }
        cnt += kBlock;
      }
      struct R2 { double rms; double peak; long over; };
      return R2{std::sqrt(out_sum / cnt), peak, n_over};
    };

    const double levels[2] = {-20.0, -12.0};
    for (double lv : levels) {
      const auto c0 = run(true, lv, 0.0);     // 基准
      const auto b9 = run(true, lv, 9.0);     // +9 dB + 限幅
      const auto a9 = run(false, lv, 9.0);    // +9 dB 不限幅
      std::printf("  [素材 %.0f dBFS RMS]\n", lv);
      std::printf("    0 dB  + 限幅 : RMS %+6.1f  峰值 %+6.2f  削顶 %ld\n",
                  Db(c0.rms), Db(c0.peak), c0.over);
      std::printf("    +9 dB + 限幅 : RMS %+6.1f  峰值 %+6.2f  削顶 %ld\n",
                  Db(b9.rms), Db(b9.peak), b9.over);
      std::printf("    +9 dB 不限幅 : RMS %+6.1f  峰值 %+6.2f  削顶 %ld\n",
                  Db(a9.rms), Db(a9.peak), a9.over);
      std::printf("    => 响度提升 %+.1f dB（限幅吃掉 %.1f dB）\n",
                  Db(b9.rms) - Db(c0.rms), 9.0 - (Db(b9.rms) - Db(c0.rms)));
    }
  }
  return 0;
}
