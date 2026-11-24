/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace sonicraft::dsp {

/**
 * 动态范围压缩器（M4b-2，链序 EQ → Compressor → Limiter）。
 *
 * 结构（feed-forward 下压式，dB 域）：
 *   |x| → 侧链高通 → 峰值包络(瞬时攻击 + 快速固定释放)
 *        → dB 域静态曲线(threshold/ratio/knee)
 *        → 增益平滑(attack/release，dB 域)
 *        → ×(makeup) → 输出
 *
 * **三个专业化改造**（对照 Pro-C / 1176 的默认行为）：
 *
 *  ① **立体声联动**：包络与增益**跨声道共享一份**，检测取 max(|L_hp|, |R_hp|)。
 *     原来每声道独立检测+独立增益，单侧信号会让两侧被不同程度压缩 → 声像漂移。
 *     三段动态（multiband.hpp M8f）早已这么做，单段此前是缺的。
 *
 *  ② **包络释放独立于增益释放**：包络用固定 `kEnvReleaseMs = 40ms` 快速回落，
 *     只让**增益平滑**用用户的 Release 参数。
 *     原来两级都用同一个 release 系数（两个一阶低通串联），
 *     阶跃响应到 63% 需约 2.68τ → 用户调 150ms 实际听到约 400ms，明显拖沓。
 *
 *  ③ **侧链高通**：检测路径上每声道一阶高通（默认 120Hz，可关）。
 *     低频能量远大于中高频，不加高通时鼓点会让整段被压下去 → 「抽吸」感。
 *     注意是**先对信号高通再取绝对值**，不是对包络高通。
 *
 * 参数约定（node 内自洽）：
 *      param 0 = threshold（dB，默认 -20）
 *      param 1 = ratio（x:1，默认 4.0）
 *      param 2 = attack（ms，默认 10）
 *      param 3 = release（ms，默认 150）
 *      param 4 = makeup 增益（dB，默认 0）
 *      param 5 = knee 拐点宽度（dB，默认 6，0 = 硬拐点）
 *      param 6 = 侧链高通截止（Hz，默认 120，<1 = 关闭）
 *      param 7 = 并联混合（0 = 全干 / 1 = 全湿，默认 1）
 *      param 8 = 检测模式（0 = peak / 1 = RMS，默认 0）
 *      param 9 = 前瞻（ms，0~10，默认 0；会引入等量延迟）
 *      param 10 = 过采样（1 = 关闭 / 2 = 2x，默认 1；2x 有约 2 样本群延迟）
 *
 * 计量（GetMeters 输出 4 个值）：
 *      [0] 输入峰值（线性，块内最大值，跨声道取大）
 *      [1] 增益衰减（dB，负值）
 *      [2] 输出峰值（线性）
 *      [3] **GR 峰值保持**（dB，负值；按 3dB/s 缓慢回落，不会永久钉死）
 */
class CompressorNode : public DspNode {
 public:
  /**
   * 极简二阶低通（RBJ cookbook），只服务于过采样的抗镜像/抗混叠滤波。
   *
   * 为什么用 IIR 而不是半带 FIR：半带 FIR 要达到可用的阻带抑制需要 30+ 抽头，
   * 而这里是**每级串两节**、CPU 只需 4 次乘法；代价是相位非线性（但压缩器的
   * 过采样只关心「减少增益调制产生的混叠」，不要求线性相位）。
   */
  struct OsBiquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;

    /** fcNorm = 截止频率 / 采样率（0~0.5） */
    void lowpass(float fcNorm, float q) {
      const float w = 2.0f * 3.141592653589793f * fcNorm;
      const float cw = std::cos(w);
      const float alpha = std::sin(w) / (2.0f * q);
      const float a0 = 1.0f + alpha;
      b0 = (1.0f - cw) * 0.5f / a0;
      b1 = (1.0f - cw) / a0;
      b2 = b0;
      a1 = (-2.0f * cw) / a0;
      a2 = (1.0f - alpha) / a0;
    }
    float process(float x) {
      const float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
      x2 = x1; x1 = x;
      y2 = y1; y1 = y;
      return y;
    }
    void reset() { x1 = x2 = y1 = y2 = 0.0f; }
  };

  static constexpr int kParamThresholdDb = 0;
  static constexpr int kParamRatio = 1;
  static constexpr int kParamAttackMs = 2;
  static constexpr int kParamReleaseMs = 3;
  static constexpr int kParamMakeupDb = 4;
  static constexpr int kParamKneeDb = 5;
  static constexpr int kParamSidechainHpHz = 6;
  /** 并联混合：0 = 全干（只听原声）、1 = 全湿（纯压缩输出） */
  static constexpr int kParamMix = 7;
  /** 检测模式：0 = peak（瞬时峰值）、1 = RMS（10ms 均方根） */
  static constexpr int kParamDetectMode = 8;
  /**
   * 前瞻（look-ahead）：0~10ms。
   *
   * 原理：**检测用当前样本、输出用 N 样本前的样本**。因为增益在检测那一刻就确定了，
   * 而它作用在一段「还没到」的音频上 → 等效于增益提前 N 样本生效，瞬态来不及冲过去。
   * 代价是**引入 N 样本的延迟**（10ms @48k = 480 样本），这是前瞻的固有成本，
   * 无法消除。默认 0（无延迟）。
   */
  static constexpr int kParamOversample = 10;
  /** 过采样倍率：1（关闭，默认）或 2。4x 暂未实现（CPU 与收益不成正比）。 */
  static constexpr float kDefaultOversample = 1.0f;
  static constexpr int kParamLookaheadMs = 9;
  static constexpr float kDefaultLookaheadMs = 0.0f;
  static constexpr float kMaxLookaheadMs = 10.0f;
  // 默认值以「不损响度」为准（2026-11-24 调整）：原 -20/4:1/makeup 0 在 0dBFS 输入下
  // 净衰减约 15dB，用户反馈「开了压限声音变小」。现改为温和值：阈值高（只在峰值动作）、
  // 低比率、带 +2dB makeup —— 0dBFS 正弦下净增益约 0dB（gain-audit 实测 +0.13dB）。
  static constexpr float kDefaultThresholdDb = -3.0f;
  static constexpr float kDefaultRatio = 1.4f;
  static constexpr float kDefaultAttackMs = 15.0f;
  static constexpr float kDefaultReleaseMs = 220.0f;
  static constexpr float kDefaultMakeupDb = 2.0f;
  static constexpr float kDefaultKneeDb = 3.0f;
  static constexpr float kDefaultSidechainHpHz = 120.0f;
  static constexpr float kDefaultMix = 1.0f;
  static constexpr float kDefaultDetectMode = 0.0f;
  /** RMS 检测的积分窗（一阶平滑的时间常数） */
  static constexpr float kRmsWindowMs = 10.0f;
  /** 检测包络的固定释放时间：独立于用户 Release，避免两级串联拖慢释放 */
  static constexpr float kEnvReleaseMs = 40.0f;
  /** GR 峰值保持的回落速度 */
  static constexpr float kGrPeakDecayDbPerSec = 3.0f;

  std::string_view id() const override { return "compressor"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    max_frames_ = max_frames > 0 ? max_frames : 128;
    threshold_db_ = kDefaultThresholdDb;
    ratio_ = kDefaultRatio;
    attack_ms_ = kDefaultAttackMs;
    release_ms_ = kDefaultReleaseMs;
    makeup_db_ = kDefaultMakeupDb;
    knee_db_ = kDefaultKneeDb;
    sidechain_hp_hz_ = kDefaultSidechainHpHz;
    mix_ = kDefaultMix;
    detect_mode_ = kDefaultDetectMode;
    lookahead_ms_ = kDefaultLookaheadMs;
    recomputeRates();
    hp_x1_.assign(static_cast<std::size_t>(channels_), 0.0f);
    hp_y1_.assign(static_cast<std::size_t>(channels_), 0.0f);
    // 前瞻环形缓冲：按最大 10ms × **2 倍过采样上限**分配。
    // 过采样时核心跑在 2x 采样率下，10ms 对应 2 倍样本数，缓冲区必须够大。
    la_size_ = static_cast<int>(kMaxLookaheadMs * 0.001f * static_cast<float>(sample_rate)) * 2 + 2;
    la_buf_.assign(static_cast<std::size_t>(channels_) * static_cast<std::size_t>(la_size_), 0.0f);
    la_pos_ = 0;
    lookahead_samples_ = 0;
    // 过采样：按 2x 上限分配（os_stride_ = 每声道在 2x 域的样本容量）
    os_stride_ = max_frames_ * 2;
    os_up_.assign(static_cast<std::size_t>(channels_) *
                  static_cast<std::size_t>(os_stride_), 0.0f);
    os_ptrs_.assign(static_cast<std::size_t>(channels_), nullptr);
    for (int c = 0; c < channels_; ++c) {
      os_ptrs_[static_cast<std::size_t>(c)] =
          os_up_.data() + static_cast<std::size_t>(c) * static_cast<std::size_t>(os_stride_);
    }
    os_up_lp_.assign(static_cast<std::size_t>(channels_) * 2, OsBiquad{});
    os_dn_lp_.assign(static_cast<std::size_t>(channels_) * 2, OsBiquad{});
    os_factor_ = 1;
    oversample_ = kDefaultOversample;
    configureOsFilters();
    Reset();
  }

  void Reset() override {
    gain_db_ = 0.0f;
    peak_env_ = 0.0f;
    rms_sq_ = 0.0f;
    for (auto& v : la_buf_) v = 0.0f;
    la_pos_ = 0;
    for (auto& v : os_up_) v = 0.0f;
    for (auto& q : os_up_lp_) q.reset();
    for (auto& q : os_dn_lp_) q.reset();
    for (auto& v : hp_x1_) v = 0.0f;
    for (auto& v : hp_y1_) v = 0.0f;
    meter_in_ = meter_out_ = 0.0f;
    meter_gr_db_ = 0.0f;
    meter_gr_peak_db_ = 0.0f;
  }

  void Process(float* const* channels, int frames) override {
    // 未开启过采样：直接走核心，路径与改造前逐位一致（这是默认路径，必须零变化）
    if (os_factor_ <= 1) {
      CompressCore(channels, frames);
      return;
    }

    // ---- 2x 过采样外壳 ----
    // 目的：压缩器的增益是**时变**的，等于对信号做乘法调制，会产生混叠。
    // 在 2x 域里算增益与应用，再抽回原采样率，可把这些混叠推到可听带外。
    const int n2 = frames * 2;

    // ① 上采样：插零（插零后幅度减半，故先 ×2 补偿能量）→ 4 阶低通抗镜像
    for (int c = 0; c < channels_; ++c) {
      const std::size_t ci = static_cast<std::size_t>(c);
      OsBiquad& s0 = os_up_lp_[ci * 2];
      OsBiquad& s1 = os_up_lp_[ci * 2 + 1];
      float* dst = os_ptrs_[ci];
      for (int i = 0; i < frames; ++i) {
        const float x = channels[c][i] * 2.0f;
        dst[2 * i] = s0.process(s1.process(x));
        dst[2 * i + 1] = s0.process(s1.process(0.0f));
      }
    }

    // ② 核心在 2x 域运行
    CompressCore(os_ptrs_.data(), n2);

    // ③ 下采样：先低通抗混叠，再抽取（保留奇数位）
    for (int c = 0; c < channels_; ++c) {
      const std::size_t ci = static_cast<std::size_t>(c);
      OsBiquad& s0 = os_dn_lp_[ci * 2];
      OsBiquad& s1 = os_dn_lp_[ci * 2 + 1];
      const float* src = os_ptrs_[ci];
      for (int j = 0; j < n2; ++j) {
        const float y = s0.process(s1.process(src[j]));
        if (j & 1) channels[c][j >> 1] = y;
      }
    }
  }

  /**
   * 配置过采样抗镜像/抗混叠滤波器（每声道 2 节 = 4 阶 Butterworth）。
   *
   * 截止取 **0.45 × 原始 Nyquist**（归一化到 2x 采样率即 0.225），留出过渡带，
   * 避免把原始奈奎斯特附近的能量也削掉。两节的 Q 是 4 阶 Butterworth 的标准值
   * （0.5412 / 1.3066），级联后通带平坦、阻带 -24dB/oct × 2。
   */
  void configureOsFilters() {
    if (os_factor_ <= 1) return;
    const float fc = 0.225f;   // 归一化到 2x 采样率 = 0.45 × 原始 Nyquist
    for (int c = 0; c < channels_; ++c) {
      const std::size_t ci = static_cast<std::size_t>(c);
      os_up_lp_[ci * 2].lowpass(fc, 0.5412f);
      os_up_lp_[ci * 2 + 1].lowpass(fc, 1.3066f);
      os_dn_lp_[ci * 2].lowpass(fc, 0.5412f);
      os_dn_lp_[ci * 2 + 1].lowpass(fc, 1.3066f);
    }
  }

  /**
   * 压缩核心：原有全部算法（检测 → 静态曲线 → 增益平滑 → 应用）。
   * 从 `Process` 拆出，以便外面套重采样外壳；
   * 它工作在**调用方给的采样率**下，不关心是否被过采样。
   */
  void CompressCore(float* const* channels, int frames) {
    const float half_knee = knee_db_ * 0.5f;
    const float slope = 1.0f - 1.0f / (ratio_ > 0.0f ? ratio_ : 1.0f);
    // 块时长同样要按核心实际采样率换算（过采样时 frames 是 2x 域的样本数）
    const float block_sec = static_cast<float>(frames) /
        (static_cast<float>(sample_rate_) * static_cast<float>(os_factor_));

    float gain_db = gain_db_;
    float peak = peak_env_;
    float in_pk = 0.0f;
    float out_pk = 0.0f;

    // **逐样本外层、逐声道内层**：立体声联动要求先跨声道取检测最大值，
    // 再算一次增益、应用到所有声道；原来的「逐声道外层」结构做不到这一点。
    for (int n = 0; n < frames; ++n) {
      // ① 检测：每声道先过侧链高通，再取绝对值，最后跨声道取最大
      float det = 0.0f;
      for (int c = 0; c < channels_; ++c) {
        const float x = channels[c][n];
        const float ax = std::fabs(x);
        if (ax > in_pk) in_pk = ax;
        // 一阶高通：y[n] = a*(y[n-1] + x[n] - x[n-1])，a=1 时直通
        const float y = hp_a_ * (hp_y1_[c] + x - hp_x1_[c]);
        hp_x1_[c] = x;
        hp_y1_[c] = y;
        const float ay = std::fabs(y);
        if (ay > det) det = ay;
      }

      // ①' **RMS 检测**：
      // 把「跨声道取大后的线性检测值」先平方、过一阶平滑（τ=10ms）、再开方。
      // 为什么不是对每个声道分别做 RMS：检测本身是**跨声道共享**的（立体声联动），
      // 若各声道独立积分再取大，两个声道的瞬态会互相错开、RMS 变得更抖。
      // RMS 的意义：能量平均而非瞬时峰值 → 压缩动作更平滑、更适合「整体响度」控制；
      // 代价是对瞬态反应变慢（鼓点起音会漏一点），所以默认仍是 peak。
      if (detect_mode_ >= 0.5f) {
        rms_sq_ += (det * det - rms_sq_) * rms_coef_;
        det = std::sqrt(rms_sq_ + 1e-12f);
      }

      // ② 峰值包络：瞬时攻击 + **固定 40ms 释放**（不用用户的 Release）
      if (det > peak) {
        peak = det;
      } else {
        peak += (det - peak) * env_release_coef_;
      }

      // ③ dB 域静态增益曲线（含软膝）
      const float peak_db = 20.0f * std::log10(peak + 1e-9f);
      const float over = peak_db - threshold_db_;
      float over_out = 0.0f;  // 超出量中被压掉的部分
      if (over > half_knee) {
        over_out = over * slope;
      } else if (over > -half_knee && knee_db_ > 0.01f) {
        const float t = over + half_knee;  // 0..knee
        over_out = slope * t * t / (2.0f * knee_db_);
      }
      const float target_db = -over_out;

      // ④ 增益平滑：attack 下压、release 回升（这里才用用户的参数）
      if (target_db < gain_db) {
        gain_db += (target_db - gain_db) * attack_coef_;
      } else {
        gain_db += (target_db - gain_db) * release_coef_;
      }

      // ⑤ **前瞻延迟线**：
      // 无论 bypass 与否都推进缓冲 —— 否则恢复旁路时缓冲里是 N 样本前的旧数据，
      // 会听到一小段「倒放」。有前瞻时**先写入当前样本、再读 N 样本前的值**。
      // 声道上限 16：远大于实际（立体声 2 / 最多 8），留足余量防越界
      float delayed[16] = {0.0f};
      // 过采样时核心跑在更高采样率下，同样的毫秒数对应更多样本 —— 必须换算
      const int la_n = lookahead_samples_ * os_factor_;
      if (la_n > 0) {
        for (int c = 0; c < channels_; ++c) {
          const std::size_t base = static_cast<std::size_t>(c) *
                                   static_cast<std::size_t>(la_size_);
          la_buf_[base + la_pos_] = channels[c][n];   // 先写当前
          int r = la_pos_ - la_n;                      // 再读 N 样本前
          if (r < 0) r += la_size_;
          delayed[c] = la_buf_[base + r];
        }
        la_pos_ = (la_pos_ + 1) % la_size_;
      } else {
        for (int c = 0; c < channels_; ++c) delayed[c] = channels[c][n];
      }

      // ⑥ 应用增益到**延迟后**的信号（同一增益 → 声像不漂移）
      if (!bypass_) {
        float g = std::pow(10.0f, gain_db / 20.0f) * makeup_lin_;
        // 输出硬限幅安全网：makeup 拉高时压缩后峰值可超 1.0，
        // 钳增益防削波（压增益而非逐样本 clamp，避免破坏包络连续性）
        if (peak > 1e-9f && peak * g > 1.0f) g = 1.0f / peak;
        // ⑥' **并联混合**（param 7）：mix=1 全湿、mix=0 全干（检测仍在跑）。
        // 用 src + (src*g - src)*mix 而非 src*(1-mix) + src*g*mix：mix=1 时
        // 不会因 (1-mix) 的浮点舍入留下残余干声。
        for (int c = 0; c < channels_; ++c) {
          const float src = delayed[c];
          channels[c][n] = src + (src * g - src) * mix_;
        }
      } else {
        for (int c = 0; c < channels_; ++c) channels[c][n] = delayed[c];
      }
      for (int c = 0; c < channels_; ++c) {
        const float ay = std::fabs(channels[c][n]);
        if (ay > out_pk) out_pk = ay;
      }
    }

    gain_db_ = gain_db;
    peak_env_ = peak;

    // ⑥ GR 峰值保持：先按 3dB/s 回落，再取更负的值
    meter_gr_peak_db_ = std::min(
        meter_gr_peak_db_ + kGrPeakDecayDbPerSec * block_sec, 0.0f);
    if (gain_db < meter_gr_peak_db_) meter_gr_peak_db_ = gain_db;

    meter_in_ = in_pk;
    meter_out_ = out_pk;
    meter_gr_db_ = gain_db;
  }

  /** UI 电平表读取：{输入峰值, 增益衰减 dB, 输出峰值, GR 峰值保持 dB} */
  bool GetMeters(float* out4) const {
    if (out4 == nullptr) return false;
    out4[0] = meter_in_;
    out4[1] = meter_gr_db_;
    out4[2] = meter_out_;
    out4[3] = meter_gr_peak_db_;
    return true;
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    switch (param_id) {
      case kParamThresholdDb: threshold_db_ = value; break;
      case kParamRatio: ratio_ = value; break;
      case kParamAttackMs: attack_ms_ = value; break;
      case kParamReleaseMs: release_ms_ = value; break;
      case kParamMakeupDb: makeup_db_ = value; break;
      case kParamKneeDb: knee_db_ = value < 0.0f ? 0.0f : value; break;
      case kParamSidechainHpHz: sidechain_hp_hz_ = value < 0.0f ? 0.0f : value; break;
      case kParamMix: mix_ = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value); break;
      case kParamDetectMode: detect_mode_ = value < 0.5f ? 0.0f : 1.0f; break;
      case kParamOversample: {
        const int f = (value >= 1.5f) ? 2 : 1;   // 只支持 1 / 2，非法值回落
        oversample_ = static_cast<float>(f);
        if (f != os_factor_) {
          os_factor_ = f;
          // 倍率变了要：重算所有时间常数（按新的核心采样率）、重配滤波器、
          // 清空缓冲（旧的 2x 数据与新倍率不匹配）
          for (auto& v : os_up_) v = 0.0f;
          for (auto& q : os_up_lp_) q.reset();
          for (auto& q : os_dn_lp_) q.reset();
          for (auto& v : la_buf_) v = 0.0f;
          la_pos_ = 0;
          configureOsFilters();
          recomputeRates();
        }
        break;
      }
      case kParamLookaheadMs: {
        const float ms = value < 0.0f ? 0.0f
                       : (value > kMaxLookaheadMs ? kMaxLookaheadMs : value);
        lookahead_ms_ = ms;
        // **0 必须映射到 0 样本**：
        // 原来无条件 `+1`，导致 lookahead=0 时变成延迟 1 样本 —— 默认路径凭空多了
        // 1 个样本的延迟，`bypass` 与「无前瞻直通」都不再是逐位相同。
        const int samples = (ms <= 0.0f)
            ? 0
            : (static_cast<int>(ms * 0.001f * static_cast<float>(sample_rate_)) + 1);
        if (samples != lookahead_samples_) {
          // 改变前瞻量时清空缓冲：旧数据与新读指针的相对位置已经变了，
          // 不清会在切换瞬间听到一段错位音频
          for (auto& v : la_buf_) v = 0.0f;
          la_pos_ = 0;
          lookahead_samples_ = samples;
        }
        break;
      }
      default: return;
    }
    recomputeRates();
  }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) {
    channels_ = channels;
    hp_x1_.assign(static_cast<std::size_t>(channels_), 0.0f);
    hp_y1_.assign(static_cast<std::size_t>(channels_), 0.0f);
  }

 private:
  void recomputeRates() {
    // **用「核心实际采样率」**：过采样时核心跑在 factor× 采样率下，
    // 若仍按原始 sr 算时间常数，同样的 attack/release 毫秒数会变成一半 —— 听觉上
    // 是「开了过采样，压缩突然变快」，属于最难查的那类 bug。
    const float sr = static_cast<float>(sample_rate_) *
                     static_cast<float>(os_factor_);
    attack_coef_ = 1.0f - std::exp(-1.0f / (attack_ms_ * 0.001f * sr));
    release_coef_ = 1.0f - std::exp(-1.0f / (release_ms_ * 0.001f * sr));
    env_release_coef_ = 1.0f - std::exp(-1.0f / (kEnvReleaseMs * 0.001f * sr));
    rms_coef_ = 1.0f - std::exp(-1.0f / (kRmsWindowMs * 0.001f * sr));
    makeup_lin_ = std::pow(10.0f, makeup_db_ / 20.0f);
    // 一阶高通系数：a = rc / (rc + dt)；截止越低 a 越接近 1（1 = 直通）
    if (sidechain_hp_hz_ < 1.0f) {
      hp_a_ = 1.0f;
    } else {
      const float rc = 1.0f / (2.0f * 3.14159265f * sidechain_hp_hz_);
      const float dt = 1.0f / sr;
      hp_a_ = rc / (rc + dt);
    }
  }

  int sample_rate_ = 48000;
  int max_frames_ = 128;
  int channels_ = 2;
  // ---- 过采样（2x）----
  float oversample_ = kDefaultOversample;   // 用户设定（1 或 2）
  int os_factor_ = 1;                       // 生效倍率（非法值回落为 1）
  int os_stride_ = 256;                     // 每声道在 2x 域的样本容量
  std::vector<float> os_up_;                // 2x 域样本缓冲（声道 × os_stride_）
  std::vector<float*> os_ptrs_;             // 每声道起始指针（喂给 CompressCore）
  std::vector<OsBiquad> os_up_lp_;          // 上采样抗镜像（每声道 2 节）
  std::vector<OsBiquad> os_dn_lp_;          // 下采样抗混叠（每声道 2 节）
  bool bypass_ = true;  // 默认透传；UI 持久化状态由引擎 Running 后补发
  float threshold_db_;
  float ratio_;
  float attack_ms_;
  float release_ms_;
  float makeup_db_;
  float knee_db_;
  float sidechain_hp_hz_;
  float mix_ = kDefaultMix;              // 并联混合（1 = 全湿）
  float detect_mode_ = kDefaultDetectMode;  // 0 = peak, 1 = RMS
  float attack_coef_;
  float release_coef_;
  float env_release_coef_;  // 包络专用（固定 40ms）
  float rms_coef_;          // RMS 积分窗（10ms）
  float rms_sq_ = 0.0f;     // 检测值的平方滑动均值（跨声道共享）
  float lookahead_ms_ = kDefaultLookaheadMs;
  int lookahead_samples_ = 0;   // 换算后的样本数（0 = 关闭）
  int la_size_ = 1;             // 环形缓冲每声道长度
  int la_pos_ = 0;              // 写指针
  std::vector<float> la_buf_;   // 前瞻环形延迟线（声道 × la_size_）
  float makeup_lin_;
  float hp_a_ = 1.0f;
  // **跨声道共享一份**（立体声联动）：不再按声道分开
  float gain_db_ = 0.0f;
  float peak_env_ = 0.0f;
  std::vector<float> hp_x1_;  // 侧链高通历史（每声道）
  std::vector<float> hp_y1_;
  // 跨声道聚合后的计量值（UI 读取）
  float meter_in_ = 0.0f;
  float meter_out_ = 0.0f;
  float meter_gr_db_ = 0.0f;
  float meter_gr_peak_db_ = 0.0f;
};

}  // namespace sonicraft::dsp
