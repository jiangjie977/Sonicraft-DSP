/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>
#include <cstring>
#include <vector>

namespace sonicraft::dsp {

/**
 * 反馈延迟网络混响（FDN，M 系列新增混响引擎）。
 *
 * 结构：每声道 N 梳状滤波器并联 + M 全通级联（mode=1 降级为 N/2 + M/2），
 * 梳状输出求和后经全通级联得到湿声。关键原语：
 *  - 分数延迟线性插值（亚样本精度，避免整数延迟的梳状染色）
 *  - 每梳阻尼低通（湿度参数控制衰减斜率）
 *  - 输出 DC 阻断高通（单极点，系数 0.99）
 *  - comb LFO 拍频打散（亚 Hz 级微调制，打散并联共振拍频，对音色影响极小）
 *  - M/S 立体声宽度（side 增益由宽度参数控制）
 *
 * 实时性契约：Prepare/Reset 在非实时线程（可分配延迟线缓冲）；Process 无分配/无锁，
 * 仅浮点运算与本块 16 次 sinf（LFO 预计算）；参数经 SPSC 队列投递，SetParam 内即时
 * 重算系数（无分配）。延迟线长度 2 的幂，环形取模用位与。
 *
 * 参数（全部 0~1，mode 为 0/1 枚举）：
 *  0 roomSize 房间尺寸（同时驱动延迟缩放与反馈量）
 *  1 humidity 湿度/阻尼（越大衰减越快）
 *  2 stereoWidth 立体声宽度（M/S side 增益）
 *  3 wet 湿声比例
 *  4 dry 干声比例
 *  5 mode 拓扑档位（0 = 全规格 8+4，1 = 轻量 4+2）
 */
class ReverbNode : public DspNode {
 public:
  static constexpr int kParamRoomSize = 0;
  static constexpr int kParamHumidity = 1;
  static constexpr int kParamStereoWidth = 2;
  static constexpr int kParamWet = 3;
  static constexpr int kParamDry = 4;
  static constexpr int kParamMode = 5;

  std::string_view id() const override { return "reverb"; }

  void Prepare(int sample_rate, int /*max_frames*/) override {
    sample_rate_ = sample_rate;

    // 延迟线统一缓冲池（非实时分配）：2 声道 × (N 梳 + M 全通)，每条 kDelayLen 样本
    const int total_lines = kNumComb * 2 + kNumAp * 2;
    pool_.assign(static_cast<std::size_t>(total_lines) * kDelayLen, 0.0f);
    int offset = 0;
    for (int i = 0; i < kNumComb; ++i) {
      comb_l_[i] = pool_.data() + offset;
      offset += kDelayLen;
      comb_r_[i] = pool_.data() + offset;
      offset += kDelayLen;
    }
    for (int i = 0; i < kNumAp; ++i) {
      ap_l_[i] = pool_.data() + offset;
      offset += kDelayLen;
      ap_r_[i] = pool_.data() + offset;
      offset += kDelayLen;
    }

    wp_ = 0;
    wet_hp_l_ = wet_hp_r_ = 0.0f;
    wet_hp_l_prev_ = wet_hp_r_prev_ = 0.0f;
    for (int i = 0; i < kNumComb; ++i) {
      lp_l_a_[i] = lp_l_b_[i] = 0.0f;
      lp_r_a_[i] = lp_r_b_[i] = 0.0f;
      // LFO 相位初始分散，避免所有 comb 同相调制
      lfo_phase_[i] = static_cast<float>(i) * 2.0f * kPi / static_cast<float>(kNumComb);
    }

    // 默认参数（与原始设计默认值对齐）
    room_size_ = 0.5f;
    humidity_ = 0.5f;
    stereo_width_ = 0.5f;
    wet_ = 0.3f;
    dry_ = 0.5f;
    mode_ = 0;
    cur_room_size_ = room_size_;
    cur_humidity_ = humidity_;
    cur_stereo_width_ = stereo_width_;
    cur_wet_ = wet_;
    cur_dry_ = dry_;
    UpdateCoeffs();
    // 逐样本斜坡的常量部分 + 运行值初值（2026-11-08）
    for (int i = 0; i < kNumComb; ++i) {
      base_d_samples_[i] = kBaseD[i] * static_cast<float>(sample_rate_);
    }
    for (int i = 0; i < kNumAp; ++i) {
      base_ap_samples_[i] = kBaseAp[i] * static_cast<float>(sample_rate_);
    }
    scale_run_ = 0.5f + cur_room_size_ * 1.8f;
    fb_run_ = 0.55f + cur_room_size_ * 0.3f;
    damp_run_ = 0.9f - cur_humidity_ * 0.65f;
    wet_run_ = cur_wet_;
    dry_run_ = cur_dry_;
    side_run_ = cur_stereo_width_;
  }

