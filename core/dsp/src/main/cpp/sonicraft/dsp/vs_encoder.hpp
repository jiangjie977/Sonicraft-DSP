/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>

#include "sonicraft/dsp/vs_decoder.hpp"
#include "sonicraft/dsp/vs_params.hpp"

namespace sonicraft::dsp::vs {

/**
 * Lt/Rt 矩阵编码（下混）：
 *
 *   低频管理：6 条主声道的低频汇入 LFE（主声道自身只留高通）
 *   输出      Lt = LFE + FL + 0.5·(C + 0.71C) + SL + 0.71C
 *            Rt = LFE + FR + 0.5·(C + 0.71C) + SR + 0.71C
 *   末端     经过一条静态电平曲线（低电平段抬升、高电平段压缩至上限），再做 outTrim。
 *
 * 与早先版本的区别：不做 90° 相移（相位扩散交给下游去相关），
 * 中置按 1.71 份分配，且末端有电平曲线 —— 这条曲线是"响度与厚度"的主要来源。
 */
class LtRtRoundTrip {
 public:
  void Prepare(float sample_rate) {
    sr_ = sample_rate > 0.0f ? sample_rate : 48000.0f;
    InitFilters();
    Reset();
  }

  void Reset() {
    for (int i = 0; i < 6; ++i) {
      hp_[i].Reset();
      lp_bm_[i].Reset();
    }
    lfe_lp_.Reset();
    strength_ = 0.0f;
  }

  /**
   * @param ch       7 声道 planar（就地读改写）
   * @param strength 0 = 完全不经过往返；1 = 完全取往返结果
   */
  void Process(float* const* ch, int frames, float strength, StereoSpatialDecoder& decoder2,
               bool movie, float center_db, float surround_db) {
    const float target = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
    if (target <= 0.001f && strength_ <= 0.001f) return;

    decoder2.SetMode(movie);
    decoder2.SetGains(center_db, surround_db, 0.0f);

    // 主声道顺序：FL FR C SL SR CS（LFE 单独处理）
    static const int kMain[6] = {kFL, kFR, kC, kSL, kSR, kCS};

    for (int i = 0; i < frames; ++i) {
      strength_ += 0.001f * (target - strength_);

      float main[6];
      for (int k = 0; k < 6; ++k) main[k] = ch[kMain[k]][i];

      // 低频管理：主声道低频汇入 LFE，主声道只留高通
      float bass_sum = 0.0f;
      for (int k = 0; k < 6; ++k) {
        bass_sum += lp_bm_[k].Process(main[k]);
        main[k] = hp_[k].Process(main[k]);
      }
      const float lfe = lfe_lp_.Process(ch[kLFE][i] + bass_sum);

      const float fl = main[0], fr = main[1], c = main[2], sl = main[3], sr = main[4];

      float lt = lfe + fl;
      float rt = lfe + fr;
      const float t = c * 0.7099999785423279f;
      const float center_mix = t + c;              // C × 1.71
      lt += center_mix * 0.5f + sl + t;
      rt += center_mix * 0.5f + sr + t;

      lt = LevelCurve(lt);
      rt = LevelCurve(rt);

      float out[vs::kChannelCount];
      decoder2.ProcessSample(lt, rt, out);

      const float s = strength_;
      for (int k = 0; k < 6; ++k) {
        ch[kMain[k]][i] = ch[kMain[k]][i] * (1.0f - s) + out[kMain[k]] * s;
      }
      ch[kLFE][i] = ch[kLFE][i] * (1.0f - s) + out[kLFE] * s;
    }
  }

  float Strength() const { return strength_; }

 private:
  /**
   * 静态电平曲线（单调、保号）：
   *   低电平段整体抬升（约 +4 dB），高电平段软限幅到 1.0。
   *
   * 早先版本照搬了"中高段把 x 钳到上限"的写法：一旦输入超过 0.25 就全变成同一个
   * 常数，后续带通把直流滤掉 —— 表现为"编码强度拉满就没声音"。这里改成单调曲线。
   */
  static float LevelCurve(float x) {
    const float sign = x < 0.0f ? -1.0f : 1.0f;
    float a = std::fabs(x) * 1.6f;          // 抬升
    if (a > 0.8f) {
      const float over = (a - 0.8f) * 5.0f; // 0.2 的余量内软压
      a = 0.8f + 0.2f * std::tanh(over);
    }
    return a * sign;
  }

  void InitFilters() {
    // 44.1k 的原系数按其采样率折算到当前采样率
    constexpr float kA2 = 0.9840099215507507f;
    constexpr float kLfeA2 = 0.9926225543022156f;
    const float ratio = 44100.0f / sr_;

    const float r_hp = std::pow(std::sqrt(kA2), ratio);
    const float a1 = -2.0f * r_hp;
    const float a2 = r_hp * r_hp;
    const float k_hp = (1.0f + a1 + a2) * 0.25f;

    const float r_lp = std::pow(std::sqrt(kA2), ratio);
    const float b1 = -2.0f * r_lp;
    const float b2 = r_lp * r_lp;
    const float k_lp = (1.0f + b1 + b2) * 0.25f;

    const float r_lfe = std::pow(std::sqrt(kLfeA2), ratio);
    const float c1 = -2.0f * r_lfe;
    const float c2 = r_lfe * r_lfe;
    const float k_lfe = (1.0f + c1 + c2) * 0.25f;

    for (int i = 0; i < 6; ++i) {
      hp_[i].b0 = k_hp;
      hp_[i].b1 = -2.0f * k_hp;
      hp_[i].b2 = k_hp;
      hp_[i].a1 = a1;
      hp_[i].a2 = a2;

      lp_bm_[i].b0 = k_lp;
      lp_bm_[i].b1 = 2.0f * k_lp;
      lp_bm_[i].b2 = k_lp;
      lp_bm_[i].a1 = b1;
      lp_bm_[i].a2 = b2;
    }
    lfe_lp_.b0 = k_lfe;
    lfe_lp_.b1 = 2.0f * k_lfe;
    lfe_lp_.b2 = k_lfe;
    lfe_lp_.a1 = c1;
    lfe_lp_.a2 = c2;
  }

  float sr_ = 48000.0f;
  float strength_ = 0.0f;
  Biquad hp_[6];
  Biquad lp_bm_[6];
  Biquad lfe_lp_;
};

}  // namespace sonicraft::dsp::vs
