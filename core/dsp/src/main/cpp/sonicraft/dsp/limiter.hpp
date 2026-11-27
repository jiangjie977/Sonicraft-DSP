/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 三段分频前瞻限幅器（峰值上限保护）。
 *
 * ## 为什么不做单段
 *
 * 单段限幅在大增益（+9~+12 dB）下有个固有问题：低频峰值决定全带增益，
 * 一记贝斯/底鼓会把高频一起压下去，听感是"抽吸、发闷"。
 * 本机实测：单段 + 高增益使用时，用户描述为"很响但被压紧、动态变小"。
 *
 * ## 做法
 *
 *    输入 ─┬─ 检测支路（未延迟）→ LR4 三分频 ─→ 包络/增益
 *          └─ 音频支路（延迟 2.5ms）→ LR4 三分频 ─→ × 增益 ─┐
 *                                                          ├→ 求和 → 末级限幅 → 去直流 → 输出
 *    门限逐段收紧（相对 ceiling）：低 −8 dB / 中 −5 dB / 高 −2 dB
 *
 * - **前瞻**：检测支路不加延迟、音频支路延迟 look_ahead 帧，
 *   所以增益在峰值到来之前就算好，不需要事后削波，也不产生削波谐波；
 * - **分频**：Linkwitz-Riley 4 阶（= 两级 Butterworth 2 阶级联，Q=0.7071），
 *   分频点 100 Hz / 600 Hz，两路交叉点幅度平（LR 的经典性质）；
 * - **末级**：对"检测支路的限幅后和"再算一次增益，压到 ceiling，并做硬钳位保底；
 * - **去直流**：单极性限幅会累积直流偏移，直流占掉的余量表现为"莫名削顶"，
 *   因此最后串一节一阶高通 y[n] = x[n] − x[n−1] + 0.999·y[n−1]。
 *
 * ## 参数
 *
 *   param 0 = ceiling（dBFS，默认 −1.0）——指**末级**上限，各段门限由它推导
 *   param 1 = release（ms，默认 60）
 *   param 2 = true-peak 检测（0/1，对末级检测信号做 4x 过采样）
 *
 * 响度计量（LUFS/峰值）在**旁路时也照常统计**，否则关掉限幅器就看不到响度。
 */
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string_view>
#include <vector>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/loudness_meter.hpp"

namespace sonicraft::dsp {

class LimiterNode : public DspNode {
 public:
  static constexpr int kParamCeilingDb = 0;
  static constexpr int kParamReleaseMs = 1;   // [固定值] UI 未暴露，按默认释放时间生效
  static constexpr int kParamTruePeak = 2;
  // 分段门限（相对 ceiling 的 dB 偏移，负值；默认 -8 / -5 / -2）与去直流开关
  static constexpr int kParamGateLowDb = 3;
  static constexpr int kParamGateMidDb = 4;
  static constexpr int kParamGateHighDb = 5;
  static constexpr int kParamDcBlock = 6;

  static constexpr float kLookAheadMs = 2.5f;
  static constexpr float kAttackMs = 1.0f;
  static constexpr float kBandAttackMs = 0.5f;   // 分段用更快 attack（频段内峰值更陡）
  static constexpr float kDefaultCeilingDb = -1.0f;
  static constexpr float kDefaultReleaseMs = 60.0f;

  // 分频点与各段相对 ceiling 的门限（dB）
  static constexpr float kXoverLowHz = 100.0f;
  static constexpr float kXoverHighHz = 600.0f;
  static constexpr float kGateOffsetLow = -5.0f;   // 出厂默认：低频少压，保住鼓点冲击
  static constexpr float kGateOffsetMid = -5.0f;
  static constexpr float kGateOffsetHigh = -2.0f;
  static constexpr float kGateOffsetMin = -12.0f;
  static constexpr float kGateOffsetMax = 0.0f;

  std::string_view id() const override { return "limiter"; }

  /** 积分响度（LUFS，ITU-R BS.1770-4）；未攒满 400 ms 块时返回 NaN */
  float Lufs() const { return meter_.IntegratedLufs(); }
  /** 短时响度（3 s 窗） */
  float ShortTermLufs() const { return meter_.ShortTermLufs(); }
  /** 采样峰值（dBFS） */
  float PeakDb() const { return meter_.PeakDb(); }

