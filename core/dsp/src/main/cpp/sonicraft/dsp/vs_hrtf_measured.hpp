/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 实测 HRIR 双耳渲染器（557 tap 时域卷积）。
 *
 * 与 ParametricHrtf 的关系：接口完全一致（Prepare/Reset/Configure/ProcessSample/
 * ItdSamples/TheoreticalIldDb/SetCalibration），所以定位自检、12 路渲染、
 * 校正写回都可以原地切换，无需改动调用方。
 *
 * 与参数化模型的差别：
 *   - 时延与耳廓谱是**测出来的**（IR 自带），不再靠 Woodworth + 陷波模拟；
 *   - 因此 ITD 不需要缩放校正（ItdScale 恒为 1），只保留 ILD 修调；
 *   - 仰角（数据集只有水平面）：在实测 IR 之后叠一层仰角线索滤波
 *     （耳廓陷波上移 + 高频亮度），否则上方 4 只扬声器会和平面对应方向同音。
 */
#pragma once

#include <cmath>

#include "sonicraft/dsp/hrir_bank.hpp"

namespace sonicraft::dsp::vs {

class MeasuredHrtf {
 public:
  static constexpr int kMaxTaps = HrirBank::kMaxTaps;
  static constexpr int kRingSize = 1024;   // ≥ kMaxTaps，且为 2 的幂
  static constexpr int kRingMask = kRingSize - 1;

  void Prepare(float sample_rate) {
    sample_rate_ = sample_rate > 0.0f ? sample_rate : 48000.0f;
    Reset();
  }

  void Reset() {
    for (int i = 0; i < kRingSize; ++i) hist_[i] = 0.0f;
    pos_ = 0;
    lp_l_ = lp_r_ = 0.0f;
    shadow_l_.Reset();
    shadow_r_.Reset();
    notch_z1_l_ = notch_z1_r_ = notch_z2_l_ = notch_z2_r_ = 0.0f;
  }

  /** 0 = 无实测数据；1..5 = 数据集编号（见 HrirBank::ModelName） */
  void SetModel(int model) {
    if (model == model_) return;
    model_ = HrirBank::IsValidModel(model) ? model : 0;
    Configure(az_deg_, el_deg_);   // 模型变了要重新装载 IR
  }

  int Model() const { return model_; }

  void Configure(float azimuth_deg, float elevation_deg) {
    az_deg_ = azimuth_deg;
    el_deg_ = elevation_deg;
    // 仰角优先用 CIPIC 的真实仰角 IR；取不到才回到水平面数据 + 陷波模拟
    HrirPair ir = HrirBank::LookupElevation(azimuth_deg, elevation_deg);
    const bool measured_elev = ir.valid();
    if (!measured_elev) {
      ir = HrirBank::Lookup(model_, azimuth_deg);
    }
    ir_l_ = ir.l;
    ir_r_ = ir.r;
    taps_ = ir.taps;

    // 峰值归一化：不同数据集的录制电平差很多，不归一会切模型就变音量
    float peak = 0.0f;
    for (int i = 0; i < taps_; ++i) {
      peak = std::fmax(peak, std::fabs(ir_l_[i]));
      peak = std::fmax(peak, std::fabs(ir_r_[i]));
    }
    ir_scale_ = peak > 1e-6f ? 1.0f / peak : 1.0f;

    itd_samples_ = -HrirBank::MeasuredItdSamples(ir);   // 本项目约定：正 = 右耳先行
    // 数据集把左右耳各自归一化过（实测 ILD 恒为 0），直接用会导致侧向定位只靠 ITD，
    // 听感"扁"。这里补一层头影：按方位给近耳加高频、远耳减高频，生成的目标 ILD 写进
    // ild_target_db_，自检的期望值随之对齐（否则自检会拿 0 dB 当目标，等于不校验）。
    const float data_ild = HrirBank::MeasuredIldDb(ir);
    // 数据自带足够 ILD（CIPIC 仰角集为 ±10~15 dB）时不再补头影，否则会重复补偿；
    // 水平面那 5 套数据的 ILD 被归一化成 0，才需要补。
    const bool need_shadow = std::fabs(data_ild) < 1.0f;
    const float shadow_ild = need_shadow ? ConfigureHeadShadow(azimuth_deg) : 0.0f;
    if (!need_shadow) {
      shadow_active_ = false;
      shadow_l_.Reset();
      shadow_r_.Reset();
    }
    ild_target_db_ = data_ild + shadow_ild;

    // 有实测仰角 IR 时不需要再"假装"仰角（否则会双重染色）
    ConfigureElevationCues(measured_elev ? 0.0f : elevation_deg);
  }

