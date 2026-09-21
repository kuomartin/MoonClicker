package com.xaxaxax.moonclicker.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 扇形選單的一顆動作按鈕。 */
data class FanMenuAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

private val TriggerSize = 32.dp
private val TriggerIconSize = 32.dp
private val ActionSize = 32.dp
private val FanRadius = 72.dp

/**
 * 觸發鈕吸附在螢幕左右任一側；點擊展開扇形選單，動作按鈕往螢幕中央那一象限（水平＋垂直都是）
 * 展開，保證不會被展到螢幕外。展開/收合、拖曳吸附全部是元件內部狀態，呼叫端只給動作清單。
 *
 * 位置記的是「吸附哪一側」＋「track 上的相對比例」，不是絕對像素：容器尺寸變了（旋轉、
 * 分割畫面……）位置由這兩個值重新算出來，不需要另外判斷「現在是不是橫向」。拖曳中的即時
 * 位置是同步的 [dragOffset]，不透過協程；[settledOffset] 只負責放開手指後的吸附動畫跟
 * 容器尺寸變化時的重新定位，兩者不會搶著寫同一個值。
 */
@Composable
fun FanFabMenu(
    triggerIcon: @Composable (Modifier)->Unit,
    actions: List<FanMenuAction>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val triggerPx = with(density) { TriggerSize.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val scope = rememberCoroutineScope()

        // 扇形方向只看「放開手指後」落定的這一份狀態，拖曳過程中不會跟著手指即時轉向。
        var snappedRight by remember { mutableStateOf(true) }
        var yFraction by remember { mutableFloatStateOf(0.5f) }

        fun trackHeightPx() = (maxHeightPx - triggerPx).coerceAtLeast(0f)
        fun targetOffset() = Offset(
            if (snappedRight) maxWidthPx - triggerPx else 0f,
            yFraction * trackHeightPx(),
        )

        val settledOffset = remember { Animatable(targetOffset(), Offset.VectorConverter) }
        var dragOffset by remember { mutableStateOf<Offset?>(null) }
        val renderOffset = dragOffset ?: settledOffset.value

        // 容器尺寸一變（旋轉、分割畫面）就照目前記住的「哪一側／比例」重新落點；
        // 拖曳中不動 settledOffset，畫面此時看的是 dragOffset。
        LaunchedEffect(maxWidthPx, maxHeightPx) {
            if (dragOffset == null) settledOffset.snapTo(targetOffset())
        }

        var expanded by remember { mutableStateOf(false) }
        val expandAnim = remember { Animatable(0f) }

        fun setExpanded(value: Boolean) {
            expanded = value
            scope.launch { expandAnim.animateTo(if (value) 1f else 0f, tween(200)) }
        }

        // 往螢幕中央那一象限展開：水平看吸在哪一側，垂直看觸發鈕落定在螢幕上半還是下半——
        // 純粹由落定位置算出來，不是寫死「橫向/直向」的分支；四個象限的 index 0 一律對應扇形
        // 裡最靠上的那顆按鈕，左右兩側因此讀起來都是「由上至下」，即使實際掃過的方向一個順
        // 時針一個逆時針。
        val inUpperHalf = yFraction < 0.5f
        val arcStartDeg = when {
            snappedRight && !inUpperHalf -> 90f   // 右下角：上→左，index 0 在上
            snappedRight && inUpperHalf -> 180f   // 右上角：左→下，index 0 在上
            !snappedRight && inUpperHalf -> 360f  // 左上角：右→下，index 0 在上
            else -> 90f                            // 左下角：上→右，index 0 在上
        }
        val arcEndDeg = when {
            snappedRight && !inUpperHalf -> 180f
            snappedRight && inUpperHalf -> 270f
            !snappedRight && inUpperHalf -> 270f
            else -> 0f
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(renderOffset.x.roundToInt(), renderOffset.y.roundToInt()) }
        ) {
            val radiusPx = with(density) { FanRadius.toPx() }
            val actionPx = with(density) { ActionSize.toPx() }
            actions.forEachIndexed { index, action ->
                val t = if (actions.size == 1) 0.5f else index.toFloat() / (actions.size - 1)
                val angleDeg = arcStartDeg + (arcEndDeg - arcStartDeg) * t
                val angleRad = angleDeg * PI.toFloat() / 180f
                val progress = expandAnim.value
                val dx = radiusPx * cos(angleRad) * progress
                val dy = -radiusPx * sin(angleRad) * progress
                if (progress > 0f) {
                    SmallFloatingActionButton(
                        onClick = {
                            setExpanded(false)
                            action.onClick()
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (triggerPx / 2f - actionPx / 2f + dx).roundToInt(),
                                    (triggerPx / 2f - actionPx / 2f + dy).roundToInt(),
                                )
                            }
                            .size(ActionSize),
                    ) {
                        Icon(action.icon, contentDescription = action.label)
                    }
                }
            }

            FloatingActionButton(
                onClick = { setExpanded(!expanded) },
                shape = CircleShape,
                containerColor = Color.Transparent,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                ),
                modifier = Modifier
                    .size(TriggerSize)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragOffset = settledOffset.value },
                            onDragEnd = {
                                val final = dragOffset ?: settledOffset.value
                                dragOffset = null
                                snappedRight = final.x + triggerPx / 2f > maxWidthPx / 2f
                                yFraction = (final.y / trackHeightPx().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                scope.launch {
                                    settledOffset.snapTo(final)
                                    settledOffset.animateTo(targetOffset(), tween(200))
                                }
                            },
                            onDragCancel = { dragOffset = null },
                        ) { change, drag ->
                            change.consume()
                            val current = dragOffset ?: settledOffset.value
                            dragOffset = Offset(
                                (current.x + drag.x).coerceIn(0f, maxWidthPx - triggerPx),
                                (current.y + drag.y).coerceIn(0f, maxHeightPx - triggerPx),
                            )
                        }
                    },
            ) {
                triggerIcon(Modifier.size(TriggerIconSize))
            }
        }
    }
}
