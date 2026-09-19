package com.iamcanincan.noticon.graphics

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.iamcanincan.noticon.util.MemberLookup

/**
 * 图标位图的取与造。
 *
 * 这里只做像素层面的事：把各种形态的来源（Drawable / 资源 id / Icon）画成位图，
 * 以及做圆形裁切、转单色、合成底板。是否该替换由上层决定。
 */
object IconBitmap {

    /**
     * 通知图标位图的边长。高密度屏上状态栏图标要 90px 往上，
     * 按这个尺寸出图，系统缩放时才不会糊成一团。
     */
    private const val ICON_EDGE = 96

    /** alpha 低于这个值就当透明，不算图形的一部分 —— 用来滤掉图标自带的半透明投影 */
    private const val MIN_SHAPE_ALPHA = 128

    /**
     * 不透明像素占比超过这个值，就认为整张是个实心块（圆形/方形实心图标）。
     * 这类图标的 alpha 只描述外轮廓，照搬 alpha 压出来就是一坨白块。
     */
    private const val SOLID_SHAPE_LIMIT = 0.7f

    /**
     * 把 Drawable 画成指定边长的方形位图。
     *
     * 关键点：显式调用 setBounds 而不是依赖 intrinsicWidth/Height。
     * 自适应图标（AdaptiveIconDrawable）的固有尺寸常常是 -1，
     * 依赖它就会算出非法尺寸、整条替换链路静默失效。
     */
    fun rasterize(drawable: Drawable, size: Int = ICON_EDGE): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /** TYPE_RESOURCE 类型的 Icon 需要用正确的 Resources 解码，不能走 Drawable 兜底 */
    fun fromResources(context: Context, resId: Int): Bitmap {
        val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
        return if (drawable != null) rasterize(drawable) else Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /**
     * 裁掉四周的空白边，再把内容放大填满。
     *
     * 自适应图标光栅化之后，内容只落在画布中间约 61% 的区域（safe zone），
     * 外面一圈是空的；直接拿去当通知图标，缩到状态栏那点尺寸就又小又糊，
     * 根本认不出是哪个应用。这里先按非透明像素求出内容边界裁出来，
     * 再等比放大填满目标尺寸。
     */
    fun fill(source: Bitmap, size: Int = ICON_EDGE): Bitmap {
        val bounds = contentBounds(source) ?: return scaleTo(source, size)
        val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height())
        return scaleTo(cropped, size)
    }

    private fun scaleTo(source: Bitmap, size: Int): Bitmap {
        return if (source.width == size && source.height == size) {
            source
        } else {
            Bitmap.createScaledBitmap(source, size, size, true)
        }
    }

    /**
     * 取桌面图标里真正代表图形的那一层。
     *
     * 自适应图标分成背景层和前景层：整张光栅化出来是一块不透明的彩色方块，
     * 拿去压单色就只剩一个白方块，什么信息都不剩。只有前景层是「透明底 + 图形」，
     * 用它的 alpha 才压得出有用的剪影。普通图标没有分层，直接用整张。
     */
    fun foregroundOf(drawable: Drawable, size: Int = ICON_EDGE): Bitmap {
        val source = if (drawable is AdaptiveIconDrawable) drawable.foreground else drawable
        return rasterize(source, size)
    }

    /** 非透明像素的外接矩形；整张都透明时返回 null */
    private fun contentBounds(source: Bitmap): Rect? {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (Color.alpha(pixels[row + x]) >= MIN_SHAPE_ALPHA) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * 把彩色图标压成系统风格的单色剪影：透明底 + 白色形状。
     *
     * 系统对这种图标的处理，和对「应用自己做好了主题适配」的完全一样 ——
     * 按主题统一着色、随深浅主题变化。所以交给系统的必须是纯 alpha 形状，
     * 不能是带颜色的位图，否则染出来就不对了。
     *
     * 形状优先取自 alpha 通道：绝大多数图标是透明底 + 图形，alpha 本身就是轮廓。
     * 整张都不透明时（自适应图标光栅化后常见）退化成「与四边底板色差异大的算图形」。
     */
    fun monochrome(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        val outputPixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var transparentCount = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) transparentCount++
        }

