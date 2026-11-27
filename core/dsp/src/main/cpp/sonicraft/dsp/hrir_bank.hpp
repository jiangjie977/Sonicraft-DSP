/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 实测 HRIR 数据集访问层。
 *
 * 五套公开学术数据集（KEMAR / KEMAR-Hannover / CIPIC / ARI / LISTEN），
 * 每套 8 个水平方位（0/45/…/315°）× 左右耳 × 557 tap @48 kHz。
 *
 * 用途：耳机虚拟环绕的卷积核。模型编号与 UI 一一对应：
 *   0 = 参数化模型（无实测数据，走 ParametricHrtf）
 *   1 = KEMAR（MIT）
 *   2 = KEMAR-Hannover
 *   3 = CIPIC
 *   4 = ARI
 *   5 = LISTEN
 */
#pragma once

#include <cmath>

#include "sonicraft/dsp/hrir/hrir_ari.hpp"
#include "sonicraft/dsp/hrir/hrir_cipic.hpp"
#include "sonicraft/dsp/hrir/hrir_cipic_elev.hpp"
#include "sonicraft/dsp/hrir/hrir_kemar.hpp"
#include "sonicraft/dsp/hrir/hrir_kemar_hannover.hpp"
#include "sonicraft/dsp/hrir/hrir_listen.hpp"

namespace sonicraft::dsp::vs {

/** 一对双耳脉冲响应（原始数据，未做任何归一化） */
struct HrirPair {
  const float* l = nullptr;
  const float* r = nullptr;
  int taps = 0;
  bool valid() const { return l != nullptr && r != nullptr && taps > 0; }
};

class HrirBank {
 public:
  static constexpr int kModelCount = 6;         // 0 参数化 + 5 实测
  static constexpr int kAzimuthCount = 8;
  static constexpr int kMaxTaps = 557;
  static constexpr float kAzimuthDeg[kAzimuthCount] = {
      0.0f, 45.0f, 90.0f, 135.0f, 180.0f, 225.0f, 270.0f, 315.0f,
  };

  /** 模型名（ASCII，供 JNI 直接返回，避免非 ASCII 字节问题） */
  static const char* ModelName(int model) {
    switch (model) {
      case 1: return "KEMAR";
      case 2: return "KEMAR-Hannover";
      case 3: return "CIPIC";
      case 4: return "ARI";
      case 5: return "LISTEN";
      default: return "Parametric";
    }
  }

  static bool IsValidModel(int model) { return model >= 0 && model < kModelCount; }

  static int TapsOf(int model) {
    switch (model) {
      case 1: return KEMAR_AZ_0_N;
      case 2: return KEMAR_HANNOVER_AZ_0_N;
      case 3: return CIPIC_AZ_0_N;
      case 4: return ARI_AZ_0_N;
      case 5: return LISTEN_AZ_0_N;
      default: return 0;
    }
  }

  /**
   * 取**最近方位**的实测 IR。
   *
   * 为什么用最近而不是插值：解码扬声器正好落在 45° 网格上（0/45/…/315），
   * 与数据集方位一一对应，不需要插值；用插值反而会把两侧 IR 的 ITD 平均掉。
   */
  static HrirPair Lookup(int model, float azimuth_deg) {
    // ① 数据集用的是「左为正」的方位定义（它的 +45° 在左手边），本项目约定「右为正」，
    //    所以取 IR 之前必须镜像一次。漏掉这一步 = 左右整体互换：
    //    自检会报「+45° 的 ITD 为负」（右耳更晚），听起来就是左右反了。
    //
    // ② 左右对称性由**数据侧**保证：
    //    - 5 套水平数据本身左右差异仅 0.1~0.9 样本，落在自检判据（1.5）之内；
    //    - CIPIC 仰角数据在生成时就强制以右侧为准做镜像（见 hrir_cipic_elev.hpp）。
    //    运行时**不要**再做左右交换：那等于把左侧方向的左右耳对调，
    //    ITD/ILD 符号会整体翻反（实测踩过：handedness 由 OK 变 FAIL）。
    const int idx = NearestIndex(-azimuth_deg);
    return PickByIndex(model, idx);
  }

