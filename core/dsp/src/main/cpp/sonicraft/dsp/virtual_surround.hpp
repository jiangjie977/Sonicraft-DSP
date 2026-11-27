/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <array>
#include <cmath>
#include <vector>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/hoa.hpp"
#include "sonicraft/dsp/vbap.hpp"
#include "sonicraft/dsp/vs_decoder.hpp"
#include "sonicraft/dsp/vs_encoder.hpp"
#include "sonicraft/dsp/vs_hrtf_engine.hpp"
#include "sonicraft/dsp/convolver.hpp"
#include "sonicraft/dsp/vs_params.hpp"
#include "sonicraft/dsp/vs_post.hpp"
#include "sonicraft/dsp/vs_selftest.hpp"

namespace sonicraft::dsp {

/**
 * 虚拟环绕渲染节点（立体声 → 7 声道虚拟布局 → 双耳）。
 *
 * 处理链（级内顺序不可调换）：
 *   ① 解码   立体声 → 7 声道（M/S + 相干度 + 非对称 steering + 80Hz LFE）
 *   ② 编解码 可选 Lt/Rt 往返（强度 0 = 直通）—— 待实现
 *   ③ 后处理 逐声道 trim/静音、中置三态、人声清晰度、低音谐波、后中置 —— 待实现
 *   ④ 渲染   HRTF（每声道一组参数化模型）→ 双耳；房间 RIR —— 待实现
 *
 * 实时性：Prepare 分配全部缓冲；Process 内不分配、不加锁、不打日志。
 * 参数经 DspGraph 的 SPSC 队列投递，节点内一阶平滑，开关走 20 ms 交叉淡化。
 */
class VirtualSurroundNode final : public DspNode {
 public:
  VirtualSurroundNode() {
    for (int i = 0; i < vs::kParamCount; ++i) {
      param_[i] = vs::kDefault[i];
      target_[i] = vs::kDefault[i];
    }
  }

  std::string_view id() const override { return "virtual_surround"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    max_frames_ = max_frames > 0 ? max_frames : 512;
    layout_.assign(static_cast<std::size_t>(vs::kChannelCount) * max_frames_, 0.0f);

    const float sr = static_cast<float>(sample_rate_);
    decoder_.Prepare(sr);
    width_lp_.Set(200.0f, sr);
    width_hp_.SetHighPass(4000.0f, sr);
    decoder2_.Prepare(sr);
    round_trip_.Prepare(sr);
    for (int c = 0; c < vs::kChannelCount; ++c) {
      hrtf_ch_[static_cast<std::size_t>(c)].Prepare(sr);
      hrtf_ch_[static_cast<std::size_t>(c)].Configure(vs::kAzimuthDeg[c], 0.0f);
    }
    static constexpr float kUpAz[4] = {-30.0f, 30.0f, -110.0f, 110.0f};
    for (int i = 0; i < 4; ++i) {
      hrtf_up_[static_cast<std::size_t>(i)].Prepare(sr);
      hrtf_up_[static_cast<std::size_t>(i)].Configure(kUpAz[i], 35.0f);
      hf_hp_[static_cast<std::size_t>(i)].SetHighPass(3000.0f, sr);
    }
    // ---- Ambisonic：12 虚拟扬声器（B 布局）+ 10 声源球谐系数 ----
    for (int s = 0; s < kAmbiSources; ++s) {
      vs::HoaEncoder::SourceCoeffs(kAmbiSrcAz[s], kAmbiSrcEl[s], ambi_coeff_[s]);
    }
    for (int j = 0; j < vs::HoaDecoder::kSpeakers; ++j) {
      auto& h = hrtf_ambi_[static_cast<std::size_t>(j)];
      h.Prepare(sr);
      h.Configure(vs::HoaDecoder::kSpkAz[j], vs::HoaDecoder::kSpkEl[j]);
    }
    // VBAP：声源方位与 HOA 完全一致（6 水平 + 4 上方 35°），表在首次用到时构建
    vbap_.Init(kAmbiSrcAz, kAmbiSrcEl, kAmbiSources,
               vs::HoaDecoder::kSpkAz, vs::HoaDecoder::kSpkEl,
               param_[vs::kParamVbapSpread]);
    vbap_ready_ = true;
    vbap_dirty_ = false;

    // 矩阵在首次需要时按 focus 构建（见 Process），这里只把状态清成"未构建"
    ambi_ready_ = false;
    ambi_dirty_ = true;
    ambi_focus_used_ = -1.0f;

    hrtf_model_.Prepare(sr);
    // 模型编号由参数 kParamHrtfModel 决定；Prepare 后强制下一次 Process 重新下发
    hrtf_model_applied_ = -1;
    post_.Prepare(sr);
    // 房间卷积复用现成的 ConvolverNode：它已内置 Dry/小房/客厅/音乐厅/大教堂 五档 RIR，
    // 与我们的 roomSize 取值 0..4 一一对应（0 = 关，不跑）。
    room_.set_channel_count(2);
    room_.Prepare(sample_rate_, max_frames_);
    room_.SetBypass(false);
    for (int c = 0; c < vs::kChannelCount; ++c) {
      ch_ptr_[static_cast<std::size_t>(c)] = layout_.data() +
          static_cast<std::size_t>(c) * static_cast<std::size_t>(max_frames_);
    }
    Reset();
  }

