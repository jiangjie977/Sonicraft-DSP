/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 自定义脚本效果器节点（2026-11-12，见 docs/SCRIPT_EFFECT_DESIGN.md）。
 *
 * 后端 = 自研「类 C 栈式虚拟机」SCVM（cvm.hpp）：零外部依赖、逐样本执行、
 * 无 GC、执行期零堆分配。对齐 JamesDSP/EEL2 的实时特性，但语言是类 C 子集。
 *
 *  - 节点与 VM 解耦（ScriptVm 抽象），harness 可注入假 VM 验证契约；
 *  - 默认 bypass=true，脚本编译失败时 VM 置空 → 原地透传，绝不拖垮音频线程。
 */
#pragma once

#include "sonicraft/dsp/cvm.hpp"
#include "sonicraft/dsp/dsp_node.hpp"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace sonicraft::dsp {

/** 脚本元素器 VM 抽象。Load() 非实时；Process() 实时线程。 */
class ScriptVm {
 public:
  virtual ~ScriptVm() = default;

  /** 加载脚本；成功返回 true。非实时线程。 */
  virtual bool Load(const std::string& source, int sample_rate, int channels,
                    int max_frames) {
    (void)source;
    (void)sample_rate;
    (void)channels;
    (void)max_frames;
    return false;
  }

  /** 就地处理 planar 缓冲（逐样本由具体 VM 决定）。实时线程。 */
  virtual void Process(float* const* channels, int frames) {
    (void)channels;
    (void)frames;
  }

  /** 参数下发（映射到脚本 slider1..8，注意是 1 基还是 0 基由实现约定）。 */
  virtual void SetParam(int param_id, float value) {
    (void)param_id;
    (void)value;
  }

  /** 哪些 slider 被脚本引用（bit i = slider(i+1)）。默认全 false。 */
  virtual void GetUsedSliders(bool* out8) const {
    for (int i = 0; i < 8; ++i) out8[i] = false;
  }

  /** 各 slider 的范围（out16[2i]=min, out16[2i+1]=max）。默认 0..1。 */
  virtual void GetSliderRanges(float* out16) const {
    for (int i = 0; i < 8; ++i) {
      out16[2 * i] = 0.0f;
      out16[2 * i + 1] = 1.0f;
    }
  }

  /** 最近一次 Load 的错误信息（空 = 成功）。 */
  virtual std::string GetError() const { return {}; }

  /** 脚本声明的 toggle/select/button 控件（JSON 数组；无则 "[]"）。 */
  virtual std::string GetControlsJson() const { return "[]"; }

  /** 各 slider 默认值（out8 = 值，has8 = 是否声明）。默认无。 */
  virtual void GetSliderDefaults(float* out8, bool* has8) const {
    for (int i = 0; i < 8; ++i) {
      out8[i] = 0.0f;
      has8[i] = false;
    }
  }
};

/** 创建默认脚本 VM（自研 SCVM）。 */
std::unique_ptr<ScriptVm> MakeScriptVm();

// 由编译产物生成控件描述 JSON（节点与 VM 共用；非实时路径）
inline std::string BuildControlsJson(const cvm::Program& prog) {
  auto esc = [](const std::string& s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (char c : s) {
      if (c == '"' || c == '\\') o += '\\';
      o += c;
    }
    return o;
  };
  std::string s = "[";
  for (std::size_t i = 0; i < prog.controls.size(); ++i) {
    if (i) s += ',';
    const cvm::Control& c = prog.controls[i];
    const char* type = (c.type == cvm::CtrlType::Toggle) ? "toggle"
                     : (c.type == cvm::CtrlType::Select) ? "select"
                     : (c.type == cvm::CtrlType::Button) ? "button" : "list";
    s += "{\"type\":\"";
    s += type;
    s += "\",\"index\":";
    s += std::to_string(c.index);
    s += ",\"name\":\"";
    s += esc(c.name);
    s += "\",\"label\":\"";
    s += esc(c.label);
    s += "\",\"options\":[";
    for (std::size_t k = 0; k < c.options.size(); ++k) {
      if (k) s += ',';
      s += '"';
      s += esc(c.options[k]);
      s += '"';
    }
    s += "]";
    if (c.has_default) {
      s += ",\"default\":";
      s += std::to_string(c.default_value);
    }
    s += "}";
  }
  s += "]";
  return s;
}

/**
 * 单条脚本 lane（原 ScriptEffectNode 的全部实现）。
 * bypass=true（默认）或 VM 未就绪时 Process() 原地返回（透传）；否则把整块交给 VM。
 *
 * 注意：对外仍由下方 `ScriptEffectNode` 包装成多 lane，外部代码只认 ScriptEffectNode。
 */
class ScriptEffectLane : public DspNode {
 public:
  std::string_view id() const override { return "script_effect"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    max_frames_ = max_frames;
    retired_.clear();  // 引擎重建时音频已停，可安全回收历史 VM
    Rebuild();
  }

  /** 最近一次脚本编译的错误（空 = 成功）。 */
  std::string GetError() const { return error_; }

  void Reset() override {}

