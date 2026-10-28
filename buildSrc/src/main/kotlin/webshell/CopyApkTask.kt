package webshell

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 把 AGP 产出的 APK 拷贝到 dist/<name>-<version>.apk。
 *
 * apkDirectory 接的是 AGP 公开的 SingleArtifact.APK 目录（里面是 APK + output-metadata.json），
 * 这样无需关心 AGP 的内部输出路径，也自动获得「先 assemble 再拷贝」的任务依赖。
 */
abstract class CopyApkTask : DefaultTask() {

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun copy() {
        val apks = apkDirectory.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        if (apks.size != 1) {
            throw GradleException("期望 ${apkDirectory.get().asFile} 下恰好有 1 个 APK，实际 ${apks.size} 个：${apks.map { it.name }}")
        }
        val out = outputFile.get().asFile
        apks[0].copyTo(out, overwrite = true)
        logger.lifecycle("[webshell] -> ${out.absolutePath}")
    }
}
