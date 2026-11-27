/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * VBAP（矢量基幅度平移）解码器。
 *
 * 与 HOA 的分工：
 *   HOA  —— 把声场展开成球谐，再全局最小二乘还原到 12 只扬声器：声场连续，但定位偏糊；
 *   VBAP —— 把每个声源只分配到"包围它的 2~3 只扬声器"：定位准、能量集中，但声场不连续。
 * 两者输出都是 12 路扬声器馈送，后续 HRTF / 房间 / 早期反射完全共用，所以可以逐参数 A/B。
 *
 * 实现要点：
 *   1. 我们的 10 路声源方位是**固定的**（6 水平 + 4 上方 35°），所以矩阵只在 Init/改扩散时算一次，
 *      运行期就是一次 12×10 乘加 —— 比 HOA（编码 90 + 解码 108 MAC）还便宜。
 *   2. 水平声源走成对幅度平移（余弦律 + 能量归一化 Σg²=1）；
 *      上方声源走"水平对 × cosδ + 最近上方扬声器 × sinδ"的仰角混合；
 *      若声源与某只扬声器同向（夹角 < 1°），直接给该扬声器。
 *   3. spread 扩散：VBAP 只点亮 2 只扬声器，听感"硬"。给相邻扬声器加一点能量并重新归一化，
 *      0 = 纯 VBAP（最准、最硬），1 = 明显扩散（接近只做粗定位）。
 */
#pragma once

#include <cmath>

namespace sonicraft::dsp::vs {

class VbapDecoder {
 public:
  static constexpr int kSpeakers = 12;
  static constexpr int kMaxSources = 12;

  /**
   * 建立「声源 → 扬声器」增益矩阵。
   *
   * @param src_az/src_el 声源方位（度，az 正 = 右，与项目约定一致）
   * @param n_src         声源数（≤ kMaxSources）
   * @param spk_az/spk_el 扬声器方位（12 只）
   * @param spread        0~1 扩散量
   */
  bool Init(const float* src_az, const float* src_el, int n_src,
            const float* spk_az, const float* spk_el, float spread) {
    if (n_src <= 0 || n_src > kMaxSources) return false;
    n_src_ = n_src;
    for (int j = 0; j < kSpeakers; ++j) {
      spk_az_[j] = spk_az[j];
      spk_el_[j] = spk_el[j];
    }
    return Rebuild(src_az, src_el, spread);
  }

  /** 改扩散量后重建（用户拖滑条时调用，一次性 12×12 计算） */
  bool Rebuild(const float* src_az, const float* src_el, float spread) {
    spread_ = Clamp01(spread);
    for (int s = 0; s < n_src_; ++s) {
      float g[kSpeakers];
      GainsFor(src_az[s], src_el[s], g);
      ApplySpread(g);
      for (int j = 0; j < kSpeakers; ++j) {
        mat_[j][s] = g[j];
      }
      src_az_[s] = src_az[s];
      src_el_[s] = src_el[s];
    }
    ready_ = true;
    return true;
  }

  /** 重新用上次的声源方位与新的扩散量重建 */
  bool RebuildSpread(float spread) {
    if (n_src_ <= 0) return false;
    return Rebuild(src_az_, src_el_, spread);
  }

  /** 12 路扬声器馈送 = 矩阵 × 声源 */
  inline void Decode(const float* src, float* spk) const {
    for (int j = 0; j < kSpeakers; ++j) {
      const float* row = mat_[j];
      float acc = 0.0f;
      for (int s = 0; s < n_src_; ++s) acc += row[s] * src[s];
      spk[j] = acc;
    }
  }

  bool ready() const { return ready_; }
  float spread() const { return spread_; }

  /** 单方向的 VBAP 增益（供未来对象渲染 / 诊断用） */
  void GainsFor(float az, float el, float* out) const {
    for (int j = 0; j < kSpeakers; ++j) out[j] = 0.0f;

    // ① 与某只扬声器同向 → 直接给该扬声器
    int nearest = 0;
    float best = -2.0f;
    for (int j = 0; j < kSpeakers; ++j) {
      const float d = Dot(az, el, spk_az_[j], spk_el_[j]);
      if (d > best) {
        best = d;
        nearest = j;
      }
    }
    if (best > 0.9998f) {   // 夹角 < 1.2°
      out[nearest] = 1.0f;
      return;
    }

    // ② 水平面：成对幅度平移
    const float az_n = Norm360(az);
    const int n = kSpeakers;
    int i1 = -1, i2 = -1;
    for (int j = 0; j < n; ++j) {
      if (std::fabs(spk_el_[j]) > 5.0f) continue;   // 只用水平扬声器
      const float a0 = spk_az_[j];
      const float a1 = spk_az_[(j + 1) % n];
      if (std::fabs(spk_el_[(j + 1) % n]) > 5.0f) continue;
      if (InArc(az_n, a0, a1)) {
        i1 = j;
        i2 = (j + 1) % n;
        break;
      }
    }
    if (i1 < 0) {   // 兜底：取最近两只水平扬声器
      out[nearest] = 1.0f;
      return;
    }

    float g1 = 0.0f;
    float g2 = 0.0f;
    PairGains(az_n, spk_az_[i1], spk_az_[i2], &g1, &g2);

    // ③ 仰角：水平对按 cosδ、最近上方扬声器按 sinδ 混合
    const float el_r = el * kPi / 180.0f;
    const float c = std::cos(el_r);
    const float s = std::sin(el_r);
    out[i1] += g1 * c;
    out[i2] += g2 * c;
    if (std::fabs(el) > 5.0f && s > 0.0f) {
      // 上方四只里挑最近的两只（按方位距离加权）
      int up1 = -1, up2 = -1;
      float d1 = 1e9f, d2 = 1e9f;
      for (int j = 0; j < n; ++j) {
        if (spk_el_[j] < 10.0f) continue;
        float d = std::fabs(AngDiff(az_n, spk_az_[j]));
        if (d < d1) {
          d2 = d1;
          up2 = up1;
          d1 = d;
          up1 = j;
        } else if (d < d2) {
          d2 = d;
          up2 = j;
        }
      }
      if (up1 >= 0) {
        if (up2 >= 0 && d2 < 60.0f) {
          const float w1 = d2 / (d1 + d2);
          const float w2 = d1 / (d1 + d2);
          out[up1] += s * w1;
          out[up2] += s * w2;
        } else {
          out[up1] += s;
        }
      }
    }

    // ④ 能量归一化：Σg² = 1（VBAP 标准做法，避免不同方位响度跳变）
    NormalizeEnergy(out);
  }

