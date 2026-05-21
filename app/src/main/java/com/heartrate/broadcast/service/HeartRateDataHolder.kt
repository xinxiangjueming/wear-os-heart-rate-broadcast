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

    fun updateHeartRate(bpm: Int) {
        _heartRate.value = bpm
    }

    fun updateBatteryLevel(level: Int) {
        _batteryLevel.value = level
    }

    fun setBroadcasting(value: Boolean) {
        _isBroadcasting.value = value
    }

    /** 停止时重置 */
    fun reset() {
        _heartRate.value = 0
        _batteryLevel.value = 0
        _isBroadcasting.value = false
    }
}
