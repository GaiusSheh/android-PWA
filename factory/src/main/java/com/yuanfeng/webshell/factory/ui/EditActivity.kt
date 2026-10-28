package com.yuanfeng.webshell.factory.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yuanfeng.webshell.factory.R
import com.yuanfeng.webshell.factory.core.SiteRules
import com.yuanfeng.webshell.factory.data.AppRepository
import com.yuanfeng.webshell.factory.data.SiteApp
import com.yuanfeng.webshell.factory.gen.AppBuilder
import com.yuanfeng.webshell.factory.gen.IconRenderer
import com.yuanfeng.webshell.factory.install.Installer
import com.yuanfeng.webshell.factory.web.BrowserFetcher
import com.yuanfeng.webshell.factory.web.SiteInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新建 / 编辑一个 App。
 *
 * 新建流程：输入网址 →「读取网页信息」自动填好名称、图标候选、主题色、白名单、包名后缀 →
 * 用户按需修改 →「生成并安装」。也可以从浏览器「分享」网址进来，此时自动读取。
 */
class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "webshell.id"

        /** 常用主题色，排在网页提取到的颜色后面 */
        private val PRESET_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF202124.toInt(), 0xFF1E88E5.toInt(), 0xFF3949AB.toInt(),
            0xFF8E24AA.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
            0xFF43A047.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
        )
    }

    private lateinit var repo: AppRepository

    /** 已保存的记录；新建且还没点过「生成」时为 null */
    private var existing: SiteApp? = null

    // ---- 图标状态 ----
    private val siteIcons = mutableListOf<Bitmap>()
    private var galleryIcon: Bitmap? = null
    /** 当前选中的图标；null 表示首字母图标 */
    private var selectedIcon: Bitmap? = null
    private var iconChanged = false

    // ---- 颜色状态 ----
    private var themeColor = Color.WHITE
    private var siteColors = listOf<Int>()
    private var settingColorText = false

    private var fetchJob: Job? = null

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlEdit: TextInputEditText
    private lateinit var fetchButton: MaterialButton
    private lateinit var fetchStatus: TextView
    private lateinit var fetchProgress: LinearProgressIndicator
    private lateinit var iconPreview: ImageView
    private lateinit var iconRow: LinearLayout
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameEdit: TextInputEditText
    private lateinit var colorSwatch: View
    private lateinit var colorLayout: TextInputLayout
    private lateinit var colorEdit: TextInputEditText
    private lateinit var colorRow: LinearLayout
    private lateinit var hostsEdit: TextInputEditText
    private lateinit var pullSwitch: MaterialSwitch
    private lateinit var darkSwitch: MaterialSwitch
    private lateinit var advanced: LinearLayout
    private lateinit var idLayout: TextInputLayout
    private lateinit var idEdit: TextInputEditText
    private lateinit var uaEdit: TextInputEditText
    private lateinit var cssEdit: TextInputEditText
    private lateinit var jsEdit: TextInputEditText
    private lateinit var buildButton: MaterialButton
    private lateinit var existingActions: LinearLayout
    private lateinit var openButton: MaterialButton
    private lateinit var uninstallButton: MaterialButton

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadGalleryImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        findViewById<View>(R.id.root).applySystemBarsPadding()
        repo = AppRepository(this)
        bindViews()

        existing = intent.getStringExtra(EXTRA_ID)?.let { repo.get(it) }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(if (existing != null) R.string.edit_title_existing else R.string.edit_title_new)
        toolbar.setNavigationOnClickListener { finish() }

        existing?.let { populate(it) } ?: run {
            pullSwitch.isChecked = true
            setColor(Color.WHITE)
        }
        setupListeners()

        // 从浏览器「分享」进来：取出网址并自动读取
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
            urlEdit.setText(url)
            fetch()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Installer.results.collect { result ->
                    if (result.packageName == existing?.packageName) {
                        existing = repo.get(existing!!.id) // 安装成功时接收器已更新记录
                        updateActions()
                    }
                }
            }
        }

        refreshColors()
        refreshIcons()
        updateActions()
    }

    override fun onResume() {
        super.onResume()
        updateActions() // 可能刚在系统里卸载了这个 App
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun bindViews() {
        urlLayout = findViewById(R.id.url_layout)
        urlEdit = findViewById(R.id.url)
        fetchButton = findViewById(R.id.fetch)
        fetchStatus = findViewById(R.id.fetch_status)
        fetchProgress = findViewById(R.id.fetch_progress)
        iconPreview = findViewById(R.id.icon_preview)
        iconRow = findViewById(R.id.icon_row)
        nameLayout = findViewById(R.id.name_layout)
        nameEdit = findViewById(R.id.name)
        colorSwatch = findViewById(R.id.color_swatch)
        colorLayout = findViewById(R.id.color_layout)
        colorEdit = findViewById(R.id.color)
        colorRow = findViewById(R.id.color_row)
        hostsEdit = findViewById(R.id.hosts)
        pullSwitch = findViewById(R.id.pull_to_refresh)
        darkSwitch = findViewById(R.id.force_dark)
        advanced = findViewById(R.id.advanced)
        idLayout = findViewById(R.id.id_layout)
        idEdit = findViewById(R.id.app_id)
        uaEdit = findViewById(R.id.user_agent)
        cssEdit = findViewById(R.id.inject_css)
        jsEdit = findViewById(R.id.inject_js)
        buildButton = findViewById(R.id.build)
        existingActions = findViewById(R.id.existing_actions)
        openButton = findViewById(R.id.open)
        uninstallButton = findViewById(R.id.uninstall)
    }

    private fun setupListeners() {
        fetchButton.setOnClickListener { fetch() }
        urlEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { fetch(); true } else false
        }
        nameEdit.doAfterTextChanged { if (selectedIcon == null) refreshIcons() }
        colorEdit.doAfterTextChanged { text ->
            if (settingColorText) return@doAfterTextChanged
            val c = SiteRules.parseCssColor(text?.toString()?.let { if (it.startsWith("#")) it else "#$it" })
            if (c != null) {
                colorLayout.error = null
                themeColor = c or 0xFF000000.toInt() // 系统栏颜色必须不透明
                updateSwatch()
                refreshIcons()
            }
        }
        idEdit.doAfterTextChanged { idLayout.helperText = getString(R.string.edit_id_helper, it?.toString().orEmpty()) }
        findViewById<MaterialButton>(R.id.advanced_toggle).setOnClickListener { advanced.isVisible = !advanced.isVisible }
        buildButton.setOnClickListener { onBuild() }
        openButton.setOnClickListener {
            existing?.let { app -> packageManager.getLaunchIntentForPackage(app.packageName)?.let(::startActivity) }
        }
        uninstallButton.setOnClickListener {
            existing?.let { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${it.packageName}"))) }
        }
        findViewById<MaterialButton>(R.id.delete).setOnClickListener {
            val app = existing ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.edit_delete_confirm)
                .setNegativeButton(R.string.edit_cancel, null)
                .setPositiveButton(R.string.edit_ok) { _, _ -> repo.delete(app.id); finish() }
                .show()
        }
    }

    private fun populate(app: SiteApp) {
        urlEdit.setText(app.url)
        nameEdit.setText(app.name)
        hostsEdit.setText(app.allowedHosts.joinToString(", "))
        pullSwitch.isChecked = app.pullToRefresh
        darkSwitch.isChecked = app.forceDarkMode
        idEdit.setText(app.id)
        uaEdit.setText(app.userAgent)
        cssEdit.setText(app.injectCss)
        jsEdit.setText(app.injectJs)
        if (!app.letterIcon) {
            repo.loadIcon(app.id)?.let { siteIcons += it; selectedIcon = it }
        }
        setColor(app.themeColor)
    }

    // ------------------------------------------------------------------
    // 读取网页信息
    // ------------------------------------------------------------------

    private fun fetch() {
        val url = SiteRules.normalizeUrl(urlEdit.text.toString()) ?: run {
            urlLayout.error = getString(R.string.error_url)
            return
        }
        urlLayout.error = null
        urlEdit.setText(url)
        val userAgent = WebSettings.getDefaultUserAgent(this) // 用 WebView 的 UA，拿到的是和 App 里一样的移动版网页

        fetchJob?.cancel()
        fetchProgress.isVisible = true
        fetchButton.isEnabled = false
        fetchStatus.setText(R.string.edit_fetching)
        // 下载 manifest / 图标时带上 WebView 的 Cookie：通过人机验证后拿到的凭据也在这里
        val inspector = SiteInspector(userAgent) { CookieManager.getInstance().getCookie(it) }
        fetchJob = lifecycleScope.launch {
            try {
                val result = when (val outcome = withContext(Dispatchers.IO) { inspector.inspect(url) }) {
                    is SiteInspector.Outcome.Ok -> outcome.result
                    is SiteInspector.Outcome.Challenge -> {
                        // 直接请求拿到的是人机验证页：改用 WebView 打开，像浏览器一样通过验证
                        fetchStatus.setText(R.string.edit_fetch_challenge)
                        val page = BrowserFetcher(this@EditActivity).fetch(outcome.url)
                        if (page == null) {
                            applyInspection(url, null)
                            fetchStatus.setText(R.string.edit_fetch_challenge_failed)
                            return@launch
                        }
                        withContext(Dispatchers.IO) { inspector.inspectHtml(page.html, page.url) }
                    }
                }
                applyInspection(url, result)
                fetchStatus.setText(R.string.edit_fetch_done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applyInspection(url, null)
                fetchStatus.text = getString(R.string.edit_fetch_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                fetchProgress.isVisible = false
                fetchButton.isEnabled = true
            }
        }
    }

    /** 用读取结果覆盖表单（用户主动点了「读取」，所以直接覆盖）；result 为 null 时只按网址推断 */
    private fun applyInspection(typedUrl: String, result: SiteInspector.Result?) {
        val finalUrl = result?.finalUrl ?: typedUrl
        val host = SiteRules.hostOf(finalUrl) ?: SiteRules.hostOf(typedUrl) ?: return
        urlEdit.setText(finalUrl)
        nameEdit.setText(result?.name ?: host.removePrefix("www."))

        siteIcons.clear()
        result?.icons?.take(6)?.forEach { siteIcons += it.bitmap }
        selectedIcon = siteIcons.firstOrNull()
        iconChanged = true

        // 网页没声明 theme-color 时，用图标的主色作为推荐
        val fromIcon = selectedIcon?.let { IconRenderer.dominantColor(it) }
        siteColors = (result?.colors.orEmpty() + listOfNotNull(fromIcon)).map { it or 0xFF000000.toInt() }.distinct()
        setColor(siteColors.firstOrNull() ?: Color.WHITE)

        val hosts = listOfNotNull(SiteRules.baseDomain(host), SiteRules.hostOf(typedUrl)?.let(SiteRules::baseDomain)).distinct()
        hostsEdit.setText(hosts.joinToString(", "))

        if (!isInstalled()) {
            val others = repo.all().map { it.id }.toSet() - setOfNotNull(existing?.id)
            idEdit.setText(SiteRules.suggestId(host, others))
        }
        refreshColors()
        refreshIcons()
    }

    // ------------------------------------------------------------------
    // 图标 / 颜色选择
    // ------------------------------------------------------------------

    private fun refreshIcons() {
        val name = nameEdit.text?.toString().orEmpty()
        iconPreview.setImageBitmap(IconRenderer.preview(IconRenderer.layers(selectedIcon, name, themeColor), dp(88)))

        iconRow.removeAllViews()
        addIconTile(null)
        (siteIcons + listOfNotNull(galleryIcon)).distinct().forEach { addIconTile(it) }
        iconRow.addView(tile(selected = false, onClick = {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            TextView(this).apply {
                setText(R.string.edit_icon_gallery)
                gravity = Gravity.CENTER
            }
        })
    }

    private fun addIconTile(bitmap: Bitmap?) {
        val name = nameEdit.text?.toString().orEmpty()
        iconRow.addView(tile(selected = selectedIcon === bitmap, onClick = {
            selectedIcon = bitmap
            iconChanged = true
            refreshIcons()
        }) {
            ImageView(this).apply {
                setImageBitmap(IconRenderer.preview(IconRenderer.layers(bitmap, name, themeColor), dp(48)))
                contentDescription = if (bitmap == null) getString(R.string.edit_icon_letter) else null
            }
        })
    }

    /** 一个 56dp 的可选卡片，选中时显示主题色描边 */
    private fun tile(selected: Boolean, onClick: () -> Unit, content: () -> View): View =
        MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(8) }
            radius = dp(12).toFloat()
            strokeWidth = if (selected) dp(3) else dp(1)
            strokeColor = if (selected) getColor(R.color.brand) else Color.LTGRAY
            cardElevation = 0f
            setOnClickListener { onClick() }
            addView(content(), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }

    private fun refreshColors() {
        colorRow.removeAllViews()
        (siteColors + PRESET_COLORS.toList()).distinct().forEach { c ->
            colorRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1), Color.GRAY)
                }
                setOnClickListener { setColor(c) }
            })
        }
    }

    private fun setColor(c: Int) {
        themeColor = c or 0xFF000000.toInt()
        settingColorText = true
        colorEdit.setText("#%06X".format(themeColor and 0xFFFFFF))
        settingColorText = false
        colorLayout.error = null
        updateSwatch()
        refreshIcons()
    }

    private fun updateSwatch() {
        colorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColor)
            setStroke(dp(1), Color.GRAY)
        }
    }

    private fun loadGalleryImage(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeScaled(uri, 512) } ?: return@launch
            galleryIcon = bitmap
            selectedIcon = bitmap
            iconChanged = true
            refreshIcons()
        }
    }

    /** 读取相册图片并缩小到最长边不超过 maxSize（图标用不到更大的分辨率） */
    private fun decodeScaled(uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
    }

    // ------------------------------------------------------------------
    // 生成并安装
    // ------------------------------------------------------------------

    private fun onBuild() {
        val app = collect() ?: return
        if (!Installer.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setNegativeButton(R.string.edit_cancel, null)
                .setPositiveButton(R.string.install_permission_go) { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }

        // 先保存记录（含图标原图）；包名后缀改了（仅限未安装时）就删掉旧记录
        selectedIcon?.let { repo.saveIcon(app.id, it) }
        repo.save(app)
        existing?.takeIf { it.id != app.id }?.let { repo.delete(it.id) }
        existing = app
        iconChanged = false
        updateActions()

        buildButton.isEnabled = false
        buildButton.setText(R.string.edit_building)
        lifecycleScope.launch {
            try {
                val apk = AppBuilder(this@EditActivity).build(app, selectedIcon)
                val updated = app.copy(revision = app.revision + 1)
                repo.save(updated)
                existing = updated
                withContext(Dispatchers.IO) { Installer.install(this@EditActivity, apk, app.packageName, app.name) }
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@EditActivity)
                    .setMessage(getString(R.string.build_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(R.string.edit_ok, null)
                    .show()
            } finally {
                buildButton.isEnabled = true
                buildButton.setText(R.string.edit_build_install)
            }
        }
    }

    /** 校验表单并组装成记录；有错误时在对应输入框显示并返回 null */
    private fun collect(): SiteApp? {
        val url = SiteRules.normalizeUrl(urlEdit.text.toString())
        urlLayout.error = if (url == null) getString(R.string.error_url) else null
        val name = nameEdit.text.toString().trim()
        nameLayout.error = if (name.isEmpty()) getString(R.string.error_name) else null
        val color = SiteRules.parseCssColor(colorEdit.text.toString().let { if (it.startsWith("#")) it else "#$it" })
        colorLayout.error = if (color == null) getString(R.string.error_color) else null
        if (url == null || name.isEmpty() || color == null) return null

        val host = SiteRules.hostOf(url)!!
        val others = repo.all().map { it.id }.toSet() - setOfNotNull(existing?.id)
        val id = idEdit.text.toString().trim().ifEmpty { SiteRules.suggestId(host, others) }
        val problem = SiteRules.idProblem(id)
        idLayout.error = when {
            problem != null -> getString(R.string.error_id, problem)
            id in others -> getString(R.string.error_id_taken)
            else -> null
        }
        if (idLayout.error != null) {
            advanced.isVisible = true
            return null
        }
        idEdit.setText(id)

        // 启动页所在域名必须在白名单内，否则一打开就跳浏览器
        var hosts = SiteRules.parseHosts(hostsEdit.text.toString())
        if (hosts.none { host == it || host.endsWith(".$it") }) hosts = hosts + SiteRules.baseDomain(host)
        hostsEdit.setText(hosts.joinToString(", "))

        val old = existing
        return SiteApp(
            id = id,
            name = name,
            url = url,
            themeColor = color or 0xFF000000.toInt(),
            allowedHosts = hosts,
            userAgent = uaEdit.text.toString().trim(),
            injectCss = cssEdit.text.toString(),
            injectJs = jsEdit.text.toString(),
            pullToRefresh = pullSwitch.isChecked,
            forceDarkMode = darkSwitch.isChecked,
            letterIcon = selectedIcon == null,
            iconStamp = if (iconChanged || old == null) System.currentTimeMillis() else old.iconStamp,
            revision = old?.revision ?: 0,
            installedHash = old?.installedHash ?: 0,
        )
    }

    private fun isInstalled(): Boolean =
        existing?.let { AppBuilder.installedVersionCode(this, it.packageName) != null } ?: false

    private fun updateActions() {
        val installed = isInstalled()
        existingActions.isVisible = existing != null
        openButton.isEnabled = installed
        uninstallButton.isEnabled = installed
        // 包名决定了「是不是同一个 App」，装上之后再改就成了另一个 App
        idEdit.isEnabled = !installed
        idLayout.helperText = getString(R.string.edit_id_helper, idEdit.text?.toString().orEmpty())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
