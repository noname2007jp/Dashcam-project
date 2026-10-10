package com.example.dashcam

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * アプリが異常終了(クラッシュ)したとき、原因調査用にスタックトレースを
 * Download/cam/crash_log_日時.txt へ保存する。保存後は通常のクラッシュ処理に引き継ぐ。
 */
class DashcamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "crash_log_$stamp.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/cam/")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write("thread=${thread.name}\n${sw}".toByteArray())
                    }
                }
            } catch (_: Throwable) {
                // ログ保存の失敗でさらに落ちないようにする
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