  void Process(float* const* channels, int frames) override {
    if (bypass_) return;  // 透传（就地不动）
    ScriptVm* vm = live_.load(std::memory_order_acquire);
    if (vm == nullptr) return;
    // 每块把参数缓存同步到当前 VM（40 次写 gmem，代价可忽略）。
    // 这样即使 VM 刚被替换，新脚本也能立刻拿到滑块/开关的值。
    ApplyParams(vm);
    vm->Process(channels, frames);
    if (button_dirty_.exchange(false, std::memory_order_acq_rel)) {
      for (int i = 0; i < 8; ++i) params_[24 + i] = 0.0f;  // 按钮脉冲只发一次
      ApplyParams(vm);
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  /** 探针：读取参数缓存（诊断用；音频线程不调用）。 */
  float param_cache(int i) const { return (i >= 0 && i < 40) ? params_[i] : 0.0f; }

  void SetParam(int param_id, float value) override {
    if (param_id < 0 || param_id >= 40) return;
    params_[param_id] = value;
    if (param_id >= 24 && param_id < 32 && value != 0.0f) {
      button_dirty_.store(true, std::memory_order_release);
    }
  }

  /** 脚本引用 slider 的位图（bit i = slider(i+1)）。优先用源码声明（UI 立即可读）。 */
  std::uint32_t GetSliderMask() const {
    if (declared_valid_) return declared_mask_;
    if (!vm_) return 0;
    bool b[8];
    vm_->GetUsedSliders(b);
    std::uint32_t m = 0;
    for (int i = 0; i < 8; ++i) {
      if (b[i]) m |= (1u << static_cast<unsigned>(i));
    }
    return m;
  }

  /** 各 slider 范围（out16[2i]=min,[2i+1]=max）。 */
  void GetSliderRanges(float* out16) const {
    if (declared_valid_) {
      for (int i = 0; i < 16; ++i) out16[i] = declared_ranges_[i];
      return;
    }
    if (vm_) {
      vm_->GetSliderRanges(out16);
      return;
    }
    for (int i = 0; i < 8; ++i) {
      out16[2 * i] = 0.0f;
      out16[2 * i + 1] = 1.0f;
    }
  }

  /** 脚本声明的 toggle/select/button 控件（JSON 数组；无则 "[]"）。 */
  std::string GetControlsJson() const {
    if (declared_valid_) return declared_json_;
    return vm_ ? vm_->GetControlsJson() : "[]";
  }

  /** 各 slider 默认值（out8 = 值，has8 = 是否声明）。 */
  void GetSliderDefaults(float* out8, bool* has8) const {
    if (declared_valid_) {
      for (int i = 0; i < 8; ++i) {
        out8[i] = declared_defaults_[i];
        has8[i] = declared_has_default_[i];
      }
      return;
    }
    if (vm_) {
      vm_->GetSliderDefaults(out8, has8);
      return;
    }
    for (int i = 0; i < 8; ++i) {
      out8[i] = 0.0f;
      has8[i] = false;
    }
  }

  // ---- 非实时线程配置 ----
  void set_channel_count(int channels) { channels_ = channels; }
  void set_script_source(std::string source) {
    source_ = std::move(source);
    RefreshDeclared();
    Rebuild();  // 立即重建并原子交接（不再依赖 bypass 命令的执行时机）
    UpdateLatency();
  }

  /** 本槽位的处理延迟（样本）。convfft_* 分区卷积固定 1 个 hop = 512。 */
  int latency() const { return latency_; }

  void UpdateLatency() {
    // 保守起见：源码里出现 convfft_ 就按 512 计（注释里提到也只是保守估计）
    latency_ = (source_.find("convfft_") != std::string::npos) ? 512 : 0;
  }

  /** 只记录源码 + 生成声明（不编译 VM）；供「禁用槽位」省内存用。 */
  void set_source_only(std::string source) {
    source_ = std::move(source);
    RefreshDeclared();
    UpdateLatency();
  }

  /**
   * 非实时：直接把源码编译一份「声明信息」（控件 / 范围 / 默认值 / 错误）。
   * 这样 UI 在「应用脚本」后能立即读到新脚本的控件，不必等音频线程重载 VM。
   */
  void RefreshDeclared() {
    declared_valid_ = false;
    declared_mask_ = 0;
    for (int i = 0; i < 8; ++i) {
      declared_ranges_[2 * i] = 0.0f;
      declared_ranges_[2 * i + 1] = 1.0f;
      declared_defaults_[i] = 0.0f;
      declared_has_default_[i] = false;
    }
    declared_json_ = "[]";
    cvm::Program tmp;
    std::string err;
    if (!cvm::Compile(source_, tmp, err)) {
      error_ = err;
      return;
    }
    error_.clear();
    for (int i = 0; i < 8; ++i) {
      if (tmp.used_sliders[static_cast<std::size_t>(i)]) {
        declared_mask_ |= (1u << static_cast<unsigned>(i));
      }
      declared_ranges_[2 * i] = tmp.slider_min[static_cast<std::size_t>(i)];
      declared_ranges_[2 * i + 1] = tmp.slider_max[static_cast<std::size_t>(i)];
      declared_defaults_[i] = tmp.slider_default[static_cast<std::size_t>(i)];
      declared_has_default_[i] = tmp.slider_has_default[static_cast<std::size_t>(i)];
    }
    declared_json_ = BuildControlsJson(tmp);
    declared_valid_ = true;
  }
  /** 注入自定义 VM（测试用）；注入后不再被 Prepare 重建。 */
  void set_vm(std::unique_ptr<ScriptVm> vm) {
    vm_ = std::move(vm);
    vm_injected_ = true;
    live_.store(vm_.get(), std::memory_order_release);
  }

  /** 兼容旧调用点：现在由 set_script_source 内部立即重建，这里不再做事。 */
  void ReloadIfBypassed() {}

  /**
   * 非实时：编译新 VM 并原子交接。
   * 旧 VM 不立即释放（音频线程本轮可能还在用），移入退休池，等 Prepare 时统一回收；
   * 音频线程通过 live_ 原子指针取当前 VM，全程不分配、不加锁。
   */
  bool Rebuild() {
    if (vm_injected_) {
      live_.store(vm_.get(), std::memory_order_release);
      return vm_ != nullptr;
    }
    auto next = MakeScriptVm();
    if (!next) {
      error_ = "no vm backend";
      return false;
    }
    if (!next->Load(source_, sample_rate_, channels_, max_frames_)) {
      error_ = next->GetError();  // 编译失败：保留旧 VM 继续出声
      return false;
    }
    ApplyParams(next.get());
    if (vm_) retired_.push_back(std::move(vm_));
    vm_ = std::move(next);
    live_.store(vm_.get(), std::memory_order_release);
    error_.clear();
    return true;
  }

 private:
  void ApplyParams(ScriptVm* vm) {
    if (vm == nullptr) return;
    for (int i = 0; i < 40; ++i) vm->SetParam(i, params_[i]);
  }

 private:
  // 内置默认脚本（软削波），类 C 语法。
  // 排版规范：4 空格缩进、函数之间空一行、函数首行写用途注释。spl0/spl1 为当前样本。
  // 注意：app 侧 ScriptDefaults.DEFAULT_SCRIPT 与本段文本保持一致。
  static constexpr const char* kDefaultScript = R"SCVM(// 内置示例：软削波 + 可调驱动
// 语法：void init() 加载时执行一次；void sample() 每个样本执行一次
// 参数声明：// sliderN: min, max（N 从 1 开始，最多 8 个）
// slider1: 0.0, 8.0
void init() {
    // 可在此预计算系数
}

void sample() {
    // g = 1.0 为原声；slider1 越大，软削波失真越明显
    float g = 1.0 + slider1;
    float x0 = spl0 * g;
    spl0 = x0 / (1.0 + abs(x0));
    float x1 = spl1 * g;
    spl1 = x1 / (1.0 + abs(x1));
}
)SCVM";

  bool bypass_ = true;
  bool vm_injected_ = false;
  int channels_ = 2;
  int sample_rate_ = 48000;
  int max_frames_ = 192;
  std::string source_ = kDefaultScript;
  std::string error_;
  int latency_ = 0;  // 处理延迟（样本）：convfft_* 分区卷积 = 1 个 hop = 512
  std::unique_ptr<ScriptVm> vm_;
  std::atomic<ScriptVm*> live_{nullptr};           // 音频线程读取的当前 VM
  std::vector<std::unique_ptr<ScriptVm>> retired_;  // 退休 VM（Prepare 时回收）
  float params_[40] = {};                           // 参数缓存（id 0..39）
  std::atomic<bool> button_dirty_{false};
  // 源码声明信息（非实时编译，UI 立即可读；与音频线程的 vm_ 解耦）
  bool declared_valid_ = false;
  std::uint32_t declared_mask_ = 0;
  float declared_ranges_[16] = {};
  float declared_defaults_[8] = {};
  bool declared_has_default_[8] = {};
  std::string declared_json_ = "[]";
};

/**
 * 多 lane 包装节点（批次 1）：内部 3 条独立 lane，音频链仍只有 1 个节点。
 * 本批只启用 lane0，其余保持休眠 → 行为与单实例完全一致；
 * 后续批次再把 SetParam/脚本源按 instance 路由到 lane1/2。
 */
class ScriptEffectNode : public DspNode {
 public:
  static constexpr int kLanes = 3;

