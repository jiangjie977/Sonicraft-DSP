/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <atomic>
#include <cstdio>
#include <ctime>

namespace sonicraft::engine {

/**
 * 双端音频取证 dump（M6.1 诊断增强）。
 *
 * 用途：同时抓「捕获输入」（PushInput 旁观）与「处理输出」（AAudio 回调出口旁观）
 * 两路交织 f32 到 files/crash/，供宿主机工具对比分析伪影归属——
 * pre-echo / birdies / 带宽截断 / 噪声底抬升：音源自带 vs 链路引入。
 *
 * 线程模型（无锁）：
 *  - Start：Kotlin 非实时线程调用，fopen 文件对 + meta，发布后立即返回。
 *  - TapIn：只在采集线程（PushInput 路径）。
 *  - TapOut：只在 AAudio 输出回调线程。
 *  in/out 各自 FILE* 仅被单一线程触碰。发布协议：Start 先写 file 指针再
 *  release-store remain>0；tap acquire-load remain>0 才读指针——无并发窗口。
 */
class AudioDump {
 public:
  /** 打开输入/输出文件对，各抓 seconds 秒自动关闭。已在抓取中则幂等返回 true。 */
  bool Start(const char* dir, int seconds, int sample_rate, int channels) {
    if (seconds <= 0 || channels <= 0) return false;
    if (in_remain_.load(std::memory_order_acquire) > 0 ||
        out_remain_.load(std::memory_order_acquire) > 0) {
      return true;  // 已在抓取，不重启（避免撕裂文件对）
    }
    const long ts = static_cast<long>(time(nullptr));
    char path[512];
    channels_ = channels;
    std::snprintf(path, sizeof(path), "%s/audio_in_%ld.f32", dir, ts);
    std::FILE* fin = std::fopen(path, "wb");
    std::snprintf(path, sizeof(path), "%s/audio_out_%ld.f32", dir, ts);
    std::FILE* fout = std::fopen(path, "wb");
    std::snprintf(path, sizeof(path), "%s/audio_meta_%ld.txt", dir, ts);
    if (std::FILE* fm = std::fopen(path, "w"); fm != nullptr) {
      std::fprintf(fm, "sr=%d ch=%d seconds=%d ts=%ld\n", sample_rate, channels,
                   seconds, ts);
      std::fclose(fm);
    }
    if (fin == nullptr || fout == nullptr) {
      if (fin != nullptr) std::fclose(fin);
      if (fout != nullptr) std::fclose(fout);
      return false;
    }
    in_file_ = fin;
    out_file_ = fout;
    // 发布：先指針后计数（release），tap 侧 acquire 读计数
    const long cap =
        static_cast<long>(seconds) * static_cast<long>(sample_rate) * channels;
    out_remain_.store(cap, std::memory_order_release);
    in_remain_.store(cap, std::memory_order_release);
    return true;
  }

  bool Active() const {
    return in_remain_.load(std::memory_order_acquire) > 0 ||
           out_remain_.load(std::memory_order_acquire) > 0;
  }

  /** 采集线程调用：追加捕获输入（交织） */
  void TapIn(const float* interleaved, int frames, int channels) {
    Tap(in_file_, in_remain_, interleaved, frames, channels);
  }

  /** 输出回调线程调用：追加处理输出（交织） */
  void TapOut(const float* interleaved, int frames, int channels) {
    Tap(out_file_, out_remain_, interleaved, frames, channels);
  }

 private:
  static void Tap(std::FILE*& f, std::atomic<long>& remain,
                  const float* interleaved, int frames, int channels) {
    long r = remain.load(std::memory_order_acquire);
    if (r <= 0 || f == nullptr) return;
    long n = static_cast<long>(frames) * channels;
    if (n > r) n = r;  // 尾块截断
    std::fwrite(interleaved, sizeof(float), static_cast<size_t>(n), f);
    if (n == r) {  // 抓满：本线程自己关自己的文件
      std::fclose(f);
      f = nullptr;
      remain.store(0, std::memory_order_release);
    } else {
      remain.store(r - n, std::memory_order_release);
    }
  }

  std::FILE* in_file_ = nullptr;   // 仅采集线程触碰
  std::FILE* out_file_ = nullptr;  // 仅 AAudio 回调线程触碰
  std::atomic<long> in_remain_{0};   // 剩余 float 数（发布/关闭标志）
  std::atomic<long> out_remain_{0};
  int channels_ = 0;
};

}  // namespace sonicraft::engine
