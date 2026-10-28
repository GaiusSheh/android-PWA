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
        fun openExternally(uri: Uri)
        fun onRenderProcessGone(view: WebView)
    }

    /**
     * 每次网页要跳转到新地址时调用。返回 false = 让 WebView 自己加载；返回 true = 我们已处理。
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (ShellConfig.isAllowed(uri) || !request.isForMainFrame) {
                    // 白名单内的页面在 App 内打开；iframe（嵌入视频、验证码等）的导航也放行
                    false
                } else {
                    host.openExternally(uri)
                    true
                }
            }
            // 这些是网页内部用的伪协议，交给 WebView 自己处理
            "about", "data", "blob", "javascript" -> false
            // mailto: tel: sms: geo: intent: market: 等全部交给系统
            else -> {
                host.openExternally(uri)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
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