  void Reset() override {
    render_gain_ = 0.0f;  // 从静音淡入，避免开关瞬间爆音
    decoder_.Reset();
    decoder2_.Reset();
    round_trip_.Reset();
    for (auto& h : hrtf_ch_) h.Reset();
    for (auto& h : hrtf_up_) h.Reset();
    for (auto& h : hrtf_ambi_) h.Reset();
    for (auto& b : hf_hp_) b.Reset();
    hrtf_model_.Reset();
    post_.Reset();
    room_.Reset();
    for (int i = 0; i < kErSize; ++i) { er_l_[i] = 0.0f; er_r_[i] = 0.0f; }
    er_pos_ = 0;
    er_lp_l_ = 0.0f;
    er_lp_r_ = 0.0f;
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id < 0 || param_id >= vs::kParamCount) return;
    target_[param_id] = value;
    // Ambisonic 解码矩阵与 focus 绑定：参数一变就标脏，由 Process 重建一次。
    // 重建是 9×9 高斯-约当（约 1.5 k flops，微秒级），只发生在用户改参数时。
    if (param_id == vs::kParamAmbiFocus || param_id == vs::kParamAmbisonicMode) {
      ambi_dirty_ = true;
      vbap_dirty_ = true;
    }
    if (param_id == vs::kParamVbapSpread) {
      vbap_dirty_ = true;
    }
    // HRTF 模型同样不能等 Process：空闲时 Process 不跑，模型切换会"显示已切、实际没换"。
    // 切换只做指针替换 + 一次性 O(557) 归一化，代价可控。
    if (param_id == vs::kParamHrtfModel) {
      const int model = static_cast<int>(value + 0.5f);
      if (model != hrtf_model_applied_) {
        ApplyHrtfModel(model);
        hrtf_model_applied_ = model;
      }
    }
  }

  /**
   * 声像定位自检 + 自动校正（**非实时**，由 UI/JNI 在非音频线程触发）。
   *
   * 用带限噪声在 **真实 5.1 声道方位**（±30 前置 / ±110 侧后 / 180 正后）测 7 个方位，检查：
   *   ① 左右不接反（θ>0 必须"右响 + 右耳早"）
   *   ② 左右镜像对称
   *   ③ ITD/ILD 与理论值的偏差（判据见 LocalizationSelfTest）
   *
   * apply_correction=true 时把校正系数写回渲染器（ITD 缩放、ILD 修调），再测一轮确认收敛。
   */
  vs::LocalizationSelfTest::Report RunLocalizationSelfTest(bool apply_correction = true) {
    // 按**当前解码器**的扬声器集合测：经典 = 7 声道 5.1；Ambisonic = 12 扬声器（含 35° 仰角）。
    // 用错集合会得到"通过"的假象——12 扬声器里有 4 个在头顶，5.1 那张表根本测不到。
    // 读 target_ 而不是 param_：param_ 是逐样本平滑后的值，只有在音频真的流过时才会推进；
    // 空闲状态（没有音频、或节点被 bypass）下它可能还停在 0，导致自检选错布局。
    if (target_[vs::kParamAmbisonicMode] > 0.5f) {
      static const char* kAmbiLabels[vs::HoaDecoder::kSpeakers] = {
          "H0", "H45", "H90", "H135", "H180", "H225", "H270", "H315",
          "UFL", "UFR", "UBL", "UBR"};
      last_report_ = vs::LocalizationSelfTest::RunLayout(
          hrtf_model_, static_cast<float>(sample_rate_),
          vs::HoaDecoder::kSpkAz, vs::HoaDecoder::kSpkEl,
          kAmbiLabels, vs::HoaDecoder::kSpeakers, apply_correction);
    } else {
      last_report_ = vs::LocalizationSelfTest::Run(hrtf_model_, static_cast<float>(sample_rate_),
                                                   apply_correction);
    }
    if (apply_correction) {
      // 校正同样作用到实际渲染用的 HRTF，否则自检与实际渲染口径不一致
      for (auto& h : hrtf_ch_) h.SetCalibration(last_report_.itd_scale, last_report_.ild_trim);
      for (auto& h : hrtf_ambi_) h.SetCalibration(last_report_.itd_scale, last_report_.ild_trim);
    }
    return last_report_;
  }

