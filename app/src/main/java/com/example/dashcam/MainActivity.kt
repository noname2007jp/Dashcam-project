package com.example.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.dashcam.service.DashcamForegroundService
import com.example.dashcam.settings.SettingsManager

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
    private lateinit var blackoutOverlay: View
    private lateinit var settingsManager: SettingsManager

    // 「開始」ボタン押下時に権限が無かった場合、権限取得後に自動で開始処理へ進めるためのフラグ
    private var startRequestedAfterPermission = false

    // 画面の疑似消灯(一定時間操作がないと輝度を下げて黒オーバーレイを表示する)
    private val screenTimeoutHandler = Handler(Looper.getMainLooper())
    private val pseudoOffRunnable = Runnable { applyPseudoOff() }
    private var isPseudoOff = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DashcamForegroundService.LocalBinder
            boundService = binder.getService()
            attachPreview()
            updatePauseButtonText()
            updateUiForRunningState(DashcamForegroundService.isRecordingActive)
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
            // 権限が揃ったら、まずプレビュー待機状態でサービスを起動する
            ensureServicePreparedAndBound()
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

        settingsManager = SettingsManager(this)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        previewView = findViewById(R.id.previewView)
        blackoutOverlay = findViewById(R.id.blackoutOverlay)

        // 疑似消灯中にタップされたら復帰する
        blackoutOverlay.setOnClickListener {
            cancelPseudoOff()
            schedulePseudoOff()
        }

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

        findViewById<Button>(R.id.buttonQuitApp).setOnClickListener { quitApp() }

        buttonStop = findViewById(R.id.buttonStop)
        buttonStop.setOnClickListener {
            confirmAndStopDashcam()
        }

        // 起動時はプレビューのみ表示(画角調整用)。録画は「開始」ボタンで始まる。
        // 権限が無い場合は起動直後に許可を求める。
        updateUiForRunningState(DashcamForegroundService.isRecordingActive)
        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        // サービスが未起動ならプレビュー待機モードで起動し、起動中ならバインドして状態を復元する
        if (hasAllPermissions()) {
            ensureServicePreparedAndBound()
        }
        updateUiForRunningState(DashcamForegroundService.isRecordingActive)
        schedulePseudoOff()
    }

    override fun onStop() {
        super.onStop()
        // Activityが表示されなくなったらプレビュー描画は止める(録画自体は継続)
        boundService?.detachPreviewSurfaceProvider()
        if (isBound) {
            // 録画中でなければ(プレビュー待機のみ)、画面を離れるときにカメラを閉じる
            if (!isChangingConfigurations) boundService?.shutdownIfIdle()
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
        cancelPseudoOff()
    }

    /**
     * Androidが画面へのタッチ/キー操作のたびに呼び出すコールバック。
     * これを使って疑似消灯までのタイマーをリセットし、消灯中であれば復帰させる。
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isPseudoOff) {
            wakeFromPseudoOff()
        }
        schedulePseudoOff()
    }

    /** 設定された時間が経過したら疑似消灯するようタイマーを(再)設定する */
    private fun schedulePseudoOff() {
        cancelPseudoOff()
        val timeoutSeconds = settingsManager.screenTimeoutSeconds ?: return // 無効化設定
        screenTimeoutHandler.postDelayed(pseudoOffRunnable, timeoutSeconds * 1000L)
    }

    private fun cancelPseudoOff() {
        screenTimeoutHandler.removeCallbacks(pseudoOffRunnable)
    }

    /** 輝度を最小にして黒いオーバーレイを表示する(疑似消灯) */
    private fun applyPseudoOff() {
        isPseudoOff = true
        blackoutOverlay.visibility = View.VISIBLE
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
    }

    /** 輝度・オーバーレイを元に戻す */
    private fun wakeFromPseudoOff() {
        isPseudoOff = false
        blackoutOverlay.visibility = View.GONE
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
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
            R.id.action_quit -> {
                quitApp()
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

    /** サービスがまだ無ければプレビュー待機モードで起動し、バインドしてプレビューを表示する */
    private fun ensureServicePreparedAndBound() {
        // 前回のサービスが停止処理中なら、破棄されるのを待ってから起動し直す
        if (DashcamForegroundService.isStopping) {
            reprepareAfterStop(0)
            return
        }
        if (!DashcamForegroundService.isRunning) {
            startDashcamService(DashcamForegroundService.ACTION_START)
        }
        if (!isBound) bindToDashcamService()
    }

    /** 「開始」ボタンから呼ばれる。パーミッションが揃っている前提で録画セッションを開始する。 */
    private fun beginRecording() {
        startDashcamService(DashcamForegroundService.ACTION_BEGIN_RECORDING)
        if (!isBound) bindToDashcamService()
        updateUiForRunningState(true)
        updatePauseButtonText()
    }

    private fun startDashcamService(action: String) {
        val intent = Intent(this, DashcamForegroundService::class.java).apply {
            this.action = action
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
        // 画面は横向き固定(AndroidManifest)のため、PreviewViewが自動で正しく補正する
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
        if (!settingsManager.confirmOnStop) {
            performStop()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("ドラレコを終了しますか?")
            .setMessage("終了すると、以降は録画・各種検知が行われなくなります。「開始」ボタンでいつでも再開できます。")
            .setPositiveButton("終了する") { _, _ -> performStop() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * アプリ自体を終了する(録画・カメラ・サービスを全て止めて画面も閉じる)。
     * 録画中の場合のみ、設定に応じて確認ダイアログを出す。
     */
    private fun quitApp() {
        if (DashcamForegroundService.isRecordingActive && settingsManager.confirmOnStop) {
            AlertDialog.Builder(this)
                .setTitle("アプリを終了しますか?")
                .setMessage("録画中です。終了すると録画・各種検知も止まります。")
                .setPositiveButton("終了する") { _, _ -> doQuitApp() }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            doQuitApp()
        }
    }

    private fun doQuitApp() {
        cancelPseudoOff()
        if (DashcamForegroundService.isRunning) stopDashcamService()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
        finishAndRemoveTask()
    }

    private fun performStop() {
        stopDashcamService()
        // バインドしたままだとサービスが完全に破棄されない(stopSelf()が
        // 保留されるだけになる)ため、ここで明示的にバインド解除する
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
        updateUiForRunningState(false)
        // 終了後もプレビューは見られるよう、サービス破棄を待って待機モードで再起動する
        reprepareAfterStop(0)
    }

    private fun reprepareAfterStop(attempt: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            ) return@postDelayed
            if (!DashcamForegroundService.isRunning) {
                if (hasAllPermissions()) ensureServicePreparedAndBound()
            } else if (attempt < 10) {
                reprepareAfterStop(attempt + 1)
            }
        }, 500)
    }

    /** フォアグラウンドサービスに停止を指示する(サービス自身がstopForeground/stopSelfを行う) */
    private fun stopDashcamService() {
        val intent = Intent(this, DashcamForegroundService::class.java).apply {
            action = DashcamForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}
