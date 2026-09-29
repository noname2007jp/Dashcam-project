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

## 画面表示について

設置時の画角調整のため、**メイン画面表示中はカメラのプレビュー映像が表示されます**
(`androidx.camera.view.PreviewView`)。`DashcamForegroundService`にバインドし、
`Preview.SurfaceProvider`を中継する構成になっています。

- Activityが表示されている間: `attachPreviewSurfaceProvider()`でプレビュー描画
- Activityが非表示(onStop)になったら: `detachPreviewSurfaceProvider()`で描画停止

録画自体はActivityの表示・非表示に関わらずサービス側で継続します。
「走行中は画面を消してバッテリー節約」という当初の要件は、今後実装する
「ディスプレイの時間設定OFF」機能(画面の疑似消灯タイマー)で別途対応する想定です。

画面下部の「終了」ボタンから、確認ダイアログを経てドラレコ自体(フォアグラウンドサービス)を
完全に終了できます。誤操作防止のため、タップ後に確認ダイアログが出ます。

## 保存先について(重要: Android/dataは使用しません)

**録画は常にMediaStore経由で公開の`Download/cam`フォルダへ直接保存されます。**
`Android/data/com.example.dashcam/files`のようなアプリ専用フォルダは一切使用しません。
理由は、アプリ専用フォルダは他のアプリ(ファイルマネージャー・動画プレイヤー等)から
直接アクセスしづらく、再生や整理がしにくいためです。

- ループ録画: `Download/cam/dashcam_loop/`
- 保護されたイベント映像: `Download/cam/dashcam_protected/`
- 保護フォルダへの「移動」は、ファイルの実体コピーではなくMediaStoreの
  `RELATIVE_PATH`更新によって行われるため高速(Android 10以降でサポートされる方式)
- **このためminSdkを29(Android 10)に引き上げています**(MediaStore.Downloadsコレクション
  がAPI 29以降のみ対応のため)

## 設定画面(追加コピー先フォルダ・使用レンズ)

- `app/src/main/java/com/example/dashcam/SettingsActivity.kt`
  メイン画面右上の三本線メニューから遷移。
  - **追加コピー先フォルダ**: SAFで任意のフォルダを選ぶと、`Download/cam`への保存に加えて
    そのフォルダへも自動コピーされる(例: 別のSDカードへのバックアップ用途)
  - **使用レンズ**: 端末が複数の背面カメラ(広角等)を持つ場合、選択できる
    (`CameraLensHelper`が焦点距離から簡易的に「広角/標準/望遠」ラベルを推定)
- `app/src/main/java/com/example/dashcam/storage/FileExporter.kt`
  追加コピー先フォルダ(SAF)へのコピー処理本体。`Download/cam`への保存自体は
  `DashcamRecorder`がMediaStore経由で直接行うため、これは追加ミラーのみを担当する
- `app/src/main/java/com/example/dashcam/storage/StorageManager.kt`
  MediaStoreクエリで`Download/cam`配下のファイルを管理(空き容量チェック・自動削除・
  保護フォルダの上限チェック)。ファイルシステムではなくMediaStoreを直接操作する
- `app/src/main/java/com/example/dashcam/camera/CameraLensHelper.kt`
  背面カメラの列挙・ラベル付け・CameraSelector生成
- `app/src/main/java/com/example/dashcam/settings/SettingsManager.kt`
  設定の永続化(SharedPreferences)。追加コピー先フォルダURI・優先カメラIDを保持

## メタデータ記録(方式3)

- `app/src/main/java/com/example/dashcam/metadata/MetadataRecorder.kt`
  GPS位置・速度・日時を1秒間隔でサンプリングし、セグメント保存完了のタイミングで
  動画と同じベースファイル名(拡張子のみ`.json`)・同じ相対パスでMediaStoreに保存する
  (例: `2026-09-18_143207.mp4` に対して `2026-09-18_143207.json`)
