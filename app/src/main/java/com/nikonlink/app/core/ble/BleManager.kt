package com.nikonlink.app.core.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.core.common.CameraDevice
import com.nikonlink.app.core.common.CameraModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nikon BLE profile.
 *
 * UUID 来自 Nikon 公开 BLE 服务定义（真机实测确认）：
 * - 服务：0000DE00/0000DE01-3DD4-4255-8D62-6DC7B9BD5561
 * - 2000 认证（4 阶段配对）
 * - 2004 连接配置（SSID/密码）
 * - 2005 连接建立（WiFi 握手完成）
 * - 2008 LSS 控制点（配对最终 OK/文件事件）
 */
object NikonBleProfile {
    private const val BASE = "-3dd4-4255-8d62-6dc7b9bd5561"

    val SERVICE_DE00 = UUID.fromString("0000de00$BASE")
    val SERVICE_DE01 = UUID.fromString("0000de01$BASE")
    val LSS_SERVICE = UUID.fromString("6155bdb9-c76d-628d-5542-d43d01de0000")

    val AUTHENTICATION = UUID.fromString("00002000$BASE")
    val POWER_CONTROL = UUID.fromString("00002001$BASE")
    val CLIENT_DEVICE_NAME = UUID.fromString("00002002$BASE")
    val SERVER_DEVICE_NAME = UUID.fromString("00002003$BASE")
    val CONNECTION_CONFIGURATION = UUID.fromString("00002004$BASE")
    val CONNECTION_ESTABLISHMENT = UUID.fromString("00002005$BASE")
    val CURRENT_TIME = UUID.fromString("00002006$BASE")
    val LOCATION_INFORMATION = UUID.fromString("00002007$BASE")
    val LSS_CONTROL_POINT = UUID.fromString("00002008$BASE")
    val LSS_FEATURE = UUID.fromString("00002009$BASE")
    val CABLE_ATTACHMENT = UUID.fromString("0000200a$BASE")
    val LSS_SERIAL_NUMBER = UUID.fromString("0000200b$BASE")
    val BATTERY_LEVEL = UUID.fromString("00002a19$BASE")
    val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val REMOTE_PAIR = UUID.fromString("00002087$BASE")
    val REMOTE_SHUTTER = UUID.fromString("00002083$BASE")

    val KNOWN_SERVICE_UUIDS: List<ParcelUuid> = listOf(
        ParcelUuid.fromString("0000de00$BASE"),
        ParcelUuid.fromString("0000de01$BASE"),
        ParcelUuid.fromString("6155bdb9-c76d-628d-5542-d43d01de0000")
    )
}

