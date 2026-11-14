/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 双端音频取证分析（M6.1 配套，2026-10-25）。
 *
 * 输入：app 端 AudioDump 抓下的两路交织 f32（捕获输入 audio_in_*.f32 与
 * 处理输出 audio_out_*.f32）。输出：文本指标报告，判定伪影归属——
 * 音源自带 vs 链路引入。
 *
 * 分析项：
 *   1) 基本量：时长/RMS/峰值/DC
 *   2) 带宽估计：平均谱噪声底 +20dB 判最高有效频带（MP3 码率指纹：128k≈16k 止）
 *   3) 谱差：输出-输入分频带 dB 差（链路新增噪声/截断）
 *   4) pre-echo：最陡瞬态前 20ms 底噪抬升比（音源自带在输入侧即可检出）
 *   5) 包络互相关对齐：估计链路延迟与相关度（深度处理下相关性低属预期）
 *   6) birdies 粗筛：2-8kHz 帧能量变异系数两端对比（参考指标）
 *
 * 用法：
 *   audio_forensics --in a.f32 --out b.f32 [--sr 48000]
 *   audio_forensics --selftest   （合成信号自证各判据）
 */

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "sonicraft/dsp/fft.hpp"

namespace {

constexpr double kPi = 3.14159265358979323846;

struct Stereo {
  std::vector<float> l;
  std::vector<float> r;
};

bool LoadF32(const char* path, Stereo* out) {
  std::FILE* f = std::fopen(path, "rb");
  if (f == nullptr) {
    std::printf("    ERROR 无法打开 %s\n", path);
    return false;
  }
  std::fseek(f, 0, SEEK_END);
  const long bytes = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  const size_t n = static_cast<size_t>(bytes) / sizeof(float);
  std::vector<float> raw(n);
  const size_t got = std::fread(raw.data(), sizeof(float), n, f);
  std::fclose(f);
  if (got != n) {
    std::printf("    ERROR %s 读取不完整\n", path);
    return false;
  }
  out->l.resize(n / 2);
  out->r.resize(n / 2);
  for (size_t i = 0; i < n / 2; ++i) {
    out->l[i] = raw[2 * i];
    out->r[i] = raw[2 * i + 1];
  }
  return true;
}

double Rms(const std::vector<float>& x, size_t b, size_t e) {
  if (e <= b) return 0.0;
  double s = 0.0;
  for (size_t i = b; i < e; ++i) s += static_cast<double>(x[i]) * x[i];
  return std::sqrt(s / static_cast<double>(e - b));
}

double Peak(const std::vector<float>& x) {
  double p = 0.0;
  for (float v : x) p = std::max(p, static_cast<double>(std::fabs(v)));
  return p;
}

double Dc(const std::vector<float>& x) {
  double s = 0.0;
  for (float v : x) s += v;
  return x.empty() ? 0.0 : s / static_cast<double>(x.size());
}

// Hann 窗平均谱（dB），N 点 × nframes 帧，中段取样
std::vector<double> AvgSpectrumDb(const std::vector<float>& x, int n, int nframes,
                                  sonicraft::dsp::FftPlan* fft) {
  std::vector<double> acc(static_cast<size_t>(n), 0.0);
  std::vector<float> re(static_cast<size_t>(n)), im(static_cast<size_t>(n), 0.0f);
  const size_t total = x.size();
  const size_t span = static_cast<size_t>(n) / 2;
  const size_t need = span * static_cast<size_t>(nframes - 1) + static_cast<size_t>(n);
  const size_t start = total > need ? (total - need) / 2 : 0;
  for (int k = 0; k < nframes; ++k) {
    const size_t off = start + span * static_cast<size_t>(k);
    if (off + static_cast<size_t>(n) > total) break;
    for (int i = 0; i < n; ++i) {
      const double w = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) / n);
      re[static_cast<size_t>(i)] = static_cast<float>(x[off + static_cast<size_t>(i)] * w);
      im[static_cast<size_t>(i)] = 0.0f;
    }
    fft->Forward(re.data(), im.data());
    for (int i = 0; i < n / 2; ++i) {
      const size_t j = static_cast<size_t>(i);
      acc[j] += std::sqrt(static_cast<double>(re[j]) * re[j] +
                          static_cast<double>(im[j]) * im[j]);
    }
  }
  std::vector<double> db(static_cast<size_t>(n) / 2, -200.0);
  for (size_t i = 0; i < db.size(); ++i) {
    db[i] = 20.0 * std::log10(acc[i] / nframes + 1e-12);
  }
  return db;
}

