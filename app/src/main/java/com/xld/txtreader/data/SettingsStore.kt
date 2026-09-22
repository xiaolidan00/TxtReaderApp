package com.xld.txtreader.data

import android.content.Context

class SettingsStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = sp.getFloat("font_size", 16f)
        set(value) { sp.edit().putFloat("font_size", value).apply() }

    var lineSpacingMult: Float
        get() = sp.getFloat("line_spacing", 2f)
        set(value) { sp.edit().putFloat("line_spacing", value).apply() }

    var textColorArgb: Long
        get() = sp.getLong("text_color", 0xFF333333)
        set(value) { sp.edit().putLong("text_color", value).apply() }

    var bgColorArgb: Long
        get() = sp.getLong("bg_color", 0xFFFFF9F0)
        set(value) { sp.edit().putLong("bg_color", value).apply() }

    var contentWidthPx: Int
        get() = sp.getInt("content_width", 0)
        set(value) { sp.edit().putInt("content_width", value).apply() }

    var contentHeightPx: Int
        get() = sp.getInt("content_height", 0)
        set(value) { sp.edit().putInt("content_height", value).apply() }

    var density: Float
        get() = sp.getFloat("density", 3f)
        set(value) { sp.edit().putFloat("density", value).apply() }

    var ttsSpeed: Float
        get() = sp.getFloat("tts_speed", 1f)
        set(value) { sp.edit().putFloat("tts_speed", value).apply() }

    var lastTtsPath: String
        get() = sp.getString("tts_last_path", "").orEmpty()
        set(value) { sp.edit().putString("tts_last_path", value).apply() }

    var bluetoothPermissionRequested: Boolean
        get() = sp.getBoolean("bluetooth_permission_requested", false)
        set(value) { sp.edit().putBoolean("bluetooth_permission_requested", value).apply() }
}
