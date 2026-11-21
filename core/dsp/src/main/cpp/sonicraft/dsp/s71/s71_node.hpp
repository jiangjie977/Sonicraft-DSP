// Surround71 环绕节点（独立于矩阵环绕，两者互斥）
//
// 来源说明：本节点的算法结构参考自开源项目 **Surround71**（7.1 → 双耳虚拟环绕），
// 该项目未声明许可证（无 LICENSE 文件、源文件无版权头），此处仅作学习/自用移植，
// 并在文件头保留来源说明。HRTF 数据来自 **MIT KEMAR**（MIT Media Lab 学术数据集）。
// 若将来对外分发，需先取得原作者与数据集方的许可。
//
// 与矩阵环绕（virtual_surround.hpp）的关系：
//   - 两者都是「立体声/多声道 → 双耳」，功能重叠，**同时开启会叠加成乱声**；
//   - UI 层保证互斥（开一个自动关另一个）；
//   - 本节点完全独立，删除本目录即可整体移除，不影响矩阵环绕。
//
// 处理链（照搬 Surround71 的 SurroundPipeline）：
//   源(1/2/6/8ch) → 7.1 上混 → [耳机: HRTF 双耳化 | 内部: 每声道增益/延迟]
//     → 6 段参数均衡器 → 声道映射 → 输出
//
// 批次进度：
//   S0 ✅ 骨架（本文件）：参数表 + 节点外壳，Process 目前为直通（恒等）
//   S1 ⬜ 上混（立体声 → 7.1）
//   S2 ⬜ 双耳化（MIT KEMAR HRIR 卷积 + 归一化 + 低音管理）
//   S3 ⬜ 每声道增益/延迟 + 电平组
//   S4 ⬜ 6 段参数均衡器
//   S5 ⬜ 外部化（早期反射）+ M/S 展宽
//   S6 ⬜ 频谱采集（仅显示）
//   S7 ⬜ 自测对齐

#ifndef SONICRAFT_DSP_S71_NODE_HPP
#define SONICRAFT_DSP_S71_NODE_HPP

#include <algorithm>
#include <cmath>
#include <string_view>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/convolver.hpp"
#include "sonicraft/dsp/s71/s71_hrir.hpp"   // MIT KEMAR HRIR（8 路方位）    // 复用 ConvolverNode（内置 5 档 RIR）
#include "sonicraft/dsp/vs_decoder.hpp"   // 复用 LowPass2 / Biquad

namespace sonicraft::dsp::s71 {

/** 8 声道布局（Windows WAVE_FORMAT_EXTENSIBLE 顺序，与 Surround71 一致） */
enum Ch : int { kFL = 0, kFR = 1, kFC = 2, kLFE = 3, kBL = 4, kBR = 5, kSL = 6, kSR = 7 };
constexpr int kChannelCount = 8;

/** 虚拟音箱方位角（0 = 正前，正 = 右侧，单位度） */
constexpr float kAzimuthDeg[kChannelCount] = {-30.0f, 30.0f, 0.0f, 0.0f, -110.0f, 110.0f, -90.0f, 90.0f};

/** 低音通道：不做 HRTF，等量送左右耳 */
constexpr bool kIsLfe[kChannelCount] = {false, false, false, true, false, false, false, false};

/**
 * 参数表（顺序固定，与 Kotlin 侧 AudioEngine.S71_PARAM_* 一一对应）。
 * 编号在本节点的 SetParam 命名空间内生效（引擎按节点分发，不需要全局唯一）。
 */
enum Param : int {
  // ---- 全局 ----
  kParamEnabled = 0,        // 0/1 本节点总开关（与 bypass 叠加判定）
  kParamHeadphoneMode = 1,  // 1 = HRTF 双耳化（默认）；0 = 每声道增益/延迟直通（内部调试用）
  kParamUpmixStrength = 2,  // 0~1   上混强度（保留 FL/FR 主体，其余按比例派生）
  kParamExternalize = 3,    // 0~1   外部化（早期反射强度）
  kParamWidth = 4,          // 0~2   M/S 展宽（1 = 原样）
  kParamOutputGainDb = 5,   // -12~+12 输出增益

