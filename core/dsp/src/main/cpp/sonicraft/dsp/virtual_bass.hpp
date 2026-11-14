/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-FileCopyrightText: 2026 qumolangmo
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 部分逻辑移植自 WEcho（GPL-3.0，Copyright (C) 2026 qumolangmo）；
 * 本文件由 Sonicraft 于 2026-11 在 WEcho 基础上移植并修改。
 */
#pragma once

#include <algorithm>
#include <cmath>
#include <string_view>
#include <vector>

#include "sonicraft/dsp/biquad.hpp"
#include "sonicraft/dsp/dsp_node.hpp"

namespace sonicraft::dsp {

/**
 * 虚拟低音（心理声学低频扩展）—— **针对手机内置扬声器**。
 *
 * ## 原理
 * 手机扬声器在 100~200Hz 以下急速滚降，物理上放不出低频。但**耳朵不会** ——
 * 听觉系统会从谐波结构里"还原"出缺失的基频（missing fundamental）。
 * 所以把 80~150Hz 的基频翻译成 160~300Hz 的二次谐波，大脑就以为听到了低频。
 *
 * ## 算法（移植自 wecho 的 `VirtualBassEffect`）
 * ```
 * x ─┬─ 80~150Hz 带通 → 一阶平滑 → Chebyshev 二次谐波 → HP 120
 *    │                                    └→ 包络归一化(env_lp/env_har)
 *    ├─ 120~600Hz 带通 ──────────────────────────┐
 *    └─ 600Hz+ 高通 ─────────────────────────────┤
 *                                                └→ 混合 → 输出
 * ```
 *
 * 三个关键设计：
 * 1. **只取 80~150Hz 做谐波源** —— 生成的 160~300Hz 正好落在扬声器能放出的频段
 * 2. **谐波后高通 120Hz** —— 去掉基频残留，避免与原信号的低频"打架"
 * 3. **包络归一化 `mod = env_lp / env_har`（限幅 0.2~5）** —— **精髓**：
 *    让谐波能量跟随基频包络。没有基频时不会凭空造谐波，
 *    否则纯中频人声也会被"加工"出嗡嗡声。
 *
 * ## 与 wecho 的一处**有意偏离**
 * wecho 是**纯替换**：`<120Hz` 的原信号完全丢弃，输出只剩谐波 + 中高频。
 * 在手机扬声器上这是对的（反正放不出），但在耳机上强制开启时会**完全没有基频**，
 * 听感发空、低频怪。
 *
 * 所以这里加 `amount` 做 **dry/wet 混合**：
 *   `out = dry × (1 − amount) + wet × amount`
 * - `amount = 0` → 完全原声（逐位透明，可用于旁路等价）
 * - `amount = 1` → 纯 wecho 行为
 * - 默认 0.7 → 保留 30% 原低频，耳机上强行开启也不至于"空掉"
 *
 * ## 参数
 *   param 0 = amount        干湿比 0~1（默认 0.7）
 *   param 1 = harmonic     谐波注入量 0~2（默认 1.3，wecho 同值）
 *   param 2 = midGain      120~600Hz 直通量 0~1（默认 0.5）
 *   param 3 = highGain     600Hz+ 直通量 0~1（默认 0.5）
 *   param 4 = envelopeRate 包络平滑截止 5~150Hz（默认 40）
 *
 * ## 计量
 *   [0] 输入低频 RMS  [1] 谐波相对基频的 dB  [2] 保留原低频比例（线性）
 */
class VirtualBassNode : public DspNode {
 public:
  static constexpr int kParamAmount = 0;
  static constexpr int kParamHarmonic = 1;
  static constexpr int kParamMidGain = 2;
  static constexpr int kParamHighGain = 3;
  static constexpr int kParamEnvelopeRate = 4;

  /**
   * 默认值**逐项对齐 `wecho yuanma/native/effects/VirtualBassEffect.cpp` 构造函数**
   * （2026-11-12 用户实听：同机同扬声器同参数下我们的听感只有 wecho 一半）。
   *
   * wecho: envelope_rate=50 / mid_gain=0.08 / high_gain=0.16 / harmonic_gain=1.00。
   * wecho 没有 amount（纯替换），这里 1.0 = 不带干声；参数仍保留供耳机上手动降。
   */
  static constexpr float kDefaultAmount = 1.0f;
  static constexpr float kDefaultHarmonic = 1.0f;
  static constexpr float kDefaultMidGain = 0.08f;
  static constexpr float kDefaultHighGain = 0.16f;
  static constexpr float kDefaultEnvelopeRate = 50.0f;
  /** yuanma 的 envelope_alpha 固定 25Hz（setEnvelopeRate 不更新它） */
  static constexpr float kYuanmaEnvHz = 25.0f;

