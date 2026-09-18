# Dashcam (Android)

個人用ドライブレコーダーアプリ。詳細仕様は別途仕様書を参照。

## GitHub Actionsでのビルド方法

1. このプロジェクト一式をGitHubリポジトリのルートに配置してpush
   (`.github/workflows/android-build.yml` が含まれていればOK)
2. GitHubの「Actions」タブを開く
3. `Android Build` ワークフローが自動実行される(pushまたはPR時)
   - 手動実行したい場合は「Run workflow」ボタンからも実行可能(`workflow_dispatch`)
4. ビルド完了後、ワークフロー実行結果のページ下部「Artifacts」から
   `dashcam-debug-apk` をダウンロードするとAPKが取得できる

## ローカルでビルドする場合

Android Studioでこのフォルダを開き、通常通り実行(Run)またはBuild > Build APKでOK。
Gradle Wrapperを同梱していないため、初回はAndroid Studioが自動生成する
(または `gradle wrapper` コマンドを実行してから `./gradlew assembleDebug`)。

## 現在の実装状況

- `app/src/main/java/com/example/dashcam/camera/DashcamRecorder.kt`
  CameraXによる録画コア部分(常時ループ録画・セグメント分割)
- `app/src/main/java/com/example/dashcam/service/DashcamForegroundService.kt`
  フォアグラウンドサービス(画面消灯後も録画継続、PARTIAL_WAKE_LOCK保持、常駐通知)
- `app/src/main/java/com/example/dashcam/sensor/ShockDetector.kt`
  加速度センサーによる衝撃検知(走行中0.5G/駐車監視0.2G、0.1G刻みで調整可能)
- `app/src/main/java/com/example/dashcam/sensor/TailgatingDetector.kt`
  急ブレーキ連続検知(煽り運転の可能性の間接検知)。閾値0.3G(0.1G刻み調整可能)、
  20秒以内に3回以上の急ブレーキで「煽り運転の可能性」と判定。走行中のみ有効
- `app/src/main/java/com/example/dashcam/location/DrivingStateDetector.kt`
  GPS速度による走行/駐車の自動判定(走行判定8km/h、駐車判定3km/h、
  チャタリング防止のため10秒間の継続を確認してから状態遷移)
- `app/src/main/java/com/example/dashcam/camera/MotionDetector.kt`
  ImageAnalysisによる動体検知(駐車監視モード用)。フレーム間の輝度差分方式、
  変化率3%以上が3フレーム連続で「動きあり」、無検知15秒継続で「動き終了」と判定
- `app/src/main/java/com/example/dashcam/MainActivity.kt`
  カメラ・マイク・位置情報・通知のパーミッションリクエストとサービス起動

## 現在の録画ロジック(DashcamForegroundService)

- 走行中: `DashcamRecorder`が常時ループ録画
- 駐車中: 通常は録画停止、`MotionDetector`が動きを検知した時だけ録画開始、
  動きが止まって15秒経過したら録画停止
- 衝撃検知(`ShockDetector`): 駐車監視中であれば衝撃検知そのものも録画開始トリガーになる
  (動体検知より早く反応させ、当て逃げ等の瞬間を録り逃さないため)

## イベント保護(衝撃検知)のファイル処理

`DashcamRecorder.markCurrentSegmentAsProtected()`呼び出し時:
- 直前に完了済みのセグメントを即座に`dashcam_protected`フォルダへ移動(`outputDir`の
  親ディレクトリ配下に作成)
- 現在録画中のセグメントは、そのセグメントの録画完了(Finalize)時に同フォルダへ移動
- `File.renameTo()`優先、失敗時はコピー+削除でフォールバック
- 保護フォルダのファイルは通常のループ録画の管理対象から外れる

## ストレージ管理・音声警告

- `app/src/main/java/com/example/dashcam/storage/StorageManager.kt`
  空き容量チェック。自動削除ライン15%/録画停止ライン5%(いずれもデフォルト値)、
  保護フォルダ上限2GB。セグメント保存完了のたびに`checkAndManage()`を呼び出す設計
- `app/src/main/java/com/example/dashcam/audio/VoiceAlertManager.kt`
  TextToSpeechによる音声警告。ストレージ危険域到達時・保護フォルダ上限超過時に読み上げ
  (同一警告の連発を防ぐため5分のクールダウンあり)

## 未実装(今後追加予定)

- 書き出し機能(FFmpegでのテキスト焼き込み)
- ディスプレイの時間設定OFF(疑似消灯)
