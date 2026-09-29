package com.example.dashcam.metadata

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 録画中の日時・GPS位置・速度を記録するクラス(方式3: 映像には焼き込まず別ファイルに保存)。
 *
 * 設計方針:
 * - GPSから1秒間隔でサンプリングし、セグメント単位でメモリ上のバッファに貯める
 * - セグメント保存完了のタイミングで drainSamples() を呼び出してバッファを取り出し、
 *   動画と同じ相対パス・同じベースファイル名(拡張子のみ.json)でMediaStoreに保存する
 * - 将来実装する書き出し機能(MediaCodec+OpenGLでの焼き込み)がこのJSONを読み込んで使う想定
 * - GPSの位置測位ができていない場合でも、1秒ごとのタイムスタンプ自体は記録し続ける
 *   (速度・位置はnullになるが、時系列の欠落を避けるため)
 */
class MetadataRecorder(private val context: Context) {

    data class Sample(
        val timestampMs: Long,
        val latitude: Double?,
        val longitude: Double?,
        val speedKmh: Float?
    )

    companion object {
        private const val TAG = "MetadataRecorder"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val LOCATION_UPDATE_INTERVAL_MS = 1000L

        private val ISO_FORMAT =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.JAPAN)
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var sampleRunnable: Runnable? = null

    @Volatile
    private var lastLocation: Location? = null

    private val samples = mutableListOf<Sample>()
    private val samplesLock = Any()

    @Volatile
    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastLocation = it }
        }
    }

    /** GPS追跡とサンプリングを開始する。呼び出し前にACCESS_FINE_LOCATIONの許可が必要。 */
    @SuppressLint("MissingPermission") // 呼び出し元でパーミッションチェック済みの前提
    fun start() {
        if (isTracking) return
        isTracking = true

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS
        ).build()
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, context.mainLooper
        )

        scheduleSampling()
        Log.i(TAG, "メタデータ記録を開始しました")
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        cancelSampling()
        Log.i(TAG, "メタデータ記録を停止しました")
    }

    private fun scheduleSampling() {
        cancelSampling()
        sampleRunnable = object : Runnable {
            override fun run() {
                if (!isTracking) return
                takeSample()
                sampleHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }
        sampleHandler.post(sampleRunnable!!)
    }

    private fun cancelSampling() {
        sampleRunnable?.let { sampleHandler.removeCallbacks(it) }
        sampleRunnable = null
    }

    private fun takeSample() {
        val location = lastLocation
        val speedKmh = if (location != null && location.hasSpeed()) {
            location.speed * 3.6f
        } else {
            null
        }

        val sample = Sample(
            timestampMs = System.currentTimeMillis(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            speedKmh = speedKmh
        )
        synchronized(samplesLock) {
            samples.add(sample)
        }
    }

    /** バッファ中のサンプルを取り出し、バッファをクリアする(セグメント境界で呼ぶ想定) */
    fun drainSamples(): List<Sample> {
        synchronized(samplesLock) {
            val copy = samples.toList()
            samples.clear()
            return copy
        }
    }

    /**
     * サンプル列をJSONとしてMediaStoreへ保存する。
     * videoDisplayName(例: "2026-09-18_143207.mp4")と同じベース名の .json として、
     * 動画と同じ相対パス(relativePath)に保存する。
     */
    fun saveSamplesToMediaStore(
        samples: List<Sample>,
        videoDisplayName: String,
        relativePath: String
    ) {
        if (samples.isEmpty()) return

        val jsonName = videoDisplayName.substringBeforeLast('.') + ".json"

        try {
            val jsonArray = JSONArray()
            samples.forEach { s ->
                val obj = JSONObject().apply {
                    put("timestamp", ISO_FORMAT.format(Date(s.timestampMs)))
                    put("timestamp_ms", s.timestampMs)
                    put("latitude", s.latitude?.let { it } ?: JSONObject.NULL)
                    put("longitude", s.longitude?.let { it } ?: JSONObject.NULL)
                    put("speed_kmh", s.speedKmh?.let { it } ?: JSONObject.NULL)
                }
                jsonArray.put(obj)
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, jsonName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }

            val itemUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )
            if (itemUri == null) {
                Log.e(TAG, "メタデータJSONの作成に失敗しました: $jsonName")
                return
            }

            context.contentResolver.openOutputStream(itemUri)?.use { output ->
                output.write(jsonArray.toString(2).toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "メタデータJSON保存完了: $jsonName (${samples.size}件)")
        } catch (e: Exception) {
            Log.e(TAG, "メタデータJSONの保存に失敗しました: $jsonName", e)
        }
    }
}