  const vs::LocalizationSelfTest::Report& last_self_test() const { return last_report_; }

  /** 诊断用：读取「命令值」（target_），用于确认 UI 的推送是否真的到达 native */
  float DebugTarget(int id) const {
    return (id >= 0 && id < vs::kParamCount) ? target_[id] : -1.0f;
  }

  void Process(float* const* channels, int frames) override {
    if (frames <= 0 || channels == nullptr) return;
    if (frames > max_frames_) frames = max_frames_;

    // 参数一阶平滑（约 20 ms 时间常数 @48k/512）
    constexpr float kParamSmooth = 0.25f;
    for (int i = 0; i < vs::kParamCount; ++i) {
      param_[i] += kParamSmooth * (target_[i] - param_[i]);
    }

    const bool want = param_[vs::kParamEnabled] > 0.5f && !bypass_;
    if (!want && render_gain_ <= 0.0f) return;  // 完全旁路：逐样本透传，零开销

    // HRTF 模型：0 = 参数化（默认，低算力）；1..5 = 实测 IR（557 tap 卷积）
    const int hrtf_model = static_cast<int>(param_[vs::kParamHrtfModel] + 0.5f);
    if (hrtf_model != hrtf_model_applied_) {
      ApplyHrtfModel(hrtf_model);
      hrtf_model_applied_ = hrtf_model;
    }

    // 解码方式：0=经典矩阵 / 1=HOA / 2=VBAP（后两者都是 12 只虚拟扬声器，共用 12 路 HRTF）
    const int decoder = static_cast<int>(param_[vs::kParamAmbisonicMode] + 0.5f);
    render_decoder_ = decoder;
    const bool ambi_on = decoder > 0;

    // HOA 解码矩阵：只在开启且参数变化时重建一次（求逆不在每个回调里跑）
    if (decoder == 1 && (ambi_dirty_ || !ambi_ready_)) {
      ambi_ready_ = hoa_.Init(param_[vs::kParamAmbiFocus]);
      ambi_focus_used_ = param_[vs::kParamAmbiFocus];
      ambi_dirty_ = false;
    }
    // VBAP 表：声源方位固定（6 水平 + 4 上方），只在扩散量变化时重建
    if (decoder == 2 && (vbap_dirty_ || !vbap_ready_)) {
      vbap_ready_ = vbap_.RebuildSpread(param_[vs::kParamVbapSpread]);
      vbap_dirty_ = false;
    }

    // ---- 三段立体声展宽（低 / 中 / 高独立权重）----
    // 为什么分段：低频展宽会破坏单声道兼容（左右反相成分在单声道折叠时抵消），
    // 而高频展宽几乎不影响兼容性、却最容易被感知为"空间感"。
    // 单一宽度做不到这个区分（早期定制版 的 ExtStereoImager 就是三段式）。
    // 权重：低 0.35 / 中 1.0 / 高 1.45，再乘用户的总强度 kParamStereoExpand。
    if (param_[vs::kParamStereoExpand] > 0.01f && channels[1] != nullptr) {
      float* pl = channels[0];
      float* pr = channels[1];
      const float raw_w = param_[vs::kParamStereoExpand];
      const float w = (raw_w < 0.0f) ? 0.0f : ((raw_w > 2.0f) ? 2.0f : raw_w);
      for (int i = 0; i < frames; ++i) {
        const float m = (pl[i] + pr[i]) * 0.5f;
        const float sd = (pl[i] - pr[i]) * 0.5f;
        const float s_low = width_lp_.Process(sd);
        const float s_high = width_hp_.Process(sd);
        const float s_mid = sd - s_low - s_high;
        const float s_out = (s_low * 0.35f + s_mid * 1.0f + s_high * 1.45f) * w;
        pl[i] = m + s_out;
        pr[i] = m - s_out;
      }
    }
    // 入口 −9 dB：解码矩阵、LFE +10 dB、编码电平曲线、7 路 HRTF 叠加会让节点输出
    // 峰值冲到 +8~9 dBFS（实测 2.79），相邻样本跳变 0.76 → 明显过曝与毛刺。
    // 实测衰减扫描：−9 dB 后峰值 1.46、跳变 0.28、越界样本 3770→999（余量交给链末限幅器）。
    // 必须放在旁路判断之后：完全旁路时要保持逐样本透传，不能被衰减。
    static constexpr float kInputTrim = 0.1995262f;
  // 净增益 +2 dB（输出归一化 0.5625，相对 unity 0.5 即 +12dB 补回是按错误标定写的）。
  // 实测内部固有增益 +21 dB，若按 +12 dB 补回则净增益 +19 dB → 输出长期顶满、
  // 软限幅深度介入（听感"过曝/发扁"）。这里收敛到 +2 dB，开关 A/B 差稳定 +3 dB。
  // 净增益 +5 dB（折中）：+2dB 用户反馈偏小，+10dB 用户反馈 A/B 差太大。
  // 实测内部固有增益 +21 dB：0.1995(入口−14dB) × 11.2(+21dB) × 0.7943 = 1.778 (+5dB)。
  // 净增益 +12 dB（用户指定）：0.1995(入口−14dB) × 11.2(+21dB) × 1.7777 = 3.981 (+12dB)。
  // 注意：输出峰值会到约 4×满刻度，全部由软限幅（拐点 0.94）压回 1.0，
  // 介入率预计 35~45% —— 听感是"很响但被压紧、动态变小"。
  // 净增益 +18 dB（用户指定）：0.1995(入口−14dB) × 11.2(+21dB) × 3.549 = 7.94 (+18dB)。
  // 警告：输出峰值会到 8× 满刻度，全靠软限幅（拐点 0.94）压回 1.0，
  // 相当于一个固定 18 dB 的压限器 —— 安静段爆响、爆满段被压成近似方波（失真）。
  static constexpr float kOutputMakeup = 3.549f;  // +12 dB 补回（净 −2 dB，配合输出增益即可复原）
  static constexpr float kSoftenPoint = 0.94f;   // 仅在峰值轻收（0.85 会压掉 30%+ 样本）        // 软限幅拐点
  static constexpr float kSoftenK = 6.0f;             // 软限幅陡度   // −14 dB（基准峰值 1.22 → 1.03，越界基本清零）
    if (channels[0] != nullptr && channels[1] != nullptr) {
      for (int i = 0; i < frames; ++i) {
        channels[0][i] *= kInputTrim;
        channels[1][i] *= kInputTrim;
      }
    }


    const float step = 1.0f / std::max(1.0f, static_cast<float>(sample_rate_) * 0.02f);
    const float goal = want ? 1.0f : 0.0f;

    decoder_.SetMode(param_[vs::kParamMode] > 0.5f);
    // 解码模式：0=Cine6.1 1=LCRS 2=Stereo 3=Mono
    decoder_.SetDecodeMode(static_cast<int>(param_[vs::kParamMode] + 0.5f));
    // 四个声道增益不再对外开放：中置/环绕/前置/后中置一律按 0 dB 直通，    // 电平只由解码矩阵系数与声道 Trim 决定。
    decoder_.SetGains(0.0f, 0.0f, 0.0f);
    decoder_.SetTransientProtect(param_[vs::kParamTransientProtect] > 0.5f);
    decoder_.SetRearSpace(param_[vs::kParamStereoExpand]);
    decoder_.SetBs775(param_[vs::kParamBs775] > 0.5f);

    DecodeFrames(channels[0], channels[1], frames);

    // 仅解码测试：跳过编解码往返、后处理与房间，只保留 解码 → trim → HRTF
    const bool debug_raw = param_[vs::kParamDebugRaw] > 0.5f;

    // Lt/Rt 编解码往返：制造相位扩散（强度 0 = 直通）
    // 【仅解码测试】也保留这一级：trim 作用在**第二级解码器（decoder2）输出**上，
    // 这样测到的才是"解码 → 再解码"之后的真实 7 声道。
    round_trip_.Process(ch_ptr_.data(), frames, param_[vs::kParamEncodeStrength],
                        decoder2_, param_[vs::kParamMode] > 0.5f,
                        param_[vs::kParamCenterGain], param_[vs::kParamSurroundGain]);

    // TODO(试听待修，暂不动算法)：
    //  1. FL/FR 里残留的中置人声过多 —— 中置提取后的扣除比例（C/2）偏小；
    //  2. C 声道人声偏弱、夹带乐器过多 —— 中置输入增益与相干度加权不足；
    //  3. 后环绕方向基本正确，但左右分离度不足、声场偏窄 —— 环绕差分分配比例待调；
    //  4. 低频正常，无需改动。

    // 7 声道后处理：中置三态 / 人声清晰度 / 低音谐波 / 后中置
    vs::PostStage::Params pp;
    pp.center_mode = param_[vs::kParamCenterMode];
    pp.dialog_db = param_[vs::kParamDialog];
    pp.dialog_enabled = param_[vs::kParamDialogEnabled];
    // 低音谐波已恢复（根因是多项式常数项产生直流，已改为奇对称映射）
    pp.bass_enhancer = param_[vs::kParamBassEnhancer];
    pp.bass_enhancer_mode = param_[vs::kParamBassEnhancerMode];
    pp.rear_center_enabled = param_[vs::kParamRearCenterEnabled];
    pp.rear_delay_ms = param_[vs::kParamRearCenterDelayMs];
    if (!debug_raw) post_.Process(ch_ptr_.data(), frames, pp);

    // 仅解码测试也走双耳渲染：trim 作用在 7 声道上，再逐路 HRTF 送双耳输出。
    // 若在这里直接下混，测试链就断了（听的不是最终链路的解码结果）。
    RenderFrames(channels[0], channels[1], frames, goal, step, false,
                 ambi_on && (decoder == 2 ? vbap_ready_ : ambi_ready_));

    // 房间卷积（湿声）：放在双耳渲染之后 —— 先空间化再进房间，房间才不会被 HRTF 染色串掉。
    // 预设映射：1=小房 2=客厅 3=音乐厅 4=大教堂；0=关（完全不跑，零开销）。
    const int room = static_cast<int>(param_[vs::kParamRoomSize] + 0.5f);
    if (!debug_raw && room >= 1 && room <= 4) {
      room_.SetParam(dsp::ConvolverNode::kParamPreset, static_cast<float>(room));
      room_.SetParam(dsp::ConvolverNode::kParamWet, param_[vs::kParamRoomWetDry]);
      room_.SetParam(dsp::ConvolverNode::kParamDry, 1.0f);
      room_.Process(channels, frames);
    }

    // 早期反射：给双耳输出叠几路衰减抽头（同侧 + 交叉），制造房间边界感。
    // 距离参数越大，反射越多、越远；0 时完全不参与（零开销）。
    const float er_amt = debug_raw ? 0.0f
        : (param_[vs::kParamDistance] > 0.0f ? param_[vs::kParamDistance] : 0.0f);
    if (er_amt > 0.01f) {
      // 距离越大：反射延迟拉长（房间更大）、高频吸收更强（空气衰减）、直达声略降
      const float tap_scale = 1.0f + 0.8f * er_amt;
      const int kTap[4] = {
          static_cast<int>(384 * tap_scale), static_cast<int>(624 * tap_scale),
          static_cast<int>(1008 * tap_scale), static_cast<int>(1632 * tap_scale)};
      static constexpr float kGain[4] = {0.50f, 0.35f, 0.25f, 0.18f};
      // 反射路径一阶低通：12 kHz（近）→ 3.5 kHz（远），这是"远=暗"的主要线索
      const float cutoff = 12000.0f - 8500.0f * er_amt;
      const float lp_a = 1.0f - std::exp(-2.0f * 3.14159265f * cutoff / sample_rate_);
      const float direct_gain = 1.0f - 0.25f * er_amt;

      float* cl = channels[0];
      float* cr = channels[1];
      for (int i = 0; i < frames; ++i) {
        er_pos_ = (er_pos_ + 1) % kErSize;
        er_l_[er_pos_] = cl[i];
        er_r_[er_pos_] = cr[i];
        float acc_l = 0.0f, acc_r = 0.0f;
        for (int k = 0; k < 4; ++k) {
          const int d_same = kTap[k] % kErSize;
          const int d_cross = (kTap[k] * 23 / 20) % kErSize;
          const int i_same = (er_pos_ - d_same + kErSize) % kErSize;
          const int i_cross = (er_pos_ - d_cross + kErSize) % kErSize;
          acc_l += er_l_[i_same] * kGain[k] + er_r_[i_cross] * kGain[k] * 0.6f;
          acc_r += er_r_[i_same] * kGain[k] + er_l_[i_cross] * kGain[k] * 0.6f;
        }
        er_lp_l_ += lp_a * (acc_l - er_lp_l_);
        er_lp_r_ += lp_a * (acc_r - er_lp_r_);
        cl[i] = cl[i] * direct_gain + er_lp_l_ * er_amt * 1.1f;
        cr[i] = cr[i] * direct_gain + er_lp_r_ * er_amt * 1.1f;
      }
    }
    // ---- 输出级：补回入口衰减的响度 + 软限幅收峰 ----
    // 入口 −14 dB 是为了让解码矩阵/LFE/编码曲线/7 路 HRTF 叠加有足够余量，
    // 但会让整体小 14 dB。这里在 HRTF（含房间/反射）之后统一补回，并用软限幅
    // 把峰值压在 1.0 以内：稳态信号完全线性放大，只有峰被平滑压住（不产生硬削波谐波）。
    if (channels[0] != nullptr && channels[1] != nullptr) {
      for (int c = 0; c < 2; ++c) {
        float* p = channels[c];
        for (int i = 0; i < frames; ++i) {
          const float x = p[i] * kOutputMakeup;
          const float a = std::fabs(x);
          if (a > kSoftenPoint) {
            const float y = kSoftenPoint + (1.0f - kSoftenPoint) *
                                              std::tanh((a - kSoftenPoint) * kSoftenK);
            p[i] = (x < 0.0f) ? -y : y;
          } else {
            p[i] = x;
          }
        }
      }
    }

  }

