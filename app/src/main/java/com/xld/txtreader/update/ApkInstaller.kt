package com.xld.txtreader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装辅助：FileProvider 授权读取 + 「安装未知应用」权限跳转。
 */
object ApkInstaller {

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /** 是否已授予本应用「安装未知应用」权限（Android 8 以下默认允许） */
    fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「允许安装未知应用」设置页 */
    fun buildManageSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /** 构建安装 APK 的系统 intent（通过 FileProvider 授予读权限） */
    fun buildInstallIntent(context: Context, apkFile: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                ),
                APK_MIME_TYPE
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
