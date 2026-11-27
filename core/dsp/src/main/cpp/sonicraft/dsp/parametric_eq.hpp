#pragma once
// 多段参量均衡器（peaking/low-shelf/high-shelf，RBJ cookbook）。SPDX-License-Identifier: GPL-3.0-only
// 容量 31 段；段数 1~31 任意（SetParam(93)，对齐 早期定制版 自定义段数）：10/15/31 用标准
// 频点表，其余段数按 20~20k 对数均分生成默认表；切段数时频点重置+状态清零。每段三参数：
// 增益(±12dB)/Q(0.3~8)/频率(20~20k)，外加段类型（95~125：0=PK peaking / 1=LSC low
// shelf / 2=HSC high shelf，shelf 为 RBJ S=1 最大平坦口径，Q 对 shelf 无效）。
// 布局：[0,31)增益 [31,62)Q [62,93)频率 93=段数 94=pre-gain(±18dB) 95=类型基址。
// 平滑：增益 30ms 斜坡；freq/Q/类型经目标系数重建+系数一阶平滑（30ms），拖动无爆音。
// bypass 走 is_bypass 通道。
#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/fft.hpp"
#include <array>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <vector>
#include <android/log.h>
#include <cstdio>
#include <ctime>
namespace sonicraft::dsp {
class ParametricEqNode : public DspNode {
 public:
  static constexpr int kMaxBands = 31;
  static constexpr float kDefaultQ = 1.0f;
  static constexpr int kQBase = kMaxBands;
  static constexpr int kFreqBase = kMaxBands * 2;
  static constexpr int kParamBandCount = kMaxBands * 3;
  static constexpr int kParamPreGainDb = kMaxBands * 3 + 1;  // 94: pre-gain dB
  static constexpr int kTypeBase = kMaxBands * 3 + 2;        // 95: 段类型（0=PK 1=LSC 2=HSC）
  static constexpr int kParamLast = kTypeBase + kMaxBands - 1;
  static constexpr std::array<float, 10> kFreqs10 = {
      31.f, 62.f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
  static constexpr std::array<float, 15> kFreqs15 = {
      25.f, 40.f, 63.f, 100.f, 160.f, 250.f, 400.f, 630.f, 1000.f, 1600.f,
      2500.f, 4000.f, 6300.f, 10000.f, 16000.f};
  static constexpr std::array<float, 31> kFreqs31 = {
      20.f, 25.f, 31.5f, 40.f, 50.f, 63.f, 80.f, 100.f, 125.f, 160.f, 200.f,
      250.f, 315.f, 400.f, 500.f, 630.f, 800.f, 1000.f, 1250.f, 1600.f, 2000.f,
      2500.f, 3150.f, 4000.f, 5000.f, 6300.f, 8000.f, 10000.f, 12500.f, 16000.f,
      20000.f};
  std::string_view id() const override { return "parametric_eq"; }
  void Prepare(int sample_rate, int) override {
    sample_rate_ = sample_rate;
    // 频谱探针：FFT plan + Hann 窗一次性预计算（非实时线程）
    if (!spec_fft_ready_) {
      spec_fft_.Init(kSpecFft);
      spec_re_.assign(kSpecFft, 0.0f);
      spec_im_.assign(kSpecFft, 0.0f);
      spec_hann_.resize(kSpecFft);
      for (int i = 0; i < kSpecFft; ++i)
        spec_hann_[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * i / (kSpecFft - 1)));
      for (int i = 0; i < kSpecBins; ++i) { spec_db_in_[i] = -60.0f; spec_db_out_[i] = -60.0f; }
      spec_fft_ready_ = true;
    }
    // 参数健康化：防成员初值 0 导致除零（实测根因：band_q_ 初值 0 → alpha=inf →
    // 系数 NaN → coeff_initialized 锁死 NaN → EQ 开启即全链无声）
    for (int i = 0; i < kMaxBands; ++i) {
      if (!(band_q_[i] > 0.05f)) band_q_[i] = kDefaultQ;
      if (!(band_freq_[i] > 0.0f) || std::isnan(band_freq_[i])) band_freq_[i] = 1000.0f;
      if (std::isnan(target_gain_db_[i]) || std::isinf(target_gain_db_[i]))
        target_gain_db_[i] = 0.0f;
      if (band_type_[i] < 0 || band_type_[i] > 2) band_type_[i] = 0;
    }
    std::fill(coeff_initialized_.begin(), coeff_initialized_.end(), false);
    ensureStates();
    recomputeAll();
  }
  void Reset() override {
    for (auto& v : z1_) v = 0.0f;
    for (auto& v : z2_) v = 0.0f;
  }
  void Process(float* const* channels, int frames) override {
    // 频谱探针喂料（bypass 前置：EQ 关时衬底仍反映透传信号，不冻结）。
    // 必须每块都喂：跳块会让 ring 时间轴非均匀（等效混叠），FFT 峰频偏移
    // （实测 1kHz 峰偏 5bin）。mono 混合 memcpy ≈0.5µs/块，无需节流。
    // 入口先喂 in ring（EQ 前信号）；出口在函数尾部喂 out ring（EQ 后信号）。
    {
      int w = spec_wpos_in_.load(std::memory_order_relaxed);
      for (int n = 0; n < frames; ++n) {
        float m = 0.0f;
        for (int c = 0; c < channels_; ++c) m += channels[c][n];
        spec_ring_in_[w] = m / static_cast<float>(channels_);
        w = (w + 1) % kSpecRing;
      }
      spec_wpos_in_.store(w, std::memory_order_relaxed);
    }
    if (bypass_) return;
    const float tau = 0.030f;
    const float alpha = 1.0f - std::exp(-static_cast<float>(frames) / (tau * sample_rate_));
    {
      float& pc = pre_gain_cur_;
      if (std::fabs(pre_gain_tgt_ - pc) > 1e-4f) pc += (pre_gain_tgt_ - pc) * alpha;
      if ((++dbg_blk_ & 15) == 0)
        __android_log_print(ANDROID_LOG_INFO, "M9EQ",
            "pc blk=%d cur=%.4f tgt=%.4f", dbg_blk_, pc, pre_gain_tgt_);
    }
    for (int i = 0; i < active_bands_; ++i) {
      float& cur = current_gain_db_[i];
      const float tgt = target_gain_db_[i];
      if (std::fabs(tgt - cur) > 0.01f) {
        cur += (tgt - cur) * alpha;
        recomputeBand(i);
      }
      for (int k = 0; k < 5; ++k) {
        float& c = cur_coeff_[i][k];
        const float t = tgt_coeff_[i][k];
        if (std::fabs(t - c) > 1e-7f) c += (t - c) * alpha;
      }
    }
    for (int i = 0; i < active_bands_; ++i) {
      // 静默段直接旁路（行业标准做法）：gain≈0 的 peaking 是零点-极点在 DC
      // 对消的临界系统，DF2 内部状态被激励后按极点（数值上可超单位圆）
      // 指数增长，输出对消不完全即爆炸（实测 blk 间 x200）——直接 skip
      if (std::fabs(target_gain_db_[i]) < 0.005f) {
        ++dbg_skip_;
        continue;
      }
      const float b0 = cur_coeff_[i][0], b1 = cur_coeff_[i][1], b2 = cur_coeff_[i][2];
      const float a1 = cur_coeff_[i][3], a2 = cur_coeff_[i][4];
      for (int c = 0; c < channels_; ++c) {
        const int off = c * kMaxBands + i;
        float z1 = z1_[off], z2 = z2_[off];
        float* ch = channels[c];
        for (int n = 0; n < frames; ++n) {
          const float x = ch[n];
          float y = b0 * x + z1;
          z1 = b1 * x - a1 * y + z2;
          z2 = b2 * x - a2 * y;
          if (!std::isfinite(y) || !std::isfinite(z1) || !std::isfinite(z2)) {
            // 系数异常污染防护：清滤波器状态、该样本输出 0，保输出链不死
            ++dbg_nan_samples_;  // 样本级 NaN 计数（文件 IO 已移除：fopen 风暴拖慢 30s 实锤）
            __android_log_print(ANDROID_LOG_ERROR, "M9EQ",
                "NaN sample! band=%d frame=%d -> state reset", i, n);
            z1 = 0.0f; z2 = 0.0f; y = 0.0f;
          }
          ch[n] = y;
        }
        z1_[off] = z1;
        z2_[off] = z2;
      }
    }
    // 末级统一乘总增益（在 for(i) 外，仅乘一次；skip 路径与滤波路径一致生效）
    if (std::fabs(pre_gain_cur_ - 1.0f) > 1e-4f) {
      for (int c = 0; c < channels_; ++c)
        for (int n = 0; n < frames; ++n) channels[c][n] *= pre_gain_cur_;
    }
    // 频谱探针出口喂料（EQ 后信号，每块都喂——同入口）
    {
      int w = spec_wpos_out_.load(std::memory_order_relaxed);
      for (int n = 0; n < frames; ++n) {
        float m = 0.0f;
        for (int c = 0; c < channels_; ++c) m += channels[c][n];
        spec_ring_out_[w] = m / static_cast<float>(channels_);
        w = (w + 1) % kSpecRing;
      }
      spec_wpos_out_.store(w, std::memory_order_relaxed);
    }
  }
  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }
  void SetParam(int param_id, float value) override {
    if ((++dbg_count_ & 7) == 0) {
      __android_log_print(ANDROID_LOG_INFO, "M9EQ",
          "SetParam id=%d v=%.2f | active=%d g0=%.1f g9=%.1f q9=%.2f bypass=%d",
          param_id, value, active_bands_, target_gain_db_[0],
          target_gain_db_[kMaxBands > 9 ? 9 : 0], band_q_[kMaxBands > 9 ? 9 : 0], bypass_);
    }
    if (param_id >= 0 && param_id < kMaxBands) {
      target_gain_db_[param_id] = clamp(value, -12.0f, 12.0f);
      recomputeBand(param_id);
    } else if (param_id >= kQBase && param_id < kQBase + kMaxBands) {
      const int i = param_id - kQBase;
      band_q_[i] = clamp(value, 0.3f, 8.0f);
      recomputeBand(i);
    } else if (param_id >= kFreqBase && param_id < kFreqBase + kMaxBands) {
      const int i = param_id - kFreqBase;
      band_freq_[i] = clamp(value, 20.0f, 20000.0f);
      recomputeBand(i);
    } else if (param_id >= kTypeBase && param_id < kTypeBase + kMaxBands) {
      const int i = param_id - kTypeBase;
      const int t = static_cast<int>(value);
      band_type_[i] = (t >= 0 && t <= 2) ? t : 0;
      recomputeBand(i);
    } else if (param_id == kParamBandCount) {
      // 段数 1~31 任意（早期定制版 对齐：非标准档按对数均分生成默认频点表）
      setBandCount(static_cast<int>(clamp(value, 1.0f, 31.0f)));
    } else if (param_id == kParamPreGainDb) {
      // 总增益（dB ±18，对齐 早期定制版 Preamp）：输出乘系数，防大提升削波/深坑补偿
      pre_gain_tgt_ = std::pow(10.0f, clamp(value, -18.0f, 18.0f) / 20.0f);
    }
  }
  float GetParam(int param_id) const {
    if (param_id >= 0 && param_id < kMaxBands) return target_gain_db_[param_id];
    if (param_id >= kQBase && param_id < kQBase + kMaxBands) return band_q_[param_id - kQBase];
    if (param_id >= kFreqBase && param_id < kFreqBase + kMaxBands)
      return band_freq_[param_id - kFreqBase];
    if (param_id >= kTypeBase && param_id < kTypeBase + kMaxBands)
      return static_cast<float>(band_type_[param_id - kTypeBase]);
    if (param_id == kParamBandCount) return static_cast<float>(active_bands_);
    if (param_id == kParamPreGainDb) return 20.0f * std::log10(std::max(pre_gain_cur_, 1e-6f));  // dbg: cur
    if (param_id == 200) return (float)dbg_skip_;  // 诊断：静默段旁路计数（95 起=段类型）
    return 0.0f;
  }
  int active_bands() const { return active_bands_; }