// 分频带平均谱级
std::vector<double> BandDb(const std::vector<double>& spec_db, int sr, int n, double bw_hz) {
  const int bands = std::max(1, static_cast<int>(sr / 2 / bw_hz));
  std::vector<double> out(static_cast<size_t>(bands), -200.0);
  for (int b = 0; b < bands; ++b) {
    const int i0 = std::max(1, static_cast<int>(b * bw_hz / sr * n));
    const int i1 = std::min(static_cast<int>(spec_db.size()),
                            static_cast<int>((b + 1) * bw_hz / sr * n));
    double s = 0.0;
    int cnt = 0;
    for (int i = i0; i < i1; ++i) {
      s += spec_db[static_cast<size_t>(i)];
      ++cnt;
    }
    out[static_cast<size_t>(b)] = cnt > 0 ? s / cnt : -200.0;
  }
  return out;
}

// 最高有效频带：从高频端往下找首个「级 > 低四分位底噪 + 20dB」的带
double EstimateBandwidth(const std::vector<double>& band_db, double bw_hz) {
  std::vector<double> sorted(band_db);
  std::sort(sorted.begin(), sorted.end());
  const double noise = sorted[sorted.size() / 4];
  for (int b = static_cast<int>(band_db.size()) - 1; b >= 2; --b) {
    if (band_db[static_cast<size_t>(b)] > noise + 20.0) {
      return (b + 1) * bw_hz;
    }
  }
  return 0.0;
}

// pre-echo：瞬态中心前 [−12,−1)ms RMS 与全局安静底噪之比（dB）。
// 参考用全信号 1ms RMS 轨迹的低 10 分位——音乐里总有安静段（歌曲间隙/气口），
// 编码泄漏段（块边界决定，几十 ms 量级）不会盖住全局分位。若音乐始终响，
// pre-echo 本被掩蔽不可闻，判据报低值与听感一致。
double PreEchoRatioAt(const std::vector<float>& x, int sr, size_t t_center, double ref) {
  const size_t w = static_cast<size_t>(sr) / 1000;
  if (t_center < 20 * w || ref <= 0.0) return 0.0;
  const double pre = Rms(x, t_center - 12 * w, t_center - 1 * w);
  return 20.0 * std::log10(pre / ref + 1e-12);
}

// 扫前 top_n 个最陡能量跳升点，pre-echo 比取中位数
double PreEchoScan(const std::vector<float>& x, int sr, int top_n) {
  const size_t w = static_cast<size_t>(sr) / 1000;
  const size_t total = x.size();
  if (total < 600 * w) return 0.0;
  // 全局安静底噪：1ms RMS 轨迹低 10 分位
  std::vector<double> traj;
  for (size_t b = 0; b + w <= total; b += w / 2) traj.push_back(Rms(x, b, b + w));
  std::vector<double> sorted(traj);
  std::sort(sorted.begin(), sorted.end());
  double ref = sorted[sorted.size() / 10];
  if (ref <= 1e-9) ref = sorted[sorted.size() / 2] * 0.01;  // 数字静音兜底
  std::vector<std::pair<double, size_t>> jumps;  // (跳升dB, 瞬态中心)
  double prev_r = Rms(x, 0, w);
  for (size_t b = w; b + w < total - 20 * w; b += w / 2) {
    const double r = Rms(x, b, b + w);
    if (prev_r > 1e-9) {
      const double jump = 20.0 * std::log10(r / prev_r + 1e-12);
      if (jump > 12.0 && b > 20 * w) jumps.emplace_back(jump, b + w);
    }
    prev_r = r;
  }
  std::sort(jumps.begin(), jumps.end(),
            [](const auto& a, const auto& b) { return a.first > b.first; });
  if (jumps.empty()) return 0.0;
  std::vector<double> ratios;
  for (int k = 0; k < top_n && k < static_cast<int>(jumps.size()); ++k) {
    ratios.push_back(PreEchoRatioAt(x, sr, jumps[static_cast<size_t>(k)].second, ref));
  }
  std::sort(ratios.begin(), ratios.end());
  return ratios[ratios.size() / 2];
}

