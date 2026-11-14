/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 低频管家效果器 —— 专业基准测量工具
 *
 * 与 `host-harness` 的区别：
 *   harness 验「功能对不对」（断言 pass/fail）；
 *   本工具产「客观测量数据」（频响 / THD / IMD / 瞬态 / 线性度），
 *   用来与行业基准（Waves MaxxBass、硬件低频增强器等）横向对照。
 *
 * 输出 CSV 到 stdout，便于后续用 Python 做表/图。
 *
 * 用法：
 *   lowfreq_bench > out.csv
 *   lowfreq_bench freq     仅频响
 *   lowfreq_bench thd      仅 THD/IMD
 *   lowfreq_bench dyn      仅动态线性度
 */

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "sonicraft/dsp/bass_enhance.hpp"
#include "sonicraft/dsp/dynamic_bass.hpp"
#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/virtual_bass.hpp"

namespace {

constexpr int kSr = 48000;
constexpr int kCh = 2;
constexpr int kFrames = 128;
constexpr double kPi = 3.14159265358979323846;

/** 稳态正弦测一段，返回跳过前 1/4（等瞬态稳定）的输入/输出。 */
struct Tone {
  std::vector<float> in;
  std::vector<float> out;
};

Tone runTone(sonicraft::dsp::DspNode& node, double freq, float amp, double seconds) {
  const int blocks = std::max(4, static_cast<int>(seconds * kSr / kFrames));
  std::vector<float> l(static_cast<std::size_t>(kFrames));
  std::vector<float> r(static_cast<std::size_t>(kFrames));
  float* chans[2] = {l.data(), r.data()};

  Tone t;
  double phase = 0.0;
  const double w = 2.0 * kPi * freq / kSr;
  for (int b = 0; b < blocks; ++b) {
    for (int i = 0; i < kFrames; ++i) {
      const float v = amp * static_cast<float>(std::sin(phase));
      phase += w;
      l[static_cast<std::size_t>(i)] = v;
      r[static_cast<std::size_t>(i)] = v;
    }
    const std::vector<float> inCopy = l;
    node.Process(chans, kFrames);
    if (b >= blocks / 4) {
      t.out.insert(t.out.end(), l.begin(), l.end());
      t.in.insert(t.in.end(), inCopy.begin(), inCopy.end());
    }
  }
  return t;
}

/** 单频点幅度（Goertzel），周期信号足够长时精度够用。 */
double ampAt(const std::vector<float>& x, double freq) {
  if (x.empty()) return 0.0;
  const double w = 2.0 * kPi * freq / kSr;
  const double coeff = 2.0 * std::cos(w);
  double s1 = 0.0;
  double s2 = 0.0;
  for (float v : x) {
    const double s0 = static_cast<double>(v) + coeff * s1 - s2;
    s2 = s1;
    s1 = s0;
  }
  const double p = s1 * s1 + s2 * s2 - coeff * s1 * s2;
  // **×2 是必须的**：标准 Goertzel 对 N 点输入的
  // `sqrt(p)` 量级是 `A·N/2`，除以 N 只得到 `A/2`。
  // 少乘这个 2 会让 THD+N 算成「剩余能量占 87%」（-1.2dB）—— 明显不可能。
  return 2.0 * std::sqrt(std::max(0.0, p)) / static_cast<double>(x.size());
}

double rms(const std::vector<float>& x) {
  if (x.empty()) return 0.0;
  double acc = 0.0;
  for (float v : x) acc += static_cast<double>(v) * v;
  return std::sqrt(acc / static_cast<double>(x.size()));
}

double db(double ratio) { return 20.0 * std::log10(ratio + 1e-12); }

/** 一个被测对象：节点 + 配置好的参数 */
struct Subject {
  const char* name;
  std::unique_ptr<sonicraft::dsp::DspNode> node;

  Subject(const char* n, std::unique_ptr<sonicraft::dsp::DspNode> nd)
      : name(n), node(std::move(nd)) {}

