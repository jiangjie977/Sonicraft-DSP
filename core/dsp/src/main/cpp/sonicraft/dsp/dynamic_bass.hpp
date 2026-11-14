/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
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
 * M15 动态低音节点（耳机专用）。设计见 `docs/M15_DYNAMIC_BASS_DESIGN.md`。
 *
 * ## 为什么不是一个"低频增益"旋钮
 * 耳机的三个物理约束决定了必须分开处理：
 *   1. 40~50Hz 以下急速滚降 → 提 30Hz 只消耗行程、拉高 THD，听感零收益
 *   2. 小单元行程有限 → 强推低频会机械限幅、破音
 *   3. 等响曲线：小音量时人耳对低频极不敏感 → 静态 boost 小音量不够、大音量轰头
 *
 * ## 三个模块（本文件按阶段实现）
 *   ① 次低频清理（Subsonic HPF，LR4）        —— 阶段 1 ✅
 *   ② 动态低频增强（按相对电平自适应）        —— 阶段 3
 *   ③ 低频保护限幅（防单元过载，含 look-ahead）—— 阶段 4
 *
 * ## 参数
 *   param 0 = amount        总强度（0~1，缩放 ②③）
 *   param 1 = subsonicHz    次低频清理截止（Hz，0 = 关闭，默认 30）
 *   param 2 = focusHz       低音中心频率（40~150，默认 80）—— ②的分频点
 *   param 3 = boostDepth    动态增强深度（0~1，默认 0.5）
 *   param 4 = protectLevel  低频保护阈值（0~1，默认 0.6）
 *
 * 计量（GetMeters 输出 3 个值）：
 *   [0] 输入低频 RMS   [1] 当前动态提升量 dB   [2] 低频保护 GR dB（负值）
 *
 * ## 不做什么（重要）
 * **不做心理声学谐波扩展**。生成 2/3 次谐波是"让耳机发出它放不出的低频"的唯一途径，
 * 但 60Hz 基频的 2/3 次谐波落在 120/180Hz —— 正是人声主体，且无法通过参数消除。
 * 用户明确选择「绝不污染人声」优先于「下潜感」，故本节点不生成任何新频率成分。
 */
class DynamicBassNode : public DspNode {
 public:
  static constexpr int kParamAmount = 0;
  static constexpr int kParamSubsonicHz = 1;
  static constexpr int kParamFocusHz = 2;
  static constexpr int kParamBoostDepth = 3;
  static constexpr int kParamProtectLevel = 4;

  static constexpr float kDefaultAmount = 0.35f;
  static constexpr float kDefaultSubsonicHz = 30.0f;
  static constexpr float kDefaultFocusHz = 80.0f;
  static constexpr float kDefaultBoostDepth = 0.5f;
  /**
   * 默认 0.6 → 新映射下 ceiling ≈ -8.4dBFS（0.38）。
   *
   * **注意方向**：protectLevel 越高、阈值越高、介入越晚。
   * 2026-11-11 曾误改成 0.4，那会让 ceiling 掉到 -11.6dBFS、保护**更激进**，
   * 与"让保护别乱压"的意图正好相反 —— 已改回 0.6（新映射本身已比旧映射宽松）。
   */
  static constexpr float kDefaultProtectLevel = 0.6f;

  // ---- 动态增强的时间常数（2026-11-11 阶段 3）----
  /** 包络攻击：低频要跟得快，否则鼓点起音漏掉 */
  static constexpr float kEnvAttackMs = 15.0f;
  /** 包络释放：放慢，避免低频周期性起伏导致增益"呼吸" */
  static constexpr float kEnvReleaseMs = 250.0f;
  /**
   * 参考峰值的**上升**时间（2026-11-11 修正）：
   * 必须显著慢于包络的 release，否则 `ref` 会瞬时贴住 `env`、导致 rel ≡ 1、
   * 动态增益恒为 0 —— 首次实现就踩了这个坑（阶段 2 透明性探针没 FAIL 反而暴露了它）。
   */
  static constexpr float kRefAttackMs = 2000.0f;
  /** 参考峰值的回落速度：极慢，只用来跟上不同曲目的响度差异 */
  static constexpr float kRefDecayDbPerSec = 3.0f;
  /** 增益平滑：目标增益每块算一次，逐样本逼近，避免块边界出现阶梯 */
  static constexpr float kGainSmoothMs = 40.0f;
  /** boostDepth = 1 且 amount = 1 时的最大提升量 */
  static constexpr float kMaxBoostDb = 12.0f;

