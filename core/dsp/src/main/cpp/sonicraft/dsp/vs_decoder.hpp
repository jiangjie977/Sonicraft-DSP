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
    for (int i = 0; i < 6; ++i) hp_main_[i].SetHighPass(80.0f, sr_);
  sur_dly_hp_l_.SetHighPass(250.0f, sr_);
  sur_dly_hp_r_.SetHighPass(250.0f, sr_);
  corr_alpha_ = 1.0f - std::exp(-1.0f / (0.05f * sr_));   // 50 ms 时间常数
  e_sum_ = e_diff_ = 0.0f;
  corr_ = 1.0f;
  e_sum_low_ = e_diff_low_ = 0.0f;
  corr_low_ = 1.0f;
    // 中置带通（人声带，Q≈1）
    center_bp_.b0 = 0.03830530494451523f;
    center_bp_.b1 = 0.07661060988903046f;
    center_bp_.b2 = 0.03830530494451523f;
    center_bp_.a1 = -1.3751437664031982f;
    center_bp_.a2 = 0.5283650159835815f;
    // 环绕：200 Hz 高通 + 6 kHz 低通。
    // 原实现是中心约 500 Hz 的窄带谐振，宽带素材(音乐/影视)落在该带内的能量很少，
    // 表现为"环绕非常弱"；放宽成宽频差分后既有乐器质感、电平也正常。
    sur_hp_l_.SetHighPass(200.0f, sr_);
    sur_hp_r_ = sur_hp_l_;
    {
      // 二阶低通（RBJ, Q=0.707）
      const float w = 2.0f * Biquad::kPi * 6000.0f / sr_;
      const float cw = std::cos(w);
      const float alpha = std::sin(w) / 1.41421356f;
      const float a0 = 1.0f + alpha;
      const float b0 = (1.0f - cw) * 0.5f / a0;
      const float b1 = (1.0f - cw) / a0;
      const float b2 = b0;
      const float a1 = (-2.0f * cw) / a0;
      const float a2 = (1.0f - alpha) / a0;
      sur_lp_l_.b0 = b0; sur_lp_l_.b1 = b1; sur_lp_l_.b2 = b2;
      sur_lp_l_.a1 = a1; sur_lp_l_.a2 = a2;
      sur_lp_r_ = sur_lp_l_;
    }
    Reset();
  }

  void Reset() {
    lp_l_.Reset();
    lp_r_.Reset();
    center_bp_.Reset();
    for (int i = 0; i < 6; ++i) hp_main_[i].Reset();
    for (int i = 0; i < kCsDelaySize; ++i) cs_delay_[i] = 0.0f;
    cs_pos_ = 0;
    sur_hp_l_.Reset();
    sur_hp_r_.Reset();
    sur_lp_l_.Reset();
    sur_lp_r_.Reset();
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

  /** 解码模式：0=Cine6.1（含后中置）1=LCRS（无后中置）2=Stereo 3=Mono */
  void SetDecodeMode(int mode) { decode_mode_ = mode < 0 ? 0 : (mode > 3 ? 3 : mode); }
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
  /** BS.775 等响：环绕矩阵系数在 0.7008（现基准）与 2.487（+11 dB）之间切换 */
  void SetBs775(bool on) { sur_matrix_target_ = on ? 1.5849f : 0.7008f; }

  /** 瞬态保护开关（默认开）：关闭后退回固定深度去相关，便于对比 */
  void SetTransientProtect(bool on) { trans_protect_ = on; }

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
    // 中置：只取人声频段（带通），避免整条频谱的乐器一起灌进中置
    const float sum = (in_l + in_r) * 0.5f;
    // 中置源（带通后、未加增益）：既用于 C 声道，也用于前置的等量扣除
    const float c_src = center_bp_.Process(sum);
    // C 只比原基准低 1 dB（原基准 0.9118 → 0.8127），补位自然、不抢
    float c = c_src * 0.8126547f * center_gain_;

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
    // 环绕：差分先缩到半幅，再各自过带通 —— 频段外的东西不会串到侧后，左右分离更干净
    // 不再先缩半：差分本身已比单声道低，再乘 0.5 会让环绕掉 6 dB（实测偏低 15.6 dB）
    const float diff = in_l - in_r;

    // 相关性估计：用于「侧向混入」的自适应加权（居中不混、宽声场才混）
    {
      const float e_s = sum * sum;
      const float e_d = diff * diff;
      e_sum_ += corr_alpha_ * (e_s - e_sum_);
      e_diff_ += corr_alpha_ * (e_d - e_diff_);
      if (e_sum_ > 1e-9f) {
        const float kk = e_diff_ / e_sum_;
        const float rho = Clamp((4.0f - kk) / (4.0f + kk), -1.0f, 1.0f);
        corr_ += corr_alpha_ * (rho - corr_);
      }
    }
    // **双频段相关性**：低频段单独估计。低频与高频的方向性常不一致
    // （贝斯居中、镲片在两侧），单一全频段 ρ 会把混合判成中间态。
    {
      const float s_low = (lp_l + lp_r) * 0.5f;
      const float d_low = lp_l - lp_r;
      e_sum_low_ += corr_alpha_ * (s_low * s_low - e_sum_low_);
      e_diff_low_ += corr_alpha_ * (d_low * d_low - e_diff_low_);
      if (e_sum_low_ > 1e-9f) {
        const float kl = e_diff_low_ / e_sum_low_;
        const float rho_l = Clamp((4.0f - kl) / (4.0f + kl), -1.0f, 1.0f);
        corr_low_ += corr_alpha_ * (rho_l - corr_low_);
      }
    }
    // 宽频差分：高通 → 低通，再按 0.6094 与滤波损耗补偿
    // 环绕矩阵系数平滑推进：开关切换时不能阶跃（原实现 0.7008 <-> 2.487 直接赋值，
    // 相差 11 dB，切换瞬间就是"咔"一声爆音，并把环绕推到过载产生毛刺）。
    sur_matrix_ += kSurMatrixSmooth * (sur_matrix_target_ - sur_matrix_);
    float sl = sur_lp_l_.Process(sur_hp_l_.Process(diff)) * sur_matrix_ * surround_gain_;
    float sr = sur_lp_r_.Process(sur_hp_r_.Process(-diff)) * sur_matrix_ * surround_gain_;
    // steering 仅用于"前置主导时抑制环绕"，不再决定左右分配
    float front_dom = std::fabs(steer_low);
    if (front_dom > 1.0f) front_dom = 1.0f;
    const float surround_damp = 1.0f - 0.35f * front_dom;
    sl *= surround_damp;
    sr *= surround_damp;

    // 混入**同侧**主声道：纯差分环绕（SL=L−R、SR=R−L）左右两侧承载的是同一个信号，
    // 双耳渲染后听起来"后方就一团、贴在中间"（单声道感）。各自混入本侧内容后，
    // 左后方才带左声道信息、右后方带右声道信息，后方才有左右分布。
    // 侧向混入：**按相关性加权**。原实现固定 0.25，导致居中人声也被混进环绕
    // （实测人声 SL/SR 仅 −0.4 dB，几乎全额泄漏）。改为 (1−ρ)/2：
    //   ρ=+1 居中 → 0（不混，人声不糊到后方）
    //   ρ=0  不相关 → 0.5（半量，保留宽声场的后方立体感）
    //   ρ=−1 反相 → 1.0（全量）
    {
      // 低频门控（平滑）：低频居中时禁止侧向混入，避免贝斯/底鼓被抹到后方
  const float low_gate = Clamp((0.7f - corr_low_) / 0.4f, 0.0f, 1.0f);
  const float side_mix = kSurroundSideMix * (1.0f - corr_) * 0.5f * low_gate;
      sl += in_l * side_mix;
      sr += in_r * side_mix;
    }

    // ---- 前置：扣掉已进中置的部分（避免重复计入）----
    float cs = 0.0f;
    // 前置把中置频段**整量**扣掉：单听 FL/FR 时中间应出现明显空洞，
    // 只有补上 C 才填平 —— 这正是三声道该有的分离感。
    float fl = (in_l - c_src) * front_gain_;
    float fr = (in_r - c_src) * front_gain_;

    // ---- 后中置：环绕之和 ----
    // 后中置：两侧环绕互为反相（线性滤波），直接相加恒为 0 ——
    // 因此取"左环绕延迟 10 ms + 右环绕"，制造去相关后才会有真实的后方中线信号。
    cs_delay_[cs_pos_] = sl;
    {
      const int read = (cs_pos_ - 480 + kCsDelaySize) & (kCsDelaySize - 1);   // 10 ms @48k
      float cs_src = (cs_delay_[read] + sr) * 0.5f;
      if (decode_mode_ != 0) cs_src = 0.0f;   // 只有 Cine6.1 产生后中置
      cs = cs_src * 0.9831886887550354f * rear_gain_;
    }
    cs_pos_ = (cs_pos_ + 1) & (kCsDelaySize - 1);

    // ---- LFE ----
    const float lfe_l = lfe_l_.Process(in_l);
    const float lfe_r = lfe_r_.Process(in_r);
    const float lfe = (lfe_l + lfe_r) * kLfeScale * lfe_gain_;

    // ---- 环绕瞬态检测（必须用**未平滑**的信号）----
    // 倾斜滤波与全通链都会抹平瞬态前沿（听感是鼓点"软、糊"），
    // 所以检测要放在两者之前，检测结果同时用于抑制倾斜与去相关。
    // 用包络二阶差分近似上升沿：只有"变化在加速"才算瞬态（稳态/衰减段不计入）。
    {
      const float env = std::fabs(sl) + std::fabs(sr);
      const float d1 = std::fabs(env - trans_prev_);
      const float d2 = std::fabs(trans_prev_ - trans_prev2_);
      trans_prev2_ = trans_prev_;
      trans_prev_ = env;
      const float onset = d1 > d2 ? (d1 - d2) : 0.0f;
      const float target = Clamp(onset * kTransOnsetGain, 0.0f, 1.0f);
      // 攻击快（约 2 个样本到 75%）、释放慢（约 60 ms），避免逐样本抖动
      const float coef = target > trans_gain_ ? kTransAttack
                                              : (1.0f / (kTransReleaseSec * sr_));
      trans_gain_ += (target - trans_gain_) * coef;
    }
    // 瞬态抑制系数（0 = 稳态、1 = 强瞬态）
    const float trans_on = trans_protect_ ? trans_gain_ : 0.0f;

    // ---- 中置/环绕微倾斜（一阶平滑，避免中置过于"贴脸"）----
    center_tilt_ = c * 0.3f + center_tilt_ * 0.7f;
    c = c + center_tilt_ * 0.2f;
    surr_tilt_l_ = sl * 0.25f + surr_tilt_l_ * 0.75f;
    surr_tilt_r_ = sr * 0.25f + surr_tilt_r_ * 0.75f;
    // 倾斜量随瞬态降低（瞬态时直接输出原信号，保住攻击前沿）
    const float tilt_l_amt = 0.2f * (1.0f - trans_on);
    const float tilt_r_amt = 0.2f * (1.0f - trans_on);
    sl = sl * (1.0f - tilt_l_amt) + surr_tilt_l_ * tilt_l_amt;
    sr = sr * (1.0f - tilt_r_amt) + surr_tilt_r_ * tilt_r_amt;

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
      // 瞬态段把去相关深度压到 30%（kTransDepth=0.7），保住攻击前沿；
      // 稳态仍按 rear_space_ 全量去相关，包围感不受影响。
      const float w = rear_space_ * (1.0f - kTransDepth * trans_on);
      sl = dry_l * (1.0f - w) + wet_l * w;
      sr = dry_r * (1.0f - w) + wet_r * w;
    }

    // 解码模式覆盖
    if (decode_mode_ == 2) {           // Stereo：只保留左右
      fl = in_l;
      fr = in_r;
      c = 0.0f;
      sl = sr = cs = 0.0f;
    } else if (decode_mode_ == 3) {    // Mono：双耳同源
      const float m = (in_l + in_r) * 0.5f;
      fl = fr = m;
      c = 0.0f;
      sl = sr = cs = 0.0f;
    } else if (decode_mode_ == 1) {    // LCRS：无后中置
      cs = 0.0f;
    }

    // ---- 环绕延迟（Dolby 惯例 10~20 ms）----
    // 侧后方与前置完全同相时会"贴在一起"，缺乏距离感。给 SL/SR 加约 12 ms 延迟，
    // 让后方听起来来自更远处；CS 保持自身的 10 ms 延迟，不叠加（避免后中置过晚）。
    {
      // **分频延迟**：只延迟 250 Hz 以上，低频直通。
      // 依据：低频定位主要靠 ILD 与相位差，整体延迟会打乱低频方向感；
      // 而扩散感来自高频的时延散布。早期定制版 的 DiffSurroundingEffect 也是这个做法。
      const float sl_hi = sur_dly_hp_l_.Process(sl);
      const float sl_lo = sl - sl_hi;
      const float sr_hi = sur_dly_hp_r_.Process(sr);
      const float sr_lo = sr - sr_hi;
      sur_dly_l_[sur_dly_pos_] = sl_hi;
      sur_dly_r_[sur_dly_pos_] = sr_hi;
      const int read = (sur_dly_pos_ - kSurDelay + kSurDelaySize) & (kSurDelaySize - 1);
      sl = sur_dly_l_[read] + sl_lo;
      sr = sur_dly_r_[read] + sr_lo;
      sur_dly_pos_ = (sur_dly_pos_ + 1) & (kSurDelaySize - 1);
    }

    // 低频管理（解码端）：6 条主声道 80 Hz 高通，与 LFE 低通互补
    fl = hp_main_[0].Process(fl);
    fr = hp_main_[1].Process(fr);
    c = hp_main_[2].Process(c);
    sl = hp_main_[3].Process(sl);
    sr = hp_main_[4].Process(sr);
    cs = hp_main_[5].Process(cs);

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
  // LFE 规范电平：相对主声道带内 +10 dB（0.8 × 3.1623）
  static constexpr float kLfeScale = 2.53f;

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
  float sur_matrix_target_ = 0.7008f;   // 目标值（由 BS.775 开关设定）
  // 环绕延迟：12 ms @48k = 576 样本；缓冲取 2 的幂便于位与取模
  static constexpr int kSurDelay = 576;
  static constexpr int kSurDelaySize = 2048;
  Biquad sur_dly_hp_l_, sur_dly_hp_r_;    // 250 Hz 分频高通（延迟支路）
  float sur_dly_l_[kSurDelaySize]{};
  float sur_dly_r_[kSurDelaySize]{};
  int sur_dly_pos_ = 0;

  static constexpr float kSurroundSideMix = 0.25f;   // 同侧主声道混入上限（0~0.5）
  // 相关性估计（50 ms）：k = E[diff²]/E[sum²] → ρ = (4−k)/(4+k)
  // 居中 k=0 → ρ=1；不相关 k=4 → ρ=0；反相 k→∞ → ρ=−1
  float e_sum_ = 0.0f;
  float e_diff_ = 0.0f;
  float corr_ = 1.0f;
  float corr_low_ = 1.0f;   // 低频段相关性（双频段方向控制）
  float e_sum_low_ = 0.0f;
  float e_diff_low_ = 0.0f;    // 初值取 1（按居中处理）：首帧不混入，避免开头泄漏人声
  float corr_alpha_ = 0.000417f;
  static constexpr float kSurMatrixSmooth = 0.004f;   // ≈5 ms 时间常数 @48k
  float sur_matrix_ = 0.7008f;

  LowPass4 lp_l_, lp_r_;
  // 分频段矩阵：中置与环绕各自先过带通，只取本声道的频段
  Biquad center_bp_;
  // 环绕：200 Hz 高通 + 6 kHz 低通（宽频差分成份），取代原来的窄带谐振
  Biquad sur_hp_l_, sur_hp_r_, sur_lp_l_, sur_lp_r_;
  // 主声道 80 Hz 高通：与 LFE 低通互补，避免低频重复叠加
  Biquad hp_main_[6];
  int decode_mode_ = 0;                       // 0=Cine6.1 1=LCRS 2=Stereo 3=Mono
  static constexpr int kCsDelaySize = 1024;   // 20 ms @48k
  float cs_delay_[kCsDelaySize]{};
  int cs_pos_ = 0;
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

  // 瞬态保护参数：上升沿归一化系数 / 攻击系数 / 释放时间 / 去相关压制深度
  static constexpr float kTransOnsetGain = 9.0f;
  static constexpr float kTransAttack = 0.75f;
  static constexpr float kTransReleaseSec = 0.06f;
  static constexpr float kTransDepth = 1.0f;   // 瞬态时完全关闭去相关
  float trans_prev_ = 0.0f;
  float trans_prev2_ = 0.0f;
  float trans_gain_ = 0.0f;
  bool trans_protect_ = true;
  int steer_tick_ = 0;
};

}  // namespace sonicraft::dsp::vs
