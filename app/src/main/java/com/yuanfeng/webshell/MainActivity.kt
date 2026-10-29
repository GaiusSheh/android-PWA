package com.yuanfeng.webshell

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * 整个 App 唯一的界面：一个全屏 WebView 加若干原生体验补丁。
 *
 * Android 基础概念速查（对照 C++ 桌面程序）：
 * - Activity ≈ 一个顶层窗口。onCreate 相当于构造 / 初始化，onDestroy 相当于析构。
 * - 系统可能随时杀掉后台进程回收内存；用户再切回来时，系统用 onSaveInstanceState 存下的
 *   Bundle 重新创建 Activity。所以「进程被杀后恢复」= 在 onSaveInstanceState 里存 WebView 历史，
 *   在 onCreate 里 restoreState。
 * - 所有 UI 操作都必须在主线程。
 */
class MainActivity : AppCompatActivity(), ShellWebViewClient.Host, ShellChromeClient.Host, PopupWindows.Host {

    // 这两个对象内部调用了 registerForActivityResult，必须在 onCreate 之前构造（作为属性初始化）
    private val permissions = PermissionBroker(this)
    private val fileChooser = FileChooser(this, permissions)

    private lateinit var webView: WebView
    private lateinit var shellWebViewClient: ShellWebViewClient
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorDetail: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var injector: Injector
    private lateinit var popups: PopupWindows
    private var refreshGuard: RefreshGuard? = null

    /** 网页视频全屏时 WebView 交给我们的 View 和回调 */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** 「网页想打开其它应用」的确认框（见 openExternally） */
    private var externalConfirm: AlertDialog? = null

    /** 当前主文档是否加载失败（失败时显示原生错误页而不是 WebView 自带的错误页） */
    private var mainFrameFailed = false
    private var failedUrl: String? = null

    /** 启动页一直显示到首屏内容出来（或超时） */
    @Volatile
    private var firstContentReady = false

    /** 渲染进程挂掉后 WebView 已被销毁，生命周期回调里不能再碰它 */
    private var webViewGone = false
    private var urlBeforeRendererGone: String? = null

