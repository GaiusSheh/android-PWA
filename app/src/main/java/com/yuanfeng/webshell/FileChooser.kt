package com.yuanfeng.webshell

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * 处理网页里的 <input type="file">。
 *
 * WebView 不会自己弹文件选择器，而是回调 WebChromeClient.onShowFileChooser，由 App 负责：
 * 1. 根据 accept 属性构造「选择文件」Intent（支持 multiple 多选）；
 * 2. 如果接受图片 / 视频，再附加「拍照」「录像」选项（相机 App 把结果写进我们通过 FileProvider 提供的临时文件）；
 * 3. 用户选完后，把结果 URI 数组回传给网页。
 *
 * 必须作为 Activity 的属性构造（registerForActivityResult 要求在 onCreate 之前注册）。
 */
class FileChooser(
    private val activity: ComponentActivity,
    private val permissions: PermissionBroker,
) {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    /** 本次提供给相机 App 的输出文件（拍照 / 录像各一个）；没被写入的会在结束时删除 */
    private val cameraOutputs = mutableListOf<Pair<File, Uri>>()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> deliver(result.resultCode, result.data) }

    /** 对应 onShowFileChooser 的返回值：返回 true 表示由我们接管，之后必须调用一次 callback */
    fun show(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        // 上一次的回调如果没完成，必须先取消，否则网页的文件选择会永久卡住
        pendingCallback?.onReceiveValue(null)
        pendingCallback = callback

        val mimeTypes = acceptedMimeTypes(params.acceptTypes)
        val wantsImage = mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" || it.startsWith("image/") }
        val wantsVideo = mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" || it.startsWith("video/") }

        if (wantsImage || wantsVideo) {
            // 本 App 在 Manifest 里声明了 CAMERA 权限（给网页 getUserMedia 用），
            // 按 Android 规定，此时调用系统相机也必须先获得 CAMERA 权限，否则会抛 SecurityException
            permissions.request(arrayOf(Manifest.permission.CAMERA)) { granted ->
                val cameraOk = Manifest.permission.CAMERA in granted
                launch(params, mimeTypes, cameraOk && wantsImage, cameraOk && wantsVideo)
            }
        } else {
            launch(params, mimeTypes, offerPhoto = false, offerVideo = false)
        }
        return true
    }

    private fun launch(
        params: WebChromeClient.FileChooserParams,
        mimeTypes: List<String>,
        offerPhoto: Boolean,
        offerVideo: Boolean,
    ) {
        cleanupCameraOutputs(keepWritten = false)
        val cameraIntents = buildList {
            if (offerPhoto) add(cameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, ".jpg"))
            if (offerVideo) add(cameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, ".mp4"))
        }

        // <input capture> 表示网页希望直接调起相机
        if (params.isCaptureEnabled && cameraIntents.isNotEmpty()) {
            startOrCancel(cameraIntents.first())
            return
        }

        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        val chooser = Intent.createChooser(pick, activity.getString(R.string.file_chooser_title)).apply {
            if (cameraIntents.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
        }
        startOrCancel(chooser)
    }

    private fun startOrCancel(intent: Intent) {
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            deliver(Activity.RESULT_CANCELED, null)
        }
    }

    /** 构造一个拍照 / 录像 Intent，输出写到 cache/camera/ 下的临时文件 */
    private fun cameraIntent(action: String, suffix: String): Intent {
        val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("capture_", suffix, dir)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        cameraOutputs += file to uri
        return Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // 通过 ClipData 携带 URI，读写授权才能随 Intent 传给相机 App（放在选择器里时尤其需要）
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun deliver(resultCode: Int, data: Intent?) {
        val callback = pendingCallback ?: return
        pendingCallback = null

        val uris: Array<Uri>? = if (resultCode == Activity.RESULT_OK) {
            val clip = data?.clipData
            val picked = when {
                // 多选：结果在 ClipData 里（排除我们自己放进去的相机输出 URI）
                clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    .filterNot { uri -> cameraOutputs.any { it.second == uri } }
                data?.data != null -> listOf(data.data!!)
                else -> emptyList()
            }
            // 选了文件就用文件；否则看相机有没有把照片 / 视频写进临时文件
            picked.ifEmpty { cameraOutputs.filter { it.first.length() > 0 }.map { it.second } }
                .takeIf { it.isNotEmpty() }?.toTypedArray()
        } else {
            null
        }
        cleanupCameraOutputs(keepWritten = uris != null)
        callback.onReceiveValue(uris)
    }

    private fun cleanupCameraOutputs(keepWritten: Boolean) {
        cameraOutputs.forEach { (file, _) -> if (!keepWritten || file.length() == 0L) file.delete() }
        cameraOutputs.clear()
    }

    // accept 属性可以是 MIME 类型（如 image/png、image/星号）也可以是扩展名（.pdf），统一转成 MIME 类型。
    // （这里不能写成 KDoc：Kotlin 块注释可嵌套，注释里出现斜杠星号会被当成新注释的开头）
    private fun acceptedMimeTypes(acceptTypes: Array<String>?): List<String> =
        acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { type ->
                if (type.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1)) else type
            }
            .distinct()
}