  void prepare() {
    node->Prepare(kSr, kFrames);
    node->SetBypass(false);
  }
};

using NodePtr = std::unique_ptr<sonicraft::dsp::DspNode>;

/** 低音保护：强度/深度拉满，保护阈值放宽（避免测量被限幅污染） */
NodePtr makeProtect() {
  auto n = std::make_unique<sonicraft::dsp::DynamicBassNode>();
  n->set_channel_count(kCh);
  n->Prepare(kSr, kFrames);
  n->SetBypass(false);
  n->SetParam(sonicraft::dsp::DynamicBassNode::kParamAmount, 1.0f);
  n->SetParam(sonicraft::dsp::DynamicBassNode::kParamBoostDepth, 1.0f);
  n->SetParam(sonicraft::dsp::DynamicBassNode::kParamSubsonicHz, 0.0f);   // 不切次低频，便于看全貌
  n->SetParam(sonicraft::dsp::DynamicBassNode::kParamFocusHz, 80.0f);
  n->SetParam(sonicraft::dsp::DynamicBassNode::kParamProtectLevel, 1.0f); // 阈值 -2dBFS，低电平下不介入
  return n;
}

/** 低音增强 · 动态模式：全参数 */
NodePtr makeEnhanceDynamic() {
  auto n = std::make_unique<sonicraft::dsp::BassEnhanceNode>();
  n->set_channel_count(kCh);
  n->Prepare(kSr, kFrames);
  n->SetBypass(false);
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamMode,
              static_cast<float>(sonicraft::dsp::BassEnhanceNode::kModeDynamic));
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamAmount, 1.0f);
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamDeep, 1.0f);
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamPunch, 0.6f);
  return n;
}

/** 低音增强 · 经典模式：最大增益 */
NodePtr makeEnhanceClassic() {
  auto n = std::make_unique<sonicraft::dsp::BassEnhanceNode>();
  n->set_channel_count(kCh);
  n->Prepare(kSr, kFrames);
  n->SetBypass(false);
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamMode,
              static_cast<float>(sonicraft::dsp::BassEnhanceNode::kModeClassic));
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamClassicGain, 12.0f);
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamClassicQ, 0.7071f);
  n->SetParam(sonicraft::dsp::BassEnhanceNode::kParamClassicCenter, 60.0f);
  return n;
}

/** 虚拟低音：干湿比 1（纯虚拟），其余默认 */
NodePtr makeVirtual() {
  auto n = std::make_unique<sonicraft::dsp::VirtualBassNode>();
  n->set_channel_count(kCh);
  n->Prepare(kSr, kFrames);
  n->SetBypass(false);
  // 测量口径对齐 `wecho yuanma` 默认值（与真机默认一致）。
  // 旧版这里用「全参数拉满」(1.3/0.5/0.5/40)，bench 数据与实际听感对不上。
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamAmount, 1.0f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamHarmonic, 1.0f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamMidGain, 0.08f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamHighGain, 0.16f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamEnvelopeRate, 50.0f);
  return n;
}

// 1/3 倍频程测点（低频段加密）
const double kFreqs[] = {20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 500, 1000};

void doFreqResponse() {
  std::printf("table,freq_hz");
  std::printf(",protect,enhance_dyn,enhance_cls,virtual\n");

  // 输入 -20dBFS：足够低，保护不介入；也足够高，远离数值噪声
  const float amp = 0.1f;
  for (double f : kFreqs) {
    std::printf("freq,%.1f", f);
    const char* labels[] = {"protect", "dyn", "cls", "virtual"};
    for (int k = 0; k < 4; ++k) {
      NodePtr node = (k == 0) ? makeProtect()
                    : (k == 1) ? makeEnhanceDynamic()
                    : (k == 2) ? makeEnhanceClassic()
                               : makeVirtual();
      const Tone t = runTone(*node, f, amp, 2.0);
      const double g = db(rms(t.out) / rms(t.in));
      std::printf(",%.3f", g);
      (void)labels;
    }
    std::printf("\n");
  }
}

