/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/fft.hpp"
#include "sonicraft/dsp/ir_inspect.hpp"
#include "sonicraft/dsp/ir_loader.hpp"
#include "sonicraft/dsp/rir_sonic.hpp"
#include "sonicraft/dsp/wav_loader.hpp"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace sonicraft::dsp {

/**
 * IR 卷积混响（M4c → M7 阶段 B：2×2 交叉矩阵分区卷积，路线图 §7.2 v2）。
 *
 * 等效系统（黄金参照 7o 据此定义；2×2 交叉矩阵，路径索引 = in*2+out）：
 *   y_out[n] = head 贡献 + tail 贡献，out ∈ {L,R}
 *     L = h_LL*x_L + h_RL*x_R；R = h_LR*x_L + h_RR*x_R
 *   （路径命名「源→宿」：LL=L 入 L 出、LR=L 入 R 出、RL=R 入 L 出、RR=R 入 R 出；
 *   与 4ch WAV ch0..ch3 = LL,LR,RL,RR 直映，对角退化即旧版单入双出）
 *     head：ir[0..H) 零延迟，时域直接卷积（H=block=128 → 每块 2×H×4 路径 MAC，
 *           比 FFT_512 更便宜且无 OLA 对齐风险——规格的 head FFT 路线放弃）
 *     tail：ir[H..L) 经 uniform partitioned convolution（UPC，dual-real 打包复
 *           FFT N2=2P）整批交付，交付延迟恒为 P=1024 帧（≈21.3ms@48k）——等效于
 *           晚段混响整体平移 P 帧，扩散场听感无差，换取每块摊销 CPU 节省
 *
 * tail 对齐设计（上次回滚根因的修正，核心）：
 *   tail_out 环形缓冲按「全域输出位置」索引：批次 k 的产出 z[kP+m] = y_t[kP+m]
 *   写入槽 (k+1)P+m，消费端读槽 n 即得 y_t[n-P]。写领先读恒 ≤ 2P，同槽被覆盖时
 *   消费早已越该槽一圈读点 H 帧以上——余量 = H，与块长/批次相位完全无关。水位
 *   P-(g_in mod P) 恒 > 0，无穿透明气泡；时间轴接缝唯一且确定（整体平移 P），
 *   彻底消除旧三级架构「FIFO 水位穿透 → 补偿错位 → 双声」的结构缺陷。
 *
 * 批次节奏：g_in 攒到 (k+1)P-H 即可计算批次 k（u = x 延迟 H），块长 64~192 均
 * 自适应（P 与块长解耦）；block=128 时每 8 块触发一批（P=8H，与规格一致）。
 *
 * wrap 项（UPC 关键细节，2026-10-24 harness 7j/7e 双 FAIL 定位）：分区 t 的完整
 * 贡献 y[m] += Σ_{i<P} h_t[i]·u[m-tP-i] 中 i>r 的项（m=kP+r）落在上一输入批次
 * 的尾部——它对应 IFFT(X_{k-t-1}·H_t) 的后 P 帧。因此每批 IFFT 的 2P 帧里：
 * 前 P 帧 = 本批主段、后 P 帧 = 下一批的提前贡献。主段必须加上 overlap（上一批
 * IFFT 后段）才是完整 y_t：z[kP+r] = overlap[r] + C[r]。漏掉 overlap 会丢所有
 * 分区的跨批次尾部贡献（湿声能量缺失 + 时间轴错位），7j 黄金参照立刻暴露。
 *
 * 交叉项乘加（阶段 B，2026-10-24）：分离乘从 2 组 H（对角）扩到 4 组——
 *   acc_L = X_L·H_LL + X_R·H_RL；acc_R = X_L·H_LR + X_R·H_RR
 * 两个 acc 各自 Hermitian，仍打包复 IFFT 一次出 L/R（FFT 次数 2/批不变）。
 * 每 preset 记录路径数（preset_paths_）：内置 RIR/2ch 文件 = 对角 2 组乘快路径
 * （零回归，乘加成本同阶段 A），4ch 文件 = 4 组；分支在外层 t 循环外判断。
 *
 * 实时性契约：Prepare/LoadFile 非实时（频域预计算）；Process 无分配无锁，
 * head 时域 MAC + tail 每批 2×FFT_2048 + T×2048×paths 分离乘（对角 ~41k、
 * 交叉 ~82k FLOP/块摊销）。
 * 切换语义：current_preset_ 指针切换，tail_out/X_cache 中旧 IR 尾自然衰减。
 *
 * 参数约定：param 0 = preset（0=干声, 1=小房间, 2=中房间, 3=大厅, 4=教堂,
 * 5=自定义文件）；param 1 = 湿声电平（0~2，全预设生效，1=归一基准，
 * 30ms 斜坡）；param 2 = 干声电平（0~1.2，乘各预设干声增益，30ms 斜坡）；
 * param 3 = 湿声低切 Hz（0=关，40~400，RBJ highpass Q=0.707，30ms 斜坡）。
 */
class ConvolverNode : public DspNode {
 public:
  static constexpr int kParamPreset = 0;
  static constexpr int kParamWet = 1;  // 湿声电平（0~2，全预设，1=归一基准）
  static constexpr int kParamDry = 2;  // 干声电平（0~1.2，乘 kDryGain[preset]）
  static constexpr int kParamLowCut = 3;  // 湿声低切 Hz（0=关，40~400）

  // ---- 预设单一定义源（X-macro，2026-10-24）----
  // 新增内置混响预设 = 只在本列表加一行；枚举索引、RIR 指针表、预延迟/低通
  // trait、干声增益全部由宏展开自动同步。字段：
  //   name       枚举标识（生成 kPreset<name>）
  //   disp       中文显示名（UI 单源：经 JNI 下发 Kotlin，勿在 Kotlin 侧另写）
  //   rir_l/rir_r 实测 RIR 数组（rir_sonic.hpp；nullptr = 非数据预设）
  //   pre_s      预延迟秒（空间特征辅助：首次反射更晚，物理正确）
  //   lp         一阶低通系数（大空间空气吸收更暗；1.0 = 原样）
  //
  // **教堂 lp 2026-11-09 由 0.62 放宽到 0.85**：换真实 RIR 后谱重心从 12000Hz 降到 5991Hz、
  // 高频本就自然衰减，再叠加 0.62（≈7.4kHz）会把 2k-6k 砍到干声的 0.58x、>6k 砍到 0.38x，
  // 实测偏闷。0.85 对应 ≈14.5kHz，接近不削。
  //   dry        干声增益
  // 教训（原 kDryGain 注释保留于下）：并行表手工对齐曾漏写一项，编译器补 0
  // 静默出错（自定义预设纯湿声发闷）——X-macro 让「加预设改一处」成为编译期
  // 保证，表长/索引由枚举与断言锁定。
#define SONICRAFT_CONV_PRESETS(X)                                             \
  X(Dry,        "干声",   nullptr,        nullptr,          0.000f, 1.00f, 0.00f) \
  X(SmallRoom,  "小房间", kRirSmallL,     kRirSmallR,       0.000f, 1.00f, 0.85f) \
  X(MediumRoom, "客厅",   kRirMediumL,    kRirMediumR,      0.005f, 0.92f, 0.85f) \
  X(LargeRoom,  "大房间", kRirLargeL,     kRirLargeR,       0.015f, 0.78f, 0.85f) \
  X(Cathedral,  "教堂",   kRirCathedralL, kRirCathedralR,   0.035f, 0.85f, 0.75f) \
  X(User,       "自定义", nullptr,        nullptr,          0.000f, 1.00f, 0.75f)

#define SONICRAFT_CONV_PRESET_ENUM(name, disp, rirL, rirR, preS, lp, dry) \
  kPreset##name,
  enum ConvPreset : int {
    SONICRAFT_CONV_PRESETS(SONICRAFT_CONV_PRESET_ENUM)
    kPresetCount,  // 末位 = 预设数量（枚举自动计数）
  };
#undef SONICRAFT_CONV_PRESET_ENUM

  // UI 显示名（跨边界契约：bridge.cpp nativeConvolverPresetNames 下发 Kotlin）。
#define SONICRAFT_CONV_PRESET_NAME(name, disp, rirL, rirR, preS, lp, dry) (disp),
  static constexpr const char* kPresetNames[kPresetCount] = {
      SONICRAFT_CONV_PRESETS(SONICRAFT_CONV_PRESET_NAME)};
#undef SONICRAFT_CONV_PRESET_NAME

  // 索引稳定性断言：preset 值跨进程边界（Kotlin 持久化 prefs / harness 期望
  // 值），任何列表重排必须在此显化——直接 FAIL 编译而不是静默错预设。
  static_assert(kPresetDry == 0 && kPresetSmallRoom == 1 &&
                    kPresetMediumRoom == 2 && kPresetLargeRoom == 3 &&
                    kPresetCathedral == 4 && kPresetUser == 5 &&
                    kPresetCount == 6,
                "convolver preset indices are persisted/ABI: reorder is a breaking change");

  // 展开成每预设数值参数表（RIR 指针/预延迟/低通/干声增益一体）。
  struct PresetDef {
    const float* rir_l;  // nullptr = 非数据预设（Dry/User）
    const float* rir_r;
    int pre_delay;       // 预延迟样本数（48k 域；RIR 源数据固定 48k）
    float lp;            // 一阶低通系数
    float dry_gain;
  };
#define SONICRAFT_CONV_PRESET_DEF(name, disp, rirL, rirR, preS, lp, dry) \
  {rirL, rirR, static_cast<int>((preS) * 48000 + 0.5f), (lp), (dry)},
  static constexpr PresetDef kPresetDefs[kPresetCount] = {
      SONICRAFT_CONV_PRESETS(SONICRAFT_CONV_PRESET_DEF)};
#undef SONICRAFT_CONV_PRESET_DEF
  // IR 上限 7680 采样（160ms@48k）。M4c 后台双声排查结论：N=16384 全频域时后台
  // CPU 降频下单块峰值 3.8ms 逼近 4ms 回调周期 → AAudio xRun（双声）。阶段 A 分区
  // 化后单块成本降 ~5×。2026-10-24 提升 7680→32768（~680ms）再→131072（2731ms，
  // B1 教堂完整长尾）：tail 分区 T 32→128（X_cache ~2MB、tail_spec ~50MB 全 preset
  // 4 路径，harness 实测教堂满载 per-block 仍在实时预算内）。内置混响预设 = 实测
  // RIR（rir_sonic.hpp 按源实际帧数，上限 131072 截断 + 归一）；此上限仅约束
  // 文件 IR。
  static constexpr int kMaxIrLen = 131072;
  // 内置 RIR 实际帧数（rir_sonic.hpp 每预设各自声明；nullptr 预设 = 0）。
  // 与 kPresetDefs 同序（X-macro 顺序）。8ae40aa 越界教训的上界即来源此处。
  static constexpr int kRirLens[kPresetCount] = {
      0, kRirSmallLen, kRirMediumLen, kRirLargeLen, kRirCathedralLen, 0};
  // tail 批次长（= tail 分区长 = 交付延迟 P）：2 的幂；block=128 时 = 8×block
  //（规格 M=8H）。固定常量使批次节奏与块长解耦（块长 64~256 均自适应，T 随之）。
  static constexpr int kTailBatch = 1024;

