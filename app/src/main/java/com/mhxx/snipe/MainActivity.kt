package com.mhxx.snipe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.*
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var switchController: SysBotBaseController? = null
    private var macroEngine: MacroEngine? = null

    private var btHidController: BluetoothHidController? = null
    private var btMacroEngine: BluetoothMacroEngine? = null

    companion object {
        private const val BT_PERMISSION_REQUEST = 1001
        private const val DISCOVERABLE_DURATION = 300  // 秒
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                setGeolocationEnabled(false)
            }
            setBackgroundColor(Color.parseColor("#0b0c10"))
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }

        macroEngine = MacroEngine(this)
        webView.addJavascriptInterface(
            WebAppInterface(this, webView, macroEngine!!),
            "AndroidBridge"
        )

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        initBluetoothHid()
    }

    // ─────────────────────────────────────────────
    //  Bluetooth HID 初期化
    // ─────────────────────────────────────────────

    private fun initBluetoothHid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), BT_PERMISSION_REQUEST)
                return
            }
        }
        startBluetoothHid()
    }

    private fun startBluetoothHid() {
        val listener = object : BluetoothHidController.Listener {
            override fun onConnected() {
                sendToJs("onBtHidStatus", "'connected'", "'✅ Switch と接続完了 (Pro Controller)'")
            }
            override fun onDisconnected() {
                sendToJs("onBtHidStatus", "'disconnected'", "'BT切断'")
            }
            override fun onError(msg: String) {
                val safe = msg.replace("'", "\\'").replace("\n", "\\n")
                sendToJs("onBtHidStatus", "'error'", "'$safe'")
            }
            override fun onStatusUpdate(msg: String) {
                val safe = msg.replace("'", "\\'").replace("\n", "\\n")
                sendToJs("onBtHidStatus", "'status'", "'$safe'")
            }
        }
        btHidController = BluetoothHidController(this, listener)
        btMacroEngine   = BluetoothMacroEngine(btHidController!!)
        btMacroEngine?.statusCallback = { status, msg ->
            val safe = msg.replace("'", "\\'")
            sendToJs("onBtMacroStatus", "'$status'", "'$safe'")
        }
        btHidController?.initialize()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBluetoothHid()
            } else {
                sendToJs("onBtHidStatus", "'error'", "'Bluetoothパーミッションが拒否されました。設定→アプリから許可してください'")
            }
        }
    }

    // ─────────────────────────────────────────────
    //  BT HID 公開 API
    // ─────────────────────────────────────────────

    /**
     * Bluetooth接続を開始する。
     *
     * ① Androidを Discoverable（検索可能）状態にする
     *    → Switch が「コントローラーの登録」画面を開くと Android を発見できる
     *
     * ② ペアリング済みの場合は hidDevice.connect() で再接続を試みる
     */
    fun connectBluetooth(macAddress: String) {
        // まず接続処理を開始
        btHidController?.connect(macAddress)
            ?: run {
                sendToJs("onBtHidStatus", "'error'", "'BT未初期化。アプリを再起動してください'")
                return
            }

        // Android を Discoverable にする（初回ペアリングで必須）
        makeDiscoverable()
    }

    /**
     * Android の Bluetooth を Discoverable にする。
     * これによりSwitchが「コントローラーの登録」でAndroidを発見できる。
     */
    fun makeDiscoverable() {
        try {
            @Suppress("DEPRECATION")
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION)
            }
            startActivity(intent)
            sendToJs("onBtHidStatus", "'status'",
                "'🔍 Discoverable開始。Switch側でコントローラーの登録を開いてください'")
        } catch (e: Exception) {
            sendToJs("onBtHidStatus", "'status'",
                "'Switch側でコントローラーの登録を開いてください'")
        }
    }

    fun disconnectBluetooth()                   { btHidController?.disconnect() }
    fun isBtConnected(): Boolean                = btHidController?.isConnected() ?: false
    fun runBtPreset(name: String, loops: Int)   { btMacroEngine?.executePreset(name, loops) }
    fun runBtJson(json: String, loops: Int)     { btMacroEngine?.executeJson(json, loops) }
    fun stopBtMacro()                           { btMacroEngine?.stop() }
    fun sendBtButton(btn: String, ms: Int)      { btHidController?.pressButton(btn, ms) }
    fun sendBtStick(side: String, x: Int, y: Int, ms: Int) {
        btHidController?.moveStick(side, x, y, ms)
    }

    // ─────────────────────────────────────────────
    //  sys-botbase (TCP) 公開 API
    // ─────────────────────────────────────────────

    fun connectToSwitch(ip: String, port: Int) {
        switchController?.disconnect(); switchController = null
        if (ip.isBlank()) {
            sendToJs("onConnectionStatus", "'error'", "'IPアドレスを入力してください'")
            return
        }
        switchController = SysBotBaseController(ip, port, object : SysBotBaseController.Listener {
            override fun onConnected() {
                macroEngine?.setController(switchController)
                runOnUiThread { sendToJs("onConnectionStatus", "'connected'", "'接続成功 ($ip:$port)'") }
            }
            override fun onDisconnected() {
                macroEngine?.setController(null)
                runOnUiThread { sendToJs("onConnectionStatus", "'disconnected'", "'切断されました'") }
            }
            override fun onError(msg: String) {
                macroEngine?.setController(null)
                runOnUiThread { sendToJs("onConnectionStatus", "'error'", "'$msg'") }
            }
        })
        switchController?.connect()
        sendToJs("onConnectionStatus", "'connecting'", "'接続中... ($ip:$port)'")
    }

    fun disconnectFromSwitch() {
        switchController?.disconnect()
        switchController = null
        macroEngine?.setController(null)
    }

    fun sendButton(button: String, durationMs: Int) {
        switchController?.pressButton(button, durationMs)
    }

    // ─────────────────────────────────────────────
    //  共通
    // ─────────────────────────────────────────────

    fun sendToJs(fn: String, vararg args: String) {
        val argStr = args.joinToString(",")
        webView.post { webView.evaluateJavascript("if(window.$fn) window.$fn($argStr);", null) }
    }

    override fun onDestroy() {
        super.onDestroy()
        switchController?.disconnect()
        macroEngine?.stopAll()
        btHidController?.cleanup()
        btMacroEngine?.stopAll()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