  // ---- 电平组 ----
  kParamBassBoostDb = 6,      // 0~+12  LFE 通道电平
  kParamBassManagement = 7,   // 0~1    <110 Hz 等量补到双耳的强度
  kParamSurroundLevelDb = 8,  // -12~+12  BL/BR/SL/SR 电平
  kParamCenterLevelDb = 9,    // -12~+12  FC 电平

  // ---- 每声道增益（8 路，dB）----
  kParamSpeakerGain0 = 10,  // 10..17
  // ---- 每声道延迟（8 路，ms）----
  kParamSpeakerDelay0 = 18,  // 18..25

  // ---- 6 段参数均衡器 ----
  kParamEqEnabled = 26,   // 0/1
  kParamEqPreampDb = 27,  // -20~+20
  kParamEqFreq0 = 28,     // 28..33  各段中心频率 Hz
  kParamEqGain0 = 34,     // 34..39  各段增益 dB
  kParamEqType0 = 40,     // 40..45  0=Peak 1=LowShelf 2=HighShelf 3=HighPass 4=LowPass
  kParamEqBandOn0 = 46,   // 46..51  各段开关 0/1

  // ---- 低音增强（与矩阵环绕共用同一组用户参数，见 VM 的 setVsBassEnh*）----
  kParamBassEnhancer = 52,      // 0~1  谐波强度
  kParamBassEnhancerMode = 53,  // 0=取自 LFE 1=取自前置

  // ---- 与矩阵环绕共用的空间参数（同一组 UI，各自节点内实现）----
  kParamRoomSize = 54,    // 0=关 1=小房间 2=客厅 3=音乐厅 4=大教堂
  kParamRoomWet = 55,     // 0~1 混响量
  kParamCenterMode = 56,  // 0=关闭 1=直通 2=混入前置
  kParamDialog = 57,      // -6~+6 dB 对话清晰度

  kParamCount = 58,
};

/** 默认值（照搬 Surround71 的 SurroundSettings 默认） */
constexpr float kDefault[kParamCount] = {
    0.0f,   // enabled（默认关，与矩阵环绕互斥）
    1.0f,   // headphone_mode
    0.85f,  // upmix_strength
    0.35f,  // externalize
    1.0f,   // width
    0.0f,   // output_gain_db

    2.0f,   // bass_boost_db
    0.25f,  // bass_management
    0.0f,   // surround_level_db
    0.0f,   // center_level_db

    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,  // speaker gain 0..7
    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,  // speaker delay 0..7

    0.0f,   // eq_enabled
    0.0f,   // eq_preamp_db
    80.0f, 300.0f, 1000.0f, 3000.0f, 8000.0f, 12000.0f,  // eq freq 0..5
    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,                   // eq gain 0..5
    0.0f, 1.0f, 0.0f, 2.0f, 0.0f, 0.0f,                   // eq type 0..5（LS/Peak/HS）
    1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,                   // eq band on 0..5

    0.0f,   // bass_enhancer（默认关）
    0.0f,   // bass_enhancer_mode（0 = 取自 LFE）

    0.0f,   // room_size（关）
    0.3f,   // room_wet
    1.0f,   // center_mode（直通）
    0.0f,   // dialog
};

/**
 * S0 骨架节点：只保存参数，Process 为**直通**（输出逐样本等于输入）。
 * 这样接入引擎后可以先验证「开关时音质零变化」，再逐批次填算法。
 */
class Surround71Node : public DspNode {
 public:
  std::string_view id() const override { return "s71_surround"; }

  void Prepare(int sample_rate, int max_frames) override {
    sr_ = sample_rate > 0 ? sample_rate : 48000;
    max_frames_ = max_frames > 0 ? max_frames : 512;
    const float fs = static_cast<float>(sr_);
    side_del_l_ = static_cast<int>(0.018f * fs);
    side_del_r_ = static_cast<int>(0.023f * fs);
    back_del_l_ = static_cast<int>(0.031f * fs);
    back_del_r_ = static_cast<int>(0.037f * fs);
    side_lp_l_.Set(7500.0f, fs);
    side_lp_r_.Set(7500.0f, fs);
    back_lp_l_.Set(5200.0f, fs);
    back_lp_r_.Set(5200.0f, fs);
    lfe_lp_.Set(110.0f, fs);
    bm_lp_.Set(110.0f, fs);
    // 频响校正：低频 +4 dB（补 HRTF 造成的低频塌陷）、5 kHz 削 −4.5 dB（削 KEMAR 耳廓峰）
    for (Rbj* f : {&fix_low_l_, &fix_low_r_}) f->SetLowShelf(fs, 200.0f, 4.0f);
    for (Rbj* f : {&fix_peak_l_, &fix_peak_r_}) f->SetPeaking(fs, 5000.0f, 1.5f, -4.5f);
    room_.set_channel_count(2);
    room_.Prepare(sr_, max_frames_);
    room_.SetBypass(false);
    Reset();
  }

