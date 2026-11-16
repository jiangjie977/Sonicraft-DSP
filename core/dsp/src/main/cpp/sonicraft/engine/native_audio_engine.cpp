/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "sonicraft/engine/native_audio_engine.hpp"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <sched.h>
#include <thread>
#include <vector>

#include "sonicraft/dsp/compressor.hpp"
#include "sonicraft/dsp/convolver.hpp"
#include "sonicraft/dsp/dynamic_bass.hpp"
#include "sonicraft/dsp/multiband.hpp"
#include "sonicraft/dsp/reverb_node.hpp"
#include "sonicraft/dsp/limiter.hpp"
#include "sonicraft/dsp/pass_through.hpp"
#include "sonicraft/dsp/parametric_eq.hpp"
#include "sonicraft/dsp/stereo_width.hpp"
#include "sonicraft/dsp/bass_enhance.hpp"
#include "sonicraft/dsp/virtual_bass.hpp"
#include "sonicraft/dsp/script_effect.hpp"
#include "sonicraft/dsp/virtual_surround.hpp"

namespace sonicraft::engine {

namespace {
// 省电：默认不绑核。块预算 4ms vs 全链实算 ~1ms，余量巨大，
// 绑大核的延迟收益不再值得锁簇功耗代价——空载 54% 大核占用经静音直通治理后，
// 剩余负载交由调度器 EAS 自由放置（参照 audioserver FastMixer 不绑核零事故）。
// 回退开关：真机 stress 若出现 xRun/水位爬升，改回 true 重编即可（低配机仍建议绑核）。
constexpr bool kBindBigCores = false;

// ---- 节点索引常量（2026-11-11，技术债 #7 收口）----
//
// 原先全部是字面量：装配处按 AddNode 的**调用顺序**隐式决定索引，而 8 处
// node_at(N) 直接写数字。插一个节点就要人工同步 8 处 + Kotlin 常量块，
// 漏一处的后果是「参数发给了错误的节点」—— 不崩、不报错、只有听感异常，
// 属于最难查的那类 bug。
//
// 现在：装配时校验实际索引与常量一致（脱节会打印 FATAL），node_at 一律用常量。
//
// **必须与 Kotlin 侧 `AudioEngine.*_NODE_INDEX` 保持一致**（跨语言契约，
// 两边任一改动都要同步；动态低音插入后会整体 +1）。
constexpr int kNodeInputGain   = 0;
constexpr int kNodeBalance     = 1;
constexpr int kNodeEq          = 2;
// AutoEq 耳机校正（2026-11-14 插入，紧跟 PEQ 之后 → 其余索引整体 +1）
constexpr int kNodeAutoEq      = 3;
// 虚拟环绕（2026-11-15 插入，紧跟 AutoEq 之后 → 其余索引整体 +1）
// 放在耳机校正之后、低频/动态之前：空间化会重塑频响，先校正再渲染才是正确顺序。
constexpr int kNodeVirtualSurround = 4;
// 动态低音（2026-11-11 插入，位于 EQ 与压限之间）
constexpr int kNodeDynamicBass = 5;
// 虚拟低音（2026-11-11 插入，紧随动态低音）
constexpr int kNodeVirtualBass = 6;
// 低音增强（2026-11-11 插入，M/S + EQ + 瞬态 + 次谐波，设备无关）
constexpr int kNodeBassEnhance = 7;
constexpr int kNodeCompressor  = 8;
constexpr int kNodeMultiband   = 9;
constexpr int kNodeConvolver   = 10;
constexpr int kNodeReverb      = 11;
constexpr int kNodeStereoWidth = 12;
constexpr int kNodeOutputGain  = 13;
// 自定义脚本效果器：位于 OutputGain 之后、Limiter 之前，
// 保证 Limiter 仍是绝对末级（用户脚本 boost 也会被限幅兜住）。
constexpr int kNodeScriptEffect = 14;
constexpr int kNodeLimiter     = 15;
constexpr int kNodeCount       = 16;

/**
 * 索引脱节标志。由 AddNodeAt 置位，装配末尾汇总写诊断文件。
 *
 * 为什么不用 logcat：**本机 adb logcat 完全取不到数据**（`logcat -d` 总行数 0，
 * 厂商 ROM 限制）。日志通道实际只有 App 私有目录一条路（`adb run-as` 可直读），
 * 与 Kotlin 侧 `writeDiag` 写的是同一个 `autostart_diag.txt`。
 */
bool g_node_index_mismatch = false;

/**
 * 装配辅助：AddNode 并校验索引与常量表一致。
 *
 * 为什么只打印不抛异常：本函数在引擎构造期调用，抛异常会让整个 App 起不来，
 * 代价远大于收益。索引脱节属于**开发期错误**，harness / 单测会先暴露；
 * 真机上一旦出现，stderr 里的 FATAL 行足以定位。
 */
int AddNodeAt(dsp::DspGraph& graph, std::unique_ptr<dsp::DspNode> node, int expected) {
  const int idx = graph.AddNode(std::move(node));
  if (idx != expected) {
    // 用 __android_log_print 而不是 fprintf(stderr)：Android 上 stderr 通常被重定向到
    // /dev/null，写进去等于没写。这个断言的全部价值就在于"能被看到"，所以必须走 logcat
    // （`adb logcat -s SonicraftDSP`）。
    __android_log_print(
        ANDROID_LOG_ERROR, "SonicraftDSP",
        "FATAL: node index mismatch: got %d, expected %d "
        "(check AddNode order vs kNodeXxx constants)", idx, expected);
    g_node_index_mismatch = true;   // 供装配末尾汇总写诊断文件
  }
  return idx;
}
}  // namespace

NativeAudioEngine::NativeAudioEngine(int sample_rate, int channels, int max_frames,
                                     const std::string& diag_dir)
    : sample_rate_(sample_rate),
      channels_(channels),
      max_frames_(max_frames),
      diag_dir_(diag_dir),
      // 8192 帧 ≈ 170ms：吸收采集协程调度抖动，避免 PushInput 因瞬时满 ring 丢块（哔啵声）
      ring_capacity_frames_(max_frames * 64),
      input_ring_(static_cast<size_t>(ring_capacity_frames_) * channels, 0.0f) {
  idle_bypass_.Configure(sample_rate);
  graph_ = std::make_unique<dsp::DspGraph>(sample_rate, channels, max_frames);
  // 运行图（2026-11-12 加入脚本效果器）：
  //   输入Gain(0) → Balance(1) → EQ(2) → 动态低音(3) → 虚拟低音(4) → 低音增强(5) →
  //   Comp(6) → 多频段(7) → Conv(8) → 算法混响(9) → Width(10) → 输出Gain(11) →
  //   脚本效果器(12) → Lim(13)
  // 铁律：输入 Gain/Balance 钉死链首（源校正区）、Comp/多频段/输出 Gain/Lim 钉死
  // 链尾（动态尾段）、Lim 绝对末级；效果类节点在两者之间。
  // 默认全部 bypass=true（透传），持久化状态由引擎 Running 后补发。
  auto inputGain = std::make_unique<dsp::GainNode>();
  inputGain->set_channel_count(channels);
  inputGain->set_id("input_gain");
  AddNodeAt(*graph_, std::move(inputGain), kNodeInputGain);
  auto balance = std::make_unique<dsp::BalanceNode>();
  balance->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(balance), kNodeBalance);
  auto eq = std::make_unique<dsp::ParametricEqNode>();
  eq->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(eq), kNodeEq);
  // AutoEq 耳机校正（2026-11-14，index 3）：第二个 ParametricEqNode 实例，
  // 与用户 PEQ **完全独立** —— 型号校正吃自己那 31 段，用户仍可自由调 PEQ 做个人偏好，
  // 两者互不覆盖、可各自旁路。紧贴 PEQ 之后：校正先行，后面的非线性节点
  // （动态低音/压限）看到的是已校正的信号。
  auto autoEq = std::make_unique<dsp::ParametricEqNode>();
  autoEq->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(autoEq), kNodeAutoEq);

  // 虚拟环绕：默认旁路，由 Kotlin 侧 vs_enabled 控制；
  // 输出仍为双声道（内部完成 7 声道解码与双耳渲染）。
  auto vs = std::make_unique<dsp::VirtualSurroundNode>();
  AddNodeAt(*graph_, std::move(vs), kNodeVirtualSurround);
  // 动态低音（M15）：插在 EQ 之后、压限之前 ——
  // 低音增强后的电平由后续压限顺带兜住；但本节点**不依赖**压限（自带低频保护，
  // 见 docs/M15_DYNAMIC_BASS_DESIGN.md ）。
  auto dynBass = std::make_unique<dsp::DynamicBassNode>();
  dynBass->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(dynBass), kNodeDynamicBass);
  // 虚拟低音（2026-11-11，index 4）：心理声学低频扩展，**针对手机内置扬声器**。
  // 与动态低音（耳机向）互斥使用 —— 两者都靠"改低频带"工作，串起来只会互相破坏：
  // 虚拟低音丢弃 <120Hz 的原信号，动态低音再去增强一个已被丢弃的频段，等于白做。
  auto vBass = std::make_unique<dsp::VirtualBassNode>();
  vBass->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(vBass), kNodeVirtualBass);
  // 低音增强（2026-11-11，index 5）：M/S + EQ + 瞬态 + 次谐波，**设备无关**。
  // 与动态低音/虚拟低音同属「低频管家」，三者互斥（都往低频加能量，叠加会过度）。
  auto bassEnh = std::make_unique<dsp::BassEnhanceNode>();
  bassEnh->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(bassEnh), kNodeBassEnhance);
  auto comp = std::make_unique<dsp::CompressorNode>();
  comp->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(comp), kNodeCompressor);
  // 多频段压限（M8c，index 4）：与单段 Comp 同职能位（干声动态塑形），插 Comp 后
  // 卷积前；与单段互斥激活由 Kotlin 层状态机切换旁路，native 两节点独立
  auto multiband = std::make_unique<dsp::MultibandNode>();
  multiband->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(multiband), kNodeMultiband);
  auto conv = std::make_unique<dsp::ConvolverNode>();
  conv->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(conv), kNodeConvolver);
  // 算法混响（FDN）与卷积混响并列（index 5/6）；两者互斥激活由 Kotlin 侧切换旁路
  auto reverb = std::make_unique<dsp::ReverbNode>();
  reverb->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(reverb), kNodeReverb);
  auto width = std::make_unique<dsp::StereoWidthNode>();
  width->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(width), kNodeStereoWidth);
  auto outputGain = std::make_unique<dsp::GainNode>();
  outputGain->set_channel_count(channels);
  outputGain->set_id("output_gain");
  AddNodeAt(*graph_, std::move(outputGain), kNodeOutputGain);
  // 自定义脚本效果器：插在 OutputGain 之后、Limiter 之前，
  // 保证 Limiter 仍是绝对末级（用户脚本 boost 也会被限幅兜住）。
  // 后端为自研 SCVM；默认 bypass=true，由 Kotlin 侧 prefs 控制。
  auto scriptFx = std::make_unique<dsp::ScriptEffectNode>();
  scriptFx->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(scriptFx), kNodeScriptEffect);

  auto limiter = std::make_unique<dsp::LimiterNode>();
  limiter->set_channel_count(channels);
  AddNodeAt(*graph_, std::move(limiter), kNodeLimiter);

  // 装配完成汇总：**写诊断文件而不是只打日志**。
  // 本机 adb logcat 取不到任何数据，"没有 mismatch 日志"从来不是证据；
  // 文件是唯一可靠通道（`adb shell run-as com.jiangjie.Sonicraft
  // cat files/crash/autostart_diag.txt`）。
  __android_log_print(ANDROID_LOG_INFO, "SonicraftDSP",
                      "graph assembled: %d nodes, index order %s",
                      kNodeCount, g_node_index_mismatch ? "MISMATCH" : "OK");
  if (!diag_dir_.empty()) {
    FILE* f = std::fopen((diag_dir_ + "/autostart_diag.txt").c_str(), "a");
    if (f != nullptr) {
      std::fprintf(f, "graph assembled: %d nodes, index order %s\n",
                   kNodeCount, g_node_index_mismatch ? "MISMATCH!" : "OK");
      std::fclose(f);
    }
  }

  // H2：常驻重建 worker（构造启动、析构 join，生命周期与引擎同寿）
  rebuild_worker_ = std::thread([this] { RebuildWorkerLoop(); });
}

