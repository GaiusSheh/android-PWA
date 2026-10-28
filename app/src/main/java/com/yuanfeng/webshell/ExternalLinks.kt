package com.yuanfeng.webshell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * 把不该在 App 内打开的链接交给系统处理：
 * - allowedHosts 以外的 http(s) 链接 → 系统默认浏览器（或已注册该域名的 App，例如 YouTube）
 * - mailto: / tel: / sms: / geo: / market: 等 → 对应的系统应用
 * - intent: → 解析成 Android Intent 启动目标 App；装不上时使用网页提供的 browser_fallback_url
 */
object ExternalLinks {
    private const val TAG = "ExternalLinks"

    /**
     * @param loadInApp 当 intent: 链接的 fallback 地址恰好在 allowedHosts 内时，用它在 App 内加载
     */
    fun open(activity: Activity, uri: Uri, loadInApp: (String) -> Unit) {
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            openIntentUri(activity, uri.toString(), loadInApp)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openIntentUri(activity: Activity, url: String, loadInApp: (String) -> Unit) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            Log.w(TAG, "无法解析 intent 链接：$url", e)
            return
        }
        // 安全措施：网页给出的 intent 只允许启动「可从浏览器启动」的页面，且不能指定具体组件，
        // 防止恶意网页借本 App 之手启动其它 App 的内部 Activity
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null

        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            when {
                fallback == null -> Toast.makeText(activity, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                ShellConfig.isAllowed(fallback) -> loadInApp(fallback)
                else -> open(activity, Uri.parse(fallback), loadInApp)
            }
        }
    }
}
