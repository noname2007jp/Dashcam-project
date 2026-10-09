package com.example.dashcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.dashcam.R
import com.example.dashcam.audio.VoiceAlertManager
import com.example.dashcam.camera.DashcamRecorder
import com.example.dashcam.camera.MotionDetector
import com.example.dashcam.location.DrivingStateDetector
import com.example.dashcam.metadata.MetadataRecorder
import com.example.dashcam.sensor.ShockDetector
import com.example.dashcam.sensor.TailgatingDetector
import com.example.dashcam.settings.SettingsManager
import com.example.dashcam.storage.FileExporter
import com.example.dashcam.storage.StorageManager

/**
 * ドラレコ本体のフォアグラウンドサービス。
 *
 * - 画面が消灯していても録画を継続するための常駐サービス
 * - CPUのみ起こす PARTIAL_WAKE_LOCK を保持(画面は起こさない)
 * - DashcamRecorder を内部で保持し、録画のライフサイクルを管理する
 *
 * LifecycleService を使うことで、DashcamRecorder が要求する
 * LifecycleOwner をこのサービス自身が提供できる。
 */
class DashcamForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "DashcamService"
        private const val NOTIFICATION_CHANNEL_ID = "dashcam_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.dashcam.action.START"
        const val ACTION_STOP = "com.example.dashcam.action.STOP"
        const val ACTION_PAUSE = "com.example.dashcam.action.PAUSE"
        const val ACTION_RESUME = "com.example.dashcam.action.RESUME"
        /** プレビュー待機中のサービスに対し、録画セッション(録画・各検知)の開始を指示する */
        const val ACTION_BEGIN_RECORDING = "com.example.dashcam.action.BEGIN_RECORDING"

        /**
         * サービスが現在起動中かどうか(同一プロセス内でのみ参照可能な簡易フラグ)。
         * MainActivityが「自動開始せず、既に起動中ならバインドのみ行う」判定に使う。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 録画セッションが開始されているか(プレビュー待機中は false) */
        @Volatile
        var isRecordingActive: Boolean = false
            private set
    }

    private var recorder: DashcamRecorder? = null
    private var shockDetector: ShockDetector? = null
    private var tailgatingDetector: TailgatingDetector? = null
    private var drivingStateDetector: DrivingStateDetector? = null
    private var motionDetector: MotionDetector? = null
    private var metadataRecorder: MetadataRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var storageManager: StorageManager? = null
    private var voiceAlertManager: VoiceAlertManager? = null
    private val settingsManager: SettingsManager by lazy { SettingsManager(this) }
    private val fileExporter: FileExporter by lazy { FileExporter(this) }

    // 駐車監視モード中かどうか(動体検知トリガー録画を有効にするかの判定に使う)
    @Volatile
    private var isParkingMode = false

    // ユーザー操作による一時停止中かどうか(終了とは異なり、サービス自体は継続する)
    @Volatile
    private var isPausedByUser = false

    /** 一時停止中かどうかを外部(MainActivity)から確認するための問い合わせ */
    fun isPaused(): Boolean = isPausedByUser

    /**
     * MainActivityがこのServiceにバインドしてPreview映像を受け取れるようにするためのBinder。
     * ServiceはstartForegroundServiceで起動されつつ、Activityから同時にbindServiceもされる
     * ハイブリッド構成(録画自体はActivityの有無に関わらず継続する)。
     */
    inner class LocalBinder : Binder() {
        fun getService(): DashcamForegroundService = this@DashcamForegroundService
    }

    private val binder = LocalBinder()

    /** 設置時の画角調整用。Activityが表示されている間だけ呼び出される想定。 */
    fun attachPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        recorder?.setPreviewSurfaceProvider(surfaceProvider)
    }

    /** Activityが非表示になったときに呼び出し、プレビュー描画を止める。 */
    fun detachPreviewSurfaceProvider() {
        recorder?.setPreviewSurfaceProvider(null)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        voiceAlertManager = VoiceAlertManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopRecordingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseRecording()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeRecording()
                return START_STICKY
            }
            ACTION_BEGIN_RECORDING -> {
                if (recorder == null) {
                    startForegroundWithNotification()
                    prepareCameraOnly()
                }
                beginRecordingSession()
            }
            else -> {
                // 起動直後はプレビューのみ(画角調整用)。録画は「開始」ボタンで始まる
                startForegroundWithNotification()
                prepareCameraOnly()
            }
        }

        // システムにkillされても可能な限り再起動してほしいので START_STICKY
        return START_STICKY
    }

    /**
     * ユーザー操作による一時停止。録画のみ停止し、サービス・各種センサー・
     * カメラのバインドは維持する(終了ボタンとは異なり、すぐ再開できる状態を保つ)。
     * 書き出し等の操作をしている間、録画を止めておきたい場合に使う想定。
     */
    private fun pauseRecording() {
        isPausedByUser = true
        recorder?.stopRecording()
        updateNotification("一時停止中")
        Log.i(TAG, "ユーザー操作により一時停止しました")
    }

    private fun resumeRecording() {
        isPausedByUser = false
        when (drivingStateDetector?.getCurrentState()) {
            DrivingStateDetector.State.PARKING -> {
                // 駐車監視中は動体検知/衝撃検知が録画開始を判断するので、ここでは待機のみ
                updateNotification("駐車監視中(待機)")
            }
            else -> {
                recorder?.startLoopRecording()
                updateNotification("走行中(常時録画中)")
            }
        }
        Log.i(TAG, "録画を再開しました")
    }

    /**
     * 録画セッションを開始する(ループ録画・メタデータ・各種検知)。
     * MetadataRecorderは最初のセグメント開始(onSegmentStarted)より前に起動する必要がある。
     */
    private fun beginRecordingSession() {
        if (isRecordingActive) return
        isRecordingActive = true
        isPausedByUser = false
        acquireWakeLock()
        startMetadataRecorder()
        startShockDetector()
        startTailgatingDetector()
        startDrivingStateDetector()
        recorder?.startLoopRecording()
        updateNotification("録画中")
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("プレビュー待機中(「開始」で録画を始めます)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ドラレコ")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_recording) // res/drawable に用意が必要
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ドラレコ録画通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "録画中であることを示す常駐通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, // 画面は起こさずCPUのみ維持
            "Dashcam::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L /*12時間の安全上限*/)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun startMetadataRecorder() {
        if (metadataRecorder != null) {
            Log.w(TAG, "既にMetadataRecorderが起動しています")
            return
        }
        metadataRecorder = MetadataRecorder(this)
        metadataRecorder?.start()
    }

    private fun stopMetadataRecorder() {
        metadataRecorder?.stop()
        metadataRecorder = null
    }

    private fun startDrivingStateDetector() {
        if (drivingStateDetector != null) {
            Log.w(TAG, "既にDrivingStateDetectorが起動しています")
            return
        }

        drivingStateDetector = DrivingStateDetector(
            context = this,
            listener = object : DrivingStateDetector.Listener {
                override fun onStateChanged(
                    newState: DrivingStateDetector.State,
                    currentSpeedKmh: Float
                ) {
                    Log.i(
                        TAG,
                        "走行状態が変化: $newState (${"%.1f".format(currentSpeedKmh)}km/h)"
                    )
                    when (newState) {
                        DrivingStateDetector.State.DRIVING -> {
                            isParkingMode = false
                            shockDetector?.setMode(ShockDetector.Mode.DRIVING)
                            tailgatingDetector?.setActive(true)
                            motionDetector?.reset()
                            // 一時停止中でなければ常時ループ録画を確実に開始/継続する
                            if (!isPausedByUser) {
                                recorder?.startLoopRecording()
                                updateNotification("走行中(常時録画中)")
                            } else {
                                updateNotification("一時停止中(走行検知)")
                            }
                        }
                        DrivingStateDetector.State.PARKING -> {
                            isParkingMode = true
                            shockDetector?.setMode(ShockDetector.Mode.PARKING)
                            tailgatingDetector?.setActive(false)
                            motionDetector?.reset()
                            // 常時ループ録画は停止。以降はMotionDetectorが
                            // 動きを検知したときだけ録画を開始する
                            recorder?.stopRecording()
                            updateNotification("駐車監視中(待機)")
                        }
                        DrivingStateDetector.State.UNKNOWN -> {
                            // 初期状態、判定中は何もしない
                        }
                    }
                }
            }
        )
        drivingStateDetector?.start()
    }

    private fun stopDrivingStateDetector() {
        drivingStateDetector?.stop()
        drivingStateDetector = null
    }

    private fun startTailgatingDetector() {
        if (tailgatingDetector != null) {
            Log.w(TAG, "既にTailgatingDetectorが起動しています")
            return
        }

        tailgatingDetector = TailgatingDetector(
            context = this,
            listener = object : TailgatingDetector.Listener {
                override fun onTailgatingSuspected(eventCount: Int) {
                    Log.i(TAG, "煽り運転の可能性を検知(急ブレーキ${eventCount}回)")
                    if (isPausedByUser) return
                    // 衝撃検知と同じ保護ロジックで前後のセグメントを保護対象にする
                    recorder?.markCurrentSegmentAsProtected()
                    updateNotification("煽り運転の可能性を検知しました(急ブレーキ${eventCount}回)")
                }
            }
        )
        tailgatingDetector?.setBrakingThreshold(settingsManager.tailgatingThreshold)
        tailgatingDetector?.start()
        // 開始直後は走行状態が未確定なため、DrivingStateDetectorの判定が
        // 出るまでは非アクティブ。DRIVING判定時に setActive(true) される。
    }

    private fun stopTailgatingDetector() {
        tailgatingDetector?.stop()
        tailgatingDetector = null
    }

    private fun startShockDetector() {
        if (shockDetector != null) {
            Log.w(TAG, "既にShockDetectorが起動しています")
            return
        }

        shockDetector = ShockDetector(
            context = this,
            listener = object : ShockDetector.Listener {
                override fun onShockDetected(magnitudeG: Float, mode: ShockDetector.Mode) {
                    Log.i(TAG, "衝撃検知イベント: ${"%.2f".format(magnitudeG)}G (mode=$mode)")
                    if (isPausedByUser) {
                        // 一時停止中は録画していないため保護対象もない
                        return
                    }
                    // 駐車監視中で、まだ動体検知による録画が始まっていない場合でも
                    // 衝撃検知自体をトリガーに録画を開始する(当て逃げ等の瞬間対策)
                    if (isParkingMode) {
                        recorder?.startLoopRecording()
                    }
                    // 現在録画中のセグメントを保護対象としてマーク
                    recorder?.markCurrentSegmentAsProtected()
                    updateNotification("衝撃を検知しました(${"%.1f".format(magnitudeG)}G)")
                }
            }
        ).apply {
            setDrivingThreshold(settingsManager.shockDrivingThreshold)
            setParkingThreshold(settingsManager.shockParkingThreshold)
            start()
        }
    }

    private fun stopShockDetector() {
        shockDetector?.stop()
        shockDetector = null
    }

    private fun prepareCameraOnly() {
        if (recorder != null) {
            Log.w(TAG, "既にRecorderが起動しています")
            return
        }

        storageManager = StorageManager(
            context = this,
            maxLoopBytesProvider = {
                settingsManager.maxLoopStorageGb?.let { gb -> gb.toLong() * 1024 * 1024 * 1024 }
            },
            listener = object : StorageManager.Listener {
                override fun onAutoDeleted(deletedCount: Int, freedBytes: Long) {
                    Log.i(TAG, "自動削除: ${deletedCount}件 (${freedBytes}bytes解放)")
                }

                override fun onStorageCritical(freePercent: Int) {
                    Log.w(TAG, "ストレージ危険域: 空き${freePercent}%。録画を停止します")
                    recorder?.stopRecording()
                    updateNotification("空き容量不足のため録画を停止しました(残り${freePercent}%)")
                    val inCooldown = storageManager?.isCriticalWarningInCooldown() == true
                    if (!inCooldown) {
                        voiceAlertManager?.speak(
                            "ストレージの空き容量が不足しています。録画を停止しました。"
                        )
                    }
                }

                override fun onProtectedFolderOverLimit(currentSizeBytes: Long) {
                    val inCooldown = storageManager?.isProtectedWarningInCooldown() == true
                    Log.w(TAG, "保護フォルダが上限を超過: ${currentSizeBytes}bytes")
                    if (!inCooldown) {
                        voiceAlertManager?.speak(
                            "保護された映像の容量が上限に達しています。整理をご検討ください。"
                        )
                    }
                }
            }
        )

        // 駐車監視モードの動体検知トリガー用アナライザ。
        // DashcamRecorder のカメラバインド時に ImageAnalysis として一緒に組み込まれる。
        motionDetector = MotionDetector(
            listener = object : MotionDetector.Listener {
                override fun onMotionDetected() {
                    Log.i(TAG, "動体検知: 録画を開始します")
                    if (isParkingMode && !isPausedByUser) {
                        recorder?.startLoopRecording()
                        updateNotification("駐車監視中(動きを検知、録画中)")
                    }
                }

                override fun onMotionStopped() {
                    Log.i(TAG, "動体検知: 動きが止まったため録画を停止します")
                    if (isParkingMode) {
                        recorder?.stopRecording()
                        updateNotification("駐車監視中(待機)")
                    }
                }
            }
        )

        recorder = DashcamRecorder(
            context = this,
            lifecycleOwner = this, // LifecycleService自身がLifecycleOwner
            motionDetector = motionDetector,
            preferredZoomRatio = settingsManager.preferredZoomRatio,
            listener = object : DashcamRecorder.Listener {
                override fun onSegmentStarted(displayName: String) {
                    metadataRecorder?.startSegment(displayName)
                }

                override fun onSegmentSaved(uri: Uri, displayName: String, durationMs: Long) {
                    Log.i(TAG, "セグメント保存: $displayName")
                    storageManager?.checkAndManage()
                    metadataRecorder?.finalizeSegmentToMediaStore(
                        displayName, DashcamRecorder.LOOP_RELATIVE_PATH
                    )
                    settingsManager.saveLocationUri?.let { customUri ->
                        fileExporter.exportLoopSegment(customUri, uri, displayName)
                    }
                }

                override fun onProtectedSegmentSaved(
                    uri: Uri,
                    displayName: String,
                    durationMs: Long
                ) {
                    Log.i(TAG, "保護セグメント保存: $displayName")
                    updateNotification("イベント映像を保護フォルダに保存しました")
                    storageManager?.checkAndManage()
                    metadataRecorder?.finalizeSegmentToMediaStore(
                        displayName, DashcamRecorder.PROTECTED_RELATIVE_PATH
                    )
                    settingsManager.saveLocationUri?.let { customUri ->
                        fileExporter.exportProtectedSegment(customUri, uri, displayName)
                    }
                }

                override fun onPreviousSegmentProtected(displayName: String) {
                    metadataRecorder?.moveJsonToProtected(
                        displayName,
                        DashcamRecorder.LOOP_RELATIVE_PATH,
                        DashcamRecorder.PROTECTED_RELATIVE_PATH
                    )
                }

                override fun onRecordingError(error: Throwable) {
                    Log.e(TAG, "録画エラー", error)
                    updateNotification("録画エラーが発生しました")
                    // ストレージ不足の可能性もあるため、念のためチェックを走らせる
                    storageManager?.checkAndManage()
                }

                override fun onCameraInitFailed(error: Throwable) {
                    Log.e(TAG, "カメラ初期化失敗", error)
                    updateNotification("カメラを起動できませんでした")
                    stopRecordingAndSelf()
                }
            }
        )

        recorder?.initialize()
        updateNotification("プレビュー待機中(「開始」で録画を始めます)")
    }

    private fun stopRecordingAndSelf() {
        isRecordingActive = false
        recorder?.release()
        recorder = null
        motionDetector = null
        storageManager = null
        stopShockDetector()
        stopTailgatingDetector()
        stopDrivingStateDetector()
        stopMetadataRecorder()
        releaseWakeLock()
        voiceAlertManager?.release()
        voiceAlertManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.release()
        recorder = null
        motionDetector = null
        storageManager = null
        stopShockDetector()
        stopTailgatingDetector()
        stopDrivingStateDetector()
        stopMetadataRecorder()
        releaseWakeLock()
        voiceAlertManager?.release()
        voiceAlertManager = null
        isRunning = false
        isRecordingActive = false
        super.onDestroy()
    }

    // startForegroundServiceで起動されつつ、MainActivityからbindServiceもされる
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
}
