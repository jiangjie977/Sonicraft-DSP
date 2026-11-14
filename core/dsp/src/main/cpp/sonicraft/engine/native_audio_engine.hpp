/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

#pragma once

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <cstddef>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "sonicraft/dsp/idle_bypass.hpp"

#include <jni.h>

#include <aaudio/AAudio.h>

#include "sonicraft/dsp/dsp_graph.hpp"
#include "sonicraft/dsp/gain_node.hpp"
#include "sonicraft/dsp/balance_node.hpp"
#include "sonicraft/engine/audio_dump.hpp"

namespace sonicraft::engine {

/**
 * 内录接管实时引擎（Mode B）。
 *
 * 数据流：Java 采集线程（AudioRecord + AudioPlaybackCapture）调用 PushInput() 写入输入
 * 环形缓冲 → AAudio 输出回调 OnData() 从环形缓冲取帧 → DspGraph 就地处理 → 写入 AAudio 输出。
 *
 * 线程模型：
 *  - 采集线程：唯一的生产者（PushInput）
 *  - AAudio 回调线程（高优先级实时）：唯一的消费者（OnData）
 * 两者通过 SPSC 环形缓冲（原子读写指针）解耦，回调内零锁。
 */
class NativeAudioEngine {
 public:
  /// diag_dir：诊断文件目录（Kotlin 传入 app 真实 filesDir/crash，替代历史硬编码包名路径）
  NativeAudioEngine(int sample_rate, int channels, int max_frames,
                    const std::string& diag_dir = "");
  ~NativeAudioEngine();

  NativeAudioEngine(const NativeAudioEngine&) = delete;
  NativeAudioEngine& operator=(const NativeAudioEngine&) = delete;

  /** 创建 AAudio 输出流并启动 */
  bool Start();
  void Stop();

  /** 采集线程调用：写入交织 float 输入；缓冲不足则丢弃整块（防撕裂） */
  void PushInput(const float* interleaved, int frames);

  /** 运行时设置噪声门限（输入 RMS）：0 = 禁用；>0 = RMS 低于阈值时输出平滑静音 */
  void SetNoiseGateThreshold(float threshold);

  /** 测试模式：输出替换为 440Hz 正弦波（验证引擎输出通路） */
  void SetTestTone(bool enabled);

  /**
   * 音频取证（M6.1）：开始双端抓取 seconds 秒 PCM 到 dir（files/crash/），
   * 文件对 audio_in_*.f32 / audio_out_*.f32（交织 f32）+ meta。非实时线程调用。
   * 返回 false = 打开文件失败。已在抓取中则幂等成功。
   */
  bool StartAudioDump(const char* dir, int seconds);

  /** 运行时下发节点参数（非旁路型）。node_index 为 AddNode 返回的索引。 */
  /**
   * 设置输出缓冲帧数（用户可调，2026-11-06）。
   * frames<=0 表示恢复默认（2×burst，下限 384 帧）。
   * 运行中调用会立即作用到当前流；下次开流沿用该值。
   */
  void SetBufferFrames(int frames);

  void SetParam(int node_index, int param_id, float value);

  /** 运行时切换节点旁路（A/B 标尺）：bypass=true 时节点透传。 */
  void SetBypass(int node_index, bool bypass);

  /** 自定义脚本效果器：下发脚本源（非实时；旁路状态下即时重载，否则下次 Prepare 生效）。 */
  void SetScriptSource(const std::string& source, int instance = 0);

  /** 自定义脚本效果器：脚本引用 slider 的位图（bit i = slider(i+1)）。 */
  std::uint32_t GetScriptSliderMask(int instance = 0);

  /** 自定义脚本效果器：各 slider 范围（out16[2i]=min,[2i+1]=max）。 */
  void GetScriptSliderRanges(float* out16, int instance = 0);

  /** 自定义脚本效果器：toggle/select/button 控件描述（JSON 数组）。 */
  std::string GetScriptControls(int instance = 0);

  /** 自定义脚本效果器：各 slider 默认值（out16[0..7]=值，[8..15]=是否声明 0/1）。 */
  void GetScriptSliderDefaults(float* out16, int instance = 0);

  /** 自定义脚本效果器：最近一次脚本编译错误（空 = 成功）。 */
  std::string GetScriptError(int instance = 0);

  /** 只做编译检查（不改变当前运行的脚本）；返回错误串，空 = 通过。 */
  std::string CheckScript(const std::string& source);