  void Prepare(int sample_rate, int /*max_frames*/) override {
    meter_.Prepare(sample_rate);
    sample_rate_ = sample_rate;
    look_ahead_ = static_cast<int>(kLookAheadMs * 0.001f * static_cast<float>(sample_rate));
    if (look_ahead_ < 1) look_ahead_ = 1;
    ring_capacity_ = look_ahead_ + 128;   // 稳态占用恒为 look_ahead_，+128 为跨块余量
    ring_.assign(static_cast<std::size_t>(ring_capacity_) * channels_, 0.0f);
    write_idx_.assign(channels_, 0);

    // 每条支路每声道 8 个二阶级（LP100×2 / HP100×2 / LP600×2 / HP600×2）
    detect_bq_.assign(static_cast<std::size_t>(channels_) * kSections, Bq{});
    audio_bq_.assign(static_cast<std::size_t>(channels_) * kSections, Bq{});
    for (int c = 0; c < channels_; ++c) {
      SetBands(detect_bq_.data() + static_cast<std::size_t>(c) * kSections);
      SetBands(audio_bq_.data() + static_cast<std::size_t>(c) * kSections);
    }

    band_peak_.assign(static_cast<std::size_t>(channels_) * 3, 0.0f);
    band_env_.assign(static_cast<std::size_t>(channels_) * 3, 1.0f);
    final_peak_.assign(channels_, 0.0f);
    final_env_.assign(channels_, 1.0f);
    dc_x_.assign(channels_, 0.0f);
    dc_y_.assign(channels_, 0.0f);
    tp_hist_.assign(static_cast<std::size_t>(channels_) * kTpTaps, 0.0f);

    ceiling_db_ = kDefaultCeilingDb;
    release_ms_ = kDefaultReleaseMs;
    gate_off_[0] = kGateOffsetLow;
    gate_off_[1] = kGateOffsetMid;
    gate_off_[2] = kGateOffsetHigh;
    dc_block_ = true;
    recomputeRates();
  }

  void Reset() override {
    for (auto& v : ring_) v = 0.0f;
    for (auto& v : write_idx_) v = 0;
    for (auto& s : detect_bq_) s.Reset();
    for (auto& s : audio_bq_) s.Reset();
    for (auto& v : band_peak_) v = 0.0f;
    for (auto& v : band_env_) v = 1.0f;
    for (auto& v : final_peak_) v = 0.0f;
    for (auto& v : final_env_) v = 1.0f;
    for (auto& v : dc_x_) v = 0.0f;
    for (auto& v : dc_y_) v = 0.0f;
    for (auto& v : tp_hist_) v = 0.0f;
  }

  void Process(float* const* channels, int frames) override {
    // 响度计量放在最前面：旁路时也照常统计，只读不改。
    meter_.Process(channels, frames);

    const std::size_t cap = static_cast<std::size_t>(ring_capacity_);
    const std::size_t look = static_cast<std::size_t>(look_ahead_);

    for (int c = 0; c < channels_; ++c) {
      float* ch = channels[c];
      float* ring = ring_.data() + static_cast<std::size_t>(c) * ring_capacity_;
      Bq* det = detect_bq_.data() + static_cast<std::size_t>(c) * kSections;
      Bq* aud = audio_bq_.data() + static_cast<std::size_t>(c) * kSections;
      float* bpeak = band_peak_.data() + static_cast<std::size_t>(c) * 3;
      float* benv = band_env_.data() + static_cast<std::size_t>(c) * 3;
      float fp = final_peak_[c];
      float fe = final_env_[c];
      std::size_t w = write_idx_[c];   // 写指针每声道独立（共享会跨声道/跨块错位）

      for (int n = 0; n < frames; ++n) {
        const float x = ch[n];

        // ---- 音频支路：延迟 look_ahead_ 帧 ----
        ring[w] = x;
        const std::size_t read_idx = (w + cap - look) % cap;
        const float xd = ring[read_idx];
        w = (w + 1) % cap;

        // ---- 两条支路各自分频 ----
        const Bands bd = Split(det, x);    // 检测（未延迟）
        const Bands ba = Split(aud, xd);   // 音频（已延迟）

        // ---- 每段独立限幅增益 ----
        float g[3];
        const float dl[3] = {bd.low, bd.mid, bd.high};
        for (int k = 0; k < 3; ++k) {
          const float ax = std::fabs(dl[k]);
          bpeak[k] = (ax > bpeak[k]) ? ax : (bpeak[k] + (ax - bpeak[k]) * release_coef_);
          float target = gate_lin_[k] / (bpeak[k] + 1e-9f);
          if (target > 1.0f) target = 1.0f;
          if (target < benv[k]) {
            benv[k] -= band_attack_step_;
            if (benv[k] < target) benv[k] = target;
          } else {
            benv[k] += (target - benv[k]) * release_coef_;
          }
          g[k] = benv[k];
        }

        // ---- 求和：检测支路用于末级判定，音频支路用于输出 ----
        const float yd = bd.low * g[0] + bd.mid * g[1] + bd.high * g[2];
        const float ya = ba.low * g[0] + ba.mid * g[1] + ba.high * g[2];

        // ---- 末级限幅（对检测侧求和算增益，作用于已延迟的音频） ----
        float detect = std::fabs(yd);
        if (tp_enabled_) {
          detect = std::max(detect, TruePeak(tp_hist_.data() +
                                             static_cast<std::size_t>(c) * kTpTaps, yd));
        }
        fp = (detect > fp) ? detect : (fp + (detect - fp) * release_coef_);
        float ftarget = ceiling_lin_ / (fp + 1e-9f);
        if (ftarget > 1.0f) ftarget = 1.0f;
        if (ftarget < fe) {
          fe -= attack_step_;
          if (fe < ftarget) fe = ftarget;
        } else {
          fe += (ftarget - fe) * release_coef_;
        }

        float y = ya * fe;

        // 硬钳位保底：任何情况下不允许超过 ceiling
        if (y > ceiling_lin_) y = ceiling_lin_;
        else if (y < -ceiling_lin_) y = -ceiling_lin_;

        // ---- 去直流（一阶高通）：抵消单极性限幅累积的偏移；可单独关闭 ----
        float yy = y;
        if (dc_block_) {
          yy = (y - dc_x_[c]) + 0.999f * dc_y_[c];
          dc_x_[c] = y;
          dc_y_[c] = yy;
        }

        ch[n] = bypass_ ? x : yy;
      }

      final_peak_[c] = fp;
      final_env_[c] = fe;
      write_idx_[c] = w;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamCeilingDb) {
      ceiling_db_ = value;   // 单位 dBFS
      recomputeRates();
    } else if (param_id == kParamReleaseMs) {
      release_ms_ = value;   // 单位 ms
      recomputeRates();
    } else if (param_id == kParamTruePeak) {
      tp_enabled_ = value > 0.5f;
    } else if (param_id == kParamGateLowDb || param_id == kParamGateMidDb ||
               param_id == kParamGateHighDb) {
      const int k = (param_id == kParamGateLowDb) ? 0 : (param_id == kParamGateMidDb ? 1 : 2);
      gate_off_[k] = std::max(kGateOffsetMin, std::min(kGateOffsetMax, value));
      recomputeRates();
    } else if (param_id == kParamDcBlock) {
      const bool on = value > 0.5f;
      if (on != dc_block_) {
        // 切换时清状态，避免残留偏移造成一次直流跳变
        for (auto& v : dc_x_) v = 0.0f;
        for (auto& v : dc_y_) v = 0.0f;
      }
      dc_block_ = on;
    }
  }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) { channels_ = channels; }