void doDistortion() {
  // THD：基频 + 2..5 次谐波
  const double thdFreqs[] = {40, 60, 80, 100};
  const float amp = 0.15f;   // -16.5dBFS

  std::printf("table,thd\n");
  std::printf("subject,freq_hz,thd_pct,thd_plus_n_db\n");
  for (int k = 0; k < 4; ++k) {
    const char* name = (k == 0) ? "protect"
                     : (k == 1) ? "enhance_dyn"
                     : (k == 2) ? "enhance_cls"
                                : "virtual";
    for (double f : thdFreqs) {
      NodePtr node = (k == 0) ? makeProtect()
                    : (k == 1) ? makeEnhanceDynamic()
                    : (k == 2) ? makeEnhanceClassic()
                               : makeVirtual();
      const Tone t = runTone(*node, f, amp, 2.0);
      const double h1 = ampAt(t.out, f);
      double sq = 0.0;
      for (int h = 2; h <= 5; ++h) {
        const double a = ampAt(t.out, f * h);
        sq += a * a;
      }
      const double thd = 100.0 * std::sqrt(sq) / (h1 + 1e-12);
      // THD+N 定义：**相对总输出**的剩余能量（扣除基频后的失真+噪声）
      //   THD+N = 20log10( sqrt(total² − fund²) / total )
      // 修正：原先写成「剩余 / 基频」，在本工具里因为各效果器有增益
      // （total > fund），会算出**正 dB**（+4.8dB），物理上不可能。
      const double r = rms(t.out);
      const double fundRms = h1 / std::sqrt(2.0);
      const double restSq = std::max(0.0, r * r - fundRms * fundRms);
      // 下限钳位到 -120dB：这些是**浮点纯算法、无量化噪声**，
      // 当输出只有基频（无谐波）时 restSq 会被 Goertzel 的微小泄漏压成 0，
      // 直接打 log10 会得到 -200dB 这种"看起来完美"的假象。
      const double thdn = std::max(-120.0, 10.0 * std::log10(restSq / (r * r) + 1e-20));
      std::printf("thd,%s,%.0f,%.4f,%.1f\n", name, f, thd, thdn);
    }
  }

  // IMD：60Hz + 700Hz，4:1 幅度（SMPTE 风格）
  std::printf("table,imd\n");
  std::printf("subject,imd_pct\n");
  for (int k = 0; k < 4; ++k) {
    const char* name = (k == 0) ? "protect"
                     : (k == 1) ? "enhance_dyn"
                     : (k == 2) ? "enhance_cls"
                                : "virtual";
    NodePtr node = (k == 0) ? makeProtect()
                    : (k == 1) ? makeEnhanceDynamic()
                    : (k == 2) ? makeEnhanceClassic()
                               : makeVirtual();

    const int blocks = std::max(4, static_cast<int>(2.0 * kSr / kFrames));
    std::vector<float> l(static_cast<std::size_t>(kFrames));
    std::vector<float> r(static_cast<std::size_t>(kFrames));
    float* chans[2] = {l.data(), r.data()};
    std::vector<float> outAll;
    outAll.reserve(static_cast<std::size_t>(blocks * kFrames));

    double p1 = 0.0;
    double p2 = 0.0;
    const double w1 = 2.0 * kPi * 60.0 / kSr;
    const double w2 = 2.0 * kPi * 700.0 / kSr;
    for (int b = 0; b < blocks; ++b) {
      for (int i = 0; i < kFrames; ++i) {
        const float v = 0.12f * static_cast<float>(std::sin(p1)) +
                        0.03f * static_cast<float>(std::sin(p2));
        p1 += w1;
        p2 += w2;
        l[static_cast<std::size_t>(i)] = v;
        r[static_cast<std::size_t>(i)] = v;
      }
      node->Process(chans, kFrames);
      if (b >= blocks / 4) {
        outAll.insert(outAll.end(), l.begin(), l.end());
      }
    }
    const double carrier = ampAt(outAll, 700.0);
    double sb = 0.0;
    for (int n = 1; n <= 3; ++n) {
      const double a = ampAt(outAll, 700.0 + 60.0 * n);
      const double b2 = ampAt(outAll, 700.0 - 60.0 * n);
      sb += a * a + b2 * b2;
    }
    std::printf("imd,%s,%.4f\n", name, 100.0 * std::sqrt(sb) / (carrier + 1e-12));
  }
}

