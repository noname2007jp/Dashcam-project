package com.example.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
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
 *
 * 画面下部の「開始」ボタンを押したときにのみ、パーミッション確認のうえ
 * フォアグラウンドサービスを起動して録画を開始する(自動開始はしない)。
 * ただし**カメラのプレビュー表示はアプリ起動直後から行う**(録画のON/OFFとは独立)。
 * 画面右上のメニュー(三本線)から設定画面(保存先フォルダ等)へ遷移できる。
 * プレビュー上をタップするとツールバー(紫のバー)の表示/非表示を切り替えられる。
 *
 * プレビューの回転について(重要):
 * プレビューの映像の向きは、最終的にCameraXの `Preview.targetRotation` で決まる。
 * 一方 `PreviewView` は自身の `Display.rotation` とセンサー角度から表示変換を作るが、
 * `targetRotation` は自分では設定しない。したがって両者が食い違うと、
 * 「プレビューだけが縦長になる/右が上になる」といった症状が出る。
 * そのため本Activityは
 *  1. バインド直後(onServiceConnected)
 *  2. DisplayListener(onDisplayChanged)
 *  3. onConfigurationChanged
 * の3経路で `syncPreviewRotation()` を呼び、常に現在の画面の向きをPreviewへ同期する。
 * 特に90度↔270度の入れ替わり(ランドスケープ反転)ではActivityの構成変更自体が
 * 発生しないため、DisplayListenerによる検知が必須になる。
 *
 * bindService/unbindServiceはonStart/onStopで対にして呼び出す(標準パターン)。
 * isBoundはbindService呼び出し時点で立て、onServiceConnectedのコールバックを
 * 待たずに二重バインドを防ぐ。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREVIEW_HINT = "設置時の画角調整用プレビューです"
        private const val PREVIEW_HINT_NEED_PERMISSION = "カメラの許可が必要です(プレビューを表示できません)"

        /** サービス停止直後にプレビュー表示を復帰させるための待ち時間 */
        private const val PREVIEW_REBIND_DELAY_MS = 400L
    }

    private var boundService: DashcamForegroundService? = null
    private var isBound = false
    private lateinit var previewView: PreviewView
    private lateinit var toolbar: Toolbar
    private lateinit var textStatus: TextView
    private lateinit var buttonStart: Button
    private lateinit var buttonPauseResume: Button
    private lateinit var buttonStop: Button

    // 「開始」ボタン押下時に権限が無かった場合、権限取得後に自動で開始処理へ進めるためのフラグ
    private var startRequestedAfterPermission = false

    private val displayManager: DisplayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /**
     * 画面の向き(Display.rotation)の変化を検知するリスナー。
     * ランドスケープの左右反転(90度↔270度)では構成変更が発生しないため、
     * このリスナーが無いとプレビューの回転が古いまま固定されてしまう。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == previewView.display?.displayId) {
                syncPreviewRotation()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DashcamForegroundService.LocalBinder
            val svc = binder.getService()
            boundService = svc
            // 録画が未開始(バインドのみ)でもプレビューを出すため、
            // カメラとプレビューだけを先に準備させる。録画は開始されない。
            svc.ensureCameraPrepared()
            attachPreview()
            updatePauseButtonText()
            updateUiForRunningState(DashcamForegroundService.isRunning)
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
            } else {
                // 起動時のプレビュー表示のためのバインド(録画は開始しない)
                if (!isBound) bindToDashcamService()
            }
            updatePermissionHint(true)
        } else {
            Toast.makeText(
                this,
                "録画には全てのパーミッションの許可が必要です",
                Toast.LENGTH_LONG
            ).show()
            updatePermissionHint(false)
        }
        startRequestedAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        previewView = findViewById(R.id.previewView)
        textStatus = findViewById(R.id.textStatus)

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

        // 自動録画開始はしない。既に起動中(バックグラウンドから復帰等)なら
        // onStart() でバインドして状態を復元する。
        updateUiForRunningState(DashcamForegroundService.isRunning)

        // 起動直後からプレビューを出すため、未許可なら起動時に権限を要求する
        // (許可されたら permissionLauncher のコールバックでバインドしてプレビュー表示)
        if (!hasAllPermissions()) {
            updatePermissionHint(false)
            startRequestedAfterPermission = false
            permissionLauncher.launch(requiredPermissions)
        } else {
            updatePermissionHint(true)
        }
    }

    override fun onStart() {
        super.onStart()
        // 画面の向きの変化を監視する(90度↔270度の反転検知に必須)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        // 録画中かどうかに関わらずバインドする。
        // バインドのみ(録画未開始)でもサービス側でカメラとプレビューが準備され、
        // アプリ起動時からカメラ映像が表示される。
        if (hasAllPermissions() && !isBound) {
            bindToDashcamService()
        }
        updateUiForRunningState(DashcamForegroundService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        // レイアウト確定後・復帰時に現在の画面の向きを同期する
        previewView.post { syncPreviewRotation() }
    }

    override fun onStop() {
        super.onStop()
        displayManager.unregisterDisplayListener(displayListener)
        // Activityが表示されなくなったらプレビュー描画は止める(録画自体は継続)
        boundService?.detachPreviewSurfaceProvider()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
    }

    /**
     * Activityが再生成されずに構成変更を処理するケース(マルチウィンドウ等)。
     * 画面の向きが変わったら必ずプレビューの回転を同期し直す。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        previewView.post { syncPreviewRotation() }
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

    private fun updatePermissionHint(granted: Boolean) {
        textStatus.text = if (granted) PREVIEW_HINT else PREVIEW_HINT_NEED_PERMISSION
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

    /**
     * 現在の画面の向きをプレビューへ同期する。
     *
     * 以前はバインド時の1回だけ設定していたため、画面を回転させても
     * プレビューの回転が古いままになり「縦長の映像になる」不具合が出ていた。
     * DisplayListener / onConfigurationChanged / onResume から毎回呼ぶ。
     */
    private fun syncPreviewRotation() {
        // Viewがまだウィンドウにアタッチされていないと display は null になる。
        // ここで ROTATION_0 等の既定値にフォールバックすると、誤った回転で
        // 固定されてしまうため、未アタッチ時は何もしない(後続のコールバックで同期される)。
        val display = previewView.display ?: return
        boundService?.setPreviewTargetRotation(display.rotation)
        Log.d(TAG, "プレビューの回転を同期: display.rotation=${display.rotation}")
    }

    /** プレビューの描画先と回転をサービスへ渡す。 */
    private fun attachPreview() {
        syncPreviewRotation()
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
                // 録画終了後も「設置時の画角調整用プレビュー」は出したままにしたいので、
                // サービスが完全に破棄されたあとにバインドし直してカメラ映像を復帰させる
                previewView.postDelayed({
                    if (!isFinishing && hasAllPermissions() && !isBound) {
                        bindToDashcamService()
                    }
                }, PREVIEW_REBIND_DELAY_MS)
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
