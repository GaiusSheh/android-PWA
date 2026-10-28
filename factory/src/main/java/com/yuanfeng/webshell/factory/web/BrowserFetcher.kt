package com.yuanfeng.webshell.factory.web

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuanfeng.webshell.factory.R
import com.yuanfeng.webshell.factory.core.SiteRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 用真正的 WebView 打开网页，通过人机验证（Cloudflare「Just a moment...」等）后取回渲染好的 HTML。
 *
 * jsoup 不执行 JavaScript，过不了这类验证；WebView 会像浏览器一样执行验证脚本，
 * 自动验证通常几秒内通过。需要勾选的验证（Turnstile 复选框）则显示在对话框里由用户点一下。
 * 通过后 Cookie（cf_clearance）留在本 App 的 CookieManager 里，后续下载图标时 [SiteInspector] 会带上。
 *
 * 必须在主线程调用。
 */
class BrowserFetcher(private val activity: Activity) {

    class Page(val html: String, val url: String)

    /** @return 通过验证后的页面；用户取消或超时返回 null */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(url: String, timeoutMs: Long = 30_000): Page? {
        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient() // 所有跳转都留在这个 WebView 里（验证通过后会自动跳回原页面）
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) // 验证组件在第三方 iframe 里

        val dp = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(TextView(activity).apply { setText(R.string.challenge_message) })
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * dp).toInt()).apply {
                topMargin = (12 * dp).toInt()
            })
        }
        val cancelled = CompletableDeferred<Unit>()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.challenge_title)
            .setView(content)
            .setNegativeButton(R.string.edit_cancel) { _, _ -> cancelled.complete(Unit) }
            .setOnCancelListener { cancelled.complete(Unit) }
            .show()

        webView.loadUrl(url)
        try {
            return withTimeoutOrNull(timeoutMs) {
                // 每秒检查一次：页面加载完成、且标题不再是验证页，就说明已经进入网站本身
                while (!cancelled.isCompleted) {
                    delay(1000)
                    val state = webView.eval("JSON.stringify([document.readyState, document.title])") ?: continue
                    val arr = runCatching { org.json.JSONArray(state) }.getOrNull() ?: continue
                    val current = webView.url ?: continue
                    if (arr.optString(0) == "complete" && current.startsWith("http") && !SiteRules.isChallengeTitle(arr.optString(1))) {
                        val html = webView.eval("document.documentElement.outerHTML") ?: continue
                        return@withTimeoutOrNull Page(html, current)
                    }
                }
                null
            }
        } finally {
            dialog.dismiss()
            webView.destroy()
        }
    }

    /** 执行 JS 并把结果（JSON 字面量）解码成字符串 */
    private suspend fun WebView.eval(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { raw ->
            val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (cont.isActive) cont.resume(value)
        }
    }
}