  /** 谐波源频段（扬声器放不出、但谐波落点能放出的区间） */
  static constexpr float kSubLoHz = 80.0f;
  static constexpr float kSubHiHz = 150.0f;
  /** 谐波后高通的截止（去掉基频残留） */
  static constexpr float kHarmonicHpHz = 120.0f;
  /** 中频直通带 */
  static constexpr float kMidLoHz = 120.0f;
  static constexpr float kMidHiHz = 600.0f;
  static constexpr float kHighHz = 600.0f;
  /** wecho 的整体补偿量（谐波能量天生偏小） */
  static constexpr float kPostGainDb = 12.0f;
  static constexpr float kEnvRateMin = 5.0f;
  static constexpr float kEnvRateMax = 150.0f;
  static constexpr int kMaxChannelsLocal = 16;

  std::string_view id() const override { return "virtual_bass"; }

  void set_channel_count(int channels) { channels_ = channels > 0 ? channels : 2; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    max_frames_ = max_frames > 0 ? max_frames : 128;

    const std::size_t n = static_cast<std::size_t>(channels_);
    sub_band_.assign(n, Band{});
    mid_band_.assign(n, Band{});
    high_band_.assign(n, HighPass{});
    harmonic_hp_.assign(n, HighPass{});

    lp_soft_.assign(n, 0.0f);
    env_lp_.assign(n, 0.0f);
    env_har_.assign(n, 0.0f);
    har_last_x_.assign(n, 0.0f);
    har_last_y_.assign(n, 0.0f);

    // 参数回默认（与其它节点一致：真实值由重放补发）
    amount_ = kDefaultAmount;
    harmonic_ = kDefaultHarmonic;
    mid_gain_ = kDefaultMidGain;
    high_gain_ = kDefaultHighGain;
    envelope_rate_ = kDefaultEnvelopeRate;

    configureFixedFilters();
    post_gain_ = std::pow(10.0f, kPostGainDb / 20.0f);
    updateEnvelopeCoeffs();
    Reset();
  }

  void Reset() override {
    for (auto& b : sub_band_) b.Reset();
    for (auto& b : mid_band_) b.Reset();
    for (auto& b : high_band_) b.Reset();
    for (auto& b : harmonic_hp_) b.Reset();
    std::fill(lp_soft_.begin(), lp_soft_.end(), 0.0f);
    std::fill(env_lp_.begin(), env_lp_.end(), 0.0f);
    std::fill(env_har_.begin(), env_har_.end(), 0.0f);
    std::fill(har_last_x_.begin(), har_last_x_.end(), 0.0f);
    std::fill(har_last_y_.begin(), har_last_y_.end(), 0.0f);
    meter_in_low_ = 0.0f;
    meter_harmonic_db_ = 0.0f;
    meter_dry_ratio_ = 1.0f;
  }

