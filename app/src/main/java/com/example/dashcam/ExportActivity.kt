package com.example.dashcam

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dashcam.export.ExportOptions
import com.example.dashcam.export.VideoExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 録画済み動画(Download/cam配下)を選択し、メタデータ(日時・位置・速度)を
 * 焼き込んで書き出す画面。
 *
 * 動画の選択はSAF(ACTION_OPEN_DOCUMENT)で行う。対応するメタデータJSONは
 * 同じ相対パス・同じベースファイル名から自動的に探す(見つからなければ焼き込み内容は空)。
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var textSelectedVideo: TextView
    private lateinit var buttonExport: Button
    private lateinit var textProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var checkDate: CheckBox
    private lateinit var checkLocation: CheckBox
    private lateinit var checkSpeed: CheckBox
    private lateinit var spinnerPosition: Spinner
    private lateinit var spinnerOrientation: Spinner

    private var selectedVideoUri: Uri? = null
    private var selectedVideoDisplayName: String? = null

    private val positionOptions = listOf(
        "左上" to ExportOptions.Position.TOP_LEFT,
        "右上" to ExportOptions.Position.TOP_RIGHT,
        "左下" to ExportOptions.Position.BOTTOM_LEFT,
        "右下" to ExportOptions.Position.BOTTOM_RIGHT
    )

    // 書き出し動画の向き。デフォルトは横向き(ドラレコ映像の標準的な向き)
    private val orientationOptions = listOf(
        "横向き" to ExportOptions.Orientation.LANDSCAPE,
        "縦向き" to ExportOptions.Orientation.PORTRAIT
    )

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        selectedVideoUri = uri
        selectedVideoDisplayName = queryDisplayName(uri)
        textSelectedVideo.text = "選択中: ${selectedVideoDisplayName ?: uri.toString()}"
        buttonExport.isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        title = "書き出し"

        textSelectedVideo = findViewById(R.id.textSelectedVideo)
        buttonExport = findViewById(R.id.buttonExport)
        textProgress = findViewById(R.id.textProgress)
        progressBar = findViewById(R.id.progressBar)
        checkDate = findViewById(R.id.checkDate)
        checkLocation = findViewById(R.id.checkLocation)
        checkSpeed = findViewById(R.id.checkSpeed)
        spinnerPosition = findViewById(R.id.spinnerPosition)
        spinnerOrientation = findViewById(R.id.spinnerOrientation)

        spinnerPosition.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            positionOptions.map { it.first }
        )
        // デフォルトは左下
        spinnerPosition.setSelection(positionOptions.indexOfFirst { it.second == ExportOptions.Position.BOTTOM_LEFT })

        spinnerOrientation.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            orientationOptions.map { it.first }
        )
        // デフォルトは横向き
        spinnerOrientation.setSelection(0)

        findViewById<Button>(R.id.buttonPickVideo).setOnClickListener {
            videoPickerLauncher.launch(arrayOf("video/mp4"))
        }

        buttonExport.setOnClickListener {
            startExport()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }

    /** 動画と同じ相対パス・同じベース名の .json をMediaStoreから探す */
    private fun findMetadataJsonUri(videoDisplayName: String): Uri? {
        val jsonName = videoDisplayName.substringBeforeLast('.') + ".json"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(jsonName)

        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun startExport() {
        val videoUri = selectedVideoUri ?: return
        val displayName = selectedVideoDisplayName ?: "output.mp4"

        val options = ExportOptions(
            showDate = checkDate.isChecked,
            showLocation = checkLocation.isChecked,
            showSpeed = checkSpeed.isChecked,
            position = positionOptions[spinnerPosition.selectedItemPosition].second,
            orientation = orientationOptions[spinnerOrientation.selectedItemPosition].second
        )

        buttonExport.isEnabled = false
        progressBar.visibility = View.VISIBLE
        textProgress.text = "準備中..."

        lifecycleScope.launch {
            val metadataUri = withContext(Dispatchers.IO) { findMetadataJsonUri(displayName) }
            if (metadataUri == null) {
                textProgress.text = "対応するメタデータが見つかりません(日時・位置なしで焼き込みます)"
            }

            val manager = VideoExportManager(this@ExportActivity)
            val result = manager.export(
                sourceVideoUri = videoUri,
                sourceDisplayName = displayName,
                metadataJsonUri = metadataUri,
                options = options,
                onProgress = { message ->
                    runOnUiThread { textProgress.text = message }
                }
            )

            progressBar.visibility = View.GONE
            buttonExport.isEnabled = true

            when (result) {
                is VideoExportManager.Result.Success -> {
                    textProgress.text = "書き出し完了: ${result.displayName}\n" +
                        "(Download/cam/dashcam_export に保存されました)"
                    Toast.makeText(this@ExportActivity, "書き出しが完了しました", Toast.LENGTH_LONG)
                        .show()
                }
                is VideoExportManager.Result.Failure -> {
                    textProgress.text = "書き出しに失敗しました: ${result.error.message}"
                    Toast.makeText(this@ExportActivity, "書き出しに失敗しました", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }
}
