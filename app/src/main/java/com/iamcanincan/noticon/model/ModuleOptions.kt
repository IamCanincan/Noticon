package com.iamcanincan.noticon.model

/**
 * 模块的行为开关。
 *
 * 默认值 = 没有配置时的兜底行为（等价于「彩色桌面图标」模式）。
 * 界面里改了设置后，值由 [com.iamcanincan.noticon.data.ModulePrefs] 从
 * SharedPreferences 读出来覆盖 —— 模块侧走 LibXposed 的远程配置通道。
 *
 * 注意 [keepOriginalColor] 与 [replacement] 必须成对：彩色图标要保色，
 * 单色剪影要交给系统上色。界面只让用户选「模式」，这一对由模式推导。
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
     * 默认开：默认策略塞进去的是彩色的应用图标，不保色的话系统会把它
     * 又染成单色，等于白换一场。只有改用 FORCE_MONOCHROME（压成单色剪影）
     * 时才需要关掉 —— 那种形状本来就该由系统按主题上色。
     */
    var keepOriginalColor: Boolean = true,

    /** 是否连代发通知（例如推送 SDK 代投、opPkg 与 pkg 不一致）一起处理 */
    var includeProxyNotifications: Boolean = false,

    /**
     * 替换策略，取值见下面的常量。
     *
     * 默认走「直接换成桌面上那个应用图标」：未适配的小图标本来就是一坨看不出
     * 是谁的色块，用用户天天在桌面上见到、认得的那个图标替掉最直观。
     * 彩色原样保留（配合 [keepOriginalColor]），不做任何去色处理。
     */
    var replacement: Int = USE_LAUNCHER_ICON,

    /** 永不处理的应用包名 */
    var excludedPackages: Set<String> = emptySet()
) {
    companion object {
        /** 未适配 → 直接换成彩色的应用启动图标 */
        const val USE_LAUNCHER_ICON = 0

        /** 未适配 → 把应用自己给的那个小图标就地压成单色 */
        const val FORCE_MONOCHROME = 1

        /** 未适配 → 用桌面应用图标生成单色剪影（保留明暗，不是纯剪影） */
        const val LAUNCHER_ICON_MONOCHROME = 2
    }
}