  ConvolverNode() {
    // preset_paths_ 初始 2（对角快路径）：atomic 数组值初始化为零后在此补齐
    for (auto& v : preset_paths_) v.store(2, std::memory_order_relaxed);
  }

  std::string_view id() const override { return "convolver"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    block_ = max_frames;
    h_ = block_;
    p_ = kTailBatch;
    n2_ = 2 * p_;  // dual-real 打包复 FFT 点数（= 实数点数）
    t_ = (kMaxIrLen - h_ + p_ - 1) / p_;
    if (t_ < 1) t_ = 1;
    // 环形容量取 2 的幂（位与取模）：
    //   x_hist 需保存最近 H+P+block 帧（head 读 n-j 最深 H-1；批次读 u 起点 kP-H，
    //   触发时 g_in ≥ (k+1)P-H → 最深读 g_in-P-H+... ≥ g_in-(P+H+block)）
    cap_hist_ = 1;
    while (cap_hist_ < static_cast<std::size_t>(h_ + p_ + block_ + 1)) cap_hist_ <<= 1;
    //   tail_out 写领先读最大 2P（见类注释），加 block 窗口余量
    cap_out_ = 1;
    while (cap_out_ < static_cast<std::size_t>(2 * p_ + block_ + 1)) cap_out_ <<= 1;
    fft_.Init(n2_);

    // 2×2 路径容量（阶段 B）：路径 idx = in*2+out（LL/LR/RL/RR），对角 preset
    // 只占 idx0/idx3，idx1/idx2 保持零。tail_spec 12MB（全 preset × 4 路径）。
    constexpr std::size_t kPaths = 4;
    head_ir_.assign(static_cast<std::size_t>(kPresetCount) * kPaths * static_cast<std::size_t>(h_), 0.0f);
    tail_spec_re_.assign(static_cast<std::size_t>(kPresetCount) * t_ * kPaths * static_cast<std::size_t>(n2_), 0.0f);
    tail_spec_im_.assign(static_cast<std::size_t>(kPresetCount) * t_ * kPaths * static_cast<std::size_t>(n2_), 0.0f);
    x_cache_re_.assign(static_cast<std::size_t>(t_) * n2_, 0.0f);
    x_cache_im_.assign(static_cast<std::size_t>(t_) * n2_, 0.0f);
    acc_re_.assign(static_cast<std::size_t>(n2_), 0.0f);
    acc_im_.assign(static_cast<std::size_t>(n2_), 0.0f);
    b_re_.assign(static_cast<std::size_t>(n2_), 0.0f);
    b_im_.assign(static_cast<std::size_t>(n2_), 0.0f);
    overlap_l_.assign(static_cast<std::size_t>(p_), 0.0f);
    overlap_r_.assign(static_cast<std::size_t>(p_), 0.0f);
    // 输入历史 / tail 产出 / UI 波形按声道（2）分配——与路径（4）区分
    const std::size_t ch = static_cast<std::size_t>(channels_);
    x_hist_.assign(ch * cap_hist_, 0.0f);
    tail_out_.assign(ch * cap_out_, 0.0f);
    user_wave_.assign(ch * kWavPoints, 0.0f);
    g_in_ = 0;
    next_batch_ = 0;
    ++prepare_count_;  // 诊断：图重建会让预设回到 Dry，这里记录次数
    current_preset_ = kPresetDry;
    target_wet_ = 1.0f;
    current_wet_ = 1.0f;
    target_dry_ = 1.0f;
    current_dry_ = 1.0f;
    // 干声查表基准：随预设切换 30ms 斜坡（P1-1，2026-10-25）。首次切换直跳
    // （dry_base_live_ 尚 false，无历史可平滑，且黄金参照 7j/7o 从第 0 块比对
    // ——直跳保证初始切换路径与旧版逐位一致）；之后运行中切换才走斜坡。
    current_dry_base_ = kDryGain[current_preset_];
    target_dry_base_ = current_dry_base_;
    dry_base_live_ = false;
    target_lowcut_hz_ = 0.0f;
    current_lowcut_hz_ = 0.0f;
    for (int c = 0; c < 2; ++c) {
      lc_x1_[c] = lc_x2_[c] = lc_y1_[c] = lc_y2_[c] = 0.0f;
    }

    // 内置混响预设 = 实测 RIR（rir_sonic.hpp，源 wecho 定制版 rir_data，截 680ms +
    // 每通道归一 Σir²=0.25，g=0.5/rms）。2026-10-24 重提取 160ms→680ms：160ms 截断抹平长尾
    // 是三预设听感趋同的主因（教堂 2.5s 源只剩前 6.4%），680ms 后衰减时长差异
    // 回归主导。slot 5（自定义 IR）不在此填充——由 LoadImpulseResponseFromFile
    // 运行时填充。参数全部来自 kPresetDefs（X-macro 单一定义源展开）。
    {
      std::vector<float> shapedL(static_cast<std::size_t>(kMaxIrLen), 0.0f);
      std::vector<float> shapedR(static_cast<std::size_t>(kMaxIrLen), 0.0f);
      for (int p = 0; p < kPresetCount; ++p) {
        const PresetDef& def = kPresetDefs[p];
        if (def.rir_l == nullptr) continue;  // Dry/User：非数据预设
        for (int c = 0; c < channels_; ++c) {
          // 右移（预延迟）+ 一阶低通（顺序因果处理，模拟大空间空气吸收）。
          // 上界 src < kRirLens[p] 必需：shaped 跑满 kMaxIrLen，而 RIR 数组按
          // 源实际帧数声明——8ae40aa 曾因缺此检查越界读（越界数据恰不进 spec
          // 才未炸）。
          const int rir_len = kRirLens[p];
          float lp = 0.0f;
          const float* src_ir = (c == 0) ? def.rir_l : def.rir_r;
          float* dst = (c == 0) ? shapedL.data() : shapedR.data();
          for (int i = 0; i < kMaxIrLen; ++i) {
            const int src = i - def.pre_delay;
            const float v =
                (src >= 0 && src < rir_len) ? src_ir[src] : 0.0f;
            lp += def.lp * (v - lp);
            dst[i] = lp;
          }
        }
        // P2（2026-10-27 自检）：右移 pre_delay 后有效内容延伸到
        // rir_len+pre_delay-1，传 rir_len 会静默截掉末尾 pre_delay 个样本
        // 的低通衰减尾（教堂 35ms / 大房间 15ms / 客厅 5ms，听感无差但为
        // 无意截断）。上限 kMaxIrLen 兜底（pre_delay ≪ 2048 样本，不触发）。
        const int eff_len = (kRirLens[p] + def.pre_delay <= kMaxIrLen)
                                ? kRirLens[p] + def.pre_delay
                                : kMaxIrLen;
        // 频率相关衰减（2026-11-08 为**白噪声型** IR 加的补偿）。
        // **2026-11-09 起对教堂停用**：教堂 IR 已换成 JDSP 的真实测量数据
        // （谱重心 6012Hz / <200Hz 9.7% / 高频本就自然衰减），再叠加这个补丁
        // 会把本该保留的高频砍成「闷罐」。其余三个预设仍是白噪声型，补丁保留。
        if (p != kPresetCathedral) {
          ApplyFreqDependentDecay(shapedL.data(), eff_len, sample_rate_);
          ApplyFreqDependentDecay(shapedR.data(), eff_len, sample_rate_);
        }
        SetIrSlot(p, shapedL.data(), shapedR.data(), eff_len);
      }
      // 干声 preset 0：head 单位冲激（零延迟恒等），tail 全零（分配即零）。
      // 2×2：LL=RR=1（对角直通），LR/RL=0。
      head_ir_[static_cast<std::size_t>(kPresetDry) * 4 * h_] = 1.0f;                       // LL
      head_ir_[static_cast<std::size_t>(kPresetDry) * 4 * h_ + 3 * h_] = 1.0f;              // RR
    }
  }

  void Reset() override {
    for (auto& v : x_hist_) v = 0.0f;
    for (auto& v : tail_out_) v = 0.0f;
    for (auto& v : x_cache_re_) v = 0.0f;
    for (auto& v : x_cache_im_) v = 0.0f;
    for (auto& v : overlap_l_) v = 0.0f;
    for (auto& v : overlap_r_) v = 0.0f;
    g_in_ = 0;
    next_batch_ = 0;
    batch_half_ = 0;
    batch_k_ = -1;
    // 干声查表基准回到「当前预设直跳」初始态：Reset（断流重建/用例边界）
    // 后首次切预设应立即正确，不残留上一段运行期的斜坡中间态
    // （黄金参照 7j/7k/7o 均按「Reset→SetParam→立即比对」口径）。
    current_dry_base_ = kDryGain[current_preset_];
    target_dry_base_ = current_dry_base_;
    dry_base_live_ = false;
  }