 private:
  static constexpr float kPi = 3.14159265358979f;

  static float Clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

  static float Norm360(float a) {
    float r = std::fmod(a, 360.0f);
    if (r < 0.0f) r += 360.0f;
    return r;
  }

  /** 有符号角差，归一化到 (−180, 180] */
  static float AngDiff(float a, float b) {
    float d = Norm360(a - b);
    if (d > 180.0f) d -= 360.0f;
    return d;
  }

  static bool InArc(float x, float a0, float a1) {
    const float d0 = AngDiff(x, a0);
    const float span = AngDiff(a1, a0);
    if (span >= 0.0f) return d0 >= -0.001f && d0 <= span + 0.001f;
    return d0 <= 0.001f && d0 >= span - 0.001f;
  }

  /**
   * 相邻两只扬声器之间的 2D VBAP 增益（正弦律）：
   *     g1 = sin(θ2 − θs) / sin(θ2 − θ1)
   *     g2 = sin(θs − θ1) / sin(θ2 − θ1)
   * 然后能量归一化（Σg² = 1）。
   *
   * 注意不是余弦律：余弦律（cos 角度加权）只在两只扬声器夹角很小时才接近，
   * 45° 间隔下会把声像往中间拉（−30° 的源会被放到约 −24°），是最常见的实现错误。
   */
  static void PairGains(float src_az, float a1, float a2, float* g1, float* g2) {
    const float t1 = a1 * kPi / 180.0f;
    const float t2 = a2 * kPi / 180.0f;
    const float ts = src_az * kPi / 180.0f;
    const float den = std::sin(t2 - t1);
    if (std::fabs(den) < 1e-6f) {
      *g1 = 1.0f;
      *g2 = 0.0f;
      return;
    }
    float x = std::sin(t2 - ts) / den;
    float y = std::sin(ts - t1) / den;
    // 负增益会让声像翻到对侧，VBAP 只取正向解
    if (x < 0.0f) x = 0.0f;
    if (y < 0.0f) y = 0.0f;
    const float nrm = std::sqrt(x * x + y * y);
    if (nrm > 1e-6f) {
      x /= nrm;
      y /= nrm;
    } else {
      x = 1.0f;
      y = 0.0f;
    }
    *g1 = x;
    *g2 = y;
  }

  static float Dot(float az1, float el1, float az2, float el2) {
    const float a1 = az1 * kPi / 180.0f;
    const float e1 = el1 * kPi / 180.0f;
    const float a2 = az2 * kPi / 180.0f;
    const float e2 = el2 * kPi / 180.0f;
    return std::cos(e1) * std::cos(e2) * std::cos(a1 - a2) + std::sin(e1) * std::sin(e2);
  }

  static void NormalizeEnergy(float* g) {
    float e = 0.0f;
    for (int j = 0; j < kSpeakers; ++j) e += g[j] * g[j];
    if (e > 1e-9f) {
      const float k = 1.0f / std::sqrt(e);
      for (int j = 0; j < kSpeakers; ++j) g[j] *= k;
    }
  }

  /** 扩散：把每只扬声器的能量按 ½·spread 分给左右邻居（水平环）后重新归一化 */
  void ApplySpread(float* g) const {
    if (spread_ <= 0.0001f) return;
    float tmp[kSpeakers];
    for (int j = 0; j < kSpeakers; ++j) tmp[j] = g[j];
    // 水平 8 只构成一个环；上方 4 只各自与同方位的水平邻居互串
    for (int j = 0; j < 8; ++j) {
      const int prev = (j + 7) % 8;
      const int next = (j + 1) % 8;
      g[j] = tmp[j] + 0.5f * spread_ * (tmp[prev] + tmp[next]);
    }
    for (int j = 8; j < 12; ++j) {
      g[j] = tmp[j];
    }
    NormalizeEnergy(g);
  }

  float mat_[kSpeakers][kMaxSources]{};
  float spk_az_[kSpeakers]{};
  float spk_el_[kSpeakers]{};
  float src_az_[kMaxSources]{};
  float src_el_[kMaxSources]{};
  int n_src_ = 0;
  float spread_ = 0.0f;
  bool ready_ = false;
};

}  // namespace sonicraft::dsp::vs
