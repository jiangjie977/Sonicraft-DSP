package com.jiangjie.Sonicraft.core

import android.os.Build

/**
 * 设备/系统真实信息（页头副标题与音效库设备卡文案此前写死
 * 「星云 AIOS / Android 15」，AVD/其他设备上一眼假——改动态读取）。
 *
 * Android 对厂商 ROM 营销名（星云 AIOS/MyOS/HyperOS…）无标准读取 API。
 * 策略：检测到 Nubia 设备显示「星云 AIOS」（NX733J 真机实际系统名，用户指定），
 * 其余设备以 Build.MANUFACTURER 首字母大写近似系统归属（AVD→Google、小米→Xiaomi），
 * 版本号用 Build.VERSION.RELEASE 真实值。
 */
object SysInfo {
    /** 系统名：Nubia 真机→星云 AIOS，其余→厂商名首字母大写（Google / Xiaomi…） */
    val romName: String =
        if (Build.MANUFACTURER.equals("nubia", ignoreCase = true)) "星云 AIOS"
        else Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    /** 系统版本号，如 15 / 16 */
    val androidVersion: String =
        Build.VERSION.RELEASE
}