  void Process(float* const* channels, int frames) override {
    if (bypass_ || frames > block_) return;  // bypass = 原样透传（混响状态不推进）

    // dry/wet 显式混合：IR 存纯湿尾，干声按当前预设查表（两路增益 30ms 斜坡）
    const float tau = 0.030f;
    const float alpha =
        1.0f - std::exp(-static_cast<float>(frames) / (tau * sample_rate_));
    if (std::fabs(target_wet_ - current_wet_) > 0.005f) {
      current_wet_ += (target_wet_ - current_wet_) * alpha;
    } else {
      current_wet_ = target_wet_;
    }
    if (std::fabs(target_dry_ - current_dry_) > 0.005f) {
      current_dry_ += (target_dry_ - current_dry_) * alpha;
    } else {
      current_dry_ = target_dry_;
    }
    // 干声查表基准斜坡（预设切换时 kDryGain 跳变 → 30ms 滑）：与 wet/低切
    // 同一 alpha 口径；未切换（target 不变）时为无操作。
    if (std::fabs(target_dry_base_ - current_dry_base_) > 0.005f) {
      current_dry_base_ += (target_dry_base_ - current_dry_base_) * alpha;
    } else {
      current_dry_base_ = target_dry_base_;
    }
    // 湿声低切 cutoff 同斜坡；每块重算 RBJ 系数（状态保留，小步进无 click）
    if (std::fabs(target_lowcut_hz_ - current_lowcut_hz_) > 0.5f) {
      current_lowcut_hz_ += (target_lowcut_hz_ - current_lowcut_hz_) * alpha;
    } else {
      current_lowcut_hz_ = target_lowcut_hz_;
    }
    const int preset = current_preset_;
    const float dry_g = current_dry_base_ * current_dry_;  // 查表基准已斜坡化
    const float wet_g = current_wet_;  // 全预设生效（1=各预设归一基准）
    const bool hpf_on = current_lowcut_hz_ >= 40.0f;  // <40 视为关（斜坡余量）
    if (hpf_on) {
      // RBJ highpass（cookbook，Q=0.707 Butterworth，12dB/oct）
      const float w0 = 6.2831853f * current_lowcut_hz_ / sample_rate_;
      const float cw = std::cos(w0), sw = std::sin(w0);
      const float a = sw / 1.4142135f;
      const float a0 = 1.0f + a;
      lc_b0_ = (1.0f + cw) / 2.0f / a0;
      lc_b1_ = -(1.0f + cw) / a0;
      lc_b2_ = lc_b0_;
      lc_a1_ = -2.0f * cw / a0;
      lc_a2_ = (1.0f - a) / a0;
    }
    // head 2×2：L_out = LL·x_L + RL·x_R；R_out = LR·x_L + RR·x_R
    const float* headLL = &head_ir_[(static_cast<std::size_t>(preset) * 4 + 0) * h_];
    const float* headLR = &head_ir_[(static_cast<std::size_t>(preset) * 4 + 1) * h_];
    const float* headRL = &head_ir_[(static_cast<std::size_t>(preset) * 4 + 2) * h_];
    const float* headRR = &head_ir_[(static_cast<std::size_t>(preset) * 4 + 3) * h_];

    // 1) 当前块写入输入历史（全域位置 g_in_ 起）
    for (int i = 0; i < frames; ++i) {
      const std::size_t slot = RingPos(g_in_ + i, cap_hist_);
      x_hist_[slot] = channels[0][i];
      x_hist_[cap_hist_ + slot] = channels[1][i];
    }

    // 1.5) 补上一批后半（必须先于 2) 消费！真机块 2048 帧 = 2P，消费一步
    //      可穿越整个批次槽区——若后半拖到块尾（3) 段）补，消费会读到未
    //      写出的陈旧环带（2026-10-24 回归：全预设啵啵，b70557a 初版把
    //      补半放在 3) 开头所致）。同块内先写后读 = 安全窗口关闭。
    if (batch_half_ != 0) ComputeBatchHalf(batch_k_, 1);

    // 2) 输出 = head（时域直接卷积，零延迟） + tail（槽 n 存 y_t[n-P]，读槽 n）。
    //    M9 省电快路径 A（2026-10-27）：湿电平斜坡收敛到 0 后，输出恒为
    //    SoftClip(dry_g·x)——head MAC、tail 读、湿路低切贡献全乘 0，白算。
    //    只省计算、不动状态：1)/1.5)/3) 段照常推进（X_cache/tail_out 槽相位
    //    与 g_in_ 严格耦合，冻结批次会在恢复 wet 时读槽错位——参考 b70557a
    //    补半顺序回归）。收敛阈值与斜坡 snap 同值，行为与全链乘 0 等价。
    wet_acc_ = 0.0f;  // 诊断：每块重置湿声能量
    dry_acc_ = 0.0f;  // 哨兵：每块重置干声能量（判「输入有声但湿声恒零」）
    if (current_wet_ <= 0.005f) {
      wet_fast_blocks_.fetch_add(1, std::memory_order_relaxed);
      for (int i = 0; i < frames; ++i) {
        channels[0][i] = SoftClip(dry_g * channels[0][i]);
        channels[1][i] = SoftClip(dry_g * channels[1][i]);
      }
    } else {
    // 快路径 B：干声 preset head=δ（LL/RR[0]=1），MAC 结果恒等于输入
    // （0+…+1·x+…+0 值等价），直赋省掉 128 深度双路径 MAC。tail（干声
    // spec 全零 → IFFT 出零）、湿路低切（作用于 wl=x）行为与全 MAC 一致。
    const bool dry_head = (preset == kPresetDry);
    if (dry_head) dry_fast_blocks_.fetch_add(1, std::memory_order_relaxed);
    std::size_t tslot = RingPos(g_in_, cap_out_);
    for (int i = 0; i < frames; ++i) {
      const long long n = g_in_ + i;
      float hl = 0.0f, hr = 0.0f;
      if (dry_head) {
        hl = channels[0][i];
        hr = channels[1][i];
      } else {
        std::size_t hslot = RingPos(n, cap_hist_);
        for (int j = 0; j < h_; ++j) {
          hl += headLL[j] * x_hist_[hslot] + headRL[j] * x_hist_[cap_hist_ + hslot];
          hr += headLR[j] * x_hist_[hslot] + headRR[j] * x_hist_[cap_hist_ + hslot];
          hslot = (hslot == 0) ? cap_hist_ - 1 : hslot - 1;
        }
      }
      const float tl = tail_out_[tslot];
      const float tr = tail_out_[cap_out_ + tslot];
      tslot = (tslot + 1 == cap_out_) ? 0 : tslot + 1;
      const float xl = channels[0][i], xr = channels[1][i];
      float wl = hl + tl, wr = hr + tr;
      if (hpf_on) {  // 湿声低切：DF1 biquad，状态按声道独立
        const float yl = lc_b0_ * wl + lc_b1_ * lc_x1_[0] + lc_b2_ * lc_x2_[0] -
                         lc_a1_ * lc_y1_[0] - lc_a2_ * lc_y2_[0];
        lc_x2_[0] = lc_x1_[0]; lc_x1_[0] = wl;
        lc_y2_[0] = lc_y1_[0]; lc_y1_[0] = yl;
        const float yr = lc_b0_ * wr + lc_b1_ * lc_x1_[1] + lc_b2_ * lc_x2_[1] -
                         lc_a1_ * lc_y1_[1] - lc_a2_ * lc_y2_[1];
        lc_x2_[1] = lc_x1_[1]; lc_x1_[1] = wr;
        lc_y2_[1] = lc_y1_[1]; lc_y1_[1] = yr;
        wl = yl; wr = yr;
      }
      wet_acc_ += wl * wl + wr * wr;  // 诊断：湿声能量（下次水印日志用）
      dry_acc_ += xl * xl + xr * xr;  // 哨兵：干声能量（与湿声对比判「失效」）
      channels[0][i] = SoftClip(dry_g * xl + wet_g * wl);
      channels[1][i] = SoftClip(dry_g * xr + wet_g * wr);
    }
    }  // else（非湿零快路径）

    // 诊断：本块湿声 RMS（0 表示湿路无输出 → 与「预设被重置/状态坏死」区分）
    wet_rms_ = (frames > 0)
                   ? std::sqrt(wet_acc_ / (2.0f * static_cast<float>(frames)))
                   : 0.0f;

    // ==== wet 哨兵（2026-11-08）====
    // 现象：卷积混响运行十几分钟后失效，其他节点（PEQ/多段/宽度/限幅）全部正常，
    // 重建引擎立刻恢复 → 判断是脏数据（xRun 丢块等扰动）进了分区卷积状态后，
    // NaN/denormal 永久驻留，导致湿声恒零、干声照常（用户感知为「混响没了」）。
    // 判据：预设非 Dry、湿增益已生效，但湿声 RMS 恒 ~0 **而干声仍有电平**。
    // 处置：Reset() 清卷积状态（不清预设、不清干湿增益），让卷积器自愈。
    // 只在连续 N 块满足时触发，正常静音段（干湿都 0）不会误触发。
    if (!bypass_ && current_wet_ > 0.005f && current_preset_ != kPresetDry) {
      const float dry_rms = (frames > 0)
                                ? std::sqrt(dry_acc_ / (2.0f * static_cast<float>(frames)))
                                : 0.0f;
      // 注意用 `> gate` 而不是 `< gate` 判死：NaN 与任何数比较恒 false，
      // 若写成 wet_rms_ < 1e-6f，真正坏成 NaN 的情况反而漏判（NaN 也不小于任何值）。
      if (wet_rms_ > 1e-4f) {
        wet_ever_alive_ = true;
        wet_dead_blocks_ = 0;
      } else if (wet_ever_alive_ && dry_rms > 1e-3f) {
        // 必须「曾经真的有湿声」才判失效：IR 本身近静音（全零/极短尾音）时
        // 湿声天生就该是 0，那种情况反复 Reset 纯属浪费，且会掩盖真实病因。
        if (++wet_dead_blocks_ >= kWetDeadResetBlocks) {
          Reset();
          wet_ever_alive_ = false;
          ++wet_sentinel_resets_;
          wet_dead_blocks_ = 0;
        }
      } else {
        wet_dead_blocks_ = 0;
      }
    }

    // 3) 推进输入计数并触发 tail 批次（拆半削峰：前半在触发块尾跑，后半在
    //    下一块开头（1.5) 消费前）补完；本段不再补半，避免晚于消费）
    g_in_ += frames;
    while (g_in_ >= (static_cast<long long>(next_batch_) + 1) * p_ - h_) {
      if (batch_half_ != 0) ComputeBatchHalf(batch_k_, 1);  // 追批：先补完上一批后半
      ComputeBatchHalf(next_batch_, 0);
      ++next_batch_;
    }
  }

  void SetBypass(bool bypass) override {
    bypass_ = bypass;
    // 诊断修正（2026-11-06）：bypass 时 Process 不再被调用，wet_rms_ 会停留在
    // 最后一次的值 → 探针会显示"有湿声"的假象（曾据此误判卷积在工作）。
    // 这里在切换旁路时清零，保证「bypass ⇒ cvWet=0」语义明确。
    if (bypass) {
      wet_acc_ = 0.0f;
      wet_rms_ = 0.0f;
    }
  }
  bool Bypassed() const override { return bypass_; }

