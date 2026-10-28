package com.yuanfeng.webshell.factory.gen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 在手机上生成自适应图标（与 PC 端 buildSrc/IconRenderer.kt 的规则一致）。
 *
 * 自适应图标画布 108dp，启动器只显示中间约 72dp，中心 66dp 圆是安全区。三种模式：
 * - LOGO：图片四角透明（favicon / logo）→ 缩放进中心 50dp，背景 = 主题色
 * - FULL_BLEED：图片四角不透明（方形图标）→ 缩放到 76dp 铺满可见区，背景 = 图片边缘平均色
 * - LETTER：没有图片 → 主题色背景 + 名称首字（中文名就是第一个汉字）
 */
object IconRenderer {

    val SCALES: Map<String, Float> = linkedMapOf(
        "mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f,
    )

    private const val CANVAS_DP = 108f
    private const val VISIBLE_DP = 72f
    private const val LOGO_BOX_DP = 50f
    private const val FULL_BLEED_DP = 76f
    private const val LETTER_DP = 44f

    /** 一个图标的两层：背景色 + 前景绘制函数（参数是画布边长 px） */
    class Layers(val backgroundColor: Int, val drawForeground: (Canvas, Int) -> Unit)

    fun layers(source: Bitmap?, name: String, themeColor: Int): Layers {
        if (source == null) return letterLayers(name, themeColor)
        return if (hasTransparentCorners(source)) {
            Layers(themeColor) { c, size -> drawFitted(c, source, size, size * LOGO_BOX_DP / CANVAS_DP) }
        } else {
            Layers(averageBorderColor(source)) { c, size -> drawFitted(c, source, size, size * FULL_BLEED_DP / CANVAS_DP) }
        }
    }

    /** 各密度的前景 / 背景 PNG，供 ApkGenerator 写进 APK */
    fun pngs(layers: Layers): Pair<Map<String, ByteArray>, Map<String, ByteArray>> {
        val fg = linkedMapOf<String, ByteArray>()
        val bg = linkedMapOf<String, ByteArray>()
        for ((density, scale) in SCALES) {
            val size = (CANVAS_DP * scale).roundToInt()
            fg[density] = png(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                layers.drawForeground(Canvas(it), size)
            })
            bg[density] = png(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(layers.backgroundColor)
            })
        }
        return fg to bg
    }

    /** 界面预览：模拟启动器的圆形裁剪，只显示中间可见的 72dp 区域 */
    fun preview(layers: Layers, sizePx: Int): Bitmap {
        val full = (sizePx * CANVAS_DP / VISIBLE_DP).roundToInt()
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.clipPath(Path().apply { addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW) })
        val offset = (sizePx - full) / 2f
        c.translate(offset, offset)
        c.drawColor(layers.backgroundColor)
        layers.drawForeground(c, full)
        return out
    }

    fun hasTransparentCorners(b: Bitmap): Boolean {
        val w = b.width - 1
        val h = b.height - 1
        return listOf(0 to 0, w to 0, 0 to h, w to h).any { (x, y) -> Color.alpha(b.getPixel(x, y)) < 250 }
    }

    /**
     * 图标的主色：不透明、且不接近纯白 / 纯黑的像素的平均色，用于网页没声明 theme-color 时推荐主题色。
     * 例如 Hacker News 的橙色方块图标 → 橙色。找不到合适像素时返回 null。
     */
    fun dominantColor(b: Bitmap): Int? {
        val step = maxOf(1, minOf(b.width, b.height) / 48) // 抽样即可，不必遍历每个像素
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            val hsv = FloatArray(3).also { Color.colorToHSV(p, it) }
            if (hsv[2] < 0.15f || (hsv[1] < 0.12f && hsv[2] > 0.85f)) continue // 跳过近黑、近白
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        return if (n == 0L) null else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    fun averageBorderColor(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        fun add(x: Int, y: Int) {
            val p = b.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        for (x in 0 until b.width) { add(x, 0); add(x, b.height - 1) }
        for (y in 0 until b.height) { add(0, y); add(b.width - 1, y) }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    // ---- 内部实现 ----

    private fun letterLayers(name: String, themeColor: Int): Layers {
        val trimmed = name.trim()
        val text = if (trimmed.isEmpty()) "?" else String(Character.toChars(trimmed.codePointAt(0))).uppercase()
        val textColor = if (ColorUtils.calculateLuminance(themeColor) > 0.6) 0xFF202124.toInt() else Color.WHITE
        return Layers(themeColor) { c, size ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                typeface = Typeface.DEFAULT_BOLD
                textSize = size * LETTER_DP / CANVAS_DP
            }
            // 按字形实际边界居中，汉字和字母都能对正
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val x = (size - bounds.width()) / 2f - bounds.left
            val y = (size - bounds.height()) / 2f - bounds.top
            c.drawText(text, x, y, paint)
        }
    }

    /** 把图片等比缩放进 box×box，居中画在 size×size 的画布上 */
    private fun drawFitted(c: Canvas, src: Bitmap, size: Int, box: Float) {
        val ratio = min(box / src.width, box / src.height)
        val w = src.width * ratio
        val h = src.height * ratio
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        c.drawBitmap(src, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun png(b: Bitmap): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