  // ---- 频谱探针（手绘区衬底）：实时预算铁律设计 ----
  // 回调线程仅做 mono 混合 memcpy 进环形缓冲（128 帧 ≈0.5µs/块，摊薄后可忽略）；
  // FFT/分带/平滑全部在 UI 拉取线程（JNI，30fps，512 点 <20µs）。除环形缓冲
  // 写位（SPSC atomic）外无跨线程共享；GetSpectrum 须在单一 UI 调用点串行使用。
  static constexpr int kSpecRing = 2048;  // = kSpecFft（最近 42.7ms 时间窗）
  static constexpr int kSpecFft = 2048;   // 23.4Hz/bin @48k（20Hz 起低频有真数据）
  static constexpr int kSpecBins = 1024;  // 直出 bin 0..1023（23.4Hz~24kHz），分带采样在 UI

  /** UI 拉取线程调用：ring 最近 2048 mono 样本 → Hann 窗 FFT → 逐 bin dB（-60..0，
   *  峰值保持 2.5dB/帧衰落）。layer 0=EQ 入口 / 1=EQ 出口。分带/对数采样在 UI 侧
   *  （与对数轴天然对齐）。n 须 >= kSpecBins。 */
  void GetSpectrum(float* out, int n, int layer) {
    if (out == nullptr) return;
    if (n < kSpecBins || !spec_fft_ready_) {
      for (int i = 0; i < n; ++i) out[i] = -60.0f;
      return;
    }
    const float* ring = layer == 0 ? spec_ring_in_ : spec_ring_out_;
    const int w = (layer == 0 ? spec_wpos_in_ : spec_wpos_out_).load(std::memory_order_relaxed);
    for (int i = 0; i < kSpecFft; ++i) {
      int idx = w - kSpecFft + i;
      if (idx < 0) idx += kSpecRing;
      spec_re_[i] = ring[idx] * spec_hann_[i];
      spec_im_[i] = 0.0f;
    }
    spec_fft_.Forward(spec_re_.data(), spec_im_.data());
    const float norm = 2.0f / kSpecFft;
    float* hold_db = layer == 0 ? spec_db_in_ : spec_db_out_;
    for (int k = 0; k < kSpecBins; ++k) {
      const float m = std::sqrt(spec_re_[k] * spec_re_[k] + spec_im_[k] * spec_im_[k]);
      const float db = 20.0f * std::log10(m * norm + 1e-6f);
      hold_db[k] = std::max(db, hold_db[k] - 2.5f);
      // 显示范围 -60~+6dB：EQ 大幅提升后出口信号幅值可 >1.0（限幅器在链尾），
      // bin 能量可超 0dB——clamp 到 +6 防溢出，UI 侧允许频谱越过 0dB 线向上画
      if (hold_db[k] < -60.0f) hold_db[k] = -60.0f;
      if (hold_db[k] > 6.0f) hold_db[k] = 6.0f;
      out[k] = hold_db[k];
    }
  }