 private:
  void DecodeFrames(const float* l, const float* r, int frames) {
    for (int i = 0; i < frames; ++i) {
      float out[vs::kChannelCount];
      decoder_.ProcessSample(l[i], r[i], out);
      for (int c = 0; c < vs::kChannelCount; ++c) {
        layout_[static_cast<std::size_t>(c) * max_frames_ + i] = out[c];
      }
    }
  }

  /** 逐声道 HRTF 累加到双耳，再与干声按 render_gain_ 交叉淡化 */
  void RenderFrames(float* l, float* r, int frames, float goal, float step, bool direct = false,
                    bool ambi = false) {
    // 逐声道 trim（dB → 线性）与静音；LFE 不做 HRTF，直接按增益混入双耳
    float gain[vs::kChannelCount];
    for (int c = 0; c < vs::kChannelCount; ++c) {
      // 参数表里 trim/mute 的排列是 L,C,R,SL,SR,CS,LFE，而声道顺序是 FL,FR,C,...
      // 直接 kParamTrimL + c 会错位（FR 拿到 C 的 trim、C 拿到 R 的），必须显式映射。
      static constexpr int kTrimId[vs::kChannelCount] = {
          vs::kParamTrimL, vs::kParamTrimR, vs::kParamTrimC,
          vs::kParamTrimSL, vs::kParamTrimSR, vs::kParamTrimCS, vs::kParamTrimLFE};
      static constexpr int kMuteId[vs::kChannelCount] = {
          vs::kParamMuteL, vs::kParamMuteR, vs::kParamMuteC,
          vs::kParamMuteSL, vs::kParamMuteSR, vs::kParamMuteCS, vs::kParamMuteLFE};
      const int trim_id = kTrimId[c];
      const int mute_id = kMuteId[c];
      gain[c] = param_[mute_id] > 0.5f ? 0.0f : std::pow(10.0f, param_[trim_id] / 20.0f);
    }

    const float height = param_[vs::kParamHeightStrength] > 0.0f
                             ? param_[vs::kParamHeightStrength] : 0.0f;
    static constexpr int kUpSrc[4] = {vs::kFL, vs::kFR, vs::kSL, vs::kSR};

    // Ambisonic 分支的常量（循环外算好，避免逐样本 pow）。
    // 「高度强度（上方包围）」与经典路径的高度重定向共用同一参数：
    // 0 = 只有水平面，1 = 上方 4 路声源最强。
    const float ambi_upper = param_[vs::kParamHeightStrength];
    const float rear_lin = std::pow(10.0f, param_[vs::kParamRearGain] / 20.0f);

    for (int i = 0; i < frames; ++i) {
      float wet_l = 0.0f;
      float wet_r = 0.0f;
      if (direct) {
        // 仅解码测试：**不过双耳渲染**，7 声道按经典下混矩阵直接进双耳，
        // 这样把某一路之外全部静音，听到的就是那一路上解码出来的原始内容。
        const float fl = layout_[static_cast<std::size_t>(vs::kFL) * max_frames_ + i] * gain[vs::kFL];
        const float fr = layout_[static_cast<std::size_t>(vs::kFR) * max_frames_ + i] * gain[vs::kFR];
        const float cc = layout_[static_cast<std::size_t>(vs::kC) * max_frames_ + i] * gain[vs::kC];
        const float sl = layout_[static_cast<std::size_t>(vs::kSL) * max_frames_ + i] * gain[vs::kSL];
        const float sr = layout_[static_cast<std::size_t>(vs::kSR) * max_frames_ + i] * gain[vs::kSR];
        const float cs = layout_[static_cast<std::size_t>(vs::kCS) * max_frames_ + i] * gain[vs::kCS];
        const float lfe = layout_[static_cast<std::size_t>(vs::kLFE) * max_frames_ + i] * gain[vs::kLFE];
        wet_l = fl + 0.7071068f * cc + 0.7071068f * sl + 0.7071068f * cs + lfe;
        wet_r = fr + 0.7071068f * cc + 0.7071068f * sr + 0.7071068f * cs + lfe;
      } else if (ambi) {
        // ambi 标志已按「当前解码器就绪」判定；具体走 HOA 还是 VBAP 由 render_decoder_ 决定
        // Ambisonic：10 声源 → HOA(9ch) → 12 虚拟扬声器（B 布局）→ 12 路 HRTF
        RenderAmbiSample(i, gain, ambi_upper, rear_lin, &wet_l, &wet_r);
      } else {
        for (int c = 0; c < vs::kChannelCount; ++c) {
          const float x = layout_[static_cast<std::size_t>(c) * max_frames_ + i] * gain[c];
          if (c == vs::kLFE) {
            wet_l += x;
            wet_r += x;
            continue;
          }
          float hl = 0.0f, hr = 0.0f;
          hrtf_ch_[static_cast<std::size_t>(c)].ProcessSample(x, &hl, &hr);
          wet_l += hl;
          wet_r += hr;
        }

        // 高度重定向：从 FL/FR/SL/SR 提取 3 kHz 以上，送到上方 4 路（仰角 +35°）
        if (height > 0.01f) {
          for (int k = 0; k < 4; ++k) {
            const int src = kUpSrc[k];
            const float x = layout_[static_cast<std::size_t>(src) * max_frames_ + i] * gain[src];
            const float hf = hf_hp_[static_cast<std::size_t>(k)].Process(x) * height * 0.5f;
            float ul = 0.0f, ur = 0.0f;
            hrtf_up_[static_cast<std::size_t>(k)].ProcessSample(hf, &ul, &ur);
            wet_l += ul;
            wet_r += ur;
          }
        }
      }

      // 交叉淡化：dry*(1−g) + wet*g（g 逐样本推进，开关无爆音）
      render_gain_ += (goal > render_gain_) ? step : -step;
      if (render_gain_ < 0.0f) render_gain_ = 0.0f;
      if (render_gain_ > 1.0f) render_gain_ = 1.0f;
      const float g = render_gain_;
      l[i] = l[i] * (1.0f - g) + wet_l * g;
      r[i] = r[i] * (1.0f - g) + wet_r * g;
    }
  }

