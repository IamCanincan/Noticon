package com.iamcanincan.noticon.entry

import com.iamcanincan.noticon.engine.ModuleRuntime
import com.iamcanincan.noticon.engine.SystemUiHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口。
 *
 * 只在 SystemUI 进程里干活，其余进程直接返回 —— 通知是系统界面画出来的，
 * 挂别的地方没意义。作用域也只写 SystemUI。
 *
 * 桌面图标与设置界面由 [com.iamcanincan.noticon.ui.MainActivity] 提供，与这里的挂钩无关。
 */
class NoticonModule : XposedModule() {

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != SYSTEM_UI) return
        ModuleRuntime.logI("attaching to SystemUI (api=${getApiVersion()}, framework=${getFrameworkName()})")
        // 挂上模块实例，读一次界面里的设置。之后不再需要重启：每次处理通知时
        // ModuleRuntime.options() 会按 TTL 自己重读，改模式即时生效。
        ModuleRuntime.attach(this)
        SystemUiHooks.install(this, param.classLoader)
    }
}