void doDynamics() {
  // 低音增强动态模式：不同输入电平下的 60Hz 提升量（等响度补偿曲线）
  const float levels[] = {0.005f, 0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.4f};
  std::printf("table,dynamics\n");
  std::printf("subject,input_dbfs,boost_db\n");
  for (float a : levels) {
    NodePtr node = makeEnhanceDynamic();
    const Tone t = runTone(*node, 60.0, a, 2.5);
    const double inDb = 20.0 * std::log10(static_cast<double>(a) / std::sqrt(2.0));
    std::printf("dyn,%.1f,%.3f\n", inDb, db(rms(t.out) / rms(t.in)));
  }
}

/** 测 IMD（60+700Hz SMPTE 风格）与 500Hz 频响，用于 L3 根因定位 */
/** 经典模式 Q 旋钮功能验证 */
void doDiagClassicQ() {
  using sonicraft::dsp::BassEnhanceNode;

  auto make = [&](float q) {
    auto n = std::make_unique<BassEnhanceNode>();
    n->set_channel_count(kCh);
    n->Prepare(kSr, kFrames);
    n->SetBypass(false);
    n->SetParam(BassEnhanceNode::kParamMode,
                static_cast<float>(BassEnhanceNode::kModeClassic));
    n->SetParam(BassEnhanceNode::kParamClassicGain, 12.0f);
    n->SetParam(BassEnhanceNode::kParamClassicCenter, 60.0f);
    n->SetParam(BassEnhanceNode::kParamClassicQ, q);
    return n;
  };

  const double freqs[] = {40.0, 60.0, 80.0, 100.0, 160.0, 200.0, 500.0, 1000.0};
  for (float q : {0.3f, 0.7071f, 2.0f}) {
    std::printf("qdiag,Q=%.4f", q);
    for (double f : freqs) {
      auto n = make(q);
      const Tone t = runTone(*n, f, 0.1f, 1.5);
      std::printf(",%.0fHz=%.2f", f, db(rms(t.out) / rms(t.in)));
    }
    std::printf("\n");
  }
}

