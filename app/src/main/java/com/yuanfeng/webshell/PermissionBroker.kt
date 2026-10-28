package com.yuanfeng.webshell

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Android 运行时权限申请的统一入口。
 *
 * Android 6.0 起，摄像头、麦克风、定位等「危险权限」除了在 Manifest 里声明，
 * 还必须在运行时弹窗让用户确认。系统一次只能显示一个权限对话框，
 * 所以这里用队列把网页同时发起的多个请求（例如同时要摄像头和麦克风）串行处理。
 *
 * 必须在 Activity 的 onCreate 之前（即作为属性初始化）构造，因为 registerForActivityResult 有此要求。
 */
class PermissionBroker(private val activity: ComponentActivity) {

    private class Pending(val permissions: Array<String>, val callback: (Set<String>) -> Unit)

    private val queue = ArrayDeque<Pending>()
    private var inFlight: Pending? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val done = inFlight ?: return@registerForActivityResult
        inFlight = null
        // 不直接用对话框返回值，而是重新查一遍：用户可能只授予了其中一部分（如只给粗略定位）
        done.callback(granted(done.permissions))
        launchNext()
    }

    /**
     * 申请一组权限，回调参数是「最终被授予的那部分」（可能为空集合）。
     * 回调总在主线程执行。
     */
    fun request(permissions: Array<String>, callback: (granted: Set<String>) -> Unit) {
        if (permissions.all { isGranted(it) }) {
            callback(permissions.toSet())
            return
        }
        queue.addLast(Pending(permissions, callback))
        launchNext()
    }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun granted(permissions: Array<String>): Set<String> = permissions.filter { isGranted(it) }.toSet()

    private fun launchNext() {
        if (inFlight != null) return
        val next = queue.removeFirstOrNull() ?: return
        val missing = next.permissions.filterNot { isGranted(it) }
        if (missing.isEmpty()) {
            next.callback(next.permissions.toSet())
            launchNext()
            return
        }
        inFlight = next
        launcher.launch(missing.toTypedArray())
    }
}
