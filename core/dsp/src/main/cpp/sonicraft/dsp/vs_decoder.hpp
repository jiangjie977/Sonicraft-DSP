/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>

#include "sonicraft/dsp/vs_hrtf.hpp"
#include "sonicraft/dsp/vs_params.hpp"

namespace sonicraft::dsp::vs {

/** 二阶低通（RBJ，Q=0.7071），两级串联构成 4 阶 */
struct LowPass2 {
  float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
  float z1 = 0.0f, z2 = 0.0f;

  void Set(float fc, float sr) {
    const float w = 2.0f * Biquad::kPi * fc / sr;
    const float cw = std::cos(w);
    const float alpha = std::sin(w) / (2.0f * 0.7071f);
    const float a0 = 1.0f + alpha;
    b0 = (1.0f - cw) * 0.5f / a0;
    b1 = (1.0f - cw) / a0;
    b2 = b0;
    a1 = (-2.0f * cw) / a0;
    a2 = (1.0f - alpha) / a0;
  }
  inline float Process(float x) {
    const float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
  void Reset() { z1 = z2 = 0.0f; }
};

/** 两级串联的低通（4 阶），用于 2kHz 分频与 80Hz LFE */
struct LowPass4 {
  LowPass2 s1, s2;
  void Set(float fc, float sr) {
    s1.Set(fc, sr);
    s2.Set(fc, sr);
  }
  inline float Process(float x) { return s2.Process(s1.Process(x)); }
  void Reset() {
    s1.Reset();
    s2.Reset();
  }
};

/** 一阶全通（用于环绕去相关；只改相位不改幅度） */
struct AllPass1 {
  float coeff = 0.0f;
  float x1 = 0.0f, y1 = 0.0f;
  inline float Process(float x) {
    const float y = coeff * x + x1 - coeff * y1;
    x1 = x;
    y1 = y;
    return y;
  }
  void Reset() { x1 = y1 = 0.0f; }
};

/**
 * 立体声 → 7 声道矩阵解码。
 *
 * 分工：
 *  - **2 kHz 分频**：低频用较慢的 steering（避免低频声像抖动），高频用较快的；
 *  - **相干度分析**：中置用 (L+R)，环绕用 (L−R)；用「包络对数差」估计中置/环绕主导度；
 *  - **非对称 steering**：声源偏一侧时，同侧前声道向中置靠、同侧环绕向后靠；
 *  - **LFE**：80 Hz 四阶低通后按 −1.9 dB 混入（不入 HRTF）；
 *  - **后中置 CS**：取环绕声道之和，供后处理级加延迟/全通。
 */
class StereoSpatialDecoder {
 public:
  void Prepare(float sample_rate) {
    sr_ = sample_rate > 0.0f ? sample_rate : 48000.0f;
    lp_l_.Set(kCrossoverHz, sr_);
    lp_r_.Set(kCrossoverHz, sr_);
    lfe_l_.Set(kLfeHz, sr_);
    lfe_r_.Set(kLfeHz, sr_);
    env_alpha_ = 1.0f - std::exp(-2.0f * Biquad::kPi * 50.0f / sr_);
    Reset();
  }

  void Reset() {
    lp_l_.Reset();
    lp_r_.Reset();
    lfe_l_.Reset();
    lfe_r_.Reset();
    level_low_l_ = level_low_r_ = 0.0f;
    level_high_l_ = level_high_r_ = 0.0f;
    dir_low_ = dir_high_ = 0.0f;
    log_level_l_ = log_level_r_ = 0.0f;
    log_level_s_ = log_level_d_ = 0.0f;
    prev_diff_ = 0.0f;
    fs_ls_ = fs_lx_ = fs_rs_ = fs_rx_ = 0.0f;
    fine_bias_ = 0.0f;
    center_tilt_ = 0.0f;
    surr_tilt_l_ = surr_tilt_r_ = 0.0f;
    for (int i = 0; i < 5; ++i) {
      ap_l_[i].Reset();
      ap_r_[i].Reset();
    }
    steer_tick_ = 0;
  }

