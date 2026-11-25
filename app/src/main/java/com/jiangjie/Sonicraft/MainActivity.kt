/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jiangjie.Sonicraft.ui.MainScreen
import com.jiangjie.Sonicraft.ui.PrivacyConsentScreen
import com.jiangjie.Sonicraft.ui.theme.SonicraftTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonicraftTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 合规门控：未同意隐私政策前**不进入 MainScreen**。
                    // MainScreen 一旦组合就会创建 MainViewModel → autoStartCaptureIfNeeded()
                    // → 检查/申请录音权限并建链。挡在这一层才能保证「同意前零动作」。
                    // 用 remember（**非** saveable）：用户点「撤回同意」后调用 Activity.recreate()，
                    // 重建时重新读 prefs 得到 false，才能回到隐私页。saveable 会把 true 恢复回来。
                    var accepted by remember { mutableStateOf(hasAcceptedPrivacy()) }
                    if (accepted) {
                        MainScreen()
                    } else {
                        PrivacyConsentScreen(
                            onAgree = {
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean(KEY_PRIVACY_ACCEPTED, true)
                                    .putInt(KEY_PRIVACY_VERSION, CURRENT_PRIVACY_VERSION)
                                    .apply()
                                accepted = true
                            },
                            // 不同意直接退出：不留后台进程、不残留任何状态
                            onDecline = { finishAffinity() },
                        )
                    }
                }
            }
        }
    }

    /**
     * 是否已同意**当前版本**的隐私条款。
     *
     * 为什么不能只看布尔值：《个保法》第 14 条要求条款有实质变更时**重新征得同意**。
     * 只存 `privacy_accepted=true` 的话，条款改了老用户也不会被重新询问。
     * 这里比较版本号，`CURRENT_PRIVACY_VERSION` 递增时老用户会自动重新看到同意页。
     *
     * 兼容迁移：历史版本只存了布尔值（无版本号）→ 视为已同意当前版本，避免升级时打扰。
     */
    private fun hasAcceptedPrivacy(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_PRIVACY_VERSION, 0)
        if (version >= CURRENT_PRIVACY_VERSION) return true
        if (version == 0 && prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)) {
            prefs.edit().putInt(KEY_PRIVACY_VERSION, CURRENT_PRIVACY_VERSION).apply()
            return true
        }
        return false
    }

    private companion object {
        /** 与 MainViewModel.PREFS_NAME 保持一致 */
        const val PREFS_NAME = "sonicraft_settings"
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        const val KEY_PRIVACY_VERSION = "privacy_accepted_version"

        /**
         * 隐私政策版本号。**条款有实质变更时 +1**（新增权限、新增数据处理方式等），
         * 老用户会被重新弹一次同意页。
         */
        const val CURRENT_PRIVACY_VERSION = 1
    }
}
