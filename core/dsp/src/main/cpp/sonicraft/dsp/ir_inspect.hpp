/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>
#include <vector>

#include "sonicraft/dsp/fft.hpp"
#include "sonicraft/dsp/wav_loader.hpp"

namespace sonicraft::dsp {

/**
 * IR 病态检测。
 *
 * 背景：用户加载 HIFI最终版2.irs 后全链五款软件（早期定制版/ViPERDSP/RV4A/RDSP/
 * 本项目）复听均现「干声 + 迟到湿声」双声——根因是该 IR 形态病态：0~160ms
 * 深静音（-84~-78dB）+ 161.4ms 孤立主脉冲（峰值 3.13）。卷积后湿声整体迟到
 * 120~161ms，远超 Haas 融合区（~30ms），人耳听成两个分离声音事件。同类社区
 * IR 常见病态还有 NaN/Inf 脏样本（wav_loader 清洗点统计）、左右不对称、削波。
 *
 * 本检测只读不改装载行为（自动前置裁剪属 DSP 行为变更，单独成批）。
 * 判据口径与离线取证原型（tmp_dualvoice_test/lr_check.py，已真机复听闭环）
 * 一致：
 *  - 主脉冲 = 2ms RMS 包络 argmax（引擎域，重采样后、尾部裁剪后的实际内容）
 *  - 前置静音 = 主脉冲前首个「2ms 窗联合 RMS > 峰值 -60dB」的窗起点
 *    （段级能量门要压过 -76dB 级的抬升段：RV4A/RDSP 的 -80dB 首尾裁剪只砍掉
 *    41ms 深静音、双声仍剩 120ms 的教训；且 SRS_2_2 实证：深静音
 *    残留段里的孤立毛刺样本会顶穿逐样本瞬时门，把 186ms 的主体迟到误判成
 *    1.1ms 前置而不裁——窗 RMS 把单样本摊薄 ~10·log10(win) dB，见
 *    HeadSilenceFrames 注释）
 *  - 双声风险：前置静音 >100ms 严重 / >30ms 可闻 / 其余正常（Haas 区）
 *  - 前置噪声 pre_noise_db：pre 段峰值相对全局峰。≤-96dB（16bit 底噪下）
 *    判「干净数字零」（设计型预延迟/延迟混响形态；不再豁免
 *    裁剪——病态一律校正）；-96~-60dB 判「导出残留」——脏前置会被卷积
 *    垫进湿声背景（HIFI最终版2 的 -84~-78dB 抬升段即此形态）
 *  - 频谱 = Welch 平均周期图（1024 点 Hann 50% 重叠，段去均值抑制直流），
 *    128 点输出（每 4 bin 取 max 保窄带峰），峰归一 0dB、底 -100dB
 */
struct IrInspectReport {
  static constexpr int kSpecPoints = 128;
  int source_rate = 0;            // 源采样率 Hz（UI 谱 x 轴换算）
  float pre_silence_ms = 0.0f;    // 前置静音长度（双声根因指标）
  float pre_noise_db = -100.0f;   // pre 段干净度（≤-96 设计型延迟可忽略提示 / -96~-60 导出残留）
  float trimmed_ms = 0.0f;        // 装载时自动裁除的前置 ms（0=未裁；UI「已自动对齐」横幅）
  float main_pulse_ms = 0.0f;     // 主脉冲位置（引擎域）
  float lr_energy_ratio_db = 0.0f;  // 10·log10(EL/ER)；1 声道 = 0
  float lr_pulse_skew_ms = 0.0f;    // L/R 主脉冲错位；1 声道 = 0
  float peak = 0.0f;              // 源域全通道峰值（线性）
  float clip_ratio = 0.0f;        // |x| ≥ 0.999 样本占比（削波）
  int nan_count = 0;              // 源文件 NaN 样本数（wav_loader 清洗点统计）
  int inf_count = 0;              // 源文件 Inf 样本数（同上）
  int risk_level = 0;             // 0 正常 / 1 可闻双声 / 2 严重双声
  float suggested_wet_gain = 1.0f;  // 湿声电平自动校准建议（批次 2；0.6~1.5）
  float crest_factor = 1.0f;      // 波峰因数 E_total/peak²（校准输入诊断；裁后对角）
  int spectrum_channels = 0;      // 谱声道数 = min(源通道, 4)
  float spectrum[4][kSpecPoints] = {};
};

/**
 * 轻量前置静音检测（装载路径的自动对齐裁剪判定，O(n)，不跑频谱）。
 * 返回首个「2ms 窗联合 RMS > 峰值 -60dB」的窗起点；窗长 = rate/500 与主脉冲
 * 包络同窗，联合口径一致（amp = sqrt(L²+R²)，R 缺失视为 0）。
 * 段级（窗 RMS）而非逐样本（瞬时幅度）判定，SRS_2_2 实证教训：
 * 深静音残留段（RMS -64.7dB）中的孤立毛刺样本（瞬时 -59dB）会顶穿逐样本
 * 瞬时门，把 186ms 的湿声主体迟到误判成 1.1ms 前置 → 不裁 → 真机干湿分离。
 * 窗 RMS 把单样本毛刺摊薄 ~10·log10(win) dB（96 样本窗 ≈ -20dB），只有
 * 段级能量才能越门；连续信号的窗 RMS ≈ 自身 RMS，不损失正常检测灵敏度。
 * pre_noise_db 输出 pre 段峰值相对全局峰的 dB（≤-96 = 纯数字零设计型延迟；
 * -96~-60 = 导出残留）。
 */
inline int HeadSilenceFrames(const std::vector<float>* diag_l,
                             const std::vector<float>* diag_r,
                             int rate, float* pre_noise_db) {
  if (pre_noise_db != nullptr) *pre_noise_db = -100.0f;
  if (diag_l == nullptr || diag_l->empty() || rate <= 0) return 0;
  const int n = static_cast<int>(diag_l->size());
  const int win = rate / 500 > 1 ? rate / 500 : 1;  // 2ms（与主脉冲包络同窗）
  auto amp_at = [](const std::vector<float>* l, const std::vector<float>* r, int i) -> double {
    const double lv = l != nullptr && i < static_cast<int>(l->size())
        ? static_cast<double>((*l)[static_cast<std::size_t>(i)]) : 0.0;
    const double rv = r != nullptr && i < static_cast<int>(r->size())
        ? static_cast<double>((*r)[static_cast<std::size_t>(i)]) : 0.0;
    return std::sqrt(lv * lv + rv * rv);
  };
  double pk = 0.0;
  for (int i = 0; i < n; ++i) {
    const double a = amp_at(diag_l, diag_r, i);
    if (a > pk) pk = a;
  }
  if (pk <= 1e-12) return 0;  // 全静音：无从谈起前置
  const double th = pk * 1e-3;   // -60 dB（与 InspectIr 同门）
  const double th2 = th * th;    // 窗均方比较门
  // 滑动平方和（O(n)）：首个窗均方 > 门限的窗起点
  std::vector<double> cs(static_cast<std::size_t>(n) + 1, 0.0);
  for (int i = 0; i < n; ++i) {
    const double a = amp_at(diag_l, diag_r, i);
    cs[static_cast<std::size_t>(i) + 1] = cs[static_cast<std::size_t>(i)] + a * a;
  }
  int s = 0;
  for (int i = 0; i < n; ++i) {
    const int hi = i + win < n ? i + win : n;
    if (hi <= i) break;
    const double mean_sq = (cs[static_cast<std::size_t>(hi)] - cs[static_cast<std::size_t>(i)]) /
        static_cast<double>(hi - i);
    if (mean_sq > th2) { s = i; break; }
  }
  if (pre_noise_db != nullptr) {
    double pre_pk = 0.0;
    for (int i = 0; i < s; ++i) {
      const double a = amp_at(diag_l, diag_r, i);
      if (a > pre_pk) pre_pk = a;
    }
    *pre_noise_db = pre_pk > 0.0
        ? static_cast<float>(20.0 * std::log10(pre_pk / pk)) : -100.0f;
  }
  return s;
}

/**
 * 波峰因数（湿声电平自动校准口径，批次 2，O(n)）：
 * 返回 E_total / peak²（对角路径联合 amp = sqrt(L²+R²)，与 HeadSilenceFrames
 * 同口径；L=R 时退化为 Σx²/max(x²)）。脉冲型 IR（HRTF/舞台，单 tap 主导）
 * →≈1；扩散长尾 → 大（数量级 10~10³）；实测房间 RIR 居中。
 * 首版曾用 E(0~20ms)/E_total 头部占比——实测大房间 RIR 天然长尾
 * （diffuse≈0.90）与合成扩散 IR 无区分度，弃用（7z b 实证）。
 * 全静音/空输入返回 1.0（建议值中性）。
 */
inline double CrestFactor(const std::vector<float>* diag_l,
                          const std::vector<float>* diag_r) {
  if (diag_l == nullptr || diag_l->empty()) return 1.0;
  const int n = static_cast<int>(diag_l->size());
  double e_total = 0.0, pk = 0.0;
  for (int i = 0; i < n; ++i) {
    const double lv = static_cast<double>((*diag_l)[static_cast<std::size_t>(i)]);
    const double rv = diag_r != nullptr && i < static_cast<int>(diag_r->size())
        ? static_cast<double>((*diag_r)[static_cast<std::size_t>(i)]) : 0.0;
    const double a2 = lv * lv + rv * rv;
    e_total += a2;
    if (a2 > pk) pk = a2;
  }
  if (pk <= 1e-24) return 1.0;
  return e_total / pk;
}

inline void InspectIr(const WavData& wav,
                      const std::vector<float>* diag_l,
                      const std::vector<float>* diag_r,
                      int eng_rate, IrInspectReport* out) {
  if (out == nullptr) return;
  *out = IrInspectReport{};
  out->source_rate = wav.sample_rate;
  out->nan_count = wav.nan_count;
  out->inf_count = wav.inf_count;

  // ---- 源域：峰值 / 削波（NaN/Inf 计数由 wav_loader 清洗点带出） ----
  {
    long long total = 0, clipped = 0;
    double pk = 0.0;
    for (int c = 0; c < wav.channel_count && c < 4; ++c) {
      for (float v : wav.ch[c]) {
        const double a = v < 0 ? -static_cast<double>(v) : static_cast<double>(v);
        if (a > pk) pk = a;
        if (a >= 0.999) ++clipped;
        ++total;
      }
    }
    out->peak = static_cast<float>(pk);
    out->clip_ratio = total > 0 ? static_cast<float>(static_cast<double>(clipped) / static_cast<double>(total)) : 0.0f;
  }

  // ---- 引擎域：主脉冲 / 前置静音 / 左右对称性 ----
  // diag_l/diag_r = 对角路径（LL/RR；1ch 时 R 为 nullptr）。联合口径与
  // 取证原型一致：幅度 = sqrt(L²+R²)，包络 = 2ms 中心窗 RMS。
  // 前置静音/pre_noise 直接调 HeadSilenceFrames（口径单一化：
  // 2ms 窗 RMS 段级判定，与裁剪路径同实现，报告卡与实际裁剪量永不漂移）。
  const int win = eng_rate / 500 > 1 ? eng_rate / 500 : 1;  // 2ms
  auto env_argmax = [&win](const std::vector<float>& x) -> int {
    const int n = static_cast<int>(x.size());
    if (n <= 0) return 0;
    // 滑动平方和（O(n)），中心窗 [i-w/2, i+w/2)
    std::vector<double> cs(static_cast<std::size_t>(n) + 1, 0.0);
    for (int i = 0; i < n; ++i) {
      cs[static_cast<std::size_t>(i) + 1] = cs[static_cast<std::size_t>(i)] +
          static_cast<double>(x[static_cast<std::size_t>(i)]) * static_cast<double>(x[static_cast<std::size_t>(i)]);
    }
    int best = 0;
    double best_v = -1.0;
    for (int i = 0; i < n; ++i) {
      int lo = i - win / 2, hi = i + win - win / 2;
      if (lo < 0) lo = 0;
      if (hi > n) hi = n;
      if (hi <= lo) continue;
      const double v = (cs[static_cast<std::size_t>(hi)] - cs[static_cast<std::size_t>(lo)]) / static_cast<double>(hi - lo);
      if (v > best_v) { best_v = v; best = i; }
    }
    return best;
  };
  if (diag_l != nullptr && !diag_l->empty()) {
    const int n = static_cast<int>(diag_l->size());
    float pre_db = -100.0f;
    const int s = HeadSilenceFrames(diag_l, diag_r, eng_rate, &pre_db);
    out->pre_silence_ms = static_cast<float>(s) * 1000.0f / static_cast<float>(eng_rate);
    out->pre_noise_db = pre_db;
    out->main_pulse_ms = static_cast<float>(env_argmax(*diag_l)) * 1000.0f / static_cast<float>(eng_rate);
    // 双声风险分级（Haas 融合区 ~30ms；>100ms 人耳明确分离）
    out->risk_level = out->pre_silence_ms > 100.0f ? 2 : (out->pre_silence_ms > 30.0f ? 1 : 0);
    // L/R 对称性（R 缺失 = 1ch，跳过）
    if (diag_r != nullptr && !diag_r->empty()) {
      const double el = env_argmax(*diag_l), er = env_argmax(*diag_r);
      out->lr_pulse_skew_ms = static_cast<float>(er - el) * 1000.0f / static_cast<float>(eng_rate);
      double sl = 0.0, sr = 0.0;
      for (int i = 0; i < n; ++i) {
        const double lv = static_cast<double>((*diag_l)[static_cast<std::size_t>(i)]);
        sl += lv * lv;
      }
      for (float v : *diag_r) {
        const double rv = static_cast<double>(v);
        sr += rv * rv;
      }
      out->lr_energy_ratio_db = sl > 1e-18 && sr > 1e-18
          ? static_cast<float>(10.0 * std::log10(sr / sl)) : 0.0f;
    }
  }

  // ---- 每声道频谱：Welch 平均周期图（段去均值 + Hann + 50% 重叠） ----
  {
    const int nch = wav.channel_count < 4 ? wav.channel_count : 4;
    out->spectrum_channels = nch;
    if (nch > 0) {
      constexpr int kSeg = 1024, kHop = kSeg / 2, kBins = kSeg / 2 + 1;
      static_assert(IrInspectReport::kSpecPoints * 4 == kSeg / 2,
                    "128 点输出须恰好覆盖 512 个单边 bin");
      FftPlan fft;
      fft.Init(kSeg);
      float hann[kSeg];
      for (int i = 0; i < kSeg; ++i) {
        hann[i] = 0.5f - 0.5f * std::cos(2.0f * 3.14159265358979323846f *
                                         static_cast<float>(i) / static_cast<float>(kSeg - 1));
      }
      for (int c = 0; c < nch; ++c) {
        const std::vector<float>& x = wav.ch[c];
        const int n = static_cast<int>(x.size());
        double acc[kBins] = {};
        int segs = 0;
        for (int start = 0; start < n; start += kHop) {
          // 段去均值（IR 直流偏置会让 bin0 爆表压扁全谱）
          double mean = 0.0;
          for (int i = 0; i < kSeg; ++i) {
            mean += start + i < n ? static_cast<double>(x[static_cast<std::size_t>(start + i)]) : 0.0;
          }
          mean /= static_cast<double>(kSeg);
          float re[kSeg], im[kSeg];
          for (int i = 0; i < kSeg; ++i) {
            const double v = start + i < n ? static_cast<double>(x[static_cast<std::size_t>(start + i)]) - mean : 0.0;
            re[i] = static_cast<float>(v) * hann[i];
            im[i] = 0.0f;
          }
          fft.Forward(re, im);
          for (int k = 0; k < kBins; ++k) {
            acc[k] += static_cast<double>(re[k]) * re[k] + static_cast<double>(im[k]) * im[k];
          }
          ++segs;
        }
        if (segs == 0) break;
        // 128 点输出：每 4 bin 取 max（保窄带峰），峰归一 0dB，底 -100dB
        double pt[IrInspectReport::kSpecPoints], ref = 0.0;
        for (int i = 0; i < IrInspectReport::kSpecPoints; ++i) {
          double m = 0.0;
          for (int b = 0; b < 4; ++b) {
            const double v = acc[i * 4 + b] / static_cast<double>(segs);
            if (v > m) m = v;
          }
          pt[i] = m;
          if (m > ref) ref = m;
        }
        for (int i = 0; i < IrInspectReport::kSpecPoints; ++i) {
          const double db = pt[i] > 0.0 && ref > 0.0 ? 10.0 * std::log10(pt[i] / ref) : -100.0;
          out->spectrum[c][i] = static_cast<float>(db < -100.0 ? -100.0 : (db > 0.0 ? 0.0 : db));
        }
      }
    }
  }
}

}  // namespace sonicraft::dsp
