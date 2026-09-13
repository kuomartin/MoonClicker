package com.xaxaxax.relc.ui.displaydetail

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber
import kotlin.math.abs

/**
 * 方向鏈 `Y → VD → FullscreenDisplayActivity → MainDisplay` 中需要我們自己寫的兩環
 * （見 issue #17）。
 *
 * 其餘兩環由系統免費提供：WindowManager 仲裁 Y 宣告的方向是否壓過我們設定的 rotation，
 * 以及 MainDisplay 跟隨 `FullscreenDisplayActivity` 的 requestedOrientation 旋轉。
 */

/**
 * 環節一：感測器 → 虛擬顯示的 user rotation。
 *
 * 來源必須是原始感測器：`FullscreenDisplayActivity` 一旦被 [Activity.setRequestedOrientation]
 * 鎖住，display 0 就不再跟著感測器轉，靠 configuration 或 display 0 的 rotation 當來源
 * 會形成死結。
 */
@Composable
fun SensorRotationDriver(
    displayRotation: Int,
    onRotationChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val currentCallback by rememberUpdatedState(onRotationChanged)
    // 進入時顯示的方向。只有當我們真的推送過方向時才需要還原——否則離開時的還原會把
    // 原本未鎖定（free）的顯示鎖成 lock，那是另一種副作用外洩。
    val entryRotation = remember { displayRotation }
    val pushedRotation = remember { mutableStateOf(false) }

    DisposableEffect(context) {
        // 以顯示當前的方向為起點，而不是 ROTATION_0：進入一個已經橫向的顯示時，
        // 第一筆相符的感測器取樣不該被當成「沒有變化」而吞掉。
        var lastRotation = entryRotation
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                // 使用者關閉自動旋轉時整條鏈停擺——這讓兩個分支都等於「直接在手機上跑 Y」：
                // UNSPECIFIED 的 app 不轉，而宣告方向的 app 仍由 WM 仲裁後照樣轉。
                if (!isAutoRotateEnabled(context)) return

                val next = quantizeOrientation(orientation, lastRotation)
                if (next == lastRotation) return
                lastRotation = next
                pushedRotation.value = true
                currentCallback(next)
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
        } else {
            Timber.w("Device cannot detect orientation; the rotation chain stays idle")
        }
        onDispose {
            listener.disable()
            if (pushedRotation.value) currentCallback(entryRotation)
        }
    }
}

/**
 * 環節二：虛擬顯示的 rotation → `FullscreenDisplayActivity` 的 requestedOrientation。
 *
 * 對 targetSdk ≥ 36 的 app，系統在 sw ≥ 600dp 的裝置上會忽略這個呼叫，OEM 亦可停用。
 * 那時 activity 不會轉，畫面退回幾何層——letterbox 正確，只是不填滿。
 */
@Composable
fun FollowDisplayRotation(activity: Activity?, rotation: Int) {
    LaunchedEffect(activity, rotation) {
        activity ?: return@LaunchedEffect
        activity.requestedOrientation = requestedOrientationFor(rotation)
    }
}

internal fun isAutoRotateEnabled(context: android.content.Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

/**
 * 感測器角度（0..359，順時針）→ `Surface.ROTATION_*`，帶遲滯。
 *
 * [OrientationEventListener.ORIENTATION_UNKNOWN] 必須維持現值：手機平放在桌上就會回報
 * UNKNOWN，把它當成 0 會在使用者沒有轉動時亂轉。
 */
internal fun quantizeOrientation(degrees: Int, current: Int): Int {
    if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return current

    val candidate = when {
        degrees >= 315 || degrees < 45 -> Surface.ROTATION_0
        degrees < 135 -> Surface.ROTATION_270
        degrees < 225 -> Surface.ROTATION_180
        else -> Surface.ROTATION_90
    }
    if (candidate == current) return current

    // 遲滯：必須越過象限邊界 BOUNDARY_MARGIN_DEGREES 度才切換，避免邊界來回抖動——
    // 每次抖動都是一次跨進程 AIDL 加一次 WindowManager 的顯示旋轉。
    val center = when (candidate) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_270 -> 90
        Surface.ROTATION_180 -> 180
        else -> 270
    }
    val delta = abs(((degrees - center + 540) % 360) - 180)
    return if (delta <= QUADRANT_HALF_WIDTH_DEGREES - BOUNDARY_MARGIN_DEGREES) candidate else current
}

/** 象限半寬：邊界距離象限中心 45 度。 */
private const val QUADRANT_HALF_WIDTH_DEGREES = 45

/** 必須越過象限邊界幾度才採用新方向。 */
private const val BOUNDARY_MARGIN_DEGREES = 30

internal fun requestedOrientationFor(rotation: Int): Int = when (rotation and 3) {
    Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
