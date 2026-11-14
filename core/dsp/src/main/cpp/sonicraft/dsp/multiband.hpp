/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>
#include <vector>

namespace sonicraft::dsp {

/**
 * 三频段压限器（M8，链序 Comp → Multiband → Reverb；与单段 Compressor 互斥
 * 运行，互斥联动在 Kotlin 层，本节点为独立节点）。
 *
 * 结构（LR4 Linkwitz-Riley 级联树，feed-forward）：
 *   x ─┬─ LP4(fc1) ────────────────→ Low 段
 *      └─ HP4(fc1) ─┬─ LP4(fc2) ──→ Mid 段
 *                   └─ HP4(fc2) ──→ High 段
 *
 *  - LR4 = 两个级联 Butterworth 二阶节（Q=1/√2）；同分频点的 LP4/HP4 相位一致，
 *    逐点和幅度恒为单位（|LP+HP| = 1 ∀ω）→ 分频不染色，端到端「合成=输入」
 *    可作为黄金参照（host-harness 9c）。
 *  - 分频点参数变化：块级对 fc 一阶平滑（τ=15ms，块头重算系数）+ 块内系数逐样本
 *    线性 ramp（DF1 延迟线存纯信号样本，系数插值安全）。2026-10-25 真机实听
 *    （M8e，NX733J）发现 TDF2 块级系数跳变在拖 fc 滑块时有可闻毛刺——TDF2 的
 *    z1/z2 是按旧系数积累的混合项，系数跳变瞬间状态失配产生块级咔嗒，块 RMS
 *    判据（原 9g）测不出、耳朵听得出；DF1+ramp 样本级连续后消除。
 *  - 段缓冲可通过 band_buffer() 读取（诊断/harness 用，存分频后、加增益前的段信号）。
 *  - 频段动力学（每段独立，与 CompressorNode 同拓扑家族）：
 *      |band| → 峰值包络(瞬时攻击+指数衰减) → dB 域静态曲线(thr/ratio, soft knee)
 *             → 增益平滑(attack 降/release 升分支) → ×10^((g+gain_db)/20) → 段输出
 *    soft knee（Pirkle 抛物线）：knee 区 y = x + (1/R−1)(x−T+W/2)²/(2W)，W=0 退化硬 knee；
 *    knee 区边界与硬区函数值、斜率双连续。
 *  - 立体声联动（M8f，2026-10-25，移植 wecho MultiBandLimiter 思路）：每段一个
 *    共享包络/增益，检测取跨声道 max(|L|,|R|)——消除单侧信号被独立检测时的
 *    声像移动（左压右不压 → 人声/元素跑位）。单声道退化为自身，无特判。
 *  - 段间防重新过冲（M8f，wecho 段限+总线限双层思路的轻量版）：三段独立压缩
 *    相加，各段包络相位重排下合成可瞬时重过 0dBFS；合成后联动峰值检测，
 *    超 kProtCeil(1.0) 瞬时回拉、τ=250ms 恢复。平时透明（LR4 合成平坦、正常
 *    峰 ≤ 输入峰），只在深压缩瞬态兜一把，减轻末级 LimiterNode 负担。
 *  - 尾部 DC blocker（M8f，wecho 思路）：trim 后一阶 DC 阻断（fc≈2Hz，
 *    R=exp(−2π·2/fs)），去除多段相加的直流漂移；@20Hz 衰减 ≈0.04dB，在 9a
 *    平坦度 0.1dB 预算内（9a @20Hz 实测见 harness 日志）。
 *  - 无 lookahead：瞬时过冲由段间保护（M8f）+ 链路末级 LimiterNode(index 8)
 *    兜底（分层动态处理惯例）。
 *
 * 参数约定（node 内自洽；每段六参：threshold/ratio/attack/release/knee/gain，
 * knee 三段独立——2026-10-25 用户拍板）：
 *      param 0-5  = Low:  thr(dB) / ratio(:1) / atk(ms) / rel(ms) / knee(dB) / gain(dB)
 *      param 6-11 = Mid:  同上
 *      param 12-17 = High: 同上
 *      param 18 = fc1（Low|Mid 分频点 Hz）
 *      param 19 = fc2（Mid|High 分频点 Hz）
 *      总开关走 SetBypass（节点惯例，不占 param_id）。
 *
 * 里程碑：频段动力学已接入（param 0-17 生效）；引擎接入与 Kotlin 镜像随 M8c。
 */
class MultibandNode : public DspNode {
 public:
  static constexpr int kBands = 3;
  // ：防过冲天花板（线性，比下游 LimiterNode −0.45dB 松，只在极端兜底）
  static constexpr float kProtCeil = 1.0f;
  // ：DC blocker 转折频率（2Hz：@20Hz 衰减 ≈0.04dB，9a 预算内）
  static constexpr float kDcFcHz = 2.0f;
  /** 检测包络的固定释放时间（ms）：与单段 CompressorNode::kEnvReleaseMs 同口径 */
  static constexpr float kEnvReleaseMs = 40.0f;

