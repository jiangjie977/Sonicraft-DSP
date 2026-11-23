/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.IBinder
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import roro.stellar.Stellar
import roro.stellar.StellarHelper
import roro.stellar.userservice.ServiceMode
import roro.stellar.userservice.StellarUserService
import roro.stellar.userservice.UserServiceArgs
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 特权后端类型（2026-11-23 双后端并存）。
 *
 * 选择策略：Stellar 优先（现有链路已验证），Shizuku（含 Shevery fork）兜底；
 * 两者都不可用时 [NONE]，由 UI 弹窗引导处理。
 */
enum class BackendKind { STELLAR, SHIZUKU, NONE }

/** 特权框架状态快照 */
data class PrivilegeState(
    val managerInstalled: Boolean = false,
    val serviceRunning: Boolean = false,
    val permissionGranted: Boolean = false,
    /** 0 = root，2000 = adb/shell，-1 = 不可用 */
    val uid: Int = -1,
    /**
     * 只认 Stellar Manager。
     * [managerInstalled] 为兼容 Shizuku 会把原版 Shizuku 也算已安装，但本应用绑定的是
     * `IStellarService`，原版 Shizuku 无法授权。UI 依据本字段区分「引导下载」与「拉起授权」。
     */
    val stellarInstalled: Boolean = false,
    /** 当前生效的后端（UI 展示用） */
    val backend: BackendKind = BackendKind.NONE,
    /** 实际 manager 包名（Shevery / 官方 Shizuku / Stellar），UI 按它显示具体名称 */
    val managerPackage: String? = null,
)

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** 后端切换事件：当前后端死亡/恢复时由 [PrivilegedClient.events] 发出。 */
data class BackendEvent(
    val from: BackendKind,
    val to: BackendKind,
    val reason: String,
) {
    val allDead: Boolean get() = to == BackendKind.NONE
}

/**
 * Loopback probe 绑定回调。
 *
 * 统一 Stellar / Shizuku 两套 UserService 机制：两者服务端都是「反射构造带
 * (Context) 构造器的 IBinder」，因此同一份 [LoopbackProbeUserService] 可被两边加载，
 * 差异只在绑定 API。
 */
interface LoopbackProbeCallback {
    fun onConnected(probe: ILoopbackProbe)
    fun onDisconnected()

    /** errorCode：-1 = 服务未连接/超时；-2 = 验证令牌不匹配（Stellar 特有）。 */
    fun onFailed(errorCode: Int, message: String)
}

/**
 * 特权能力抽象（2026-11-23 双后端）。
 *
 * 能力面：
 *  1. 免弹窗授予 PROJECT_MEDIA（内录授权）
 *  2. 跟随服务自启
 *  3. 执行特权命令 / 读取系统属性（诊断）
 *  4. 绑定 shell 侧 loopback 探针（AudioPolicy + REMOTE_SUBMIX）
 */
interface PrivilegedClient {
    val state: StateFlow<PrivilegeState>
    val uid: Int
    val kind: BackendKind

    /** 后端切换事件流（Stellar/Shizuku 死亡、恢复、全灭）。默认空流。 */
    val events: SharedFlow<BackendEvent> get() = EMPTY_EVENTS

    /** 快速探测：当前后端的 binder 是否可 ping。 */
    fun isAlive(): Boolean

    /**
     * 重新探测特权状态。
     * 必须由 onResume 调用：用户去授权后切回本应用，binder 并没有重连，
     * 不主动刷新的话 UI 会一直停在「未授权」。
     */
    fun refresh()

    /** 向当前后端请求授权（Stellar 侧确认 / Shizuku 系统对话框）。 */
    fun requestPermission()
    fun grantMediaProjectionSilently(): Boolean