NativeAudioEngine::~NativeAudioEngine() {
  Stop();
  // H2：先停 worker 再析构成员——worker 线程解引用 self，必须在本函数结束前终止；
  // worker 若在 RestartOutputStream 持锁中，上方 Stop() 已先与其串行化
  worker_shutdown_.store(true);
  rebuild_cv_.notify_all();
  if (rebuild_worker_.joinable()) rebuild_worker_.join();
}

bool NativeAudioEngine::Start() {
  if (running_.load()) return true;
  // 持锁与 Stop()/RestartOutputStream() 互斥：stream_ 的所有读写点必须都在
  // restart_mutex_ 内（GetStats 亦持锁读），否则 JNI 侧 Watchdog 与流重建
  // 线程存在 use-after-free 竞态窗口。
  std::lock_guard<std::mutex> lock(restart_mutex_);
  return OpenAndStartStream();
}

bool NativeAudioEngine::OpenAndStartStream() {
  AAudioStreamBuilder* builder = nullptr;
  if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) return false;

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  // 显式声明媒体属性：缺省 usage 可能被 audio policy 归入无声流（flinger track G=-inf）
  // 使用 MEDIA：这是本机**唯一已验证**在蓝牙耳机下正常出声的配置
  // （2026-11-06 实测：MEDIA → 蓝牙出声、单声+音效；ACCESSIBILITY → 用户反馈扬声器出声，
  //  但该次实验期间播放器处于暂停状态，无法据此判定 usage 与路由的因果关系，
  //  故结论仅为“回到已验证可用的配置”，不宣称 usage 是扬声器输出的原因。）
  AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
  AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
  // 防回环：声明本输出流禁止被任何 playback capture 捕获。否则微信来消息触发
  // 输出流重建（OnStreamError/看门狗→RestartOutputStream）的竞态窗口里，仅靠
  // excludeUid 在部分 OEM 上会短暂失效，导致已处理的声音被自身捕获源再次捕获，
  // 形成正反馈双声（"来消息后才出现/加剧"的根因）。
  AAudioStreamBuilder_setAllowedCapturePolicy(builder, AAUDIO_ALLOW_CAPTURE_BY_NONE);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setSampleRate(builder, sample_rate_);
  AAudioStreamBuilder_setChannelCount(builder, channels_);
  AAudioStreamBuilder_setBufferCapacityInFrames(builder, max_frames_ * 2);
  AAudioStreamBuilder_setDataCallback(builder, &NativeAudioEngine::DataCallback, this);
  // 错误回调：微信来电等场景系统切换音频路由 → 流被 detach（error）→ 无此回调
  // 引擎即永久停摆（stats 冻结 + 全链静音）。detached 线程内重建（error 回调线程
  // 禁止操作流）。
  AAudioStreamBuilder_setErrorCallback(builder, &NativeAudioEngine::OnStreamError, this);

  const aaudio_result_t open = AAudioStreamBuilder_openStream(builder, &stream_);
  AAudioStreamBuilder_delete(builder);
  if (open != AAUDIO_OK || stream_ == nullptr) return false;

  // 输出缓冲余量（方案 A，2026-11-06）：
  // 实测 avgNs≈0.5ms 但 maxNs 偶发 5.88ms（卷积批次 IFFT + 调度抖动叠加），
  // 超过 192 帧(4ms) 的实时预算即 underrun 补零 —— 听感就是「偶尔爆一下」。
  // 这里把缓冲从默认（≈1~2 个 burst）提到 2 个 burst 且不少于 384 帧（≈8ms），
  // 用以吸收这类尖峰；代价是端到端延迟 +4ms 左右。
  {
    const int32_t burst = AAudioStream_getFramesPerBurst(stream_);
    const int user = desired_buffer_frames_.load(std::memory_order_relaxed);
    const int32_t want = (user > 0) ? user : ((burst > 0) ? (burst * 2) : 384);
    const int32_t got = AAudioStream_setBufferSizeInFrames(stream_, want);
    __android_log_print(ANDROID_LOG_INFO, "SonicraftDSP",
                        "buffer: burst=%d want=%d got=%d user=%d", burst, want, got, user);
  }

  write_pos_.store(0);
  read_pos_.store(0);
  running_.store(true);

  if (AAudioStream_requestStart(stream_) != AAUDIO_OK) {
    // 审计 H1：不得调 Stop()——本函数仅由 Start()/RestartOutputStream()
    // 在持有 restart_mutex_ 下调用，std::mutex 不可重入，Stop 内二次加锁 = 同线程
    // 永久死锁（此后 GetStats 全部阻塞 = 全链僵死零日志，疑为「22 分钟死寂」真身）。
    // 手动内联无锁清理：requestStart 失败的流未启动，无回调并发，直接 close 即可。
    AAudioStream_close(stream_);
    stream_ = nullptr;
    running_.store(false);
    return false;
  }
  // 绑核禁用诊断：原在回调线程首块写（fopen 违反自家实时契约），搬至启动
  // 路径（start/重建线程，非实时）。进程一次。
  static std::atomic<bool> bindDiagDone{false};
  if (!kBindBigCores && !bindDiagDone.exchange(true) && !diag_dir_.empty()) {
    FILE* f = std::fopen((diag_dir_ + "/autostart_diag.txt").c_str(), "a");
    if (f != nullptr) {
      std::fprintf(f, "ts=%ld audio callback bind disabled (power, EAS placed)\n",
                   static_cast<long>(time(nullptr)));
      std::fclose(f);
    }
  }
  return true;
}

