package com.heartrate.broadcast.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.UUID

/**
 * BLE GATT Server + Advertiser
 * 将设备模拟为标准蓝牙心率传感器，向外广播心率和电量数据。
 *
 * 符合 Bluetooth Heart Rate Profile (HRP) 规范：
 * - Heart Rate Service (0x180D) with Heart Rate Measurement (0x2A37) NOTIFY
 * - Battery Service (0x180F) with Battery Level (0x2A19) READ+NOTIFY
 */
class BleHeartRateBroadcaster(private val context: Context) {

    companion object {
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BODY_SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_CONTROL_POINT_UUID: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Flags: bit0=0 (UINT8), bit1-2=11 (sensor contact supported+detected), bit3=0, bit4=0
        private const val HR_FLAGS: Byte = 0x06
        private const val BODY_SENSOR_LOCATION: Byte = 0x01  // Chest
        // BLE_SHORT_NAME 已移除，改用 Build.BRAND + Build.MODEL 动态获取
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var heartRateCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(mutableSetOf())
    private val subscribedDevices: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(mutableSetOf())
    @Volatile
    private var isRunning = false
    private var pendingServicesCount = 0

    // 统一延迟调度
    @Volatile
    private var handlerThread: HandlerThread? = null
    @Volatile
    private var handler: Handler? = null

    private fun ensureHandler(): Handler {
        handler?.let { return it }
        synchronized(this) {
            handler?.let { return it }
            val ht = HandlerThread("BLE-Deferred").apply { start() }
            handlerThread = ht
            val h = Handler(ht.looper)
            handler = h
            return h
        }
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // 节流
    private var lastNotifyTime = 0L
    private var lastNotifiedBpm = 0
    private var lastNotifiedBattery = -1
    private var lastSentBattery = -2

    // 拥塞控制
    private val retryingDevices: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(mutableSetOf())
    private val congestedDevices: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(mutableSetOf())

    // GATT 服务是否已完全就绪（onServiceAdded 全部完成）
    @Volatile
    private var gattReady = false

    private fun hasBlePermissions(): Boolean {
        val required = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun start(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            android.util.Log.e("BLE", "BluetoothAdapter 为 null")
            return false
        }
        if (!adapter.isEnabled) {
            android.util.Log.e("BLE", "蓝牙未开启")
            return false
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            android.util.Log.e("BLE", "设备不支持 BLE 多重广播")
            return false
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            android.util.Log.e("BLE", "BluetoothLeAdvertiser 为 null")
            return false
        }

        if (!hasBlePermissions()) {
            android.util.Log.e("BLE", "缺少 BLUETOOTH_ADVERTISE 或 BLUETOOTH_CONNECT 权限")
            return false
        }

        setupGattServer()

        isRunning = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isRunning = false
        gattReady = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        stopAdvertising()
        closeGattServer()
        connectedDevices.clear()
        subscribedDevices.clear()
        congestedDevices.clear()
        retryingDevices.clear()
    }

    fun getSubscribedDeviceCount(): Int = subscribedDevices.size
    fun isRunning(): Boolean = isRunning

    @SuppressLint("MissingPermission")
    fun updateHeartRate(bpm: Int, batteryLevel: Int) {
        if (!isRunning) {
            android.util.Log.d("BLE", "updateHeartRate 跳过: isRunning=false")
            return
        }
        if (!gattReady) {
            android.util.Log.d("BLE", "updateHeartRate 跳过: gattReady=false")
            return
        }
        if (subscribedDevices.isEmpty()) {
            android.util.Log.d("BLE", "updateHeartRate 跳过: 无订阅设备")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 1000 && bpm == lastNotifiedBpm && batteryLevel == lastNotifiedBattery) return
        lastNotifyTime = now
        lastNotifiedBpm = bpm
        lastNotifiedBattery = batteryLevel

        val hrValue = if (bpm > 0) bpm else 0
        val hrBytes = byteArrayOf(HR_FLAGS, hrValue.toByte())
        @Suppress("DEPRECATION")
        heartRateCharacteristic?.setValue(hrBytes)
        @Suppress("DEPRECATION")
        batteryCharacteristic?.setValue(byteArrayOf(batteryLevel.toByte()))

        val server = gattServer
        val hrChar = heartRateCharacteristic
        val batChar = batteryCharacteristic
        if (server == null || hrChar == null || batChar == null) return

        for (device in subscribedDevices) {
            if (device in congestedDevices) {
                android.util.Log.d("BLE", "跳过拥塞设备: ${device.address}")
                continue
            }
            android.util.Log.d("BLE", "发送通知: ${device.address}, HR=$hrValue, BAT=$batteryLevel")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, hrChar, false, hrBytes)
                    if (batteryLevel != lastSentBattery) {
                        server.notifyCharacteristicChanged(device, batChar, false, byteArrayOf(batteryLevel.toByte()))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, hrChar, false)
                    if (batteryLevel != lastSentBattery) {
                        @Suppress("DEPRECATION")
                        server.notifyCharacteristicChanged(device, batChar, false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("BLE", "通知异常: ${e.message}, device=${device.address}")
            }
        }
        if (batteryLevel != lastSentBattery) lastSentBattery = batteryLevel
        android.util.Log.d("BLE", "已通知 ${subscribedDevices.size} 设备: HR=$bpm, BAT=$batteryLevel")
    }

    /**
     * 按规范创建 GATT 服务。
     *
     * 关键：不手动添加 CCCD 描述符。
     * Android BLE 栈在 PROPERTY_NOTIFY/INDICATE 特征上会自动创建 CCCD。
     * 手动添加会导致 "no desc for handle" —— 系统创建了一个，应用又创建了一个，
     * 两个描述符的 handle 冲突，客户端写入时找不到正确的那个。
     */
    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        // ── Heart Rate Service (0x180D) ──
        val heartRateService = BluetoothGattService(
            HEART_RATE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Heart Rate Measurement (0x2A37) — NOTIFY only (规范要求)
        // 系统会自动创建 CCCD 描述符
        heartRateCharacteristic = BluetoothGattCharacteristic(
            HEART_RATE_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        @Suppress("DEPRECATION")
        heartRateCharacteristic!!.setValue(byteArrayOf(HR_FLAGS, 0x00))
        heartRateService.addCharacteristic(heartRateCharacteristic)

        // Body Sensor Location (0x2A38) — READ (可选但客户端普遍期望)
        val bodySensorLocation = BluetoothGattCharacteristic(
            BODY_SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        @Suppress("DEPRECATION")
        bodySensorLocation.value = byteArrayOf(BODY_SENSOR_LOCATION)
        heartRateService.addCharacteristic(bodySensorLocation)

        // Heart Rate Control Point (0x2A39) — WRITE (规范要求，用于复位 Energy Expended)
        val hrControlPoint = BluetoothGattCharacteristic(
            HEART_RATE_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        heartRateService.addCharacteristic(hrControlPoint)

        // ── Battery Service (0x180F) ──
        val batteryService = BluetoothGattService(
            BATTERY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Battery Level (0x2A19) — READ + NOTIFY
        batteryCharacteristic = BluetoothGattCharacteristic(
            BATTERY_LEVEL_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        @Suppress("DEPRECATION")
        batteryCharacteristic!!.value = byteArrayOf(0x00)
        batteryService.addCharacteristic(batteryCharacteristic)

        // 先注册 Heart Rate Service，完成后再注册 Battery Service
        // 串行注册避免 handle 分配冲突
        pendingServicesCount = 0
        gattServer?.addService(heartRateService)

        // 超时兜底：3秒后如果 onServiceAdded 没有全部触发，强制就绪
        ensureHandler().postDelayed({
            if (isRunning && !gattReady) {
                android.util.Log.w("BLE", "GATT 服务注册超时，强制就绪 (已注册 $pendingServicesCount 个)")
                gattReady = true
                if (advertiseCallback == null) startAdvertising()
            }
        }, 3000)

        android.util.Log.d("BLE", "正在注册 Heart Rate Service...")
    }

    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val bleName = Build.MODEL
        bluetoothAdapter?.name = bleName

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .setIncludeDeviceName(true)
            .build()

        android.util.Log.d("BLE", "开始广播，设备名: $bleName")

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                android.util.Log.d("BLE", "广播启动成功")
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "数据过大"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播者过多"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "已在广播"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "内部错误"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "不支持"
                    else -> "未知($errorCode)"
                }
                android.util.Log.e("BLE", "广播启动失败: $reason")
            }
        }

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun restartAdvertising() {
        android.util.Log.d("BLE", "重新启动广播")
        stopAdvertising()
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        advertiseCallback?.let { advertiser?.stopAdvertising(it) }
    }

    private fun closeGattServer() {
        gattServer?.close()
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            android.util.Log.d("BLE", "onServiceAdded: ${service.uuid}, status=$status, count=${pendingServicesCount + 1}")

            pendingServicesCount++
            when (pendingServicesCount) {
                1 -> {
                    // Heart Rate Service 注册完成，注册 Battery Service
                    android.util.Log.d("BLE", "HR 服务注册完成，注册 BAT 服务...")
                    val batteryService = BluetoothGattService(
                        BATTERY_SERVICE_UUID,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY
                    )
                    batteryCharacteristic = BluetoothGattCharacteristic(
                        BATTERY_LEVEL_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    )
                    @Suppress("DEPRECATION")
                    batteryCharacteristic!!.value = byteArrayOf(0x00)
                    batteryService.addCharacteristic(batteryCharacteristic!!)
                    gattServer?.addService(batteryService)
                }
                2 -> {
                    // 两个服务都注册完成
                    gattReady = true
                    android.util.Log.d("BLE", "所有 GATT 服务注册完成，开始广播")
                    startAdvertising()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            android.util.Log.d("BLE", "onConnectionStateChange: ${device.address}, status=$status, newState=$newState, gattReady=$gattReady")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                subscribedDevices.add(device)
                android.util.Log.d("BLE", "设备已连接: ${device.address}，自动订阅通知")
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                restartAdvertising()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                subscribedDevices.remove(device)
                congestedDevices.remove(device)
                retryingDevices.remove(device)
                android.util.Log.d("BLE", "设备已断开: ${device.address}，剩余: ${connectedDevices.size}")
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                HEART_RATE_MEASUREMENT_UUID -> {
                    // 规范：HR Measurement 不支持 Read
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                }
                BODY_SENSOR_LOCATION_UUID -> {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value ?: byteArrayOf(BODY_SENSOR_LOCATION)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                }
                BATTERY_LEVEL_UUID -> {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value ?: byteArrayOf(0x00)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR) {
                val cccdValue = if (device in subscribedDevices) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, cccdValue)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR) {
                val isEnable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val isDisable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)

                if (isEnable) {
                    subscribedDevices.add(device)
                    android.util.Log.d("BLE", "设备 ${device.address} 订阅通知，当前: ${subscribedDevices.size}")
                } else if (isDisable) {
                    subscribedDevices.remove(device)
                    android.util.Log.d("BLE", "设备 ${device.address} 取消订阅")
                } else {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                    return
                }

                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (e: Exception) {
                        android.util.Log.d("BLE", "CCCD 响应由系统处理: ${e.message}")
                    }
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            // Heart Rate Control Point 写入 — 规范要求支持
            if (characteristic.uuid == HEART_RATE_CONTROL_POINT_UUID) {
                android.util.Log.d("BLE", "HR Control Point 写入: ${value.joinToString { "%02X".format(it) }}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            android.util.Log.d("BLE", "MTU 变更: ${device.address}, mtu=$mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.w("BLE", "通知失败: status=$status, device=${device.address}")
                if (status == 135 && isRunning && device in subscribedDevices
                    && retryingDevices.add(device)) {
                    congestedDevices.add(device)
                    ensureHandler().postDelayed({
                        retryingDevices.remove(device)
                        if (!isRunning || device !in subscribedDevices) {
                            congestedDevices.remove(device)
                            return@postDelayed
                        }
                        val server = gattServer ?: return@postDelayed
                        val hrChar = heartRateCharacteristic ?: return@postDelayed
                        val batChar = batteryCharacteristic ?: return@postDelayed
                        val hrValue = if (lastNotifiedBpm > 0) lastNotifiedBpm else 0
                        val hrBytes = byteArrayOf(HR_FLAGS, hrValue.toByte())
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                server.notifyCharacteristicChanged(device, hrChar, false, hrBytes)
                                server.notifyCharacteristicChanged(device, batChar, false, byteArrayOf(lastNotifiedBattery.toByte()))
                            } else {
                                @Suppress("DEPRECATION")
                                server.notifyCharacteristicChanged(device, hrChar, false)
                                @Suppress("DEPRECATION")
                                server.notifyCharacteristicChanged(device, batChar, false)
                            }
                        } catch (_: Exception) {
                            congestedDevices.remove(device)
                        }
                    }, 1000)
                }
            } else {
                congestedDevices.remove(device)
            }
        }
    }
}
