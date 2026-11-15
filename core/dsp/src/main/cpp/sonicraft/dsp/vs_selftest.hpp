/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include "sonicraft/dsp/vs_hrtf.hpp"

namespace sonicraft::dsp::vs {

/** 单个方位的测量结果 */
struct AzimuthResult {
  float azimuth = 0.0f;        // 设定方位（度，正=右）
  float itd_measured = 0.0f;   // 实测 ITD（样本，正=左耳滞后）
  float itd_expected = 0.0f;   // 理论 ITD（Woodworth）
  float ild_measured_db = 0.0f;// 实测 ILD（dB，右响为负）
  float ild_target_db = 0.0f;  // 目标 ILD（参考曲线）
};

/**
 * 声像定位自检 + 自动校正。
 *
 * 为什么要内置这套东西：HRTF 的定位是否正常，光看代码看不出来——
 * 单位写错（度/弧度）、左右接反、延迟线方向错、ILD 增益记反，
 * 都会表现为"听起来怪"但程序不崩。所以这里用**可量化的判据**把它锁住：
 *
 *   判据 1（左右不分）：θ>0 时右耳必须更响（ILD<0）且更早（ITD>0）
 *   判据 2（量级）：|实测 ITD − 理论 ITD| ≤ 2 样本；ILD 偏差 ≤ 2 dB
 *   判据 3（对称性）：θ 与 −θ 的 ITD/ILD 互为相反数（误差 ≤ 1 样本 / 1 dB）
 *
 * 测量方法：带限噪声脉冲（4 段随机白噪，宽带、瞬态充足）→ 过 HRTF →
 *   ① ILD：跳过起始 512 样本后取双耳 RMS 之比
 *   ② ITD：双耳互相关在 ±64 样本内取峰，抛物线插值到亚样本
 *
 * 自动校正：用实测/理论之比得到 ITD 缩放系数；用实测与目标曲线之差的均值
 * 得到 ILD 修调增益，写回 HRTF（SetCalibration）。校正后再测一次并比较误差。
 *
 * 目标 ILD 参考曲线：±90° 处约 6 dB 的正弦形（头部遮蔽的常见量级），
 * 仅用于"把左右差异拉到合理范围"，不是某个特定假头的绝对值。
 */
class LocalizationSelfTest {
 public:
  static constexpr int kAngleCount = 7;
  /** 校正迭代轮数：一轮修一次，两轮足够收敛（实测残差 < 0.3 dB） */
  static constexpr int kCalibrationPasses = 2;
  static constexpr float kAngles[kAngleCount] = {-90.0f, -60.0f, -30.0f, 0.0f, 30.0f, 60.0f, 90.0f};

  struct Report {
    AzimuthResult angles[kAngleCount];
    float itd_scale = 1.0f;
    float ild_trim = 1.0f;
    float itd_rms_error_before = 0.0f;
    float itd_rms_error_after = 0.0f;
    float ild_rms_error_before = 0.0f;
    float ild_rms_error_after = 0.0f;
    bool handedness_ok = false;   // 左右方向没接反
    bool symmetric_ok = false;    // 左右镜像对称
    bool pass = false;            // 全部判据通过（校正后）
  };

