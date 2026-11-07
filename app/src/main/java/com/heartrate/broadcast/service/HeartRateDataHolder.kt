package com.heartrate.broadcast.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单例数据桥梁：Service 写入心率/电量，UI 通过 StateFlow 读取。
 */
object HeartRateDataHolder {

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    /**
     * 数据页第 3 行「已连接」：实际写了 CCCD 开启心率 notify 的外部设备数。
     * 按 sportlinkwear `HrBroadcastManager.connectedCount` 的修复口径，与 GATT 物理连入数严格区分；
     * 由 `BleHeartRateBroadcaster.subscribedDevices` 派生，连入本身不改变计数。
     */
    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

    /** 广播启动时刻（System.currentTimeMillis），用于第 1 行运动时间 */
    private val _startedAtMs = MutableStateFlow(0L)
    val startedAtMs: StateFlow<Long> = _startedAtMs.asStateFlow()

    fun updateHeartRate(bpm: Int) {
        _heartRate.value = bpm
    }

    fun updateBatteryLevel(level: Int) {
        _batteryLevel.value = level
    }

    fun setBroadcasting(value: Boolean) {
        _isBroadcasting.value = value
    }

    fun updateConnectedCount(count: Int) {
        _connectedCount.value = count
    }

    fun setStartedAtMs(ms: Long) {
        _startedAtMs.value = ms
    }

    /** 停止时重置 */
    fun reset() {
        _heartRate.value = 0
        _batteryLevel.value = 0
        _isBroadcasting.value = false
        _connectedCount.value = 0
        _startedAtMs.value = 0L
    }
}
