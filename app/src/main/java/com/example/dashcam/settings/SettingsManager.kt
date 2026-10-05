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
        private const val KEY_PREFERRED_ZOOM_RATIO = "preferred_zoom_ratio"
        private const val KEY_MAX_LOOP_STORAGE_GB = "max_loop_storage_gb"

        // 検知閾値(G)。未設定時のデフォルト値は各Detectorの既定値と一致させる
        private const val KEY_SHOCK_DRIVING_G = "shock_driving_threshold_g"
        private const val KEY_SHOCK_PARKING_G = "shock_parking_threshold_g"
        private const val KEY_TAILGATING_G = "tailgating_braking_threshold_g"
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

    /** 選択されたレンズのズーム倍率(1.0未満なら超広角)。未設定(標準)ならnull。 */
    var preferredZoomRatio: Float?
        get() = if (prefs.contains(KEY_PREFERRED_ZOOM_RATIO)) {
            prefs.getFloat(KEY_PREFERRED_ZOOM_RATIO, 1.0f)
        } else {
            null
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_PREFERRED_ZOOM_RATIO).apply()
            } else {
                prefs.edit().putFloat(KEY_PREFERRED_ZOOM_RATIO, value).apply()
            }
        }

    /** ループ録画フォルダに使わせる容量上限(GB単位)。未設定(無制限)ならnull。 */
    var maxLoopStorageGb: Int?
        get() = if (prefs.contains(KEY_MAX_LOOP_STORAGE_GB)) {
            prefs.getInt(KEY_MAX_LOOP_STORAGE_GB, 0)
        } else {
            null
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_MAX_LOOP_STORAGE_GB).apply()
            } else {
                prefs.edit().putInt(KEY_MAX_LOOP_STORAGE_GB, value).apply()
            }
        }

    // ==== 検知閾値(0.1G刻みで調整可能。設定画面のスピナーから変更する) ====

    /** 衝撃検知: 走行中の閾値(G)。既定0.5G */
    var shockDrivingThresholdG: Float
        get() = prefs.getFloat(KEY_SHOCK_DRIVING_G, 0.5f)
        set(value) {
            prefs.edit().putFloat(KEY_SHOCK_DRIVING_G, value).apply()
        }

    /** 衝撃検知: 駐車監視中の閾値(G)。既定0.2G */
    var shockParkingThresholdG: Float
        get() = prefs.getFloat(KEY_SHOCK_PARKING_G, 0.2f)
        set(value) {
            prefs.edit().putFloat(KEY_SHOCK_PARKING_G, value).apply()
        }

    /** 煽り運転検知(急ブレーキ)の閾値(G)。既定0.3G */
    var tailgatingBrakingThresholdG: Float
        get() = prefs.getFloat(KEY_TAILGATING_G, 0.3f)
        set(value) {
            prefs.edit().putFloat(KEY_TAILGATING_G, value).apply()
        }
}