  void Reset() override {
    for (int i = 0; i < kParamCount; ++i) param_[i] = kDefault[i];
    for (int i = 0; i < kDelaySize; ++i) dL_[i] = dR_[i] = 0.0f;
    pos_ = 0;
    side_lp_l_.Reset();
    side_lp_r_.Reset();
    back_lp_l_.Reset();
    back_lp_r_.Reset();
    lfe_lp_.Reset();
    bm_lp_.Reset();
    harm_lp_ = 0.0f;
    room_.Reset();
    for (int i = 0; i < kChannelCount; ++i) hrir_conv_[i].Reset();
    fix_low_l_.Reset();
    fix_low_r_.Reset();
    fix_peak_l_.Reset();
    fix_peak_r_.Reset();
  }

  void Process(float* const* channels, int frames) override {
    if (bypass_) return;
    const bool want = param_[kParamEnabled] > 0.5f;
    if (!want) return;
    if (channels == nullptr || channels[0] == nullptr || channels[1] == nullptr) return;

    // ---- S1：立体声 → 7.1 上混，再简单折叠回双耳（S2 换成 HRTF）----
    const float s = ClampF(param_[kParamUpmixStrength], 0.0f, 1.0f);
    const float norm = 1.0f / (1.0f + 0.45f * s);   // 照搬 Surround71 的归一化
    // 折叠系数：8 路相加后回到原电平（实测标定，见 S1 探针）
    // 注意：S2 起用 HRTF 卷积替代了原来的「朴素折叠」，fold 已不再参与输出，
    // 保留变量只为兼容旧注释与后续可能的旁路路径。
    (void)(1.0f - 0.35f * s);

    float* pl = channels[0];
    float* pr = channels[1];
    for (int i = 0; i < frames; ++i) {
      const float l = pl[i];
      const float r = pr[i];
      float ch8[kChannelCount];
      UpmixOne(l, r, s, norm, ch8);

      // ---- 中置模式（0=关闭 / 1=直通 / 2=混入前置）----
      // 与矩阵环绕语义一致：关闭时 FC 静音；混入前置时按 −3 dB 并入 FL/FR 后 FC 静音。
      {
        const int cm = static_cast<int>(param_[kParamCenterMode] + 0.5f);
        if (cm == 0) {
          ch8[kFC] = 0.0f;
        } else if (cm == 2) {
          const float half = ch8[kFC] * 0.7071f;
          ch8[kFL] += half;
          ch8[kFR] += half;
          ch8[kFC] = 0.0f;
        }
        // 对话清晰度：FC 增益（正值提人声，负值衰减）
        const float dlg = param_[kParamDialog];
        if (cm != 0 && (dlg > 0.05f || dlg < -0.05f)) {
          ch8[kFC] *= std::pow(10.0f, dlg * 0.05f);
        }
      }

      // ---- 低音增强（与矩阵环绕同一套算法：120 Hz 一阶低通 → 奇对称谐波 → 叠加）----
      {
        const float amount = ClampF(param_[kParamBassEnhancer], 0.0f, 1.0f);
        if (amount > 0.001f) {
          const bool from_lfe = param_[kParamBassEnhancerMode] < 0.5f;
          const float x = from_lfe ? ch8[kLFE] : (ch8[kFL] + ch8[kFR]) * 0.5f;
          harm_lp_ += 0.0156f * (x - harm_lp_);
          const float h = Harmonics(harm_lp_) * amount;
          if (from_lfe) {
            ch8[kLFE] += h;
          } else {
            ch8[kFL] += h;
            ch8[kFR] += h;
          }
        }
      }

      // 8 路声道 Trim（与「公共接口 · 声道 Trim」共享同一组数值，VM 侧做映射下发）
      for (int c = 0; c < kChannelCount; ++c) {
        const float g_db = param_[kParamSpeakerGain0 + c];
        if (g_db < -59.0f) {
          ch8[c] = 0.0f;   // 静音（-60 dB 约定为 mute）
        } else if (g_db > 0.001f || g_db < -0.001f) {
          ch8[c] *= std::pow(10.0f, g_db * 0.05f);
        }
      }

      // ---- LFE 电平（Surround71 的 BassBoostDb，默认 +2 dB）----
      {
        const float boost_db = param_[kParamBassBoostDb];
        if (boost_db > 0.01f || boost_db < -0.01f) {
          ch8[kLFE] *= std::pow(10.0f, boost_db * 0.05f);
        }
      }

      // ---- 低音管理（Surround71 的 BassManagement）----
      // 把 LFE 的 110 Hz 以下能量**等量**补到左右耳，低频更结实、不发散。
      // 用独立滤波器（lfe_lp_ 已在生成 LFE 时用过，复用会改变其状态）。
      const float bm = ClampF(param_[kParamBassManagement], 0.0f, 1.0f);
      float bm_mono = 0.0f;
      if (bm > 0.001f) {
        bm_mono = bm_lp_.Process(ch8[kLFE]) * bm;
      }

      // ---- HRTF 双耳化：8 路各按方位做 HRIR 卷积，再累加到双耳 ----
      // LFE 不做 HRTF（低音无方向性），等量送左右耳。
      float lo = 0.0f;
      float ro = 0.0f;
      for (int c = 0; c < kChannelCount; ++c) {
        const HrirPair* hp = HrirForChannel(c);
        if (hp == nullptr) {
          lo += ch8[c] * 0.5f;
          ro += ch8[c] * 0.5f;
        } else {
          lo += hrir_conv_[c].Process(ch8[c], hp->l);
          ro += hrir_conv_[c].Process(ch8[c], hp->r);
        }
      }
      // HRIR 已按「8 路最大能量归一化」，叠加后仍需一个总缩放（探针标定）
      // HRIR 卷积会分散能量（139 抽头、归一化后峰值仅 0.7），7 路叠加后净增益约 -6.6 dB。
      // 实测输入 0.5 → RMS 0.165（正弦输入 RMS 0.354），比值 0.466。乘 2.14 补回 ≈ 0 dB。
      const float kHrirGain = 0.90f;
      lo = lo * kHrirGain + bm_mono;
      ro = ro * kHrirGain + bm_mono;
      pl[i] = lo;
      pr[i] = ro;
    }

    // ---- 固定频响校正（校正 HRIR 自身的染色）----
    // 实测（居中正弦扫频，1 kHz 为 0 dB）：4 kHz +4.8 dB、6 kHz +3.3 dB（刺耳区），
    // 60 Hz −22 dB、120 Hz −10.9 dB（低频塌陷）。这两处都是 MIT KEMAR 的固有特性。
    // 因为 HRIR 数据集固定，补偿也是固定的 —— 不需要让用户手调 6 段 EQ。
    // 位置：HRTF 之后、房间之前（房间湿声是额外空间感，不该再被"校正成平"）。
    if (freq_fix_on_) {
      for (int i = 0; i < frames; ++i) {
        pl[i] = fix_low_l_.Process(pl[i]);
        pl[i] = fix_peak_l_.Process(pl[i]);
        pr[i] = fix_low_r_.Process(pr[i]);
        pr[i] = fix_peak_r_.Process(pr[i]);
      }
    }

    // ---- 环境声场（房间 RIR 卷积）----
    // 复用 ConvolverNode：它内置 Dry/小房间/客厅/音乐厅/大教堂 五档 RIR，
    // 与矩阵环绕用的是同一个实现，因此听感一致。
    // 放在折叠之后 —— 房间作用于最终双耳信号（与矩阵环绕的顺序一致）。
    if (param_[kParamRoomSize] > 0.5f) {
      room_.SetParam(dsp::ConvolverNode::kParamPreset, param_[kParamRoomSize]);
      room_.SetParam(dsp::ConvolverNode::kParamWet, param_[kParamRoomWet]);
      room_.SetParam(dsp::ConvolverNode::kParamDry, 1.0f);
      room_.Process(channels, frames);
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id >= 0 && param_id < kParamCount) param_[param_id] = value;
  }

