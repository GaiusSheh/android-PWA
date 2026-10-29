package com.yuanfeng.webshell

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * WebChromeClient 负责「浏览器 UI」层面的事件：进度条、全屏视频、文件选择、权限请求等。
 * 浏览器里这些由 Chrome 的界面完成，在 WebView 里必须由 App 自己实现。
 */
class ShellChromeClient(
    private val host: Host,
    private val permissions: PermissionBroker,
    private val fileChooser: FileChooser,
) : WebChromeClient() {

    interface Host {
        fun onProgress(percent: Int)
        fun enterFullscreen(view: View, callback: CustomViewCallback)
        fun exitFullscreen()
        /** 网页要打开新窗口（window.open / target=_blank），见 [PopupWindows] */
        fun onCreateWindow(isUserGesture: Boolean, resultMsg: Message): Boolean
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        host.onProgress(newProgress)
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        host.onCreateWindow(isUserGesture, resultMsg)

    // ---- 视频全屏：网页请求全屏时，WebView 交给我们一个承载视频的 View，由我们铺满屏幕 ----

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.enterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.exitFullscreen()
    }

    /** 没有 poster 的 <video> 默认会显示一个灰色播放图标占位，返回透明图去掉它 */
    override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    // ---- 文件上传 ----

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = fileChooser.show(filePathCallback, fileChooserParams)

    // ---- 摄像头 / 麦克风（getUserMedia）：网页权限 → Android 运行时权限 ----

    override fun onPermissionRequest(request: PermissionRequest) {
        // 只对白名单内的网站开放设备权限
        if (!ShellConfig.isAllowed(request.origin)) {
            request.deny()
            return
        }
        val androidPermissions = mutableSetOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPermissions += Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPermissions += Manifest.permission.RECORD_AUDIO
            }
        }
        permissions.request(androidPermissions.toTypedArray()) { granted ->
            val grantable = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA in granted
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO in granted
                    // DRM 受保护内容（部分视频网站需要），不对应 Android 权限
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
                    else -> false
                }
            }
            if (grantable.isEmpty()) request.deny() else request.grant(grantable.toTypedArray())
        }
    }

    // ---- 定位（navigator.geolocation） ----

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        if (!ShellConfig.isAllowed(origin)) {
            callback.invoke(origin, false, false)
            return
        }
        val wanted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.request(wanted) { granted ->
            // 只给了粗略定位也算允许；retain=false：不在 WebView 内记住，每次以 Android 权限状态为准
            callback.invoke(origin, granted.isNotEmpty(), false)
        }
    }
}
