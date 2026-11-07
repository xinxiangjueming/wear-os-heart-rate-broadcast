package com.heartrate.broadcast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlin.math.min

/**
 * 心率广播数据页 —— 照搬 sportlinkwear 的 1:2:1 三行版式规格：
 *
 * - 第 1 行：运动时间（收在内接正方形宽度内、水平居中）
 * - 第 2 行：心率 + 手表电量（铺满整屏，左右各半）
 * - 第 3 行：已连接设备数（同第 1 行）
 *
 * 几何定稿值（与源 `ui/history/HistoryStatComponents.kt` 一致，勿改）：
 * - `side = min(maxWidth, maxHeight) * 0.7071`
 * - 整块高度 = `side * 7/6`
 * - 格内「数值 : 标签」高度权重 = 4 : 3
 * - 数值/标签字号由 TextMeasurer 按各自带的真实可用宽高实测反解，不写死 sp
 *
 * 源工程用 Wear Compose Material3 + HistoryCell/AutoValueText；本工程已升级到
 * Wear Compose Material3 1.5.6，几何与自适应字号逻辑保持同一规格（2026-11-07）。
 */
private const val INSCRIBED_SQUARE_RATIO = 0.7071f
private const val TRIPLE_ROW_HEIGHT_SCALE = 7f / 6f

/** 一个数据格：上为数值、下为标签，字号各自按可用空间自适应。 */
@Composable
fun StatCell(
    value: String,
    valueColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    labelColor: Color = Color.Gray
) {
    Column(modifier = modifier) {
        AutoSizeText(
            text = value,
            color = valueColor,
            maxFontSp = 46f,
            modifier = Modifier.fillMaxWidth().weight(4f)
        )
        AutoSizeText(
            text = label,
            color = labelColor,
            maxFontSp = 11f,
            modifier = Modifier.fillMaxWidth().weight(3f)
        )
    }
}

@Composable
private fun AutoSizeText(
    text: String,
    color: Color,
    maxFontSp: Float,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val availW = with(density) { maxWidth.toPx() }
        val availH = with(density) { maxHeight.toPx() }
        val fontSize = remember(text, availW, availH, maxFontSp) {
            if (availW <= 0f || availH <= 0f) {
                maxFontSp.sp
            } else {
                val probe = TextStyle(fontSize = 100.sp)
                val result = measurer.measure(text = text, style = probe, maxLines = 1)
                val needH = result.size.height.toFloat()
                val needW = result.size.width.toFloat()
                val byH = if (needH > 0f) availH / needH * 100f else maxFontSp
                val byW = if (needW > 0f) availW * 0.96f / needW * 100f else maxFontSp
                val raw = min(maxFontSp, min(byH, byW))
                ((raw * 2f).toInt() / 2f).coerceAtLeast(6f).sp
            }
        }
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun BroadcastStatsLayout(
    top: @Composable () -> Unit,
    middle: @Composable RowScope.() -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    // 尺寸完全由调用方给定：微光态传 fillMaxSize()，交互态传 weight(1f) 给底部按钮让位
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth, maxHeight) * INSCRIBED_SQUARE_RATIO
        Column(modifier = Modifier.fillMaxWidth().height(side * TRIPLE_ROW_HEIGHT_SCALE)) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.width(side).fillMaxHeight()) { top() }
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), content = middle)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.width(side).fillMaxHeight()) { bottom() }
            }
        }
    }
}
