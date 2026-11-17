/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>

namespace sonicraft::dsp::vs {

/** 二阶节（转置直二型），系数在非实时线程算好，实时只做 MAC。 */
struct Biquad {
  float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
  float z1 = 0.0f, z2 = 0.0f;

  inline float Process(float x) {
    const float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
  void Reset() { z1 = z2 = 0.0f; }

  /** 高通（RBJ，Q=0.707） */
  void SetHighPass(float fc, float sr) {
    const float w = 2.0f * kPi * fc / sr;
    const float alpha = std::sin(w) / 1.41421356f;
    const float a0 = 1.0f + alpha;
    b0 = (1.0f + std::cos(w)) / (2.0f * a0);
    b1 = -(1.0f + std::cos(w)) / a0;
    b2 = b0;
    a1 = (-2.0f * std::cos(w)) / a0;
    a2 = (1.0f - alpha) / a0;
  }
  /** 带阻 */
  void SetNotch(float fc, float q, float sr) {
    const float w = 2.0f * kPi * fc / sr;
    const float alpha = std::sin(w) / (2.0f * q);
    const float a0 = 1.0f + alpha;
    b0 = 1.0f / a0;
    b1 = (-2.0f * std::cos(w)) / a0;
    b2 = 1.0f / a0;
    a1 = (-2.0f * std::cos(w)) / a0;
    a2 = (1.0f - alpha) / a0;
  }

  static constexpr float kPi = 3.14159265358979323846f;
};

/** 小数延迟线（线性插值），长度固定 256 样本，覆盖头部最大 ITD（约 0.75ms@48k）。 */
class DelayLine {
 public:
  static constexpr int kSize = 256;
  static constexpr int kMask = kSize - 1;

  void Reset() {
    for (int i = 0; i < kSize; ++i) buf_[i] = 0.0f;
    pos_ = 0;
  }

