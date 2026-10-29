package com.yuanfeng.webshell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * 网页打开的新窗口（window.open、target=_blank）。
 *
 * 为什么要真的开新窗口：「用 Google 登录」等按钮常用弹窗模式，登录页在弹窗里完成后通过
 * window.opener 把结果传回原页面再关闭自己。如果把弹窗内容直接塞进主 WebView，opener 为空，登录永远完成不了。
 *
 * 但大多数新窗口并不是登录：站内的 target=_blank 链接、站外链接、广告。所以先用一个不显示的临时 WebView
 * 接住新窗口，等它的第一个真实地址出来再决定去向：
 * - 登录入口（[AuthRules]）→ 显示为盖住主页面的全屏弹窗，window.opener 可用
 * - 非用户点击触发 → 丢弃（多半是广告弹窗）
 * - allowedHosts 内 → 在主 WebView 打开（保持「站内链接留在当前页」的体验）
 * - 其它 → 交给系统浏览器
 *
 * 同一时间只处理一个新窗口；弹窗内部再开的窗口直接在弹窗里打开（弹窗不支持多窗口）。
 */
class PopupWindows(
    private val context: Context,
    private val mainWebView: WebView,
    private val container: View,
    private val holder: FrameLayout,
    private val title: TextView,
    private val host: Host,
) {

    interface Host {
        fun openExternally(uri: Uri, userInitiated: Boolean)
        /** 弹窗出现或关闭（MainActivity 据此更新返回键处理） */
        fun onPopupChanged()
    }

    /** 已接住、但还不知道第一个地址的临时 WebView */
    private var pending: WebView? = null
    /** 正在显示的登录弹窗 */
    private var popup: WebView? = null

    val isShowing: Boolean get() = popup != null

    /** 由 WebChromeClient.onCreateWindow 调用；返回 true 表示接管了这个新窗口 */
    fun create(isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (pending != null || popup != null) return false
        val child = WebView(context)
        configure(child)
        child.webViewClient = PopupClient(isUserGesture)
        child.webChromeClient = PopupChromeClient()
        pending = child
        (resultMsg.obj as WebView.WebViewTransport).webView = child
        resultMsg.sendToTarget()
        // 网页 window.open() 之后一直不导航（例如只往空白窗口里写内容），就当作没打开过
        child.postDelayed({ if (pending === child) discard(child) }, PROBE_TIMEOUT_MS)
        return true
    }

    /** 弹窗里能后退就后退，否则关闭弹窗 */
    fun back() {
        val view = popup ?: return
        if (view.canGoBack()) view.goBack() else close()
    }

    fun close() {
        val view = popup ?: return
        popup = null
        holder.removeView(view)
        container.isVisible = false
        destroyLater(view)
        host.onPopupChanged()
    }

    fun onResume() {
        popup?.onResume()
    }

    fun onPause() {
        popup?.onPause()
    }

    fun destroy() {
        pending?.let { it.stopLoading(); it.destroy() }
        pending = null
        popup?.let {
            holder.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        popup = null
    }

    /** 与主 WebView 保持一致的设置（JS、存储、UA、第三方 Cookie），否则登录页可能工作不正常 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(child: WebView) {
        child.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)    // 弹窗里再开的窗口就在弹窗内打开
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = mainWebView.settings.userAgentString
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(child.settings, ShellConfig.forceDarkMode)
        }
    }

    /**
     * 临时 WebView 拿到第一个真实地址时决定去向。
     * @return true = 这个窗口成为登录弹窗，继续加载；false = 已转交别处，调用方应停止加载
     */
    private fun route(view: WebView, uri: Uri, isUserGesture: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase()
        val isHttp = scheme == "http" || scheme == "https"
        pending = null
        when {
            isHttp && AuthRules.isAuthEntry(uri.host, uri.path) -> {
                show(view, uri)
                return true
            }
            !isUserGesture -> Unit // 非用户点击、也不是登录：丢弃
            isHttp && ShellConfig.isAllowed(uri) -> mainWebView.loadUrl(uri.toString())
            else -> host.openExternally(uri, userInitiated = true) // 走到这里时一定是用户点击
        }
        discard(view)
        return false
    }

    private fun show(view: WebView, uri: Uri) {
        popup = view
        title.text = uri.host
        holder.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        container.isVisible = true
        host.onPopupChanged()
    }

    private fun discard(view: WebView) {
        if (pending === view) pending = null
        destroyLater(view)
    }

    /** 这些回调常常就发生在被销毁的 WebView 自己的回调里，当场 destroy 会出问题，所以推迟到下一轮消息循环 */
    private fun destroyLater(view: WebView) {
        mainWebView.post {
            view.stopLoading()
            view.destroy()
        }
    }

    /** 不是真正的页面地址，继续等待下一次导航 */
    private fun isPlaceholder(uri: Uri) = uri.scheme?.lowercase() in setOf("about", "data", "blob", "javascript")

    private inner class PopupClient(private val isUserGesture: Boolean) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (popup === view) {
                // 登录弹窗内：http(s) 一律留在弹窗里（登录途中的各种中间页），其它协议交给系统
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || isPlaceholder(uri)) return false
                host.openExternally(uri, request.hasGesture())
                return true
            }
            if (pending !== view || !request.isForMainFrame || isPlaceholder(uri)) return false
            return !route(view, uri, isUserGesture)
        }

        /** 新窗口的第一个地址不一定经过 shouldOverrideUrlLoading，这里兜底 */
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (popup === view) {
                title.text = Uri.parse(url).host
                return
            }
            val uri = Uri.parse(url)
            if (pending !== view || isPlaceholder(uri)) return
            if (!route(view, uri, isUserGesture)) view.stopLoading()
        }
    }

    private inner class PopupChromeClient : WebChromeClient() {
        /** 网页调用 window.close()（登录完成后弹窗通常会自己关闭） */
        override fun onCloseWindow(window: WebView) {
            when {
                popup === window -> close()
                pending === window -> discard(window)
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5000L
    }
}