  std::string_view id() const override { return "script_effect"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    max_frames_ = max_frames;
    const std::size_t cap = static_cast<std::size_t>(max_frames > 0 ? max_frames : 1);
    dryL_.assign(cap, 0.0f);
    mixL_.assign(cap, 0.0f);
    alignL_.assign(2048, 0.0f);
    alignR_.assign(2048, 0.0f);
    alignPos_ = 0;
    dryRingL_.assign(2048, 0.0f);
    dryRingR_.assign(2048, 0.0f);
    dryRingPos_ = 0;
    mixR_.assign(cap, 0.0f);
    dryR_.assign(cap, 0.0f);
    tmpL_.assign(cap, 0.0f);
    tmpR_.assign(cap, 0.0f);
    accL_.assign(cap, 0.0f);
    accR_.assign(cap, 0.0f);
    for (int i = 0; i < kLanes; ++i) {
      if (lane_on_[i].load(std::memory_order_relaxed)) lanes_[i].Prepare(sample_rate, max_frames);
      else lanes_[i].set_channel_count(channels_);
    }
  }

  void Reset() override {
    for (auto& l : lanes_) l.Reset();
  }

  /**
   * 槽位拓扑（2026-11-13）：
   *  - 串联槽位：就地处理，输入 = 上一级串联槽位的输出（第一级 = 机架输入）；
   *  - 并联槽位：从**机架输入（OutputGain 输出）的干声副本**处理，结果按 wet 累加；
   *  - 输出 = 主链输出 + Σ(并联输出 × wet)，随后交给 Limiter 节点。
   * 全是串联时走原路径，零额外开销。
   */
  /** 一块（含左右声道）的 RMS。诊断探针用，代价 ~1 次遍历。 */
  static float BlockRms(const float* l, const float* r, int n) {
    if (l == nullptr || n <= 0) return 0.0f;
    double sum = 0.0;
    for (int i = 0; i < n; ++i) {
      const double a = l[i];
      sum += a * a;
      if (r != nullptr) {
        const double b = r[i];
        sum += b * b;
      }
    }
    const int cnt = (r != nullptr) ? (n * 2) : n;
    return static_cast<float>(std::sqrt(sum / static_cast<double>(cnt)));
  }