void NativeAudioEngine::OnStreamError(AAudioStream* /*stream*/, void* user_data,
                                      aaudio_result_t error) {
  auto* self = static_cast<NativeAudioEngine*>(user_data);
  if (self->restarting_.exchange(true)) return;
  // 连续故障指数退避：50ms（路由切换单发）→ 100 → 400 → 1s → 5s（断链级循环：
  // capture 全零 → 空流被省电 disconnect → -899 循环，重建无法根治，降频保命，
  // 真正的恢复交给 Kotlin 采集断链保险丝）。30s 无错即重置为新故障序列。
  //
  // 调整：首档 200→50、次档 400→100。设备切换（插拔耳机/连断蓝牙）必然让
  // AAudio 流 DISCONNECTED(-899)，旧档位使切换中断至少 200ms、第二次 400ms，用户可感知。
  // 单发路由切换属于正常事件，理应最快恢复；只有连续失败才需要退避保护（≥3 起仍是 400ms）。
  const long now = static_cast<long>(time(nullptr));
  if (now - self->last_error_ts_.exchange(now) > 30) self->error_streak_.store(0);
  const int streak = self->error_streak_.fetch_add(1);
  int backoff_ms = 50;
  if (streak >= 1) backoff_ms = 100;
  if (streak >= 3) backoff_ms = 400;
  if (streak >= 5) backoff_ms = 1000;
  if (streak >= 8) backoff_ms = 5000;
  // H2（2026-11-03 审计）：backoff 等待与重建移入常驻 worker——原 detached 线程在
  // Kotlin stop→nativeDestroy→delete 后解引用 self = UAF。回调线程仅置位+notify
  // （错误回调线程禁止操作流/文件 IO，保持原约束）。
  self->pending_error_.store(static_cast<int>(error));
  self->pending_streak_.store(streak);
  self->pending_backoff_ms_.store(backoff_ms);
  self->rebuild_pending_.store(true);
  self->rebuild_cv_.notify_all();
}

