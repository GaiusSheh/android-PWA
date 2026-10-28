package com.yuanfeng.webshell

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/**
 * 按配置向网页注入 CSS / JS（例如隐藏网站自带的顶栏、「下载 App」横幅）。
 *
 * 文件在构建时被拷贝到 assets/webshell/ 下（见 GenerateSiteResourcesTask），这里在启动时读一次缓存起来。
 * 只对 allowedHosts 内的页面注入，避免把脚本塞进第三方页面。
 */
class Injector(context: Context) {

    private val css: String? = readAsset(context, "webshell/inject.css")
    private val js: String? = readAsset(context, "webshell/inject.js")

    /**
     * 注入 CSS。会在「首屏可见」和「加载完成」时各调用一次：
     * 前者让样式尽早生效、减少顶栏闪一下的情况，后者兜底。用固定 id 保证同一页面只插一次。
     */
    fun injectCss(webView: WebView) {
        val css = css ?: return
        if (!ShellConfig.isAllowed(webView.url)) return
        val script = """
            (function() {
              var id = '__webshell_css';
              if (document.getElementById(id)) return;
              var style = document.createElement('style');
              style.id = id;
              style.textContent = ${JSONObject.quote(css)};
              (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * 页面加载完成（onPageFinished）时注入 JS。
     * onPageFinished 对同一个页面可能触发多次，用全局标记保证每个页面只执行一次。
     * 注意：单页应用（SPA）内部用 pushState 切换「页面」时不会触发 onPageFinished，脚本需要自己监听路由变化。
     */
    fun injectJs(webView: WebView) {
        val js = js ?: return
        if (!ShellConfig.isAllowed(webView.url)) return
        val script = "(function() {\n" +
            "if (window.__webshellJsInjected) return;\n" +
            "window.__webshellJsInjected = true;\n" +
            js + "\n})();"
        webView.evaluateJavascript(script, null)
    }

    private fun readAsset(context: Context, path: String): String? = try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: java.io.IOException) {
        null // 该 App 没配置此项
    }
}
