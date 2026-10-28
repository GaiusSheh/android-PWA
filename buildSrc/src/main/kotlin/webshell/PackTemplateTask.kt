package webshell

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * 把模板 APK 放进工厂 App 的 assets（template/webshell-template.apk），并在构建期检查
 * 工厂需要替换的条目都在固定路径上。
 *
 * 如果某次升级 AGP 后资源路径又被缩短（见 gradle.properties 的 android.enableResourceOptimizations），
 * 这里会直接让构建失败，而不是产出一个在手机上才报错的工厂 App。
 */
abstract class PackTemplateTask : DefaultTask() {

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val templateApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val apk = templateApk.get().asFile
        val required = buildList {
            add("AndroidManifest.xml")
            add("assets/webshell/config.json")
            for (density in IconRenderer.DENSITIES.keys) {
                add("res/mipmap-$density-v4/ic_launcher_foreground.png")
                add("res/mipmap-$density-v4/ic_launcher_background.png")
            }
        }
        ZipFile(apk).use { zip ->
            val missing = required.filter { zip.getEntry(it) == null }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "模板 APK 缺少工厂需要替换的条目 $missing。" +
                        "检查 gradle.properties 里 android.enableResourceOptimizations=false 是否仍然生效。"
                )
            }
        }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        apk.copyTo(File(out, "template/webshell-template.apk"), overwrite = true)
    }
}