// 包络（1ms RMS 轨迹）互相关：0..500ms，返回延迟样本与峰值滞后处相关系数
std::pair<long, double> EnvelopeXcorr(const std::vector<float>& a, const std::vector<float>& b,
                                      int sr) {
  const size_t w = static_cast<size_t>(sr) / 1000;
  auto envelope = [&](const std::vector<float>& x) {
    std::vector<double> e;
    for (size_t i = 0; i + w <= x.size(); i += w) e.push_back(Rms(x, i, i + w));
    return e;
  };
  const std::vector<double> ea = envelope(a);
  const std::vector<double> eb = envelope(b);
  if (ea.empty() || eb.empty()) return {0, 0.0};
  const int max_lag = std::min(500, static_cast<int>(std::min(ea.size(), eb.size())) - 2);
  double best_num = 0.0;
  long best_lag = 0;
  for (int lag = 0; lag <= max_lag; ++lag) {
    double s = 0.0;
    const size_t n = std::min(ea.size(), eb.size() - static_cast<size_t>(lag));
    for (size_t i = 0; i < n; ++i) {
      s += ea[i] * eb[static_cast<size_t>(lag) + i];
    }
    if (s > best_num) {
      best_num = s;
      best_lag = lag;
    }
  }
  double na = 0.0, nb = 0.0;
  const size_t n = std::min(ea.size(), eb.size() - static_cast<size_t>(best_lag));
  for (size_t i = 0; i < n; ++i) {
    na += ea[i] * ea[i];
    nb += eb[static_cast<size_t>(best_lag) + i] * eb[static_cast<size_t>(best_lag) + i];
  }
  const double corr = (na > 0.0 && nb > 0.0) ? best_num / std::sqrt(na * nb) : 0.0;
  return {static_cast<long>(best_lag) * static_cast<long>(w), corr};
}

// birdies 粗筛：2-8kHz 帧能量变异系数（std/mean）
double BirdieCv(const std::vector<float>& x, int sr, int n, sonicraft::dsp::FftPlan* fft) {
  const int i_lo = std::max(1, static_cast<int>(2000.0 / sr * n));
  const int i_hi = std::min(n / 2 - 1, static_cast<int>(8000.0 / sr * n));
  std::vector<double> frames;
  std::vector<float> re(static_cast<size_t>(n)), im(static_cast<size_t>(n));
  const size_t hop = static_cast<size_t>(n) / 2;
  for (size_t off = 0; off + static_cast<size_t>(n) < x.size(); off += hop) {
    for (int i = 0; i < n; ++i) {
      const double w = 0.5 - 0.5 * std::cos(2.0 * kPi * i / n);
      re[static_cast<size_t>(i)] = static_cast<float>(x[off + static_cast<size_t>(i)] * w);
      im[static_cast<size_t>(i)] = 0.0f;
    }
    fft->Forward(re.data(), im.data());
    double s = 0.0;
    for (int i = i_lo; i < i_hi; ++i) {
      const size_t j = static_cast<size_t>(i);
      s += static_cast<double>(re[j]) * re[j] + static_cast<double>(im[j]) * im[j];
    }
    frames.push_back(s);
  }
  if (frames.size() < 8) return 0.0;
  double mean = 0.0;
  for (double v : frames) mean += v;
  mean /= static_cast<double>(frames.size());
  double var = 0.0;
  for (double v : frames) var += (v - mean) * (v - mean);
  var /= static_cast<double>(frames.size());
  return mean > 0.0 ? std::sqrt(var) / mean : 0.0;
}

