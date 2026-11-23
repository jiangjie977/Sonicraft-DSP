/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>

namespace sonicraft::dsp {

/** π（与 MultibandNode 内同名常量同值，抽取后统一走这个） */
inline constexpr float kBiquadPi = 3.141592653589793f;

/**
 * RBJ cookbook 二阶滤波器，带**块内系数线性 ramp**。
 *
 * 从 `MultibandNode` 的私有内部结构抽取，供 动态低音复用。
 *
 * 为什么需要 ramp：滤波器系数突变会让状态变量（x1/x2/y1/y2）与新系数不匹配，
 * 产生"咔"声。做法是本块开始时算出目标系数 → 逐样本线性逼近 →
 * 块尾精确落位（`SnapCoeffs`）消掉浮点累积误差。
 *
 * **迁移说明（技术债）**：`multiband.hpp` 目前仍保留同名的内部实现，
 * 两份并存是为了避免本次改动波及多段压限（它是 harness 覆盖最厚的模块之一）。
 * 待 落地后单独做一次迁移提交，把 multiband 切到本文件。
 */
struct Biquad {
  float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;        // 当前系数
  float tb0 = 1.0f, tb1 = 0.0f, tb2 = 0.0f, ta1 = 0.0f, ta2 = 0.0f;   // 本块目标
  float db0 = 0.0f, db1 = 0.0f, db2 = 0.0f, da1 = 0.0f, da2 = 0.0f;   // 每样本增量
  float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;

  float Process(float x) {
    const float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1;
    x1 = x;
    y2 = y1;
    y1 = y;
    return y;
  }

  /** RBJ cookbook，Q=1/√2（Butterworth 二阶）；hp=false 低通 / true 高通。只写目标系数。 */
  void ConfigureTarget(float fc, int sample_rate, bool hp) {
    const float w0 = 2.0f * kBiquadPi * fc / static_cast<float>(sample_rate);
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) * 0.70710678f;   // sin(w0)/(2Q), Q=1/√2
    float b0n, b1n, b2n;
    if (hp) {
      b0n = (1.0f + cw) * 0.5f;
      b1n = -(1.0f + cw);
      b2n = (1.0f + cw) * 0.5f;
    } else {
      b0n = (1.0f - cw) * 0.5f;
      b1n = 1.0f - cw;
      b2n = (1.0f - cw) * 0.5f;
    }
    const float a0 = 1.0f + alpha;
    tb0 = b0n / a0;
    tb1 = b1n / a0;
    tb2 = b2n / a0;
    ta1 = (-2.0f * cw) / a0;
    ta2 = (1.0f - alpha) / a0;
  }

