package com.xld.txtreader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object SettingsUtil {

    fun openDefaultOpenerSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return true
        }
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        return runCatching {
            context.startActivity(fallback)
        }.isSuccess
    }
}