  // ---- 低频保护（阶段 4）----
  /**
   * 前瞻量。低频单元的机械过载是**瞬时**的，等峰值到了再压已经晚了，
   * 所以固定 1.5ms 前瞻 —— 增益提前生效，瞬态来不及冲过去。
   *
   * **代价：整个节点引入 1.5ms 延迟**（但只在保护开启时；protectLevel=0 时走直通不延迟）。
   */
  static constexpr float kProtectLookaheadMs = 1.5f;
  /** 保护包络：瞬时攻击（保护优先，绝不让峰值漏过去）+ 80ms 释放 */
  static constexpr float kProtectReleaseMs = 80.0f;
  /**
   * 阈值映射：protectLevel 0 → 关闭；0.4（默认）→ 约 -11.6dBFS；1 → **-2dBFS**。
   *
   * **上限从 -6dBFS 提到 -2dBFS**（2026-11-11 真机实测后修正）：
   * 商业音乐的峰值普遍在 -1~0dBFS，-6dBFS 的阈值意味着**几乎全程介入** ——
   * 实测 bad guy 副歌（输入 -8dBFS）时低频被压掉 2dB，
   * 用户感受是"参数拉满，低音反而更弱"，比偶尔过载糟糕得多。
   * 提到 -2dBFS 后：普通音量完全不介入，只有真接近削波才压。
   * 兜底还有链尾主限幅器（默认开启），不会真的削波。
   */
  static constexpr float kProtectCeilMinDb = -18.0f;
  static constexpr float kProtectCeilMaxDb = -2.0f;

  static constexpr float kMinFocusHz = 40.0f;
  static constexpr float kMaxFocusHz = 150.0f;
  static constexpr float kMaxSubsonicHz = 50.0f;

  std::string_view id() const override { return "dynamic_bass"; }

  void set_channel_count(int channels) { channels_ = channels > 0 ? channels : 2; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    max_frames_ = max_frames > 0 ? max_frames : 128;
    // 每声道一套 LR4 高通（2 节级联）
    hpf_.assign(static_cast<std::size_t>(channels_), LR4Split{});
    split_.assign(static_cast<std::size_t>(channels_), LR4Split{});
    // 参数落回默认（与其它节点一致：Prepare 重置到已知状态，真实值由重放补发）
    amount_ = kDefaultAmount;
    subsonic_hz_ = kDefaultSubsonicHz;
    focus_hz_ = kDefaultFocusHz;
    boost_depth_ = kDefaultBoostDepth;
    protect_level_ = kDefaultProtectLevel;
    recomputeDynamicCoeffs();
    configureHpf();
    configureSplit();

    // 低频保护的 look-ahead 延迟线：**lo 与 hi 各一条、延迟量相同**。
    // 只延迟 lo 会让 lo/hi 在分频点附近产生相位差，重构不再平坦（梳状滤波）。
    dly_size_ = static_cast<int>(kProtectLookaheadMs * 0.001f *
                                 static_cast<float>(sample_rate_)) + 2;
    dly_len_ = dly_size_ - 2;   // 实际延迟样本数
    dly_lo_.assign(static_cast<std::size_t>(channels_),
                   std::vector<float>(static_cast<std::size_t>(dly_size_), 0.0f));
    dly_hi_.assign(static_cast<std::size_t>(channels_),
                   std::vector<float>(static_cast<std::size_t>(dly_size_), 0.0f));
    dly_pos_ = 0;
    for (auto& s : hpf_) s.CommitTargets();   // Prepare 阶段直接落位，不做 ramp
    for (auto& s : split_) s.CommitTargets();
    Reset();
  }

  void Reset() override {
    for (auto& s : hpf_) s.Reset();
    for (auto& s : split_) s.Reset();
    meter_in_low_ = 0.0f;
    meter_boost_db_ = 0.0f;
    meter_gr_db_ = 0.0f;
    low_env_ = 0.0f;
    ref_level_ = 1e-6f;   // 参考峰值：启动时贴地，随信号抬升
    gain_run_ = 1.0f;     // 实际作用的增益，逐样本平滑逼近 target
    meter_boost_db_ = 0.0f;
    protect_env_ = 0.0f;
    protect_gain_ = 1.0f;
    for (auto& d : dly_lo_) std::fill(d.begin(), d.end(), 0.0f);
    for (auto& d : dly_hi_) std::fill(d.begin(), d.end(), 0.0f);
    dly_pos_ = 0;
  }

