package com.iamcanincan.noticon.data

import android.content.Context
import android.content.SharedPreferences
import com.iamcanincan.noticon.model.ModuleOptions

/**
 * 模块配置的存储约定 —— 界面进程与 SystemUI 进程之间的唯一通道。
 *
 * 界面（App 进程）用普通的 [Context.getSharedPreferences] 写；模块（SystemUI 进程）
 * 通过本应用暴露的只读 ContentProvider（见 [ConfigProvider]）读。
 * **两边必须用同一个文件名**，否则模块读到的永远是默认值，界面改了也不生效。
 *
 * 界面只暴露「模式」这一个选择，其余开关由模式推导 —— 因为替换策略和
 * 是否保留原色必须一致：彩色图标要保色，单色剪影要交给系统上色。
 * 拆成两个独立开关迟早会被配成互相矛盾的组合。
 */
object ModulePrefs {

    /** 界面侧日志 TAG，和模块侧共用，方便在同一份 logcat 里对照两边看到的东西 */
    const val TAG = "Noticon"

    /** SharedPreferences 文件名，两边共用 */
    const val FILE = "noticon"

    /**
     * 配置通道 provider 的 authority 后缀。
     *
     * 完整 authority = 包名 + 这个后缀（清单里写死，模块侧按同一规则拼）。
     * 之所以不用 LibXposed 的 getRemotePreferences：实测 Vector 2.2 返回空对象、
     * openRemoteFile 也找不到文件，配置根本传不到模块 —— 详见 [ConfigProvider]。
     */
    const val AUTHORITY_SUFFIX = ".config"

    /** provider 返回的列名，模块侧按这些名字取值 */
    const val COLUMN_MODE = "mode"
    const val COLUMN_ENABLED = "enabled"

    const val KEY_ENABLED = "enabled"
    const val KEY_MODE = "mode"

    /** 彩色桌面图标：换成应用在桌面上的图标，保留原色 */
    const val MODE_LAUNCHER_ICON = 0

    /** 系统黑白：把应用原始小图标压成单色，交给系统按主题上色 */
    const val MODE_MONOCHROME = 1

    /**
     * 没存过配置时用的模式（用户定的：默认单色）。
     *
     * 界面初始值、[seedIfAbsent]、[read]、[fromValues] 和 [ConfigProvider] 都取这一个常量 ——
     * 之前这几处各写各的默认值，改默认模式时漏掉任何一处，
     * 就会出现「界面显示彩色、模块按单色跑」这种两边不一致的怪现象。
     */
    const val DEFAULT_MODE = MODE_MONOCHROME

    /** 界面侧入口 */
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 首次打开界面时把默认值写下去，让配置文件存在（模块读不到文件时会退回同样的默认值） */
    fun seedIfAbsent(prefs: SharedPreferences) {
        if (prefs.contains(KEY_MODE)) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_MODE, DEFAULT_MODE)
            .apply()
    }

    /**
     * 把存下来的配置翻译成模块行为。
     *
     * [ModuleOptions.keepOriginalColor] 由模式推导，不单独存 —— 见类注释。
     */
    fun read(prefs: SharedPreferences): ModuleOptions = derive(
        mode = prefs.getInt(KEY_MODE, DEFAULT_MODE),
        enabled = prefs.getBoolean(KEY_ENABLED, true)
    )

    /**
     * 从直接解析配置文件得到的键值对构造。
     *
     * XML 里所有值都是字符串（`<int name="mode" value="1" />`），
     * 解析失败或键缺失时一律退回默认值 —— 和 [read] 的兜底行为保持一致。
     */
    fun fromValues(values: Map<String, String>): ModuleOptions = derive(
        mode = values[KEY_MODE]?.trim()?.toIntOrNull() ?: DEFAULT_MODE,
        enabled = values[KEY_ENABLED]?.trim()?.toBooleanStrictOrNull() ?: true
    )

    /** 从 provider 查出来的原始值构造。列里存的是整数，调用方已转好类型 */
    fun fromRaw(mode: Int, enabled: Boolean): ModuleOptions = derive(mode, enabled)

    private fun derive(mode: Int, enabled: Boolean): ModuleOptions {
        val monochrome = mode == MODE_MONOCHROME
        return ModuleOptions(
            enabled = enabled,
            replacement = if (monochrome) ModuleOptions.FORCE_MONOCHROME else ModuleOptions.USE_LAUNCHER_ICON,
            keepOriginalColor = !monochrome
        )
    }
}
