/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import com.jiangjie.Sonicraft.core.engine.LoopbackPcmSource
import com.jiangjie.Sonicraft.privilege.ILoopbackProbe
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loopback 链路的**进程级**持有者。
 *
 * 背景：`ILoopbackProbe` 句柄与 `LoopbackPcmSource` 原先都是 `MainViewModel` 的字段。
 * ViewModel 随 Activity 销毁而清理（从桌面重新点图标、系统回收后台 Activity 都会发生），
 * 但**链其实还活着**（Binder 在 shell 服务里、引擎是进程级单例）。
 * 于是 `autoStartCaptureIfNeeded()` 看到 `probe == null` 就误判「链已断」→ 全量重建：
 * 电源键闪一次、声音断一次，用户感知为「每次点开 App 都要重新加载电源」。
 *
 * 该单例让「链是否活着」与 UI 生命周期解耦：
 *  - 建链成功 → [attach]
 *  - 显式停止 / 链路异常 → [detach]
 *  - Activity 重建后 → [isAlive] 为 true，直接复用，不重建
 */
@Singleton
class LoopbackChainHolder @Inject constructor() {

    @Volatile
    var probe: ILoopbackProbe? = null
        private set

    @Volatile
    var source: LoopbackPcmSource? = null
        private set

    /**
     * 是否正在建链。
     * 前台(ViewMode)建链与前台服务(headless)恢复**不能同时绑定**同一个 UserService：
     * 两次 bindUserService 令牌不同 → 后到者拿到 `code=-2 验证令牌不匹配`。
     */
    @Volatile
    var building: Boolean = false

    /** 建链成功后登记；两者都非空才算「链在手」 */
    fun attach(p: ILoopbackProbe, s: LoopbackPcmSource) {
        probe = p
        source = s
    }

    fun detach() {
        probe = null
        source = null
    }

    val isAlive: Boolean
        get() = probe != null && source != null
}
