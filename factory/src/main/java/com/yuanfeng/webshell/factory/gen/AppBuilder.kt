package com.yuanfeng.webshell.factory.gen

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import com.yuanfeng.webshell.factory.BuildConfig
import com.yuanfeng.webshell.factory.core.ApkGenerator
import com.yuanfeng.webshell.factory.core.AppSpec
import com.yuanfeng.webshell.factory.core.SiteRules
import com.yuanfeng.webshell.factory.data.SiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 把一条 [SiteApp] 记录变成一个已签名的 APK 文件（Android 侧的胶水代码）：
 * 准备模板 APK 和签名密钥 → 渲染图标 → 生成 config.json → 调用 [ApkGenerator]。
 */
class AppBuilder(private val context: Context) {

    /**
     * @param icon 图标原图；app.letterIcon 为 true 时忽略
     * @return 生成的 APK（在 cache 目录，安装后可删除）
     */
    suspend fun build(app: SiteApp, icon: Bitmap?): File = withContext(Dispatchers.Default) {
        val layers = IconRenderer.layers(if (app.letterIcon) null else icon, app.name, app.themeColor)
        val (fg, bg) = IconRenderer.pngs(layers)

        // versionCode 必须比已安装的版本大，否则系统拒绝覆盖安装：
        // 以工厂（=模板）的版本号为基数，每生成一次 +1；工厂重装后记录丢失时，也保证大于已安装的版本
        val computed = BuildConfig.VERSION_CODE * 1000 + (app.revision + 1)
        val installed = installedVersionCode(context, app.packageName) ?: 0
        val versionCode = maxOf(computed, installed + 1)

        val spec = AppSpec(
            packageName = app.packageName,
            label = app.name,
            versionCode = versionCode,
            versionName = "${BuildConfig.VERSION_NAME}.${app.revision + 1}",
            usesCleartext = app.url.startsWith("http://", ignoreCase = true),
            configJson = SiteRules.configJson(
                url = app.url,
                allowedHosts = app.allowedHosts,
                themeColor = app.themeColor,
                userAgent = app.userAgent,
                enablePullToRefresh = app.pullToRefresh,
                forceDarkMode = app.forceDarkMode,
            ),
            injectCss = app.injectCss,
            injectJs = app.injectJs,
            iconForeground = fg,
            iconBackground = bg,
        )
        val (key, cert) = signingKey()
        val out = File(context.cacheDir, "generated/${app.id}.apk")
        ApkGenerator.generate(templateApk(), spec, key, cert, out, File(context.cacheDir, "work"))
        out
    }

    /**
     * 模板 APK 打包在 assets 里；ApkGenerator 需要随机读取（ZipFile），所以先拷贝到文件。
     * 文件名带上工厂的安装时间，工厂升级后自动换用新模板。
     */
    private fun templateApk(): File {
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.filesDir, "template")
        val file = File(dir, "template-${BuildConfig.VERSION_CODE}-$updated.apk")
        if (!file.isFile) {
            dir.deleteRecursively()
            dir.mkdirs()
            val tmp = File(dir, "copying.tmp")
            context.assets.open("template/webshell-template.apk").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            check(tmp.renameTo(file)) { "无法写入模板文件 $file" }
        }
        return file
    }

    /** 签名密钥由构建时从 keystore 导出（见 buildSrc/Signing.kt 的 ExportSigningKeyTask） */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val keyBytes = context.assets.open("signing/key.pk8").use { it.readBytes() }
        val algorithm = if (runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes)) }.isSuccess) "RSA" else "EC"
        val key = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = context.assets.open("signing/cert.der").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return key to cert
    }

    companion object {
        fun installedVersionCode(context: Context, packageName: String): Int? = try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
