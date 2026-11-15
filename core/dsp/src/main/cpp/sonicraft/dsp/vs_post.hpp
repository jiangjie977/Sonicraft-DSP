/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>

#include "sonicraft/dsp/vs_hrtf.hpp"
#include "sonicraft/dsp/vs_params.hpp"

namespace sonicraft::dsp::vs {

/**
 * 7 声道后处理（渲染前的最后一道整形）。
 *
 * 四件事，顺序固定：
 *   1. **中置三态**：0=关闭（中置静音）/ 1=直通 / 2=按 −3 dB 混入前置后静音；
 *   2. **人声清晰度**：正值给中置加增益并叠加瞬态增强（上限 1.3×），负值纯衰减；
 *   3. **低音谐波**：对数域多项式偶次谐波，从 LFE 或前置取源，补足小喇叭的低频体感；
 *   4. **后中置**：可调延迟（≤255 样本）+ 一阶全通，增强后方定位。
 *
 * 全部状态在 Prepare 分配；Process 内无分配、无锁。
 */
class PostStage {
 public:
  void Prepare(float sample_rate) {
    sr_ = sample_rate > 0.0f ? sample_rate : 48000.0f;
    Reset();
  }

  void Reset() {
    center_direct_ = 0.0f;
    center_mix_ = 0.0f;
    for (int i = 0; i < kMaxDelay; ++i) delay_buf_[i] = 0.0f;
    delay_pos_ = 0;
    ap_x1_ = ap_y1_ = 0.0f;
    harm_lp_ = 0.0f;
    prev_prev_c_ = prev_c_ = 0.0f;
  }

  /** 后处理需要的参数快照（由节点从参数表填充） */
  struct Params {
    float center_mode = 1.0f;
    float dialog_db = 0.0f;
    float dialog_enabled = 1.0f;
    float bass_enhancer = 0.0f;
    float bass_enhancer_mode = 0.0f;
    float rear_center_enabled = 0.0f;
    float rear_delay_ms = 5.0f;
  };

  /**
   * @param ch  7 声道 planar 视图（长度 ≥ frames 的指针数组，就地修改）
   */
  void Process(float* const* ch, int frames, const Params& p) {
    // ---- 1. 中置三态（一阶平滑，切换不爆音）----
    const float mode = p.center_mode < 0.5f ? 0.0f : (p.center_mode < 1.5f ? 1.0f : 2.0f);
    const float direct_goal = mode == 1.0f ? 1.0f : 0.0f;
    const float mix_goal = mode == 2.0f ? 1.0f : 0.0f;
    const float smooth = 0.0025f;

    // ---- 2. 人声清晰度 ----
    const float dialog_db = p.dialog_enabled ? Clamp(p.dialog_db, -6.0f, 6.0f) : 0.0f;
    const float dialog_gain = DbToGain(dialog_db);
    const float transient_boost = dialog_db > 0.0f ? (1.0f + 0.3f * (dialog_db / 6.0f)) : 1.0f;

    // ---- 4. 后中置延迟 ----
    float delay_samples = p.rear_center_enabled ? (p.rear_delay_ms * 0.001f * sr_) : 0.0f;
    if (delay_samples < 0.0f) delay_samples = 0.0f;
    if (delay_samples > static_cast<float>(kMaxDelay - 1)) delay_samples = static_cast<float>(kMaxDelay - 1);

    for (int i = 0; i < frames; ++i) {
      float c = ch[kC][i];
      float fl = ch[kFL][i];
      float fr = ch[kFR][i];

      // ---- 人声清晰度：中置增益 + 瞬态增强 ----
      if (dialog_db != 0.0f) {
        const float d1 = c - prev_c_;
        const float d2 = prev_c_ - prev_prev_c_;
        prev_prev_c_ = prev_c_;
        prev_c_ = c;
        // 二阶差分近似"瞬态强度"：只在能量上升沿加权，稳态不受益（避免整体变响）
        const float trans = (std::fabs(d1) > std::fabs(d2)) ? (std::fabs(d1) - std::fabs(d2)) : 0.0f;
        const float boost = 1.0f + (transient_boost - 1.0f) * Clamp(trans * 8.0f, 0.0f, 1.0f);
        c *= dialog_gain * boost;
      }

      // ---- 中置三态 ----
      center_direct_ += smooth * (direct_goal - center_direct_);
      center_mix_ += smooth * (mix_goal - center_mix_);
      const float c_mix = c * 0.7071068f * center_mix_;  // −3 dB
      fl += c_mix;
      fr += c_mix;
      ch[kC][i] = c * center_direct_;

      // ---- 后中置：延迟 + 全通 ----
      if (p.rear_center_enabled) {
        float cs = ch[kCS][i];
        delay_buf_[delay_pos_] = cs;
        const int read = (delay_pos_ - static_cast<int>(delay_samples) + kMaxDelay) % kMaxDelay;
        float d = delay_buf_[read];
        // 一阶全通 a=0.5：改相位不改幅度，强化"在身后"的感觉
        const float y = 0.5f * d + ap_x1_ - 0.5f * ap_y1_;
        ap_x1_ = d;
        ap_y1_ = y;
        delay_pos_ = (delay_pos_ + 1) % kMaxDelay;
        ch[kCS][i] = y;
      }

      ch[kFL][i] = fl;
      ch[kFR][i] = fr;
    }

    // ---- 3. 低音谐波（块级，按最后一帧的参数；逐块更新足够）----
    const float amount = Clamp(p.bass_enhancer, 0.0f, 1.0f);
    if (amount > 0.001f) {
      const bool from_lfe = p.bass_enhancer_mode < 0.5f;
      float* src = from_lfe ? ch[kLFE] : nullptr;
      for (int i = 0; i < frames; ++i) {
        const float x = from_lfe ? src[i] : (ch[kFL][i] + ch[kFR][i]) * 0.5f;
        // 一阶低通（120 Hz）取低频
        harm_lp_ += 0.0156f * (x - harm_lp_);
        const float h = Harmonics(harm_lp_);
        if (from_lfe) {
          src[i] += h * amount;
        } else {
          ch[kFL][i] += h * amount;
          ch[kFR][i] += h * amount;
        }
      }
    }
  }

  static constexpr int kMaxDelay = 256;

 private:
  static float Clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
  static float DbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

  /**
   * 对数域多项式谐波生成（全波整流 → 归一化 → 多项式 → 还原幅度 → 恢复符号）。
   * 系数按"低次谐波为主、不刺耳"整定；输入超过 ±1 时先钳位，避免多项式爆掉。
   */
  static float Harmonics(float x) {
    const float sign = x < 0.0f ? -1.0f : 1.0f;
    float a = std::fabs(x);
    if (a > 1.0f) a = 1.0f;
    if (a < 1e-6f) return 0.0f;
    int scale = 0;
    while (a < 0.5f && scale > -20) {
      a *= 2.0f;
      --scale;
    }
    const float y = (0.124774f - a * 0.042153f) * a - 0.082826f;
    const float restored = y * std::pow(2.0f, static_cast<float>(scale));
    return restored * sign;
  }

  float sr_ = 48000.0f;
  float center_direct_ = 0.0f, center_mix_ = 0.0f;
  float delay_buf_[kMaxDelay]{};
  int delay_pos_ = 0;
  float ap_x1_ = 0.0f, ap_y1_ = 0.0f;
  float harm_lp_ = 0.0f;
  float prev_c_ = 0.0f, prev_prev_c_ = 0.0f;
};

}  // namespace sonicraft::dsp::vs
