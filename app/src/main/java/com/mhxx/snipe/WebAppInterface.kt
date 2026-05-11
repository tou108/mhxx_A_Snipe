package com.mhxx.snipe

import android.content.Context
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript ↔ Android ブリッジ (統合版)
 *
 * ── sys-botbase (WiFi TCP) ──
 *   AndroidBridge.connectSwitch(ip, port)
 *   AndroidBridge.disconnectSwitch()
 *   AndroidBridge.pressButton(btn, ms)
 *   AndroidBridge.runMacro(json)
 *   AndroidBridge.runPreset(name)
 *   AndroidBridge.stopMacro()
 *   AndroidBridge.moveStick(side, x, y)
 *
 * ── BT HID (Bluetooth Direct) ──
 *   AndroidBridge.connectBluetooth(macAddress)
 *   AndroidBridge.disconnectBluetooth()
 *   AndroidBridge.btPressButton(btn, ms)
 *   AndroidBridge.btPressButtons(json, ms)
 *   AndroidBridge.btMoveStick(side, x, y, ms)
 *   AndroidBridge.runBtPreset(name, loops)
 *   AndroidBridge.runBtMacro(json, loops)
 *   AndroidBridge.stopBtMacro()
 *   AndroidBridge.isBtConnected()
 */
class WebAppInterface(
    private val activity: MainActivity,
    private val webView: WebView,
    private val macroEngine: MacroEngine
) {

    // ─────────────────────────────────────────────
    // sys-botbase (WiFi TCP)
    // ─────────────────────────────────────────────
    @JavascriptInterface fun connectSwitch(ip: String, port: Int) {
        activity.runOnUiThread { activity.connectToSwitch(ip, port) }
    }

    @JavascriptInterface fun disconnectSwitch() {
        activity.runOnUiThread { activity.disconnectFromSwitch() }
    }

    @JavascriptInterface fun pressButton(button: String, durationMs: Int) {
        activity.sendButton(button, durationMs)
    }

    @JavascriptInterface fun pressButtons(buttonsJson: String, durationMs: Int) {
        val arr = JSONArray(buttonsJson)
        val list = (0 until arr.length()).map { arr.getString(it) }
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("type","multi"); put("buttons", JSONArray(list)); put("duration", durationMs)
            })
        }
        macroEngine.executeJson(json.toString())
    }

    @JavascriptInterface fun runMacro(jsonStr: String) {
        macroEngine.setStatusCallback { s, m -> activity.sendToJs("onMacroStatus","'$s'","'$m'") }
        macroEngine.executeJson(jsonStr)
    }

    @JavascriptInterface fun runPreset(presetName: String) {
        macroEngine.setStatusCallback { s, m -> activity.sendToJs("onMacroStatus","'$s'","'$m'") }
        macroEngine.executePreset(presetName)
    }

    @JavascriptInterface fun stopMacro() { macroEngine.stop() }

    @JavascriptInterface fun moveStick(side: String, x: Int, y: Int) {
        // sys-botbase経由スティック (SysBotBaseControllerに委譲)
        val macro = JSONArray().apply {
            put(JSONObject().apply {
                put("type","stick"); put("side",side); put("x",x); put("y",y); put("duration",100)
            })
        }
        macroEngine.executeJson(macro.toString())
    }

    // ─────────────────────────────────────────────
    // BT HID (Bluetooth Direct / JoyConDroid統合)
    // ─────────────────────────────────────────────
    @JavascriptInterface fun connectBluetooth(macAddress: String) {
        activity.runOnUiThread { activity.connectBluetooth(macAddress) }
    }

    @JavascriptInterface fun disconnectBluetooth() {
        activity.runOnUiThread { activity.disconnectBluetooth() }
    }

    @JavascriptInterface fun btPressButton(button: String, durationMs: Int) {
        activity.sendBtButton(button, durationMs)
    }

    @JavascriptInterface fun btPressButtons(buttonsJson: String, durationMs: Int) {
        val arr = JSONArray(buttonsJson)
        val list = (0 until arr.length()).map { arr.getString(it) }
        // BluetoothHidController.pressButtonsに委譲
        list.forEachIndexed { i, _ ->
            if (i == 0) activity.sendBtButton(list.joinToString("+"), durationMs)
        }
    }

    @JavascriptInterface fun btMoveStick(side: String, x: Int, y: Int, durationMs: Int) {
        activity.sendBtStick(side, x, y, durationMs)
    }

    @JavascriptInterface fun runBtPreset(name: String, loops: Int) {
        activity.runBtPreset(name, loops.coerceAtLeast(1))
    }

    @JavascriptInterface fun runBtMacro(jsonStr: String, loops: Int) {
        activity.runBtJson(jsonStr, loops.coerceAtLeast(1))
    }

    @JavascriptInterface fun stopBtMacro() { activity.stopBtMacro() }

    @JavascriptInterface fun isBtConnected(): Boolean = activity.isBtConnected()

    // ─────────────────────────────────────────────
    // ユーティリティ
    // ─────────────────────────────────────────────
    @JavascriptInterface fun vibrate(ms: Int) {
        @Suppress("DEPRECATION")
        (activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(ms.toLong().coerceIn(10, 500))
    }

    @JavascriptInterface fun getPresetList(): String = macroEngine.PRESETS.keys.joinToString(",")
    @JavascriptInterface fun isAndroid(): Boolean    = true
    @JavascriptInterface fun getAppVersion(): String = "3.0.0-MHXX-Integrated"
}
