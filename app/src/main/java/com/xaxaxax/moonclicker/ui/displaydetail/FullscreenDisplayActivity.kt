package com.xaxaxax.moonclicker.ui.displaydetail

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.core.AppLocale
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.ui.component.FanFabMenu
import com.xaxaxax.moonclicker.ui.component.FanMenuAction
import com.xaxaxax.moonclicker.ui.theme.MoonClickerTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class FullscreenDisplayActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.attachBaseContext(newBase)
        } else {
            super.attachBaseContext(AppLocale.wrap(newBase, AppSettings.readAppLanguageTag(newBase)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("displayId", -1)
        if (displayId == -1) {
            Timber.e("No displayId provided to FullscreenDisplayActivity")
            finish()
            return
        }

        enableEdgeToEdge()

        // ADR-0017：MainDisplay 自己轉不該讓畫面截圖做動畫。seamless 是
        // hint，系統仍會依八項前提自行退回 CROSSFADE/ROTATE，這裡不用寫 fallback。
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            MoonClickerTheme {
                FullscreenDisplayScreen(displayId)
            }
        }
    }
}

data class AppEntry(val packageName: String, val label: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenDisplayScreen(
    targetDisplayId: Int,
    viewModel: FullscreenDisplayViewModel = hiltViewModel()
) {
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsState()
    val service by viewModel.service.collectAsState()
    val surfaceViewRef = remember { mutableStateOf<SurfaceView?>(null) }

    LaunchedEffect(Unit) {
        viewModel.finishEvents.collect { activity?.finish() }
    }
    // 單一來源：鏡像的 Viewport 讀這一份，決定 letterbox 與內容尺寸。
    val geometry = rememberDisplayGeometry(targetDisplayId)

    // issue #41：退出畫面（返回鍵、Home、或畫面上的 Exit 按鈕，onPause 一律涵蓋）時留一張
    // 縮圖給 Displays 列表用；只在真的有鏡像畫面時才有東西可擷取。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, targetDisplayId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                surfaceViewRef.value?.let { surfaceView ->
                    val bitmap = Bitmap.createBitmap(
                        surfaceView.width.coerceAtLeast(1),
                        surfaceView.height.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    PixelCopy.request(
                        surfaceView,
                        bitmap,
                        { result ->
                            if (result == PixelCopy.SUCCESS) {
                                viewModel.captureThumbnail(targetDisplayId, bitmap)
                            } else {
                                Timber.e("PixelCopy failed: result=%d", result)
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ADR-0017：鏡像跟著這個 Activity 的視窗自然旋轉，不釘住面板；VD 怎麼轉是它自己的事，
    // 兩者互不牽制，這個 activity 也不把 VD 的方向鎖進 requestedOrientation。

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (service != null) {
            VirtualDisplayMirror(
                targetDisplayId = targetDisplayId,
                geometry = geometry,
                addSurface = { viewModel.addSurface(targetDisplayId, it) },
                removeSurface = { viewModel.removeSurface(targetDisplayId, it) },
                service = service!!,
                modifier = Modifier.fillMaxSize(),
                onSurfaceViewCreated = { surfaceViewRef.value = it },
            )
        }

        val startAppLabel = stringResource(R.string.fullscreen_menu_start_app)
        val closeDisplayLabel = stringResource(R.string.fullscreen_menu_close_display)
        val powerOffLabel = stringResource(R.string.fullscreen_menu_power_off)
        val exitLabel = stringResource(R.string.fullscreen_exit)
        val homeLabel = stringResource(R.string.fullscreen_menu_home)
        val fanActions = buildList {
            add(FanMenuAction(Icons.Default.Apps, startAppLabel) { viewModel.onAction(FullscreenAction.StartApp, targetDisplayId) })
            if (targetDisplayId != 0) {
                add(FanMenuAction(Icons.Default.Close, closeDisplayLabel) { viewModel.onAction(FullscreenAction.CloseDisplay, targetDisplayId) })
                add(FanMenuAction(Icons.Default.PowerSettingsNew, powerOffLabel) { viewModel.onAction(FullscreenAction.PowerOff, targetDisplayId) })
            }
            add(FanMenuAction(Icons.Default.Logout, exitLabel) { viewModel.onAction(FullscreenAction.Exit, targetDisplayId) })
            add(FanMenuAction(Icons.Default.Home, homeLabel) { viewModel.onAction(FullscreenAction.Home, targetDisplayId) })
        }
        FanFabMenu(
            triggerIcon = { modifier ->
                AsyncImage(
                    model = R.mipmap.ic_launcher,
                    contentDescription = null,
                    modifier = modifier
                )
            },
            actions = fanActions,
            modifier = Modifier.fillMaxSize(),
        )

        if (uiState.showAppList) {
            val pinnedApps by viewModel.pinnedApps.collectAsState()
            var query by remember { mutableStateOf("") }
            val visibleApps = remember(uiState.apps, pinnedApps, query) {
                uiState.apps
                    .filter { it.label.contains(query, ignoreCase = true) }
                    .sortedWith(compareByDescending<AppEntry> { it.packageName in pinnedApps }.thenBy { it.label })
            }
            AlertDialog(
                onDismissRequest = { viewModel.closeAppList() },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.fullscreen_select_app_title))
                        IconButton(onClick = { viewModel.refreshAppList() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
                        }
                    }
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.common_search)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(visibleApps, key = { it.packageName }) { app ->
                                val pinned = app.packageName in pinnedApps
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { viewModel.launchApp(app.packageName, targetDisplayId) },
                                            onLongClick = { viewModel.togglePinned(app.packageName) },
                                        )
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (pinned) {
                                        Icon(
                                            Icons.Default.PushPin,
                                            contentDescription = stringResource(R.string.common_pinned),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                    }
                                    Text(app.label, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.closeAppList() }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

    }
}
