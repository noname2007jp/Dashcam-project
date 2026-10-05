package com.example.dashcam.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.dashcam.metadata.MetadataRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 書き出し時にどの情報を焼き込むかのオプション */
data class ExportOptions(
    val showDate: Boolean = true,
    val showLocation: Boolean = true,
    val showSpeed: Boolean = true,
    val position: Position = Position.BOTTOM_LEFT
) {
    enum class Position { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
}

/**
 * メタデータ(MetadataRecorder.Sample)を、動画の再生位置(timeMs)に応じて
 * Canvasへ焼き込むクラス。
 *
 * @param samples 対象セグメントのメタデータ(時刻昇順であること)
 * @param videoStartEpochMs 動画の先頭(timeMs=0)に対応する実時刻(エポックミリ秒)。
 *   samples の最初の要素のタイムスタンプを近似値として使うのが基本。
 */
class OverlayTextRenderer(
    private val samples: List<MetadataRecorder.Sample>,
    private val videoStartEpochMs: Long,
    private val options: ExportOptions
) {
    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
        private const val PADDING = 24f
        private const val LINE_HEIGHT = 44f
        private const val TEXT_SIZE = 36f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = TEXT_SIZE
        isAntiAlias = true
    }
    private val backgroundPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
    }

    /** 動画の再生位置(timeMs)に対応する行を描画する */
    fun draw(canvas: Canvas, timeMs: Long) {
        val sample = findNearestSample(videoStartEpochMs + timeMs) ?: return

        val lines = mutableListOf<String>()
        if (options.showDate) {
            lines.add(DATE_FORMAT.format(Date(sample.timestampMs)))
        }
        if (options.showLocation) {
            val lat = sample.latitude
            val lon = sample.longitude
            lines.add(
                if (lat != null && lon != null) {
                    "%.5f, %.5f".format(lat, lon)
                } else {
                    "GPS: --"
                }
            )
        }
        if (options.showSpeed) {
            val speed = sample.speedKmh
            lines.add(if (speed != null) "%.1f km/h".format(speed) else "-- km/h")
        }
        if (lines.isEmpty()) return

        drawLines(canvas, lines)
    }

    private fun findNearestSample(targetEpochMs: Long): MetadataRecorder.Sample? {
        if (samples.isEmpty()) return null
        return samples.minByOrNull { kotlin.math.abs(it.timestampMs - targetEpochMs) }
    }

    private fun drawLines(canvas: Canvas, lines: List<String>) {
        val maxWidth = lines.maxOf { textPaint.measureText(it) }
        val blockWidth = maxWidth + PADDING * 2
        val blockHeight = LINE_HEIGHT * lines.size + PADDING

        val bounds = Rect()
        canvas.getClipBounds(bounds)
        val canvasWidth = bounds.width().takeIf { it > 0 } ?: canvas.width
        val canvasHeight = bounds.height().takeIf { it > 0 } ?: canvas.height

        val (left, top) = when (options.position) {
            ExportOptions.Position.TOP_LEFT -> PADDING to PADDING
            ExportOptions.Position.TOP_RIGHT -> (canvasWidth - blockWidth - PADDING) to PADDING
            ExportOptions.Position.BOTTOM_LEFT ->
                PADDING to (canvasHeight - blockHeight - PADDING)
            ExportOptions.Position.BOTTOM_RIGHT ->
                (canvasWidth - blockWidth - PADDING) to (canvasHeight - blockHeight - PADDING)
        }

        canvas.drawRoundRect(
            left, top, left + blockWidth, top + blockHeight, 12f, 12f, backgroundPaint
        )

        lines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                left + PADDING,
                top + PADDING + LINE_HEIGHT * (index + 1) - 10f,
                textPaint
            )
        }
    }
}
