package com.heartrate.broadcast.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.heartrate.broadcast.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await

/**
 * 前台服务：
 * 1. 通过 Health Services API 读取心率
 * 2. 读取设备电量
 * 3. 通过 BLE GATT Server 广播数据
 */
class HeartRateBroadcastService : Service() {

    companion object {
        const val ACTION_START = "com.heartrate.broadcast.START"
        const val ACTION_STOP = "com.heartrate.broadcast.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HeartRateBroadcastService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HeartRateBroadcastService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var bleBroadcaster: BleHeartRateBroadcaster? = null
    private var measureClient: MeasureClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentHeartRate = 0
    private var isMonitoring = false
    private var isStopping = false  // 防止重复 stop

    // 通知节流：仅数值变化时更新
    private var lastNotifiedHeartRate = -1
    private var lastNotifiedBattery = -1
    // 心跳监控：记录最后一次收到心率数据的时间
    @Volatile
    private var lastDataReceivedTime = 0L

    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: androidx.health.services.client.data.Availability) {
            android.util.Log.d("HealthService", "可用性变更: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            lastDataReceivedTime = System.currentTimeMillis()
            val heartRatePoints = data.getData(DataType.HEART_RATE_BPM)
            for (point in heartRatePoints) {
                val bpm = point.value.toInt()
                if (bpm <= 0) {
                    android.util.Log.d("HealthService", "心率无效: $bpm，跳过")
                    return
                }
                currentHeartRate = bpm
                android.util.Log.d("HealthService", "心率: $bpm bpm")

                // 同步到共享数据桥梁，UI 会自动更新
                HeartRateDataHolder.updateHeartRate(bpm)

                // 更新 BLE 广播数据
                val battery = getBatteryLevel()
                HeartRateDataHolder.updateBatteryLevel(battery)
                bleBroadcaster?.updateHeartRate(bpm, battery)

                // 仅数值变化时更新通知
                if (bpm != lastNotifiedHeartRate || battery != lastNotifiedBattery) {
                    lastNotifiedHeartRate = bpm
                    lastNotifiedBattery = battery
                    updateNotification(bpm, battery)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBroadcasting()
            ACTION_STOP -> stopBroadcasting()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 不在这里调用 stopBroadcasting()，避免 Activity 重建时误停服务
        // Service 的生命周期由用户通过按钮或 ACTION_STOP 主动控制
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startBroadcasting() {
        if (isMonitoring) return

        // 重置停止标志，防止进程重建后残留
        isStopping = false

        // 启动前台通知
        val notification = NotificationHelper.buildNotification(this, 0, getBatteryLevel())
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)

        // 获取 WakeLock 保持 CPU 运行
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeartRateBroadcast::WakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4小时超时

        // 初始化 BLE 广播器
        bleBroadcaster = BleHeartRateBroadcaster(this)
        val bleStarted = bleBroadcaster?.start() ?: false
        if (!bleStarted) {
            android.util.Log.e("Service", "BLE广播启动失败")
            HeartRateDataHolder.reset()  // 重置 UI 状态
            stopSelf()
            return
        }

        // BLE 启动成功后才通知 UI
        HeartRateDataHolder.setBroadcasting(true)
        HeartRateDataHolder.updateBatteryLevel(getBatteryLevel())

        // 初始化 Health Services
        val healthServices = HealthServices.getClient(this)
        measureClient = healthServices.measureClient

        // 注册心率监听
        serviceScope.launch {
            try {
                val capabilities = measureClient!!.getCapabilitiesAsync().await()
                val supportedTypes = capabilities.supportedDataTypesMeasure
                if (DataType.HEART_RATE_BPM in supportedTypes) {
                    measureClient!!.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
                    isMonitoring = true
                    lastDataReceivedTime = System.currentTimeMillis()  // 初始化心跳计时
                    android.util.Log.d("Service", "心率监听已启动")
                } else {
                    android.util.Log.e("Service", "设备不支持心率监测")
                    stopSelf()
                }
            } catch (e: Exception) {
                android.util.Log.e("Service", "Health Services 初始化失败", e)
                stopSelf()
            }
        }

        // 心跳协程：监控心率数据是否停滞，若超过 30 秒无数据则重新注册回调
        // 避免过于频繁地重注册，会打断传感器的 ACQUIRING → AVAILABLE 初始化过程
        serviceScope.launch {
            while (isActive) {
                delay(10000)
                if (isMonitoring) {
                    val elapsed = System.currentTimeMillis() - lastDataReceivedTime
                    if (elapsed > 30_000) {
                        android.util.Log.w("Service", "心率数据已停滞 ${elapsed / 1000}秒，重新注册监听")
                        try {
                            measureClient?.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)?.await()
                        } catch (_: Exception) {}
                        try {
                            measureClient?.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
                            lastDataReceivedTime = System.currentTimeMillis()  // 重置计时，避免立即再次触发
                            android.util.Log.d("Service", "心率监听重新注册成功")
                        } catch (e: Exception) {
                            android.util.Log.e("Service", "心率监听重新注册失败: ${e.message}")
                        }
                    }
                }
            }
        }

        // 定期更新电量（仅变化时刷新通知）
        serviceScope.launch {
            while (isActive) {
                delay(10000)
                if (isMonitoring) {
                    val battery = getBatteryLevel()
                    HeartRateDataHolder.updateBatteryLevel(battery)
                    if (battery != lastNotifiedBattery) {
                        lastNotifiedBattery = battery
                        updateNotification(currentHeartRate, battery)
                    }
                }
            }
        }

        // 广播状态监控：定期检查 BLE 广播是否仍在正常工作
        serviceScope.launch {
            while (isActive) {
                delay(15000)
                if (isMonitoring) {
                    val broadcaster = bleBroadcaster
                    if (broadcaster == null || !broadcaster.isRunning()) {
                        android.util.Log.w("Service", "BLE 广播异常停止，尝试重启")
                        try {
                            val newBroadcaster = BleHeartRateBroadcaster(this@HeartRateBroadcastService)
                            if (newBroadcaster.start()) {
                                bleBroadcaster = newBroadcaster
                                android.util.Log.d("Service", "BLE 广播重启成功")
                            } else {
                                android.util.Log.e("Service", "BLE 广播重启失败")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Service", "BLE 广播重启异常", e)
                        }
                    }

                    // 检查蓝牙适配器状态
                    try {
                        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
                        if (adapter != null && !adapter.isEnabled) {
                            android.util.Log.w("Service", "蓝牙被关闭，服务可能无法正常工作")
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopBroadcasting() {
        if (isStopping) return  // 防止重复调用
        isStopping = true
        isMonitoring = false

        // 通知 UI 停止广播，重置数据
        HeartRateDataHolder.reset()

        // 同步注销心率监听（确保在 scope 取消前完成）
        runBlocking(Dispatchers.IO) {
            try {
                measureClient?.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)?.await()
            } catch (_: Exception) {}
        }

        bleBroadcaster?.stop()
        bleBroadcaster = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun updateNotification(heartRate: Int, battery: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, heartRate, battery)
        )
    }
}
