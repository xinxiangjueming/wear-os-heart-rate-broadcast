package com.heartrate.broadcast.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartrate.broadcast.R
import com.heartrate.broadcast.service.HeartRateBroadcastService
import com.heartrate.broadcast.service.HeartRateDataHolder
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 心率广播主界面 —— 交互态与微光态共用 sportlinkwear 的 1:2:1 三行数据页。
 *
 * - 交互态：数据页 + 底部开始/停止按钮（本工程没有暂停页，按钮必须保留）
 * - 微光态：只显示数据页，不显示按钮；数值改压暗配色避免烧屏/过曝
 *
 * 两态数据同源 [HeartRateDataHolder]；微光态刷新降到 30s 一次以省电。
 */
@Composable
fun MainScreen(inAmbient: Boolean = false) {
    val context = LocalContext.current

    val heartRate by HeartRateDataHolder.heartRate.collectAsState()
    val batteryLevel by HeartRateDataHolder.batteryLevel.collectAsState()
    val isBroadcasting by HeartRateDataHolder.isBroadcasting.collectAsState()
    val connectedCount by HeartRateDataHolder.connectedCount.collectAsState()
    val startedAtMs by HeartRateDataHolder.startedAtMs.collectAsState()

    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isBroadcasting, startedAtMs, inAmbient) {
        if (!isBroadcasting) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = if (startedAtMs > 0L) System.currentTimeMillis() - startedAtMs else 0L
            delay(if (inAmbient) 30_000L else 1_000L)
        }
    }

    var pendingStart by remember { mutableStateOf(false) }
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            HeartRateBroadcastService.start(context)
        }
        pendingStart = false
    }

    // 配色：交互态沿用 sportlinkwear 口径；微光态按源的 BroadcastAmbientScreen 压暗
    val hrColor = if (heartRate > 0) {
        if (inAmbient) Color.White else Color(0xFFE91E63)
    } else {
        Color(0xFF666666)
    }
    val batteryColor = if (inAmbient) Color(0xFFCCCCCC) else Color(0xFF2E7D32)
    val connectedColor = if (inAmbient) Color(0xFFCCCCCC) else Color(0xFFB3B3B3)
    val timeColor = Color(0xFFFFEB3B)

    val statsPage: @Composable (Modifier) -> Unit = { pageModifier ->
        BroadcastStatsLayout(
            modifier = pageModifier,
            top = {
                StatCell(
                    value = formatElapsed(elapsedMs),
                    valueColor = timeColor,
                    label = stringResource(R.string.label_elapsed),
                    modifier = Modifier.fillMaxSize()
                )
            },
            middle = {
                StatCell(
                    value = if (heartRate > 0) "$heartRate" else "--",
                    valueColor = hrColor,
                    label = stringResource(R.string.label_bpm),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                StatCell(
                    value = "$batteryLevel",
                    valueColor = batteryColor,
                    label = stringResource(R.string.label_battery_pct),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            },
            bottom = {
                StatCell(
                    value = "$connectedCount",
                    valueColor = connectedColor,
                    label = stringResource(R.string.label_connected),
                    modifier = Modifier.fillMaxSize()
                )
            }
        )
    }

    // Wear M3：ScreenScaffold 承载页面。本页是固定数据页、没有滚动列表，所以官方 EdgeButton
    // 直接放在内容底部，不走 edgeButton 槽 —— 该槽的高度由 ScrollInfoProvider.lastItemOffset
    // 驱动，无列表时会把按钮压到几乎不可见（sportlinkwear 00:30 已实证）。
    ScreenScaffold(timeText = {}) { _ ->
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // 数据页左右留白；外层不加 padding，贴边按钮才能真正贴到圆屏底沿
            statsPage(
                if (inAmbient) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)
                }
            )

            if (!inAmbient) {
                EdgeButton(
                    onClick = {
                        if (isBroadcasting) {
                            HeartRateBroadcastService.stop(context)
                        } else {
                            val adapter = BluetoothAdapter.getDefaultAdapter()
                            if (adapter?.isEnabled == true) {
                                HeartRateBroadcastService.start(context)
                            } else {
                                pendingStart = true
                                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                        }
                    },
                    buttonSize = EdgeButtonSize.Small,
                    colors = ButtonDefaults.buttonColors(
                        // 开启 = miuix 蓝（0xFF3482FF）；停止 = 热烈红（0xFFE53935），
                        // 不用 M3 theme.error（深色主题下偏粉）。
                        containerColor = if (isBroadcasting) {
                            Color(0xFFE53935)
                        } else {
                            Color(0xFF3482FF)
                        },
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(if (isBroadcasting) R.string.btn_stop else R.string.btn_start),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 与 sportlinkwear `formatTime` 同格式：HH:MM:SS */
private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return String.format(
        Locale.US, "%02d:%02d:%02d",
        total / 3600, (total % 3600) / 60, total % 60
    )
}
