package com.rustvnt.remote

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.concurrent.Executors

/** A manual check belongs to this Activity; never show results after it is destroyed. */
internal class ReleaseUpdateController(
    private val activity: AppCompatActivity,
    private val changed: () -> Unit,
) : DefaultLifecycleObserver {
    val currentVersion: String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "未知"
    var checking = false
        private set
    private val executor = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null

    init { activity.lifecycle.addObserver(this) }

    fun check() {
        if (checking || activity.isDestroyed) return
        checking = true
        changed()
        executor.execute {
            val result = runCatching { GitHubReleases.latest() }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                checking = false
                changed()
                result.fold(::showRelease) { error ->
                    showMessage("检查更新失败", if (error is IOException) error.message ?: "网络连接失败，请稍后重试。"
                        else "无法读取发布信息，请稍后重试。", true)
                }
            }
        }
    }

    private fun showRelease(release: AppRelease?) {
        if (release == null) {
            showMessage("检查更新", "仓库尚未发布正式版本。", true)
            return
        }
        val current = ReleaseVersion.parse(currentVersion)
        val latest = ReleaseVersion.parse(release.tag)!!
        if (current == null) {
            showMessage("无法比较版本", "当前版本 $currentVersion 无法识别，请前往发布页面查看。", true)
            return
        }
        if (latest <= current) {
            showMessage("检查更新", "当前已是最新版本（$currentVersion）。")
            return
        }
        val apk = release.apk(Build.SUPPORTED_ABIS.toList())
        val message = buildString {
            append("当前版本：$currentVersion\n最新版本：${release.tag}\n\n")
            append(release.notes.ifBlank { "此版本未提供更新说明。" })
            append(if (apk != null) "\n\n安装包：${apk.name}\n将通过浏览器下载，下载后请打开 APK 安装。"
                else "\n\n此版本尚无适配的 APK，请到发布页面查看。")
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本 ${release.tag}")
            .setMessage(message)
            .setNegativeButton("稍后", null)
            .setNeutralButton("发布页面") { _, _ -> open(GitHubReleases.PAGE + "/tag/" + Uri.encode(release.tag)) }
            .apply { if (apk != null) setPositiveButton("下载更新") { _, _ -> open(apk.url) } }
            .show()
    }

    private fun showMessage(title: String, message: String, releasesLink: Boolean = false) {
        dialog = MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
            .setPositiveButton("确定", null)
            .apply { if (releasesLink) setNeutralButton("发布页面") { _, _ -> open(GitHubReleases.PAGE) } }
            .show()
    }

    private fun open(url: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(activity, "没有可打开链接的浏览器", Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        executor.shutdownNow()
        dialog?.dismiss()
        dialog = null
    }
}
