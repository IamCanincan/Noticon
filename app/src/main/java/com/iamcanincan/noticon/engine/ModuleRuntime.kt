package com.iamcanincan.noticon.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.iamcanincan.noticon.data.ModulePrefs
import com.iamcanincan.noticon.model.ModuleOptions
import io.github.libxposed.api.XposedInterface

/**
 * 挂钩运行期的共享状态，活在 SystemUI 进程里。
 *
 * 进程内没有 Application 可用，SystemUI 的 Context 只能从挂钩点上顺出来，
 * 这里存一份作为后续加载资源的兜底。
 *
 * 常驻 SystemUI 进程是本模块的运行前提（它没有自己的进程），
 * 生命周期与 SystemUI 一致，不存在 Activity 泄漏问题，故抑制 StaticFieldLeak。
 */
@SuppressLint("StaticFieldLeak")
object ModuleRuntime {

    const val TAG = "Noticon"

    /** 从通知行绑定处顺出来的 SystemUI 上下文 */
    var systemContext: Context? = null

    /** 模块实例，用来读界面写下的远程配置 */
    private var module: XposedInterface? = null

    private var current: ModuleOptions = ModuleOptions()
    private var lastReadAt = 0L

    /**
     * 远程配置的重读间隔。
     *
     * 界面里改了设置要尽快生效，但没必要每条通知都去读一次文件 ——
     * 1 秒足够盖住「改完设置 → 下拉通知栏」这个动作，也不会让高频通知反复读盘。
     */
    private const val OPTIONS_TTL_MS = 1_000L

    /**
     * 挂上模块实例并读一次配置。
     *
     * 注意这里只是「读」，不涉及挂钩：挂钩装好之后跟模式无关，
     * 所以换模式既不用重新挂钩，也不用重启系统界面。
     */
    fun attach(module: XposedInterface) {
        this.module = module
        if (!supportsRemotePreferences(module)) {
            logW("framework has no remote preferences support, using defaults")
        }
        refresh(force = true)
    }

    /**
     * 当前生效的选项。
     *
     * 会按 [OPTIONS_TTL_MS] 重读远程配置，所以界面里切换模式后**新收到的通知立刻按新模式处理**，
     * 不需要重启。
     *
     * 已经在通知栏里的那条不会跟着变：它的行是首次展开时 inflate 的，替换就发生在那一刻，
     * 之后不会重来。要它变，得等这条通知重新加载（重新发出，或重启系统界面）。
     */
    fun options(): ModuleOptions {
        refresh(force = false)
        return current
    }

    private fun refresh(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReadAt < OPTIONS_TTL_MS) return
        lastReadAt = now

        val m = module ?: return
        if (!supportsRemotePreferences(m)) return

        // 读失败就沿用上一次的值：宁可维持现状，也别因为一次读盘失败把用户的设置重置了
        val read = runCatching {
            m.getRemotePreferences(ModulePrefs.FILE)?.let { ModulePrefs.read(it) }
        }.onFailure { logW("remote preferences unreadable: ${it.message}") }.getOrNull() ?: return

        if (force || read != current) {
            current = read
            logI("options: enabled=${read.enabled} mode=${read.replacement} keepColor=${read.keepOriginalColor}")
        }
    }

    /** 框架是否声明支持远程配置（API 102 的能力位） */
    private fun supportsRemotePreferences(m: XposedInterface): Boolean =
        (m.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) != 0L

    fun logI(message: String) = Log.i(TAG, message)

    fun logW(message: String) = Log.w(TAG, message)

    fun logE(message: String, cause: Throwable) = Log.e(TAG, message, cause)
}
