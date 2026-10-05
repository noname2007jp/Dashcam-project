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
 * 画面下部の「開始」ボタンを押したときにのみ、パーミッション確認のうえ
 * フォアグラウンドサービスを起動する(自動開始はしない)。
 * 画面右上のメニュー(三本線)から設定画面(保存先フォルダ等)へ遷移できる。
 * プレビュー上をタップするとツールバー(紫のバー)の表示/非表示を切り替えられる。
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

    // 「開始」ボタン押下時に権限が無かった場合、権限取得後に自動で開始処理へ進めるためのフラグ
    private var startRequestedAfterPermission = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DashcamForegroundService.LocalBinder
            boundService = binder.getService()
            attachPreview()
            updatePauseButtonText()
            updateUiForRunningState(true)
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
            if (startRequestedAfterPermission) {
                beginRecording()
            }
        } else {
            Toast.makeText(
                this,
                "録画には全てのパーミッションの許可が必要です",
                Toast.LENGTH_LONG
            ).show()
        }
        startRequestedAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        previewView = findViewById(R.id.previewView)

        // プレビュー部分をタップするとツールバーの表示/非表示を切り替える
        previewView.setOnClickListener {
            toolbar.visibility = if (toolbar.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        buttonStart = findViewById(R.id.buttonStart)
        buttonStart.setOnClickListener {
            if (hasAllPermissions()) {
                beginRecording()
            } else {
                startRequestedAfterPermission = true
                permissionLauncher.launch(requiredPermissions)
            }
        }

        buttonPauseResume = findViewById(R.id.buttonPauseResume)
        buttonPauseResume.setOnClickListener {
            togglePauseResume()
        }

        buttonStop = findViewById(R.id.buttonStop)
        buttonStop.setOnClickListener {
            confirmAndStopDashcam()
        }

        // 自動開始はしない。既に起動中(バックグラウンドから復帰等)なら
        // onStart() でバインドして状態を復元する。
        updateUiForRunningState(DashcamForegroundService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        // 既にサービスが起動中(以前「開始」した状態が続いている)なら、
        // 新規開始はせずバインドのみ行って状態を復元する
        if (hasAllPermissions() && DashcamForegroundService.isRunning && !isBound) {
            bindToDashcamService()
        }
        updateUiForRunningState(DashcamForegroundService.isRunning)
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

    /** 「開始」ボタンから呼ばれる。パーミッションが揃っている前提でサービスを開始する。 */
    private fun beginRecording() {
        startDashcamService()
        if (!isBound) bindToDashcamService()
        updateUiForRunningState(true)
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
        // Activity(=この画面)の実際のDisplayの向きに合わせてプレビューの回転を設定する。
        // 画面回転でActivityが再生成されるたびにこの関数が呼ばれるため、常に
        // そのときの実際の画面の向きに同期する。
        val displayRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        boundService?.setPreviewTargetRotation(displayRotation)
        boundService?.attachPreviewSurfaceProvider(previewView.surfaceProvider)
    }

    /** 開始前/開始後でボタンの表示を切り替える */
    private fun updateUiForRunningState(running: Boolean) {
        buttonStart.visibility = if (running) View.GONE else View.VISIBLE
        buttonPauseResume.visibility = if (running) View.VISIBLE else View.GONE
        buttonStop.visibility = if (running) View.VISIBLE else View.GONE
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
            .setMessage("終了すると、以降は録画・各種検知が行われなくなります。「開始」ボタンでいつでも再開できます。")
            .setPositiveButton("終了する") { _, _ ->
                stopDashcamService()
                // バインドしたままだとサービスが完全に破棄されない(stopSelf()が
                // 保留されるだけになる)ため、ここで明示的にバインド解除する
                if (isBound) {
                    unbindService(serviceConnection)
                    isBound = false
                    boundService = null
                }
                updateUiForRunningState(false)
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
