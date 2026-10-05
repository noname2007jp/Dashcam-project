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
    そのフォルダへも自動コピーされる(例: 別のSDカードへのバックアップ用途)。
    **`Download/cam`自体や、その内側・外側で重複する場所は選択できない**
    (同じファイルへコピーしようとしてエラーになるため、選択時とコピー実行時の両方で
    チェックし、該当する場合は警告またはスキップする)
  - **使用レンズ**: 端末が超広角に対応している場合、選択できる
    (`CameraLensHelper`がズーム倍率の対応範囲から判定。Pixel等は広角/超広角が別カメラIDでは
    なく1つの論理カメラのズーム倍率で切り替わる構成のため、この方式を採用)
- `app/src/main/java/com/example/dashcam/storage/FileExporter.kt`
  追加コピー先フォルダ(SAF)へのコピー処理本体。`Download/cam`への保存自体は
  `DashcamRecorder`がMediaStore経由で直接行うため、これは追加ミラーのみを担当する
- `app/src/main/java/com/example/dashcam/storage/StorageManager.kt`
  MediaStoreクエリで`Download/cam`配下のファイルを管理(空き容量チェック・自動削除・
  保護フォルダの上限チェック)。ファイルシステムではなくMediaStoreを直接操作する
- `app/src/main/java/com/example/dashcam/camera/CameraLensHelper.kt`
  超広角レンズの対応判定(`CONTROL_ZOOM_RATIO_RANGE`、Android 11以降)とズーム倍率の選択肢生成
- `app/src/main/java/com/example/dashcam/settings/SettingsManager.kt`
  設定の永続化(SharedPreferences)。追加コピー先フォルダURI・優先カメラIDを保持

## メタデータ記録(方式3、1秒ごとの逐次書き込み)

- `app/src/main/java/com/example/dashcam/metadata/MetadataRecorder.kt`
  GPS位置・速度・日時を1秒間隔でサンプリングし、**サンプリングのたびにローカルの
  一時ファイル(キャッシュディレクトリ、JSON Lines形式)へ逐次追記**する。
  メモリ上にため込んでセグメント完了時にまとめて書き出す方式だと、アプリが
  強制終了した場合に最大セグメント分(2分)のデータが失われるため、1秒ごとに
  確実にディスクへ書き込む設計にしている
- セグメント開始(`DashcamRecorder.Listener.onSegmentStarted`)のタイミングで
  新しい一時ファイルを用意し、セグメント完了(`onSegmentSaved`/`onProtectedSegmentSaved`)の
  タイミングで、動画と同じベースファイル名(拡張子のみ`.json`)・同じ相対パスで
  MediaStoreへアップロード、一時ファイルを削除する
  (例: `2026-09-18_143207.mp4` に対して `2026-09-18_143207.json`)
- 保存形式はJSON Lines(1行1サンプルのJSONオブジェクト)。逐次追記に向いているため
  この形式を採用している。書き出し機能(`VideoExportManager`)側は、この形式に加えて
  旧形式(JSON配列をまとめて書き出す形式)も読めるようフォールバックを用意している
- 映像自体への焼き込みはまだ行わない(MediaCodec+OpenGLベースの書き出し機能が
  このJSONを読み込んで使う)
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

### 修正履歴(2026-10時点)

- **GPS/速度の取得方式**: `location.hasSpeed()`→`location.getSpeed() * 3.6f`(m/s→km/h)という
  標準的な方式で取得しており、方式自体に問題はないことを確認済み。ただし**Android 14以降の
  端末でGPSの継続取得が不安定になりうる設定不備**を発見し修正(下記参照)
- **位置情報のforegroundServiceType不足**: `AndroidManifest.xml`の`<service>`定義と
  `startForeground()`呼び出しに`location`タイプが指定されておらず、カメラ用途の
  `camera`タイプのみだった。Android 14以降はこれが原因で裏側でのGPS更新が制限される
  可能性があるため、`camera|location`に修正し、`FOREGROUND_SERVICE_LOCATION`権限も追加した
- **書き出し時のテキスト向きのズレ**: `VideoExportManager`が元動画の幅・高さを
  `MediaMetadataRetriever`から取得する際、回転情報(`METADATA_KEY_VIDEO_ROTATION`)を
  考慮していなかったため、90度/270度回転の動画でテキスト描画用Canvasの縦横が
  実際の映像の向きと食い違っていた。回転情報を読み取って幅・高さを補正するよう修正
  (`VideoOverlayProcessor`側の回転補正も90度専用だったものを0/90/180/270度すべてに対応するよう汎用化)

