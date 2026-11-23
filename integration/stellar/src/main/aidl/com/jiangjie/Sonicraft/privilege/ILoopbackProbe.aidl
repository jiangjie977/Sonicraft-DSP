/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege;

import com.jiangjie.Sonicraft.privilege.ILoopbackCallback;

/**
 * Loopback 探针（2026-11-04，P0 可行性验证）：跑在 Stellar UserService（shell uid=2000）。
 *
 * 回环音效处理接口（设计说明见内部文档）：
 *   Binder.clearCallingIdentity + VMRuntime.setHiddenApiExemptions(["L"])
 *   → AudioMixingRule.Builder.addMixRule(USAGE_MEDIA)
 *   → AudioMix(ROUTE_FLAG_LOOP_BACK)
 *   → AudioPolicy → AudioManager.registerAudioPolicy
 *   → AudioAttributes.setInternalCapturePreset(8=REMOTE_SUBMIX) + addTag(registration)
 *   → AudioRecord 读流
 *
 * 与旧 ACPConfig 路线的区别：走 AudioService 混音层，不受 per-stream capture policy
 * （酷狗 policy=2 + FLAG_NO_MEDIA_PROJECTION）约束；LOOP_BACK 是复制，原声照常播放。
 */
interface ILoopbackProbe {
    /** 服务进程 uid（预期 2000 = shell） */
    int getServiceUid();

    /**
     * 启动：注册 LOOP_BACK mix + 建 AudioRecord，返回状态描述。
     * @param sampleRate 采样率
     * @param excludeUid 要从 mix 中排除的 uid（必须传调用方 app 的 uid）——
     *   否则 app 自己的 AAudio 输出（USAGE_MEDIA）也会被 LOOP_BACK 进 mix，
     *   而 record 一旦建失败就形成「吃流无消费者」黑洞 → 音频线程阻塞卡死
     *   （2026-11-04 实锤事故）。系统 mix 与万象都带 RULE_EXCLUDE_UID。
     */
    String start(int sampleRate, int excludeUid);

    /**
     * P1：启动 PCM 流式回传（与 [start] 互斥，内部走同一套 policy/record 建立流程）。
     * @param sampleRate 采样率
     * @param excludeUid 要从 mix 排除的 uid（调用方自身）
     * @param cb oneway 回调，采集线程每 ~20ms 交一批 PCM16 交织数据
     */
    String startStream(int sampleRate, int excludeUid, ILoopbackCallback cb);

    /**
     * 按 uid 分 mix 的电平探针：注册一个只匹配 [targetUid] 的 LOOP_BACK mix 并建 record，
     * 读到的电平只属于该 app 这一路 —— 用于判定「DP 静音是否连坐它的副本」
     * （全局 mix 的电平会被其它同时在播的 app 撑住，掩盖单路失源）。
     * 与 [start]/[startStream] 互斥，调用前会先 stop。
     */
    String startUidProbe(int sampleRate, int targetUid);

    /**
     * 在**本服务进程**内对给定 session 挂 DynamicsProcessing(-200dB) 静音。
     *
     * 为什么必须由服务持有：DynamicsProcessing 是 AudioFlinger 效果链上的对象，
     * 由创建它的进程持有；app 进程被 force-stop / 崩溃时，app 创建的 effect
     * **可能残留**（dump 可见 `0 Clients` 孤儿），继续压着目标 app → 用户只能重启。
     * 本服务是 DAEMON 常驻进程，配合 [attachClient] 的死亡回调，可在 app 死亡瞬间
     * 释放全部静音，从根本上消除孤儿效果。
     *
     * 语义：**同步到 sids** —— 列表内新 sid 挂载，列表外旧 sid 释放。
     */
    String muteSessions(in int[] sids);

    /**
     * 16 kHz 特权「静音器」（双路之一，2026-11-05）。
     *
     * 背景：只有开启 `allowPrivilegedPlaybackCapture(true)` 的 mix 才会把
     * BY_SYSTEM/0x400 的流重路由进 submix（=原声消失），而框架限制该标志的
     * **采样率 ≤16kHz**。所以这条路不用于取音质，只负责"把原声搬走"；
     * 48k 的音频仍由普通 LOOP_BACK 那一路复制给 DSP。
     *
     * 本方法只注册静音器并起一个"读空"线程（必须持续读，否则 mix output 会堵塞）。
     */
    String startSilencer16k(int excludeUid);

    /** 释放 16k 静音器（policy + record） */
    void stopSilencer();

    /**
     * 触发 APM 重算路由（2026-11-05 REROUTE 实验）。
     *
     * 背景：我方注册 `RENDER|LOOP_BACK` 后，**已存在**的 AudioTrack 不会被重路由
     * （酷狗 deviceIds 仍是主输出，mix output 恒 Standby）；只有新建的 track 才可能进 mix。
     * AOSP 里 policy 注册本应触发一次 invalidate，但 OEM 似乎没做。这里手动制造一次
     * 「路由策略变更」：切到 MODE_IN_COMMUNICATION 再切回 MODE_NORMAL，
     * 观察能否让已存在的 player 重新走一遍 getOutputForAttr。
     *
     * @return 操作结果描述
     */
    String nudgeRouting();

    /**
     * 建链后校验「原声是否已被重路由进我方 submix」，未重路由则强制重建 track。
     *
     * 背景（2026-11-05 实锤）：重路由只在 player 的 AudioTrack **晚于**我方 Remote
     * Submix 设备就绪时创建才生效；已存在的 track 不会迁移。表现为同版本代码
     * 有时 `deviceIds` 指向我方 submix（单声），有时仍指主输出（双声/断供）。
     *
     * 做法：比对活跃 MEDIA player 的 deviceIds 与我方 submix Port ID；
     * 不匹配则派发 pause→play（触发 AudioTrack 重建）后再校验一次。
     *
     * @return "ok ..." / "fail ..."（失败时调用方应退化为不静音，宁可双声不可哑）
     */
    String ensureRerouted(int excludeUid);

    /** 释放本服务持有的全部静音效果（幂等） */
    String unmuteAll();

    /**
     * 绑定客户端 binder。服务对其 linkToDeath：客户端进程一死立即
     * `unmuteAll()` + [stop]（撤销 policy / 释放 record），不留任何残留。
     */
    void attachClient(IBinder client);

    /** 最近窗口 RMS 电平（0~1）；> 0 表示确实抓到了流 */
    float getLevel();

    /** 完整步骤追踪（失败定位用，比单条错误信息有用） */
    String getTrail();

    /** 停止并释放 */
    void stop();
}