  float GetParam(int param_id) const {
    return (param_id >= 0 && param_id < kParamCount) ? param_[param_id] : 0.0f;
  }

 private:
  static float ClampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

  /**
   * 谐波生成（与 vs_post.hpp 的 Harmonics 同源）：奇对称平方映射，零输入→零输出，
   * 不产生直流，只生成二次谐波 —— 补足小喇叭/耳机对低频的体感。
   */
  static float Harmonics(float x) {
    const float sign = x < 0.0f ? -1.0f : 1.0f;
    float a = x < 0.0f ? -x : x;
    if (a > 1.0f) a = 1.0f;
    return sign * a * a * 1.5f;
  }

  /**
   * RBJ 双二阶（本文件只用到 Peaking 与 LowShelf 两种）。
   *
   * 为什么在节点内部做固定校正，而不是复用全局 EQ 节点：
   *   全局 EQ（节点 2/3）位于本节点**之前**，而 4~6 kHz 的峰是 **HRIR 卷积产生**的，
   *   在全局 EQ 之后才出现 —— 用全局 EQ 去挖，会导致「关掉 Surround71 时声音发闷」。
   *   频响染色发生在哪一级，就得在哪一级之后校正。
   */
  struct Rbj {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;

    inline float Process(float x) {
      const float y = b0 * x + z1;
      z1 = b1 * x - a1 * y + z2;
      z2 = b2 * x - a2 * y;
      return y;
    }
    void Reset() { z1 = z2 = 0.0f; }

