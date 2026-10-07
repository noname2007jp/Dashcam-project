# 変更点まとめ(2026-10-07)

対象: **プレビュー映像の向き不具合の修正** + **アプリ起動時からのプレビュー表示**

---

## 1. 症状(修正前)

| # | 症状 |
|---|---|
| 1 | 縦で起動 → 横に向けるとプレビュー画像が縦長になる |
| 2 | 右下の横向き回転マークを押すと、右が上になった縦長の映像になる |
| 3 | 横で起動しても縦で起動しても同じ挙動(起動時の向きに関係なく再現) |
| 4 | 録画ファイル自体は正常(プレビュー表示だけの問題) |

---

## 2. 原因

プレビューの回転は最終的に CameraX の `Preview.targetRotation` で決まるが、この値が
**バインド時の1回だけ設定され、その後まったく更新されていなかった**。

- `PreviewView` は自身の `Display.rotation` とセンサー角度から表示変換を組み立てるが、
  `Preview.targetRotation` は自分では設定しない(AndroidX の `PreviewView.java` で確認)。
- したがって `targetRotation` と `Display.rotation` が90度ずれると、
  **プレビューだけが縦長・右が上**に見える。録画側は `OrientationEventListener` で
  独立に更新しているため正常に見える(症状4と一致)。
- `android:screenOrientation="fullSensor"` のため、**90度↔270度(ランドスケープ左右反転)の
  切り替えではActivityの構成変更が発生しない**。`onCreate` / `onServiceConnected` に
  依存した同期では追従できないケースが残る(症状3と一致)。
- さらに `previewView.display` が未アタッチ時に `null` となり、`?: ROTATION_0` で
  **黙って縦(0)に固定**される経路があった(横起動時に壊れる要因)。

---

## 3. 修正内容(ファイル別)

### 3-1. `app/src/main/java/com/example/dashcam/MainActivity.kt`

| 修正 | 内容 |
|---|---|
| 修正A(必須) | `DisplayManager.DisplayListener` を追加。`onDisplayChanged` で `syncPreviewRotation()` を呼ぶ。90度↔270度の反転検知に必須 |
| 修正A(必須) | `onResume` / `onConfigurationChanged` でも `syncPreviewRotation()` を呼び、3経路で同期 |
| 修正A(必須) | `syncPreviewRotation()` は `previewView.display ?: return` とし、**未アタッチ時に既定値で固定しない**よう変更(従来の `?: ROTATION_0` を廃止) |
| 追加要件 | `onStart()` で、**録画中かどうかに関わらず**バインドするよう変更(起動時からプレビューを出すため) |
| 追加要件 | 起動時に権限が無ければ権限ダイアログを表示し、許可後に自動バインドしてプレビュー表示。拒否時は画面下部に「カメラの許可が必要です」と表示 |
| 追加要件 | `onServiceConnected` で `ensureCameraPrepared()` を呼び、録画を開始せずカメラ/プレビューだけ準備 |
| 追加要件 | 「終了」で録画を止めたあと、プレビュー復帰のため再バインド(400ms後) |
| 付随修正 | `onServiceConnected` の `updateUiForRunningState(true)` を `DashcamForegroundService.isRunning` 基準に修正(プレビュー用バインドで誤って「一時停止/終了」ボタンが出るのを防ぐ) |

### 3-2. `app/src/main/java/com/example/dashcam/camera/DashcamRecorder.kt`

| 修正 | 内容 |
|---|---|
| 修正B | `setPreviewTargetRotation()` を冪等化(同一値ならスキップ)し、更新をログ出力 |
| 修正D | `Preview` に `ResolutionSelector` を導入し、録画(FHD=16:9)と同じアスペクト比に統一 |
| 修正D | `ImageAnalysis` の非推奨 `setTargetResolution(Size(320,240))` を `ResolutionSelector` + `ResolutionStrategy(640x480)` に置換 |
| 追加要件 | `initialize()` を冪等化(`initialized` フラグ)。プレビュー用バインド→録画開始の順で呼ばれても二重バインドしない |
| 付随修正 | `release()` で `setSurfaceProvider(null)` と参照のクリアを行う |

### 3-3. `app/src/main/java/com/example/dashcam/service/DashcamForegroundService.kt`

| 修正 | 内容 |
|---|---|
| 追加要件 | `ensureCameraPrepared()` を新設(カメラ/プレビューのみ準備、録画は開始しない) |
| 追加要件 | `isRunning = true` を `onCreate` から `onStartCommand`(録画開始時)へ移動 |
| 追加要件 | `startRecorder()` を「生成(createRecorder)→初期化→録画開始」に分離。プレビュー用に生成済みなら初期化をスキップ |

### 3-4. `app/src/main/res/layout/activity_main.xml`

| 修正 | 内容 |
|---|---|
| 修正C | `PreviewView` に `app:scaleType="fitCenter"` を明示。既定の `fillCenter` は録画される画角より表示範囲が狭くなり、設置時の画角調整に使えないため |

### 3-5. その他

- `.gitignore` を追加(`build/`, `.gradle/`, `local.properties`, `*.apk`, キーストア等)
- Gradle Wrapper(`gradlew`, `gradlew.bat`, `gradle/wrapper/`)を追加
  (READMEに「同梱していない」とあったため。ローカルは `./gradlew assembleDebug` でビルド可能)
- `README.md` を更新(画面表示の仕様変更と本変更履歴を追記)

---

## 4. 実機での確認ポイント

1. 縦で起動 → 横に向ける → プレビューが正しい向き(横長)になること
2. 横で起動 → 縦に向ける → 同様に正しい向きになること
3. 横向きのまま左右を反転(180度回す)しても、常に上が上であること
   ※この経路はActivityが再生成されないため、DisplayListenerが効いているかの確認になる
4. アプリ起動直後(「開始」を押す前)からプレビューが出ること
5. 「開始」を押す前は「開始」ボタンのみ、「開始」後は「一時停止」「終了」が出ること
6. 「終了」で録画を止めたあともプレビューが表示されたままであること

ログでの確認:

```
adb logcat -s MainActivity DashcamRecorder DashcamService
```

`プレビューの回転を同期: display.rotation=...` と
`PreviewのtargetRotationを更新: ...` が、画面を回すたびに出力されれば正常。

---

## 5. 検証状況

- `gradle assembleDebug` によるビルド: **成功**(debug APK を生成済み)
- 実機/エミュレータでの動作確認: **未実施**(この環境に端末が無いため)
  → 上記「4. 実機での確認ポイント」での確認をお願いします