void NativeAudioEngine::RebuildWorkerLoop() {
  while (true) {
    {
      std::unique_lock<std::mutex> lk(rebuild_cv_mutex_);
      rebuild_cv_.wait(lk, [&] {
        return worker_shutdown_.load() || rebuild_pending_.load();
      });
    }
    if (worker_shutdown_.load()) return;
    const int error = pending_error_.load();
    const int streak = pending_streak_.load();
    int backoff_ms = pending_backoff_ms_.load();
    // 诊断：与 Kotlin writeDiag 同文件（autostart_diag.txt）；目录由 Kotlin 注入
    if (!diag_dir_.empty()) {
      FILE* f = std::fopen((diag_dir_ + "/autostart_diag.txt").c_str(), "a");
      if (f != nullptr) {
        std::fprintf(f, "ts=%ld stream error=%d streak=%d, rebuilding output (backoff %dms)\n",
                     static_cast<long>(time(nullptr)), error, streak, backoff_ms);
        std::fclose(f);
      }
    }
    // backoff 分片睡眠：析构请求（worker_shutdown_）即时响应，join 不被长退避卡住
    while (backoff_ms > 0 && !worker_shutdown_.load()) {
      const int slice = std::min(backoff_ms, 100);
      std::this_thread::sleep_for(std::chrono::milliseconds(slice));
      backoff_ms -= slice;
    }
    if (worker_shutdown_.load()) return;
    if (!RestartOutputStream()) {
      // 失败延迟重试一次（同样分片，保留原重试语义）
      int retry_ms = pending_backoff_ms_.load();
      while (retry_ms > 0 && !worker_shutdown_.load()) {
        const int slice = std::min(retry_ms, 100);
        std::this_thread::sleep_for(std::chrono::milliseconds(slice));
        retry_ms -= slice;
      }
      if (!worker_shutdown_.load()) RestartOutputStream();
    }
    restarting_.store(false);
    rebuild_pending_.store(false);
  }
}

bool NativeAudioEngine::RestartOutputStream() {
  std::lock_guard<std::mutex> lock(restart_mutex_);
  if (stream_ != nullptr) {
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
  }
  running_.store(false);
  return OpenAndStartStream();
}

void NativeAudioEngine::Stop() {
  std::lock_guard<std::mutex> lock(restart_mutex_);
  running_.store(false);
  if (stream_ != nullptr) {
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
  }
}

void NativeAudioEngine::SetNoiseGateThreshold(float threshold) {
  gate_threshold_.store(threshold > 0.0f ? threshold : 0.0f, std::memory_order_relaxed);
}

void NativeAudioEngine::SetTestTone(bool enabled) {
  test_tone_.store(enabled, std::memory_order_relaxed);
}

void NativeAudioEngine::SetParam(int node_index, int param_id, float value) {
  if (graph_ != nullptr) {
    dsp::ParamCommand cmd{};
    cmd.node_index = node_index;
    cmd.param_id = param_id;
    cmd.value = value;
    cmd.is_bypass = false;
    graph_->PushCommand(cmd);
  }
}

void NativeAudioEngine::SetBypass(int node_index, bool bypass) {
  if (graph_ != nullptr) {
    dsp::ParamCommand cmd{};
    cmd.node_index = node_index;
    cmd.is_bypass = true;
    cmd.value = bypass ? 1.0f : 0.0f;
    graph_->PushCommand(cmd);
  }
}

void NativeAudioEngine::SetScriptSource(const std::string& source, int instance) {
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node == nullptr) return;
  node->set_script_source(source, instance);
  node->ReloadIfBypassed(instance);  // 旁路状态下立即生效；否则等下次 Prepare
  // 诊断：脚本编译结果非实时上报，便于真机/模拟器用 logcat 确认 SCVM 是否吃到脚本。
  const std::string err = node->GetError(instance);
  if (!err.empty()) {
    __android_log_print(ANDROID_LOG_WARN, "SonicraftDSP",
                        "ScriptEffect compile error: %s", err.c_str());
  } else {
    __android_log_print(ANDROID_LOG_INFO, "SonicraftDSP",
                        "ScriptEffect compiled ok, sliders mask=0x%02x",
                        static_cast<unsigned>(node->GetSliderMask(instance)));
  }
}

std::uint32_t NativeAudioEngine::GetScriptSliderMask(int instance) {
  if (graph_ == nullptr) return 0;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr ? node->GetSliderMask(instance) : 0;
}

void NativeAudioEngine::GetScriptSliderRanges(float* out16, int instance) {
  if (out16 == nullptr) return;
  if (graph_ != nullptr) {
    if (auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect))) {
      node->GetSliderRanges(out16, instance);
      return;
    }
  }
  for (int i = 0; i < 8; ++i) {
    out16[2 * i] = 0.0f;
    out16[2 * i + 1] = 1.0f;
  }
}

std::string NativeAudioEngine::GetScriptControls(int instance) {
  if (graph_ == nullptr) return "[]";
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr ? node->GetControlsJson(instance) : "[]";
}

