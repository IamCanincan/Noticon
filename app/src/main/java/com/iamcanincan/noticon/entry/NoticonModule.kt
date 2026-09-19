package com.iamcanincan.noticon.entry

import com.iamcanincan.noticon.engine.ModuleRuntime
import com.iamcanincan.noticon.engine.SystemUiHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口。
 *
 * 只在 SystemUI 进程里干活，其余进程直接返回 —— 通知是系统界面画出来的，
 * 挂别的地方没意义。本模块没有界面、没有桌面图标，作用域也只写 SystemUI。
 */
class NoticonModule : XposedModule() {

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != SYSTEM_UI) return
        ModuleRuntime.logI("attaching to SystemUI (api=${getApiVersion()}, framework=${getFrameworkName()})")
        SystemUiHooks.install(this, param.classLoader)
    }
}