  inline void ProcessSample(float x, float* out_l, float* out_r) {
    hist_[pos_] = x;
    float l = 0.0f;
    float r = 0.0f;
    if (taps_ > 0) {
      const float* hl = ir_l_;
      const float* hr = ir_r_;
      int idx = pos_;
      for (int k = 0; k < taps_; ++k) {
        const float s = hist_[idx];
        l += hl[k] * s;
        r += hr[k] * s;
        idx = (idx - 1) & kRingMask;
      }
      l *= ir_scale_;
      r *= ir_scale_;
    }
    pos_ = (pos_ + 1) & kRingMask;

    // 仰角线索：耳廓陷波上移 + 高频亮度（水平面不参与，避免改变现有音色）
    if (el_active_) {
      l = ApplyElevation(l, true);
      r = ApplyElevation(r, false);
    }

    // ILD 修调：与参数化模型同口径（左 × trim，右 ÷ trim）
    // 头影：近耳高频略提、远耳略降（能量近似守恒，不改整体响度）
    if (shadow_active_) {
      l = ApplyShelf(l, shadow_l_);
      r = ApplyShelf(r, shadow_r_);
    }

    *out_l = l * ild_trim_;
    *out_r = r / ild_trim_;
  }

  /** IR 自带的 ITD（已含本项目符号约定） */
  float ItdSamples() const { return itd_samples_; }
  /** 实测 IR 不需要时间校正，恒为 1 */
  float ItdScale() const { return 1.0f; }
  /** IR 自带的 ILD 作为目标值（dB，正 = 左耳更响） */
  float TheoreticalIldDb() const { return ild_target_db_; }

  void SetCalibration(float itd_scale, float ild_trim) {
    (void)itd_scale;   // 实测 IR 不做时间缩放：IR 的时延来自真实测量
    ild_trim_ = ild_trim > 0.2f && ild_trim < 2.5f ? ild_trim : 1.0f;
  }
  float IldTrim() const { return ild_trim_; }

 private:
  // ---- 头影（head shadow）：补数据集缺失的 ILD ----
  //
  // 真实头部对侧耳在高频会被遮挡（1.5 kHz 以上开始，8 kHz 可达 15~20 dB）。
  // 这 5 套数据的左右耳能量被归一化成相等，ILD 恒为 0，只能靠 ITD 判左右，
  // 侧向"偏一侧"的感觉会明显偏弱。这里用一对高阶架（high-shelf）补回来：
  //   近耳 +kHeadShadowMaxDb/2，远耳 −kHeadShadowMaxDb/2
  // 取对半分配是为了**总能量近似不变**（一个 +3 dB 一个 −3 dB）。
  static constexpr float kPi_ = 3.14159265358979f;
  static constexpr float kHeadShadowMaxDb = 6.0f;   // 90° 处的总 ILD（±3 dB）

  struct Shelf {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;
    void Reset() { z1 = z2 = 0.0f; }
    bool bypass() const { return b1 == 0.0f && b2 == 0.0f && a1 == 0.0f && a2 == 0.0f; }
  };

  static void SetHighShelf(Shelf* s, float gain_db, float fc, float fs) {
    if (std::fabs(gain_db) < 0.05f) {
      s->b0 = 1.0f;
      s->b1 = s->b2 = s->a1 = s->a2 = 0.0f;
      return;
    }
    const float A = std::pow(10.0f, gain_db / 40.0f);
    const float w0 = 2.0f * kPi_ * fc / fs;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float alpha = sw * 0.5f * std::sqrt((A + 1.0f / A) * (1.0f / 1.0f - 1.0f) + 2.0f);
    const float two_sqrtA_alpha = 2.0f * std::sqrt(A) * alpha;
    const float a0 = (A + 1.0f) - (A - 1.0f) * cw + two_sqrtA_alpha;
    s->b0 = (A * ((A + 1.0f) + (A - 1.0f) * cw + two_sqrtA_alpha)) / a0;
    s->b1 = (-2.0f * A * ((A - 1.0f) + (A + 1.0f) * cw)) / a0;
    s->b2 = (A * ((A + 1.0f) + (A - 1.0f) * cw - two_sqrtA_alpha)) / a0;
    s->a1 = (2.0f * ((A - 1.0f) - (A + 1.0f) * cw)) / a0;
    s->a2 = ((A + 1.0f) - (A - 1.0f) * cw - two_sqrtA_alpha) / a0;
  }

  static inline float ApplyShelf(float x, Shelf& s) {
    const float y = s.b0 * x + s.z1;
    s.z1 = s.b1 * x - s.a1 * y + s.z2;
    s.z2 = s.b2 * x - s.a2 * y;
    return y;
  }

