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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DashcamForegroundService.LocalBinder
            boundService = binder.getService()
            attachPreview()
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
            startDashcamService()
            if (!isBound) bindToDashcamService()
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

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        previewView = findViewById(R.id.previewView)

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            confirmAndStopDashcam()
        }

        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }
        // 権限が既にある場合の起動・バインドは onStart() に任せる
    }

    override fun onStart() {
        super.onStart()
        if (hasAllPermissions()) {
            startDashcamService()
            if (!isBound) bindToDashcamService()
        }
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
