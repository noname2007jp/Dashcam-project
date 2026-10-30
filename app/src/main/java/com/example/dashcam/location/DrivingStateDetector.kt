package com.example.dashcam.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.LinkedList

/**
 * GPS速度をもとに「走行中」か「駐車中」かを自動判定するクラス。
 *
 * 設計方針:
 * - GPS速度が一定時間(SUSTAIN_DURATION_MS)継続して閾値を超えたら DRIVING へ遷移
 * - 逆に一定時間継続して閾値を下回ったら PARKING へ遷移
 * - 単発のGPSノイズによるチャタリング(頻繁な状態往復)を防ぐため、
 *   瞬間値ではなく直近の速度履歴で判定する(ヒステリシス)
 *
 * 走行判定の閾値(DRIVING_THRESHOLD)を駐車判定の閾値(PARKING_THRESHOLD)より
 * 高く設定することで、閾値付近でのバタつきをさらに抑えている。
 */
class DrivingStateDetector(
    private val context: Context,
    private val listener: Listener
) {
    enum class State {
        UNKNOWN, DRIVING, PARKING
    }

    interface Listener {
        fun onStateChanged(newState: State, currentSpeedKmh: Float)
    }

    companion object {
        private const val TAG = "DrivingStateDetector"
        private const val QUICK_DRIVING_KMH = 10.0f
        private const val DRIVING_SUSTAIN_MS = 3_000L

        // 走行中とみなす速度(km/h)。これを超えたら走行中に遷移
        private const val DRIVING_THRESHOLD_KMH = 8.0f

        // 駐車中とみなす速度(km/h)。これを下回ったら駐車中に遷移
        // (DRIVING_THRESHOLDより低くすることでヒステリシスを持たせる)
        private const val PARKING_THRESHOLD_KMH = 3.0f

        // この時間継続して条件を満たしたら状態を遷移させる(チャタリング防止)
        private const val SUSTAIN_DURATION_MS = 10_000L

        // GPS位置情報の取得間隔
        private const val LOCATION_UPDATE_INTERVAL_MS = 3_000L

        // 状態判定に使う速度履歴の保持件数
        private const val SPEED_HISTORY_SIZE = 10
    }

    /** 停止とみなす速度(km/h以下)。設定から変更可能。 */
    @Volatile var parkingThresholdKmh: Float = PARKING_THRESHOLD_KMH
    /** 停止とみなすための連続時間(ミリ秒)。設定から変更可能。 */
    @Volatile var parkingSustainMs: Long = SUSTAIN_DURATION_MS

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var currentState: State = State.UNKNOWN
    private var candidateState: State = State.UNKNOWN
    private var candidateSinceMs: Long = 0L

    private val speedHistoryKmh = LinkedList<Float>()

    // 時刻つきの速度履歴(急ブレーキの確認用。直近30秒分)
    private val timedSpeeds = LinkedList<Pair<Long, Float>>()

    /** 直近 windowMs 以内の最大速度と最新速度の差(km/h)。下がっていなければ0。 */
    fun recentSpeedDropKmh(windowMs: Long): Float {
        val now = System.currentTimeMillis()
        val recent = timedSpeeds.filter { now - it.first <= windowMs }
        if (recent.isEmpty()) return 0f
        val latest = recent.last().second
        return (recent.maxOf { it.second } - latest).coerceAtLeast(0f)
    }
    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleNewLocation(location)
        }
    }

    /**
     * GPS追跡を開始する。
     * 呼び出し前に ACCESS_FINE_LOCATION パーミッションが許可されている必要がある。
     */
    @SuppressLint("MissingPermission") // 呼び出し元でパーミッションチェック済みの前提
    fun start() {
        if (isTracking) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS
        ).build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            context.mainLooper
        )
        isTracking = true
        Log.i(TAG, "走行/駐車判定を開始しました")
    }

    fun stop() {
        if (!isTracking) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        speedHistoryKmh.clear()
        timedSpeeds.clear()
        Log.i(TAG, "走行/駐車判定を停止しました")
    }

    fun getCurrentState(): State = currentState

    private fun handleNewLocation(location: Location) {
        // Location.getSpeed() は m/s。km/hに変換
        val speedKmh = if (location.hasSpeed()) {
            location.speed * 3.6f
        } else {
            0f
        }

        timedSpeeds.addLast(System.currentTimeMillis() to speedKmh)
        while (timedSpeeds.isNotEmpty() &&
            System.currentTimeMillis() - timedSpeeds.first.first > 30_000L
        ) timedSpeeds.removeFirst()

        speedHistoryKmh.addLast(speedKmh)
        if (speedHistoryKmh.size > SPEED_HISTORY_SIZE) {
            speedHistoryKmh.removeFirst()
        }

        // 直近の速度履歴の平均値を判定に使う(単発の急な値によるブレを抑える)
        val avgSpeedKmh = speedHistoryKmh.average().toFloat()

        evaluateState(avgSpeedKmh, speedKmh)
    }

    private fun evaluateState(avgSpeedKmh: Float, latestSpeedKmh: Float) {
        // 走行側の閾値は、停止速度設定より必ず高く保つ(ヒステリシス確保)
        val drivingThreshold = maxOf(QUICK_DRIVING_KMH, parkingThresholdKmh + 3f)
        val suggestedState = when {
            // 走行は現在速度で素早く判定(発進直後に駐車モードが残らないように)
            latestSpeedKmh >= drivingThreshold -> State.DRIVING
            // 停止は厳密判定: 毎回のGPS速度が閾値以下であること(超えたら計測をやり直す)
            latestSpeedKmh <= parkingThresholdKmh -> State.PARKING
            else -> currentState // 閾値の中間帯では現状維持
        }

        if (suggestedState == State.UNKNOWN || suggestedState == currentState) {
            // 状態に変化なし、または中間帯のため候補をリセット
            if (suggestedState != candidateState) {
                candidateState = currentState
            }
            return
        }

        val now = System.currentTimeMillis()

        if (suggestedState != candidateState) {
            // 新しい候補状態の計測を開始
            candidateState = suggestedState
            candidateSinceMs = now
            return
        }

        // 同じ候補状態が SUSTAIN_DURATION_MS 続いたら本採用
        val needMs = if (suggestedState == State.PARKING) parkingSustainMs else DRIVING_SUSTAIN_MS
        if (now - candidateSinceMs >= needMs) {
            transitionTo(suggestedState, avgSpeedKmh)
        }
    }

    private fun transitionTo(newState: State, speedKmh: Float) {
        if (newState == currentState) return
        Log.i(TAG, "状態遷移: $currentState -> $newState (速度=${"%.1f".format(speedKmh)}km/h)")
        currentState = newState
        listener.onStateChanged(newState, speedKmh)
    }
}
