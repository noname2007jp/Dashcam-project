/*
 * VideoOverlayProcessor が生成した「映像のみ(音声なし)」のmp4に、元動画の音声トラックを
 * 合成するクラス。映像は既にエンコード済みのためそのままコピー(再エンコードなし)、
 * 音声も元動画からそのまま抜き出してコピーする(ストリームコピー、劣化なし)。
 */
package com.example.dashcam.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class AudioMuxer(
    private val context: Context,
    private val videoOnlyFile: File,
    private val audioSourceUri: Uri,
    private val resultFile: File
) {
    /**
     * 音声合成を実行する。元動画に音声トラックが存在しない場合は、
     * 映像のみのファイルをそのまま resultFile にコピーする。
     */
    suspend fun mux() = withContext(Dispatchers.Default) {
        val audioExtractor = MediaExtractor().apply {
            setDataSource(context, audioSourceUri, null)
        }
        val audioTrackInfo = (0 until audioExtractor.trackCount)
            .map { i -> i to audioExtractor.getTrackFormat(i) }
            .firstOrNull { (_, f) -> f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

        if (audioTrackInfo == null) {
            // 音声トラックがない場合は映像のみのファイルをそのまま採用
            audioExtractor.release()
            videoOnlyFile.copyTo(resultFile, overwrite = true)
            return@withContext
        }
        val (audioTrackIndex, audioFormat) = audioTrackInfo
        audioExtractor.selectTrack(audioTrackIndex)

        val videoExtractor = MediaExtractor().apply { setDataSource(videoOnlyFile.path) }
        val videoTrackIndex = (0 until videoExtractor.trackCount)
            .first { i ->
                videoExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
        videoExtractor.selectTrack(videoTrackIndex)
        val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

        val muxer = MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerVideoTrack = muxer.addTrack(videoFormat)
        val muxerAudioTrack = muxer.addTrack(audioFormat)
        muxer.start()

        val bufferSize = 1024 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        // 映像トラックをコピー
        copyTrack(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)
        // 音声トラックをコピー
        copyTrack(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)

        videoExtractor.release()
        audioExtractor.release()
        muxer.stop()
        muxer.release()
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: android.media.MediaCodec.BufferInfo
    ) {
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            bufferInfo.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}