    /** 免弹窗创建归属于本 app 的 MediaProjection；失败返回 null（降级系统弹窗）。 */
    fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)? = null): MediaProjection?
    fun runCommand(vararg cmd: String): CommandResult

    /** 以特权身份取系统服务 binder（供免弹窗投影等反射路径使用）。 */
    fun getSystemService(name: String): IBinder?

    /**
     * 绑定 shell 侧 loopback 探针。
     *
     * @param processSuffix UserService 进程名后缀（默认 loopback_probe）
     * @param version 服务版本号：服务端代码变化必须递增，否则复用旧进程（-2 令牌不匹配）
     */
    fun bindLoopbackProbe(
        processSuffix: String = PROBE_SUFFIX,
        version: Long = PROBE_VERSION,
        callback: LoopbackProbeCallback,
    )

    fun unbindLoopbackProbe(processSuffix: String = PROBE_SUFFIX)

    companion object {
        const val PROBE_SUFFIX = "loopback_probe"

        /** 探针服务版本：服务端代码变化必须递增，否则 Stellar 复用旧进程（-2）。 */
        const val PROBE_VERSION = 52L

        /** 无后端（Noop）的默认空事件流 */
        private val EMPTY_EVENTS: SharedFlow<BackendEvent> =
            MutableSharedFlow<BackendEvent>(extraBufferCapacity = 1).asSharedFlow()
    }
}

/** Stellar 实现（用户指定优先） */
@Singleton
class StellarPrivilegedClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : PrivilegedClient {

    private val _state = MutableStateFlow(PrivilegeState())
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    /** 自动授权请求限频时间戳（60s 内只请求一次） */
    @Volatile
    private var lastAutoRequestAt = 0L

    override val kind: BackendKind get() = BackendKind.STELLAR

    private val binderReceivedListener = Stellar.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Stellar.OnBinderDeadListener { refresh() }

    init {
        runCatching {
            Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
            Stellar.addBinderDeadListener(binderDeadListener)
        }
        refresh()
    }

    override val uid: Int
        get() = _state.value.uid

    override fun isAlive(): Boolean = runCatching { Stellar.pingBinder() }.getOrDefault(false)

    override fun requestPermission() {
        if (isAlive()) {
            Stellar.requestPermission(requestCode = 1)
            SonicraftLog.i(TAG, "已向 Stellar 发起授权请求")
        }
    }

    override fun refresh() {
        val installed = StellarHelper.isManagerInstalled(context)
        val running = isAlive()
        val granted = running && runCatching { Stellar.checkSelfPermission() }.getOrDefault(false)
        val uid = if (running) runCatching { Stellar.uid }.getOrDefault(-1) else -1
        _state.value = PrivilegeState(
            managerInstalled = installed,
            serviceRunning = running,
            permissionGranted = granted,
            uid = uid,
            stellarInstalled = StellarHelper.isStellarInstalled(context),
            backend = BackendKind.STELLAR,
            managerPackage = STELLAR_MANAGER_PKG,
        )
        // 2026-11-23：Stellar 在跑但 `stellar` 未授权 → 自动发起一次原生授权请求。
        // 背景：Manager 可能把 DSP 归为「Shizuku 兼容应用」，授权页只写 shizuku 权限，
        // stellar 保持 ASK；此时 bindUserService 报 code=-3，用户从 UI 也点不到。
        // 限频 60s，避免 refresh（onResume/binder 事件）反复弹确认页。
        if (running && !granted) {
            val now = System.currentTimeMillis()
            if (now - lastAutoRequestAt > AUTO_REQUEST_INTERVAL_MS) {
                lastAutoRequestAt = now
                runCatching { Stellar.requestPermission(requestCode = AUTO_REQUEST_CODE) }
                SonicraftLog.i(TAG, "stellar 未授权 → 自动发起授权请求")
            }
        }
    }

    override fun grantMediaProjectionSilently(): Boolean {
        if (!isAlive()) return false
        // shell 身份执行 appops 授权 PROJECT_MEDIA，避免系统弹窗。
        val result = runCommand("cmd", "appops", "set", context.packageName, "PROJECT_MEDIA", "allow")
        val ok = result.exitCode == 0
        if (!ok) {
            SonicraftLog.w(TAG, "appops 授权失败: ${result.stderr.trim()}")
        }
        return ok
    }

