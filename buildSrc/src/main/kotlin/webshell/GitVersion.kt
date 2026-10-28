package webshell

import org.gradle.api.logging.Logging
import org.gradle.api.provider.ProviderFactory
import java.io.File

/**
 * 从 git 历史自动推导版本号。
 *
 * - versionCode = 当前分支的 commit 总数（git rev-list --count HEAD）。
 *   Android 要求覆盖安装时 versionCode 不能变小；只要不改写历史（force push / rebase 已发布的提交），
 *   每多一个 commit 它就 +1，天然单调递增。
 * - versionName：
 *     HEAD 正好打了 tag（如 v1.2.0）      → "1.2.0"
 *     HEAD 在某个 tag 之后                → "1.2.0-r57"（57 = versionCode）
 *     仓库里没有任何 tag                  → "r57"
 *
 * 注意：CI 里 checkout 必须拉全量历史（actions/checkout 的 fetch-depth: 0），
 * 否则浅克隆只有 1 个 commit，versionCode 会变成 1。
 */
object GitVersion {

    data class Info(val code: Int, val name: String)

    private val logger = Logging.getLogger(GitVersion::class.java)

    fun compute(providers: ProviderFactory, repoDir: File): Info {
        fun git(vararg args: String): String? = try {
            val out = providers.exec {
                commandLine("git", *args)
                workingDir(repoDir)
                isIgnoreExitValue = true
            }
            if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim().ifEmpty { null } else null
        } catch (e: Exception) {
            null // 没装 git 或不是 git 仓库
        }

        val count = git("rev-list", "--count", "HEAD")?.toIntOrNull()
        if (count == null) {
            logger.warn("[webshell] 无法从 git 读取 commit 数（不是 git 仓库或还没有提交），versionCode 暂用 1")
            return Info(code = 1, name = "0-nogit")
        }

        val exactTag = git("describe", "--tags", "--exact-match", "HEAD")
        val lastTag = git("describe", "--tags", "--abbrev=0", "HEAD")
        val name = when {
            exactTag != null -> exactTag.removePrefix("v")
            lastTag != null -> "${lastTag.removePrefix("v")}-r$count"
            else -> "r$count"
        }
        return Info(code = count, name = name)
    }
}
