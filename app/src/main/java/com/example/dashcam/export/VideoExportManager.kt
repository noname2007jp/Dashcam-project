package com.example.dashcam.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import com.example.dashcam.metadata.MetadataRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 書き出し機能全体の統括クラス。
 *
 * 手順:
 * 1. 元動画(MediaStore Uri)をデコードし、OpenGLでメタデータテキストを重ねて再エンコード
 *    (VideoOverlayProcessor。この時点では映像のみ、音声なし)
 * 2. 元動画から音声トラックを抜き出して合成(AudioMuxer。再エンコードなしのストリームコピー)
 * 3. 完成したファイルを Download/cam/dashcam_export へMediaStore経由で保存
 * 4. 一時ファイルを削除
 */
class VideoExportManager(private val context: Context) {

    companion object {
        private const val TAG = "VideoExportManager"
        const val EXPORT_RELATIVE_PATH = "Download/cam/dashcam_export/"
    }

    sealed class Result {
        data class Success(val outputUri: Uri, val displayName: String) : Result()
        data class Failure(val error: Throwable) : Result()
    }

    /**
     * @param sourceVideoUri 元動画(MediaStoreのUri)
     * @param sourceDisplayName 元動画の表示名(例: "2026-09-18_143207.mp4")
     * @param metadataJsonUri 対応するメタデータJSONのUri(なければnull。焼き込み内容が空になる)
     * @param options 焼き込みオプション(日時/位置/速度、表示位置)
     * @param onProgress 進捗コールバック(0.0〜1.0の概算。厳密な進捗ではない)
     */
    suspend fun export(
        sourceVideoUri: Uri,
        sourceDisplayName: String,
        metadataJsonUri: Uri?,
        options: ExportOptions,
        onProgress: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.Default) {
        val tempDir = File(context.cacheDir, "export_tmp").apply { mkdirs() }
        val videoOnlyFile = File(tempDir, "video_only_${System.currentTimeMillis()}.mp4")
        val muxedFile = File(tempDir, "muxed_${System.currentTimeMillis()}.mp4")

        try {
            onProgress("メタデータを読み込み中")
            val samples = metadataJsonUri?.let { loadSamples(it) } ?: emptyList()
            val videoStartEpochMs = samples.firstOrNull()?.timestampMs
                ?: System.currentTimeMillis()
            val overlayRenderer = OverlayTextRenderer(samples, videoStartEpochMs, options)

            onProgress("映像にテキストを焼き込み中")
            val processor = VideoOverlayProcessor(
                context = context,
                sourceUri = sourceVideoUri,
                resultFile = videoOnlyFile
            )
            processor.encode { timeMs ->
                overlayRenderer.draw(this, timeMs)
            }

            onProgress("音声を合成中")
            AudioMuxer(
                context = context,
                videoOnlyFile = videoOnlyFile,
                audioSourceUri = sourceVideoUri,
                resultFile = muxedFile
            ).mux()

            onProgress("保存先へコピー中")
            val outputDisplayName = sourceDisplayName.substringBeforeLast('.') + "_export.mp4"
            val outputUri = saveToMediaStore(muxedFile, outputDisplayName)
                ?: throw IllegalStateException("MediaStoreへの保存に失敗しました")

            Result.Success(outputUri, outputDisplayName)
        } catch (e: Exception) {
            Log.e(TAG, "書き出しに失敗しました", e)
            Result.Failure(e)
        } finally {
            videoOnlyFile.delete()
            muxedFile.delete()
        }
    }

    private fun loadSamples(metadataJsonUri: Uri): List<MetadataRecorder.Sample> {
        return try {
            val text = context.contentResolver.openInputStream(metadataJsonUri)
                ?.bufferedReader()?.use { it.readText() } ?: return emptyList()
            val jsonArray = JSONArray(text)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                MetadataRecorder.Sample(
                    timestampMs = obj.getLong("timestamp_ms"),
                    latitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude"),
                    longitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude"),
                    speedKmh = if (obj.isNull("speed_kmh")) null else obj.getDouble("speed_kmh").toFloat()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "メタデータJSONの読み込みに失敗しました", e)
            emptyList()
        }
    }

    private fun saveToMediaStore(file: File, displayName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
        }
        val itemUri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
        ) ?: return null

        context.contentResolver.openOutputStream(itemUri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        return itemUri
    }
}
