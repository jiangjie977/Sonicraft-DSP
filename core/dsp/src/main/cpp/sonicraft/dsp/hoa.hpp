/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 二阶高阶 Ambisonics（HOA N=2）编解码器。
 *
 * 通道：ACN 排序、N3D 归一化、9 通道
 *   0:W  1:Y  2:Z  3:X  4:V  5:T  6:R  7:S  8:U
 * 方位约定与项目一致：azDeg 正 = 右（顺时针），elDeg 正 = 上。
 *
 * 解码：12 个虚拟扬声器（8 水平 45° 等距 + 4 上方 35°），Moore-Penrose 伪逆，
 * 可选 max-rE 加窗（"前向聚焦"），用于压制解码旁瓣、把能量收拢到主方向。
 *
 * 全部计算在 Prepare / 参数变化时完成，实时路径只有乘加（Decode）。
 */
#pragma once

#include <cmath>

namespace sonicraft::dsp::vs {

class HoaEncoder {
 public:
  static constexpr int kOrder = 2;
  static constexpr int kChannels = 9;   // (N+1)^2

  /**
   * 声源方位 → 9 个球谐系数（N3D / ACN）。
   *
   * 标准 Ambisonics 的方位角 φ 逆时针为正，而本项目 az 顺时针为正，
   * 因此 φ = −az，代码里体现为 sin 项取负。
   */
  static void SourceCoeffs(float az_deg, float el_deg, float coeffs[kChannels]) {
    constexpr float kPi = 3.14159265358979323846f;
    constexpr float kSqrt3 = 1.7320508075688772f;
    const float az = az_deg * kPi / 180.0f;
    const float el = el_deg * kPi / 180.0f;
    const float caz = std::cos(az);
    const float saz = std::sin(az);
    const float sel = std::sin(el);
    const float cel = std::cos(el);
    const float c2a = std::cos(2.0f * az);
    const float s2a = std::sin(2.0f * az);
    const float c2e = cel * cel;          // cos²δ
    const float s2d = std::sin(2.0f * el);

    coeffs[0] = 1.0f;                              // W
    coeffs[1] = -saz * cel;                        // Y
    coeffs[2] = sel;                               // Z
    coeffs[3] = caz * cel;                         // X
    coeffs[4] = -kSqrt3 * s2a * c2e;               // V
    coeffs[5] = -kSqrt3 * saz * s2d;               // T
    coeffs[6] = 0.5f * (3.0f * sel * sel - 1.0f);  // R
    coeffs[7] = kSqrt3 * caz * s2d;                // S
    coeffs[8] = 0.5f * kSqrt3 * c2a * c2e;         // U
  }
};

class HoaDecoder {
 public:
  /** B 布局：8 水平（45° 等距）+ 4 上方（±45° / ±135°，仰角 35°） */
  static constexpr int kSpeakers = 12;
  static constexpr int kChannels = 9;

  static constexpr float kSpkAz[kSpeakers] = {
      0.0f, 45.0f, 90.0f, 135.0f, 180.0f, 225.0f, 270.0f, 315.0f,
      -45.0f, 45.0f, -135.0f, 135.0f,
  };
  static constexpr float kSpkEl[kSpeakers] = {
      0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
      35.0f, 35.0f, 35.0f, 35.0f,
  };

  /** max-rE 权重（N=2，Zotter/Frank 标准值），按阶数取 */
  static constexpr float kMaxRe[3] = {1.0f, 0.6455f, 0.2887f};

  /** ACN 通道 → 球谐阶数（0:W / 1..3:一阶 / 4..8:二阶） */
  static int OrderOf(int ch) { return (ch == 0) ? 0 : (ch <= 3 ? 1 : 2); }

