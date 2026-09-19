package com.example.dashcam.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
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
 * - 設置時の画角調整のため Preview ユースケースを常にバインドする。
 *   ただし実際に画面へ表示するかどうかは setPreviewSurfaceProvider() で
 *   呼び出し側(Activity)が能動的に制御する(Activity非表示中は何もレンダリングされない)
 * - 常時ループ録画: SEGMENT_DURATION_MS ごとに録画ファイルを分割
 * - セグメント保存後にコールバックで通知し、ストレージ管理(古いファイル削除)は
 *   呼び出し側(StorageManager 等)に委譲する
 * - motionDetector を渡した場合、VideoCapture と同時に ImageAnalysis も
 *   バインドし、駐車監視モードの動体検知フレームを供給する
 */
class DashcamRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val outputDir: File,
    private val listener: Listener,
    private val motionDetector: MotionDetector? = null
) {
    interface Listener {
        /** 1セグメントの録画が正常に完了して保存されたときに呼ばれる */
        fun onSegmentSaved(file: File, durationMs: Long)

        /**
         * 衝撃検知等により保護対象となったセグメントが、保護フォルダへの
         * 移動を完了したときに呼ばれる(onSegmentSavedの代わりに呼ばれる)
         */
        fun onProtectedSegmentSaved(file: File, durationMs: Long)

        /** 録画中にエラーが発生したときに呼ばれる(ストレージ不足等) */
        fun onRecordingError(error: Throwable)

        /** カメラの初期化に失敗したときに呼ばれる */
        fun onCameraInitFailed(error: Throwable)
    }

    companion object {
        private const val TAG = "DashcamRecorder"

        // セグメント分割間隔(ミリ秒)。仕様書: 1〜3分単位でファイル分割
        const val SEGMENT_DURATION_MS = 2 * 60 * 1000L // 2分

        // 保護対象ファイルの保存先サブフォルダ名(outputDirの親配下に作成)
        private const val PROTECTED_DIR_NAME = "dashcam_protected"

        private val FILENAME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.JAPAN)
    }

    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var currentRecording: Recording? = null
    private var currentSegmentFile: File? = null
    private var segmentStartTimeMs: Long = 0L

    // 直前に完了したセグメント(衝撃検知が発生した瞬間の「1つ前」を保護するために保持)
    private var lastCompletedSegmentFile: File? = null

    // 現在録画中のセグメントが保護対象としてマークされているかどうか
    @Volatile
    private var pendingProtectionForCurrentSegment = false

    // 保護処理の状態を守るためのロック(複数スレッドからの呼び出しに対応)
    private val protectionLock = Any()

    private val protectedDir: File by lazy {
        File(outputDir.parentFile ?: outputDir, PROTECTED_DIR_NAME)
    }

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
        preview = Preview.Builder().build()

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
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )
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
     * - 直前の完了済みセグメントは即座に保護フォルダへ移動する
     * - 現在録画中のセグメントは、録画完了(Finalize)時に保護フォルダへ移動する
     *   (セグメント境界をまたぐイベントでも前後を録り逃さないための設計)
     */
    fun markCurrentSegmentAsProtected() {
        synchronized(protectionLock) {
            pendingProtectionForCurrentSegment = true
            Log.i(TAG, "現在のセグメントを保護対象としてマーク: ${currentSegmentFile?.name}")

            val previousFile = lastCompletedSegmentFile
            if (previousFile != null && previousFile.exists()) {
                cameraExecutor.execute {
                    val moved = moveToProtectedFolder(previousFile)
                    if (moved != null) {
                        Log.i(TAG, "直前のセグメントを保護フォルダへ移動: ${moved.name}")
                    }
                }
                // 同じセグメントを二重に保護対象としないようクリア
                lastCompletedSegmentFile = null
            }
        }
    }

    /**
     * ファイルを保護フォルダへ移動する。同一ストレージ内であれば File.renameTo で
     * 高速に移動できるが、失敗した場合はコピー後に元ファイルを削除するフォールバックを行う。
     */
    private fun moveToProtectedFolder(file: File): File? {
        if (!protectedDir.exists()) protectedDir.mkdirs()

        val destination = File(protectedDir, file.name)
        return try {
            if (file.renameTo(destination)) {
                destination
            } else {
                file.copyTo(destination, overwrite = true)
                file.delete()
                destination
            }
        } catch (e: Exception) {
            Log.e(TAG, "保護フォルダへの移動に失敗しました: ${file.name}", e)
            null
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
                        val shouldProtect = synchronized(protectionLock) {
                            val flag = pendingProtectionForCurrentSegment
                            pendingProtectionForCurrentSegment = false
                            flag
                        }

                        if (shouldProtect) {
                            Log.i(TAG, "保護対象セグメントを保存完了: ${file.name} (${duration}ms)")
                            val moved = moveToProtectedFolder(file)
                            listener.onProtectedSegmentSaved(moved ?: file, duration)
                            // 保護フォルダに移動したファイルは通常のループ削除対象では
                            // ないため、lastCompletedSegmentFile には設定しない
                        } else {
                            Log.i(TAG, "セグメント保存完了: ${file.name} (${duration}ms)")
                            lastCompletedSegmentFile = file
                            listener.onSegmentSaved(file, duration)
                        }
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
