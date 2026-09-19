package com.iamcanincan.noticon.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.view.View
import android.os.Build
import android.widget.RemoteViews
import com.iamcanincan.noticon.util.MemberLookup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 往 SystemUI 里装挂钩。
 *
 * 这里挂的全是私有类/私有方法，任何一环在不同版本上都可能改名或改签名，
 * 所以每个挂钩都是「找不到就记日志跳过」，绝不让 SystemUI 因为装钩失败而崩。
 * 装上的钩子会以 <方法名> hooked 打进日志，真机排障时看这个就知道缺了哪一段。
 */
object SystemUiHooks {

    private val EXCEPTION_MODE = XposedInterface.ExceptionMode.PROTECTIVE

    private const val ROW_BINDER_MODERN =
        "com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl"
    private const val ROW_BINDER_LEGACY =
        "com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl"
    private const val NOTIFICATION_ENTRY =
        "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    private const val ICON_MANAGER =
        "com.android.systemui.statusbar.notification.icon.IconManager"
    private const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
    private const val STATUS_BAR_ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
    private const val CONTRAST_UTIL = "com.android.internal.util.ContrastColorUtil"

    /** 挂载时会逐个探测这些类是否存在，缺的会打进日志 */
    private val TARGET_CLASSES = listOf(
        ROW_BINDER_MODERN, ROW_BINDER_LEGACY, NOTIFICATION_ENTRY,
        ICON_MANAGER, STATUS_BAR_ICON, STATUS_BAR_ICON_VIEW, CONTRAST_UTIL
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        reportEnvironment(classLoader)
        installRowInflation(module, classLoader)
        installColorRetention(module, classLoader)
    }

    /**
     * 挂载前先把环境探一遍，把「哪些类没找到」直接打进日志。
     *
     * SystemUI 的类名历代改得很勤，而这一步离线没法验证。真机上只要看一眼
     * `adb logcat -s Noticon` 开头的 missing: 就知道要换哪个名字，不用猜。
     */
    private fun reportEnvironment(classLoader: ClassLoader) {
        ModuleRuntime.logI("device sdk=${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
        val missing = TARGET_CLASSES.filter { MemberLookup.findClass(it, classLoader) == null }
        if (missing.isEmpty()) {
            ModuleRuntime.logI("all target classes resolved")
        } else {
            for (name in missing) ModuleRuntime.logW("missing class: $name")
        }
    }

    /**
     * 通知行 inflate 之前把小图标换掉。
     * 这是主挂钩点：换图发生在这里，后面几个钩只是保证换完的图不被染回单色。
     */
    private fun installRowInflation(module: XposedModule, classLoader: ClassLoader) {
        val binderClass = MemberLookup.findClass(ROW_BINDER_MODERN, classLoader)
            ?: MemberLookup.findClass(ROW_BINDER_LEGACY, classLoader)
        if (binderClass == null) {
            ModuleRuntime.logE("NotificationRowBinderImpl not found", NoSuchElementException(ROW_BINDER_MODERN))
            return
        }
        val entryClass = MemberLookup.findClass(NOTIFICATION_ENTRY, classLoader) ?: return

        for (method in binderClass.declaredMethods) {
            if (method.name != "inflateViews") continue
            if (!method.parameterTypes.contains(entryClass)) continue
            method.isAccessible = true

            module.hook(method).setId("inflateViews").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                captureSystemContext(chain.thisObject)

                for (arg in chain.args) {
                    if (arg == null || !entryClass.isInstance(arg)) continue
                    runCatching {
                        val sbn = (MemberLookup.readFieldByType(arg, StatusBarNotification::class.java)
                            ?: MemberLookup.readField(arg, "mSbn")) as? StatusBarNotification ?: return@runCatching
                        val context = ModuleRuntime.systemContext ?: return@runCatching
                        // TYPE_RESOURCE 的图标必须用目标应用自己的 Resources 解码
                        val targetContext = runCatching {
                            context.createPackageContext(
                                sbn.packageName,
                                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                            )
                        }.getOrDefault(context)
                        NotificationIconPatch.patch(sbn, targetContext)
                    }.onFailure { ModuleRuntime.logE("inflateViews patch failed", it) }
                }
                chain.proceed()
            }
            ModuleRuntime.logI("inflateViews hooked")
            return
        }
        ModuleRuntime.logE("inflateViews not found", NoSuchMethodException("inflateViews"))
    }

    /** 顺一个 SystemUI 的 Context 出来，后面取包名资源要用 */
    private fun captureSystemContext(binder: Any?) {
        val context = binder?.let { MemberLookup.readField(it, "mContext") as? Context }
            ?: ModuleRuntime.systemContext
        if (context != null) ModuleRuntime.systemContext = context
    }