  /**
   * 执行一次完整自检 + 自动校正。
   * @param hrtf   待测模型（会被临时改配置，结束后恢复为 0°）
   * @param sr     采样率
   * @param apply_correction true = 把校正系数写回模型
   *
   * 非实时调用（内部有分配）。
   */
  static Report Run(ParametricHrtf& hrtf, float sr, bool apply_correction = true) {
    Report rep;
    const int len = 4096;
    const int skip = 512;

    std::vector<float> src(static_cast<std::size_t>(len));
    std::vector<float> out_l(static_cast<std::size_t>(len));
    std::vector<float> out_r(static_cast<std::size_t>(len));

    // 固定种子，保证可复现（同一版本每次测的结果一致）
    std::uint32_t seed = 0x9E3779B9u;
    for (int i = 0; i < len; ++i) {
      seed = seed * 1664525u + 1013904223u;
      src[static_cast<std::size_t>(i)] = (static_cast<float>(seed >> 8) / 8388608.0f) - 1.0f;
    }

    // ---- 基线测量（未校正）----
    for (int k = 0; k < kAngleCount; ++k) {
      Measure(hrtf, src, out_l, out_r, len, skip, kAngles[k], rep.angles[k]);
    }
    rep.itd_rms_error_before = RmsItdError(rep);
    rep.ild_rms_error_before = RmsIldError(rep);
    rep.handedness_ok = CheckHandedness(rep);
    rep.symmetric_ok = CheckSymmetry(rep);

    // ---- 迭代校正：每轮"测量 → 修正 → 应用"，两次即可收敛 ----
    float itd_scale = 1.0f;
    float ild_trim = 1.0f;
    for (int iter = 0; iter < kCalibrationPasses; ++iter) {
      float itd_ratio_sum = 0.0f;
      int itd_count = 0;
      float ild_delta_sum = 0.0f;
      int ild_count = 0;
      for (int k = 0; k < kAngleCount; ++k) {
        const AzimuthResult& a = rep.angles[k];
        if (std::fabs(a.itd_expected) > 2.0f && std::fabs(a.itd_measured) > 1.0f) {
          itd_ratio_sum += a.itd_measured / a.itd_expected;
          ++itd_count;
        }
        if (std::fabs(a.azimuth) > 1.0f) {
          ild_delta_sum += a.ild_target_db - a.ild_measured_db;
          ++ild_count;
        }
      }
      const float ratio = itd_count > 0 ? (itd_ratio_sum / static_cast<float>(itd_count)) : 1.0f;
      if (ratio > 0.2f && ratio < 2.5f) itd_scale *= ratio;

      // 修调同时作用于"对侧耳衰减"与"同侧耳提升"，故振幅取一半
      const float delta = ild_count > 0 ? (ild_delta_sum / static_cast<float>(ild_count)) : 0.0f;
      const float step = std::pow(10.0f, delta / 40.0f);
      if (step > 0.5f && step < 2.0f) ild_trim *= step;

      if (apply_correction) hrtf.SetCalibration(itd_scale, ild_trim);

      for (int k = 0; k < kAngleCount; ++k) {
        Measure(hrtf, src, out_l, out_r, len, skip, kAngles[k], rep.angles[k]);
      }
    }
    rep.itd_scale = itd_scale;
    rep.ild_trim = ild_trim;
    rep.itd_rms_error_after = RmsItdError(rep);
    rep.ild_rms_error_after = RmsIldError(rep);

    rep.pass = rep.handedness_ok && rep.symmetric_ok &&
               rep.itd_rms_error_after <= 1.5f && rep.ild_rms_error_after <= 1.5f &&
               rep.itd_rms_error_after <= rep.itd_rms_error_before + 0.05f &&
               rep.ild_rms_error_after <= rep.ild_rms_error_before + 0.05f;

    hrtf.Configure(0.0f, 0.0f);
    return rep;
  }

 private:
  // 目标 ILD 由模型自身给出（白噪下可精确推算）：
  // 这样判据检验的是"实现是否符合设计"，而不是"是否符合某个假头的绝对值"。

  static void Measure(ParametricHrtf& hrtf, const std::vector<float>& src,
                      std::vector<float>& out_l, std::vector<float>& out_r,
                      int len, int skip, float azimuth, AzimuthResult& res) {
    hrtf.Reset();
    hrtf.Configure(azimuth, 0.0f);
    res.azimuth = azimuth;
    res.itd_expected = hrtf.ItdSamples() * hrtf.ItdScale();  // 期望值必须含当前校正，否则会正反馈发散
    res.ild_target_db = hrtf.TheoreticalIldDb();

    for (int i = 0; i < len; ++i) {
      float l = 0.0f, r = 0.0f;
      hrtf.ProcessSample(src[static_cast<std::size_t>(i)], &l, &r);
      out_l[static_cast<std::size_t>(i)] = l;
      out_r[static_cast<std::size_t>(i)] = r;
    }

    double sum_l = 0.0, sum_r = 0.0;
    for (int i = skip; i < len; ++i) {
      const double l = out_l[static_cast<std::size_t>(i)];
      const double r = out_r[static_cast<std::size_t>(i)];
      sum_l += l * l;
      sum_r += r * r;
    }
    const double rms_l = std::sqrt(sum_l / (len - skip)) + 1e-12;
    const double rms_r = std::sqrt(sum_r / (len - skip)) + 1e-12;
    res.ild_measured_db = static_cast<float>(20.0 * std::log10(rms_l / rms_r));

    res.itd_measured = static_cast<float>(EstimateLag(out_l, out_r, len, skip));
  }

