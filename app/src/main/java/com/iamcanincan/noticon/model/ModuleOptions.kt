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
     * 默认关：现在默认策略是压成单色剪影，交出去的 alpha 形状本来就该由系统
     * 按主题上色 —— 那样才和「应用自己适配过」完全一致（深色主题白、浅色主题深）。
     * 只有改用 USE_LAUNCHER_ICON（往通知里塞彩色启动图标）时才需要打开，
     * 否则系统会把塞进去的彩色图标又染回单色，白换一场。
     */
    var keepOriginalColor: Boolean = false,

    /** 是否连代发通知（例如推送 SDK 代投、opPkg 与 pkg 不一致）一起处理 */
    var includeProxyNotifications: Boolean = false,

    /**
     * 替换策略，取值见下面的常量。
     *
     * 默认走「压成系统风格的单色剪影」：这样修出来的图标和那些本来就做好了
     * 主题适配的应用完全一致 —— 状态栏统一着色、随主题深浅变化，
     * 而不是往通知里塞一张彩色启动图标。
     */
    var replacement: Int = FORCE_MONOCHROME,

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
