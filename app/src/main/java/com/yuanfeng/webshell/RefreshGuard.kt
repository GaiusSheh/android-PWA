package com.yuanfeng.webshell

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * 解决「下拉刷新」和网页内部滚动的冲突。
 *
 * SwipeRefreshLayout 判断「子 View 是否还能往上滚」来决定手指下拉时是滚动内容还是触发刷新。
 * 对 WebView 来说，webView.scrollY 只反映整个文档的滚动位置；
 * 但很多网页（尤其单页应用、聊天界面）是在某个 <div style="overflow:auto"> 里滚动的，
 * 此时文档本身 scrollY 一直是 0，直接下拉就会误触发刷新。
 *
 * 做法：注入一段 JS，在每次手指按下（touchstart）时，从被触摸的元素向上查找
 * 「可滚动且没有滚到顶」的祖先元素，把结果通过 JS 接口报告给原生侧。
 * touchstart 在手指真正拖动（超过系统 touch slop）之前就会到达，所以判定来得及。
 */
class RefreshGuard(private val webView: WebView) {

    @Volatile
    private var innerScrolled = false

    /** 由网页 JS 调用（运行在 WebView 的后台线程，所以字段用 @Volatile） */
    @JavascriptInterface
    fun report(scrolledInside: Boolean) {
        innerScrolled = scrolledInside
    }

    /** 给 SwipeRefreshLayout.setOnChildScrollUpCallback 用：返回 true 表示「内容还能往上滚，别刷新」 */
    fun canChildScrollUp(): Boolean = webView.scrollY > 0 || innerScrolled

    /** 每个页面都需要重新注入（页面跳转后 JS 环境是全新的） */
    fun install() {
        webView.evaluateJavascript(SCRIPT, null)
    }

    companion object {
        const val JS_NAME = "__webshellRefreshGuard"

        private val SCRIPT = """
            (function() {
              if (window.__webshellRefreshGuardInstalled) return;
              window.__webshellRefreshGuardInstalled = true;
              function scrolledInside(el) {
                for (; el && el.nodeType === 1 && el !== document.documentElement; el = el.parentElement) {
                  if (el.scrollTop > 0 && /(auto|scroll|overlay)/.test(getComputedStyle(el).overflowY)) {
                    return true;
                  }
                }
                return false;
              }
              document.addEventListener('touchstart', function(e) {
                try { $JS_NAME.report(scrolledInside(e.target)); } catch (_) {}
              }, { capture: true, passive: true });
            })();
        """.trimIndent()
    }
}
