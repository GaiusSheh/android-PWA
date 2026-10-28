package com.yuanfeng.webshell.factory.core

import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 生成一个 App 所需的全部差异信息 */
data class AppSpec(
    /** 完整包名，如 com.yuanfeng.webshell.news_ycombinator */
    val packageName: String,
    /** 桌面上显示的名字 */
    val label: String,
    val versionCode: Int,
    val versionName: String,
    /** 启动页是 http:// 时为 true，允许明文流量 */
    val usesCleartext: Boolean,
    /** assets/webshell/config.json 的完整内容（见壳的 ShellConfig.kt） */
    val configJson: String,
    val injectCss: String?,
    val injectJs: String?,
    /** 密度名（mdpi…xxxhdpi）→ 自适应图标前景 PNG */
    val iconForeground: Map<String, ByteArray>,
    /** 密度名 → 自适应图标背景 PNG */
    val iconBackground: Map<String, ByteArray>,
)

/**
 * 改写模板 APK，生成一个新的、已签名的独立 App。
 *
 * 步骤：
 * 1. 改写二进制 Manifest：包名（及由它派生的 FileProvider authority、权限名）、名称、版本号、明文开关；
 * 2. 替换 assets/webshell/config.json、注入文件和图标 PNG，其余文件原样拷贝；
 * 3. 写出新 zip 时把未压缩条目按 4 字节对齐（Android 11+ 要求 resources.arsc 未压缩且对齐，否则拒绝安装）；
 * 4. 用 apksig（Google 官方签名库，就是 apksigner 的实现）做 v2 + v3 签名。
 *
 * 不依赖任何 Android API，可以在电脑上做单元测试。
 */
object ApkGenerator {

    /** 模板 APK 的包名；Manifest 中所有以它开头的字符串都会被替换成新包名 */
    const val TEMPLATE_PACKAGE = "com.yuanfeng.webshell.template"
    const val PACKAGE_PREFIX = "com.yuanfeng.webshell."

    val DENSITIES = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    private const val MANIFEST = "AndroidManifest.xml"
    private const val CONFIG = "assets/webshell/config.json"
    private const val INJECT_CSS = "assets/webshell/inject.css"
    private const val INJECT_JS = "assets/webshell/inject.js"

    private val PACKAGE_PATTERN = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")

    fun foregroundPath(density: String) = "res/mipmap-$density-v4/ic_launcher_foreground.png"
    fun backgroundPath(density: String) = "res/mipmap-$density-v4/ic_launcher_background.png"

    /**
     * @param template 模板 APK
     * @param out 输出的已签名 APK
     * @param workDir 存放中间文件（未签名 APK）的目录
     */
    fun generate(template: File, spec: AppSpec, key: PrivateKey, cert: X509Certificate, out: File, workDir: File) {
        require(PACKAGE_PATTERN.matches(spec.packageName)) { "包名不合法：${spec.packageName}" }
        require(spec.packageName != TEMPLATE_PACKAGE) { "不能使用模板自身的包名" }
        require(spec.label.isNotBlank()) { "名称不能为空" }
        require(spec.versionCode > 0) { "versionCode 必须为正数：${spec.versionCode}" }

        workDir.mkdirs()
        val unsigned = File(workDir, "unsigned-${spec.packageName}.apk")
        try {
            writeUnsigned(template, spec, unsigned)
            out.parentFile?.mkdirs()
            sign(unsigned, out, key, cert)
        } finally {
            unsigned.delete()
        }
    }

