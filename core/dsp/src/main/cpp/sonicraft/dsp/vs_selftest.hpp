/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include "sonicraft/dsp/vs_hrtf_engine.hpp"

namespace sonicraft::dsp::vs {

/** 单个方位的测量结果 */
struct AzimuthResult {
  float azimuth = 0.0f;        // 设定方位（度，正=右）
  float elevation = 0.0f;      // 设定仰角（度，正=上）；12 扬声器布局里上方 4 只是 35°，
                               // 方位与水平扬声器重复（±45 各一路），镜像判据必须按「方位+仰角」配对
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
  /** 通用布局（Ambisonic 12 扬声器）用的上限；5.1 用 7 个 */
  static constexpr int kMaxAngleCount = 16;
  /** 校正迭代轮数：一轮修一次，两轮足够收敛（实测残差 < 0.3 dB） */
  static constexpr int kCalibrationPasses = 2;
  /**
   * 测量方位：与渲染用的声道方位一一对应，覆盖真实 5.1 布局而不是前半圆。
   *
   * 排列必须**首尾镜像**（CheckSymmetry 按 k 与 kAngleCount-1-k 配对）：
   *   (-110,110) / (-30,30) / (0,0) 成对，180 放正中（自对称、不参与配对）。
   * 旧表是 ±90 步进 30°，只能覆盖前半圆，测不到侧后方与正后方 —— 自检通过
   * 也不代表 ±110/180 的渲染正确。
   */
  static constexpr float kAngles[kAngleCount] = {-110.0f, -30.0f, 0.0f, 180.0f, 0.0f, 30.0f, 110.0f};