  void Process(float* const* channels, int frames) override {
    if (frames <= 0) return;
    const float* probeR = (channels_ >= 2) ? channels[1] : nullptr;
    rms_in_.store(BlockRms(channels[0], probeR, frames));
    const float rackWet = rack_wet_.load(std::memory_order_relaxed);
    const bool doMix = rackWet < 0.999f;
    // 处理链带来的延迟（串联累计 + 并联对齐），干湿混音时干声需补同样的量
    const int outLat = rack_latency();
    int mn = frames;
    if (doMix) {
      mn = (frames < static_cast<int>(mixL_.size())) ? frames
                                                     : static_cast<int>(mixL_.size());
      for (int k = 0; k < mn; ++k) {
        mixL_[static_cast<std::size_t>(k)] = channels[0][k];
        if (channels_ >= 2) mixR_[static_cast<std::size_t>(k)] = channels[1][k];
      }
    }
    bool anyPar = false;
    for (int i = 0; i < kLanes; ++i) {
      if (lane_on_[i].load(std::memory_order_relaxed) &&
          lane_parallel_[i].load(std::memory_order_relaxed)) anyPar = true;
    }
    if (!anyPar) {
        for (int k = 0; k < kLanes; ++k) {
          const int i = order_[k];
          if (lane_on_[i].load(std::memory_order_relaxed)) {
            rms_lane_in_[i].store(BlockRms(channels[0], probeR, frames));
            lanes_[i].Process(channels, frames);
            rms_lane_out_[i].store(BlockRms(channels[0], probeR, frames));
          }
      }
      if (doMix) ApplyWetMix(channels, mn, rackWet, outLat);
      rms_out_.store(BlockRms(channels[0], probeR, frames));
      return;
    }

    const int n = (frames < static_cast<int>(dryL_.size()))
                      ? frames
                      : static_cast<int>(dryL_.size());
    if (n <= 0) return;
    const bool stereo = channels_ >= 2;
    for (int k = 0; k < n; ++k) {
      dryL_[static_cast<std::size_t>(k)] = channels[0][k];
      if (stereo) dryR_[static_cast<std::size_t>(k)] = channels[1][k];
    }
    std::fill(accL_.begin(), accL_.begin() + n, 0.0f);
    if (stereo) std::fill(accR_.begin(), accR_.begin() + n, 0.0f);

      for (int k = 0; k < kLanes; ++k) {
        const int i = order_[k];
        if (!lane_on_[i].load(std::memory_order_relaxed)) continue;
      if (!lane_parallel_[i].load(std::memory_order_relaxed)) {
        rms_lane_in_[i].store(BlockRms(channels[0], probeR, frames));
        lanes_[i].Process(channels, frames);  // 主链：就地串联
        rms_lane_out_[i].store(BlockRms(channels[0], probeR, frames));
        continue;
      }
      for (int k = 0; k < n; ++k) {
        tmpL_[static_cast<std::size_t>(k)] = dryL_[static_cast<std::size_t>(k)];
        if (stereo) tmpR_[static_cast<std::size_t>(k)] = dryR_[static_cast<std::size_t>(k)];
      }
      float* tmpBuf[2] = {tmpL_.data(), stereo ? tmpR_.data() : tmpL_.data()};
      rms_lane_in_[i].store(BlockRms(dryL_.data(), stereo ? dryR_.data() : nullptr, n));
      lanes_[i].Process(tmpBuf, frames);
      rms_lane_out_[i].store(BlockRms(tmpL_.data(), stereo ? tmpR_.data() : nullptr, n));
      const float w = lane_wet_[i].load(std::memory_order_relaxed);
      for (int k = 0; k < n; ++k) {
        accL_[static_cast<std::size_t>(k)] += tmpL_[static_cast<std::size_t>(k)] * w;
        if (stereo) {
          accR_[static_cast<std::size_t>(k)] += tmpR_[static_cast<std::size_t>(k)] * w;
        }
      }
    }

    // 并联总量自动归一：Σwet > 1 时按比例缩放，避免叠加直接顶到 Limiter
    float sumWet = 0.0f;
    for (int i = 0; i < kLanes; ++i) {
      if (lane_on_[i].load(std::memory_order_relaxed) &&
          lane_parallel_[i].load(std::memory_order_relaxed)) {
        sumWet += lane_wet_[i].load(std::memory_order_relaxed);
      }
    }
    const float scale = (sumWet > 1.0f) ? (1.0f / sumWet) : 1.0f;

    // A 方案：主链若为空（全并联），channels 仍是干声 → 直接相加即保留干声
    for (int k = 0; k < n; ++k) {
      channels[0][k] += accL_[static_cast<std::size_t>(k)] * scale;
      if (stereo) channels[1][k] += accR_[static_cast<std::size_t>(k)] * scale;
    }
    // 对齐：并联支路若带处理延迟（convfft），把主链延后同样的量，
    // 否则干声与湿声在时间上错开，叠加会出现梳状染色。
    int parLat = 0;
    for (int i = 0; i < kLanes; ++i) {
      if (lane_on_[i].load(std::memory_order_relaxed) &&
          lane_parallel_[i].load(std::memory_order_relaxed)) {
        const int l = lanes_[i].latency();
        if (l > parLat) parLat = l;
      }
    }
    if (parLat > 0) ApplyAlignDelay(channels, frames, parLat);

    if (doMix) ApplyWetMix(channels, mn, rackWet, outLat);
    rms_out_.store(BlockRms(channels[0], probeR, frames));
  }

