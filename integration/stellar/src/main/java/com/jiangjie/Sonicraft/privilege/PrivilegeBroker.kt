/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege

import android.content.Context
import android.media.projection.MediaProjection
import android.os.IBinder
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import rikka.shizuku.Shizuku
import roro.stellar.Stellar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双后端仲裁器。
 *
 * 选择策略（用户指定）：
 *  1. **Stellar 优先**——现有 loopback 链路已 6/6 验证、`-2` 自愈等坑都踩过；
 *  2. Stellar 不可用时用 **Shizuku**（Shevery 优先，官方 Shizuku 同协议）；
 *  3. 两者都不可用 → [NoopPrivilegedClient]，经 [events] 发 [BackendEvent.allDead]
 *     让 UI 弹窗引导处理。
 *
 * 切换触发：
 *  - 两边的 binder received / dead 监听；
 *  - 调用方主动 [refresh]。
 *
 * 注意：切换只会改变「特权命令 / 免弹窗投影 / probe 绑定」走哪个后端，
 * **已经绑定的 loopback probe 不会自动迁移**——旧 probe 必须由调用方 stop + detach，
 * 再按新后端重新 bind（见 AudioChainManager 对 [events] 的订阅）。
 */
@Singleton
class PrivilegeBroker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stellar: StellarPrivilegedClient,
    private val shizuku: ShizukuPrivilegedClient,
) : PrivilegedClient {

    private val noop = NoopPrivilegedClient()

    private val _active = MutableStateFlow<PrivilegedClient>(noop)
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<PrivilegeState> = _active
        .flatMapLatest { it.state }
        .stateIn(scope, SharingStarted.Eagerly, PrivilegeState())

    override val kind: BackendKind get() = _active.value.kind
    override val uid: Int get() = _active.value.uid

    /** 当前生效的后端（排障/UI 用） */
    val activeBackend: BackendKind get() = _active.value.kind

    init {
        runCatching {
            Stellar.addBinderReceivedListenerSticky(
                Stellar.OnBinderReceivedListener { reevaluate("stellar binder received") },
            )
            Stellar.addBinderDeadListener(
                Stellar.OnBinderDeadListener { reevaluate("stellar binder dead") },
            )
        }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(
                Shizuku.OnBinderReceivedListener { reevaluate("shizuku binder received") },
            )
            Shizuku.addBinderDeadListener(
                Shizuku.OnBinderDeadListener { reevaluate("shizuku binder dead") },
            )
        }
        reevaluate("init")
    }

    /** 首轮探测只定后端、不发事件（避免 App 启动就弹「已切换」提示）。 */
    private var initialized = false

    /** 重新探测并切换后端；仅在 kind 变化时发事件。 */
    private fun reevaluate(reason: String) {
        // 不能只看 isAlive —— Stellar 活着但未授权时 bindUserService 会
        // code=-3；此时若 Shizuku 已授权，应让后者接管（授权优先级 > 先到先得）。
        // 两者都未授权时仍按 Stellar 优先（等用户授权）。
        val now: PrivilegedClient = when {
            stellar.isAlive() && stellar.state.value.permissionGranted -> stellar
            shizuku.isAlive() && shizuku.state.value.permissionGranted -> shizuku
            stellar.isAlive() -> stellar
            shizuku.isAlive() -> shizuku
            else -> noop
        }
        val old = _active.value
        if (now.kind == old.kind) {
            initialized = true
            return
        }
        _active.value = now
        SonicraftLog.i(TAG, "特权后端切换 ${old.kind} → ${now.kind}（$reason）")
        if (initialized) {
            _events.tryEmit(BackendEvent(old.kind, now.kind, reason))
        }
        initialized = true
    }

    override fun isAlive(): Boolean = _active.value.isAlive()

    override fun refresh() {
        runCatching { stellar.refresh() }
        runCatching { shizuku.refresh() }
        reevaluate("refresh")
    }

    override fun requestPermission() = _active.value.requestPermission()

    override fun grantMediaProjectionSilently(): Boolean =
        _active.value.grantMediaProjectionSilently()

    override fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)?): MediaProjection? =
        _active.value.createMediaProjectionSilently(onFailureDetail)

    override fun runCommand(vararg cmd: String): CommandResult =
        _active.value.runCommand(*cmd)

    override fun getSystemService(name: String): IBinder? =
        _active.value.getSystemService(name)

    override fun bindLoopbackProbe(
        processSuffix: String,
        version: Long,
        callback: LoopbackProbeCallback,
    ) = _active.value.bindLoopbackProbe(processSuffix, version, callback)

    override fun unbindLoopbackProbe(processSuffix: String) =
        _active.value.unbindLoopbackProbe(processSuffix)

    private companion object {
        const val TAG = "PrivilegeBroker"
    }
}