void NativeAudioEngine::GetScriptSliderDefaults(float* out16, int instance) {
  if (out16 == nullptr) return;
  float vals[8];
  bool has[8];
  for (int i = 0; i < 8; ++i) {
    vals[i] = 0.0f;
    has[i] = false;
  }
  if (graph_ != nullptr) {
    if (auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect))) {
      node->GetSliderDefaults(vals, has, instance);
    }
  }
  for (int i = 0; i < 8; ++i) {
    out16[i] = vals[i];
    out16[8 + i] = has[i] ? 1.0f : 0.0f;
  }
}

void NativeAudioEngine::SetScriptLaneEnabled(int instance, bool enabled) {
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node != nullptr) node->set_lane_enabled(instance, enabled);
}

bool NativeAudioEngine::IsScriptLaneEnabled(int instance) {
  if (graph_ == nullptr) return false;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr && node->lane_enabled(instance);
}

void NativeAudioEngine::SetScriptLaneParallel(int instance, bool parallel) {
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node != nullptr) node->set_lane_parallel(instance, parallel);
}

bool NativeAudioEngine::IsScriptLaneParallel(int instance) {
  if (graph_ == nullptr) return false;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr && node->lane_parallel(instance);
}

void NativeAudioEngine::SetScriptLaneWet(int instance, float wet) {
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node != nullptr) node->set_lane_wet(instance, wet);
}

void NativeAudioEngine::SetScriptLaneOrder(int a, int b, int c) {
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node != nullptr) node->set_lane_order(a, b, c);
}

void NativeAudioEngine::GetScriptRmsLevels(float* out8) {
  if (out8 == nullptr) return;
  for (int i = 0; i < 8; ++i) out8[i] = 0.0f;
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node != nullptr) node->get_rms_levels(out8);
}

void NativeAudioEngine::SetScriptRackWet(float wet) {
  if (graph_ == nullptr) return;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  if (node != nullptr) node->set_rack_wet(wet);
}

int NativeAudioEngine::GetScriptLatency() {
  if (graph_ == nullptr) return 0;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr ? node->rack_latency() : 0;
}

float NativeAudioEngine::GetScriptRackWet() {
  if (graph_ == nullptr) return 1.0f;
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr ? node->rack_wet() : 1.0f;
}

std::string NativeAudioEngine::RunVirtualSurroundSelfTest() {
  if (!graph_) return "engine not ready";
  auto* node = dynamic_cast<dsp::VirtualSurroundNode*>(graph_->node_at(kNodeVirtualSurround));
  if (node == nullptr) return "virtual surround node missing";

  const auto rep = node->RunLocalizationSelfTest(true);

  char line[160];
  std::string out;
  // 报告只写 ASCII：JNI 侧对非 ASCII 字节统一替换为 '?'（防止 NewStringUTF 收到非法
  // UTF-8 直接 abort），若这里写中文，界面上会整片变成问号。
  out += rep.pass ? "result: PASS\n" : "result: FAIL\n";
  std::snprintf(line, sizeof(line), "ITD err %.2f -> %.2f samples\n",
                rep.itd_rms_error_before, rep.itd_rms_error_after);
  out += line;
  std::snprintf(line, sizeof(line), "ILD err %.2f -> %.2f dB\n",
                rep.ild_rms_error_before, rep.ild_rms_error_after);
  out += line;
  std::snprintf(line, sizeof(line), "handedness %s / symmetry %s / itd_scale %.3f / ild_trim %.3f\n",
                rep.handedness_ok ? "OK" : "FAIL",
                rep.symmetric_ok ? "OK" : "FAIL",
                rep.itd_scale, rep.ild_trim);
  out += line;
  // 声道标注：顺序与 LocalizationSelfTest::kAngles 一一对应
  //   kAngles = {-110, -30, 0, 180, 0, 30, 110}
  static const char* kChan[dsp::vs::LocalizationSelfTest::kAngleCount] = {
      "SL", "FL", "C", "CS", "LFE", "FR", "SR"};
  out += "chan  azim   ITD_me  ITD_th  ILD_me  ILD_tg  dev\n";
  out += "----- ------ -------- -------- ------- ------- -----\n";
  for (int i = 0; i < dsp::vs::LocalizationSelfTest::kAngleCount; ++i) {
    const auto& a = rep.angles[i];
    const float ditd = a.itd_measured - a.itd_expected;
    const float dild = a.ild_measured_db - a.ild_target_db;
    const bool ok = std::fabs(ditd) <= 1.5f && std::fabs(dild) <= 1.5f;
    std::snprintf(line, sizeof(line), "%-5s %6.0f %8.2f %8.2f %7.2f %7.2f  %s\n",
                  kChan[i], a.azimuth, a.itd_measured, a.itd_expected,
                  a.ild_measured_db, a.ild_target_db, ok ? "OK" : "CHECK");
    out += line;
  }
  return out;
}

std::string NativeAudioEngine::CheckScript(const std::string& source) {
  dsp::cvm::Program tmp;
  std::string err;
  if (dsp::cvm::Compile(source, tmp, err)) return std::string();
  return err;
}

std::string NativeAudioEngine::GetScriptError(int instance) {
  if (graph_ == nullptr) return {};
  auto* node = dynamic_cast<dsp::ScriptEffectNode*>(graph_->node_at(kNodeScriptEffect));
  return node != nullptr ? node->GetError(instance) : std::string();
}

std::string NativeAudioEngine::LoadImpulseResponse(const char* path) {
  if (graph_ == nullptr || path == nullptr) return "引擎未就绪";
  auto* conv = dynamic_cast<dsp::ConvolverNode*>(graph_->node_at(kNodeConvolver));
  if (conv == nullptr) return "卷积节点未找到";
  if (!conv->LoadImpulseResponseFromFile(path)) {
    return conv->LastLoadError().empty() ? "加载失败" : conv->LastLoadError();
  }
  return "";
}

void NativeAudioEngine::SetTailPartitionLimit(int max_parts) {
  if (graph_ == nullptr) return;
  auto* conv = dynamic_cast<dsp::ConvolverNode*>(graph_->node_at(kNodeConvolver));
  if (conv != nullptr) conv->SetTailPartitionLimit(max_parts);
}