  /** 按数据集索引取一对 IR（不含方位镜像逻辑） */
  static HrirPair PickByIndex(int model, int idx) {
    switch (model) {
      case 1:
        return Pick<KEMAR_AZ_0_L, KEMAR_AZ_0_R, KEMAR_AZ_0_N,
                    KEMAR_AZ_45_L, KEMAR_AZ_45_R, KEMAR_AZ_45_N,
                    KEMAR_AZ_90_L, KEMAR_AZ_90_R, KEMAR_AZ_90_N,
                    KEMAR_AZ_135_L, KEMAR_AZ_135_R, KEMAR_AZ_135_N,
                    KEMAR_AZ_180_L, KEMAR_AZ_180_R, KEMAR_AZ_180_N,
                    KEMAR_AZ_225_L, KEMAR_AZ_225_R, KEMAR_AZ_225_N,
                    KEMAR_AZ_270_L, KEMAR_AZ_270_R, KEMAR_AZ_270_N,
                    KEMAR_AZ_315_L, KEMAR_AZ_315_R, KEMAR_AZ_315_N>(idx);
      case 2:
        return Pick<KEMAR_HANNOVER_AZ_0_L, KEMAR_HANNOVER_AZ_0_R, KEMAR_HANNOVER_AZ_0_N,
                    KEMAR_HANNOVER_AZ_45_L, KEMAR_HANNOVER_AZ_45_R, KEMAR_HANNOVER_AZ_45_N,
                    KEMAR_HANNOVER_AZ_90_L, KEMAR_HANNOVER_AZ_90_R, KEMAR_HANNOVER_AZ_90_N,
                    KEMAR_HANNOVER_AZ_135_L, KEMAR_HANNOVER_AZ_135_R, KEMAR_HANNOVER_AZ_135_N,
                    KEMAR_HANNOVER_AZ_180_L, KEMAR_HANNOVER_AZ_180_R, KEMAR_HANNOVER_AZ_180_N,
                    KEMAR_HANNOVER_AZ_225_L, KEMAR_HANNOVER_AZ_225_R, KEMAR_HANNOVER_AZ_225_N,
                    KEMAR_HANNOVER_AZ_270_L, KEMAR_HANNOVER_AZ_270_R, KEMAR_HANNOVER_AZ_270_N,
                    KEMAR_HANNOVER_AZ_315_L, KEMAR_HANNOVER_AZ_315_R, KEMAR_HANNOVER_AZ_315_N>(idx);
      case 3:
        return Pick<CIPIC_AZ_0_L, CIPIC_AZ_0_R, CIPIC_AZ_0_N,
                    CIPIC_AZ_45_L, CIPIC_AZ_45_R, CIPIC_AZ_45_N,
                    CIPIC_AZ_90_L, CIPIC_AZ_90_R, CIPIC_AZ_90_N,
                    CIPIC_AZ_135_L, CIPIC_AZ_135_R, CIPIC_AZ_135_N,
                    CIPIC_AZ_180_L, CIPIC_AZ_180_R, CIPIC_AZ_180_N,
                    CIPIC_AZ_225_L, CIPIC_AZ_225_R, CIPIC_AZ_225_N,
                    CIPIC_AZ_270_L, CIPIC_AZ_270_R, CIPIC_AZ_270_N,
                    CIPIC_AZ_315_L, CIPIC_AZ_315_R, CIPIC_AZ_315_N>(idx);
      case 4:
        return Pick<ARI_AZ_0_L, ARI_AZ_0_R, ARI_AZ_0_N,
                    ARI_AZ_45_L, ARI_AZ_45_R, ARI_AZ_45_N,
                    ARI_AZ_90_L, ARI_AZ_90_R, ARI_AZ_90_N,
                    ARI_AZ_135_L, ARI_AZ_135_R, ARI_AZ_135_N,
                    ARI_AZ_180_L, ARI_AZ_180_R, ARI_AZ_180_N,
                    ARI_AZ_225_L, ARI_AZ_225_R, ARI_AZ_225_N,
                    ARI_AZ_270_L, ARI_AZ_270_R, ARI_AZ_270_N,
                    ARI_AZ_315_L, ARI_AZ_315_R, ARI_AZ_315_N>(idx);
      case 5:
        return Pick<LISTEN_AZ_0_L, LISTEN_AZ_0_R, LISTEN_AZ_0_N,
                    LISTEN_AZ_45_L, LISTEN_AZ_45_R, LISTEN_AZ_45_N,
                    LISTEN_AZ_90_L, LISTEN_AZ_90_R, LISTEN_AZ_90_N,
                    LISTEN_AZ_135_L, LISTEN_AZ_135_R, LISTEN_AZ_135_N,
                    LISTEN_AZ_180_L, LISTEN_AZ_180_R, LISTEN_AZ_180_N,
                    LISTEN_AZ_225_L, LISTEN_AZ_225_R, LISTEN_AZ_225_N,
                    LISTEN_AZ_270_L, LISTEN_AZ_270_R, LISTEN_AZ_270_N,
                    LISTEN_AZ_315_L, LISTEN_AZ_315_R, LISTEN_AZ_315_N>(idx);
      default:
        return {};
    }
  }

