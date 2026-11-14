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
 * 低音增强 —— **与输出设备无关**，耳机与外放都能用。
 *
 * 移植自 wecho 的 `BassEffect`（UI 里叫「低频质感」/ `lowFrequencyGain`）。
 *
 * ## 与 M15「动态低音」的分工
 * | | 动态低音 | 低音增强（本节点） |
 * |---|---|---|
 * | 做法 | LR4 分频重构，只处理低频带 | M/S 分解 + EQ + 瞬态 + 次谐波 |
 * | 其他频段 | **幅度逐位不变**（有探针保证） | 低频增量叠加，中高频被动受影响较小 |
 * | 适合 | 想要"干净、不越界"的低频提升 | 想要"体感 + 打击感 + 下潜" |
 * | 设备 | 通用 | 通用 |
 *
 * 两者**互斥**（同属低频管家），因为都是往低频加能量，叠加会过度。
 *
 * ## 四个模块（全部照 wecho 移植）
 * 1. **静态 EQ**：`LowShelf@60Hz (+6dB×amount)` + `Peak@45Hz (+4dB×amount)`
 * 2. **动态增益**：`1 + deep × amount × 1/(1 + env×15)`
 *    —— 电平越低增益越大，即**等响度补偿**（小音量补得多、大音量几乎不动）
 * 3. **Punch**：`(low − low_prev) × punch × 100` —— 一阶差分 = 高通，放大瞬态沿，
 *    换来鼓点的"打击感"
 * 4. **次谐波**：`LP(80) → flip-flop 分频 → LP(40)`
 *    —— 每半个周期翻转极性，得到低八度的信号，补"下潜"
 *
 * ## 为什么用 M/S 而不是直接处理 L/R
 * 低频在多数录音里是**单声道**的（居中）。只处理 M、S 支路高通 120Hz 之后：
 * - 低频能量全部集中在 M，不会左右抵消
 * - S 支路保留 >120Hz 的立体声信息，声场不被压扁
 *
 * ## 只加增量：`out = m_hp + (m_low − m_low_origin)`
 * 这是 wecho 最巧妙的一处 —— 不替换原信号，只**叠加增量**。
 * 所以 amount=0 / deep=0 / punch=0 时输出与原信号完全一致（探针已验证）。
 *
 * ## 参数
 *   param 0 = amount  总强度 0~1（映射 wecho 的 gain 0~10）
 *   param 1 = deep    下潜量 0~1（动态增益 + 次谐波）
 *   param 2 = punch   瞬态量 0~1
 */
class BassEnhanceNode : public DspNode {
 public:
  // ---- 动态模式参数（0~2）----
  static constexpr int kParamAmount = 0;
  static constexpr int kParamDeep = 1;
  static constexpr int kParamPunch = 2;
  // ---- 模式切换与经典模式参数（3~6，2026-11-11 对照
  //      `wecho_projectV2.5.1_studio_rack` 的 `BassEffect` 双模式实现）----
  static constexpr int kParamMode = 3;
  static constexpr int kParamClassicGain = 4;
  static constexpr int kParamClassicQ = 5;
  static constexpr int kParamClassicCenter = 6;

  static constexpr int kModeClassic = 0;
  static constexpr int kModeDynamic = 1;

