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
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.iamcanincan.noticon.util.MemberLookup
import kotlin.math.abs

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

    /** 与底板色的 RGB 差之和超过这个值才算图形，只在整张图标都不透明时用得上 */
    private const val PLATE_DELTA = 90

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

        if (transparentCount > pixels.size / 20) {
            // 自带透明区域 → alpha 就是最准的遮罩，直接照搬
            for (i in pixels.indices) {
                if (Color.alpha(pixels[i]) >= MIN_SHAPE_ALPHA) outputPixels[i] = Color.WHITE
            }
        } else {
            // 整张不透明：拿四边的平均色当底板色，和它差得远的才是图形
            val plate = edgeAverage(pixels, w, h)
            val plateR = Color.red(plate)
            val plateG = Color.green(plate)
            val plateB = Color.blue(plate)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) continue
                val delta = abs(Color.red(pixel) - plateR) +
                        abs(Color.green(pixel) - plateG) +
                        abs(Color.blue(pixel) - plateB)
                if (delta > PLATE_DELTA) outputPixels[i] = Color.WHITE
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, w, 0, 0, w, h)
        return result
    }

    /** 采样四条边的平均色，当作不透明图标的底板色 */
    private fun edgeAverage(pixels: IntArray, w: Int, h: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        fun take(index: Int) {
            val pixel = pixels[index]
            if (Color.alpha(pixel) < MIN_SHAPE_ALPHA) return
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
            count++
        }
        for (x in 0 until w) {
            take(x)
            take((h - 1) * w + x)
        }
        for (y in 0 until h) {
            take(y * w)
            take(y * w + w - 1)
        }
        return if (count == 0) Color.BLACK else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
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
     */
    @SuppressLint("DiscouragedPrivateApi")
    fun applySmallIcon(icon: Icon, notification: Notification) {
        runCatching {
            val field = Notification::class.java.getDeclaredField("mSmallIcon")
            field.isAccessible = true
            field.set(notification, icon)
        }
    }

}