  /**
   * Ambisonic 路径的单样本渲染。
   *
   * 10 路声源（方位固定，见 kAmbiSrcAz/El）→ 二阶球谐编码（9ch）→
   * 12 虚拟扬声器解码（B 布局，伪逆 + max-rE）→ 每扬声器独立 HRTF → 双耳。
   *
   * 前/后半球分流：后半球（135/180/225 + 后上 ±135）额外乘 rear_gain，
   * 与经典路径的 `rear_gain` 语义一致（否则后场会比前场还响）。
   * LFE 不进 HRTF，直接按增益混入双耳 —— 与经典路径保持一致。
   */
  void RenderAmbiSample(int i, const float gain[vs::kChannelCount], float upper_gain,
                        float rear_lin, float* wet_l, float* wet_r) {
    const auto idx = [this, i](int c) {
      return static_cast<std::size_t>(c) * static_cast<std::size_t>(max_frames_) +
             static_cast<std::size_t>(i);
    };

    float src[kAmbiSources];
    src[0] = layout_[idx(vs::kFL)] * gain[vs::kFL];
    src[1] = layout_[idx(vs::kFR)] * gain[vs::kFR];
    src[2] = layout_[idx(vs::kC)] * gain[vs::kC];
    // 侧/后与后中置沿用参考实现的 1.8 倍提升（矩阵下混会压低这些声道）
    src[3] = layout_[idx(vs::kSL)] * gain[vs::kSL] * 1.8f;
    src[4] = layout_[idx(vs::kSR)] * gain[vs::kSR] * 1.8f;
    src[5] = layout_[idx(vs::kCS)] * gain[vs::kCS] * 1.8f;

    // 上方 4 路：与「高度重定向」同源（FL/FR/SL/SR 的 >3 kHz）
    const float up = upper_gain * 0.5f;
    if (up > 0.001f) {
      for (int k = 0; k < 4; ++k) {
        const int c = kUpSrcCh[k];
        src[6 + k] = hf_hp_[static_cast<std::size_t>(k)].Process(layout_[idx(c)] * gain[c]) * up;
      }
    } else {
      for (int k = 0; k < 4; ++k) src[6 + k] = 0.0f;
    }

    float ambi[vs::HoaEncoder::kChannels];
    if (render_decoder_ < 2) {
      // 只有走 HOA 才需要球谐编码（VBAP 直接查矩阵，省 90 MAC/样本）
      for (int k = 0; k < vs::HoaEncoder::kChannels; ++k) ambi[k] = 0.0f;
      for (int s = 0; s < kAmbiSources; ++s) {
        const float g = src[s];
        if (g == 0.0f) continue;
        const float* cf = ambi_coeff_[s];
        for (int k = 0; k < vs::HoaEncoder::kChannels; ++k) ambi[k] += g * cf[k];
      }
    }

    float spk[vs::HoaDecoder::kSpeakers];
    if (render_decoder_ >= 2) {
      // VBAP：直接把 10 路声源按方位摆到 12 只扬声器（矩阵已在 Prepare/改扩散时算好）
      vbap_.Decode(src, spk);
    } else {
      hoa_.Decode(ambi, spk);
    }

    float wl = 0.0f;
    float wr = 0.0f;
    for (int j = 0; j < vs::HoaDecoder::kSpeakers; ++j) {
      float hl = 0.0f;
      float hr = 0.0f;
      hrtf_ambi_[static_cast<std::size_t>(j)].ProcessSample(spk[j], &hl, &hr);
      const bool rear = (j >= 3 && j <= 5) || j == 10 || j == 11;
      const float g = rear ? rear_lin : 1.0f;
      wl += hl * g;
      wr += hr * g;
    }

    const float lfe = layout_[idx(vs::kLFE)] * gain[vs::kLFE];
    *wet_l = wl + lfe;
    *wet_r = wr + lfe;
  }