  /**
   * 用 12 个扬声器方向初始化解码矩阵：D = Y · (YᵀY)⁻¹ · diag(maxRE)。
   *
   * @param focus 0~1 前向聚焦：0 = 不加窗（能量最平坦、声场最宽）
   *                       1 = 完整 max-rE（旁瓣最小、前向最集中）
   * @return 矩阵是否可逆（rank 必须为 9，否则退回经典路径）
   */
  bool Init(float focus) {
    float y[kSpeakers][kChannels];
    for (int j = 0; j < kSpeakers; ++j) {
      HoaEncoder::SourceCoeffs(kSpkAz[j], kSpkEl[j], y[j]);
    }

    // A = YᵀY（9×9）
    float a[kChannels][kChannels];
    for (int i = 0; i < kChannels; ++i) {
      for (int k = 0; k < kChannels; ++k) {
        float sum = 0.0f;
        for (int j = 0; j < kSpeakers; ++j) sum += y[j][i] * y[j][k];
        a[i][k] = sum;
      }
    }

    // 高斯-约当求逆（带部分主元）
    float inv[kChannels][kChannels];
    for (int i = 0; i < kChannels; ++i) {
      for (int k = 0; k < kChannels; ++k) inv[i][k] = (i == k) ? 1.0f : 0.0f;
    }
    for (int col = 0; col < kChannels; ++col) {
      int pivot = col;
      float best = std::fabs(a[col][col]);
      for (int r = col + 1; r < kChannels; ++r) {
        const float v = std::fabs(a[r][col]);
        if (v > best) {
          best = v;
          pivot = r;
        }
      }
      if (best < 1e-9f) {
        ready_ = false;  // 奇异：方向集没有张成 9 维
        return false;
      }
      if (pivot != col) {
        for (int k = 0; k < kChannels; ++k) {
          const float t = a[col][k];
          a[col][k] = a[pivot][k];
          a[pivot][k] = t;
          const float u = inv[col][k];
          inv[col][k] = inv[pivot][k];
          inv[pivot][k] = u;
        }
      }
      const float diag = a[col][col];
      for (int k = 0; k < kChannels; ++k) {
        a[col][k] /= diag;
        inv[col][k] /= diag;
      }
      for (int r = 0; r < kChannels; ++r) {
        if (r == col) continue;
        const float f = a[r][col];
        if (std::fabs(f) < 1e-12f) continue;
        for (int k = 0; k < kChannels; ++k) {
          a[r][k] -= f * a[col][k];
          inv[r][k] -= f * inv[col][k];
        }
      }
    }

    // 窗函数在 focus 上做线性插值：0 → 全 1（不平滑），1 → max-rE
    const float f01 = focus < 0.0f ? 0.0f : (focus > 1.0f ? 1.0f : focus);
    float window[3];
    for (int o = 0; o < 3; ++o) window[o] = 1.0f + f01 * (kMaxRe[o] - 1.0f);

    for (int j = 0; j < kSpeakers; ++j) {
      for (int k = 0; k < kChannels; ++k) {
        float s = 0.0f;
        for (int i = 0; i < kChannels; ++i) s += y[j][i] * inv[i][k];
        mat_[j][k] = s * window[OrderOf(k)];
      }
    }

    // 能量归一化：让「正前方单个声源」解码后的 12 路总能量 = 1。
    // 目的有两个：
    //   ① 与经典路径的响度可比（未归一化时只有 0.22，开启后会明显变小）；
    //   ② 「前向聚焦」只改变指向性而不改变响度 —— max-rE 加窗本身会削能量，
    //      不归一化的话用户把聚焦拉满就变成"变清晰但音量掉 3~6 dB"。
    {
      float ambi[kChannels];
      HoaEncoder::SourceCoeffs(0.0f, 0.0f, ambi);
      float energy = 0.0f;
      for (int j = 0; j < kSpeakers; ++j) {
        float s = 0.0f;
        for (int k = 0; k < kChannels; ++k) s += mat_[j][k] * ambi[k];
        energy += s * s;
      }
      if (energy > 1e-9f) {
        const float scale = 1.0f / std::sqrt(energy);
        for (int j = 0; j < kSpeakers; ++j) {
          for (int k = 0; k < kChannels; ++k) mat_[j][k] *= scale;
        }
      }
    }

    focus_ = f01;
    ready_ = true;
    return true;
  }

  /** 9 通道球谐 → 12 扬声器馈送 */
  void Decode(const float ambi[kChannels], float spk[kSpeakers]) const {
    for (int j = 0; j < kSpeakers; ++j) {
      const float* row = mat_[j];
      spk[j] = row[0] * ambi[0] + row[1] * ambi[1] + row[2] * ambi[2] +
               row[3] * ambi[3] + row[4] * ambi[4] + row[5] * ambi[5] +
               row[6] * ambi[6] + row[7] * ambi[7] + row[8] * ambi[8];
    }
  }

  bool Ready() const { return ready_; }
  float Focus() const { return focus_; }

 private:
  float mat_[kSpeakers][kChannels]{};
  float focus_ = -1.0f;
  bool ready_ = false;
};

}  // namespace sonicraft::dsp::vs
