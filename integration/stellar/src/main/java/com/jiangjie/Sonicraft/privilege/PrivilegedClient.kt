/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege

import android.content.Context
import android.media.projection.MediaProjection
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import roro.stellar.Stellar
import roro.stellar.StellarHelper
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** 特权框架状态快照 */
data class PrivilegeState(
    val managerInstalled: Boolean = false,
    val serviceRunning: Boolean = false,
    val permissionGranted: Boolean = false,
    /** 0 = root，2000 = adb/shell，-1 = 不可用 */
    val uid: Int = -1,
    /**
     * 只认 Stellar Manager（2026-11-08）。
     * [managerInstalled] 为兼容 Shizuku 会把原版 Shizuku 也算已安装，但本应用绑定的是
     * `IStellarService`，原版 Shizuku 无法授权。UI 依据本字段区分「引导下载」与「拉起授权」。
     */
    val stellarInstalled: Boolean = false,
)

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * 特权能力抽象。MVP 只保留内录所需的三个能力：
 *  1. 免弹窗授予 PROJECT_MEDIA（内录授权）
 *  2. 跟随服务自启（M4）
 *  3. 执行特权命令 / 读取系统属性（诊断）
 *
 * 后续可替换为 NoopPrivilegedClient（无特权降级）或 Shizuku 实现，接口不变。
 */
interface PrivilegedClient {
    val state: StateFlow<PrivilegeState>
    val uid: Int

    /**
     * 重新探测特权状态（2026-11-08）。
     * 必须由 onResume 调用：用户去 Stellar 里授权后切回本应用，binder 并没有重连，
     * 不主动刷新的话 UI 会一直停在「未授权」。
     */
    fun refresh()
    /** 向 Stellar 请求本 App 的 stellar 权限（用户在 Stellar 侧确认一次） */
    fun requestPermission()
    fun grantMediaProjectionSilently(): Boolean
    /** M2：经 Stellar 免弹窗创建归属于本 app 的 MediaProjection；失败返回 null（降级系统弹窗）。
     *  [onFailureDetail] 失败详情回调（本机 logcat 不可用，诊断信息经此落盘）。 */
    fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)? = null): MediaProjection?
    fun runCommand(vararg cmd: String): CommandResult
}

/** Stellar 实现（用户指定优先） */
@Singleton
class StellarPrivilegedClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : PrivilegedClient {

    private val _state = MutableStateFlow(PrivilegeState())
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    private val binderReceivedListener = Stellar.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Stellar.OnBinderDeadListener { refresh() }

    init {
        Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
        Stellar.addBinderDeadListener(binderDeadListener)
        refresh()
    }

    override val uid: Int
        get() = _state.value.uid

    override fun requestPermission() {
        if (Stellar.pingBinder()) {
            Stellar.requestPermission(requestCode = 1)
            SonicraftLog.i(TAG, "已向 Stellar 发起授权请求")
        }
    }

    override fun refresh() {
        val installed = StellarHelper.isManagerInstalled(context)
        val running = Stellar.pingBinder()
        val granted = running && runCatching { Stellar.checkSelfPermission() }.getOrDefault(false)
        val uid = if (running) runCatching { Stellar.uid }.getOrDefault(-1) else -1
        _state.value = PrivilegeState(
            managerInstalled = installed,
            serviceRunning = running,
            permissionGranted = granted,
            uid = uid,
            stellarInstalled = StellarHelper.isStellarInstalled(context),
        )
    }

    override fun grantMediaProjectionSilently(): Boolean {
        if (!Stellar.pingBinder()) return false
        // shell 身份执行 appops 授权 PROJECT_MEDIA，避免系统弹窗。
        // 后续可改为 StellarBinderWrapper 直连 IAppOpsService，省去进程开销。
        val result = runCommand("cmd", "appops", "set", context.packageName, "PROJECT_MEDIA", "allow")
        val ok = result.exitCode == 0
        if (!ok) {
            SonicraftLog.w(TAG, "appops 授权失败: ${result.stderr.trim()}")
        }
        return ok
    }

    override fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)?): MediaProjection? =
        SilentProjectionFactory.create(context, onFailureDetail)

    override fun runCommand(vararg cmd: String): CommandResult {
        if (!Stellar.pingBinder()) return CommandResult(-1, "", "Stellar 服务未连接")
        val process = try {
            Stellar.newProcess(
                cmd.map { it as String? }.toTypedArray(),
                null,
                null,
            )
        } catch (e: Throwable) {
            SonicraftLog.e(TAG, "特权命令创建失败", e)
            return CommandResult(-1, "", e.message ?: "unknown error")
        }
        // H3（2026-11-03 审计）：readText()/waitFor() 原本零超时——server「活着但挂起」
        // （binder busy/fd 耗尽）时单次调用即可永久冻结调用线程：接管轮询、采集断链
        // 保险丝、自愈探针全部停摆且无日志。读流池线程化 + waitFor 限时，超时 destroy
        // 强制关 fd（读流线程随之 EOF 解除），调用线程上限 8s 必返回。
        val stdoutF = streamPool.submit(Callable { process.inputStream.bufferedReader().readText() })
        val stderrF = streamPool.submit(Callable { process.errorStream.bufferedReader().readText() })
        val exitF = streamPool.submit(Callable { process.waitFor() })
        return try {
            val exitCode = exitF.get(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            CommandResult(exitCode, stdoutF.get(2, TimeUnit.SECONDS), stderrF.get(2, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            runCatching { process.destroy() } // 关 fd：三个池线程随之解除阻塞
            CommandResult(-2, "", "超时（>${CMD_TIMEOUT_MS}ms，已强制销毁）")
        } catch (e: Throwable) {
            runCatching { process.destroy() }
            SonicraftLog.e(TAG, "特权命令执行失败", e)
            CommandResult(-1, "", e.message ?: "unknown error")
        } finally {
            // 读流抛异常等路径也必须销毁 holder，否则 server 侧 fd 泄漏
            // （server 侧 reaper 兜底，此 destroy 为快速回收的双保险）
            runCatching { process.destroy() }
        }
    }

    private companion object {
        const val TAG = "Privileged"

        /** 单条特权命令超时上限：dumpsys audio 实测 <2s，8s 已极保守 */
        const val CMD_TIMEOUT_MS = 8_000L

        /** 流读取/waitFor 守护线程池（超时 destroy 关 fd 后自然排空，线程随之回收） */
        val streamPool = Executors.newCachedThreadPool { r ->
            Thread(r, "stellar-cmd-io").apply { isDaemon = true }
        }
    }
}

/** 无特权降级实现：所有特权能力均不可用，调用方走系统弹窗/普通路径 */
class NoopPrivilegedClient : PrivilegedClient {
    private val _state = MutableStateFlow(PrivilegeState())
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()
    override val uid: Int get() = -1
    override fun refresh() = Unit
    override fun requestPermission() = Unit
    override fun grantMediaProjectionSilently(): Boolean = false
    override fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)?): MediaProjection? = null
    override fun runCommand(vararg cmd: String): CommandResult =
        CommandResult(-1, "", "无特权框架可用")
}
