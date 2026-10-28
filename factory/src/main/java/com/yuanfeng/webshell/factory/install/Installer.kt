package com.yuanfeng.webshell.factory.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.yuanfeng.webshell.factory.R
import com.yuanfeng.webshell.factory.data.AppRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * 用系统 PackageInstaller 安装生成的 APK。
 *
 * 流程：创建安装会话 → 写入 APK → 提交。系统通过 [InstallResultReceiver] 回报结果：
 * 第一次安装会返回 STATUS_PENDING_USER_ACTION，需要我们把系统的确认界面弹出来让用户点「安装」；
 * Android 12+ 上更新「由本工厂安装的 App」时，系统通常允许免确认直接更新。
 */
object Installer {

    data class Result(val packageName: String, val success: Boolean, val message: String?)

    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 8)
    /** 安装结果事件流，编辑页订阅它来刷新按钮状态 */
    val results: SharedFlow<Result> = _results

    internal fun publish(result: Result) {
        _results.tryEmit(result)
    }

    /** 用户是否已允许本 App「安装未知应用」 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, apk: File, packageName: String, label: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .putExtra(InstallResultReceiver.EXTRA_PACKAGE, packageName)
                .putExtra(InstallResultReceiver.EXTRA_LABEL, label)
            // 系统要往这个 Intent 里填结果，所以必须是 MUTABLE
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** 接收 PackageInstaller 的安装结果 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 弹出系统的「是否安装」确认界面
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // 记下「已安装的是哪一版配置」，列表据此显示「有改动未生成」
                val repo = AppRepository(context)
                repo.all().firstOrNull { it.packageName == pkg }?.let { repo.save(it.copy(installedHash = it.contentHash())) }
                Toast.makeText(context, context.getString(R.string.install_success, label), Toast.LENGTH_SHORT).show()
                Installer.publish(Installer.Result(pkg, true, null))
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status=$status"
                // 用户在确认界面点了取消时 status = STATUS_FAILURE_ABORTED，不算错误，不弹提示
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, context.getString(R.string.install_failed, message), Toast.LENGTH_LONG).show()
                }
                Installer.publish(Installer.Result(pkg, false, message))
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "webshell.package"
        const val EXTRA_LABEL = "webshell.label"
    }
}
