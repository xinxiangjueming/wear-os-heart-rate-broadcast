# ProGuard rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Health Services
-keep class androidx.health.services.client.** { *; }

# BLE / Bluetooth
-keep class android.bluetooth.** { *; }
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }
-keep class * extends android.bluetooth.BluetoothGattServerCallback { *; }

# Coroutines
-keepnames class kotlinx.coroutines.** { *; }
