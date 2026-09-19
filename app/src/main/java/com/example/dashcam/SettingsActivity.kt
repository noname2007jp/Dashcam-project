package com.example.dashcam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.dashcam.settings.SettingsManager

/**
 * 保存先フォルダを設定する画面。
 * SAF(Storage Access Framework)でユーザーにフォルダを選ばせ、
 * 以降の読み書き権限を永続化する。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var textCurrentLocation: TextView

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        // 以降もアクセスできるよう永続的な読み書き権限を取得
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        settingsManager.saveLocationUri = uri
        updateCurrentLocationText()
        Toast.makeText(this, "保存先フォルダを設定しました", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "設定"

        settingsManager = SettingsManager(this)
        textCurrentLocation = findViewById(R.id.textCurrentLocation)

        findViewById<Button>(R.id.buttonChooseFolder).setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        findViewById<Button>(R.id.buttonClearFolder).setOnClickListener {
            settingsManager.clearSaveLocation()
            updateCurrentLocationText()
            Toast.makeText(this, "保存先の設定を解除しました", Toast.LENGTH_SHORT).show()
        }

        updateCurrentLocationText()
    }

    private fun updateCurrentLocationText() {
        val uri = settingsManager.saveLocationUri
        textCurrentLocation.text = if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            "現在の設定: $displayName へ自動コピー"
        } else {
            "現在の設定: アプリ専用フォルダのみ"
        }
    }
}
