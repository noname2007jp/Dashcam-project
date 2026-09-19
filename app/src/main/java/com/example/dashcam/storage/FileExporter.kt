package com.example.dashcam.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 完了したセグメントファイルを、ユーザーが設定した保存先フォルダ(SAF)へ
 * 自動的にコピーするクラス。
 *
 * 録画そのものはアプリ専用フォルダへ書き込まれるため、このクラスは
 * あくまで「利用者が取り出しやすい場所への複製」を担当する(非破壊・追加コピー)。
 */
class FileExporter(private val context: Context) {

    companion object {
        private const val TAG = "FileExporter"

        // 保存先フォルダ配下に作成するサブフォルダ名
        private const val LOOP_SUBDIR_NAME = "dashcam_loop"
        private const val PROTECTED_SUBDIR_NAME = "dashcam_protected"
    }

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** 通常のループ録画セグメントを保存先フォルダへコピーする */
    fun exportLoopSegment(destinationTreeUri: Uri, sourceFile: File) {
        exportInternal(destinationTreeUri, sourceFile, LOOP_SUBDIR_NAME)
    }

    /** 保護されたセグメントを保存先フォルダへコピーする */
    fun exportProtectedSegment(destinationTreeUri: Uri, sourceFile: File) {
        exportInternal(destinationTreeUri, sourceFile, PROTECTED_SUBDIR_NAME)
    }

    private fun exportInternal(destinationTreeUri: Uri, sourceFile: File, subdirName: String) {
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
                subDir.findFile(sourceFile.name)?.delete()

                val destDoc = subDir.createFile("video/mp4", sourceFile.name)
                if (destDoc == null) {
                    Log.e(TAG, "コピー先ファイルの作成に失敗しました: ${sourceFile.name}")
                    return@execute
                }

                context.contentResolver.openOutputStream(destDoc.uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "保存先フォルダへコピー完了: ${sourceFile.name} -> $subdirName")
            } catch (e: Exception) {
                Log.e(TAG, "保存先フォルダへのコピーに失敗しました: ${sourceFile.name}", e)
            }
        }
    }
}