/**
 * BLE 管理器 - Nikon 原生 GATT 适配层。
 *
 * 连接流程不再使用推断 UUID，而是执行 Nikon BLE 标准 4 阶段配对：
 * 1. 写入 0x01 阶段消息到 2000
 * 2. 收到 0x00 回执后写入 0x03 阶段消息
 * 3. 收到 0x02 阶段消息（盐值校验）后写入 0x03 阶段消息
 * 4. 收到 0x04 阶段消息后等待 2008 最终 OK（或超时）再写入 32 字节客户端 ID
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_DURATION_MS = 15000L
        private const val GATT_TIMEOUT_MS = 12000L
        private const val PAIRING_STAGE_TIMEOUT_MS = 60000L
        private const val RSSI_HEARTBEAT_MS = 5000L
        private const val RSSI_FAIL_LIMIT = 3
        private const val CLIENT_NAME_PREFIX = "NikonLink"
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val secureRandom = SecureRandom()

    private var gatt: BluetoothGatt? = null
    private var heartbeatJob: Job? = null
    private var scanJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var pairingTimeoutJob: Job? = null
    private var serviceDiscoveryTimeoutJob: Job? = null
    private var finalizationJob: Job? = null
    private var fallbackScope: CoroutineScope? = null
    private var scope: CoroutineScope? = null

    private var pairingSession: NikonPairingSession? = null
    private var pairingFinalized = false
    private var mtuNegotiated = false
    private var pairedCharacteristic: BluetoothGattCharacteristic? = null
    private var currentGattService: BluetoothGattService? = null
    private val pendingNotifications = ArrayDeque<Pair<UUID, Boolean>>()
    private var missedRssiReads = 0
    private var connectedDeviceAddress: String? = null

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableSharedFlow<CameraDevice>(extraBufferCapacity = 8)
    val discoveredDevices: SharedFlow<CameraDevice> = _discoveredDevices.asSharedFlow()

    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    private val _fileNotification = MutableSharedFlow<FileNotification>(extraBufferCapacity = 16)
    val fileNotification: SharedFlow<FileNotification> = _fileNotification.asSharedFlow()

    private val _wifiCredential = MutableSharedFlow<WifiCredential>(replay = 1, extraBufferCapacity = 1)
    val wifiCredential: SharedFlow<WifiCredential> = _wifiCredential.asSharedFlow()

    private val _connectFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connectFailed: SharedFlow<Unit> = _connectFailed.asSharedFlow()

    private val _pairingMessage = MutableStateFlow<String?>(null)
    val pairingMessage: StateFlow<String?> = _pairingMessage.asStateFlow()

    private val _lastHeartbeatAt = MutableStateFlow(0L)
    val lastHeartbeatAt: StateFlow<Long> = _lastHeartbeatAt.asStateFlow()

    fun start(scope: CoroutineScope) {
        this.scope = scope
        fallbackScope?.cancel()
        fallbackScope = null
        Timber.tag(TAG).i("BleManager started")
    }

    fun stop() {
        stopHeartbeat()
        stopScan()
        disconnect()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        fallbackScope?.cancel()
        fallbackScope = null
        scope = null
        Timber.tag(TAG).i("BleManager stopped")
    }

    private fun activeScope(): CoroutineScope {
        return scope ?: fallbackScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
            fallbackScope = it
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Timber.tag(TAG).e("BLE scanner not available")
            return
        }

        stopScan()
        _connectionState.value = BleConnectionState.SCANNING

        // 优先按尼康服务 UUID 过滤，减少非相机设备干扰。
        val scanFilters = NikonBleProfile.KNOWN_SERVICE_UUIDS.map {
            ScanFilter.Builder().setServiceUuid(it).build()
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: return
                val cameraDevice = CameraDevice(
                    address = device.address,
                    name = name,
                    rssi = result.rssi,
                    model = parseCameraModel(name)
                )
                Timber.tag(TAG).d("Discovered: ${cameraDevice.name} [${cameraDevice.address}] RSSI=${result.rssi}")
                _discoveredDevices.tryEmit(cameraDevice)
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.tag(TAG).e("Scan failed with error: $errorCode")
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }

        scanner.startScan(scanFilters, scanSettings, scanCallback)
        this.scanCallback = scanCallback

        scanJob = activeScope().launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
        Timber.tag(TAG).i("BLE scan started")
    }

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanCallback?.let { callback ->
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        }
        scanCallback = null
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "Invalid BLE address: $address")
            null
        } ?: run {
            Timber.tag(TAG).e("Device not found: $address")
            _connectFailed.tryEmit(Unit)
            return
        }

        stopScan()
        disconnectInternal()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null
        finalizationJob?.cancel()
        finalizationJob = null
        pairingSession = null
        pairingFinalized = false
        mtuNegotiated = false
        pairedCharacteristic = null
        currentGattService = null
        pendingNotifications.clear()
        _pairingMessage.value = null
        missedRssiReads = 0
        _connectionState.value = BleConnectionState.CONNECTING
        connectedDeviceAddress = address

        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )

        connectTimeoutJob = activeScope().launch {
            delay(GATT_TIMEOUT_MS)
            if (_connectionState.value == BleConnectionState.CONNECTING) {
                Timber.tag(TAG).w("GATT connect timed out after ${GATT_TIMEOUT_MS}ms")
                failConnect()
            }
        }
        Timber.tag(TAG).i("Connecting to $address")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        disconnectInternal()
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal() {
        stopHeartbeat()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null
        finalizationJob?.cancel()
        finalizationJob = null
        pairingSession = null
        pairingFinalized = false
        mtuNegotiated = false
        pairedCharacteristic = null
        currentGattService = null
        pendingNotifications.clear()
        _pairingMessage.value = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connectedDeviceAddress = null
        missedRssiReads = 0
    }

    @SuppressLint("MissingPermission")
    private fun failConnect() {
        _connectFailed.tryEmit(Unit)
        disconnectInternal()
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.tag(TAG).i("GATT connected, negotiating MTU 517...")
                    mtuNegotiated = false
                    val mtuRequested = runCatching { gatt.requestMtu(517) }.getOrDefault(false)
                    if (mtuRequested) {
                        serviceDiscoveryTimeoutJob?.cancel()
                        serviceDiscoveryTimeoutJob = activeScope().launch {
                            delay(3000)
                            if (!mtuNegotiated && this@BleManager.gatt === gatt) {
                                Timber.tag(TAG).w("MTU negotiation timed out, discovering services anyway")
                                if (!gatt.discoverServices()) failConnect()
                            }
                        }
                    } else {
                        Timber.tag(TAG).w("MTU request rejected, discovering services directly")
                        if (!gatt.discoverServices()) failConnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.tag(TAG).w("GATT disconnected (status=$status)")
                    if (status != BluetoothGatt.GATT_SUCCESS &&
                        _connectionState.value == BleConnectionState.CONNECTING
                    ) {
                        _connectFailed.tryEmit(Unit)
                    }
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    stopHeartbeat()
                    serviceDiscoveryTimeoutJob?.cancel()
                    serviceDiscoveryTimeoutJob = null
                    finalizationJob?.cancel()
                    finalizationJob = null
                    pairingTimeoutJob?.cancel()
                    pairingTimeoutJob = null
                    pairingSession = null
                    pairingFinalized = false
                    mtuNegotiated = false
                    pairedCharacteristic = null
                    currentGattService = null
                    pendingNotifications.clear()
                    gatt.close()
                    if (this@BleManager.gatt === gatt) {
                        this@BleManager.gatt = null
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuNegotiated = true
            serviceDiscoveryTimeoutJob?.cancel()
            serviceDiscoveryTimeoutJob = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).w("MTU negotiation failed (status=$status), using default MTU")
            } else {
                Timber.tag(TAG).i("MTU negotiated: $mtu")
            }
            if (!gatt.discoverServices()) {
                Timber.tag(TAG).e("discoverServices failed")
                failConnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).e("Service discovery failed: $status")
                failConnect()
                return
            }

            val service = gatt.getService(NikonBleProfile.SERVICE_DE01)
                ?: gatt.getService(NikonBleProfile.SERVICE_DE00)
                ?: gatt.getService(NikonBleProfile.LSS_SERVICE)
            if (service == null) {
                Timber.tag(TAG).e("Nikon service not found, BLE cannot be marked connected")
                failConnect()
                return
            }

            val authChar = service.getCharacteristic(NikonBleProfile.AUTHENTICATION)
                ?: service.getCharacteristic(NikonBleProfile.REMOTE_PAIR)
            if (authChar == null) {
                Timber.tag(TAG).e("Nikon authentication characteristic not found")
                failConnect()
                return
            }

            pairedCharacteristic = authChar
            setupNotifications(gatt, service, authChar.uuid)

            activeScope().launch {
                delay(400)
                if (this@BleManager.gatt !== gatt) return@launch
                startPairing(gatt, service, authChar)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                NikonBleProfile.AUTHENTICATION -> {
                    handlePairingStage(value)
                }
                NikonBleProfile.LSS_CONTROL_POINT -> {
                    if (isFinalOk(value)) {
                        Timber.tag(TAG).i("Nikon pairing final OK received")
                        finalizePairing(gatt)
                    } else {
                        Timber.tag(TAG).d("LSS control point data: ${value.toHex()}")
                    }
                }
                NikonBleProfile.BATTERY_LEVEL -> {
                    Timber.tag(TAG).d("Battery level: ${value.firstOrNull()?.toInt() ?: -1}")
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (characteristic.uuid) {
                NikonBleProfile.CONNECTION_CONFIGURATION -> {
                    parseWifiConfiguration(characteristic.value)
                }
                NikonBleProfile.SERVER_DEVICE_NAME -> {
                    Timber.tag(TAG).d("Server name: ${String(characteristic.value, Charsets.UTF_8).trimEnd('\u0000')}")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Timber.tag(TAG).d("Write ${characteristic.uuid} status=$status")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).w("Descriptor write failed: ${descriptor.uuid} status=$status")
            }
            enableNextNotification(gatt)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
                missedRssiReads = 0
                _lastHeartbeatAt.value = System.currentTimeMillis()
            } else {
                missedRssiReads++
                Timber.tag(TAG).w("RSSI read failed ($missedRssiReads/$RSSI_FAIL_LIMIT)")
                if (missedRssiReads >= RSSI_FAIL_LIMIT &&
                    _connectionState.value == BleConnectionState.CONNECTED
                ) {
                    Timber.tag(TAG).w("BLE link unhealthy, disconnecting")
                    disconnect()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupNotifications(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        authUuid: UUID
    ) {
        currentGattService = service
        pendingNotifications.clear()
        pendingNotifications.addLast(authUuid to true)
        pendingNotifications.addLast(NikonBleProfile.LSS_CONTROL_POINT to false)
        pendingNotifications.addLast(NikonBleProfile.BATTERY_LEVEL to false)
        enableNextNotification(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(gatt: BluetoothGatt) {
        val service = currentGattService ?: return
        while (true) {
            val entry = pendingNotifications.removeFirstOrNull() ?: return
            val characteristic = service.getCharacteristic(entry.first) ?: continue
            val descriptor = characteristic.getDescriptor(NikonBleProfile.CCC_DESCRIPTOR) ?: continue
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            descriptor.value = if (entry.second) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val written = gatt.writeDescriptor(descriptor)
            if (!written) {
                pendingNotifications.addFirst(entry)
                activeScope().launch {
                    delay(120)
                    enableNextNotification(gatt)
                }
            }
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPairing(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        authCharacteristic: BluetoothGattCharacteristic
    ) {
        if (pairingSession != null) return

        val timestamp = ByteArray(8).also { secureRandom.nextBytes(it) }
        val clientId = ByteArray(8).also { secureRandom.nextBytes(it) }
        clientId[0] = 0x01

        val session = if (service.getCharacteristic(NikonBleProfile.AUTHENTICATION) != null) {
            NikonAuthPairing(timestamp, clientId)
        } else {
            NikonRemotePairing(timestamp, clientId)
        }
        pairingSession = session
        _pairingMessage.value = "请在相机上确认配对（OK）"

        writeCharacteristic(authCharacteristic, session.initialMessage())

        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = activeScope().launch {
            delay(PAIRING_STAGE_TIMEOUT_MS)
            if (pairingSession != null && _connectionState.value == BleConnectionState.CONNECTING) {
                Timber.tag(TAG).w("Nikon BLE pairing timed out")
                failConnect()
            }
        }
        Timber.tag(TAG).i("Nikon 4-stage pairing started")
    }

    @SuppressLint("MissingPermission")
    private fun handlePairingStage(data: ByteArray) {
        val session = pairingSession ?: return
        val next = session.handleStage(data)
        if (next != null) {
            val char = pairedCharacteristic ?: return
            writeCharacteristic(char, next)
        } else if (session.isComplete()) {
            val g = gatt ?: return
            schedulePairingFinalization(g)
        } else if (!session.hasError()) {
            // 等待下一阶段
        } else {
            Timber.tag(TAG).e("Nikon pairing stage rejected")
            failConnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun schedulePairingFinalization(gatt: BluetoothGatt) {
        if (pairingFinalized || finalizationJob?.isActive == true) return
        Timber.tag(TAG).i("Stage 4 complete, waiting for 2008 final OK before writing ID")
        finalizationJob = activeScope().launch {
            delay(2500)
            if (!pairingFinalized && this@BleManager.gatt === gatt) {
                Timber.tag(TAG).w("Final OK not received in time, writing ID anyway")
                finalizePairing(gatt)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun finalizePairing(gatt: BluetoothGatt) {
        if (pairingFinalized) return
        pairingFinalized = true
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        finalizationJob?.cancel()
        finalizationJob = null
        pairingSession = null
        _pairingMessage.value = null

        val service = gatt.getService(NikonBleProfile.SERVICE_DE01)
            ?: gatt.getService(NikonBleProfile.SERVICE_DE00)
            ?: gatt.getService(NikonBleProfile.LSS_SERVICE)
        val nameChar = service?.getCharacteristic(NikonBleProfile.CLIENT_DEVICE_NAME)
        if (nameChar != null) {
            val clientName = "$CLIENT_NAME_PREFIX-${ByteArray(3).also { secureRandom.nextBytes(it) }.toHex()}"
            // Nikon expects a fixed 32-byte ASCII controller ID, zero-padded.
            val nameBytes = clientName.toByteArray(Charsets.US_ASCII)
            val padded = ByteArray(32)
            nameBytes.copyInto(padded, 0, 0, nameBytes.size.coerceAtMost(32))
            writeCharacteristic(nameChar, padded)
        }

        _connectionState.value = BleConnectionState.CONNECTED
        startHeartbeat()
        activeScope().launch {
            delay(250)
            requestWifiCredential()
        }
        Timber.tag(TAG).i("BLE connected with Nikon pairing confirmed")
    }

    private fun isFinalOk(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == 0x01.toByte() && data[1] == 0x00.toByte()
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val g = gatt ?: return
        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            g.writeCharacteristic(characteristic)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "writeCharacteristic failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHeartbeat() {
        stopHeartbeat()
        missedRssiReads = 0
        heartbeatJob = activeScope().launch {
            while (isActive) {
                delay(RSSI_HEARTBEAT_MS)
                val g = gatt ?: break
                try {
                    if (!g.readRemoteRssi()) {
                        missedRssiReads++
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "BLE heartbeat read failed")
                    missedRssiReads++
                }
            }
        }
        Timber.tag(TAG).i("BLE heartbeat started (RSSI keep-alive)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiCredential() {
        val service = gatt?.getService(NikonBleProfile.SERVICE_DE01)
            ?: gatt?.getService(NikonBleProfile.SERVICE_DE00)
            ?: gatt?.getService(NikonBleProfile.LSS_SERVICE)
        val characteristic = service?.getCharacteristic(NikonBleProfile.CONNECTION_CONFIGURATION)
            ?: return
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            gatt?.readCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestWifiReconnect() {
        val service = gatt?.getService(NikonBleProfile.SERVICE_DE01)
            ?: gatt?.getService(NikonBleProfile.SERVICE_DE00)
            ?: gatt?.getService(NikonBleProfile.LSS_SERVICE)
        val characteristic = service?.getCharacteristic(NikonBleProfile.CONNECTION_ESTABLISHMENT)
            ?: return
        // 手机端连接相机 WiFi 后向相机写 0x01/0x02 建立连接。
        writeCharacteristic(characteristic, byteArrayOf(0x01))
        Timber.tag(TAG).i("WiFi establishment requested via BLE")
    }

    private fun parseWifiConfiguration(data: ByteArray) {
        if (data.size < 101) {
            Timber.tag(TAG).d("WiFi configuration too short: ${data.size} bytes")
            return
        }

        val flags = data[0].toInt() and 0xFF
        val ssidRaw = data.copyOfRange(1, 33)
        val passwordRaw = data.copyOfRange(33, 97)
        val security = data[97].toInt() and 0xFF
        val ipBuffer = ByteBuffer.wrap(data, 98, 4).order(ByteOrder.LITTLE_ENDIAN)
        val ip = "${ipBuffer.get().toInt() and 0xFF}.${ipBuffer.get().toInt() and 0xFF}." +
                "${ipBuffer.get().toInt() and 0xFF}.${ipBuffer.get().toInt() and 0xFF}"

        val ssid = ssidRaw.toAsciiOrNull()
        val password = passwordRaw.toAsciiOrNull()
        if (ssid != null && password != null) {
            Timber.tag(TAG).i("WiFi credential received: SSID=$ssid flags=$flags security=$security")
            _wifiCredential.tryEmit(
                WifiCredential(
                    ssid = ssid,
                    password = password,
                    ipAddress = ip,
                    port = 15740
                )
            )
        } else {
            Timber.tag(TAG).w("WiFi config is LsSec encrypted; use camera WiFi scan or manual SSID")
        }
    }

    private fun parseCameraModel(name: String?): CameraModel {
        if (name == null) return CameraModel.UNKNOWN
        val normalized = name.replace(" ", "")
        return when {
            normalized.contains("Z50II", ignoreCase = true) -> CameraModel.Z50II
            normalized.contains("Z6III", ignoreCase = true) -> CameraModel.Z6III
            normalized.contains("Z7II", ignoreCase = true) -> CameraModel.Z7II
            normalized.contains("Z6II", ignoreCase = true) -> CameraModel.Z6II
            normalized.contains("ZFC", ignoreCase = true) -> CameraModel.ZFC
            normalized.contains("Z50", ignoreCase = true) -> CameraModel.Z50
            normalized.contains("Z30", ignoreCase = true) -> CameraModel.Z30
            normalized.contains("Z5", ignoreCase = true) -> CameraModel.Z5
            normalized.contains("Z7", ignoreCase = true) -> CameraModel.Z7
            normalized.contains("Z6", ignoreCase = true) -> CameraModel.Z6
            normalized.contains("Z8", ignoreCase = true) -> CameraModel.Z8
            normalized.contains("Z9", ignoreCase = true) -> CameraModel.Z9
            normalized.contains("Zf", ignoreCase = true) -> CameraModel.ZF
            else -> CameraModel.UNKNOWN
        }
    }

    fun isConnected(): Boolean = _connectionState.value == BleConnectionState.CONNECTED
}

/**
 * Nikon BLE 配对状态机。
 */
