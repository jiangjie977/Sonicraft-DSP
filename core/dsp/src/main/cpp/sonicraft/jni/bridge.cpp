/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#include <jni.h>

#include <memory>
#include <string>

#include "sonicraft/dsp/convolver.hpp"
#include "sonicraft/dsp/dsp_graph.hpp"
#include "sonicraft/dsp/pass_through.hpp"
#include "sonicraft/engine/native_audio_engine.hpp"

namespace {

sonicraft::dsp::DspGraph* FromHandle(jlong handle) {
  return reinterpret_cast<sonicraft::dsp::DspGraph*>(handle);
}

}  // namespace

extern "C" {

// nativeCreate/nativeDestroy 为静态方法（Kotlin companion object 声明），签名第二参数为 jclass
JNIEXPORT jlong JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativeCreate(
    JNIEnv* /*env*/, jclass /*clazz*/, jint sample_rate, jint channels, jint max_frames) {
  auto* graph = new sonicraft::dsp::DspGraph(static_cast<int>(sample_rate), static_cast<int>(channels),
                                             static_cast<int>(max_frames));
  auto node = std::make_unique<sonicraft::dsp::PassThroughNode>();
  node->set_channel_count(static_cast<int>(channels));
  graph->AddNode(std::move(node));
  return reinterpret_cast<jlong>(graph);
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativeDestroy(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  delete FromHandle(handle);
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativeProcess(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray buffer, jint frames) {
  auto* graph = FromHandle(handle);
  if (graph == nullptr || buffer == nullptr) return;
  jfloat* data = env->GetFloatArrayElements(buffer, nullptr);
  if (data == nullptr) return;
  graph->ProcessInterleaved(reinterpret_cast<float*>(data), static_cast<int>(frames));
  // 0 = 拷贝回 Java 数组并释放
  env->ReleaseFloatArrayElements(buffer, data, 0);
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativePushCommand(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint node_index, jint param_id,
    jfloat value, jboolean is_bypass) {
  auto* graph = FromHandle(handle);
  if (graph == nullptr) return;
  sonicraft::dsp::ParamCommand cmd{};
  cmd.node_index = static_cast<int>(node_index);
  cmd.param_id = static_cast<int>(param_id);
  cmd.value = static_cast<float>(value);
  cmd.is_bypass = is_bypass == JNI_TRUE;
  graph->PushCommand(cmd);
}

JNIEXPORT jlongArray JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativeStats(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  auto* graph = FromHandle(handle);
  if (graph == nullptr) return nullptr;
  const sonicraft::dsp::PerfProbe& probe = graph->probe();
  jlong values[4] = {static_cast<jlong>(probe.LastProcessNanos()),
                     static_cast<jlong>(probe.MaxProcessNanos()),
                     static_cast<jlong>(probe.TotalProcessNanos()),
                     static_cast<jlong>(probe.ProcessCount())};
  jlongArray result = env->NewLongArray(4);
  if (result == nullptr) return nullptr;
  env->SetLongArrayRegion(result, 0, 4, values);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativeNodeCount(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* graph = FromHandle(handle);
  return graph == nullptr ? 0 : static_cast<jint>(graph->node_count());
}

// 卷积混响预设显示名（convolver.hpp X-macro 单源下发；顺序 = ConvPreset 枚举索引，
// 与 Kotlin REVERB_PRESET_* 常量对齐）。静态方法：不依赖图实例。
JNIEXPORT jobjectArray JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_DspGraphHandle_nativeConvolverPresetNames(
    JNIEnv* env, jclass /*clazz*/) {
  constexpr int kCount = static_cast<int>(sonicraft::dsp::ConvolverNode::kPresetCount);
  jclass str_cls = env->FindClass("java/lang/String");
  if (str_cls == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(kCount, str_cls, nullptr);
  if (result == nullptr) return nullptr;
  for (int i = 0; i < kCount; ++i) {
    jstring s = env->NewStringUTF(sonicraft::dsp::ConvolverNode::kPresetNames[i]);
    if (s == nullptr) return nullptr;
    env->SetObjectArrayElement(result, i, s);
    env->DeleteLocalRef(s);
  }
  return result;
}

// ---- NativeAudioEngine（内录接管实时引擎） ----

JNIEXPORT jlong JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeCreate(
    JNIEnv* env, jclass /*clazz*/, jint sample_rate, jint channels, jint max_frames,
    jstring diag_dir) {
  // 诊断目录由 Kotlin 注入（app 真实 filesDir/crash），替代历史硬编码包名路径
  const char* dir_chars = diag_dir != nullptr ? env->GetStringUTFChars(diag_dir, nullptr) : nullptr;
  std::string dir = dir_chars != nullptr ? dir_chars : "";
  if (dir_chars != nullptr) env->ReleaseStringUTFChars(diag_dir, dir_chars);
  auto* engine = new sonicraft::engine::NativeAudioEngine(
      static_cast<int>(sample_rate), static_cast<int>(channels), static_cast<int>(max_frames),
      dir);
  return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeIsRunning(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  return engine != nullptr && engine->IsRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeStart(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  return (engine != nullptr && engine->Start()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeStop(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->Stop();
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativePushInput(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray buffer, jint frames) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || buffer == nullptr) return;
  jfloat* data = env->GetFloatArrayElements(buffer, nullptr);
  if (data == nullptr) return;
  engine->PushInput(reinterpret_cast<float*>(data), static_cast<int>(frames));
  env->ReleaseFloatArrayElements(buffer, data, JNI_ABORT);  // 仅读取，无需写回
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetNoiseGate(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jfloat threshold) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetNoiseGateThreshold(threshold);
}

// M4c.2：加载外部 IR 文件（wav/.irs/.ir/pcm/fir，iir 拒绝）到卷积混响 slot 4。
// 非实时线程调用；返回 null = 成功，非 null = 失败原因（UI 直接显示）。
// 成功后由 Kotlin 侧 SetParam(preset=4) 切换，失败回退当前预设。
JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeLoadImpulseResponse(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring path) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || path == nullptr) {
    return env->NewStringUTF("引擎未就绪");
  }
  const char* p = env->GetStringUTFChars(path, nullptr);
  if (p == nullptr) return env->NewStringUTF("内存不足");
  const std::string err = engine->LoadImpulseResponse(p);
  env->ReleaseStringUTFChars(path, p);
  return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

// 尾分区上限（低端机负载分档）：须在 Start 前调用（Prepare 前生效）
JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetTailPartitionLimit(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint max_parts) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetTailPartitionLimit(static_cast<int>(max_parts));
}

// 多频段每段计量：out 长度须 >= 9，[band*3+0/1/2] = 入峰值 / GR(dB) / 出峰值。
// 返回 false = 引擎未就绪或无多频段节点，Kotlin 侧据此跳过本次刷新（不清零已有读数）。
JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeMultibandMeters(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  if (env->GetArrayLength(out) < 9) return JNI_FALSE;
  float buf[9];
  if (!engine->GetMultibandMeters(buf)) return JNI_FALSE;
  env->SetFloatArrayRegion(out, 0, 9, buf);
  return JNI_TRUE;
}

// 单段压缩器计量：out 长度需 ≥ 3 = {输入峰值, 增益衰减 dB, 输出峰值}。
// 返回 false = 引擎未就绪或无压缩器节点，Kotlin 侧跳过本次刷新（不清零已有读数）。
JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeCompressorMeters(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  // 4 个值：{输入峰值, GR dB, 输出峰值, GR 峰值保持 dB}
  if (env->GetArrayLength(out) < 4) return JNI_FALSE;
  float buf[4];
  if (!engine->GetCompressorMeters(buf)) return JNI_FALSE;
  env->SetFloatArrayRegion(out, 0, 4, buf);
  return JNI_TRUE;
}

// 病态脏前置自动对齐开关（方案 A；默认开，下次装载生效）
JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetTrimSickHead(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jboolean enabled) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetTrimSickHead(enabled == JNI_TRUE);
}

// 用户 IR 分析数据 v4.1（1556 floats；v4 1554 + 湿声自动校准建议）：
// [0..2] energyL/energyR/frames | [3..1026] L/R 包络各 512 | [1027] 源通道数
// [1028] 布局版本=4 | [1029] 源采样率 | [1030] 前置静音 ms | [1031] 主脉冲 ms
// [1032] R/L 能量比 dB | [1033] L/R 主脉冲错位 ms | [1034] NaN 数 | [1035] Inf 数
// [1036] 峰值 | [1037] 削波占比 | [1038] 双声风险 0/1/2 | [1039] 谱声道数 N
// [1040] 前置噪声 dB（≤-96 干净=设计型延迟；-96~-60 导出残留；仅报告口径，不再豁免裁剪）
// [1041] 装载时自动裁除的前置 ms（0=未裁，UI「已自动对齐」横幅）
// [1042..1042+N*128) 每声道 128 点 dB 谱（峰 0dB / 底 -100dB）
// [1554] 湿声电平自动校准建议（批次 2，0.6~1.5；未装载=1）
JNIEXPORT jfloatArray JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetIrAnalysis(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  jfloatArray arr = env->NewFloatArray(1556);
  if (engine == nullptr || arr == nullptr) return arr;
  std::vector<jfloat> tmp(1556, 0.0f);
  engine->GetIrAnalysis(tmp.data(), 1556);
  env->SetFloatArrayRegion(arr, 0, 1556, tmp.data());
  return arr;
}

// EQ 实时频谱双层 bin 直出（手绘区衬底）：2048 floats = 入口 1024 bin + 出口
// 1024 bin dB（-60..0）。FFT 在 UI 拉取线程（30fps）。
JNIEXPORT jfloatArray JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetEqSpectrum(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  jfloatArray arr = env->NewFloatArray(2048);
  if (engine == nullptr || arr == nullptr) return arr;
  std::vector<jfloat> tmp(2048, -60.0f);
  engine->GetEqSpectrum(tmp.data(), 2048);
  env->SetFloatArrayRegion(arr, 0, 2048, tmp.data());
  return arr;
}

// 重建 AAudio 输出流（看门狗兜底：错误回调重建失败时由 Kotlin 层再触发）
JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeRestartOutput(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  return engine != nullptr && engine->RestartOutputStream() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetTestTone(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jboolean enabled) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetTestTone(enabled == JNI_TRUE);
}

// M6.1 音频取证：开始双端抓 PCM（audio_in_*/audio_out_*.f32 写 dir）。
// 非实时线程调用；返回 true = 已启动（或已在抓取中）。
JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeDumpAudio(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring dir, jint seconds) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || dir == nullptr) return JNI_FALSE;
  const char* p = env->GetStringUTFChars(dir, nullptr);
  if (p == nullptr) return JNI_FALSE;
  const jboolean ok =
      engine->StartAudioDump(p, static_cast<int>(seconds)) ? JNI_TRUE : JNI_FALSE;
  env->ReleaseStringUTFChars(dir, p);
  return ok;
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetParam(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint node_index, jint param_id,
    jfloat value) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetParam(static_cast<int>(node_index),
                                          static_cast<int>(param_id),
                                          static_cast<float>(value));
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetBypass(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint node_index, jboolean bypass) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetBypass(static_cast<int>(node_index), bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScript(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring source) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || source == nullptr) return;
  const char* s = env->GetStringUTFChars(source, nullptr);
  if (s != nullptr) {
    engine->SetScriptSource(std::string(s));
    env->ReleaseStringUTFChars(source, s);
  }
}

JNIEXPORT jint JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptSliderMask(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  return engine != nullptr ? static_cast<jint>(engine->GetScriptSliderMask()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptSliderRanges(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  float buf[16];
  engine->GetScriptSliderRanges(buf);
  env->SetFloatArrayRegion(out, 0, 16, buf);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptSliderDefaults(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  float buf[16];
  engine->GetScriptSliderDefaults(buf);
  env->SetFloatArrayRegion(out, 0, 16, buf);
  return JNI_TRUE;
}

// ---- 多 lane（instance）版本。旧符号保留，行为等价于 instance=0 ----
JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScriptLaneOrder(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint a, jint b, jint c) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr) return;
  engine->SetScriptLaneOrder(static_cast<int>(a), static_cast<int>(b), static_cast<int>(c));
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScriptRackWet(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jfloat wet) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr) return;
  engine->SetScriptRackWet(static_cast<float>(wet));
}

JNIEXPORT jint JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptLatency(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  return engine != nullptr ? static_cast<jint>(engine->GetScriptLatency()) : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptRackWet(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  return engine != nullptr ? static_cast<jfloat>(engine->GetScriptRackWet()) : 1.0f;
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptRmsLevels(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  float buf[12] = {};
  engine->GetScriptRmsLevels(buf);
  env->SetFloatArrayRegion(out, 0, 12, buf);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScriptLaneParallel(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint instance, jboolean parallel) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr) return;
  engine->SetScriptLaneParallel(static_cast<int>(instance), parallel != JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScriptLaneWet(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint instance, jfloat wet) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr) return;
  engine->SetScriptLaneWet(static_cast<int>(instance), static_cast<float>(wet));
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScriptI(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint instance, jstring source) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || source == nullptr) return;
  const char* raw = env->GetStringUTFChars(source, nullptr);
  if (raw == nullptr) return;
  engine->SetScriptSource(std::string(raw), static_cast<int>(instance));
  env->ReleaseStringUTFChars(source, raw);
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetScriptLaneEnabled(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint instance, jboolean enabled) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr) return;
  engine->SetScriptLaneEnabled(static_cast<int>(instance), enabled != JNI_FALSE);
}

JNIEXPORT jint JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptSliderMaskI(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint instance) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr) return 0;
  return static_cast<jint>(engine->GetScriptSliderMask(static_cast<int>(instance)));
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptSliderRangesI(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint instance, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  float buf[16] = {};
  engine->GetScriptSliderRanges(buf, static_cast<int>(instance));
  env->SetFloatArrayRegion(out, 0, 16, buf);
  return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptControlsI(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint instance) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  const std::string json =
      engine != nullptr ? engine->GetScriptControls(static_cast<int>(instance)) : std::string("[]");
  return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptErrorI(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint instance) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  const std::string raw =
      engine != nullptr ? engine->GetScriptError(static_cast<int>(instance)) : std::string();
  std::string safe;
  safe.reserve(raw.size());
  for (unsigned char c : raw) safe += (c < 0x80) ? static_cast<char>(c) : '?';
  return env->NewStringUTF(safe.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptSliderDefaultsI(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint instance, jfloatArray out) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine == nullptr || out == nullptr) return JNI_FALSE;
  float buf[16] = {};
  engine->GetScriptSliderDefaults(buf, static_cast<int>(instance));
  env->SetFloatArrayRegion(out, 0, 16, buf);
  return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeCheckScript(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring source) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  std::string err;
  if (engine != nullptr && source != nullptr) {
    const char* raw = env->GetStringUTFChars(source, nullptr);
    if (raw != nullptr) {
      err = engine->CheckScript(std::string(raw));
      env->ReleaseStringUTFChars(source, raw);
    }
  }
  // 与 GetScriptError 一致：只回传 ASCII，避免非法 UTF-8 触发 abort
  std::string safe;
  safe.reserve(err.size());
  for (unsigned char c : err) safe += (c < 0x80) ? static_cast<char>(c) : '?';
  return env->NewStringUTF(safe.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptControls(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  const std::string json = engine != nullptr ? engine->GetScriptControls() : std::string("[]");
  return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeVsSelfTest(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  const std::string raw = engine != nullptr ? engine->RunVirtualSurroundSelfTest() : std::string();
  // 与其他字符串返回一致：非 ASCII 一律替换，避免 NewStringUTF 收到非法 UTF-8
  std::string safe;
  safe.reserve(raw.size());
  for (unsigned char c : raw) safe += (c < 0x80) ? static_cast<char>(c) : '?';
  return env->NewStringUTF(safe.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeGetScriptError(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  const std::string raw = engine != nullptr ? engine->GetScriptError() : std::string();
  // 兜底：把非 ASCII 字节替换掉，避免 NewStringUTF 收到非法 UTF-8 触发 abort
  std::string err;
  err.reserve(raw.size());
  for (unsigned char c : raw) err += (c < 0x80) ? static_cast<char>(c) : '?';
  return env->NewStringUTF(err.c_str());
}

JNIEXPORT jlongArray JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeStats(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  // 11 元素：原 9 元素时 native 的 n>=10 分支（xRun/旁路标志）永假——xRunBlocks
  // 线上恒 0 的断档，顺带扩到 11 修通
  // 24 = 11 基础 + 3 卷积诊断 + 10 逐节点平均耗时
  constexpr int kStatsLen = 24;
  jlongArray result = env->NewLongArray(kStatsLen);
  if (result == nullptr) return nullptr;
  jlong values[kStatsLen] = {0};
  if (engine != nullptr) engine->GetStats(values, kStatsLen);
  env->SetLongArrayRegion(result, 0, kStatsLen, values);
  return result;
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeSetBufferFrames(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint frames) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  if (engine != nullptr) engine->SetBufferFrames(static_cast<int>(frames));
}

JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_core_dsp_NativeAudioEngine_nativeDestroy(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* engine = reinterpret_cast<sonicraft::engine::NativeAudioEngine*>(handle);
  delete engine;
}

// ---- 崩溃自捕获：native signal handler（写文件，绕开 logcat） ----
#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <unwind.h>
#include <dlfcn.h>

// 只在安装时保存路径字符串；fd 在**崩溃那一刻**才打开。
//
// 2026-11-24 修复：旧实现安装时就 open(O_TRUNC)，于是每次 App 启动都创建一个
// 0 字节 crash_native_*.txt（真机上积累了 6 个空文件），而真崩溃时反而可能
// 因为 fd 已被关闭/进程被杀而写不出栈。改为「安装存路径、崩溃时 append」。
static int g_crash_fd = -1;
static char g_crash_path[512] = {0};

struct CrashUnwindArg {
  int fd;
  int count;
};

static _Unwind_Reason_Code CrashUnwindCb(struct _Unwind_Context* ctx, void* arg) {
  auto* a = static_cast<CrashUnwindArg*>(arg);
  uintptr_t pc = _Unwind_GetIP(ctx);
  if (pc == 0) return _URC_NO_REASON;
  char buf[320];
  Dl_info info;
  int n;
  if (dladdr(reinterpret_cast<void*>(pc), &info) != 0 && info.dli_fname != nullptr) {
    uintptr_t off = pc - reinterpret_cast<uintptr_t>(info.dli_fbase);
    n = snprintf(buf, sizeof(buf), "#%d %s 0x%lx\n",
                 a->count++, info.dli_fname, (unsigned long)off);
  } else {
    n = snprintf(buf, sizeof(buf), "#%d 0x%lx\n", a->count++, (unsigned long)pc);
  }
  if (n > 0) write(a->fd, buf, static_cast<size_t>(n));
  return _URC_NO_REASON;
}

static void CrashSigHandler(int sig, siginfo_t* info, void* /*ucontext*/) {
  // 约束：handler 内只做 async-signal-safe 调用（open/write/fsync/close/_exit）。
  if (g_crash_path[0] != '\0') {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
      char head[224];
      int hn = snprintf(head, sizeof(head),
                        "\n=== native crash sig=%d addr=%p pid=%d tid=%d ===\n",
                        sig,
                        info != nullptr ? info->si_addr : nullptr,
                        static_cast<int>(getpid()),
                        static_cast<int>(gettid()));
      if (hn > 0) write(fd, head, static_cast<size_t>(hn));
      CrashUnwindArg ua{fd, 0};
      _Unwind_Backtrace(CrashUnwindCb, &ua);
      const char* tail = "=== end ===\n";
      write(fd, tail, strlen(tail));
      fsync(fd);
      close(fd);
    }
  }
  // 恢复默认动作后直接退出：不用 signal()/raise()（非 async-signal-safe，
  // 且可能被再次捕获造成递归）。
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = SIG_DFL;
  sigemptyset(&sa.sa_mask);
  sigaction(sig, &sa, nullptr);
  _exit(128 + sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jiangjie_Sonicraft_CrashCapture_nativeInstall(JNIEnv* env, jclass,
                                                      jstring path) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  if (p != nullptr) {
    snprintf(g_crash_path, sizeof(g_crash_path), "%s", p);
    env->ReleaseStringUTFChars(path, p);
  }

  // 备用栈：栈溢出型 SIGSEGV 时，handler 在默认栈上无处可跑。
  static char alt_stack[SIGSTKSZ * 4];
  stack_t ss;
  ss.ss_sp = alt_stack;
  ss.ss_size = sizeof(alt_stack);
  ss.ss_flags = 0;
  sigaltstack(&ss, nullptr);

  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = CrashSigHandler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);
  const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGSYS};
  for (int s : kSignals) sigaction(s, &sa, nullptr);
}

}  // extern "C"
