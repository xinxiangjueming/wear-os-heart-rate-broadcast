package com.heartrate.broadcast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.AppScaffold
import com.heartrate.broadcast.theme.WearOSHeartRateTheme
import com.heartrate.broadcast.ui.MainScreen
import com.heartrate.broadcast.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HrBroadcast"
    }

    // 拦截所有返回手势，禁止滑动返回
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // 不做任何事，吞掉返回事件
        }
    }

    /** 微光态由 AmbientLifecycleObserver 驱动，交给 MainScreen 切换配色/隐藏按钮 */
    private val ambientState = mutableStateOf(false)
    private var ambientObserver: AmbientLifecycleObserver? = null

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        permissionCallback?.invoke(allGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注册拦截返回手势的回调
        onBackPressedDispatcher.addCallback(this, backCallback)

        // 不支持微光的手表在 Ambient 注册/生命周期分发阶段会抛异常，先装安全兜底，
        // 再做共享库探测 + try/catch 注册（照搬 sportlinkwear 的 SportActivity 口径）
        installSafeLifecycleCallback()
        setupAmbientObserver()

        setContent {
            var permissionsGranted by remember { mutableStateOf(areAllPermissionsGranted()) }
            val inAmbient by ambientState

            WearOSHeartRateTheme {
                // Wear M3：AppScaffold 提供 ScaffoldState，两个页面各自用 ScreenScaffold 承载。
                // 顶层时间文本置空，由子页按需显式给 TimeText（保持原 PermissionScreen 才有时间文本的口径）。
                AppScaffold(timeText = {}) {
                    if (permissionsGranted) {
                        MainScreen(inAmbient = inAmbient)
                    } else {
                        PermissionScreen(
                            onRequestPermissions = {
                                permissionLauncher.launch(requiredPermissions)
                                permissionCallback = { granted ->
                                    permissionsGranted = granted
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * 设备是否有 Google Wear OS 共享库（决定 Ambient 微光是否可用）。
     *
     * 与 `androidx.wear.ambient.SharedLibraryVersion` 内部判断方式一致：运行时类加载检查
     * `WearableActivityController`。普通手机 / 部分安卓表没有该共享库，此时**必须跳过**
     * [AmbientLifecycleObserver] 初始化，否则会在 Activity 启动的生命周期分发阶段抛
     * `IllegalStateException`（Could not find wearable shared library classes）导致崩溃。
     */
    private fun hasGoogleWearableLibrary(): Boolean = try {
        Class.forName("com.google.android.wearable.compat.WearableActivityController")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    private fun setupAmbientObserver() {
        if (!hasGoogleWearableLibrary()) {
            Log.w(TAG, "wearable 共享库缺失，跳过 AmbientLifecycleObserver（避免生命周期分发崩溃）")
            return
        }
        try {
            val observer = AmbientLifecycleObserver(
                this,
                object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                    override fun onEnterAmbient(details: AmbientLifecycleObserver.AmbientDetails) {
                        // 微光期间必须清掉常亮 flag，否则系统下一次超时不会再进微光
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        window.decorView.keepScreenOn = false
                        ambientState.value = true
                    }

                    override fun onExitAmbient() {
                        ambientState.value = false
                    }
                }
            )
            ambientObserver = observer
            lifecycle.addObserver(observer)
            Log.i(TAG, "AmbientLifecycleObserver 注册成功（共享库路径可用）")
        } catch (e: Exception) {
            Log.e(TAG, "AmbientLifecycleObserver failed: ${e.message}")
        }
    }

    /**
     * 全局兜底：吞掉 Wear OS 生命周期分发相关的异常，避免无微光支持的设备直接崩回表盘。
     * 其它异常原样交回系统默认处理器。
     */
    private fun installSafeLifecycleCallback() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (throwable?.stackTraceToString()?.contains("Wear") == true ||
                throwable?.message?.contains("ActivityLifecycleCallbacks") == true
            ) {
                Log.w(TAG, "Wear OS lifecycle error caught: ${throwable.message}")
                Thread.setDefaultUncaughtExceptionHandler(prev)
            } else {
                prev?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
