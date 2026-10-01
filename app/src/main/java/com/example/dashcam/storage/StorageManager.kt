package com.example.dashcam.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.example.dashcam.camera.DashcamRecorder

/**
 * ストレージ空き容量の監視・自動削除を行うクラス。
 *
 * 録画がAndroid/data配下のアプリ専用フォルダを使わず、MediaStore経由で
 * 公開の Download/cam フォルダへ直接書き込まれる設計になったため、
 * このクラスもファイルシステム(java.io.File)ではなくMediaStoreクエリで
 * 対象ファイルを管理する。
 *
 * 設計方針(仕様書より):
 * - 自動削除ライン(デフォルト15%): 下回ったら通常のループ録画から古いファイル順に削除
 * - 録画停止ライン(デフォルト5%): 下回ったら容量枯渇によるクラッシュ防止のため録画停止
 * - 保護フォルダ上限(デフォルト2GB): 超えたら警告(自動削除はしない。イベント映像のため)
 * - チェックタイミングは呼び出し側(セグメント保存完了時など)に委譲する
 */
class StorageManager(
    private val context: Context,
    private val listener: Listener,
    private val autoDeleteThresholdPercent: Int = DEFAULT_AUTO_DELETE_THRESHOLD_PERCENT,
    private val stopThresholdPercent: Int = DEFAULT_STOP_THRESHOLD_PERCENT,
    private val protectedMaxBytes: Long = DEFAULT_PROTECTED_MAX_BYTES,
    /**
     * ループ録画フォルダに使わせる最大容量(バイト)を返す関数。
     * nullを返せば上限なし(空き容量パーセンテージによる制御のみ)。
     * 設定変更をサービス再起動なしで反映できるよう、値そのものではなく
     * 関数(都度読み出し)で受け取る。
     */
    private val maxLoopBytesProvider: () -> Long? = { null }
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

        enforceLoopCapacity()
        checkProtectedFolderSize()
    }

    private fun handleCritical(freePercent: Int) {
        Log.w(TAG, "空き容量が録画停止ライン(${stopThresholdPercent}%)を下回りました: ${freePercent}%")
        listener.onStorageCritical(freePercent)
        lastCriticalWarnTimeMs = System.currentTimeMillis()
    }

    /** 直近の警告からクールダウン時間が経過しているか(呼び出し側での音声抑制判定に利用可能) */
    fun isCriticalWarningInCooldown(): Boolean {
        return System.currentTimeMillis() - lastCriticalWarnTimeMs < WARNING_COOLDOWN_MS
    }

    fun isProtectedWarningInCooldown(): Boolean {
        return System.currentTimeMillis() - lastProtectedWarnTimeMs < WARNING_COOLDOWN_MS
    }

    /** ループ録画フォルダ(dashcam_loop)内のアイテムを、古い順に削除して空きを確保する */
    private fun autoDeleteOldest() {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(DashcamRecorder.LOOP_RELATIVE_PATH)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        var deletedCount = 0
        var freedBytes = 0L

        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext()) {
                    if (computeFreePercent() > autoDeleteThresholdPercent) break

                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    val itemUri: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        .buildUpon().appendPath(id.toString()).build()

                    val rows = resolver.delete(itemUri, null, null)
                    if (rows > 0) {
                        deletedCount++
                        freedBytes += size
                        Log.i(TAG, "古いセグメントを自動削除: id=$id (${size}bytes)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "自動削除中にエラーが発生しました", e)
        }

        if (deletedCount > 0) {
            listener.onAutoDeleted(deletedCount, freedBytes)
        }
    }

    /**
     * ループ録画フォルダの合計サイズが、ユーザー設定の上限を超えていたら
     * 古い順に削除して上限内に収める。上限が未設定(null)の場合は何もしない。
     */
    private fun enforceLoopCapacity() {
        val maxBytes = maxLoopBytesProvider() ?: return
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(DashcamRecorder.LOOP_RELATIVE_PATH)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        val items = mutableListOf<Pair<Long, Long>>() // id to size
        var totalSize = 0L

        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    items.add(id to size)
                    totalSize += size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "容量上限チェック中にエラーが発生しました", e)
            return
        }

        if (totalSize <= maxBytes) return

        var deletedCount = 0
        var freedBytes = 0L
        for ((id, size) in items) {
            if (totalSize <= maxBytes) break
            val itemUri: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                .buildUpon().appendPath(id.toString()).build()
            val rows = resolver.delete(itemUri, null, null)
            if (rows > 0) {
                deletedCount++
                freedBytes += size
                totalSize -= size
                Log.i(TAG, "容量上限超過のため自動削除: id=$id (${size}bytes)")
            }
        }

        if (deletedCount > 0) {
            listener.onAutoDeleted(deletedCount, freedBytes)
        }
    }

    private fun checkProtectedFolderSize() {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(DashcamRecorder.PROTECTED_RELATIVE_PATH)

        var totalSize = 0L
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    totalSize += cursor.getLong(sizeColumn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保護フォルダサイズの取得に失敗しました", e)
            return
        }

        if (totalSize > protectedMaxBytes) {
            Log.w(TAG, "保護フォルダが上限(${protectedMaxBytes}bytes)を超過: ${totalSize}bytes")
            listener.onProtectedFolderOverLimit(totalSize)
            lastProtectedWarnTimeMs = System.currentTimeMillis()
        }
    }

    /** 公開Downloadボリュームの空き容量(%)を取得する */
    private fun computeFreePercent(): Int {
        return try {
            @Suppress("DEPRECATION")
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val stat = StatFs(downloadsDir.path)
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