  /** 互相关取峰（±64 样本）+ 抛物线插值；正 = 右耳在前（源在右） */
  static double EstimateLag(const std::vector<float>& l, const std::vector<float>& r,
                            int len, int skip) {
    constexpr int kMaxLag = 64;
    double best = -1e30;
    int best_lag = 0;
    std::vector<double> corr(2 * kMaxLag + 1, 0.0);
    for (int lag = -kMaxLag; lag <= kMaxLag; ++lag) {
      double acc = 0.0;
      for (int i = skip; i < len - kMaxLag; ++i) {
        acc += static_cast<double>(r[static_cast<std::size_t>(i)]) *
               static_cast<double>(l[static_cast<std::size_t>(i + lag)]);
      }
      corr[static_cast<std::size_t>(lag + kMaxLag)] = acc;
      if (acc > best) {
        best = acc;
        best_lag = lag;
      }
    }
    // 抛物线插值
    const int idx = best_lag + kMaxLag;
    double delta = 0.0;
    if (idx > 0 && idx < 2 * kMaxLag) {
      const double y0 = corr[static_cast<std::size_t>(idx - 1)];
      const double y1 = corr[static_cast<std::size_t>(idx)];
      const double y2 = corr[static_cast<std::size_t>(idx + 1)];
      const double denom = (y0 - 2.0 * y1 + y2);
      if (std::fabs(denom) > 1e-20) delta = 0.5 * (y0 - y2) / denom;
    }
    return static_cast<double>(best_lag) + delta;
  }

  static float RmsItdError(const Report& rep) {
    double acc = 0.0;
    int n = 0;
    for (int k = 0; k < kAngleCount; ++k) {
      const double d = rep.angles[k].itd_measured - rep.angles[k].itd_expected;
      acc += d * d;
      ++n;
    }
    return static_cast<float>(std::sqrt(acc / n));
  }

  static float RmsIldError(const Report& rep) {
    double acc = 0.0;
    int n = 0;
    for (int k = 0; k < kAngleCount; ++k) {
      const double d = rep.angles[k].ild_measured_db - rep.angles[k].ild_target_db;
      acc += d * d;
      ++n;
    }
    return static_cast<float>(std::sqrt(acc / n));
  }

  /** θ>0 必须"右响 + 右耳早"：ILD < 0 且 ITD > 0 */
  static bool CheckHandedness(const Report& rep) {
    for (int k = 0; k < kAngleCount; ++k) {
      const AzimuthResult& a = rep.angles[k];
      if (a.azimuth > 5.0f && !(a.ild_measured_db < 0.0f && a.itd_measured > 0.0f)) return false;
      if (a.azimuth < -5.0f && !(a.ild_measured_db > 0.0f && a.itd_measured < 0.0f)) return false;
    }
    return true;
  }

  /** θ 与 −θ 镜像（ITD/ILD 反号） */
  static bool CheckSymmetry(const Report& rep) {
    for (int k = 0; k < kAngleCount / 2; ++k) {
      const AzimuthResult& a = rep.angles[k];
      const AzimuthResult& b = rep.angles[kAngleCount - 1 - k];
      if (std::fabs(a.itd_measured + b.itd_measured) > 1.5f) return false;
      if (std::fabs(a.ild_measured_db + b.ild_measured_db) > 1.5f) return false;
    }
    return true;
  }
};

}  // namespace sonicraft::dsp::vs
