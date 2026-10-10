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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 録画中の日時・GPS位置・速度を記録するクラス(方式3: 映像には焼き込まず別ファイルに保存)。
 *
 * 設計方針:
 * - GPSから1秒間隔でサンプリングし、そのたびにローカルの一時ファイルへ逐次追記する
 *   (JSON Lines形式: 1行につき1サンプルのJSONオブジェクト)。
 *   メモリ上にため込んでセグメント完了時にまとめて書き出す方式だと、アプリ強制終了時に
 *   最大SEGMENT_DURATION_MS分のデータが失われるため、1秒ごとにディスクへ確実に書き込む。
 * - セグメント開始時(DashcamRecorder.Listener.onSegmentStarted)に新しい一時ファイルを用意し、
 *   セグメント完了時(onSegmentSaved/onProtectedSegmentSaved)にMediaStoreへアップロードして
 *   一時ファイルを削除する
 * - GPSの測位ができていない場合でも、1秒ごとのタイムスタンプ自体は記録し続ける
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
        private const val TMP_DIR_NAME = "metadata_tmp"
        private const val LOOP_PATH = "Download/cam/dashcam_loop/"
        private const val PROTECTED_PATH = "Download/cam/dashcam_protected/"

        private val ISO_FORMAT =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.JAPAN)
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var sampleRunnable: Runnable? = null

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var isTracking = false

    private val tmpDir: File by lazy {
        File(context.cacheDir, TMP_DIR_NAME).apply { mkdirs() }
    }

    // 現在のセグメントに対応する一時ファイル(逐次追記先)
    @Volatile
    private var currentSegmentFile: File? = null

    @Volatile
    private var currentSegmentName: String? = null

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
        Thread { recoverOrphanedFiles() }.start()
        Log.i(TAG, "メタデータ記録を開始しました")
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        cancelSampling()
        // 停止時に書きかけのセグメントが残っていると、終了処理の都合でJSONが欠落するため
        // この時点で保存してしまう(最後のセグメントのJSONが無くなる問題の対策)
        val file = currentSegmentFile
        val name = currentSegmentName
        currentSegmentFile = null
        currentSegmentName = null
        if (file != null && name != null) {
            uploadTmpFile(file, name.substringBeforeLast('.') + ".json", LOOP_PATH)
        }
        Log.i(TAG, "メタデータ記録を停止しました")
    }

    /**
     * 新しいセグメントの記録を開始する(DashcamRecorder.Listener.onSegmentStartedから呼ぶ)。
     * 対応する一時ファイルを新規作成(空の状態)する。
     */
    fun startSegment(videoDisplayName: String) {
        val baseName = videoDisplayName.substringBeforeLast('.')
        val file = File(tmpDir, "$baseName.jsonl")
        try {
            file.writeText("") // 新規作成/既存なら空にする
            currentSegmentFile = file
            currentSegmentName = videoDisplayName
        } catch (e: Exception) {
            Log.e(TAG, "一時ファイルの作成に失敗しました: $baseName", e)
            currentSegmentFile = null
        }
    }

    /**
     * セグメント完了時に呼ぶ。一時ファイルの内容をMediaStoreへアップロードし、
     * 一時ファイルを削除する。videoDisplayNameと同じベース名の .json として保存する。
     *
     * 一時ファイルは「現在のセグメント」ではなく動画名から特定する
     * (停止・一時停止・次セグメント開始との競合で JSON が欠落するのを防ぐため)。
     */
    fun finalizeSegmentToMediaStore(videoDisplayName: String, relativePath: String) {
        val baseName = videoDisplayName.substringBeforeLast('.')
        val file = File(tmpDir, "$baseName.jsonl")
        if (currentSegmentFile == file) currentSegmentFile = null
        uploadTmpFile(file, "$baseName.json", relativePath)
    }

    /** 一時ファイルをMediaStoreへ保存して削除する。空・存在しない場合は何もしない。 */
    private fun uploadTmpFile(file: File, jsonName: String, relativePath: String) {
        if (!file.exists()) return
        if (file.length() == 0L) {
            file.delete()
            return
        }
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, jsonName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val itemUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
            )
            if (itemUri == null) {
                Log.e(TAG, "メタデータJSONの作成に失敗しました: $jsonName")
                return // 一時ファイルは残し、次回起動時の回収に任せる
            }
            context.contentResolver.openOutputStream(itemUri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            Log.i(TAG, "メタデータ保存完了: $jsonName")
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "メタデータの保存に失敗しました: $jsonName", e)
        }
    }

    /**
     * 前回アプリが異常終了した等で一時フォルダに残っているメタデータを回収する。
     * 対応する動画が保護フォルダにあれば保護フォルダへ、それ以外はloopフォルダへ保存する。
     */
    private fun recoverOrphanedFiles() {
        val orphans = tmpDir.listFiles { f -> f.extension == "jsonl" } ?: return
        for (file in orphans) {
            if (file == currentSegmentFile) continue
            val baseName = file.nameWithoutExtension
            val path = if (videoExistsIn(baseName + ".mp4", PROTECTED_PATH)) PROTECTED_PATH else LOOP_PATH
            uploadTmpFile(file, "$baseName.json", path)
        }
    }

    private fun videoExistsIn(displayName: String, relativePath: String): Boolean {
        return try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(displayName, relativePath),
                null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 既にloopフォルダへ保存済みのメタデータJSONを保護フォルダへ移動する。
     * 動画側が保護フォルダへ移された際に、同じベース名の .json も一緒に移すために使う。
     */
    fun moveJsonToProtected(
        videoDisplayName: String,
        loopRelativePath: String,
        protectedRelativePath: String
    ) {
        val jsonName = videoDisplayName.substringBeforeLast('.') + ".json"
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(jsonName, loopRelativePath),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, protectedRelativePath)
                    }
                    context.contentResolver.update(uri, values, null, null)
                    Log.i(TAG, "メタデータJSONを保護フォルダへ移動: $jsonName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "メタデータJSONの移動に失敗しました: $jsonName", e)
        }
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
        appendSampleToCurrentFile(sample)
    }

    private fun appendSampleToCurrentFile(sample: Sample) {
        val file = currentSegmentFile ?: return // セグメント未開始中はサンプリングのみ行い記録しない
        try {
            val obj = JSONObject().apply {
                put("timestamp", ISO_FORMAT.format(Date(sample.timestampMs)))
                put("timestamp_ms", sample.timestampMs)
                put("latitude", sample.latitude ?: JSONObject.NULL)
                put("longitude", sample.longitude ?: JSONObject.NULL)
                put("speed_kmh", sample.speedKmh ?: JSONObject.NULL)
            }
            file.appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "メタデータの逐次書き込みに失敗しました", e)
        }
    }
}
