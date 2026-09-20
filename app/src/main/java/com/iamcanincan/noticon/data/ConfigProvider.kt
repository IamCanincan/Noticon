package com.iamcanincan.noticon.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * 把界面里选的配置暴露给模块。
 *
 * 模块活在 SystemUI 进程里，既读不到本应用的私有目录（被系统隔离挡掉，
 * 表现为 ENOENT），也用不了 LibXposed 的远程配置 —— 实测 Vector 2.2 的
 * `getRemotePreferences` 返回空对象、`openRemoteFile` 找不到任何文件，
 * 配置根本传不过去。ContentProvider 是 Android 标准的跨进程通道，
 * 不依赖任何框架实现，Vector / LSPosed 上都能用。
 *
 * 安全：
 * - 清单里声明 `exported` 加 `readPermission="android.permission.STATUS_BAR"`，
 *   这是签名级权限，只有 SystemUI、system_server 这类系统组件持有，普通应用查不进来；
 * - 代码里再按调用方包名校验一次作为第二道闸；
 * - 只读，暴露的内容仅是「图标模式 / 是否启用」这类非敏感开关。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        if (!isTrustedCaller()) return cursor
        val ctx = context ?: return cursor
        val prefs = ModulePrefs.of(ctx)
        cursor.addRow(
            arrayOf<Any>(
                prefs.getInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_LAUNCHER_ICON),
                if (prefs.getBoolean(ModulePrefs.KEY_ENABLED, true)) 1 else 0,
                if (prefs.getBoolean(ModulePrefs.KEY_INCLUDE_PROXY, false)) 1 else 0
            )
        )
        return cursor
    }

    /**
     * 第二道闸：只认系统进程和 SystemUI。
     *
     * 不能用 uid 硬判 —— 这台设备上 SystemUI 跑在 10154 而不是 1000，
     * 所以按「调用方 uid 归属哪个包」来判断。
     */
    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return true
        val packages = context?.packageManager?.getPackagesForUid(uid) ?: return false
        return packages.any { it == SYSTEM_UI }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(READ_ONLY)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException(READ_ONLY)

    companion object {
        const val SYSTEM_UI = "com.android.systemui"

        private const val READ_ONLY = "Noticon 的配置通道是只读的"

        /** 列名与 [ModulePrefs.COLUMN_*] 一一对应 */
        val COLUMNS = arrayOf(
            ModulePrefs.COLUMN_MODE,
            ModulePrefs.COLUMN_ENABLED,
            ModulePrefs.COLUMN_INCLUDE_PROXY
        )
    }
}