private interface NikonPairingSession {
    fun initialMessage(): ByteArray

    /** 返回需要写给相机的下一阶段消息；null 表示等待最终 OK 或失败。 */
    fun handleStage(data: ByteArray): ByteArray?

    fun hasError(): Boolean = false

    /** Stage 4 已收到并校验通过，可以进入最终确认（等待 2008 / 补写 ID）。 */
    fun isComplete(): Boolean = false
}

/**
 * Nikon smart-device（2000 认证）配对。
 *
 * 密钥与盐值来自公开 BLE 配对资料，Blowfish 使用标准
 * Blowfish/ECB/NoPadding 完成，避免依赖尼康私有 LsSec 原生库。
 */
private class NikonAuthPairing(
    private val timestamp: ByteArray,
    private val clientId: ByteArray
) : NikonPairingSession {

    private var stage2Timestamp: ByteArray? = null
    private var error = false
    private var complete = false
    private val blowfish = NikonBlowfish()

    init {
        if (!blowfish.isAvailable) {
            error = true
        }
    }

    override fun initialMessage(): ByteArray = buildMessage(0x01, timestamp, clientId)

    override fun handleStage(data: ByteArray): ByteArray? {
        if (data.size < 17) {
            error = true
            return null
        }

        return when (val stage = data[0].toInt() and 0xFF) {
            0x00 -> initialMessage()
            0x02 -> handleStage2(data)
            0x04 -> handleStage4(data)
            else -> {
                Timber.tag(TAG).w("Unexpected Nikon pairing stage 0x${stage.toString(16)}")
                error = true
                null
            }
        }
    }

    private fun handleStage2(data: ByteArray): ByteArray? {
        val msgTimestamp = data.copyOfRange(1, 9)
        val msgId = data.copyOfRange(9, 17)
        val stage0Timestamp = timestamp

        for (salt in SALT) {
            val probe = intArrayOf(
                salt[0],
                salt[1],
                msgTimestamp.beInt(0),
                msgTimestamp.beInt(4),
                stage0Timestamp.beInt(0),
                stage0Timestamp.beInt(4)
            )
            val digest = blowfish.hash(probe)
            if (digest.first == msgId.beInt(0) && digest.second == msgId.beInt(4)) {
                val response = intArrayOf(
                    salt[0],
                    salt[1],
                    stage0Timestamp.beInt(0),
                    stage0Timestamp.beInt(4),
                    msgTimestamp.beInt(0),
                    msgTimestamp.beInt(4)
                )
                val nextId = blowfish.hash(response)
                val idBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    .putInt(nextId.first)
                    .putInt(nextId.second)
                    .array()
                stage2Timestamp = msgTimestamp
                return buildMessage(0x03, timestamp, idBytes)
            }
        }

        Timber.tag(TAG).w("Nikon stage 2 salt verification failed")
        error = true
        return null
    }

    private fun handleStage4(data: ByteArray): ByteArray? {
        val expected = stage2Timestamp
        if (expected == null || !expected.contentEquals(data.copyOfRange(1, 9))) {
            error = true
            return null
        }
        // 真机实测（Z50II/Z8）: smart-device pairing stops at stage 4; there is no stage 5.
        complete = true
        return null
    }

    override fun hasError(): Boolean = error

    override fun isComplete(): Boolean = complete

    private fun buildMessage(stage: Int, ts: ByteArray, id: ByteArray): ByteArray {
        return ByteArray(17).also { out ->
            out[0] = stage.toByte()
            System.arraycopy(ts, 0, out, 1, 8)
            System.arraycopy(id, 0, out, 9, 8)
        }
    }

    companion object {
        private const val TAG = "NikonAuthPairing"
        private val SALT = arrayOf(
            intArrayOf(0x704066e4.toInt(), 0x0433d552.toInt()),
            intArrayOf(0xed4b8fac.toInt(), 0x15f7e47b.toInt()),
            intArrayOf(0x24471f11.toInt(), 0x8b5ea1fc.toInt()),
            intArrayOf(0x05960c31.toInt(), 0x2b8c7f41.toInt()),
            intArrayOf(0xfda588c1.toInt(), 0xeba8b1f3.toInt()),
            intArrayOf(0x99166056.toInt(), 0x1bd3d550.toInt()),
            intArrayOf(0xcd32687f.toInt(), 0xa9e28a30.toInt()),
            intArrayOf(0x2a8fe834.toInt(), 0xdec7ebf4.toInt())
        )
    }
}