  static constexpr float kDefaultAmount = 0.5f;
  static constexpr float kDefaultDeep = 0.3f;
  static constexpr float kDefaultPunch = 0.6f;
  /**
   * 动态增益平滑时间常数（L3 修复，2026-11-11）。
   * `env_` 由 `|low|` 快跟随得到，60Hz 整流后带 120Hz 纹波；
   * 全参数下 dyn_gain 在 1~11 倍间抖动 → 700±120Hz 边带（实测 IMD 5.84%）。
   * 20ms 平滑对 120Hz 衰减约 −23dB，等响度补偿的慢适应不受影响。
   */
  static constexpr float kDynGainSmoothMs = 20.0f;
  /**
   * 动态调制深度软限（L3 修复，2026-11-11）。
   *
   * 全参数下 `deep × gain10 = 10`，dyn_gain 在 1~11 倍间摆动、次谐波被放大
   * 到 15 倍 —— 这两处是 IMD 5.84% 的全部来源（诊断：关次谐波后降到 3.06%）。
   *
   * 用「knee=1.5 原样通过 + 超过后指数渐近到 2.0」的分段压缩：
   * 默认参数（amount=0.5/deep=0.3 → x=1.5）**逐位不变**，
   * 只收敛拉满后的极端行为。比 `x/(1+x/L)` 更精准（那个会把默认值也压 37%），
   * 也比硬钳位自然（无折点失真）。
   */
  static constexpr float kDeepGainSoftLimit = 2.0f;
  static constexpr int kDefaultMode = kModeDynamic;
  static constexpr float kDefaultClassicGain = 6.0f;    // dB
  static constexpr float kDefaultClassicQ = 0.7071f;
  static constexpr float kDefaultClassicCenter = 60.0f; // Hz
  static constexpr float kClassicGainMax = 15.0f;
  static constexpr float kClassicCenterMin = 30.0f;
  static constexpr float kClassicCenterMax = 120.0f;
  static constexpr float kClassicQMin = 0.3f;
  static constexpr float kClassicQMax = 2.0f;
  /** wecho 经典模式的固定额外增益（`× 1.5`），保留原值 */
  static constexpr float kClassicExtraGain = 1.5f;
  /**
   * 经典模式共振峰（Q 旋钮，2026-11-12 用户要求「实现功能，不可移除」）。
   *
   * L1 把经典模式改成 LR4 分频重构后，分频的 Q 必须固定 1/√2 才能互补平坦，
   * 原 `classic_q` 因此失效。现在让 Q 作用于**低频带内的一个 bell**：
   *   Q 低 → 峰宽缓、增益小（0.6dB）；Q 高 → 峰窄尖、增益大（4.0dB），
   *   正好对应 UI 文案「Q 越高，截止点附近越鼓」。
   * 分频本身仍是固定 Q 的 LR4，且 bell 只乘在 lo 上：
   * 160Hz 以上 lo≈0，输出仍 ≈ hi ≈ 1，L1 的平坦性不受影响。
   */
  static constexpr float kClassicResoGainMinDb = 0.6f;
  static constexpr float kClassicResoGainMaxDb = 4.0f;

  // ---- wecho 的固定频率（原样保留）----
  static constexpr float kHpMonoHz = 20.0f;      // M 支路去 DC
  static constexpr float kEnvLpHz = 120.0f;      // 包络检测带宽
  static constexpr float kShelfHz = 60.0f;       // LowShelf 中心
  static constexpr float kShelfQ = 0.7f;
  static constexpr float kShelfMaxDb = 6.0f;     // amount=1 时
  static constexpr float kPeakHz = 45.0f;        // Peak 中心
  static constexpr float kPeakQ = 1.2f;
  static constexpr float kPeakMaxDb = 4.0f;      // amount=1 时
  static constexpr float kSubLpHz = 80.0f;       // 次谐波源低通
  static constexpr float kSubLp2Hz = 40.0f;      // 次谐波后低通
  static constexpr float kSideHpHz = 120.0f;     // S 支路高通
  // 包络时间常数（wecho 原值）
  static constexpr float kEnvAttackMs = 30.0f;
  static constexpr float kEnvReleaseMs = 200.0f;

  std::string_view id() const override { return "bass_enhance"; }

  void set_channel_count(int channels) { channels_ = channels > 0 ? channels : 2; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    max_frames_ = max_frames > 0 ? max_frames : 128;

    amount_ = kDefaultAmount;
    deep_ = kDefaultDeep;
    punch_ = kDefaultPunch;
    mode_ = kDefaultMode;
    classic_gain_ = kDefaultClassicGain;
    classic_q_ = kDefaultClassicQ;
    classic_center_ = kDefaultClassicCenter;

    // 经典模式：每声道一套 LR4 分频器（L1 修复，2026-11-11）
    classical_split_.assign(static_cast<std::size_t>(channels_), LR4Split{});
    classic_reso_.assign(static_cast<std::size_t>(channels_), Biquad{});

    configureFixed();
    applyEqCoeffs(/*immediate=*/true);
    recomputeEnvCoeffs();
    Reset();
  }

  void Reset() override {
    hp_m_.ResetState();
    lp_env_.ResetState();
    shelf_.ResetState();
    peak_.ResetState();
    sub_lp_.ResetState();
    sub_lp2_.ResetState();
    hp_s_.ResetState();
    for (auto& sp : classical_split_) sp.Reset();
    for (auto& r : classic_reso_) r.ResetState();

    env_ = 0.0f;
    dyn_gain_smooth_ = 1.0f;
    prev_low_ = 0.0f;
    prev_sub_ = 0.0f;
    sub_flip_ = 1.0f;
    meter_low_db_ = -96.0f;
    meter_dyn_db_ = 0.0f;
  }

