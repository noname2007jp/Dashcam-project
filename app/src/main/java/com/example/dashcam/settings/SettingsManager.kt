package com.example.dashcam.settings

import android.content.Context
import android.net.Uri

/**
 * アプリ設定の永続化(SharedPreferences)。
 *
 * 現在保持する設定:
 * - 保存先フォルダ(SAFで選択したツリーURI)。未設定ならアプリ専用フォルダのみを使用する。
 */
class SettingsManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "dashcam_settings"
        private const val KEY_SAVE_LOCATION_URI = "save_location_uri"
    }

    /** SAFで選択した保存先フォルダのツリーURI。未設定ならnull。 */
    var saveLocationUri: Uri?
        get() = prefs.getString(KEY_SAVE_LOCATION_URI, null)?.let { Uri.parse(it) }
        set(value) {
            prefs.edit().putString(KEY_SAVE_LOCATION_URI, value?.toString()).apply()
        }

    fun clearSaveLocation() {
        prefs.edit().remove(KEY_SAVE_LOCATION_URI).apply()
    }
}
