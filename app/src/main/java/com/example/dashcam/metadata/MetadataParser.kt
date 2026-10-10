package com.example.dashcam.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** メタデータJSON(JSON Lines。旧形式の配列も可)を読み込む。書き出し・走行再生で共用。 */
object MetadataParser {
    private const val TAG = "MetadataParser"

    fun load(
        context: Context,
        uri: Uri
    ): Pair<List<MetadataRecorder.Sample>, List<MetadataRecorder.Event>> {
        val none = emptyList<MetadataRecorder.Sample>() to emptyList<MetadataRecorder.Event>()
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return none
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                none
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { parseSample(arr.optJSONObject(it)) } to
                    emptyList()
            } else {
                val samples = mutableListOf<MetadataRecorder.Sample>()
                val events = mutableListOf<MetadataRecorder.Event>()
                trimmed.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    runCatching {
                        val obj = JSONObject(line)
                        if (obj.optString("type") == "event") {
                            events.add(
                                MetadataRecorder.Event(
                                    timestampMs = obj.getLong("timestamp_ms"),
                                    type = obj.optString("event"),
                                    g = if (obj.isNull("g")) null else obj.getDouble("g").toFloat()
                                )
                            )
                        } else {
                            parseSample(obj)?.let { samples.add(it) }
                        }
                    }
                }
                samples to events
            }
        } catch (e: Exception) {
            Log.e(TAG, "メタデータJSONの読み込みに失敗しました", e)
            none
        }
    }

    private fun parseSample(obj: JSONObject?): MetadataRecorder.Sample? {
        obj ?: return null
        return MetadataRecorder.Sample(
            timestampMs = obj.getLong("timestamp_ms"),
            latitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude"),
            longitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude"),
            speedKmh = if (obj.isNull("speed_kmh")) null else obj.getDouble("speed_kmh").toFloat()
        )
    }
}
