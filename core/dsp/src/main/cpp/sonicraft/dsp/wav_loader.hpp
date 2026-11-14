/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace sonicraft::dsp {

/**
 * 最小 WAV/RIFF 解析器（M4c.2 IR 文件加载，非实时线程专用）。
 *
 *  - 支持 PCM16 / PCM24 / PCM32(int) / IEEE float32，1-4 声道
 *    （WAVE_FORMAT_EXTENSIBLE 按 SubFormat 实际格式识别）。
 *  - 输出统一为 [-1,1) float，最多 4 通道（4ch = HRTF/交叉矩阵 IR：
 *    通道排列无论 LL/LR/RL/RR 还是 LL/RL/LR/RR，第 1、4 声道均为对角直达项）。
 *  - 单声道仅填 ch[0]。
 *  - 不支持的数据（其他格式/压缩/声道数>4）返回 false，由调用方回退内置预设。
 */
struct WavData {
  std::vector<float> ch[4];
  int channel_count = 0;
  int sample_rate = 0;
  int frames = 0;
  // 数值垃圾统计（f32 清洗点计数；整数路径恒 0）。病态检测（ir_inspect）
  // 上 UI 警示用——HIFI最终版2.irs 实测 78 个 NaN + 3e38 巨值，用户有权在
  // 加载时看到「这个文件脏」，而不是只看到湿声莫名变小。
  int nan_count = 0;  // NaN 样本数（已置零）
  int inf_count = 0;  // Inf 样本数（已置零；>65536 巨值按垃圾置零但不单列）
};

inline bool LoadWavFile(const std::string& path, WavData* out) {
  if (out == nullptr) return false;
  FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) return false;

  auto read_u32 = [&f](uint32_t* v) { return std::fread(v, 4, 1, f) == 1; };
  auto read_u16 = [&f](uint16_t* v) { return std::fread(v, 2, 1, f) == 1; };
  auto tag_eq = [](const uint8_t* t, const char* s) {
    return t[0] == static_cast<uint8_t>(s[0]) && t[1] == static_cast<uint8_t>(s[1]) &&
           t[2] == static_cast<uint8_t>(s[2]) && t[3] == static_cast<uint8_t>(s[3]);
  };

  uint8_t hdr[12];
  if (std::fread(hdr, 1, 12, f) != 12 || !tag_eq(hdr, "RIFF") || !tag_eq(hdr + 8, "WAVE")) {
    std::fclose(f);
    return false;
  }

  uint16_t fmt_tag = 0, channels = 0, bits = 0;
  uint32_t sample_rate = 0;
  std::vector<uint8_t> data_bytes;
  bool have_fmt = false, have_data = false;

  while (!have_fmt || !have_data) {
    uint8_t chunk_hdr[8];
    if (std::fread(chunk_hdr, 1, 8, f) != 8) break;
    const uint32_t chunk_size =
        static_cast<uint32_t>(chunk_hdr[4]) | (static_cast<uint32_t>(chunk_hdr[5]) << 8) |
        (static_cast<uint32_t>(chunk_hdr[6]) << 16) | (static_cast<uint32_t>(chunk_hdr[7]) << 24);
    // P3（2026-10-27 自检）：chunk_size 直接来自文件头，畸形/恶意文件可声明
    // 4GB 级值——resize 直接 OOM 崩进程（runCatching 抓不住 native
    // bad_alloc），0xFFFFFFFF（流式未定长标记）还会令下方 fseek 回退死循环。
    // IR 文件合理上限远低于此（131072 帧 × 4ch × 4B ≈ 2MB），取 64MB 留足
    // 余量，超限按不支持格式拒绝。
    if (chunk_size > 64u * 1024u * 1024u) break;
    if (tag_eq(chunk_hdr, "fmt ")) {
      // 一次性读入 fmt chunk（最多 64 字节），字段全部按 chunk 内偏移取——
      // 禁止「部分读 + fseek 跳尾 + 再回读」的混排：WAVE_FORMAT_EXTENSIBLE 的
      // SubFormat 位于 chunk offset 24（此前实现 fseek 跳尾后 fread 读到的是
      // 下一个 chunk 头，fmt_tag 变垃圾且文件指针错位，导致 extensible 文件
      // 全部解析失败——奥菲斯 HRTF 4ch/24bit/44.1k 实测踩中）。
      uint8_t fmt[64] = {0};
      const uint32_t read_n = chunk_size < 64 ? chunk_size : 64;
      if (std::fread(fmt, 1, read_n, f) != read_n) break;
      if (chunk_size > read_n) std::fseek(f, static_cast<long>(chunk_size - read_n), SEEK_CUR);
      fmt_tag = static_cast<uint16_t>(fmt[0]) | static_cast<uint16_t>(fmt[1] << 8);
      channels = static_cast<uint16_t>(fmt[2]) | static_cast<uint16_t>(fmt[3] << 8);
      sample_rate = static_cast<uint32_t>(fmt[4]) | (static_cast<uint32_t>(fmt[5]) << 8) |
                    (static_cast<uint32_t>(fmt[6]) << 16) | (static_cast<uint32_t>(fmt[7]) << 24);
      bits = static_cast<uint16_t>(fmt[14]) | static_cast<uint16_t>(fmt[15] << 8);
      if (fmt_tag == 0xFFFE && chunk_size >= 40) {
        fmt_tag = static_cast<uint16_t>(fmt[24]) | static_cast<uint16_t>(fmt[25] << 8);
      }
      have_fmt = true;
    } else if (tag_eq(chunk_hdr, "data")) {
      data_bytes.resize(chunk_size);
      if (std::fread(data_bytes.data(), 1, chunk_size, f) != chunk_size) break;
      have_data = true;
    } else {
      std::fseek(f, static_cast<long>((chunk_size + 1u) & ~1u), SEEK_CUR);  // word 对齐跳过
    }
  }
  std::fclose(f);

  if (!have_fmt || !have_data || channels == 0 || channels > 4 || sample_rate == 0) return false;
  const bool is_float = (fmt_tag == 3);
  const int bytes_per_sample = bits / 8;
  if (bytes_per_sample <= 0 || (bits != 16 && bits != 24 && bits != 32)) return false;
  if (!is_float && bits == 32 && fmt_tag != 1) return false;

  const std::size_t frame_bytes = static_cast<std::size_t>(bytes_per_sample) * channels;
  const std::size_t total_frames = data_bytes.size() / frame_bytes;
  if (total_frames == 0) return false;

  out->sample_rate = static_cast<int>(sample_rate);
  out->frames = static_cast<int>(total_frames);
  out->channel_count = static_cast<int>(channels);
  for (int c = 0; c < static_cast<int>(channels); ++c) {
    out->ch[c].resize(total_frames);
  }

  for (std::size_t i = 0; i < total_frames; ++i) {
    const uint8_t* p = data_bytes.data() + i * frame_bytes;
    for (int c = 0; c < static_cast<int>(channels); ++c) {
      const uint8_t* q = p + static_cast<std::size_t>(c) * bytes_per_sample;
      float v = 0.0f;
      if (is_float && bits == 32) {
        uint32_t u = static_cast<uint32_t>(q[0]) | (static_cast<uint32_t>(q[1]) << 8) |
                     (static_cast<uint32_t>(q[2]) << 16) | (static_cast<uint32_t>(q[3]) << 24);
        float fv;
        std::memcpy(&fv, &u, 4);
        // 样本级数值清洗（2026-10-26「R 湿声冤案」根因修复）：社区 IR 文件
        // （HIFI最终版2.irs 实测）R 声道撒布 78 个 NaN + 若干 3e38 级巨大值。
        // NaN 原样进 IR 后，ConvolverNode 归一能量累加被污染成 NaN，g=0 门
        // （NaN>1e-18 恒 false）把整个 R 侧湿声静音——听感只剩 L 湿 + 双耳
        // 干声直通，正是用户「双声」投诉链的最后一环。巨大有限值同罪（把
        // g_R 压到 -200dB 级）。处置=置零该样本、保留其余内容：78/28446 个
        // 坏样本不该毙掉整个文件。幅度门 65536 与 ir_loader 裸 f32 探测同
        // 口径（容纳整数刻度未归一导出 ±32767，仅拦浮点垃圾）。
        // 置零同时计数（ir_inspect → UI 警示「文件含脏样本」）
        if (!std::isfinite(fv)) {
          if (std::isnan(fv)) ++out->nan_count; else ++out->inf_count;
          fv = 0.0f;
        } else if (std::fabs(fv) > 65536.0f) {
          fv = 0.0f;
        }
        v = fv;
      } else if (bits == 16) {
        const int16_t s = static_cast<int16_t>(static_cast<uint16_t>(q[0]) | (static_cast<uint16_t>(q[1]) << 8));
        v = static_cast<float>(s) / 32768.0f;
      } else if (bits == 24) {
        // 24 位小端拼入 32 位后高 8 位必须符号扩展：负样本（q[2] 最高位=1）
        // 不扩展会被读成 +1.0~+2.0 的正数（2026-10-24 用户自定义 IR「糊闷」
        // 根因——24bit 文件负半波整体翻正，卷积核被破坏）。PCM16/32 靠
        // int16_t/int32_t 转换的 wrap 语义天然正确，唯独 24 位需手动扩展。
        uint32_t u = static_cast<uint32_t>(q[0]) |
                     (static_cast<uint32_t>(q[1]) << 8) |
                     (static_cast<uint32_t>(q[2]) << 16);
        if ((u & 0x800000u) != 0u) u |= 0xFF000000u;
        v = static_cast<float>(static_cast<int32_t>(u)) / 8388608.0f;
      } else {  // int32 PCM
        const int32_t s = static_cast<int32_t>(static_cast<uint32_t>(q[0]) |
                                               (static_cast<uint32_t>(q[1]) << 8) |
                                               (static_cast<uint32_t>(q[2]) << 16) |
                                               (static_cast<uint32_t>(q[3]) << 24));
        v = static_cast<float>(s) / 2147483648.0f;
      }
      out->ch[c][i] = v;
    }
  }
  return true;
}

}  // namespace sonicraft::dsp