    /**
     * 返回键处理：全屏视频 → 退出全屏；登录弹窗 → 弹窗内后退或关闭弹窗；网页能后退 → 后退；
     * 都不是 → 不拦截，交给系统（退出 App）。
     * 只在「需要拦截」时启用这个回调，这样 Android 14+ 的预测性返回动画也能正确显示。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                popups.isShowing -> popups.back()
                webView.canGoBack() -> webView.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先读取 assets/webshell/config.json，后面所有逻辑都依赖它
        ShellConfig.init(this)
        // 必须在 super.onCreate 之前调用：接管 Android 12+ 系统启动页
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        setupSystemBars()
        setContentView(R.layout.activity_main)
        // 根布局背景 = themeColor，透过透明的系统栏露出来
        findViewById<View>(R.id.root).setBackgroundColor(ShellConfig.themeColor)

        webView = findViewById(R.id.web_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        errorDetail = findViewById(R.id.error_detail)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        findViewById<View>(R.id.error_retry).setOnClickListener { retry() }

        applyWindowInsets(findViewById(R.id.content))
        applyAccentColor()

        val splashDeadline = SystemClock.uptimeMillis() + SPLASH_MAX_MS
        splash.setKeepOnScreenCondition { !firstContentReady && SystemClock.uptimeMillis() < splashDeadline }

        setupWebView()
        popups = PopupWindows(
            context = this,
            mainWebView = webView,
            container = findViewById(R.id.popup_container),
            holder = findViewById(R.id.popup_web_holder),
            title = findViewById(R.id.popup_title),
            host = this,
        )
        findViewById<View>(R.id.popup_close).setOnClickListener { popups.close() }
        setupPullToRefresh()
        onBackPressedDispatcher.addCallback(this, backCallback)

        // 进程被杀 / 深色模式切换导致的重建：恢复 WebView 的历史栈和当前页面，以及是否正在登录
        shellWebViewClient.authFlowActive = savedInstanceState?.getBoolean(KEY_AUTH_FLOW) ?: false
        val restored = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)?.let { webView.restoreState(it) } != null
        if (!restored) {
            webView.loadUrl(savedInstanceState?.getString(KEY_LAST_URL) ?: ShellConfig.startUrl)
        }
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
     * Edge-to-edge：让 App 内容延伸到状态栏和导航栏下方，系统栏本身设为透明。
     * 根布局背景是 themeColor，透过透明系统栏露出来，就实现了「系统栏颜色 = themeColor」。
     * （Android 15+ 强制 edge-to-edge，旧的 window.statusBarColor 已无效，这是官方推荐做法。）
     */
    private fun setupSystemBars() {
        val lightBackground = ColorUtils.calculateLuminance(ShellConfig.themeColor) > 0.5
        // light 风格 = 深色图标（用于浅色背景）；dark 风格 = 浅色图标
        val style = if (lightBackground) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关掉系统给三键导航栏自动加的半透明遮罩，否则看不到 themeColor
            window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * 给内容区加上 padding，避开状态栏、导航栏、刘海和软键盘。
     * Edge-to-edge 模式下 windowSoftInputMode=adjustResize 不再自动生效，必须自己处理键盘(IME)高度，
     * 否则网页底部的输入框会被键盘挡住。
     */
    private fun applyWindowInsets(content: View) {
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    /** 进度条和下拉刷新圈使用 themeColor；themeColor 太浅（如白色）时换成灰色，否则看不见 */
    private fun applyAccentColor() {
        val accent = if (ColorUtils.calculateLuminance(ShellConfig.themeColor) > 0.85) {
            0xFF5F6368.toInt()
        } else {
            ShellConfig.themeColor
        }
        progress.progressTintList = ColorStateList.valueOf(accent)
        swipeRefresh.setColorSchemeColors(accent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        injector = Injector(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage / sessionStorage，大多数现代网站必需
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)     // target=_blank / window.open 交给 PopupWindows 分流
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true          // 允许双指缩放
            displayZoomControls = false         // 但不显示老式的 +/- 缩放按钮
            allowFileAccess = false             // 网页不能读取本地文件
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(true)
            ShellConfig.userAgent(this@MainActivity)?.let { userAgentString = it }
        }

        // Cookie 持久化：WebView 默认会把 Cookie 存到磁盘，这里额外允许第三方 Cookie
        // （很多登录流程、嵌入的评论区 / 支付页依赖它），并在 onPause 时 flush 确保落盘。
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // forceDarkMode：系统处于深色模式时，把本身不支持深色的网页也用算法压暗。
        // targetSdk >= 33 时，这个开关只在 App 主题为深色（DayNight 跟随系统）时生效，正好符合需求。
        // 网页若自己支持 prefers-color-scheme: dark，则优先使用网页自己的深色样式。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, ShellConfig.forceDarkMode)
        }

        // debug 版可以在电脑 Chrome 打开 chrome://inspect 调试网页
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        shellWebViewClient = ShellWebViewClient(this)
        webView.webViewClient = shellWebViewClient
        webView.webChromeClient = ShellChromeClient(this, permissions, fileChooser)
        webView.setDownloadListener(DownloadHandler(this, webView, permissions))
    }

    private fun setupPullToRefresh() {
        if (!ShellConfig.pullToRefresh) {
            swipeRefresh.isEnabled = false
            return
        }
        val guard = RefreshGuard(webView)
        refreshGuard = guard
        webView.addJavascriptInterface(guard, RefreshGuard.JS_NAME)
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> guard.canChildScrollUp() }
        swipeRefresh.setOnRefreshListener {
            if (mainFrameFailed) retry() else webView.reload()
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (!webViewGone) webView.onResume()
        popups.onResume()
    }

    override fun onPause() {
        if (!webViewGone) webView.onPause()
        popups.onPause()
        // 确保 Cookie（登录状态）写入磁盘；否则进程被杀时最近的 Cookie 可能丢失
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTH_FLOW, shellWebViewClient.authFlowActive)
        if (webViewGone) {
            urlBeforeRendererGone?.let { outState.putString(KEY_LAST_URL, it) }
            return
        }
        val state = Bundle()
        webView.saveState(state)
        outState.putBundle(KEY_WEBVIEW_STATE, state)
    }

