package com.example.dashcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.dashcam.R
import com.example.dashcam.audio.VoiceAlertManager
import com.example.dashcam.camera.DashcamRecorder
import com.example.dashcam.camera.MotionDetector
import com.example.dashcam.location.DrivingStateDetector
import com.example.dashcam.sensor.ShockDetector
import com.example.dashcam.sensor.TailgatingDetector
import com.example.dashcam.storage.StorageManager
import java.io.File

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
    }

    private var recorder: DashcamRecorder? = null
    private var shockDetector: ShockDetector? = null
    private var tailgatingDetector: TailgatingDetector? = null
    private var drivingStateDetector: DrivingStateDetector? = null
    private var motionDetector: MotionDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var storageManager: StorageManager? = null
    private var voiceAlertManager: VoiceAlertManager? = null

    // 駐車監視モード中かどうか(動体検知トリガー録画を有効にするかの判定に使う)
    @Volatile
    private var isParkingMode = false

    override fun onCreate() {
        super.onCreate()
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
            else -> {
                startForegroundWithNotification()
                acquireWakeLock()
                startRecorder()
                startShockDetector()
                startTailgatingDetector()
                startDrivingStateDetector()
            }
        }

        // システムにkillされても可能な限り再起動してほしいので START_STICKY
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("録画中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
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
                            // 常時ループ録画を確実に開始/継続する
                            recorder?.startLoopRecording()
                            updateNotification("走行中(常時録画中)")
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
                    // 衝撃検知と同じ保護ロジックで前後のセグメントを保護対象にする
                    recorder?.markCurrentSegmentAsProtected()
                    updateNotification("煽り運転の可能性を検知しました(急ブレーキ${eventCount}回)")
                }
            }
        )
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
            start()
        }
    }

    private fun stopShockDetector() {
        shockDetector?.stop()
        shockDetector = null
    }

    private fun startRecorder() {
        if (recorder != null) {
            Log.w(TAG, "既にRecorderが起動しています")
            return
        }

        val outputDir = File(getExternalFilesDir(null), "dashcam_loop")
        val protectedDir = File(getExternalFilesDir(null), "dashcam_protected")

        storageManager = StorageManager(
            loopDir = outputDir,
            protectedDir = protectedDir,
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
                    if (isParkingMode) {
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
            outputDir = outputDir,
            motionDetector = motionDetector,
            listener = object : DashcamRecorder.Listener {
                override fun onSegmentSaved(file: File, durationMs: Long) {
                    Log.i(TAG, "セグメント保存: ${file.name}")
                    storageManager?.checkAndManage()
                }

                override fun onProtectedSegmentSaved(file: File, durationMs: Long) {
                    Log.i(TAG, "保護セグメント保存: ${file.name}")
                    updateNotification("イベント映像を保護フォルダに保存しました")
                    storageManager?.checkAndManage()
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
        // 初期状態は走行中とみなして常時録画を開始する。
        // DrivingStateDetectorの判定が確定し次第、駐車中であれば自動的に停止される。
        recorder?.startLoopRecording()
        updateNotification("録画中")
    }

    private fun stopRecordingAndSelf() {
        recorder?.release()
        recorder = null
        motionDetector = null
        storageManager = null
        stopShockDetector()
        stopTailgatingDetector()
        stopDrivingStateDetector()
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
        releaseWakeLock()
        voiceAlertManager?.release()
        voiceAlertManager = null
        super.onDestroy()
    }

    // バインド機能は使わない(startService経由でのみ利用)
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