- 映像自体への焼き込みはまだ行わない(将来のMediaCodec+OpenGLベースの書き出し機能が
  このJSONを読み込んで使う想定)
- GPSの測位ができていない場合でもタイムスタンプ自体は記録し続ける(位置・速度はnull)

### 既知の制約

- 衝撃検知等で「直前に完了したセグメント」が事後的に保護フォルダへ移動される場合、
  対応するメタデータJSONはこの移動処理の対象外(ループフォルダに残る)。
  現状は許容範囲としているが、必要であれば今後の改善対象とする

## 書き出し機能(MediaCodec + OpenGL、FFmpeg不使用)

参考記事([takusan.negitoro.dev](https://takusan.negitoro.dev/posts/android_add_canvas_text_to_video/))
の構成をベースに実装。

- `app/src/main/java/com/example/dashcam/export/CodecInputSurface.kt`
  EGL/OpenGLのセットアップ。MediaCodecのエンコーダー入力SurfaceをOpenGL経由で描画可能にする
- `app/src/main/java/com/example/dashcam/export/TextureRenderer.kt`
  動画フレーム(External OESテクスチャ)とCanvas(2Dテクスチャ)をフラグメントシェーダーで
  切り替えながら合成描画する
- `app/src/main/java/com/example/dashcam/export/VideoOverlayProcessor.kt`
  MediaExtractor(デコード)→OpenGL合成→MediaCodec(エンコード)→MediaMuxerの本体処理。
  音声は含まない(映像のみ)
- `app/src/main/java/com/example/dashcam/export/AudioMuxer.kt`
  映像のみのファイルに、元動画の音声トラックをストリームコピー(再エンコードなし)で合成
- `app/src/main/java/com/example/dashcam/export/OverlayTextRenderer.kt`
  メタデータJSON(MetadataRecorder.Sample)を動画の再生位置に応じてCanvasへ描画する。
  日時/位置/速度のON・OFF、表示位置(四隅)に対応
- `app/src/main/java/com/example/dashcam/export/VideoExportManager.kt`
  上記を統括し、`Download/cam/dashcam_export/`へMediaStore経由で保存する
- `app/src/main/java/com/example/dashcam/ExportActivity.kt`
  書き出し操作画面。メイン画面右上メニュー(オーバーフロー)の「書き出し」から遷移。
  SAFで動画を選択すると、同じベース名の`.json`をMediaStoreから自動的に探して使う

### 処理の流れ

1. 元動画(MediaStoreのUri)を`MediaExtractor`で直接デコード(事前コピー不要)
2. デコードされたフレームをOpenGLでSurfaceTextureとして受け取り、同時にメタデータの
   テキストをCanvasに描画してテクスチャとして合成
3. 合成結果を`MediaCodec`のエンコーダー入力Surfaceへ描画→再エンコード(映像のみ)
4. 元動画から音声トラックを抜き出し、映像のみのファイルとストリームコピーで合成
5. 完成したmp4を`Download/cam/dashcam_export/`へMediaStore経由で保存

### 既知の制約・注意点

- OpenGL/MediaCodecまわりは実機でないと正しく動作するか検証できない(エミュレータでは
  不安定になりやすい)。**実機での動作確認が必須**
- 処理時間はそこそこかかる(動画の長さ・端末性能次第)。現状はActivity内の
  コルーチンで実行しているため、Activityを閉じると処理が中断される可能性がある。
  本格的に使うならフォアグラウンドサービス化を検討すべき
- 音声・映像のインターリーブは簡易的な実装(トラックごとに一括書き込み)。
  一般的なプレイヤーでは問題なく再生できるはずだが、シビアなストリーミング用途には
  不向き
- メタデータが見つからない場合は、テキストなしでそのまま書き出される

## 未実装(今後追加予定)

- ディスプレイの時間設定OFF(疑似消灯)
- 書き出し処理のフォアグラウンドサービス化(長時間処理の安定性向上)