  void Process(float* const* channels, int frames) override {
    if (channels == nullptr || frames <= 0) return;

    // 旁路：**完全不碰信号**，保证与输入逐位相同（探针实测 max_err=0）
    if (bypass_) return;

    const bool do_hpf = subsonic_hz_ > 0.0f;
    if (do_hpf) {
      for (auto& s : hpf_) s.BeginBlockRamp(frames);
    }
    for (auto& s : split_) s.BeginBlockRamp(frames);

    // 目标增益**每块算一次**（含一次 pow）：包络本身是慢变量，块级（~2.7ms）
    // 的延迟完全够用；逐样本做 pow 会白白吃掉 CPU。
    const float target = computeTargetGain();

    for (int i = 0; i < frames; ++i) {
      float lo_buf[kMaxChannelsLocal] = {0.0f};
      float hi_buf[kMaxChannelsLocal] = {0.0f};
      float lo_peak = 0.0f;

      // 先算出所有声道的 lo/hi —— 包络必须**跨声道取 max**，
      // 否则单侧信号会让两声道拿到不同增益，造成声像漂移（与压缩器同一教训）。
      const int nch = std::min(channels_, kMaxChannelsLocal);
      for (int c = 0; c < nch; ++c) {
        float v = channels[c][i];

        // ① 次低频清理（LR4 高通，两节级联）
        if (do_hpf) {
          LR4Split& hf = hpf_[static_cast<std::size_t>(c)];
          hf.hp[0].StepCoeffs();
          v = hf.hp[0].Process(v);
          hf.hp[1].StepCoeffs();
          v = hf.hp[1].Process(v);
        }

        // ② 分频：LR4 的 lp4 与 hp4 相位一致、相加平坦
        LR4Split& sp = split_[static_cast<std::size_t>(c)];
        sp.lp[0].StepCoeffs();
        float lo = sp.lp[0].Process(v);
        sp.lp[1].StepCoeffs();
        lo = sp.lp[1].Process(lo);

        sp.hp[0].StepCoeffs();
        float hi = sp.hp[0].Process(v);
        sp.hp[1].StepCoeffs();
        hi = sp.hp[1].Process(hi);

        lo_buf[c] = lo;
        hi_buf[c] = hi;
        const float alo = std::fabs(lo);
        if (alo > lo_peak) lo_peak = alo;
      }

      // ③ 包络跟踪（跨声道共享）+ 参考峰值
      updateEnvelope(lo_peak);

      // ④ 增益逐样本平滑逼近块级目标（消块边界阶梯）
      gain_run_ += (target - gain_run_) * gain_smooth_coef_;

      // ⑤ 低频保护（阶段 4）：先算保护增益，再走 look-ahead 延迟线输出。
      const bool do_protect = protect_level_ > 0.001f;
      if (do_protect) {
        // 检测跨声道最大低频峰值（与包络同一口径，保证两声道拿同一增益）
        float pk = 0.0f;
        for (int c = 0; c < nch; ++c) {
          const float alo = std::fabs(lo_buf[c] * gain_run_);
          if (alo > pk) pk = alo;
        }
        updateProtect(pk);

        // 写入延迟线（lo / hi 同步推进），再读 1.5ms 前的值 ——
        // 增益在检测那一刻就确定，却作用在"还没到"的样本上，等效于提前生效
        for (int c = 0; c < nch; ++c) {
          std::vector<float>& dl = dly_lo_[static_cast<std::size_t>(c)];
          std::vector<float>& dh = dly_hi_[static_cast<std::size_t>(c)];
          dl[static_cast<std::size_t>(dly_pos_)] = lo_buf[c] * gain_run_;
          dh[static_cast<std::size_t>(dly_pos_)] = hi_buf[c];
          int r = dly_pos_ - dly_len_;
          if (r < 0) r += dly_size_;
          channels[c][i] = dl[static_cast<std::size_t>(r)] * protect_gain_ +
                           dh[static_cast<std::size_t>(r)];
        }
        dly_pos_ = (dly_pos_ + 1) % dly_size_;
      } else {
        // 保护关闭：不延迟、不改 hi，只缩放低频带
        for (int c = 0; c < nch; ++c) {
          channels[c][i] = lo_buf[c] * gain_run_ + hi_buf[c];
        }
      }
    }

    if (do_hpf) {
      for (auto& s : hpf_) s.SnapCoeffs();
    }
    for (auto& s : split_) s.SnapCoeffs();

    // 计量
    meter_in_low_ = low_env_;
    meter_boost_db_ = 20.0f * std::log10(gain_run_ > 1e-9f ? gain_run_ : 1e-9f);
    // 保护 GR：0 表示没介入，负值表示压了多少
    meter_gr_db_ = 20.0f * std::log10(protect_gain_ > 1e-9f ? protect_gain_ : 1e-9f);
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    switch (param_id) {
      case kParamAmount:
        amount_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamSubsonicHz: {
        const float v = std::clamp(value, 0.0f, kMaxSubsonicHz);
        if (v != subsonic_hz_) {
          subsonic_hz_ = v;
          configureHpf();
          // 新系数由下一块的 BeginBlockRamp 平滑逼近，不在这里 snap（否则爆音）
        }
        break;
      }
      case kParamFocusHz: {
        const float v = std::clamp(value, kMinFocusHz, kMaxFocusHz);
        if (v != focus_hz_) {
          focus_hz_ = v;
          configureSplit();   // 新系数由下一块 BeginBlockRamp 平滑逼近
        }
        break;
      }
      case kParamBoostDepth:
        boost_depth_ = std::clamp(value, 0.0f, 1.0f);
        break;
      case kParamProtectLevel:
        protect_level_ = std::clamp(value, 0.0f, 1.0f);
        break;
      default:
        break;
    }
  }

