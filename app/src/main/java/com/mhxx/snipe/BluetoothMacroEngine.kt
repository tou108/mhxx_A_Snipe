package com.mhxx.snipe

import kotlinx.coroutines.*

/**
 * BluetoothMacroEngine
 *
 * BluetoothHidControllerを使ったMHXX自動マクロエンジン。
 * MacroEngineと同じJSON形式でマクロを受け取り、BT HID経由でSwitchに送信します。
 *
 * 自動スナイプモード:
 *   - MHXX マカ錬金スナイプ: ループ実行
 *   - 採掘スナイプ: ループ実行
 *   - カスタムループ: JSON定義のマクロをN回繰り返し
 */
class BluetoothMacroEngine(
    private val btController: BluetoothHidController
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    var statusCallback: ((String, String) -> Unit)? = null

    // ───────────────────────────────
    // プリセット定義 (MHXX特化)
    // ───────────────────────────────
    val PRESETS = mapOf(
        "btMakaAlchemy"  to ::buildBtMakaAlchemy,
        "btMining"       to ::buildBtMining,
        "btSaveLoad"     to ::buildBtSaveLoad,
        "btConfirm3"     to ::buildBtConfirm3,
        "btMenuOpen"     to ::buildBtMenuOpen
    )

    fun executePreset(name: String, loops: Int = 1) {
        val builder = PRESETS[name] ?: run {
            statusCallback?.invoke("error", "プリセット未発見: $name")
            return
        }
        val actions = builder()
        if (loops > 1) {
            runMacro(listOf(BtAction.Repeat(loops, actions)), name)
        } else {
            runMacro(actions, name)
        }
    }

    fun executeJson(jsonStr: String, loops: Int = 1) {
        try {
            val arr = org.json.JSONArray(jsonStr)
            val actions = parseActions(arr)
            val finalActions = if (loops > 1) listOf(BtAction.Repeat(loops, actions)) else actions
            runMacro(finalActions, "カスタムマクロ")
        } catch (e: Exception) {
            statusCallback?.invoke("error", "JSONエラー: ${e.message}")
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        statusCallback?.invoke("stopped", "BTマクロ停止")
    }

    fun stopAll() { scope.cancel() }

    // ───────────────────────────────
    // 実行エンジン
    // ───────────────────────────────
    private fun runMacro(actions: List<BtAction>, name: String) {
        activeJob?.cancel()
        activeJob = scope.launch {
            statusCallback?.invoke("running", "$name 実行中 (BT)...")
            try {
                executeActions(actions)
                statusCallback?.invoke("done", "$name 完了")
            } catch (e: CancellationException) {
                statusCallback?.invoke("stopped", "BTマクロ中止")
            } catch (e: Exception) {
                statusCallback?.invoke("error", "エラー: ${e.message}")
            }
        }
    }

    private suspend fun executeActions(actions: List<BtAction>) {
        for (action in actions) {
            ensureActive()
            when (action) {
                is BtAction.Press -> {
                    btController.pressButton(action.button, action.duration)
                    delay(action.duration.toLong() + 50)
                }
                is BtAction.Multi -> {
                    btController.pressButtons(action.buttons, action.duration)
                    delay(action.duration.toLong() + 50)
                }
                is BtAction.Wait -> delay(action.ms.toLong())
                is BtAction.Stick -> {
                    btController.moveStick(action.side, action.x, action.y, action.duration)
                    delay(action.duration.toLong() + 50)
                }
                is BtAction.Repeat -> {
                    repeat(action.count) {
                        ensureActive()
                        executeActions(action.actions)
                    }
                }
                is BtAction.Comment -> { /* skip */ }
            }
        }
    }

    // ───────────────────────────────
    // JSONパーサー
    // ───────────────────────────────
    private fun parseActions(arr: org.json.JSONArray): List<BtAction> {
        val result = mutableListOf<BtAction>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result += when (obj.getString("type")) {
                "press"   -> BtAction.Press(
                    obj.getString("button"),
                    obj.optInt("duration", 100)
                )
                "multi"   -> {
                    val btns = obj.getJSONArray("buttons")
                    BtAction.Multi(
                        (0 until btns.length()).map { btns.getString(it) },
                        obj.optInt("duration", 100)
                    )
                }
                "wait"    -> BtAction.Wait(obj.getInt("ms"))
                "stick"   -> BtAction.Stick(
                    obj.optString("side", "L"),
                    obj.optInt("x", 0),
                    obj.optInt("y", 0),
                    obj.optInt("duration", 300)
                )
                "repeat"  -> BtAction.Repeat(
                    obj.getInt("count"),
                    parseActions(obj.getJSONArray("actions"))
                )
                else      -> BtAction.Comment(obj.optString("text", ""))
            }
        }
        return result
    }

    // ───────────────────────────────
    // MHXXプリセットビルダー
    // ───────────────────────────────

    private fun buildBtMakaAlchemy(): List<BtAction> = listOf(
        BtAction.Comment("マカ錬金お守りスナイプ (BT HID)"),
        // Continueを押す
        BtAction.Press("A", 150),
        BtAction.Wait(3500),
        // マカ錬金店に移動 (左スティック左)
        BtAction.Stick("L", -32767, 0, 800),
        BtAction.Wait(400),
        // 決定
        BtAction.Press("A", 100),
        BtAction.Wait(1800),
        BtAction.Press("A", 100),
        BtAction.Wait(600),
        BtAction.Press("A", 100),
        BtAction.Wait(600),
        // 錬金結果
        BtAction.Press("A", 100),
        BtAction.Wait(2500),
        // 結果確認
        BtAction.Press("A", 100),
        BtAction.Wait(500)
    )

    private fun buildBtMining(): List<BtAction> = listOf(
        BtAction.Comment("炭鉱採掘スナイプ (BT HID)"),
        BtAction.Press("A", 100),
        BtAction.Wait(2500),
        BtAction.Press("A", 100),
        BtAction.Wait(1000),
        BtAction.Press("A", 100),
        BtAction.Wait(500)
    )

    private fun buildBtSaveLoad(): List<BtAction> = listOf(
        BtAction.Comment("セーブ & ロード (BT HID)"),
        BtAction.Press("X", 100),
        BtAction.Wait(700),
        BtAction.Press("UP", 80),
        BtAction.Wait(200),
        BtAction.Press("A", 100),
        BtAction.Wait(400),
        BtAction.Press("A", 100),
        BtAction.Wait(2500),
        BtAction.Press("HOME", 100),
        BtAction.Wait(600),
        BtAction.Press("HOME", 100),
        BtAction.Wait(1800)
    )

    private fun buildBtConfirm3(): List<BtAction> = listOf(
        BtAction.Repeat(3, listOf(
            BtAction.Press("A", 100),
            BtAction.Wait(500)
        ))
    )

    private fun buildBtMenuOpen(): List<BtAction> = listOf(
        BtAction.Press("X", 100),
        BtAction.Wait(500)
    )
}

// ───────────────────────────────
// BT アクション型
// ───────────────────────────────
sealed class BtAction {
    data class Press(val button: String, val duration: Int) : BtAction()
    data class Multi(val buttons: List<String>, val duration: Int) : BtAction()
    data class Wait(val ms: Int) : BtAction()
    data class Stick(val side: String, val x: Int, val y: Int, val duration: Int) : BtAction()
    data class Repeat(val count: Int, val actions: List<BtAction>) : BtAction()
    data class Comment(val text: String) : BtAction()
}