  /** 启停某个脚本 lane（0..2）。启用时节点会补一次 Prepare。 */
  void SetScriptLaneEnabled(int instance, bool enabled);
  bool IsScriptLaneEnabled(int instance);

  /** 槽位拓扑：true = 并联（取干声副本、结果按 wet 累加），false = 串联（主链就地）。 */
  void SetScriptLaneParallel(int instance, bool parallel);
  bool IsScriptLaneParallel(int instance);
  /** 并联支路混合比例（0..2，默认 1/3）。 */
  void SetScriptLaneWet(int instance, float wet);

  /** 槽位处理顺序（拖拽换序）：lane 索引的排列。 */
  void SetScriptLaneOrder(int a, int b, int c);

  /** RMS 探针：12 个量（前 8 为电平，后 4 为 lane0 参数缓存/启停/并联/湿声）。 */
  void GetScriptRmsLevels(float* out8);

  /** 机架级干湿：0 = 全干（透传），1 = 全湿（默认）。 */
  void SetScriptRackWet(float wet);
  float GetScriptRackWet();

  /** 脚本机架整体延迟（样本）：各启用槽位延迟的最大值。 */
  int GetScriptLatency();

  /**
   * 加载外部 IR 文件到卷积混响 slot 4（M4c.2）。非实时线程调用（Kotlin 协程）。
   * 返回空串 = 成功；非空 = 失败原因（UI 直接显示）。失败回退当前预设不产生任何变化。
   */
  std::string LoadImpulseResponse(const char* path);

  /** 尾分区上限（低端机负载分档）：须在 Start() 前（Prepare 前）调用。0 = 不限制。 */
  void SetTailPartitionLimit(int max_parts);

  /**
   * 多频段每段计量（2026-11-08，供 UI 的 IN/GR/OUT 电平表）。
   * out 长度须 >= 9，索引 [band*3 + 0/1/2] = 入峰值 / GR(dB) / 出峰值，band 0=低 1=中 2=高。
   * 返回 false = 引擎未就绪或无多频段节点。非实时线程调用（UI 轮询）。
   */
  bool GetMultibandMeters(float* out9);
  /** 单段压缩器计量：out3 = {输入峰值, 增益衰减 dB, 输出峰值} */
  bool GetCompressorMeters(float* out3);

  /** 病态脏前置自动对齐开关（转发卷积节点；默认开，下次装载生效）。 */
  void SetTrimSickHead(bool on);

  /**
   * 用户 IR 分析数据（UI 频谱/能量显示）。out 长度须 >= 1027：
   *   [0]=energyL, [1]=energyR, [2]=frames, [3..514]=L 包络(512), [515..1026]=R 包络(512)。
   * 长度 >= 1028 时追加 [1027]=用户 IR 源文件通道数（1/2/4…，0=未知）。
   * 未加载自定义 IR 时全 0。
   */
  void GetIrAnalysis(jfloat* out, int n);

  /**
   * EQ 实时频谱（手绘区衬底，双层 bin 直出）：out[0..1023]=EQ 入口、
   * out[1024..2047]=EQ 出口，各 1024 bin dB（-60..0，峰值保持）。分带/对数
   * 采样在 UI 侧。FFT 在调用线程（UI 拉取，30fps）；引擎未运行时填 -60。
   * n 须 >= 2048。
   */
  void GetEqSpectrum(jfloat* out, int n);

  /**
   * 统计。out 长度须 >= 11：
   *   { lastNs, maxNs, totalNs, processCount, waterMarkFrames, inputLevelScaled(×1e6),
   *     ondataMaxFrames, pushDropBlocks, underrunBlocks, xRunBlocks, idleBypassActive }
   */
  void GetStats(jlong* out, int n);

  bool IsRunning() const { return running_.load(std::memory_order_acquire); }

  /**
   * 重建输出流（非实时线程调用，看门狗/错误回调触发）。微信来电等场景触发系统
   * 音频路由切换 → AAudio 流 error → 无回调即引擎停摆（stats 冻结/静音不恢复）。
   * 内部持锁与 Stop() 互斥；失败自动延迟重试一次。
   */
  bool RestartOutputStream();