  /** 串联链累计延迟（样本）：启用的非并联槽位延迟之和。 */
  int series_latency() const {
    int sum = 0;
    for (int i = 0; i < kLanes; ++i) {
      if (!lane_on_[i].load(std::memory_order_relaxed)) continue;
      if (lane_parallel_[i].load(std::memory_order_relaxed)) continue;
      sum += lanes_[i].latency();
    }
    return sum;
  }

  /** 并联支路最大延迟（样本）：用于主链对齐。 */
  int parallel_latency() const {
    int m = 0;
    for (int i = 0; i < kLanes; ++i) {
      if (!lane_on_[i].load(std::memory_order_relaxed)) continue;
      if (!lane_parallel_[i].load(std::memory_order_relaxed)) continue;
      const int l = lanes_[i].latency();
      if (l > m) m = l;
    }
    return m;
  }

  /** 机架真实输出延迟（样本）= 串联累计 + 并联对齐量。宿主补偿以此为准。 */
  int rack_latency() const { return series_latency() + parallel_latency(); }

  /** 主链整体延后 n 个样本（环形缓冲，音频线程零分配）。
   *  注意：必须"写入当前位、读回 n 位之前"，不能读写同一槽位——
   *  那样实际延迟恒等于缓冲长度（2048），与请求的 n 无关（2026-11-14 修正）。 */
  void ApplyAlignDelay(float* const* channels, int frames, int n) {
    if (alignL_.empty()) return;
    const int size = static_cast<int>(alignL_.size());
    if (n <= 0 || n >= size) return;
    const bool stereo = channels_ >= 2;
    for (int k = 0; k < frames; ++k) {
      const int w = alignPos_;
      const int r = (w + size - n) % size;
      const float keepL = alignL_[static_cast<std::size_t>(r)];
      alignL_[static_cast<std::size_t>(w)] = channels[0][k];
      channels[0][k] = keepL;
      if (stereo) {
        const float keepR = alignR_[static_cast<std::size_t>(r)];
        alignR_[static_cast<std::size_t>(w)] = channels[1][k];
        channels[1][k] = keepR;
      }
      alignPos_ = (w + 1) % size;
    }
  }

  /** 把干声快照 mixL_/mixR_ 延后 delay 个样本，使其与处理链时间对齐。 */
  void AlignDryBuffer(int n, int delay) {
    if (dryRingL_.empty()) return;
    const int size = static_cast<int>(dryRingL_.size());
    if (delay <= 0 || delay >= size) return;
    const bool stereo = channels_ >= 2;
    for (int k = 0; k < n; ++k) {
      const int w = dryRingPos_;
      const int r = (w + size - delay) % size;
      const float keepL = dryRingL_[static_cast<std::size_t>(r)];
      dryRingL_[static_cast<std::size_t>(w)] = mixL_[static_cast<std::size_t>(k)];
      mixL_[static_cast<std::size_t>(k)] = keepL;
      if (stereo) {
        const float keepR = dryRingR_[static_cast<std::size_t>(r)];
        dryRingR_[static_cast<std::size_t>(w)] = mixR_[static_cast<std::size_t>(k)];
        mixR_[static_cast<std::size_t>(k)] = keepR;
      }
      dryRingPos_ = (w + 1) % size;
    }
  }

