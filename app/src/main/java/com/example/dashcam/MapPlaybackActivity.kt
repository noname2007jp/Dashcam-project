package com.example.dashcam

import android.annotation.SuppressLint
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dashcam.metadata.MetadataParser
import com.example.dashcam.metadata.MetadataRecorder
import com.example.dashcam.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 録画済み動画のメタデータJSONから、走行ルートを地図上で再生する画面(「走行を見る」)。
 *
 * 地図はWebView上のJavaScript(assets/map.html)で描画する。既定はOpenStreetMap(Leaflet)、
 * 設定でGoogleマップ(Maps JavaScript API。APIキーが必要)を選べる。
 * 衝撃・急ブレーキの発生位置は色付きの印で表示し、タップすると再生位置がそこへ飛ぶ。
 */
class MapPlaybackActivity : AppCompatActivity() {

    private class Point(val sample: MetadataRecorder.Sample, val lat: Double, val lon: Double)

    private lateinit var webMap: WebView
    private lateinit var textStatus: TextView
    private lateinit var textNotice: TextView
    private lateinit var textInfo: TextView
    private lateinit var seek: SeekBar
    private lateinit var buttonPlay: Button
    private lateinit var buttonSpeed: Button

    private var provider = SettingsManager.MAP_OSM
    private var mapReady = false
    private var points: List<Point> = emptyList()
    private var events: List<Pair<Int, MetadataRecorder.Event>> = emptyList()

