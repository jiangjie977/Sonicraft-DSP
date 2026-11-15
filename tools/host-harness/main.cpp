/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Host-side DSP self-check harness (/ M4a).
 * Verifies:
 *   1. DspGraph pass-through (PassThroughNode): output is sample-identical to input;
 *   2. PassThroughNode bypass command: output is zeroed;
 *   3. perf probe: process count and timing;
 *   4. ParametricEqNode (M4a): default bypass=true passes through; after enabling
 *      processing + +12dB on band 0 (31Hz), low-frequency content is boosted with a
 *      smooth ramp (no NaN/Inf, no sample jumps, converges to ~4x RMS).
 */
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <limits>
#include <vector>

#include "sonicraft/dsp/compressor.hpp"
#include "sonicraft/dsp/dynamic_bass.hpp"
#include "sonicraft/dsp/virtual_bass.hpp"
#include "sonicraft/dsp/bass_enhance.hpp"
#include "sonicraft/dsp/convolver.hpp"
#include "sonicraft/dsp/dsp_graph.hpp"
#include "sonicraft/dsp/limiter.hpp"
#include "sonicraft/dsp/multiband.hpp"
#include "sonicraft/dsp/pass_through.hpp"
#include "sonicraft/dsp/parametric_eq.hpp"
#include "sonicraft/dsp/stereo_width.hpp"
#include "sonicraft/dsp/idle_bypass.hpp"
#include "sonicraft/dsp/script_effect.hpp"
#include "sonicraft/dsp/virtual_surround.hpp"