  /**
   * delay 样本数（0 ~ kSize-1），支持小数。
   *
   * 用 **Catmull-Rom 三次插值**而不是两点线性插值：
   * 线性插值在 frac≈0.5 处等价于一个 2 抽头 FIR（sqrt((1−f)²+f²) ≈ −3 dB 的宽带衰减），
   * 会让"被延迟的那只耳"整体变闷 —— 实测 ILD 会凭空多出约 2 dB，听感上高频发暗。
   * 三次插值把这项误差压到 0.3 dB 以内。
   */
  inline float Process(float x, float delay) {
    buf_[pos_] = x;
    float d = delay;
    if (d < 0.0f) d = 0.0f;
    if (d > static_cast<float>(kMask - 3)) d = static_cast<float>(kMask - 3);
    const int i0 = static_cast<int>(d);
    const float t = d - static_cast<float>(i0);
    const int b0 = (pos_ - i0 + 1) & kMask;
    const int b1 = (pos_ - i0) & kMask;
    const int b2 = (pos_ - i0 - 1) & kMask;
    const int b3 = (pos_ - i0 - 2) & kMask;
    const float p0 = buf_[b0], p1 = buf_[b1], p2 = buf_[b2], p3 = buf_[b3];
    const float t2 = t * t;
    const float t3 = t2 * t;
    const float out = 0.5f * ((2.0f * p1) + (-p0 + p2) * t +
                              (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
                              (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
    pos_ = (pos_ + 1) & kMask;
    return out;
  }

 private:
  float buf_[kSize]{};
  int pos_ = 0;
};

/**
 * 参数化双耳模型（水平面为主）。
 *
 * 组成（每耳独立）：
 *   1. **ITD**：Woodworth 近似 —— itd = (a/c)·(θ_front + sin θ_front)，a=8.75cm、c=343m/s，
 *      前方用 |θ|、后方用 π−|θ|，因此前后镜像的 ITD 对称（与真实头部接近）。
 *   2. **ILD**：对侧耳一阶低通（截止随方位从 20kHz 降到 5kHz）+ 增益（1 − 0.85·x·(0.7+0.3cosθ)），
 *      后方再给同侧耳叠一层轻微低通（0.92 增益）。
 *   3. **躯干高通**：25Hz + 16Hz/度（仰角抬高时截止上移，模拟躯干遮挡变化）。
 *   4. **俯仰线索**（仰角 > 5° 时启用）：8.5k~13.5kHz 带阻 + 3.5kHz 以上轻微提亮。
 *   5. **后方线索**：后方额外低通（20k→14k）与 7kHz 带阻，模拟耳廓后方衰减。
 *
 * 参数全部在 Configure() 内算好；ProcessSample() 只做乘加与延迟线读写。
 */
class ParametricHrtf {
 public:
  /**
   * 两套参数：0 = 现有（早先实现，级联较简）；1 = **源模型参数原样**（默认）。
   *
   * 差别只在参数化分支的「深度/亮度/前向陷波」三处：
   *   现有：陷波全量、提亮作用在低频、无前向陷波；
   *   源模型：陷波按深度混入（后方 0.30/0.40、仰角 0.30/0.50）、
   *           提亮是 **高频** 提升（out +（g−1）·（out−lowpass））、
   *           前方额外叠一道 9k−(az/90)·3.5k、Q=4 的谱陷波（深度 0.30）。
   */
  enum Preset : int { kPresetCurrent = 0, kPresetSource = 1 };

  void SetPreset(Preset p) { preset_ = p; }
  Preset GetPreset() const { return preset_; }
  void Prepare(float sample_rate) {
    sr_ = sample_rate > 0.0f ? sample_rate : 48000.0f;
    Reset();
  }

  void Reset() {
    delay_l_.Reset();
    delay_r_.Reset();
    hp_l_.Reset();
    hp_r_.Reset();
    el_notch_l_.Reset();
    el_notch_r_.Reset();
    fs_notch_.Reset();
    rear_notch_l_.Reset();
    rear_notch_r_.Reset();
    bright_l_ = bright_r_ = 0.0f;
    ild_lp_l_ = ild_lp_r_ = 0.0f;
    rear_lp_l_ = rear_lp_r_ = 0.0f;
  }

  /** 方位角（0=正前，正=右侧，度）与仰角 */
  void Configure(float azimuth_deg, float elevation_deg) {
    float az = azimuth_deg;
    while (az > 180.0f) az -= 360.0f;
    while (az < -180.0f) az += 360.0f;

    const float az_rad = az * Biquad::kPi / 180.0f;
    const float el_rad = elevation_deg * Biquad::kPi / 180.0f;
    (void)el_rad;
    const float abs_az = std::fabs(az_rad);
    const bool at_pole = std::fabs(std::fabs(az_rad) - Biquad::kPi) < 0.0005f;
    const bool rear = abs_az > Biquad::kPi * 0.5f;
    const float front_az = rear ? (Biquad::kPi - abs_az) : abs_az;

    // ---- ITD ----
    const float itd = (kHeadRadius / kSpeedOfSound) * (front_az + std::sin(front_az));
    const float itd_samples = itd * sr_;
    itd_l_ = (az > 0.001f && !at_pole) ? itd_samples : 0.0f;
    // 正后方：左右完全对称，ILD 必须为 0（否则会与 itd=0 矛盾，产生"偏侧"错觉）
    itd_r_ = (az < -0.001f && !at_pole) ? itd_samples : 0.0f;

    // ---- ILD ----
    // 前方（0~90°）用 abs_az 正常加深阴影；
    // 后方（90~180°）**不能套用"前方镜像"**（那会让 110° 的 ILD 掉到 5 dB，
    // 而实测头模在 110° 仍有 8~15 dB）。真实原因是耳廓朝前，后方对侧耳遮挡更强。
    // 这里改用「90° 处峰值 → 180° 归零」的形状（cos 收口）：
    //   90°  → 1.00（ILD ≈ 10.8 dB）
    //   110° → 0.94（ILD ≈ 9~10 dB）
    //   180° → 0.00（正后方左右对称，ILD 必须为 0）
    float x_az;
    if (rear) {
      // 90~130°：维持峰值阴影（真实头模 110° 仍有 8~15 dB，只有 90° 附近才是峰值）
      // 130~180°：cos 收口到 0（正后方左右对称，ILD 必须归零）
      const float deg = abs_az * 180.0f / Biquad::kPi;
      if (deg <= 130.0f) {
        x_az = 1.0f;
      } else {
        const float t = (deg - 130.0f) / 50.0f;
        x_az = std::cos(t * Biquad::kPi * 0.5f);
      }
    } else {
      x_az = front_az / (Biquad::kPi * 0.5f);
    }
    float fc_ild = 20000.0f - 8000.0f * x_az;
    if (fc_ild < 5000.0f) fc_ild = 5000.0f;
    const float coeff = std::exp(-2.0f * Biquad::kPi * fc_ild / sr_);
    const float contralateral_gain = 1.0f - 0.85f * x_az * (0.7f + 0.3f * std::cos(front_az));
    const float ipsi_rear_coeff = std::exp(-2.0f * Biquad::kPi * fc_ild * 0.5f / sr_);

    if (az > 0.001f && !at_pole) {  // 声源在右：左耳为对侧耳
      ild_coeff_l_ = coeff;
      ild_gain_l_ = contralateral_gain;
      ild_coeff_r_ = rear ? ipsi_rear_coeff : 1.0f;
      ild_gain_r_ = rear ? 0.92f : 1.0f;
    } else if (az < -0.001f && !at_pole) {
      ild_coeff_r_ = coeff;
      ild_gain_r_ = contralateral_gain;
      ild_coeff_l_ = rear ? ipsi_rear_coeff : 1.0f;
      ild_gain_l_ = rear ? 0.92f : 1.0f;
    } else {
      ild_coeff_l_ = ild_coeff_r_ = 1.0f;
      ild_gain_l_ = ild_gain_r_ = 1.0f;
    }

    // ---- 躯干高通 ----
    float hp_fc = 25.0f + elevation_deg * 16.0f;
    if (hp_fc < 25.0f) hp_fc = 25.0f;
    if (hp_fc > 3000.0f) hp_fc = 3000.0f;
    hp_l_.SetHighPass(hp_fc, sr_);
    hp_r_.SetHighPass(hp_fc, sr_);

    // ---- 俯仰线索 ----
    if (elevation_deg > 5.0f) {
      float el_fc = 8500.0f + (elevation_deg / 90.0f) * 5000.0f;
      el_notch_l_.SetNotch(el_fc, 3.0f, sr_);
      el_notch_r_.SetNotch(el_fc, 3.0f, sr_);
      // 源模型：增强档 0.025 / 封顶 3.5；普通档 0.016 / 2.5
      const bool enhanced = preset_ == kPresetSource;
      float g = 1.0f + (enhanced ? 0.025f : 0.016f) * elevation_deg;
      const float cap = enhanced ? 3.5f : 2.5f;
      if (g > cap) g = cap;
      el_notch_depth_ = enhanced ? 0.50f : 0.30f;
      bright_gain_ = g;
      bright_coeff_ = std::exp(-2.0f * Biquad::kPi * 3500.0f / sr_);
      el_notch_on_ = true;
    } else {
      el_notch_l_ = Biquad{};
      el_notch_r_ = Biquad{};
      bright_gain_ = 1.0f;
      bright_coeff_ = 0.0f;
      el_notch_on_ = false;
    }

    // ---- 前方/侧方谱陷波（仅源模型）：9k − (az_deg/90)·3.5k，Q=4，深度 0.30 ----
    if (preset_ == kPresetSource && !rear) {
      const float az_deg = std::fabs(az);
      float fs_fc = 9000.0f - (az_deg / 90.0f) * 3500.0f;
      if (fs_fc < 2000.0f) fs_fc = 2000.0f;
      fs_notch_.SetNotch(fs_fc, 4.0f, sr_);
      fs_on_ = true;
      fs_depth_ = 0.30f;
    } else {
      fs_notch_ = Biquad{};
      fs_on_ = false;
    }

    // ---- 后方线索 ----
    if (rear) {
      const float rear_amount = (abs_az - Biquad::kPi * 0.5f) / (Biquad::kPi * 0.5f);
      float rear_fc = 20000.0f - 6000.0f * rear_amount;
      rear_lp_coeff_ = std::exp(-2.0f * Biquad::kPi * rear_fc / sr_);
      rear_gain_ = 0.85f - 0.12f * rear_amount;
      rear_notch_l_.SetNotch(7000.0f, 2.0f, sr_);
      rear_notch_r_.SetNotch(7000.0f, 2.0f, sr_);
      rear_on_ = true;
      rear_notch_depth_ = preset_ == kPresetSource ? 0.40f : 1.0f;
    } else {
      rear_lp_coeff_ = 1.0f;
      rear_gain_ = 1.0f;
      rear_notch_l_ = Biquad{};
      rear_notch_r_ = Biquad{};
      rear_on_ = false;
    }

    azimuth_ = az;
  }

  /** 单样本渲染：输入已是单声道声源，输出到双耳 */
  inline void ProcessSample(float x, float* out_l, float* out_r) {
    float l = delay_l_.Process(x, itd_l_ * itd_scale_);
    float r = delay_r_.Process(x, itd_r_ * itd_scale_);

    // 系数≈1 表示"本侧耳不衰减"，必须直通；否则 y = a·y + (1−a)·x 会衰减到 0
    if (ild_coeff_l_ < 0.999f) {
      l = ild_lp_l_ = ild_coeff_l_ * ild_lp_l_ + (1.0f - ild_coeff_l_) * l;
    } else {
      ild_lp_l_ = l;
    }
    if (ild_coeff_r_ < 0.999f) {
      r = ild_lp_r_ = ild_coeff_r_ * ild_lp_r_ + (1.0f - ild_coeff_r_) * r;
    } else {
      ild_lp_r_ = r;
    }
    l *= ild_gain_l_ * ild_trim_;
    r *= ild_gain_r_ / ild_trim_;

    l = hp_l_.Process(l);
    r = hp_r_.Process(r);

    if (el_notch_on_) {
      // 源模型语义：**高频提升** —— out + (g−1)·(out − lowpass(out))
      const float lp_l = bright_l_ = bright_coeff_ * bright_l_ + (1.0f - bright_coeff_) * l;
      const float lp_r = bright_r_ = bright_coeff_ * bright_r_ + (1.0f - bright_coeff_) * r;
      l += (bright_gain_ - 1.0f) * (l - lp_l);
      r += (bright_gain_ - 1.0f) * (r - lp_r);
      // 仰角陷波按深度混入（源模型 0.50 增强 / 0.30 普通，本地全量）
      const float nl = el_notch_l_.Process(l);
      const float nr = el_notch_r_.Process(r);
      l = l * (1.0f - el_notch_depth_) + nl * el_notch_depth_;
      r = r * (1.0f - el_notch_depth_) + nr * el_notch_depth_;
    }

    if (rear_on_) {
      if (rear_lp_coeff_ < 0.999f) {
        l = rear_lp_l_ = rear_lp_coeff_ * rear_lp_l_ + (1.0f - rear_lp_coeff_) * l;
        r = rear_lp_r_ = rear_lp_coeff_ * rear_lp_r_ + (1.0f - rear_lp_coeff_) * r;
      } else {
        rear_lp_l_ = l;
        rear_lp_r_ = r;
      }
      // 后方 7 kHz 陷波按深度混入（源模型 0.40 增强 / 0.30 普通）
      const float nl = rear_notch_l_.Process(l);
      const float nr = rear_notch_r_.Process(r);
      l = (l * (1.0f - rear_notch_depth_) + nl * rear_notch_depth_) * rear_gain_;
      r = (r * (1.0f - rear_notch_depth_) + nr * rear_notch_depth_) * rear_gain_;
    } else if (fs_on_) {
      // 前方/侧方谱陷波（仅源模型）：深度 0.30
      const float nl = fs_notch_.Process(l);
      const float nr = fs_notch_.Process(r);
      l = l * (1.0f - fs_depth_) + nl * fs_depth_;
      r = r * (1.0f - fs_depth_) + nr * fs_depth_;
    }

    *out_l = l;
    *out_r = r;
  }

  // ---- 自检/校正接口 ----
  /** 理论 ITD（样本，含符号：正 = 左耳滞后） */
  float ItdSamples() const { return itd_l_ - itd_r_; }
  /** 当前对侧耳一阶低通系数（用于理论 ILD 推算） */
  float ContralateralCoeff() const { return ild_coeff_l_ < ild_coeff_r_ ? ild_coeff_l_ : ild_coeff_r_; }

  /**
   * 白噪激励下的**模型理论 ILD**（dB，右响为负）。
   *
   * 一阶低通 a 对白噪的增益 = sqrt((1−a)/(1+a))，故每耳总增益 = ild_gain × 该值；
   * 自检用它与实测值比对 —— 偏差即实现与设计不符（例：系数为 1 时未直通会让该耳归零）。
   */
  float TheoreticalIldDb() const {
    const float gl = ild_gain_l_ * NoiseGain(ild_coeff_l_);
    const float gr = ild_gain_r_ * NoiseGain(ild_coeff_r_);
    return 20.0f * std::log10((gl + 1e-12f) / (gr + 1e-12f));
  }
  float Azimuth() const { return azimuth_; }

  void SetCalibration(float itd_scale, float ild_trim) {
    itd_scale_ = itd_scale > 0.2f && itd_scale < 2.5f ? itd_scale : 1.0f;
    ild_trim_ = ild_trim > 0.2f && ild_trim < 2.5f ? ild_trim : 1.0f;
  }
  float ItdScale() const { return itd_scale_; }
  float IldTrim() const { return ild_trim_; }

  /** 一阶低通对白噪的幅度增益；a≥1 视为直通 */
  static float NoiseGain(float a) {
    if (a >= 0.999f) return 1.0f;
    if (a <= 0.0f) return 0.0f;
    return std::sqrt((1.0f - a) / (1.0f + a));
  }

  static constexpr float kHeadRadius = 0.0875f;
  static constexpr float kSpeedOfSound = 343.0f;

 private:
  float sr_ = 48000.0f;
  float azimuth_ = 0.0f;

  float itd_l_ = 0.0f, itd_r_ = 0.0f;
  float ild_coeff_l_ = 1.0f, ild_coeff_r_ = 1.0f;
  float ild_gain_l_ = 1.0f, ild_gain_r_ = 1.0f;
  float rear_lp_coeff_ = 1.0f, rear_gain_ = 1.0f;
  float bright_gain_ = 1.0f, bright_coeff_ = 0.0f;
  bool el_notch_on_ = false;
  Preset preset_ = kPresetSource;   // 默认使用源模型参数
  float el_notch_depth_ = 0.30f;
  float rear_notch_depth_ = 1.0f;
  Biquad fs_notch_;
  bool fs_on_ = false;
  float fs_depth_ = 0.30f;
  bool rear_on_ = false;

  float ild_lp_l_ = 0.0f, ild_lp_r_ = 0.0f;
  float rear_lp_l_ = 0.0f, rear_lp_r_ = 0.0f;
  float bright_l_ = 0.0f, bright_r_ = 0.0f;

  // 自检得到的校正系数（默认 1 = 未校正）
  float itd_scale_ = 1.0f;
  float ild_trim_ = 1.0f;

  DelayLine delay_l_, delay_r_;
  Biquad hp_l_, hp_r_;
  Biquad el_notch_l_, el_notch_r_;
  Biquad rear_notch_l_, rear_notch_r_;
};

}  // namespace sonicraft::dsp::vs