  void SetMode(bool movie) { movie_ = movie; }
  /**
   * @param rear_db 后中置（CS）增益。此前 CS 只有固定 ms_gain，参数表里的
   *                rear_gain 一直是死参数 —— 这里补上。
   */
  void SetGains(float center_db, float surround_db, float lfe_db) {
    center_gain_ = DbToGain(center_db);
    surround_gain_ = DbToGain(surround_db);
    lfe_gain_ = DbToGain(lfe_db);
  }

  void SetRearGain(float rear_db) { rear_gain_ = DbToGain(rear_db); }

  /**
   * 后环绕空间感（0~1）：控制环绕声道的**去相关深度**。
   *
   * 0 = 全通链完全旁路，SL/SR 与原差分信号同相（贴耳、无包围感）；
   * 1 = 完全走 5 级全通（相位最散，包围感最强，但过大时后方会"糊"）。
   * 中间值按比例混合，因此这是一个连续的"空间感"旋钮，而不是开关。
   */
  void SetRearSpace(float amount) {
    rear_space_ = amount < 0.0f ? 0.0f : (amount > 1.0f ? 1.0f : amount);
  }

  /**
   * 前场补偿：只作用于 FL/FR。
   *
   * 空间化后前端常显得变薄（能量被中置与环绕分走），这里给前置一个独立补偿量。
   * 注意它**不是**总增益：改它不会动中置与环绕，也不影响 LFE。
   */
  void SetFrontGain(float front_db) { front_gain_ = DbToGain(front_db); }

