package com.yuanfeng.webshell

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast

/**
 * 把网页里的下载交给系统 DownloadManager。
 *
 * DownloadManager 会「重新」请求一次下载地址（不经过 WebView），所以要手动带上：
 * - 该地址对应的 Cookie（否则需要登录才能下的文件会下载到登录页）
 * - 与 WebView 一致的 User-Agent 和 Referer
 * 文件名优先取服务器 Content-Disposition 头里的 filename* / filename（支持中文 RFC 5987 编码）。
 *
 * 文件保存在系统「下载」目录，通知栏显示进度，完成后可点击打开。
 */
class DownloadHandler(
    private val context: Context,
    private val webView: WebView,
    private val permissions: PermissionBroker,
) : DownloadListener {

    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // blob: / data: 链接的数据只存在于网页内存中，DownloadManager 无法获取（见 README 已知限制）
            Toast.makeText(context, R.string.download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 8~9：写公共 Download 目录需要存储权限
            permissions.request(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                if (granted.isNotEmpty()) {
                    enqueue(uri, url, userAgent, contentDisposition, mimetype)
                } else {
                    Toast.makeText(context, R.string.download_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            enqueue(uri, url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun enqueue(uri: Uri, url: String, userAgent: String?, contentDisposition: String?, mimetype: String?) {
        val fileName = sanitize(
            fileNameFromContentDisposition(contentDisposition)
                ?: URLUtil.guessFileName(url, contentDisposition, mimetype)
        )
        val mime = mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: mimetype

        try {
            val request = DownloadManager.Request(uri).apply {
                mime?.let { setMimeType(it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                webView.url?.let { addRequestHeader("Referer", it) }
                setTitle(fileName)
                setDescription(uri.host)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, context.getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val FILENAME_QUOTED = Regex("""filename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
        private val FILENAME_PLAIN = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)

        /**
         * 解析 Content-Disposition。系统自带的 URLUtil.guessFileName 不支持
         * filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf 这种 RFC 5987 写法，中文文件名会变成乱码或 downloadfile.bin。
         */
        fun fileNameFromContentDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            FILENAME_STAR.find(header)?.let { m ->
                val charset = m.groupValues[1].ifBlank { "UTF-8" }
                return try {
                    java.net.URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset)
                } catch (e: Exception) {
                    null
                }
            }
            FILENAME_QUOTED.find(header)?.let { return it.groupValues[1].replace(Regex("""\\(.)"""), "$1") }
            FILENAME_PLAIN.find(header)?.let { return it.groupValues[1] }
            return null
        }

        /** 去掉路径分隔符等非法字符，防止 ../ 之类的文件名写到下载目录之外 */
        private fun sanitize(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("""[\u0000-\u001f:*?"<>|]"""), "_")
                .trim().trimStart('.')
                .ifEmpty { "download" }
    }
}
