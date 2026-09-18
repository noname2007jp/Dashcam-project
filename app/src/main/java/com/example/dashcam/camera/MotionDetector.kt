package com.example.dashcam.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * ImageAnalysisのフレームから動体検知を行うアナライザ(駐車監視モード用)。
 *
 * 設計方針(仕様書より):
 * - 低解像度フレーム(320x240目安)で、輝度(Y平面)のみを使ったフレーム間差分方式
 * - 変化ピクセルの割合が閾値を超えたフレームが連続で複数回続いたら
 *   「動きあり」と判定(単発ノイズによる誤検知防止のヒステリシス)
 * - 動き検知後、一定時間(QUIET_DURATION_MS)動きなしが続いたら「動き終了」を通知する
 *   (録画停止トリガー用。前後の映像を録り逃さないための余裕時間でもある)
 */
class MotionDetector(
    private val listener: Listener
) : ImageAnalysis.Analyzer {

    interface Listener {
        /** 動きを検知し始めたときに1回だけ呼ばれる(録画開始トリガー) */
        fun onMotionDetected()

        /** 動きなしの状態が一定時間続いたときに1回だけ呼ばれる(録画停止トリガー) */
        fun onMotionStopped()
    }

    companion object {
        private const val TAG = "MotionDetector"

        // ピクセル値(輝度)の差分がこれを超えたら「変化あり」とみなす(0-255)
        private const val PIXEL_DIFF_THRESHOLD = 25

        // フレーム内の変化ピクセル割合がこれを超えたら「このフレームは動きあり」
        private const val CHANGED_RATIO_THRESHOLD = 0.03f // 3%

        // 連続してこの回数「動きあり」フレームが続いたら正式に動き検知とする
        private const val MIN_CONSECUTIVE_MOTION_FRAMES = 3

        // 動きなし状態がこの時間続いたら「動き終了」とする
        private const val QUIET_DURATION_MS = 15_000L

        // 処理負荷軽減のため、全ピクセルではなく間引いてサンプリングする間隔
        private const val SAMPLING_STEP = 4
    }

    private var previousFrame: ByteArray? = null
    private var frameWidth = 0
    private var frameHeight = 0

    private var consecutiveMotionFrames = 0
    private var isMotionActive = false
    private var lastMotionTimeMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            // Y平面(輝度)のみを使用。RGB変換より軽量。
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val currentFrame = ByteArray(buffer.remaining())
            buffer.get(currentFrame)

            val width = image.width
            val height = image.height

            val prev = previousFrame
            if (prev != null && prev.size == currentFrame.size &&
                width == frameWidth && height == frameHeight
            ) {
                val changedRatio = computeChangedRatio(prev, currentFrame)
                handleFrameResult(changedRatio)
            }

            previousFrame = currentFrame
            frameWidth = width
            frameHeight = height
        } catch (e: Exception) {
            Log.e(TAG, "フレーム解析エラー", e)
        } finally {
            image.close()
        }
    }

    private fun computeChangedRatio(prev: ByteArray, current: ByteArray): Float {
        var changedCount = 0
        var sampledCount = 0
        var i = 0
        while (i < current.size) {
            val diff = abs((current[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            if (diff > PIXEL_DIFF_THRESHOLD) {
                changedCount++
            }
            sampledCount++
            i += SAMPLING_STEP
        }
        return if (sampledCount > 0) changedCount.toFloat() / sampledCount else 0f
    }

    private fun handleFrameResult(changedRatio: Float) {
        val now = System.currentTimeMillis()
        val frameHasMotion = changedRatio >= CHANGED_RATIO_THRESHOLD

        if (frameHasMotion) {
            consecutiveMotionFrames++
            lastMotionTimeMs = now

            if (!isMotionActive && consecutiveMotionFrames >= MIN_CONSECUTIVE_MOTION_FRAMES) {
                isMotionActive = true
                Log.i(TAG, "動き検知: 開始 (変化率=${"%.1f".format(changedRatio * 100)}%)")
                listener.onMotionDetected()
            }
        } else {
            consecutiveMotionFrames = 0

            if (isMotionActive && now - lastMotionTimeMs >= QUIET_DURATION_MS) {
                isMotionActive = false
                Log.i(TAG, "動き検知: 終了(無検知が${QUIET_DURATION_MS / 1000}秒継続)")
                listener.onMotionStopped()
            }
        }
    }

    /** 状態をリセットする(走行/駐車モード切替時などに呼び出す) */
    fun reset() {
        previousFrame = null
        consecutiveMotionFrames = 0
        isMotionActive = false
    }
}
