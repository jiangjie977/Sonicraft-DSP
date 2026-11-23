/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.privilege

import android.content.Context
import android.media.projection.MediaProjection
import android.os.IBinder
import android.os.Process
import com.jiangjie.Sonicraft.core.common.SonicraftLog

/**
 * 经特权后端（Stellar / Shizuku）免弹窗创建 MediaProjection。
 *
 * 路线：
 *  1. [serviceProvider] 以 shell/root 身份拿 IMediaProjectionManager binder；
 *  2. 反射系统 boot classpath 里的 IMediaProjectionManager$Stub.asInterface 取 Proxy
 *     —— 用系统自带类，方法序与系统服务天然一致，无需同步 aidl；
 *  3. createProjection(uid=本app, packageName=本app, SCREEN_CAPTURE, 0L) 创建归属
 *     本 app 的投影 token（不弹系统授权弹窗）；
 *  4. 反射 hide 构造器包装成 MediaProjection，交给现有 startCapture 链路
 *     （AudioPlaybackCaptureConfiguration / PROJECT_MEDIA appop 校验零改动）。
 *
 * 任一环节失败返回 null，调用方降级走系统一次性弹窗。
 */
object SilentProjectionFactory {

    private const val TAG = "SilentProjection"

    private const val SERVICE_NAME = "media_projection"
    private const val TYPE_SCREEN_CAPTURE = 1 // MediaProjectionManager.MEDIA_PROJECTION_TYPE_SCREEN_CAPTURE

    /**
     * @param serviceProvider 后端提供的「取系统服务 binder」能力（Stellar / Shizuku 各自实现）
     * @param onFailureDetail 失败详情回调（异常类型+消息+cause 链），供诊断文件留痕——
     *   本机 logcat 被 ROM 过滤读不到，异常只写 logcat 等于丢失（实测踩坑）。
     */
    fun create(
        context: Context,
        serviceProvider: (String) -> IBinder?,
        onFailureDetail: ((String) -> Unit)? = null,
    ): MediaProjection? {
        val managerBinder = runCatching { serviceProvider(SERVICE_NAME) }.getOrNull()
        if (managerBinder == null) {
            onFailureDetail?.invoke("特权后端未提供 $SERVICE_NAME binder")
            SonicraftLog.w(TAG, "特权链路不可用，免弹窗投影不可用")
            return null
        }
        // Android 15 hidden API 拦截：未豁免时 getDeclaredMethod 直接抛 NoSuchMethodException
        // （实测踩坑：免弹窗路径一直失败，昨天「无弹窗」实为 consent 复用被误判为成功）
        grantHiddenApiExemption()
        return runCatching {
            // IMediaProjectionManager$Stub.asInterface(binder)：按名字+参数个数找。
            // 注意：Android 15/16 的 AIDL 生成会把 asInterface 放到
            // **接口类** `IMediaProjectionManager` 上，`$Stub` 里可能只有 2 个方法且不含
            // 它 —— 只查 $Stub 会报「Stub 无 asInterface(1参)：共2方法」。故两个类都试。
            val candidates = listOf(
                "android.media.projection.IMediaProjectionManager",
                "android.media.projection.IMediaProjectionManager\$Stub",
            )
            var asInterface: java.lang.reflect.Method? = null
            for (cn in candidates) {
                val cls = runCatching { Class.forName(cn) }.getOrNull() ?: continue
                asInterface = cls.declaredMethods.firstOrNull {
                    it.name == "asInterface" &&
                        it.parameterCount == 1 &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers)
                }?.apply { isAccessible = true }
                if (asInterface != null) break
            }
            val asMethod = asInterface
                ?: error(
                    "找不到 asInterface(IBinder)。候选类方法： " +
                        candidates.joinToString("; ") { cn ->
                            val cls = runCatching { Class.forName(cn) }.getOrNull()
                            cn + "=" + (cls?.declaredMethods?.map { "${it.name}/${it.parameterCount}" }
                                ?.joinToString(",") ?: "null")
                        },
                )
            val manager = asMethod.invoke(null, managerBinder)
                ?: error("asInterface 返回 null")

            // createProjection(int uid, String packageName, int type, long stateCallback)
            val createProjection = (manager.javaClass.declaredMethods
                .firstOrNull { it.name == "createProjection" && it.parameterCount == 4 }
                ?: error("manager 无 createProjection(4参) 方法"))
                .apply { isAccessible = true }
            val projection = createProjection.invoke(
                manager,
                Process.myUid(),
                context.packageName,
                TYPE_SCREEN_CAPTURE,
                0L,
            ) ?: error("createProjection 返回 null")

            // hide 构造器 MediaProjection(...)，Android 各版本参数顺序可能不同，两种候选都试
            val projectionInterface = Class.forName("android.media.projection.IMediaProjection")
            val mediaProjectionClass = Class.forName("android.media.projection.MediaProjection")
            val ctor = runCatching {
                mediaProjectionClass.getDeclaredConstructor(projectionInterface, Context::class.java)
            }.recoverCatching {
                mediaProjectionClass.getDeclaredConstructor(Context::class.java, projectionInterface)
            }.getOrThrow().apply { isAccessible = true }

            ctor.newInstance(projection, context) as MediaProjection
        }.onFailure { e ->
            val detail = buildString {
                append("${e.javaClass.simpleName}: ${e.message}")
                var cause = e.cause
                while (cause != null) {
                    append(" <- ${cause.javaClass.simpleName}: ${cause.message}")
                    cause = cause.cause
                }
            }
            onFailureDetail?.invoke(detail)
            SonicraftLog.e(TAG, "免弹窗 MediaProjection 创建失败，降级系统弹窗", e)
        }.getOrNull()
    }

    /** VMRuntime.setHiddenApiExemptions("L") 解除全部隐藏 API 限制（debug 可用） */
    private fun grantHiddenApiExemption() {
        runCatching {
            val vmRuntime = Class.forName("android.os.VMRuntime")
            val runtime = vmRuntime.getDeclaredMethod("getRuntime").invoke(null)
            vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String::class.java)
                .invoke(runtime, "L")
        }
    }
}
