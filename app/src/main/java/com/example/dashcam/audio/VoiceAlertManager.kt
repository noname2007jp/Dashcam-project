package com.example.dashcam.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TTS(Text-to-Speech)による音声警告を再生するクラス。
 *
 * 設計方針(仕様書より):
 * - ストレージ容量など、運転中に画面を見なくても気づけるよう音声で警告する
 * - QUEUE_ADDを使い、複数の警告が短時間に発生しても読み上げが重ならないようにする
 */
class VoiceAlertManager(context: Context) {

    companion object {
        private const val TAG = "VoiceAlertManager"
        private const val UTTERANCE_ID_PREFIX = "dashcam_alert_"
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.JAPAN)
                isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!isReady) {
                    Log.w(TAG, "日本語音声合成データが利用できません")
                }
            } else {
                Log.e(TAG, "TextToSpeechの初期化に失敗しました (status=$status)")
            }
        }
    }

    /** メッセージを読み上げる。TTS未準備の場合は何もしない。 */
    fun speak(message: String) {
        if (!isReady) {
            Log.w(TAG, "TTS未準備のため読み上げをスキップ: $message")
            return
        }
        val utteranceId = UTTERANCE_ID_PREFIX + System.currentTimeMillis()
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    /** リソースを解放する。Serviceのライフサイクル終了時に必ず呼び出すこと。 */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
