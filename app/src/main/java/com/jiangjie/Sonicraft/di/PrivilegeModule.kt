/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.di

import com.jiangjie.Sonicraft.privilege.PrivilegeBroker
import com.jiangjie.Sonicraft.privilege.PrivilegedClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrivilegeModule {

    /**
     * 2026-11-23：双后端仲裁（Stellar 优先，Shizuku 兜底）。
     * 调用方仍注入 [PrivilegedClient]，无需感知具体后端；切换经 `events` 通知。
     */
    @Binds
    @Singleton
    abstract fun bindPrivilegedClient(impl: PrivilegeBroker): PrivilegedClient
}