  static constexpr int kParamLowThresholdDb = 0;
  static constexpr int kParamLowRatio = 1;
  static constexpr int kParamLowAttackMs = 2;
  static constexpr int kParamLowReleaseMs = 3;
  static constexpr int kParamLowKneeDb = 4;
  static constexpr int kParamLowGainDb = 5;
  static constexpr int kParamMidThresholdDb = 6;
  static constexpr int kParamMidRatio = 7;
  static constexpr int kParamMidAttackMs = 8;
  static constexpr int kParamMidReleaseMs = 9;
  static constexpr int kParamMidKneeDb = 10;
  static constexpr int kParamMidGainDb = 11;
  static constexpr int kParamHighThresholdDb = 12;
  static constexpr int kParamHighRatio = 13;
  static constexpr int kParamHighAttackMs = 14;
  static constexpr int kParamHighReleaseMs = 15;
  static constexpr int kParamHighKneeDb = 16;
  static constexpr int kParamHighGainDb = 17;
  static constexpr int kParamSplit1Hz = 18;
  static constexpr int kParamSplit2Hz = 19;

  // 母带温和式默认（docs/M8_MULTIBAND_LIMITER_DESIGN.md ）
  static constexpr float kDefaultThrLow = -26.0f, kDefaultThrMid = -22.0f, kDefaultThrHigh = -20.0f;
  static constexpr float kDefaultRatioLow = 2.5f, kDefaultRatioMid = 2.0f, kDefaultRatioHigh = 2.0f;
  static constexpr float kDefaultAtkLow = 20.0f, kDefaultAtkMid = 15.0f, kDefaultAtkHigh = 5.0f;
  static constexpr float kDefaultRelLow = 180.0f, kDefaultRelMid = 150.0f, kDefaultRelHigh = 120.0f;
  static constexpr float kDefaultKnee = 6.0f;
  static constexpr float kDefaultFc1Hz = 200.0f;
  static constexpr float kDefaultFc2Hz = 3500.0f;

  std::string_view id() const override { return "multiband"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    max_frames_ = max_frames;
    fc1_cur_ = fc1_tgt_ = kDefaultFc1Hz;
    fc2_cur_ = fc2_tgt_ = kDefaultFc2Hz;
    dyn_[0] = BandParams{kDefaultThrLow, kDefaultRatioLow, kDefaultAtkLow,
                         kDefaultRelLow, kDefaultKnee, 0.0f};
    dyn_[1] = BandParams{kDefaultThrMid, kDefaultRatioMid, kDefaultAtkMid,
                         kDefaultRelMid, kDefaultKnee, 0.0f};
    dyn_[2] = BandParams{kDefaultThrHigh, kDefaultRatioHigh, kDefaultAtkHigh,
                         kDefaultRelHigh, kDefaultKnee, 0.0f};
    for (auto& d : dyn_) d.recomputeRates(sample_rate_);
    splits1_.assign(static_cast<std::size_t>(channels_), LR4Split{});
    splits2_.assign(static_cast<std::size_t>(channels_), LR4Split{});
    recomputeSplits(-1);  // Prepare：当前=目标直接落位，无 ramp
    const std::size_t st = static_cast<std::size_t>(kBands);
    env_.assign(st, 0.0f);      // M8f 联动：每段一份（不再 ×channels）
    gain_db_.assign(st, 0.0f);
    const std::size_t need =
        static_cast<std::size_t>(kBands) * channels_ * static_cast<std::size_t>(max_frames_);
    band_buf_.assign(need, 0.0f);
    dc_x_.assign(static_cast<std::size_t>(channels_), 0.0f);
    dc_y_.assign(static_cast<std::size_t>(channels_), 0.0f);
    dc_r_ = std::exp(-2.0f * kPi * kDcFcHz / static_cast<float>(sample_rate_));
    prot_env_ = 0.0f;
    prot_rel_ = 1.0f - std::exp(-1.0f / (0.25f * static_cast<float>(sample_rate_)));  // τ=250ms
  }

