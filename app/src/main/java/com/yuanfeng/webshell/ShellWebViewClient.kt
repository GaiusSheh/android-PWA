package com.yuanfeng.webshell

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebViewClient 负责「导航」层面的事件：链接点击、页面开始 / 完成、加载错误、渲染进程崩溃。
 * 具体的界面反应交给 [Host]（即 MainActivity）处理，这个类只做判断和转发。
 */
class ShellWebViewClient(private val host: Host) : WebViewClient() {

    interface Host {
        fun onPageStarted(url: String)
        /** 新页面的首帧已经绘制（比 onPageFinished 早，此时页面已可见） */
        fun onPageVisible(url: String)
        fun onPageFinished(url: String)
        fun onMainFrameError(failingUrl: String, description: CharSequence)
        fun onHistoryChanged()
        /** @param userInitiated 是否由用户点击触发；不是时先请用户确认，见 MainActivity.openExternally */
        fun openExternally(uri: Uri, userInitiated: Boolean)
        fun onRenderProcessGone(view: WebView)
    }

    /**
     * 是否处于第三方登录流程中（见 [AuthRules]）。流程中主框架的所有 http(s) 导航都留在 App 内，
     * 因为登录途中会经过事先无法列举的域名（Google 的地区域名、企业 SSO 页面等），任何一跳被交给
     * 浏览器，会话就断了。回到 allowedHosts 内的页面时结束。
     * MainActivity 负责在 Activity 重建时保存 / 恢复这个状态。
     */
    var authFlowActive = false

    /**
     * 当前主框架导航是否由用户点击发起。服务器重定向的请求不带点击标记（hasGesture 为 false），
     * 但「点链接 → 站内跳转地址 302 → 站外」（论坛外链、点击统计链接、「在 App 中打开」）仍是用户的操作，
     * 所以重定向沿用这条导航开头时的状态；新的非重定向导航（例如网页脚本自己改地址）会重新设置它。
     */
    private var mainFrameNavByGesture = false

    private fun userInitiated(request: WebResourceRequest): Boolean =
        request.hasGesture() || (request.isForMainFrame && request.isRedirect && mainFrameNavByGesture)

    /**
     * 每次网页要跳转到新地址时调用。返回 false = 让 WebView 自己加载；返回 true = 我们已处理。
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (request.isForMainFrame && !request.isRedirect) mainFrameNavByGesture = request.hasGesture()
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                // iframe（嵌入视频、验证码等）的导航一律放行
                if (!request.isForMainFrame) return false
                val allowed = ShellConfig.isAllowed(uri)
                when {
                    // 先判断登录入口：即使某个 App 的 allowedHosts 恰好包含登录域名，也要进入登录流程
                    AuthRules.isAuthEntry(uri.host, uri.path) -> {
                        val wasActive = authFlowActive
                        authFlowActive = true
                        if (request.isRedirect && !wasActive && !allowed) {
                            // 网站跳往登录页的这一跳由壳接管，改为新导航：有的网站表单 POST 后 303 到
                            // 登录页，会被网站自己的 CSP（form-action 'self'）拦下，表现为点按钮没反应
                            view.loadUrl(uri.toString())
                            true
                        } else {
                            false
                        }
                    }
                    allowed -> {
                        authFlowActive = false
                        false
                    }
                    authFlowActive -> false
                    else -> {
                        host.openExternally(uri, userInitiated(request))
                        true
                    }
                }
            }
            // 这些是网页内部用的伪协议，交给 WebView 自己处理
            "about", "data", "blob", "javascript" -> false
            // mailto: tel: sms: geo: intent: market: 等全部交给系统
            else -> {
                host.openExternally(uri, userInitiated(request))
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // 用返回键从登录页退回网站时不会经过 shouldOverrideUrlLoading，在这里结束登录流程
        if (authFlowActive && ShellConfig.isAllowed(url)) {
            val uri = Uri.parse(url)
            if (!AuthRules.isAuthEntry(uri.host, uri.path)) authFlowActive = false
        }
        host.onPageStarted(url)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        host.onPageVisible(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        host.onPageFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        host.onHistoryChanged()
    }

    /** 只关心主文档的错误（断网、DNS 失败、连接超时等）；图片、脚本等子资源失败不影响页面整体 */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            host.onMainFrameError(request.url.toString(), error.description)
        }
    }

    /**
     * WebView 的渲染进程被系统回收（内存不足）或崩溃。
     * 如果不处理（返回 false），整个 App 会跟着崩溃；返回 true 并重建 WebView 即可恢复。
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.onRenderProcessGone(view)
        return true
    }
}
