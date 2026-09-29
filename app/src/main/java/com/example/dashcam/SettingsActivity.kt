package com.example.dashcam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.dashcam.camera.CameraLensHelper
import com.example.dashcam.settings.SettingsManager

/**
 * 保存先フォルダ・使用レンズを設定する画面。
 * SAF(Storage Access Framework)でユーザーにフォルダを選ばせ、
 * 以降の読み書き権限を永続化する。
 * 端末が複数の背面カメラ(広角等)を持つ場合、使用するレンズも選択できる。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var textCurrentLocation: TextView
    private lateinit var textLensDescription: TextView
    private lateinit var radioGroupLens: RadioGroup

    private var lensOptions: List<Pair<CameraLensHelper.BackCameraOption, String>> = emptyList()

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
        textLensDescription = findViewById(R.id.textLensDescription)
        radioGroupLens = findViewById(R.id.radioGroupLens)

        findViewById<Button>(R.id.buttonChooseFolder).setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        findViewById<Button>(R.id.buttonClearFolder).setOnClickListener {
            settingsManager.clearSaveLocation()
            updateCurrentLocationText()
            Toast.makeText(this, "保存先の設定を解除しました", Toast.LENGTH_SHORT).show()
        }

        updateCurrentLocationText()
        loadLensOptions()
    }

    private fun updateCurrentLocationText() {
        val uri = settingsManager.saveLocationUri
        textCurrentLocation.text = if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            "録画先: Download/cam(常時) + $displayName (追加コピー)"
        } else {
            "録画先: Download/cam のみ"
        }
    }

    /** カメラ権限がある前提で、端末が持つ背面カメラ(広角含む)を列挙する */
    private fun loadLensOptions() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            textLensDescription.text = "カメラ権限が許可されていないため確認できません"
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                lensOptions = CameraLensHelper.listBackCameraOptionsWithLabels(provider)
                renderLensOptions()
            } catch (e: Exception) {
                textLensDescription.text = "レンズ情報の取得に失敗しました"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun renderLensOptions() {
        radioGroupLens.removeAllViews()

        if (lensOptions.size <= 1) {
            textLensDescription.text = "この端末では背面カメラは1つのみ検出されました(選択不要)"
            return
        }

        textLensDescription.text =
            "複数の背面カメラが検出されました。使用するレンズを選択してください" +
                "(焦点距離から推定したラベルのため、実際の画角と異なる場合があります)"

        val currentId = settingsManager.preferredCameraId

        // 「標準(自動)」選択肢
        val autoButton = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "標準(自動選択)"
            isChecked = currentId == null
        }
        radioGroupLens.addView(autoButton)

        val idToViewId = mutableMapOf<Int, String>()

        lensOptions.forEach { (option, label) ->
            val button = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "$label (カメラID: ${option.cameraId})"
                isChecked = currentId == option.cameraId
            }
            idToViewId[button.id] = option.cameraId
            radioGroupLens.addView(button)
        }

        radioGroupLens.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == autoButton.id) {
                settingsManager.preferredCameraId = null
                Toast.makeText(this, "標準(自動選択)に設定しました", Toast.LENGTH_SHORT).show()
            } else {
                val cameraId = idToViewId[checkedId]
                settingsManager.preferredCameraId = cameraId
                Toast.makeText(this, "レンズを設定しました(再起動後に反映)", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
}
