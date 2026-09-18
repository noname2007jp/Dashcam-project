package com.example.dashcam.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * 短時間に急ブレーキ(急減速)が繰り返し発生したことを検知するクラス。
 *
 * 「煽り運転そのもの」を直接判定することはできないため、
 * 「あおられて何度も急な減速を強いられている」という挙動パターンを
 * 間接的な手がかりとして検知する設計。
 *
 * 設計方針:
 * - ShockDetectorと同じ加速度センサー(TYPE_LINEAR_ACCELERATION)を使用するが、
 *   衝撃検知(0.5G)より低い閾値(初期値0.3G)で「急ブレーキ」を検知する
 * - 一定時間(WINDOW_MS)内に急ブレーキがREQUIRED_EVENT_COUNT回以上発生したら
 *   「煽り運転の可能性」として通知する
 * - 走行中のみ有効(setActive(true)で有効化、駐車中は無効化する想定)
 * - 1回の急ブレーキで加速度センサーが連続して閾値超えを検出してしまうことがあるため、
 *   EVENT_COOLDOWN_MSの間隔を空けて「1回のブレーキ」として数える
 */
class TailgatingDetector(
    context: Context,
    private val listener: Listener
) : SensorEventListener {

    interface Listener {
        /**
         * 短時間内に急ブレーキが繰り返し検知され、煽り運転の可能性があると判定されたときに呼ばれる
         * @param eventCount 判定に使われた急ブレーキの回数
         */
        fun onTailgatingSuspected(eventCount: Int)
    }

    companion object {
        private const val TAG = "TailgatingDetector"
        private const val GRAVITY_MS2 = SensorManager.STANDARD_GRAVITY

        const val DEFAULT_BRAKING_THRESHOLD_G = 0.3f
        const val THRESHOLD_STEP_G = 0.1f
        const val MIN_THRESHOLD_G = 0.1f
        const val MAX_THRESHOLD_G = 2.0f

        // 同一の急ブレーキを重複カウントしないための最小間隔
        private const val EVENT_COOLDOWN_MS = 1_500L

        // この時間内の急ブレーキ回数を集計する(スライディングウィンドウ)
        private const val WINDOW_MS = 20_000L

        // ウィンドウ内でこの回数以上の急ブレーキがあったら「煽り運転の可能性」と判定
        private const val REQUIRED_EVENT_COUNT = 3

        // 一度判定を通知したら、この時間は再判定しない(通知の連発防止)
        private const val TRIGGER_COOLDOWN_MS = 30_000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var brakingThresholdG: Float = DEFAULT_BRAKING_THRESHOLD_G

    // 直近の急ブレーキ発生時刻の履歴(ウィンドウ内のものだけを保持)
    private val eventTimestamps = ArrayDeque<Long>()

    private var lastEventTimeMs = 0L
    private var lastTriggerTimeMs = 0L
    private var isListening = false

    // 走行中のみtrueにする(駐車中は急ブレーキの概念がないため無効化)
    @Volatile
    private var isActive = false

    /** 走行中/駐車中に応じて有効・無効を切り替える */
    fun setActive(active: Boolean) {
        isActive = active
        if (!active) {
            eventTimestamps.clear()
        }
        Log.i(TAG, "アクティブ状態を変更: $active")
    }

    /** 急ブレーキ判定の閾値を設定(0.1G刻み、MIN〜MAXでクランプ) */
    fun setBrakingThreshold(g: Float) {
        val stepped = Math.round(g / THRESHOLD_STEP_G) * THRESHOLD_STEP_G
        brakingThresholdG = stepped.coerceIn(MIN_THRESHOLD_G, MAX_THRESHOLD_G)
        Log.i(TAG, "急ブレーキ判定閾値を更新: ${brakingThresholdG}G")
    }

    fun start() {
        if (isListening) return
        if (linearAccelSensor == null) {
            Log.w(TAG, "この端末は TYPE_LINEAR_ACCELERATION に対応していません")
            return
        }
        sensorManager.registerListener(
            this,
            linearAccelSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
        isListening = true
        Log.i(TAG, "急ブレーキ連続検知を開始しました")
    }

    fun stop() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
        eventTimestamps.clear()
        Log.i(TAG, "急ブレーキ連続検知を停止しました")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isActive) return
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeG = sqrt(x * x + y * y + z * z) / GRAVITY_MS2

        if (magnitudeG < brakingThresholdG) return

        val now = System.currentTimeMillis()

        // 同一ブレーキの重複カウントを防ぐ
        if (now - lastEventTimeMs < EVENT_COOLDOWN_MS) return
        lastEventTimeMs = now

        eventTimestamps.addLast(now)
        // ウィンドウ外の古い記録を削除
        while (eventTimestamps.isNotEmpty() && now - eventTimestamps.first() > WINDOW_MS) {
            eventTimestamps.removeFirst()
        }

        Log.i(
            TAG,
            "急ブレーキ検知: ${"%.2f".format(magnitudeG)}G " +
                "(直近${WINDOW_MS / 1000}秒間で${eventTimestamps.size}回)"
        )

        if (eventTimestamps.size >= REQUIRED_EVENT_COUNT &&
            now - lastTriggerTimeMs >= TRIGGER_COOLDOWN_MS
        ) {
            lastTriggerTimeMs = now
            val count = eventTimestamps.size
            eventTimestamps.clear()
            Log.i(TAG, "煽り運転の可能性を検知(急ブレーキ${count}回)")
            listener.onTailgatingSuspected(count)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 未使用
    }
}