  void Reset() override {
    for (auto& s : splits1_) s.Reset();
    for (auto& s : splits2_) s.Reset();
    for (auto& v : env_) v = 0.0f;
    for (auto& v : gain_db_) v = 0.0f;
    for (auto& v : dc_x_) v = 0.0f;
    for (auto& v : dc_y_) v = 0.0f;
    prot_env_ = 0.0f;
  }

  void Process(float* const* channels, int frames) override {
    if (bypass_) return;  // A/B 标尺：bypass = 原样透传（不处理，与 ParametricEqNode 同惯例）

    // 块头：分频点一阶平滑逼近（τ=15ms），变化才重算系数
    const float alpha =
        1.0f - std::exp(-static_cast<float>(frames) / (0.015f * static_cast<float>(sample_rate_)));
    bool recalc = false;
    if (std::fabs(fc1_tgt_ - fc1_cur_) > fc1_cur_ * 1e-4f) {
      fc1_cur_ += (fc1_tgt_ - fc1_cur_) * alpha;
      recalc = true;
    }
    if (std::fabs(fc2_tgt_ - fc2_cur_) > fc2_cur_ * 1e-4f) {
      fc2_cur_ += (fc2_tgt_ - fc2_cur_) * alpha;
      recalc = true;
    }
    if (recalc) recomputeSplits(frames);

    for (int c = 0; c < channels_; ++c) {
      float* ch = channels[c];
      float* lo = bandData(0, c);
      float* mid = bandData(1, c);
      float* hi = bandData(2, c);
      LR4Split& s1 = splits1_[static_cast<std::size_t>(c)];
      LR4Split& s2 = splits2_[static_cast<std::size_t>(c)];
      for (int n = 0; n < frames; ++n) {
        if (recalc) {  // 系数逐样本 ramp：分频系数对信号流样本级连续
          s1.StepCoeffs();
          s2.StepCoeffs();
        }
        const float x = ch[n];
        const float low = s1.lp[0].Process(s1.lp[1].Process(x));
        const float hp1 = s1.hp[0].Process(s1.hp[1].Process(x));
        lo[n] = low;
        mid[n] = s2.lp[0].Process(s2.lp[1].Process(hp1));
        hi[n] = s2.hp[0].Process(s2.hp[1].Process(hp1));
      }
      if (recalc) {
        s1.SnapCoeffs();
        s2.SnapCoeffs();
      }
    }

    // 频段动力学（立体声联动）：每段一个共享包络/增益，检测取跨声道
    // max(|L|,|R|)——单声道时 max 即自身，无特判。合成 → 段间防过冲 → DC。
    {
      float env0 = env_[0], env1 = env_[1], env2 = env_[2];
      float g0 = gain_db_[0], g1 = gain_db_[1], g2 = gain_db_[2];
      const BandParams& d0 = dyn_[0];
      const BandParams& d1 = dyn_[1];
      const BandParams& d2 = dyn_[2];
      float prot = prot_env_;
      // 计量累加器：块内峰值，块尾写回成员供 UI 读
      float pk0 = 0.0f, pk1 = 0.0f, pk2 = 0.0f;
      float op0 = 0.0f, op1 = 0.0f, op2 = 0.0f;
      for (int n = 0; n < frames; ++n) {
        float a0 = 0.0f, a1 = 0.0f, a2 = 0.0f;
        for (int c = 0; c < channels_; ++c) {
          const float l = std::fabs(bandData(0, c)[n]);
          const float m = std::fabs(bandData(1, c)[n]);
          const float h = std::fabs(bandData(2, c)[n]);
          if (l > a0) a0 = l;
          if (m > a1) a1 = m;
          if (h > a2) a2 = h;
        }
        const float gl0 = BandStep(d0, a0, env0, g0);
        const float gl1 = BandStep(d1, a1, env1, g1);
        const float gl2 = BandStep(d2, a2, env2, g2);
        // 计量：a0/a1/a2 已是跨声道 max|·|，乘上该段增益即段输出
        if (a0 > pk0) pk0 = a0;
        if (a1 > pk1) pk1 = a1;
        if (a2 > pk2) pk2 = a2;
        const float o0 = a0 * gl0, o1 = a1 * gl1, o2 = a2 * gl2;
        if (o0 > op0) op0 = o0;
        if (o1 > op1) op1 = o1;
        if (o2 > op2) op2 = o2;
        for (int c = 0; c < channels_; ++c) {
          channels[c][n] = bandData(0, c)[n] * gl0 + bandData(1, c)[n] * gl1 +
                           bandData(2, c)[n] * gl2;
        }
        // 尾部 DC blocker（先 DC 后 trim：逐样本乘法可交换，数学等价，
        // 但保护钳制的天花板因此就是最终输出天花板——若先 trim 后 DC，
        // DC 高通会在钳制波形上再抬 ~0.13% 破坏 1.0 上限）
        for (int c = 0; c < channels_; ++c) {
          float* ch = channels[c];
          const std::size_t ci = static_cast<std::size_t>(c);
          const float x = ch[n];
          const float out = (x - dc_x_[ci]) + dc_r_ * dc_y_[ci];
          dc_x_[ci] = x;
          dc_y_[ci] = out;
          ch[n] = out;
        }
        // 段间防重新过冲（M8f）：DC 后峰值联动检测，超 ceil 瞬时回拉、包络
        // τ=250ms 恢复；正常信号峰 ≤ 输入峰 → 永不触发（透明）
        float ay = 0.0f;
        for (int c = 0; c < channels_; ++c) {
          const float v = std::fabs(channels[c][n]);
          if (v > ay) ay = v;
        }
        if (ay > prot) {
          prot = ay;
        } else {
          prot += (ay - prot) * prot_rel_;
        }
        const float trim = (prot > kProtCeil) ? kProtCeil / prot : 1.0f;
        if (trim < 1.0f) {
          for (int c = 0; c < channels_; ++c) {
            channels[c][n] *= trim;
          }
        }
      }
      env_[0] = env0;
      env_[1] = env1;
      env_[2] = env2;
      gain_db_[0] = g0;
      gain_db_[1] = g1;
      gain_db_[2] = g2;
      prot_env_ = prot;
      // 计量写回：块级峰值，供 UI 绘制 IN/GR/OUT 电平表
      band_in_pk_[0] = pk0;
      band_in_pk_[1] = pk1;
      band_in_pk_[2] = pk2;
      band_out_pk_[0] = op0;
      band_out_pk_[1] = op1;
      band_out_pk_[2] = op2;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  // ==== 计量访问器（2026-11-08，多段压限 UI 的 IN/GR/OUT 电平表）====
  // band: 0=低 1=中 2=高。peak 为线性幅值（0~1+），GR 为 dB（负值=压缩中）。
  // 均为块级快照，UI 按 ~10fps 轮询即可；写入只在音频线程，读取为 relaxed 语义。
  float DiagnBandInPeak(int band) const {
    return (band >= 0 && band < 3) ? band_in_pk_[band] : 0.0f;
  }
  float DiagnBandOutPeak(int band) const {
    return (band >= 0 && band < 3) ? band_out_pk_[band] : 0.0f;
  }
  float DiagnBandGrDb(int band) const {
    if (band < 0 || band >= 3) return 0.0f;
    const std::size_t i = static_cast<std::size_t>(band);
    return (i < gain_db_.size()) ? gain_db_[i] : 0.0f;
  }

  void SetParam(int param_id, float value) override {
    if (param_id >= kParamLowThresholdDb && param_id <= kParamHighGainDb) {
      const int band = param_id / 6;
      const int sub = param_id % 6;
      BandParams& d = dyn_[band];
      switch (sub) {
        case 0: d.thr_db = Clamp(value, -60.0f, 0.0f); break;
        case 1: d.ratio = Clamp(value, 1.0f, 20.0f); break;
        case 2: d.atk_ms = Clamp(value, 0.1f, 100.0f); break;
        case 3: d.rel_ms = Clamp(value, 5.0f, 1000.0f); break;
        case 4: d.knee_db = Clamp(value, 0.0f, 12.0f); break;
        case 5: d.gain_db = Clamp(value, -12.0f, 12.0f); break;
      }
      d.recomputeRates(sample_rate_);
      return;
    }
    switch (param_id) {
      case kParamSplit1Hz:
        if (value > 0.0f) fc1_tgt_ = value;
        break;
      case kParamSplit2Hz:
        if (value > 0.0f) fc2_tgt_ = value;
        break;
      default:
        break;
    }
  }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) {
    channels_ = channels;
  }

  /** 频段缓冲读取（诊断/验证用）：band 0=Low 1=Mid 2=High；仅 Prepare 后有效 */
  const float* band_buffer(int band, int channel) const {
    return bandData(band, channel);
  }

 private:
  // DF1 biquad + 逐样本系数 ramp（2026-10-25 毛刺修复）。
  // TDF2 的 z1/z2 是按旧系数积累的混合项，系数块级跳变瞬间状态失配 → 每块一次
  // 咔嗒（真机拖 fc 滑块可闻毛刺）；DF1 延迟线 x1/x2/y1/y2 是纯信号样本，系数
  // 插值安全。块内从当前系数线性 ramp 到目标系数，样本级连续 → 零毛刺。
  struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;  // 当前系数
    float tb0 = 1.0f, tb1 = 0.0f, tb2 = 0.0f, ta1 = 0.0f, ta2 = 0.0f;  // 本块目标
    float db0 = 0.0f, db1 = 0.0f, db2 = 0.0f, da1 = 0.0f, da2 = 0.0f;  // 每样本增量
    float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;

    float Process(float x) {
      const float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
      x2 = x1;
      x1 = x;
      y2 = y1;
      y1 = y;
      return y;
    }

    // RBJ cookbook，Q=1/√2（Butterworth 二阶）；hp=false 低通 / true 高通。
    // 只写目标系数，当前系数由 BeginBlockRamp/StepCoeffs 逼近
    void ConfigureTarget(float fc, int sample_rate, bool hp) {
      const float w0 = 2.0f * kPi * fc / static_cast<float>(sample_rate);
      const float cw = std::cos(w0);
      const float alpha = std::sin(w0) * 0.70710678f;  // sin(w0)/(2Q), Q=1/√2
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

    // 块尾精确落位（消浮点累积误差），静止块增量归零
    void SnapCoeffs() {
      b0 = tb0;
      b1 = tb1;
      b2 = tb2;
      a1 = ta1;
      a2 = ta2;
      db0 = db1 = db2 = da1 = da2 = 0.0f;
    }

    // 直接落位（Prepare/Reset：无 ramp，当前=目标）
    void CommitTarget() {
      SnapCoeffs();
    }

    void ResetState() {
      x1 = x2 = y1 = y2 = 0.0f;
    }
  };

  // LR4 分频对：LP4 = lp[0]→lp[1]，HP4 = hp[0]→hp[1]（同系数级联两次）
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

  static constexpr float kPi = 3.141592653589793f;

  // 每段动力学参数 + 由 ms 导出的平滑系数（ParametricEqNode SetParam 钳制惯例）
  struct BandParams {
    float thr_db;
    float ratio;
    float atk_ms;
    float rel_ms;
    float knee_db;
    float gain_db;
    float atk_coef = 0.0f;
    float rel_coef = 0.0f;
    /**
     * 包络专用释放系数（2026-11-09 新增）。
     *
     * **为什么必须与 rel_coef 分开**：原来包络跟随和增益平滑共用 rel_coef，
     * 等于两个相同的一阶低通串联——阶跃响应到 63% 需约 2.68τ。
     * 实测 release 设 150ms 时实际回落到 37% 用了 **353ms**（2.35×），
     * 用户听到的释放时间与旋钮标注严重不符。
     *
     * 改为包络用**固定 40ms** 快速回落（检测要快），只有增益平滑用用户参数
     * （控制才该慢）。40ms 约为 60Hz 的 2.4 个周期，不会让包络跟随波形起伏产生
     * 低频调制失真。与单段 `CompressorNode::kEnvReleaseMs` 同一口径。
     */
    float env_rel_coef = 0.0f;

    void recomputeRates(int sample_rate) {
      const float sr = static_cast<float>(sample_rate);
      atk_coef = 1.0f - std::exp(-1.0f / (atk_ms * 0.001f * sr));
      rel_coef = 1.0f - std::exp(-1.0f / (rel_ms * 0.001f * sr));
      env_rel_coef = 1.0f - std::exp(-1.0f / (kEnvReleaseMs * 0.001f * sr));
    }
  };

  // 单段动力学步进：包络跟随 → dB 曲线(soft knee) → 分支平滑；返回线性段增益
  // （含段增益 gain_db；拓扑与 CompressorNode 同族，knee 见头注释公式）
  static float BandStep(const BandParams& d, float ax, float& env, float& g_db) {
    if (ax > env) {
      env = ax;
    } else {
      // 包络用固定 40ms（不再复用 rel_coef）——见 BandParams::env_rel_coef 注释
      env += (ax - env) * d.env_rel_coef;
    }
    const float e_db = 20.0f * std::log10(env + 1e-9f);
    float y_db;
    if (d.knee_db <= 0.0f) {
      y_db = (e_db > d.thr_db) ? d.thr_db + (e_db - d.thr_db) / d.ratio : e_db;
    } else {
      const float half = 0.5f * d.knee_db;
      if (e_db < d.thr_db - half) {
        y_db = e_db;
      } else if (e_db > d.thr_db + half) {
        y_db = d.thr_db + (e_db - d.thr_db) / d.ratio;
      } else {
        const float t = e_db - d.thr_db + half;
        y_db = e_db + (1.0f / d.ratio - 1.0f) * t * t / (2.0f * d.knee_db);
      }
    }
    const float target_db = y_db - e_db;  // ≤ 0（下压式）
    g_db += (target_db - g_db) * ((target_db < g_db) ? d.atk_coef : d.rel_coef);
    return std::pow(10.0f, (g_db + d.gain_db) * 0.05f);
  }

  static float Clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  void recomputeSplits(int frames) {
    for (auto& s : splits1_) {
      s.ConfigureTarget(fc1_cur_, sample_rate_);
      if (frames > 0) s.BeginBlockRamp(frames);
      else s.CommitTargets();
    }
    for (auto& s : splits2_) {
      s.ConfigureTarget(fc2_cur_, sample_rate_);
      if (frames > 0) s.BeginBlockRamp(frames);
      else s.CommitTargets();
    }
  }

  float* bandData(int band, int channel) {
    return band_buf_.data() +
           (static_cast<std::size_t>(band) * channels_ + channel) *
               static_cast<std::size_t>(max_frames_);
  }

  const float* bandData(int band, int channel) const {
    return band_buf_.data() +
           (static_cast<std::size_t>(band) * channels_ + channel) *
               static_cast<std::size_t>(max_frames_);
  }

  int sample_rate_ = 48000;
  int channels_ = 2;
  int max_frames_ = 2048;
  bool bypass_ = true;  // 默认透传；UI 持久化状态由引擎 Running 后补发
  float fc1_cur_ = kDefaultFc1Hz, fc1_tgt_ = kDefaultFc1Hz;
  float fc2_cur_ = kDefaultFc2Hz, fc2_tgt_ = kDefaultFc2Hz;
  // 每通道独立滤波器状态（声道间绝不共享 biquad 状态）
  std::vector<LR4Split> splits1_;  // fc1：Low | (Mid+High)
  std::vector<LR4Split> splits2_;  // fc2：Mid | High（作用于 fc1 高通输出）
  std::vector<float> band_buf_;   // 3 段 × 声道 × max_frames（pre-gain 段信号）
  BandParams dyn_[kBands];        // 每段动力学参数与导出系数
  std::vector<float> env_;        // M8f 联动：每段 1 份峰值包络（跨声道共享）
  std::vector<float> gain_db_;    // M8f 联动：每段 1 份平滑增益（dB）
  std::vector<float> dc_x_;       // M8f DC blocker 状态（per channel）
  std::vector<float> dc_y_;
  float dc_r_ = 0.999f;           // Prepare 按采样率重算（exp(−2π·kDcFcHz/fs)）
  float prot_env_ = 0.0f;         // M8f 段间防过冲联动包络
  float prot_rel_ = 0.0f;         // Prepare 按 fs 重算（τ=250ms）
  // ==== 计量（2026-11-08，多段压限 UI 的 IN/GR/OUT 电平表）====
  // 块级峰值：in = 分段后跨声道 max|·|；out = in × 该段当前增益；GR 直接读 gain_db_。
  // 这几个量在动力学循环里本来就算，这里只是记下来供 UI 读取，实时路径仅多 6 次比较。
  float band_in_pk_[3] = {};      // 每段输入峰值（块级）
  float band_out_pk_[3] = {};     // 每段输出峰值（块级）
};

}  // namespace sonicraft::dsp
