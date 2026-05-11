package com.mhxx.snipe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors

/**
 * BluetoothHidController
 *
 * AndroidをSwitchのPro ControllerとしてBluetooth HID接続する。
 *
 * ─────────────────────────────────────
 *  接続の仕組み（重要）
 * ─────────────────────────────────────
 * Bluetooth HID では Switch が「ホスト」、Android が「デバイス」です。
 *
 * ① 初回ペアリング
 *    Android側: HIDアプリ登録 → Discoverable状態にする
 *    Switch側 : コントローラーの持ち方/順番変更 → コントローラーの登録
 *    → Switch が Android を発見 → ペアリング → onConnectionStateChanged(CONNECTED)
 *
 * ② 再接続（ペアリング済み）
 *    Android側: HIDアプリ登録後に hidDevice.connect(bondedDevice) を呼ぶ
 *    → Switch が応答 → onConnectionStateChanged(CONNECTED)
 *
 * connect(mac) を呼んだ後、makeDiscoverable() を必ず呼ぶこと。
 */
class BluetoothHidController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(msg: String)
        fun onStatusUpdate(msg: String)
    }

    companion object {
        val BUTTON_BITS = mapOf(
            "Y"       to Pair(2, 0x01),
            "X"       to Pair(2, 0x02),
            "B"       to Pair(2, 0x04),
            "A"       to Pair(2, 0x08),
            "R"       to Pair(2, 0x40),
            "ZR"      to Pair(2, 0x80.toByte().toInt() and 0xFF),
            "MINUS"   to Pair(3, 0x01),
            "PLUS"    to Pair(3, 0x02),
            "RS"      to Pair(3, 0x04),
            "LS"      to Pair(3, 0x08),
            "HOME"    to Pair(3, 0x10),
            "CAPTURE" to Pair(3, 0x20),
            "DOWN"    to Pair(4, 0x01),
            "UP"      to Pair(4, 0x02),
            "RIGHT"   to Pair(4, 0x04),
            "LEFT"    to Pair(4, 0x08),
            "L"       to Pair(4, 0x40),
            "ZL"      to Pair(4, 0x80.toByte().toInt() and 0xFF),
            "DDOWN"   to Pair(4, 0x01),
            "DUP"     to Pair(4, 0x02),
            "DRIGHT"  to Pair(4, 0x04),
            "DLEFT"   to Pair(4, 0x08),
        )

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(" ", "").replace("\n", "")
            return ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var switchDevice: BluetoothDevice? = null   // ペアリング先（MACから生成 or 接続済みデバイス）
    private var targetMac: String = ""                  // ユーザーが入力したMAC
    private var appRegistered = false
    @Volatile private var deviceConnected = false
    private var reportCounter: Byte = 0

    // ── SDP設定 ──────────────────────────────
    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Pro Controller",
        "Gamepad",
        "Nintendo",
        BluetoothHidDevice.SUBCLASS1_GAMEPAD,
        buildDescriptor()
    )
    private val qosOut = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
        800, 9, 800, 11250, 11250
    )

    // ── Bluetooth 接続状態監視レシーバー ──────────────
    // Switch が Discoverable な Android を発見してペアリングしてくる際に
    // ACTION_ACL_CONNECTED でキャッチする
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!checkPermission()) return
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            when (intent.action) {
                // ペアリング要求 → 自動承認（HIDプロファイルで処理される）
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    if (state == BluetoothDevice.BOND_BONDED) {
                        mainHandler.post { listener.onStatusUpdate("ペアリング完了。接続中...") }
                        // ペアリング完了 → switchDevice を更新して接続
                        switchDevice = dev
                        dev?.let { connectToDevice(it) }
                    }
                }
                // ACL接続（ペアリング済みデバイスからの接続）
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    // onConnectionStateChanged で処理するので基本不要だが念のため
                }
            }
        }
    }

    // ── HIDコールバック ──────────────────────────
    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (registered) {
                mainHandler.post { listener.onStatusUpdate("HID登録完了。Switchのコントローラー登録画面を開いてください") }
                // ペアリング済みのデバイスがあれば再接続を試みる
                val mac = targetMac
                if (mac.isNotEmpty() && hidDevice != null) {
                    tryReconnectBonded(mac)
                }
            } else {
                mainHandler.post { listener.onStatusUpdate("HID登録解除") }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    deviceConnected = true
                    switchDevice = device
                    mainHandler.post {
                        listener.onStatusUpdate("Switch と接続完了！")
                        listener.onConnected()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    deviceConnected = false
                    mainHandler.post { listener.onDisconnected() }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    mainHandler.post { listener.onStatusUpdate("Switch に接続中...") }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, buildEmptyInputReport())
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) { }
    }

    // ── Profileサービスリスナー ──────────────────
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                if (checkPermission()) {
                    hidDevice?.registerApp(sdpSettings, null, qosOut, executor, hidCallback)
                } else {
                    mainHandler.post { listener.onError("BLUETOOTH_CONNECT パーミッションが必要です") }
                }
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            appRegistered = false
        }
    }

    // ─────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────

    /**
     * Bluetoothプロファイルプロキシを初期化。アプリ起動時に一度だけ呼ぶ。
     */
    fun initialize(): Boolean {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter
        if (bluetoothAdapter == null) {
            listener.onError("このデバイスはBluetoothをサポートしていません")
            return false
        }
        if (!checkPermission()) {
            listener.onError("Bluetoothパーミッションが必要です（設定から許可してください）")
            return false
        }
        // ペアリング状態変化を監視
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        context.registerReceiver(bondReceiver, filter)

        return bluetoothAdapter!!.getProfileProxy(
            context, profileListener, BluetoothProfile.HID_DEVICE
        )
    }

    /**
     * 接続を開始する。
     * - 初回ペアリング時: MACはオプション。Discoverableにした後Switchからの接続を待つ。
     * - 再接続時: MACを指定するとペアリング済みデバイスへ直接接続を試みる。
     */
    fun connect(macAddress: String) {
        if (!checkPermission()) {
            listener.onError("Bluetoothパーミッションが必要です")
            return
        }
        targetMac = macAddress.trim().uppercase()

        if (targetMac.isNotEmpty() && !isValidMac(targetMac)) {
            listener.onError("MACアドレス形式エラー: $targetMac\n例: AA:BB:CC:DD:EE:FF")
            return
        }

        if (appRegistered && hidDevice != null) {
            // HID登録済み → 即座に再接続試行 or 待機状態に入る
            if (targetMac.isNotEmpty()) {
                tryReconnectBonded(targetMac)
            } else {
                listener.onStatusUpdate("Switchのコントローラー登録画面を開いてください")
            }
        } else {
            // まだ登録前 → onAppStatusChanged で処理される
            listener.onStatusUpdate("HID登録中... しばらくお待ちください")
        }
    }

    /**
     * 切断
     */
    fun disconnect() {
        if (!checkPermission()) return
        try { switchDevice?.let { hidDevice?.disconnect(it) } } catch (_: Exception) {}
        deviceConnected = false
        switchDevice = null
    }

    /**
     * クリーンアップ（Activity.onDestroy で呼ぶ）
     */
    fun cleanup() {
        disconnect()
        try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {}
        hidDevice = null
    }

    fun isConnected() = deviceConnected

    // ── ボタン / スティック操作 ──────────────────

    fun pressButton(buttonName: String, durationMs: Int) {
        if (!deviceConnected) return
        executor.submit {
            try {
                sendReport(buildInputReport(setOf(buttonName.uppercase())))
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendReport(buildInputReport(emptySet()))
                Thread.sleep(30)
            } catch (_: Exception) {}
        }
    }

    fun pressButtons(buttons: List<String>, durationMs: Int) {
        if (!deviceConnected) return
        executor.submit {
            try {
                sendReport(buildInputReport(buttons.map { it.uppercase() }.toSet()))
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendReport(buildInputReport(emptySet()))
                Thread.sleep(30)
            } catch (_: Exception) {}
        }
    }

    fun moveStick(side: String, x: Int, y: Int, durationMs: Int) {
        if (!deviceConnected) return
        executor.submit {
            try {
                sendReport(buildInputReportWithStick(emptySet(), side, x, y))
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendReport(buildInputReport(emptySet()))
                Thread.sleep(30)
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────
    //  内部処理
    // ─────────────────────────────────────────────

    /**
     * ペアリング済みデバイスへの再接続を試みる。
     * ペアリングされていない場合は何もしない（Switch側からの接続を待つ）。
     */
    private fun tryReconnectBonded(mac: String) {
        if (!checkPermission()) return
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            val bonded = bondedDevices.firstOrNull {
                it.address.uppercase() == mac.uppercase()
            }
            if (bonded != null) {
                // ペアリング済み → 直接接続
                switchDevice = bonded
                mainHandler.post { listener.onStatusUpdate("ペアリング済みデバイスへ再接続中...") }
                connectToDevice(bonded)
            } else {
                // 未ペアリング → Switchからの接続を待つ（makeDiscoverable は MainActivity で呼ぶ）
                mainHandler.post {
                    listener.onStatusUpdate(
                        "未ペアリング。\n" +
                        "Switch: コントローラーの登録 を開いてください"
                    )
                }
            }
        } catch (e: Exception) {
            mainHandler.post { listener.onStatusUpdate("接続準備中...") }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!checkPermission()) return
        try {
            val result = hidDevice?.connect(device)
            if (result != true) {
                mainHandler.post { listener.onStatusUpdate("接続要求送信完了。Switch側での操作をお待ちください") }
            }
        } catch (e: Exception) {
            mainHandler.post { listener.onError("接続試行エラー: ${e.message}") }
        }
    }

    // ── レポートビルダー ────────────────────────

    private fun buildInputReport(pressedButtons: Set<String>) =
        buildInputReportWithStick(pressedButtons, null, 0, 0)

    private fun buildInputReportWithStick(
        pressedButtons: Set<String>,
        stickSide: String?,
        stickX: Int,
        stickY: Int
    ): ByteArray {
        val data = ByteArray(48)
        data[0] = ++reportCounter
        data[1] = 0x8E.toByte()
        for (btn in pressedButtons) {
            val bits = BUTTON_BITS[btn] ?: continue
            data[bits.first] = (data[bits.first].toInt() or bits.second).toByte()
        }
        val lx = if (stickSide?.uppercase() == "L") packAxis(stickX) else 0x800
        val ly = if (stickSide?.uppercase() == "L") packAxis(stickY) else 0x800
        data[5] = (lx and 0xFF).toByte()
        data[6] = (((lx shr 8) and 0xF) or ((ly and 0xF) shl 4)).toByte()
        data[7] = ((ly shr 4) and 0xFF).toByte()
        val rx = if (stickSide?.uppercase() == "R") packAxis(stickX) else 0x800
        val ry = if (stickSide?.uppercase() == "R") packAxis(stickY) else 0x800
        data[8] = (rx and 0xFF).toByte()
        data[9] = (((rx shr 8) and 0xF) or ((ry and 0xF) shl 4)).toByte()
        data[10] = ((ry shr 4) and 0xFF).toByte()
        return data
    }

    private fun buildEmptyInputReport() = buildInputReport(emptySet())

    // -32767..32767 → 0..4095
    private fun packAxis(v: Int) = ((v.coerceIn(-32767, 32767) + 32767) * 4095 / 65534)

    private fun sendReport(data: ByteArray) {
        if (!deviceConnected || !checkPermission()) return
        try { hidDevice?.sendReport(switchDevice ?: return, 0x30, data) } catch (_: Exception) {}
    }

    // Pro Controller HID Descriptor（JoyConDroidより）
    private fun buildDescriptor(): ByteArray = hexToBytes(
        "05010905a1010601ff85210921750895308102" +
        "853009307508953081028531093175089669018102" +
        "853209327508966901810285330933750896690181028533" +
        "3f050919012910150025017501951081020501" +
        "09390f150025077504950181420509750495018101" +
        "050109300931093309341600002" +
        "7ffff000075109504810206" +
        "01ff850109017508953091028510091075089530910285" +
        "110911750895309102851209127508953091028513" +
        "0914750895309102c0"
    )

    private fun isValidMac(mac: String) =
        mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))

    private fun checkPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
}