  void set_channel_count(int channels) {
    channels_ = channels;
    ensureStates();
  }
 private:
  // ---- 频谱探针状态（双 ring：in=EQ 入口 / out=EQ 出口，Pro-Q 式前后对比）----
  float spec_ring_in_[kSpecRing] = {};
  float spec_ring_out_[kSpecRing] = {};
  std::atomic<int> spec_wpos_in_{0};
  std::atomic<int> spec_wpos_out_{0};
  bool spec_fft_ready_ = false;
  ::sonicraft::dsp::FftPlan spec_fft_;
  std::vector<float> spec_re_, spec_im_, spec_hann_;
  float spec_db_in_[kSpecBins] = {};
  float spec_db_out_[kSpecBins] = {};

  static constexpr float kPi = 3.141592653589793f;
  static float clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
  void setBandCount(int n) {
    if (n == active_bands_) return;
    active_bands_ = n;
    __android_log_print(ANDROID_LOG_INFO, "M9EQ", "setBandCount n=%d", n);
    for (int i = 0; i < n; ++i) {
      // 10/15/31 用标准档位表；其余段数按 20~20k 对数均分（末段=20k，早期定制版 同口径）
      float fc;
      if (n == 10) fc = kFreqs10[i];
      else if (n == 15) fc = kFreqs15[i];
      else if (n == 31) fc = kFreqs31[i];
      else if (i == n - 1) fc = 20000.f;
      else {
        fc = std::exp(std::log(20.f) +
                      (std::log(20000.f) - std::log(20.f)) * static_cast<float>(i) /
                          static_cast<float>(n - 1));
      }
      band_freq_[i] = fc;
      band_q_[i] = kDefaultQ;
      target_gain_db_[i] = 0.0f;
      current_gain_db_[i] = 0.0f;
      band_type_[i] = 0;
      recomputeBand(i);
    }
    Reset();
  }
  void ensureStates() {
    const std::size_t need =
        static_cast<std::size_t>(channels_) * static_cast<std::size_t>(kMaxBands);
    if (z1_.size() != need) {
      z1_.assign(need, 0.0f);
      z2_.assign(need, 0.0f);
    }
  }
  void recomputeAll() {
    for (int i = 0; i < kMaxBands; ++i) recomputeBand(i);
  }
  void recomputeBand(int i) {
    // 系数计算用 double（行业标准修法）：低频段 w0 极小时 b0/b2/a0/a2 均≈1、
    // 相互差≈1e-3，float32 的 0/0 型相消会把 H(DC) 推到 1e12 量级（病态）
    const double A = std::pow(10.0, (double)target_gain_db_[i] / 40.0);
    const double w0 = 2.0 * 3.141592653589793 * (double)band_freq_[i] / (double)sample_rate_;
    const double cosw = std::cos(w0);
    const double sinw = std::sin(w0);
    const double qv = (double)band_q_[i] > 0.05 ? (double)band_q_[i] : (double)kDefaultQ;
    const int type = (band_type_[i] >= 0 && band_type_[i] <= 2) ? band_type_[i] : 0;
    double b0, b1, b2, a0, a1, a2;
    if (type == 0) {
      // PK peaking（RBJ cookbook）
      const double alpha = sinw / (2.0 * qv);
      b0 = 1.0 + alpha * A;
      b1 = -2.0 * cosw;
      b2 = 1.0 - alpha * A;
      a0 = 1.0 + alpha / A;
      a1 = -2.0 * cosw;
      a2 = 1.0 - alpha / A;
    } else {
      // Shelf（RBJ cookbook，S=1 最大平坦，AutoEq 默认口径；Q 对 shelf 无效）
      const double sqrtA = std::sqrt(A);
      const double alpha = sinw / 2.0 * std::sqrt(2.0);
      if (type == 1) {
        // LSC low shelf：DC 处 = gain 全量，Nyquist 处 = 0dB
        b0 =       A * ((A + 1.0) - (A - 1.0) * cosw + 2.0 * sqrtA * alpha);
        b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw);
        b2 =       A * ((A + 1.0) - (A - 1.0) * cosw - 2.0 * sqrtA * alpha);
        a0 =           (A + 1.0) + (A - 1.0) * cosw + 2.0 * sqrtA * alpha;
        a1 =    -2.0 * ((A - 1.0) + (A + 1.0) * cosw);
        a2 =           (A + 1.0) + (A - 1.0) * cosw - 2.0 * sqrtA * alpha;
      } else {
        // HSC high shelf：DC 处 = 0dB，Nyquist 处 = gain 全量
        b0 =       A * ((A + 1.0) + (A - 1.0) * cosw + 2.0 * sqrtA * alpha);
        b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw);
        b2 =       A * ((A + 1.0) + (A - 1.0) * cosw - 2.0 * sqrtA * alpha);
        a0 =           (A + 1.0) - (A - 1.0) * cosw + 2.0 * sqrtA * alpha;
        a1 =     2.0 * ((A - 1.0) - (A + 1.0) * cosw);
        a2 =           (A + 1.0) - (A - 1.0) * cosw - 2.0 * sqrtA * alpha;
      }
    }
    if (std::isnan(b0) || std::isnan(b1) || std::isnan(b2) ||
        std::isnan(a1) || std::isnan(a2) || std::isnan(a0) || a0 == 0.0f) {
      // 兜底：系数异常时直接单位增益（直通），绝不产出 NaN 系数
      tgt_coeff_[i][0] = 1.0f; tgt_coeff_[i][1] = 0.0f; tgt_coeff_[i][2] = 0.0f;
      tgt_coeff_[i][3] = 0.0f; tgt_coeff_[i][4] = 0.0f;
    } else {
      tgt_coeff_[i][0] = b0 / a0;
      tgt_coeff_[i][1] = b1 / a0;
      tgt_coeff_[i][2] = b2 / a0;
      tgt_coeff_[i][3] = a1 / a0;
      tgt_coeff_[i][4] = a2 / a0;
    }
    if (!coeff_initialized_[i]) {
      for (int k = 0; k < 5; ++k) cur_coeff_[i][k] = tgt_coeff_[i][k];
      coeff_initialized_[i] = true;
    }
    if (std::isnan(tgt_coeff_[i][0]) || std::isnan(tgt_coeff_[i][3]) ||
        std::isnan(tgt_coeff_[i][1]) || std::isnan(tgt_coeff_[i][2]) ||
        std::isnan(tgt_coeff_[i][4])) {
      {
        FILE* f = fopen("/data/data/com.jiangjie.Sonicraft/files/crash/eq_nan.log", "a");
        if (f) {
          fprintf(f, "ts=%lld NaN coeff band=%d freq=%.4f q=%.4f gain=%.4f sr=%d\n",
                  (long long)time(nullptr), i, band_freq_[i], band_q_[i],
                  target_gain_db_[i], sample_rate_);
          fclose(f);
        }
      }
      __android_log_print(ANDROID_LOG_ERROR, "M9EQ",
          "NaN coeff! band=%d freq=%.4f q=%.4f gain=%.4f sr=%d",
          i, band_freq_[i], band_q_[i], target_gain_db_[i], sample_rate_);
    }
  }
  int sample_rate_ = 48000;
  int channels_ = 2;
  int active_bands_ = 10;
  bool bypass_ = true;
  int dbg_count_ = 0;
  long dbg_nan_samples_ = 0;
  long dbg_skip_ = 0;
  long dbg_blk_ = 0;
  float pre_gain_tgt_ = 1.0f;   // 总增益目标（线性）
  float pre_gain_cur_ = 1.0f;   // 总增益当前（30ms 斜坡）
  std::array<float, kMaxBands> target_gain_db_{};
  std::array<float, kMaxBands> current_gain_db_{};
  std::array<float, kMaxBands> band_q_{};
  std::array<int, kMaxBands> band_type_{};  // 0=PK 1=LSC 2=HSC
  std::array<float, kMaxBands> band_freq_ = {
      31.f, 62.f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f,
      1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f,
      1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f, 1000.f,
      1000.f, 1000.f};
  std::array<std::array<float, 5>, kMaxBands> tgt_coeff_{};
  std::array<std::array<float, 5>, kMaxBands> cur_coeff_{};
  std::array<bool, kMaxBands> coeff_initialized_{};
  std::vector<float> z1_;
  std::vector<float> z2_;
};
}  // namespace sonicraft::dsp
