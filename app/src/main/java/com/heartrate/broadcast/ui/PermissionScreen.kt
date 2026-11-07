package com.heartrate.broadcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.heartrate.broadcast.R

@Composable
fun PermissionScreen(onRequestPermissions: () -> Unit) {
    ScreenScaffold(timeText = { TimeText() }) { _ ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.Black)
        ) {
            val screenH = maxHeight

            // ① 心形：放在屏幕顶部 1/3 区域内，并在该区域内上下居中 ⇒ 中心 ≈ H/6。
            // 取整屏高度作分母（不扣 Scaffold 上下内边距），三段位置互不随字号联动。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(screenH / 3f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "❤️",
                    fontSize = 32.sp
                )
            }

            // ② 「心率广播」+「需要蓝牙和通知权限」两行：整块中心精确落在屏幕中间高度 0.5H。
            Box(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.permission_title),
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.permission_desc),
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                }
            }

            // ③ 授权按钮：Wear M3 官方 EdgeButton（原自绘月牙按钮已删除），
            // 配色与主界面「开启」按钮统一为 miuix 蓝；外层不整体 padding 才能贴合圆屏下沿。
            EdgeButton(
                onClick = onRequestPermissions,
                buttonSize = EdgeButtonSize.Small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3482FF),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            ) {
                Text(
                    text = stringResource(R.string.permission_grant),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1
                )
            }
        }
    }
}
