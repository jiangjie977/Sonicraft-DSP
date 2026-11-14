package roro.stellar

import android.content.Context
import roro.stellar.Stellar.pingBinder
import roro.stellar.Stellar.sELinuxContext
import roro.stellar.Stellar.uid
import roro.stellar.Stellar.version

object StellarHelper {
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val STELLAR_MANAGER_PACKAGE_NAME = "roro.stellar.manager"

    fun isManagerInstalled(context: Context): Boolean {
        try {
            context.packageManager.getPackageInfo(STELLAR_MANAGER_PACKAGE_NAME, 0)
            return true
        } catch (_: Exception) {
            try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }

    /**
     * 只认 Stellar Manager。
     *
     * 与 [isManagerInstalled] 的区别：后者为兼容 Shizuku 而把原版 Shizuku 也算作"已安装"，
     * 但 Sonicraft 绑定的是 `com.stellar.server.IStellarService`——**原版 Shizuku 没有这个
     * 接口**，装它能装不能授权。UI 用它决定"引导下载 Stellar"还是"拉起客户端授权"，
     * 混用会导致用户被引导去 Shizuku 里反复找不到授权入口。
     */
    fun isStellarInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(STELLAR_MANAGER_PACKAGE_NAME, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun openManager(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(STELLAR_MANAGER_PACKAGE_NAME)
                ?: context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
            intent?.let {
                context.startActivity(it)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    val serviceInfo: ServiceInfo?
        get() = if (!pingBinder()) null else try {
            ServiceInfo(uid, version, sELinuxContext)
        } catch (_: Exception) {
            null
        }

    class ServiceInfo(
        val uid: Int,
        val version: Int,
        val seLinuxContext: String?
    ) {
        val isRoot: Boolean
            get() = uid == 0

        val isAdb: Boolean
            get() = uid == 2000

        override fun toString(): String =
            "ServiceInfo{uid=$uid, version=$version, seLinuxContext='$seLinuxContext'}"
    }
}
