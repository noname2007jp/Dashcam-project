package com.example.dashcam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
 *
 * 端末が超広角レンズに対応している場合(ズーム倍率1.0未満に対応)、
 * 使用するレンズ(標準/超広角)も選択できる。Pixel等の多くの機種では
 * 広角・超広角は別カメラIDではなく、1つのカメラのズーム倍率で切り替わる構成のため、
 * ズーム倍率ベースで選択肢を組み立てている。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var textCurrentLocation: TextView
    private lateinit var textLensDescription: TextView
    private lateinit var radioGroupLens: RadioGroup
    private lateinit var spinnerCapacity: Spinner
    private lateinit var seekShockDriving: SeekBar
    private lateinit var textShockDrivingValue: TextView
    private lateinit var seekShockParking: SeekBar
    private lateinit var textShockParkingValue: TextView
    private lateinit var seekTailgating: SeekBar
    private lateinit var textTailgatingValue: TextView

    private lateinit var spinnerScreenTimeout: Spinner

    private var lensOptions: List<CameraLensHelper.ZoomLensOption> = emptyList()

    // SeekBarのprogress(0始まり)からG値(0.1刻み)への変換: value = MIN + progress * STEP
    private val thresholdStep = 0.1f
    private val thresholdMin = 0.1f

    private val screenTimeoutOptions = listOf(
        "常時点灯(OFFにしない)" to null,
        "30秒" to 30,
        "1分" to 60,
        "3分" to 180,
        "5分" to 300,
        "10分" to 600
    )

    private val capacityOptions = listOf(
        "無制限" to null,
        "8GB" to 8,
        "16GB" to 16,
        "32GB" to 32,
        "64GB" to 64,
        "128GB" to 128,
        "256GB" to 256
    )

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        if (isOverlappingWithDownloadsCam(uri)) {
            AlertDialog.Builder(this)
                .setTitle("同じ保存先は選択できません")
                .setMessage(
                    "選択したフォルダは、録画の保存先である Download/cam と同じか、" +
                        "それを含む(または含まれる)場所です。\n\n" +
                        "この状態で追加コピー先に設定すると、同じファイルへコピーしようとして" +
                        "エラーになります。Download/cam とは別のフォルダを選んでください。"
                )
                .setPositiveButton("OK", null)
                .show()
            return@registerForActivityResult
        }

        // 以降もアクセスできるよう永続的な読み書き権限を取得
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        settingsManager.saveLocationUri = uri
        updateCurrentLocationText()
        Toast.makeText(this, "保存先フォルダを設定しました", Toast.LENGTH_SHORT).show()
    }

    /**
     * 選択されたフォルダが Download/cam と同じ場所、その内側、またはその外側で
     * Download/cam を含んでしまう場所かどうかを判定する。
     * (例: Download/cam 自体、Download/cam/dashcam_loop 等のサブフォルダ、
     *  逆にDownload全体を選んだ場合など)
     *
     * ドキュメントIDの形式は外部ストレージプロバイダ(primary:...)を前提とした簡易判定のため、
     * 他のプロバイダ(別アプリが提供するクラウドストレージ等)を選んだ場合は検出できないことがある。
     */
    private fun isOverlappingWithDownloadsCam(uri: Uri): Boolean {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val normalized = docId.substringAfter(':', docId)
                .replace('\\', '/')
                .trim('/')
            val target = "Download/cam"
            normalized.equals(target, ignoreCase = true) ||
                normalized.startsWith("$target/", ignoreCase = true) ||
                target.startsWith("$normalized/", ignoreCase = true) ||
                normalized.equals("Download", ignoreCase = true)
        } catch (e: Exception) {
            false // 判定できない場合は誤ってブロックしないようfalseとする
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "設定"

        settingsManager = SettingsManager(this)
        textCurrentLocation = findViewById(R.id.textCurrentLocation)
        textLensDescription = findViewById(R.id.textLensDescription)
        radioGroupLens = findViewById(R.id.radioGroupLens)
        spinnerCapacity = findViewById(R.id.spinnerCapacity)
        seekShockDriving = findViewById(R.id.seekShockDriving)
        textShockDrivingValue = findViewById(R.id.textShockDrivingValue)
        seekShockParking = findViewById(R.id.seekShockParking)
        textShockParkingValue = findViewById(R.id.textShockParkingValue)
        seekTailgating = findViewById(R.id.seekTailgating)
        textTailgatingValue = findViewById(R.id.textTailgatingValue)
        spinnerScreenTimeout = findViewById(R.id.spinnerScreenTimeout)

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
        setupCapacitySpinner()
        setupThresholdSliders()
        setupScreenTimeoutSpinner()

        findViewById<android.widget.CheckBox>(R.id.checkParkingMotion).apply {
            isChecked = settingsManager.parkingMotionRecordingEnabled
            setOnCheckedChangeListener { _, checked ->
                settingsManager.parkingMotionRecordingEnabled = checked
                Toast.makeText(
                    this@SettingsActivity, "次回の駐車判定から反映されます", Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<android.widget.CheckBox>(R.id.checkConfirmOnStop).apply {
            isChecked = settingsManager.confirmOnStop
            setOnCheckedChangeListener { _, checked -> settingsManager.confirmOnStop = checked }
        }
    }

    private fun setupScreenTimeoutSpinner() {
        spinnerScreenTimeout.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            screenTimeoutOptions.map { it.first }
        )

        val currentSeconds = settingsManager.screenTimeoutSeconds
        val currentIndex = screenTimeoutOptions.indexOfFirst { it.second == currentSeconds }
        spinnerScreenTimeout.setSelection(if (currentIndex >= 0) currentIndex else 0)

        spinnerScreenTimeout.post {
            spinnerScreenTimeout.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        settingsManager.screenTimeoutSeconds = screenTimeoutOptions[position].second
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
        }
    }

    private fun setupCapacitySpinner() {
        spinnerCapacity.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            capacityOptions.map { it.first }
        )

        val currentGb = settingsManager.maxLoopStorageGb
        val currentIndex = capacityOptions.indexOfFirst { it.second == currentGb }
        spinnerCapacity.setSelection(if (currentIndex >= 0) currentIndex else 0)

        spinnerCapacity.post {
            spinnerCapacity.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        settingsManager.maxLoopStorageGb = capacityOptions[position].second
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
        }
    }

    /** G値 -> SeekBarのprogress値に変換(0.1刻み) */
    private fun thresholdToProgress(value: Float): Int {
        return ((value - thresholdMin) / thresholdStep).toInt().coerceAtLeast(0)
    }

    /** SeekBarのprogress値 -> G値に変換(0.1刻み) */
    private fun progressToThreshold(progress: Int): Float {
        return thresholdMin + progress * thresholdStep
    }

    private fun setupThresholdSliders() {
        seekShockDriving.progress = thresholdToProgress(settingsManager.shockDrivingThreshold)
        textShockDrivingValue.text =
            "走行中の衝撃検知: %.1fG".format(settingsManager.shockDrivingThreshold)
        seekShockDriving.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToThreshold(progress)
                textShockDrivingValue.text = "走行中の衝撃検知: %.1fG".format(value)
                if (fromUser) settingsManager.shockDrivingThreshold = value
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Toast.makeText(
                    this@SettingsActivity, "設定を保存しました(次回起動時から反映)", Toast.LENGTH_SHORT
                ).show()
            }
        })

        seekShockParking.progress = thresholdToProgress(settingsManager.shockParkingThreshold)
        textShockParkingValue.text =
            "駐車監視中の衝撃検知: %.1fG".format(settingsManager.shockParkingThreshold)
        seekShockParking.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToThreshold(progress)
                textShockParkingValue.text = "駐車監視中の衝撃検知: %.1fG".format(value)
                if (fromUser) settingsManager.shockParkingThreshold = value
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Toast.makeText(
                    this@SettingsActivity, "設定を保存しました(次回起動時から反映)", Toast.LENGTH_SHORT
                ).show()
            }
        })

        seekTailgating.progress = thresholdToProgress(settingsManager.tailgatingThreshold)
        textTailgatingValue.text =
            "急ブレーキ(煽り運転の可能性)検知: %.1fG".format(settingsManager.tailgatingThreshold)
        seekTailgating.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToThreshold(progress)
                textTailgatingValue.text = "急ブレーキ(煽り運転の可能性)検知: %.1fG".format(value)
                if (fromUser) settingsManager.tailgatingThreshold = value
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Toast.makeText(
                    this@SettingsActivity, "設定を保存しました(次回起動時から反映)", Toast.LENGTH_SHORT
                ).show()
            }
        })
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

    /** カメラ権限がある前提で、端末が対応するレンズ(ズーム倍率)を確認する */
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
                lensOptions = CameraLensHelper.listZoomLensOptions(provider)
                renderLensOptions()
            } catch (e: Exception) {
                textLensDescription.text = "レンズ情報の取得に失敗しました"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun renderLensOptions() {
        radioGroupLens.removeAllViews()

        if (lensOptions.size <= 1) {
            textLensDescription.text =
                "この端末では超広角レンズへの切り替えに対応する情報が確認できませんでした" +
                    "(標準レンズのみ使用されます)"
            return
        }

        textLensDescription.text =
            "この端末は超広角レンズに対応しています。使用するレンズを選択してください " +
                "(ズーム倍率での切り替えのため、録画中に再起動すると反映されます)"

        val currentRatio = settingsManager.preferredZoomRatio
        val idToRatio = mutableMapOf<Int, Float?>()

        lensOptions.forEach { option ->
            val button = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "${option.label} (ズーム${option.zoomRatio}x)"
                // 「標準(1.0x)」はpreferredZoomRatio=nullと同じ扱いにする
                isChecked = if (option.zoomRatio == 1.0f) {
                    currentRatio == null || currentRatio == 1.0f
                } else {
                    currentRatio == option.zoomRatio
                }
            }
            idToRatio[button.id] = if (option.zoomRatio == 1.0f) null else option.zoomRatio
            radioGroupLens.addView(button)
        }

        radioGroupLens.setOnCheckedChangeListener { _, checkedId ->
            settingsManager.preferredZoomRatio = idToRatio[checkedId]
            Toast.makeText(this, "レンズを設定しました(再起動後に反映)", Toast.LENGTH_SHORT).show()
        }
    }
}
