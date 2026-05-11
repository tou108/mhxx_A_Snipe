package com.mhxx.snipe

import android.Manifest
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

    // BT HID (JoyConDroid統合)
    private var btHidController: BluetoothHidController? = null
    private var btMacroEngine: BluetoothMacroEngine? = null

    companion object {
        private const val BT_PERMISSION_REQUEST = 1001
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

    private fun initBluetoothHid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), BT_PERMISSION_REQUEST)
                return
            }
        }
        startBluetoothHid()
    }

    private fun startBluetoothHid() {
        val listener = object : BluetoothHidController.Listener {
            override fun onConnected() {
                sendToJs("onBtHidStatus", "'connected'", "'BT接続成功 (Pro Controller)'")
            }
            override fun onDisconnected() {
                sendToJs("onBtHidStatus", "'disconnected'", "'BT切断'")
            }
            override fun onError(msg: String) {
                sendToJs("onBtHidStatus", "'error'", "'${msg.replace("'", "")}'")
            }
            override fun onStatusUpdate(msg: String) {
                sendToJs("onBtHidStatus", "'status'", "'${msg.replace("'", "")}'")
            }
        }
        btHidController = BluetoothHidController(this, listener)
        btMacroEngine   = BluetoothMacroEngine(btHidController!!)
        btMacroEngine?.statusCallback = { status, msg ->
            sendToJs("onBtMacroStatus", "'$status'", "'${msg.replace("'", "")}'")
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
                sendToJs("onBtHidStatus", "'error'", "'Bluetoothパーミッション拒否'")
            }
        }
    }

    // ── BT HID公開API ──────────────────────────
    fun connectBluetooth(macAddress: String) {
        btHidController?.connect(macAddress)
            ?: sendToJs("onBtHidStatus", "'error'", "'BT未初期化'")
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

    // ── sys-botbase (TCP) 公開API ──────────────
    fun connectToSwitch(ip: String, port: Int) {
        switchController?.disconnect(); switchController = null
        if (ip.isBlank()) { sendToJs("onConnectionStatus","'error'","'IPを入力してください'"); return }
        switchController = SysBotBaseController(ip, port, object : SysBotBaseController.Listener {
            override fun onConnected() {
                macroEngine?.setController(switchController)
                runOnUiThread { sendToJs("onConnectionStatus","'connected'","'接続成功 ($ip:$port)'") }
            }
            override fun onDisconnected() {
                macroEngine?.setController(null)
                runOnUiThread { sendToJs("onConnectionStatus","'disconnected'","'切断されました'") }
            }
            override fun onError(msg: String) {
                macroEngine?.setController(null)
                runOnUiThread { sendToJs("onConnectionStatus","'error'","'$msg'") }
            }
        })
        switchController?.connect()
        sendToJs("onConnectionStatus","'connecting'","'接続中... ($ip:$port)'")
    }
    fun disconnectFromSwitch() {
        switchController?.disconnect(); switchController = null; macroEngine?.setController(null)
    }
    fun sendButton(button: String, durationMs: Int) { switchController?.pressButton(button, durationMs) }

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
