package com.example.dashcam.storage

import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * ストレージ空き容量の監視・自動削除を行うクラス。
 *
 * 設計方針(仕様書より):
 * - 自動削除ライン(デフォルト15%): 下回ったら通常のループ録画から古いファイル順に削除
 * - 録画停止ライン(デフォルト5%): 下回ったら容量枯渇によるクラッシュ防止のため録画停止
 * - 保護フォルダ上限(デフォルト2GB): 超えたら警告(自動削除はしない。イベント映像のため)
 * - チェックタイミングは呼び出し側(セグメント保存完了時など)に委譲する
 */
class StorageManager(
    private val loopDir: File,
    private val protectedDir: File,
    private val listener: Listener,
    private val autoDeleteThresholdPercent: Int = DEFAULT_AUTO_DELETE_THRESHOLD_PERCENT,
    private val stopThresholdPercent: Int = DEFAULT_STOP_THRESHOLD_PERCENT,
    private val protectedMaxBytes: Long = DEFAULT_PROTECTED_MAX_BYTES
) {
    interface Listener {
        /** 自動削除を実行したときに呼ばれる */
        fun onAutoDeleted(deletedCount: Int, freedBytes: Long)

        /** 録画停止ラインを下回ったときに呼ばれる(呼び出し側で録画停止処理を行う) */
        fun onStorageCritical(freePercent: Int)

        /** 保護フォルダの合計サイズが上限を超えたときに呼ばれる */
        fun onProtectedFolderOverLimit(currentSizeBytes: Long)
    }

    companion object {
        private const val TAG = "StorageManager"

        const val DEFAULT_AUTO_DELETE_THRESHOLD_PERCENT = 15
        const val DEFAULT_STOP_THRESHOLD_PERCENT = 5
        const val DEFAULT_PROTECTED_MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2GB

        // 同じ警告を短時間に連発させないためのクールダウン
        private const val WARNING_COOLDOWN_MS = 5 * 60 * 1000L // 5分
    }

    private var lastCriticalWarnTimeMs = 0L
    private var lastProtectedWarnTimeMs = 0L

    /**
     * ストレージ状態をチェックし、必要に応じて自動削除や警告コールバックを行う。
     * セグメント保存完了のタイミング等、呼び出し側の都合の良いタイミングで呼び出す。
     */
    fun checkAndManage() {
        val freePercent = computeFreePercent()
        Log.i(TAG, "空き容量チェック: ${freePercent}%")

        when {
            freePercent <= stopThresholdPercent -> {
                handleCritical(freePercent)
                // 危険域でもまず削除を試み、少しでも空きを確保する
                autoDeleteOldest()
            }
            freePercent <= autoDeleteThresholdPercent -> {
                autoDeleteOldest()
            }
        }

        checkProtectedFolderSize()
    }

    private fun handleCritical(freePercent: Int) {
        val now = System.currentTimeMillis()
        // 録画停止自体は呼び出し側が毎回安全に処理できる前提のため常に通知するが、
        // 音声警告のスパムは呼び出し側の判断に委ねるためここではフラグ管理はしない。
        // (呼び出し側でTTSのクールダウンを別途管理する設計にしてもよい)
        Log.w(TAG, "空き容量が録画停止ライン(${stopThresholdPercent}%)を下回りました: ${freePercent}%")
        listener.onStorageCritical(freePercent)
        lastCriticalWarnTimeMs = now
    }

    /** 直近の警告からクールダウン時間が経過しているか(呼び出し側での音声抑制判定に利用可能) */
    fun isCriticalWarningInCooldown(): Boolean {
        return System.currentTimeMillis() - lastCriticalWarnTimeMs < WARNING_COOLDOWN_MS
    }

    fun isProtectedWarningInCooldown(): Boolean {
        return System.currentTimeMillis() - lastProtectedWarnTimeMs < WARNING_COOLDOWN_MS
    }

    private fun autoDeleteOldest() {
        if (!loopDir.exists()) return

        val files = loopDir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() }
            ?: return

        var deletedCount = 0
        var freedBytes = 0L

        for (file in files) {
            val freePercent = computeFreePercent()
            if (freePercent > autoDeleteThresholdPercent) break

            val size = file.length()
            if (file.delete()) {
                deletedCount++
                freedBytes += size
                Log.i(TAG, "古いセグメントを自動削除: ${file.name} (${size}bytes)")
            }
        }

        if (deletedCount > 0) {
            listener.onAutoDeleted(deletedCount, freedBytes)
        }
    }

    private fun checkProtectedFolderSize() {
        if (!protectedDir.exists()) return

        val totalSize = protectedDir.listFiles { f -> f.isFile }
            ?.sumOf { it.length() } ?: 0L

        if (totalSize > protectedMaxBytes) {
            Log.w(TAG, "保護フォルダが上限(${protectedMaxBytes}bytes)を超過: ${totalSize}bytes")
            listener.onProtectedFolderOverLimit(totalSize)
            lastProtectedWarnTimeMs = System.currentTimeMillis()
        }
    }

    private fun computeFreePercent(): Int {
        return try {
            val stat = StatFs(loopDir.path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (totalBytes <= 0) return 100
            ((availableBytes * 100) / totalBytes).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "空き容量の取得に失敗しました", e)
            100 // 取得失敗時は安全側(問題なしとみなす)に倒す
        }
    }
}
