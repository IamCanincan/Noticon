package com.iamcanincan.noticon.data

import android.content.Context
import android.content.SharedPreferences
import com.iamcanincan.noticon.model.ModuleOptions

/**
 * 模块配置的存储约定 —— 界面进程与 SystemUI 进程之间的唯一通道。
 *
 * 界面（App 进程）用普通的 [Context.getSharedPreferences] 写；
 * 模块（SystemUI 进程）用 LibXposed API 102 的 `getRemotePreferences` 读。
 * **两边必须用同一个文件名**，否则模块读到的永远是默认值，界面改了也不生效。
 *
 * 界面只暴露「模式」这一个选择，其余开关由模式推导 —— 因为替换策略和
 * 是否保留原色必须一致：彩色图标要保色，单色剪影要交给系统上色。
 * 拆成两个独立开关迟早会被配成互相矛盾的组合。
 */
object ModulePrefs {

    /** SharedPreferences 文件名，两边共用 */
    const val FILE = "noticon"

    const val KEY_ENABLED = "enabled"
    const val KEY_MODE = "mode"
    const val KEY_INCLUDE_PROXY = "includeProxy"

    /** 彩色桌面图标：换成应用在桌面上的图标，保留原色 */
    const val MODE_LAUNCHER_ICON = 0

    /** 系统黑白：把应用原始小图标压成单色，交给系统按主题上色 */
    const val MODE_MONOCHROME = 1

    /** 界面侧入口 */
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 首次打开界面时把默认值写下去，让配置文件存在（模块读不到文件时会退回同样的默认值） */
    fun seedIfAbsent(prefs: SharedPreferences) {
        if (prefs.contains(KEY_MODE)) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_MODE, MODE_LAUNCHER_ICON)
            .putBoolean(KEY_INCLUDE_PROXY, false)
            .apply()
    }

    /**
     * 把存下来的配置翻译成模块行为。
     *
     * [ModuleOptions.keepOriginalColor] 由模式推导，不单独存 —— 见类注释。
     */
    fun read(prefs: SharedPreferences): ModuleOptions {
        val mode = prefs.getInt(KEY_MODE, MODE_LAUNCHER_ICON)
        val monochrome = mode == MODE_MONOCHROME
        return ModuleOptions(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            replacement = if (monochrome) ModuleOptions.FORCE_MONOCHROME else ModuleOptions.USE_LAUNCHER_ICON,
            keepOriginalColor = !monochrome,
            includeProxyNotifications = prefs.getBoolean(KEY_INCLUDE_PROXY, false)
        )
    }
}