int RunAnalysis(const Stereo& in, const Stereo& out, int sr) {
  sonicraft::dsp::FftPlan fft;
  constexpr int kN = 8192;
  fft.Init(kN);

  std::printf("== 双端音频取证报告 ==\n");
  auto rep = [&](const char* tag, const std::vector<float>& x) {
    std::printf("  [%s] 时长 %.2fs  RMS %.5f  峰值 %.4f  DC %+.5f\n", tag,
                static_cast<double>(x.size()) / sr, Rms(x, 0, x.size()), Peak(x), Dc(x));
  };
  rep("IN.L", in.l);
  rep("IN.R", in.r);
  rep("OUT.L", out.l);
  rep("OUT.R", out.r);

  {
    const auto [lag, corr] = EnvelopeXcorr(in.l, out.l, sr);
    std::printf("对齐：输出滞后输入 %ld 样本（%.1f ms），包络相关系数 %.3f\n", lag,
                static_cast<double>(lag) / sr * 1000.0, corr);
    if (corr < 0.5) {
      std::printf("  注：相关性低 = 深度处理（混响/宽度）在起作用，以谱对比为准\n");
    }
  }

  const auto spec_in = AvgSpectrumDb(in.l, kN, 12, &fft);
  const auto spec_out = AvgSpectrumDb(out.l, kN, 12, &fft);
  constexpr double kBw = 500.0;
  const auto band_in = BandDb(spec_in, sr, kN, kBw);
  const auto band_out = BandDb(spec_out, sr, kN, kBw);
  std::printf("带宽估计（底噪+20dB 判据）：IN ≈ %.0f Hz   OUT ≈ %.0f Hz\n",
              EstimateBandwidth(band_in, kBw), EstimateBandwidth(band_out, kBw));
  std::printf("分频带谱差（OUT-IN，dB；正值 = 链路新增能量）：\n");
  double worst_add = -1e9, worst_cut = 1e9;
  int worst_add_b = 1, worst_cut_b = 1;
  for (size_t b = 1; b < band_in.size(); ++b) {
    const double d = band_out[b] - band_in[b];
    if (d > worst_add) { worst_add = d; worst_add_b = static_cast<int>(b); }
    if (d < worst_cut) { worst_cut = d; worst_cut_b = static_cast<int>(b); }
    if (b % 4 == 0) {
      std::printf("  %5.0f-%5.0f Hz: %+6.1f\n", b * kBw, (b + 1) * kBw, d);
    }
  }
  std::printf("  最大新增：%.0f Hz 处 %+.1f dB；最大损失：%.0f Hz 处 %+.1f dB\n",
              (worst_add_b + 0.5) * kBw, worst_add, (worst_cut_b + 0.5) * kBw, worst_cut);

  {
    const double pin = PreEchoScan(in.l, sr, 5);
    const double pout = PreEchoScan(out.l, sr, 5);
    std::printf("pre-echo 瞬前底噪抬升（中位，5 个最陡瞬态）：IN %+.1f dB  OUT %+.1f dB\n",
                pin, pout);
    if (pin > 10.0) {
      std::printf("  判读：IN 侧显著抬升 = 音源自带 pre-echo（编码伪影），非链路引入\n");
    } else if (pout - pin > 10.0) {
      std::printf("  判读：OUT 相对 IN 新增抬升 = 链路嫌疑（查卷积分块/缓冲）\n");
    } else {
      std::printf("  判读：两侧均无显著 pre-echo（<10dB）\n");
    }
  }

  {
    sonicraft::dsp::FftPlan fft4k;  // BirdieCv 缓冲 4096，须匹配 plan 长度
    fft4k.Init(4096);
    const double cv_in = BirdieCv(in.l, sr, 4096, &fft4k);
    const double cv_out = BirdieCv(out.l, sr, 4096, &fft4k);
    std::printf("birdies 粗筛（2-8kHz 帧能量 CV，参考指标）：IN %.2f  OUT %.2f\n", cv_in,
                cv_out);
    if (cv_out > cv_in * 1.5 && cv_out - cv_in > 0.1) {
      std::printf("  判读：OUT 闪烁显著高于 IN——混响尾波动也会推高 CV，结合听感判读\n");
    }
  }
  return 0;
}

