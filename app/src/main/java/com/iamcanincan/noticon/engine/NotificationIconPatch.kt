package com.iamcanincan.noticon.engine

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
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

    /** 铺在启动图标下面的浅色底板；图标不透明时看不见，主要用于兜住透明边缘 */
    private const val PLATE_COLOR = 0xFFE6F0FA.toInt()

    fun patch(sbn: StatusBarNotification, context: Context) {
        try {
            val notification = sbn.notification ?: return
            val pkg = sbn.packageName
            val options = ModuleRuntime.options
            if (!options.enabled) return

            // 1) 排除名单。代发通知（opPkg 与 pkg 不一致）按开关单独决定要不要放行
            if (pkg in options.excludedPackages) {
                // getOpPkg 直到 API 29 才公开，直接调用在 Android 8/9 上会 NoSuchMethodError
                val opPkg = MemberLookup.invoke(sbn, "getOpPkg") as? String
                val isProxy = opPkg != null && opPkg != pkg
                if (!isProxy || !options.includeProxyNotifications) return
            }

            val smallIcon = notification.smallIcon ?: return

            // 2) 已经适配过的单色图标不动
            if (options.preserveTinted) {
                val bitmap = IconBitmap.decode(smallIcon, context)
                if (bitmap != null && ToneCheck.isGrayscale(bitmap)) return
            }

            // 3) 按策略处理未适配的图标
            when (options.replacement) {
                ModuleOptions.USE_LAUNCHER_ICON -> useLauncherIcon(pkg, notification, context)
                ModuleOptions.FORCE_MONOCHROME -> forceMonochrome(smallIcon, notification, context)
            }
            ModuleRuntime.logI("patched $pkg")
        } catch (t: Throwable) {
            ModuleRuntime.logE("patch failed", t)
        }
    }

    private fun useLauncherIcon(pkg: String, notification: Notification, context: Context) {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        val launcherDrawable = packageManager.getApplicationIcon(appInfo)
        val launcherBitmap: Bitmap = IconBitmap.rasterize(launcherDrawable)
        IconBitmap.applySmallIcon(
            Icon.createWithBitmap(IconBitmap.onPlate(launcherBitmap, PLATE_COLOR)),
            notification
        )
    }

    private fun forceMonochrome(smallIcon: Icon, notification: Notification, context: Context) {
        val bitmap = IconBitmap.decode(smallIcon, context) ?: return
        if (ToneCheck.isGrayscale(bitmap)) return
        IconBitmap.applySmallIcon(Icon.createWithBitmap(IconBitmap.monochrome(bitmap)), notification)
    }
}