  void Reset() override {
    const int total_lines = kNumComb * 2 + kNumAp * 2;
    if (!pool_.empty()) {
      std::memset(pool_.data(), 0,
                  static_cast<std::size_t>(total_lines) * kDelayLen * sizeof(float));
    }
    wp_ = 0;
    wet_hp_l_ = wet_hp_r_ = 0.0f;
    wet_hp_l_prev_ = wet_hp_r_prev_ = 0.0f;
    for (int i = 0; i < kNumComb; ++i) {
      lp_l_a_[i] = lp_l_b_[i] = 0.0f;
      lp_r_a_[i] = lp_r_b_[i] = 0.0f;
      lfo_phase_[i] = static_cast<float>(i) * 2.0f * kPi / static_cast<float>(kNumComb);
    }
    cur_room_size_ = room_size_;
    cur_humidity_ = humidity_;
    cur_stereo_width_ = stereo_width_;
    cur_wet_ = wet_;
    cur_dry_ = dry_;
    // 斜坡运行值同步回到目标值：断流重建后首块不应从旧的中间态滑过来
    scale_run_ = 0.5f + cur_room_size_ * 1.8f;
    fb_run_ = 0.55f + cur_room_size_ * 0.3f;
    damp_run_ = 0.9f - cur_humidity_ * 0.65f;
    wet_run_ = cur_wet_;
    dry_run_ = cur_dry_;
    side_run_ = cur_stereo_width_;
  }

