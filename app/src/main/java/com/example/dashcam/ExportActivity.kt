package com.example.dashcam

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dashcam.export.ExportForegroundService
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
 *
 * 書き出し処理自体は ExportForegroundService に委譲する。これにより、
 * この画面を閉じても(数十秒〜数分かかる)書き出し処理が中断されず、
 * 進捗は通知で確認できる。
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var textSelectedVideo: TextView
    private lateinit var buttonExport: Button
    private lateinit var textProgress: TextView
    private lateinit var checkDate: CheckBox
    private lateinit var checkLocation: CheckBox
    private lateinit var checkSpeed: CheckBox
    private lateinit var spinnerPosition: Spinner
    private lateinit var radioGroupOrientation: RadioGroup

    private var selectedVideoUri: Uri? = null
    private var selectedVideoDisplayName: String? = null

    private val positionOptions = listOf(
        "左上" to ExportOptions.Position.TOP_LEFT,
        "右上" to ExportOptions.Position.TOP_RIGHT,
        "左下" to ExportOptions.Position.BOTTOM_LEFT,
        "右下" to ExportOptions.Position.BOTTOM_RIGHT
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
        checkDate = findViewById(R.id.checkDate)
        checkLocation = findViewById(R.id.checkLocation)
        checkSpeed = findViewById(R.id.checkSpeed)
        spinnerPosition = findViewById(R.id.spinnerPosition)
        radioGroupOrientation = findViewById(R.id.radioGroupOrientation)

        // 進捗バーはフォアグラウンドサービス側の通知で確認する運用に変更したため非表示にする
        findViewById<ProgressBar>(R.id.progressBar).visibility = android.view.View.GONE

        spinnerPosition.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            positionOptions.map { it.first }
        )
        // デフォルトは左下
        spinnerPosition.setSelection(positionOptions.indexOfFirst { it.second == ExportOptions.Position.BOTTOM_LEFT })

        findViewById<Button>(R.id.buttonPickVideo).setOnClickListener {
            videoPickerLauncher.launch(arrayOf("video/mp4"))
        }

        buttonExport.setOnClickListener {
            startExport()
        }

        if (ExportForegroundService.isExporting) {
            textProgress.text = "既に別の書き出しが進行中です。完了を通知でお待ちください。"
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
        if (ExportForegroundService.isExporting) {
            Toast.makeText(this, "既に書き出し処理が実行中です", Toast.LENGTH_SHORT).show()
            return
        }

        val videoUri = selectedVideoUri ?: return
        val displayName = selectedVideoDisplayName ?: "output.mp4"

        val options = ExportOptions(
            showDate = checkDate.isChecked,
            showLocation = checkLocation.isChecked,
            showSpeed = checkSpeed.isChecked,
            position = positionOptions[spinnerPosition.selectedItemPosition].second
        )

        val orientation = if (radioGroupOrientation.checkedRadioButtonId == R.id.radioPortrait) {
            VideoExportManager.OutputOrientation.PORTRAIT
        } else {
            VideoExportManager.OutputOrientation.LANDSCAPE
        }

        textProgress.text = "メタデータを確認中..."

        lifecycleScope.launch {
            val metadataUri = withContext(Dispatchers.IO) { findMetadataJsonUri(displayName) }

            val intent = Intent(this@ExportActivity, ExportForegroundService::class.java).apply {
                action = ExportForegroundService.ACTION_START_EXPORT
                putExtra(ExportForegroundService.EXTRA_SOURCE_URI, videoUri)
                putExtra(ExportForegroundService.EXTRA_SOURCE_DISPLAY_NAME, displayName)
                if (metadataUri != null) {
                    putExtra(ExportForegroundService.EXTRA_METADATA_URI, metadataUri)
                }
                putExtra(ExportForegroundService.EXTRA_SHOW_DATE, options.showDate)
                putExtra(ExportForegroundService.EXTRA_SHOW_LOCATION, options.showLocation)
                putExtra(ExportForegroundService.EXTRA_SHOW_SPEED, options.showSpeed)
                putExtra(ExportForegroundService.EXTRA_POSITION, options.position.name)
                putExtra(ExportForegroundService.EXTRA_ORIENTATION, orientation.name)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            textProgress.text = if (metadataUri == null) {
                "書き出しを開始しました(対応するメタデータが見つからないため、日時・位置なしで焼き込みます)。\n進捗は通知で確認できます。この画面を閉じても処理は継続します。"
            } else {
                "書き出しを開始しました。進捗は通知で確認できます。この画面を閉じても処理は継続します。"
            }
            Toast.makeText(this@ExportActivity, "書き出しを開始しました", Toast.LENGTH_SHORT).show()
        }
    }
}
