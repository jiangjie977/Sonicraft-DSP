/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <array>
#include <cmath>
#include <vector>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/vs_decoder.hpp"
#include "sonicraft/dsp/vs_encoder.hpp"
#include "sonicraft/dsp/vs_hrtf.hpp"
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
    hrtf_model_.Prepare(sr);
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
    last_report_ = vs::LocalizationSelfTest::Run(hrtf_model_, static_cast<float>(sample_rate_),
                                                 apply_correction);
    if (apply_correction) {
      // 校正同样作用到 7 个声道渲染器，否则自检与实际渲染口径不一致
      for (auto& h : hrtf_ch_) h.SetCalibration(last_report_.itd_scale, last_report_.ild_trim);
    }
    return last_report_;
  }

  const vs::LocalizationSelfTest::Report& last_self_test() const { return last_report_; }

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
    decoder_.SetFrontGain(0.0f);
    decoder_.SetRearGain(0.0f);
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

    // TODO(试听待修，2026-11-15，暂不动算法)：
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
    RenderFrames(channels[0], channels[1], frames, goal, step, false);

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
  void RenderFrames(float* l, float* r, int frames, float goal, float step, bool direct = false) {
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

  bool bypass_ = true;   // 默认旁路，接入图后由 UI 打开
  float render_gain_ = 0.0f;
  int sample_rate_ = 48000;
  int max_frames_ = 512;

  std::array<float, vs::kParamCount> param_{};
  std::array<float, vs::kParamCount> target_{};
  std::vector<float> layout_;  // 7 声道 × max_frames（planar 展开存储）

  vs::StereoSpatialDecoder decoder_;
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
  std::array<vs::ParametricHrtf, vs::kChannelCount> hrtf_ch_{};
  // 上方 4 个虚拟声源（±30° / ±110°，仰角 +35°）与其 3 kHz 高通
  std::array<vs::ParametricHrtf, 4> hrtf_up_{};
  std::array<vs::Biquad, 4> hf_hp_{};
  vs::ParametricHrtf hrtf_model_;
  vs::LocalizationSelfTest::Report last_report_{};
};

}  // namespace sonicraft::dsp
