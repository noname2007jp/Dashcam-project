package com.example.dashcam.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * MediaStore(Download/cam)に保存された完了セグメントを、ユーザーが追加で
 * 指定したSAFフォルダ(例: 別のSDカードや特定のフォルダ)へコピーするクラス。
 *
 * 録画自体は常にMediaStore経由でDownload/cam配下に保存されるため
 * (Android/dataは一切使用しない)、これはあくまで任意の追加ミラーコピーを担当する。
 */
class FileExporter(private val context: Context) {

    companion object {
        private const val TAG = "FileExporter"

        // 保存先フォルダ配下に作成するサブフォルダ名
        private const val LOOP_SUBDIR_NAME = "dashcam_loop"
        private const val PROTECTED_SUBDIR_NAME = "dashcam_protected"
    }

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** 通常のループ録画セグメントを指定フォルダへコピーする */
    fun exportLoopSegment(destinationTreeUri: Uri, sourceUri: Uri, displayName: String) {
        exportInternal(destinationTreeUri, sourceUri, displayName, LOOP_SUBDIR_NAME)
    }

    /** 保護されたセグメントを指定フォルダへコピーする */
    fun exportProtectedSegment(destinationTreeUri: Uri, sourceUri: Uri, displayName: String) {
        exportInternal(destinationTreeUri, sourceUri, displayName, PROTECTED_SUBDIR_NAME)
    }

    private fun exportInternal(
        destinationTreeUri: Uri,
        sourceUri: Uri,
        displayName: String,
        subdirName: String
    ) {
        executor.execute {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, destinationTreeUri)
                if (rootDoc == null || !rootDoc.canWrite()) {
                    Log.e(TAG, "保存先フォルダへの書き込み権限がありません")
                    return@execute
                }

                val subDir = rootDoc.findFile(subdirName) ?: rootDoc.createDirectory(subdirName)
                if (subDir == null) {
                    Log.e(TAG, "サブフォルダの作成に失敗しました: $subdirName")
                    return@execute
                }

                // 同名ファイルが既にあれば削除してから作り直す(上書き相当)
                subDir.findFile(displayName)?.delete()

                val destDoc = subDir.createFile("video/mp4", displayName)
                if (destDoc == null) {
                    Log.e(TAG, "コピー先ファイルの作成に失敗しました: $displayName")
                    return@execute
                }

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(destDoc.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "保存先フォルダへコピー完了: $displayName -> $subdirName")
            } catch (e: Exception) {
                Log.e(TAG, "保存先フォルダへのコピーに失敗しました: $displayName", e)
            }
        }
    }
}
