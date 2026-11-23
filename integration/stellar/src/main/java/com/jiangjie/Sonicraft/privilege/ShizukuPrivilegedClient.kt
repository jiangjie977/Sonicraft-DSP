/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.os.IBinder
import com.jiangjie.Sonicraft.core.common.SonicraftLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 后端（2026-11-23）。
 *
 * 目标实现优先 Shevery（`com.hamondev.shevery`，Shizuku fork，自启链路更成熟），
 * 官方 Shizuku（`moe.shizuku.privileged.api`）作为备选。二者 API 协议一致：
 * Shevery 保留了 `moe.shizuku.privileged.api.shizuku` authority 与 `rikka.shizuku` 包名，
 * 因此官方 API 依赖可直接连通。
 *
 * 与 Stellar 的差异只在绑定 API：本类用 [Shizuku.bindUserService]，
 * 服务类仍是同一个 [LoopbackProbeUserService]（两端服务端都按 `(Context)` 构造器反射实例化）。
 */
@Singleton
class ShizukuPrivilegedClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : PrivilegedClient {

    private val _state = MutableStateFlow(PrivilegeState())
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    override val kind: BackendKind get() = BackendKind.SHIZUKU

    /** 每个 processSuffix 一个 ServiceConnection，unbind 时用 */
    private val connections = java.util.concurrent.ConcurrentHashMap<String, ServiceConnection>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }
        refresh()
    }

    override val uid: Int
        get() = _state.value.uid

    override fun isAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun requestPermission() {
        if (!isAlive()) return
        runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            SonicraftLog.i(TAG, "已向 Shizuku 发起授权请求")
        }.onFailure { SonicraftLog.w(TAG, "requestPermission 失败: ${it.message}") }
    }

    override fun refresh() {
        val running = isAlive()
        // Shevery 优先，官方 Shizuku 兜底（二者 authority 互斥，只能装一个）
        val managerPkg = when {
            context.isPackageInstalled(PKG_SHEVERY) -> PKG_SHEVERY
            context.isPackageInstalled(PKG_SHIZUKU) -> PKG_SHIZUKU
            else -> null
        }
        val granted = running && shizukuPermissionGranted()
        val uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        _state.value = PrivilegeState(
            managerInstalled = managerPkg != null,
            serviceRunning = running,
            permissionGranted = granted,
            uid = uid,
            stellarInstalled = false,
            backend = BackendKind.SHIZUKU,
            managerPackage = managerPkg,
        )
    }

    override fun grantMediaProjectionSilently(): Boolean {
        if (!isAlive()) return false
        val result = runCommand("cmd", "appops", "set", context.packageName, "PROJECT_MEDIA", "allow")
        val ok = result.exitCode == 0
        if (!ok) SonicraftLog.w(TAG, "appops 授权失败: ${result.stderr.trim()}")
        return ok
    }

    override fun createMediaProjectionSilently(onFailureDetail: ((String) -> Unit)?): MediaProjection? =
        SilentProjectionFactory.create(
            context = context,
            serviceProvider = { name -> getSystemService(name) },
            onFailureDetail = onFailureDetail,
        )

    /**
     * 取系统服务 binder，并用 [ShizukuBinderWrapper] 包装：transact 经 Shizuku server
     * （shell 身份）转发，而不是以 app uid 直连——否则 media_projection 等受保护服务会拒绝。
     */
    override fun getSystemService(name: String): IBinder? = runCatching {
        val raw = SystemServiceHelper.getSystemService(name) ?: return null
        ShizukuBinderWrapper(raw)
    }.getOrNull()

    override fun runCommand(vararg cmd: String): CommandResult {
        if (!isAlive()) return CommandResult(-1, "", "Shizuku 服务未连接")
        val process = try {
            newProcessCompat(cmd.map { it as String? }.toTypedArray())
        } catch (e: Throwable) {
            SonicraftLog.e(TAG, "Shizuku 特权命令创建失败", e)
            return CommandResult(-1, "", e.message ?: "unknown error")
        }
        return consumeProcess(process)
    }

    /**
     * Shizuku API 13.1.5 起 `newProcess` 被改为 private（API 14 计划移除），
     * 但服务端 AIDL 方法仍在。反射调用，避免升级 API 版本后才能用。
     */
    @Suppress("UNCHECKED_CAST")
    private fun newProcessCompat(cmd: Array<String?>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd as Array<String>, null, null) as Process
    }

    override fun bindLoopbackProbe(
        processSuffix: String,
        version: Long,
        callback: LoopbackProbeCallback,
    ) {
        if (!isAlive()) {
            callback.onFailed(-1, "Shizuku 服务未连接")
            return
        }
        if (!shizukuPermissionGranted()) {
            callback.onFailed(-1, "Shizuku 未授权：请在授权弹窗/授权页允许后重试")
            return
        }
        // 2026-11-23：重装/重启后 Shevery 可能残留旧探针进程（server 已丢 binder 但进程还在），
        // 新旧两份 AudioPolicy 并存会抢路由 → 副本无数据 / underrun 暴涨。
        // bind 前清一次（进程名固定为 <pkg>:<suffix>）。
        runCatching {
            runCommand("pkill", "-f", "${context.packageName}:$processSuffix")
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                callback.onConnected(ILoopbackProbe.Stub.asInterface(service))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                callback.onDisconnected()
            }
        }
        runCatching {
            Shizuku.bindUserService(buildArgs(processSuffix, version), conn)
            connections[processSuffix] = conn
        }.onFailure {
            SonicraftLog.e(TAG, "Shizuku binding loopback probe failed", it)
            callback.onFailed(-1, it.message ?: "bind failed")
        }
    }

    override fun unbindLoopbackProbe(processSuffix: String) {
        val conn = connections.remove(processSuffix) ?: return
        runCatching {
            Shizuku.unbindUserService(buildArgs(processSuffix, PrivilegedClient.PROBE_VERSION), conn, true)
        }
    }

    private fun buildArgs(processSuffix: String, version: Long): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, LoopbackProbeUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix(processSuffix)
            .version(version.toInt())
            .debuggable(false)

    private fun consumeProcess(process: Process): CommandResult {
        val stdoutF = streamPool.submit(Callable { process.inputStream.bufferedReader().readText() })
        val stderrF = streamPool.submit(Callable { process.errorStream.bufferedReader().readText() })
        val exitF = streamPool.submit(Callable { process.waitFor() })
        return try {
            val exitCode = exitF.get(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            CommandResult(exitCode, stdoutF.get(2, TimeUnit.SECONDS), stderrF.get(2, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            runCatching { process.destroy() }
            CommandResult(-2, "", "超时（>${CMD_TIMEOUT_MS}ms，已强制销毁）")
        } catch (e: Throwable) {
            runCatching { process.destroy() }
            SonicraftLog.e(TAG, "Shizuku 特权命令执行失败", e)
            CommandResult(-1, "", e.message ?: "unknown error")
        } finally {
            runCatching { process.destroy() }
        }
    }

    private companion object {
        const val TAG = "ShizukuPriv"

        /** Shevery（Shizuku fork） */
        const val PKG_SHEVERY = "com.hamondev.shevery"

        /** 官方 Shizuku */
        const val PKG_SHIZUKU = "moe.shizuku.privileged.api"

        const val REQUEST_CODE = 1
        const val CMD_TIMEOUT_MS = 8_000L

        val streamPool = Executors.newCachedThreadPool { r ->
            Thread(r, "shizuku-cmd-io").apply { isDaemon = true }
        }
    }
}