### 既知の制約・注意点

- OpenGL/MediaCodecまわりは実機でないと正しく動作するか検証できない(エミュレータでは
  不安定になりやすい)。**実機での動作確認が必須**
- 出力の解像度・ビットレート・フレームレートは`MediaMetadataRetriever`で元動画から
  実際の値を読み取り、それに合わせてエンコードする(固定値ではない)。取得に失敗した
  場合のみ1920x1080・8Mbps・30fpsの既定値にフォールバックする
- 処理時間はそこそこかかる(動画の長さ・端末性能次第)。現状はActivity内の
  コルーチンで実行しているため、Activityを閉じると処理が中断される可能性がある。
  本格的に使うならフォアグラウンドサービス化を検討すべき
- 音声・映像のインターリーブは簡易的な実装(トラックごとに一括書き込み)。
  一般的なプレイヤーでは問題なく再生できるはずだが、シビアなストリーミング用途には
  不向き
- メタデータが見つからない場合は、テキストなしでそのまま書き出される
- 追加コピー先フォルダは`Download/cam`自体・その内側/外側と重複する場所を選べない
  (選択時・実行時の両方でチェック)

## 録画容量の上限設定

- `app/src/main/java/com/example/dashcam/storage/StorageManager.kt`の`enforceLoopCapacity()`
  ループ録画フォルダ(`dashcam_loop`)の合計サイズが設定した上限を超えたら、古い順に削除する。
  保護フォルダは対象外
- 設定画面で「無制限/8GB/16GB/32GB/64GB/128GB/256GB」から選択可能
- 空き容量パーセンテージによる制御(自動削除15%・停止5%)とは独立して動作し、両方のうち
  いずれかの条件で削除がトリガーされる
- 設定変更はサービス再起動なしで次回チェック時から反映される(`maxLoopBytesProvider`経由で
  都度最新の設定値を読み出すため)

## 一時停止/再開機能

メイン画面下部に「一時停止」ボタンを追加(「終了」ボタンの隣)。

- **一時停止**: 録画のみ停止する。サービス・各種センサー・カメラのバインドは維持したまま
  (「終了」と違い、アプリ自体は動き続ける)
- 一時停止中は、走行検知・動体検知・衝撃検知・煽り運転検知による録画の自動再開・
  セグメント保護トリガーを全て無効化(`isPausedByUser`フラグでガード)
- **再開**: 現在の走行/駐車状態に応じて適切な録画モードに復帰する
- 用途: 書き出し作業中など、録画を続けたくない場面でアプリを終了せずに一時停止できる

## UI・操作性の改善(2026-10)

1. **画面回転への追従**: `DashcamRecorder`がサービス側でカメラをバインドしているため、
   起動時の向きに固定される不具合があった。`OrientationEventListener`で端末の向きを
   監視し、Preview/VideoCaptureの`targetRotation`を動的に更新するよう修正
2. **書き出し時の向き選択**: 上記の回転追従が完全に信頼できるとは限らないため、
   `ExportActivity`に「横(推奨)/縦」の手動選択を追加。自動検出した回転情報には
   頼らず、常にこの指定を優先して出力解像度を決定する(デフォルトは横)
3. **ツールバーの表示/非表示切替**: プレビュー画面をタップすると、上部のツールバー
   (アプリ名・メニューのあるバー)の表示/非表示が切り替わる
4. **アプリアイコン**: カメラ+録画ランプのAdaptive Icon(`ic_launcher_background.xml` /
   `ic_launcher_foreground.xml`)を新規作成し、デフォルトアイコンから変更
5. **開始ボタンによる手動起動**: アプリ起動時の自動開始を廃止し、「開始」ボタンを
   押したときのみ録画を開始するよう変更。「終了」はアプリを閉じず「開始」ボタンの
   表示に戻るだけになった(`DashcamForegroundService.isRunning`で起動状態を判定)
6. **検知閾値の設定項目**: 設定画面に、走行中の衝撃検知・駐車監視中の衝撃検知・
   急ブレーキ(煽り運転の可能性)検知の各閾値をSeekBar(0.1G刻み)で調整できる項目を追加
   (`SettingsManager`に保存、次回サービス起動時から反映)

## 未実装(今後追加予定)

- ディスプレイの時間設定OFF(疑似消灯)
- 書き出し処理のフォアグラウンドサービス化(長時間処理の安定性向上)