bool NativeAudioEngine::GetMultibandMeters(float* out9) {
  if (graph_ == nullptr || out9 == nullptr) return false;
  // 节点索引 4 = 多频段压限（与 Kotlin AudioEngine.MULTIBAND_NODE_INDEX 对齐）
  auto* mb = dynamic_cast<dsp::MultibandNode*>(graph_->node_at(kNodeMultiband));
  if (mb == nullptr) return false;
  for (int b = 0; b < 3; ++b) {
    out9[b * 3 + 0] = mb->DiagnBandInPeak(b);
    out9[b * 3 + 1] = mb->DiagnBandGrDb(b);
    out9[b * 3 + 2] = mb->DiagnBandOutPeak(b);
  }
  return true;
}

bool NativeAudioEngine::GetCompressorMeters(float* out4) {
  if (graph_ == nullptr || out4 == nullptr) return false;
  // 节点索引 3 = 单段压缩器（与 Kotlin AudioEngine.COMPRESSOR_NODE_INDEX 对齐）
  auto* comp = dynamic_cast<dsp::CompressorNode*>(graph_->node_at(kNodeCompressor));
  if (comp == nullptr) return false;
  return comp->GetMeters(out4);
}

void NativeAudioEngine::SetTrimSickHead(bool on) {
  if (graph_ == nullptr) return;
  auto* conv = dynamic_cast<dsp::ConvolverNode*>(graph_->node_at(kNodeConvolver));
  if (conv != nullptr) conv->SetTrimSickHead(on);
}

void NativeAudioEngine::BindBigCoresOnce() {
  static std::atomic<bool> done{false};
  if (done.exchange(true)) return;
  if (!kBindBigCores) return;  // disabled 诊断行已搬至 OpenAndStartStream（非回调线程）
  // AAudio 回调线程绑核：解析全部核的 cpuinfo_max_freq，绑到最高频核集合
  // （Prime/大核簇）。低端机回调被摔到 A53/A55 小核是单块耗时劣化 2-3 倍的
  // 最大杀手；一次性 sched_setaffinity 比任何算法优化都直接。保守退出条件：
  // 核表解析不足 2 个、或最高频核超过 4 个（疑似全同频表，绑了白绑）。
  // 注意：sched_setaffinity 作用于当前线程，绑核必须发生在回调线程，
  // 无法搬出回调；仅 kBindBigCores=true 时才进入本路径。
  std::vector<int> big;
  long best = 0;
  int found = 0;
  for (int c = 0; c < 32; ++c) {
    char path[128];
    std::snprintf(path, sizeof(path),
                  "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
    FILE* f = std::fopen(path, "r");
    if (f == nullptr) continue;
    long khz = 0;
    if (std::fscanf(f, "%ld", &khz) == 1 && khz > 0) {
      ++found;
      if (khz > best) {
        best = khz;
        big.clear();
        big.push_back(c);
      } else if (khz == best) {
        big.push_back(c);
      }
    }
    std::fclose(f);
  }
  if (found < 2 || big.empty() || static_cast<int>(big.size()) > 4) return;
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int c : big) CPU_SET(c, &set);
  if (sched_setaffinity(0, sizeof(set), &set) == 0 && !diag_dir_.empty()) {
    // 与 Kotlin writeDiag 同文件，装机后验证绑核是否生效
    FILE* f = std::fopen((diag_dir_ + "/autostart_diag.txt").c_str(), "a");
    if (f != nullptr) {
      std::fprintf(f, "ts=%ld audio callback bound to:", static_cast<long>(time(nullptr)));
      for (int c : big) std::fprintf(f, " cpu%d", c);
      std::fprintf(f, "\n");
      std::fclose(f);
    }
  }
}

void NativeAudioEngine::GetEqSpectrum(jfloat* out, int n) {
  if (out == nullptr || n < 2048) return;
  auto* eq = graph_ != nullptr
      ? dynamic_cast<dsp::ParametricEqNode*>(graph_->node_at(kNodeEq))
      : nullptr;
  if (eq == nullptr) {
    for (int i = 0; i < n; ++i) out[i] = -60.0f;
    return;
  }
  eq->GetSpectrum(out, 1024, 0);        // 入口（EQ 前）
  eq->GetSpectrum(out + 1024, 1024, 1); // 出口（EQ 后）
}

void NativeAudioEngine::GetIrAnalysis(jfloat* out, int n) {
  if (out == nullptr || n < 1027) return;
  auto* conv = dynamic_cast<dsp::ConvolverNode*>(graph_->node_at(kNodeConvolver));
  if (conv == nullptr || !conv->UserIrLoaded()) {
    for (int i = 0; i < n; ++i) out[i] = 0.0f;
    return;
  }
  out[0] = conv->UserEnergy(0);
  out[1] = conv->UserEnergy(1);
  out[2] = static_cast<float>(conv->UserIrFrames());
  if (n >= 1028) out[1027] = static_cast<float>(conv->UserIrChannels());
  for (int i = 0; i < dsp::ConvolverNode::kWavPoints; ++i) {
    out[3 + i] = conv->UserWave(0)[i];
    out[3 + dsp::ConvolverNode::kWavPoints + i] = conv->UserWave(1)[i];
  }
  // ---- v4.1 布局（1556 floats）：病态检测报告 + 自动对齐信息 + 每声道频谱 ----
  // [1028] 布局版本=4 | [1029] 源采样率 | [1030] 前置静音 ms | [1031] 主脉冲 ms
  // [1032] R/L 能量比 dB | [1033] L/R 主脉冲错位 ms | [1034] NaN 数 | [1035] Inf 数
  // [1036] 峰值 | [1037] 削波占比 | [1038] 双声风险 0/1/2 | [1039] 谱声道数 N
  // [1040] 前置噪声 dB（≤-96 干净=设计型延迟；-96~-60 导出残留；仅报告口径，2026-10-27 起不再豁免裁剪）
  // [1041] 装载时自动裁除的前置 ms（0=未裁，UI「已自动对齐」横幅）
  // [1042..1042+N*128) 每声道 128 点 dB 谱（峰 0dB / 底 -100dB）
  // [1554] 湿声电平自动校准建议（批次 2，0.6~1.5；未装载=1）
  if (n >= 1556) {
    for (int i = 1028; i < 1556; ++i) out[i] = 0.0f;
    const dsp::IrInspectReport& rep = conv->UserIrReport();
    out[1028] = 4.0f;
    out[1029] = static_cast<float>(rep.source_rate);
    out[1030] = rep.pre_silence_ms;
    out[1031] = rep.main_pulse_ms;
    out[1032] = rep.lr_energy_ratio_db;
    out[1033] = rep.lr_pulse_skew_ms;
    out[1034] = static_cast<float>(rep.nan_count);
    out[1035] = static_cast<float>(rep.inf_count);
    out[1036] = rep.peak;
    out[1037] = rep.clip_ratio;
    out[1038] = static_cast<float>(rep.risk_level);
    out[1039] = static_cast<float>(rep.spectrum_channels);
    out[1040] = rep.pre_noise_db;
    out[1041] = rep.trimmed_ms;
    out[1554] = rep.suggested_wet_gain;  // 批次 2：湿声电平自动校准建议
    __android_log_print(ANDROID_LOG_INFO, "SonicraftIR",
        "report: sr=%d pre=%.1fms pre_noise=%.1fdB pulse=%.1fms risk=%d specN=%d trimmed=%.1fms",
        rep.source_rate, rep.pre_silence_ms, rep.pre_noise_db,
        rep.main_pulse_ms, rep.risk_level, rep.spectrum_channels, rep.trimmed_ms);
    for (int c = 0; c < rep.spectrum_channels && c < 4; ++c) {
      for (int i = 0; i < dsp::IrInspectReport::kSpecPoints; ++i) {
        out[1042 + c * dsp::IrInspectReport::kSpecPoints + i] = rep.spectrum[c][i];
      }
    }
  }
}

