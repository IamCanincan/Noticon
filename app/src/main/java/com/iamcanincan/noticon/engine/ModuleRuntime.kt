package com.iamcanincan.noticon.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import com.iamcanincan.noticon.data.ModulePrefs
import com.iamcanincan.noticon.model.ModuleOptions
import io.github.libxposed.api.XposedInterface
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

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

    /** 模块实例，用来读界面写下的配置 */
    private var module: XposedInterface? = null

    private var current: ModuleOptions = ModuleOptions()
    private var lastReadAt = 0L
    private var lastSource: String? = null

    /**
     * 配置的重读间隔。
     *
     * 界面里改了设置要尽快生效，但没必要每条通知都去读一次 ——
     * 1 秒足够盖住「改完设置 → 下拉通知栏」这个动作，也不会让高频通知反复读。
     */
    private const val OPTIONS_TTL_MS = 1_000L

    /** 候选文件名，按可能性排序。SharedPreferences 落盘时就是 `<名字>.xml` */
    private val REMOTE_FILE_CANDIDATES = listOf(
        "${ModulePrefs.FILE}.xml",
        ModulePrefs.FILE,
        "shared_prefs/${ModulePrefs.FILE}.xml",
        "files/${ModulePrefs.FILE}.xml"
    )

    /**
     * provider 查询的结果，由后台线程写、主线程读。
     *
     * ContentResolver 查询有可能把模块应用进程拉起来（几百毫秒），而 [options]
     * 是在 SystemUI 主线程上被通知处理调用的 —— 阻塞在这里会拖慢通知栏。
     * 所以查询放后台，这里只存最近一次结果。
     */
    @Volatile
    private var providerOptions: ModuleOptions? = null

    @Volatile
    private var providerQueryAt = 0L

    @Volatile
    private var providerQueryInFlight = false

    private val providerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "noticon-config")
    }

    /**
     * 挂上模块实例并读一次配置。
     *
     * 注意这里只是「读」，不涉及挂钩：挂钩装好之后跟模式无关，
     * 所以换模式既不用重新挂钩，也不用重启系统界面。
     */
    fun attach(module: XposedInterface) {
        this.module = module
        logI(
            "attach: framework=${runCatching { module.frameworkName }.getOrNull()}" +
                " ${runCatching { module.frameworkVersion }.getOrNull()}" +
                " api=${runCatching { module.apiVersion }.getOrNull()}"
        )
        refresh(force = true)
    }

    /**
     * 当前生效的选项。
     *
     * 会按 [OPTIONS_TTL_MS] 重读配置，所以界面里切换模式后**新收到的通知立刻按新模式处理**，
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
        val read = readOptions(m) ?: return

        if (force || read.options != current || read.source != lastSource) {
            current = read.options
            lastSource = read.source
            logI("options: ${read.options.describe()} (source=${read.source})")
        }
    }

    /** 配置值 + 它的来源，来源要打进日志才好排障 */
    private class Read(val options: ModuleOptions, val source: String)

    /**
     * 按可靠性从高到低依次尝试。
     *
     * 排在最前的是 provider —— 它是唯一一条实测能通的：模块读不到模块应用的私有目录
     * （系统隔离，表现为 ENOENT），LibXposed 的 getRemotePreferences 在 Vector 2.2 上
     * 返回空对象、openRemoteFile 也找不到文件。后面几条保留着，在 LSPosed 上仍然管用。
     */
    private fun readOptions(m: XposedInterface): Read? {
        scheduleProviderQuery(m)
        providerOptions?.let { return Read(it, "provider") }
        readPrefsFileDirectly(m)?.let { return Read(it, "direct-file") }
        readFromRemoteFile(m)?.let { return Read(it, "openRemoteFile") }
        readFromPreferences(m)?.let { return Read(it, "getRemotePreferences") }
        return null
    }

    /** 到点了就把 provider 查询丢到后台线程，本函数不阻塞 */
    private fun scheduleProviderQuery(m: XposedInterface) {
        val now = SystemClock.elapsedRealtime()
        if (providerQueryInFlight || now - providerQueryAt < OPTIONS_TTL_MS) return
        providerQueryAt = now
        providerQueryInFlight = true
        providerExecutor.execute {
            try {
                providerOptions = readFromProvider(m)
            } finally {
                providerQueryInFlight = false
            }
        }
    }

    /**
     * 查模块应用暴露的只读配置 provider。
     *
     * 这是跨进程拿配置的正路：provider 跑在模块应用进程里，读的是它自己的
     * SharedPreferences，所以既不受 SystemUI 的隔离限制，也不依赖框架实现。
     *
     * 每一条提前返回都打日志 —— 这条通道曾经"静默地什么都不返回"，
     * 结果配置一直用默认值而没人发现，排查时必须有据可查。
     */
    private fun readFromProvider(m: XposedInterface): ModuleOptions? {
        val context = systemUiContext()
        if (context == null) {
            logW("provider skipped: no SystemUI context yet")
            return null
        }
        val pkg = runCatching { m.moduleApplicationInfo?.packageName }.getOrNull()
        if (pkg == null) {
            logW("provider skipped: module package name unavailable")
            return null
        }
        val uri = Uri.parse("content://$pkg${ModulePrefs.AUTHORITY_SUFFIX}/config")
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    // provider 对非可信调用方返回空游标（包名校验没过）
                    logW("provider returned no row (caller not trusted?)")
                    return@use null
                }
                val options = ModulePrefs.fromRaw(
                    mode = cursor.getInt(cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_MODE)),
                    enabled = cursor.getInt(cursor.getColumnIndexOrThrow(ModulePrefs.COLUMN_ENABLED)) != 0
                )
                logI("provider ok: ${options.describe()}")
                options
            }
        }.onFailure {
            logW("provider query failed: ${it::class.java.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * 拿一个 SystemUI 自己的 Context。
     *
     * 顺序：
     * 1. 挂钩点上顺出来的那个（最可靠，一定是 SystemUI 的 Context）；
     * 2. `ActivityThread.currentApplication()` —— 它的包名就是 com.android.systemui，
     *    正是 provider 校验需要的调用方身份。
     *
     * **刻意不走 `getSystemContext()` + `createPackageContext()`**：那个系统上下文的包名是
     * `android`，要再包一层才可用；而 `createPackageContext` 会进到 `LoadedApk.updateApplicationInfo`
     * → `createOrUpdateClassLoaderLocked`，框架在这个点上还会再派发一次 packageReady。
     * 于是我们自己的调用把自己重入进来，且此时 ClassLoader 还没建好、所有目标类都找不到。
     * 实测过：开机那次 `attaching to SystemUI` 之后 5 个类全 missing、钩子一个都没装上，
     * 就是这条路径造成的。绕开它，开机第一次派发就能正常装钩。
     *
     * 拿不到就返回 null（provider 这条腿这轮跳过，下次 TTL 到点再试），不要退而用 `android` 上下文 ——
     * 那样只会换来一条 `SecurityException: Given calling package android does not match caller's uid`。
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun systemUiContext(): Context? {
        systemContext?.let { return it }
        applicationContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val currentApplication = activityThread.getDeclaredMethod("currentApplication")
            currentApplication.isAccessible = true
            currentApplication.invoke(current) as? Context
        }.onFailure {
            logW("application context unavailable: ${it::class.java.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /** SystemUI 进程的 Application，包名就是 com.android.systemui */
    private val applicationContext: Context?
        get() = cachedApplicationContext ?: runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val method = activityThread.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            (method.invoke(current) as? Context)?.also { cachedApplicationContext = it }
        }.getOrNull()

    @Volatile
    private var cachedApplicationContext: Context? = null

    /**
     * 直接按绝对路径读模块应用的配置文件。
     *
     * 每次都能拿到最新内容，不经过任何进程内缓存。但模块应用和 SystemUI 是两个 uid，
     * 实测被系统隔离挡掉（ENOENT），所以只能当备胎。
     */
    private fun readPrefsFileDirectly(m: XposedInterface): ModuleOptions? {
        val dataDir = runCatching { m.moduleApplicationInfo?.dataDir }.getOrNull() ?: return null
        val file = File(dataDir, "shared_prefs/${ModulePrefs.FILE}.xml")
        if (!file.canRead()) return null
        val values = runCatching { file.inputStream().use { parsePrefsXml(it) } }
            .onFailure { logW("direct read '$file' failed: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull() ?: return null
        return values.takeIf { it.containsKey(ModulePrefs.KEY_MODE) }?.let { ModulePrefs.fromValues(it) }
    }

    /**
     * 走框架的远程文件通道。
     *
     * 只认「解析出来的键里有 KEY_MODE」的候选文件 —— 否则名字试错时
     * 一个空文件也会被当成有效配置，把用户的设置悄悄重置成默认值。
     */
    private fun readFromRemoteFile(m: XposedInterface): ModuleOptions? {
        for (name in REMOTE_FILE_CANDIDATES) {
            val values = runCatching {
                m.openRemoteFile(name)?.use { parsePrefsXml(FileInputStream(it.fileDescriptor)) }
            }.onFailure {
                logW("openRemoteFile('$name') failed: ${it::class.java.simpleName}: ${it.message}")
            }.getOrNull() ?: continue
            if (!values.containsKey(ModulePrefs.KEY_MODE)) {
                logW("remote file '$name' has no ${ModulePrefs.KEY_MODE}, skipping (parsed=$values)")
                continue
            }
            return ModulePrefs.fromValues(values)
        }
        return null
    }

    private fun readFromPreferences(m: XposedInterface): ModuleOptions? {
        val prefs = runCatching { m.getRemotePreferences(ModulePrefs.FILE) }
            .onFailure { logW("getRemotePreferences threw: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull()
        if (prefs == null) {
            logW("getRemotePreferences returned null")
            return null
        }
        return runCatching { ModulePrefs.read(prefs) }
            .onFailure { logW("remote preferences unreadable: ${it.message}") }
            .getOrNull()
    }

    /** SharedPreferences 的 XML 就是一串 `<类型 name="键" value="值" />`，把键值对抠出来即可 */
    private fun parsePrefsXml(input: InputStream): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val out = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val key = parser.getAttributeValue(null, "name")
                val value = parser.getAttributeValue(null, "value")
                if (key != null && value != null) out[key] = value
            }
            event = parser.next()
        }
        return out
    }

    fun logI(message: String) = Log.i(TAG, message)

    fun logW(message: String) = Log.w(TAG, message)

    fun logE(message: String, cause: Throwable) = Log.e(TAG, message, cause)
}