  /**
   * 仰角 ≥10° 时取 CIPIC 的**真实仰角** IR（8 个方向：±30/±45/±110/±135 @35°）。
   *
   * 为什么单独一套：水平面的 5 套数据只有仰角 0，上方扬声器只能靠陷波假装。
   * CIPIC 的仰角匹配误差实测 ≤4.9°（±30 为 0.9°，±45/±135 为 0.5°）。
   *
   * 注意：这套数据**自带真实 ILD**（±10~15 dB），调用方不应再叠加头影补偿。
   */
  static HrirPair LookupElevation(float azimuth_deg, float elevation_deg) {
    if (std::fabs(elevation_deg) < 10.0f) return {};
    constexpr float kDeg = 3.14159265358979f / 180.0f;
    int best = -1;
    float best_d = 1e9f;
    for (int i = 0; i < hrir::kCipicElevDirections; ++i) {
      const hrir::CipicElevDir& d = hrir::kCipicElevDirs[i];
      float d_az = std::fabs(AngDiff(azimuth_deg, d.az_deg)) * std::cos(elevation_deg * kDeg);
      float d_el = std::fabs(elevation_deg - d.el_deg);
      const float dist = std::sqrt(d_az * d_az + d_el * d_el);
      if (dist < best_d) {
        best_d = dist;
        best = i;
      }
    }
    if (best < 0) return {};
    return {hrir::kCipicElevDirs[best].l, hrir::kCipicElevDirs[best].r, hrir::kCipicElevTaps};
  }

  /** 有符号角差（−180..180） */
  static float AngDiff(float a, float b) {
    float d = std::fmod(a - b, 360.0f);
    if (d > 180.0f) d -= 360.0f;
    if (d < -180.0f) d += 360.0f;
    return d;
  }

  /** 方位 → 最近数据集索引（0..7）；az 允许任意实数（内部归一化到 0..360） */
  static int NearestIndex(float azimuth_deg) {
    float az = std::fmod(azimuth_deg, 360.0f);
    if (az < 0.0f) az += 360.0f;
    int best = 0;
    float best_d = 1e9f;
    for (int i = 0; i < kAzimuthCount; ++i) {
      float d = std::fabs(az - kAzimuthDeg[i]);
      if (d > 180.0f) d = 360.0f - d;   // 环形距离
      if (d < best_d) {
        best_d = d;
        best = i;
      }
    }
    return best;
  }

  /**
   * 从 IR 现场测量 ITD（样本，正 = 右耳滞后）。
   *
   * 为什么要测：实测 IR 的时延已经包含在数据里，自检需要拿它当"期望值"，
   * 不能再用 Woodworth 公式估算（各数据集的假头尺寸不同）。
   * 方法：双耳互相关在 ±64 样本内取峰，抛物线插值到亚样本。
   */
  static float MeasuredItdSamples(const HrirPair& ir) {
    if (!ir.valid()) return 0.0f;
    constexpr int kMaxLag = 64;
    double best = -1e30;
    int best_lag = 0;
    double corr[2 * kMaxLag + 1] = {};
    const int n = ir.taps;
    for (int lag = -kMaxLag; lag <= kMaxLag; ++lag) {
      double acc = 0.0;
      const int lo = lag > 0 ? lag : 0;
      const int hi = lag < 0 ? n + lag : n;
      for (int i = lo; i < hi; i += 2) {   // 隔点取样，够用且省一半
        acc += static_cast<double>(ir.r[i]) * static_cast<double>(ir.l[i - lag]);
      }
      corr[lag + kMaxLag] = acc;
      if (acc > best) {
        best = acc;
        best_lag = lag;
      }
    }
    double delta = 0.0;
    const int idx = best_lag + kMaxLag;
    if (idx > 0 && idx < 2 * kMaxLag) {
      const double y0 = corr[idx - 1], y1 = corr[idx], y2 = corr[idx + 1];
      const double den = y0 - 2.0 * y1 + y2;
      if (std::fabs(den) > 1e-20) delta = 0.5 * (y0 - y2) / den;
    }
    return static_cast<float>(best_lag) + static_cast<float>(delta);
  }

  /** 从 IR 测量 ILD（dB，正 = 左耳更响） */
  static float MeasuredIldDb(const HrirPair& ir) {
    if (!ir.valid()) return 0.0f;
    double e_l = 0.0, e_r = 0.0;
    for (int i = 0; i < ir.taps; ++i) {
      e_l += static_cast<double>(ir.l[i]) * ir.l[i];
      e_r += static_cast<double>(ir.r[i]) * ir.r[i];
    }
    e_l += 1e-20;
    e_r += 1e-20;
    return static_cast<float>(10.0 * std::log10(e_l / e_r));
  }

 private:
  template <const float* A0L, const float* A0R, int A0N,
            const float* A1L, const float* A1R, int A1N,
            const float* A2L, const float* A2R, int A2N,
            const float* A3L, const float* A3R, int A3N,
            const float* A4L, const float* A4R, int A4N,
            const float* A5L, const float* A5R, int A5N,
            const float* A6L, const float* A6R, int A6N,
            const float* A7L, const float* A7R, int A7N>
  static HrirPair Pick(int idx) {
    switch (idx) {
      case 0: return {A0L, A0R, A0N};
      case 1: return {A1L, A1R, A1N};
      case 2: return {A2L, A2R, A2N};
      case 3: return {A3L, A3R, A3N};
      case 4: return {A4L, A4R, A4N};
      case 5: return {A5L, A5R, A5N};
      case 6: return {A6L, A6R, A6N};
      default: return {A7L, A7R, A7N};
    }
  }
};

}  // namespace sonicraft::dsp::vs
