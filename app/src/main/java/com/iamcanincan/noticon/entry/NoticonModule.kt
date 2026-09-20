package com.iamcanincan.noticon.entry

import android.os.Handler
import android.os.Looper
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

        /**
         * 装钩重试次数与间隔。
         *
         * 第一次 packageReady 有可能早于 ClassLoader 就绪（框架还在
         * `LoadedApk.createOrUpdateClassLoaderLocked` 里），那时目标类一个都 load 不出来。
         * 实测正常时 ~90ms 后就都能拿到了，20 × 250ms 留足余量。
         */
        const val MAX_INSTALL_ATTEMPTS = 20
        const val INSTALL_RETRY_MS = 250L
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 模块被加载进某个进程时先报一声。
     *
     * 这一行是排查时的分水岭：有它说明框架确实加载了模块，问题在后面的挂钩；
     * 没它就说明框架压根没把模块放进这个进程，再怎么查挂钩都是白费力气。
     * 所以哪怕什么都还没做，也要先把这行打出来。
     */
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        ModuleRuntime.logI(
            "module loaded in ${param.processName} (systemServer=${param.isSystemServer})"
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != SYSTEM_UI) return
        ModuleRuntime.logI("attaching to SystemUI (api=${getApiVersion()}, framework=${getFrameworkName()})")
        // 挂上模块实例，读一次界面里的设置。之后不再需要重启：每次处理通知时
        // ModuleRuntime.options() 会按 TTL 自己重读，改模式即时生效。
        ModuleRuntime.attach(this)
        installHooks(param.classLoader, attempt = 1)
    }

    /**
     * 装挂钩，装不上就隔一会儿再试。
     *
     * 不能把「框架会再派发一次 packageReady」当成可以依赖的约定 —— 实测确实会派发
     * 2~3 次，但第二次是否够早、是否一定发生都不由我们决定。自己重试才稳。
     *
     * 重试是幂等的（`SystemUiHooks` 内部按方法和 id 判重），重复调用不会把同一条钩子挂两遍。
     *
     * ⚠ 但「已经装上了」要立刻收工：后几次派发带来的 ClassLoader 可能是坏的
     * （实测第三次派发 5 个目标类全 missing），照着重试就是空转 20 轮、刷 5 秒日志。
     */
    private fun installHooks(classLoader: ClassLoader, attempt: Int) {
        if (SystemUiHooks.isInstalled()) return
        if (SystemUiHooks.install(this, classLoader, quiet = attempt > 1)) return
        if (attempt >= MAX_INSTALL_ATTEMPTS) {
            ModuleRuntime.logE(
                "hook install gave up after $attempt attempts",
                IllegalStateException("SystemUI target classes never became resolvable")
            )
            return
        }
        ModuleRuntime.logW("hook install attempt $attempt failed, retrying in ${INSTALL_RETRY_MS}ms")
        mainHandler.postDelayed({ installHooks(classLoader, attempt + 1) }, INSTALL_RETRY_MS)
    }
}