 private:
  static aaudio_data_callback_result_t DataCallback(AAudioStream* stream, void* user_data,
                                                    void* audio_data, int32_t num_frames);
  aaudio_data_callback_result_t OnData(void* audio_data, int32_t num_frames);
  /** 首次回调时把本线程绑到最高频核集合（低端机被摔到小核是最大延迟杀手）。
   *  成员函数：诊断落盘需用 diag_dir_（由 Kotlin 注入 app 真实目录）。 */
  void BindBigCoresOnce();
  /** AAudio 错误回调：流被系统 detach（路由切换/设备拔插）时触发，置位唤醒重建 worker */
  static void OnStreamError(AAudioStream* stream, void* user_data, aaudio_result_t error);
  bool OpenAndStartStream();
  /** H2 常驻重建 worker 主循环：backoff 等待 + 重建 + diag 全部在本线程（受管、可 join） */
  void RebuildWorkerLoop();

  int sample_rate_;
  int channels_;
  int max_frames_;
  int ring_capacity_frames_;
  std::string diag_dir_;

  /// stream_ 并发约定：读/写全部发生在 restart_mutex_ 内
  /// （Start/Stop/RestartOutputStream/GetStats 均持锁），回调线程不触碰它。
  AAudioStream* stream_ = nullptr;
  /** 用户设定的输出缓冲帧数（0 = 用默认：2×burst、下限 384 帧） */
  std::atomic<int> desired_buffer_frames_{0};
  std::unique_ptr<dsp::DspGraph> graph_;

  std::vector<float> input_ring_;  // 交织，容量 = ring_capacity_frames_ * channels_
  std::atomic<size_t> write_pos_{0};
  std::atomic<size_t> read_pos_{0};
  std::atomic<bool> running_{false};

  std::atomic<int> water_mark_frames_{0};
  // 空载旁路状态机（省电）：仅回调线程读写（OnData 内 Update/active）
  dsp::IdleBypass idle_bypass_;
  std::atomic<int> input_level_scaled_{0};  // RMS × 1e6
  std::atomic<int> ondata_max_frames_{0};   // AAudio 回调最大帧数（诊断）
  // 数据完整性探针：定位残余哔啵声的丢失环节
  std::atomic<long> push_drop_blocks_{0};  // PushInput 因 ring 满整块丢弃次数
  std::atomic<long> underrun_blocks_{0};   // OnData 欠载补零次数（输出瞬断 = 咔哒声）

  // 噪声门限：输入 RMS 低于阈值时输出平滑静音（运行时可调）
  std::atomic<float> gate_threshold_{0.0f};  // 0 = 禁用
  float gate_gain_{1.0f};                    // 仅 OnData（输出）线程读写，无需原子

  // 测试正弦：输出替换为纯正弦（仅输出线程读写 phase）
  std::atomic<bool> test_tone_{false};
  float test_tone_phase_{0.0f};

  // 输出流重建（错误回调/看门狗触发）：与 Stop() 互斥；restarting_ 防错误回调重入
  std::mutex restart_mutex_;
  std::atomic<bool> restarting_{false};

  // 审计 H2：错误回调原 spawn detached 线程（sleep 最长 20s 后解引用
  // self）——期间 Kotlin stop→nativeDestroy→delete 即 SIGSEGV（CAPTURE_DEAD 自动
  // 止损显著提高 stop 落在 backoff 窗口的概率）。改常驻 worker：构造启动、析构
  // join，生命周期与引擎同寿，self 悬空根除。回调线程仅置位+notify。
  std::thread rebuild_worker_;
  std::mutex rebuild_cv_mutex_;
  std::condition_variable rebuild_cv_;
  std::atomic<bool> rebuild_pending_{false};
  std::atomic<bool> worker_shutdown_{false};
  // 错误回调线程写入、worker 读取的重建参数（回调线程禁止操作流/文件 IO）
  std::atomic<int> pending_error_{0};
  std::atomic<int> pending_streak_{0};
  // 默认 200→50ms（设备切换属正常单发事件，应最快恢复；连续失败另有退避阶梯）
  std::atomic<int> pending_backoff_ms_{50};

  // 连续错误退避（2026-11-01：capture 全零 → 引擎输出空流 → 系统省电 disconnect
  // → -899 → 重建 → 再全零，4.7s/轮无限循环 163 次）。30s 无错即视为新故障序列。
  std::atomic<int> error_streak_{0};
  std::atomic<long> last_error_ts_{0};

  // 双端音频取证 dump（M6.1）：TapIn 仅采集线程 / TapOut 仅回调线程
  AudioDump dump_;
};

}  // namespace sonicraft::engine
