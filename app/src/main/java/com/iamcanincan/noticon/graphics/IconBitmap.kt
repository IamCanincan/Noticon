package com.iamcanincan.noticon.graphics

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.core.content.res.ResourcesCompat
import com.iamcanincan.noticon.util.MemberLookup

/**
 * 图标位图的取与造。
 *
 * 这里只做像素层面的事：把各种形态的来源（Drawable / 资源 id / Icon）画成位图，
 * 以及做圆形裁切、转单色、合成底板。是否该替换由上层决定。
 */
object IconBitmap {

    private const val ICON_EDGE = 64

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

    /** 圆形裁切：radius 会被 Canvas 自动收敛到边长的一半，所以传边长即是正圆 */
    fun rounded(source: Bitmap, radiusPx: Int): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val rect = Rect(0, 0, source.width, source.height)
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = Color.DKGRAY
        canvas.drawRoundRect(RectF(rect), radiusPx.toFloat(), radiusPx.toFloat(), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, rect, rect, paint)
        return output
    }

    /**
     * 铺一层圆形底板再放上图标。
     * 启动图标本身大多是不透明的正圆/方圆角，底板通常看不见；
     * 它的作用是兜住那些带透明边缘的图标，免得直接飘在通知里。
     */
    fun onPlate(foreground: Bitmap, plateColor: Int, size: Int = ICON_EDGE): Bitmap {
        val plate = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        plate.eraseColor(plateColor)
        val output = rounded(plate, size)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val scaled = if (foreground.width == size && foreground.height == size) {
            foreground
        } else {
            Bitmap.createScaledBitmap(foreground, size, size, true)
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    /**
     * 把彩色图标压成单色，模拟「应用自己做了主题适配」的样子。
     *
     * 先取全图平均亮度当阈值，再看四边是否有留白或反色，据此决定保留暗部还是亮部；
     * 处理的是通知图标，尺寸很小，直接逐像素扫一遍即可。
     */
    fun monochrome(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        val outputPixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        var brightnessSum = 0
        var opaqueCount = 0
        for (pixel in pixels) {
            if (pixel != 0) {
                brightnessSum += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                opaqueCount++
            }
        }

        if (opaqueCount > 0) {
            val average = brightnessSum / opaqueCount
            val up = pixels[(w * 1.5).toInt()]
            val down = pixels[(w * h - w * 1.5).toInt()]
            val left = pixels[((h / 2) * w - w + 2)]
            val right = pixels[((h / 2) * w - 1)]

            // 四边都是亮色 → 图是「白底黑形」，需要反相；四边都是暗色 → 本来就有留白
            val needsInvert = isBrighter(up, average) && isBrighter(down, average) &&
                    isBrighter(left, average) && isBrighter(right, average)
            val hasPadding = isDarker(up, average) && isDarker(down, average) &&
                    isDarker(left, average) && isDarker(right, average)

            var keepDark: Boolean? = null
            for (i in pixels.indices) {
                val pixel = pixels[i]
                if (pixel == 0) continue
                val sum = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                val threshold = 3 * average

                if (hasPadding || needsInvert) {
                    if (keepDark == null) keepDark = sum > threshold
                    if (keepDark && sum <= threshold) outputPixels[i] = Color.WHITE
                    else if (!keepDark && sum > threshold) outputPixels[i] = Color.WHITE
                } else {
                    if (keepDark == null) keepDark = sum <= threshold
                    if (keepDark && sum <= threshold) outputPixels[i] = Color.WHITE
                    else if (!keepDark && sum > threshold) outputPixels[i] = Color.WHITE
                }
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, w, 0, 0, w, h)
        return result
    }

    /** 把各种载体的 Icon 还原成位图，取不到就返回 null 交给上层跳过 */
    fun decode(icon: Icon, context: Context): Bitmap? = try {
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
    } catch (_: Throwable) {
        null
    }

    /** Notification.mSmallIcon 是私有字段，没有公开 setter，只能反射写 */
    fun applySmallIcon(icon: Icon, notification: Notification) {
        runCatching {
            val field = Notification::class.java.getDeclaredField("mSmallIcon")
            field.isAccessible = true
            field.set(notification, icon)
        }
    }

    private fun isBrighter(color: Int, average: Int): Boolean {
        if (color == 0) return false
        return Color.red(color) + Color.green(color) + Color.blue(color) > 3 * average
    }

    private fun isDarker(color: Int, average: Int): Boolean {
        if (color == 0) return false
        return Color.red(color) + Color.green(color) + Color.blue(color) <= 3 * average
    }
}