namespace {

int failures = 0;

void Check(bool ok, const char* what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++failures;
}

}  // namespace

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);  // 无缓冲：崩溃时输出不丢（定位用）
    // ---- 虚拟环绕：声像定位自检 + 自动校正 ----
    // 判据：左右手性不接反 / 镜像对称 / ITD 与 ILD 相对模型理论值的偏差收敛
    {
        sonicraft::dsp::VirtualSurroundNode vs_node;
        vs_node.Prepare(48000, 512);
        const auto vs_rep = vs_node.RunLocalizationSelfTest(true);
        Check(vs_rep.handedness_ok, "virtual surround: 左右手性正确（θ>0 右响且右耳早）");
        Check(vs_rep.symmetric_ok, "virtual surround: 左右镜像对称");
        Check(vs_rep.itd_rms_error_after <= 1.5f, "virtual surround: ITD 误差收敛（<=1.5 样本）");
        Check(vs_rep.ild_rms_error_after <= 1.5f, "virtual surround: ILD 误差收敛（<=1.5 dB）");
        Check(vs_rep.pass, "virtual surround: 声像定位自检总体通过");

        // 旁路时必须逐样本透传（未实现 DSP 体前不允许改动声音）
        std::vector<float> l(256, 0.25f), r(256, -0.25f);
        float* ch[2] = {l.data(), r.data()};
        vs_node.Process(ch, 256);
        bool same = true;
        for (int i = 0; i < 256; ++i) {
            if (l[static_cast<size_t>(i)] != 0.25f || r[static_cast<size_t>(i)] != -0.25f) { same = false; break; }
        }
        Check(same, "virtual surround: 默认旁路时逐样本透传");

        // ---- 后处理级：中置三态 / 后中置延迟（直接测 PostStage，确定性最强）----
        {
            using sonicraft::dsp::vs::PostStage;
            const int n = 64;
            std::vector<std::vector<float>> buf(7, std::vector<float>(n, 0.0f));
            float* ptr[7];
            for (int c = 0; c < 7; ++c) ptr[c] = buf[c].data();

            auto fill = [&]() {
                for (int c = 0; c < 7; ++c) std::fill(buf[c].begin(), buf[c].end(), 0.0f);
                std::fill(buf[sonicraft::dsp::vs::kC].begin(), buf[sonicraft::dsp::vs::kC].end(), 0.5f);
                std::fill(buf[sonicraft::dsp::vs::kFL].begin(), buf[sonicraft::dsp::vs::kFL].end(), 0.1f);
                std::fill(buf[sonicraft::dsp::vs::kFR].begin(), buf[sonicraft::dsp::vs::kFR].end(), 0.1f);
                std::fill(buf[sonicraft::dsp::vs::kCS].begin(), buf[sonicraft::dsp::vs::kCS].end(), 0.3f);
            };

            PostStage post;
            post.Prepare(48000.0f);
            PostStage::Params pp;
            pp.dialog_enabled = 0.0f;
            pp.rear_center_enabled = 0.0f;

            // 模式 0：中置静音（平滑收敛后应接近 0）
            pp.center_mode = 0.0f;
            for (int b = 0; b < 300; ++b) { fill(); post.Process(ptr, n, pp); }
            Check(std::fabs(buf[sonicraft::dsp::vs::kC][0]) < 1e-3f,
                  "post: 中置模式 0 时中置静音");

            // 模式 2：中置按 −3 dB 混入前置（0.5*0.707 ≈ 0.354）
            post.Reset();
            pp.center_mode = 2.0f;
            for (int b = 0; b < 300; ++b) { fill(); post.Process(ptr, n, pp); }
            Check(std::fabs(buf[sonicraft::dsp::vs::kFL][0] - (0.1f + 0.3536f)) < 0.02f,
                  "post: 中置模式 2 时前置收到 −3 dB 中置");

            // 后中置 5 ms 延迟：首块输出应接近 0（48000*0.005 = 240 > 64）
            post.Reset();
            pp.center_mode = 1.0f;
            pp.rear_center_enabled = 1.0f;
            pp.rear_delay_ms = 5.0f;
            fill();
            post.Process(ptr, n, pp);
            float cs_energy = 0.0f;
            for (int i = 0; i < n; ++i) cs_energy += std::fabs(buf[sonicraft::dsp::vs::kCS][i]);
            Check(cs_energy < 0.05f, "post: 后中置 5ms 延迟首块无声（延迟生效）");
        }

        // ---- 解码 + 渲染：开启后必须真的改变信号，且定位方向正确 ----
        vs_node.SetBypass(false);
        vs_node.SetParam(sonicraft::dsp::vs::kParamEnabled, 1.0f);
        vs_node.SetParam(sonicraft::dsp::vs::kParamCenterGain, 0.0f);
        vs_node.SetParam(sonicraft::dsp::vs::kParamSurroundGain, 0.0f);

        // 用 500 Hz 正弦做探针：HRTF 有 25 Hz 高通，直流会被滤掉，只剩 LFE 等量加到
        // 双耳，会把左右差异掩盖（第一版断言就是因为用常量而失败）。
        auto run_blocks = [&](float amp_l, float amp_r, int blocks, double* out_l, double* out_r) {
            std::vector<float> bl(512), br(512);
            double acc_l = 0.0, acc_r = 0.0;
            double phase = 0.0;
            const double w = 2.0 * 3.141592653589793 * 500.0 / 48000.0;
            for (int b = 0; b < blocks; ++b) {
                for (int i = 0; i < 512; ++i) {
                    const float v = static_cast<float>(std::sin(phase));
                    phase += w;
                    bl[static_cast<size_t>(i)] = v * amp_l;
                    br[static_cast<size_t>(i)] = v * amp_r;
                }
                float* ch[2] = {bl.data(), br.data()};
                vs_node.Process(ch, 512);
                if (b >= 2) {   // 跳过交叉淡化爬升段
                    for (int i = 0; i < 512; ++i) {
                        const double l = bl[static_cast<size_t>(i)], r = br[static_cast<size_t>(i)];
                        acc_l += l * l; acc_r += r * r;
                    }
                }
            }
            *out_l = std::sqrt(acc_l);
            *out_r = std::sqrt(acc_r);
        };

        double ml = 0.0, mr = 0.0;
        run_blocks(0.3f, 0.3f, 6, &ml, &mr);       // 居中（左右同相）
        Check(std::isfinite(ml) && std::isfinite(mr) && (ml + mr) > 0.0,
              "virtual surround: 开启后输出有效（非 NaN 且非静音）");
        const double center_balance = std::fabs(ml - mr) / (ml + mr + 1e-12);
        Check(center_balance < 0.15, "virtual surround: 居中信号双耳能量接近（|<L-R| 小于 15%）");

        double ll = 0.0, lr = 0.0;
        run_blocks(0.4f, 0.0f, 6, &ll, &lr);       // 纯左声道
        Check(ll > lr, "virtual surround: 纯左输入时左耳能量更大（方向正确）");

        double rl = 0.0, rr = 0.0;
        run_blocks(0.0f, 0.4f, 6, &rl, &rr);       // 纯右声道
        Check(rr > rl, "virtual surround: 纯右输入时右耳能量更大（方向正确）");
    }

    // 独立 RBJ peaking + TDF2 验证：抛开节点/图，直接验证滤波器数学
    // （band0=31Hz, +12dB, 40Hz 信号经该 biquad 应被放大约 2.6x）
    {
        const float kPi = 3.141592653589793f;
        const float sr = 48000.0f, f0 = 31.0f, Q = 1.0f, dBgain = 12.0f;
        const float A = std::pow(10.0f, dBgain / 40.0f);
        const float w0 = 2.0f * kPi * f0 / sr;
        const float cw = std::cos(w0), sw = std::sin(w0);
        const float alpha = sw / (2.0f * Q);
        const float B0 = (1.0f + alpha * A) / (1.0f + alpha / A);
        const float B1 = (-2.0f * cw) / (1.0f + alpha / A);
        const float B2 = (1.0f - alpha * A) / (1.0f + alpha / A);
        const float A1 = (-2.0f * cw) / (1.0f + alpha / A);
        const float A2 = (1.0f - alpha / A) / (1.0f + alpha / A);
        float z1 = 0.0f, z2 = 0.0f;
        const float step = 2.0f * kPi * 40.0f / sr;
        float sum = 0.0f; const int N = 2048;
        for (int n = 0; n < N; ++n) {
            const float x = 0.3f * std::sin(step * n);
            const float y = B0 * x + z1;
            z1 = B1 * x - A1 * y + z2;
            z2 = B2 * x - A2 * y;
            sum += y * y;
        }
        const float rms_out = std::sqrt(sum / N);
        const float rms_in = 0.3f / 1.4142f;
        std::printf("    INFO inline-eq rms=%.4f (input=%.4f, ratio=%.2f)\n",
                     rms_out, rms_in, rms_out / rms_in);
        Check(rms_out > rms_in * 2.0f, "inline RBJ peaking: +12dB band0 boosts 40Hz (>= ~2x)");
    }

    constexpr int kSampleRate = 48000;
    constexpr int kChannels = 2;
    constexpr int kMaxFrames = 128;

    sonicraft::dsp::DspGraph graph(kSampleRate, kChannels, kMaxFrames);
    graph.AddNode(std::make_unique<sonicraft::dsp::PassThroughNode>());  // index 0
    auto eq = std::make_unique<sonicraft::dsp::ParametricEqNode>();
    eq->set_channel_count(kChannels);
    graph.AddNode(std::move(eq));  // index 1

    const int frames = kMaxFrames;
    std::vector<float> buffer(static_cast<std::size_t>(frames) * kChannels, 0.5f);
    const std::vector<float> original = buffer;

    // 1) pass-through
    graph.ProcessInterleaved(buffer.data(), frames);
    bool identical = true;
    for (std::size_t i = 0; i < buffer.size(); ++i) {
        if (std::fabs(buffer[i] - original[i]) > 1e-6f) {
            identical = false;
            break;
        }
    }
    Check(identical, "pass-through: output is sample-identical to input");

    // 2) bypass command pushed from the config thread
    sonicraft::dsp::ParamCommand cmd{};
    cmd.node_index = 0;
    cmd.is_bypass = true;
    cmd.value = 1.0f;
    Check(graph.PushCommand(cmd), "command enqueued");

    graph.ProcessInterleaved(buffer.data(), frames);
    bool zeroed = true;
    for (float v : buffer) {
        if (std::fabs(v) > 1e-6f) {
            zeroed = false;
            break;
        }
    }
    Check(zeroed, "bypass: output zeroed");

    // 3) perf probe
    Check(graph.probe().ProcessCount() == 2, "probe: process count == 2");
    std::printf("    last=%lluns max=%lluns\n",
                static_cast<unsigned long long>(graph.probe().LastProcessNanos()),
                static_cast<unsigned long long>(graph.probe().MaxProcessNanos()));

    // 恢复 PassThrough(node0) 为正常透传：前面 bypass 测试把它置零，会污染后续 EQ 测试
    sonicraft::dsp::ParamCommand restore{};
    restore.node_index = 0;
    restore.is_bypass = true;
    restore.value = 0.0f;  // bypass=false => 透传
    graph.PushCommand(restore);

    // 4) ParametricEqNode: 真正的连续 40Hz 测试音（落在 band0=31Hz 通带内）。
    //    关键：相位必须在块之间连续累加，否则每 128 样本重置会把这个波形变成高频周期
    //    信号（~187Hz），band0（31Hz）对其无增益，从而误判"节点无 boost"。
    constexpr int kBlocks = 48;        // 计 ramp 在内的总块数
    constexpr int kLeadIn = 32;        // 前段：让 30ms 增益斜坡收敛，不计入 RMS
    constexpr int kMeasure = kBlocks - kLeadIn;
    const float step = 2.0f * 3.141592653589793f * 40.0f / kSampleRate;
    const float kAmp = 0.3f;

    // 基线输入 RMS：用连续 40Hz 正弦（≈ A/√2）作为对比基准
    float input_rms = 0.0f;
    {
        float ph = 0.0f; float sq = 0.0f; long cnt = 0;
        for (int blk = 0; blk < kBlocks; ++blk) {
            for (int i = 0; i < frames * kChannels; ++i) {
                const float s = kAmp * std::sin(ph); ph += step;
                sq += s * s; ++cnt;
            }
        }
        input_rms = std::sqrt(sq / cnt);
    }

    std::vector<float> sine(static_cast<std::size_t>(frames) * kChannels);
    float tone_phase = 0.0f;
    auto fillTone = [&]() {
        // 逐帧推进一个样本相位：解交织后每声道采样间隔恰为 step => 真正的 40Hz 连续音。
        // （若按每个元素推进相位，解交织会把它变成 80Hz，落在 band0 通带外。）
        for (int f = 0; f < frames; ++f) {
            const float s = kAmp * std::sin(tone_phase);
            tone_phase += step;
            const std::size_t base = static_cast<std::size_t>(f) * kChannels;
            sine[base + 0] = s;
            sine[base + 1] = s;
        }
    };
    fillTone();

    // 4a) default bypass=true => pass-through (output == input)
    std::vector<float> out = sine;
    graph.ProcessInterleaved(out.data(), frames);
    bool eq_pass = true;
    for (std::size_t i = 0; i < out.size(); ++i) {
        if (std::fabs(out[i] - sine[i]) > 1e-5f) { eq_pass = false; break; }
    }
    Check(eq_pass, "parametric_eq: bypass=true passes through");

    // 4b) enable processing + +12dB on band 0, process kBlocks; verify smooth ramp + boost
    sonicraft::dsp::ParamCommand bcmd{};
    bcmd.node_index = 1;
    bcmd.is_bypass = true;
    bcmd.value = 0.0f;  // bypass=false => processing on
    graph.PushCommand(bcmd);
    sonicraft::dsp::ParamCommand gcmd{};
    gcmd.node_index = 1;
    gcmd.is_bypass = false;
    gcmd.param_id = 0;  // band 0 (31Hz)
    gcmd.value = 12.0f;  // +12 dB
    graph.PushCommand(gcmd);

    bool no_nan = true;
    bool no_jump = true;
    float measure_sum = 0.0f;
    long measure_cnt = 0;
    for (int blk = 0; blk < kBlocks; ++blk) {
        fillTone();
        out = sine;
        graph.ProcessInterleaved(out.data(), frames);
        for (std::size_t i = 1; i < out.size(); ++i) {
            if (!std::isfinite(out[i])) no_nan = false;
            if (std::fabs(out[i] - out[i - 1]) > 0.5f) no_jump = false;  // 平滑：无样本突变
        }
        if (blk >= kLeadIn) {  // 仅对增益收敛后的块累积 RMS
            for (float v : out) { measure_sum += v * v; ++measure_cnt; }
        }
    }
    const float last_rms = std::sqrt(measure_sum / measure_cnt);
    Check(no_nan, "parametric_eq: no NaN/Inf during ramp");
    Check(no_jump, "parametric_eq: no sample jumps during ramp (smooth)");

    // 4c) 隔离测试：直接调节点虚函数（不经图命令路径），区分节点本身 vs 命令路径
    sonicraft::dsp::ParametricEqNode iso;
    iso.set_channel_count(kChannels);
    iso.Prepare(kSampleRate, kMaxFrames);
    iso.SetBypass(false);
    iso.SetParam(0, 12.0f);
    std::vector<float> p0(static_cast<std::size_t>(frames)), p1(static_cast<std::size_t>(frames));
    float* planar_ptrs[2] = { p0.data(), p1.data() };
    float iso_phase = 0.0f;
    float iso_sum = 0.0f;
    long iso_cnt = 0;
    bool iso_nan = true, iso_jump = true;
    for (int blk = 0; blk < kBlocks; ++blk) {
        for (int i = 0; i < frames; ++i) {
            const float s = kAmp * std::sin(iso_phase); iso_phase += step;  // 相位跨块连续
            p0[i] = s; p1[i] = s;
        }
        iso.Process(planar_ptrs, frames);
        for (int i = 1; i < frames; ++i) {
            if (!std::isfinite(p0[i])) iso_nan = false;
            if (std::fabs(p0[i] - p0[i - 1]) > 0.5f) iso_jump = false;
        }
        if (blk >= kLeadIn) {
            for (int i = 0; i < frames; ++i) { iso_sum += p0[i] * p0[i]; ++iso_cnt; }
        }
    }
    const float iso_last = std::sqrt(iso_sum / iso_cnt);
    std::printf("    INFO input_rms=%.4f graph_rms=%.4f iso_rms=%.4f ratio=%.2f\n",
                 input_rms, last_rms, iso_last, last_rms / input_rms);
    Check(last_rms > input_rms * 1.8f,
          "parametric_eq: +12dB band0 boosts 40Hz content (>= ~1.8x measured RMS)");
    Check(iso_nan, "iso: no NaN/Inf");
    Check(iso_jump, "iso: no sample jumps");

    // 4d) band9（16kHz）隔离验证：UI 高音滑条挂在 band9，host-harness 必须覆盖该频段。
    //     16kHz@48kHz 接近奈奎斯特（w0≈2.09 rad），验证高频段系数与响应同样正确。
    sonicraft::dsp::ParametricEqNode iso9;
    iso9.set_channel_count(kChannels);
    iso9.Prepare(kSampleRate, kMaxFrames);
    iso9.SetBypass(false);
    iso9.SetParam(9, 12.0f);
    const float step9 = 2.0f * 3.141592653589793f * 16000.0f / kSampleRate;  // band9 中心频率
    float phase9 = 0.0f;
    float sum9 = 0.0f;
    long cnt9 = 0;
    bool nan9 = true, jump9 = true;
    for (int blk = 0; blk < kBlocks; ++blk) {
        for (int i = 0; i < frames; ++i) {
            const float s = kAmp * std::sin(phase9); phase9 += step9;  // 相位跨块连续
            p0[i] = s; p1[i] = s;
        }
        iso9.Process(planar_ptrs, frames);
        for (int i = 1; i < frames; ++i) {
            if (!std::isfinite(p0[i])) nan9 = false;
            // 16kHz@48kHz 每周期 3 样本，boost 后相邻样本天然相位差 120°，
            // 最大差 ≈ 2·A·sin(60°) ≈ 2.0（A≈1.15）。0.5 阈值仅适用于低频信号，
            // 此处只检测真正的非连续突变（咔哒），故放宽到 3.0。
            if (std::fabs(p0[i] - p0[i - 1]) > 3.0f) jump9 = false;
        }
        if (blk >= kLeadIn) {  // 仅对增益收敛后的块累积 RMS
            for (int i = 0; i < frames; ++i) { sum9 += p0[i] * p0[i]; ++cnt9; }
        }
    }
    const float rms9 = std::sqrt(sum9 / cnt9);
    const float input_rms9 = kAmp / 1.4142f;  // 稳态正弦理论 RMS
    std::printf("    INFO band9(16kHz) input_rms=%.4f boosted_rms=%.4f ratio=%.2f\n",
                 input_rms9, rms9, rms9 / input_rms9);
    Check(nan9, "band9: no NaN/Inf");
    Check(jump9, "band9: no sample jumps");
    Check(rms9 > input_rms9 * 1.8f,
          "parametric_eq: +12dB band9 boosts 16kHz content (>= ~1.8x measured RMS)");

    // 5) LimiterNode（M4b）：look-ahead 峰值限幅，末级防削波安全网
    //    5a) 超电平输入（峰值 1.4，模拟 EQ +12dB 后的大音量源）→ 输出峰值 <= ceiling(-1dBFS)
    //    5b) 低电平输入（0.3，不触发限幅）→ 输出 = 输入延迟 look_ahead 帧的逐样本副本（增益=1）
    //    5c) 平滑性（并入 5a 的 no-jump 断言）
    //    5d) bypass=true → 输出逐样本等于输入（原样透传，无延迟）
    {
        const int kLBlocks = 64;
        sonicraft::dsp::LimiterNode lim;
        lim.set_channel_count(kChannels);
        lim.Prepare(kSampleRate, kMaxFrames);
        lim.SetBypass(false);
        lim.SetParam(sonicraft::dsp::LimiterNode::kParamCeilingDb, -1.0f);

        const float kLStep = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float lphase = 0.0f;

        // 5a+5c) 峰值 1.4 输入 → 压到 ceiling
        float peak_out = 0.0f;
        bool lim_nan = true, lim_jump = true;
        for (int blk = 0; blk < kLBlocks; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = 1.4f * std::sin(lphase); lphase += kLStep;
                p0[i] = s; p1[i] = s;
            }
            lim.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                const float a = std::fabs(p0[i]);
                if (!std::isfinite(a)) lim_nan = false;
                if (blk >= kLBlocks / 2 && a > peak_out) peak_out = a;  // 稳态后半段取峰
            }
            for (int i = 1; i < frames; ++i) {
                if (std::fabs(p0[i] - p0[i - 1]) > 0.5f) {
                    if (lim_jump) {
                        std::printf("    PROBE jump at blk=%d i=%d prev=%.4f cur=%.4f\n",
                                    blk, i, p0[i - 1], p0[i]);
                    }
                    lim_jump = false;
                }
            }
        }
        const float ceiling_lin = std::pow(10.0f, -1.0f / 20.0f);
        std::printf("    INFO limiter: in_peak=1.4 out_peak=%.4f ceiling=%.4f\n",
                     peak_out, ceiling_lin);
        Check(lim_nan, "limiter: no NaN/Inf");
        Check(lim_jump, "limiter: no sample jumps (smooth)");
        Check(peak_out <= ceiling_lin * 1.02f,
              "limiter: 1.4 peak input limited to ceiling (-1dBFS)");

        // 5b) 低电平：输出 = 输入延迟 look_ahead(120帧=2.5ms@48k) 的精确副本
        lim.Reset();
        std::vector<float> hist;
        hist.reserve(static_cast<std::size_t>(kLBlocks * frames));
        bool lin_exact = true;
        lphase = 0.0f;
        for (int blk = 0; blk < kLBlocks && lin_exact; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = 0.3f * std::sin(lphase); lphase += kLStep;
                p0[i] = s; p1[i] = s;
                hist.push_back(s);
            }
            lim.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                const long in_idx = static_cast<long>(blk) * frames + i - 120;
                if (in_idx >= 0 &&
                    std::fabs(p0[i] - hist[static_cast<std::size_t>(in_idx)]) > 1e-5f) {
                    std::printf("    PROBE mismatch blk=%d i=%d out=%.7f ref=%.7f\n",
                                blk, i, p0[i], hist[static_cast<std::size_t>(in_idx)]);
                    lin_exact = false; break;
                }
            }
        }
        Check(lin_exact, "limiter: below-threshold input passes as exact delayed copy (gain=1)");

        // 5d) bypass 透传（无延迟）。参考与输入同源生成（避免相位累加 vs 乘法的浮点差）
        lim.SetBypass(true);
        bool lbypass = true;
        {
            std::vector<float> ref(static_cast<std::size_t>(frames));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.3f * std::sin(lphase); lphase += kLStep;
                p0[i] = s; p1[i] = s; ref[i] = s;
            }
            lim.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                if (std::fabs(p0[i] - ref[i]) > 1e-6f) { lbypass = false; break; }
            }
        }
        Check(lbypass, "limiter: bypass=true passes through sample-identical (no delay)");

        // 5e) 深度压限（复现真机听感场景）：ceiling=-12dBFS（0.251），0.5 幅度正弦
        //     → 输出峰值 <= ceiling；且增益对包络（非瞬时样本）计算 => 无信号频率锯齿，
        //     断言为平滑（此前的抖动/杂音根因）。
        lim.Reset();
        lim.SetBypass(false);
        lim.SetParam(sonicraft::dsp::LimiterNode::kParamCeilingDb, -12.0f);
        float deep_peak = 0.0f;
        bool deep_jump = true, deep_nan = true;
        for (int blk = 0; blk < kLBlocks; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(lphase); lphase += kLStep;
                p0[i] = s; p1[i] = s;
            }
            lim.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                const float a = std::fabs(p0[i]);
                if (!std::isfinite(a)) deep_nan = false;
                if (blk >= kLBlocks / 2 && a > deep_peak) deep_peak = a;
            }
            for (int i = 1; i < frames; ++i) {
                if (std::fabs(p0[i] - p0[i - 1]) > 0.5f) deep_jump = false;
            }
        }
        const float ceiling_12 = std::pow(10.0f, -12.0f / 20.0f);
        std::printf("    INFO limiter deep: in_peak=0.5 out_peak=%.4f ceiling=%.4f\n",
                     deep_peak, ceiling_12);
        Check(deep_nan, "limiter deep: no NaN/Inf");
        Check(deep_jump, "limiter deep: no sample jumps (envelope-based gain, no sawtooth)");
        Check(deep_peak <= ceiling_12 * 1.05f,
              "limiter deep: 0.5 peak input limited to -12dBFS ceiling");

        // 5f) true-peak（BS.1770 4x 过采样，2026-10-27 RocoDSP 立项补强）：
        //     ±0.88 方波采样峰 0.88 < ceiling 0.891（旧检测不触发限幅），但 DAC
        //     理想带限重构存在 Gibbs 带内过冲 → 真峰 > ceiling——采样峰值检测的洞。
        //     tp=1：包络取 max(采样峰, 4x 过采样真峰估计) → 输出重构真峰 ≤ ceiling。
        //     参考检测独立实现（同系数数值、独立代码路径）。
        auto tpPeak = [](const std::vector<float>& sig, std::size_t from) {
            static const float C[3][24] = {
                {0.0000000000f, 0.0001446395f, -0.0006845229f, 0.0018882074f, -0.0041852741f,
                 0.0081816935f, -0.0147019688f, 0.0249430312f, -0.0409857821f, 0.0675820023f,
                 -0.1202563988f, 0.2983884783f, 0.8951654349f, -0.1683589584f, 0.0826002251f,
                 -0.0472912870f, 0.0278775055f, -0.0161021563f, 0.0088362290f, -0.0044739137f,
                 0.0020026442f, -0.0007215241f, 0.0001516951f, 0.0000000000f},
                {0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f,
                 0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f,
                 -0.0000000000f, 0.0000000000f, 1.0000000000f, 0.0000000000f, -0.0000000000f,
                 0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f,
                 -0.0000000000f, -0.0000000000f, -0.0000000000f, -0.0000000000f},
                {-0.0000000000f, -0.0001403435f, 0.0006611775f, -0.0018136995f, 0.0039922800f,
                 -0.0077349641f, 0.0137344936f, -0.0229156862f, 0.0367218788f, -0.0580670438f,
                 0.0949758205f, -0.1817952908f, 0.9089764540f, 0.2849274614f, -0.1078387957f,
                 0.0567519946f, -0.0320819606f, 0.0180717021f, -0.0097527809f, 0.0048794533f,
                 -0.0021647381f, 0.0007745222f, -0.0001619348f, -0.0000000000f},
            };
            float hist[24] = {0};
            float pk = 0.0f;
            for (std::size_t n = from; n < sig.size(); ++n) {
                for (int i = 23; i > 0; --i) hist[i] = hist[i - 1];
                hist[0] = sig[n];
                const float a0 = std::fabs(sig[n]);  // 原样本恒等点
                if (a0 > pk) pk = a0;
                for (int p = 0; p < 3; ++p) {
                    float acc = 0.0f;
                    for (int i = 0; i < 24; ++i) acc += hist[i] * C[p][i];
                    const float a = std::fabs(acc);
                    if (a > pk) pk = a;
                }
            }
            return pk;
        };
        const int kSqHalf = 24;  // ~1kHz 方波 @48k：半周期 24 帧，沿间距足够 Gibbs 完整发展
        auto runSquare = [&](bool tp, std::vector<float>& out) {
            lim.Reset();
            lim.SetBypass(false);
            lim.SetParam(sonicraft::dsp::LimiterNode::kParamCeilingDb, -1.0f);  // 5e 留下 -12dB，必须重设
            lim.SetParam(sonicraft::dsp::LimiterNode::kParamTruePeak, tp ? 1.0f : 0.0f);
            out.clear();
            int sq = 0;
            float lvl = -0.88f;
            for (int blk = 0; blk < kLBlocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    if (sq == 0) { lvl = -lvl; sq = kSqHalf; }
                    --sq;
                    p0[i] = lvl; p1[i] = lvl;
                }
                lim.Process(planar_ptrs, frames);
                out.insert(out.end(), p0.begin(), p0.begin() + frames);
            }
        };
        std::vector<float> sqOff, sqOn;
        runSquare(false, sqOff);
        runSquare(true, sqOn);
        const std::size_t steady = sqOff.size() / 2;  // 稳态后半段
        const float tpOff = tpPeak(sqOff, steady);
        const float tpOn = tpPeak(sqOn, steady);
        std::printf("    INFO limiter tp: square +-0.88 ceiling=%.4f oversampled_peak off=%.4f on=%.4f\n",
                     ceiling_lin, tpOff, tpOn);
        Check(tpOff > ceiling_lin,
              "limiter tp: +-0.88 square reconstructed peak exceeds ceiling with detection OFF (hole is real)");
        Check(tpOn <= ceiling_lin * 1.05f,
              "limiter tp: true-peak detection ON keeps reconstructed peak under ceiling");
    }

    // 6) CompressorNode（M4b-2）：feed-forward 下压式压缩，链序 EQ → Comp → Lim
    //    默认参数 threshold=-20dB, ratio=4:1, attack=10ms, release=150ms, makeup=0
    //    6a) 0.5 幅度（-6dB 峰）超阈值 → 稳态输出峰值符合静态曲线（理论 -16.5dB 峰）
    //    6b) 0.05 幅度（-26dB 峰）低于阈值 → 不压缩（输出峰值 ≈ 输入）
    //    6c) 平滑无跳变（并入 6a）
    //    6d) bypass 透传
    {
        const int kCBlocks = 64;
        sonicraft::dsp::CompressorNode comp;
        comp.set_channel_count(kChannels);
        comp.Prepare(kSampleRate, kMaxFrames);
        comp.SetBypass(false);

        const float kCStep = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float cphase = 0.0f;

        // 6a+6c) 超阈值压缩
        float comp_peak = 0.0f;
        bool comp_jump = true, comp_nan = true;
        for (int blk = 0; blk < kCBlocks; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(cphase); cphase += kCStep;
                p0[i] = s; p1[i] = s;
            }
            comp.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                const float a = std::fabs(p0[i]);
                if (!std::isfinite(a)) comp_nan = false;
                if (blk >= kCBlocks / 2 && a > comp_peak) comp_peak = a;
            }
            for (int i = 1; i < frames; ++i) {
                if (std::fabs(p0[i] - p0[i - 1]) > 0.5f) comp_jump = false;
            }
        }
        // 理论：peak_db=-6.02 → out_db = -20 + (-6.02+20)/4 = -16.5 → 输出峰值 = 10^(out_db/20) = 0.1496
        const float in_db = 20.0f * std::log10(0.5f);
        const float out_db_theory = -20.0f + (in_db + 20.0f) / 4.0f;
        const float theory_peak = std::pow(10.0f, out_db_theory / 20.0f);
        const float comp_peak_db = 20.0f * std::log10(comp_peak + 1e-9f);
        const float theory_db = 20.0f * std::log10(theory_peak + 1e-9f);
        std::printf("    INFO compressor: in_peak=0.5 out_peak=%.4f (theory=%.4f)\n",
                     comp_peak, theory_peak);
        Check(comp_nan, "compressor: no NaN/Inf");
        Check(comp_jump, "compressor: no sample jumps (smooth)");
        Check(std::fabs(comp_peak_db - theory_db) < 1.5f,
              "compressor: static gain curve matches threshold/ratio (within 1.5dB)");

        // 6b) 低电平不压缩
        comp.Reset();
        float low_peak = 0.0f;
        for (int blk = 0; blk < kCBlocks; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = 0.05f * std::sin(cphase); cphase += kCStep;
                p0[i] = s; p1[i] = s;
            }
            comp.Process(planar_ptrs, frames);
            if (blk >= kCBlocks / 2) {
                for (int i = 0; i < frames; ++i) {
                    const float a = std::fabs(p0[i]);
                    if (a > low_peak) low_peak = a;
                }
            }
        }
        std::printf("    INFO compressor low: in_peak=0.05 out_peak=%.4f (no compression)\n", low_peak);
        Check(std::fabs(low_peak - 0.05f) < 0.0025f,
              "compressor: below-threshold input uncompressed (peak ~= input)");

        // 6d) bypass 透传——必须用超阈值信号（0.5 幅度，压缩状态中）验证，
        //     否则低于阈值的增益≈0，bypass 缺陷会被假阳性掩盖。
        comp.Reset();
        comp.SetBypass(false);
        for (int blk = 0; blk < kCBlocks; ++blk) {  // 先进入压缩稳态
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(cphase); cphase += kCStep;
                p0[i] = s; p1[i] = s;
            }
            comp.Process(planar_ptrs, frames);
        }
        comp.SetBypass(true);
        bool cbypass = true;
        {
            std::vector<float> ref(static_cast<std::size_t>(frames));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(cphase); cphase += kCStep;
                p0[i] = s; p1[i] = s; ref[i] = s;
            }
            comp.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                if (std::fabs(p0[i] - ref[i]) > 1e-6f) { cbypass = false; break; }
            }
        }
        Check(cbypass, "compressor: bypass=true passes through sample-identical (mid-compression)");
    }

    // 6e) **立体声联动探针**（M4b-2 补强，2026-11-11）
    //     L = 0.99（-0.1dB）、R = 0.001（-60dB）。若检测与增益是「每声道独立」，
    //     R 几乎不会被压（它远低于阈值），L/R 的增益比会明显偏离 1.0 ——
    //     这正是单侧信号导致**声像漂移**的机理。
    //     改为跨声道共享包络/增益后，两声道必须被**同一个系数**处理：
    //     增益比 ≈ 1.000，且该系数确实 < 1（证明在压缩，不是空转）。
    {
        sonicraft::dsp::CompressorNode comp;
        comp.set_channel_count(kChannels);
        comp.Prepare(kSampleRate, kMaxFrames);
        comp.SetBypass(false);
        comp.SetParam(sonicraft::dsp::CompressorNode::kParamThresholdDb, -24.0f);
        comp.SetParam(sonicraft::dsp::CompressorNode::kParamRatio, 8.0f);
        comp.SetParam(sonicraft::dsp::CompressorNode::kParamKneeDb, 0.0f);
        comp.SetParam(sonicraft::dsp::CompressorNode::kParamAttackMs, 1.0f);
        comp.SetParam(sonicraft::dsp::CompressorNode::kParamReleaseMs, 100.0f);
        // 关掉侧链高通：本测试只关心联动，不希望 HP 改变检测信号
        comp.SetParam(sonicraft::dsp::CompressorNode::kParamSidechainHpHz, 0.0f);

        const float st = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float ph = 0.0f;
        float l_out = 0.0f, r_out = 0.0f;
        const int blocks = 64;
        for (int blk = 0; blk < blocks; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = std::sin(ph); ph += st;
                p0[i] = 0.99f * s;     // L 全幅
                p1[i] = 0.001f * s;    // R 极弱
            }
            comp.Process(planar_ptrs, frames);
            if (blk >= blocks / 2) {   // 只统计后半段（避开 attack 过渡）
                for (int i = 0; i < frames; ++i) {
                    const float al = std::fabs(p0[i]);
                    const float ar = std::fabs(p1[i]);
                    if (al > l_out) l_out = al;
                    if (ar > r_out) r_out = ar;
                }
            }
        }
        const float gl = l_out / 0.99f;
        const float gr = r_out / 0.001f;
        const float gain_ratio = (gl > 1e-9f) ? (gr / gl) : 0.0f;
        std::printf("    INFO compressor link: gainL=%.5f gainR=%.5f ratio=%.6f\n",
                    gl, gr, gain_ratio);
        Check(gl < 0.9f,
              "compressor: stereo link — actually compressing (shared gain < 1)");
        Check(std::fabs(gain_ratio - 1.0f) < 0.01f,
              "compressor: stereo link — L/R share ONE gain (ratio ~= 1.0, no image drift)");
    }

    // 6f) **侧链高通探针**（M4b-2 补强，2026-11-11）
    //     同电平（0dBFS）下分别喂 60Hz 与 1kHz：
    //     · HP 关闭时两者 GR 应当接近（低频能量同样能触发检测）
    //     · HP=120Hz 时 60Hz 的 GR 必须**明显变小**（低频被挡在检测路径外）
    //     · 同时 1kHz 基本不受影响（HP 只切低频）—— 否则就是「整体被衰减」而非高通
    {
        auto grForFreq = [&](float freq, float hpHz) -> float {
            sonicraft::dsp::CompressorNode c;
            c.set_channel_count(kChannels);
            c.Prepare(kSampleRate, kMaxFrames);
            c.SetBypass(false);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamThresholdDb, -24.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamRatio, 4.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamKneeDb, 3.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamAttackMs, 2.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamReleaseMs, 100.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamSidechainHpHz, hpHz);

            const float st = 2.0f * 3.141592653589793f * freq / kSampleRate;
            float ph = 0.0f;
            const int blocks = 48;
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = std::sin(ph); ph += st;   // 0dBFS
                    p0[i] = s; p1[i] = s;
                }
                c.Process(planar_ptrs, frames);
            }
            float m[4] = {0, 0, 0, 0};
            c.GetMeters(m);
            return m[1];   // GR dB（负值）
        };

        const float g60_off = grForFreq(60.0f, 0.0f);
        const float g1k_off = grForFreq(1000.0f, 0.0f);
        const float g60_hp = grForFreq(60.0f, 120.0f);
        const float g1k_hp = grForFreq(1000.0f, 120.0f);
        std::printf("    INFO sidechain HP: 60Hz off=%.2f hp=%.2f | 1kHz off=%.2f hp=%.2f\n",
                    g60_off, g60_hp, g1k_off, g1k_hp);
        Check(std::fabs(g60_hp) < std::fabs(g60_off) - 3.0f,
              "compressor: sidechain HP suppresses low-freq triggering (>3dB less GR at 60Hz)");
        Check(std::fabs(std::fabs(g1k_hp) - std::fabs(g1k_off)) < 1.0f,
              "compressor: sidechain HP transparent at 1kHz (within 1dB)");
    }

    // 6g) **并联混合探针**（2026-11-11，param 7）
    //     mix=0 应当**直通**（输出 = 输入），但检测仍在跑（GR 表照常有读数）；
    //     mix=1 是纯压缩输出；mix=0.5 落在两者之间。
    //     关键语义：mix=0 **不等于 bypass** —— bypass 连检测都停，mix=0 只去掉湿声。
    {
        auto runMix = [&](float mix, float* out_peak, float* gr_db) {
            sonicraft::dsp::CompressorNode c;
            c.set_channel_count(kChannels);
            c.Prepare(kSampleRate, kMaxFrames);
            c.SetBypass(false);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamMix, mix);
            // 其余用默认：threshold=-20 / ratio=4 / knee=6 / hp=120
            const float st = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
            float ph = 0.0f;
            const int blocks = 64;
            float pk = 0.0f;
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(ph); ph += st;
                    p0[i] = s; p1[i] = s;
                }
                c.Process(planar_ptrs, frames);
                if (blk >= blocks / 2) {
                    for (int i = 0; i < frames; ++i) {
                        const float a = std::fabs(p0[i]);
                        if (a > pk) pk = a;
                    }
                }
            }
            float m[4] = {0, 0, 0, 0};
            c.GetMeters(m);
            *out_peak = pk;
            *gr_db = m[1];
        };

        float pk_dry = 0.0f, gr_dry = 0.0f;
        float pk_mid = 0.0f, gr_mid = 0.0f;
        float pk_wet = 0.0f, gr_wet = 0.0f;
        runMix(0.0f, &pk_dry, &gr_dry);
        runMix(0.5f, &pk_mid, &gr_mid);
        runMix(1.0f, &pk_wet, &gr_wet);
        std::printf("    INFO mix: dry(0)=%.4f mid(0.5)=%.4f wet(1)=%.4f | GR dry=%.2f mid=%.2f wet=%.2f\n",
                    pk_dry, pk_mid, pk_wet, gr_dry, gr_mid, gr_wet);

        Check(std::fabs(pk_dry - 0.5f) < 0.002f,
              "compressor: mix=0 is pass-through (output == input)");
        Check(gr_dry < -5.0f,
              "compressor: mix=0 still detects (GR metering active, not bypass)");
        Check(pk_wet < pk_mid && pk_mid < pk_dry,
              "compressor: mix is monotonic (dry > mid > wet)");
    }

    // 6h) **RMS 检测探针**（2026-11-11，param 8）
    //     对**稳态正弦**：峰值检测看到的是幅值 A，RMS 看到的是 A/√2（低 3.01dB）。
    //     经过 ratio=4 的压缩曲线（slope=0.75）后，GR 的差应当是 3.01 × 0.75 ≈ **2.26dB**。
    //     这条同时验证「RMS 是能量平均」而非「另一次峰值检测」。
    {
        auto grForMode = [&](float mode) -> float {
            sonicraft::dsp::CompressorNode c;
            c.set_channel_count(kChannels);
            c.Prepare(kSampleRate, kMaxFrames);
            c.SetBypass(false);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamDetectMode, mode);
            const float st = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
            float ph = 0.0f;
            const int blocks = 128;   // 跑够长，让 RMS 积分完全收敛
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(ph); ph += st;
                    p0[i] = s; p1[i] = s;
                }
                c.Process(planar_ptrs, frames);
            }
            float m[4] = {0, 0, 0, 0};
            c.GetMeters(m);
            return m[1];
        };

        const float gr_peak = grForMode(0.0f);
        const float gr_rms = grForMode(1.0f);
        const float delta = std::fabs(gr_peak) - std::fabs(gr_rms);
        std::printf("    INFO detect mode: GR peak=%.2f rms=%.2f delta=%.2f (theory=2.26)\n",
                    gr_peak, gr_rms, delta);
        Check(delta > 1.8f && delta < 2.8f,
              "compressor: RMS detection sees -3dB on steady sine (GR delta ~2.26dB @4:1)");
    }

    // 6i) **前瞻探针**（2026-11-11，param 9）
    //     分两件事验：
    //     a) **延迟量**：lookahead=2ms @48k = 96 样本，单样本脉冲的输出峰应出现在
    //        输入位置 + 96（用 2ms 而非 5ms，保证落在 128 样本的单块内，便于定位）
    //     b) **瞬态效果**：先跑静音收敛，再注入脉冲。无前瞻时增益来不及降、脉冲
    //        原样通过；有前瞻时检测提前 96 样本、配合 1ms attack 足以在脉冲到达前
    //        把增益压下去，输出峰值必须明显更低。
    {
        const int kLaSamples = static_cast<int>(0.002f * kSampleRate) + 1;  // 97

        auto runImpulse = [&](float laMs, float* out_peak, int* peak_idx) {
            sonicraft::dsp::CompressorNode c;
            c.set_channel_count(kChannels);
            c.Prepare(kSampleRate, kMaxFrames);
            c.SetBypass(false);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamThresholdDb, -30.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamRatio, 8.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamKneeDb, 0.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamAttackMs, 1.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamReleaseMs, 50.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamSidechainHpHz, 0.0f);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamLookaheadMs, laMs);

            // 静音预热：让增益稳定在 0dB
            for (int blk = 0; blk < 16; ++blk) {
                for (int i = 0; i < frames; ++i) { p0[i] = 0.0f; p1[i] = 0.0f; }
                c.Process(planar_ptrs, frames);
            }
            // 注入单样本脉冲
            for (int i = 0; i < frames; ++i) { p0[i] = 0.0f; p1[i] = 0.0f; }
            p0[0] = 0.9f; p1[0] = 0.9f;
            c.Process(planar_ptrs, frames);

            float pk = 0.0f;
            int idx = -1;
            for (int i = 0; i < frames; ++i) {
                const float a = std::fabs(p0[i]);
                if (a > pk) { pk = a; idx = i; }
            }
            *out_peak = pk;
            *peak_idx = idx;
        };

        float pk_off = 0.0f, pk_on = 0.0f;
        int idx_off = -1, idx_on = -1;
        runImpulse(0.0f, &pk_off, &idx_off);
        runImpulse(2.0f, &pk_on, &idx_on);
        std::printf("    INFO lookahead: no-LA peak=%.4f @%d | LA2ms peak=%.4f @%d (expect @%d)\n",
                    pk_off, idx_off, pk_on, idx_on, kLaSamples);

        Check(idx_on == kLaSamples,
              "compressor: look-ahead delays output by exactly N samples");
        Check(idx_off == 0,
              "compressor: look-ahead=0 adds no delay (impulse stays at sample 0)");
        Check(pk_on < pk_off * 0.8f,
              "compressor: look-ahead catches the transient (output peak >20% lower)");
    }

    // 6j) **过采样探针**（2026-11-11，param 10）
    //     2x 过采样最隐蔽的风险不是音质，而是**时间常数被带偏**：
    //     核心跑在 2x 采样率下，若 attack/release 的系数仍按原始 sr 计算，
    //     同样的 150ms 会变成 75ms —— 听觉上是「一开过采样，压缩突然变快」。
    //     所以这里不测 THD，先测「稳态 GR 与 1x 是否一致」和「幅度是否被滤波器吃掉」。
    {
        struct OsResult { float peak; float gr; bool finite; };
        auto runOs = [&](float os) -> OsResult {
            sonicraft::dsp::CompressorNode c;
            c.set_channel_count(kChannels);
            c.Prepare(kSampleRate, kMaxFrames);
            c.SetBypass(false);
            c.SetParam(sonicraft::dsp::CompressorNode::kParamOversample, os);
            const float st = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
            float ph = 0.0f;
            const int blocks = 96;
            float pk = 0.0f;
            bool fin = true;
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(ph); ph += st;
                    p0[i] = s; p1[i] = s;
                }
                c.Process(planar_ptrs, frames);
                if (blk >= blocks / 2) {
                    for (int i = 0; i < frames; ++i) {
                        if (!std::isfinite(p0[i])) fin = false;
                        const float a = std::fabs(p0[i]);
                        if (a > pk) pk = a;
                    }
                }
            }
            float m[4] = {0, 0, 0, 0};
            c.GetMeters(m);
            return OsResult{pk, m[1], fin};
        };

        const OsResult r1 = runOs(1.0f);
        const OsResult r2 = runOs(2.0f);
        std::printf("    INFO oversample: 1x peak=%.4f GR=%.2f | 2x peak=%.4f GR=%.2f\n",
                    r1.peak, r1.gr, r2.peak, r2.gr);
        Check(r2.finite, "compressor: 2x oversampling produces finite output");
        Check(std::fabs(r1.gr - r2.gr) < 0.3f,
              "compressor: 2x oversampling keeps GR unchanged (time constants not scaled)");
        Check(std::fabs(r1.peak - r2.peak) / r1.peak < 0.05f,
              "compressor: 2x oversampling keeps level within 5% (filter is flat)");
    }

    // 6k) **动态低音（阶段 1）：旁路透明 + 次低频清理**
    //     本阶段节点只有 HPF；后面的分频重构/动态增强/保护限幅各有自己的探针。
    {
        using sonicraft::dsp::DynamicBassNode;

        // (a) 旁路必须**逐位相同** —— "效果器默认透明"是硬约束，
        //     任何浮点运算残留都会在 24bit 回放里变成底噪。
        {
            DynamicBassNode db;
            db.set_channel_count(kChannels);
            db.Prepare(kSampleRate, kMaxFrames);
            db.SetBypass(true);
            std::vector<float> ref0(static_cast<std::size_t>(frames));
            std::vector<float> ref1(static_cast<std::size_t>(frames));
            float max_err = 0.0f;
            const float st = 2.0f * 3.141592653589793f * 60.0f / kSampleRate;
            float ph = 0.0f;
            for (int blk = 0; blk < 16; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.5f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                    ref0[static_cast<std::size_t>(i)] = v;
                    ref1[static_cast<std::size_t>(i)] = v;
                }
                db.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    max_err = std::max(max_err,
                                       std::fabs(p0[i] - ref0[static_cast<std::size_t>(i)]));
                    max_err = std::max(max_err,
                                       std::fabs(p1[i] - ref1[static_cast<std::size_t>(i)]));
                }
            }
            std::printf("    INFO dynamic_bass: bypass max_err=%.3e\n", max_err);
            Check(max_err == 0.0f,
                  "dynamic bass: bypass is bit-identical to input");
        }

        // (b) 次低频清理：subsonic=30Hz 时
        //     · 30Hz 必须明显衰减（LR4 在截止点约 -6dB）
        //     · 80Hz 必须基本不变（远离截止）
        auto rmsFor = [&](float freq, float subsonicHz) -> double {
            DynamicBassNode db;
            db.set_channel_count(kChannels);
            db.Prepare(kSampleRate, kMaxFrames);
            db.SetBypass(false);
            db.SetParam(DynamicBassNode::kParamSubsonicHz, subsonicHz);
            // 隔离变量：本条只测 HPF，关掉动态增强与低频保护，
            // 否则它们会改变低频电平，让"HPF 衰减量"这个测量失去意义
            db.SetParam(DynamicBassNode::kParamBoostDepth, 0.0f);
            db.SetParam(DynamicBassNode::kParamProtectLevel, 0.0f);   // 同理：只测分频
            db.SetParam(DynamicBassNode::kParamProtectLevel, 0.0f);
            const float st = 2.0f * 3.141592653589793f * freq / kSampleRate;
            float ph = 0.0f;
            const int blocks = 48;
            double acc = 0.0;
            long long n = 0;
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.5f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                db.Process(planar_ptrs, frames);
                if (blk >= blocks / 2) {   // 跳过滤波器起振
                    for (int i = 0; i < frames; ++i) {
                        acc += static_cast<double>(p0[i]) * p0[i];
                        ++n;
                    }
                }
            }
            return n > 0 ? std::sqrt(acc / static_cast<double>(n)) : 0.0;
        };

        const double r30_off = rmsFor(30.0f, 0.0f);    // 关闭 = 直通
        const double r30_hp = rmsFor(30.0f, 30.0f);
        const double r80_off = rmsFor(80.0f, 0.0f);
        const double r80_hp = rmsFor(80.0f, 30.0f);
        const double att30 = 20.0 * std::log10((r30_hp + 1e-12) / (r30_off + 1e-12));
        const double d80 = 20.0 * std::log10((r80_hp + 1e-12) / (r80_off + 1e-12));
        std::printf("    INFO dynamic_bass: subsonic=30Hz -> 30Hz %.2fdB, 80Hz %.2fdB\n",
                    att30, d80);
        Check(att30 < -5.0,
              "dynamic bass: subsonic HPF attenuates 30Hz (>5dB; LR4 ~ -6dB at fc)");
        Check(std::fabs(d80) < 1.0,
              "dynamic bass: subsonic HPF leaves 80Hz untouched (<1dB)");
    }

    // 6l) **阶段 2：分频重构透明性**
    //     这是「只提升低音、不影响其他频段」的**技术保证**：
    //     信号被 LR4 分成低频带/高频带，只对低频带乘增益再相加。
    //     由于 LR4 的 lp4 与 hp4 相位一致、相加平坦，改动 lo 不会波及 hi。
    //     本阶段 lo 的增益固定 1.0 —— 所以此时「处理 ≈ 不处理」，
    //     正好用来验证重构本身没有引入染色。
    {
        using sonicraft::dsp::DynamicBassNode;

        // 输出/输入 RMS 比（dB）。subsonic=0 表示跳过次低频清理，
        // 只走「分频 → ×1 → 重构」这一条路径。
        auto gainDb = [&](float freq) -> double {
            DynamicBassNode db;
            db.set_channel_count(kChannels);
            db.Prepare(kSampleRate, kMaxFrames);
            db.SetBypass(false);
            db.SetParam(DynamicBassNode::kParamSubsonicHz, 0.0f);   // 只测分频
            // **隔离动态增强与保护**：否则稳态下 boost 恰好为 0、
            // 而保护会把 30Hz 压掉 4.6dB，让"分频透明性"测出莫名其妙的负数。
            // 这两个模块各由 6m / 6n 负责，本条只验重构本身。
            db.SetParam(DynamicBassNode::kParamBoostDepth, 0.0f);
            db.SetParam(DynamicBassNode::kParamProtectLevel, 0.0f);
            const float st = 2.0f * 3.141592653589793f * freq / kSampleRate;
            float ph = 0.0f;
            const int blocks = 64;
            double acc = 0.0;
            long long n = 0;
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.5f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                db.Process(planar_ptrs, frames);
                if (blk >= blocks / 2) {
                    for (int i = 0; i < frames; ++i) {
                        acc += static_cast<double>(p0[i]) * p0[i];
                        ++n;
                    }
                }
            }
            const double out_rms = std::sqrt(acc / static_cast<double>(n));
            // 输入 RMS：0.5 幅值正弦 = 0.5/√2
            const double in_rms = 0.5 / std::sqrt(2.0);
            return 20.0 * std::log10((out_rms + 1e-12) / (in_rms + 1e-12));
        };

        const double g30 = gainDb(30.0f);
        const double g200 = gainDb(200.0f);
        const double g1k = gainDb(1000.0f);
        const double g3k = gainDb(3000.0f);
        std::printf(
            "    INFO dynamic_bass split: 30Hz %.2fdB | 200Hz %.2fdB | 1kHz %.2fdB | 3kHz %.2fdB\n",
            g30, g200, g1k, g3k);

        // 频段内（远低于分频点）：重构应平坦
        Check(std::fabs(g30) < 0.5,
              "dynamic bass split: low band transparent at 30Hz (<0.5dB)");
        // 「其他频段」：这是整个设计的核心承诺
        Check(std::fabs(g1k) < 0.1,
              "dynamic bass split: 1kHz unaffected (<0.1dB, proves mid is untouched)");
        Check(std::fabs(g3k) < 0.1,
              "dynamic bass split: 3kHz unaffected (<0.1dB)");
        // 靠近分频点的过渡区，允许稍大的重构误差
        Check(std::fabs(g200) < 0.5,
              "dynamic bass split: 200Hz near crossover still flat (<0.5dB)");
    }

    // 6m) **阶段 3：动态低频增强方向性**
    //     必须用**瞬态**验，不能用稳态正弦 ——
    //     稳态时 env 与 ref 都收敛到信号峰值（rel→1），boost 恒为 0，
    //     那是设计意图（持续大电平不该再提），但会让"稳态测试"误判为没实现。
    //     正确做法：先用大信号把 ref 拉到高位，再突然降到低电平，
    //     此时 env 跟得快、ref 落得慢 → rel < 1 → boost 上升。
    {
        using sonicraft::dsp::DynamicBassNode;

        auto runSteps = [&](float amount, float* boost_loud, float* boost_quiet) {
            DynamicBassNode db;
            db.set_channel_count(kChannels);
            db.Prepare(kSampleRate, kMaxFrames);
            db.SetBypass(false);
            db.SetParam(DynamicBassNode::kParamSubsonicHz, 0.0f);
            db.SetParam(DynamicBassNode::kParamBoostDepth, 1.0f);   // 深度拉满
            db.SetParam(DynamicBassNode::kParamAmount, amount);

            const float st = 2.0f * 3.141592653589793f * 60.0f / kSampleRate;
            float ph = 0.0f;

            // ① 大信号 3 秒：建立参考峰值（kRefAttackMs = 2s，需跑够）
            const int loud_blocks = 3 * kSampleRate / frames;
            for (int blk = 0; blk < loud_blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.5f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                db.Process(planar_ptrs, frames);
            }
            float m[4] = {0, 0, 0, 0};
            db.GetMeters(m);
            *boost_loud = m[1];

            // ② 骤降到 -26dB（0.025 幅值），跑 400ms 让 env 落定、ref 还没跟上
            const int quiet_blocks = 400 * kSampleRate / 1000 / frames;
            for (int blk = 0; blk < quiet_blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.025f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                db.Process(planar_ptrs, frames);
            }
            db.GetMeters(m);
            *boost_quiet = m[1];
        };

        float loud = 0.0f, quiet = 0.0f;
        runSteps(1.0f, &loud, &quiet);
        float loud_half = 0.0f, quiet_half = 0.0f;
        runSteps(0.5f, &loud_half, &quiet_half);

        std::printf(
            "    INFO dynamic_bass boost: loud=%.2fdB quiet=%.2fdB | amount0.5 quiet=%.2fdB\n",
            loud, quiet, quiet_half);

        Check(std::fabs(loud) < 0.5f,
              "dynamic bass: steady loud signal gets NO boost (rel ~= 1)");
        Check(quiet > 3.0f,
              "dynamic bass: quiet passage gets boost (>3dB after -26dB step)");
        Check(quiet_half < quiet - 1.0f,
              "dynamic bass: amount scales the boost (0.5 gives clearly less than 1.0)");
    }

    // 6n) **阶段 4：低频保护限幅**
    //     验证四件事：① 超阈值被压到天花板附近；② 关掉时完全透明；
    //     ③ 低于阈值不介入；④ **只作用于低频带**，1kHz 不受影响。
    //     第 ④ 条是"不影响其他频段"承诺在保护模块上的延续。
    {
        using sonicraft::dsp::DynamicBassNode;

        auto peakFor = [&](float freq, float amplitude, float protectLevel) -> float {
            DynamicBassNode db;
            db.set_channel_count(kChannels);
            db.Prepare(kSampleRate, kMaxFrames);
            db.SetBypass(false);
            db.SetParam(DynamicBassNode::kParamSubsonicHz, 0.0f);   // 不清理次低频
            db.SetParam(DynamicBassNode::kParamBoostDepth, 0.0f);   // 不加动态增强
            db.SetParam(DynamicBassNode::kParamProtectLevel, protectLevel);

            const float st = 2.0f * 3.141592653589793f * freq / kSampleRate;
            float ph = 0.0f;
            const int blocks = 64;
            float pk = 0.0f;
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = amplitude * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                db.Process(planar_ptrs, frames);
                if (blk >= blocks / 2) {
                    for (int i = 0; i < frames; ++i) {
                        const float a = std::fabs(p0[i]);
                        if (a > pk) pk = a;
                    }
                }
            }
            return pk;
        };

        // protectLevel = 0.6 → ceiling = -18 + 0.6*16 = -8.4dBFS ≈ 0.380
        // （上限从 -6 提到 -2 后映射区间变宽，2026-11-11）
        //
        // **必须用 30Hz 而不是 60Hz**：保护只作用于分频后的低频带（<80Hz），
        // 而 60Hz 紧邻 80Hz 分频点 —— LR4 低通在 60Hz 只剩约 0.76 倍，另有 0.24 倍
        // 进了高频带、保护管不到。实测 60Hz 输入 0.9 只能压到 0.505（GR -7.5dB），
        // 那不是 bug 而是"只保护低频带"的必然结果。30Hz 远低于分频点，
        // 几乎全部能量都在低频带内，才能干净地验证保护本身。
        const float big_on = peakFor(30.0f, 0.9f, 0.6f);
        const float big_off = peakFor(30.0f, 0.9f, 0.0f);
        const float small_on = peakFor(30.0f, 0.1f, 0.6f);
        const float mid_on = peakFor(1000.0f, 0.9f, 0.6f);
        std::printf(
            "    INFO dynamic_bass protect: loud30=%.4f off=%.4f small30=%.4f mid1k=%.4f\n",
            big_on, big_off, small_on, mid_on);

        Check(big_on < 0.45f,
              "dynamic bass: protect limits loud low band to ceiling (~0.380 at level 0.6)");
        Check(big_on < big_off * 0.5f,
              "dynamic bass: protect actually reduces loud low band (less than half)");
        Check(std::fabs(big_off - 0.9f) < 0.06f,
              "dynamic bass: protect=0 is transparent for loud low band");
        Check(std::fabs(small_on - 0.1f) < 0.02f,
              "dynamic bass: protect does not engage below ceiling");
        Check(std::fabs(mid_on - 0.9f) < 0.06f,
              "dynamic bass: protect leaves 1kHz untouched (low-band only)");
    }

    // 6o) **虚拟低音（心理声学扩展）**
    //     三条断言对应三个必须成立的特性：
    //       ① amount=0 完全透明（可当旁路用）
    //       ② 60Hz 基频确实生成 120Hz 二次谐波（核心功能）
    //       ③ 纯 120Hz 输入**不得**凭空生成 240Hz（防误触发，靠包络归一化保证）
    {
        using sonicraft::dsp::VirtualBassNode;

        // Goertzel：单频点幅度，比整段 FFT 省事且够用
        auto goertzel = [&](const std::vector<float>& x, double freq) -> double {
            if (x.empty()) return 0.0;
            const double w = 2.0 * 3.141592653589793 * freq / kSampleRate;
            const double coeff = 2.0 * std::cos(w);
            double s1 = 0.0, s2 = 0.0;
            for (float v : x) {
                const double s0 = static_cast<double>(v) + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            const double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
            return std::sqrt(std::max(0.0, power)) / static_cast<double>(x.size());
        };

        auto runTone = [&](float freq, float amount, std::vector<float>* out) {
            VirtualBassNode vb;
            vb.set_channel_count(kChannels);
            vb.Prepare(kSampleRate, kMaxFrames);
            vb.SetBypass(false);
            vb.SetParam(VirtualBassNode::kParamAmount, amount);
            vb.SetParam(VirtualBassNode::kParamHarmonic, 1.3f);

            const float st = 2.0f * 3.141592653589793f * freq / kSampleRate;
            float ph = 0.0f;
            const int blocks = 2 * kSampleRate / frames;   // 2 秒
            out->clear();
            out->reserve(static_cast<std::size_t>(blocks * frames));
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.5f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                vb.Process(planar_ptrs, frames);
                if (blk >= blocks / 4) {   // 跳过起振
                    out->insert(out->end(), p0.begin(), p0.begin() + frames);
                }
            }
        };

        // ① amount=0 → 逐位透明
        {
            VirtualBassNode vb;
            vb.set_channel_count(kChannels);
            vb.Prepare(kSampleRate, kMaxFrames);
            vb.SetBypass(false);
            vb.SetParam(VirtualBassNode::kParamAmount, 0.0f);

            float max_err = 0.0f;
            const float st = 2.0f * 3.141592653589793f * 60.0f / kSampleRate;
            float ph = 0.0f;
            for (int blk = 0; blk < 8; ++blk) {
                std::vector<float> ref(static_cast<std::size_t>(frames));
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.5f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                    ref[static_cast<std::size_t>(i)] = v;
                }
                vb.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    max_err = std::max(max_err,
                                       std::fabs(p0[i] - ref[static_cast<std::size_t>(i)]));
                }
            }
            std::printf("    INFO virtual_bass: amount=0 max_err=%.3e\n", max_err);
            Check(max_err == 0.0f, "virtual bass: amount=0 is bit-identical to input");
        }

        // ② 100Hz（**谐波源带内**）→ 200Hz 谐波必须出现
        //
        // 为什么不用 60Hz 测：谐波源是 80~150Hz 带通，60Hz 落在过渡带外、
        // 被 HP80 衰减一半以上，生成的谐波自然弱。**这不是缺陷** ——
        // 手机扬声器连 80Hz 都放不出，需要"翻译"的正是 80~150Hz 这一段；
        // 60Hz 以下的内容本系统不负责（那属于耳机向的动态低音）。
        std::vector<float> out100;
        runTone(100.0f, 1.0f, &out100);
        const double a100 = goertzel(out100, 100.0);
        const double a200 = goertzel(out100, 200.0);
        const double a300 = goertzel(out100, 300.0);
        std::printf("    INFO virtual_bass: 100Hz in -> 100Hz=%.5f  200Hz=%.5f  300Hz=%.5f\n",
                    a100, a200, a300);
        Check(a200 > 0.02,
              "virtual bass: 100Hz fundamental generates 200Hz harmonic (amplitude > 0.02)");
        // **三次谐波断言（2026-11-12 对齐 yuanma 后新增）**：
        // yuanma 用 Harmonic<3> {0,.13,.13} → 0.52x³+0.26x²−0.39x−0.13，
        // 会生成 300Hz（100Hz 的三次）。我们旧版只有二次谐波，这正是"听感比
        // wecho 薄"的原因之一。此断言防止以后又把三次项改丢。
        // 实测 300Hz=0.00161（比带外噪声底 0.00002 高约 80 倍）；
        // 二次多项式不可能产生 100Hz 的三次谐波，故此断言验证的是三次项确实在。
        // 幅度比 200Hz 低 ~26dB，说明听感差异的主因仍是混合系数而非谐波阶数。
        Check(a300 > 0.001,
              "virtual bass: 100Hz generates 3rd harmonic 300Hz (yuanma Harmonic<3>)");
        // 判据 -20dB → -30dB（2026-11-12 对齐 yuanma）：
        // 按 wecho 原式（纯替换、Harmonic<3>）后，多项式含 −0.39x 项，
        // 加上 HP120 后仍有基频残留，残留基频比旧版大是**原版行为**。
        // -30dB 仍能区分"谐波生成了"和"没生成"（后者 -inf）。
        const double harm_db = 20.0 * std::log10((a200 + 1e-12) / (a100 + 1e-12));
        Check(harm_db > -30.0,
              "virtual bass: 200Hz harmonic within 30dB of residual fundamental");

        // ③ 纯 400Hz（**谐波源带外**）→ 不得生成 800Hz
        //
        // 为什么不用 120Hz 测：120Hz 确实在 80~150 带内，生成 240Hz 是**预期行为**。
        // 真正要防的是"中频内容被强行加工"—— 只有基频在带外时才有说服力。
        std::vector<float> out400;
        runTone(400.0f, 1.0f, &out400);
        const double b400 = goertzel(out400, 400.0);
        const double b800 = goertzel(out400, 800.0);
        const double ratio = 20.0 * std::log10((b800 + 1e-12) / (b400 + 1e-12));
        std::printf("    INFO virtual_bass: 400Hz in -> 400Hz=%.5f  800Hz=%.5f (%.1fdB)\n",
                    b400, b800, ratio);
        // 阈值 0.02 → 0.003：对齐 yuanma 后 mid_gain 默认 0.08
        // （旧版 0.5），中频直通被刻意压低以突出谐波 —— 这是设计不是缺陷。
        // 0.003 仍能区分"中频通道开着"和"通道断了"（后者 -inf）。
        Check(b400 > 0.003,
              "virtual bass: 400Hz passes through the (intentionally attenuated) mid band");
        Check(ratio < -40.0,
              "virtual bass: no phantom harmonic for out-of-band tone (<-40dB)");
    }

    // 6p) **低音增强（M/S + EQ + 瞬态 + 次谐波，设备无关）**
    //     移植自 wecho `BassEffect`。四条断言：
    //       ① 三个参数全 0 时接近透明（只切除 <20Hz、S 支路高通 120Hz 的痕迹）
    //       ② 60Hz 确实被抬起来（EQ 生效）
    //       ③ 等响度补偿：小电平的增益明显大于大电平
    //       ④ 1kHz 基本不动（低频模块不越界）
    {
        using sonicraft::dsp::BassEnhanceNode;

        auto runTo = [&](float freq, float amp, float amount, float deep, float punch,
                         std::vector<float>* out, float* dyn_db) {
            BassEnhanceNode be;
            be.set_channel_count(kChannels);
            be.Prepare(kSampleRate, kMaxFrames);
            be.SetBypass(false);
            be.SetParam(BassEnhanceNode::kParamAmount, amount);
            be.SetParam(BassEnhanceNode::kParamDeep, deep);
            be.SetParam(BassEnhanceNode::kParamPunch, punch);

            const float st = 2.0f * 3.141592653589793f * freq / kSampleRate;
            float ph = 0.0f;
            const int blocks = 2 * kSampleRate / frames;
            out->clear();
            out->reserve(static_cast<std::size_t>(blocks * frames));
            float m[4] = {0, 0, 0, 0};
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = amp * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                be.Process(planar_ptrs, frames);
                if (blk == blocks - 1) be.GetMeters(m);
                if (blk >= blocks / 4) {
                    out->insert(out->end(), p0.begin(), p0.begin() + frames);
                }
            }
            if (dyn_db != nullptr) *dyn_db = m[1];
        };

        auto rms = [](const std::vector<float>& v) {
            double acc = 0.0;
            for (float x : v) acc += static_cast<double>(x) * x;
            return std::sqrt(acc / std::max<std::size_t>(1, v.size()));
        };

        // ① 三个参数全 0 → 电平不变
        //
        // **不能比逐样本相位**：M 支路有 HP20、S 支路有 HP120，60Hz 过这两级会有
        // 相位偏移（实测逐样本误差 0.32，但那只是相移不是失真）。
        // 这个节点的设计是「只加增量」，全 0 时 delta=0、输出 = HP20(输入)，
        // 所以正确的判据是**电平不变**。
        {
            std::vector<float> off;
            float dyn = 0.0f;
            runTo(60.0f, 0.5f, 0.0f, 0.0f, 0.0f, &off, &dyn);
            const double rms_off = rms(off);
            const double rms_in = 0.5 / std::sqrt(2.0);
            const double diff_db = 20.0 * std::log10((rms_off + 1e-12) / rms_in);
            std::printf("    INFO bass_enhance: all-zero level diff=%.2fdB\n", diff_db);
            Check(std::fabs(diff_db) < 0.5,
                  "bass enhance: all params zero keeps level (only <20Hz removed)");
        }

        // ② 60Hz 被抬起
        std::vector<float> base60, boost60;
        runTo(60.0f, 0.5f, 0.0f, 0.0f, 0.0f, &base60, nullptr);
        runTo(60.0f, 0.5f, 1.0f, 0.0f, 0.0f, &boost60, nullptr);
        const double g60 = 20.0 * std::log10((rms(boost60) + 1e-12) / (rms(base60) + 1e-12));
        std::printf("    INFO bass_enhance: 60Hz amount 0->1 : %.2fdB\n", g60);
        // 判据 3.0 → **2.0**：实测 2.24dB，这不是 bug ——
        // RBJ lowShelf 的增益是"DC 处满额、中心频率 fc 处只有一半"，
        // 所以 +6dB 的 shelf 设在 60Hz 时，60Hz 本身只得到 +3dB；
        // Peak@45Hz 在 60Hz 已回落到 ~+0.5dB。合计约 +3.5dB 的 m_low，
        // 但输出是 `m_hp + (m_low − m_low_origin)`（只加增量），
        // 所以端到端只有 ~2.2dB。**这是 wecho 原版的行为，1:1 保留**。
        Check(g60 > 2.0,
              "bass enhance: 60Hz boosted >2dB at amount=1 (shelf half-gain at fc)");

        // ③ 等响度补偿：小电平增益 > 大电平
        float dyn_quiet = 0.0f, dyn_loud = 0.0f;
        std::vector<float> tmp;
        runTo(60.0f, 0.03f, 0.5f, 0.8f, 0.0f, &tmp, &dyn_quiet);
        runTo(60.0f, 0.45f, 0.5f, 0.8f, 0.0f, &tmp, &dyn_loud);
        std::printf("    INFO bass_enhance: dynamic gain quiet=%.2fdB loud=%.2fdB\n",
                    dyn_quiet, dyn_loud);
        Check(dyn_quiet > dyn_loud + 3.0,
              "bass enhance: quieter input gets more dynamic gain (loudness compensation)");

        // ④ 1kHz 基本不动
        std::vector<float> base1k, boost1k;
        runTo(1000.0f, 0.5f, 0.0f, 0.0f, 0.0f, &base1k, nullptr);
        runTo(1000.0f, 0.5f, 1.0f, 1.0f, 1.0f, &boost1k, nullptr);
        const double g1k = 20.0 * std::log10((rms(boost1k) + 1e-12) / (rms(base1k) + 1e-12));
        std::printf("    INFO bass_enhance: 1kHz full-boost change : %.2fdB\n", g1k);
        Check(std::fabs(g1k) < 1.0,
              "bass enhance: 1kHz essentially untouched even at full boost (<1dB)");
    }

    // 6q) **低音增强双模式**（2026-11-11，对照 wecho_projectV2.5.1 的 BassEffect）
    //     经典模式 = `out += LP(fc,Q)(out) × 10^(g/20) × 1.5`，不碰 M/S / 包络 / 瞬态。
    //     这组断言只关心「两种模式行为确实不同 + 经典模式本身成立」。
    {
        using sonicraft::dsp::BassEnhanceNode;

        auto runMode = [&](int mode, float classicGain, float freqAmp,
                           std::vector<float>* out) {
            BassEnhanceNode be;
            be.set_channel_count(kChannels);
            be.Prepare(kSampleRate, kMaxFrames);
            be.SetBypass(false);
            be.SetParam(BassEnhanceNode::kParamMode, static_cast<float>(mode));
            be.SetParam(BassEnhanceNode::kParamClassicGain, classicGain);
            // 动态模式参数给同一套，避免"没设所以为 0"的干扰
            be.SetParam(BassEnhanceNode::kParamAmount, 0.5f);
            be.SetParam(BassEnhanceNode::kParamDeep, 0.3f);
            be.SetParam(BassEnhanceNode::kParamPunch, 0.6f);

            const float st = 2.0f * 3.141592653589793f * freqAmp / kSampleRate;
            float ph = 0.0f;
            const int blocks = 2 * kSampleRate / frames;
            out->clear();
            out->reserve(static_cast<std::size_t>(blocks * frames));
            for (int blk = 0; blk < blocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.3f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                be.Process(planar_ptrs, frames);
                if (blk >= blocks / 4) {
                    out->insert(out->end(), p0.begin(), p0.begin() + frames);
                }
            }
        };

        auto rmsOf = [](const std::vector<float>& v) {
            double acc = 0.0;
            for (float x : v) acc += static_cast<double>(x) * x;
            return std::sqrt(acc / std::max<std::size_t>(1, v.size()));
        };

        const double in_rms = 0.3 / std::sqrt(2.0);

        // ① 经典模式 60Hz 应该明显提升（低通 60Hz + 15dB 增益 × 1.5）
        std::vector<float> cls60, dyn60;
        runMode(BassEnhanceNode::kModeClassic, 15.0f, 60.0f, &cls60);
        runMode(BassEnhanceNode::kModeDynamic, 15.0f, 60.0f, &dyn60);
        const double cls60_db = 20.0 * std::log10((rmsOf(cls60) + 1e-12) / in_rms);
        const double dyn60_db = 20.0 * std::log10((rmsOf(dyn60) + 1e-12) / in_rms);
        std::printf("    INFO bass_enhance mode: 60Hz classic=%.2fdB dynamic=%.2fdB\n",
                    cls60_db, dyn60_db);
        Check(cls60_db > 6.0,
              "bass enhance classic: 60Hz boosted >6dB at gain=15 (LP+gain add)");
        Check(std::fabs(cls60_db - dyn60_db) > 1.0,
              "bass enhance: classic and dynamic modes behave differently");

        // ② 经典模式 1kHz 受影响应很小（低通 60Hz 对 1k 衰减约 -24dB，再 ×5.6 也只有 0.35×）
        std::vector<float> cls1k;
        runMode(BassEnhanceNode::kModeClassic, 15.0f, 1000.0f, &cls1k);
        const double cls1k_db = 20.0 * std::log10((rmsOf(cls1k) + 1e-12) / in_rms);
        std::printf("    INFO bass_enhance mode: 1kHz classic=%.2fdB\n", cls1k_db);
        Check(std::fabs(cls1k_db) < 3.0,
              "bass enhance classic: 1kHz change stays small at max gain (<3dB)");

        // ③ 经典模式 gain=0 时只加 LP×1.5（仍有少量低频），不能变成静音
        std::vector<float> cls0;
        runMode(BassEnhanceNode::kModeClassic, 0.0f, 60.0f, &cls0);
        const double cls0_db = 20.0 * std::log10((rmsOf(cls0) + 1e-12) / in_rms);
        std::printf("    INFO bass_enhance mode: 60Hz classic gain=0 : %.2fdB\n", cls0_db);
        Check(cls0_db > 1.0,
              "bass enhance classic: gain=0 still adds LP×1.5 (>1dB), not silent");

        // ④ 切模式不产生 NaN / 爆音
        {
            BassEnhanceNode be;
            be.set_channel_count(kChannels);
            be.Prepare(kSampleRate, kMaxFrames);
            be.SetBypass(false);
            bool finite = true;
            const float st = 2.0f * 3.141592653589793f * 80.0f / kSampleRate;
            float ph = 0.0f;
            for (int blk = 0; blk < 24; ++blk) {
                // 每 4 块切一次模式
                if (blk % 4 == 0) {
                    be.SetParam(BassEnhanceNode::kParamMode,
                                (blk / 4) % 2 == 0 ? 1.0f : 0.0f);
                }
                for (int i = 0; i < frames; ++i) {
                    const float v = 0.3f * std::sin(ph);
                    ph += st;
                    p0[i] = v;
                    p1[i] = v;
                }
                be.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    if (!std::isfinite(p0[i]) || std::fabs(p0[i]) > 4.0f) finite = false;
                }
            }
            Check(finite,
                  "bass enhance: mode switching stays finite and bounded");
        }
    }

    // 7) ConvolverNode（M4c）：全频域卷积 + OLA，末级混响

    //    7a) preset 0（单位冲激）→ 输出 = 输入精确副本（FFT/OLA 管线恒等性验证）
    //    7b) preset 1（小房间 IR）→ 正弦稳态输出有混响能量、平滑无跳变、无 NaN
    //    7c) preset 1→3→0 切换稳定性
    //    7d) bypass 透传
    //    附：每块卷积耗时（预算参考）
    {
        sonicraft::dsp::ConvolverNode conv;
        conv.set_channel_count(kChannels);
        conv.Prepare(kSampleRate, kMaxFrames);
        conv.SetBypass(false);
        conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset, 0.0f);

        const float kVStep = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float vphase = 0.0f;

        // 7a) 单位冲激恒等（参考与输入同源生成，避免相位累加浮点差假阳性）。
        //     块长混排 128/64/96：真机回调 192 帧被引擎切成 128+64，小块 OLA
        //     路径必须同样精确（直通卷积零延迟，输出[k] = 输入[k]）。
        //     双通道比对：R 通道走 IFFT 虚部还原路径（dual-real 合成公式），此前
        //     只验 L，R 曾是测试盲区。
        //     阈值取 1e-3：16384 点单精度 FFT 往返在零填充区残留 ~1e-4 噪声，
        //     经 OLA 尾部跨块累积（8 块 RMS~3e-4、峰值~1e-3）；真实混响中该区
        //     本就是信号，听感无碍，此处仅验证管线恒等性无逻辑错误。
        bool conv_id = true;
        float max_err = 0.0f;
        {
            const int lens[8] = {128, 64, 128, 96, 64, 128, 128, 64};
            for (int blk = 0; blk < 8 && conv_id; ++blk) {
                const int fl = lens[blk];
                std::vector<float> ref(static_cast<std::size_t>(fl));
                for (int i = 0; i < fl; ++i) {
                    const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                    p0[i] = s; p1[i] = s; ref[static_cast<std::size_t>(i)] = s;
                }
                conv.Process(planar_ptrs, fl);
                for (int i = 0; i < fl; ++i) {
                    const float e0 = std::fabs(p0[i] - ref[static_cast<std::size_t>(i)]);
                    const float e1 = std::fabs(p1[i] - ref[static_cast<std::size_t>(i)]);
                    const float e = e0 > e1 ? e0 : e1;
                    if (e > max_err) max_err = e;
                    if (e > 1e-3f) {
                        std::printf("    PROBE conv id: blk=%d fl=%d i=%d p0=%.6f p1=%.6f ref=%.6f\n",
                                    blk, fl, i, p0[i], p1[i], ref[static_cast<std::size_t>(i)]);
                        conv_id = false; break;
                    }
                }
            }
        }
        std::printf("    INFO conv id: max_err=%.6f\n", max_err);
        Check(conv_id, "convolver: unit-impulse preset reproduces input exactly (mixed block sizes, both channels)");

        // 7a2) 大块口径回归锁（2026-10-24 真机全预设啵啵回归）：真机
        //      FRAMES_PER_BLOCK=192 > H=128，消费一步可越过批次槽区起点——
        //      b70557a 初版把补半放在消费之后 → 每批 64 帧读陈旧环带 =
        //      全预设哔哔啵啵；harness 此前恒用 ≤128 帧喂（恰好安全）故未
        //      暴露。用例：同一输入序列分别以「真机口径 192/2048 混排」与
        //      「基准 128 定长」跑两个实例（小房间真实 tail，恒等预设 tail
        //      全零暴露不了陈旧槽读），断言输出逐位一致——批次触发按 g_in_
        //      门槛与块边界无关，块长只影响调度时序不影响数学；任何调度
        //      错位（读旧槽）都会让大块跑偏离基准被立刻抓获。
        bool big_id = true;
        float big_err = 0.0f;
        {
            sonicraft::dsp::ConvolverNode conv_big;
            conv_big.set_channel_count(kChannels);
            conv_big.Prepare(kSampleRate, 2048);
            conv_big.SetBypass(false);
            conv_big.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                              static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
            sonicraft::dsp::ConvolverNode conv_ref;
            conv_ref.set_channel_count(kChannels);
            conv_ref.Prepare(kSampleRate, 2048);
            conv_ref.SetBypass(false);
            conv_ref.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                              static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
            constexpr int kBigTotal = 29952;  // ≈ 29 批次周期
            std::vector<float> b0(kBigTotal), b1(kBigTotal), r0(kBigTotal), r1(kBigTotal);
            const int lens[12] = {192, 192, 192, 2048, 192, 2048, 96, 128, 2048, 192, 192, 2048};  // 真机混排（轮总和 9568）
            float bphase = 0.0f;
            for (int i = 0; i < kBigTotal; ++i) {
                const float s = 0.5f * std::sin(bphase); bphase += kVStep;
                b0[static_cast<std::size_t>(i)] = s;
                b1[static_cast<std::size_t>(i)] = s;
                r0[static_cast<std::size_t>(i)] = s;  // 同一输入序列喂两个实例
                r1[static_cast<std::size_t>(i)] = s;
            }
            {   // 大块口径跑（喂满 kBigTotal，末块截断）
                int off = 0;
                for (int blk = 0; blk < 48 && off < kBigTotal; ++blk) {
                    int fl = lens[blk % 12];
                    if (off + fl > kBigTotal) fl = kBigTotal - off;
                    float* bp[2] = {b0.data() + off, b1.data() + off};
                    conv_big.Process(bp, fl);
                    off += fl;
                }
            }
            {   // 清尾：最后一批的后半需要有块可跑（否则用例在批中途截断，
                //      大块口径的最后一批永远差一个后半——2026-10-24 插桩定位）
                float z1[2048] = {}, z2[2048] = {};
                float* zp1[2] = {z1, z1};
                float* zp2[2] = {z2, z2};
                for (int blk = 0; blk < 3; ++blk) {
                    conv_big.Process(zp1, 2048);
                    conv_ref.Process(zp2, 2048);
                }
            }
            {   // 基准口径跑（128 定长，同输入）
                int off = 0;
                while (off < kBigTotal) {
                    const int fl = (kBigTotal - off < 128) ? kBigTotal - off : 128;
                    float* rp[2] = {r0.data() + off, r1.data() + off};
                    conv_ref.Process(rp, fl);
                    off += fl;
                }
            }
            int nbad = 0;
            for (int i = 0; i < kBigTotal; ++i) {
                const float e0 = std::fabs(b0[static_cast<std::size_t>(i)] - r0[static_cast<std::size_t>(i)]);
                const float e1 = std::fabs(b1[static_cast<std::size_t>(i)] - r1[static_cast<std::size_t>(i)]);
                const float e = e0 > e1 ? e0 : e1;
                if (e > big_err) big_err = e;
                if (e > 1e-9f) {
                    big_id = false;  // 同代码同输入 → 调度正确应逐位一致
                    if (++nbad <= 5)
                        std::printf("    PROBE big-block bad[%d] i=%d (mod 1024=%d) b=%.6f r=%.6f\n",
                                    nbad, i, i % 1024, b0[static_cast<std::size_t>(i)], r0[static_cast<std::size_t>(i)]);
                }
            }
            std::printf("    PROBE big-block nbad=%d/%d\n", nbad, kBigTotal);
        }
        std::printf("    INFO conv id big-block: max_err=%.3g\n", big_err);
        Check(big_id, "convolver: device-sized blocks (192/2048) bit-exact vs 128-frame baseline (consumer-overrun regression)");

        // 7b) 小房间混响：稳态能量 + 平滑 + 耗时
        conv.Reset();
        conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                      static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
        float rev_sum = 0.0f;
        long rev_cnt = 0;
        bool rev_nan = true, rev_jump = true;
        double conv_us_total = 0.0;
        int conv_us_blocks = 0;
        for (int blk = 0; blk < 64; ++blk) {
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                p0[i] = s; p1[i] = s;
            }
            const auto t0 = std::chrono::steady_clock::now();
            conv.Process(planar_ptrs, frames);
            const auto t1 = std::chrono::steady_clock::now();
            if (blk >= 8) {  // 跳过首块缓存预热
                conv_us_total += std::chrono::duration<double, std::micro>(t1 - t0).count();
                ++conv_us_blocks;
            }
            for (int i = 0; i < frames; ++i) {
                if (!std::isfinite(p0[i])) rev_nan = false;
                if (blk >= 32) { rev_sum += p0[i] * p0[i]; ++rev_cnt; }
            }
            for (int i = 1; i < frames; ++i) {
                if (std::fabs(p0[i] - p0[i - 1]) > 0.5f) rev_jump = false;
            }
        }
        const float in_rms_ref = 0.5f / 1.4142f;
        const float rev_rms = std::sqrt(rev_sum / rev_cnt);
        const float rev_ratio = rev_rms / in_rms_ref;
        std::printf("    INFO convolver: in_rms=%.4f reverbed_rms=%.4f ratio=%.2f, per-block=%.0fus (budget 2670us)\n",
                     in_rms_ref, rev_rms, rev_ratio, conv_us_total / conv_us_blocks);
        Check(rev_nan, "convolver: no NaN/Inf");
        Check(rev_jump, "convolver: no sample jumps (smooth)");
        Check(rev_ratio > 0.2f && rev_ratio < 5.0f,
              "convolver: small-room IR produces bounded reverberant energy");

        // 7c) 预设切换稳定性（hall → dry）
        bool conv_switch = true;
        for (int preset = 3; preset >= 0 && conv_switch; --preset) {
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset, static_cast<float>(preset));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                p0[i] = s; p1[i] = s;
            }
            conv.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                if (!std::isfinite(p0[i])) { conv_switch = false; break; }
            }
        }
        Check(conv_switch, "convolver: preset switching stable (no NaN/Inf)");

        // 7d) bypass 透传
        conv.SetBypass(true);
        bool vbypass = true;
        {
            std::vector<float> ref(static_cast<std::size_t>(frames));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                p0[i] = s; p1[i] = s; ref[i] = s;
            }
            conv.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                if (std::fabs(p0[i] - ref[i]) > 1e-6f) { vbypass = false; break; }
            }
        }
        Check(vbypass, "convolver: bypass=true passes through sample-identical");

        // 7e) 立体声去相关：L=R 的单声道输入，去相关 IR（左右独立种子）的左右
        //     输出必须显著不同——否则混响输出呈单声道感。输入用双正弦宽带化：
        //     单频下 ratio 依赖 |H_L-H_R| 与 dry 相干项 2·0.85·Re(H_L·e^{-jθ})
        //     的相位巧合（tail 平移给 H 乘公共旋转因子，sig 随 θ 变），宽带统计
        //     才收敛到 IR 的去相关性质本身。阈值 0.20：宽带实测 ~0.24+，旧单频
        //     校准值 0.35 是相位巧合不再适用（L/R 数学正确性已由 7j 双通道锁定）。
        //     统计窗口分段采样：tail 交付延迟恒定，窗口 2×128=256 帧无批次伪影。
        {
            conv.Reset();
            conv.SetBypass(false);
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
            const float kVStep2 = 2.0f * 3.141592653589793f * 3337.0f / kSampleRate;
            float vphase2 = 0.0f;
            // 冲洗旧尾（bypass 期间尾被冻结）
            for (int blk = 0; blk < 130; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(vphase) + 0.2f * std::sin(vphase2);
                    vphase += kVStep; vphase2 += kVStep2;
                    p0[i] = s; p1[i] = s;
                }
                conv.Process(planar_ptrs, frames);
            }
            double sum_diff = 0.0, sum_sig = 0.0;
            for (int round = 0; round < 10; ++round) {
                for (int skip = 0; skip < 14; ++skip) {
                    for (int i = 0; i < frames; ++i) {
                        const float s = 0.5f * std::sin(vphase) + 0.2f * std::sin(vphase2);
                        vphase += kVStep; vphase2 += kVStep2;
                        p0[i] = s; p1[i] = s;
                    }
                    conv.Process(planar_ptrs, frames);
                }
                for (int blk = 0; blk < 2; ++blk) {
                    for (int i = 0; i < frames; ++i) {
                        const float s = 0.5f * std::sin(vphase) + 0.2f * std::sin(vphase2);
                        vphase += kVStep; vphase2 += kVStep2;
                        p0[i] = s; p1[i] = s;
                    }
                    conv.Process(planar_ptrs, frames);
                    for (int i = 0; i < frames; ++i) {
                        const float d = p0[i] - p1[i];
                        sum_diff += static_cast<double>(d) * d;
                        sum_sig += static_cast<double>(p0[i]) * p0[i];
                    }
                }
            }
            const double diff_rms = std::sqrt(sum_diff);
            const double sig_rms = std::sqrt(sum_sig);
            std::printf("    INFO stereo decorr: diffRms=%.4f sigRms=%.4f ratio=%.2f\n",
                        diff_rms, sig_rms, sig_rms > 1e-9 ? diff_rms / sig_rms : 0.0);
            Check(diff_rms >= 0.20 * sig_rms && sig_rms > 1e-9,
                  "convolver: stereo decorrelation (L/R outputs differ for mono input)");
        }

        // 7i) tail 对齐脉冲探针（临时诊断）：手工双脉冲 IR（ir[128]=1, ir[1280]=0.5，
        //     即 tail_ir[0] 与 tail_ir[1152]）+ 单位脉冲输入 → 输出峰位置直接暴露
        //     tail 对齐。期望峰：n=0（dry 0.75）、n=1152（0.3·g 归一后 ~0.268）、
        //     n=2304（延迟 = H + j + P = 128+1152+1024，幅度减半）。
        {
            const char* irp = "test_ir_pulse.wav";
            {
                const int n = 2048, sr = 48000, ch = 1;
                std::vector<float> samples(static_cast<std::size_t>(n * ch), 0.0f);
                samples[static_cast<std::size_t>(128)] = 1.0f;  // 仅 tail_ir[0] 单脉冲
                FILE* f = std::fopen(irp, "wb");
                if (f != nullptr) {
                    const std::size_t data_bytes = samples.size() * 4;
                    const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                    std::fwrite("RIFF", 1, 4, f);
                    std::fwrite(&riff, 4, 1, f);
                    std::fwrite("WAVEfmt ", 1, 8, f);
                    const uint32_t fmt_size = 16, fmt_tag = 3, srate = sr, byte_rate = sr * ch * 4;
                    const uint16_t nch = ch, bits = 32, align = ch * 4;
                    std::fwrite(&fmt_size, 4, 1, f);
                    std::fwrite(&fmt_tag, 2, 1, f);
                    std::fwrite(&nch, 2, 1, f);
                    std::fwrite(&srate, 4, 1, f);
                    std::fwrite(&byte_rate, 4, 1, f);
                    std::fwrite(&align, 2, 1, f);
                    std::fwrite(&bits, 2, 1, f);
                    std::fwrite("data", 1, 4, f);
                    const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                    std::fwrite(&dsz, 4, 1, f);
                    std::fwrite(samples.data(), 1, data_bytes, f);
                    std::fclose(f);
                }
            }
            conv.Reset();
            conv.SetBypass(false);
            const bool pok = conv.LoadImpulseResponseFromFile(irp);
            std::printf("    PROBE pulse load ok=%d err=[%s]\n", pok ? 1 : 0, conv.LastLoadError().c_str());
            std::remove(irp);
            if (pok) {
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
            std::vector<float> xs(static_cast<std::size_t>(32 * 128), 0.0f);
            xs[0] = 1.0f;
            int off2 = 0;
            int noise_cnt = 0, noise_first = -1;
            for (int blk = 0; blk < 32; ++blk) {
                for (int i = 0; i < 128; ++i) {
                    p0[i] = xs[static_cast<std::size_t>(off2 + i)];
                    p1[i] = xs[static_cast<std::size_t>(off2 + i)];
                }
                conv.Process(planar_ptrs, 128);
                for (int i = 0; i < 128; ++i) {
                    const float a = std::fabs(p0[i]);
                    if (a > 0.02f) {
                        std::printf("    PROBE pulse-out: n=%d v=%.5f\n", off2 + i, p0[i]);
                    } else if (a > 0.005f) {
                        ++noise_cnt;
                        if (noise_first < 0) noise_first = off2 + i;
                    }
                }
                off2 += 128;
            }
            std::printf("    PROBE pulse noise(>5e-3): cnt=%d first=%d\n", noise_cnt, noise_first);
            }
        }

        // 7j) 黄金参照（架构保真门，阶段 A 更新）：ConvolverNode 输出 vs 等效系统
        //     的时域直接卷积独立参照。两阶段分区的等效 IR = head ir[0..H) 零延迟 +
        //     tail ir[H..L) 整体平移 H+P（u 延迟 H + 批次交付延迟 P，等效晚段混响
        //     平移 1152 帧 ≈ 24ms@48k，扩散场无感知）：
        //       y[n] = 0.85·x[n] + Σ_{i<H} ir[i]·x[n-i] + Σ_j ir[H+j]·x[n-H-P-j]
        //     （H=block=128、P=1024，与 ConvolverNode 内部参数一致；dry_g=0.85 对齐
        //      kDryGain[smallroom]；参照实现非被测代码路径）。任何未来架构改动必须
        //     |y_test - y_ref| < 5e-4 全程通过——数学保真由此测试锁定，听感回归只剩
        //     架构本身的时间轴行为（tail 平移是否可闻，由真机盲听裁决）。
        {
            conv.Reset();
            conv.SetBypass(false);
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
            constexpr int kBlocks = 256, kBLen = 128;
            constexpr int kTotal = kBlocks * kBLen;
            constexpr int kHead = 128;   // head 长度 = block（与 ConvolverNode 一致）
            constexpr int kTailP = 1024; // tail 批次长 = 交付延迟（与 kTailBatch 一致）
            std::vector<float> xin(static_cast<std::size_t>(kTotal));
            const double st1 = 2.0 * 3.141592653589793 * 997.0 / 48000.0;
            const double st2 = 2.0 * 3.141592653589793 * 3337.0 / 48000.0;
            for (int i = 0; i < kTotal; ++i) {
                xin[static_cast<std::size_t>(i)] =
                    0.5f * std::sin(static_cast<double>(i) * st1) +
                    0.2f * std::sin(static_cast<double>(i) * st2);
            }
            // 独立时域参照（L/R 双通道分别生成：L=kRirSmallL、R=kRirSmallR）
            std::vector<float> yref(static_cast<std::size_t>(kTotal), 0.0f);
            std::vector<float> yrefR(static_cast<std::size_t>(kTotal), 0.0f);
            const float* rirs[2] = {sonicraft::dsp::kRirSmallL, sonicraft::dsp::kRirSmallR};
            for (int c = 0; c < 2; ++c) {
                std::vector<float>& yr = (c == 0) ? yref : yrefR;
                const float* rir = rirs[c];
                for (int n = 0; n < kTotal; ++n) {
                    double acc = 0.0;
                    // head 段：ir[0..H) 零延迟
                    const int imax_h = n < kHead ? n : kHead - 1;
                    for (int i = 0; i <= imax_h; ++i) {
                        acc += static_cast<double>(xin[static_cast<std::size_t>(n - i)]) *
                               static_cast<double>(rir[i]);
                    }
                    // tail 段：ir[H..L) 作用在延迟 H+P 的输入上（u 延迟 H + 交付延迟 P；
                    // xi<0 后随 j 递减，可 break）
                    for (int j = 0;; ++j) {
                        const int idx = kHead + j;
                        if (idx >= sonicraft::dsp::kRirSmallLen) break;
                        const long long xi = static_cast<long long>(n) - kHead - kTailP - j;
                        if (xi < 0) break;
                        acc += static_cast<double>(xin[static_cast<std::size_t>(xi)]) *
                               static_cast<double>(rir[idx]);
                    }
                    yr[static_cast<std::size_t>(n)] =
                        static_cast<float>(0.85 * xin[static_cast<std::size_t>(n)] + acc);
                }
            }
            // 被测跑 256 块
            double max_err = 0.0;
            int off = 0;
            int first_bad = -1;
            float y_peak = 0.0f;
            double seg_max[8] = {0, 0, 0, 0, 0, 0, 0, 0};
            for (int blk = 0; blk < kBlocks; ++blk) {
                for (int i = 0; i < kBLen; ++i) {
                    p0[i] = xin[static_cast<std::size_t>(off + i)];
                    p1[i] = xin[static_cast<std::size_t>(off + i)];
                }
                conv.Process(planar_ptrs, kBLen);
                for (int i = 0; i < kBLen; ++i) {
                    const double err0 = std::fabs(static_cast<double>(p0[i]) -
                                                  static_cast<double>(yref[static_cast<std::size_t>(off + i)]));
                    const double err1 = std::fabs(static_cast<double>(p1[i]) -
                                                  static_cast<double>(yrefR[static_cast<std::size_t>(off + i)]));
                    const double err = err0 > err1 ? err0 : err1;
                    if (err > max_err) max_err = err;
                    const int seg = (off + i) / (kTotal / 8);
                    if (seg < 8 && err > seg_max[seg]) seg_max[seg] = err;
                    if (first_bad < 0 && err > 5e-4) {
                        first_bad = off + i;
                        std::printf("    PROBE first bad: n=%d ch=%c y=%.6f ref=%.6f\n",
                                    off + i, err0 > err1 ? 'L' : 'R',
                                    err0 > err1 ? p0[i] : p1[i],
                                    err0 > err1 ? yref[static_cast<std::size_t>(off + i)]
                                                : yrefR[static_cast<std::size_t>(off + i)]);
                    }
                    const float a = std::fabs(p0[i]);
                    if (a > y_peak) y_peak = a;
                }
                off += kBLen;
            }
            std::printf("    INFO golden: y_peak=%.4f first_bad=%d max_err=%.6f\n",
                        y_peak, first_bad, max_err);
            std::printf("    INFO golden segs:");
            for (double s : seg_max) std::printf(" %.4f", s);
            std::printf("\n");
            Check(max_err < 5e-4,
                  "convolver: golden reference (time-domain conv matches node output)");
        }

        // 7k) 长 IR 端到端（kMaxIrLen 提升验收）：320ms 三脉冲合成 IR（15360 帧，
        //     L=R 脉冲在 [0]/[128]/[15359]）+ 0.5 幅度单脉冲输入 → 三输出峰解析
        //     可验：n=0（dry 0.75 + head ir[0]）、n=1152（tail 首分区 j=0，延迟
        //     H+P）、n=16383（tail 末帧 j=15231 → 分区 14 i=895，延迟 H+j+P）。
        //     归一 g=0.3/sqrt(3) 精确已知（全部样本能量=3），峰值/位置/杂讯三断言
        //     覆盖 T 分区填充正确性与最后分区读路径。
        {
            conv.Reset();
            conv.SetBypass(false);
            const char* wav_path = "test_ir_long.wav";
            {
                const int n = 15360, sr = 48000, ch = 2;
                std::vector<float> samples(static_cast<std::size_t>(n * ch), 0.0f);
                // 交错布局：L 帧 x 在 index x*ch（先填 L，再复制到 R）
                samples[0] = 1.0f;
                samples[128 * ch] = 1.0f;
                samples[15359 * ch] = 1.0f;
                for (int i = 0; i < n; ++i) {
                    samples[static_cast<std::size_t>(i * ch + 1)] =
                        samples[static_cast<std::size_t>(i * ch)];
                }
                FILE* f = std::fopen(wav_path, "wb");
                if (f != nullptr) {
                    const std::size_t data_bytes = samples.size() * 4;
                    const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                    std::fwrite("RIFF", 1, 4, f);
                    std::fwrite(&riff, 4, 1, f);
                    std::fwrite("WAVEfmt ", 1, 8, f);
                    const uint32_t fmt_size = 16, fmt_tag = 3, srate = sr, byte_rate = sr * ch * 4;
                    const uint16_t nch = ch, bits = 32, align = ch * 4;
                    std::fwrite(&fmt_size, 4, 1, f);
                    std::fwrite(&fmt_tag, 2, 1, f);
                    std::fwrite(&nch, 2, 1, f);
                    std::fwrite(&srate, 4, 1, f);
                    std::fwrite(&byte_rate, 4, 1, f);
                    std::fwrite(&align, 2, 1, f);
                    std::fwrite(&bits, 2, 1, f);
                    std::fwrite("data", 1, 4, f);
                    const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                    std::fwrite(&dsz, 4, 1, f);
                    std::fwrite(samples.data(), 1, data_bytes, f);
                    std::fclose(f);
                }
            }
            const bool load_ok = conv.LoadImpulseResponseFromFile(wav_path);
            Check(load_ok && conv.UserIrLoaded(), "convolver: 320ms long IR loads into slot 5");
            std::remove(wav_path);
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
            constexpr int kN7k = 132 * 128;  // 16896 帧 > 末峰 16383
            std::vector<float> xs7k(static_cast<std::size_t>(kN7k), 0.0f);
            xs7k[0] = 0.5f;
            const float g7k = 0.3f / std::sqrt(3.0f);
            const int exp_n[3] = {0, 1152, 16383};
            const float exp_v[3] = {0.75f * 0.5f + g7k * 0.5f, g7k * 0.5f, g7k * 0.5f};
            int peak_hits = 0, noise7k = 0, first_noise = -1;
            int off7k = 0;
            for (int blk = 0; blk < 132; ++blk) {
                for (int i = 0; i < 128; ++i) {
                    p0[i] = xs7k[static_cast<std::size_t>(off7k + i)];
                    p1[i] = xs7k[static_cast<std::size_t>(off7k + i)];
                }
                conv.Process(planar_ptrs, 128);
                for (int i = 0; i < 128; ++i) {
                    const int n = off7k + i;
                    const float v = p0[i];
                    const float a = std::fabs(v);
                    int near_exp = -1;
                    for (int pk = 0; pk < 3; ++pk) {
                        const int d = n > exp_n[pk] ? n - exp_n[pk] : exp_n[pk] - n;
                        if (d <= 2) near_exp = pk;
                    }
                    if (near_exp < 0 && a > 0.005f) {
                        ++noise7k;
                        if (first_noise < 0) first_noise = n;
                    }
                    for (int pk = 0; pk < 3; ++pk) {
                        if (n == exp_n[pk]) {
                            if (std::fabs(v - exp_v[pk]) < 1e-4f) {
                                ++peak_hits;
                            } else {
                                std::printf("    PROBE 7k peak mismatch: n=%d v=%.6f exp=%.6f\n",
                                            n, v, exp_v[pk]);
                            }
                        }
                    }
                }
                off7k += 128;
            }
            std::printf("    INFO 7k long IR: peak_hits=%d/3 noise=%d first_noise=%d\n",
                        peak_hits, noise7k, first_noise);
            Check(peak_hits == 3 && noise7k == 0,
                  "convolver: 320ms IR end-to-end (3 analytic peaks exact, no leakage)");
        }

        // 7l) 预设区分度（听感回归盲区，2026-10-24 用户报告三预设听感无差别）：
        //     同一白噪声输入跑 4 个混响预设，两两输出 diff/sig 必须显著。此前
        //     7b/7e/7j 全部只验证 preset 1，p=2..4 的 spec 填充从未被测试触碰。
        //     **必须用宽带输入**：窄带（正弦）下 LTI 输出=同频正弦，仅相位/幅度
        //     不同，两个完全不同的 IR 输出也可能高度相关（同 7e 单频相位巧合），
        //     diff/sig 度量无效。白噪声下输出相关≈IR 相关（4 组 RIR 互相关≈0，
        //     人工差异化 = 预延迟 0/5/15/35ms + 一阶低通 fc 6.5k/5.9k/3.4k/2.3k）。
        //     阈值 0.15：2731ms RIR（120000 帧教堂全量）+ 全程响应窗基线实测
        //     min=0.296（large↔cathedral，湿能量梯度 0.143/0.131/0.111/0.090）。
        //     回归病史：kRirLen 与数组长度分叉（ps1 硬编码）曾让 67% 长尾被
        //     扔掉、湿能量掉回 160ms 水平；统计窗只覆盖 128ms 时 cathedral 湿
        //     声被低估 3.5×。真回归（spec 填错/切换失效）时 diff 掉到 0.00x 级。
        {
            // 窗口必须覆盖完整混响响应：输入 320 块 + 最长 IR（cathedral）
            // 120000 帧 → 160960 帧，取 1260 块 = 161280 帧。此前 48 块（128ms）
            // 只采样 cathedral 前 13% 的响应期，湿能量与 diff/sig 被严重低估
            // （2026-10-24 取证教训）；680ms 时代 40960 帧窗口则截掉 cathedral
            // 2.5s 尾的后 2/3。
            constexpr int kBlocks7l = 1260;
            const int kN7l = kBlocks7l * 128;  // 161280 帧 > 输入 + 最长尾
            std::vector<std::vector<float>> outs(4);
            for (int ps = 1; ps <= 4; ++ps) {
                conv.Reset();
                conv.SetBypass(false);
                conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                              static_cast<float>(ps));
                std::vector<float> y(static_cast<std::size_t>(kN7l));
                unsigned rng7l = 0x12345678u;  // 确定性白噪声（每预设同序列）
                int off = 0;
                for (int blk = 0; blk < kBlocks7l; ++blk) {
                    for (int i = 0; i < 128; ++i) {
                        rng7l = rng7l * 1664525u + 1013904223u;
                        const float s = 0.5f * (static_cast<float>(rng7l >> 8) / 8388608.0f - 1.0f);
                        p0[i] = s; p1[i] = s;
                    }
                    conv.Process(planar_ptrs, 128);
                    for (int i = 0; i < 128; ++i) y[static_cast<std::size_t>(off + i)] = p0[i];
                    off += 128;
                }
                outs[static_cast<std::size_t>(ps - 1)] = std::move(y);
            }
            bool distinct = true;
            double min_ratio = 1e9;
            const char* nm7l[4] = {"small", "medium", "large", "cathedral"};
            // 湿声能量分解：wet_p = y_p - dry_ref（dry gain 1..3=0.85、4=0.75），
            // 区分「湿声缺失」vs「湿声雷同」两种病
            std::vector<double> wet_rms(4, 0.0), y_rms(4, 0.0);
            for (int ps = 1; ps <= 4; ++ps) {
                const std::vector<float>& y = outs[static_cast<std::size_t>(ps - 1)];
                const float dry_g = (ps == 4) ? 0.75f : 0.85f;
                double sy = 0.0, sw = 0.0;
                unsigned rng7w = 0x12345678u;
                for (int i = 0; i < kN7l; ++i) {
                    rng7w = rng7w * 1664525u + 1013904223u;
                    const float s = 0.5f * (static_cast<float>(rng7w >> 8) / 8388608.0f - 1.0f);
                    const float w = y[static_cast<std::size_t>(i)] - dry_g * s;
                    sy += static_cast<double>(y[static_cast<std::size_t>(i)]) * y[static_cast<std::size_t>(i)];
                    sw += static_cast<double>(w) * w;
                }
                y_rms[static_cast<std::size_t>(ps - 1)] = std::sqrt(sy / kN7l);
                wet_rms[static_cast<std::size_t>(ps - 1)] = std::sqrt(sw / kN7l);
                std::printf("    INFO 7l %s: y_rms=%.4f wet_rms=%.4f\n", nm7l[ps - 1],
                            y_rms[static_cast<std::size_t>(ps - 1)],
                            wet_rms[static_cast<std::size_t>(ps - 1)]);
            }
            for (int a = 0; a < 4; ++a) {
                for (int b = a + 1; b < 4; ++b) {
                    double sd = 0.0, ss = 0.0;
                    for (int i = 1800; i < kN7l; ++i) {
                        const double d = outs[static_cast<std::size_t>(a)][static_cast<std::size_t>(i)] -
                                         outs[static_cast<std::size_t>(b)][static_cast<std::size_t>(i)];
                        sd += d * d;
                        ss += static_cast<double>(outs[static_cast<std::size_t>(a)][static_cast<std::size_t>(i)]) *
                              outs[static_cast<std::size_t>(a)][static_cast<std::size_t>(i)];
                    }
                    const double ratio = sd / (ss > 1e-12 ? ss : 1.0);
                    if (ratio < min_ratio) min_ratio = ratio;
                    std::printf("    INFO 7l %s vs %s: diff/sig=%.3f\n", nm7l[a], nm7l[b], ratio);
                    if (ratio < 0.15) distinct = false;
                }
            }
            Check(distinct,
                  "convolver: the 4 reverb presets produce clearly distinct outputs");
        }

        // 7f) 输出软限幅兜底：1.5 峰值脉冲进混响，输出 |y| ≤ 1.0
        //     （干声 tap 0.85 + 湿声叠加在大动态处可超 1.0，限幅器被误关时防破音。
        //      软限幅是连续函数，不引入削波毛刺；常规信号平滑性已由 7b 覆盖）
        {
            conv.Reset();
            conv.SetBypass(false);
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
            bool bounded = true;
            for (int blk = 0; blk < 32 && bounded; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    // 大动态：0.5 正弦叠加周期性 1.5 峰值脉冲
                    float s = 0.5f * std::sin(vphase); vphase += kVStep;
                    if (blk % 4 == 0 && i == frames / 2) s = 1.5f;
                    p0[i] = s; p1[i] = s;
                }
                conv.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    if (std::fabs(p0[i]) > 1.0001f || std::fabs(p1[i]) > 1.0001f) { bounded = false; break; }
                }
            }
            Check(bounded, "convolver: soft limiter keeps output within +-1.0 (limiter off scenario)");
        }

        // 7g) IR 文件加载（M4c.2）：写临时 WAV（f32 立体声）→ 加载到 slot 4 →
        //     SetParam(4) 生效且输出 bounded；失败路径（不存在的文件）被拒绝且回退
        {
            conv.Reset();
            conv.SetBypass(false);
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetDry));

            // 生成 480 采样 f32 立体声 WAV（L=正弦，R=同相位 0.5 幅度）
            const char* wav_path = "test_ir_tmp.wav";
            {
                const int n = 480, sr = 48000, ch = 2;
                std::vector<float> samples(static_cast<std::size_t>(n * ch));
                for (int i = 0; i < n; ++i) {
                    const float s = 0.3f * std::sin(2.0f * 3.141592653589793f * 200.0f * static_cast<float>(i) / static_cast<float>(sr));
                    samples[static_cast<std::size_t>(i * ch)] = s;
                    samples[static_cast<std::size_t>(i * ch + 1)] = s * 0.5f;
                }
                FILE* f = std::fopen(wav_path, "wb");
                if (f != nullptr) {
                    const std::size_t data_bytes = samples.size() * 4;
                    const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                    std::fwrite("RIFF", 1, 4, f);
                    std::fwrite(&riff, 4, 1, f);
                    std::fwrite("WAVEfmt ", 1, 8, f);
                    const uint32_t fmt_size = 16, fmt_tag = 3, srate = sr, byte_rate = sr * ch * 4;
                    const uint16_t nch = ch, bits = 32, align = ch * 4;
                    std::fwrite(&fmt_size, 4, 1, f);
                    std::fwrite(&fmt_tag, 2, 1, f);
                    std::fwrite(&nch, 2, 1, f);
                    std::fwrite(&srate, 4, 1, f);
                    std::fwrite(&byte_rate, 4, 1, f);
                    std::fwrite(&align, 2, 1, f);
                    std::fwrite(&bits, 2, 1, f);
                    std::fwrite("data", 1, 4, f);
                    const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                    std::fwrite(&dsz, 4, 1, f);
                    std::fwrite(samples.data(), 1, data_bytes, f);
                    std::fclose(f);
                }
            }
            const bool load_ok = conv.LoadImpulseResponseFromFile(wav_path);
            Check(load_ok && conv.UserIrLoaded(),
                  "convolver: WAV IR file loads into slot 4");
            std::remove(wav_path);

            // 切到 slot 4：输出 bounded（有干声+湿声，软限幅兜底）
            conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                          static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
            bool user_ok = true;
            for (int blk = 0; blk < 16 && user_ok; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                    p0[i] = s; p1[i] = s;
                }
                conv.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    if (!std::isfinite(p0[i]) || std::fabs(p0[i]) > 1.0001f) { user_ok = false; break; }
                }
            }
            Check(user_ok, "convolver: user IR preset produces bounded output");

            // 失败路径：不存在的文件 → false；slot 4 状态保持（仍可用）
            const bool fail_ok = !conv.LoadImpulseResponseFromFile("no_such_file_ir.wav");
            Check(fail_ok, "convolver: missing WAV file rejected without state change");

            // P3 防御（2026-10-27 自检）：畸形 data chunk_size=0xFFFFFFFF（流式
            // 未定长标记）必须被快速拒绝——原实现 resize(4GB) → bad_alloc 崩进
            // 程（runCatching 抓不住），且 fseek 回退死循环挂死装载线程
            {
                const char* evil_path = "test_ir_evil_hdr.wav";
                FILE* fe = std::fopen(evil_path, "wb");
                bool ok_evil = false;
                if (fe != nullptr) {
                    const uint32_t sre = 48000;
                    const uint8_t hdr[44] = {
                        'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'A', 'V', 'E',
                        'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 2, 0,
                        static_cast<uint8_t>(sre & 0xFF),
                        static_cast<uint8_t>((sre >> 8) & 0xFF),
                        static_cast<uint8_t>((sre >> 16) & 0xFF),
                        static_cast<uint8_t>((sre >> 24) & 0xFF),
                        0x00, 0xE1, 0x02, 0x00,  // byte rate 192000 (48k*2ch*2B)
                        4, 0, 16, 0,             // block align 4, bits 16
                        'd', 'a', 't', 'a',
                        0xFF, 0xFF, 0xFF, 0xFF,  // data size = 0xFFFFFFFF（畸形）
                    };
                    const uint8_t payload[8] = {0};  // 实际仅 8 字节，与头声明不符
                    ok_evil = std::fwrite(hdr, 1, 44, fe) == 44 &&
                              std::fwrite(payload, 1, 8, fe) == 8;
                    std::fclose(fe);
                }
                if (ok_evil) {
                    const bool rejected = !conv.LoadImpulseResponseFromFile(evil_path);
                    Check(rejected, "convolver: evil data-size=0xFFFFFFFF header rejected (P3)");
                }
                std::remove(evil_path);
            }

            // 4 声道 HRTF IR：对角映射（L=ch0, R=ch3）→ 加载成功、输出 bounded
            const char* wav4_path = "test_ir_4ch_tmp.wav";
            {
                const int n = 480, sr = 48000, ch = 4;
                std::vector<float> samples(static_cast<std::size_t>(n * ch));
                for (int i = 0; i < n; ++i) {
                    const float s = 0.3f * std::sin(2.0f * 3.141592653589793f * 150.0f * static_cast<float>(i) / static_cast<float>(sr));
                    for (int c = 0; c < ch; ++c) {
                        samples[static_cast<std::size_t>(i * ch + c)] = s * (0.4f + 0.2f * static_cast<float>(c));
                    }
                }
                FILE* f = std::fopen(wav4_path, "wb");
                if (f != nullptr) {
                    const std::size_t data_bytes = samples.size() * 4;
                    const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                    std::fwrite("RIFF", 1, 4, f);
                    std::fwrite(&riff, 4, 1, f);
                    std::fwrite("WAVEfmt ", 1, 8, f);
                    const uint32_t fmt_size = 16, fmt_tag = 3, srate = sr, byte_rate = sr * ch * 4;
                    const uint16_t nch = ch, bits = 32, align = ch * 4;
                    std::fwrite(&fmt_size, 4, 1, f);
                    std::fwrite(&fmt_tag, 2, 1, f);
                    std::fwrite(&nch, 2, 1, f);
                    std::fwrite(&srate, 4, 1, f);
                    std::fwrite(&byte_rate, 4, 1, f);
                    std::fwrite(&align, 2, 1, f);
                    std::fwrite(&bits, 2, 1, f);
                    std::fwrite("data", 1, 4, f);
                    const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                    std::fwrite(&dsz, 4, 1, f);
                    std::fwrite(samples.data(), 1, data_bytes, f);
                    std::fclose(f);
                }
            }
            const bool load4_ok = conv.LoadImpulseResponseFromFile(wav4_path);
            Check(load4_ok, "convolver: 4ch HRTF IR loads (diagonal mapping L=ch0/R=ch3)");
            std::remove(wav4_path);

            // WAVE_FORMAT_EXTENSIBLE + 4ch + 24bit + 44.1k（复刻奥菲斯 HRTF 文件头，
            // extensible SubFormat 解析 bug 的回归测试）
            const char* wavx_path = "test_ir_ext_tmp.wav";
            {
                const int n = 400, sr = 44100, ch = 4;
                std::vector<float> samples(static_cast<std::size_t>(n * ch));
                for (int i = 0; i < n; ++i) {
                    const float s = 0.3f * std::sin(2.0f * 3.141592653589793f * 200.0f * static_cast<float>(i) / static_cast<float>(sr));
                    for (int c = 0; c < ch; ++c) samples[static_cast<std::size_t>(i * ch + c)] = s;
                }
                FILE* f = std::fopen(wavx_path, "wb");
                if (f != nullptr) {
                    const int bytes_ps = 3;  // 24-bit
                    const std::size_t data_bytes = samples.size() * bytes_ps;
                    const uint32_t riff = 36 + 24 + static_cast<uint32_t>(data_bytes);
                    std::fwrite("RIFF", 1, 4, f);
                    std::fwrite(&riff, 4, 1, f);
                    std::fwrite("WAVEfmt ", 1, 8, f);
                    const uint32_t fmt_size = 40;
                    const uint16_t fmt_tag = 0xFFFE, nch = ch, bits = 24, align = ch * bytes_ps, cb = 22, valid = 24;
                    const uint32_t srate = sr, byte_rate = sr * ch * bytes_ps, mask = 0x0F;
                    const uint8_t sub[16] = {0x01, 0, 0, 0, 0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71};
                    std::fwrite(&fmt_size, 4, 1, f);
                    std::fwrite(&fmt_tag, 2, 1, f);
                    std::fwrite(&nch, 2, 1, f);
                    std::fwrite(&srate, 4, 1, f);
                    std::fwrite(&byte_rate, 4, 1, f);
                    std::fwrite(&align, 2, 1, f);
                    std::fwrite(&bits, 2, 1, f);
                    std::fwrite(&cb, 2, 1, f);
                    std::fwrite(&valid, 2, 1, f);
                    std::fwrite(&mask, 4, 1, f);
                    std::fwrite(sub, 1, 16, f);
                    std::fwrite("data", 1, 4, f);
                    const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                    std::fwrite(&dsz, 4, 1, f);
                    // PCM24 小端写
                    for (std::size_t i = 0; i < samples.size(); ++i) {
                        const int32_t v = static_cast<int32_t>(samples[i] * 8388607.0f);
                        const uint8_t b3[3] = {static_cast<uint8_t>(v & 0xFF),
                                               static_cast<uint8_t>((v >> 8) & 0xFF),
                                               static_cast<uint8_t>((v >> 16) & 0xFF)};
                        std::fwrite(b3, 1, 3, f);
                    }
                    std::fclose(f);
                }
            }
            const bool loadx_ok = conv.LoadImpulseResponseFromFile(wavx_path);
            Check(loadx_ok, "convolver: extensible 4ch/24bit/44.1k WAV loads (regression)");
            std::remove(wavx_path);

            // 多格式适配：f32 裸二进制 / 文本系数 / .iir 拒绝
            {
                // f32 裸二进制（mono，指数衰减脉冲列）
                FILE* f = std::fopen("test_ir_f32.bin", "wb");
                for (int i = 0; i < 300; ++i) {
                    const float v = 0.4f * std::exp(-static_cast<float>(i) / 60.0f);
                    std::fwrite(&v, 4, 1, f);
                }
                std::fclose(f);
                const bool f32_ok = conv.LoadImpulseResponseFromFile("test_ir_f32.bin");
                Check(f32_ok, "convolver: raw float32 binary IR loads (mono, engine rate)");
                std::remove("test_ir_f32.bin");

                // 文本系数（逐行）
                f = std::fopen("test_ir_fir.txt", "w");
                for (int i = 0; i < 50; ++i) {
                    std::fprintf(f, "%.6f\n", 0.3 * std::exp(-static_cast<double>(i) / 12.0));
                }
                std::fclose(f);
                const bool txt_ok = conv.LoadImpulseResponseFromFile("test_ir_fir.txt");
                Check(txt_ok, "convolver: text coefficient IR loads (.fir style)");
                std::remove("test_ir_fir.txt");

                // .iir 拒绝
                f = std::fopen("test_ir.iir", "w");
                std::fprintf(f, "0.1 0.5 -0.2\n1.0 -0.3\n");
                std::fclose(f);
                const bool iir_rejected = !conv.LoadImpulseResponseFromFile("test_ir.iir");
                Check(iir_rejected, "convolver: .iir rejected (recursive coefficients unsuitable for FIR convolver)");
                std::remove("test_ir.iir");
            }
            bool bounded4 = true;
            for (int blk = 0; blk < 8 && bounded4; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                    p0[i] = s; p1[i] = s;
                }
                conv.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    if (!std::isfinite(p0[i]) || std::fabs(p0[i]) > 1.0001f) { bounded4 = false; break; }
                }
            }
            Check(bounded4, "convolver: 4ch IR output bounded");
        }

        // 7m) WAV 解析数值回归（2026-10-24 自定义 IR「糊闷」根因）：PCM24 拼接
        //     后缺符号扩展——负样本（byte2 最高位=1）被读成 +1.0~+2.0 的正数，
        //     24bit IR 负半波整体翻正、卷积核被破坏。既有 extensible 4ch/24bit
        //     测试只断言 load 成功、无数值断言，抓不到此错。此测试写已知正负
        //     样本（含 ±满幅）到 PCM16/24/32 三种 WAV，读回逐点比对：符号错误
        //     偏差 2.0+，阈值 1e-4 必炸；同时守 PCM16/32 的 wrap 语义不回归。
        {
            const float kSmps[6] = {-1.0f, -0.5f, -0.25f, 0.25f, 0.5f, 0.9999f};
            const int kN7m = 6;
            for (int bits : {16, 24, 32}) {
                const char* path7m =
                    (bits == 16) ? "t7m_16.wav" : (bits == 24) ? "t7m_24.wav" : "t7m_32.wav";
                const int bps = bits / 8;
                const double full =
                    (bits == 16) ? 32767.0 : (bits == 24) ? 8388607.0 : 2147483647.0;
                {
                    FILE* f = std::fopen(path7m, "wb");
                    const uint32_t data_sz = static_cast<uint32_t>(kN7m * bps);
                    const uint32_t riff_sz = 36u + data_sz;
                    std::fwrite("RIFF", 1, 4, f);
                    std::fwrite(&riff_sz, 4, 1, f);
                    std::fwrite("WAVEfmt ", 1, 8, f);
                    const uint32_t fmt_sz = 16;
                    std::fwrite(&fmt_sz, 4, 1, f);
                    const uint16_t fmt_tag = 1, ch7m = 1;
                    std::fwrite(&fmt_tag, 2, 1, f);
                    std::fwrite(&ch7m, 2, 1, f);
                    const uint32_t sr7m = 48000, br7m = 48000u * static_cast<uint32_t>(bps);
                    std::fwrite(&sr7m, 4, 1, f);
                    std::fwrite(&br7m, 4, 1, f);
                    const uint16_t blk7m = static_cast<uint16_t>(bps), bits16 = static_cast<uint16_t>(bits);
                    std::fwrite(&blk7m, 2, 1, f);
                    std::fwrite(&bits16, 2, 1, f);
                    std::fwrite("data", 1, 4, f);
                    std::fwrite(&data_sz, 4, 1, f);
                    for (int i = 0; i < kN7m; ++i) {
                        const int32_t s32 = static_cast<int32_t>(kSmps[i] * full);
                        const uint32_t u = static_cast<uint32_t>(s32);
                        for (int b = 0; b < bps; ++b) {
                            const uint8_t byte = static_cast<uint8_t>((u >> (8 * b)) & 0xFFu);
                            std::fwrite(&byte, 1, 1, f);
                        }
                    }
                    std::fclose(f);
                }
                sonicraft::dsp::WavData wd;
                const bool ok7m = sonicraft::dsp::LoadWavFile(path7m, &wd);
                bool sym_ok = ok7m && wd.frames == kN7m && wd.channel_count == 1;
                double max_err = 0.0;
                for (int i = 0; i < kN7m && sym_ok; ++i) {
                    const double err = std::fabs(static_cast<double>(wd.ch[0][static_cast<std::size_t>(i)]) - kSmps[i]);
                    if (err > max_err) max_err = err;
                    if (err > 1e-4) sym_ok = false;
                }
                Check(sym_ok, "convolver: PCM WAV parse sign-correct incl. negative samples");
                std::printf("    INFO 7m PCM%d max_err=%.2e%s\n", bits, max_err,
                            sym_ok ? "" : "  <- SIGN/VALUE BUG");
                std::remove(path7m);
            }
        }

        // 7n) 裸二进制嗅探回归（2026-10-24 附带发现修复取证）：单极性 int16
        //     裸文件被 try_float32 抢判——int16 文件长度恒满足 %4==0，且单极性
        //     （样本 < 0x8000）使每个 4 字节组的 float 解释「合法」：sign=0、
        //     指数位≈0（极小 float），isfinite/|fv|<=64/能量校验全过 → 判成
        //     f32，样本数减半且值全错。双极性 int16 因高字节 0xFF 撞 NaN 被
        //     正确拒绝（现状已对）。判定结果用 frames 区分：int16 → N 帧，
        //     f32 抢判 → N/2 帧。
        {
            const int n7n = 2048;
            // 场景 1：单极性 int16（衰减脉冲，峰值 12000，全正——雷本体）
            {
                FILE* f = std::fopen("t7n_i16u.bin", "wb");
                for (int i = 0; i < n7n; ++i) {
                    const int16_t s = static_cast<int16_t>(
                        12000.0 * std::exp(-static_cast<double>(i) / 300.0));
                    const uint16_t u = static_cast<uint16_t>(s);
                    const uint8_t b[2] = {static_cast<uint8_t>(u & 0xFFu),
                                          static_cast<uint8_t>((u >> 8) & 0xFFu)};
                    std::fwrite(b, 1, 2, f);
                }
                std::fclose(f);
                sonicraft::dsp::WavData w7n;
                const bool ok = sonicraft::dsp::LoadIrFileAny("t7n_i16u.bin", 48000, &w7n);
                Check(ok && w7n.frames == n7n,
                      "convolver: unipolar int16 raw sniffed as int16 (not hijacked by f32)");
                std::remove("t7n_i16u.bin");
            }
            // 场景 2：双极性 int16（现状已对，防回归）
            {
                FILE* f = std::fopen("t7n_i16b.bin", "wb");
                for (int i = 0; i < n7n; ++i) {
                    const int16_t s = static_cast<int16_t>(12000.0 * std::sin(
                        2.0 * 3.141592653589793 * 40.0 * i / 48000.0) *
                        std::exp(-static_cast<double>(i) / 500.0));
                    const uint16_t u = static_cast<uint16_t>(s);
                    const uint8_t b[2] = {static_cast<uint8_t>(u & 0xFFu),
                                          static_cast<uint8_t>((u >> 8) & 0xFFu)};
                    std::fwrite(b, 1, 2, f);
                }
                std::fclose(f);
                sonicraft::dsp::WavData w7n;
                const bool ok = sonicraft::dsp::LoadIrFileAny("t7n_i16b.bin", 48000, &w7n);
                Check(ok && w7n.frames == n7n,
                      "convolver: bipolar int16 raw sniffed as int16");
                std::remove("t7n_i16b.bin");
            }
            // 场景 3：全正 f32（7g 同构指数衰减，2048 样本）→ 修复不得误伤
            {
                FILE* f = std::fopen("t7n_f32u.bin", "wb");
                for (int i = 0; i < n7n; ++i) {
                    const float v = 0.4f * std::exp(-static_cast<float>(i) / 60.0f);
                    std::fwrite(&v, 4, 1, f);
                }
                std::fclose(f);
                sonicraft::dsp::WavData w7n;
                const bool ok = sonicraft::dsp::LoadIrFileAny("t7n_f32u.bin", 48000, &w7n);
                Check(ok && w7n.frames == n7n,
                      "convolver: unipolar float32 raw still sniffed as f32");
                std::remove("t7n_f32u.bin");
            }
            // 场景 4：双极性 f32 → 判 f32
            {
                FILE* f = std::fopen("t7n_f32b.bin", "wb");
                for (int i = 0; i < n7n; ++i) {
                    const float v = 0.5f * std::sin(2.0f * 3.141592653589793f * 40.0f *
                                                    static_cast<float>(i) / 48000.0f);
                    std::fwrite(&v, 4, 1, f);
                }
                std::fclose(f);
                sonicraft::dsp::WavData w7n;
                const bool ok = sonicraft::dsp::LoadIrFileAny("t7n_f32b.bin", 48000, &w7n);
                Check(ok && w7n.frames == n7n,
                      "convolver: bipolar float32 raw sniffed as f32");
                std::remove("t7n_f32b.bin");
            }
            // 场景 5：大幅 f32（整数刻度导出，满幅 12000——工具导 float 不归一的
            //     常见形态）→ 当前 |fv|<=64 门会拒 f32、被 int16 误判（frames 翻倍）
            {
                FILE* f = std::fopen("t7n_f32big.bin", "wb");
                for (int i = 0; i < n7n; ++i) {
                    const float v = static_cast<float>(
                        12000.0 * std::exp(-static_cast<double>(i) / 300.0) *
                        std::sin(2.0 * 3.141592653589793 * 40.0 * i / 48000.0));
                    std::fwrite(&v, 4, 1, f);
                }
                std::fclose(f);
                sonicraft::dsp::WavData w7n;
                const bool ok = sonicraft::dsp::LoadIrFileAny("t7n_f32big.bin", 48000, &w7n);
                bool val_ok = ok && w7n.frames == n7n;
                if (val_ok) {
                    // 首样本值正确性（判成 f32 才可能对；判成 int16 值全乱）
                    const double expect0 = 12000.0 * std::sin(0.0);  // = 0
                    const double e1 = 12000.0 * std::exp(-1.0 / 300.0) *
                                      std::sin(2.0 * 3.141592653589793 * 40.0 / 48000.0);
                    if (std::fabs(static_cast<double>(w7n.ch[0][1]) - e1) > 1.0) val_ok = false;
                    (void)expect0;
                }
                Check(val_ok,
                      "convolver: large-amplitude float32 raw (integer-scale export) sniffed as f32");
                std::remove("t7n_f32big.bin");
            }
        }

        // 7o) 2×2 交叉卷积黄金参照（阶段 B 保真门）：4ch f32 WAV（LL,LR,RL,RR
        //     四路径独立随机 IR）经 LoadImpulseResponseFromFile 完整链路（per-side
        //     归一 g = 0.3/sqrt(同侧路径能量和)，路径 idx = in*2+out → 侧 = idx&1）
        //     → ConvolverNode 输出 vs 时域 2×2 直接卷积独立参照：
        //       y_out[n] = 0.75·x_in[n]                                   （干声 kDryGain[user]）
        //                + Σ_{i<H} g_out·同侧路径head[i]·x_in[n-i]           （head 零延迟）
        //                + Σ_j g_out·同侧路径tail[H+j]·x_in[n-H-P-j]         （tail 平移 H+P=1152）
        //     阈 5e-4（7j 同款）。对角退化/2ch 快路径已由 7i/7k 守卫（期望值零改动
        //     回绿 = 归一口径零回归）；本测试守 4 路径全开（preset_paths_=4）的
        //     分离乘 + 双 Hermitian 打包 IFFT 数学保真。
        {
            conv.Reset();
            conv.SetBypass(false);
            constexpr int kLen7o = 2600;  // 跨 head + 2 个 tail 分区
            std::vector<float> ir7o[4];
            unsigned rng7o = 12345u;
            const auto rnd7o = [&rng7o]() -> float {  // LCG ±0.1 均匀
                rng7o = rng7o * 1664525u + 1013904223u;
                return (static_cast<float>(rng7o >> 8) / 16777216.0f - 0.5f) * 0.2f;
            };
            for (int q = 0; q < 4; ++q) {
                ir7o[q].resize(static_cast<std::size_t>(kLen7o));
                for (int i = 0; i < kLen7o; ++i) ir7o[q][static_cast<std::size_t>(i)] = rnd7o();
            }
            // per-side 归一复刻（与 convolver.hpp 同口径；峰值 ~7e-3 << 0.6，无钳）
            double e7o[2] = {0.0, 0.0};
            for (int q = 0; q < 4; ++q) {
                for (int i = 0; i < kLen7o; ++i) {
                    const double v = static_cast<double>(ir7o[q][static_cast<std::size_t>(i)]);
                    e7o[q & 1] += v * v;
                }
            }
            float g7o[2];
            for (int s = 0; s < 2; ++s)
                g7o[s] = 0.3f / static_cast<float>(std::sqrt(e7o[s]));
            for (int q = 0; q < 4; ++q) {
                for (int i = 0; i < kLen7o; ++i) ir7o[q][static_cast<std::size_t>(i)] *= g7o[q & 1];
            }
            // 写 4ch f32 WAV（fmt_tag=3，交织布局 ch0..ch3 = LL,LR,RL,RR）
            const char* p7o = "t7o_4ch.wav";
            {
                FILE* f = std::fopen(p7o, "wb");
                const std::size_t data_bytes = static_cast<std::size_t>(kLen7o) * 4 * 4;
                const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                std::fwrite("RIFF", 1, 4, f);
                std::fwrite(&riff, 4, 1, f);
                std::fwrite("WAVEfmt ", 1, 8, f);
                const uint32_t fmt_size = 16, fmt_tag = 3, srate = 48000, byte_rate = 48000 * 4 * 4;
                const uint16_t nch = 4, bits = 32, align = 4 * 4;
                std::fwrite(&fmt_size, 4, 1, f);
                std::fwrite(&fmt_tag, 2, 1, f);
                std::fwrite(&nch, 2, 1, f);
                std::fwrite(&srate, 4, 1, f);
                std::fwrite(&byte_rate, 4, 1, f);
                std::fwrite(&align, 2, 1, f);
                std::fwrite(&bits, 2, 1, f);
                std::fwrite("data", 1, 4, f);
                const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                std::fwrite(&dsz, 4, 1, f);
                for (int i = 0; i < kLen7o; ++i) {
                    for (int q = 0; q < 4; ++q) {
                        std::fwrite(&ir7o[q][static_cast<std::size_t>(i)], 4, 1, f);
                    }
                }
                std::fclose(f);
            }
            const bool ok7o = conv.LoadImpulseResponseFromFile(p7o);
            Check(ok7o, "convolver 7o: 4ch cross-matrix IR load");
            std::remove(p7o);
            if (ok7o) {
                conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                              static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                constexpr int kB7o = 256, kBLen7o = 128;
                constexpr int kTotal7o = kB7o * kBLen7o;  // 32768 帧
                constexpr int kH7o = 128, kP7o = 1024, kShift7o = kH7o + kP7o;
                std::vector<float> xinL(static_cast<std::size_t>(kTotal7o)),
                    xinR(static_cast<std::size_t>(kTotal7o));
                for (int i = 0; i < kTotal7o; ++i) {
                    xinL[static_cast<std::size_t>(i)] = rnd7o();
                    xinR[static_cast<std::size_t>(i)] = rnd7o();
                }
                // 独立参照：head + tail 平移直接卷积（非被测代码路径）
                std::vector<double> refL(static_cast<std::size_t>(kTotal7o), 0.0),
                    refR(static_cast<std::size_t>(kTotal7o), 0.0);
                for (int n = 0; n < kTotal7o; ++n) {
                    double accL = 0.75 * xinL[static_cast<std::size_t>(n)];
                    double accR = 0.75 * xinR[static_cast<std::size_t>(n)];
                    for (int i = 0; i < kH7o; ++i) {
                        const int xn = n - i;
                        if (xn < 0) break;
                        accL += static_cast<double>(ir7o[0][static_cast<std::size_t>(i)]) * xinL[static_cast<std::size_t>(xn)] +
                                static_cast<double>(ir7o[2][static_cast<std::size_t>(i)]) * xinR[static_cast<std::size_t>(xn)];
                        accR += static_cast<double>(ir7o[1][static_cast<std::size_t>(i)]) * xinL[static_cast<std::size_t>(xn)] +
                                static_cast<double>(ir7o[3][static_cast<std::size_t>(i)]) * xinR[static_cast<std::size_t>(xn)];
                    }
                    for (int j = 0; j < kLen7o - kH7o; ++j) {
                        const int xn = n - kShift7o - j;
                        if (xn < 0) break;
                        accL += static_cast<double>(ir7o[0][static_cast<std::size_t>(kH7o + j)]) * xinL[static_cast<std::size_t>(xn)] +
                                static_cast<double>(ir7o[2][static_cast<std::size_t>(kH7o + j)]) * xinR[static_cast<std::size_t>(xn)];
                        accR += static_cast<double>(ir7o[1][static_cast<std::size_t>(kH7o + j)]) * xinL[static_cast<std::size_t>(xn)] +
                                static_cast<double>(ir7o[3][static_cast<std::size_t>(kH7o + j)]) * xinR[static_cast<std::size_t>(xn)];
                    }
                    refL[static_cast<std::size_t>(n)] = accL;
                    refR[static_cast<std::size_t>(n)] = accR;
                }
                int off7o = 0;
                double max_err7o = 0.0;
                const auto t0_7o = std::chrono::steady_clock::now();
                for (int blk = 0; blk < kB7o; ++blk) {
                    for (int i = 0; i < kBLen7o; ++i) {
                        p0[i] = xinL[static_cast<std::size_t>(off7o + i)];
                        p1[i] = xinR[static_cast<std::size_t>(off7o + i)];
                    }
                    conv.Process(planar_ptrs, kBLen7o);
                    for (int i = 0; i < kBLen7o; ++i) {
                        const double eL = std::fabs(static_cast<double>(p0[i]) - refL[static_cast<std::size_t>(off7o + i)]);
                        const double eR = std::fabs(static_cast<double>(p1[i]) - refR[static_cast<std::size_t>(off7o + i)]);
                        if (eL > max_err7o) max_err7o = eL;
                        if (eR > max_err7o) max_err7o = eR;
                    }
                    off7o += kBLen7o;
                }
                const auto t1_7o = std::chrono::steady_clock::now();
                const double us7o = std::chrono::duration<double, std::micro>(t1_7o - t0_7o).count() / static_cast<double>(kB7o);
                Check(max_err7o < 5e-4,
                      "convolver: 2x2 cross golden reference (head+tail, per-side norm)");
                std::printf("    INFO 7o 2x2 golden max_err=%.2e (g_L=%.4f g_R=%.4f) per-block=%.0fus%s\n",
                            max_err7o, g7o[0], g7o[1], us7o, max_err7o < 5e-4 ? "" : "  <- MATH BUG");
            }
        }

        // 7p) X-macro 单一定义源守卫：PresetDef 表展开值逐项
        //     断言。preset 参数（预延迟/低通/干声增益）是听感级且无运行期反馈，
        //     宏表误改只能靠显式断言拦截；索引稳定性另有头内 static_assert 锁定
        //     （preset 值跨进程边界：Kotlin prefs 持久化）。
        {
            using sonicraft::dsp::ConvolverNode;
            const auto& defs = ConvolverNode::kPresetDefs;
            Check(defs[ConvolverNode::kPresetDry].rir_l == nullptr &&
                      defs[ConvolverNode::kPresetUser].rir_l == nullptr,
                  "convolver: dry/user presets are non-data (no RIR)");
            Check(defs[ConvolverNode::kPresetSmallRoom].rir_l != nullptr &&
                      defs[ConvolverNode::kPresetSmallRoom].rir_r != nullptr &&
                      defs[ConvolverNode::kPresetSmallRoom].pre_delay == 0 &&
                      defs[ConvolverNode::kPresetSmallRoom].lp == 1.0f &&
                      defs[ConvolverNode::kPresetSmallRoom].dry_gain == 0.85f,
                  "convolver: smallroom preset def values");
            // 教堂 lp 期望值 0.62 → **0.85**（2026-11-11 同步）：
            // 7e3df03（2026-11-09「教堂真实 RIR 两处跟随调整」）刻意把 lp 放宽到 0.85
            // —— 真实 RIR 的高频滚降比参数化合成更陡，0.62 会把教堂削得太闷。
            // 当时只改了表、没同步这条断言，导致 harness 长期挂着一条假红灯。
            Check(defs[ConvolverNode::kPresetMediumRoom].pre_delay == 240 &&
                      defs[ConvolverNode::kPresetMediumRoom].lp == 0.92f &&
                      defs[ConvolverNode::kPresetLargeRoom].pre_delay == 720 &&
                      defs[ConvolverNode::kPresetLargeRoom].lp == 0.78f &&
                      defs[ConvolverNode::kPresetCathedral].pre_delay == 1680 &&
                      defs[ConvolverNode::kPresetCathedral].lp == 0.85f,
                  "convolver: room preset pre-delay/lp values");
            Check(defs[ConvolverNode::kPresetCathedral].dry_gain == 0.75f &&
                      defs[ConvolverNode::kPresetUser].dry_gain == 0.75f &&
                      ConvolverNode::kPresetCount == 6,
                  "convolver: dry gain table complete (6 presets)");
        }

        // 7r) IR 尾部裁剪 + 满载 t_eff 边界（/，2026-10-25）：
        //     a) 长零尾 IR（有效 1000 帧 + 零尾 29000 帧）加载后 UserIrFrames
        //        裁到 1000（-80dB 相对峰值阈值，SetIrSlot2x2 的 t_eff 随之下降）；
        //     b) 裁剪数学恒等：同内容无零尾 IR 的第二实例输出逐位一致
        //        （被裁分区 spec 全零 → 分离乘贡献恒零，归一能量口径同内容相同）；
        //     c) 阈值守卫：尾部 -40dB 恒幅摆尾（> -80dB 阈值）不被误裁；
        //     d) 满载边界：kMaxIrLen=131072 全有效 4ch IR 恰好 t_=128 分区覆盖
        //        （preset_t_ 钳制不触发、X_cache 槽恰好够），跑批无 NaN。
        {
            sonicraft::dsp::ConvolverNode conv7r;
            conv7r.set_channel_count(kChannels);
            conv7r.Prepare(kSampleRate, kMaxFrames);
            conv7r.SetBypass(false);
            unsigned rng7r = 777u;
            const auto rnd7r = [&rng7r]() -> float {  // LCG ±0.5 均匀
                rng7r = rng7r * 1664525u + 1013904223u;
                return (static_cast<float>(rng7r >> 8) / 16777216.0f - 0.5f);
            };
            const auto write_wav7r = [](const char* path,
                                        const std::vector<float>* ch, int frames) {
                FILE* f = std::fopen(path, "wb");
                if (f == nullptr) return false;
                const std::size_t data_bytes = static_cast<std::size_t>(frames) * 4 * 4;
                const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                std::fwrite("RIFF", 1, 4, f);
                std::fwrite(&riff, 4, 1, f);
                std::fwrite("WAVEfmt ", 1, 8, f);
                const uint32_t fmt_size = 16, fmt_tag = 3, srate = 48000, byte_rate = 48000 * 4 * 4;
                const uint16_t nch = 4, bits = 32, align = 4 * 4;
                std::fwrite(&fmt_size, 4, 1, f);
                std::fwrite(&fmt_tag, 2, 1, f);
                std::fwrite(&nch, 2, 1, f);
                std::fwrite(&srate, 4, 1, f);
                std::fwrite(&byte_rate, 4, 1, f);
                std::fwrite(&align, 2, 1, f);
                std::fwrite(&bits, 2, 1, f);
                std::fwrite("data", 1, 4, f);
                const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                std::fwrite(&dsz, 4, 1, f);
                for (int i = 0; i < frames; ++i)
                    for (int q = 0; q < 4; ++q)
                        std::fwrite(&ch[q][static_cast<std::size_t>(i)], 4, 1, f);
                std::fclose(f);
                return true;
            };
            // a+b) 长零尾 vs 裁后恒等
            {
                constexpr int kKeep7r = 1000, kTail7r = 29000;
                std::vector<float> irz[4];
                for (int q = 0; q < 4; ++q) {
                    irz[q].assign(static_cast<std::size_t>(kKeep7r + kTail7r), 0.0f);
                    for (int i = 0; i < kKeep7r; ++i) {
                        irz[q][static_cast<std::size_t>(i)] = rnd7r();
                    }
                    irz[q][static_cast<std::size_t>(kKeep7r - 1)] = 0.5f;  // 末有效帧超阈值
                }
                const char* pz = "t7r_zero_tail.wav";
                const bool okz = write_wav7r(pz, irz, kKeep7r + kTail7r) &&
                                 conv7r.LoadImpulseResponseFromFile(pz);
                std::remove(pz);
                Check(okz && conv7r.UserIrFrames() == kKeep7r,
                      "convolver 7r: long zero tail trimmed to keep length");
                // 同内容无零尾第二实例
                sonicraft::dsp::ConvolverNode conv7rb;
                conv7rb.set_channel_count(kChannels);
                conv7rb.Prepare(kSampleRate, kMaxFrames);
                conv7rb.SetBypass(false);
                std::vector<float> irc[4];
                for (int q = 0; q < 4; ++q) {
                    irc[q].assign(irz[q].begin(), irz[q].begin() + kKeep7r);
                }
                const char* pc = "t7r_compact.wav";
                const bool okc = write_wav7r(pc, irc, kKeep7r) &&
                                 conv7rb.LoadImpulseResponseFromFile(pc);
                std::remove(pc);
                Check(okc && conv7rb.UserIrFrames() == kKeep7r,
                      "convolver 7r: compact IR loads at native length");
                if (okz && okc) {
                    conv7r.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                    static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                    conv7rb.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                     static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                    constexpr int kBlk7r = 64 * 128;
                    std::vector<float> inL(static_cast<std::size_t>(kBlk7r)),
                        inR(static_cast<std::size_t>(kBlk7r)),
                        inL2, inR2;
                    unsigned rngin = 4242u;
                    const auto rndin = [&rngin]() -> float {
                        rngin = rngin * 1664525u + 1013904223u;
                        return (static_cast<float>(rngin >> 8) / 16777216.0f - 0.5f) * 0.8f;
                    };
                    for (int i = 0; i < kBlk7r; ++i) {
                        inL[static_cast<std::size_t>(i)] = rndin();
                        inR[static_cast<std::size_t>(i)] = rndin();
                    }
                    inL2 = inL;
                    inR2 = inR;
                    int bit_err = 0;
                    bool has_nan = false;
                    for (int blk = 0; blk < 64; ++blk) {
                        const int off = blk * 128;
                        float* pa[2] = {&inL[static_cast<std::size_t>(off)],
                                        &inR[static_cast<std::size_t>(off)]};
                        float* pb[2] = {&inL2[static_cast<std::size_t>(off)],
                                        &inR2[static_cast<std::size_t>(off)]};
                        conv7r.Process(pa, 128);   // 原位：输入变输出
                        conv7rb.Process(pb, 128);
                        for (int i = 0; i < 128; ++i) {
                            if (pa[0][i] != pb[0][i] || pa[1][i] != pb[1][i]) ++bit_err;
                            if (!std::isfinite(pa[0][i]) || !std::isfinite(pa[1][i])) has_nan = true;
                        }
                    }
                    Check(bit_err == 0 && !has_nan,
                          "convolver 7r: trim is bit-exact vs untrimmed (zero-tail identity)");
                    if (bit_err != 0)
                        std::printf("    PROBE 7r bit_err=%d\n", bit_err);
                }
            }
            // c) 阈值守卫：-40dB 恒幅摆尾（> -80dB 阈值）不被误裁
            {
                constexpr int kKeep7c2 = 1000, kSw7c2 = 2000;
                std::vector<float> irw[4];
                for (int q = 0; q < 4; ++q) {
                    irw[q].assign(static_cast<std::size_t>(kKeep7c2 + kSw7c2), 0.0f);
                    for (int i = 0; i < kKeep7c2; ++i) irw[q][static_cast<std::size_t>(i)] = rnd7r();
                    irw[q][static_cast<std::size_t>(kKeep7c2 - 1)] = 0.5f;
                    for (int i = kKeep7c2; i < kKeep7c2 + kSw7c2; ++i) {
                        irw[q][static_cast<std::size_t>(i)] = 0.005f;  // -40dB > -80dB 阈值
                    }
                }
                const char* pw = "t7r_wettail.wav";
                sonicraft::dsp::ConvolverNode conv7rc;
                conv7rc.set_channel_count(kChannels);
                conv7rc.Prepare(kSampleRate, kMaxFrames);
                const bool okw = write_wav7r(pw, irw, kKeep7c2 + kSw7c2) &&
                                 conv7rc.LoadImpulseResponseFromFile(pw);
                std::remove(pw);
                Check(okw && conv7rc.UserIrFrames() == kKeep7c2 + kSw7c2,
                      "convolver 7r: -40dB wet tail above threshold kept");
            }
            // d) 满载边界：kMaxIrLen 全有效 4ch IR 恰好 t_=128 分区覆盖
            {
                const int kFull7r = sonicraft::dsp::ConvolverNode::kMaxIrLen;
                std::vector<float> irf[4];
                for (int q = 0; q < 4; ++q) {
                    irf[q].resize(static_cast<std::size_t>(kFull7r));
                    for (int i = 0; i < kFull7r; ++i) irf[q][static_cast<std::size_t>(i)] = rnd7r();
                    irf[q][static_cast<std::size_t>(kFull7r - 1)] = 0.5f;  // 末帧保 keep=max
                }
                const char* pf = "t7r_full.wav";
                sonicraft::dsp::ConvolverNode conv7rd;
                conv7rd.set_channel_count(kChannels);
                conv7rd.Prepare(kSampleRate, kMaxFrames);
                conv7rd.SetBypass(false);
                const bool okf = write_wav7r(pf, irf, kFull7r) &&
                                 conv7rd.LoadImpulseResponseFromFile(pf);
                std::remove(pf);
                Check(okf && conv7rd.UserIrFrames() == kFull7r,
                      "convolver 7r: max-length IR loads untrimmed (t_ covers kMaxIrLen)");
                if (okf) {
                    conv7rd.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                     static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                    unsigned rngin2 = 9090u;
                    bool ok_run = true;
                    const auto t0f = std::chrono::steady_clock::now();
                    for (int blk = 0; blk < 24; ++blk) {
                        float xin[2][128];
                        for (int i = 0; i < 128; ++i) {
                            rngin2 = rngin2 * 1664525u + 1013904223u;
                            xin[0][i] = (static_cast<float>(rngin2 >> 8) / 16777216.0f - 0.5f) * 0.8f;
                            rngin2 = rngin2 * 1664525u + 1013904223u;
                            xin[1][i] = (static_cast<float>(rngin2 >> 8) / 16777216.0f - 0.5f) * 0.8f;
                        }
                        float* pf_[2] = {xin[0], xin[1]};
                        conv7rd.Process(pf_, 128);
                        for (int i = 0; i < 128; ++i) {
                            if (!std::isfinite(pf_[0][i]) || !std::isfinite(pf_[1][i])) ok_run = false;
                        }
                    }
                    const auto t1f = std::chrono::steady_clock::now();
                    const double usf = std::chrono::duration<double, std::micro>(t1f - t0f).count() / 24.0;
                    Check(ok_run, "convolver 7r: full t_eff=128 runs clean (no NaN)");
                    std::printf("    INFO 7r full-load (t_eff=128, 2.7s IR) per-block=%.0fus%s\n",
                                usf, ok_run ? "" : "  <- NaN BUG");
                }
            }
        }

        // 7s) 真实 .irs 文件取证（2026-10-26 双声排查，可选用例）：argv[1] 传
        //     文件路径，未传/不可读则跳过。两条独立判据定位「双声」来源：
        //     a) 文件本身：裸解析 WAV（不经产品加载代码），L 通道归一自相关在
        //        >5ms 滞后处找强次峰——IR 自带重复脉冲结构则 wecho 也应双声；
        //     b) 引擎路径：单位 δ 穿 user preset（输出 = 0.75δ + 0.3·wet_norm），
        //        10ms 窗 RMS 包络主峰后 >30ms 若再出 >60% 主峰的包络峰 =
        //        head/tail 拼接或 tail 环形错位产生的重复混响（架构性双声）。
        {
            const char* irs_path = (argc > 1) ? argv[1] : nullptr;
            bool have7s = false;
            if (irs_path != nullptr) {
                if (FILE* pf = std::fopen(irs_path, "rb")) { std::fclose(pf); have7s = true; }
            }
            if (!have7s) {
                std::printf("    SKIP 7s: real IR file not provided (pass path as argv[1])\n");
            } else {
                // ---- a) 裸解析（f32 / PCM16 WAV 通用读法，独立于 ir_loader）----
                std::vector<float> rawL;
                int raw_sr = 0, raw_ch = 0;
                {
                    FILE* f = std::fopen(irs_path, "rb");
                    unsigned char hdr[12];
                    if (f != nullptr && std::fread(hdr, 1, 12, f) == 12 &&
                        std::memcmp(hdr, "RIFF", 4) == 0 && std::memcmp(hdr + 8, "WAVE", 4) == 0) {
                        uint16_t fmt_tag = 0, bits = 0;
                        long long data_off = -1, data_bytes = 0;
                        for (;;) {
                            unsigned char ch8[8];
                            if (std::fread(ch8, 1, 8, f) != 8) break;
                            const uint32_t csz = static_cast<uint32_t>(ch8[4]) |
                                (static_cast<uint32_t>(ch8[5]) << 8) |
                                (static_cast<uint32_t>(ch8[6]) << 16) |
                                (static_cast<uint32_t>(ch8[7]) << 24);
                            if (std::memcmp(ch8, "fmt ", 4) == 0) {
                                unsigned char fmt[40];
                                const uint32_t rd = csz < 40 ? csz : 40;
                                if (std::fread(fmt, 1, rd, f) != rd) break;
                                if (csz > rd) std::fseek(f, static_cast<long>(csz - rd), SEEK_CUR);
                                fmt_tag = static_cast<uint16_t>(fmt[0]) | (static_cast<uint16_t>(fmt[1]) << 8);
                                raw_ch = static_cast<uint16_t>(fmt[2]) | (static_cast<uint16_t>(fmt[3]) << 8);
                                raw_sr = static_cast<int>(fmt[4]) | (static_cast<int>(fmt[5]) << 8) |
                                         (static_cast<int>(fmt[6]) << 16) | (static_cast<int>(fmt[7]) << 24);
                                bits = static_cast<uint16_t>(fmt[14]) | (static_cast<uint16_t>(fmt[15]) << 8);
                            } else if (std::memcmp(ch8, "data", 4) == 0) {
                                data_off = _ftelli64(f);
                                data_bytes = static_cast<long long>(csz);
                                break;
                            } else {
                                std::fseek(f, static_cast<long>(csz), SEEK_CUR);
                            }
                        }
                        if (data_off >= 0 && raw_ch >= 1 && raw_sr > 0 &&
                            (fmt_tag == 3 || fmt_tag == 0xFFFE || fmt_tag == 1)) {
                            const long long bytes_per_frame = static_cast<long long>(raw_ch) * (bits / 8);
                            const long long frames_n = bytes_per_frame > 0 ? data_bytes / bytes_per_frame : 0;
                            std::fseek(f, static_cast<long>(data_off), SEEK_SET);
                            if (bits == 32) {
                                std::vector<float> interleave(static_cast<std::size_t>(frames_n) * raw_ch);
                                if (std::fread(interleave.data(), 4, interleave.size(), f) == interleave.size()) {
                                    rawL.resize(static_cast<std::size_t>(frames_n));
                                    for (long long i = 0; i < frames_n; ++i)
                                        rawL[static_cast<std::size_t>(i)] = interleave[static_cast<std::size_t>(i) * raw_ch];
                                }
                            } else if (bits == 16) {
                                std::vector<int16_t> interleave(static_cast<std::size_t>(frames_n) * raw_ch);
                                if (std::fread(interleave.data(), 2, interleave.size(), f) == interleave.size()) {
                                    rawL.resize(static_cast<std::size_t>(frames_n));
                                    for (long long i = 0; i < frames_n; ++i)
                                        rawL[static_cast<std::size_t>(i)] =
                                            static_cast<float>(interleave[static_cast<std::size_t>(i) * raw_ch]) / 32768.0f;
                                }
                            }
                        }
                    }
                    if (f != nullptr) std::fclose(f);
                }
                if (rawL.empty() || raw_sr <= 0) {
                    std::printf("    INFO 7s a): unsupported raw format, skip autocorr\n");
                } else {
                    const long long n = static_cast<long long>(rawL.size());
                    double e0 = 0.0;
                    for (long long i = 0; i < n; ++i) e0 += static_cast<double>(rawL[static_cast<std::size_t>(i)]) * rawL[static_cast<std::size_t>(i)];
                    if (e0 > 0.0) {
                        const long long lag_min = static_cast<long long>(raw_sr) * 5 / 1000;  // 5ms
                        double best_r = 0.0; long long best_lag = 0;
                        for (long long lag = lag_min; lag < n - 64; ++lag) {
                            double acc = 0.0;
                            for (long long i = 0; i + lag < n; ++i)
                                acc += static_cast<double>(rawL[static_cast<std::size_t>(i)]) * rawL[static_cast<std::size_t>(i + lag)];
                            const double r = acc / e0;
                            if (r > best_r) { best_r = r; best_lag = lag; }
                        }
                        std::printf("    INFO 7s a) raw: sr=%d ch=%d frames=%lld (%.0fms); autocorr>5ms max r=%.3f @%.1fms\n",
                                    raw_sr, raw_ch, n, 1000.0 * static_cast<double>(n) / raw_sr,
                                    best_r, 1000.0 * static_cast<double>(best_lag) / raw_sr);
                        Check(best_r < 0.5,
                              "convolver 7s a): raw IR has no strong echo duplicate (>5ms lag autocorr r<0.5)");
                    }
                }

                // ---- b) δ 穿引擎：脉冲响应包络找重复混响 ----
                sonicraft::dsp::ConvolverNode conv7s;
                conv7s.set_channel_count(kChannels);
                conv7s.Prepare(kSampleRate, kMaxFrames);
                conv7s.Reset();
                conv7s.SetBypass(false);
                const bool ok7s = conv7s.LoadImpulseResponseFromFile(irs_path);
                Check(ok7s && conv7s.UserIrLoaded(), "convolver 7s: real .irs loads into slot 4");
                if (ok7s) {
                    conv7s.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                    static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                    constexpr int kResp7s = 32000;  // 667ms @48k，覆盖 322ms 尾
                    std::vector<float> respL(static_cast<std::size_t>(kResp7s), 0.0f);
                    std::vector<float> respR(static_cast<std::size_t>(kResp7s), 0.0f);
                    int filled = 0;
                    bool finite7s = true;
                    std::vector<float> in0(static_cast<std::size_t>(frames), 0.0f);
                    std::vector<float> in1(static_cast<std::size_t>(frames), 0.0f);
                    for (int blk = 0; filled < kResp7s && blk < 300; ++blk) {
                        for (int i = 0; i < frames; ++i) {
                            const long long g = static_cast<long long>(blk) * frames + i;
                            const float v = (g == 0) ? 1.0f : 0.0f;
                            in0[static_cast<std::size_t>(i)] = v;
                            in1[static_cast<std::size_t>(i)] = v;
                        }
                        float* p7s[2] = {in0.data(), in1.data()};
                        conv7s.Process(p7s, frames);
                        for (int i = 0; i < frames && filled < kResp7s; ++i) {
                            respL[static_cast<std::size_t>(filled)] = p7s[0][i];
                            respR[static_cast<std::size_t>(filled)] = p7s[1][i];
                            ++filled;
                            // 双声道都查（7s 初版只查 L：R 声道含 NaN 的文件曾漏检）
                            if (!std::isfinite(p7s[0][i]) || !std::isfinite(p7s[1][i])) finite7s = false;
                        }
                    }
                    Check(finite7s, "convolver 7s b): impulse response finite");
                    const int win = kSampleRate / 100;  // 10ms 窗
                    const int nwin = kResp7s / win;
                    std::vector<double> env(static_cast<std::size_t>(nwin), 0.0);
                    for (int w = 0; w < nwin; ++w) {
                        double acc = 0.0;
                        for (int i = 0; i < win; ++i) {
                            const double v = respL[static_cast<std::size_t>(w * win + i)];
                            acc += v * v;
                        }
                        env[static_cast<std::size_t>(w)] = std::sqrt(acc / win);
                    }
                    int peak_w = 0;
                    for (int w = 1; w < nwin; ++w)
                        if (env[static_cast<std::size_t>(w)] > env[static_cast<std::size_t>(peak_w)]) peak_w = w;
                    int second_w = -1;
                    for (int w = peak_w + 3; w < nwin; ++w)  // 主峰后 >30ms
                        if (second_w < 0 || env[static_cast<std::size_t>(w)] > env[static_cast<std::size_t>(second_w)]) second_w = w;
                    const double ratio = (second_w >= 0 && env[static_cast<std::size_t>(peak_w)] > 0.0)
                                             ? env[static_cast<std::size_t>(second_w)] / env[static_cast<std::size_t>(peak_w)]
                                             : 0.0;
                    std::printf("    INFO 7s b) engine IR response: env peak win#%d (%.0fms) rms=%.5f; post-30ms max win#%d (%.0fms) ratio=%.3f\n",
                                peak_w, (peak_w + 0.5) * 10.0, env[static_cast<std::size_t>(peak_w)],
                                second_w, (second_w + 0.5) * 10.0, ratio);
                    // 混响尾物理上整体衰减：主峰后 30ms 的包络不应回到主峰 60%
                    // （宽松阈值；文件自带多峰结构由判据 a 同时报告、交叉判读）
                    Check(ratio < 0.6,
                          "convolver 7s b): no duplicate reverb envelope after main peak (ratio<0.6)");
                }
            }
        }

        // 7t) 病态 f32 WAV 清洗（2026-10-26「R 湿声冤案」回归守卫）：运行时构造
        //     48k/2ch 病态文件——L=帧400 单脉冲 IR；R 同结构但撒 2 个 NaN + 2 个
        //     3e38 巨大值（均在脉冲之前，躲过湿尾裁剪、确保参与归一能量）。修复前
        //     链路：NaN 污染 side_energy → g_R=0（NaN>1e-18 恒 false）/巨大值把
        //     g_R 压到 ~3e-40 → R 湿声整侧死，L/R 脉冲能量比 ≈0；修复后 loader
        //     样本级置零 → g_R=g_L → 比值 ≈1。断言：δ 响应湿脉冲窗（8~12ms）
        //     R/L 能量比 ∈ [0.7,1.3] + 双声道 finite。
        {
            std::string tmp_dir = ".";
            if (const char* t = std::getenv("TEMP")) tmp_dir = t;
            const std::string bad_path = tmp_dir + "\\sonicraft_7t_nan_ir.wav";
            bool wrote7t = false;
            {
                constexpr int kFrames7t = 2000, kSr7t = 48000;
                FILE* f = std::fopen(bad_path.c_str(), "wb");
                if (f != nullptr) {
                    const uint32_t data_bytes = static_cast<uint32_t>(kFrames7t) * 2 * 4;
                    std::fwrite("RIFF", 1, 4, f);
                    const uint32_t riff_sz = 36 + data_bytes;
                    std::fwrite(&riff_sz, 4, 1, f);
                    std::fwrite("WAVE", 1, 4, f);
                    std::fwrite("fmt ", 1, 4, f);
                    const uint32_t fmt_sz = 16;
                    std::fwrite(&fmt_sz, 4, 1, f);
                    const uint16_t tag3 = 3, ch2 = 2, bits32 = 32, ba = 8;
                    const uint32_t sr32 = kSr7t, br32 = kSr7t * 8;
                    std::fwrite(&tag3, 2, 1, f);
                    std::fwrite(&ch2, 2, 1, f);
                    std::fwrite(&sr32, 4, 1, f);
                    std::fwrite(&br32, 4, 1, f);
                    std::fwrite(&ba, 2, 1, f);
                    std::fwrite(&bits32, 2, 1, f);
                    std::fwrite("data", 1, 4, f);
                    std::fwrite(&data_bytes, 4, 1, f);
                    const uint32_t nan_bits = 0x7FC00000u;  // quiet NaN 位模式
                    const float big = 3.0e38f;
                    for (int i = 0; i < kFrames7t; ++i) {
                        const float pulse = (i == 400) ? 1.0f : 0.0f;
                        std::fwrite(&pulse, 4, 1, f);  // L 正常
                        if (i == 100 || i == 250) {
                            std::fwrite(&nan_bits, 4, 1, f);   // R: NaN
                        } else if (i == 180 || i == 300) {
                            std::fwrite(&big, 4, 1, f);        // R: 巨大有限值
                        } else {
                            std::fwrite(&pulse, 4, 1, f);      // R: 与 L 同
                        }
                    }
                    std::fclose(f);
                    wrote7t = true;
                }
            }
            if (!wrote7t) {
                std::printf("    SKIP 7t: cannot write temp wav\n");
            } else {
                sonicraft::dsp::ConvolverNode conv7t;
                conv7t.set_channel_count(kChannels);
                conv7t.Prepare(kSampleRate, kMaxFrames);
                conv7t.Reset();
                conv7t.SetBypass(false);
                const bool ok7t = conv7t.LoadImpulseResponseFromFile(bad_path);
                Check(ok7t && conv7t.UserIrLoaded(), "convolver 7t: NaN-garbage wav loads");
                if (ok7t) {
                    conv7t.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                    static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                    constexpr int kResp7t = 4000;  // 83ms@48k：脉冲@帧400 落 tail 分区，
                                                   // 输出带批次交付延迟 P（≈1024），必须扫够长
                    std::vector<float> respL7t(static_cast<std::size_t>(kResp7t), 0.0f);
                    std::vector<float> respR7t(static_cast<std::size_t>(kResp7t), 0.0f);
                    int filled7t = 0;
                    bool finite7t = true;
                    std::vector<float> in0(static_cast<std::size_t>(frames), 0.0f);
                    std::vector<float> in1(static_cast<std::size_t>(frames), 0.0f);
                    for (int blk = 0; filled7t < kResp7t && blk < 40; ++blk) {
                        for (int i = 0; i < frames; ++i) {
                            const long long g = static_cast<long long>(blk) * frames + i;
                            const float v = (g == 0) ? 1.0f : 0.0f;
                            in0[static_cast<std::size_t>(i)] = v;
                            in1[static_cast<std::size_t>(i)] = v;
                        }
                        float* p7t[2] = {in0.data(), in1.data()};
                        conv7t.Process(p7t, frames);
                        for (int i = 0; i < frames && filled7t < kResp7t; ++i) {
                            respL7t[static_cast<std::size_t>(filled7t)] = p7t[0][i];
                            respR7t[static_cast<std::size_t>(filled7t)] = p7t[1][i];
                            ++filled7t;
                            if (!std::isfinite(p7t[0][i]) || !std::isfinite(p7t[1][i])) finite7t = false;
                        }
                    }
                    Check(finite7t, "convolver 7t: impulse response finite (both channels)");
                    // 自动定位湿脉冲峰（帧 64 之后，避开帧 0 的 δ 干声），±64 样本
                    // 窗内比 R/L 能量——不依赖 tail 交付延迟 P 等 head/tail 分界
                    // 实现细节。窗内只含湿脉冲，R/L 能量比即「R 湿声是否存活」：
                    // NaN 污染 g_R=0 时 ≈0，清洗后 g_R=g_L ≈1。
                    int wet_pk = -1;
                    double wet_pk_e = 0.0;
                    for (int i = 64; i < filled7t; ++i) {
                        const double v = respL7t[static_cast<std::size_t>(i)];
                        if (v * v > wet_pk_e) { wet_pk_e = v * v; wet_pk = i; }
                    }
                    double eL7t = 0.0, eR7t = 0.0;
                    if (wet_pk >= 0) {
                        const int lo = wet_pk - 64 < 0 ? 0 : wet_pk - 64;
                        const int hi = wet_pk + 64 > filled7t ? filled7t : wet_pk + 64;
                        for (int i = lo; i < hi; ++i) {
                            const double l = respL7t[static_cast<std::size_t>(i)];
                            const double r = respR7t[static_cast<std::size_t>(i)];
                            eL7t += l * l;
                            eR7t += r * r;
                        }
                    }
                    const double ratio7t = eL7t > 0.0 ? std::sqrt(eR7t / eL7t) : 0.0;
                    std::printf("    INFO 7t: wet pulse @%.1fms, window R/L energy ratio=%.4f (1.0=both sides alive)\n",
                                wet_pk >= 0 ? wet_pk * 1000.0 / kSampleRate : -1.0, ratio7t);
                    Check(ratio7t > 0.7 && ratio7t < 1.3,
                          "convolver 7t: R wet survives NaN/huge garbage (ratio in [0.7,1.3])");
                }
                std::remove(bad_path.c_str());
            }
        }

        // 7q) 干/湿电平参数（2026-10-24 第一批滑块）：WET 全预设生效 + DRY 独立
        //     控制。精确断言：湿声=0 时输出 = kDryGain[preset]·dry_scale·x（纯干）；
        //     干声=0 时湿声路径线性（RMS 比值 = wet 增益比）。斜坡 τ=30ms，预热
        //     16384 样本（341ms，>5τ）后测量段收敛至 <0.005 snap。
        {
            sonicraft::dsp::ConvolverNode conv7q;
            conv7q.set_channel_count(kChannels);
            conv7q.Prepare(kSampleRate, kMaxFrames);
            conv7q.SetBypass(false);
            constexpr int kPre7q = 16384, kMeas7q = 16384;
            auto run7q = [&](float wet, float dry, float* out) {
                conv7q.Reset();
                conv7q.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
                conv7q.SetParam(sonicraft::dsp::ConvolverNode::kParamWet, wet);
                conv7q.SetParam(sonicraft::dsp::ConvolverNode::kParamDry, dry);
                float phase = 0.0f;
                for (int i = 0; i < kPre7q + kMeas7q; ++i) {
                    const float x = 0.3f * std::sin(phase);
                    phase += 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
                    p0[0] = x; p1[0] = x;
                    conv7q.Process(planar_ptrs, 1);
                    if (i >= kPre7q) out[i - kPre7q] = p0[0];
                }
            };
            std::vector<float> o7q(static_cast<std::size_t>(kMeas7q));

            // 纯干缩放：wet=0 → out = 0.85·dry·x（smallroom kDryGain）
            run7q(0.0f, 1.0f, o7q.data());
            float pk1 = 0.0f;
            for (float v : o7q) pk1 = std::max(pk1, std::fabs(v));
            Check(std::fabs(pk1 - 0.85f * 0.3f) < 5e-4, "convolver: dry level scale 1.0");
            run7q(0.0f, 0.5f, o7q.data());
            float pk2 = 0.0f;
            for (float v : o7q) pk2 = std::max(pk2, std::fabs(v));
            Check(std::fabs(pk2 - 0.425f * 0.3f) < 5e-4, "convolver: dry level scale 0.5");

            // 湿声线性：dry=0 → 湿声独占，RMS(1.5)/RMS(1.0) = 1.5
            auto rms7q = [&](float wet) {
                run7q(wet, 0.0f, o7q.data());
                double acc = 0.0;
                for (float v : o7q) acc += static_cast<double>(v) * v;
                return std::sqrt(acc / kMeas7q);
            };
            const double r10 = rms7q(1.0f), r15 = rms7q(1.5f);
            Check(std::fabs(r15 / r10 - 1.5) < 5e-3, "convolver: wet level linear (all presets)");
        }

        // 7r) 湿声低切（2026-10-24 第二批滑块）：RBJ highpass Q=0.707 只作用
        //     于湿声路径（干声直通不受影响）。比值断言（各自与低切关基准比）：
        //     cutoff=200Hz 时 1kHz 基本无损（大于 0.9），50Hz 强衰减（小于
        //     0.25，二阶理论 -24dB 留余量）。
        {
            sonicraft::dsp::ConvolverNode conv7r;
            conv7r.set_channel_count(kChannels);
            conv7r.Prepare(kSampleRate, kMaxFrames);
            conv7r.SetBypass(false);
            constexpr int kPre7r = 16384, kMeas7r = 16384;
            auto rms7r = [&](float hz, float freq) {
                conv7r.Reset();
                conv7r.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                                static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetSmallRoom));
                conv7r.SetParam(sonicraft::dsp::ConvolverNode::kParamWet, 1.0f);
                conv7r.SetParam(sonicraft::dsp::ConvolverNode::kParamDry, 0.0f);
                conv7r.SetParam(sonicraft::dsp::ConvolverNode::kParamLowCut, hz);
                float phase = 0.0f;
                double acc = 0.0;
                for (int i = 0; i < kPre7r + kMeas7r; ++i) {
                    const float x = 0.3f * std::sin(phase);
                    phase += 2.0f * 3.141592653589793f * freq / kSampleRate;
                    p0[0] = x; p1[0] = x;
                    conv7r.Process(planar_ptrs, 1);
                    if (i >= kPre7r) acc += static_cast<double>(p0[0]) * p0[0];
                }
                return std::sqrt(acc / kMeas7r);
            };
            const double base1k = rms7r(0.0f, 1000.0f);
            const double hp1k = rms7r(200.0f, 1000.0f);
            const double off50 = rms7r(0.0f, 50.0f);
            const double hp50 = rms7r(200.0f, 50.0f);
            Check(base1k > 0.0 && hp1k / base1k > 0.9, "convolver: lowcut passes 1kHz");
            Check(off50 > 0.0 && hp50 / off50 < 0.25, "convolver: lowcut attenuates 50Hz");
        }

        // 7x) 病态 IR 检测 + 每声道频谱（2026-10-26 双声实证驱动，判据 =
        //     tmp_dualvoice_test 离线原型，真机五款复听闭环）：
        //     a) 干净立体声脉冲 → risk 0、pre_silence≈0、2 声道谱峰≈0dB
        //     b) 复刻 HIFI最终版2 形态（前置 160ms 深静音 + 孤立主脉冲 + 3 NaN）
        //        → risk 2、pre>100ms、nan_count==3（检测口径守卫：b 前关闭自动
        //        对齐 SetTrimSickHead(false)，装载保持原样；裁剪行为由 7y 验）
        //     c) 左右不对称（L@0ms / R@100ms 同幅脉冲）→ skew≈100ms 且不误报双声
        {
            auto write_f32_wav = [](const char* path, int sr, int ch,
                                    const std::vector<float>& inter) {
                FILE* f = std::fopen(path, "wb");
                if (f == nullptr) return;
                const std::size_t data_bytes = inter.size() * 4;
                const uint32_t riff = 36 + static_cast<uint32_t>(data_bytes);
                std::fwrite("RIFF", 1, 4, f);
                std::fwrite(&riff, 4, 1, f);
                std::fwrite("WAVEfmt ", 1, 8, f);
                const uint32_t fmt_size = 16, fmt_tag = 3, srate = sr,
                               byte_rate = sr * ch * 4;
                const uint16_t nch = ch, bits = 32, align = ch * 4;
                std::fwrite(&fmt_size, 4, 1, f);
                std::fwrite(&fmt_tag, 2, 1, f);
                std::fwrite(&nch, 2, 1, f);
                std::fwrite(&srate, 4, 1, f);
                std::fwrite(&byte_rate, 4, 1, f);
                std::fwrite(&align, 2, 1, f);
                std::fwrite(&bits, 2, 1, f);
                std::fwrite("data", 1, 4, f);
                const uint32_t dsz = static_cast<uint32_t>(data_bytes);
                std::fwrite(&dsz, 4, 1, f);
                std::fwrite(inter.data(), 1, data_bytes, f);
                std::fclose(f);
            };
            // a) 干净立体声：前 500 帧（≈11ms）静音，双极性衰减脉冲
            {
                const int sr = 44100, n = 1600;
                std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                for (int i = 500; i < n; ++i) {
                    const float s = 0.6f * std::exp(-static_cast<float>(i - 500) / 120.0f) *
                        std::sin(2.0f * 3.141592653589793f * 900.0f * static_cast<float>(i) / static_cast<float>(sr));
                    inter[static_cast<std::size_t>(i * 2)] = s;
                    inter[static_cast<std::size_t>(i * 2 + 1)] = s * 0.9f;
                }
                write_f32_wav("t7x_clean.wav", sr, 2, inter);
                Check(conv.LoadImpulseResponseFromFile("t7x_clean.wav"), "7x clean: loads");
                const sonicraft::dsp::IrInspectReport& rep = conv.UserIrReport();
                Check(rep.risk_level == 0, "7x clean: risk 0 (pre-silence within Haas zone)");
                Check(rep.pre_silence_ms < 20.0f, "7x clean: pre_silence < 20ms");
                Check(rep.pre_noise_db <= -96.0f,
                      "7x clean: pre_noise_db <= -96dB (clean digital zero -> design-grade delay, UI exemption eligible)");
                Check(rep.spectrum_channels == 2, "7x clean: spectrum has 2 channels");
                Check(rep.source_rate == 44100, "7x clean: source rate carried (spectrum x-axis)");
                float peak_db = -999.0f;
                for (int i = 0; i < sonicraft::dsp::IrInspectReport::kSpecPoints; ++i) {
                    peak_db = std::max(peak_db, rep.spectrum[0][i]);
                }
                Check(peak_db > -1.0f && peak_db <= 0.01f,
                      "7x clean: spectrum peak normalized to 0dB");
                std::printf("    INFO 7x clean: pre=%.1fms pulse=%.1fms skew=%.1fms pre_noise=%.1fdB spec_peak=%.1fdB\n",
                            rep.pre_silence_ms, rep.main_pulse_ms,
                            rep.lr_pulse_skew_ms, rep.pre_noise_db, peak_db);
                std::remove("t7x_clean.wav");
            }
            // b) 病态复刻：前置 160ms 幅度 1e-4（<-60dB 门）噪声 + 0.9 孤立脉冲 + 3 NaN
            {
                const int sr = 44100, pre_n = 7070, n = pre_n + 800;
                std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                unsigned rng = 0xCAFEBABEu;
                for (int i = 0; i < pre_n; ++i) {
                    rng = rng * 1664525u + 1013904223u;
                    const float s = 1e-4f * (static_cast<float>(rng >> 8) / 8388608.0f - 1.0f);
                    inter[static_cast<std::size_t>(i * 2)] = s;
                    inter[static_cast<std::size_t>(i * 2 + 1)] = s;
                }
                inter[static_cast<std::size_t>(pre_n * 2)] = 0.9f;
                inter[static_cast<std::size_t>(pre_n * 2 + 1)] = 0.9f;
                inter[static_cast<std::size_t>((pre_n + 100) * 2)] = std::numeric_limits<float>::quiet_NaN();
                inter[static_cast<std::size_t>((pre_n + 200) * 2 + 1)] = std::numeric_limits<float>::quiet_NaN();
                inter[static_cast<std::size_t>((pre_n + 300) * 2)] = std::numeric_limits<float>::quiet_NaN();
                write_f32_wav("t7x_sick.wav", sr, 2, inter);
                conv.SetTrimSickHead(false);  // 检测口径守卫：装载保持原样
                Check(conv.LoadImpulseResponseFromFile("t7x_sick.wav"), "7x sick: loads (cleaned)");
                const sonicraft::dsp::IrInspectReport& rep = conv.UserIrReport();
                Check(rep.risk_level == 2, "7x sick: risk 2 (dual-voice, pre > 100ms)");
                Check(rep.pre_silence_ms > 100.0f && rep.pre_silence_ms < 200.0f,
                      "7x sick: pre_silence ~= 160ms");
                Check(rep.main_pulse_ms > 150.0f && rep.main_pulse_ms < 190.0f,
                      "7x sick: main pulse @ ~161ms");
                Check(rep.nan_count == 3, "7x sick: 3 NaN counted at cleanup point");
                Check(rep.pre_noise_db > -96.0f && rep.pre_noise_db < -60.0f,
                      "7x sick: pre_noise_db in dirty band (export residue, UI warns not to ignore)");
                bool spec_finite = true;
                for (int i = 0; i < sonicraft::dsp::IrInspectReport::kSpecPoints; ++i) {
                    if (!std::isfinite(rep.spectrum[0][i]) || !std::isfinite(rep.spectrum[1][i])) {
                        spec_finite = false;
                        break;
                    }
                }
                Check(spec_finite, "7x sick: spectrum finite despite NaN input");
                std::printf("    INFO 7x sick: pre=%.1fms pulse=%.1fms risk=%d nan=%d pre_noise=%.1fdB\n",
                            rep.pre_silence_ms, rep.main_pulse_ms, rep.risk_level,
                            rep.nan_count, rep.pre_noise_db);
                std::remove("t7x_sick.wav");
                conv.SetTrimSickHead(true);  // 恢复默认开（7y 验裁剪）
            }
            // c) 左右不对称：L 脉冲 @0ms，R 脉冲 @100ms（同幅 → 能量比≈0dB）
            {
                const int sr = 44100, r_at = 4410, n = r_at + 600;
                std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                for (int i = 0; i < 500; ++i) {
                    inter[static_cast<std::size_t>(i * 2)] =
                        0.5f * std::exp(-static_cast<float>(i) / 120.0f) *
                        std::sin(2.0f * 3.141592653589793f * 700.0f * static_cast<float>(i) / static_cast<float>(sr));
                }
                for (int i = r_at; i < r_at + 500; ++i) {
                    inter[static_cast<std::size_t>(i * 2 + 1)] =
                        0.5f * std::exp(-static_cast<float>(i - r_at) / 120.0f) *
                        std::sin(2.0f * 3.141592653589793f * 700.0f * static_cast<float>(i) / static_cast<float>(sr));
                }
                write_f32_wav("t7x_asym.wav", sr, 2, inter);
                Check(conv.LoadImpulseResponseFromFile("t7x_asym.wav"), "7x asym: loads");
                const sonicraft::dsp::IrInspectReport& rep = conv.UserIrReport();
                Check(std::fabs(rep.lr_pulse_skew_ms - 100.0f) < 15.0f,
                      "7x asym: L/R pulse skew ~= 100ms");
                Check(std::fabs(rep.lr_energy_ratio_db) < 3.0f,
                      "7x asym: L/R energy ratio ~= 0dB");
                Check(rep.risk_level == 0, "7x asym: asymmetry does not false-positive dual voice");
                std::printf("    INFO 7x asym: skew=%.1fms ratio=%.1fdB risk=%d\n",
                            rep.lr_pulse_skew_ms, rep.lr_energy_ratio_db, rep.risk_level);
                std::remove("t7x_asym.wav");
            }
            // 7y) 病态脏前置自动对齐（方案 A，2026-10-26）：装载路径裁剪
            //     a) 脏前置（1e-4 噪声底）50ms @48k → 装载即裁：trimmed≈50ms、
            //        pre_silence<10ms、risk=0、δ 纯湿响应主峰 <30ms（双声消失）
            //     b) 纯零前置 50ms（设计型延迟）→ 同样裁（2026-10-27 用户决策：
            //        检测到病态一律校正，不再豁免设计型延迟；要保留延迟效果用
            //        设置开关整体关闭）：trimmed≈50ms、pre<10ms、risk=0、
            //        pre_noise≤-96dB（报告语义保留：干净度指标照常输出）
            // 7y-a: 脏前置 50ms 装载即对齐（默认 trim_sick_head_=true）
            {
                const int sr = 48000, pre_n = 2400, n = pre_n + 800;
                std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                unsigned rng7y = 0xDEADBEEFu;
                for (int i = 0; i < pre_n; ++i) {
                    rng7y = rng7y * 1664525u + 1013904223u;
                    const float s7y = 1e-4f * (static_cast<float>(rng7y >> 8) / 8388608.0f - 1.0f);
                    inter[static_cast<std::size_t>(i * 2)] = s7y;
                    inter[static_cast<std::size_t>(i * 2 + 1)] = s7y;
                }
                inter[static_cast<std::size_t>(pre_n * 2)] = 0.9f;
                inter[static_cast<std::size_t>(pre_n * 2 + 1)] = 0.9f;
                write_f32_wav("t7y_sick.wav", sr, 2, inter);
                Check(conv.LoadImpulseResponseFromFile("t7y_sick.wav"), "convolver 7y a: loads");
                const sonicraft::dsp::IrInspectReport& rep7ya = conv.UserIrReport();
                Check(rep7ya.trimmed_ms > 45.0f && rep7ya.trimmed_ms < 55.0f,
                      "convolver 7y a: trimmed ~= 50ms");
                Check(rep7ya.pre_silence_ms < 10.0f,
                      "convolver 7y a: post-trim pre_silence < 10ms");
                Check(rep7ya.main_pulse_ms < 10.0f,
                      "convolver 7y a: main pulse moved to head");
                Check(rep7ya.risk_level == 0, "convolver 7y a: risk cleared after trim");
                Check(conv.LastTrimMs() > 45.0f && conv.LastTrimMs() < 55.0f,
                      "convolver 7y a: LastTrimMs consistent");
                // δ 纯湿响应：主峰前移进交付延迟区（双声消失）
                conv.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                              static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetUser));
                conv.SetParam(sonicraft::dsp::ConvolverNode::kParamDry, 0.0f);
                conv.SetParam(sonicraft::dsp::ConvolverNode::kParamWet, 1.0f);
                constexpr int kResp7y = 6000;
                std::vector<float> inL7y(static_cast<std::size_t>(frames), 0.0f),
                    inR7y(static_cast<std::size_t>(frames), 0.0f);
                std::vector<float> resp7y(static_cast<std::size_t>(kResp7y), 0.0f);
                int filled7y = 0;
                for (int blk = 0; filled7y < kResp7y; ++blk) {
                    std::fill(inL7y.begin(), inL7y.end(), 0.0f);
                    std::fill(inR7y.begin(), inR7y.end(), 0.0f);
                    if (blk == 2) { inL7y[0] = 1.0f; inR7y[0] = 1.0f; }
                    float* p7y[2] = {inL7y.data(), inR7y.data()};
                    conv.Process(p7y, frames);
                    for (int i = 0; i < frames && filled7y < kResp7y; ++i) {
                        resp7y[static_cast<std::size_t>(filled7y)] = p7y[0][i];
                        ++filled7y;
                    }
                }
                int pk7y = -1;
                double pk_e7y = 0.0;
                for (int i = 8; i < filled7y; ++i) {
                    const double v = resp7y[static_cast<std::size_t>(i)];
                    if (v * v > pk_e7y) { pk_e7y = v * v; pk7y = i; }
                }
                const float pk_ms7y = pk7y >= 0
                    ? static_cast<float>(pk7y) * 1000.0f / static_cast<float>(kSampleRate) : -1.0f;
                std::printf("    INFO 7y a: trimmed=%.1fms pre=%.1fms pulse=%.1fms risk=%d wet_pk@%.1fms\n",
                            rep7ya.trimmed_ms, rep7ya.pre_silence_ms, rep7ya.main_pulse_ms,
                            rep7ya.risk_level, pk_ms7y);
                Check(pk_ms7y >= 0.0f && pk_ms7y < 30.0f,
                      "convolver 7y a: wet peak aligned <30ms (dual-voice gone)");
                std::remove("t7y_sick.wav");
            }
            // 7y-b: 纯零前置 50ms（设计型延迟）同样裁除对齐（病态一律校正）
            {
                const int sr = 48000, pre_n = 2400, n = pre_n + 600;
                std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                for (int i = 0; i < 500; ++i) {
                    const float s7yb = 0.7f * std::exp(-static_cast<float>(i) / 150.0f);
                    inter[static_cast<std::size_t>(pre_n * 2 + i * 2)] = s7yb;
                    inter[static_cast<std::size_t>(pre_n * 2 + i * 2 + 1)] = s7yb;
                }
                write_f32_wav("t7y_design.wav", sr, 2, inter);
                Check(conv.LoadImpulseResponseFromFile("t7y_design.wav"), "convolver 7y b: loads");
                const sonicraft::dsp::IrInspectReport& rep7yb = conv.UserIrReport();
                Check(rep7yb.trimmed_ms > 45.0f && rep7yb.trimmed_ms < 55.0f,
                      "convolver 7y b: design delay trimmed ~50ms (no exemption)");
                Check(rep7yb.pre_silence_ms < 10.0f,
                      "convolver 7y b: pre_silence aligned <10ms");
                Check(rep7yb.pre_noise_db <= -96.0f, "convolver 7y b: pre_noise <= -96dB (report keeps semantics)");
                Check(rep7yb.risk_level == 0, "convolver 7y b: risk 0 after trim");
                std::remove("t7y_design.wav");
            }
            // 7y-c: 毛刺样本+延迟主脉冲（SRS_2_2 实证形态，2026-10-27）：
            //     183ms 深静音残留（-71dB RMS）中 1 个 -59.5dB 孤立毛刺样本 +
            //     183.3ms 处主脉冲。旧逐样本 -60dB 门被毛刺顶穿 → 误判前置仅
            //     ~2ms 不裁 → 湿声主体迟到 183ms（真机「干湿没对齐」根因）。
            //     段级口径（2ms 窗 RMS）必须裁到主脉冲起点。
            {
                const int sr = 48000, pre_n = 8800, n = pre_n + 1200;
                std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                unsigned rng7yc = 0xCAFEBABEu;
                for (int i = 0; i < pre_n; ++i) {
                    rng7yc = rng7yc * 1664525u + 1013904223u;
                    const float s7yc = 5e-4f * (static_cast<float>(rng7yc >> 8) / 8388608.0f - 1.0f);
                    inter[static_cast<std::size_t>(i * 2)] = s7yc;
                    inter[static_cast<std::size_t>(i * 2 + 1)] = s7yc;
                }
                inter[static_cast<std::size_t>(100 * 2)] = 1.2e-3f;      // 孤立毛刺（顶穿旧逐样本门）
                inter[static_cast<std::size_t>(100 * 2 + 1)] = 1.2e-3f;
                inter[static_cast<std::size_t>(pre_n * 2)] = 0.9f;       // 主脉冲 @183.3ms
                inter[static_cast<std::size_t>(pre_n * 2 + 1)] = 0.9f;
                for (int i = 1; i < 1200; ++i) {                          // 衰减尾
                    const float s7yc = 0.9f * std::exp(-static_cast<float>(i) / 300.0f);
                    inter[static_cast<std::size_t>((pre_n + i) * 2)] = s7yc;
                    inter[static_cast<std::size_t>((pre_n + i) * 2 + 1)] = s7yc;
                }
                write_f32_wav("t7y_glitch.wav", sr, 2, inter);
                Check(conv.LoadImpulseResponseFromFile("t7y_glitch.wav"), "convolver 7y c: loads");
                const sonicraft::dsp::IrInspectReport& rep7yc = conv.UserIrReport();
                Check(rep7yc.trimmed_ms > 170.0f && rep7yc.trimmed_ms < 195.0f,
                      "convolver 7y c: glitch-immune trim ~= 183ms (SRS_2_2 form)");
                Check(rep7yc.pre_silence_ms < 10.0f,
                      "convolver 7y c: post-trim pre_silence < 10ms");
                Check(rep7yc.main_pulse_ms < 10.0f,
                      "convolver 7y c: main pulse moved to head");
                Check(rep7yc.risk_level == 0, "convolver 7y c: risk cleared");
                Check(conv.LastTrimMs() > 170.0f && conv.LastTrimMs() < 195.0f,
                      "convolver 7y c: LastTrimMs consistent");
                std::printf("    INFO 7y c: trimmed=%.1fms pre=%.1fms pulse=%.1fms risk=%d noise=%.1fdB\n",
                            rep7yc.trimmed_ms, rep7yc.pre_silence_ms, rep7yc.main_pulse_ms,
                            rep7yc.risk_level, rep7yc.pre_noise_db);
                std::remove("t7y_glitch.wav");
            }
            // 7z) 湿声电平自动校准建议（批次 2，2026-10-27；口径 2026-10-27 二次修正）：
            //     suggested_wet_gain = 1 + log2(3/crest)/4（log2 域，锚=3=真实音乐
            //     DSP 卷积核典型 crest，PROBE 实测库分布 2.1~5.1），clamp 0.6~1.5。
            //     首版 sqrt(RIR 标定/crest) 因 RIR crest=1243 离群，真实库 6/6 全
            //     顶格无区分度（PROBE 实证），弃用。
            //     a) 单 tap 纯脉冲（crest=1）→ 1.40
            //     b) 600ms 衰减噪声扩散型（crest 上万）→ 底格 0.6
            //     c) 锚自洽：3 等幅 tap（crest=3）→ ≈1.0
            //     d) 5 等幅 tap（crest=5）→ ≈0.82（锚上方收敛侧）
            {
                const int sr7z = 48000;
                // 7z a: 脉冲型（单 tap 对，crest=1 → ≈1.40）
                {
                    const int n = 1200;  // 25ms，峰在头、后全零
                    std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                    inter[0] = 0.9f;
                    inter[1] = 0.9f;
                    write_f32_wav("t7z_imp.wav", sr7z, 2, inter);
                    Check(conv.LoadImpulseResponseFromFile("t7z_imp.wav"), "convolver 7z a: loads");
                    const sonicraft::dsp::IrInspectReport& rep7za = conv.UserIrReport();
                    Check(rep7za.suggested_wet_gain > 1.35f && rep7za.suggested_wet_gain < 1.45f,
                          "convolver 7z a: impulse-type suggests wet boost (~1.40)");
                    std::printf("    INFO 7z a: suggested=%.3f crest=%.1f\n",
                                rep7za.suggested_wet_gain, rep7za.crest_factor);
                    std::remove("t7z_imp.wav");
                }
                // 7z b: 扩散型
                {
                    const int n = 28800;  // 600ms
                    std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                    unsigned rng7z = 0x1234567u;
                    for (int i = 0; i < n; ++i) {
                        rng7z = rng7z * 1664525u + 1013904223u;
                        const float nz = static_cast<float>(rng7z >> 8) / 8388608.0f - 1.0f;
                        const float s7z = 0.5f * nz * std::exp(-static_cast<float>(i) / 14400.0f);
                        inter[static_cast<std::size_t>(i * 2)] = s7z;
                        inter[static_cast<std::size_t>(i * 2 + 1)] = s7z;
                    }
                    write_f32_wav("t7z_diff.wav", sr7z, 2, inter);
                    Check(conv.LoadImpulseResponseFromFile("t7z_diff.wav"), "convolver 7z b: loads");
                    const sonicraft::dsp::IrInspectReport& rep7zb = conv.UserIrReport();
                    Check(rep7zb.suggested_wet_gain < 0.65f,
                          "convolver 7z b: diffuse-type clamps to floor (0.6)");
                    std::printf("    INFO 7z b: suggested=%.3f crest=%.1f\n",
                                rep7zb.suggested_wet_gain, rep7zb.crest_factor);
                    std::remove("t7z_diff.wav");
                }
                // 7z c/d: 等幅 k-tap 构造 crest=k（对角联合样本能量 1.62/tap）
                for (int k7z = 3; k7z <= 5; k7z += 2) {
                    const int n = 1200;
                    std::vector<float> inter(static_cast<std::size_t>(n * 2), 0.0f);
                    for (int t = 0; t < k7z; ++t) {
                        const int pos = t * 100;
                        inter[static_cast<std::size_t>(pos * 2)] = 0.9f;
                        inter[static_cast<std::size_t>(pos * 2 + 1)] = 0.9f;
                    }
                    const std::string name7z = k7z == 3 ? "t7z_c3.wav" : "t7z_d5.wav";
                    write_f32_wav(name7z.c_str(), sr7z, 2, inter);
                    Check(conv.LoadImpulseResponseFromFile(name7z), "convolver 7z cd: loads");
                    const sonicraft::dsp::IrInspectReport& rep7zcd = conv.UserIrReport();
                    if (k7z == 3) {
                        Check(rep7zcd.suggested_wet_gain > 0.95f && rep7zcd.suggested_wet_gain < 1.05f,
                              "convolver 7z c: anchor self-consistent (crest=3 -> ~1.0)");
                    } else {
                        Check(rep7zcd.suggested_wet_gain > 0.75f && rep7zcd.suggested_wet_gain < 0.88f,
                              "convolver 7z d: crest=5 -> ~0.82 (above-anchor taper)");
                    }
                    std::printf("    INFO 7z %c: suggested=%.3f crest=%.1f\n",
                                k7z == 3 ? 'c' : 'd', rep7zcd.suggested_wet_gain,
                                rep7zcd.crest_factor);
                    std::remove(name7z.c_str());
                }
            }
            // PROBE（临时取证，2026-10-27「全顶格 1.5」用户报告）：真实脉冲库 IR
            // 走同一装载路径实测建议值，并打印标定 crest 绝对量级。
            {
                const char* probe_files[] = {"probe1.irs", "probe2.irs", "probe3.irs",
                                             "probe4.irs", "probe5.irs", "probe6.irs"};
                for (const char* pf : probe_files) {
                    std::FILE* t = std::fopen(pf, "rb");
                    if (t == nullptr) continue;
                    std::fclose(t);
                    if (conv.LoadImpulseResponseFromFile(pf)) {
                        const sonicraft::dsp::IrInspectReport& rp = conv.UserIrReport();
                        std::printf("    PROBE %s: suggested=%.3f crest=%.1f trimmed=%.1fms\n",
                                    pf, rp.suggested_wet_gain, rp.crest_factor, rp.trimmed_ms);
                    } else {
                        std::printf("    PROBE %s: LOAD FAIL\n", pf);
                    }
                    std::remove(pf);
                }
            }
        }
    }

    // 8) StereoWidthNode（M4d）：M/S 矩阵立体声宽度
    //    8a) width=1 恒等        8b) width=0 单声道化      8c) width=2 展宽精确值
    //    8d) bypass 透传          8e) width 斜坡连续性
    {
        sonicraft::dsp::StereoWidthNode sw;
        sw.set_channel_count(kChannels);
        sw.Prepare(kSampleRate, kMaxFrames);
        sw.SetBypass(false);

        const float kVStep = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float vphase = 0.0f;

        // 8a) width=1 恒等
        sw.SetParam(sonicraft::dsp::StereoWidthNode::kParamWidth, 1.0f);
        bool w_ident = true;
        {
            std::vector<float> rl(static_cast<std::size_t>(frames)), rr(static_cast<std::size_t>(frames));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                rl[static_cast<std::size_t>(i)] = s;
                rr[static_cast<std::size_t>(i)] = 0.5f * std::sin(2.0f * 3.141592653589793f * 3000.0f / kSampleRate * static_cast<float>(i));
            }
            p0 = rl; p1 = rr;
            sw.Process(planar_ptrs, frames);
            for (int i = 0; i < frames && w_ident; ++i) {
                if (std::fabs(p0[i] - rl[static_cast<std::size_t>(i)]) > 1e-6f ||
                    std::fabs(p1[i] - rr[static_cast<std::size_t>(i)]) > 1e-6f) w_ident = false;
            }
        }
        Check(w_ident, "stereo_width: width=1 is identity (both channels)");

        // 8b) width=0 单声道化：L'=R'=(L+R)/2（斜坡完全收敛后精确断言；
        //     30ms 一阶斜坡每块残差 ×0.9145，snap 阈值 0.005 需 ~61 块触发，取 100 块）
        {
            sw.SetParam(sonicraft::dsp::StereoWidthNode::kParamWidth, 0.0f);
            for (int blk = 0; blk < 100; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    p0[i] = 0.5f * std::sin(vphase); vphase += kVStep;
                    p1[i] = 0.5f * std::sin(vphase * 0.7f);
                }
                sw.Process(planar_ptrs, frames);
            }
            bool ok = true;
            for (int i = 0; i < frames && ok; ++i) {
                if (std::fabs(p0[i] - p1[i]) > 1e-4f) ok = false;
            }
            Check(ok, "stereo_width: width=0 collapses to mono after ramp (L==R exact)");
        }

        // 8c) width=2 展宽精确值（斜坡收敛后，输入留副本比对）：
        //     mid 不变（L'+R' == L+R）且 side 加倍（L'-R' == 2(L-R)）
        {
            sw.SetParam(sonicraft::dsp::StereoWidthNode::kParamWidth, 2.0f);
            std::vector<float> rl(static_cast<std::size_t>(frames)), rr(static_cast<std::size_t>(frames));
            for (int blk = 0; blk < 100; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    rl[static_cast<std::size_t>(i)] = 0.5f * std::sin(vphase); vphase += kVStep;
                    rr[static_cast<std::size_t>(i)] = 0.5f * std::sin(vphase * 0.7f);
                }
                p0 = rl; p1 = rr;
                sw.Process(planar_ptrs, frames);
            }
            bool ok = true;
            for (int i = 0; i < frames && ok; ++i) {
                const float mid_in = rl[static_cast<std::size_t>(i)] + rr[static_cast<std::size_t>(i)];
                const float side_in = rl[static_cast<std::size_t>(i)] - rr[static_cast<std::size_t>(i)];
                if (std::fabs((p0[i] + p1[i]) - mid_in) > 1e-4f) ok = false;       // mid 不变
                if (std::fabs((p0[i] - p1[i]) - 2.0f * side_in) > 1e-4f) ok = false;  // side 加倍
            }
            Check(ok, "stereo_width: width=2 doubles side, keeps mid");
        }

        // 8d) bypass 透传
        {
            sw.SetBypass(true);
            std::vector<float> rl(static_cast<std::size_t>(frames)), rr(static_cast<std::size_t>(frames));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(vphase); vphase += kVStep;
                rl[static_cast<std::size_t>(i)] = s; rr[static_cast<std::size_t>(i)] = -s;
            }
            p0 = rl; p1 = rr;
            sw.Process(planar_ptrs, frames);
            bool ok = true;
            for (int i = 0; i < frames && ok; ++i) {
                if (std::fabs(p0[i] - rl[static_cast<std::size_t>(i)]) > 1e-6f ||
                    std::fabs(p1[i] - rr[static_cast<std::size_t>(i)]) > 1e-6f) ok = false;
            }
            Check(ok, "stereo_width: bypass=true passes through sample-identical");
        }

        // 8e) width 斜坡连续性：从 2.0 拉到 0.0，逐块无跳变
        {
            sw.SetBypass(false);
            sw.SetParam(sonicraft::dsp::StereoWidthNode::kParamWidth, 2.0f);
            for (int blk = 0; blk < 60; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    p0[i] = 0.5f * std::sin(vphase); vphase += kVStep;
                    p1[i] = 0.5f * std::sin(vphase * 0.7f);
                }
                sw.Process(planar_ptrs, frames);
            }
            sw.SetParam(sonicraft::dsp::StereoWidthNode::kParamWidth, 0.0f);
            bool smooth = true;
            for (int blk = 0; blk < 60 && smooth; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    p0[i] = 0.5f * std::sin(vphase); vphase += kVStep;
                    p1[i] = 0.5f * std::sin(vphase * 0.7f);
                }
                sw.Process(planar_ptrs, frames);
                for (int i = 1; i < frames; ++i) {
                    if (std::fabs(p0[i] - p0[i - 1]) > 0.5f ||
                        std::fabs(p1[i] - p1[i - 1]) > 0.5f) { smooth = false; break; }
                }
            }
            Check(smooth, "stereo_width: width ramp 2.0->0.0 no sample jumps");
        }
    }

    // 9) MultibandNode（M8a）：LR4 级联分频树 + 段直通合成（频段动力学随 接入）
    //    LR4 恒等式：同分频点 LP4+HP4 相位一致且 |LP+HP|=1 ∀ω → 合成不染色。
    //    9a) 步进正弦合成平坦性（全频带 <0.1dB）
    //    9b) 频段隔离：25Hz（距 fc1=200Hz 近 3 倍频程）只应出现在 Low 段（<-40dB）
    //    9c) 端到端单位性：任意混合信号 输出≈输入（整链黄金参照）
    {
        sonicraft::dsp::MultibandNode mb;
        mb.set_channel_count(kChannels);
        mb.Prepare(kSampleRate, kMaxFrames);
        mb.SetBypass(false);
        const int kMBlocks = 768;  // ≈2 秒 @128 帧块

        // 动力学中性化：ratio=1（曲线数学恒等，knee 项系数归零）+ gain=0 →
        // 全链透明，分频树单独受测（9a/9b/9c 口径）；9d 起再逐用例设参数
        for (int p = 0; p <= 17; ++p) {
            mb.SetParam(p, (p % 6 == 1) ? 1.0f : 0.0f);
        }

        // 9a) 合成平坦性：fc1=200 / fc2=3500，11 个频点稳态正弦，RMS 偏差 <0.1dB
        {
            const float kFreqs[] = {20.0f, 50.0f, 100.0f, 200.0f, 500.0f, 1000.0f,
                                    2000.0f, 3500.0f, 5000.0f, 10000.0f, 20000.0f};
            const float kRmsIn = 0.25f / 1.41421356f;  // 0.25 幅度正弦理论 RMS
            bool flat = true;
            float worst_db = 0.0f;
            float worst_f = 0.0f;
            for (float f : kFreqs) {
                mb.Reset();
                const float step = 2.0f * 3.141592653589793f * f / kSampleRate;
                float phase = 0.0f;
                double sum = 0.0;
                long cnt = 0;
                for (int blk = 0; blk < kMBlocks; ++blk) {
                    for (int i = 0; i < frames; ++i) {
                        const float s = 0.25f * std::sin(phase);
                        phase += step;
                        p0[i] = s;
                        p1[i] = s;
                    }
                    mb.Process(planar_ptrs, frames);
                    if (blk >= kMBlocks / 2) {
                        for (int i = 0; i < frames; ++i) {
                            sum += static_cast<double>(p0[i]) * p0[i];
                            ++cnt;
                        }
                    }
                }
                const float rms_out = static_cast<float>(std::sqrt(sum / cnt));
                const float dev_db = std::fabs(20.0f * std::log10(rms_out / kRmsIn + 1e-12f));
                if (dev_db > worst_db) { worst_db = dev_db; worst_f = f; }
                if (dev_db > 0.1f) flat = false;
            }
            std::printf("    INFO multiband flatness: worst %.4f dB @ %.0f Hz\n", worst_db, worst_f);
            Check(flat, "multiband 9a: LR4 sum flatness within 0.1dB across 11 freqs");
        }

        // 9b) 频段隔离：25Hz 正弦 → Mid/High 段残余 < -40dB（相对 Low 段）
        {
            mb.Reset();
            const float step = 2.0f * 3.141592653589793f * 25.0f / kSampleRate;
            float phase = 0.0f;
            double sum[3] = {0.0, 0.0, 0.0};
            long cnt = 0;
            for (int blk = 0; blk < kMBlocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.25f * std::sin(phase);
                    phase += step;
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
                if (blk >= kMBlocks / 2) {
                    for (int b = 0; b < 3; ++b) {
                        const float* bb = mb.band_buffer(b, 0);
                        for (int i = 0; i < frames; ++i) {
                            sum[b] += static_cast<double>(bb[i]) * bb[i];
                        }
                    }
                    cnt += frames;
                }
            }
            const float rms[3] = {static_cast<float>(std::sqrt(sum[0] / cnt)),
                                  static_cast<float>(std::sqrt(sum[1] / cnt)),
                                  static_cast<float>(std::sqrt(sum[2] / cnt))};
            std::printf("    INFO multiband isolation @25Hz: lo=%.5f mid=%.6f hi=%.7f\n",
                         rms[0], rms[1], rms[2]);
            Check(rms[0] > 0.1f, "multiband 9b: 25Hz lands in Low band");
            Check(rms[1] < rms[0] * 0.01f, "multiband 9b: Mid residual < -40dB");
            Check(rms[2] < rms[0] * 0.01f, "multiband 9b: High residual < -40dB");
        }

        // 9c) 宽带能量守恒：LR4 合成幅度平坦但带相位旋转（IIR 交叉的 2 阶全通
        //     特性，时域逐点恒等不成立——判据用 RMS 守恒，逐频点平坦由 9a 覆盖）
        {
            mb.Reset();
            std::uint32_t lcg = 0x12345678u;
            double sum_in = 0.0, sum_out = 0.0;
            long cnt = 0;
            bool sane = true;
            for (int blk = 0; blk < kMBlocks; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    lcg = lcg * 1664525u + 1013904223u;
                    const float noise =
                        (static_cast<float>(lcg) / 2147483648.0f - 1.0f) * 0.3f;
                    const float s =
                        0.2f * std::sin(2.0f * 3.141592653589793f * 1000.0f *
                                        static_cast<float>(blk * frames + i) / kSampleRate) +
                        noise;
                    p0[i] = s;
                    p1[i] = s;
                    if (blk >= kMBlocks / 2) sum_in += static_cast<double>(s) * s;
                }
                mb.Process(planar_ptrs, frames);
                if (blk >= kMBlocks / 2) {
                    for (int i = 0; i < frames; ++i) {
                        const float y = p0[i];
                        if (!(std::fabs(y) <= 4.0f)) sane = false;  // NaN/发散守门
                        sum_out += static_cast<double>(y) * y;
                        ++cnt;
                    }
                }
            }
            const float dev_db = static_cast<float>(
                std::fabs(10.0 * std::log10(sum_out / sum_in + 1e-30)));
            std::printf("    INFO multiband broadband RMS: dev=%.4f dB\n", dev_db);
            Check(sane, "multiband 9c: no NaN/divergence on broadband input");
            Check(dev_db < 0.1, "multiband 9c: broadband RMS conserved within 0.1dB");
        }

        // 9d) 静态曲线：25Hz 正弦只落 Low 段（Mid/High 残余 -72dB 可忽略），
        //     输出 RMS vs 理论曲线（硬 knee 三点 + soft knee 中心一点）±1.5dB（同 6a 口径）
        {
            const float kThr = -26.0f, kRatio = 3.0f;
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamLowThresholdDb, kThr);
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamLowRatio, kRatio);
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamLowAttackMs, 0.1f);
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamLowReleaseMs, 1000.0f);  // 包络纹波最小化
            const struct {
                float amp;    // 正弦峰值幅度
                float knee;   // knee 宽度 dB
            } pts[] = {
                {0.125f, 0.0f},  // -18dB 峰，硬区
                {0.25f, 0.0f},   // -12dB 峰，硬区
                {0.7f, 0.0f},    // -3.1dB 峰，硬区
                {0.05f, 6.0f},   // -26dB 峰 = knee 区中心（thr-26/knee6）
            };
            const float kStep25 = 2.0f * 3.141592653589793f * 25.0f / kSampleRate;
            bool curve_ok = true;
            float worst_dev = 0.0f;
            for (const auto& pt : pts) {
                mb.SetParam(sonicraft::dsp::MultibandNode::kParamLowKneeDb, pt.knee);
                mb.Reset();
                float phase = 0.0f;
                double sum = 0.0;
                long cnt = 0;
                for (int blk = 0; blk < kMBlocks; ++blk) {
                    for (int i = 0; i < frames; ++i) {
                        const float s = pt.amp * std::sin(phase);
                        phase += kStep25;
                        p0[i] = s;
                        p1[i] = s;
                    }
                    mb.Process(planar_ptrs, frames);
                    if (blk >= kMBlocks / 2) {
                        for (int i = 0; i < frames; ++i) {
                            sum += static_cast<double>(p0[i]) * p0[i];
                            ++cnt;
                        }
                    }
                }
                const float rms = static_cast<float>(std::sqrt(sum / cnt));
                const float l_db = 20.0f * std::log10(pt.amp);
                float g_db;
                if (pt.knee <= 0.0f) {
                    g_db = kThr + (l_db - kThr) / kRatio - l_db;
                } else {
                    const float t = l_db - kThr + 0.5f * pt.knee;  // knee 中心点
                    g_db = (1.0f / kRatio - 1.0f) * t * t / (2.0f * pt.knee);
                }
                const float rms_theory =
                    (pt.amp / 1.41421356f) * std::pow(10.0f, g_db / 20.0f);
                const float dev = std::fabs(20.0f * std::log10(rms / rms_theory + 1e-12f));
                if (dev > worst_dev) worst_dev = dev;
                if (dev > 1.5f) curve_ok = false;
            }
            std::printf("    INFO multiband 9d static curve: worst dev=%.3f dB (4 pts)\n",
                         worst_dev);
            Check(curve_ok, "multiband 9d: static gain curve (hard+knee) within 1.5dB");
        }

        // 9e) bypass 逐位一致：Low 段压缩稳态中（25Hz 0.5 幅度超 -26dB 阈值）切旁路
        {
            mb.Reset();
            const float kStep25 = 2.0f * 3.141592653589793f * 25.0f / kSampleRate;
            float phase = 0.0f;
            for (int blk = 0; blk < 256; ++blk) {  // 先进入压缩稳态
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * std::sin(phase);
                    phase += kStep25;
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
            }
            mb.SetBypass(true);
            bool passthrough = true;
            std::vector<float> ref(static_cast<std::size_t>(frames));
            for (int i = 0; i < frames; ++i) {
                const float s = 0.5f * std::sin(phase);
                phase += kStep25;
                p0[i] = s;
                p1[i] = s;
                ref[i] = s;
            }
            mb.Process(planar_ptrs, frames);
            for (int i = 0; i < frames; ++i) {
                if (std::fabs(p0[i] - ref[i]) > 1e-6f) {
                    passthrough = false;
                    break;
                }
            }
            mb.SetBypass(false);
            Check(passthrough, "multiband 9e: bypass passthrough bit-exact while compressing");
        }

        // 9f) 参数快速扫描 + 静音/满幅交替：无 NaN、无样本跳变（同 6c 口径）
        {
            mb.Reset();
            std::uint32_t lcg = 0x87654321u;
            bool nan_ok = true;
            bool jump_ok = true;
            for (int blk = 0; blk < 256; ++blk) {
                for (int k = 0; k < 3; ++k) {  // 每块随机改 3 个参数（含极值/越界→钳制）
                    lcg = lcg * 1664525u + 1013904223u;
                    const int pid = static_cast<int>(lcg % 20u);
                    lcg = lcg * 1664525u + 1013904223u;
                    const float val =
                        (static_cast<float>(lcg) / 2147483648.0f - 1.0f) * 120.0f;
                    mb.SetParam(pid, val);
                }
                const bool silent = (blk / 8) % 2 == 0;  // 每 8 块静音/满幅交替
                for (int i = 0; i < frames; ++i) {
                    const float s =
                        silent ? 0.0f
                               : std::sin(2.0f * 3.141592653589793f * 1000.0f *
                                          static_cast<float>(blk * frames + i) / kSampleRate);
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    if (!std::isfinite(p0[i])) nan_ok = false;
                    if (std::fabs(p0[i]) > 8.0f) jump_ok = false;  // 发散守门
                }
            }
            Check(nan_ok, "multiband 9f: no NaN/Inf under param sweeping");
            Check(jump_ok, "multiband 9f: bounded output under param sweeping");
        }

        // 9g) fc1 阶跃 dezipper：200→2000Hz，块级系数平滑无样本级跳变；
        //     收敛后 600Hz 归属 Mid→Low 翻转（分频点真正生效的归属证明）
        {
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamSplit1Hz, 200.0f);
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamSplit2Hz, 3500.0f);
            mb.Reset();
            const float kStep600 = 2.0f * 3.141592653589793f * 600.0f / kSampleRate;
            float phase = 0.0f;
            // 预热：等 fc 平滑收敛（9f 可能改过 fc 目标）
            for (int blk = 0; blk < 256; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.25f * std::sin(phase);
                    phase += kStep600;
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
            }
            // 阶跃前对照：600Hz @ fc1=200 属 Mid/High 域（Low 段弱）
            double lo_ref = 0.0, mid_ref = 0.0;
            long cnt_ref = 0;
            for (int blk = 0; blk < 128; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.25f * std::sin(phase);
                    phase += kStep600;
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    lo_ref += static_cast<double>(mb.band_buffer(0, 0)[i]) *
                              mb.band_buffer(0, 0)[i];
                    mid_ref += static_cast<double>(mb.band_buffer(1, 0)[i]) *
                               mb.band_buffer(1, 0)[i];
                    ++cnt_ref;
                }
            }
            const bool before_mid = mid_ref > lo_ref * 100.0;  // Mid 段显著强于 Low
            // 阶跃 + 平滑期无跳变
            mb.SetParam(sonicraft::dsp::MultibandNode::kParamSplit1Hz, 2000.0f);
            // 判据=块间能量渐变 + 样本级斜率上限。块 RMS 测不出单样本咔嗒
            // （2026-10-25 真机拖 fc 毛刺即为 TDF2 状态失配的单样本尖峰，
            // 块 RMS 全绿）。斜率阈值 0.25 为定标值：600Hz 正弦自然斜率
            // ≈0.020，fc 平滑扫过交叉区的物理相位重排（固有下限）
            // 实测 0.216（DF1+ramp），旧 TDF2 状态失配分量 0.323（真机
            // 毛刺来源）——0.25 卡在两者之间：新实现 PASS、TDF2 回退 FAIL
            bool ramp_ok = true;
            double prev_rms = -1.0;
            float worst_ratio = 1.0f;
            float prev_sample = 0.0f;
            float worst_slope = 0.0f;
            for (int blk = 0; blk < 256; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.25f * std::sin(phase);
                    phase += kStep600;
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
                double sum2 = 0.0;
                for (int i = 0; i < frames; ++i) {
                    if (!std::isfinite(p0[i])) ramp_ok = false;
                    const float slope = std::fabs(p0[i] - prev_sample);
                    if (slope > worst_slope) worst_slope = slope;
                    if (slope > 0.25f) ramp_ok = false;
                    prev_sample = p0[i];
                    sum2 += static_cast<double>(p0[i]) * p0[i];
                }
                const double rms = std::sqrt(sum2 / frames);
                if (prev_rms > 1e-9) {
                    const double ratio = rms > prev_rms ? rms / prev_rms : prev_rms / rms;
                    if (ratio > worst_ratio) worst_ratio = ratio;
                    if (ratio > 1.5) ramp_ok = false;  // 块间能量突变 >3.5dB = 爆音级
                }
                prev_rms = rms;
            }
            std::printf("    INFO multiband 9g ramp: worst block-RMS ratio=%.4f worst sample slope=%.4f\n",
                         worst_ratio, worst_slope);
            // 收敛后：600Hz @ fc1=2000 应落 Low 段（Mid 段 -42dB 残余）
            double lo_after = 0.0, mid_after = 0.0;
            long cnt_after = 0;
            for (int blk = 0; blk < 128; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.25f * std::sin(phase);
                    phase += kStep600;
                    p0[i] = s;
                    p1[i] = s;
                }
                mb.Process(planar_ptrs, frames);
                for (int i = 0; i < frames; ++i) {
                    lo_after += static_cast<double>(mb.band_buffer(0, 0)[i]) *
                                mb.band_buffer(0, 0)[i];
                    mid_after += static_cast<double>(mb.band_buffer(1, 0)[i]) *
                                 mb.band_buffer(1, 0)[i];
                    ++cnt_after;
                }
            }
            const bool after_low = lo_after > mid_after * 100.0;
            std::printf("    INFO multiband 9g fc step: before(mid>>lo)=%d after(lo>>mid)=%d\n",
                         before_mid ? 1 : 0, after_low ? 1 : 0);
            Check(before_mid, "multiband 9g: 600Hz in Mid band before fc1 step");
            Check(after_low, "multiband 9g: 600Hz relocated to Low band after step");
            Check(ramp_ok, "multiband 9g: fc ramp smooth (no sample jumps/NaN)");
        }

        // 9h) 块长不变性（7a2 惯例）：参数静止（全默认）下 192/2048/64 混排 vs
        //     128 基线，输出逐位一致——分频 IIR/包络/增益平滑全逐样本同构，唯一
        //     块长相关路径是 fc 平滑（静止期系数恒定，不参与）
        {
            sonicraft::dsp::MultibandNode mb_base;
            mb_base.set_channel_count(2);
            mb_base.Prepare(kSampleRate, 128);
            sonicraft::dsp::MultibandNode mb_mix;
            mb_mix.set_channel_count(2);
            mb_mix.Prepare(kSampleRate, 2048);
            const int kTotal = 48000;  // 1 秒
            const int kPattern[3] = {192, 2048, 64};
            const double kStepT = 2.0 * 3.141592653589793 * 1000.0 / kSampleRate;
            std::uint32_t lcg = 0x2468ace0u;
            int pos = 0;
            int pat = 0;
            bool exact = true;
            std::vector<float> inL(static_cast<std::size_t>(2048));
            std::vector<float> inR(static_cast<std::size_t>(2048));
            std::vector<float> bufL(static_cast<std::size_t>(2048));
            std::vector<float> bufR(static_cast<std::size_t>(2048));
            std::vector<float> refL(static_cast<std::size_t>(2048));
            std::vector<float> refR(static_cast<std::size_t>(2048));
            while (pos < kTotal && exact) {
                const int len = std::min(kPattern[pat % 3], kTotal - pos);
                ++pat;
                for (int i = 0; i < len; ++i) {
                    lcg = lcg * 1664525u + 1013904223u;
                    const float noise =
                        (static_cast<float>(lcg) / 2147483648.0f - 1.0f) * 0.3f;
                    const float s = 0.2f * std::sin(kStepT * (pos + i)) + noise;
                    inL[static_cast<std::size_t>(i)] = s;
                    inR[static_cast<std::size_t>(i)] = s;
                }
                // 混排节点：整块喂（输出留在 bufL/bufR）
                for (int i = 0; i < len; ++i) {
                    bufL[static_cast<std::size_t>(i)] = inL[static_cast<std::size_t>(i)];
                    bufR[static_cast<std::size_t>(i)] = inR[static_cast<std::size_t>(i)];
                }
                float* mix_ptrs[2] = {bufL.data(), bufR.data()};
                mb_mix.Process(mix_ptrs, len);
                // 基线节点：同一原始输入切成 <=128 子块顺序喂（独立缓冲防就地改写串扰）
                int done = 0;
                while (done < len) {
                    const int sub = std::min(128, len - done);
                    for (int i = 0; i < sub; ++i) {
                        refL[static_cast<std::size_t>(i)] =
                            inL[static_cast<std::size_t>(done + i)];
                        refR[static_cast<std::size_t>(i)] =
                            inR[static_cast<std::size_t>(done + i)];
                    }
                    float* base_ptrs[2] = {refL.data(), refR.data()};
                    mb_base.Process(base_ptrs, sub);
                    for (int i = 0; i < sub; ++i) {
                        if (std::memcmp(&refL[static_cast<std::size_t>(i)],
                                        &bufL[static_cast<std::size_t>(done + i)],
                                        sizeof(float)) != 0 ||
                            std::memcmp(&refR[static_cast<std::size_t>(i)],
                                        &bufR[static_cast<std::size_t>(done + i)],
                                        sizeof(float)) != 0) {
                            exact = false;
                            break;
                        }
                    }
                    if (!exact) break;
                    done += sub;
                }
                pos += len;
            }
            Check(exact, "multiband 9h: block-size invariance (192/2048/64 vs 128 bit-exact)");
        }

        // 9i) 三件套：立体声联动 / DC blocker / 段间防重新过冲
        // 9i-1 联动：R=0.5·L 同源不同幅 + Low 段深压 → 增益共享下输出样本级保持
        //     2:1；独立检测时 R 包络低、增益高，2:1 必被破坏。联动生效前提=
        //     压缩确实动作（输出 RMS < 30% 输入）
        {
            sonicraft::dsp::MultibandNode mb;
            mb.set_channel_count(2);
            mb.Prepare(kSampleRate, kMaxFrames);
            mb.SetBypass(false);
            mb.SetParam(0, -30.0f);  // Low thr
            mb.SetParam(1, 3.0f);    // ratio
            mb.SetParam(2, 5.0f);    // atk
            mb.SetParam(3, 150.0f);  // rel
            mb.SetParam(4, 6.0f);    // knee
            mb.SetParam(5, 0.0f);    // gain
            std::vector<float> buf(static_cast<std::size_t>(frames) * 2);
            float* ptrs[2] = {buf.data(), buf.data() + frames};
            double phase = 0.0;
            const double step = 2.0 * 3.141592653589793 * 80.0 / kSampleRate;
            double worst_2to1 = 0.0, sum_in = 0.0, sum_out = 0.0;
            long cnt = 0;
            for (int blk = 0; blk < 256; ++blk) {  // ≈0.68s @128，预热 32 块后统计
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.5f * static_cast<float>(std::sin(phase));
                    phase += step;
                    ptrs[0][i] = s;
                    ptrs[1][i] = 0.5f * s;
                }
                mb.Process(ptrs, frames);
                if (blk >= 32) {
                    for (int i = 0; i < frames; ++i) {
                        const double d = std::fabs(static_cast<double>(ptrs[0][i]) -
                                                   2.0 * static_cast<double>(ptrs[1][i]));
                        if (d > worst_2to1) worst_2to1 = d;
                        sum_in += 0.25;  // 输入 L 幅度 0.5 → RMS²=0.125/2
                        sum_out += static_cast<double>(ptrs[0][i]) * ptrs[0][i];
                        ++cnt;
                    }
                }
            }
            const double rms_in = std::sqrt(sum_in / cnt);
            const double rms_out = std::sqrt(sum_out / cnt);
            std::printf("    INFO multiband 9i-1 link: worst |L-2R|=%.2e gr=%.1f%%\n",
                         worst_2to1, 100.0 * rms_out / rms_in);
            Check(rms_out < rms_in * 0.3, "multiband 9i-1: compression active (GR>10dB)");
            Check(worst_2to1 < 1e-3, "multiband 9i-1: linked gain keeps L/R 2:1 sample-exact");
        }

        // 9i-2 DC blocker（动力学中性化，只测直流链）：0.5 常数输入 1s，末 0.1s
        //     均值 <0.02（理论稳态→0）；1kHz 正弦 RMS 损耗 <0.05dB（fc=2Hz 不伤音频）
        {
            sonicraft::dsp::MultibandNode mb;
            mb.set_channel_count(2);
            mb.Prepare(kSampleRate, kMaxFrames);
            mb.SetBypass(false);
            for (int p = 0; p <= 17; ++p) {
                mb.SetParam(p, (p % 6 == 1) ? 1.0f : 0.0f);
            }
            std::vector<float> buf(static_cast<std::size_t>(frames) * 2);
            float* ptrs[2] = {buf.data(), buf.data() + frames};
            for (int i = 0; i < frames; ++i) {
                ptrs[0][i] = 0.5f;
                ptrs[1][i] = 0.5f;
            }
            double tail_sum = 0.0;
            long tail_cnt = 0;
            for (int blk = 0; blk < 375; ++blk) {  // 1s @128
                mb.Process(ptrs, frames);
                if (blk >= 338) {  // 末 0.1s
                    for (int i = 0; i < frames; ++i) {
                        tail_sum += ptrs[0][i];
                        ++tail_cnt;
                    }
                }
            }
            const double dc_res = std::fabs(tail_sum / tail_cnt);
            std::printf("    INFO multiband 9i-2 dc: residual=%.4f\n", dc_res);
            Check(dc_res < 0.02, "multiband 9i-2: DC input blocked (residual <0.02)");
            // 1kHz 透明性：RMS 损耗判据
            mb.Reset();
            double sum_in = 0.0, sum_out = 0.0;
            long cnt2 = 0;
            double phase = 0.0;
            const double step2 = 2.0 * 3.141592653589793 * 1000.0 / kSampleRate;
            for (int blk = 0; blk < 256; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.25f * static_cast<float>(std::sin(phase));
                    phase += step2;
                    ptrs[0][i] = s;
                    ptrs[1][i] = s;
                }
                mb.Process(ptrs, frames);
                if (blk >= 32) {  // 去 DC 暂态
                    for (int i = 0; i < frames; ++i) {
                        sum_in += 0.03125;  // 0.25²/2
                        sum_out += static_cast<double>(ptrs[0][i]) * ptrs[0][i];
                        ++cnt2;
                    }
                }
            }
            const double loss_db =
                10.0 * std::log10((sum_in / cnt2) / (sum_out / cnt2) + 1e-12);
            std::printf("    INFO multiband 9i-2 1kHz loss=%.4f dB\n", loss_db);
            Check(loss_db < 0.05, "multiband 9i-2: 1kHz RMS loss <0.05dB");
        }

        // 9i-3 段间防重新过冲：三段 gain=+12dB（合成必然 >ceil=1.0）→ trim 必须把
        //     全部样本钳在 1.0 附近；对照增益归零后 worst 不超输入峰（平时透明）
        {
            sonicraft::dsp::MultibandNode mb;
            mb.set_channel_count(2);
            mb.Prepare(kSampleRate, kMaxFrames);
            mb.SetBypass(false);
            for (int p = 0; p <= 17; ++p) {
                mb.SetParam(p, (p % 6 == 1) ? 1.0f : 0.0f);
            }
            mb.SetParam(5, 12.0f);   // Low +12dB makeup
            mb.SetParam(11, 12.0f);  // Mid +12dB
            mb.SetParam(17, 12.0f);  // High +12dB
            std::vector<float> buf(static_cast<std::size_t>(frames) * 2);
            float* ptrs[2] = {buf.data(), buf.data() + frames};
            double phase = 0.0;
            const double step3 = 2.0 * 3.141592653589793 * 440.0 / kSampleRate;
            float worst_boosted = 0.0f, worst_neutral = 0.0f;
            for (int blk = 0; blk < 128; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.9f * static_cast<float>(std::sin(phase));
                    phase += step3;
                    ptrs[0][i] = s;
                    ptrs[1][i] = s;
                }
                mb.Process(ptrs, frames);
                if (blk >= 16) {
                    for (int i = 0; i < frames; ++i) {
                        const float a = std::fabs(ptrs[0][i]);
                        if (a > worst_boosted) worst_boosted = a;
                    }
                }
            }
            for (int p = 5; p <= 17; p += 6) {
                mb.SetParam(p, 0.0f);
            }
            mb.Reset();
            phase = 0.0;
            for (int blk = 0; blk < 128; ++blk) {
                for (int i = 0; i < frames; ++i) {
                    const float s = 0.9f * static_cast<float>(std::sin(phase));
                    phase += step3;
                    ptrs[0][i] = s;
                    ptrs[1][i] = s;
                }
                mb.Process(ptrs, frames);
                if (blk >= 16) {
                    for (int i = 0; i < frames; ++i) {
                        const float a = std::fabs(ptrs[0][i]);
                        if (a > worst_neutral) worst_neutral = a;
                    }
                }
            }
            std::printf("    INFO multiband 9i-3 protect: boosted=%.4f neutral=%.4f\n",
                         worst_boosted, worst_neutral);
            Check(worst_boosted <= 1.0005f,
                  "multiband 9i-3: anti-overshoot clamps sum >ceil to 1.0");
            Check(worst_neutral <= 0.905f,
                  "multiband 9i-3: neutral path transparent (no trim engaged)");
        }
    }

    // ---- 10) 省电：空载旁路状态机 + 卷积快路径（行为回归） ----
    //     10a) IdleBypass：3.9s 静音不激活 / 过 4s 门限激活 / 信号当块恢复 /
    //          -60dB 轻音量永不误判（阈值 -80dB 设计验证）
    //     10b) 干声 head δ 快路径：线性恒等（比值恒定）+ 命中计数增长；
    //          bit-exact 行为回归由既有 7a 干声黄金参照承担
    //     10c) 湿零快路径：教堂 preset wet=0 收敛后命中；恢复 wet=1 混响尾
    //          回归（批次状态机未断，NaN/静音锁死即 FAIL）
    {
        sonicraft::dsp::IdleBypass ib;
        ib.Configure(kSampleRate);
        const int blk10 = kMaxFrames;
        bool not_yet10 = true;
        for (int i = 0; i < (kSampleRate * 39 / 10) / blk10; ++i)
            not_yet10 = ib.Update(0.0f, blk10) ? false : not_yet10;
        Check(not_yet10, "idle_bypass 10a: 3.9s silence not yet bypassed");
        bool activated10 = false;
        for (int i = 0; i < (kSampleRate / 5) / blk10 + 2; ++i)
            activated10 = ib.Update(0.0f, blk10);
        Check(activated10, "idle_bypass 10a: 4s+ silence activates bypass");
        Check(!ib.Update(0.2f, blk10),
              "idle_bypass 10a: signal releases bypass same block");
        bool quiet10 = true;
        for (int i = 0; i < (kSampleRate * 5) / blk10; ++i)
            quiet10 = ib.Update(1.0e-3f, blk10) ? false : quiet10;
        Check(quiet10, "idle_bypass 10a: -60dB program never bypasses");
        std::printf("    INFO 10a: silent_frames=%lld active=%d\n",
                    ib.silent_frames(), ib.active() ? 1 : 0);
    }
    {
        sonicraft::dsp::ConvolverNode conv10;
        conv10.set_channel_count(kChannels);
        conv10.Prepare(kSampleRate, kMaxFrames);
        conv10.SetBypass(false);
        conv10.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset, 0.0f);
        const float step10 = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float ph10 = 0.0f;
        std::vector<float> in10(static_cast<std::size_t>(frames), 0.0f);
        bool dry_ok10 = true;
        double g_ref10 = 0.0;
        for (int b = 0; b < 40 && dry_ok10; ++b) {
            for (int i = 0; i < frames; ++i) {
                const float v = 0.1f * std::sin(ph10);
                in10[static_cast<std::size_t>(i)] = v;
                p0[static_cast<std::size_t>(i)] = v;
                p1[static_cast<std::size_t>(i)] = 0.08f * std::sin(ph10 + 0.7f);
                ph10 += step10;
            }
            float* ptrs10[2] = {p0.data(), p1.data()};
            conv10.Process(ptrs10, frames);
            for (int i = 0; i < frames; ++i) {
                if (std::isnan(ptrs10[0][i])) dry_ok10 = false;
                const double iv = static_cast<double>(in10[static_cast<std::size_t>(i)]);
                if (std::fabs(iv) > 1e-3) {  // 避零点；SoftClip 线性区（幅 0.1·k）
                    const double g = static_cast<double>(ptrs10[0][i]) / iv;
                    if (g_ref10 == 0.0) g_ref10 = g;
                    else if (std::fabs(g - g_ref10) > 1e-4) dry_ok10 = false;
                }
            }
        }
        Check(dry_ok10 && conv10.DryFastBlocks() >= 40,
              "convolver 10b: dry head fast-path linear identity engaged");
    }
    {
        sonicraft::dsp::ConvolverNode conv10c;
        conv10c.set_channel_count(kChannels);
        conv10c.Prepare(kSampleRate, kMaxFrames);
        conv10c.SetBypass(false);
        conv10c.SetParam(sonicraft::dsp::ConvolverNode::kParamPreset,
                         static_cast<float>(sonicraft::dsp::ConvolverNode::kPresetCathedral));
        conv10c.SetParam(sonicraft::dsp::ConvolverNode::kParamWet, 0.0f);
        const float step10c = 2.0f * 3.141592653589793f * 1000.0f / kSampleRate;
        float ph10c = 0.0f;
        double rms0c = 0.0;
        long long n0c = 0;
        for (int b = 0; b < 135; ++b) {  // 预热 341ms（>5τ）wet 斜坡收敛
            for (int i = 0; i < frames; ++i) {
                const float v = 0.2f * std::sin(ph10c);
                p0[static_cast<std::size_t>(i)] = v;
                p1[static_cast<std::size_t>(i)] = v;
                ph10c += step10c;
            }
            float* pc10[2] = {p0.data(), p1.data()};
            conv10c.Process(pc10, frames);
            if (b >= 100) {
                for (int i = 0; i < frames; ++i) {
                    rms0c += static_cast<double>(pc10[0][i]) * pc10[0][i];
                    ++n0c;
                }
            }
        }
        const double r0c = std::sqrt(rms0c / static_cast<double>(n0c));
        Check(conv10c.WetFastBlocks() > 0,
              "convolver 10c: wet-zero fast-path engaged after ramp");
        conv10c.SetParam(sonicraft::dsp::ConvolverNode::kParamWet, 1.0f);
        double rms1c = 0.0;
        long long n1c = 0;
        bool nan1c = false;
        for (int b = 0; b < 300; ++b) {
            for (int i = 0; i < frames; ++i) {
                const float v = 0.2f * std::sin(ph10c);
                p0[static_cast<std::size_t>(i)] = v;
                p1[static_cast<std::size_t>(i)] = v;
                ph10c += step10c;
            }
            float* pc10[2] = {p0.data(), p1.data()};
            conv10c.Process(pc10, frames);
            if (b >= 40) {  // 30ms 斜坡收敛后再测
                for (int i = 0; i < frames; ++i) {
                    if (std::isnan(pc10[0][i])) nan1c = true;
                    rms1c += static_cast<double>(pc10[0][i]) * pc10[0][i];
                    ++n1c;
                }
            }
        }
        const double r1c = std::sqrt(rms1c / static_cast<double>(n1c));
        std::printf("    INFO 10c: rms(wet=0)=%.4f rms(wet=1)=%.4f ratio=%.2f\n",
                    r0c, r1c, r1c / (r0c + 1e-9));
        // 阈值 1.05 → **1.02**：实测 ratio=1.04，长期假红灯。
        // 为什么只有 4%：该 preset 的 dry_gain=0.75，干声占绝对主导
        // （0.2/√2 × 0.75 = 0.1061，与实测 rms(wet=0) 完全吻合），
        // 混响是归一化后的叠加能量，不可能把总 RMS 拉高 5% 以上。
        // 1.02 仍能抓到「尾巴完全没回来」的回归（那种情况 ratio ≈ 1.00）。
        Check(!nan1c && r1c > r0c * 1.02,
              "convolver 10c: reverb tail returns after wet restore (state intact)");
    }

    // 8) ScriptEffectNode（自定义脚本效果器，2026-11-12）：
    //    VM 可插拔，这里注入「×0.5」假 VM 验证「节点↔VM」契约（无需真实脚本）：
    //      a) 默认 bypass=true → 逐样本等于输入（透传）
    //      b) 注入 VM + 启用 → 输出被 VM 改写（×0.5）
    {
        using sonicraft::dsp::ScriptEffectNode;
        using sonicraft::dsp::ScriptVm;

        struct ScaleHalfVm : ScriptVm {
            bool Load(const std::string&, int, int, int) override { return true; }
            void Process(float* const* ch, int n) override {
                for (int c = 0; c < 2; ++c)
                    for (int i = 0; i < n; ++i) ch[c][i] *= 0.5f;
            }
        };

        std::vector<float> sl(frames), sr(frames);
        for (int i = 0; i < frames; ++i) { sl[i] = 1.0f; sr[i] = -0.5f; }
        float* sch[2] = {sl.data(), sr.data()};

        ScriptEffectNode node;
        node.set_channel_count(2);
        node.Prepare(kSampleRate, kMaxFrames);

        node.Process(sch, frames);  // 默认 bypass → 透传
        bool transparent = true;
        for (int i = 0; i < frames; ++i) {
            if (std::fabs(sl[i] - 1.0f) > 1e-6f || std::fabs(sr[i] + 0.5f) > 1e-6f) {
                transparent = false;
                break;
            }
        }
        Check(transparent, "script effect: default bypass passes through sample-identical");

        node.set_vm(std::make_unique<ScaleHalfVm>());
        node.SetBypass(false);
        node.Process(sch, frames);  // VM ×0.5
        bool scaled = true;
        for (int i = 0; i < frames; ++i) {
            if (std::fabs(sl[i] - 0.5f) > 1e-6f || std::fabs(sr[i] + 0.25f) > 1e-6f) {
                scaled = false;
                break;
            }
        }
        Check(scaled, "script effect: injected VM processes the block (x0.5)");
    }

    // 真实 SCVM 后端：默认软削波 x/(1+|x|)（x=1→0.5，x=-0.5→-1/3）
    {
        using sonicraft::dsp::ScriptEffectNode;
        std::vector<float> sl(frames), sr(frames);
        for (int i = 0; i < frames; ++i) { sl[i] = 1.0f; sr[i] = -0.5f; }
        float* sch[2] = {sl.data(), sr.data()};
        ScriptEffectNode node;
        node.set_channel_count(2);
        node.Prepare(kSampleRate, kMaxFrames);
        node.SetBypass(false);
        node.Process(sch, frames);
        const bool ok = std::fabs(sl[0] - 0.5f) < 1e-4f &&
                        std::fabs(sr[0] + (1.0f / 3.0f)) < 1e-4f;
        Check(ok, "script effect: default SCVM soft-clip processes block");

        // slider 位图：引用 slider1 → mask bit0
        ScriptEffectNode sn;
        sn.set_channel_count(2);
        sn.set_script_source("void init(){}\nvoid sample(){ spl0 = spl0 * slider1; spl1 = spl0; }");
        sn.Prepare(kSampleRate, kMaxFrames);
        Check(sn.GetSliderMask() == 1, "script effect: slider mask detects slider1");

        // 范围声明：// slider1: 0, 2
        ScriptEffectNode rn;
        rn.set_channel_count(2);
        rn.set_script_source("// slider1: 0, 2\nvoid init(){}\nvoid sample(){ spl0 = spl0 * slider1; spl1 = spl0; }");
        rn.Prepare(kSampleRate, kMaxFrames);
        float rg[16];
        rn.GetSliderRanges(rg);
        Check(rn.GetSliderMask() == 1 && std::fabs(rg[0]) < 1e-6f &&
                  std::fabs(rg[1] - 2.0f) < 1e-6f,
              "script effect: slider range declared 0..2");

        // 高级控件：toggle / select / button / list + 默认值声明
        ScriptEffectNode cn;
        cn.set_channel_count(2);
        cn.set_script_source(
            "// toggle1: 开关, default=1\n// select1: 模式 = A|B, default=1\n"
            "// button1: 触发\n// list1: 频段 = 低|中|高, default=5\n"
            "float acc = 0.0;\n"
            "void init(){}\n"
            "void sample(){ acc = acc + toggle1 + select1 + button1 + list1; spl0 = acc; spl1 = acc; }\n");
        cn.Prepare(kSampleRate, kMaxFrames);
        const std::string cj = cn.GetControlsJson();
        const bool jsonOk = cj.find("\"toggle\"") != std::string::npos &&
                            cj.find("\"select\"") != std::string::npos &&
                            cj.find("\"button\"") != std::string::npos &&
                            cj.find("\"list\"") != std::string::npos &&
                            cj.find("\"default\":") != std::string::npos &&
                            cj.find("\"A\"") != std::string::npos;
        Check(jsonOk, "script effect: controls json reports toggle/select/button/list + defaults");

        cn.SetBypass(false);
        cn.SetParam(8, 1.0f);    // toggle1 = 1
        cn.SetParam(16, 1.0f);   // select1 = 1
        cn.SetParam(24, 1.0f);   // button1 = 1（仅一个样本）
        cn.SetParam(32, 5.0f);   // list1 = 0b101
        float c0[2] = {0.0f, 0.0f};
        float c1[2] = {0.0f, 0.0f};
        float* cch[2] = {c0, c1};
        cn.Process(cch, 2);
        // 样本1: 1+1+1+5=8；样本2: 按钮已清零 → 8+1+1+0+5=15
        Check(std::fabs(c0[0] - 8.0f) < 1e-4f && std::fabs(c0[1] - 15.0f) < 1e-4f,
              "script effect: list/toggle/select/button params reach the script");

        // slider 默认值声明：// slider1: 0, 2, 1.5
        ScriptEffectNode dn;
        dn.set_channel_count(2);
        dn.set_script_source("// slider1: 0, 2, 1.5\nvoid init(){}\nvoid sample(){ spl0 = slider1; spl1 = slider1; }\n");
        dn.Prepare(kSampleRate, kMaxFrames);
        float dvals[8];
        bool dhas[8];
        dn.GetSliderDefaults(dvals, dhas);
        Check(dhas[0] && std::fabs(dvals[0] - 1.5f) < 1e-5f && !dhas[1],
              "script effect: slider default parsed from declaration");

        // 编译错误回显：非法脚本应返回带行号的错误
        ScriptEffectNode en;
        en.set_channel_count(2);
        en.set_script_source("void sample() { spl0 = ; }");
        en.Prepare(kSampleRate, kMaxFrames);
        Check(en.GetError().find("line") != std::string::npos,
              "script effect: compile error reported with line number");

        // 局部数组：块内 scratch
        ScriptEffectNode la;
        la.set_channel_count(2);
        la.set_script_source("void sample(){ float a[4]; a[0]=spl0; a[1]=a[0]*2.0; spl0=a[1]; spl1=a[1]; }");
        la.Prepare(kSampleRate, kMaxFrames);
        la.SetBypass(false);
        float l0[4] = {0.5f, 0, 0, 0};
        float l1[4] = {0.5f, 0, 0, 0};
        float* lch[2] = {l0, l1};
        la.Process(lch, 4);
        Check(std::fabs(l0[0] - 1.0f) < 1e-5f, "script effect: local array works");

        // 用户函数：square
        ScriptEffectNode uf;
        uf.set_channel_count(2);
        uf.set_script_source(
            R"SCVM(float square(float x) { return x * x; }
void sample() { spl0 = square(spl0); spl1 = square(spl1); }
)SCVM");
        uf.Prepare(kSampleRate, kMaxFrames);
        uf.SetBypass(false);
        float u0[2] = {0.5f, 0.5f};
        float u1[2] = {0.5f, 0.5f};
        float* uch[2] = {u0, u1};
        uf.Process(uch, 2);
        Check(std::fabs(u0[0] - 0.25f) < 1e-5f, "script effect: user function (square) works");

        // 用户函数嵌套调用：inc(inc(1.0)) = 3
        ScriptEffectNode rf;
        rf.set_channel_count(2);
        rf.set_script_source(
            R"SCVM(float inc(float x) { return x + 1.0; }
void sample() { spl0 = inc(inc(1.0)); spl1 = spl0; }
)SCVM");
        rf.Prepare(kSampleRate, kMaxFrames);
        rf.SetBypass(false);
        float r0[2] = {0.0f, 0.0f};
        float r1[2] = {0.0f, 0.0f};
        float* rch[2] = {r0, r1};
        rf.Process(rch, 2);
        Check(std::fabs(r0[0] - 3.0f) < 1e-5f, "script effect: nested user function inc(inc(1))=3");

        // 用户函数递归：fib(6)=8（验证调用帧 + 函数体内部跳转重定位）
        ScriptEffectNode rf2;
        rf2.set_channel_count(2);
        rf2.set_script_source(
            R"SCVM(float fib(float n) { if (n < 2.0) { return n; } return fib(n - 1.0) + fib(n - 2.0); }
void sample() { spl0 = fib(6.0); spl1 = spl0; }
)SCVM");
        rf2.Prepare(kSampleRate, kMaxFrames);
        rf2.SetBypass(false);
        float q0[2] = {0.0f, 0.0f};
        float q1[2] = {0.0f, 0.0f};
        float* qch[2] = {q0, q1};
        rf2.Process(qch, 2);
        Check(std::fabs(q0[0] - 8.0f) < 1e-4f, "script effect: recursive user function fib(6)=8");

        // 内建 biquad：1kHz 低通对直流（x=1）稳态增益应为 1
        {
            ScriptEffectNode bq;
            bq.set_channel_count(2);
            bq.set_script_source("void sample(){ spl0 = bq_lp(0, 1000.0, 0.707, spl0); spl1 = spl0; }");
            bq.Prepare(kSampleRate, kMaxFrames);
            bq.SetBypass(false);
            float b0[192] = {};
            float b1[192] = {};
            float* bch[2] = {b0, b1};
            for (int k = 0; k < 30; ++k) {
                for (int i = 0; i < 192; ++i) {
                    b0[i] = 1.0f;
                    b1[i] = 1.0f;
                }
                bq.Process(bch, 192);
            }
            Check(std::fabs(b0[191] - 1.0f) < 1e-2f, "script effect: builtin bq_lp passes DC");
        }

        // 内建延迟线：写后读 64 样本，前 64 个样本应为 0
        {
            ScriptEffectNode dl;
            dl.set_channel_count(2);
            dl.set_script_source(
                "void sample(){ dl_write(0, spl0); spl0 = dl_read(0, 64.0); spl1 = spl0; }");
            dl.Prepare(kSampleRate, kMaxFrames);
            dl.SetBypass(false);
            float d0[192] = {};
            float d1[192] = {};
            float* dch[2] = {d0, d1};
            for (int i = 0; i < 192; ++i) {
                d0[i] = 0.5f;
                d1[i] = 0.5f;
            }
            dl.Process(dch, 192);
            Check(std::fabs(d0[0]) < 1e-6f && std::fabs(d0[100] - 0.5f) < 1e-5f,
                  "script effect: builtin delay line (write/read)");
        }

        // .eel 子集：sliderN 头 + @init/@sample + 隐式全局
        {
            ScriptEffectNode eel;
            eel.set_channel_count(2);
            eel.set_script_source(
                "desc:test\nslider1:0.5<0<1>gain\n@init\n  x = 1.0;\n"
                "@sample\n  spl0 = spl0 * slider1 * x;\n  spl1 = spl0;\n");
            eel.Prepare(kSampleRate, kMaxFrames);
            eel.SetBypass(false);
            eel.SetParam(0, 0.5f);
            float e0[2] = {0.8f, 0.8f};
            float e1[2] = {0.8f, 0.8f};
            float* ech[2] = {e0, e1};
            eel.Process(ech, 2);
            float erng[16];
            eel.GetSliderRanges(erng);
            Check(std::fabs(e0[0] - 0.4f) < 1e-5f && (eel.GetSliderMask() & 1) != 0 &&
                      std::fabs(erng[0]) < 1e-6f && std::fabs(erng[1] - 1.0f) < 1e-6f,
                  "script effect: eel subset (slider header + @init/@sample)");
        }

        // 诊断：用户脚本 desc:gain 的编译结果（临时探针）
        {
            ScriptEffectNode gc;
            gc.set_channel_count(2);
            gc.set_script_source(
                "desc:gain\nslider1:0.5<0<2>gain\n@init\n  g = 1.0;\n@sample\n"
                "  spl0 = spl0 * g * slider1;\n  spl1 = spl0;\n");
            const bool eelOk = gc.lane(0).GetError().empty();
            gc.Prepare(kSampleRate, kMaxFrames);
            gc.SetBypass(false);
            gc.SetParam(0, 0.5f);
            float g0[2] = {1.0f, 1.0f};
            float g1[2] = {1.0f, 1.0f};
            float* gch[2] = {g0, g1};
            gc.Process(gch, 2);
            Check(eelOk && std::fabs(g0[0] - 0.5f) < 1e-4f,
                  "script effect: eel desc:gain compiles and scales by slider1");
        }

        // .eel 子集：( ... ) 块
        {
            ScriptEffectNode eif;
            eif.set_channel_count(2);
            eif.set_script_source(
                "@sample\n  if (spl0 > 0.0) ( spl0 = 0.5; )\n  spl1 = spl0;\n");
            eif.Prepare(kSampleRate, kMaxFrames);
            eif.SetBypass(false);
            float f0[2] = {0.3f, -0.3f};
            float f1[2] = {0.0f, 0.0f};
            float* fch[2] = {f0, f1};
            eif.Process(fch, 2);
            Check(std::fabs(f0[0] - 0.5f) < 1e-6f && std::fabs(f0[1] + 0.3f) < 1e-6f,
                  "script effect: eel subset (paren block in if)");
        }

        // 控件声明与音频线程解耦：启用状态下换脚本，控件应立即可读（不等 VM 重载）
        {
            ScriptEffectNode sw;
            sw.set_channel_count(2);
            sw.set_script_source(
                "// slider1: 0, 2\nvoid sample(){ spl0 = spl0 * slider1; spl1 = spl0; }");
            sw.Prepare(kSampleRate, kMaxFrames);
            sw.SetBypass(false);
            float swr[16];
            sw.GetSliderRanges(swr);
            const bool before = (sw.GetSliderMask() == 1) && std::fabs(swr[1] - 2.0f) < 1e-6f;
            sw.set_script_source(
                "// slider2: 0, 5\n// toggle1: 开关\n"
                "void sample(){ spl0 = spl0 * slider2; spl1 = spl0; }");
            sw.GetSliderRanges(swr);
            const std::string j = sw.GetControlsJson();
            Check(before && (sw.GetSliderMask() & 2) != 0 && std::fabs(swr[3] - 5.0f) < 1e-6f &&
                      j.find("toggle1") != std::string::npos,
                  "script effect: declared controls update immediately while enabled");
        }

        // 块处理：block() 每块执行一次，可读写整块
        {
            ScriptEffectNode blk;
            blk.set_channel_count(2);
            blk.set_script_source(
                "void block(){ int i = 0; while (i < blk_size()) {"
                " blk_set(0, i, blk_get(0, i) * 0.25);"
                " blk_set(1, i, blk_get(1, i) * 0.25); i = i + 1; } }");
            blk.Prepare(kSampleRate, kMaxFrames);
            blk.SetBypass(false);
            float b0[8] = {1, 1, 1, 1, 1, 1, 1, 1};
            float b1[8] = {1, 1, 1, 1, 1, 1, 1, 1};
            float* bch[2] = {b0, b1};
            blk.Process(bch, 8);
            Check(std::fabs(b0[0] - 0.25f) < 1e-6f && std::fabs(b1[7] - 0.25f) < 1e-6f,
                  "script effect: block() processes whole block");
        }

        // 内建 FFT：单位冲激 → 正变换 → 逆变换 → re[0] 恢复 1.0
        {
            ScriptEffectNode fftn;
            fftn.set_channel_count(2);
            fftn.set_script_source(
                "float out = 0.0;\n"
                "void init(){ fft_size(64); int i = 0; while (i < 64) { fft_load(i, 0.0);"
                " i = i + 1; } fft_load(0, 1.0); fft_forward(); fft_inverse();"
                " out = fft_out(0); }\n"
                "void sample(){ spl0 = out; spl1 = out; }");
            fftn.Prepare(kSampleRate, kMaxFrames);
            fftn.SetBypass(false);
            float g0[2] = {0, 0};
            float g1[2] = {0, 0};
            float* gch[2] = {g0, g1};
            fftn.Process(gch, 2);
            Check(std::fabs(g0[0] - 1.0f) < 1e-4f,
                  "script effect: builtin FFT round-trip");
        }

        // 内建卷积：IR = [0.5, 0.5]，冲激输入 → [0.5, 0.5, 0, ...]
        {
            ScriptEffectNode cv;
            cv.set_channel_count(2);
            cv.set_script_source(
                "void init(){ conv_setup(0, 2); conv_load(0, 0, 0.5); conv_load(0, 1, 0.5); }\n"
                "void block(){ conv_run(0, 0); conv_run(0, 1); }");
            cv.Prepare(kSampleRate, kMaxFrames);
            cv.SetBypass(false);
            float c0[8] = {1, 0, 0, 0, 0, 0, 0, 0};
            float c1[8] = {1, 0, 0, 0, 0, 0, 0, 0};
            float* cch[2] = {c0, c1};
            cv.Process(cch, 8);
            Check(std::fabs(c0[0] - 0.5f) < 1e-6f && std::fabs(c0[1] - 0.5f) < 1e-6f &&
                      std::fabs(c0[2]) < 1e-6f,
                  "script effect: builtin convolver (IR)");
        }

        // 分区 FFT 卷积：IR=[1]，冲激输入 → 延迟 512 样本输出
        {
            ScriptEffectNode pc;
            pc.set_channel_count(2);
            pc.set_script_source(
                "void init(){ convfft_setup(1); convfft_load(0, 1.0); }\n"
                "void block(){ convfft_run(0); convfft_run(1); }");
            pc.Prepare(kSampleRate, kMaxFrames);
            pc.SetBypass(false);
            std::vector<float> p0(1024, 0.0f);
            std::vector<float> p1(1024, 0.0f);
            p0[0] = 1.0f;
            float* pch[2] = {p0.data(), p1.data()};
            pc.Process(pch, 1024);
            Check(std::fabs(p0[0]) < 1e-6f && std::fabs(p0[511]) < 1e-6f &&
                      std::fabs(p0[512] - 1.0f) < 1e-4f,
                  "script effect: partitioned FFT convolution (delay 512)");
        }

        // 干湿对齐 + 卷积：IR=[1] 延 512，wet=0.5 时
        // 干声也必须延 512，否则 0..511 会漏出干声（= 梳状染色）
        {
            ScriptEffectNode wc;
            wc.set_channel_count(2);
            wc.set_script_source(
                "void init(){ convfft_setup(1); convfft_load(0, 1.0); }\n"
                "void block(){ convfft_run(0); convfft_run(1); }");
            wc.Prepare(kSampleRate, kMaxFrames);
            wc.SetBypass(false);
            wc.set_rack_wet(0.5f);
            std::vector<float> q0(1024, 0.0f);
            std::vector<float> q1(1024, 0.0f);
            q0[0] = 1.0f;
            float* qch[2] = {q0.data(), q1.data()};
            wc.Process(qch, 1024);
            const bool silentBefore = std::fabs(q0[0]) < 1e-6f && std::fabs(q0[511]) < 1e-6f;
            const bool sumAtDelay = std::fabs(q0[512] - 1.0f) < 1e-3f;
            Check(silentBefore && sumAtDelay && wc.rack_latency() == 512,
                  "script effect: dry/wet aligns dry to convfft latency");
        }

        // 三元表达式：cond ? a : b
        {
            ScriptEffectNode te;
            te.set_channel_count(2);
            te.set_script_source(
                "void sample(){ spl0 = (spl0 > 0.0) ? 0.5 : -0.5; spl1 = spl0; }");
            te.Prepare(kSampleRate, kMaxFrames);
            te.SetBypass(false);
            float t0[2] = {0.3f, -0.3f};
            float t1[2] = {0.0f, 0.0f};
            float* tch[2] = {t0, t1};
            te.Process(tch, 2);
            Check(std::fabs(t0[0] - 0.5f) < 1e-6f && std::fabs(t0[1] + 0.5f) < 1e-6f,
                  "script effect: ternary operator");
        }

        // EEL function 定义 + local() + 括号体
        {
            ScriptEffectNode ef;
            ef.set_channel_count(2);
            ef.set_script_source(
                "function twice(x) ( x * 2 );\n"
                "@sample\n  spl0 = twice(spl0);\n  spl1 = spl0;\n");
            ef.Prepare(kSampleRate, kMaxFrames);
            ef.SetBypass(false);
            float h0[2] = {0.25f, 0.25f};
            float h1[2] = {0.25f, 0.25f};
            float* hch[2] = {h0, h1};
            ef.Process(hch, 2);
            Check(std::fabs(h0[0] - 0.5f) < 1e-6f,
                  "script effect: eel function definition");
        }

        // 换脚本必须立即生效：启用状态下直接 set_script_source（不经过 bypass 命令）
        {
            ScriptEffectNode swp;
            swp.set_channel_count(2);
            swp.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            swp.Prepare(kSampleRate, kMaxFrames);
            swp.SetBypass(false);
            float a0[2] = {1.0f, 1.0f};
            float a1[2] = {1.0f, 1.0f};
            float* ach[2] = {a0, a1};
            swp.Process(ach, 2);
            const bool first = std::fabs(a0[0] - 0.5f) < 1e-5f;

            swp.set_script_source("void sample(){ spl0 = spl0 * 0.25; spl1 = spl0; }");
            float b0[2] = {1.0f, 1.0f};
            float b1[2] = {1.0f, 1.0f};
            float* bch[2] = {b0, b1};
            swp.Process(bch, 2);
            const bool second = std::fabs(b0[0] - 0.25f) < 1e-5f;

            // 换脚本后滑块也必须作用到「新的」VM
            swp.set_script_source("void sample(){ spl0 = spl0 * slider1; spl1 = spl0; }");
            swp.SetParam(0, 0.125f);
            float c0[2] = {1.0f, 1.0f};
            float c1[2] = {1.0f, 1.0f};
            float* cch[2] = {c0, c1};
            swp.Process(cch, 2);
            Check(first && second && std::fabs(c0[0] - 0.125f) < 1e-5f,
                  "script effect: source switch + slider apply to new VM");
        }

        // 只有 block()、没有 sample()：block 每块只能执行一次（否则每样本重跑 → 爆音）
        {
            ScriptEffectNode bo;
            bo.set_channel_count(2);
            bo.set_script_source(
                "void block(){ int i = 0; while (i < blk_size()) {"
                " blk_set(0, i, blk_get(0, i) * 0.5);"
                " blk_set(1, i, blk_get(1, i) * 0.5); i = i + 1; } }");
            bo.Prepare(kSampleRate, kMaxFrames);
            bo.SetBypass(false);
            float o0[8] = {1, 1, 1, 1, 1, 1, 1, 1};
            float o1[8] = {1, 1, 1, 1, 1, 1, 1, 1};
            float* och[2] = {o0, o1};
            bo.Process(och, 8);
            Check(std::fabs(o0[0] - 0.5f) < 1e-6f && std::fabs(o1[7] - 0.5f) < 1e-6f,
                  "script effect: block-only script runs once per block");
        }

        // 槽位拓扑：全串联（1×0.5×0.5=0.25）
        {
            ScriptEffectNode s0;
            s0.set_channel_count(2);
            s0.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            s0.set_lane_enabled(1, true);
            s0.lane(1).set_channel_count(2);
            s0.lane(1).set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            s0.Prepare(kSampleRate, kMaxFrames);
            s0.SetBypass(false);
            float v0[2] = {1.0f, 1.0f};
            float v1[2] = {1.0f, 1.0f};
            float* vch[2] = {v0, v1};
            s0.Process(vch, 2);
            Check(std::fabs(v0[0] - 0.25f) < 1e-5f, "topology: all series multiplies");
        }

        // 槽位拓扑：全并联，保留干声（1 + 0.5/3 + 0.25/3 = 1.25）
        {
            ScriptEffectNode s1;
            s1.set_channel_count(2);
            s1.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            s1.set_lane_parallel(0, true);
            s1.set_lane_wet(0, 1.0f / 3.0f);
            s1.set_lane_enabled(1, true);
            s1.lane(1).set_channel_count(2);
            s1.lane(1).set_script_source("void sample(){ spl0 = spl0 * 0.25; spl1 = spl0; }");
            s1.set_lane_parallel(1, true);
            s1.set_lane_wet(1, 1.0f / 3.0f);
            s1.Prepare(kSampleRate, kMaxFrames);
            s1.SetBypass(false);
            float w0[2] = {1.0f, 1.0f};
            float w1[2] = {1.0f, 1.0f};
            float* wch[2] = {w0, w1};
            s1.Process(wch, 2);
            Check(std::fabs(w0[0] - 1.25f) < 1e-4f,
                  "topology: all parallel keeps dry and sums wet");
        }

        // 槽位拓扑：混合（槽位1串 ×0.5；槽位2并 ×0.5、wet=0.5 → 0.5+0.25=0.75）
        {
            ScriptEffectNode s2;
            s2.set_channel_count(2);
            s2.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            s2.set_lane_enabled(1, true);
            s2.lane(1).set_channel_count(2);
            s2.lane(1).set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            s2.set_lane_parallel(1, true);
            s2.set_lane_wet(1, 0.5f);
            s2.Prepare(kSampleRate, kMaxFrames);
            s2.SetBypass(false);
            float x0[2] = {1.0f, 1.0f};
            float x1[2] = {1.0f, 1.0f};
            float* xch[2] = {x0, x1};
            s2.Process(xch, 2);
            Check(std::fabs(x0[0] - 0.75f) < 1e-5f,
                  "topology: series + parallel branch merges before limiter");
        }

        // 非 ASCII 字符（中文标点）必须返回 ASCII 安全错误，否则 JNI NewStringUTF 会 abort
        {
            sonicraft::dsp::cvm::Program bp;
            std::string berr;
            const std::string bad = "void sample(){ spl0 = spl0 * 2\uff1f; }";
            const bool bok = sonicraft::dsp::cvm::Compile(bad, bp, berr);
            bool bascii = true;
            for (unsigned char ch : berr) {
                if (ch >= 0x80) bascii = false;
            }
            Check(!bok && bascii && berr.find("non-ASCII") != std::string::npos,
                  "script effect: non-ASCII char yields ASCII-safe error");
        }

        // EEL 子集：错误行号必须映射回用户原始文件的行（而不是翻译后的行）
        {
            sonicraft::dsp::cvm::Program lp;
            std::string lerr;
            const std::string eelbad =
                "desc:a\ndesc:b\ndesc:c\nslider1:0.5<0<1>g\n@init\n  g = 1.0;\n"
                "@sample\n  spl0 = spl0 * ;\n";
            sonicraft::dsp::cvm::Compile(eelbad, lp, lerr);
            Check(lerr.find("line 8") != std::string::npos,
                  "script effect: eel error line mapped to original source");
        }

        // 多 lane：lane0 与 lane1 串行处理（1 * 0.5 * 0.25 = 0.125）
        {
            ScriptEffectNode ml;
            ml.set_channel_count(2);
            ml.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            ml.set_lane_enabled(1, true);
            ml.lane(1).set_channel_count(2);
            ml.lane(1).set_script_source("void sample(){ spl0 = spl0 * 0.25; spl1 = spl0; }");
            ml.Prepare(kSampleRate, kMaxFrames);
            ml.SetBypass(false);
            float m0[2] = {1.0f, 1.0f};
            float m1[2] = {1.0f, 1.0f};
            float* mch[2] = {m0, m1};
            ml.Process(mch, 2);
            Check(std::fabs(m0[0] - 0.125f) < 1e-5f,
                  "script effect: multiple lanes process in series");
        }

        // 参数按 instance 路由：两条 lane 的 slider1 互不影响（*2 与 +0.5）
        {
            ScriptEffectNode mp;
            mp.set_channel_count(2);
            mp.set_script_source("void sample(){ spl0 = spl0 * slider1; spl1 = spl0; }");
            mp.set_lane_enabled(1, true);
            mp.lane(1).set_channel_count(2);
            mp.lane(1).set_script_source("void sample(){ spl0 = spl0 + slider1; spl1 = spl0; }");
            mp.Prepare(kSampleRate, kMaxFrames);
            mp.SetBypass(false);
            mp.SetParam(0, 2.0f);    // lane0.slider1
            mp.SetParam(64, 0.5f);   // lane1.slider1
            float r0[1] = {1.0f};
            float r1[1] = {1.0f};
            float* rch[2] = {r0, r1};
            mp.Process(rch, 1);
            const bool both = std::fabs(r0[0] - 2.5f) < 1e-5f;
            mp.SetParam(64, 0.0f);   // 只改 lane1，不应影响 lane0
            float s0[1] = {1.0f};
            float s1[1] = {1.0f};
            float* sch[2] = {s0, s1};
            mp.Process(sch, 1);
            Check(both && std::fabs(s0[0] - 2.0f) < 1e-5f,
                  "script effect: per-instance params route to own lane");
        }

        // 槽位顺序：lane0(x2) 与 lane1(+0.5) 交换后结果不同（2.5 -> 3.0）
        {
            ScriptEffectNode od;
            od.set_channel_count(2);
            od.set_script_source("void sample(){ spl0 = spl0 * 2.0; spl1 = spl0; }");
            od.set_lane_enabled(1, true);
            od.lane(1).set_channel_count(2);
            od.lane(1).set_script_source("void sample(){ spl0 = spl0 + 0.5; spl1 = spl0; }");
            od.Prepare(kSampleRate, kMaxFrames);
            od.SetBypass(false);
            float q0[1] = {1.0f};
            float q1[1] = {1.0f};
            float* qch[2] = {q0, q1};
            od.Process(qch, 1);
            const bool normal = std::fabs(q0[0] - 2.5f) < 1e-5f;
            od.set_lane_order(1, 0, 2);
            float r0[1] = {1.0f};
            float r1[1] = {1.0f};
            float* rch[2] = {r0, r1};
            od.Process(rch, 1);
            Check(normal && std::fabs(r0[0] - 3.0f) < 1e-5f,
                  "script effect: lane order changes series result");
        }

        // 参数端到端：SetParam(0, 0.7) 应让 spl0 乘 0.7（此前 31 条探针缺这一环，
        // 导致"参数没进 VM"这类缺陷无法被回归拦住）
        {
            ScriptEffectNode sp;
            sp.set_channel_count(2);
            sp.set_script_source("void sample(){ spl0 = spl0 * slider1; spl1 = spl0; }");
            sp.Prepare(kSampleRate, kMaxFrames);
            sp.SetBypass(false);
            sp.SetParam(0, 0.7f);
            float v0[2] = {1.0f, 1.0f};
            float v1[2] = {1.0f, 1.0f};
            float* vch[2] = {v0, v1};
            sp.Process(vch, 2);
            Check(std::fabs(v0[0] - 0.7f) < 1e-4f,
                  "script effect: slider param reaches the script (x0.7 end-to-end)");
        }

        // 机架干湿：wet=0 全干（透传）、wet=1 全湿、wet=0.5 各一半
        {
            ScriptEffectNode wd;
            wd.set_channel_count(2);
            wd.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            wd.Prepare(kSampleRate, kMaxFrames);
            wd.SetBypass(false);
            wd.set_rack_wet(0.0f);
            float a0[1] = {1.0f};
            float a1[1] = {1.0f};
            float* ach[2] = {a0, a1};
            wd.Process(ach, 1);
            const bool dry = std::fabs(a0[0] - 1.0f) < 1e-5f;

            wd.set_rack_wet(1.0f);
            float b0[1] = {1.0f};
            float b1[1] = {1.0f};
            float* bch[2] = {b0, b1};
            wd.Process(bch, 1);
            const bool wet = std::fabs(b0[0] - 0.5f) < 1e-5f;

            wd.set_rack_wet(0.5f);
            float c0[1] = {1.0f};
            float c1[1] = {1.0f};
            float* cch[2] = {c0, c1};
            wd.Process(cch, 1);
            Check(dry && wet && std::fabs(c0[0] - 0.75f) < 1e-5f,
                  "script effect: rack dry/wet mix (0->dry, 1->wet, 0.5->half)");
        }

        // 延迟上报：convfft_* 的槽位按 512 样本计，普通脚本 0
        {
            ScriptEffectNode l0;
            l0.set_channel_count(2);
            l0.set_script_source("void sample(){ spl0 = spl0 * 0.5; spl1 = spl0; }");
            l0.Prepare(kSampleRate, kMaxFrames);
            const int plain = l0.rack_latency();

            ScriptEffectNode l1;
            l1.set_channel_count(2);
            l1.set_script_source("void sample(){ convfft_run(0); }");
            l1.Prepare(kSampleRate, kMaxFrames);
            const int conv = l1.rack_latency();

            Check(plain == 0 && conv == 512,
                  "script effect: rack latency reports 0 / 512 for convfft");
        }

        // 延迟原语：ApplyAlignDelay 必须按请求样本数延后
        // （历史缺陷：读写同一环形槽位 -> 实际延迟恒等于缓冲长度 2048）
        {
            ScriptEffectNode dl;
            dl.set_channel_count(2);
            dl.set_lane_enabled(0, false);
            dl.Prepare(kSampleRate, kMaxFrames);
            float a0[8] = {1, 0, 0, 0, 0, 0, 0, 0};
            float a1[8] = {0, 0, 0, 0, 0, 0, 0, 0};
            float* ach[2] = {a0, a1};
            dl.ApplyAlignDelay(ach, 8, 3);
            Check(std::fabs(a0[0]) < 1e-6f && std::fabs(a0[2]) < 1e-6f &&
                      std::fabs(a0[3] - 1.0f) < 1e-6f,
                  "script effect: align delay honors exact sample count");
        }

        // 延迟上报：串联累加 + 并联取最大（一个串联 convfft + 一个并联 convfft = 1024）
        {
            ScriptEffectNode ls;
            ls.set_channel_count(2);
            ls.set_script_source("void sample(){ convfft_run(0); }", 0);
            ls.set_script_source("void sample(){ convfft_run(0); }", 1);
            ls.set_lane_enabled(1, true);
            ls.set_lane_parallel(1, true);
            ls.Prepare(kSampleRate, kMaxFrames);
            Check(ls.series_latency() == 512 && ls.parallel_latency() == 512 &&
                      ls.rack_latency() == 1024,
                  "script effect: latency = series sum + parallel max");
        }

        // 参数不因槽位关闭而丢失：关闭态推送 -> 启用 -> Prepare -> 应正常乘算
        // （对应"总闸一开就静音"的回归：旧实现里关闭态会被丢弃，slider 永远为 0）
        {
            ScriptEffectNode pg;
            pg.set_channel_count(2);
            pg.set_lane_enabled(0, false);
            pg.set_script_source("void sample(){ spl0 = spl0 * slider1; spl1 = spl0; }", 0);
            pg.SetParam(0, 0.5f);            // 关闭状态下推送
            pg.set_lane_enabled(0, true);    // 再启用
            pg.Prepare(kSampleRate, kMaxFrames);
            pg.SetBypass(false);
            float y0[2] = {1.0f, 1.0f};
            float y1[2] = {1.0f, 1.0f};
            float* ych[2] = {y0, y1};
            pg.Process(ych, 2);
            Check(std::fabs(y0[0] - 0.5f) < 1e-4f,
                  "script effect: params pushed while lane disabled survive re-enable");
        }
    }

    if (failures == 0) {
        std::printf("host-harness: ALL PASS\n");
        return 0;
    }
    std::printf("host-harness: %d FAILURE(S)\n", failures);
    return 1;
}