  // ---- Ambisonic：10 路声源方位（水平 6 + 上方 4，与 B 布局的上方扬声器同向）----
  static constexpr int kAmbiSources = 10;
  static constexpr float kAmbiSrcAz[kAmbiSources] = {
      -30.0f, 30.0f, 0.0f, -110.0f, 110.0f, 180.0f, -45.0f, 45.0f, -135.0f, 135.0f,
  };
  static constexpr float kAmbiSrcEl[kAmbiSources] = {
      0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 35.0f, 35.0f, 35.0f, 35.0f,
  };
  /** 上方 4 路所取自的声道（与高度重定向一致） */
  static constexpr int kUpSrcCh[4] = {vs::kFL, vs::kFR, vs::kSL, vs::kSR};

  bool bypass_ = true;   // 默认旁路，接入图后由 UI 打开
  float render_gain_ = 0.0f;
  int sample_rate_ = 48000;
  int max_frames_ = 512;

  std::array<float, vs::kParamCount> param_{};
  std::array<float, vs::kParamCount> target_{};
  std::vector<float> layout_;  // 7 声道 × max_frames（planar 展开存储）

  vs::StereoSpatialDecoder decoder_;
  vs::LowPass2 width_lp_;   // 三段展宽的 200 Hz 低通（只作用于 S 支路）
  vs::Biquad width_hp_;     // 三段展宽的 4 kHz 高通（只作用于 S 支路）
  vs::StereoSpatialDecoder decoder2_;
  vs::LtRtRoundTrip round_trip_;
  vs::PostStage post_;
  dsp::ConvolverNode room_;
  // 早期反射：4 个抽头（8/13/21/34 ms）+ 每耳一路交叉，距离越远反射越多
  static constexpr int kErSize = 2400;   // 50 ms @48k
  float er_l_[kErSize]{};
  float er_r_[kErSize]{};
  int er_pos_ = 0;
  // 反射路径的高频吸收（距离越远越暗）与直达声衰减
  float er_lp_l_ = 0.0f;
  float er_lp_r_ = 0.0f;
  std::array<float*, vs::kChannelCount> ch_ptr_{};
  std::array<vs::HrtfEngine, vs::kChannelCount> hrtf_ch_{};
  // 上方 4 个虚拟声源（±30° / ±110°，仰角 +35°）与其 3 kHz 高通
  std::array<vs::HrtfEngine, 4> hrtf_up_{};
  std::array<vs::Biquad, 4> hf_hp_{};