/**
 * Nikon ML-L7 remote 配对（部分机型不提供 2000 认证特征时使用）。
 */
private class NikonRemotePairing(
    private val timestamp: ByteArray,
    private val clientId: ByteArray
) : NikonPairingSession {

    private var error = false
    private var complete = false

    override fun initialMessage(): ByteArray = buildMessage(0x01, timestamp, clientId)

    override fun handleStage(data: ByteArray): ByteArray? {
        if (data.size < 17) {
            error = true
            return null
        }
        return when (data[0].toInt() and 0xFF) {
            0x00 -> initialMessage()
            0x02 -> {
                val zeroed = data.copyOfRange(1, 17).all { it == 0.toByte() }
                if (!zeroed) {
                    error = true
                    null
                } else {
                    buildMessage(0x03, ByteArray(8), ByteArray(8))
                }
            }
            0x04 -> {
                complete = true
                null
            }
            else -> {
                error = true
                null
            }
        }
    }

    override fun hasError(): Boolean = error

    override fun isComplete(): Boolean = complete

    private fun buildMessage(stage: Int, ts: ByteArray, id: ByteArray): ByteArray {
        return ByteArray(17).also { out ->
            out[0] = stage.toByte()
            System.arraycopy(ts, 0, out, 1, 8)
            System.arraycopy(id, 0, out, 9, 8)
        }
    }
}