    override fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)?): MediaProjection? =
        SilentProjectionFactory.create(
            context = context,
            serviceProvider = { name -> getSystemService(name) },
            onFailureDetail = onFailureDetail,
        )

    override fun getSystemService(name: String): IBinder? =
        runCatching { Stellar.getSystemService(name) }.getOrNull()

    override fun runCommand(vararg cmd: String): CommandResult {
        if (!isAlive()) return CommandResult(-1, "", "Stellar 服务未连接")
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
        return consumeProcess(process)
    }

    override fun bindLoopbackProbe(
        processSuffix: String,
        version: Long,
        callback: LoopbackProbeCallback,
    ) {
        if (!isAlive()) {
            callback.onFailed(-1, "Stellar 服务未连接")
            return
        }
        val args = UserServiceArgs.Builder(LoopbackProbeUserService::class.java)
            .processNameSuffix(processSuffix)
            .versionCode(version)
            .serviceMode(ServiceMode.DAEMON)
            .build()
        runCatching {
            StellarUserService.bindUserService(
                args,
                object : StellarUserService.ServiceCallback {
                    override fun onServiceConnected(service: IBinder) {
                        callback.onConnected(ILoopbackProbe.Stub.asInterface(service))
                    }

                    override fun onServiceDisconnected() {
                        callback.onDisconnected()
                    }

                    override fun onServiceStartFailed(errorCode: Int, message: String) {
                        callback.onFailed(errorCode, message)
                    }
                },
            )
        }.onFailure {
            SonicraftLog.e(TAG, "binding loopback probe failed", it)
            callback.onFailed(-1, it.message ?: "bind failed")
        }
    }

    override fun unbindLoopbackProbe(processSuffix: String) {
        runCatching {
            val args = UserServiceArgs.Builder(LoopbackProbeUserService::class.java)
                .processNameSuffix(processSuffix)
                .versionCode(PrivilegedClient.PROBE_VERSION)
                .serviceMode(ServiceMode.DAEMON)
                .build()
            StellarUserService.unbindUserService(args)
        }
    }

    /** H3（2026-11-03 审计）：readText()/waitFor() 原本零超时——server「活着但挂起」时永久冻结。 */
    private fun consumeProcess(process: Process): CommandResult {
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
            runCatching { process.destroy() }
        }
    }

    private companion object {
        const val TAG = "Privileged"

        /** Stellar Manager 包名（UI 显示用） */
        const val STELLAR_MANAGER_PKG = "roro.stellar.manager"

        /** 自动授权请求的 requestCode（与手动请求的 1 共用即可） */
        const val AUTO_REQUEST_CODE = 1

        /** 自动授权请求限频：60s 内最多一次 */
        const val AUTO_REQUEST_INTERVAL_MS = 60_000L

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
    override val kind: BackendKind get() = BackendKind.NONE
    override fun isAlive(): Boolean = false
    override fun refresh() = Unit
    override fun requestPermission() = Unit
    override fun grantMediaProjectionSilently(): Boolean = false
    override fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)?): MediaProjection? = null
    override fun runCommand(vararg cmd: String): CommandResult =
        CommandResult(-1, "", "无特权框架可用")

    override fun getSystemService(name: String): IBinder? = null

    override fun bindLoopbackProbe(
        processSuffix: String,
        version: Long,
        callback: LoopbackProbeCallback,
    ) {
        callback.onFailed(-1, "无特权框架可用")
    }

    override fun unbindLoopbackProbe(processSuffix: String) = Unit
}

/** 包可见性工具（Shizuku 后端判断 Manager 是否安装） */
internal fun Context.isPackageInstalled(pkg: String): Boolean = runCatching {
    packageManager.getPackageInfo(pkg, 0)
    true
}.getOrDefault(false)

/** Shizuku 权限检查结果（解耦 PackageManager 常量，便于复用） */
internal fun shizukuPermissionGranted(): Boolean = runCatching {
    rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)