    private fun writeUnsigned(template: File, spec: AppSpec, unsigned: File) {
        ZipFile(template).use { zip ->
            // ---- 准备要替换 / 新增的条目 ----
            val manifest = BinaryManifest(zip.getInputStream(requireEntry(zip, MANIFEST)).use { it.readBytes() })
            val replaced = manifest.replacePrefix(TEMPLATE_PACKAGE, spec.packageName)
            check(replaced > 0) { "模板 Manifest 中找不到包名 $TEMPLATE_PACKAGE" }
            manifest.setString("application", BinaryManifest.ATTR_LABEL, spec.label)
            manifest.setInt("manifest", BinaryManifest.ATTR_VERSION_CODE, spec.versionCode)
            manifest.setString("manifest", BinaryManifest.ATTR_VERSION_NAME, spec.versionName)
            manifest.setBoolean("application", BinaryManifest.ATTR_USES_CLEARTEXT_TRAFFIC, spec.usesCleartext)

            val replacements = linkedMapOf<String, ByteArray>()
            replacements[MANIFEST] = manifest.toByteArray()
            replacements[CONFIG] = spec.configJson.toByteArray(Charsets.UTF_8)
            for (d in DENSITIES) {
                replacements[foregroundPath(d)] = requireNotNull(spec.iconForeground[d]) { "缺少 $d 前景图标" }
                replacements[backgroundPath(d)] = requireNotNull(spec.iconBackground[d]) { "缺少 $d 背景图标" }
                requireEntry(zip, foregroundPath(d))
                requireEntry(zip, backgroundPath(d))
            }
            requireEntry(zip, CONFIG)
            val additions = linkedMapOf<String, ByteArray>()
            spec.injectCss?.takeIf { it.isNotBlank() }?.let { additions[INJECT_CSS] = it.toByteArray(Charsets.UTF_8) }
            spec.injectJs?.takeIf { it.isNotBlank() }?.let { additions[INJECT_JS] = it.toByteArray(Charsets.UTF_8) }

            // ---- 写出新 zip ----
            CountingOutputStream(BufferedOutputStream(FileOutputStream(unsigned))).use { counter ->
                ZipOutputStream(counter).use { zos ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (isSignatureFile(name)) continue               // 旧签名丢弃，稍后重新签
                        if (name == INJECT_CSS || name == INJECT_JS) continue // 由 additions 决定是否存在
                        val data = replacements[name] ?: zip.getInputStream(entry).use { it.readBytes() }
                        writeEntry(zos, counter, name, data, stored = entry.method == ZipEntry.STORED)
                    }
                    for ((name, data) in additions) writeEntry(zos, counter, name, data, stored = false)
                }
            }
        }
    }

    private fun writeEntry(zos: ZipOutputStream, counter: CountingOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        entry.time = FIXED_TIME
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = CRC32().apply { update(data) }.value
            // 本地文件头 = 30 字节 + 文件名 + extra。用 extra 字段填充，使数据起始偏移是 4 的倍数（等同 zipalign）
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val base = counter.count + 30 + nameLen + ALIGN_EXTRA_MIN
            val pad = ((4 - base % 4) % 4).toInt()
            entry.extra = alignmentExtra(pad)
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    /** Android 约定的对齐 extra 字段（ID 0xD935）：[ID][长度][对齐值][填充 0...] */
    private const val ALIGN_EXTRA_MIN = 6

    private fun alignmentExtra(pad: Int): ByteArray {
        val total = ALIGN_EXTRA_MIN + pad
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD935.toShort())
            .putShort((total - 4).toShort())
            .putShort(4)
            .array()
    }

    private fun sign(input: File, output: File, key: PrivateKey, cert: X509Certificate) {
        @Suppress("DEPRECATION")
        val signer = ApkSigner.SignerConfig.Builder("WEBSHELL", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(false) // minSdk 26 只需要 v2 / v3
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun requireEntry(zip: ZipFile, name: String): ZipEntry =
        zip.getEntry(name) ?: throw IllegalStateException("模板 APK 缺少条目 $name")

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" || upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    /** 1981-01-01，与 AGP 打包时使用的固定时间一致，保证在 DOS 时间范围内（不会生成额外的时间戳 extra） */
    private const val FIXED_TIME = 347155200000L

    /** 统计写出字节数，用来计算每个条目在 zip 中的偏移 */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }
    }
}
