/*
 * 参考: https://takusan.negitoro.dev/posts/android_add_canvas_text_to_video/ の実装を
 * 参考にKotlinで書き起こしたもの。MediaExtractor/MediaCodec/MediaMuxerを組み合わせて
 * 動画をデコードし、OpenGL経由でCanvasの内容を重ねて再エンコードする。
 *
 * このクラスは映像トラックのみを処理する(音声は含まれない)。音声の再合成は
 * AudioMuxer が別途行う。
 */
package com.example.dashcam.export

import android.content.Context
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 動画にCanvasで描いた内容を重ねて、新しい動画(映像のみ、音声なし)を生成するクラス。
 *
 * @param sourceUri 元動画(MediaStoreのUriを直接指定できる。事前コピー不要)
 * @param resultFile 出力先(映像のみのmp4。ローカルの一時ファイル)
 * @param outputVideoWidth エンコード後の幅
 * @param outputVideoHeight エンコード後の高さ
 * @param bitRate ビットレート
 * @param frameRate フレームレート
 */
class VideoOverlayProcessor(
    private val context: Context,
    private val sourceUri: Uri,
    private val resultFile: File,
    private val outputVideoWidth: Int = 1920,
    private val outputVideoHeight: Int = 1080,
    private val bitRate: Int = 8_000_000,
    private val frameRate: Int = 30
) {
    /**
     * エンコードを実行する。
     * @param onCanvasDrawRequest Canvasに描画するコールバック。timeMsは動画内の再生位置(ミリ秒)
     */
    suspend fun encode(
        onProgressPercent: (Int) -> Unit = {},
        onCanvasDrawRequest: Canvas.(timeMs: Long) -> Unit
    ) = withContext(Dispatchers.Default) {
        val mediaExtractor = MediaExtractor().apply {
            setDataSource(context, sourceUri, null)
        }
        val (trackIndex, format) = (0 until mediaExtractor.trackCount)
            .map { i -> i to mediaExtractor.getTrackFormat(i) }
            .first { (_, f) -> f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        mediaExtractor.selectTrack(trackIndex)
        mediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        // 進捗(%)算出用の動画長。取得できない場合は進捗を出さない
        val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        var lastPercent = -1

        val videoMimeType = format.getString(MediaFormat.KEY_MIME)!!
        val videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)

        // 回転情報が入っていることがあるため考慮する(0/90/180/270度すべてに対応)
        val rotationDegrees = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }
            .getOrDefault(0)
        val originVideoWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            videoHeight
        } else {
            videoWidth
        }
        val originVideoHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            videoWidth
        } else {
            videoHeight
        }
        // デコードされたフレームの回転を打ち消すための補正角度
        // (90度回転されているソースは270度回転させて打ち消す、等)
        val compensationDegrees = when (rotationDegrees) {
            90 -> 270f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }

        val encodeMediaCodec = MediaCodec.createEncoderByType(videoMimeType).apply {
            val videoFormat = MediaFormat.createVideoFormat(
                videoMimeType, outputVideoWidth, outputVideoHeight
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val codecInputSurface = CodecInputSurface(
            encodeMediaCodec.createInputSurface(),
            TextureRenderer(
                outputVideoWidth = outputVideoWidth,
                outputVideoHeight = outputVideoHeight,
                originVideoWidth = originVideoWidth,
                originVideoHeight = originVideoHeight,
                videoRotationDegrees = compensationDegrees
            )
        )
        codecInputSurface.makeCurrent()
        encodeMediaCodec.start()
        codecInputSurface.createRender()

        val decodeMediaCodec = MediaCodec.createDecoderByType(videoMimeType).apply {
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            configure(format, codecInputSurface.drawSurface, null, 0)
        }
        decodeMediaCodec.start()

        val mediaMuxer = MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        var videoTrackIndex = -1
        var outputDone = false
        var inputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferId = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferId)!!
                        val size = mediaExtractor.readSampleData(inputBuffer, 0)
                        if (size > 0) {
                            decodeMediaCodec.queueInputBuffer(
                                inputBufferId, 0, size, mediaExtractor.sampleTime, 0
                            )
                            mediaExtractor.advance()
                        } else {
                            decodeMediaCodec.queueInputBuffer(
                                inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                var decoderOutputAvailable = true
                while (decoderOutputAvailable) {
                    val encoderStatus = encodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (encoderStatus >= 0) {
                        val encodedData = encodeMediaCodec.getOutputBuffer(encoderStatus)!!
                        if (bufferInfo.size > 1 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encodeMediaCodec.releaseOutputBuffer(encoderStatus, false)
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = mediaMuxer.addTrack(encodeMediaCodec.outputFormat)
                        mediaMuxer.start()
                    }

                    if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue

                    val outputBufferId = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decoderOutputAvailable = false
                    } else if (outputBufferId >= 0) {
                        val doRender = bufferInfo.size != 0
                        decodeMediaCodec.releaseOutputBuffer(outputBufferId, doRender)
                        if (doRender) {
                            codecInputSurface.awaitNewImage()
                            codecInputSurface.drawImage { canvas ->
                                canvas.onCanvasDrawRequest(bufferInfo.presentationTimeUs / 1000L)
                            }
                            codecInputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            codecInputSurface.swapBuffers()
                            if (durationUs > 0) {
                                val percent = (bufferInfo.presentationTimeUs * 100 / durationUs)
                                    .toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgressPercent(percent)
                                }
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderOutputAvailable = false
                            encodeMediaCodec.signalEndOfInputStream()
                        }
                    }
                }
            }
        } finally {
            mediaExtractor.release()
            decodeMediaCodec.stop()
            decodeMediaCodec.release()
            codecInputSurface.release()
            encodeMediaCodec.stop()
            encodeMediaCodec.release()
            mediaMuxer.stop()
            mediaMuxer.release()
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
