/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cmath>
#include <vector>

namespace sonicraft::dsp {

/**
 * Radix-2 迭代 FFT（Cooley-Tukey DIT，就地）。
 *
 *  - 仅在非实时线程的 Prepare 阶段用于 IR 频域预计算；实时线程每块做
 *    forward+inverse 各一次（预分配缓冲，无分配、无锁）。
 *  - 预计算位反转表与 twiddle 表，Prepare 时构造一次。
 *  - v1 用复 FFT 处理实信号（虚部填 0），代码简单、开销可接受（N=16384 时
 *    真机单次 FFT 估算 <0.3ms，每块两次在 2.67ms 预算内；后续可换实数打包
 *    优化为 N/2 点）。
 */
class FftPlan {
 public:
  void Init(int n) {
    n_ = n;
    levels_ = 0;
    while ((1 << levels_) < n) ++levels_;
    rev_.assign(static_cast<std::size_t>(n), 0);
    for (int i = 0; i < n; ++i) {
      int r = 0;
      for (int b = 0; b < levels_; ++b) {
        r = (r << 1) | ((i >> b) & 1);
      }
      rev_[static_cast<std::size_t>(i)] = r;
    }
    // twiddle：单位根 e^{-2πik/n}，k = 0..n/2-1
    cos_.resize(static_cast<std::size_t>(n) / 2);
    sin_.resize(static_cast<std::size_t>(n) / 2);
    for (int k = 0; k < n / 2; ++k) {
      const double ang = -2.0 * 3.14159265358979323846 * static_cast<double>(k) / n;
      cos_[static_cast<std::size_t>(k)] = static_cast<float>(std::cos(ang));
      sin_[static_cast<std::size_t>(k)] = static_cast<float>(std::sin(ang));
    }
  }

  int Size() const { return n_; }

  /** 正向变换（频谱乘 e^{-jωt}）。re/im 长度须为 n。 */
  void Forward(float* re, float* im) const {
    Transform(re, im, false);
  }

  /** 逆变换（含 1/n 归一化）。re/im 长度须为 n。 */
  void Inverse(float* re, float* im) const {
    Transform(re, im, true);
    const float inv = 1.0f / static_cast<float>(n_);
    for (int i = 0; i < n_; ++i) {
      re[i] *= inv;
      im[i] *= inv;
    }
  }

 private:
  void Transform(float* re, float* im, bool conjugate) const {
    const int n = n_;
    // 位反转重排
    for (int i = 0; i < n; ++i) {
      const std::size_t r = rev_[static_cast<std::size_t>(i)];
      if (r > static_cast<std::size_t>(i)) {
        float t = re[i]; re[i] = re[r]; re[r] = t;
        t = im[i]; im[i] = im[r]; im[r] = t;
      }
    }
    // 蝶形：size = 当前子 DFT 长度
    const float sign = conjugate ? 1.0f : -1.0f;
    for (int size = 2; size <= n; size <<= 1) {
      const int half = size >> 1;
      const int step = n / size;  // twiddle 表索引步长
      for (int i = 0; i < n; i += size) {
        for (int j = 0; j < half; ++j) {
          const std::size_t tw = static_cast<std::size_t>(j * step);
          const float wr = cos_[tw];
          const float wi = sign * sin_[tw];
          const int a = i + j;
          const int b = a + half;
          const float xr = re[b] * wr - im[b] * wi;
          const float xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr;
          im[b] = im[a] - xi;
          re[a] += xr;
          im[a] += xi;
        }
      }
    }
  }

  int n_ = 0;
  int levels_ = 0;
  std::vector<int> rev_;
  std::vector<float> cos_;
  std::vector<float> sin_;
};

}  // namespace sonicraft::dsp
