package com.example.dashcam.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * 加速度センサーによる衝撃検知クラス。
 *
 * 設計方針(仕様書より):
 * - TYPE_LINEAR_ACCELERATION(重力成分を除去済み)を使用
 * - 走行中/駐車中でモードを切り替え、それぞれ異なる閾値・調整幅を持つ
 *   - 走行中(DRIVING)   初期値 0.5G、0.1G刻みで調整可能
 *   - 駐車監視(PARKING) 初期値 0.2G、0.1G刻みで調整可能
 * - 市販ドラレコ(トヨタ純正)調査により、走行中0.5Gは標準的な値と確認済み
 *
 * 誤検知軽減のため、短時間の単発スパイクのみを衝撃として扱う
 * (一定時間内に連続して閾値を超え続ける場合は振動とみなし無視する、等の
 *  高度なフィルタリングは今後の拡張ポイント)
 */
class ShockDetector(
    context: Context,
    private val listener: Listener
) : SensorEventListener {

    enum class Mode {
        DRIVING, PARKING
    }

    interface Listener {
        /**
         * 衝撃を検知したときに呼ばれる。
         * @param magnitudeG 検知した合成加速度(G単位)
         * @param mode 検知時のモード(走行中/駐車中)
         */
        fun onShockDetected(magnitudeG: Float, mode: Mode)
    }

    companion object {
        private const val TAG = "ShockDetector"
        private const val GRAVITY_MS2 = SensorManager.STANDARD_GRAVITY

        const val DEFAULT_DRIVING_THRESHOLD_G = 0.5f
        const val DEFAULT_PARKING_THRESHOLD_G = 0.2f
        const val THRESHOLD_STEP_G = 0.1f
        const val MIN_THRESHOLD_G = 0.1f
        const val MAX_THRESHOLD_G = 5.0f

        // 同一衝撃の多重検知を防ぐためのクールダウン時間
        private const val DETECTION_COOLDOWN_MS = 3000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    var mode: Mode = Mode.DRIVING
        private set

    var drivingThresholdG: Float = DEFAULT_DRIVING_THRESHOLD_G
        private set
    var parkingThresholdG: Float = DEFAULT_PARKING_THRESHOLD_G
        private set

    private var lastDetectionTimeMs: Long = 0L
    private var isListening = false

    /** 走行中/駐車中の判定に応じてモードを切り替える(走行/駐車判定ロジック側から呼び出す想定) */
    fun setMode(newMode: Mode) {
        if (mode != newMode) {
            Log.i(TAG, "検知モード切替: $mode -> $newMode")
            mode = newMode
        }
    }

    /** 走行中の閾値を設定(0.1G刻み、MIN〜MAXの範囲でクランプ) */
    fun setDrivingThreshold(g: Float) {
        drivingThresholdG = clampThreshold(g)
        Log.i(TAG, "走行中の閾値を更新: ${drivingThresholdG}G")
    }

    /** 駐車監視中の閾値を設定(0.1G刻み、MIN〜MAXの範囲でクランプ) */
    fun setParkingThreshold(g: Float) {
        parkingThresholdG = clampThreshold(g)
        Log.i(TAG, "駐車監視の閾値を更新: ${parkingThresholdG}G")
    }

    private fun clampThreshold(g: Float): Float {
        // THRESHOLD_STEP_G刻みに丸める
        val stepped = Math.round(g / THRESHOLD_STEP_G) * THRESHOLD_STEP_G
        return stepped.coerceIn(MIN_THRESHOLD_G, MAX_THRESHOLD_G)
    }

    /** センサー監視を開始する */
    fun start() {
        if (isListening) return
        if (linearAccelSensor == null) {
            Log.w(TAG, "この端末は TYPE_LINEAR_ACCELERATION に対応していません")
            return
        }
        // SENSOR_DELAY_GAME 相当(約20ms間隔)で衝撃の瞬間を捉えやすくする
        sensorManager.registerListener(
            this,
            linearAccelSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
        isListening = true
        Log.i(TAG, "衝撃検知を開始しました(mode=$mode)")
    }

    /** センサー監視を停止する */
    fun stop() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
        Log.i(TAG, "衝撃検知を停止しました")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeMs2 = sqrt(x * x + y * y + z * z)
        val magnitudeG = magnitudeMs2 / GRAVITY_MS2

        val threshold = when (mode) {
            Mode.DRIVING -> drivingThresholdG
            Mode.PARKING -> parkingThresholdG
        }

        if (magnitudeG >= threshold) {
            val now = System.currentTimeMillis()
            if (now - lastDetectionTimeMs >= DETECTION_COOLDOWN_MS) {
                lastDetectionTimeMs = now
                Log.i(TAG, "衝撃検知: ${"%.2f".format(magnitudeG)}G (閾値 ${threshold}G, mode=$mode)")
                listener.onShockDetected(magnitudeG, mode)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 未使用
    }
}
