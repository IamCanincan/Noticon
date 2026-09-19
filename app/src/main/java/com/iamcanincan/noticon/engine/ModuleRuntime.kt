package com.iamcanincan.noticon.engine

import android.content.Context
import android.util.Log
import com.iamcanincan.noticon.model.ModuleOptions

/**
 * 挂钩运行期的共享状态，活在 SystemUI 进程里。
 *
 * 进程内没有 Application 可用，SystemUI 的 Context 只能从挂钩点上顺出来，
 * 这里存一份作为后续加载资源的兜底。
 */
object ModuleRuntime {

    const val TAG = "Noticon"

    /** 从通知行绑定处顺出来的 SystemUI 上下文 */
    var systemContext: Context? = null

    /** 当前生效的行为选项 */
    var options: ModuleOptions = ModuleOptions()

    fun logI(message: String) = Log.i(TAG, message)

    fun logE(message: String, cause: Throwable) = Log.e(TAG, message, cause)
}