    /** 峰值型（用于削 KEMAR 的耳廓共振峰） */
    void SetPeaking(float sr, float fc, float q, float gain_db) {
      const float A = std::pow(10.0f, gain_db * 0.025f);
      const float w0 = 2.0f * 3.14159265358979323846f * fc / sr;
      const float cw = std::cos(w0);
      const float alpha = std::sin(w0) / (2.0f * q);
      const float a0 = 1.0f + alpha / A;
      b0 = (1.0f + alpha * A) / a0;
      b1 = (-2.0f * cw) / a0;
      b2 = (1.0f - alpha * A) / a0;
      a1 = (-2.0f * cw) / a0;
      a2 = (1.0f - alpha / A) / a0;
    }

    /** 低搁架（用于补回 HRTF 造成的低频衰减） */
    void SetLowShelf(float sr, float fc, float gain_db, float shelf_slope = 1.0f) {
      const float A = std::pow(10.0f, gain_db * 0.025f);
      const float w0 = 2.0f * 3.14159265358979323846f * fc / sr;
      const float cw = std::cos(w0);
      const float sw = std::sin(w0);
      const float alpha = sw * 0.5f * std::sqrt((A + 1.0f / A) * (1.0f / shelf_slope - 1.0f) + 2.0f);
      const float two_sqrtA_alpha = 2.0f * std::sqrt(A) * alpha;
      const float a0 = (A + 1.0f) + (A - 1.0f) * cw + two_sqrtA_alpha;
      b0 = (A * ((A + 1.0f) - (A - 1.0f) * cw + two_sqrtA_alpha)) / a0;
      b1 = (2.0f * A * ((A - 1.0f) - (A + 1.0f) * cw)) / a0;
      b2 = (A * ((A + 1.0f) - (A - 1.0f) * cw - two_sqrtA_alpha)) / a0;
      a1 = (-2.0f * ((A - 1.0f) + (A + 1.0f) * cw)) / a0;
      a2 = ((A + 1.0f) + (A - 1.0f) * cw - two_sqrtA_alpha) / a0;
    }
  };