  /**
   * 把 HRTF 模型下发到全部渲染实例。
   *
   * 为什么放在 Process 里而不是 SetParam：SetParam 只写 target_，模型编号要等
   * 参数平滑到位后才是稳定值；这里用「与上次生效值比较」的方式，只在真正变化时
   * 触发一次重装载（每实例 O(557) 的峰值归一化 + ITD/ILD 测量，共 23 实例）。
   */
  void ApplyHrtfModel(int model) {
    for (auto& h : hrtf_ch_) h.SetModel(model);
    for (auto& h : hrtf_up_) h.SetModel(model);
    for (auto& h : hrtf_ambi_) h.SetModel(model);
    hrtf_model_.SetModel(model);
  }

  // ---- Ambisonic / VBAP 路径（共用 12 只虚拟扬声器与 12 路 HRTF）----
  std::array<vs::HrtfEngine, vs::HoaDecoder::kSpeakers> hrtf_ambi_{};
  vs::VbapDecoder vbap_;
  bool vbap_ready_ = false;
  bool vbap_dirty_ = true;
  /** 当前渲染用的解码方式：0 经典 / 1 HOA / 2 VBAP */
  int render_decoder_ = 0;
  vs::HoaDecoder hoa_;
  float ambi_coeff_[kAmbiSources][vs::HoaEncoder::kChannels]{};
  bool ambi_ready_ = false;
  bool ambi_dirty_ = true;
  float ambi_focus_used_ = -1.0f;
  vs::HrtfEngine hrtf_model_;
  /** 当前已生效的 HRTF 模型（-1 = 未下发） */
  int hrtf_model_applied_ = -1;
  vs::LocalizationSelfTest::Report last_report_{};
};

}  // namespace sonicraft::dsp
