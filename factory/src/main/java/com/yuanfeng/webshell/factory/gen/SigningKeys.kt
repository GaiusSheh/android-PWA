package com.yuanfeng.webshell.factory.gen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * 生成的 App 用哪把密钥签名。工厂有两种构建方式（见 factory/build.gradle.kts 的 webshell.publicFactory）：
 *
 * - **私有版**（./build.sh factory）：构建时从开发者的 keystore 导出私钥，打包在 assets/signing/。
 *   手机生成的 App 与电脑构建的 App 签名一致；代价是这个工厂 APK 等同私钥，不能外传。
 * - **公开版**（./build.sh factory-public）：APK 里没有私钥，可以放进 Release 给别人下载。
 *   第一次生成 App 时在这台手机的 AndroidKeyStore 里生成一把专用密钥，之后一直用它。
 *   AndroidKeyStore 的密钥不能导出：卸载工厂或清除工厂数据后密钥丢失，之前生成的 App 无法再覆盖更新。
 *
 * AndroidKeyStore 生成密钥对时会顺带生成自签名证书，所以不需要额外的证书库；
 * apksig 通过标准的 java.security.Signature 签名，能直接使用这把不可导出的私钥。
 */
object SigningKeys {
    private const val BUNDLED_KEY = "signing/key.pk8"
    private const val BUNDLED_CERT = "signing/cert.der"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val DEVICE_KEY_ALIAS = "webshell-app-signing"

    /** 是否是打包了开发者私钥的私有版工厂 */
    fun isBundled(context: Context): Boolean =
        context.assets.list("signing")?.contains("key.pk8") == true

    /** 公开版：这台手机上是否已经生成过设备密钥 */
    fun deviceKeyExists(): Boolean = deviceKeyStore().containsAlias(DEVICE_KEY_ALIAS)

    fun load(context: Context): Pair<PrivateKey, X509Certificate> =
        if (isBundled(context)) bundled(context) else device()

    /** 签名密钥由构建时从 keystore 导出（见 buildSrc/Signing.kt 的 ExportSigningKeyTask） */
    private fun bundled(context: Context): Pair<PrivateKey, X509Certificate> {
        val keyBytes = context.assets.open(BUNDLED_KEY).use { it.readBytes() }
        val algorithm = if (runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes)) }.isSuccess) "RSA" else "EC"
        val key = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = context.assets.open(BUNDLED_CERT).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return key to cert
    }

    private fun device(): Pair<PrivateKey, X509Certificate> {
        val store = deviceKeyStore()
        if (!store.containsAlias(DEVICE_KEY_ALIAS)) generateDeviceKey()
        val entry = store.getEntry(DEVICE_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("AndroidKeyStore 中的 $DEVICE_KEY_ALIAS 不是私钥条目")
        return entry.privateKey to entry.certificate as X509Certificate
    }

    private fun generateDeviceKey() {
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
        // apksig 对 RSA 密钥按 APK 大小选 SHA-256 或 SHA-512，两种摘要都要允许；APK 签名用 PKCS#1 v1.5 填充
        val spec = KeyGenParameterSpec.Builder(DEVICE_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            // 与开发者证书（也叫 CN=WebShell）区分开，排查签名问题时一眼能看出是哪种工厂生成的
            .setCertificateSubject(X500Principal("CN=WebShell device key"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    private fun deviceKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