bool NativeAudioEngine::StartAudioDump(const char* dir, int seconds) {
  return dump_.Start(dir, seconds, sample_rate_, channels_);
}

void NativeAudioEngine::PushInput(const float* interleaved, int frames) {
  if (!running_.load() || frames <= 0) return;
  // 音频取证（M6.1）：旁观抓捕获输入，不影响数据流
  dump_.TapIn(interleaved, frames, channels_);
  const size_t cap = static_cast<size_t>(ring_capacity_frames_) * channels_;
  const size_t need = static_cast<size_t>(frames) * channels_;
  size_t w = write_pos_.load(std::memory_order_relaxed);
  const size_t r = read_pos_.load(std::memory_order_acquire);
  // 可用空间 = cap - (w - r)；不足则丢整块（防止覆盖未消费数据造成撕裂）
  if (cap - (w - r) < need) {
    push_drop_blocks_.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  for (size_t i = 0; i < need; ++i) {
    input_ring_[w % cap] = interleaved[i];
    ++w;
  }
  write_pos_.store(w, std::memory_order_release);
}

aaudio_data_callback_result_t NativeAudioEngine::DataCallback(AAudioStream* /*stream*/,
                                                              void* user_data, void* audio_data,
                                                              int32_t num_frames) {
  auto* self = static_cast<NativeAudioEngine*>(user_data);
  return self->OnData(audio_data, num_frames);
}

aaudio_data_callback_result_t NativeAudioEngine::OnData(void* audio_data, int32_t num_frames) {
  if (kBindBigCores) BindBigCoresOnce();  // 首次回调绑大核；disabled 时零开销零 IO（诊断已在启动路径写）
  {
    const int prev = ondata_max_frames_.load(std::memory_order_relaxed);
    if (num_frames > prev) ondata_max_frames_.store(num_frames, std::memory_order_relaxed);
  }
  const size_t cap = static_cast<size_t>(ring_capacity_frames_) * channels_;
  float* out = static_cast<float*>(audio_data);
  const int need = static_cast<int>(num_frames) * channels_;
  const size_t r0 = read_pos_.load(std::memory_order_relaxed);
  const size_t w = write_pos_.load(std::memory_order_acquire);
  const int available0 = static_cast<int>(w - r0);

  // 高水位防线：混响开启后单块峰值耗时 + 调度抖动偶发使消费落后，ring 积压冲高
  // 至满环 → PushInput 丢新块（输出缺块=爆音）。此处由消费端（唯一写 read_pos_
  // 者）受控跳过最旧数据，把水位钳回安全线（保留余量吸收抖动），等价时间轴前跳
  // 一次——比满环持续丢新块的可闻性更轻。
  constexpr int kHighWaterFrames = 6144;   // 触发钳制的积压阈值（128ms@48k）
  constexpr int kTargetWaterFrames = 4096;  // 钳回目标（85ms@48k），余量 42ms 抗抖动
  size_t r = r0;
  if (available0 / channels_ > kHighWaterFrames) {
    const size_t skip = static_cast<size_t>(available0 / channels_ - kTargetWaterFrames) * channels_;
    r += skip;
  }
  const int available = static_cast<int>(w - r);
  water_mark_frames_.store(available / channels_);

  int consumed = 0;
  if (available >= need) {
    for (int i = 0; i < need; ++i) {
      out[i] = input_ring_[r % cap];
      ++r;
    }
    consumed = need;
  } else {
    // 欠载：已采集部分填入，剩余补零（输出静音）——每一次都是可闻咔哒声
    underrun_blocks_.fetch_add(1, std::memory_order_relaxed);
    for (int i = 0; i < available; ++i) {
      out[i] = input_ring_[r % cap];
      ++r;
    }
    for (int i = available; i < need; ++i) out[i] = 0.0f;
    consumed = available;
  }
  read_pos_.store(r, std::memory_order_release);

  double sum = 0.0;
  for (int i = 0; i < consumed; ++i) sum += static_cast<double>(out[i]) * out[i];
  const double rms = consumed > 0 ? std::sqrt(sum / consumed) : 0.0;
  input_level_scaled_.store(static_cast<int>(rms * 1e6));

  // 空载旁路（省电，2026-10-27）：输入静音持续 4s → 跳过整条 DSP 链。
  // 实测 NX733J 无播放时引擎空转占 ~54% 单核大核（utime 差分），是耗电排行
  // 主因。此时输出=输入=数字静音，无需另行置零；任何超阈值块立即解冻（当块
  // 恢复全链，<1 块 ≈4ms 无感）。激活前混响尾已在全链运行中自然衰减完毕
  // （门限 4s > kMaxIrLen 2.73s 尾长上界），切换点不可闻。
  const bool bypass_idle = idle_bypass_.Update(static_cast<float>(rms), num_frames);
  if (graph_ != nullptr && !bypass_idle) {
    // AAudio 回调帧数可能超过 DSP 块上限（max_frames_），必须分块处理：
    // DspGraph 的 planar 缓冲按 max_frames_ 分配，整块传入会越界 → 输出数字噪声
    int offset = 0;
    while (offset < num_frames) {
      const int chunk = std::min(num_frames - offset, max_frames_);
      graph_->ProcessInterleaved(out + static_cast<size_t>(offset) * channels_, chunk);
      offset += chunk;
    }
  }

  // 噪声门限：输入 RMS 低于阈值时输出平滑静音（攻击 12ms / 释放 120ms，防咔哒）
  const float thr = gate_threshold_.load(std::memory_order_relaxed);
  if (thr > 0.0f && num_frames > 0) {
    const float target = rms < thr ? 0.0f : 1.0f;
    const float tau = target < gate_gain_ ? 0.012f : 0.120f;
    const float alpha = 1.0f - std::exp(-static_cast<float>(num_frames) / (tau * sample_rate_));
    gate_gain_ += (target - gate_gain_) * alpha;
    const float gain = gate_gain_;
    for (int i = 0; i < need; ++i) out[i] *= gain;
  }

  // 测试模式：输出替换为 440Hz 正弦（相位连续，幅度 0.2）——听到正弦即证明输出来自本引擎
  if (test_tone_.load(std::memory_order_relaxed)) {
    constexpr float kFreq = 440.0f;
    constexpr float kAmp = 0.2f;
    const float step = static_cast<float>(2.0 * M_PI * kFreq / sample_rate_);
    for (int i = 0; i < num_frames; ++i) {
      const float v = kAmp * std::sin(test_tone_phase_);
      for (int c = 0; c < channels_; ++c) {
        out[static_cast<size_t>(i) * channels_ + c] = v;
      }
      test_tone_phase_ += step;
      if (test_tone_phase_ > 2.0f * static_cast<float>(M_PI)) {
        test_tone_phase_ -= 2.0f * static_cast<float>(M_PI);
      }
    }
  }
  // 音频取证（M6.1）：旁观抓最终输出（噪声门/test tone 之后的 DAC 数据）
  dump_.TapOut(out, num_frames, channels_);
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void NativeAudioEngine::SetBufferFrames(int frames) {
  desired_buffer_frames_.store(frames > 0 ? frames : 0, std::memory_order_relaxed);
  // 与 Start/Stop 互斥：stream_ 的读必须持锁（同 GetStats 的理由，避免 use-after-free）
  std::lock_guard<std::mutex> lock(restart_mutex_);
  if (stream_ == nullptr) return;
  const int32_t burst = AAudioStream_getFramesPerBurst(stream_);
  const int32_t want = (frames > 0) ? frames : ((burst > 0) ? burst * 2 : 384);
  const int32_t got = AAudioStream_setBufferSizeInFrames(stream_, want);
  __android_log_print(ANDROID_LOG_INFO, "SonicraftDSP",
                      "SetBufferFrames: want=%d got=%d", want, got);
}

void NativeAudioEngine::GetStats(jlong* out, int n) {
  // 与 Start/Stop/RestartOutputStream 互斥：stream_ 读必须持锁，否则 Watchdog
  // 线程可能把已被错误回调线程 close 的指针交给 AAudioStream_getXRunCount
  // （use-after-free）。锁仅诊断线程竞争，250ms 一次无实时影响。
  std::lock_guard<std::mutex> lock(restart_mutex_);
  if (n < 6 || graph_ == nullptr) return;
  const dsp::PerfProbe& probe = graph_->probe();
  out[0] = probe.LastProcessNanos();
  out[1] = probe.MaxProcessNanos();
  out[2] = probe.TotalProcessNanos();
  out[3] = probe.ProcessCount();
  out[4] = water_mark_frames_.load();
  out[5] = input_level_scaled_.load();
  if (n >= 7) out[6] = ondata_max_frames_.load();
  if (n >= 8) out[7] = push_drop_blocks_.load();
  if (n >= 9) out[8] = underrun_blocks_.load();
  // AAudio 输出流自身 underrun 计数（DSP 超时→回调未按时返回→输出饿=可闻噪声）。
  // 内部 ring 的 underrun 探针测不到这一层，xRun 是后台噪声诊断的关键证据。
  if (n >= 10) {
    out[9] = (stream_ != nullptr) ? AAudioStream_getXRunCount(stream_) : 0;
  }
  // 空载旁路激活标志（诊断：watermark 日志可观测 DSP 是否在空转）
  if (n >= 11) out[10] = idle_bypass_.active() ? 1 : 0;
  // 卷积混响诊断三值：
  //   [11] Prepare 次数（增长=图被重建，预设会被重置为 Dry）
  //   [12] 当前预设（0=Dry，即无混响）
  //   [13] 湿声 RMS ×1e6（0 且预设非 Dry ⇒ 卷积状态坏死）
  if (n >= 14) {
    out[11] = 0;
    out[12] = 0;
    out[13] = 0;
    // 节点索引 5 = 卷积混响（与 Kotlin AudioEngine.CONVOLVER_NODE_INDEX 对齐）
    if (auto* cv = dynamic_cast<dsp::ConvolverNode*>(graph_->node_at(kNodeConvolver))) {
      out[11] = cv->DiagnPrepareCount();
      out[12] = cv->DiagnPreset();
      out[13] = static_cast<jlong>(cv->DiagnWetRms() * 1000000.0f);
    }
  }
  // 逐节点平均耗时（ns/次），索引 14..23 对应节点 0..9（2026-11-06 诊断）
  if (n >= 24) {
    const std::uint64_t cnt = probe.ProcessCount() > 0 ? probe.ProcessCount() : 1;
    for (int i = 0; i < 10; ++i) {
      out[14 + i] = static_cast<jlong>(graph_->node_nanos(i) / cnt);
    }
  }
}

}  // namespace sonicraft::engine