void doDiagL3() {
  using sonicraft::dsp::BassEnhanceNode;

  auto make = [&](float amount, float deep, float punch) {
    auto n = std::make_unique<BassEnhanceNode>();
    n->set_channel_count(kCh);
    n->Prepare(kSr, kFrames);
    n->SetBypass(false);
    n->SetParam(BassEnhanceNode::kParamMode,
                static_cast<float>(BassEnhanceNode::kModeDynamic));
    n->SetParam(BassEnhanceNode::kParamAmount, amount);
    n->SetParam(BassEnhanceNode::kParamDeep, deep);
    n->SetParam(BassEnhanceNode::kParamPunch, punch);
    return n;
  };

  auto imdOf = [&](sonicraft::dsp::DspNode& node) {
    const int blocks = std::max(4, static_cast<int>(2.0 * kSr / kFrames));
    std::vector<float> l(static_cast<std::size_t>(kFrames));
    std::vector<float> r(static_cast<std::size_t>(kFrames));
    float* chans[2] = {l.data(), r.data()};
    std::vector<float> outAll;
    outAll.reserve(static_cast<std::size_t>(blocks * kFrames));
    double p1 = 0.0, p2 = 0.0;
    const double w1 = 2.0 * kPi * 60.0 / kSr;
    const double w2 = 2.0 * kPi * 700.0 / kSr;
    for (int b = 0; b < blocks; ++b) {
      for (int i = 0; i < kFrames; ++i) {
        const float v = 0.12f * static_cast<float>(std::sin(p1)) +
                        0.03f * static_cast<float>(std::sin(p2));
        p1 += w1; p2 += w2;
        l[static_cast<std::size_t>(i)] = v;
        r[static_cast<std::size_t>(i)] = v;
      }
      node.Process(chans, kFrames);
      if (b >= blocks / 4) outAll.insert(outAll.end(), l.begin(), l.end());
    }
    const double carrier = ampAt(outAll, 700.0);
    double sb = 0.0;
    for (int n = 1; n <= 3; ++n) {
      const double a = ampAt(outAll, 700.0 + 60.0 * n);
      const double c = ampAt(outAll, 700.0 - 60.0 * n);
      sb += a * a + c * c;
    }
    return 100.0 * std::sqrt(sb) / (carrier + 1e-12);
  };

  struct Case { const char* name; float a, d, p; };
  const Case cases[] = {
    {"EQ only        (deep=0 punch=0)", 1.0f, 0.0f, 0.0f},
    {"EQ + punch     (deep=0 p=0.6) ", 1.0f, 0.0f, 0.6f},
    {"EQ + dyn+sub   (deep=1 p=0)  ", 1.0f, 1.0f, 0.0f},
    {"ALL            (deep=1 p=0.6)", 1.0f, 1.0f, 0.6f},
    {"ALL p=0.3                    ", 1.0f, 1.0f, 0.3f},
    {"ALL deep=0.3                 ", 1.0f, 0.3f, 0.6f},
    {"ALL amount=0.5               ", 0.5f, 1.0f, 0.6f},
  };
  for (const auto& c : cases) {
    auto node = make(c.a, c.d, c.p);
    const double imd = imdOf(*node);
    auto node2 = make(c.a, c.d, c.p);
    const Tone t = runTone(*node2, 500.0, 0.1f, 1.5);
    const double g500 = db(rms(t.out) / rms(t.in));
    std::printf("l3,%s,imd,%.3f,g500,%.2f\n", c.name, imd, g500);
  }
}

void doDiagHarmonicEffect() {
  // 固定 midGain=0.5 / highGain=0.5 / amount=1，只变 harmonic
  for (float h : {0.0f, 0.3f, 0.6f, 1.0f, 1.3f}) {
    auto n = std::make_unique<sonicraft::dsp::VirtualBassNode>();
    n->set_channel_count(kCh);
    n->Prepare(kSr, kFrames);
    n->SetBypass(false);
    n->SetParam(sonicraft::dsp::VirtualBassNode::kParamAmount, 1.0f);
    n->SetParam(sonicraft::dsp::VirtualBassNode::kParamHarmonic, h);
    n->SetParam(sonicraft::dsp::VirtualBassNode::kParamMidGain, 0.5f);
    n->SetParam(sonicraft::dsp::VirtualBassNode::kParamHighGain, 0.5f);
    const Tone t = runTone(*n, 200.0, 0.1f, 2.0);
    std::printf("harmeffect,%.1f,%.2f,base200,%.6f,har400,%.6f\n",
                h, db(rms(t.out) / rms(t.in)), ampAt(t.out, 200.0), ampAt(t.out, 400.0));
  }
}

void doDiagMidBand() {
  // 单独测 mid 带通：midGain=1 / highGain=0 / harmonic=0 / amount=1
  // 此时输出应当 = 1.0 × mid。若 200Hz 仍衰减，说明 Band 滤波器本身有问题。
  auto n = std::make_unique<sonicraft::dsp::VirtualBassNode>();
  n->set_channel_count(kCh);
  n->Prepare(kSr, kFrames);
  n->SetBypass(false);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamAmount, 1.0f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamHarmonic, 0.0f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamMidGain, 1.0f);
  n->SetParam(sonicraft::dsp::VirtualBassNode::kParamHighGain, 0.0f);
  for (double f : {100.0, 160.0, 200.0, 250.0, 300.0, 400.0, 500.0}) {
    const Tone t = runTone(*n, f, 0.1f, 1.5);
    std::printf("midband,%.0f,%.3f\n", f, db(rms(t.out) / rms(t.in)));
  }
}

