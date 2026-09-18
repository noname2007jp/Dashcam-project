package com.example.dashcam.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * ドラレコのコア録画クラス。
 *
 * 設計方針(仕様書より):
 * - アウトカメラのみ使用
 * - Preview ユースケースはバインドしない(GPU負荷削減のため画面表示なし)
 * - 常時ループ録画: SEGMENT_DURATION_MS ごとに録画ファイルを分割
 * - セグメント保存後にコールバックで通知し、ストレージ管理(古いファイル削除)は
 *   呼び出し側(StorageManager 等)に委譲する
 */
class DashcamRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val outputDir: File,
    private val listener: Listener
) {
    interface Listener {
        /** 1セグメントの録画が正常に完了して保存されたときに呼ばれる */
        fun onSegmentSaved(file: File, durationMs: Long)

        /** 録画中にエラーが発生したときに呼ばれる(ストレージ不足等) */
        fun onRecordingError(error: Throwable)

        /** カメラの初期化に失敗したときに呼ばれる */
        fun onCameraInitFailed(error: Throwable)
    }

    companion object {
        private const val TAG = "DashcamRecorder"

        // セグメント分割間隔(ミリ秒)。仕様書: 1〜3分単位でファイル分割
        const val SEGMENT_DURATION_MS = 2 * 60 * 1000L // 2分

        private val FILENAME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.JAPAN)
    }

    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var currentSegmentFile: File? = null
    private var segmentStartTimeMs: Long = 0L

    private val segmentHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var segmentRunnable: Runnable? = null

    @Volatile
    private var isRunning = false

    /** カメラを初期化し、バインドする。呼び出し後 startLoopRecording() で録画開始。 */
    fun initialize() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindCamera(cameraProvider)
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

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // アウトカメラのみ

        try {
            cameraProvider.unbindAll()
            // Preview はバインドしない(GPU負荷削減、画面表示不要のため)
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                videoCapture
            )
            Log.i(TAG, "カメラのバインドに成功しました")
        } catch (e: Exception) {
            Log.e(TAG, "カメラのバインドに失敗しました", e)
            listener.onCameraInitFailed(e)
        }
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
     * 現在録画中のセグメントを「保護対象」として即座に確定する。
     * 衝撃検知(Gセンサー)や手動録画ボタンから呼び出す想定。
     * 実際の前後バッファの保護ロジック(protected フォルダへの移動)は
     * onSegmentSaved コールバック側 or 呼び出し元で行う。
     */
    fun markCurrentSegmentAsProtected() {
        currentSegmentFile?.let {
            Log.i(TAG, "現在のセグメントを保護対象としてマーク: ${it.name}")
            // 実装メモ: ここでファイルパスをイベントキューに積んでおき、
            // セグメント確定後に protected フォルダへコピー/移動する設計を推奨。
            // (セグメント境界をまたぐイベントの場合は前後2セグメントを保護対象にする)
        }
    }

    private fun startNewSegment() {
        val vc = videoCapture ?: run {
            Log.e(TAG, "VideoCapture が未初期化です")
            return
        }

        if (!outputDir.exists()) outputDir.mkdirs()

        val fileName = "${FILENAME_FORMAT.format(System.currentTimeMillis())}.mp4"
        val outputFile = File(outputDir, fileName)
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        currentSegmentFile = outputFile
        segmentStartTimeMs = System.currentTimeMillis()

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
                Log.i(TAG, "セグメント録画開始: ${currentSegmentFile?.name}")
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
                    val file = currentSegmentFile
                    val duration = System.currentTimeMillis() - segmentStartTimeMs
                    if (file != null) {
                        Log.i(TAG, "セグメント保存完了: ${file.name} (${duration}ms)")
                        listener.onSegmentSaved(file, duration)
                    }
                }
                currentRecording = null

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
        stopRecording()
    }
}
