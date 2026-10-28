package com.yuanfeng.webshell.factory.core

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * 在电脑上验证「改写模板 APK + 签名」：用真实的模板 APK（:app:exportTemplateApk 的产物）
 * 生成一个新 App，再用 apksig 校验签名、解析 Manifest、检查对齐，并（如果找得到）用 SDK 的 aapt2 交叉验证。
 * 路径由 factory/build.gradle.kts 通过系统属性传入。
 */
class ApkGeneratorTest {

    companion object {
        private lateinit var template: File
        private lateinit var key: PrivateKey
        private lateinit var cert: X509Certificate
        private lateinit var tmp: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            template = File(requireNotNull(System.getProperty("webshell.templateApk")) { "缺少系统属性 webshell.templateApk" })
            val signing = File(requireNotNull(System.getProperty("webshell.signingDir")) { "缺少系统属性 webshell.signingDir" }, "signing")
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(signing, "key.pk8").readBytes()))
            cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(File(signing, "cert.der").inputStream()) as X509Certificate
            tmp = File(System.getProperty("java.io.tmpdir"), "webshell-test-${System.nanoTime()}").apply { mkdirs() }
        }

        private fun solidPng(size: Int, argb: Int): ByteArray {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            img.createGraphics().apply { color = Color(argb, true); fillRect(0, 0, size, size); dispose() }
            return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
    }

    private fun readManifest(apk: File): BinaryManifest = ZipFile(apk).use { zip ->
        BinaryManifest(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
    }

    @Test
    fun manifestRoundTripIsByteIdentical() {
        val original = ZipFile(template).use { it.getInputStream(it.getEntry("AndroidManifest.xml")).readBytes() }
        // 不做任何修改时，重新编码的结果应与 AAPT2 的输出逐字节相同，证明编码器正确
        assertArrayEquals(original, BinaryManifest(original).toByteArray())
    }

    @Test
    fun generatesSignedInstallableApk() {
        val sizes = mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432)
        val fg = sizes.mapValues { solidPng(it.value, 0xFFFF0000.toInt()) }
        val bg = sizes.mapValues { solidPng(it.value, 0xFF00FF00.toInt()) }
        val label = "测试 App & \"引号\" 'x' 😀"
        val config = SiteRules.configJson("http://192.168.1.2:8080/", listOf("192.168.1.2"), 0xFF123456.toInt(), "", true, true)
        val spec = AppSpec(
            packageName = "com.yuanfeng.webshell.unit_test",
            label = label,
            versionCode = 1234,
            versionName = "1.2.3",
            usesCleartext = true,
            configJson = config,
            injectCss = "body{}",
            injectJs = null,
            iconForeground = fg,
            iconBackground = bg,
        )
        val out = File(tmp, "out.apk")
        ApkGenerator.generate(template, spec, key, cert, out, tmp)

        // 1. 签名有效（v2 + v3），且证书就是我们给的
        val result = ApkVerifier.Builder(out).setMinCheckedPlatformVersion(26).build().verify()
        assertTrue("签名校验失败：${result.errors}", result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertEquals(cert, result.signerCertificates.single())

        // 2. Manifest 改写正确，且模板包名不再出现
        val m = readManifest(out)
        assertEquals("com.yuanfeng.webshell.unit_test", m.getPlainString("manifest", "package"))
        assertEquals(label, m.getString("application", BinaryManifest.ATTR_LABEL))
        assertEquals(1234, m.getInt("manifest", BinaryManifest.ATTR_VERSION_CODE))
        assertEquals("1.2.3", m.getString("manifest", BinaryManifest.ATTR_VERSION_NAME))
        assertEquals(-1, m.getInt("application", BinaryManifest.ATTR_USES_CLEARTEXT_TRAFFIC))
        assertTrue(m.strings.none { it.contains(ApkGenerator.TEMPLATE_PACKAGE) })
        assertTrue(m.strings.contains("com.yuanfeng.webshell.unit_test.fileprovider"))

        // 3. 文件替换 / 新增正确
        ZipFile(out).use { zip ->
            assertEquals(config, zip.getInputStream(zip.getEntry("assets/webshell/config.json")).readBytes().toString(Charsets.UTF_8))
            assertEquals("body{}", zip.getInputStream(zip.getEntry("assets/webshell/inject.css")).readBytes().toString(Charsets.UTF_8))
            assertNull(zip.getEntry("assets/webshell/inject.js"))
            for (d in ApkGenerator.DENSITIES) {
                assertArrayEquals(fg[d], zip.getInputStream(zip.getEntry(ApkGenerator.foregroundPath(d))).readBytes())
                assertArrayEquals(bg[d], zip.getInputStream(zip.getEntry(ApkGenerator.backgroundPath(d))).readBytes())
            }
        }

        // 4. 未压缩条目（尤其 resources.arsc）4 字节对齐，否则 Android 11+ 拒绝安装
        assertStoredEntriesAligned(out)

        // 5. 用 SDK 自带的 aapt2 交叉验证（与系统安装器使用同一套解析逻辑）
        aapt2()?.let { aapt2 ->
            val proc = ProcessBuilder(aapt2.path, "dump", "badging", out.path).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals("aapt2 失败：$text", 0, proc.waitFor())
            assertTrue(text, text.contains("package: name='com.yuanfeng.webshell.unit_test' versionCode='1234' versionName='1.2.3'"))
            // aapt2 在 Windows 上按控制台代码页输出、并把 " 转义成 \"，中文会乱码；这里只校验 ASCII 部分，
            // 中文内容的正确性已由上面 BinaryManifest 的解析断言保证
            assertTrue(text, text.contains("application-label:'") && text.contains(" App & \\\"") && text.contains("'x' "))
        }
    }

    @Test
    fun rejectsInvalidPackageName() {
        val spec = AppSpec("Bad.Name", "x", 1, "1", false, "{}", null, null, emptyMap(), emptyMap())
        val e = runCatching { ApkGenerator.generate(template, spec, key, cert, File(tmp, "bad.apk"), tmp) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    private fun aapt2(): File? {
        val sdk = System.getProperty("webshell.sdkDir") ?: return null
        return File(sdk, "build-tools").listFiles().orEmpty().sortedDescending()
            .map { File(it, if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "aapt2.exe" else "aapt2") }
            .firstOrNull { it.isFile }
    }

    /** 解析 zip 中央目录，检查每个 STORED 条目的数据起始偏移 */
    private fun assertStoredEntriesAligned(apk: File) {
        RandomAccessFile(apk, "r").use { f ->
            val tail = ByteArray(minOf(f.length(), 65_557L).toInt())
            f.seek(f.length() - tail.size); f.readFully(tail)
            val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val eocd = (tail.size - 22 downTo 0).first { t.getInt(it) == 0x06054b50 }
            val count = t.getShort(eocd + 10).toInt() and 0xFFFF
            val cdOffset = t.getInt(eocd + 16).toLong()

            f.seek(cdOffset)
            var stored = 0
            repeat(count) {
                val h = ByteArray(46); f.readFully(h)
                val c = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                val method = c.getShort(10).toInt()
                val nameLen = c.getShort(28).toInt() and 0xFFFF
                val extraLen = c.getShort(30).toInt() and 0xFFFF
                val commentLen = c.getShort(32).toInt() and 0xFFFF
                val lho = c.getInt(42).toLong() and 0xFFFFFFFFL
                val name = ByteArray(nameLen).also { f.readFully(it) }.toString(Charsets.UTF_8)
                f.skipBytes(extraLen + commentLen)
                if (method == 0) {
                    val back = f.filePointer
                    f.seek(lho)
                    val l = ByteArray(30); f.readFully(l)
                    val lb = ByteBuffer.wrap(l).order(ByteOrder.LITTLE_ENDIAN)
                    val dataOffset = lho + 30 + (lb.getShort(26).toInt() and 0xFFFF) + (lb.getShort(28).toInt() and 0xFFFF)
                    assertEquals("$name 的数据偏移 $dataOffset 未 4 字节对齐", 0L, dataOffset % 4)
                    stored++
                    f.seek(back)
                }
            }
            assertTrue("至少 resources.arsc 应该是未压缩条目", stored > 0)
        }
    }
}