void doDiagVirtual() {
  // L2 诊断：200Hz 输入时，输出能量到底跑到哪个频率去了
  NodePtr node = makeVirtual();
  const Tone t = runTone(*node, 200.0, 0.1f, 2.0);
  std::printf("diag,rms_in,%.6f\n", rms(t.in));
  std::printf("diag,rms_out,%.6f\n", rms(t.out));
  std::printf("diag,rms_ratio_db,%.2f\n", db(rms(t.out) / rms(t.in)));
  for (double f : {60.0, 100.0, 160.0, 200.0, 240.0, 300.0, 400.0, 600.0, 800.0}) {
    std::printf("diag,%.0f,in,%.6f,out,%.6f,ratio_db,%.1f\n",
                f, ampAt(t.in, f), ampAt(t.out, f),
                db(ampAt(t.out, f) / (ampAt(t.in, f) + 1e-12)));
  }
}

void doStereo() {
  // 只给左声道，测右声道串扰（M/S 处理可能引入）
  std::printf("table,stereo\n");
  std::printf("subject,crosstalk_db\n");
  for (int k = 0; k < 4; ++k) {
    const char* name = (k == 0) ? "protect"
                     : (k == 1) ? "enhance_dyn"
                     : (k == 2) ? "enhance_cls"
                                : "virtual";
    NodePtr node = (k == 0) ? makeProtect()
                    : (k == 1) ? makeEnhanceDynamic()
                    : (k == 2) ? makeEnhanceClassic()
                               : makeVirtual();

    const int blocks = std::max(4, static_cast<int>(2.0 * kSr / kFrames));
    std::vector<float> l(static_cast<std::size_t>(kFrames));
    std::vector<float> r(static_cast<std::size_t>(kFrames));
    float* chans[2] = {l.data(), r.data()};
    double ph = 0.0;
    const double w = 2.0 * kPi * 60.0 / kSr;
    double rAcc = 0.0;
    double lAcc = 0.0;
    long n = 0;
    for (int b = 0; b < blocks; ++b) {
      for (int i = 0; i < kFrames; ++i) {
        const float v = 0.1f * static_cast<float>(std::sin(ph));
        ph += w;
        l[static_cast<std::size_t>(i)] = v;
        r[static_cast<std::size_t>(i)] = 0.0f;
      }
      node->Process(chans, kFrames);
      if (b >= blocks / 4) {
        for (int i = 0; i < kFrames; ++i) {
          lAcc += static_cast<double>(l[static_cast<std::size_t>(i)]) * l[static_cast<std::size_t>(i)];
          rAcc += static_cast<double>(r[static_cast<std::size_t>(i)]) * r[static_cast<std::size_t>(i)];
          ++n;
        }
      }
    }
    const double crosstalk = 20.0 * std::log10(std::sqrt(rAcc / n) / (std::sqrt(lAcc / n) + 1e-12) + 1e-12);
    std::printf("stereo,%s,%.1f\n", name, crosstalk);
  }
}

}  // namespace

int main(int argc, char** argv) {
  const char* what = (argc > 1) ? argv[1] : "all";

  if (std::strcmp(what, "all") == 0 || std::strcmp(what, "freq") == 0) {
    doFreqResponse();
  }
  if (std::strcmp(what, "all") == 0 || std::strcmp(what, "thd") == 0) {
    doDistortion();
  }
  if (std::strcmp(what, "all") == 0 || std::strcmp(what, "dyn") == 0) {
    doDynamics();
  }
  if (std::strcmp(what, "all") == 0 || std::strcmp(what, "stereo") == 0) {
    doStereo();
  }
  if (std::strcmp(what, "diag") == 0) {
    doDiagVirtual();
    doDiagMidBand();
    doDiagHarmonicEffect();
    doDiagL3();
    doDiagClassicQ();
  }
  return 0;
}
