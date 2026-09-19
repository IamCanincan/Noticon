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
     * 跳过系统的统一着色、保住图标原色。
     *
     * 默认关：默认策略交出去的是单色剪影，本来就该由系统按主题上色
     * （深色主题白、浅色主题深）。只有改用 USE_LAUNCHER_ICON（塞彩色启动图标）
     * 时才需要打开，否则系统会把彩色图标又染回单色，白换一场。
     */
    var keepOriginalColor: Boolean = false,

    /** 是否连代发通知（例如推送 SDK 代投、opPkg 与 pkg 不一致）一起处理 */
    var includeProxyNotifications: Boolean = false,

    /**
     * 替换策略，取值见下面的常量。
     *
     * 默认走「就地压成黑白」：不去碰桌面图标，只把应用自己给的那个彩色小图标
     * 压成系统认得的单色形状，剩下的交给系统按主题着色。
     * 通知里的图标因此始终是黑白的，和原生适配过的应用长得一样。
     */
    var replacement: Int = FORCE_MONOCHROME,

    /** 永不处理的应用包名 */
    var excludedPackages: Set<String> = emptySet()
) {
    companion object {
        /** 未适配 → 直接换成彩色的应用启动图标 */
        const val USE_LAUNCHER_ICON = 0

        /** 未适配 → 把应用自己给的那个小图标就地压成单色 */
        const val FORCE_MONOCHROME = 1

        /** 未适配 → 用桌面应用图标生成单色剪影（默认） */
        const val LAUNCHER_ICON_MONOCHROME = 2
    }
}