 private:
  // ---- 二分阶 biquad（转置直接 II） ----
  struct Bq {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;
    void Reset() { z1 = z2 = 0.0f; }
    inline float Run(float x) {
      const float y = b0 * x + z1;
      z1 = b1 * x - a1 * y + z2;
      z2 = b2 * x - a2 * y;
      return y;
    }
    void SetLowPass(float fc, float fs) { Set(fc, fs, true); }
    void SetHighPass(float fc, float fs) { Set(fc, fs, false); }
    void Set(float fc, float fs, bool low) {
      const float kPi = 3.14159265358979f;
      const float w0 = 2.0f * kPi * fc / fs;
      const float cw = std::cos(w0);
      const float sw = std::sin(w0);
      const float q = 0.70710678f;              // Butterworth
      const float alpha = sw / (2.0f * q);
      const float a0 = 1.0f + alpha;
      if (low) {
        b0 = (1.0f - cw) * 0.5f / a0;
        b1 = (1.0f - cw) / a0;
        b2 = b0;
      } else {
        b0 = (1.0f + cw) * 0.5f / a0;
        b1 = -(1.0f + cw) / a0;
        b2 = b0;
      }
      a1 = (-2.0f * cw) / a0;
      a2 = (1.0f - alpha) / a0;
    }
  };

  struct Bands {
    float low, mid, high;
  };

  static constexpr int kSections = 8;

  /** 一帧分频：low = LP100(LR4)；mid = HP100 → LP600；high = HP600(LR4) */
  static inline Bands Split(Bq* s, float x) {
    float low = s[0].Run(x);
    low = s[1].Run(low);
    float hp = s[2].Run(x);
    hp = s[3].Run(hp);
    float mid = s[4].Run(hp);
    mid = s[5].Run(mid);
    float high = s[6].Run(x);
    high = s[7].Run(high);
    return {low, mid, high};
  }

  void SetBands(Bq* s) {
    const float fs = static_cast<float>(sample_rate_);
    s[0].SetLowPass(kXoverLowHz, fs);
    s[1].SetLowPass(kXoverLowHz, fs);      // LR4 = 两级 Butterworth 级联
    s[2].SetHighPass(kXoverLowHz, fs);
    s[3].SetHighPass(kXoverLowHz, fs);
    s[4].SetLowPass(kXoverHighHz, fs);
    s[5].SetLowPass(kXoverHighHz, fs);
    s[6].SetHighPass(kXoverHighHz, fs);
    s[7].SetHighPass(kXoverHighHz, fs);
  }