  // M9 省电快路径诊断计数（harness 10b/10c 断言旁路生效；atomic 免实时/诊断
  // 线程竞态）。干声 head δ 直赋 / 湿零快路径各自命中的块数。
  long long DryFastBlocks() const {
    return dry_fast_blocks_.load(std::memory_order_relaxed);
  }
  long long WetFastBlocks() const {
    return wet_fast_blocks_.load(std::memory_order_relaxed);
  }

  // 诊断访问器（2026-11-06）：供引擎把三值写进水印日志
  int DiagnPrepareCount() const { return prepare_count_; }
  int DiagnPreset() const { return current_preset_; }   // 0=Dry（无混响）
  float DiagnWetRms() const { return wet_rms_; }
  /** wet 哨兵累计触发次数（2026-11-08）：>0 表示曾捕获到「湿声状态坏死」并自愈过 */
  int DiagnWetSentinelResets() const { return wet_sentinel_resets_; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamPreset) {
      const int preset = static_cast<int>(value);
      if (preset >= 0 && preset < kPresetCount) {
        // slot 5 需文件加载成功后才可选（防切到未初始化的静音 spec）
        if (preset == kPresetUser && !user_ir_loaded_.load(std::memory_order_acquire)) return;
        current_preset_ = preset;  // 实时线程内仅指针索引切换（频域已预计算）
        // 干声查表基准：首次切换直跳（黄金参照兼容），运行中切换 30ms 斜坡
        if (!dry_base_live_) {
          current_dry_base_ = kDryGain[preset];
          dry_base_live_ = true;
        }
        target_dry_base_ = kDryGain[preset];
      }
    } else if (param_id == kParamWet) {
      // 湿声电平（0~2，全预设生效），30ms 斜坡防突变
      target_wet_ = value < 0.0f ? 0.0f : (value > 2.0f ? 2.0f : value);
    } else if (param_id == kParamLowCut) {
      // 湿声低切 Hz（0=关，40~400），30ms 斜坡防频点跳变
      if (value <= 0.0f) target_lowcut_hz_ = 0.0f;
      else target_lowcut_hz_ = value < 40.0f ? 40.0f : (value > 400.0f ? 400.0f : value);
    } else if (param_id == kParamDry) {
      // 干声电平（0~1.2，乘各预设干声增益），30ms 斜坡防突变
      target_dry_ = value < 0.0f ? 0.0f : (value > 1.2f ? 1.2f : value);
    }
  }

  /**
   * 加载外部 IR 文件（WAV，1-4 声道）到 slot 5（M4c.2，仅限非实时线程调用）。
   *
   * 流程：解析 WAV（PCM16/24/32/f32；4ch 取对角直达项）→ CatmullRom 4 点重采样到
   * sample_rate_ → 长度截断 kMaxIrLen → 湿尾能量归一 0.3 + 湿峰值上限 0.6
   * （文件 IR 专用参数，与内置 RIR 的 0.5 分离——脉冲型 IR 与干声同相叠加，见
   * 归一处注释）→ SetIrSlot 填 head/tail 频域结构并置 user_loaded_。
   *
   * 线程安全：slot 5 与实时线程正在读的 slot 0..4 是不同内存区，写入无竞态；
   * 加载完成前 SetParam(5) 被 user_loaded_ 拒绝，实时线程永远不会读到半成品。
   * 成功后由调用方（Kotlin 协程）发 SetParam(kPresetUser) 切换；失败返回 false
   * 且不改变任何状态（回退当前预设）。
   */
  bool LoadImpulseResponseFromFile(const std::string& path) {
    WavData wav;
    std::string load_err;
    if (!LoadIrFileAny(path, sample_rate_, &wav, &load_err) || wav.frames <= 0 ||
        wav.sample_rate <= 0) {
      last_load_err_ = load_err;
      return false;
    }
    last_load_err_.clear();
    last_trim_ms_ = 0.0f;

    // 通道映射（阶段 B 2×2）：4ch（HRTF/交叉矩阵 IR）→ 全四路径 LL,LR,RL,RR
    // = ch0..ch3（矩阵按 [out][in]「源→宿」语义：y1 = LL·x1 + RL·x2，与
    // JamesDSP 2x4x2 参照一致）；2ch → L=ch0,R=ch1；1ch → L=R=ch0。
    // 非 4ch 的交叉路径置空（对角退化，走 2 组乘快路径）。
    const bool has_cross = wav.channel_count >= 4;
    int src_idx[4] = {0, has_cross ? 1 : -1, has_cross ? 2 : -1,
                      wav.channel_count >= 2 ? (has_cross ? 3 : 1) : 0};

    // CatmullRom 4 点三次样条重采样到引擎采样率（阶段 C 已落地）：镜像衰减
    // ~-50dB（线性插值仅 ~-26dB），44.1k→48k 时旧奈奎斯特 22.05kHz 镜像落回
    // 音频带内——线性插值的镜像正是 HRTF 高频杂音来源。越界索引用边界钳制。
    const double ratio = static_cast<double>(wav.sample_rate) / static_cast<double>(sample_rate_);
    const long long out_len_ll =
        static_cast<long long>(static_cast<double>(wav.frames) / ratio);
    int out_len = static_cast<int>(out_len_ll < kMaxIrLen ? out_len_ll : kMaxIrLen);
    if (out_len < 1) return false;
    user_ir_frames_ = out_len;

    std::vector<float> ir_buf[4];  // [路径][out_len]，路径 idx = in*2+out
    for (int q = 0; q < 4; ++q) {
      if (src_idx[q] < 0) continue;  // 对角退化：交叉路径保持空
      const std::vector<float>& src = wav.ch[static_cast<std::size_t>(src_idx[q])];
      std::vector<float> resampled(static_cast<std::size_t>(out_len), 0.0f);
      const auto src_at = [&src, n = wav.frames](int idx) -> float {
        return src[static_cast<std::size_t>(idx < 0 ? 0 : (idx >= n ? n - 1 : idx))];
      };
      for (int i = 0; i < out_len; ++i) {
        const double pos = static_cast<double>(i) * ratio;
        const int i0 = static_cast<int>(pos);
        const float t = static_cast<float>(pos - static_cast<double>(i0));
        const float p0 = src_at(i0 - 1);
        const float p1 = src_at(i0);
        const float p2 = src_at(i0 + 1);
        const float p3 = src_at(i0 + 2);
        // Catmull-Rom 三次样条
        resampled[static_cast<std::size_t>(i)] = 0.5f * ((2.0f * p1) + (p2 - p0) * t +
            (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t * t +
            (3.0f * (p1 - p2) + p3 - p0) * t * t * t);
      }
      ir_buf[q] = std::move(resampled);
    }
    // 湿尾裁剪（P0-2，2026-10-25）：相对 4 路径全局峰值 -80dB 阈值，取最晚
    // 有效样本位置统一截断（保持矩阵时间对齐，非逐路径独立）。其后恒零
    // 分区被 SetIrSlot2x2 的 t_eff 排除 → 长零尾 IR 的批次负载直接下降。
    // -80dB 深于 16bit 底噪（-96dB）之下的可闻下限，误裁可闻内容概率可
    // 忽略（wecho trimIrTail 用 -140dBFS 几乎只裁数字零，JDSP shrink 用
    // -80/-100 双阈值——取积极档，收益是实打实的 CPU）。至少保留 1 帧。
    {
      float peak_all = 0.0f;
      for (int q = 0; q < 4; ++q) {
        if (ir_buf[static_cast<std::size_t>(q)].empty()) continue;  // 对角退化空路径
        for (int i = 0; i < out_len; ++i) {
          const float a = std::fabs(ir_buf[static_cast<std::size_t>(q)][static_cast<std::size_t>(i)]);
          if (a > peak_all) peak_all = a;
        }
      }
      if (peak_all > 1e-12f) {
        const float th = peak_all * 1e-4f;  // -80 dB
        int keep = 0;
        for (int q = 0; q < 4; ++q) {
          if (ir_buf[static_cast<std::size_t>(q)].empty()) continue;
          for (int i = out_len - 1; i >= keep; --i) {
            if (std::fabs(ir_buf[static_cast<std::size_t>(q)][static_cast<std::size_t>(i)]) > th) {
              if (i + 1 > keep) keep = i + 1;
              break;
            }
          }
        }
        if (keep < 1) keep = 1;
        if (keep < out_len) {
          for (int q = 0; q < 4; ++q) {
            if (ir_buf[static_cast<std::size_t>(q)].empty()) continue;
            ir_buf[static_cast<std::size_t>(q)].resize(static_cast<std::size_t>(keep));
          }
          out_len = keep;
          user_ir_frames_ = keep;
        }
      }
    }
    // 病态前置自动对齐（方案 A，2026-10-26；口径 2026-10-27 段级化；范围
    // 2026-10-27 扩大全覆盖）：前置静音 >30ms（Haas 融合区外）→ 四路径统一
    // 裁掉公共静音前缀，湿声不再迟到，干湿对齐，双声消除；噪声底一并裁掉
    // （HIFI最终版2 -61dB / super sound人声 -62dB 实证）。设计型纯零预延迟
    // （≤-96dB）不再豁免——用户决策：检测到病态一律校正（延迟设计意图的
    // 场景用设置开关整体关闭，7y-b 守卫）。
    // 2026-10-27 SRS_2_2 教训：深静音残留段（RMS -64.7dB）中的孤立
    // 毛刺样本会顶穿逐样本瞬时门，把 186ms 主体迟到误判成 1.1ms 前置而不裁
    // → 起点判定改 2ms 窗 RMS 段级门（与主脉冲包络同窗同实现，7y-c 守卫）。
    // 只裁内存副本，pulse/ 原文件不动；SetTrimSickHead(false) 整体关闭
    // （设置项）。检测与 InspectIr 同一实现（口径单一）；四路径同长度裁剪
    // 保持矩阵时间对齐（与湿尾裁剪同一哲学）。位于湿尾裁剪之后、UI 包络/
    // 能量/病态报告生成之前 → 分析数据全部反映裁后形态。
    if (trim_sick_head_.load(std::memory_order_relaxed)) {
      const std::vector<float>* dl = ir_buf[0].empty() ? nullptr : &ir_buf[0];
      const std::vector<float>* dr = ir_buf[3].empty() ? nullptr : &ir_buf[3];
      if (dl != nullptr) {
        float pre_db = -100.0f;
        const int s = HeadSilenceFrames(dl, dr, sample_rate_, &pre_db);
        const float s_ms = static_cast<float>(s) * 1000.0f / static_cast<float>(sample_rate_);
        if (s_ms > 30.0f) {
          for (int q = 0; q < 4; ++q) {
            if (ir_buf[static_cast<std::size_t>(q)].empty()) continue;
            ir_buf[static_cast<std::size_t>(q)].erase(
                ir_buf[static_cast<std::size_t>(q)].begin(),
                ir_buf[static_cast<std::size_t>(q)].begin() + s);
          }
          out_len -= s;
          user_ir_frames_ = out_len;
          last_trim_ms_ = s_ms;
        }
      }
    }
    // 分析数据（UI 频谱/能量显示，重采样后、归一前的原始形态）：取对角
    // LL/RR 两路（2ch 文件即 L/R；4ch 交叉矩阵的 UI 视角仍按左右直达项）。
    // 能量 = Σ|ir|（万向同口径）；波形 = 512 点 max-abs 包络（脉冲不被稀释）
    for (int c = 0; c < channels_ && c < 2; ++c) {
      const std::vector<float>& resampled = ir_buf[c == 0 ? 0 : 3];
      if (resampled.empty()) continue;
      double e = 0.0;
      for (int i = 0; i < out_len; ++i) e += std::fabs(static_cast<double>(resampled[static_cast<std::size_t>(i)]));
      user_energy_[c] = static_cast<float>(e);
      const int seg = out_len / kWavPoints > 0 ? out_len / kWavPoints : 1;
      for (int w = 0; w < kWavPoints; ++w) {
        float best = 0.0f;
        const int start = w * seg;
        const int stop = start + seg > out_len ? out_len : start + seg;
        for (int i = start; i < stop; ++i) {
          const float v = resampled[static_cast<std::size_t>(i)];
          if (std::fabs(v) > std::fabs(best)) best = v;  // 保留符号的峰值
        }
        user_wave_[static_cast<std::size_t>(c) * kWavPoints + static_cast<std::size_t>(w)] = best;
      }
    }
    // 病态检测 + 每声道频谱（只读，不影响装载行为）：前置静音/主脉冲/
    // 左右对称性在引擎域对角路径（重采样后、尾部裁剪后的实际内容）上算；
    // NaN/削波/频谱在源域（wav，清洗后）上算。HIFI最终版2.irs 实证：
    // 该文件 pre_silence≈161ms → risk 2，与五款软件真机复听双声一致。
    {
      const std::vector<float>* diag_l = ir_buf[0].empty() ? nullptr : &ir_buf[0];
      const std::vector<float>* diag_r = ir_buf[3].empty() ? nullptr : &ir_buf[3];
      InspectIr(wav, diag_l, diag_r, sample_rate_, &user_ir_report_);
      user_ir_report_.trimmed_ms = last_trim_ms_;  // 裁剪信息随报告出（UI 横幅）
    }
    // 湿声电平自动校准建议（批次 2，2026-10-27）：稳态能量归一（下方 0.3）已
    // 拉平不同 IR 的总湿声响度，剩余干湿感差异来自形态——脉冲型 IR（HRTF/舞台）
    // 能量集中少数 tap、与干声同相相干融合，湿声几乎听不出「混响」；扩散长尾
    // 掩蔽干声明显发糊。以波峰因数推建议值（经验形态归一，非精确定律；只算
    // 不改湿声路径）：
    //   crest = E_total / peak²（对角联合，裁后，CrestFactor；脉冲≈1、扩散大）
    //   suggested = 1 + log2(3 / max(crest,1)) / 4，clamp 0.6~1.5
    // 锚 = 3.0 = 音乐 DSP 卷积核典型 crest（2026-10-27 真机脉冲库 PROBE 实测
    // 6/6 落在 2.1~5.1：魔神定制HiFi 2.2 / SRS_2_2 2.8 / Archy kodi 5.1）→
    // 主力库建议 ≈0.8~1.2（贴近用户手动值 1.0）；纯 HRTF 脉冲（crest≈1）1.40；
    // 实测房间 RIR（crest 数百~数千）→ 底格收敛（长尾本就湿）。log2 域每 16 倍
    // crest 差 → 建议 1.0 变化。首版 sqrt(RIR 标定/crest) 因 RIR crest=1243
    // 离群 2~3 个数量级，对真实库全顶格 1.5 无区分度，弃用（7z 实证）。
    // Kotlin「湿声电平自动校准」开关开时应用；用户手动拉滑块即覆盖（同一 IR
    // 不重复校准，见 ViewModel）；开关关-开一次可全库按新口径重校准。
    {
      double crest = CrestFactor(
          ir_buf[0].empty() ? nullptr : &ir_buf[0],
          ir_buf[3].empty() ? nullptr : &ir_buf[3]);
      if (crest < 1.0) crest = 1.0;
      user_ir_report_.crest_factor = static_cast<float>(crest);
      float s = 1.0f + (std::log2(3.0f) - std::log2(static_cast<float>(crest))) / 4.0f;
      if (s < 0.6f) s = 0.6f;
      if (s > 1.5f) s = 1.5f;
      user_ir_report_.suggested_wet_gain = s;
    }
    // 湿尾能量归一（文件 IR 专用参数，2×2 按输出侧合并口径）：
    //   g_out = 0.3 / sqrt(到该输出的所有路径能量和)——L 侧 = (LL,RL)、
    //   R 侧 = (LR,RR)。2ch 对角文件退化为旧的每通道独立 g（7k 锁定，
    //   精确零回归）；4ch 交叉矩阵把同侧多路径能量合并归一，湿峰值保护
    //   依旧成立。
    // 与内置 RIR（0.5）分离的原因：HRTF/舞台类 IR 是脉冲/早反射型——能量
    // 集中在开头少数样本且与干声同相叠加（非相干抵消不成立），能量归一
    // 0.5 后湿峰值可叠加超 1 → SoftClip 持续弯曲 = 可闻失真（2026-10-23
    // 用户加载奥菲斯 HRTF 实测爆音杂音多）。文件 IR 用湿尾 0.3 + 湿峰值
    // 上限 0.6（干声 0.75 由 kDryGain 显式混合），稳态 |H| ≈ 1.05 以内。
    {
      double side_energy[2] = {0.0, 0.0};  // [out]：L=0、R=1（路径 idx = in*2+out → out = idx&1）
      for (int q = 0; q < 4; ++q) {
        if (ir_buf[q].empty()) continue;  // 对角退化路径（1ch/2ch 文件）
        const int side = q & 1;
        for (int i = 0; i < out_len; ++i) {
          const double v = static_cast<double>(ir_buf[q][static_cast<std::size_t>(i)]);
          side_energy[side] += v * v;
        }
      }
      float g_side[2], peak_side[2] = {0.0f, 0.0f};
      // NaN 防线语义（2026-10-26）：side_energy 混入 NaN 时比较恒 false →
      // g=0（静音该侧）而非传播 NaN 炸整条链——是「最后防线」不是清洗点；
      // 数值垃圾应在 wav_loader 读入时置零（否则单声道脏样本 = 整侧湿声
      // 冤案静音，HIFI最终版2.irs 实测踩中）。本门只兜「上游漏网」。
      for (int s = 0; s < 2; ++s) {
        g_side[s] = side_energy[s] > 1e-18 ? 0.3f / static_cast<float>(std::sqrt(side_energy[s])) : 0.0f;
      }
      for (int q = 0; q < 4; ++q) {
        if (ir_buf[q].empty()) continue;
        const float g = g_side[q & 1];
        for (int i = 0; i < out_len; ++i) {
          ir_buf[q][static_cast<std::size_t>(i)] *= g;
          const float a = std::fabs(ir_buf[q][static_cast<std::size_t>(i)]);
          if (a > peak_side[q & 1]) peak_side[q & 1] = a;
        }
      }
      for (int s = 0; s < 2; ++s) {
        if (peak_side[s] > 0.6f) {
          const float pg = 0.6f / peak_side[s];
          for (int q = 0; q < 4; ++q) {
            if (ir_buf[q].empty() || (q & 1) != s) continue;
            for (int i = 0; i < out_len; ++i) ir_buf[q][static_cast<std::size_t>(i)] *= pg;
          }
        }
      }
    }
    // 干声不注入 IR——统一由 Process 显式混合（wet 增益可独立缩放，干湿分离）。
    SetIrSlot2x2(kPresetUser,
                 ir_buf[0].empty() ? nullptr : ir_buf[0].data(),
                 ir_buf[1].empty() ? nullptr : ir_buf[1].data(),
                 ir_buf[2].empty() ? nullptr : ir_buf[2].data(),
                 ir_buf[3].empty() ? nullptr : ir_buf[3].data(),
                 out_len, has_cross ? 4 : 2);
    user_ir_channels_ = wav.channel_count;  // 源文件真实通道数（1/2/4+），UI 显示用
    user_ir_loaded_.store(true, std::memory_order_release);
    return true;
  }

  bool UserIrLoaded() const { return user_ir_loaded_.load(std::memory_order_acquire); }

  /**
   * 尾分区上限（低端机负载分档）：各预设有效分区数 preset_t_[p] 在 SetIrSlot2x2
   * 里钳到 min(t_, limit)。limit <= 0 = 不限制。须在 Prepare 前设置（spec 一次性
   * 计算，事后设置不回溯）。低端机教堂 118 分区封顶 32 分区 ≈ 800ms 尾。
   */
  void SetTailPartitionLimit(int max_parts) {
    tail_partition_limit_.store(max_parts, std::memory_order_relaxed);
  }
  /** 病态脏前置自动对齐开关（默认开）。关闭 = 保持 IR 原样装载（警示照常）。 */
  void SetTrimSickHead(bool on) { trim_sick_head_.store(on, std::memory_order_relaxed); }
  /** 最近一次装载实际裁除的前置 ms（0 = 未裁；harness 断言/UI 诊断用） */
  float LastTrimMs() const { return last_trim_ms_; }
  const std::string& LastLoadError() const { return last_load_err_; }

  // ---- 用户 IR 分析数据（UI 频谱/能量显示，加载时生成） ----
  static constexpr int kWavPoints = 512;  // 波形包络点数
  float UserEnergy(int c) const { return user_energy_[c]; }
  const float* UserWave(int c) const { return user_wave_.data() + static_cast<std::size_t>(c) * kWavPoints; }
  int UserIrFrames() const { return user_ir_frames_; }
  /** 用户 IR 源文件通道数（1/2/4…；0 = 未加载） */
  int UserIrChannels() const { return user_ir_channels_; }
  /** 病态检测报告 + 每声道频谱（装载时生成；未加载 = 默认值全零） */
  const IrInspectReport& UserIrReport() const { return user_ir_report_; }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) {
    channels_ = channels;
  }

 private:
  /** 负数安全的环形槽位（pos 可为负：前导零语义由缓冲初始零保证） */
  static std::size_t RingPos(long long pos, std::size_t cap) {
    long long m = pos % static_cast<long long>(cap);
    return static_cast<std::size_t>(m < 0 ? m + static_cast<long long>(cap) : m);
  }

  /**
   * 将时域 2×2 路径 IR 写入 slot p 的分区结构（阶段 B 唯一填表路径）：
   * head[0..H) 时域保存；tail 分区 t = ir[H+tP .. H+(t+1)P) 逐分区实数 FFT
   * （每路径独立，Process 分离乘直接复用）。ir_len 之外按零填充；路径指针
   * 为 nullptr = 该路径全零（对角退化的 LR/RL）。
   * paths：该 preset 的活跃路径组数（2=对角快路径，4=完整交叉），写
   * preset_paths_[p]，ComputeBatch 据此选择分离乘组数。非实时线程调用。
   */
 public:
  void SetIrSlot2x2(int p, const float* irLL, const float* irLR,
                    const float* irRL, const float* irRR, int ir_len, int paths) {
    const float* irs[4] = {irLL, irLR, irRL, irRR};
    if (ir_len > kMaxIrLen) ir_len = kMaxIrLen;
    if (ir_len < 0) ir_len = 0;
    // P1 并发门（2026-10-27 自检）：slot 5 运行中重装时（换 IR / 切 trim 开关 /
    // 恢复链路三入口），实时线程可能正挂在 USER 预设读 spec/head。先原子清零
    // t_eff 关闸（Accumulate 求和循环整体跳过 = 装载窗口内湿尾干净静音，替代
    // 原先的撕裂频谱垃圾声）；paths 先行写（关闸期读到的值不生效）；head/spec
    // 全部写完后 release 写回 t_eff 开闸——实时线程 acquire 读到新 t_eff 时新
    // spec 必已完整可见。残余瞬态：开闸前 half=0 已锁定旧 batch_t_eff_ 的在途
    // 批次可能读半个新 spec（≤半批 ~0.3ms，新旧均为合法 IR 频谱，无垃圾声）；
    // head 顺序覆盖撕裂窗口仅 512 float 写入（纳秒级），同上口径。
    preset_t_[p].store(0, std::memory_order_release);
    preset_paths_[p].store(static_cast<unsigned char>(paths == 4 ? 4 : 2),
                           std::memory_order_relaxed);
    // 该 preset 的有效 tail 分区数：ir_len ≤ h_ 时尾部全零（t_eff=0，纯 head）。
    // 求和循环只跑 t_eff 个分区——零分区乘积恒零，跳过数学恒等、纯省 CPU
    // （B1 把全局 t_ 提到 128 后，短 IR preset 曾被迫全量跑 128 分区零乘，
    //  批次帧 ~1ms/批 = 前台贴预算、后台降频即 xRun，实听「哔哔啵啵欠载感」）。
    int t_eff = (ir_len > h_) ? (ir_len - h_ + p_ - 1) / p_ : 0;
    // 钳制到 t_ 不只是省 CPU，还是 X_cache 槽冲突的正确性约束：槽 = k mod t_
    // （ComputeBatchHalf），批 k 消费读槽 (k-t) mod t_，t ∈ [0, t_eff)。要求
    // t_eff ≤ t_（t=0 写后读是设计行为，写在求和前）。超长 IR 尾部被钳掉的
    // 分区静默丢弃（内容缺失但无陈旧槽读）。
    if (t_eff > t_) t_eff = t_;
    const int lim = tail_partition_limit_.load(std::memory_order_relaxed);
    if (lim > 0 && t_eff > lim) t_eff = lim;
    for (int q = 0; q < 4; ++q) {
      const float* ir = irs[q];  // nullptr = 全零路径
      float* head = &head_ir_[(static_cast<std::size_t>(p) * 4 + static_cast<std::size_t>(q)) * h_];
      for (int i = 0; i < h_; ++i) head[i] = (ir != nullptr && i < ir_len) ? ir[i] : 0.0f;
      for (int t = 0; t < t_; ++t) {
        float* re = &tail_spec_re_[(static_cast<std::size_t>(p) * t_ + t) * 4 * n2_ + static_cast<std::size_t>(q) * n2_];
        float* im = &tail_spec_im_[(static_cast<std::size_t>(p) * t_ + t) * 4 * n2_ + static_cast<std::size_t>(q) * n2_];
        for (int j = 0; j < n2_; ++j) {
          const int idx = h_ + t * p_ + j;
          re[j] = (ir != nullptr && j < p_ && idx < ir_len) ? ir[idx] : 0.0f;
          im[j] = 0.0f;
        }
        fft_.Forward(re, im);
      }
    }
    preset_t_[p].store(t_eff, std::memory_order_release);  // 最后开闸
  }

  /** 对角包装（内置 RIR / 2ch 单入双出 IR）：LR=RL=零，走 2 组乘快路径。 */
  void SetIrSlot(int p, const float* irL, const float* irR, int ir_len) {
    SetIrSlot2x2(p, irL, nullptr, nullptr, irR, ir_len, 2);
  }

 private:
  /**
   * tail 批次 k（仅非实时语义约束下的实时线程调用，无分配无锁）——拆半削峰版：
   * u 批次 = x[kP-H, (k+1)P-H)（输入历史环形读取，前导负位置 = 初始零）→
   * dual-real 打包 FFT → X_cache[(k mod T)] 环形写入 →
   * half=0：ACC 清零 + Σ_t 前半分区分离乘（锁定预设号/批号/t_eff 存档）；
   * half=1：Σ_t 后半分区 + IFFT → 前 P 帧 = z 批次（实部 L / 虚部 R）→
   *         写 tail_out 槽 (k+1)P+m。
   * 动机（2026-10-24 蓝牙啵啵取证）：教堂 t_eff=118 单批尖峰 0.612ms 集
   * 中消耗，供给节拍毛刺在蓝牙 A2DP 栈内断流（app 侧探针不可见）——拆
   * 半后单块批次尖峰减半。两半必须同批号/同预设：切预设/换 IR 只能发生
   * 在批间（后半用前半存档的 batch_*，区间自洽；spec 内容在加载瞬间本
   * 就是「未定义」口径，与拆批前一致）。
   */
  void ComputeBatchHalf(int k, int half) {
    if (half == 0) {
      // 1) u 批次打包
      for (int j = 0; j < p_; ++j) {
        const std::size_t slot = RingPos(static_cast<long long>(k) * p_ - h_ + j, cap_hist_);
        b_re_[j] = x_hist_[slot];
        b_im_[j] = x_hist_[cap_hist_ + slot];
      }
      for (int j = p_; j < n2_; ++j) {
        b_re_[j] = 0.0f;
        b_im_[j] = 0.0f;
      }
      fft_.Forward(b_re_.data(), b_im_.data());
      // 2) X_cache 环形写入（槽 k mod t_；槽数即分区总数 t_，消费约束
      //    t_eff ≤ t_ 见 SetIrSlot2x2 钳制处——t=0 写后读，t≥1 读的槽
      //    k-t 距本槽至少差 1，不被本批覆盖）
      const int slot_k = k % t_;
      float* cr = &x_cache_re_[static_cast<std::size_t>(slot_k) * n2_];
      float* ci = &x_cache_im_[static_cast<std::size_t>(slot_k) * n2_];
      for (int i = 0; i < n2_; ++i) {
        cr[i] = b_re_[i];
        ci[i] = b_im_[i];
      }
      // 3a) ACC 清零 + 前半分区求和；锁定批参数供后半（防批间切换/重填混加）
      for (int i = 0; i < n2_; ++i) {
        acc_re_[i] = 0.0f;
        acc_im_[i] = 0.0f;
      }
      batch_k_ = k;
      batch_preset_ = current_preset_;
      // acquire 配对 SetIrSlot2x2 的 release 开闸：读到 t_eff>0 时新 spec 完整可见
      batch_t_eff_ = preset_t_[batch_preset_].load(std::memory_order_acquire);
      batch_half_ = 1;
      Accumulate(0, batch_t_eff_ > 0 ? (batch_t_eff_ + 1) / 2 : 0);
    } else {
      // 3b) 后半分区求和（区间自洽用存档值）
      const int half_t = batch_t_eff_ > 0 ? (batch_t_eff_ + 1) / 2 : 0;
      Accumulate(half_t, batch_t_eff_);
      batch_half_ = 0;
      // 4) IFFT → 2P 帧：前 P 帧 = 本批主段（分区 i≤r 项），后 P 帧 = 下一批提前
      //    贡献（分区 i>r 的 wrap 项，见类注释）
      fft_.Inverse(acc_re_.data(), acc_im_.data());
      // 5) 完整 z 批次 = overlap（上一批后段） + 本批前段 → 写 tail_out 槽 (k+1)P+m
      //   （消费端读槽 n 得 y_t[n-P]）；随后本批后段存入 overlap 供下一批。
      for (int m = 0; m < p_; ++m) {
        const std::size_t slot = RingPos((static_cast<long long>(k) + 1) * p_ + m, cap_out_);
        tail_out_[slot] = acc_re_[m] + overlap_l_[m];
        tail_out_[cap_out_ + slot] = acc_im_[m] + overlap_r_[m];
      }
      for (int m = 0; m < p_; ++m) {
        overlap_l_[m] = acc_re_[p_ + m];
        overlap_r_[m] = acc_im_[p_ + m];
      }
    }
  }

  /** 分离乘求和 [t_from, t_to)：累加进 ACC（前半/后半共用，t 顺序与整批单循环一致）。 */
  void Accumulate(int t_from, int t_to) {
    const int k = batch_k_;
    const float* spec_re_base = &tail_spec_re_[static_cast<std::size_t>(batch_preset_) * t_ * 4 * n2_];
    const float* spec_im_base = &tail_spec_im_[static_cast<std::size_t>(batch_preset_) * t_ * 4 * n2_];
    const bool full =
        preset_paths_[batch_preset_].load(std::memory_order_relaxed) == 4;  // 2=对角快路径 4=完整交叉
    for (int t = t_from; t < t_to; ++t) {
      // 读槽 (k-t) mod t_：t_eff ≤ t_ 保证 t ≥ 1 时读的是历史批次的槽、
      // 不与本批写入槽 k mod t_ 冲突（约束推导见 SetIrSlot2x2 钳制处）
      const int xs = ((k - t) % t_ + t_) % t_;
      const float* xr = &x_cache_re_[static_cast<std::size_t>(xs) * n2_];
      const float* xi = &x_cache_im_[static_cast<std::size_t>(xs) * n2_];
      // 路径指针 idx = in*2+out：0=LL 1=LR 2=RL 3=RR
      const float* hre0 = spec_re_base + (static_cast<std::size_t>(t) * 4 + 0) * n2_;
      const float* him0 = spec_im_base + (static_cast<std::size_t>(t) * 4 + 0) * n2_;
      const float* hre1 = spec_re_base + (static_cast<std::size_t>(t) * 4 + 1) * n2_;
      const float* him1 = spec_im_base + (static_cast<std::size_t>(t) * 4 + 1) * n2_;
      const float* hre2 = spec_re_base + (static_cast<std::size_t>(t) * 4 + 2) * n2_;
      const float* him2 = spec_im_base + (static_cast<std::size_t>(t) * 4 + 2) * n2_;
      const float* hre3 = spec_re_base + (static_cast<std::size_t>(t) * 4 + 3) * n2_;
      const float* him3 = spec_im_base + (static_cast<std::size_t>(t) * 4 + 3) * n2_;
      for (int kk = 0; kk < n2_; ++kk) {
        const int kc = (kk == 0) ? 0 : n2_ - kk;
        const float ar = xr[kk], ai = xi[kk];
        const float br = xr[kc], bi = xi[kc];
        // 分离：X_L = (Z[k]+conj(Z[N-k]))/2，X_R = (Z[k]-conj(Z[N-k]))/(2i)
        const float xl_re = (ar + br) * 0.5f, xl_im = (ai - bi) * 0.5f;
        const float xr_re = (ai + bi) * 0.5f, xr_im = (br - ar) * 0.5f;
        // Y_L = X_L·H_LL + X_R·H_RL；Y_R = X_L·H_LR + X_R·H_RR
        // （对角快路径 = 交叉项系数零：Y_L = X_L·H_LL、Y_R = X_R·H_RR，
        //  乘加数同阶段 A。合成 Z' = Y_L + i·Y_R（两个 acc 均 Hermitian，
        //  IFFT 后实部=L、虚部=R）。
        float yl_re, yl_im, yr_re, yr_im;
        if (full) {
          const float a_re = xl_re * hre0[kk] - xl_im * him0[kk];
          const float a_im = xl_re * him0[kk] + xl_im * hre0[kk];
          const float b_re = xr_re * hre2[kk] - xr_im * him2[kk];
          const float b_im = xr_re * him2[kk] + xr_im * hre2[kk];
          yl_re = a_re + b_re;
          yl_im = a_im + b_im;
          const float c_re = xl_re * hre1[kk] - xl_im * him1[kk];
          const float c_im = xl_re * him1[kk] + xl_im * hre1[kk];
          const float d_re = xr_re * hre3[kk] - xr_im * him3[kk];
          const float d_im = xr_re * him3[kk] + xr_im * hre3[kk];
          yr_re = c_re + d_re;
          yr_im = c_im + d_im;
        } else {
          yl_re = xl_re * hre0[kk] - xl_im * him0[kk];
          yl_im = xl_re * him0[kk] + xl_im * hre0[kk];
          yr_re = xr_re * hre3[kk] - xr_im * him3[kk];
          yr_im = xr_re * him3[kk] + xr_im * hre3[kk];
        }
        acc_re_[kk] += yl_re - yr_im;
        acc_im_[kk] += yl_im + yr_re;
      }
    }
  }

  /**
   * 输出软限幅兜底：|x|≤0.9 线性直通（零失真），超出部分 tanh 平滑弯曲压到 ±1。
   * 动机：干声 tap 与湿声瞬时叠加大动态处峰值可到 ~1.3-1.5，末级限幅器被
   * 误关时输出硬削波破音（2026-10-23 用户实听）；此安全网保证混响节点单独开启
   * 也不破音。连续无导数跳变，不产生削波毛刺。
   */
  static float SoftClip(float x) {
    // denormal（次正规数）清除（2026-11-08）：长尾卷积衰减到 1e-30 量级后会落入
    // 次正规数区间，ARM 上这类数值的运算可能慢 10~100 倍 → 偶发单帧耗时尖峰，
    // 音频表现为「滋啦」。教堂尾巴最长（2.5s）、最容易吃到这个。
    // 用 `!(|x| > 阈值)` 而非 `<`，同时覆盖 0 与 NaN（NaN 比较恒 false）。
    if (!(std::fabs(x) > 1e-15f)) return 0.0f;
    constexpr float kLin = 0.9f;
    constexpr float kHead = 1.0f - kLin;  // 0.1
    if (x > kLin) return kLin + std::tanh((x - kLin) / kHead) * kHead;
    if (x < -kLin) return -kLin + std::tanh((x + kLin) / kHead) * kHead;
    return x;
  }

  /**
   * 频率相关衰减（2026-11-08，修「教堂预设啵啵吱吱噪音」）。
   *
   * **病因（实测证据）**：内置四个预设的 IR 分频段 T30 几乎相等
   * （教堂 2.40 / 2.45 / 2.45s），谱重心恒在 12000Hz（≈ sr/4，白噪声理论值），
   * 200Hz 以下能量仅 0.8%、无早期反射尖峰 —— 这批标注 measured RIR 的数据实质是
   * 「白噪声 × 统一指数包络」。真实房间的低频衰减远慢于高频（空气与材料吸收），
   * 所以噪声型 IR 的听感就是：低频随机起伏 = 「啵啵」，高频噪声 = 「吱吱」。
   * 教堂因尾巴最长（2.5s）最明显，小房间/客厅/大房间只是噪声太短未被察觉。
   *
   * **做法**：两级一阶低通把 IR 分成低/中/高三段，中/高频额外乘快速衰减包络
   * （中频 τ=1.2s、高频 τ=0.35s，取绝对时间以保证各预设的高频消失速度一致），
   * 低频原样保留（保住丰满度）。最后按 IR 时长做**混合强度**：
   *   0.5s 以下几乎不动（本来听不出问题，别动正常的东西）
   *   2.5s 满强度（教堂）
   * 结束时归一化回原能量基准 Σir²=0.25，保证湿声电平不跳变。
   *
   * **只在 Prepare 的离线预处理里跑**，实时路径零成本。
   */
  static void ApplyFreqDependentDecay(float* ir, int len, int sr) {
    if (ir == nullptr || len <= 0 || sr <= 0) return;
    const float dur = static_cast<float>(len) / static_cast<float>(sr);
    float strength = (dur - 0.5f) / 2.0f;  // 0.5s→0，2.5s→1
    if (strength <= 0.0f) return;
    if (strength > 1.0f) strength = 1.0f;

    constexpr float kTwoPi = 6.283185307179586f;
    const float a_low = 1.0f - std::exp(-kTwoPi * 500.0f / static_cast<float>(sr));
    const float a_high = 1.0f - std::exp(-kTwoPi * 4000.0f / static_cast<float>(sr));
    const float tau_mid = 1.2f * static_cast<float>(sr);   // 样本
    const float tau_high = 0.35f * static_cast<float>(sr);

    // **级联互补**分频（2026-11-08 修正）。
    //
    // 曾经踩过的坑：直接对 v 做二阶低通再用 `v - lp2b` 当高通 —— lp2b 相位滞后
    // 约 90°，相减时低频并没有被抵消掉（反而在低频处产生凹陷），于是低频漏进
    // "高频段"被快速衰减，高频又漏进低频段不被衰减 → 音色被扭曲，听感比修复前更糟。
    // 正确做法：每一层只做「低通输出 + 余量」，则 low + rest 恒等于输入，与相位无关；
    // 余量再做下一层。三段相加严格等于 v，衰减某段只改变该段能量，不引入相位错乱。
    // 原始峰值先记下来：归一化时要一并约束（见下方说明）
    float orig_peak = 0.0f;
    for (int i = 0; i < len; ++i) {
      const float a = std::fabs(ir[i]);
      if (a > orig_peak) orig_peak = a;
    }

    float s1a = 0.0f, s1b = 0.0f;  // 500Hz 两级串联 → 低频段（4kHz 处 -36dB）
    float s2 = 0.0f;               // 在「余量」上再分 4kHz → 中频
    double energy = 0.0;
    float new_peak = 0.0f;
    for (int i = 0; i < len; ++i) {
      const float v = ir[i];
      s1a += a_low * (v - s1a);
      s1b += a_low * (s1a - s1b);
      const float low = s1b;
      const float restLow = v - low;  // 严格互补：low + restLow = v
      s2 += a_high * (restLow - s2);
      const float mid = s2;
      const float high = restLow - s2;  // 严格互补：mid + high = restLow
      const float t = static_cast<float>(i);
      const float processed =
          low + mid * std::exp(-t / tau_mid) + high * std::exp(-t / tau_high);
      // 按 IR 时长混回原样：短 IR 保持原状，长 IR 才响应
      const float y = v + (processed - v) * strength;
      ir[i] = y;
      energy += static_cast<double>(y) * y;
      const float a = std::fabs(y);
      if (a > new_peak) new_peak = a;
    }

    // 归一化必须**同时**约束能量与峰值（2026-11-08 修复）。
    // 只对总能量归一化时，被削掉的高频能量会全部堆到低频 —— 低频峰值因子本来就高，
    // 结果峰值反而从 0.58 涨到 0.88（实测，真实音乐卷积），叠加干声后逼近满刻度，
    // 对低频响应差的手机扬声器就是直接的破音（用户实测「切扬声器更严重」）。
    // 故取两者中更严格的那个：能量不超基准，峰值也绝不高于原始 IR。
    if (energy > 1e-12) {
      const float g_energy = static_cast<float>(std::sqrt(0.25 / energy));
      const float g_peak = (new_peak > 1e-9f && orig_peak > 1e-9f)
                               ? (orig_peak / new_peak)
                               : g_energy;
      const float g = (g_peak < g_energy) ? g_peak : g_energy;
      for (int i = 0; i < len; ++i) ir[i] *= g;
    }
  }

  /**
   * 密度优化版合成 IR（V2）：预延迟 + 早反射簇 + 双层衰减噪声。
   * 输出为未归一形态（保留作离线密度实验工具，当前未被调用）。
   */
  void GenerateIr(float* ir, int len, float tau_ms, float predelay_ms, uint32_t seed) {
    const float sr = static_cast<float>(sample_rate_);
    const int pre = static_cast<int>(predelay_ms * 0.001f * sr);
    const float decay1 = 1.0f / (tau_ms * 0.45f * 0.001f * sr);
    const float decay2 = 1.0f / (tau_ms * 0.001f * sr);
    const int late_start = pre + static_cast<int>(0.015f * sr);
    uint32_t s = seed | 1u;
    float lp1 = 0.0f, lp2 = 0.0f;
    for (int i = 0; i < len; ++i) ir[i] = 0.0f;
    for (int i = pre; i < len; ++i) {
      s = s * 1664525u + 1013904223u;
      const float noise = static_cast<float>(static_cast<int32_t>(s)) / 2147483648.0f;
      const float env = std::exp(-static_cast<float>(i - pre) * decay2);
      // 层 1：快衰减亮层（前段密度主力）
      lp1 += 0.6f * (noise - lp1);
      ir[i] = lp1 * std::exp(-static_cast<float>(i - pre) * decay1);
      // 层 2：慢衰减暗层，pre+15ms 后 15ms 平滑渐入，尾密度
      if (i >= late_start) {
        lp2 += 0.35f * (noise - lp2);
        const float fade = std::fmin(1.0f, static_cast<float>(i - late_start) / (0.015f * sr));
        ir[i] += lp2 * env * fade;
      }
    }
    // 早反射簇：7 个离散 tap（符号由 seed 位决定，左右独立于噪声纹理）
    {
      const float tap_ms[7] = {1.0f, 4.0f, 9.0f, 15.0f, 21.0f, 25.0f, 28.0f};
      for (int k = 0; k < 7; ++k) {
        const int idx = pre + static_cast<int>(tap_ms[k] * 0.001f * sr);
        if (idx < len) {
          const float sign = ((seed >> k) & 1u) != 0u ? 1.0f : -1.0f;
          ir[idx] += sign * 0.10f * std::exp(-static_cast<float>(k) * 0.35f);
        }
      }
    }
  }

  int sample_rate_ = 48000;
  int channels_ = 2;
  bool bypass_ = true;
  int block_ = 128;
  // ---- 分区结构参数（Prepare 推导） ----
  int h_ = 128;    // head 长度 = block（零延迟 early reflections）
  int p_ = 1024;   // tail 批次长 = 分区长 = 交付延迟
  int n2_ = 2048;  // dual-real 打包复 FFT 点数（= 2P 实数点）
  int t_ = 8;      // tail 分区数 = ceil((kMaxIrLen-H)/P)
  std::size_t cap_hist_ = 2048;  // 输入历史环形容量（2 的幂）
  std::size_t cap_out_ = 4096;   // tail 输出环形容量（2 的幂）
  int current_preset_ = kPresetDry;
  std::atomic<bool> user_ir_loaded_{false};  // slot 5 就绪标志（协程写/实时线程读）
  // 尾分区上限（低端机分档，Prepare 前设置；0 = 不限制）
  std::atomic<int> tail_partition_limit_{0};
  std::string last_load_err_;                // 最近一次加载失败原因（非实时线程）
  std::vector<float> user_wave_;             // [ch][512] max-abs 包络（UI 显示）
  float user_energy_[2] = {0.0f, 0.0f};      // Σ|ir|（重采样后、归一前，万向同口径）
  int user_ir_frames_ = 0;
  int user_ir_channels_ = 0;  // 源文件真实通道数（LoadImpulseResponseFromFile 写，0=未加载）
  IrInspectReport user_ir_report_;  // 病态检测 + 每声道频谱（装载时生成，UI 警示用）
  std::atomic<bool> trim_sick_head_{true};  // 病态脏前置自动对齐（方案 A，默认开；JNI 线程写/装载协程读）
  float last_trim_ms_ = 0.0f;     // 最近一次装载裁除的前置 ms（每装载清零）
  // dry/wet 混合增益表（按预设）：干声预设恒等；实测 RIR 预设干声 0.85（湿尾归一 0.5
  // 非相干叠加稳态 ~0.99）；自定义预设干声 0.75 + wet 可调（脉冲型 IR 相干叠加，
  // 归一 0.3 基准下稳态 ~1.05 以内）。
  // 干声增益 = X-macro 单一定义源展开（每预设一行，与索引/参数表强同步）。
  // 旧教训：手工并行表在 kPresetCount 扩到 6 时只写了 5 个初值，第 6 项被编译器
  // 补 0.0 → 自定义预设纯湿声无干声 → 实听「发闷」（2026-10-23 用户报告）。
  // 现表长由枚举 kPresetCount 锁定，加预设不可能再漏。