  /** 机架级干湿混音：out = dry*(1-w) + processed*w
   *  dryDelay：干声需补偿的样本数（= 串联累计 + 并联对齐），否则干湿相位错开产生梳状染色。 */
  void ApplyWetMix(float* const* channels, int n, float w, int dryDelay = 0) {
    if (dryDelay > 0) AlignDryBuffer(n, dryDelay);
    const float dry = 1.0f - w;
    const bool stereo = channels_ >= 2;
    for (int k = 0; k < n; ++k) {
      channels[0][k] = mixL_[static_cast<std::size_t>(k)] * dry + channels[0][k] * w;
      if (stereo) {
        channels[1][k] = mixR_[static_cast<std::size_t>(k)] * dry + channels[1][k] * w;
      }
    }
  }

  /** 机架干湿：0 = 全干，1 = 全湿（默认）。 */
  void set_rack_wet(float w) {
    if (w < 0.0f) w = 0.0f;
    if (w > 1.0f) w = 1.0f;
    rack_wet_.store(w, std::memory_order_relaxed);
  }
  float rack_wet() const { return rack_wet_.load(std::memory_order_relaxed); }

  /** 探针读取：{机架入, 机架出, laneN 入/出 ×3, lane0 slider1 缓存, lane0 启停, lane0 并联, lane0 湿声}。 */
  void get_rms_levels(float* out) const {
    if (out == nullptr) return;
    out[0] = rms_in_.load();
    out[1] = rms_out_.load();
    for (int i = 0; i < kLanes; ++i) {
      out[2 + i * 2] = rms_lane_in_[i].load();
      out[3 + i * 2] = rms_lane_out_[i].load();
    }
    out[8] = lanes_[0].param_cache(0);
    out[9] = lane_on_[0].load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[10] = lane_parallel_[0].load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    out[11] = lane_wet_[0].load(std::memory_order_relaxed);
  }

  void SetBypass(bool bypass) override {
    for (auto& l : lanes_) l.SetBypass(bypass);
  }
  bool Bypassed() const override { return lanes_[0].Bypassed(); }

  /**
   * 参数路由：param_id = instance*64 + local_id（local 0..39 与单实例时逐位兼容）。
   * 越界忽略；**未启用的 lane 只缓存不丢弃**。
   * 原因：params_ 初值全 0，而 Lane::Process 每块用 ApplyParams 把缓存写进 gmem。
   * 若关闭态丢弃推送，启用后 slider 仍为 0 —— gain 类脚本乘 0 就变成静音
   * （2026-11-14 定位的「总闸一开就静音」）。
   */
  void SetParam(int param_id, float value) override {
    if (param_id < 0) return;
    const int inst = param_id / 64;
    const int local = param_id % 64;
    if (inst < 0 || inst >= kLanes) return;
    lanes_[inst].SetParam(local, value);  // 关闭态也缓存，启用后立即可用
  }

  void set_channel_count(int channels) {
    channels_ = channels;
    for (auto& l : lanes_) l.set_channel_count(channels);
  }
  void set_script_source(std::string source, int instance = 0) {
    if (instance < 0 || instance >= kLanes) return;
    // 禁用槽位只记源码、不编译 VM（省内存/编译时间），启用时 Prepare 会真正加载
    if (lane_on_[instance].load(std::memory_order_relaxed)) {
      lanes_[instance].set_script_source(std::move(source));
    } else {
      lanes_[instance].set_source_only(std::move(source));
    }
  }
  void set_vm(std::unique_ptr<ScriptVm> vm, int instance = 0) {
    if (instance < 0 || instance >= kLanes) return;
    lanes_[instance].set_vm(std::move(vm));
  }
  void ReloadIfBypassed(int instance = 0) {
    if (instance >= 0 && instance < kLanes) lanes_[instance].ReloadIfBypassed();
  }
  bool Rebuild(int instance = 0) {
    return (instance >= 0 && instance < kLanes) ? lanes_[instance].Rebuild() : false;
  }
  void RefreshDeclared(int instance = 0) {
    if (instance >= 0 && instance < kLanes) lanes_[instance].RefreshDeclared();
  }

  std::string GetError(int instance = 0) const {
    return (instance >= 0 && instance < kLanes) ? lanes_[instance].GetError() : std::string();
  }
  std::uint32_t GetSliderMask(int instance = 0) const {
    return (instance >= 0 && instance < kLanes) ? lanes_[instance].GetSliderMask() : 0u;
  }
  void GetSliderRanges(float* out16, int instance = 0) const {
    if (instance >= 0 && instance < kLanes) lanes_[instance].GetSliderRanges(out16);
  }
  std::string GetControlsJson(int instance = 0) const {
    return (instance >= 0 && instance < kLanes) ? lanes_[instance].GetControlsJson()
                                                : std::string("[]");
  }
  void GetSliderDefaults(float* out8, bool* has8, int instance = 0) const {
    if (instance >= 0 && instance < kLanes) lanes_[instance].GetSliderDefaults(out8, has8);
  }

