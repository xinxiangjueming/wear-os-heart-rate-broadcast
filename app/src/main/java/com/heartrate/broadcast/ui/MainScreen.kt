package com.heartrate.broadcast.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.heartrate.broadcast.R
import com.heartrate.broadcast.service.HeartRateBroadcastService
import com.heartrate.broadcast.service.HeartRateDataHolder
import androidx.compose.foundation.background

@Composable
fun MainScreen() {
    val context = LocalContext.current

    // 从共享数据桥梁读取，Service 更新时 UI 自动刷新
    val heartRate by HeartRateDataHolder.heartRate.collectAsState()
    val batteryLevel by HeartRateDataHolder.batteryLevel.collectAsState()
    val isBroadcasting by HeartRateDataHolder.isBroadcasting.collectAsState()

    // 蓝牙开启请求
    var pendingStart by remember { mutableStateOf(false) }
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 用户同意开启蓝牙，启动服务
            HeartRateBroadcastService.start(context)
        }
        pendingStart = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Row 1: Title / Status ──
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isBroadcasting) {
                    Text(
                        text = stringResource(R.string.status_broadcasting),
                        style = MaterialTheme.typography.caption1,
                        color = Color(0xFFFFEB3B)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.title_heart_rate_broadcast),
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFEB3B)
                    )
                }
            }

            // ── Row 2: Heart Rate (left) | Battery (right) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isBroadcasting && heartRate > 0) "$heartRate" else "--",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE53935)
                    )
                    Text(
                        text = stringResource(R.string.label_heart_rate),
                        style = MaterialTheme.typography.caption2,
                        color = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = Color(0xFF2E7D32))) {
                                append("$batteryLevel")
                            }
                            withStyle(style = SpanStyle(color = Color.White)) {
                                append("%")
                            }
                        },
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.label_battery),
                        style = MaterialTheme.typography.caption2,
                        color = Color.White
                    )
                }
            }

            // ── Row 3: Start / Stop ──
            Button(
                onClick = {
                    if (isBroadcasting) {
                        HeartRateBroadcastService.stop(context)
                    } else {
                        // 检查蓝牙是否开启
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        if (adapter?.isEnabled == true) {
                            HeartRateBroadcastService.start(context)
                        } else {
                            // 弹系统窗请求开启蓝牙
                            pendingStart = true
                            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            bluetoothEnableLauncher.launch(enableIntent)
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = if (isBroadcasting)
                    ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                else
                    ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(40.dp)
            ) {
                Text(
                    text = stringResource(if (isBroadcasting) R.string.btn_stop else R.string.btn_start),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }
    }
}