  /** 计量输出：[0] 输入低频 RMS、[1] 动态提升 dB、[2] 保护 GR dB */
  void GetMeters(float* out) const {
    if (out == nullptr) return;
    out[0] = meter_in_low_;
    out[1] = meter_boost_db_;
    out[2] = meter_gr_db_;
  }

 private:
  /** 时间常数换算（Prepare 与采样率变化时调用）。 */
  void recomputeDynamicCoeffs() {
    const float sr = static_cast<float>(sample_rate_);
    env_attack_coef_ = 1.0f - std::exp(-1.0f / (kEnvAttackMs * 0.001f * sr));
    env_release_coef_ = 1.0f - std::exp(-1.0f / (kEnvReleaseMs * 0.001f * sr));
    gain_smooth_coef_ = 1.0f - std::exp(-1.0f / (kGainSmoothMs * 0.001f * sr));
    // 参考峰值每秒回落 kRefDecayDbPerSec：换算成每样本系数
    ref_attack_coef_ = 1.0f - std::exp(-1.0f / (kRefAttackMs * 0.001f * sr));
    protect_release_coef_ = 1.0f - std::exp(-1.0f / (kProtectReleaseMs * 0.001f * sr));
    const float decay_per_sample = 1.0f - std::pow(10.0f, -kRefDecayDbPerSec / 20.0f / sr);
    ref_decay_coef_ = decay_per_sample;
  }

  /**
   * 包络：快攻（15ms）慢放（250ms）；参考峰值**慢速**跟涨（2s）极慢回落（3dB/s）。
   *
   * 两级时间常数的差距是动态增强能成立的前提：
   *   env 跟得动鼓点的起伏，ref 只跟得上"这首歌整体多响"，
   *   于是 `env / ref` 在低频强的段落接近 1（不提升）、在低频弱的段落明显小于 1（多提升）。
   */
  void updateEnvelope(float peak) {
    const float coef = (peak > low_env_) ? env_attack_coef_ : env_release_coef_;
    low_env_ += (peak - low_env_) * coef;

    // ref 用**独立**的慢速系数，不能直接赋值为 low_env_
    // （那会让 rel ≡ 1，动态增益恒为 0 —— 见 kRefAttackMs 的注释）
    if (low_env_ > ref_level_) {
      ref_level_ += (low_env_ - ref_level_) * ref_attack_coef_;
    } else {
      ref_level_ -= ref_level_ * ref_decay_coef_;
    }
    if (ref_level_ < 1e-6f) ref_level_ = 1e-6f;
  }

  /**
   * 目标低频带增益。
   *
   * **为什么用相对电平而不是绝对 dBFS**：流媒体响度战导致源电平差异 10dB+，
   * 绝对阈值会让"安静的歌永远满额提升、响的歌永远不提升"。
   * 以本曲自己的滚动峰值为参考，才能做到"小音量多提、大音量少提"。
   */
  float computeTargetGain() const {
    if (amount_ <= 0.0f || boost_depth_ <= 0.0f) return 1.0f;
    const float max_boost_db = boost_depth_ * kMaxBoostDb * amount_;
    if (max_boost_db <= 0.0f) return 1.0f;
    const float rel = (ref_level_ > 1e-6f)
                          ? std::clamp(low_env_ / ref_level_, 0.0f, 1.0f)
                          : 1.0f;
    // rel 越接近 1（= 已到本曲峰值）提升越少；越小提升越多
    const float boost_db = max_boost_db * (1.0f - rel);
    return std::pow(10.0f, boost_db / 20.0f);
  }

