package com.example.dashcam.camera

import kotlin.math.abs

/**
 * 隣り合う2フレーム(輝度Y)の「画面全体のズレ(px)」を推定する。
 * 列方向・行方向の明るさの平均(プロファイル)を比べるだけの軽量な方式で、
 * 衝突や段差でカメラが揺れた瞬間の大きさを測る目的(衝撃判定の補助)。
 * 物体認識はしない。ズレが測れない(模様が少ない等)場合は 0 を返す。
 */
object ShakeEstimator {
    private const val RANGE = 20

    /** @return Pair(dx, dy)。センサー画像の軸(端末の向きとは無関係) */
    fun estimate(prev: ByteArray, cur: ByteArray, width: Int, height: Int, stride: Int): Pair<Int, Int> {
        if (prev.size < stride * (height - 1) + width || cur.size < stride * (height - 1) + width) {
            return 0 to 0
        }
        val pCol = DoubleArray(width); val cCol = DoubleArray(width)
        val pRow = DoubleArray(height); val cRow = DoubleArray(height)
        var y = 0
        var rows = 0
        while (y < height) {
            val base = y * stride
            for (x in 0 until width) {
                pCol[x] += (prev[base + x].toInt() and 0xFF).toDouble()
                cCol[x] += (cur[base + x].toInt() and 0xFF).toDouble()
            }
            rows++
            y += 2
        }
        for (x in 0 until width) { pCol[x] = pCol[x] / rows; cCol[x] = cCol[x] / rows }
        val cols = (width + 1) / 2
        for (yy in 0 until height) {
            val base = yy * stride
            var ps = 0.0; var cs = 0.0
            var x = 0
            while (x < width) {
                ps += (prev[base + x].toInt() and 0xFF)
                cs += (cur[base + x].toInt() and 0xFF)
                x += 2
            }
            pRow[yy] = ps / cols; cRow[yy] = cs / cols
        }
        return profileShift(cCol, pCol) to profileShift(cRow, pRow)
    }

    private fun profileShift(a0: DoubleArray, b0: DoubleArray): Int {
        val n = a0.size
        val am = a0.average(); val bm = b0.average()
        val a = DoubleArray(n) { a0[it] - am }
        val b = DoubleArray(n) { b0[it] - bm }
        fun sad(d: Int): Double {
            var sum = 0.0
            val len = n - abs(d)
            if (len <= 0) return Double.MAX_VALUE
            for (i in 0 until len) {
                val ai = if (d >= 0) i + d else i
                val bi = if (d >= 0) i else i - d
                sum += abs(a[ai] - b[bi])
            }
            return sum / len
        }
        val s0 = sad(0)
        var best = s0
        var bestD = 0
        for (d in -RANGE..RANGE) {
            val v = sad(d)
            if (v < best) { best = v; bestD = d }
        }
        return if (best < 0.8 * s0) bestD else 0
    }
}