    override fun onDestroy() {
        externalConfirm?.dismiss()
        popups.destroy()
        if (!webViewGone) {
            // WebView 持有大量 native 资源，Activity 销毁时必须先从布局里移除再 destroy，避免内存泄漏
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // ShellWebViewClient.Host
    // ------------------------------------------------------------------

    override fun onPageStarted(url: String) {
        mainFrameFailed = false
        progress.isVisible = true
    }

    override fun onPageVisible(url: String) {
        if (mainFrameFailed) return // WebView 自带错误页的首帧，忽略
        errorView.isVisible = false
        firstContentReady = true
        injector.injectCss(webView)
        refreshGuard?.install()
    }

    override fun onPageFinished(url: String) {
        swipeRefresh.isRefreshing = false
        progress.isVisible = false
        firstContentReady = true
        if (!mainFrameFailed) {
            errorView.isVisible = false
            injector.injectCss(webView)
            injector.injectJs(webView)
            refreshGuard?.install()
        }
        updateBackCallback()
    }

    override fun onMainFrameError(failingUrl: String, description: CharSequence) {
        mainFrameFailed = true
        failedUrl = failingUrl
        errorDetail.text = description
        errorView.isVisible = true
        swipeRefresh.isRefreshing = false
        firstContentReady = true
    }

    override fun onHistoryChanged() = updateBackCallback()

    /**
     * 不是用户点击触发的外部跳转（网页自动跳应用商店、广告拉起其它 App 等）先弹窗确认，和 Chrome 的做法一致。
     * 不直接丢弃：「点支付 → 后台下单 → 再拉起支付 App」这类正常流程中间隔了网络请求，WebView 也不认为是点击。
     * 同一时间只显示一个确认框，已有时忽略新的请求，避免被连续弹窗。
     */
    override fun openExternally(uri: Uri, userInitiated: Boolean) {
        val open = { ExternalLinks.open(this, uri) { url -> webView.loadUrl(url) } }
        if (userInitiated) {
            open()
            return
        }
        if (externalConfirm?.isShowing == true) return
        val isWeb = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        externalConfirm = AlertDialog.Builder(this)
            .setTitle(if (isWeb) R.string.external_confirm_browser else R.string.external_confirm_app)
            .setMessage(ExternalLinks.describe(uri))
            .setPositiveButton(R.string.external_confirm_open) { _, _ -> open() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onRenderProcessGone(view: WebView) {
        // 记下当前地址，销毁旧 WebView，然后重建整个 Activity（会走 onSaveInstanceState → onCreate）
        urlBeforeRendererGone = view.url
        webViewGone = true
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        recreate()
    }

    // ------------------------------------------------------------------
    // ShellChromeClient.Host
    // ------------------------------------------------------------------

    override fun onProgress(percent: Int) {
        progress.progress = percent
        progress.isVisible = percent < 100
    }

    override fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenContainer.isVisible = true
        // 隐藏状态栏和导航栏；从屏幕边缘滑动可临时呼出
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateBackCallback()
    }

    override fun exitFullscreen() {
        val view = customView ?: return
        // 先清空再回调：onCustomViewHidden 可能会反过来触发 onHideCustomView → exitFullscreen
        customView = null
        fullscreenContainer.removeView(view)
        fullscreenContainer.isVisible = false
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        updateBackCallback()
    }

    override fun onCreateWindow(isUserGesture: Boolean, resultMsg: Message): Boolean =
        popups.create(isUserGesture, resultMsg)

    // ------------------------------------------------------------------
    // PopupWindows.Host（openExternally 与 ShellWebViewClient.Host 共用同一个实现）
    // ------------------------------------------------------------------

    override fun onPopupChanged() = updateBackCallback()

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    private fun retry() {
        progress.isVisible = true
        val url = failedUrl
        if (url != null) webView.loadUrl(url) else webView.reload()
    }

    private fun updateBackCallback() {
        if (webViewGone) return
        backCallback.isEnabled = customView != null || popups.isShowing || webView.canGoBack()
    }

    private companion object {
        const val KEY_WEBVIEW_STATE = "webshell.webview_state"
        const val KEY_LAST_URL = "webshell.last_url"
        const val KEY_AUTH_FLOW = "webshell.auth_flow"
        /** 启动页最长显示时间：网页很慢时也不要一直卡在启动页 */
        const val SPLASH_MAX_MS = 2500L
    }
}
