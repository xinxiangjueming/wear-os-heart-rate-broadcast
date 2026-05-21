package com.heartrate.broadcast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.heartrate.broadcast.ui.MainScreen
import com.heartrate.broadcast.ui.PermissionScreen
import com.heartrate.broadcast.theme.WearOSHeartRateTheme

class MainActivity : ComponentActivity() {

    // 拦截所有返回手势，禁止滑动返回
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // 不做任何事，吞掉返回事件
        }
    }

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

        setContent {
            var permissionsGranted by remember {
                mutableStateOf(areAllPermissionsGranted())
            }

            WearOSHeartRateTheme {
                if (permissionsGranted) {
                    MainScreen()
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

    private fun areAllPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
