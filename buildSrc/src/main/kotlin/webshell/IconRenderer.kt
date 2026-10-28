package webshell

import org.gradle.api.GradleException
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 在构建期用 JDK 自带的 java.awt 生成 Android「自适应图标」(adaptive icon) 所需的位图。
 *
 * 自适应图标背景知识（Android 8.0+，本项目 minSdk 26 正好全覆盖）：
 * - 图标由「背景层」+「前景层」叠成，画布 108dp × 108dp。
 * - 启动器用各自的形状（圆形、圆角方形……）裁出中间约 72dp 的区域显示；
 *   中心直径 66dp 的圆是保证不会被裁掉的「安全区」。
 * - 所以前景素材必须四周留白，不能直接把一张方形 logo 拉满 108dp。
 *
 * 三种生成模式：
 * 1. LOGO：提供的 PNG 四角透明（典型的 favicon / logo）→ 等比缩放进中心 50dp 方框，背景用 themeColor。
 * 2. FULL_BLEED：提供的 PNG 四角不透明（本身就是一张方形图标）→ 缩放到 76dp 铺满可见区，
 *    背景用图片边缘平均色，避免启动器做视差动画时露出杂色。
 * 3. LETTER：没有提供图标 → themeColor 底色 + 名字首字母。
 */
object IconRenderer {

    /** Android 屏幕密度桶 → 相对 mdpi(1dp = 1px) 的倍率 */
    val DENSITIES: Map<String, Double> = linkedMapOf(
        "mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0,
    )

    private const val CANVAS_DP = 108.0
    private const val LOGO_BOX_DP = 50.0
    private const val FULL_BLEED_DP = 76.0
    private const val LETTER_DP = 44.0

    class Rendered(
        /** 背景层颜色 (ARGB) */
        val backgroundColor: Int,
        /** 按密度生成前景位图 */
        val foreground: (densityScale: Double) -> BufferedImage,
    )

    fun fromPng(file: File, themeColor: Int): Rendered {
        val src = ImageIO.read(file) ?: throw GradleException("[icon] 无法读取 PNG：${file.absolutePath}")
        val argb = toArgb(src)
        return if (hasTransparentCorners(argb)) {
            Rendered(themeColor) { scale ->
                drawCentered(argb, (CANVAS_DP * scale).roundToInt(), (LOGO_BOX_DP * scale).roundToInt())
            }
        } else {
            Rendered(averageBorderColor(argb)) { scale ->
                drawCentered(argb, (CANVAS_DP * scale).roundToInt(), (FULL_BLEED_DP * scale).roundToInt())
            }
        }
    }

    /**
     * 首字母图标。优先用 name 的第一个字符（中文名就是第一个汉字）；
     * 如果构建机字体里没有这个字（例如 Linux CI 上没装中文字体），退回到 id 的首字母。
     */
    fun letter(name: String, fallbackId: String, themeColor: Int): Rendered {
        val textColor = if (luminance(themeColor) > 0.6) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
        return Rendered(themeColor) { scale ->
            val size = (CANVAS_DP * scale).roundToInt()
            val font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((LETTER_DP * scale).toFloat())
            val first = name.trim().codePointAt(0)
            val text = if (font.canDisplay(first)) {
                String(Character.toChars(first)).uppercase()
            } else {
                fallbackId.take(1).uppercase()
            }

            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.font = font
            g.color = Color(textColor, true)
            // 用字形的实际可见边界居中（比 FontMetrics 的 ascent/descent 更准，汉字和拉丁字母都居中）
            val bounds = font.createGlyphVector(g.fontRenderContext, text).visualBounds
            val x = (size - bounds.width) / 2 - bounds.x
            val y = (size - bounds.height) / 2 - bounds.y
            g.drawString(text, x.toFloat(), y.toFloat())
            g.dispose()
            img
        }
    }

    fun writePng(img: BufferedImage, out: File) {
        out.parentFile.mkdirs()
        if (!ImageIO.write(img, "png", out)) throw GradleException("[icon] 写 PNG 失败：$out")
    }

    /** 相对亮度（0 = 黑，1 = 白），用于决定前景文字 / 状态栏图标用深色还是浅色 */
    fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    // ---- 内部实现 ----

    private fun toArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val copy = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        copy.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return copy
    }

    private fun hasTransparentCorners(img: BufferedImage): Boolean {
        val w = img.width - 1
        val h = img.height - 1
        return listOf(0 to 0, w to 0, 0 to h, w to h).any { (x, y) -> (img.getRGB(x, y) ushr 24) < 250 }
    }

    private fun averageBorderColor(img: BufferedImage): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        fun add(x: Int, y: Int) {
            val p = img.getRGB(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        for (x in 0 until img.width) { add(x, 0); add(x, img.height - 1) }
        for (y in 0 until img.height) { add(0, y); add(img.width - 1, y) }
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** 把 src 等比缩放进 box×box 方框，居中画在 canvas×canvas 的透明画布上 */
    private fun drawCentered(src: BufferedImage, canvas: Int, box: Int): BufferedImage {
        val ratio = min(box.toDouble() / src.width, box.toDouble() / src.height)
        val scaled = highQualityScale(src, max(1, (src.width * ratio).roundToInt()), max(1, (src.height * ratio).roundToInt()))
        val out = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            drawImage(scaled, (canvas - scaled.width) / 2, (canvas - scaled.height) / 2, null)
            dispose()
        }
        return out
    }

    /** 逐级减半缩小，避免一次性从 1024px 缩到 108px 产生锯齿 */
    private fun highQualityScale(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
        var current = src
        var w = src.width
        var h = src.height
        do {
            w = if (w > targetW) max(targetW, w / 2) else targetW
            h = if (h > targetH) max(targetH, h / 2) else targetH
            val next = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            next.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(current, 0, 0, w, h, null)
                dispose()
            }
            current = next
        } while (w != targetW || h != targetH)
        return current
    }
}