  /** 4x 过采样真峰估计（3 个相位的 24 tap 多相 FIR），返回幅值 */
  float TruePeak(float* h, float x) {
    for (int i = kTpTaps - 1; i > 0; --i) h[i] = h[i - 1];
    h[0] = x;
    float peak = 0.0f;
    for (int p = 0; p < 3; ++p) {
      const float* k = kTpCoef[p];
      float acc = 0.0f;
      for (int i = 0; i < kTpTaps; ++i) acc += h[i] * k[i];
      peak = std::max(peak, std::fabs(acc));
    }
    return peak;
  }

  void recomputeRates() {
    ceiling_lin_ = std::pow(10.0f, ceiling_db_ / 20.0f);
    const float fs = static_cast<float>(sample_rate_);
    attack_step_ = 1.0f / (kAttackMs * 0.001f * fs);
    band_attack_step_ = 1.0f / (kBandAttackMs * 0.001f * fs);
    release_coef_ = 1.0f - std::exp(-1.0f / (release_ms_ * 0.001f * fs));
    for (int k = 0; k < 3; ++k) {
      gate_lin_[k] = std::pow(10.0f, (ceiling_db_ + gate_off_[k]) / 20.0f);
    }
  }

  // 4x 过采样真峰值插值核：3 个子样本相位（d=0.25/0.5/0.75）× 24 tap
  // Blackman 窗 sinc（截止 0.25 归一，堵 4x 镜像带），每相 DC 归一。
  static constexpr int kTpTaps = 24;
  static constexpr float kTpCoef[3][kTpTaps] = {
      {0.0000000000f, 0.0001446395f, -0.0006845229f, 0.0018882074f, -0.0041852741f,
       0.0081816935f, -0.0147019688f, 0.0249430312f, -0.0409857821f, 0.0675820023f,
       -0.1202563988f, 0.2983884783f, 0.8951654349f, -0.1683589584f, 0.0826002251f,
       -0.0472912870f, 0.0278775055f, -0.0161021563f, 0.0088362290f, -0.0044739137f,
       0.0020026442f, -0.0007215241f, 0.0001516951f, 0.0000000000f},
      {0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f,
       0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f,
       -0.0000000000f, 0.0000000000f, 1.0000000000f, 0.0000000000f, -0.0000000000f,
       0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f,
       -0.0000000000f, -0.0000000000f, -0.0000000000f, -0.0000000000f},
      {-0.0000000000f, -0.0001403435f, 0.0006611775f, -0.0018136995f, 0.0039922800f,
       -0.0077349641f, 0.0137344936f, -0.0229156862f, 0.0367218788f, -0.0580670438f,
       0.0949758205f, -0.1817952908f, 0.9089764540f, 0.2849274614f, -0.1078387957f,
       0.0567519946f, -0.0320819606f, 0.0180717021f, -0.0097527809f, 0.0048794533f,
       -0.0021647381f, 0.0007745222f, -0.0001619348f, -0.0000000000f},
  };

  int sample_rate_ = 48000;
  int channels_ = 2;
  LoudnessMeter meter_;
  bool bypass_ = true;   // 默认透传；UI 持久化状态由引擎 Running 后补发

  int look_ahead_ = 120;
  int ring_capacity_ = 248;
  std::vector<float> ring_;              // [channel][ring_capacity_]
  std::vector<std::size_t> write_idx_;   // 每声道独立写指针

  std::vector<Bq> detect_bq_;   // [channel][kSections]
  std::vector<Bq> audio_bq_;    // [channel][kSections]

  std::vector<float> band_peak_;   // [channel][3] 每段峰值包络
  std::vector<float> band_env_;    // [channel][3] 每段增益包络
  std::vector<float> final_peak_;  // [channel]
  std::vector<float> final_env_;   // [channel]
  std::vector<float> dc_x_;        // [channel] 去直流历史
  std::vector<float> dc_y_;        // [channel]

  float gate_off_[3] = {kGateOffsetLow, kGateOffsetMid, kGateOffsetHigh};
  bool dc_block_ = true;
  float ceiling_db_ = kDefaultCeilingDb;
  float release_ms_ = kDefaultReleaseMs;
  float ceiling_lin_ = 0.891f;      // 10^(-1/20)
  float gate_lin_[3] = {0.0f, 0.0f, 0.0f};
  float attack_step_ = 0.0f;
  float band_attack_step_ = 0.0f;
  float release_coef_ = 0.0f;

  bool tp_enabled_ = false;         // true-peak 检测（param 2；Kotlin 构链后显式开）
  std::vector<float> tp_hist_;      // [channel][kTpTaps]
};

}  // namespace sonicraft::dsp
