package webshell

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Properties

/** 一组签名参数：keystore 文件 + 密码 + 别名 */
data class SigningInfo(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    /** true = 正式 release keystore；false = 本机 debug keystore 兜底 */
    val isRelease: Boolean,
)

/**
 * 读取签名配置。优先 keystore.properties，其次环境变量 WEBSHELL_KEYSTORE_*；
 * 都没有时返回本机的 debug keystore（~/.android/debug.keystore）作为兜底。
 */
object Signing {

    fun load(rootDir: File, providers: ProviderFactory): SigningInfo {
        val props = Properties().apply {
            val f = File(rootDir, "keystore.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        fun value(propKey: String, envKey: String): String? =
            props.getProperty(propKey)?.takeIf { it.isNotBlank() }
                ?: providers.environmentVariable(envKey).orNull?.takeIf { it.isNotBlank() }

        val storeFile = value("storeFile", "WEBSHELL_KEYSTORE_FILE")?.let { path ->
            File(path).let { if (it.isAbsolute) it else File(rootDir, path) }
        }
        val storePassword = value("storePassword", "WEBSHELL_KEYSTORE_PASSWORD")
        val keyAlias = value("keyAlias", "WEBSHELL_KEY_ALIAS")
        val keyPassword = value("keyPassword", "WEBSHELL_KEY_PASSWORD")

        if (storeFile != null && storeFile.isFile && storePassword != null && keyAlias != null && keyPassword != null) {
            return SigningInfo(storeFile, storePassword, keyAlias, keyPassword, isRelease = true)
        }
        return debug()
    }

    /** Android SDK 约定的 debug keystore（AGP 第一次 debug 签名时自动创建） */
    fun debug() = SigningInfo(
        storeFile = File(System.getProperty("user.home"), ".android/debug.keystore"),
        storePassword = "android",
        keyAlias = "androiddebugkey",
        keyPassword = "android",
        isRelease = false,
    )
}

/**
 * 把签名私钥和证书导出成工厂 App 能直接读取的格式：
 *   key.pk8   PKCS#8 DER 私钥
 *   cert.der  X.509 DER 证书
 *
 * 工厂 App 在手机上用它给生成的 App 签名，这样：
 * - 手机生成的 App 和 PC 用 build.sh 构建的 App 签名相同，可以互相覆盖安装；
 * - 卸载重装工厂后，仍然能更新以前生成的 App。
 *
 * 代价：私钥随工厂 APK 分发。工厂 APK 只能自己用，不要分享给别人（详见 README）。
 */
abstract class ExportSigningKeyTask : DefaultTask() {

    @get:Input abstract val storePath: Property<String>
    @get:Input abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val keyPassword: Property<String>
    /** true = debug keystore 兜底（不存在时自动创建）。注意 Gradle 不允许 Property 属性名以 is 开头 */
    @get:Input abstract val debugKeystore: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun export() {
        val store = File(storePath.get())
        if (!store.isFile) {
            if (!debugKeystore.get()) throw GradleException("keystore 不存在：$store")
            createDebugKeystore(store)
        }

        val ks = loadKeyStore(store, storePassword.get().toCharArray())
        val key = ks.getKey(keyAlias.get(), keyPassword.get().toCharArray()) as? PrivateKey
            ?: throw GradleException("keystore $store 中没有别名为 ${keyAlias.get()} 的私钥")
        val cert = ks.getCertificate(keyAlias.get())
            ?: throw GradleException("keystore $store 中没有别名为 ${keyAlias.get()} 的证书")

        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        File(out, "signing").mkdirs()
        File(out, "signing/key.pk8").writeBytes(key.encoded)
        File(out, "signing/cert.der").writeBytes(cert.encoded)
    }

    /** JDK 9+ 的 keytool 默认生成 PKCS12，旧的可能是 JKS；两种都试 */
    private fun loadKeyStore(file: File, password: CharArray): KeyStore {
        var last: Exception? = null
        for (type in listOf("PKCS12", "JKS")) {
            try {
                return KeyStore.getInstance(type).apply { file.inputStream().use { load(it, password) } }
            } catch (e: Exception) {
                last = e
            }
        }
        throw GradleException("无法读取 keystore $file：${last?.message}", last)
    }

    /** 全新机器（如 CI）上 debug keystore 还没被 AGP 创建时，按 SDK 约定参数自己创建一个 */
    private fun createDebugKeystore(file: File) {
        file.parentFile.mkdirs()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val proc = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", file.path,
            "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
            "-dname", "CN=Android Debug,O=Android,C=US",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw GradleException("创建 debug keystore 失败：$output")
    }
}
