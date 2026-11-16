/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cstdint>

namespace sonicraft::dsp::vs {

/**
 * 虚拟环绕（虚拟环绕声渲染）参数表。
 *
 * 设计约束：
 *  - ID 一经发布不可复用（与 Kotlin 侧 VS_PARAM_* 一一对应，跨语言契约）；
 *  - 全部为标量 float，靠节点内平滑斜坡避免参数突变爆音；
 *  - 枚举型参数按整数值下发（后端按 +0.5 取整）。
 */
enum Param : int {
  // ---- 总控 ----
  kParamEnabled = 0,        // 0/1 旁路开关（节点内部做交叉淡化）
  kParamMode = 1,           // 0=音乐 1=电影（决定 steering 上限与平滑系数）
  kParamSurroundGain = 2,   // dB，环绕声道总增益
  kParamCenterGain = 3,     // dB，中置总增益
  kParamRearGain = 4,       // dB，后中置增益
  kParamFrontGain = 5,      // dB，前场补偿（空间化后常需 +1~3dB）
  kParamDistance = 6,       // 0~1，声源距离（影响早期反射比例）

  // ---- 编解码往返（0 = 完全直通，1 = 完全经过）----
  kParamEncodeStrength = 7,  // 0~1

  // ---- 中置处理 ----
  kParamCenterMode = 8,     // 0=关闭 1=直通 2=混合到前置（-3dB）
  kParamDialog = 9,         // -6~+6 dB，人声清晰度
  kParamDialogEnabled = 10, // 0/1

  // ---- 低音谐波 ----
  kParamBassEnhancer = 11,      // 0~1 强度
  kParamBassEnhancerMode = 12,  // 0=LFE 1=Front

  // ---- 后中置 ----
  kParamRearCenterEnabled = 13, // 0/1
  kParamRearCenterDelayMs = 14, // 0~15 ms（实现上限 255 样本）

  // ---- HRTF ----
  kParamHrtfModel = 15,      // 0=参数化 1..N=用户导入的数据集
  kParamHrtfMode = 16,       // 0=经典 HRTF 1=Ambisonic
  kParamHeightStrength = 17, // 0~1 高度重定向强度（>3kHz）
  kParamEarlyReflection = 18,// 0~1 早期反射量
  kParamAmbisonicMode = 19,  // 0/1
  kParamStereoExpand = 20,   // 0~1 立体声展宽

  // ---- 头部追踪 ----
  kParamHeadTrackEnabled = 21, // 0/1
  kParamHeadYawDeg = 22,       // -180~180

  // ---- 房间（RIR）----
  kParamRoomSize = 23, // 0=关闭 1=小 2=客厅 3=音乐厅 4=大教堂
  kParamRoomWetDry = 24, // 0~1

  // ---- 逐声道 trim（dB）与静音 ----
  kParamTrimL = 25,
  kParamTrimC = 26,
  kParamTrimR = 27,
  kParamTrimSL = 28,
  kParamTrimSR = 29,
  kParamTrimCS = 30,
  kParamTrimLFE = 31,
  kParamMuteL = 32,
  kParamMuteC = 33,
  kParamMuteR = 34,
  kParamMuteSL = 35,
  kParamMuteSR = 36,
  kParamMuteCS = 37,
  kParamMuteLFE = 38,

  /**
   * 仅解码测试：解码 → 声道 trim → 双耳渲染，其余环节全部旁通
   * （Lt/Rt 往返、后处理、房间卷积都不参与），用于逐个确认 7 声道是否正常。
   */
  kParamDebugRaw = 39,

  /** BS.775 等响：把环绕声道矩阵系数补到与前置同响（补偿约 +11 dB） */
  kParamBs775 = 40,
  kParamTransientProtect = 41,   // 0/1 环绕瞬态保护（瞬态段抑制倾斜与去相关）

  kParamCount = 42,
};

/** 7 声道内部顺序：FL / FR / C / SL / SR / CS / LFE */
enum Ch : int { kFL = 0, kFR = 1, kC = 2, kSL = 3, kSR = 4, kCS = 5, kLFE = 6, kChannelCount = 7 };

/** 各声道标称方位角（度，0=正前，顺时针为正） */
constexpr float kAzimuthDeg[kChannelCount] = {-30.0f, 30.0f, 0.0f, -110.0f, 110.0f, 180.0f, 0.0f};

/** 参数默认值（索引即 Param 值） */
constexpr float kDefault[kParamCount] = {
    /* enabled            */ 0.0f,
    /* mode               */ 0.0f,
    /* surround_gain      */ 0.0f,
    /* center_gain        */ 0.0f,
    /* rear_gain          */ -6.0f,
    /* front_gain         */ 0.0f,
    /* distance           */ 0.5f,
    /* encode_strength    */ 0.5f,
    /* center_mode        */ 1.0f,
    /* dialog             */ 0.0f,
    /* dialog_enabled     */ 1.0f,
    /* bass_enhancer      */ 0.0f,
    /* bass_mode          */ 0.0f,
    /* rear_center        */ 0.0f,
    /* rear_delay_ms      */ 5.0f,
    /* hrtf_model         */ 0.0f,
    /* hrtf_mode          */ 0.0f,
    /* height_strength    */ 0.5f,
    /* early_reflection   */ 0.3f,
    /* ambisonic          */ 0.0f,
    /* stereo_expand      */ 0.3f,
    /* head_track         */ 0.0f,
    /* head_yaw           */ 0.0f,
    /* room_size          */ 0.0f,
    /* room_wet_dry       */ 0.35f,
    /* trim_l             */ 0.0f,
    /* trim_c             */ 0.0f,
    /* trim_r             */ 0.0f,
    /* trim_sl            */ 0.0f,
    /* trim_sr            */ 0.0f,
    /* trim_cs            */ 0.0f,
    /* trim_lfe           */ 0.0f,
    /* mute_l             */ 0.0f,
    /* mute_c             */ 0.0f,
    /* mute_r             */ 0.0f,
    /* mute_sl            */ 0.0f,
    /* mute_sr            */ 0.0f,
    /* mute_cs            */ 0.0f,
    /* mute_lfe           */ 0.0f,
    /* debug_raw          */ 0.0f,
    /* transient_protect  */ 1.0f,   // 默认开启
    /* bs775              */ 0.0f,
};

}  // namespace sonicraft::dsp::vs