  /** 按方位配置头影，返回生成的目标 ILD（dB，正 = 左耳更响） */
  float ConfigureHeadShadow(float az_deg) {
    float az = std::fmod(az_deg, 360.0f);
    if (az > 180.0f) az -= 360.0f;
    if (az < -180.0f) az += 360.0f;
    // |sin(az)|：0° 时为 0，90° 时最大，180° 回落到 0（正后方两耳等距）
    const float s = std::fabs(std::sin(az * kPi_ / 180.0f));
    const float half = 0.5f * kHeadShadowMaxDb * s;
    shadow_active_ = half > 0.05f;
    shadow_l_.Reset();
    shadow_r_.Reset();
    if (!shadow_active_) {
      SetHighShelf(&shadow_l_, 0.0f, 1500.0f, sample_rate_);
      SetHighShelf(&shadow_r_, 0.0f, 1500.0f, sample_rate_);
      return 0.0f;
    }
    // 源在右（az>0）→ 右耳为近耳（提），左耳为远耳（降）
    const bool right_near = az > 0.0f;
    SetHighShelf(&shadow_l_, right_near ? -half : half, 1500.0f, sample_rate_);
    SetHighShelf(&shadow_r_, right_near ? half : -half, 1500.0f, sample_rate_);
    // 与项目约定一致：正 = 左耳更响。源在右 → 左耳更轻 → 返回负值
    return right_near ? -(2.0f * half) : (2.0f * half);
  }

  /** 仰角 >5° 才启用；陷波中心随仰角上移（8.5 kHz → 13.5 kHz），并抬一点高频 */
  void ConfigureElevationCues(float el_deg) {
    el_active_ = std::fabs(el_deg) > 5.0f;
    if (!el_active_) {
      notch_b0_ = 1.0f;
      notch_b1_ = notch_b2_ = notch_a1_ = notch_a2_ = 0.0f;
      bright_gain_ = 0.0f;
      return;
    }
    const float fc = 8500.0f + (std::fabs(el_deg) / 90.0f) * 5000.0f;
    const float q = 2.0f;
    const float gain_db = -6.0f;
    // RBJ peaking EQ
    const float A = std::pow(10.0f, gain_db / 40.0f);
    const float w0 = 2.0f * 3.14159265358979f * fc / sample_rate_;
    const float alpha = std::sin(w0) / (2.0f * q);
    const float cw = std::cos(w0);
    const float a0 = 1.0f + alpha / A;
    notch_b0_ = (1.0f + alpha * A) / a0;
    notch_b1_ = (-2.0f * cw) / a0;
    notch_b2_ = (1.0f - alpha * A) / a0;
    notch_a1_ = (-2.0f * cw) / a0;
    notch_a2_ = (1.0f - alpha / A) / a0;
    bright_gain_ = 0.016f * std::fabs(el_deg);   // 约 +0.56 dB @35°
  }

  inline float ApplyElevation(float x, bool left) {
    // 一阶低通取高频，做高频 shelf
    float& lp = left ? lp_l_ : lp_r_;
    const float a = 0.35f;   // 约 8 kHz @48k
    lp += a * (x - lp);
    float y = x + (x - lp) * bright_gain_;

    // 二阶陷波（转置直接 II）
    float& z1 = left ? notch_z1_l_ : notch_z1_r_;
    float& z2 = left ? notch_z2_l_ : notch_z2_r_;
    const float out = notch_b0_ * y + z1;
    z1 = notch_b1_ * y - notch_a1_ * out + z2;
    z2 = notch_b2_ * y - notch_a2_ * out;
    return out;
  }

  float sample_rate_ = 48000.0f;
  float az_deg_ = 0.0f;
  float el_deg_ = 0.0f;
  int model_ = 0;

  const float* ir_l_ = nullptr;
  const float* ir_r_ = nullptr;
  int taps_ = 0;
  float ir_scale_ = 1.0f;
  float itd_samples_ = 0.0f;
  float ild_target_db_ = 0.0f;
  bool shadow_active_ = false;
  Shelf shadow_l_;
  Shelf shadow_r_;
  float ild_trim_ = 1.0f;

  float hist_[kRingSize] = {};
  int pos_ = 0;

  bool el_active_ = false;
  float bright_gain_ = 0.0f;
  float lp_l_ = 0.0f;
  float lp_r_ = 0.0f;
  float notch_b0_ = 1.0f, notch_b1_ = 0.0f, notch_b2_ = 0.0f;
  float notch_a1_ = 0.0f, notch_a2_ = 0.0f;
  float notch_z1_l_ = 0.0f, notch_z1_r_ = 0.0f;
  float notch_z2_l_ = 0.0f, notch_z2_r_ = 0.0f;
};

}  // namespace sonicraft::dsp::vs
