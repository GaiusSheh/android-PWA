package com.yuanfeng.webshell.factory.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yuanfeng.webshell.factory.core.ApkGenerator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 工厂里保存的一条 App 记录（对应一个生成的 App） */
data class SiteApp(
    /** 包名后缀；完整包名 = com.yuanfeng.webshell.<id>，安装后不能再改 */
    val id: String,
    val name: String,
    val url: String,
    val themeColor: Int,
    val allowedHosts: List<String>,
    val userAgent: String = "",
    val injectCss: String = "",
    val injectJs: String = "",
    val pullToRefresh: Boolean = true,
    val forceDarkMode: Boolean = false,
    /** true = 首字母图标；false = 使用 icons/<id>.png */
    val letterIcon: Boolean = true,
    /** 图标图片最后一次更换的时间，参与「是否有改动未生成」的判断 */
    val iconStamp: Long = 0,
    /** 已生成的次数，用于计算递增的 versionCode */
    val revision: Int = 0,
    /** 最近一次安装成功时的配置指纹；与当前指纹不同说明有改动还没生成 */
    val installedHash: Int = 0,
) {
    val packageName: String get() = ApkGenerator.PACKAGE_PREFIX + id

    /** 配置指纹：影响生成结果的字段 */
    fun contentHash(): Int = listOf(
        name, url, themeColor, allowedHosts, userAgent, injectCss, injectJs,
        pullToRefresh, forceDarkMode, letterIcon, iconStamp,
    ).hashCode()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("url", url); put("themeColor", themeColor)
        put("allowedHosts", JSONArray(allowedHosts)); put("userAgent", userAgent)
        put("injectCss", injectCss); put("injectJs", injectJs)
        put("pullToRefresh", pullToRefresh); put("forceDarkMode", forceDarkMode)
        put("letterIcon", letterIcon); put("iconStamp", iconStamp)
        put("revision", revision); put("installedHash", installedHash)
    }

    companion object {
        fun fromJson(o: JSONObject) = SiteApp(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            themeColor = o.getInt("themeColor"),
            allowedHosts = o.getJSONArray("allowedHosts").let { a -> (0 until a.length()).map { a.getString(it) } },
            userAgent = o.optString("userAgent"),
            injectCss = o.optString("injectCss"),
            injectJs = o.optString("injectJs"),
            pullToRefresh = o.optBoolean("pullToRefresh", true),
            forceDarkMode = o.optBoolean("forceDarkMode", false),
            letterIcon = o.optBoolean("letterIcon", true),
            iconStamp = o.optLong("iconStamp"),
            revision = o.optInt("revision"),
            installedHash = o.optInt("installedHash"),
        )
    }
}

/**
 * 记录的持久化：files/apps.json 存配置，files/icons/<id>.png 存图标原图。
 * 数据量很小（几十条），每次整体读写即可。所有方法都在主线程调用也没问题。
 */
class AppRepository(context: Context) {
    private val file = File(context.filesDir, "apps.json")
    private val iconDir = File(context.filesDir, "icons")

    fun all(): List<SiteApp> {
        if (!file.isFile) return emptyList()
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until arr.length()).map { SiteApp.fromJson(arr.getJSONObject(it)) }
    }

    fun get(id: String): SiteApp? = all().firstOrNull { it.id == id }

    /** 新增或按 id 覆盖 */
    fun save(app: SiteApp) {
        val list = all().filterNot { it.id == app.id } + app
        write(list)
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        iconFile(id).delete()
    }

    fun saveIcon(id: String, bitmap: Bitmap) {
        iconDir.mkdirs()
        iconFile(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun loadIcon(id: String): Bitmap? = iconFile(id).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    private fun iconFile(id: String) = File(iconDir, "$id.png")

    private fun write(list: List<SiteApp>) {
        // 先写临时文件再替换，避免写到一半进程被杀导致记录损坏
        val tmp = File(file.parentFile, "apps.json.tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
