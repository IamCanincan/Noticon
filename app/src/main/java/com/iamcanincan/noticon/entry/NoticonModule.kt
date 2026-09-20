package com.iamcanincan.noticon.entry

import com.iamcanincan.noticon.data.ModulePrefs
import com.iamcanincan.noticon.engine.ModuleRuntime
import com.iamcanincan.noticon.engine.SystemUiHooks
import com.iamcanincan.noticon.model.ModuleOptions
import io.github.libxposed.api.XposedInterface
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
        // 先读设置再挂钩：挂钩行为取决于界面里选的模式
        ModuleRuntime.options = loadOptions()
        SystemUiHooks.install(this, param.classLoader)
    }

    /**
     * 读取界面里保存的设置。
     *
     * 走 API 102 的远程配置通道（[XposedInterface.getRemotePreferences]），
     * 文件名必须与界面侧 [ModulePrefs.FILE] 完全一致。
     *
     * 任何一步失败（框架不支持该能力、界面还没打开过导致文件不存在）都退回默认值 ——
     * 模块必须能在没有任何配置的情况下独立工作，这也是它以前"配置写死"的那套默认行为。
     */
    private fun loadOptions(): ModuleOptions {
        val defaults = ModuleOptions()
        if (!supportsRemotePreferences()) {
            ModuleRuntime.logW("framework has no remote preferences support, using defaults")
            return defaults
        }
        return runCatching {
            val prefs = getRemotePreferences(ModulePrefs.FILE) ?: return@runCatching defaults
            ModulePrefs.read(prefs).also {
                ModuleRuntime.logI(
                    "options: enabled=${it.enabled} mode=${it.replacement} keepColor=${it.keepOriginalColor}"
                )
            }
        }.onFailure {
            ModuleRuntime.logW("remote preferences unreadable, using defaults: ${it.message}")
        }.getOrDefault(defaults)
    }

    /** 框架是否声明支持远程配置（API 102 的能力位） */
    private fun supportsRemotePreferences(): Boolean =
        (getFrameworkProperties() and XposedInterface.PROP_CAP_REMOTE) != 0L
}
