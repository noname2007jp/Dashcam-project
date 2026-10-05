package com.example.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.dashcam.service.DashcamForegroundService

/**
 * 起動用Activity。
 * 必要なパーミッションをリクエストし、揃ったらフォアグラウンドサービスを起動する。
 * 画面右上のメニュー(三本線)から設定画面(保存先フォルダ等)へ遷移できる。
 *
 * 設置時の画角調整用に、Activityが表示されている間だけカメラのプレビュー映像を
 * PreviewViewに表示する。録画自体はサービス側で継続しており、Activityの表示・非表示は
 * 録画のON/OFFには影響しない。
 *
 * bindService/unbindServiceはonStart/onStopで対にして呼び出す(標準パターン)。
 * isBoundはbindService呼び出し時点で立て、onServiceConnectedのコールバックを
 * 待たずに二重バインドを防ぐ。
 */
class MainActivity : AppCompatActivity() {

    private var boundService: DashcamForegroundService? = null
    private var isBound = false
    private lateinit var previewView: PreviewView
    private lateinit var toolbar: Toolbar
    private lateinit var buttonStart: Button
    private lateinit var buttonPauseResume: Button
    private lateinit var buttonStop: Button

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DashcamForegroundService.LocalBinder
            boundService = binder.getService()
            attachPreview()
            updatePauseButtonText()
            updateUiForRecordingState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
        }
    }

    // アプリの録画機能に必須のパーミッション
    private val requiredPermissions: Array<String> by lazy {
        val base = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        // Android 13(API 33)以降は通知の許可も必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        base.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            // 権限が揃っても自動では録画を開始しない。
            // ユーザーが「録画開始」ボタンを押したときに開始する
            updateUiForRecordingState()
        } else {
            Toast.makeText(
                this,
                "録画には全てのパーミッションの許可が必要です",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        previewView = findViewById(R.id.previewView)

        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)
        buttonPauseResume = findViewById(R.id.buttonPauseResume)

        // プレビューをタップすると、上部のバー(Dashcam表示・メニュー)を表示/非表示切り替え
        previewView.setOnClickListener {
            toolbar.visibility = if (toolbar.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        // 起動時は自動開始せず、「録画開始」ボタンで明示的に開始する
        buttonStart.setOnClickListener {
            if (hasAllPermissions()) {
                startDashcam()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }

        buttonStop.setOnClickListener {
            confirmAndStopDashcam()
        }

        buttonPauseResume.setOnClickListener {
            togglePauseResume()
        }

        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }
        updateUiForRecordingState()
    }

    override fun onStart() {
        super.onStart()
        // サービスが既に動いている場合(設定画面から戻ってきた等)だけ再バインドする。
        // 自動的な録画開始は行わない(開始ボタン操作を必須にするため)
        if (hasAllPermissions() && DashcamForegroundService.isServiceActive && !isBound) {
            bindToDashcamService()
        }
        updateUiForRecordingState()
    }

    /**
     * 録画(サービス)の稼働状態に応じてボタンの表示を切り替える。
     * 未起動: 「録画開始」のみ表示 / 稼働中: 一時停止・終了を表示
     */
    private fun updateUiForRecordingState() {
        val active = DashcamForegroundService.isServiceActive
        buttonStart.visibility = if (active) View.GONE else View.VISIBLE
        buttonPauseResume.visibility = if (active) View.VISIBLE else View.GONE
        buttonStop.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun startDashcam() {
        startDashcamService()
        if (!isBound) bindToDashcamService()
        updateUiForRecordingState()
    }

    override fun onStop() {
        super.onStop()
        // Activityが表示されなくなったらプレビュー描画は止める(録画自体は継続)
        boundService?.detachPreviewSurfaceProvider()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_export -> {
                startActivity(Intent(this, ExportActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startDashcamService() {
        val intent = Intent(this, DashcamForegroundService::class.java).apply {
            action = DashcamForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindToDashcamService() {
        val intent = Intent(this, DashcamForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true
    }

    private fun attachPreview() {
        boundService?.attachPreviewSurfaceProvider(previewView.surfaceProvider)
    }

    /**
     * 録画の一時停止/再開を切り替える。「終了」と違い、サービス自体は停止せず
     * 録画のみ止める(書き出し作業中など、録画を一時的に止めたいときに使う想定)。
     */
    private fun togglePauseResume() {
        val service = boundService ?: return
        // startService()は非同期のため、呼び出し前の状態を基準に次の表示を決める
        val wasPaused = service.isPaused()
        val intent = Intent(this, DashcamForegroundService::class.java).apply {
            action = if (wasPaused) {
                DashcamForegroundService.ACTION_RESUME
            } else {
                DashcamForegroundService.ACTION_PAUSE
            }
        }
        startService(intent)
        buttonPauseResume.text = if (wasPaused) "一時停止" else "再開"
    }

    private fun updatePauseButtonText() {
        buttonPauseResume.text = if (boundService?.isPaused() == true) "再開" else "一時停止"
    }

    /** 誤操作防止のため確認ダイアログを出してから録画を終了する */
    private fun confirmAndStopDashcam() {
        AlertDialog.Builder(this)
            .setTitle("ドラレコを終了しますか?")
            .setMessage("終了すると、以降は録画・各種検知が行われなくなります。")
            .setPositiveButton("終了する") { _, _ ->
                stopDashcamService()
                finish()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** フォアグラウンドサービスに停止を指示する(サービス自身がstopForeground/stopSelfを行う) */
    private fun stopDashcamService() {
        val intent = Intent(this, DashcamForegroundService::class.java).apply {
            action = DashcamForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}