    /**
     * 保色相关挂钩。分三处，缺一处就会出现「图换了但被染成灰白」的半成品效果：
     * 1. IconManager#setIcon —— 打上 icon_is_pre_L 标记，跳过统一着色；
     * 2. StatusBarIconView#updateIconColor —— 非单色图标不强制设色；
     * 3. Notification.Builder#processSmallIconColor —— 去掉外圈圆底和留白。
     */
    /**
     * 要用 getIdentifier 取 SystemUI 内部的资源 id（icon_is_pre_L、left_icon），
     * 这些 id 不在本模块的编译资源里，只能按名字反查，故抑制 DiscouragedApi。
     */
    @SuppressLint("DiscouragedApi")
    private fun installColorRetention(module: XposedModule, classLoader: ClassLoader) {
        val iconManager = MemberLookup.findClass(ICON_MANAGER, classLoader)
        val entryClass = MemberLookup.findClass(NOTIFICATION_ENTRY, classLoader)
        val statusBarIcon = MemberLookup.findClass(STATUS_BAR_ICON, classLoader)
        val statusBarIconView = MemberLookup.findClass(STATUS_BAR_ICON_VIEW, classLoader)

        val setIcon = if (iconManager != null && entryClass != null && statusBarIcon != null && statusBarIconView != null) {
            MemberLookup.methodWithParams(iconManager, "setIcon", entryClass, statusBarIcon, statusBarIconView)
        } else {
            null
        }

        if (setIcon != null) {
            module.hook(setIcon).setId("setIcon").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                if (shouldKeepColor()) {
                    for (arg in chain.args) {
                        if (arg is View) {
                            val tagId = arg.context.resources.getIdentifier("icon_is_pre_L", "id", "com.android.systemui")
                            if (tagId != 0) arg.setTag(tagId, true)
                        }
                    }
                }
                chain.proceed()
            }
            ModuleRuntime.logI("setIcon hooked")
        }

        if (statusBarIconView == null) return

        MemberLookup.methodWithParams(statusBarIconView, "updateIconColor")?.let { method ->
            val contrastUtil = MemberLookup.findClass(CONTRAST_UTIL, classLoader)
            module.hook(method).setId("updateIconColor").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
                if (shouldKeepColor()) {
                    runCatching {
                        val view = chain.thisObject as View
                        val sbn = MemberLookup.readField(view, "mNotification") as? StatusBarNotification
                        if (sbn != null && sbn.packageName != "android") {
                            val context = view.context
                            val instance = MemberLookup.invokeStatic(
                                contrastUtil!!, "getInstance", arrayOf(context), Context::class.java
                            )
                            val isGrayscale = MemberLookup.invoke(
                                instance!!, "isGrayscaleIcon",
                                arrayOf(context, sbn.notification.smallIcon),
                                Context::class.java, Icon::class.java
                            ) as? Boolean
                            if (isGrayscale == false) MemberLookup.writeField(view, "mCurrentSetColor", 0)
                        }
                    }.onFailure { ModuleRuntime.logE("updateIconColor failed", it) }
                }
                chain.proceed()
            }
            ModuleRuntime.logI("updateIconColor hooked")
        }

        installSmallIconColor(module, classLoader)
    }

    @SuppressLint("DiscouragedApi")
    private fun installSmallIconColor(module: XposedModule, classLoader: ClassLoader) {
        val builderClass = MemberLookup.findClass("android.app.Notification\$Builder", classLoader)
        // 嵌套类的二进制名必须用 $ 分隔，写成 . 会让 loadClass 返回 null
        val paramsClass = MemberLookup.findClass("android.app.Notification\$StandardTemplateParams", classLoader)
        if (builderClass == null || paramsClass == null) return

        val method = runCatching {
            MemberLookup.declaredMethod(
                builderClass, "processSmallIconColor",
                Icon::class.java, RemoteViews::class.java, paramsClass
            )
        }.getOrNull() ?: return

        module.hook(method).setId("processSmallIconColor").setExceptionMode(EXCEPTION_MODE).intercept { chain ->
            if (shouldKeepColor()) {
                runCatching {
                    val context = (MemberLookup.readField(chain.thisObject, "mContext") as? Context)
                        ?: ModuleRuntime.systemContext
                    val smallIcon = chain.getArg(0) as Icon
                    val contentView = chain.getArg(1) as RemoteViews
                    val colorUtil = MemberLookup.invoke(chain.thisObject, "getColorUtil")
                    val isGrayscale = MemberLookup.invoke(
                        colorUtil!!, "isGrayscaleIcon",
                        arrayOf(context, smallIcon), Context::class.java, Icon::class.java
                    ) as? Boolean
                    if (isGrayscale == false && context != null) {
                        contentView.setInt(android.R.id.icon, "setBackgroundResource", android.R.color.transparent)
                        contentView.setViewPadding(android.R.id.icon, 0, 0, 0, 0)
                        val leftIcon = context.resources.getIdentifier("left_icon", "id", "com.android.systemui")
                        if (leftIcon != 0) contentView.setViewPadding(leftIcon, 0, 0, 0, 0)
                    }
                }.onFailure { ModuleRuntime.logE("processSmallIconColor failed", it) }
            }
            chain.proceed()
        }
        ModuleRuntime.logI("processSmallIconColor hooked")
    }

    private fun shouldKeepColor(): Boolean {
        val options = ModuleRuntime.options
        return options.enabled && options.keepOriginalColor
    }
}