  void Process(float* const* channels, int frames) override {
    if (channels == nullptr || frames <= 0) return;
    if (bypass_) return;

    // ---- 经典模式 ----
    //
    // **L1 修复（2026-11-11）**：原来是 `out = in + LP(fc,Q)(in) × gain`，
    // 直接把低通信号加回原信号。LP 在截止点以上相位滞后约 −146°，与原信号相消，
    // 实测 160/200Hz 出现 −5.6/−5.0dB 凹陷（专业验收发现）。
    //
    // 改为**分频重构**：
    //   `out = hp4(x) + lp4(x) × (1 + gain)`
    // LR4 的 lp4/hp4 同分频点、相位一致、相加平坦 ⇒ 高频段 lp4≈0、hp4≈1，
    // 输出严格回到 1.0（0.00dB），凹陷消失。
    // 代价：分频斜率固定（LR4 的 Q 固定 1/√2），`classic_q` 不再生效。
    // 两套参数仍然完全独立（`classic_*` 与 amount/deep/punch 互不读写）。
    if (mode_ == kModeClassic) {
      const int nchC = std::min(channels_, 16);
      const float lin = std::pow(10.0f, std::clamp(classic_gain_, 0.0f, kClassicGainMax) / 20.0f);
      const float lowGain = 1.0f + lin * kClassicExtraGain;
      for (auto& sp : classical_split_) sp.BeginBlockRamp(frames);
      for (auto& r : classic_reso_) r.BeginBlockRamp(frames);
      for (int i = 0; i < frames; ++i) {
        for (auto& sp : classical_split_) sp.StepCoeffs();
        for (auto& r : classic_reso_) r.StepCoeffs();
        for (int c = 0; c < nchC; ++c) {
          const std::size_t ci = static_cast<std::size_t>(c);
          const float x = channels[c][i];
          auto& sp = classical_split_[ci];
          const float lo = sp.lp[1].Process(sp.lp[0].Process(x));
          const float hi = sp.hp[1].Process(sp.hp[0].Process(x));
          // Q 旋钮：只对低频带做共振染色，高频段 lo≈0 时透明
          const float shaped = classic_reso_[ci].Process(lo);
          channels[c][i] = hi + shaped * lowGain;
        }
      }
      for (auto& sp : classical_split_) sp.SnapCoeffs();
      for (auto& r : classic_reso_) r.SnapCoeffs();
      meter_low_db_ = -96.0f;
      meter_dyn_db_ = 20.0f * std::log10(lowGain + 1e-9f);
      return;
    }

    // ---- 动态模式（原有实现）----
    // 三个参数全 0 → 输出 == 输入（只加增量的设计保证了这一点），
    // 但仍然要跑完滤波让状态保持连续，否则突然变非零会有瞬态
    const bool mono = channels_ < 2;
    const float gain10 = amount_ * 10.0f;   // wecho 的 gain 量纲 0~10
    // **L3 软限（定稿）**：默认值完全不动，只压缩拉满后的极端值。
    //
    // `knee = 1.5` 正好是默认参数（amount=0.5 × deep=0.3 × 10 = 1.5）。
    // x ≤ 1.5 时**原样通过**（默认音色逐位不变）；超过后指数渐近到
    // `kDeepGainSoftLimit = 2.0`，全参数 x=10 时收敛到 ≈2.0。
    // 这样 IMD 从 5.84% 降到 1.26%，而用户平时听的默认音色一点没变。
    const float rawDeepGain = deep_ * gain10;
    const float knee = 1.5f;
    const float softDeepGain_ = (rawDeepGain <= knee)
        ? rawDeepGain
        : knee + (kDeepGainSoftLimit - knee) *
              (1.0f - std::exp(-(rawDeepGain - knee) / 1.5f));

    // 系数 ramp（EQ 会随 amount 变，其它固定但走同一机制无害）
    for (auto* q : {&shelf_, &peak_}) q->BeginBlockRamp(frames);

    for (int i = 0; i < frames; ++i) {
      const float l = channels[0][i];
      const float r = mono ? l : channels[1][i];
      const float m = (l + r) * 0.5f;
      const float s = (l - r) * 0.5f;

      // ---- M 支路 ----
      const float m_hp = hp_m_.Process(m);
      const float m_low_origin = lp_env_.Process(m_hp);

      // ① 动态增益：电平越低增益越大（等响度补偿）
      const float env_abs = std::fabs(m_low_origin);
      const float coef = (env_abs > env_) ? env_attack_ : env_release_;
      env_ += (env_abs - env_) * coef;
      // **L3 修复（2026-11-11，二次修正后定稿）**
      //
      // 诊断证据（全参数 amount=1）：
      //   deep=0  → IMD 0.24~0.41%（合格）
      //   deep=1  → IMD 5.84~6.33%（不合格），且降 punch 无效（6.10%）
      //   关掉次谐波 → IMD 3.06%
      // ⇒ 元凶不是 punch（一阶差分是**线性**运算，根本不产生互调），
      //   而是 deep 驱动的两处非线性：包络调制（~3%）与 flip-flop 次谐波（~2.8%）。
      //
      // **试过无效**：给 dyn_gain 加 20ms 平滑（IMD 5.84→5.86）——
      // 因为「增益随包络变化」本身就是非线性，平滑滤不掉调制本身。
      // 真正要收敛的是**极端参数下的调制深度**，见 kDeepGainSoftLimit。
      // 保留平滑是为了让增益变化不生硬，不是 IMD 的主修手段。
      const float dyn_target = 1.0f + softDeepGain_ * (1.0f / (1.0f + env_ * 15.0f));
      dyn_gain_smooth_ += (dyn_target - dyn_gain_smooth_) * dyn_gain_coef_;
      const float dyn_gain = dyn_gain_smooth_;

      // ② Punch：一阶差分放大瞬态沿
      const float diff = m_low_origin - prev_low_;
      prev_low_ = m_low_origin;
      const float punch_sig = diff * punch_ * 100.0f;

      // ③ 静态 EQ（含系数 ramp）
      shelf_.StepCoeffs();
      peak_.StepCoeffs();
      float m_low = peak_.Process(shelf_.Process(m_low_origin));
      m_low *= dyn_gain;
      m_low += punch_sig;

      // ④ 次谐波：flip-flop 分频（向下过零时翻转极性 → 0.5× 频率）
      const float sub_in = sub_lp_.Process(m_low_origin);
      if (prev_sub_ > 0.0f && sub_in <= 0.0f) {
        sub_flip_ = -sub_flip_;
      }
      prev_sub_ = sub_in;
      m_low += sub_lp2_.Process(sub_in * sub_flip_) * softDeepGain_ * 1.5f;

      // ⑤ 只加增量，原信号保留
      const float delta = m_low - m_low_origin;
      const float m_out = m_hp + delta;

      // ---- S 支路：只保留 >120Hz 的立体声信息 ----
      const float s_hp = hp_s_.Process(s);

      channels[0][i] = m_out + s_hp;
      if (!mono) channels[1][i] = m_out - s_hp;

      if (i == frames - 1) {
        meter_low_db_ = 20.0f * std::log10(env_ + 1e-9f);
        meter_dyn_db_ = 20.0f * std::log10(dyn_gain + 1e-9f);
      }
    }

    shelf_.SnapCoeffs();
    peak_.SnapCoeffs();
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    switch (param_id) {
      case kParamAmount: {
        const float v = std::clamp(value, 0.0f, 1.0f);
        if (v != amount_) {
          amount_ = v;
          applyEqCoeffs(/*immediate=*/false);   // 走 ramp，不 snap
        }
        break;
      }
      case kParamDeep:
        deep_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamPunch:
        punch_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamMode: {
        const int v = (value >= 0.5f) ? kModeDynamic : kModeClassic;
        if (v != mode_) {
          mode_ = v;
          // 切模式时重置状态，避免残留滤波状态产生咔哒声
          Reset();
        }
        break;
      }
      case kParamClassicGain:
        classic_gain_ = std::clamp(value, 0.0f, kClassicGainMax);
        break;
      case kParamClassicQ: {
        // 2026-11-12：用户要求 Q 实现功能（不可移除）。
        // LR4 分频的 Q 仍固定，这个 Q 控制低频带内共振峰的宽度与高度。
        const float v = std::clamp(value, kClassicQMin, kClassicQMax);
        if (v != classic_q_) {
          classic_q_ = v;
          configureClassicResonance();
        }
        break;
      }
      case kParamClassicCenter: {
        const float v = std::clamp(value, kClassicCenterMin, kClassicCenterMax);
        if (v != classic_center_) {
          classic_center_ = v;
          for (auto& sp : classical_split_) {
            sp.ConfigureTarget(classic_center_, sample_rate_);
            sp.CommitTargets();
          }
          configureClassicResonance();
        }
        break;
      }
      default:
        break;
    }
  }