int Selftest() {
  std::printf("== selftest：合成信号验证各判据 ==\n");
  constexpr int kSr = 48000;
  constexpr int kFrames = kSr * 3;  // 3s（帧数；交织 f32 文件 = 帧数×2 floats）
  // 内容（逻辑单声道，后按 [L,R]=[m,m] 交织写入）：
  //  t<1.5s     稳态 440/1k/3k 正弦 + -60dBFS 白噪底（连续谱，喂带宽判据）
  //  1.5~1.98s  静音段（纯 -60dBFS 噪底，喂全局分位参考）
  //  1.98~2.0s  预埋 -40dBFS 的 2.5kHz 副本（模拟瞬态前几十 ms 的编码泄漏）
  //  2.0s 起    满幅 2.5kHz 瞬态串 20ms（带宽内，不污染高频带判据），随后恢复稳态
  //  全带 >6.5kHz 截零（模拟低码率带宽截断指纹）
  std::vector<float> mono(static_cast<size_t>(kFrames), 0.0f);
  unsigned seed = 777;
  auto noise = [&seed]() {
    seed = seed * 1664525u + 1013904223u;
    return static_cast<float>(seed) / 2147483648.0f - 1.0f;
  };
  for (int i = 0; i < kFrames; ++i) {
    const double t = static_cast<double>(i) / kSr;
    double v = 0.0;
    if (t < 1.5 || t > 2.02) {
      v = 0.3 * std::sin(2 * kPi * 440.0 * t) + 0.2 * std::sin(2 * kPi * 1000.0 * t) +
          0.15 * std::sin(2 * kPi * 3000.0 * t);
    } else if (t >= 2.0) {
      v = 1.0 * std::sin(2 * kPi * 2500.0 * t);  // 瞬态串
    } else if (t >= 1.98) {
      v = 0.01 * std::sin(2 * kPi * 2500.0 * t);  // pre-echo：-40dBFS 副本（20ms）
    }
    v += 0.001 * noise();  // -60dBFS 连续噪底（全带；信号带高出 ~76dB → 判据取信号带）
    mono[static_cast<size_t>(i)] = static_cast<float>(v);
  }
  // 交织写入：in = [m,m]；out = in 延迟 1152 帧（24ms）+ -50dBFS 白噪
  std::vector<float> in(static_cast<size_t>(kFrames) * 2);
  std::vector<float> out(static_cast<size_t>(kFrames) * 2);
  constexpr long kDelay = 24 * kSr / 1000;  // 帧
  for (int i = 0; i < kFrames; ++i) {
    in[static_cast<size_t>(2 * i)] = mono[static_cast<size_t>(i)];
    in[static_cast<size_t>(2 * i + 1)] = mono[static_cast<size_t>(i)];
    const long src = i - kDelay;
    float v = src >= 0 ? mono[static_cast<size_t>(src)] : 0.0f;
    v += 0.003f * noise();  // -50dBFS
    out[static_cast<size_t>(2 * i)] = v;
    out[static_cast<size_t>(2 * i + 1)] = v;
  }
  const char* in_path = "forensics_st_in.f32";
  const char* out_path = "forensics_st_out.f32";
  auto write = [&](const char* path, const std::vector<float>& x) {
    std::FILE* f = std::fopen(path, "wb");
    if (f == nullptr) {
      std::printf("selftest 写 %s 失败\n", path);
      return;
    }
    std::fwrite(x.data(), sizeof(float), x.size(), f);
    std::fclose(f);
  };
  write(in_path, in);
  write(out_path, out);
  Stereo si, so;
  if (!LoadF32(in_path, &si) || !LoadF32(out_path, &so)) return 1;
  const int rc = RunAnalysis(si, so, kSr);
  std::printf("== selftest 预期 ==\n");
  std::printf("  延迟 1152±64 帧（24ms）、相关≈1\n");
  std::printf("  带宽 IN≈OUT≈3.5kHz（最高稳态 3k；噪底连续全带 → 判据停在信号带）\n");
  std::printf("  IN pre-echo 抬升 >10dB（-40dB 副本 vs -60dB 噪底），OUT 同样继承\n");
  std::printf("  谱差在信号带（≤3.2k）≈0，高频带随白噪差为正\n");
  return rc;
}

}  // namespace

int main(int argc, char** argv) {
  const char* in_path = nullptr;
  const char* out_path = nullptr;
  int sr = 48000;
  for (int i = 1; i < argc; ++i) {
    if (std::strcmp(argv[i], "--in") == 0 && i + 1 < argc) in_path = argv[++i];
    else if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) out_path = argv[++i];
    else if (std::strcmp(argv[i], "--sr") == 0 && i + 1 < argc) sr = std::atoi(argv[++i]);
    else if (std::strcmp(argv[i], "--selftest") == 0) return Selftest();
  }
  if (in_path == nullptr || out_path == nullptr) {
    std::printf("用法：audio_forensics --in a.f32 --out b.f32 [--sr 48000]\n");
    std::printf("      audio_forensics --selftest\n");
    return 1;
  }
  Stereo in, out;
  if (!LoadF32(in_path, &in) || !LoadF32(out_path, &out)) return 1;
  return RunAnalysis(in, out, sr);
}
