package com.iamcanincan.noticon.model

/**
 * 模块的行为开关。
 *
 * 本项目没有界面、没有 ContentProvider，也不读远程配置：
 * 下面这些默认值就是最终行为，改代码即改行为。
 */
data class ModuleOptions(

    /** 总开关 */
    var enabled: Boolean = true,

    /**
     * 已经做主题适配（单色）的图标原样放过 —— 这是「适配过的不要动」的核心。
     */
    var preserveTinted: Boolean = true,

    /**
     * 保留替换后图标的原色。
     * 关掉的话状态栏会按主题把图标统一染成白/灰，换了的图标又变回一团色块。
     */
    var keepOriginalColor: Boolean = true,

    /** 是否连代发通知（例如推送 SDK 代投、opPkg 与 pkg 不一致）一起处理 */
    var includeProxyNotifications: Boolean = false,

    /** 替换策略，取值见下面的常量 */
    var replacement: Int = USE_LAUNCHER_ICON,

    /** 永不处理的应用包名 */
    var excludedPackages: Set<String> = emptySet()
) {
    companion object {
        /** 未适配 → 换成应用启动图标 */
        const val USE_LAUNCHER_ICON = 0

        /** 未适配 → 就地压成单色，模拟应用自己适配过 */
        const val FORCE_MONOCHROME = 1
    }
}