        val total = pixels.size
        val solidRatio = (total - transparentCount).toFloat() / total
        val useAlpha = transparentCount > total / 20 && solidRatio < SOLID_SHAPE_LIMIT
        if (useAlpha) {
            // 透明底上的图形：alpha 本身就是最准的轮廓
            for (i in pixels.indices) {
                if (Color.alpha(pixels[i]) >= MIN_SHAPE_ALPHA) outputPixels[i] = Color.WHITE
            }
        } else {
            // 实心图标：alpha 只描述外轮廓，照搬就是一坨白块，改用二值化挖图形
            binarize(pixels, outputPixels)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 去掉颜色、只留明暗。
     *
     * 灰度图三通道相等，正好命中系统「这个图标做过主题适配」的判据
     * （ToneCheck 用的是同一套标准），于是系统会按主题给它上色 ——
     * 深色主题下白、浅色主题下深，和原生适配过的图标一模一样。
     *
     * 为什么不压成纯 alpha 剪影：桌面图标是一整块不透明的彩色图形，
     * 二值化只会得到它的外轮廓（圆形图标就压成一个白圆），细节全丢。
     * 灰度化保留了明暗层次，缩到状态栏那点尺寸也认得出是哪个应用。
     */
    fun grayscale(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        val output = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = Color.alpha(pixel)
            if (alpha < MIN_SHAPE_ALPHA) continue
            val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            output[i] = Color.argb(alpha, gray, gray, gray)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 亮度二值化，给实心图标挖出里面的图形。
     *
     * 阈值不拍脑袋定常数，用 Otsu 自动求（让前后景的类间方差最大），
     * 各种配色的图标都能自适应。图形通常占比较少，据此决定留暗部还是亮部。
     */
    private fun binarize(pixels: IntArray, output: IntArray) {
        val threshold = otsuThreshold(pixels)
        var dark = 0
        var light = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) continue
            if (luma(pixel) < threshold) dark++ else light++
        }
        if (dark + light == 0) return
        val keepDark = dark <= light
        for (i in pixels.indices) {
            val pixel = pixels[i]
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) continue
            val isShape = if (keepDark) luma(pixel) < threshold else luma(pixel) >= threshold
            if (isShape) output[i] = Color.WHITE
        }
    }

    private fun luma(pixel: Int): Int {
        return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
    }

    /** Otsu：遍历所有可能阈值，取类间方差最大的那个 */
    private fun otsuThreshold(pixels: IntArray): Int {
        val histogram = IntArray(256)
        var total = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) continue
            histogram[luma(pixel)]++
            total++
        }
        if (total == 0) return 128

        var sum = 0
        for (i in 0..255) sum += i * histogram[i]

        var sumBackground = 0
        var weightBackground = 0
        var maxVariance = 0.0
        var threshold = 128
        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground *
                    (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }
        return threshold
    }

    /**
     * 把各种载体的 Icon 还原成位图，取不到就返回 null 交给上层跳过。
     *
     * getType()/getResId() 是 API 28 才公开的，Android 8.x 上只能走 loadDrawable 兜底，
     * 所以这里显式判版本而不是指望 catch —— 兜底路径同样能拿到图。
     */
    fun decode(icon: Icon, context: Context): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (icon.type) {
                Icon.TYPE_RESOURCE -> fromResources(context, icon.resId)
                Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> MemberLookup.readField(icon, "mObj1") as? Bitmap
                Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> {
                    val path = MemberLookup.readField(icon, "mString1") as? String
                    if (path != null) BitmapFactory.decodeFile(path) else null
                }
                Icon.TYPE_DATA -> {
                    val bytes = MemberLookup.readField(icon, "mObj1") as? ByteArray
                    val offset = MemberLookup.readField(icon, "mInt1") as? Int
                    val length = MemberLookup.readField(icon, "mInt2") as? Int
                    if (bytes != null && offset != null && length != null) {
                        BitmapFactory.decodeByteArray(bytes, offset, length)
                    } else {
                        null
                    }
                }
                else -> icon.loadDrawable(context)?.let { rasterize(it) }
            }
        } else {
            icon.loadDrawable(context)?.let { rasterize(it) }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Notification.mSmallIcon 是私有字段，没有公开 setter，只能反射写。
     * 这是本模块唯一一处「必须碰私有 API」的地方，没有替代方案，故抑制告警。
     *
     * 返回是否真的写进去了：某些定制系统改过这个字段，反射会失败。
     * 上层靠这个返回值决定要不要打 patched 日志，写失败就不该报成功 ——
     * patched 是排查时唯一的「真的换了」凭据，报假阳性会让排查走错方向。
     */
    @SuppressLint("DiscouragedPrivateApi")
    fun applySmallIcon(icon: Icon, notification: Notification): Boolean = runCatching {
        val field = Notification::class.java.getDeclaredField("mSmallIcon")
        field.isAccessible = true
        field.set(notification, icon)
        true
    }.getOrDefault(false)

}
