/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

namespace sonicraft::dsp {

/**
 * 空载旁路状态机（M9 省电，2026-10-27）。
 *
 * 背景：全局接管模式下引擎全天挂机，无音频播放时输入为数字静音（欠载补零/
 * 静音流 RMS = 精确 0），但 DSP 全链（卷积 head MAC + tail 分区 + 混响 + EQ +
 * 限制器）照样满算——实测静音空转占 ~54% 单核（utime 3s 差分，NX733J），
 * 是系统耗电排行的主因（1894 mAh / 6.6h，几乎全挂前台服务 CPU）。
 *
 * 机制：输入 RMS 低于阈值持续满 kSilenceSeconds 秒 → 旁路激活（调用方跳过
 * 整条 DSP 链，输出保持输入原样——此时输入必为静音）；任何超阈值块立即解冻
 * （当块恢复全链，恢复延迟 < 1 块 ≈ 4ms，无感）。激活前混响尾已在全链运行中
 * 自然衰减完毕，切换点不可闻。
 *
 * 为什么静音门限是 4 秒：旁路会截断混响尾的继续衰减，必须在尾衰减到不可闻
 * 之后才允许切——门限必须 > 最长 IR 尾。上界受 kMaxIrLen=131072（2.73s@48k）
 * 截断保护、内置教堂 RIR 更短，4s 覆盖全部预设并留约 1.3s 边距。
 *
 * 为什么阈值取 -80dBFS：数字静音流与欠载补零的 RMS = 0；正常听音量（系统
 * 数字音量最小非零档约 -50dB、音乐节目 RMS 再抬 10~20dB）远高于 -80dB，
 * 不会把轻音量播放误判为静音。
 *
 * 线程模型：仅实时回调线程读写（Update/active），无需原子；纯逻辑类，可被
 * host-harness 直接测试（10a）。
 */
class IdleBypass {
 public:
  /// 输入 RMS 判静音阈值（线性，≈ -80dBFS）
  static constexpr float kRmsThreshold = 1.0e-4f;
  /// 持续静音多久后旁路（覆盖 kMaxIrLen 尾长上界 + 边距）
  static constexpr float kSilenceSeconds = 4.0f;

  void Configure(int sample_rate) {
    threshold_frames_ =
        static_cast<long long>(kSilenceSeconds * static_cast<float>(sample_rate));
  }

  /**
   * 每回调块调用。返回 true = 本块应旁路（跳过 DSP 全链）；超阈值块无条件
   * 返回 false 并立即复位计时（当块恢复全链处理）。
   */
  bool Update(float rms, int frames) {
    if (rms >= kRmsThreshold) {
      silent_frames_ = 0;
      active_ = false;
      return false;
    }
    silent_frames_ += frames;
    if (!active_ && silent_frames_ >= threshold_frames_) active_ = true;
    return active_;
  }

  bool active() const { return active_; }
  long long silent_frames() const { return silent_frames_; }

  void Reset() {
    silent_frames_ = 0;
    active_ = false;
  }

 private:
  long long threshold_frames_ = 1;
  long long silent_frames_ = 0;
  bool active_ = false;
};

}  // namespace sonicraft::dsp