  /**
   * 直接时域卷积（139 抽头）。用 2 倍长度历史缓冲避免取模：
   * 写入 pos 与 pos+kHrirTaps 两处，回读 [pos+kHrirTaps-t] 永远落在合法区间。
   * 成本：每路每样本 139 MAC，7 路 ≈ 973 MAC/样本 ≈ 47 M MAC/s（手机轻松）。
   */
  struct HrirConv {
    float hist[2 * kHrirTaps]{};
    int pos = 0;
    void Reset() {
      for (int i = 0; i < 2 * kHrirTaps; ++i) hist[i] = 0.0f;
      pos = 0;
    }
    inline float Process(float x, const float* h) {
      hist[pos] = x;
      hist[pos + kHrirTaps] = x;
      float acc = 0.0f;
      const int base = pos + kHrirTaps;
      for (int t = 0; t < kHrirTaps; ++t) acc += hist[base - t] * h[t];
      if (++pos >= kHrirTaps) pos = 0;
      return acc;
    }
  };

  /** 40 ms 延迟线容量（48 kHz 下 4096 = 85 ms，留足余量；2 的幂便于取模） */
  static constexpr int kDelaySize = 4096;
  static constexpr int kDelayMask = kDelaySize - 1;

  /**
   * 单样本上混（照搬 Surround71 Upmixer.Read 的逐样本算法）：
   *   - 侧环绕：18 ms / 23 ms 不对称延迟 + 7.5 kHz 低通
   *   - 后环绕：31 ms / 37 ms 不对称延迟 + 5.2 kHz 低通
   *   - LFE：中置信号过 110 Hz 低通
   *   左右延迟不对称是关键 —— 它让两侧内容天然去相关，避免后方单声道化。
   */
  void UpmixOne(float l, float r, float s, float norm, float* out8) {
    dL_[pos_] = l;
    dR_[pos_] = r;
    const float sideL = dL_[(pos_ - side_del_l_) & kDelayMask];
    const float sideR = dR_[(pos_ - side_del_r_) & kDelayMask];
    const float backL = dL_[(pos_ - back_del_l_) & kDelayMask];
    const float backR = dR_[(pos_ - back_del_r_) & kDelayMask];
    pos_ = (pos_ + 1) & kDelayMask;

    const float mid = (l + r) * 0.5f;
    out8[kFC] = mid * 0.55f * s;
    out8[kFL] = l * (1.0f - 0.25f * s);
    out8[kFR] = r * (1.0f - 0.25f * s);
    out8[kSL] = side_lp_l_.Process(sideL) * 0.52f * s;
    out8[kSR] = side_lp_r_.Process(sideR) * 0.52f * s;
    out8[kBL] = back_lp_l_.Process(backL) * 0.44f * s;
    out8[kBR] = back_lp_r_.Process(backR) * 0.44f * s;
    out8[kLFE] = lfe_lp_.Process(mid) * 0.60f * s;

    for (int i = 0; i < kChannelCount; ++i) out8[i] *= norm;
  }

  int sr_ = 48000;
  int max_frames_ = 512;
  bool bypass_ = true;   // 默认旁路：与矩阵环绕互斥，由 UI/prefs 控制
  float param_[kParamCount]{};

  // ---- S1 上混状态 ----
  float dL_[kDelaySize]{};
  float dR_[kDelaySize]{};
  int pos_ = 0;
  int side_del_l_ = 0, side_del_r_ = 0, back_del_l_ = 0, back_del_r_ = 0;
  vs::LowPass2 side_lp_l_, side_lp_r_, back_lp_l_, back_lp_r_, lfe_lp_;
  vs::LowPass2 bm_lp_;   // 低音管理的独立低通（避免污染 LFE 生成用的 lfe_lp_）
  float harm_lp_ = 0.0f;  // 低音增强的 120 Hz 近似一阶低通状态
  dsp::ConvolverNode room_;   // 环境声场（复用内置五档 RIR）
  HrirConv hrir_conv_[kChannelCount];   // 8 路 HRTF 卷积状态
  // 固定频响校正（左右各两级：低搁架 + 峰值）
  Rbj fix_low_l_, fix_low_r_, fix_peak_l_, fix_peak_r_;
  bool freq_fix_on_ = true;
  static constexpr float kFoldGain = 0.70f;   // 折叠系数（S1 标定，探针确认）
};

}  // namespace sonicraft::dsp::s71

#endif  // SONICRAFT_DSP_S71_NODE_HPP
