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
        private const val KEY_SHOCK_DRIVING_THRESHOLD = "shock_driving_threshold_g"
        private const val KEY_SHOCK_PARKING_THRESHOLD = "shock_parking_threshold_g"
        private const val KEY_TAILGATING_THRESHOLD = "tailgating_threshold_g"
        private const val KEY_PARKING_MOTION_RECORDING = "parking_motion_recording"
        private const val KEY_MAP_PROVIDER = "map_provider"
        private const val KEY_GOOGLE_MAPS_KEY = "google_maps_api_key"
        const val MAP_OSM = "osm"
        const val MAP_GOOGLE = "google"
        private const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
        private const val KEY_CONFIRM_ON_STOP = "confirm_on_stop"
        private const val KEY_SCREEN_TIMEOUT_SECONDS = "screen_timeout_seconds"

        // ShockDetector/TailgatingDetector側の初期値と合わせている
        const val DEFAULT_SHOCK_DRIVING_THRESHOLD_G = 0.5f
        const val DEFAULT_SHOCK_PARKING_THRESHOLD_G = 0.2f
        const val DEFAULT_TAILGATING_THRESHOLD_G = 0.3f
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

    /** 走行中の衝撃検知閾値(G)。0.1G刻みを想定。 */
    var shockDrivingThreshold: Float
        get() = prefs.getFloat(KEY_SHOCK_DRIVING_THRESHOLD, DEFAULT_SHOCK_DRIVING_THRESHOLD_G)
        set(value) {
            prefs.edit().putFloat(KEY_SHOCK_DRIVING_THRESHOLD, value).apply()
        }

    /** 駐車監視中の衝撃検知閾値(G)。0.1G刻みを想定。 */
    var shockParkingThreshold: Float
        get() = prefs.getFloat(KEY_SHOCK_PARKING_THRESHOLD, DEFAULT_SHOCK_PARKING_THRESHOLD_G)
        set(value) {
            prefs.edit().putFloat(KEY_SHOCK_PARKING_THRESHOLD, value).apply()
        }

    /** 急ブレーキ(煽り運転の可能性)検知閾値(G)。0.1G刻みを想定。 */
    var tailgatingThreshold: Float
        get() = prefs.getFloat(KEY_TAILGATING_THRESHOLD, DEFAULT_TAILGATING_THRESHOLD_G)
        set(value) {
            prefs.edit().putFloat(KEY_TAILGATING_THRESHOLD, value).apply()
        }

    /**
     * 駐車中の動体検知録画(通常は録画停止、動きを検知したときだけ録画し、
     * 動きが止まって15秒で停止)を有効にするか。無効の場合は駐車中も常時録画を続ける。既定: 有効
     */
    var parkingMotionRecordingEnabled: Boolean
        get() = prefs.getBoolean(KEY_PARKING_MOTION_RECORDING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PARKING_MOTION_RECORDING, value).apply()
        }

    /** 走行再生で使う地図。既定はOpenStreetMap。 */
    var mapProvider: String
        get() = prefs.getString(KEY_MAP_PROVIDER, MAP_OSM) ?: MAP_OSM
        set(value) {
            prefs.edit().putString(KEY_MAP_PROVIDER, value).apply()
        }

    /** Google Maps JavaScript API のキー(Google地図を選んだ場合のみ使用。端末内に保存)。 */
    var googleMapsApiKey: String
        get() = prefs.getString(KEY_GOOGLE_MAPS_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_GOOGLE_MAPS_KEY, value.trim()).apply()
        }

    /** アプリを最近使用したアプリ一覧から消したとき、録画も含めて全て停止するか(既定: 停止する)。 */
    var stopOnTaskRemoved: Boolean
        get() = prefs.getBoolean(KEY_STOP_ON_TASK_REMOVED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_STOP_ON_TASK_REMOVED, value).apply()
        }

    /** 「終了」ボタン押下時に確認ダイアログを出すか(既定: 出す)。 */
    var confirmOnStop: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_ON_STOP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CONFIRM_ON_STOP, value).apply()
        }

    /**
     * 画面を疑似消灯(輝度最小+黒オーバーレイ)するまでの無操作時間(秒)。
     * 未設定(null)の場合は消灯しない(常時点灯)。
     */
    var screenTimeoutSeconds: Int?
        get() = if (prefs.contains(KEY_SCREEN_TIMEOUT_SECONDS)) {
            prefs.getInt(KEY_SCREEN_TIMEOUT_SECONDS, 0)
        } else {
            null
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_SCREEN_TIMEOUT_SECONDS).apply()
            } else {
                prefs.edit().putInt(KEY_SCREEN_TIMEOUT_SECONDS, value).apply()
            }
        }
}