/**
 * 仅使用 Blowfish ECB 加密 8 字节块，模拟 Nikon smart-device hash。
 */
internal class NikonBlowfish {
    private val cipher: Cipher?

    init {
        cipher = try {
            val key = byteArrayOf(
                0xff.toByte(), 0xff.toByte(), 0xaa.toByte(), 0x55.toByte(),
                0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x00.toByte()
            )
            Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Blowfish unavailable")
            null
        }
    }

    fun hash(words: IntArray): Pair<Int, Int> {
        val cipher = cipher ?: return 0 to 0
        var left = 0x01020304
        var right = 0x05060708
        for (i in words.indices step 2) {
            val inLeft = words[i] xor left
            val inRight = words[i + 1] xor right
            val block = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(inLeft)
                .putInt(inRight)
                .array()
            val encrypted = cipher.doFinal(block)
            val buffer = ByteBuffer.wrap(encrypted).order(ByteOrder.BIG_ENDIAN)
            left = buffer.int
            right = buffer.int
        }
        return left to right
    }

    val isAvailable: Boolean get() = cipher != null

    companion object {
        private const val TAG = "NikonBlowfish"
    }
}

private fun ByteArray.beInt(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}

private fun ByteArray.toAsciiOrNull(): String? {
    var length = 0
    while (length < size && this[length] != 0.toByte()) {
        val b = this[length].toInt() and 0xFF
        if (b < 0x20 || b > 0x7E) return null
        length++
    }
    return if (length == 0) null else String(this, 0, length, Charsets.US_ASCII)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * BLE 连接子状态
 */
enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

/**
 * 新文件通知数据
 */
data class FileNotification(
    val fileHandle: Int,
    val fileSize: Long,
    val fileName: String,
    val timestamp: Long
)

/**
 * WiFi 凭证（通过 BLE 交换）
 */
data class WifiCredential(
    val ssid: String,
    val password: String,
    val ipAddress: String,
    val port: Int = 15740
)
