# MHXXSnipeApp Integrated v3.0

MHXXお守りスナイプツール + JoyConDroid Bluetooth HID コントローラー統合版

## 統合内容

### 既存機能 (そのまま維持)
- **護石サーチタブ** — お守りスナイプ検索・自動スナイプループ
- **Switch接続タブ** — WiFi/sys-botbase TCP接続コントローラー
- **マクロタブ** — マクロ録画・プリセット・カスタムJSON実行

### 新機能 🆕
- **BT直接タブ** — JoyConDroidエンジンによるBluetooth HID直接接続
  - SwitchのMACアドレスを入力して接続
  - AndroidがSwitchの **Pro Controller** として認識される
  - sys-botbase/Homebrewが不要
  - 完全自動マクロ (マカ錬金スナイプ / 採掘スナイプ ループ実行)
  - カスタムJSONマクロ (BT HID経由)

## 新規追加ファイル

| ファイル | 説明 |
|----------|------|
| `BluetoothHidController.kt` | JoyConDroidのBT HIDエンジン移植。Pro Controller HID登録・接続・ボタンレポート送信 |
| `BluetoothMacroEngine.kt` | BT HID用マクロエンジン。MHXX特化プリセット付き |
| `MainActivity.kt` (更新) | BT HID初期化・パーミッション要求追加 |
| `WebAppInterface.kt` (更新) | BT HID JS Bridgeメソッド追加 |
| `AndroidManifest.xml` (更新) | BLUETOOTH_CONNECT/ADVERTISE/SCAN パーミッション追加 |
| `index.html` (更新) | 「BT直接」タブ追加 (MAC入力・コントローラーUI・自動マクロUI) |

## BT接続手順

1. Switch本体の設定 → コントローラーと通信機器 → 「コントローラーとの通信を切る」または「持ち方/順番を変える」でペアリングモードにする
2. Switchの設定 → 本体 → Bluetooth本体情報でMACアドレスを確認
3. アプリの「BT直接」タブを開き、MACアドレスを入力
4. 「接続」ボタンを押す
5. 接続後、マクロを実行

## 要件

- Android 9.0+ (API 28+) — BluetoothHidDevice APIのため
- Android 12以上の場合はBLUETOOTH_CONNECT / BLUETOOTH_ADVERTISEパーミッションが必要