  /** 单样本解码；out 长度为 7（FL/FR/C/SL/SR/CS/LFE） */
  void ProcessSample(float in_l, float in_r, float* out) {
    if (!std::isfinite(in_l)) in_l = 0.0f;
    if (!std::isfinite(in_r)) in_r = 0.0f;

    const float lp_l = lp_l_.Process(in_l);
    const float lp_r = lp_r_.Process(in_r);
    const float hp_l = in_l - lp_l;
    const float hp_r = in_r - lp_r;

    if ((steer_tick_++ & 0x3f) == 0) UpdateSteering(lp_l, lp_r, hp_l, hp_r, in_l, in_r);

    // ---- 中置 ----
    const float center_input_gain = movie_ ? 0.5011872f : 0.35f;  // −6 dB / −9 dB
    float c = (in_l + in_r) * center_input_gain * center_gain_;

    // ---- 细粒度 bias（高频包络差 → 左右偏置）----
    const float fine_left = fs_lx_ + fs_rx_;
    const float fine_right = fs_ls_ + fs_rs_;
    const float fine_total = fine_left + fine_right;
    float bias = fine_total > 1e-6f ? (fine_right - fine_left) / fine_total : 0.0f;
    if (bias > 1.0f) bias = 1.0f;
    if (bias < -1.0f) bias = -1.0f;
    fine_bias_ += 0.008f * (bias - fine_bias_);

    const float mix = 0.5f;
    const float steer_low = dir_low_ * (1.0f - mix) + fine_bias_ * mix;
    const float steer_high = dir_high_ * (1.0f - mix) + fine_bias_ * mix;

    // ---- 环绕：由 (L−R) 按 steering 分配到左右 ----
    const float diff = in_l - in_r;
    const float sl_low = diff * (0.5f + steer_low * 0.5f);
    const float sr_low = diff * (0.5f - steer_low * 0.5f);
    const float sl_high = diff * (0.5f + steer_high * 0.5f);
    const float sr_high = diff * (0.5f - steer_high * 0.5f);

    float front_dom = std::fabs(steer_low);
    if (front_dom > 1.0f) front_dom = 1.0f;
    const float surround_damp = 1.0f - 0.7f * front_dom;
    float sl = (sl_low + sl_high) * 0.5f * surround_gain_ * kSurroundLimit * surround_damp;
    float sr = (sr_low + sr_high) * 0.5f * surround_gain_ * kSurroundLimit * surround_damp;
    if (!movie_) {
      sl *= 0.9f;
      sr *= 0.9f;
    }

    // ---- 前置：扣掉已进中置的部分（避免重复计入）----
    float fl = (in_l - c * 0.5f) * front_gain_;
    float fr = (in_r - c * 0.5f) * front_gain_;

    // ---- 后中置：环绕之和 ----
    const float ms_gain = movie_ ? 0.25f : 0.15f;
    const float cs = (sl + sr) * ms_gain * rear_gain_;

    // ---- LFE ----
    const float lfe_l = lfe_l_.Process(in_l);
    const float lfe_r = lfe_r_.Process(in_r);
    const float lfe = (lfe_l + lfe_r) * kLfeScale * lfe_gain_;

    // ---- 中置/环绕微倾斜（一阶平滑，避免中置过于"贴脸"）----
    center_tilt_ = c * 0.3f + center_tilt_ * 0.7f;
    c = c + center_tilt_ * 0.2f;
    surr_tilt_l_ = sl * 0.25f + surr_tilt_l_ * 0.75f;
    sl = sl * 0.8f + surr_tilt_l_ * 0.2f;
    surr_tilt_r_ = sr * 0.25f + surr_tilt_r_ * 0.75f;
    sr = sr * 0.8f + surr_tilt_r_ * 0.2f;

    // ---- 环绕去相关：5 级一阶全通（左右系数反号）----
    static constexpr float kAp[5] = {-0.6f, 0.5f, -0.4f, 0.3f, -0.25f};
    {
      const float dry_l = sl, dry_r = sr;
      float wet_l = sl, wet_r = sr;
      for (int i = 0; i < 5; ++i) {
        ap_l_[i].coeff = kAp[i];
        ap_r_[i].coeff = -kAp[i];
        wet_l = ap_l_[i].Process(wet_l);
        wet_r = ap_r_[i].Process(wet_r);
      }
      // 注意：全通链即使 wet=0 也必须继续跑，否则切回时状态是旧的（会「啵」一声）
      const float w = rear_space_;
      sl = dry_l * (1.0f - w) + wet_l * w;
      sr = dry_r * (1.0f - w) + wet_r * w;
    }

    out[kFL] = fl;
    out[kFR] = fr;
    out[kC] = c;
    out[kSL] = sl;
    out[kSR] = sr;
    out[kCS] = cs;
    out[kLFE] = lfe;
  }

  /** 最近一次的 steering（正 = 声源偏右），供动态方位角使用 */
  float steer_low() const { return dir_low_; }
  float steer_high() const { return dir_high_; }

  static constexpr float kCrossoverHz = 2000.0f;
  static constexpr float kLfeHz = 80.0f;
  static constexpr float kSurroundLimit = 0.5623413f;  // −5 dB
  static constexpr float kLfeScale = 0.8f;             // −1.9 dB

 private:
  static float DbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

