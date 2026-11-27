/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 双耳渲染引擎：一套接口，两个后端。
 *
 *   后端 A（model = 0）：ParametricHrtf —— 参数化模型（ITD + ILD + 陷波），算力极低
 *   后端 B（model = 1..5）：MeasuredHrtf —— 实测 HRIR 卷积（557 tap），音色与定位以测量为准
 *
 * 为什么做成一个壳而不是直接改调用方：定位自检、12 路渲染、校正写回都只依赖
 * Configure/ProcessSample/ItdSamples/TheoreticalIldDb/SetCalibration 这五个方法，
 * 统一后切换后端不需要改任何调用点，也方便 A/B 回退。
 */
#pragma once

#include "sonicraft/dsp/vs_hrtf.hpp"
#include "sonicraft/dsp/vs_hrtf_measured.hpp"

namespace sonicraft::dsp::vs {

class HrtfEngine {
 public:
  static constexpr int kModelCount = HrirBank::kModelCount;

  void Prepare(float sample_rate) {
    sr_ = sample_rate;
    param_.Prepare(sample_rate);
    measured_.Prepare(sample_rate);
    measured_.SetModel(model_);
    use_measured_ = (model_ != 0);
    Configure(az_deg_, el_deg_);
  }

  void Reset() {
    param_.Reset();
    measured_.Reset();
  }

  /** 0 = 参数化；1..5 = 实测数据集 */
  void SetModel(int model) {
    const int m = HrirBank::IsValidModel(model) ? model : 0;
    if (m == model_) return;
    model_ = m;
    measured_.SetModel(m);
    use_measured_ = (m != 0);
    Configure(az_deg_, el_deg_);   // 换后端后按当前方位重新装载
  }

  int Model() const { return model_; }
  bool UsingMeasured() const { return use_measured_; }
  const char* ModelName() const { return HrirBank::ModelName(model_); }

  void Configure(float azimuth_deg, float elevation_deg) {
    az_deg_ = azimuth_deg;
    el_deg_ = elevation_deg;
    param_.Configure(azimuth_deg, elevation_deg);
    measured_.Configure(azimuth_deg, elevation_deg);
  }

  inline void ProcessSample(float x, float* out_l, float* out_r) {
    if (use_measured_) {
      measured_.ProcessSample(x, out_l, out_r);
    } else {
      param_.ProcessSample(x, out_l, out_r);
    }
  }

  float ItdSamples() const {
    return use_measured_ ? measured_.ItdSamples() : param_.ItdSamples();
  }
  float ItdScale() const {
    return use_measured_ ? measured_.ItdScale() : param_.ItdScale();
  }
  float TheoreticalIldDb() const {
    return use_measured_ ? measured_.TheoreticalIldDb() : param_.TheoreticalIldDb();
  }
  void SetCalibration(float itd_scale, float ild_trim) {
    param_.SetCalibration(itd_scale, ild_trim);
    measured_.SetCalibration(itd_scale, ild_trim);
  }

  float IldTrim() const {
    return use_measured_ ? measured_.IldTrim() : param_.IldTrim();
  }
  /** 实测后端的卷积核长度（参数化后端返回 0），供诊断显示 */
  int Taps() const { return use_measured_ ? HrirBank::TapsOf(model_) : 0; }

 private:
  float sr_ = 48000.0f;
  float az_deg_ = 0.0f;
  float el_deg_ = 0.0f;
  int model_ = 0;
  bool use_measured_ = false;

  ParametricHrtf param_;
  MeasuredHrtf measured_;
};

}  // namespace sonicraft::dsp::vs
