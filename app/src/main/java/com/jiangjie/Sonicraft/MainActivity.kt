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

    private fun hasAcceptedPrivacy(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRIVACY_ACCEPTED, false)

    private companion object {
        /** 与 MainViewModel.PREFS_NAME 保持一致 */
        const val PREFS_NAME = "sonicraft_settings"
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
    }
}
