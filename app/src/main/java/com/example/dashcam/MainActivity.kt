package com.example.dashcam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 最小構成の起動用Activity。
 * 今後、パーミッションリクエスト・フォアグラウンドサービス起動処理をここに追加していく想定。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
