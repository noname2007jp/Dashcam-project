package com.example.dashcam.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * ドラレコのコア録画クラス。
 *
 * 設計方針(仕様書より):
 * - アウトカメラのみ使用
 * - 設置時の画角調整のため Preview ユースケースを常にバインドする。
 *   ただし実際に画面へ表示するかどうかは setPreviewSurfaceProvider() で
 *   呼び出し側(Activity)が能動的に制御する(Activity非表示中は何もレンダリングされない)
 * - 常時ループ録画: SEGMENT_DURATION_MS ごとに録画ファイルを分割
 * - 録画は MediaStore(Downloads/公開領域)経由で直接 "Download/cam/dashcam_loop"
 *   フォルダへ書き込む。Android/data配下のアプリ専用フォルダは一切使用しない
 *   (ファイルマネージャー等から直接アクセスできるようにするため)
 * - セグメント保存後にコールバックで通知し、ストレージ管理(古いファイル削除)は
 *   呼び出し側(StorageManager 等)に委譲する
 * - motionDetector を渡した場合、VideoCapture と同時に ImageAnalysis も
 *   バインドし、駐車監視モードの動体検知フレームを供給する
 *
 * 注意: MediaStore.Downloads は API 29(Android 10)以降のみ利用可能なため、
 * このクラスは minSdk 29 を前提としている。
 */
class DashcamRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: Listener,
    private val motionDetector: MotionDetector? = null,
    private val preferredZoomRatio: Float? = null
) {
    interface Listener {
        /**
         * 新しいセグメントの録画が始まったときに呼ばれる。
         * メタデータ記録(MetadataRecorder)がこのタイミングで対応するファイルを
         * 開始できるようにするためのフック。
         */
        fun onSegmentStarted(displayName: String)

        /** 1セグメントの録画が正常に完了して保存されたときに呼ばれる */
        fun onSegmentSaved(uri: Uri, displayName: String, durationMs: Long)

        /**
         * 衝撃検知等により保護対象となったセグメントが、保護フォルダへの
         * 移動を完了したときに呼ばれる(onSegmentSavedの代わりに呼ばれる)
         */
        fun onProtectedSegmentSaved(uri: Uri, displayName: String, durationMs: Long)

        /**
         * 直前に完了済みのセグメントが保護フォルダへ移動されたときに呼ばれる。
         * 対応するメタデータJSONも保護フォルダへ移動するためのフック。
         */
        fun onPreviousSegmentProtected(displayName: String)

        /** 録画中にエラーが発生したときに呼ばれる(ストレージ不足等) */
        fun onRecordingError(error: Throwable)

        /** カメラの初期化に失敗したときに呼ばれる */
        fun onCameraInitFailed(error: Throwable)
    }

    companion object {
        private const val TAG = "DashcamRecorder"

        // セグメント分割間隔(ミリ秒)。仕様書: 1〜3分単位でファイル分割
        const val SEGMENT_DURATION_MS = 2 * 60 * 1000L // 2分

        // MediaStore上の保存先(Downloadコレクション配下の相対パス)
        const val LOOP_RELATIVE_PATH = "Download/cam/dashcam_loop/"
        const val PROTECTED_RELATIVE_PATH = "Download/cam/dashcam_protected/"

        private val FILENAME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.JAPAN)
    }

    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null

    // bindCamera()が非同期のため、先に渡されたプレビュー描画先を保持して、
    // バインド完了時に必ず適用する(起動直後に黒画面になる問題の対策)
    @Volatile
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var released = false

    private var currentRecording: Recording? = null
    private var currentSegmentUri: Uri? = null
    private var currentSegmentDisplayName: String? = null
    private var segmentStartTimeMs: Long = 0L

    // 直前に完了したセグメント(衝撃検知が発生した瞬間の「1つ前」を保護するために保持)
    private var lastCompletedSegmentUri: Uri? = null
    private var lastCompletedSegmentDisplayName: String? = null

    // 現在録画中のセグメントが保護対象としてマークされているかどうか
    @Volatile
    private var pendingProtectionForCurrentSegment = false

    // 保護処理の状態を守るためのロック(複数スレッドからの呼び出しに対応)
    private val protectionLock = Any()

    private val segmentHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var segmentRunnable: Runnable? = null

    @Volatile
    private var isRunning = false

    /** カメラを初期化し、バインドする。呼び出し後 startLoopRecording() で録画開始。 */
    fun initialize() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                if (released) return@addListener
                cameraProvider = provider
                bindCamera(provider)
            } catch (e: Exception) {
                Log.e(TAG, "カメラ初期化失敗", e)
                listener.onCameraInitFailed(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
        // 720p〜1080p程度を目安に選択。端末非対応時は自動フォールバック。
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)
        preview = Preview.Builder().build().also {
            pendingSurfaceProvider?.let { sp -> it.setSurfaceProvider(sp) }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // アウトカメラのみ

        // motionDetector が渡されている場合、動体検知用の低解像度フレームを
        // 供給する ImageAnalysis ユースケースも併せてバインドする
        val imageAnalysis = motionDetector?.let { detector ->
            ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(cameraExecutor, detector) }
        }

        val useCases = mutableListOf<UseCase>(videoCapture!!, preview!!)
        imageAnalysis?.let { useCases.add(it) }

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )
            // 広角(超広角)レンズが選択されている場合、ズーム倍率を1.0未満に設定することで
            // 端末が内部的に超広角レンズへ切り替える(Pixel等、レンズが別カメラIDとして
            // 公開されていない機種向けの対応)
            preferredZoomRatio?.let { ratio ->
                camera.cameraControl.setZoomRatio(ratio)
                Log.i(TAG, "ズーム倍率を設定しました: $ratio")
            }
            Log.i(TAG, "カメラのバインドに成功しました(動体検知=${imageAnalysis != null})")
        } catch (e: Exception) {
            Log.e(TAG, "カメラのバインドに失敗しました", e)
            listener.onCameraInitFailed(e)
        }
    }

    /**
     * プレビュー映像の描画先を設定する。設置時の画角調整のため、Activityが
     * 画面に表示されている間だけ呼び出し側が SurfaceProvider を渡す想定。
     * nullを渡すと描画を停止する(Activityが非表示になったときなど)。
     */
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = surfaceProvider
        preview?.setSurfaceProvider(surfaceProvider)
    }

    /** 常時ループ録画を開始する。走行中モードで呼び出す想定。 */
    fun startLoopRecording() {
        if (isRunning) {
            Log.w(TAG, "既に録画中です")
            return
        }
        isRunning = true
        startNewSegment()
    }

    /** 録画を完全に停止する(駐車モードへの切り替え時などに呼び出す)。 */
    fun stopRecording() {
        isRunning = false
        cancelSegmentTimer()
        finalizeCurrentSegment()
    }

    /**
     * 現在録画中のセグメントと、直前に完了したセグメントの両方を
     * 「保護対象」としてマークする。衝撃検知(Gセンサー)や手動録画ボタンから
     * 呼び出す想定。
     *
     * - 直前の完了済みセグメントは即座にMediaStore上で保護フォルダへ移動する
     *   (RELATIVE_PATHの更新による論理的な移動。ファイルの再コピーは発生しない)
     * - 現在録画中のセグメントは、録画完了(Finalize)時に保護フォルダへ移動する
     *   (セグメント境界をまたぐイベントでも前後を録り逃さないための設計)
     */
    fun markCurrentSegmentAsProtected() {
        synchronized(protectionLock) {
            pendingProtectionForCurrentSegment = true
            Log.i(TAG, "現在のセグメントを保護対象としてマーク: $currentSegmentDisplayName")

            val previousUri = lastCompletedSegmentUri
            val previousName = lastCompletedSegmentDisplayName
            if (previousUri != null) {
                cameraExecutor.execute {
                    val moved = moveToProtectedFolder(previousUri)
                    if (moved) {
                        Log.i(TAG, "直前のセグメントを保護フォルダへ移動しました")
                        previousName?.let { listener.onPreviousSegmentProtected(it) }
                    }
                }
                // 同じセグメントを二重に保護対象としないようクリア
                lastCompletedSegmentUri = null
                lastCompletedSegmentDisplayName = null
            }
        }
    }

    /**
     * MediaStore上のアイテムを保護フォルダへ「移動」する。
     * RELATIVE_PATHを更新するだけなので、実ファイルのコピー/削除は発生しない
     * (Android 10以降でサポートされる移動方法)。
     */
    private fun moveToProtectedFolder(uri: Uri): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, PROTECTED_RELATIVE_PATH)
            }
            val updated = context.contentResolver.update(uri, values, null, null)
            updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "保護フォルダへの移動に失敗しました", e)
            false
        }
    }

    private fun startNewSegment() {
        val vc = videoCapture ?: run {
            Log.e(TAG, "VideoCapture が未初期化です")
            return
        }

        val fileName = "${FILENAME_FORMAT.format(System.currentTimeMillis())}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, LOOP_RELATIVE_PATH)
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        currentSegmentDisplayName = fileName
        currentSegmentUri = null
        segmentStartTimeMs = System.currentTimeMillis()
        listener.onSegmentStarted(fileName)

        currentRecording = vc.output
            .prepareRecording(context, outputOptions)
            .apply {
                // 音声録音が必要な場合は withAudioEnabled() を追加
                // (RECORD_AUDIO パーミッションが別途必要)
            }
            .start(cameraExecutor) { event ->
                handleRecordEvent(event)
            }

        // 次のセグメントへの切り替えタイマーをセット
        scheduleSegmentRotation()
    }

    private fun scheduleSegmentRotation() {
        cancelSegmentTimer()
        segmentRunnable = Runnable {
            if (isRunning) {
                rotateSegment()
            }
        }
        segmentHandler.postDelayed(segmentRunnable!!, SEGMENT_DURATION_MS)
    }

    private fun cancelSegmentTimer() {
        segmentRunnable?.let { segmentHandler.removeCallbacks(it) }
        segmentRunnable = null
    }

    private fun rotateSegment() {
        // 現在の録画を止めて、完了コールバック内で次のセグメントを開始する
        currentRecording?.stop()
    }

    private fun finalizeCurrentSegment() {
        currentRecording?.stop()
        currentRecording = null
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.i(TAG, "セグメント録画開始: $currentSegmentDisplayName")
            }
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Log.e(TAG, "録画エラー: ${event.error} / ${event.cause}")
                    listener.onRecordingError(
                        event.cause ?: RuntimeException("録画エラー code=${event.error}")
                    )
                    // ストレージ不足等の場合、呼び出し元(StorageManager)が
                    // 容量確保後に再度 startLoopRecording() を呼ぶ想定
                } else {
                    val uri = event.outputResults.outputUri
                    val displayName = currentSegmentDisplayName
                    val duration = System.currentTimeMillis() - segmentStartTimeMs

                    if (displayName != null) {
                        val shouldProtect = synchronized(protectionLock) {
                            val flag = pendingProtectionForCurrentSegment
                            pendingProtectionForCurrentSegment = false
                            flag
                        }

                        if (shouldProtect) {
                            Log.i(TAG, "保護対象セグメントを保存完了: $displayName (${duration}ms)")
                            moveToProtectedFolder(uri)
                            listener.onProtectedSegmentSaved(uri, displayName, duration)
                            // 保護フォルダに移動したアイテムは通常のループ削除対象では
                            // ないため、lastCompletedSegmentUri には設定しない
                        } else {
                            Log.i(TAG, "セグメント保存完了: $displayName (${duration}ms)")
                            lastCompletedSegmentUri = uri
                            lastCompletedSegmentDisplayName = displayName
                            listener.onSegmentSaved(uri, displayName, duration)
                        }
                    }
                }
                currentRecording = null
                currentSegmentUri = null

                // 継続録画中なら次のセグメントを開始(ループ)
                if (isRunning) {
                    startNewSegment()
                }
            }
            else -> {
                // Pause / Resume / Status イベントは現状未使用
            }
        }
    }

    /** リソース解放。Activity/Service の onDestroy 等から呼び出す。 */
    fun release() {
        released = true
        stopRecording()
        // カメラを確実に閉じる(サービス破棄を待たず、ここで全ユースケースを解除する)
        preview?.setSurfaceProvider(null)
        pendingSurfaceProvider = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "カメラの解除に失敗しました", e)
        }
        cameraProvider = null
    }
}
