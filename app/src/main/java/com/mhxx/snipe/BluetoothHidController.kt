package com.mhxx.snipe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors

/**
 * BluetoothHidController
 *
 * JoyConDroidのBluetoothHIDエンジンを利用して
 * AndroidをSwitchのProController(BT HID)として動作させます。
 *
 * 接続手順:
 *   1. Switchを「コントローラーの持ち方/順番を変える」画面にする
 *   2. SwitchのMACアドレスを入力
 *   3. connect(macAddress) を呼ぶ
 *   4. コールバックで connected を受け取ったらボタン送信可能
 *
 * Nintendo Switch Pro Controller HID Descriptor (JoyConDroidより)
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
        private const val TAG = "BluetoothHidController"

        // Pro Controller HID Descriptor (JoyConDroidより引用)
        private val HID_DESCRIPTOR = hexToBytes(
            "05010905a1010601ff85210921750895308102853009307508953081028531" +
            "093175089669018102853209327508966901810285330933750896690181028533" +
            "0f3f0509190129101500250175019510810205010939150025077504950181420509" +
            "7504950181010501093009310933093416000027ffff000075109504810206" +
            "01ff850109017508953091028510091075089530910285110911750895309102" +
            "851209127508953091028512091375089530910285130914750895309102c0"
        )

        // 実際のJoyConDroidのdescriptor (ControllerType.ktより)
        private val PRO_DESCRIPTOR = hexToBytes(
            "05010905a1010601ff852109217508953081028530093075089530810285310" +
            "9317508966901810285320932750896690181028533093375089669018102853f" +
            "050919012910150025017501951081020501093909310933093416000027ffff00" +
            "007510950481020601ff850109017508953091028510091075089530910285110" +
            "9117508953091028512091275089530910285130914750895309102c0"
        )

        // ボタンビットマップ (Full Report 0x30 用)
        // Byte 2 (右ボタン): Y=0x01, X=0x02, B=0x04, A=0x08, SR=0x10, SL=0x20, R=0x40, ZR=0x80
        // Byte 3 (共通):     MINUS=0x01, PLUS=0x02, RS=0x04, LS=0x08, HOME=0x10, CAPTURE=0x20
        // Byte 4 (左/十字):  DOWN=0x01, UP=0x02, RIGHT=0x04, LEFT=0x08, SL=0x20, SR=0x10, L=0x40, ZL=0x80

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
            // D-padエイリアス
            "DDOWN"   to Pair(4, 0x01),
            "DUP"     to Pair(4, 0x02),
            "DRIGHT"  to Pair(4, 0x04),
            "DLEFT"   to Pair(4, 0x08),
        )

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(" ", "")
            return ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }

        // スティックのニュートラル値 (12bit packed, center=0x800)
        private val STICK_NEUTRAL = byteArrayOf(0x00.toByte(), 0x80.toByte(), 0x00.toByte())
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var switchDevice: BluetoothDevice? = null
    private var appRegistered = false
    @Volatile private var deviceConnected = false
    private var reportCounter: Byte = 0

    // SDP設定 (Nintendo Switch Pro Controller)
    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Pro Controller",
        "Gamepad",
        "Nintendo",
        BluetoothHidDevice.SUBCLASS2_GAMEPAD,
        buildDescriptor()
    )

    // QoS設定
    private val qosSettings = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
        21720, 362, 21720, 16667, 16667
    )

    // HIDデバイスコールバック
    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (registered) {
                listener.onStatusUpdate("HIDアプリ登録完了")
                switchDevice?.let { connectToDevice(it) }
            } else {
                listener.onStatusUpdate("HIDアプリ登録解除")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    deviceConnected = true
                    switchDevice = device
                    mainHandler.post { listener.onConnected() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    deviceConnected = false
                    mainHandler.post { listener.onDisconnected() }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    mainHandler.post { listener.onStatusUpdate("BT接続中...") }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, buildEmptyInputReport())
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Switch→コントローラー方向のデータ (rumble等) は無視
        }
    }

    // BluetoothProfile.ServiceListener
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                if (checkPermission()) {
                    hidDevice?.registerApp(sdpSettings, null, qosSettings, executor, hidCallback)
                } else {
                    mainHandler.post { listener.onError("Bluetoothパーミッションが必要です") }
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            appRegistered = false
        }
    }

    // ───────────────────────────────────────
    // 公開API
    // ───────────────────────────────────────

    fun initialize(): Boolean {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter
        if (bluetoothAdapter == null) {
            listener.onError("このデバイスはBluetoothをサポートしていません")
            return false
        }
        if (!checkPermission()) {
            listener.onError("Android 12以上ではBluetoothConnectパーミッションが必要です")
            return false
        }
        return bluetoothAdapter!!.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    fun connect(macAddress: String) {
        val cleanMac = macAddress.trim().uppercase()
        if (!isValidMac(cleanMac)) {
            listener.onError("無効なMACアドレス: $cleanMac")
            return
        }
        if (!checkPermission()) {
            listener.onError("Bluetoothパーミッションが必要です")
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(cleanMac)
                ?: run { listener.onError("デバイスが見つかりません: $cleanMac"); return }
            switchDevice = device
            listener.onStatusUpdate("接続中... $cleanMac")

            if (appRegistered && hidDevice != null) {
                connectToDevice(device)
            }
            // appRegisteredでない場合はonAppStatusChangedで接続される
        } catch (e: Exception) {
            listener.onError("接続エラー: ${e.message}")
        }
    }

    fun disconnect() {
        if (!checkPermission()) return
        try {
            switchDevice?.let { hidDevice?.disconnect(it) }
        } catch (_: Exception) {}
        deviceConnected = false
        switchDevice = null
    }

    fun cleanup() {
        disconnect()
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {}
        hidDevice = null
    }

    fun isConnected() = deviceConnected

    /**
     * ボタンを押してdurationMs後に離す
     */
    fun pressButton(buttonName: String, durationMs: Int) {
        if (!deviceConnected) return
        executor.submit {
            try {
                val report = buildInputReport(setOf(buttonName.uppercase()))
                sendReport(report)
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendReport(buildInputReport(emptySet())) // release
                Thread.sleep(30)
            } catch (_: Exception) {}
        }
    }

    /**
     * 複数ボタン同時押し
     */
    fun pressButtons(buttons: List<String>, durationMs: Int) {
        if (!deviceConnected) return
        executor.submit {
            try {
                val report = buildInputReport(buttons.map { it.uppercase() }.toSet())
                sendReport(report)
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendReport(buildInputReport(emptySet()))
                Thread.sleep(30)
            } catch (_: Exception) {}
        }
    }

    /**
     * スティック移動 (x, y: -32767 ~ 32767)
     */
    fun moveStick(side: String, x: Int, y: Int, durationMs: Int) {
        if (!deviceConnected) return
        executor.submit {
            try {
                val report = buildInputReportWithStick(emptySet(), side, x, y)
                sendReport(report)
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendReport(buildInputReport(emptySet())) // neutral
                Thread.sleep(30)
            } catch (_: Exception) {}
        }
    }

    // ───────────────────────────────────────
    // レポートビルダー
    // ───────────────────────────────────────

    private fun buildInputReport(pressedButtons: Set<String>): ByteArray {
        return buildInputReportWithStick(pressedButtons, null, 0, 0)
    }

    private fun buildInputReportWithStick(
        pressedButtons: Set<String>,
        stickSide: String?,
        stickX: Int,
        stickY: Int
    ): ByteArray {
        // Full Input Report (0x30) 48バイト
        val data = ByteArray(48)
        data[0] = ++reportCounter               // タイマー
        data[1] = 0x8E.toByte()                 // バッテリー + 接続情報

        // ボタンビット設定
        for (btn in pressedButtons) {
            val bits = BUTTON_BITS[btn] ?: continue
            data[bits.first] = (data[bits.first].toInt() or bits.second).toByte()
        }

        // 左スティック (ニュートラル = 0x800)
        val lx = if (stickSide?.uppercase() == "L") packStickAxis(stickX) else 0x800
        val ly = if (stickSide?.uppercase() == "L") packStickAxis(stickY) else 0x800
        data[5] = (lx and 0xFF).toByte()
        data[6] = (((lx shr 8) and 0xF) or ((ly and 0xF) shl 4)).toByte()
        data[7] = ((ly shr 4) and 0xFF).toByte()

        // 右スティック (ニュートラル = 0x800)
        val rx = if (stickSide?.uppercase() == "R") packStickAxis(stickX) else 0x800
        val ry = if (stickSide?.uppercase() == "R") packStickAxis(stickY) else 0x800
        data[8] = (rx and 0xFF).toByte()
        data[9] = (((rx shr 8) and 0xF) or ((ry and 0xF) shl 4)).toByte()
        data[10] = ((ry shr 4) and 0xFF).toByte()

        data[11] = 0x00 // バイブレーター入力カウンター

        return data
    }

    private fun buildEmptyInputReport(): ByteArray {
        return buildInputReport(emptySet())
    }

    // -32767..32767 → 0..4095 (12bit)
    private fun packStickAxis(v: Int): Int {
        val clamped = v.coerceIn(-32767, 32767)
        return ((clamped + 32767) * 4095 / 65534)
    }

    private fun sendReport(data: ByteArray) {
        if (!deviceConnected) return
        if (!checkPermission()) return
        try {
            val dev = switchDevice ?: return
            hidDevice?.sendReport(dev, 0x30, data)
        } catch (_: Exception) {}
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!checkPermission()) return
        try {
            hidDevice?.connect(device)
            listener.onStatusUpdate("接続試行中: ${device.address}")
        } catch (e: Exception) {
            listener.onError("接続失敗: ${e.message}")
        }
    }

    // Pro Controller用HID Descriptorバイト列
    private fun buildDescriptor(): ByteArray {
        // JoyConDroid ControllerType.java の DESCRIPTOR 文字列をバイト変換
        val hex = "05010905a1010601ff852109217508953081028530093075089530810285310" +
                  "93175089669018102853209327508966901810285330933750896690181028533" +
                  "053f0509190129101500250175019510810205010939150025077504" +
                  "950181420509750495018101050109300931093309341600002" +
                  "7ffff0000751095048102 0601ff850109017508953091028510091075089530910285" +
                  "1109117508953091028512091275089530910285130914750895309102c0"
        return hexToBytes(hex.replace(" ", ""))
    }

    private fun isValidMac(mac: String): Boolean =
        mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
