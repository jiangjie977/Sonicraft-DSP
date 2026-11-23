package roro.stellar.userservice

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.stellar.server.IUserServiceCallback
import roro.stellar.Stellar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "StellarUserService"

object StellarUserService {

    interface ServiceCallback {
        fun onServiceConnected(service: IBinder)
        fun onServiceDisconnected()
        fun onServiceStartFailed(errorCode: Int, message: String) {}
    }

    private val activeConnections = ConcurrentHashMap<String, ConnectionRecord>()

    private class ConnectionRecord(
        val args: UserServiceArgs,
        val callback: ServiceCallback,
        val handler: Handler?,
        var token: String? = null,
        var binder: IBinder? = null,
        var verificationToken: String = UUID.randomUUID().toString()
    )

    @JvmStatic
    @JvmOverloads
    fun bindUserService(
        args: UserServiceArgs,
        callback: ServiceCallback,
        handler: Handler? = Handler(Looper.getMainLooper())
    ) {
        Log.i(TAG, "class=${args.className}, suffix=${args.processNameSuffix}")

        val service = Stellar.getService()
        if (service == null) {
            Log.e(TAG, "")
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "")
            }
            return
        }
        Log.d(TAG, "")

        val packageName = Stellar.getPackageName()
        if (packageName == null) {
            Log.e(TAG, "")
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, "")
            }
            return
        }
        Log.d(TAG, "packageName=$packageName")

        val key = getConnectionKey(args)

        val existing = activeConnections[key]
        if (existing?.binder?.pingBinder() == true) {
            dispatchCallback(handler) {
                callback.onServiceConnected(existing.binder!!)
            }
            return
        }

        val record = ConnectionRecord(args, callback, handler)
        activeConnections[key] = record

        val aidlCallback = createAidlCallback(record, key, handler)

        try {
            Log.d(TAG, "")
            val bundle = args.toBundle(packageName)
            bundle.putString(UserServiceArgs.ARG_VERIFICATION_TOKEN, record.verificationToken)
            val token = service.startUserService(bundle, aidlCallback)
            Log.i(TAG, "token=$token")
            record.token = token
        } catch (e: SecurityException) {
            // 修「重装后无限崩溃」：Binder 权限拒绝抛的是 SecurityException
            // （RuntimeException），不是 RemoteException，原来直接逃逸到主线程 →
            // autoStartCaptureIfNeeded 崩溃 → Activity 重启 → 再探测 → 崩溃循环（每 2~3s 一个
            // crash_java 文件）。典型场景：Stellar 服务在跑但本应用未获授权。
            // 用独立错误码 -3，便于上层给出「去 Stellar 授权」的提示而非笼统失败。
            Log.e(TAG, "startUserService 权限被拒（未获 stellar 授权）", e)
            activeConnections.remove(key)
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-3, "未获 stellar 授权，请在 Stellar 中授权本应用")
            }
        } catch (e: Exception) {
            // RemoteException 及其它运行时异常统一兜底：绝不允许从 Binder 绑定路径抛到主线程
            Log.e(TAG, "startUserService 失败", e)
            activeConnections.remove(key)
            dispatchCallback(handler) {
                callback.onServiceStartFailed(-1, ": ${e.message}")
            }
        }
    }

    @JvmStatic
    fun unbindUserService(args: UserServiceArgs) {
        val key = getConnectionKey(args)
        Log.i(TAG, "key=$key")

        val record = activeConnections[key]
        if (record == null) {
            Log.w(TAG, "key=$key")
            return
        }

        val token = record.token
        if (token == null) {
            Log.w(TAG, "")
            activeConnections.remove(key)
            dispatchCallback(record.handler) {
                record.callback.onServiceDisconnected()
            }
            return
        }

        val service = Stellar.getService()
        if (service == null) {
            Log.w(TAG, "")
            activeConnections.remove(key)
            dispatchCallback(record.handler) {
                record.callback.onServiceDisconnected()
            }
            return
        }

        activeConnections.remove(key)

        try {
            Log.i(TAG, "token=$token")
            service.stopUserService(token)
        } catch (e: RemoteException) {
            Log.e(TAG, "", e)
        }

        dispatchCallback(record.handler) {
            record.callback.onServiceDisconnected()
        }
    }

    @JvmStatic
    fun peekUserService(args: UserServiceArgs): IBinder? {
        val key = getConnectionKey(args)
        val record = activeConnections[key] ?: return null
        return if (record.binder?.pingBinder() == true) record.binder else null
    }

    @JvmStatic
    fun getUserServiceCount(): Int {
        val service = Stellar.getService() ?: return 0
        return try {
            service.userServiceCount
        } catch (e: RemoteException) {
            0
        }
    }

    private fun getConnectionKey(args: UserServiceArgs): String =
        "${args.className}:${args.processNameSuffix}"

    private fun dispatchCallback(handler: Handler?, action: () -> Unit) {
        handler?.post(action) ?: action()
    }

    private fun createAidlCallback(
        record: ConnectionRecord,
        key: String,
        handler: Handler?
    ): IUserServiceCallback.Stub {
        return object : IUserServiceCallback.Stub() {
            override fun onServiceConnected(service: IBinder, returnedToken: String?) {
                Log.i(TAG, "服务已连接, binder=$service")
                if (returnedToken != record.verificationToken) {
                    Log.e(TAG, "验证令牌不匹配，拒绝连接")
                    activeConnections.remove(key)
                    dispatchCallback(handler) {
                        record.callback.onServiceStartFailed(-2, "验证令牌不匹配")
                    }
                    return
                }
                record.binder = service
                dispatchCallback(handler) {
                    record.callback.onServiceConnected(service)
                }
            }

            override fun onServiceDisconnected() {
                Log.i(TAG, "服务断开连接")
                record.binder = null
                record.verificationToken = UUID.randomUUID().toString()
                Log.d(TAG, "已更换验证令牌")
                activeConnections.remove(key)
                dispatchCallback(handler) {
                    record.callback.onServiceDisconnected()
                }
            }

            override fun onServiceStartFailed(errorCode: Int, message: String) {
                Log.e(TAG, "服务启动失败, code=$errorCode, msg=$message")
                activeConnections.remove(key)
                dispatchCallback(handler) {
                    record.callback.onServiceStartFailed(errorCode, message)
                }
            }
        }
    }
}
