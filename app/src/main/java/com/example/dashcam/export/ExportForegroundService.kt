package com.example.dashcam.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 書き出し(メタデータ焼き込み)処理をフォアグラウンドサービスとして実行するクラス。
 *
 * 以前はExportActivity内のコルーチンで実行していたため、Activityを閉じると
 * 処理が中断される可能性があった。書き出しは動画の長さ次第で数十秒〜数分かかる
 * ことがあるため、Activityのライフサイクルから切り離してサービス側で実行する。
 *
 * 同時に実行できる書き出しは1件のみ(isExportingで簡易的にガードする)。
 */
class ExportForegroundService : Service() {

    companion object {
        private const val TAG = "ExportForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "dashcam_export_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_EXPORT = "com.example.dashcam.action.START_EXPORT"

        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_SOURCE_DISPLAY_NAME = "source_display_name"
        const val EXTRA_METADATA_URI = "metadata_uri"
        const val EXTRA_SHOW_DATE = "show_date"
        const val EXTRA_SHOW_LOCATION = "show_location"
        const val EXTRA_SHOW_SPEED = "show_speed"
        const val EXTRA_SHOW_EVENTS = "show_events"
        const val EXTRA_POSITION = "position"
        const val EXTRA_ORIENTATION = "orientation"

        @Volatile
        var isExporting: Boolean = false
            private set

        private val _progressText = MutableStateFlow("")

        /** 書き出しの進捗表示用テキスト(例: "42% 映像にテキストを焼き込み中")。ExportActivityが購読する */
        val progressText: StateFlow<String> = _progressText
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var exportJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_EXPORT) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isExporting) {
            Log.w(TAG, "既に書き出し処理が実行中です。新規リクエストは無視します")
            stopSelf()
            return START_NOT_STICKY
        }

        val sourceUri: Uri? = intent.getParcelableExtra(EXTRA_SOURCE_URI)
        val displayName = intent.getStringExtra(EXTRA_SOURCE_DISPLAY_NAME)
        val metadataUri: Uri? = intent.getParcelableExtra(EXTRA_METADATA_URI)
        val options = ExportOptions(
            showDate = intent.getBooleanExtra(EXTRA_SHOW_DATE, true),
            showLocation = intent.getBooleanExtra(EXTRA_SHOW_LOCATION, true),
            showSpeed = intent.getBooleanExtra(EXTRA_SHOW_SPEED, true),
            showEventMarks = intent.getBooleanExtra(EXTRA_SHOW_EVENTS, true),
            position = ExportOptions.Position.valueOf(
                intent.getStringExtra(EXTRA_POSITION) ?: ExportOptions.Position.BOTTOM_LEFT.name
            )
        )
        val orientation = VideoExportManager.OutputOrientation.valueOf(
            intent.getStringExtra(EXTRA_ORIENTATION)
                ?: VideoExportManager.OutputOrientation.LANDSCAPE.name
        )

        if (metadataUri == null) {
            // JSONが無ければ焼き込む内容がないため、書き出さずに完了する
            _progressText.value = "メタデータ(JSON)がないため、書き出しは不要です"
            // startForegroundServiceで起動された以上、必ずstartForegroundを呼んでから終了する
            startForegroundWithNotification("メタデータ(JSON)がないため、書き出しは不要です")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }

        if (sourceUri == null || displayName == null) {
            Log.e(TAG, "必要なパラメータが不足しています")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification("書き出しを準備中...")
        runExport(sourceUri, displayName, metadataUri, options, orientation)

        return START_NOT_STICKY
    }

    private fun runExport(
        sourceUri: Uri,
        displayName: String,
        metadataUri: Uri?,
        options: ExportOptions,
        orientation: VideoExportManager.OutputOrientation
    ) {
        isExporting = true
        _progressText.value = "0% 準備中"
        exportJob = serviceScope.launch {
            val manager = VideoExportManager(this@ExportForegroundService)
            val result = manager.export(
                sourceVideoUri = sourceUri,
                sourceDisplayName = displayName,
                metadataJsonUri = metadataUri,
                options = options,
                outputOrientation = orientation,
                onProgress = { message, percent ->
                    val text = "$percent% $message"
                    _progressText.value = text
                    updateNotification(text, percent = percent)
                }
            )

            isExporting = false

            when (result) {
                is VideoExportManager.Result.Success -> {
                    _progressText.value = "100% 書き出し完了: ${result.displayName}"
                    updateNotification("書き出し完了: ${result.displayName}", ongoing = false)
                    Log.i(TAG, "書き出し完了: ${result.displayName}")
                }
                is VideoExportManager.Result.NoMetadata -> {
                    _progressText.value = "メタデータ(JSON)がないため、書き出しは不要です"
                    updateNotification("メタデータ(JSON)がないため、書き出しは不要です", ongoing = false)
                }
                is VideoExportManager.Result.Failure -> {
                    _progressText.value = "書き出しに失敗しました: ${result.error.message}"
                    updateNotification(
                        "書き出しに失敗しました: ${result.error.message}", ongoing = false
                    )
                    Log.e(TAG, "書き出しに失敗しました", result.error)
                }
            }

            // 完了後の通知は残したまま、フォアグラウンド状態だけ解除してサービスを終了する
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, ongoing: Boolean = true, percent: Int? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, ongoing, percent))
    }

    private fun buildNotification(
        text: String,
        ongoing: Boolean,
        percent: Int? = null
    ): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("動画の書き出し")
            .setContentText(text)
            .setSmallIcon(com.example.dashcam.R.drawable.ic_recording)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (percent != null) setProgress(100, percent, false)
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "動画書き出し通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "動画の書き出し(メタデータ焼き込み)の進捗通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        exportJob?.cancel()
        isExporting = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