  void Process(float* const* channels, int frames) override {
    if (channels == nullptr || frames <= 0) return;

    // 旁路：逐位透明
    if (bypass_) return;

    // amount = 0 → 纯原声，不需要跑任何滤波（也保证"关掉 = 逐位相同"）
    const float wet = std::clamp(amount_, 0.0f, 1.0f);
    if (wet <= 1e-4f) {
      meter_dry_ratio_ = 1.0f;
      return;
    }
    const float dry = 1.0f - wet;

    const int nch = std::min(channels_, kMaxChannelsLocal);

    for (int i = 0; i < frames; ++i) {
      for (int c = 0; c < nch; ++c) {
        const std::size_t ci = static_cast<std::size_t>(c);
        const float x = channels[c][i];

        // ---- 谐波支路：80~150Hz → 平滑 → 二次谐波 → 高通 120 ----
        float sub = sub_band_[ci].Process(x);

        // 一阶平滑（截止 = envelopeRate）：抑制高频毛刺进入非线性环节，
        // 否则会产生大量高次互调，听起来像"砂纸"
        lp_soft_[ci] += lp_soft_alpha_ * (sub - lp_soft_[ci]);
        sub = lp_soft_[ci];

        // **对齐 yuanma `Harmonic<3>` + coeffs {0, 0.13, 0.13}**。
        // 其 setCoeffs 把增益写进 tmp[1..3]，再按 Chebyshev T_i 展开：
        //   0.13·T2 + 0.13·T3 = 0.52x³ + 0.26x² − 0.39x − 0.13
        // 我们原先只用了二次（0.26x²+0.13x−0.13），**少了三次谐波**
        // （100Hz→300Hz 恰在小扬声器能放的频段），这是听感变薄的原因之一。
        const float x2 = sub * sub;
        const float poly = 0.52f * x2 * sub + 0.26f * x2 - 0.39f * sub - 0.13f;
        // 差分 + 泄漏积分 = 一阶高通，去掉 DC 与低频残留
        float har = (poly - har_last_x_[ci]) + har_last_y_[ci] * 0.998f;
        har_last_x_[ci] = poly;
        har_last_y_[ci] = har;

        har = harmonic_hp_[ci].Process(har);

        // ---- 包络归一化：无基频时不造谐波 ----
        const float a_lp = std::fabs(sub);
        const float a_har = std::fabs(har);
        env_lp_[ci] += env_alpha_ * (a_lp - env_lp_[ci]);
        env_har_[ci] += env_alpha_ * (a_har - env_har_[ci]);
        float mod = env_lp_[ci] / (env_har_[ci] + 1e-8f);
        mod = std::clamp(mod, 0.2f, 5.0f);
        har *= mod;

        // ---- 直通支路 ----
        const float mid = mid_band_[ci].Process(x);
        const float high = high_band_[ci].Process(x);

        // **按 `wecho yuanma` 1:1 还原混合公式（2026-11-12，用户实听裁定）**
        //
        // yuanma: out = (mid_gain*0.25*bp + high_gain*0.25*hp
        //                + 2.4*y*harmonic_gain) * post_gain;  audio = out;
        //
        // 昨天 L2 为了修「200Hz −19dB」把 mid/high 抬成 kMidThroughGain=2.5、
        // 且 post_gain 只给谐波，结果中频直通比 wecho 高 5~11 倍，谐波被稀释
        // 到「谐波÷中频」只有 wecho 的 ~6%，听感因此只剩一半。
        // **中频被压低本来就是 wecho 让谐波突出的设计，不是缺陷** ——
        // 「频响平坦」基准不适用于故意失真的心理声学效果器。
        // 这里同时保留 dry/wet（wecho 无此参数）：amount=1.0 时 dry=0，等价纯替换。
        const float wet_sig =
            (mid_gain_ * 0.25f * mid +
             high_gain_ * 0.25f * high +
             2.4f * har * harmonic_) * post_gain_;

        channels[c][i] = x * dry + wet_sig * wet;
      }
    }

    meter_dry_ratio_ = dry;
    if (nch > 0) {
      // 低频 RMS 用第一声道近似（仅用于 UI 显示）
      double acc = 0.0;
      for (int i = 0; i < frames; ++i) {
        const double v = channels[0][i];
        acc += v * v;
      }
      meter_in_low_ = static_cast<float>(std::sqrt(acc / static_cast<double>(frames)));
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    switch (param_id) {
      case kParamAmount:
        amount_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamHarmonic:
        harmonic_ = std::clamp(value, 0.0f, 2.0f);
        break;
      case kParamMidGain:
        mid_gain_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamHighGain:
        high_gain_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamEnvelopeRate: {
        const float v = std::clamp(value, kEnvRateMin, kEnvRateMax);
        if (v != envelope_rate_) {
          envelope_rate_ = v;
          updateEnvelopeCoeffs();
        }
        break;
      }
      default:
        break;
    }
  }

  /** 计量：[0] 低频 RMS、[1] 谐波 dB、[2] 保留原低频比例 */
  void GetMeters(float* out) const {
    if (out == nullptr) return;
    out[0] = meter_in_low_;
    out[1] = meter_harmonic_db_;
    out[2] = meter_dry_ratio_;
  }

 private:
  /** 带通 = 2 阶 HP ×2 级联 + 2 阶 LP ×2 级联（等效 LR4×LR4，与 wecho 的 setBandPass 一致）。 */
  struct Band {
    Biquad hp[2];
    Biquad lp[2];

    void Configure(float hp_hz, float lp_hz, int sr) {
      hp[0].ConfigureTarget(hp_hz, sr, true);
      hp[1].ConfigureTarget(hp_hz, sr, true);
      lp[0].ConfigureTarget(lp_hz, sr, false);
      lp[1].ConfigureTarget(lp_hz, sr, false);
      for (auto* q : {&hp[0], &hp[1], &lp[0], &lp[1]}) q->CommitTarget();
    }
    void Reset() {
      for (auto* q : {&hp[0], &hp[1], &lp[0], &lp[1]}) q->ResetState();
    }
    float Process(float x) {
      x = hp[0].Process(x);
      x = hp[1].Process(x);
      x = lp[0].Process(x);
      x = lp[1].Process(x);
      return x;
    }
  };

  struct HighPass {
    Biquad hp[2];
    void Configure(float hz, int sr) {
      hp[0].ConfigureTarget(hz, sr, true);
      hp[1].ConfigureTarget(hz, sr, true);
      for (auto* q : {&hp[0], &hp[1]}) q->CommitTarget();
    }
    void Reset() {
      for (auto* q : {&hp[0], &hp[1]}) q->ResetState();
    }
    float Process(float x) {
      x = hp[0].Process(x);
      x = hp[1].Process(x);
      return x;
    }
  };

  /**
   * 固定分频点，只在 Prepare 时配置一次。
   * 不做系数 ramp：这些频率与参数无关（用户改不了 80/150/120/600），
   * 唯一会变的 envelopeRate 走一阶平滑系数而非 biquad，所以没有突变爆音风险。
   */
  void configureFixedFilters() {
    for (int c = 0; c < channels_; ++c) {
      const std::size_t ci = static_cast<std::size_t>(c);
      sub_band_[ci].Configure(kSubLoHz, kSubHiHz, sample_rate_);
      mid_band_[ci].Configure(kMidLoHz, kMidHiHz, sample_rate_);
      high_band_[ci].Configure(kHighHz, sample_rate_);
      harmonic_hp_[ci].Configure(kHarmonicHpHz, sample_rate_);
    }
  }

  void updateEnvelopeCoeffs() {
    const float sr = static_cast<float>(sample_rate_);
    // **按 `wecho yuanma` 对齐**：
    //   envelope_alpha = 2π×25/sr（固定，setEnvelopeRate 不更新它）
    //   lp_soft_alpha  = 2π×envelopeRate/sr（跟随「跟随速度」滑块）
    // 新版 studio_rack 把「两者都跟参数」当成 bug 修了，但用户实听对比的
    // 是 yuanma，所以这里刻意保留该行为 —— 不是遗忘，是有意对齐参考版本。
    env_alpha_ = std::clamp(
        2.0f * 3.14159265f * kYuanmaEnvHz / sr, 1e-5f, 1.0f);
    lp_soft_alpha_ = std::clamp(
        2.0f * 3.14159265f * envelope_rate_ / sr, 1e-5f, 1.0f);
  }

  int sample_rate_ = 48000;
  int max_frames_ = 128;
  int channels_ = 2;

  float amount_ = kDefaultAmount;
  float harmonic_ = kDefaultHarmonic;
  float mid_gain_ = kDefaultMidGain;
  float high_gain_ = kDefaultHighGain;
  float envelope_rate_ = kDefaultEnvelopeRate;

  bool bypass_ = true;   // 默认透传（与其它节点一致，持久化状态由引擎 Running 后补发）

  std::vector<Band> sub_band_;
  std::vector<Band> mid_band_;
  std::vector<HighPass> high_band_;
  std::vector<HighPass> harmonic_hp_;

  std::vector<float> lp_soft_;
  std::vector<float> env_lp_;
  std::vector<float> env_har_;
  std::vector<float> har_last_x_;
  std::vector<float> har_last_y_;

  float env_alpha_ = 0.0f;
  float lp_soft_alpha_ = 0.0f;
  float post_gain_ = 1.0f;

  float meter_in_low_ = 0.0f;
  float meter_harmonic_db_ = 0.0f;
  float meter_dry_ratio_ = 1.0f;
};

}  // namespace sonicraft::dsp