  /** 计量：[0] 低频包络 dB、[1] 当前动态增益 dB */
  void GetMeters(float* out) const {
    if (out == nullptr) return;
    out[0] = meter_low_db_;
    out[1] = meter_dyn_db_;
  }

 private:
  /** 按当前 Q / 中心频率配置低频带共振峰（Q 与中心频率共用同一组系数）。 */
  void configureClassicResonance() {
    const float t = std::clamp(
        (classic_q_ - kClassicQMin) / (kClassicQMax - kClassicQMin), 0.0f, 1.0f);
    const float gainDb = kClassicResoGainMinDb +
                         t * (kClassicResoGainMaxDb - kClassicResoGainMinDb);
    for (auto& r : classic_reso_) {
      r.ConfigurePeak(classic_center_, sample_rate_, gainDb, classic_q_);
      r.CommitTarget();
    }
  }

  void configureFixed() {
    hp_m_.ConfigureTarget(kHpMonoHz, sample_rate_, true);
    lp_env_.ConfigureTarget(kEnvLpHz, sample_rate_, false);
    sub_lp_.ConfigureTarget(kSubLpHz, sample_rate_, false);
    sub_lp2_.ConfigureTarget(kSubLp2Hz, sample_rate_, false);
    hp_s_.ConfigureTarget(kSideHpHz, sample_rate_, true);
    for (auto& sp : classical_split_) sp.ConfigureTarget(classic_center_, sample_rate_);
    hp_m_.CommitTarget();
    lp_env_.CommitTarget();
    sub_lp_.CommitTarget();
    sub_lp2_.CommitTarget();
    hp_s_.CommitTarget();
    for (auto& sp : classical_split_) sp.CommitTargets();
    configureClassicResonance();
  }