#define SONICRAFT_CONV_PRESET_DRY(name, disp, rirL, rirR, preS, lp, dry) (dry),
  static constexpr float kDryGain[kPresetCount] = {
      SONICRAFT_CONV_PRESETS(SONICRAFT_CONV_PRESET_DRY)};
#undef SONICRAFT_CONV_PRESET_DRY
  float target_wet_ = 1.0f;
  float current_wet_ = 1.0f;
  // M9 省电快路径命中计数（Process 写/诊断读，relaxed 足够）
  std::atomic<long long> dry_fast_blocks_{0};  // 干声 head δ 直赋命中块数
  std::atomic<long long> wet_fast_blocks_{0};  // 湿零快路径命中块数

  // ==== 诊断探针（2026-11-06）====
  // 目的：区分「混响消失」的两类根因
  //   ① prepare_count_ 增长且预设回到 Dry → 图被重建、Prepare 重置了预设（治本=重建后补发参数）
  //   ② 预设正常但 wet_rms_ 恒 0 → 卷积状态坏死（NaN/denormal，治本=wet 哨兵）
  // 均为低频读写，成本可忽略。
  int prepare_count_ = 0;      // Prepare() 被调用次数（图重建证据）
  float wet_rms_ = 0.0f;       // 最近一块湿声 RMS（0 = 无湿声输出）
  float wet_acc_ = 0.0f;       // 块内湿声能量累加（临时）

  // ==== wet 哨兵（2026-11-08）====
  // 「预设正常但湿声恒零 + 干声有电平」连续这么多个块就 Reset 一次。
  // 200 块 × 192 帧 / 48k ≈ 0.8 秒：足够区分真正的状态坏死与正常的淡入淡出。
  static constexpr int kWetDeadResetBlocks = 200;
  int wet_dead_blocks_ = 0;      // 连续失效块计数
  int wet_sentinel_resets_ = 0;  // 哨兵累计触发次数（诊断/回归断言）
  bool wet_ever_alive_ = false;  // 本轮是否真的产出过湿声（防「静音 IR」误触发）
  float dry_acc_ = 0.0f;         // 块内干声能量累加（临时，与 wet_acc_ 同生命周期）
  float target_dry_ = 1.0f;
  float current_dry_ = 1.0f;
  // 干声查表基准（kDryGain 随预设切换的 30ms 斜坡形态，见 Prepare/Process）
  float current_dry_base_ = 1.0f;
  float target_dry_base_ = 1.0f;
  bool dry_base_live_ = false;  // false=尚未切换过（首次 SetParam 直跳）
  // 湿声低切（RBJ highpass Q=0.707）：系数每块重算，DF1 状态按声道独立
  float target_lowcut_hz_ = 0.0f;
  float current_lowcut_hz_ = 0.0f;
  float lc_b0_ = 1.0f, lc_b1_ = 0.0f, lc_b2_ = 0.0f;
  float lc_a1_ = 0.0f, lc_a2_ = 0.0f;
  float lc_x1_[2] = {0.0f, 0.0f}, lc_x2_[2] = {0.0f, 0.0f};
  float lc_y1_[2] = {0.0f, 0.0f}, lc_y2_[2] = {0.0f, 0.0f};
  FftPlan fft_;                       // n2_ 点（tail 批次用）
  // head 时域 IR：[preset][路径 4][H]，路径 idx = in*2+out（LL/LR/RL/RR）
  std::vector<float> head_ir_;
  // tail 分区频谱（每路径独立实数 FFT，ComputeBatch 分离乘直接复用）：
  // [preset][t][路径 4][N2]；对角 preset 只占 idx0/idx3，idx1/idx2 恒零
  std::vector<float> tail_spec_re_;
  std::vector<float> tail_spec_im_;
  // 每 preset 活跃路径组数（2=对角快路径 / 4=完整交叉）。P1 并发门（2026-10-27
  // 自检）：slot 5 运行中重装时实时线程在挂载预设上读此表，改 atomic——写序
  // 在 SetIrSlot2x2 开闸（t_eff release 写回）之前，acquire 读到新 t_eff 时
  // paths 必已可见；读端 relaxed（顺序由 t_eff 的 acquire 保证）。
  std::atomic<unsigned char> preset_paths_[kPresetCount] = {};
  // 每 preset 有效 tail 分区数（ceil((ir_len-h_)/p_)，≤0 = 纯 head）。
  // P1 并发门核心：SetIrSlot2x2 关闸写 0 → 写 head/spec → release 写回 t_eff
  // 开闸；ComputeBatchHalf acquire 读（配对见其注释）。干声 preset 不经
  // SetIrSlot2x2，依赖值初始化零 = 求和循环整体跳过。
  std::atomic<int> preset_t_[kPresetCount] = {};
  // 输入历史环形：[ch][cap_hist]（全域位置索引 RingPos）
  std::vector<float> x_hist_;
  // tail 产出环形：[ch][cap_out]，槽 n ≡ y_t[n-P]（消费端直接按输出位置读）
  std::vector<float> tail_out_;
  // 批次工作区
  std::vector<float> x_cache_re_;   // [T][N2] 历史输入批次频谱环形
  std::vector<float> x_cache_im_;
  std::vector<float> acc_re_;       // [N2] 频域累加器 / IFFT 输出
  std::vector<float> acc_im_;
  std::vector<float> b_re_;         // [N2] u 批次打包缓冲
  std::vector<float> b_im_;
  std::vector<float> overlap_l_;    // [P] 上一批 IFFT 后段（wrap 项，L）
  std::vector<float> overlap_r_;    // [P] 上一批 IFFT 后段（wrap 项，R）
  long long g_in_ = 0;              // 已消费输入帧（全域，含当前块）
  // 拆半批次状态：batch_half_ 1=前半已完成待补后半；batch_preset_/batch_t_eff_
  // 为前半锁定的批参数（后半沿用，防批间切预设/重填 IR 混加；spec 内容在
  // 加载瞬间本就是「未定义」口径）；batch_k_ 为前半批号
  int batch_half_ = 0;
  int batch_preset_ = 0;
  int batch_t_eff_ = 0;
  long long batch_k_ = -1;
  int next_batch_ = 0;              // 下一待计算 tail 批次号
};

}  // namespace sonicraft::dsp
