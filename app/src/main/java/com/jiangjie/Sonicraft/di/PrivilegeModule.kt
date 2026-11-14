/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.jiangjie.Sonicraft.di

import com.jiangjie.Sonicraft.privilege.PrivilegedClient
import com.jiangjie.Sonicraft.privilege.StellarPrivilegedClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrivilegeModule {

    @Binds
    @Singleton
    abstract fun bindPrivilegedClient(impl: StellarPrivilegedClient): PrivilegedClient
}
