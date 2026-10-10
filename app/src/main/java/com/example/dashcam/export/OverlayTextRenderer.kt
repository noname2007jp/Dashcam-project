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
    val showEventMarks: Boolean = true,
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
    private val options: ExportOptions,
    private val events: List<MetadataRecorder.Event> = emptyList(),
    private val videoDurationMs: Long = 0L
) {
    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
        private const val PADDING = 24f
        private const val LINE_HEIGHT = 44f
        private const val TEXT_SIZE = 36f

        // イベント表示の範囲: 発生の2秒前〜5秒後
        private const val EVENT_BEFORE_MS = 2_000L
        private const val EVENT_AFTER_MS = 5_000L

        private val COLOR_SHOCK = Color.rgb(229, 57, 53)    // 赤
        private val COLOR_BRAKING = Color.rgb(251, 140, 0)  // オレンジ
        private const val BAR_HEIGHT = 12f
    }

    private fun eventColor(type: String) =
        if (type == MetadataRecorder.EVENT_SHOCK) COLOR_SHOCK else COLOR_BRAKING

    private fun eventLabel(e: MetadataRecorder.Event): String =
        if (e.type == MetadataRecorder.EVENT_SHOCK) {
            if (e.g != null) "衝撃 %.1fG".format(e.g) else "衝撃"
        } else {
            "急ブレーキ"
        }

    private val barPaint = Paint()
    private val badgePaint = Paint()
    private val badgeTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = TEXT_SIZE
        isAntiAlias = true
        isFakeBoldText = true
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
        val bounds = Rect()
        canvas.getClipBounds(bounds)
        val canvasWidth = bounds.width().takeIf { it > 0 } ?: canvas.width
        val canvasHeight = bounds.height().takeIf { it > 0 } ?: canvas.height

        // 現在表示すべきイベント(発生の2秒前〜5秒後。複数あれば最新のもの)
        val nowEpoch = videoStartEpochMs + timeMs
        val activeEvent = if (options.showEventMarks) {
            events.filter { nowEpoch >= it.timestampMs - EVENT_BEFORE_MS &&
                nowEpoch <= it.timestampMs + EVENT_AFTER_MS }
                .maxByOrNull { it.timestampMs }
        } else null

        if (options.showEventMarks) {
            drawTimelineBar(canvas, canvasWidth, canvasHeight, timeMs)
        }

        val sample = findNearestSample(nowEpoch)
        if (sample != null) {
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
            if (lines.isNotEmpty()) {
                drawLines(canvas, lines, canvasWidth, canvasHeight, activeEvent)
            }
        }

        if (activeEvent != null) {
            drawBadge(canvas, canvasWidth, activeEvent, timeMs)
        }
    }

    /** 画面下端の細い時間バー。イベント発生位置に色付きの目印、再生位置に白いカーソルを表示する */
    private fun drawTimelineBar(canvas: Canvas, w: Int, h: Int, timeMs: Long) {
        if (events.isEmpty() || videoDurationMs <= 0) return
        val top = h - BAR_HEIGHT
        barPaint.color = Color.argb(110, 255, 255, 255)
        canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), barPaint)
        for (e in events) {
            val t = (e.timestampMs - videoStartEpochMs).coerceIn(0L, videoDurationMs)
            val x = w * (t.toFloat() / videoDurationMs)
            barPaint.color = eventColor(e.type)
            canvas.drawRect(x - 5f, top - 10f, x + 5f, h.toFloat(), barPaint)
        }
        val px = w * (timeMs.coerceIn(0L, videoDurationMs).toFloat() / videoDurationMs)
        barPaint.color = Color.WHITE
        canvas.drawRect(px - 2f, top, px + 2f, h.toFloat(), barPaint)
    }

    /** イベント中に出す色付きバッジ(点滅)。情報パネルと重ならない側の上隅に表示する */
    private fun drawBadge(canvas: Canvas, w: Int, e: MetadataRecorder.Event, timeMs: Long) {
        val blinkOn = (timeMs / 500L) % 2L == 0L
        val label = (if (blinkOn) "● " else "○ ") + eventLabel(e)
        val width = badgeTextPaint.measureText(label) + PADDING * 2
        val height = LINE_HEIGHT + PADDING * 0.6f
        val panelOnTopRight = options.position == ExportOptions.Position.TOP_RIGHT
        val left = if (panelOnTopRight) PADDING else w - width - PADDING
        val top = PADDING
        badgePaint.color = eventColor(e.type)
        canvas.drawRoundRect(left, top, left + width, top + height, 12f, 12f, badgePaint)
        canvas.drawText(label, left + PADDING, top + height - 14f, badgeTextPaint)
    }

    private fun findNearestSample(targetEpochMs: Long): MetadataRecorder.Sample? {
        if (samples.isEmpty()) return null
        return samples.minByOrNull { kotlin.math.abs(it.timestampMs - targetEpochMs) }
    }

    private fun drawLines(
        canvas: Canvas,
        lines: List<String>,
        canvasWidth: Int,
        canvasHeight: Int,
        activeEvent: MetadataRecorder.Event?
    ) {
        val maxWidth = lines.maxOf { textPaint.measureText(it) }
        val blockWidth = maxWidth + PADDING * 2
        val blockHeight = LINE_HEIGHT * lines.size + PADDING
        // イベント中は文字色と枠線をイベントの色にする(通常は白)
        val accent = activeEvent?.let { eventColor(it.type) }
        textPaint.color = accent ?: Color.WHITE
        // 時間バー(画面下端)と重ならないよう、下側の配置では少し上げる
        val bottomInset = if (options.showEventMarks && events.isNotEmpty()) BAR_HEIGHT + 10f else 0f

        val (left, top) = when (options.position) {
            ExportOptions.Position.TOP_LEFT -> PADDING to PADDING
            ExportOptions.Position.TOP_RIGHT -> (canvasWidth - blockWidth - PADDING) to PADDING
            ExportOptions.Position.BOTTOM_LEFT ->
                PADDING to (canvasHeight - blockHeight - PADDING - bottomInset)
            ExportOptions.Position.BOTTOM_RIGHT ->
                (canvasWidth - blockWidth - PADDING) to
                    (canvasHeight - blockHeight - PADDING - bottomInset)
        }

        canvas.drawRoundRect(
            left, top, left + blockWidth, top + blockHeight, 12f, 12f, backgroundPaint
        )

        if (accent != null) {
            val stroke = Paint().apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = 5f
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                left, top, left + blockWidth, top + blockHeight, 12f, 12f, stroke
            )
        }

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
