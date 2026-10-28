package webshell

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/**
 * 为单个 App（flavor）生成专属的 Android 资源和 assets。
 *
 * 产物会通过 AGP 的 Variant API 挂到对应变体的 res / assets 源目录上（见 app/build.gradle.kts），
 * 效果等同于手写 app/src/<flavor>/res，但无需为每个网站维护一份目录。
 *
 * 生成内容：
 *   assets/webshell/config.json                         运行时配置（见 ShellConfig.kt）
 *   assets/webshell/inject.css|js                       需要注入的 CSS / JS（配置了才有）
 *   res/mipmap-anydpi/ic_launcher*.xml                  自适应图标描述（内容固定）
 *   res/mipmap-<密度>/ic_launcher_foreground.png        前景层
 *   res/mipmap-<密度>/ic_launcher_background.png        背景层（纯色或图片边缘色）
 *
 * 约束（为了让手机上的工厂 App 能直接改写 APK）：每个 App 的差异只能落在
 * assets 文件、图标 PNG 和 Manifest 字面值里，不能放进 resources.arsc（颜色 / 字符串资源）。
 * 工厂的 ApkGenerator 按 res/mipmap-<密度>-v4/ic_launcher_{foreground,background}.png 这个固定路径替换图标。
 */
@CacheableTask
abstract class GenerateSiteResourcesTask : DefaultTask() {

    @get:Input abstract val appId: Property<String>
    @get:Input abstract val appName: Property<String>
    @get:Input abstract val startUrl: Property<String>
    @get:Input abstract val allowedHosts: ListProperty<String>
    @get:Input abstract val themeColor: Property<Int>
    @get:Input abstract val userAgent: Property<String>
    @get:Input abstract val enablePullToRefresh: Property<Boolean>
    @get:Input abstract val forceDarkMode: Property<Boolean>

    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val iconFile: RegularFileProperty

    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val injectCss: RegularFileProperty

    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val injectJs: RegularFileProperty

    @get:OutputDirectory abstract val resOutputDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // CI 服务器没有显示器，java.awt 必须以无头模式运行
        System.setProperty("java.awt.headless", "true")

        val res = resOutputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val assets = assetsOutputDir.get().asFile.apply { deleteRecursively(); mkdirs() }

        // ---- 图标 ----
        val color = themeColor.get()
        val icon = iconFile.orNull?.asFile
        val rendered = if (icon != null) {
            IconRenderer.fromPng(icon, color)
        } else {
            IconRenderer.letter(appName.get(), appId.get(), color)
        }
        for ((density, scale) in IconRenderer.DENSITIES) {
            IconRenderer.writePng(rendered.foreground(scale), File(res, "mipmap-$density/ic_launcher_foreground.png"))
            IconRenderer.writePng(solid(rendered.backgroundColor, scale), File(res, "mipmap-$density/ic_launcher_background.png"))
        }
        File(res, "mipmap-anydpi").mkdirs()
        File(res, "mipmap-anydpi/ic_launcher.xml").writeText(ADAPTIVE_ICON_XML, Charsets.UTF_8)
        File(res, "mipmap-anydpi/ic_launcher_round.xml").writeText(ADAPTIVE_ICON_XML, Charsets.UTF_8)

        // ---- 运行时配置 ----
        val config = JsonObject().apply {
            addProperty("url", startUrl.get())
            add("allowedHosts", JsonArray().apply { allowedHosts.get().forEach { add(it) } })
            addProperty("themeColor", "#%08X".format(color))
            addProperty("userAgent", userAgent.get())
            addProperty("enablePullToRefresh", enablePullToRefresh.get())
            addProperty("forceDarkMode", forceDarkMode.get())
        }
        File(assets, "webshell").mkdirs()
        File(assets, "webshell/config.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(config), Charsets.UTF_8)
        injectCss.orNull?.asFile?.copyTo(File(assets, "webshell/inject.css"), overwrite = true)
        injectJs.orNull?.asFile?.copyTo(File(assets, "webshell/inject.js"), overwrite = true)
    }

    /** 背景层：与前景同尺寸（108dp）的纯色 PNG */
    private fun solid(argb: Int, scale: Double): BufferedImage {
        val size = Math.round(108 * scale).toInt()
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply {
            color = Color(argb, true)
            fillRect(0, 0, size, size)
            dispose()
        }
        return img
    }

    private companion object {
        // 前景、背景都是位图，便于工厂 App 直接替换文件（颜色资源在 resources.arsc 里，不便改写）
        val ADAPTIVE_ICON_XML = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            |    <background android:drawable="@mipmap/ic_launcher_background" />
            |    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
            |</adaptive-icon>
            |""".trimMargin()
    }
}