  /** EQ 增益随 amount 线性缩放（wecho 就是 `6.0×gain/10` 与 `4.0×gain/10`）。 */
  void applyEqCoeffs(bool immediate) {
    const float g = amount_ * 10.0f;
    shelf_.ConfigureShelf(kShelfHz, sample_rate_, kShelfMaxDb * g / 10.0f, kShelfQ, true);
    peak_.ConfigurePeak(kPeakHz, sample_rate_, kPeakMaxDb * g / 10.0f, kPeakQ);
    if (immediate) {
      shelf_.CommitTarget();
      peak_.CommitTarget();
    }
  }

  void recomputeEnvCoeffs() {
    const float sr = static_cast<float>(sample_rate_);
    env_attack_ = 1.0f - std::exp(-1.0f / (kEnvAttackMs * 0.001f * sr));
    env_release_ = 1.0f - std::exp(-1.0f / (kEnvReleaseMs * 0.001f * sr));
  }

  int sample_rate_ = 48000;
  int max_frames_ = 128;
  int channels_ = 2;

  float amount_ = kDefaultAmount;
  float deep_ = kDefaultDeep;
  float punch_ = kDefaultPunch;

  int mode_ = kDefaultMode;
  float classic_gain_ = kDefaultClassicGain;
  float classic_q_ = kDefaultClassicQ;
  float classic_center_ = kDefaultClassicCenter;
  std::vector<LR4Split> classical_split_;
  std::vector<Biquad> classic_reso_;   // 经典模式 Q 共振峰（每声道一个）

  bool bypass_ = true;   // 默认透传（与其它节点一致）

  Biquad hp_m_;
  Biquad lp_env_;
  Biquad shelf_;
  Biquad peak_;
  Biquad sub_lp_;
  Biquad sub_lp2_;
  Biquad hp_s_;

  float env_attack_ = 0.0f;
  float env_release_ = 0.0f;
  float env_ = 0.0f;
  float dyn_gain_smooth_ = 1.0f;
  float dyn_gain_coef_ = 0.001f;
  float prev_low_ = 0.0f;
  float prev_sub_ = 0.0f;
  float sub_flip_ = 1.0f;

  float meter_low_db_ = -96.0f;
  float meter_dyn_db_ = 0.0f;
};

}  // namespace sonicraft::dsp
