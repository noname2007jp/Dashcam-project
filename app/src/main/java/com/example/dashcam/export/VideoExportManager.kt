package com.example.dashcam.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
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

        // 元動画の情報取得に失敗した場合のみ使う既定値
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_BIT_RATE = 8_000_000
        private const val DEFAULT_FRAME_RATE = 30
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
            onProgress("元動画の情報を確認中")
            val sourceInfo = readSourceVideoInfo(sourceVideoUri)
            Log.i(
                TAG,
                "元動画情報: ${sourceInfo.width}x${sourceInfo.height}, " +
                    "${sourceInfo.bitRate / 1_000_000}Mbps, ${sourceInfo.frameRate}fps"
            )

            onProgress("メタデータを読み込み中")
            val samples = metadataJsonUri?.let { loadSamples(it) } ?: emptyList()
            val videoStartEpochMs = samples.firstOrNull()?.timestampMs
                ?: System.currentTimeMillis()
            val overlayRenderer = OverlayTextRenderer(samples, videoStartEpochMs, options)

            onProgress("映像にテキストを焼き込み中")

            // 書き出しの向き指定(横/縦)。録画時に向きが固定されてしまった動画でも、
            // ここで90度回転させて指定した向きに出力する。デフォルトは横向き。
            val sourceIsLandscape = sourceInfo.width >= sourceInfo.height
            val wantLandscape = options.orientation == ExportOptions.Orientation.LANDSCAPE
            var outputWidth = sourceInfo.width
            var outputHeight = sourceInfo.height
            var extraRotation = 0f
            if (sourceIsLandscape != wantLandscape) {
                outputWidth = sourceInfo.height
                outputHeight = sourceInfo.width
                extraRotation = 90f
                Log.i(TAG, "書き出し時に向きを変更します: ${options.orientation} (90度回転)")
            }

            val processor = VideoOverlayProcessor(
                context = context,
                sourceUri = sourceVideoUri,
                resultFile = videoOnlyFile,
                outputVideoWidth = outputWidth,
                outputVideoHeight = outputHeight,
                bitRate = sourceInfo.bitRate,
                frameRate = sourceInfo.frameRate,
                extraRotationDegrees = extraRotation
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

    private data class SourceVideoInfo(
        val width: Int,
        val height: Int,
        val bitRate: Int,
        val frameRate: Int
    )

    /**
     * 元動画の実際の解像度・ビットレート・フレームレートを読み取る。
     * これを使わずに固定値でエンコードすると、元動画より低品質な出力になったり
     * (ビットレートが元より低い場合)、逆に無駄にファイルサイズが増えたりする
     * (ビットレートが元より高い場合)ため、必ず元動画の値に合わせる。
     *
     * 重要: MediaMetadataRetrieverのWIDTH/HEIGHTは「コーディングされた生の幅・高さ」であり、
     * 回転情報(METADATA_KEY_VIDEO_ROTATION)を反映していない。90度/270度回転の動画では
     * 幅と高さが実際の表示状態と入れ替わっているため、ここで補正しておかないと、
     * 出力Canvas(=テキスト描画先)の縦横が映像の実際の向きと食い違い、
     * 「映像は正しい向きなのにテキストだけ90度ずれる」といった不具合の原因になる。
     */
    private fun readSourceVideoInfo(sourceUri: Uri): SourceVideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: DEFAULT_WIDTH
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: DEFAULT_HEIGHT
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            // 90度/270度回転の場合、実際の表示上の幅・高さは入れ替わる
            val (width, height) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            val bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_BIT_RATE
            val frameRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull()?.toInt()?.takeIf { it > 0 } ?: DEFAULT_FRAME_RATE

            Log.i(
                TAG,
                "元動画の回転情報: ${rotation}度 (生の解像度 ${rawWidth}x${rawHeight} " +
                    "-> 補正後 ${width}x${height})"
            )

            SourceVideoInfo(width, height, bitRate, frameRate)
        } catch (e: Exception) {
            Log.e(TAG, "元動画の情報取得に失敗したため既定値を使用します", e)
            SourceVideoInfo(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_BIT_RATE, DEFAULT_FRAME_RATE)
        } finally {
            retriever.release()
        }
    }

    /**
     * メタデータJSONを読み込む。
     * 現在の形式は JSON Lines(1行1サンプルのJSONオブジェクト、MetadataRecorderが
     * 1秒ごとに逐次追記する形式)。念のため、旧形式(JSON配列をまとめて書き出す形式)
     * のファイルも読めるようフォールバックを用意している。
     */
    private fun loadSamples(metadataJsonUri: Uri): List<MetadataRecorder.Sample> {
        return try {
            val text = context.contentResolver.openInputStream(metadataJsonUri)
                ?.bufferedReader()?.use { it.readText() } ?: return emptyList()
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                emptyList()
            } else if (trimmed.startsWith("[")) {
                // 旧形式(JSON配列)
                val jsonArray = JSONArray(trimmed)
                (0 until jsonArray.length()).mapNotNull { i ->
                    parseSample(jsonArray.optJSONObject(i))
                }
            } else {
                // 現行形式(JSON Lines)
                trimmed.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        runCatching { parseSample(org.json.JSONObject(line)) }.getOrNull()
                    }
                    .toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "メタデータJSONの読み込みに失敗しました", e)
            emptyList()
        }
    }

    private fun parseSample(obj: org.json.JSONObject?): MetadataRecorder.Sample? {
        obj ?: return null
        return MetadataRecorder.Sample(
            timestampMs = obj.getLong("timestamp_ms"),
            latitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude"),
            longitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude"),
            speedKmh = if (obj.isNull("speed_kmh")) null else obj.getDouble("speed_kmh").toFloat()
        )
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