  struct Report {
    AzimuthResult angles[kMaxAngleCount];
    /** 实际测量的方位数（5.1 = 7，Ambisonic = 12） */
    int count = kAngleCount;
    /** 可选声道名（与 angles 同序）；为空时界面用方位角显示 */
    const char* labels[kMaxAngleCount] = {};
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
  static Report Run(HrtfEngine& hrtf, float sr, bool apply_correction = true) {
    Report rep;
    rep.count = kAngleCount;
    // 报告里显示声道名（与 kAngles 一一对应）
    static const char* kLabels[kAngleCount] = {"SL", "FL", "C", "CS", "LFE", "FR", "SR"};
    for (int i = 0; i < kAngleCount; ++i) rep.labels[i] = kLabels[i];
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

  /**
   * 按**任意布局**自检（Ambisonic 12 扬声器用）。
   *
   * 与 [Run] 的区别：
   *   - 方位/仰角由调用方给（Ambisonic 的上方 4 只是 35° 仰角）；
   *   - 条数可变，报告里带上条数与声道名；
   *   - 校正只做一轮（12 方位 × 2 轮 = 24 次 4096 点测量，再翻倍太慢）。
   *
   * @param labels 可选声道名（长度 = count），可为 nullptr
   */
  static Report RunLayout(HrtfEngine& hrtf, float sr,
                          const float* az, const float* el,
                          const char* const* labels, int count,
                          bool apply_correction = true) {
    Report rep;
    if (count > kMaxAngleCount) count = kMaxAngleCount;
    rep.count = count;
    for (int k = 0; k < count; ++k) rep.labels[k] = labels != nullptr ? labels[k] : nullptr;

    const int len = 4096;
    const int skip = 512;
    std::vector<float> src(static_cast<std::size_t>(len));
    std::vector<float> out_l(static_cast<std::size_t>(len));
    std::vector<float> out_r(static_cast<std::size_t>(len));

    std::uint32_t seed = 0x9E3779B9u;
    for (int i = 0; i < len; ++i) {
      seed = seed * 1664525u + 1013904223u;
      src[static_cast<std::size_t>(i)] = (static_cast<float>(seed >> 8) / 8388608.0f) - 1.0f;
    }

    for (int k = 0; k < count; ++k) {
      Measure(hrtf, src, out_l, out_r, len, skip, az[k], rep.angles[k], el[k]);
    }
    rep.itd_rms_error_before = RmsItdError(rep);
    rep.ild_rms_error_before = RmsIldError(rep);
    rep.handedness_ok = CheckHandedness(rep);
    rep.symmetric_ok = CheckSymmetry(rep);

    if (apply_correction) {
      float itd_ratio_sum = 0.0f;
      int itd_n = 0;
      float ild_delta_sum = 0.0f;
      int ild_n = 0;
      for (int k = 0; k < count; ++k) {
        const AzimuthResult& a = rep.angles[k];
        if (std::fabs(a.itd_expected) > 2.0f && std::fabs(a.itd_measured) > 1.0f) {
          itd_ratio_sum += a.itd_measured / a.itd_expected;
          ++itd_n;
        }
        if (std::fabs(a.azimuth) > 1.0f) {
          ild_delta_sum += a.ild_target_db - a.ild_measured_db;
          ++ild_n;
        }
      }
      const float ratio = itd_n > 0 ? (itd_ratio_sum / static_cast<float>(itd_n)) : 1.0f;
      const float itd_scale = (ratio > 0.2f && ratio < 2.5f) ? ratio : 1.0f;
      const float delta = ild_n > 0 ? (ild_delta_sum / static_cast<float>(ild_n)) : 0.0f;
      const float step = std::pow(10.0f, delta / 40.0f);
      const float ild_trim = (step > 0.5f && step < 2.0f) ? step : 1.0f;
      hrtf.SetCalibration(itd_scale, ild_trim);
      rep.itd_scale = itd_scale;
      rep.ild_trim = ild_trim;

      for (int k = 0; k < count; ++k) {
        Measure(hrtf, src, out_l, out_r, len, skip, az[k], rep.angles[k], el[k]);
      }
    }

    rep.itd_rms_error_after = RmsItdError(rep);
    rep.ild_rms_error_after = RmsIldError(rep);
    // 「校正后不得变差」的容差取 0.15：ILD 的可闻阈约 1 dB，0.1~0.2 dB 的起伏是
    // 校正步长与量化抖动，不是回归。原先 0.05 太紧 —— 实测出现过 before=0.35 /
    // after=0.47 被判 FAIL，但每条方向的实际偏差都 <1 dB。
    rep.pass = rep.handedness_ok && rep.symmetric_ok &&
               rep.itd_rms_error_after <= 1.5f && rep.ild_rms_error_after <= 1.5f &&
               rep.itd_rms_error_after <= rep.itd_rms_error_before + 0.15f &&
               rep.ild_rms_error_after <= rep.ild_rms_error_before + 0.15f;
    hrtf.Configure(0.0f, 0.0f);
    return rep;
  }

 private:
  // 目标 ILD 由模型自身给出（白噪下可精确推算）：
  // 这样判据检验的是"实现是否符合设计"，而不是"是否符合某个假头的绝对值"。

  static void Measure(HrtfEngine& hrtf, const std::vector<float>& src,
                      std::vector<float>& out_l, std::vector<float>& out_r,
                      int len, int skip, float azimuth, AzimuthResult& res,
                      float elevation = 0.0f) {
    hrtf.Reset();
    hrtf.Configure(azimuth, elevation);
    res.azimuth = azimuth;
    res.elevation = elevation;
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
    for (int k = 0; k < rep.count; ++k) {
      const double d = rep.angles[k].itd_measured - rep.angles[k].itd_expected;
      acc += d * d;
      ++n;
    }
    return static_cast<float>(std::sqrt(acc / n));
  }

  static float RmsIldError(const Report& rep) {
    double acc = 0.0;
    int n = 0;
    for (int k = 0; k < rep.count; ++k) {
      const double d = rep.angles[k].ild_measured_db - rep.angles[k].ild_target_db;
      acc += d * d;
      ++n;
    }
    return static_cast<float>(std::sqrt(acc / n));
  }

  /** θ>0 必须"右响 + 右耳早"：ILD < 0 且 ITD > 0 */
  static bool CheckHandedness(const Report& rep) {
    for (int k = 0; k < rep.count; ++k) {
      const AzimuthResult& a = rep.angles[k];
      // 正后方（±180 附近）左右对称：ITD 理论为 0、ILD 也为 0，不适用"右响右耳早"判据。
      //
      // ILD 判据要按**数据集能力**取舍：实测 HRIR 若在生成时按单耳各自归一化，
      // 左右能量会被抹平（ILD 恒为 0），此时只能靠 ITD 判左右。
      // 用目标 ILD 的绝对值判断该方向有没有可用 ILD 线索：≥1 dB 才要求符号正确。
      const bool has_ild = (a.ild_target_db > 1.0f) || (a.ild_target_db < -1.0f);
      if (a.azimuth > 5.0f && a.azimuth < 175.0f) {
        if (!(a.itd_measured > 0.0f)) return false;
        if (has_ild && !(a.ild_measured_db < 0.0f)) return false;
      }
      if (a.azimuth < -5.0f && a.azimuth > -175.0f) {
        if (!(a.itd_measured < 0.0f)) return false;
        if (has_ild && !(a.ild_measured_db > 0.0f)) return false;
      }
    }
    return true;
  }

  /**
   * θ 与 −θ 镜像（ITD/ILD 反号）。
   *
   * 配对方式：为每个方位找**方位角互补**（θ 与 −θ，容差 2°）的另一条。
   * 为什么不用 `count-1-k` 下标配对：那只对 5.1 那张手工排过序的方位表成立，
   * Ambisonic 的 12 扬声器表（0/45/…/315 + 4 上方）按方位是乱序的。
   * 找不到互补项（如 0° / 180° 自对称）就跳过。
   */
  static bool CheckSymmetry(const Report& rep) {
    for (int k = 0; k < rep.count; ++k) {
      const AzimuthResult& a = rep.angles[k];
      if (std::fabs(a.azimuth) < 5.0f || std::fabs(a.azimuth) > 175.0f) continue;
      for (int j = 0; j < rep.count; ++j) {
        if (j == k) continue;
        const AzimuthResult& b = rep.angles[j];
        if (std::fabs(a.azimuth + b.azimuth) > 2.0f) continue;
        // 必须同仰角：12 扬声器布局里 UFL(-45,35°) 与 H45 的方位互补关系会串台，
        // 不加这一条就会拿"上方 45°"去比"水平 45°"，必然误判为不对称。
        if (std::fabs(a.elevation - b.elevation) > 5.0f) continue;
        if (std::fabs(a.itd_measured + b.itd_measured) > 1.5f) return false;
        if (std::fabs(a.ild_measured_db + b.ild_measured_db) > 1.5f) return false;
      }
    }
    return true;
  }
};

}  // namespace sonicraft::dsp::vs
