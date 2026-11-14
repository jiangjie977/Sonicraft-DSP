/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SonicraftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashCapture.install(this)
    }
}