  void UpdateSteering(float lp_l, float lp_r, float hp_l, float hp_r, float in_l, float in_r) {
    level_low_l_ += env_alpha_ * (std::fabs(lp_l) - level_low_l_);
    level_low_r_ += env_alpha_ * (std::fabs(lp_r) - level_low_r_);
    const float denom_low = level_low_l_ + level_low_r_;
    const float steer_alpha = movie_ ? 0.02f : 0.005f;
    const float steer_limit = movie_ ? 1.2f : 0.8f;

    float raw_low = denom_low > 1e-6f ? (level_low_l_ - level_low_r_) / denom_low : 0.0f;
    dir_low_ += steer_alpha * (raw_low - dir_low_);
    dir_low_ = Clamp(dir_low_, -steer_limit, steer_limit);

    level_high_l_ += env_alpha_ * (std::fabs(hp_l) - level_high_l_);
    level_high_r_ += env_alpha_ * (std::fabs(hp_r) - level_high_r_);
    const float denom_high = level_high_l_ + level_high_r_;
    float raw_high = denom_high > 1e-6f ? (level_high_l_ - level_high_r_) / denom_high : 0.0f;
    dir_high_ += steer_alpha * (raw_high - dir_high_);
    dir_high_ = Clamp(dir_high_, -steer_limit, steer_limit);

    // 中置/环绕主导度：左右包络对数差 + 差分信号的一阶差分包络之比
    const float log_alpha = 0.05f;
    log_level_l_ += log_alpha * (std::fabs(in_l) - log_level_l_);
    log_level_r_ += log_alpha * (std::fabs(in_r) - log_level_r_);
    const float diff = in_l - in_r;
    const float diff_hp = diff - prev_diff_;
    prev_diff_ = diff;
    log_level_s_ += log_alpha * (std::fabs(diff) * 0.5f - log_level_s_);
    log_level_d_ += log_alpha * (std::fabs(diff_hp) * 0.5f - log_level_d_);

    constexpr float kLogEps = 1e-8f;
    const float lr_diff = std::log2((log_level_l_ + kLogEps) / (log_level_r_ + kLogEps));
    const float slsr_diff = std::log2((log_level_s_ + kLogEps) / (log_level_d_ + kLogEps));

    const float fs_k = movie_ ? 3.5f : 2.5f;
    const float fs_limit = movie_ ? 6.0f : 5.0f;
    float ls = std::pow(2.0f, -lr_diff * fs_k * 0.5f - slsr_diff * fs_k * 0.3f);
    float lx = std::pow(2.0f, lr_diff * fs_k * 0.5f - slsr_diff * fs_k * 0.3f);
    float rs = std::pow(2.0f, -lr_diff * fs_k * 0.5f + slsr_diff * fs_k * 0.3f);
    float rx = std::pow(2.0f, lr_diff * fs_k * 0.5f + slsr_diff * fs_k * 0.3f);
    ls = Clamp(ls, 0.0f, fs_limit) * 0.5f;
    lx = Clamp(lx, 0.0f, fs_limit) * 0.5f;
    rs = Clamp(rs, 0.0f, fs_limit) * 0.5f;
    rx = Clamp(rx, 0.0f, fs_limit) * 0.5f;

    const float fs_smooth = movie_ ? 0.01f : 0.004f;
    fs_ls_ += fs_smooth * (ls - fs_ls_);
    fs_lx_ += fs_smooth * (lx - fs_lx_);
    fs_rs_ += fs_smooth * (rs - fs_rs_);
    fs_rx_ += fs_smooth * (rx - fs_rx_);
  }

  static float Clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

  float sr_ = 48000.0f;
  bool movie_ = false;
  float center_gain_ = 1.0f, surround_gain_ = 1.0f, lfe_gain_ = 1.0f;
  float front_gain_ = 1.0f;
  float rear_gain_ = 1.0f;
  float rear_space_ = 0.5f;

  LowPass4 lp_l_, lp_r_;
  LowPass4 lfe_l_, lfe_r_;

  float env_alpha_ = 0.0f;
  float level_low_l_ = 0.0f, level_low_r_ = 0.0f;
  float level_high_l_ = 0.0f, level_high_r_ = 0.0f;
  float dir_low_ = 0.0f, dir_high_ = 0.0f;

  float log_level_l_ = 0.0f, log_level_r_ = 0.0f;
  float log_level_s_ = 0.0f, log_level_d_ = 0.0f;
  float prev_diff_ = 0.0f;
  float fs_ls_ = 0.0f, fs_lx_ = 0.0f, fs_rs_ = 0.0f, fs_rx_ = 0.0f;
  float fine_bias_ = 0.0f;

  float center_tilt_ = 0.0f, surr_tilt_l_ = 0.0f, surr_tilt_r_ = 0.0f;
  AllPass1 ap_l_[5], ap_r_[5];
  int steer_tick_ = 0;
};

}  // namespace sonicraft::dsp::vs