  /** 供后续批次/测试按实例访问（0..kLanes-1）。 */
  ScriptEffectLane& lane(int i) { return lanes_[(i >= 0 && i < kLanes) ? i : 0]; }
  /** 启停某条 lane；首次启用时补一次 Prepare（否则该 lane 没有可用 VM）。 */
  void set_lane_enabled(int i, bool on) {
    if (i < 0 || i >= kLanes) return;
    const bool was = lane_on_[i].load(std::memory_order_relaxed);
    lane_on_[i].store(on, std::memory_order_relaxed);
    if (on && !was) {
      lanes_[i].set_channel_count(channels_);
      // 只有节点已 Prepare（采样率有效）时才补 Prepare；
      // 否则用 0 采样率 Prepare 会重建 VM 并丢掉已推送的参数（2026-11-13 定位）
      if (sample_rate_ > 0 && max_frames_ > 0) {
        lanes_[i].Prepare(sample_rate_, max_frames_);
      }
    }
  }
  bool lane_enabled(int i) const {
    return (i >= 0 && i < kLanes) ? lane_on_[i].load(std::memory_order_relaxed) : false;
  }

  /** 槽位处理顺序（拖拽换序）：order_[0..2] 是 lane 索引的排列。 */
  void set_lane_order(int a, int b, int c) {
    const int in[3] = {a, b, c};
    bool used[3] = {false, false, false};
    for (int i = 0; i < 3; ++i) {
      if (in[i] < 0 || in[i] >= kLanes || used[in[i]]) return;  // 非法排列直接忽略
      used[in[i]] = true;
    }
    order_[0] = a;
    order_[1] = b;
    order_[2] = c;
  }
  int lane_order(int pos) const {
    return (pos >= 0 && pos < kLanes) ? order_[pos] : 0;
  }

  /** 槽位拓扑：true = 并联（从干声拷贝处理并累加），false = 串联（主链就地）。 */
  void set_lane_parallel(int i, bool v) {
    if (i >= 0 && i < kLanes) lane_parallel_[i].store(v, std::memory_order_relaxed);
  }
  bool lane_parallel(int i) const {
    return (i >= 0 && i < kLanes) ? lane_parallel_[i].load(std::memory_order_relaxed)
                                  : false;
  }
  /** 并联支路混合比例（0 = 不参与，1 = 全量；默认 1/3 防叠加过载）。 */
  void set_lane_wet(int i, float w) {
    if (i < 0 || i >= kLanes) return;
    if (w < 0.0f) w = 0.0f;
    if (w > 2.0f) w = 2.0f;
    lane_wet_[i].store(w, std::memory_order_relaxed);
  }
  float lane_wet(int i) const {
    return (i >= 0 && i < kLanes) ? lane_wet_[i].load(std::memory_order_relaxed) : 0.0f;
  }

 private:
  ScriptEffectLane lanes_[kLanes];
  // 跨线程（UI/引擎线程写、音频线程读）→ 必须原子；relaxed 足够（只做标志/增益）
  std::atomic<bool> lane_on_[kLanes] = {{true}, {false}, {false}};  // 默认只跑 lane0
  std::atomic<bool> lane_parallel_[kLanes] = {{false}, {false}, {false}};  // 默认全串联
  std::atomic<float> lane_wet_[kLanes] = {{0.3333f}, {0.3333f}, {0.3333f}};
  // 机架级干湿：1 = 全湿（默认，行为与之前一致），0 = 全干（透传输入）
  std::atomic<float> rack_wet_{1.0f};
  std::vector<float> mixL_, mixR_;  // 湿声混音用的输入快照
  int order_[kLanes] = {0, 1, 2};  // 处理顺序（槽位位置 -> lane 索引）
  // ---- RMS 探针（2026-11-13）：供后台读取，不暴露到 UI ----
  // in = 机架输入，out = 机架输出；laneIn/laneOut = 各槽位进/出
  std::atomic<float> rms_in_{0.0f};
  std::atomic<float> rms_out_{0.0f};
  std::atomic<float> rms_lane_in_[kLanes] = {{0.0f}, {0.0f}, {0.0f}};
  std::atomic<float> rms_lane_out_[kLanes] = {{0.0f}, {0.0f}, {0.0f}};
  int channels_ = 2;
  int sample_rate_ = 48000;
  int max_frames_ = 192;
  // 并联用的预分配缓冲（音频线程不分配）
  std::vector<float> dryL_, dryR_, tmpL_, tmpR_, accL_, accR_;
  // 并联支路带延迟（如 convfft 卷积）时，把主链对齐到同一时刻
  std::vector<float> alignL_, alignR_;
  int alignPos_ = 0;
  // 干声对齐环形缓冲（干湿混音用，与主链对齐缓冲分开，避免双重延迟）
  std::vector<float> dryRingL_, dryRingR_;
  int dryRingPos_ = 0;
};

// ---------------------------------------------------------------------------
// 自研 SCVM 后端
// ---------------------------------------------------------------------------
class CvmScriptVm : public ScriptVm {
 public:
  bool Load(const std::string& source, int sample_rate, int channels,
            int max_frames) override {
    (void)max_frames;
    channels_ = (channels > 0 ? channels : 2);
    cvm::Program prog;
    std::string err;
    if (!cvm::Compile(source, prog, err)) {
      error_ = err;
      ok_ = false;
      return false;
    }
    prog_ = std::move(prog);
    vm_.Reset(&prog_);
    vm_.SetSampleRate(static_cast<float>(sample_rate));
    vm_.gmem()[cvm::kSlotSrate] = static_cast<float>(sample_rate);
    vm_.RunInit();
    ok_ = true;
    return true;
  }