  /**
   * 可调 Q 的 RBJ 二阶低通。
   *
   * 与 [ConfigureTarget] 的区别：那个是给**分频**用的固定 Q=1/√2（LR4 的一半），
   * 保证 lp/hp 相加平坦；这个是给**音色**用的，Q 可调（Q>0.707 在截止点起包，
   * 会让低频更"鼓"，正是经典低音增强想要的染色）。
   */
  void ConfigureLowPassQ(float fc, int sample_rate, float q) {
    const float w0 = 2.0f * kBiquadPi * fc / static_cast<float>(sample_rate);
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * std::max(1e-3f, q));
    const float b0n = (1.0f - cw) * 0.5f;
    const float b1n = 1.0f - cw;
    const float b2n = (1.0f - cw) * 0.5f;
    const float a0 = 1.0f + alpha;
    tb0 = b0n / a0;
    tb1 = b1n / a0;
    tb2 = b2n / a0;
    ta1 = (-2.0f * cw) / a0;
    ta2 = (1.0f - alpha) / a0;
  }

  /**
   * RBJ cookbook **low/high shelf**。`gain_db` 可正可负（负 = 衰减）。
   *
   * 与 [ConfigureTarget] 的区别：那个是固定 Q=1/√2 的二阶 LP/HP（分频用），
   * 这个是带增益的搁架（EQ 用）。两者共用同一套系数 ramp 机制，
   * 所以参数变化时同样不会爆音。
   *
   * @param q   0.7 附近最平；>0.7 会在转折点起包
   * @param low true = low shelf（提升/衰减低频），false = high shelf
   */
  void ConfigureShelf(float fc, int sample_rate, float gain_db, float q, bool low) {
    const float A = std::pow(10.0f, gain_db / 40.0f);
    const float w0 = 2.0f * kBiquadPi * fc / static_cast<float>(sample_rate);
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float tsa = 2.0f * std::sqrt(A) * alpha;

    float b0n, b1n, b2n, a0n, a1n, a2n;
    if (low) {
      b0n = A * ((A + 1.0f) - (A - 1.0f) * cw + tsa);
      b1n = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cw);
      b2n = A * ((A + 1.0f) - (A - 1.0f) * cw - tsa);
      a0n = (A + 1.0f) + (A - 1.0f) * cw + tsa;
      a1n = -2.0f * ((A - 1.0f) + (A + 1.0f) * cw);
      a2n = (A + 1.0f) + (A - 1.0f) * cw - tsa;
    } else {
      b0n = A * ((A + 1.0f) + (A - 1.0f) * cw + tsa);
      b1n = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cw);
      b2n = A * ((A + 1.0f) + (A - 1.0f) * cw - tsa);
      a0n = (A + 1.0f) - (A - 1.0f) * cw + tsa;
      a1n = 2.0f * ((A - 1.0f) - (A + 1.0f) * cw);
      a2n = (A + 1.0f) - (A - 1.0f) * cw - tsa;
    }
    tb0 = b0n / a0n;
    tb1 = b1n / a0n;
    tb2 = b2n / a0n;
    ta1 = a1n / a0n;
    ta2 = a2n / a0n;
  }

  /**
   * RBJ cookbook **peaking EQ**（钟形）。`gain_db` 可正可负。
   *
   * 与 shelf 的差别：它只在 fc 附近起作用、两侧回到 0dB —— 用来给某个
   * 窄频段（如 45Hz）额外加一点"体感"，而不把整个低频一起抬起来。
   */
  void ConfigurePeak(float fc, int sample_rate, float gain_db, float q) {
    const float A = std::pow(10.0f, gain_db / 40.0f);
    const float w0 = 2.0f * kBiquadPi * fc / static_cast<float>(sample_rate);
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);

    const float b0n = 1.0f + alpha * A;
    const float b1n = -2.0f * cw;
    const float b2n = 1.0f - alpha * A;
    const float a0n = 1.0f + alpha / A;
    const float a1n = -2.0f * cw;
    const float a2n = 1.0f - alpha / A;

    tb0 = b0n / a0n;
    tb1 = b1n / a0n;
    tb2 = b2n / a0n;
    ta1 = a1n / a0n;
    ta2 = a2n / a0n;
  }

  void BeginBlockRamp(int frames) {
    const float inv = 1.0f / static_cast<float>(frames);
    db0 = (tb0 - b0) * inv;
    db1 = (tb1 - b1) * inv;
    db2 = (tb2 - b2) * inv;
    da1 = (ta1 - a1) * inv;
    da2 = (ta2 - a2) * inv;
  }

  void StepCoeffs() {
    b0 += db0;
    b1 += db1;
    b2 += db2;
    a1 += da1;
    a2 += da2;
  }

  /** 块尾精确落位（消浮点累积误差），静止块增量归零 */
  void SnapCoeffs() {
    b0 = tb0;
    b1 = tb1;
    b2 = tb2;
    a1 = ta1;
    a2 = ta2;
    db0 = db1 = db2 = da1 = da2 = 0.0f;
  }

  /** 直接落位（Prepare/Reset：无 ramp，当前=目标） */
  void CommitTarget() { SnapCoeffs(); }

  void ResetState() { x1 = x2 = y1 = y2 = 0.0f; }
};

/**
 * LR4 分频对：LP4 = lp[0]→lp[1]，HP4 = hp[0]→hp[1]（同系数级联两次）。
 * 同分频点的 LP4/HP4 相位一致、相加平坦 —— 这是"只处理低频带再重构"
 * 不污染其余频段的前提。
 */
struct LR4Split {
  Biquad lp[2];
  Biquad hp[2];

  void ConfigureTarget(float fc, int sample_rate) {
    lp[0].ConfigureTarget(fc, sample_rate, false);
    lp[1].ConfigureTarget(fc, sample_rate, false);
    hp[0].ConfigureTarget(fc, sample_rate, true);
    hp[1].ConfigureTarget(fc, sample_rate, true);
  }

  void CommitTargets() {
    for (auto* q : {&lp[0], &lp[1], &hp[0], &hp[1]}) q->CommitTarget();
  }

  void BeginBlockRamp(int frames) {
    for (auto* q : {&lp[0], &lp[1], &hp[0], &hp[1]}) q->BeginBlockRamp(frames);
  }

  void StepCoeffs() {
    for (auto* q : {&lp[0], &lp[1], &hp[0], &hp[1]}) q->StepCoeffs();
  }

  void SnapCoeffs() {
    for (auto* q : {&lp[0], &lp[1], &hp[0], &hp[1]}) q->SnapCoeffs();
  }

  void Reset() {
    for (auto* q : {&lp[0], &lp[1], &hp[0], &hp[1]}) q->ResetState();
  }
};

}  // namespace sonicraft::dsp
