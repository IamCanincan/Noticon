package com.iamcanincan.noticon.engine

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import com.iamcanincan.noticon.graphics.IconBitmap
import com.iamcanincan.noticon.graphics.ToneCheck
import com.iamcanincan.noticon.model.ModuleOptions
import com.iamcanincan.noticon.util.MemberLookup

/**
 * 通知小图标的修复主体。
 *
 * 判定顺序：
 * 1. 命中排除名单且不是代发通知 → 跳过；
 * 2. 图标已经是单色（说明应用做过主题适配）→ 跳过；
 * 3. 其余按 replacement 策略处理：换成启动图标，或就地压成单色。
 */
object NotificationIconPatch {

    fun patch(sbn: StatusBarNotification, context: Context) {
        try {
            val notification = sbn.notification ?: return
            val pkg = sbn.packageName
            // 每次处理通知都取一次当前选项（内部按 TTL 重读远程配置），
            // 所以界面里切换模式后，新通知立刻按新模式处理，不需要重启
            val options = ModuleRuntime.options()
            if (!options.enabled) return

            // 1) 排除名单。代发通知（opPkg 与 pkg 不一致）按开关单独决定要不要放行
            if (pkg in options.excludedPackages) {
                // getOpPkg 直到 API 29 才公开，直接调用在 Android 8/9 上会 NoSuchMethodError
                val opPkg = MemberLookup.invoke(sbn, "getOpPkg") as? String
                val isProxy = opPkg != null && opPkg != pkg
                if (!isProxy || !options.includeProxyNotifications) return
            }

            val smallIcon = notification.smallIcon ?: return
            val beforeType = iconType(smallIcon)

            // 2) 已经适配过的单色图标不动
            if (options.preserveTinted) {
                val bitmap = IconBitmap.decode(smallIcon, context)
                if (bitmap != null && ToneCheck.isGrayscale(bitmap)) return
            }

            // 3) 按策略处理未适配的图标
            val replaced = when (options.replacement) {
                ModuleOptions.USE_LAUNCHER_ICON -> useLauncherIcon(pkg, notification, context)
                ModuleOptions.FORCE_MONOCHROME -> forceMonochrome(smallIcon, notification, context)
                ModuleOptions.LAUNCHER_ICON_MONOCHROME -> launcherIconMonochrome(pkg, notification, context)
                else -> false
            }
            // 只有真的换了才打日志；跳过的情况不打，免得日志里分不清「换了」和「没动」
            if (replaced) {
                ModuleRuntime.logI(
                    "patched $pkg $beforeType->${iconType(notification.smallIcon)}" +
                        " via=${options.replacementName}"
                )
            }
        } catch (t: Throwable) {
            ModuleRuntime.logE("patch failed", t)
        }
    }

    /**
     * 日志里显示的图标类型。
     *
     * Icon.getType() 是 API 28 才公开的，minSdk 26 下直连会 NoSuchMethodError，
     * 所以这里显式判版本。拿不到就显示 ?，只影响日志，不影响替换逻辑。
     */
    private fun iconType(icon: Icon?): String {
        if (icon == null) return "null"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "?"
        return icon.type.toString()
    }

    private fun useLauncherIcon(pkg: String, notification: Notification, context: Context): Boolean {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        val launcherDrawable = packageManager.getApplicationIcon(appInfo)
        // 自适应图标光栅化后四周空一圈，先裁掉再放大填满，否则通知里那个图标会很小
        val launcherBitmap = IconBitmap.fill(IconBitmap.rasterize(launcherDrawable))
        return IconBitmap.applySmallIcon(Icon.createWithBitmap(launcherBitmap), notification)
    }

    /**
     * 用桌面应用图标生成系统风格的通知图标。
     *
     * 轮廓取自用户认得的桌面图标，但交出去的是透明底 + 白色形状的单色剪影，
     * 由系统按主题统一着色 —— 一眼认得出是哪个应用，又和那些本来就适配好
     * 的图标长得一样，不会五颜六色地散在通知里。
     *
     * 注意顺序：先 fill 再 monochrome。fill 裁掉的透明边正好是 monochrome
     * 判断形状的凭据，反过来做会把整块图标压成一个实心圆。
     */
    private fun launcherIconMonochrome(pkg: String, notification: Notification, context: Context): Boolean {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        val launcherDrawable = packageManager.getApplicationIcon(appInfo)
        // 只取前景层，再裁掉四周空白放大填满，最后去掉颜色只留明暗
        val filled = IconBitmap.fill(IconBitmap.foregroundOf(launcherDrawable))
        return IconBitmap.applySmallIcon(Icon.createWithBitmap(IconBitmap.grayscale(filled)), notification)
    }

    private fun forceMonochrome(smallIcon: Icon, notification: Notification, context: Context): Boolean {
        val bitmap = IconBitmap.decode(smallIcon, context) ?: return false
        // 已经是单色的就不用再压一遍
        if (ToneCheck.isGrayscale(bitmap)) return false
        return IconBitmap.applySmallIcon(Icon.createWithBitmap(IconBitmap.monochrome(bitmap)), notification)
    }
}