    private var index = 0
    private var playing = false
    private var speed = 1
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            if (index < points.size - 1) {
                index++
                updatePosition()
                handler.postDelayed(this, 1000L / speed)
            } else {
                setPlaying(false)
            }
        }
    }

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadVideo(uri) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_playback)
        title = "走行を見る"

        webMap = findViewById(R.id.webMap)
        textStatus = findViewById(R.id.textStatus)
        textNotice = findViewById(R.id.textNotice)
        textInfo = findViewById(R.id.textInfo)
        seek = findViewById(R.id.seekPosition)
        buttonPlay = findViewById(R.id.buttonPlay)
        buttonSpeed = findViewById(R.id.buttonSpeed)

        val settings = SettingsManager(this)
        val key = settings.googleMapsApiKey
        provider = settings.mapProvider
        if (provider == SettingsManager.MAP_GOOGLE && key.isBlank()) {
            provider = SettingsManager.MAP_OSM
            textNotice.text = "Googleマップが選ばれていますが、APIキーが未設定のため " +
                "OpenStreetMapで表示します(設定で入力できます)。"
        } else if (provider == SettingsManager.MAP_GOOGLE) {
            textNotice.text = "Googleマップを使用中です。利用量によっては料金が発生します。通信が必要です。"
        } else {
            textNotice.text = "OpenStreetMapを使用中です(個人の軽い利用向けの公開サーバー)。" +
                "通信が必要です。© OpenStreetMap contributors"
        }

        webMap.settings.javaScriptEnabled = true
        webMap.settings.domStorageEnabled = true
        webMap.addJavascriptInterface(Bridge(), "Android")
        webMap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val k = JSONObject.quote(key)
                webMap.evaluateJavascript("start('$provider', $k)", null)
            }
        }
        val html = assets.open("map.html").bufferedReader().use { it.readText() }
        // 公開タイルサーバーやAPIキーのリファラー制限のため、https のベースURLを付けて読み込む
        webMap.loadDataWithBaseURL("https://dashcam.example/", html, "text/html", "utf-8", null)

        findViewById<Button>(R.id.buttonPickVideo).setOnClickListener {
            videoPicker.launch(arrayOf("video/mp4"))
        }
        buttonPlay.setOnClickListener {
            if (points.isEmpty()) return@setOnClickListener
            if (!playing && index >= points.size - 1) index = 0
            setPlaying(!playing)
        }
        buttonSpeed.setOnClickListener {
            speed = when (speed) { 1 -> 2; 2 -> 4; else -> 1 }
            buttonSpeed.text = "${speed}×"
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    index = progress
                    updatePosition()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    override fun onStop() {
        super.onStop()
        setPlaying(false)
    }

    override fun onDestroy() {
        webMap.destroy()
        super.onDestroy()
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        buttonPlay.text = if (value) "一時停止" else "再生"
        handler.removeCallbacks(tick)
        if (value) handler.postDelayed(tick, 1000L / speed)
    }

    private fun loadVideo(uri: Uri) {
        setPlaying(false)
        val name = queryDisplayName(uri)
        textStatus.text = "読み込み中: ${name ?: uri}"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val jsonUri = name?.let { findJsonUri(it) } ?: return@withContext null
                MetadataParser.load(this@MapPlaybackActivity, jsonUri)
            }
            if (result == null) {
                textStatus.text = "この動画にはメタデータ(JSON)がありません"
                clearData()
                return@launch
            }
            val (samples, evs) = result
            val pts = samples.mapNotNull { s ->
                val la = s.latitude
                val lo = s.longitude
                if (la != null && lo != null) Point(s, la, lo) else null
            }
            if (pts.isEmpty()) {
                textStatus.text = "位置情報(GPS)が記録されていないため、地図に表示できません"
                clearData()
                return@launch
            }
            points = pts
            events = evs.mapNotNull { e ->
                // イベント時刻に最も近い位置サンプルへ対応づける
                val i = pts.indices.minByOrNull { kotlin.math.abs(pts[it].sample.timestampMs - e.timestampMs) }
                if (i == null) null else i to e
            }
            index = 0
            seek.max = pts.size - 1
            seek.progress = 0
            seek.isEnabled = true
            buttonPlay.isEnabled = true
            textStatus.text = "${name}  /  位置${pts.size}点・イベント${events.size}件"
            sendData()
        }
    }

    private fun clearData() {
        points = emptyList()
        events = emptyList()
        seek.isEnabled = false
        buttonPlay.isEnabled = false
        textInfo.text = "--"
        if (mapReady) webMap.evaluateJavascript("clearAll()", null)
    }

    private fun eventLabel(e: MetadataRecorder.Event): String {
        val kind = if (e.type == MetadataRecorder.EVENT_SHOCK) "衝撃" else "急ブレーキ"
        val g = if (e.g != null) " %.1fG".format(e.g) else ""
        return "$kind$g (${timeFormat.format(Date(e.timestampMs))})"
    }

    private fun sendData() {
        if (!mapReady || points.isEmpty()) return
        val obj = JSONObject()
        val pts = JSONArray()
        points.forEach { pts.put(JSONArray().put(it.lat).put(it.lon)) }
        obj.put("points", pts)
        val evArr = JSONArray()
        events.forEach { (i, e) ->
            evArr.put(
                JSONObject().put("i", i).put("lat", points[i].lat).put("lon", points[i].lon)
                    .put("type", e.type).put("label", eventLabel(e))
            )
        }
        obj.put("events", evArr)
        webMap.evaluateJavascript("setData($obj)", null)
        updatePosition()
    }

    private fun updatePosition() {
        if (points.isEmpty()) return
        val p = points[index]
        seek.progress = index
        val speedText = p.sample.speedKmh?.let { "%.0f km/h".format(it) } ?: "-- km/h"
        textInfo.text = "${timeFormat.format(Date(p.sample.timestampMs))}   $speedText"
        if (mapReady) webMap.evaluateJavascript("setPos(${p.lat}, ${p.lon})", null)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }

    private fun findJsonUri(videoDisplayName: String): Uri? {
        val jsonName = videoDisplayName.substringBeforeLast('.') + ".json"
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(jsonName), null
        )?.use { c ->
            if (c.moveToFirst()) {
                return ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            }
        }
        return null
    }

    /** WebView内のJavaScriptから呼ばれる(別スレッドで来るためメインスレッドへ戻す) */
    private inner class Bridge {
        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                mapReady = true
                sendData()
            }
        }

        @JavascriptInterface
        fun onEventClick(pointIndex: Int) {
            runOnUiThread {
                if (pointIndex in points.indices) {
                    index = pointIndex
                    updatePosition()
                }
            }
        }

        @JavascriptInterface
        fun onMapError(message: String) {
            runOnUiThread { textNotice.text = "地図エラー: $message" }
        }
    }
}
