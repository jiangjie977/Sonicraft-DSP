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
#include <string_view>

#include "sonicraft/dsp/dsp_node.hpp"

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

  kParamCount = 52,
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
    Reset();
  }

  void Reset() override {
    for (int i = 0; i < kParamCount; ++i) param_[i] = kDefault[i];
  }

  void Process(float* const* channels, int frames) override {
    (void)channels;
    (void)frames;
    // S0：直通。S1 起在此处依次接入 upmix → HRTF → EQ。
    // 注意：**必须保持逐样本恒等**，否则 harness 的透传断言会失败。
    if (bypass_) return;
    const bool want = param_[kParamEnabled] > 0.5f;
    if (!want) return;
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
  int sr_ = 48000;
  int max_frames_ = 512;
  bool bypass_ = true;   // 默认旁路：与矩阵环绕互斥，由 UI/prefs 控制
  float param_[kParamCount]{};
};

}  // namespace sonicraft::dsp::s71

#endif  // SONICRAFT_DSP_S71_NODE_HPP
