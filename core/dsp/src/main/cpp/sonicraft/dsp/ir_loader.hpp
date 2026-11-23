/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/wav_loader.hpp"

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace sonicraft::dsp {

/**
 * IR 文件统一加载入口（M4c.2 格式适配，仅限非实时线程调用）。
 *
 * 支持矩阵：
 *   .wav/.irs/.ir（RIFF 容器）→ 标准 WAV 解析（按字节头判定，扩展名无关）
 *   .pcm/.fir/.ir（裸数据）   → 三级内容嗅探：float32 二进制 → int16le 二进制 →
 *                               文本系数（逐 token strtof）；约定引擎采样率、
 *                               单声道（L=R 路径，裸格式无声道/采样率元信息）
 *   .iir                      → 明确拒绝：IIR 递归系数是无限长脉冲响应，
 *                               FIR 卷积架构数学上无法承载（转等效 FIR 需
 *                               截断+已知 biquad 结构，不做）
 *
 * 裸格式的采样率按 engine_rate 处理（调用方传入），即不做重采样。
 */
inline bool LoadIrFileAny(const std::string& path, int engine_rate, WavData* out,
                          std::string* err = nullptr) {
  auto set_err = [err](const char* msg) {
    if (err != nullptr) *err = msg;
    return false;
  };

  // 大小写不敏感的扩展名判定
  const std::size_t dot = path.find_last_of('.');
  std::string ext = dot == std::string::npos ? "" : path.substr(dot);
  for (auto& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  if (ext == ".iir") {
    return set_err("IIR 递归系数不适用于卷积混响（FIR 架构只能承载有限长脉冲响应）");
  }

  // 1) RIFF 容器（.wav/.irs/.ir 内为标准 WAV 时命中）
  {
    WavData w;
    if (LoadWavFile(path, &w)) {
      *out = w;
      return true;
    }
  }

  // 读全文件字节
  FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) return set_err("文件不存在或无法读取");
  std::fseek(f, 0, SEEK_END);
  const long file_size = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  if (file_size <= 0) {
    std::fclose(f);
    return set_err("文件为空");
  }
  std::vector<uint8_t> bytes(static_cast<std::size_t>(file_size));
  const std::size_t got = std::fread(bytes.data(), 1, bytes.size(), f);
  std::fclose(f);
  if (got == 0) return set_err("文件读取失败");
  bytes.resize(got);

  auto try_float32 = [&]() -> bool {
    if (bytes.size() < 4 || bytes.size() % 4 != 0) return false;
    const std::size_t n = bytes.size() / 4;
    std::vector<float> v(n);
    std::size_t finite_cnt = 0;
    double energy = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
      uint32_t u = static_cast<uint32_t>(bytes[i * 4]) |
                   (static_cast<uint32_t>(bytes[i * 4 + 1]) << 8) |
                   (static_cast<uint32_t>(bytes[i * 4 + 2]) << 16) |
                   (static_cast<uint32_t>(bytes[i * 4 + 3]) << 24);
      float fv;
      std::memcpy(&fv, &u, 4);
      // 幅度门 65536：须容纳「整数刻度未归一导出」的 f32 IR（int16 满刻度
      // float 化 = ±32767）。旧门 64 会拒掉这类真 f32 → 被 try_int16 误判
      // 接手（frames 翻倍、值全乱，2026-10-24 harness 7n 场景 5 坐实）。
      // isfinite/能量/非零计数三门不动，随机二进制仍被 NaN/Inf 门拦截。
      if (!std::isfinite(fv) || std::fabs(fv) > 65536.0f) return false;
      if (std::fabs(fv) > 1e-12f) ++finite_cnt;
      energy += static_cast<double>(fv) * fv;
      v[i] = fv;
    }
    if (finite_cnt < 4 || energy < 1e-12) return false;  // 全零/近零不是 IR
    out->ch[0] = std::move(v);
    out->ch[1].assign(out->ch[0].begin(), out->ch[0].end());  // mono → L=R
    out->channel_count = 1;
    out->sample_rate = engine_rate;
    out->frames = static_cast<int>(n);
    return true;
  };

  auto try_int16 = [&]() -> bool {
    if (bytes.size() < 2 || bytes.size() % 2 != 0) return false;
    const std::size_t n = bytes.size() / 2;
    std::vector<float> v(n);
    std::size_t nonzero = 0;
    double energy = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
      const int16_t s = static_cast<int16_t>(static_cast<uint16_t>(bytes[i * 2]) |
                                             (static_cast<uint16_t>(bytes[i * 2 + 1]) << 8));
      if (s != 0) ++nonzero;
      energy += static_cast<double>(s) * s;
      v[i] = static_cast<float>(s) / 32768.0f;
    }
    if (nonzero < 4 || energy < 1e-6) return false;
    out->ch[0] = std::move(v);
    out->ch[1].assign(out->ch[0].begin(), out->ch[0].end());
    out->channel_count = 1;
    out->sample_rate = engine_rate;
    out->frames = static_cast<int>(n);
    return true;
  };

  auto try_text = [&]() -> bool {
    // 文本系数：逐 token strtof（逗号/空白/换行分隔均可）
    std::vector<float> v;
    const char* p = reinterpret_cast<const char*>(bytes.data());
    const char* end = p + bytes.size();
    char* stop = nullptr;
    while (p < end && v.size() < 1000000) {
      // 跳过非数值前缀
      while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n' ||
                         *p == ',' || *p == ';' || *p == '[' || *p == ']')) ++p;
      if (p >= end) break;
      const float fv = std::strtof(p, &stop);
      if (stop == p) {
        // 非数值 token：跳过一个字符（容忍注释/标签，如 "a1=0.5"）
        ++p;
        continue;
      }
      if (!std::isfinite(fv)) return false;
      v.push_back(fv);
      p = stop;
    }
    if (v.size() < 4) return false;
    double energy = 0.0;
    for (float fv : v) energy += static_cast<double>(fv) * fv;
    if (energy < 1e-12) return false;
    out->ch[0] = std::move(v);
    out->ch[1].assign(out->ch[0].begin(), out->ch[0].end());
    out->channel_count = 1;
    out->sample_rate = engine_rate;
    out->frames = static_cast<int>(out->ch[0].size());
    return true;
  };

  // RIFF 头拦截（2026-10-27 P3 用例坐实）：文件声明 RIFF/WAVE 却解析失败
  // （畸形 chunk 头 / 不支持的编码）时，裸嗅探会把 "RIFF" 头字节当 int16
  // 样本装成几十帧噪声 IR（装载「成功」实为垃圾）——明确报错更正确。
  // 纯裸格式（.irs/.pcm/.fir）不会以 RIFF 开头，此拦截不影响合法裸数据。
  if (bytes.size() >= 12 && std::memcmp(bytes.data(), "RIFF", 4) == 0 &&
      std::memcmp(bytes.data() + 8, "WAVE", 4) == 0) {
    return set_err("WAV 文件损坏或编码不支持");
  }

  // 2) float32 二进制 → 3) int16le 二进制 → 4) 文本系数
  if (try_float32()) return true;
  if (try_int16()) return true;
  if (try_text()) return true;
  return set_err("无法识别的 IR 数据（尝试 float32/int16/文本系数均失败）");
}

}  // namespace sonicraft::dsp