  void Process(float* const* channels, int frames) override {
    if (bypass_) return;  // A/B 标尺：bypass = 原样透传（尾音冻结，切换无断裂）
    if (channels_ < 2 || frames <= 0) return;

    // 2026-11-08 修「拖动房间大小滑块有毛刺」：
    // 原实现是**块级**一阶平滑（每 4ms 才更新一次参数），块间是阶跃；这个阶跃进入
    // 8 个梳状反馈环后被放大成可闻的 zipper 噪音。改为**块内逐样本线性斜坡**：
    // 起点 = 上块末值（*_run_）、终点 = 本块 target，块尾恰好等于下块首 → 全程连续。
    const float inv_n = 1.0f / static_cast<float>(frames);
    const float scale_to = 0.5f + room_size_ * 1.8f;
    const float fb_to = 0.55f + room_size_ * 0.3f;
    const float damp_to = 0.9f - humidity_ * 0.65f;
    const float scale_step = (scale_to - scale_run_) * inv_n;
    const float fb_step = (fb_to - fb_run_) * inv_n;
    const float damp_step = (damp_to - damp_run_) * inv_n;
    const float wet_step = (wet_ - wet_run_) * inv_n;
    const float dry_step = (dry_ - dry_run_) * inv_n;
    const float side_step = (stereo_width_ - side_run_) * inv_n;
    // cur_* 与 UpdateCoeffs 仅用于维护诊断字段（rev_d_ 等）与其他读取方，语义不变
    const float kSmooth = 0.2f;  // 块级系数（@48k 每块 4ms，约 20ms 时间常数）
    cur_room_size_ += (room_size_ - cur_room_size_) * kSmooth;
    cur_humidity_ += (humidity_ - cur_humidity_) * kSmooth;
    cur_stereo_width_ += (stereo_width_ - cur_stereo_width_) * kSmooth;
    cur_wet_ += (wet_ - cur_wet_) * kSmooth;
    cur_dry_ += (dry_ - cur_dry_) * kSmooth;
    UpdateCoeffs();

    float* l = channels[0];
    float* r = channels[1];

    const int num_comb = (mode_ == 1) ? kNumComb / 2 : kNumComb;
    const int num_ap = (mode_ == 1) ? kNumAp / 2 : kNumAp;
    const float comb_sum_scale = (mode_ == 1) ? 0.25f : 0.125f;

    // 块级 LFO 调制值预计算：LFO 频率极低，整块用恒定调制值即可
    float mod_l[kNumComb], mod_r[kNumComb];
    const float block_phase_inc = 2.0f * kPi * static_cast<float>(frames) / static_cast<float>(sample_rate_);
    for (int i = 0; i < num_comb; ++i) {
      const float freq = kLfoFreqBase + static_cast<float>(i) * kLfoFreqStep;
      lfo_phase_[i] += freq * block_phase_inc;
      while (lfo_phase_[i] >= 2.0f * kPi) lfo_phase_[i] -= 2.0f * kPi;
      mod_l[i] = std::sin(lfo_phase_[i]) * kLfoDepth;
      // 右声道 90° 相位差，增强立体声扩散
      mod_r[i] = std::sin(lfo_phase_[i] + kPi * 0.5f) * kLfoDepth;
    }

    for (int s = 0; s < frames; ++s) {
      // 逐样本推进参数斜坡（步进为 0 时这几行就是加法，成本可忽略）
      scale_run_ += scale_step;
      fb_run_ += fb_step;
      damp_run_ += damp_step;
      wet_run_ += wet_step;
      dry_run_ += dry_step;
      side_run_ += side_step;

      const float in_l = l[s];
      const float in_r = r[s];

      // ===== 左声道：梳状滤波器并联 =====
      float comb_sum_l = 0.0f;
      for (int i = 0; i < num_comb; ++i) {
        const float c = DelayRead(comb_l_[i], base_d_samples_[i] * scale_run_ + mod_l[i]);
        lp_l_a_[i] += damp_run_ * (c - lp_l_a_[i]);
        lp_l_b_[i] = lp_l_a_[i];
        comb_sum_l += lp_l_b_[i];
      }
      comb_sum_l *= comb_sum_scale;

      // ===== 左声道：全通级联 =====
      float ap_in = comb_sum_l;
      float wet_l = 0.0f;
      for (int i = 0; i < num_ap; ++i) {
        const float ap_out = DelayRead(ap_l_[i], base_ap_samples_[i] * scale_run_);
        ap_l_[i][wp_] = ap_in - kApGain * ap_out;
        ap_in = ap_out + kApGain * ap_l_[i][wp_];
        if (i == num_ap - 1) wet_l = ap_in;
      }

      // ===== 左声道：梳状反馈写回 =====
      for (int i = 0; i < num_comb; ++i) {
        comb_l_[i][wp_] = in_l + lp_l_b_[i] * fb_run_;
      }

      // ===== 右声道：梳状滤波器并联（延迟按 r_mult 拉伸）=====
      float comb_sum_r = 0.0f;
      for (int i = 0; i < num_comb; ++i) {
        const float c = DelayRead(comb_r_[i], base_d_samples_[i] * scale_run_ * kRMult[i] + mod_r[i]);
        lp_r_a_[i] += damp_run_ * (c - lp_r_a_[i]);
        lp_r_b_[i] = lp_r_a_[i];
        comb_sum_r += lp_r_b_[i];
      }
      comb_sum_r *= comb_sum_scale;

      // ===== 右声道：全通级联（延迟按 r_ap_mult 拉伸）=====
      ap_in = comb_sum_r;
      float wet_r = 0.0f;
      for (int i = 0; i < num_ap; ++i) {
        const float ap_out = DelayRead(ap_r_[i], base_ap_samples_[i] * scale_run_ * kRApMult[i]);
        ap_r_[i][wp_] = ap_in - kApGain * ap_out;
        ap_in = ap_out + kApGain * ap_r_[i][wp_];
        if (i == num_ap - 1) wet_r = ap_in;
      }

      // ===== 右声道：梳状反馈写回 =====
      for (int i = 0; i < num_comb; ++i) {
        comb_r_[i][wp_] = in_r + lp_r_b_[i] * fb_run_;
      }

      // 推进写指针
      wp_ = (wp_ + 1) & kDelayM1;

      // ===== 输出 DC 阻断高通 =====
      wet_hp_l_ = kHpCoeff * (wet_hp_l_ + wet_l - wet_hp_l_prev_);
      wet_hp_r_ = kHpCoeff * (wet_hp_r_ + wet_r - wet_hp_r_prev_);
      wet_hp_l_prev_ = wet_l;
      wet_hp_r_prev_ = wet_r;
      wet_l = wet_hp_l_;
      wet_r = wet_hp_r_;

      // ===== M/S 立体声宽度 =====
      const float mid = (wet_l + wet_r) * 0.5f;
      const float side = (wet_l - wet_r) * side_run_;
      wet_l = mid + side;
      wet_r = mid - side;

      // ===== 干湿混合（就地）=====
      l[s] = in_l * dry_run_ + wet_l * wet_run_;
      r[s] = in_r * dry_run_ + wet_r * wet_run_;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    switch (param_id) {
      case kParamRoomSize:
        room_size_ = Clamp01(value);
        break;
      case kParamHumidity:
        humidity_ = Clamp01(value);
        break;
      case kParamStereoWidth:
        stereo_width_ = Clamp01(value);
        break;
      case kParamWet:
        wet_ = Clamp01(value);
        break;
      case kParamDry:
        dry_ = Clamp01(value);
        break;
      case kParamMode:
        mode_ = value >= 0.5f ? 1 : 0;
        break;
    }
  }

  /** AddNode 前设置声道数（当前固定双声道） */
  void set_channel_count(int channels) { channels_ = channels; }

 private:
  static constexpr float kPi = 3.141592653589793f;
  static constexpr int kDelayLen = 65536;   // 2 的幂（环形取模位与）
  static constexpr int kDelayM1 = kDelayLen - 1;
  static constexpr int kNumComb = 8;
  static constexpr int kNumAp = 4;

  // 右声道延迟拉伸倍数（打破左右对称，增强立体声扩散）
  static constexpr float kRMult[kNumComb] = {1.08f, 0.93f, 1.12f, 0.91f, 0.96f, 1.09f, 0.89f, 1.11f};
  static constexpr float kRApMult[kNumAp] = {1.1f, 0.9f, 0.92f, 1.07f};

  // 基延迟长度（秒），与采样率无关；实际延迟 = 基长 × sr × 缩放系数
  static constexpr float kBaseD[kNumComb] = {0.0293f, 0.0371f, 0.0439f, 0.0523f, 0.0617f, 0.0701f, 0.0793f, 0.0892f};
  static constexpr float kBaseAp[kNumAp] = {0.0103f, 0.0167f, 0.0241f, 0.0337f};

  static constexpr float kApGain = 0.6f;
  static constexpr float kHpCoeff = 0.99f;
  static constexpr float kLfoDepth = 0.8f;        // 调制深度（样本）
  static constexpr float kLfoFreqBase = 0.3f;     // 基础调制频率 (Hz)
  static constexpr float kLfoFreqStep = 0.07f;    // 每 comb 频率增量 (Hz)

  inline static float Clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

  // 分数延迟读（线性插值）；delay_samples 可为小数，len 须为 2 的幂
  inline float DelayRead(const float* buf, float delay_samples) {
    float read_pos = static_cast<float>(wp_) - delay_samples;
    while (read_pos < 0.0f) read_pos += static_cast<float>(kDelayLen);
    const int ix0 = static_cast<int>(std::floor(read_pos)) & kDelayM1;
    const int ix1 = (ix0 + 1) & kDelayM1;
    const float a = read_pos - std::floor(read_pos);
    return buf[ix0] + a * (buf[ix1] - buf[ix0]);
  }

  void UpdateCoeffs() {
    rev_delay_scale_ = 0.5f + cur_room_size_ * 1.8f;
    rev_fb_gain_ = 0.55f + cur_room_size_ * 0.3f;
    rev_damp_coeff_ = 0.9f - cur_humidity_ * 0.65f;
    wet_mix_norm_ = cur_wet_;
    dry_mix_norm_ = cur_dry_;
    stereo_side_coeff_ = cur_stereo_width_;

    for (int i = 0; i < kNumComb; ++i) {
      rev_d_[i] = kBaseD[i] * static_cast<float>(sample_rate_) * rev_delay_scale_;
    }
    for (int i = 0; i < kNumAp; ++i) {
      rev_ap_[i] = kBaseAp[i] * static_cast<float>(sample_rate_) * rev_delay_scale_;
    }
  }

  std::vector<float> pool_;                 // 统一延迟线缓冲
  float* comb_l_[kNumComb] = {};
  float* comb_r_[kNumComb] = {};
  float* ap_l_[kNumAp] = {};
  float* ap_r_[kNumAp] = {};

  int wp_ = 0;
  float lp_l_a_[kNumComb] = {};
  float lp_l_b_[kNumComb] = {};
  float lp_r_a_[kNumComb] = {};
  float lp_r_b_[kNumComb] = {};
  float wet_hp_l_ = 0.0f, wet_hp_r_ = 0.0f;
  float wet_hp_l_prev_ = 0.0f, wet_hp_r_prev_ = 0.0f;
  float lfo_phase_[kNumComb] = {};

  // 参数（target，由 SetParam 设置；Process 内平滑逼近 current）
  float room_size_ = 0.5f;
  float humidity_ = 0.5f;
  float stereo_width_ = 0.5f;
  float wet_ = 0.3f;
  float dry_ = 0.5f;
  int mode_ = 0;
  // 平滑后的当前值（Process 一阶低通逼近 target）
  float cur_room_size_ = 0.5f;
  float cur_humidity_ = 0.5f;
  float cur_stereo_width_ = 0.5f;
  float cur_wet_ = 0.3f;
  float cur_dry_ = 0.5f;

  // 派生系数
  float rev_delay_scale_ = 1.0f;
  float rev_fb_gain_ = 0.7f;
  float rev_damp_coeff_ = 0.575f;
  float wet_mix_norm_ = 0.3f;
  float dry_mix_norm_ = 0.5f;
  float stereo_side_coeff_ = 0.5f;
  float rev_d_[kNumComb] = {};
  float rev_ap_[kNumAp] = {};

  // ==== 块内逐样本斜坡运行值（2026-11-08 修「拖动房间大小有毛刺」）====
  // 原实现每块只更新一次参数（4ms 一个阶跃），8 个梳状反馈环会把这个阶跃放大成
  // 可闻的 zipper 噪音。改为块内线性斜坡：起点=上块末值、终点=本块 target、
  // 步进=(to-from)/frames，块间天然连续（块尾 == 下块首）。
  float base_d_samples_[kNumComb] = {};  // kBaseD[i] * sample_rate（与 room_size 无关的常量部分）
  float base_ap_samples_[kNumAp] = {};
  float scale_run_ = 1.4f;    // 延迟缩放 = 0.5 + room*1.8
  float fb_run_ = 0.70f;      // 梳状反馈增益 = 0.55 + room*0.3
  float damp_run_ = 0.575f;   // 阻尼系数 = 0.9 - humidity*0.65
  float wet_run_ = 0.3f;
  float dry_run_ = 0.5f;
  float side_run_ = 0.5f;

  int sample_rate_ = 48000;
  int channels_ = 2;
  bool bypass_ = true;
};

}  // namespace sonicraft::dsp