  void Process(float* const* channels, int frames) override {
    if (!ok_) return;
    // block() 段：每块执行一次，可读写整块（为卷积/FFT 提供块级入口）
    if (prog_.block_offset >= 0) {
      vm_.SetBlock(frames, channels[0], channels_ >= 2 ? channels[1] : nullptr);
      vm_.RunBlock();
      vm_.CopyBlockOut(channels[0], channels_ >= 2 ? channels[1] : nullptr);
    }
    for (int i = 0; i < frames; ++i) {
      vm_.gmem()[cvm::kSlotSpl0] = channels[0][i];
      if (channels_ >= 2) vm_.gmem()[cvm::kSlotSpl1] = channels[1][i];
      vm_.RunSample();
      channels[0][i] = vm_.gmem()[cvm::kSlotSpl0];
      if (channels_ >= 2) channels[1][i] = vm_.gmem()[cvm::kSlotSpl1];
      // 按钮只对「按下的那一个样本」生效：样本执行完立即清零
      for (int k = 0; k < 8; ++k) vm_.gmem()[cvm::kSlotButton1 + k] = 0.0f;
    }
  }

  void SetParam(int param_id, float value) override {
    if (param_id < 0) return;
    if (param_id < 8) {              // slider1..8
      vm_.gmem()[cvm::kSlotSlider1 + param_id] = value;
    } else if (param_id < 16) {      // toggle1..8
      vm_.gmem()[cvm::kSlotToggle1 + (param_id - 8)] = value;
    } else if (param_id < 24) {      // select1..8
      vm_.gmem()[cvm::kSlotSelect1 + (param_id - 16)] = value;
    } else if (param_id < 32) {      // button1..8
      vm_.gmem()[cvm::kSlotButton1 + (param_id - 24)] = value;
    } else if (param_id < 40) {      // list1..8（位掩码）
      vm_.gmem()[cvm::kSlotList1 + (param_id - 32)] = value;
    }
  }

  void GetUsedSliders(bool* out8) const override {
    for (int i = 0; i < 8; ++i) out8[i] = prog_.used_sliders[static_cast<std::size_t>(i)];
  }

  void GetSliderRanges(float* out16) const override {
    for (int i = 0; i < 8; ++i) {
      out16[2 * i] = prog_.slider_min[static_cast<std::size_t>(i)];
      out16[2 * i + 1] = prog_.slider_max[static_cast<std::size_t>(i)];
    }
  }

  void GetSliderDefaults(float* out8, bool* has8) const override {
    for (int i = 0; i < 8; ++i) {
      out8[i] = prog_.slider_default[static_cast<std::size_t>(i)];
      has8[i] = prog_.slider_has_default[static_cast<std::size_t>(i)];
    }
  }

  std::string GetControlsJson() const override {
    auto esc = [](const std::string& s) {
      std::string o;
      o.reserve(s.size() + 8);
      for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        o += c;
      }
      return o;
    };
    std::string s = "[";
    for (std::size_t i = 0; i < prog_.controls.size(); ++i) {
      if (i) s += ',';
      const cvm::Control& c = prog_.controls[i];
      const char* type = (c.type == cvm::CtrlType::Toggle) ? "toggle"
                       : (c.type == cvm::CtrlType::Select) ? "select"
                       : (c.type == cvm::CtrlType::Button) ? "button" : "list";
      s += "{\"type\":\"";
      s += type;
      s += "\",\"index\":";
      s += std::to_string(c.index);
      s += ",\"name\":\"";
      s += esc(c.name);
      s += "\",\"label\":\"";
      s += esc(c.label);
      s += "\",\"options\":[";
      for (std::size_t k = 0; k < c.options.size(); ++k) {
        if (k) s += ',';
        s += '"';
        s += esc(c.options[k]);
        s += '"';
      }
      s += "]";
      if (c.has_default) {
        s += ",\"default\":";
        s += std::to_string(c.default_value);
      }
      s += "}";
    }
    s += "]";
    return s;
  }

  const std::string& error() const { return error_; }

  std::string GetError() const override { return error_; }

 private:
  cvm::Program prog_;
  cvm::Vm vm_;
  int channels_ = 2;
  bool ok_ = false;
  std::string error_;
};

inline std::unique_ptr<ScriptVm> MakeScriptVm() {
  return std::make_unique<CvmScriptVm>();
}

}  // namespace sonicraft::dsp
