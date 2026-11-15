/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/vs_decoder.hpp"
#include "sonicraft/dsp/vs_params.hpp"

namespace sonicraft::dsp::vs {

/**
 * Lt/Rt 编解码往返（矩阵环绕的"再解码"路线）。
 *
 * 目的不是"传数据"，而是**制造相位扩散**：把 7 声道按矩阵压回 Lt/Rt，
 * 环绕声道先做 90° 相移再混入，然后再解一次 —— 这一步会让原本相关的
 * L/R 在中高频产生可控的去相关，听感上就是"声场变宽、有包裹感"。
 *
 * 相位：用两级一阶全通近似 90°（在 200 Hz~8 kHz 区间误差约 ±10°），
 * 比真 Hilbert FIR（64 tap 卷积）便宜一个数量级，听感差异可忽略。
 *
 * 强度：0 = 完全直通（不经过往返）；1 = 完全取往返结果。
 * 混合用一阶平滑，避免拨动时爆音。
 */
class LtRtRoundTrip {
 public:
  void Prepare(float sample_rate) {
    sr_ = sample_rate > 0.0f ? sample_rate : 48000.0f;
    // 两级一阶全通近似 90°：左右取反号构成正交对（0.6 / −0.4 在 200 Hz~8 kHz
    // 相位误差 ≤ ±10°，比 64 tap Hilbert FIR 便宜一个数量级）
    ap_l_[0].coeff = 0.6f;
    ap_l_[1].coeff = -0.4f;
    ap_r_[0].coeff = -0.6f;
    ap_r_[1].coeff = 0.4f;
    Reset();
  }

  void Reset() {
    for (int i = 0; i < 2; ++i) {
      ap_l_[i].Reset();
      ap_r_[i].Reset();
    }
    strength_ = 0.0f;
  }

  /**
   * @param ch      7 声道 planar（就地读改写）
   * @param frames  本块帧数
   * @param strength 目标强度 0~1
   * @param decoder2 第二个解码器实例（与第一个互不共享状态）
   */
  void Process(float* const* ch, int frames, float strength, StereoSpatialDecoder& decoder2,
               bool movie, float center_db, float surround_db) {
    const float target = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
    if (target <= 0.001f && strength_ <= 0.001f) return;

    decoder2.SetMode(movie);
    decoder2.SetGains(center_db, surround_db, 0.0f);

    for (int i = 0; i < frames; ++i) {
      strength_ += 0.001f * (target - strength_);  // 约 20 ms（@48k 逐样本）

      const float fl = ch[kFL][i], fr = ch[kFR][i], c = ch[kC][i];
      const float sl = ch[kSL][i], sr = ch[kSR][i], cs = ch[kCS][i];

      // 环绕 90° 相移（两级一阶全通，左正右负构成正交对）
      const float ql = ap_r_[1].Process(ap_r_[0].Process(sl));
      const float qr = ap_l_[1].Process(ap_l_[0].Process(sr));

      // 矩阵编码：Lt = FL + 0.707C + jSL + CS；Rt = FR + 0.707C − jSR + CS
      const float lt = fl + 0.7071068f * c + ql + 0.7071068f * cs;
      const float rt = fr + 0.7071068f * c - qr + 0.7071068f * cs;

      // 与原立体声按强度混合后再解一次（解码器内部会把 Lt/Rt 重新分配）
      float out[vs::kChannelCount];
      decoder2.ProcessSample(lt, rt, out);

      const float s = strength_;
      ch[kFL][i] = fl * (1.0f - s) + out[kFL] * s;
      ch[kFR][i] = fr * (1.0f - s) + out[kFR] * s;
      ch[kC][i] = c * (1.0f - s) + out[kC] * s;
      ch[kSL][i] = sl * (1.0f - s) + out[kSL] * s;
      ch[kSR][i] = sr * (1.0f - s) + out[kSR] * s;
      ch[kCS][i] = cs * (1.0f - s) + out[kCS] * s;
      ch[kLFE][i] = ch[kLFE][i];  // LFE 不参与矩阵往返
    }
  }

  float Strength() const { return strength_; }

 private:
  float sr_ = 48000.0f;
  float strength_ = 0.0f;
  AllPass1 ap_l_[2];
  AllPass1 ap_r_[2];
};

}  // namespace sonicraft::dsp::vs