  /**
   * 低频保护包络与增益。
   *
   * 攻击是**瞬时**的（峰值一超阈值立刻压住）：低频单元的过载是不可逆的机械问题，
   * 不能像压缩器那样给 10ms 的柔和攻击。释放 80ms，避免低频周期性起伏导致音量抽吸。
   */
  void updateProtect(float peak) {
    // 瞬时攻击：包络直接跟随更高的峰值
    if (peak > protect_env_) {
      protect_env_ = peak;
    } else {
      protect_env_ += (peak - protect_env_) * protect_release_coef_;
    }

    const float ceil = protectCeiling();
    const float want = (protect_env_ > ceil && protect_env_ > 1e-9f)
                           ? (ceil / protect_env_)
                           : 1.0f;
    // 压下去要立刻生效（否则一个样本的峰就漏了），放开才走平滑
    if (want < protect_gain_) {
      protect_gain_ = want;
    } else {
      protect_gain_ += (want - protect_gain_) * protect_release_coef_;
    }
  }

  /** 当前保护阈值（线性）。protectLevel 越高，阈值越高、介入越晚。 */
  float protectCeiling() const {
    const float db = kProtectCeilMinDb +
                     protect_level_ * (kProtectCeilMaxDb - kProtectCeilMinDb);
    return std::pow(10.0f, db / 20.0f);
  }

  /** 按当前 subsonic_hz_ 写目标系数（0 = 关闭时跳过）。 */
  void configureHpf() {
    if (subsonic_hz_ <= 0.0f) return;   // 关闭：不更新系数（Process 跳过该级）
    for (auto& s : hpf_) s.ConfigureTarget(subsonic_hz_, sample_rate_);
  }

  /** 按当前 focus_hz_ 写分频系数（低频带 / 高频带分离点）。 */
  void configureSplit() {
    for (auto& s : split_) s.ConfigureTarget(focus_hz_, sample_rate_);
  }

  int sample_rate_ = 48000;
  int max_frames_ = 128;
  int channels_ = 2;

  float amount_ = kDefaultAmount;
  float subsonic_hz_ = kDefaultSubsonicHz;
  float focus_hz_ = kDefaultFocusHz;
  float boost_depth_ = kDefaultBoostDepth;
  float protect_level_ = kDefaultProtectLevel;

  bool bypass_ = true;   // 默认透传（与其它节点一致，持久化状态由引擎 Running 后补发）

  std::vector<LR4Split> hpf_;    // 每声道一套次低频清理（只用 hp 两节）
  std::vector<LR4Split> split_;  // 每声道一套 80Hz 分频（lp 低频带 / hp 高频带）

  // 计量（阶段 1 只用 [0]）
  float meter_in_low_ = 0.0f;
  float meter_boost_db_ = 0.0f;
  float meter_gr_db_ = 0.0f;

  // ---- 动态增强状态（阶段 3）----
  static constexpr int kMaxChannelsLocal = 16;
  float low_env_ = 0.0f;        // 低频带包络（跨声道共享）
  float ref_level_ = 1e-6f;     // 参考峰值（滚动最大值，缓慢回落）
  float gain_run_ = 1.0f;       // 实际作用的低频增益（逐样本平滑）
  float env_attack_coef_ = 0.0f;
  float env_release_coef_ = 0.0f;
  float ref_attack_coef_ = 0.0f;
  float ref_decay_coef_ = 0.0f;
  float gain_smooth_coef_ = 0.0f;

  // ---- 低频保护（阶段 4）----
  float protect_env_ = 0.0f;         // 低频带峰值包络（跨声道共享）
  float protect_gain_ = 1.0f;        // 当前保护增益（<= 1）
  float protect_release_coef_ = 0.0f;
  std::vector<std::vector<float>> dly_lo_;   // look-ahead（每声道一条）
  std::vector<std::vector<float>> dly_hi_;   // 与 lo 同步延迟，保持重构平坦
  int dly_size_ = 1;
  int dly_len_ = 0;
  int dly_pos_ = 0;
};

}  // namespace sonicraft::dsp
