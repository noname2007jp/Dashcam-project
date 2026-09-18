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
import com.example.dashcam.camera.DashcamRecorder
import com.example.dashcam.sensor.ShockDetector
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
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
                    // 現在録画中のセグメントを保護対象としてマーク
                    recorder?.markCurrentSegmentAsProtected()
                    updateNotification("衝撃を検知しました(${"%.1f".format(magnitudeG)}G)")
                    // TODO: 駐車監視モード(Mode.PARKING)時は、動体検知トリガー録画側の
                    //       録画開始とも連携させる
                }
            }
        ).apply {
            // 現時点では走行中判定は未実装のため、常にDRIVINGモードで起動
            // TODO: 走行/駐車判定ロジック実装後、setMode()で自動切り替えする
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

        recorder = DashcamRecorder(
            context = this,
            lifecycleOwner = this, // LifecycleService自身がLifecycleOwner
            outputDir = outputDir,
            listener = object : DashcamRecorder.Listener {
                override fun onSegmentSaved(file: File, durationMs: Long) {
                    Log.i(TAG, "セグメント保存: ${file.name}")
                    // TODO: StorageManager にファイル保存完了を通知し、
                    //       容量チェック・古いファイル削除を行う
                }

                override fun onRecordingError(error: Throwable) {
                    Log.e(TAG, "録画エラー", error)
                    updateNotification("録画エラーが発生しました")
                    // TODO: ストレージ不足エラーの場合は音声警告を再生
                }

                override fun onCameraInitFailed(error: Throwable) {
                    Log.e(TAG, "カメラ初期化失敗", error)
                    updateNotification("カメラを起動できませんでした")
                    stopRecordingAndSelf()
                }
            }
        )

        recorder?.initialize()
        recorder?.startLoopRecording()
        updateNotification("録画中")
    }

    private fun stopRecordingAndSelf() {
        recorder?.release()
        recorder = null
        stopShockDetector()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.release()
        recorder = null
        stopShockDetector()
        releaseWakeLock()
        super.onDestroy()
    }

    // バインド機能は使わない(startService経由でのみ利用)
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
