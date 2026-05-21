# Wear OS 心率广播应用 / Wear OS Heart Rate Broadcast App

将 Wear OS 手表模拟为标准蓝牙心率传感器，通过 BLE 广播心率和电量数据。
Turn your Wear OS watch into a standard Bluetooth heart rate sensor, broadcasting heart rate and battery data via BLE.

## 功能 / Features

- ❤️ 通过 Health Services API 实时读取心率 / Real-time heart rate reading via Health Services API
- 🔋 读取设备电量 / Read device battery level
- 📡 通过 BLE GATT Server 广播标准心率数据 / Broadcast standard heart rate data via BLE GATT Server
- 🔔 前台服务持续运行 / Foreground service for continuous operation

## 权限 / Permissions

应用启动后自动请求以下权限：
The app automatically requests the following permissions on launch:
- `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_SCAN` — 蓝牙操作 / Bluetooth operations
- `POST_NOTIFICATIONS` — 前台服务通知 / Foreground service notification
- `ACCESS_FINE_LOCATION` — BLE 扫描需要 / Required for BLE scanning

## 工作原理 / How It Works

1. 用户点击「开始」按钮 / User taps the "Start" button
2. 启动前台服务，通过 Health Services API 订阅心率数据 / Foreground service starts, subscribes to heart rate data via Health Services API
3. 创建 BLE GATT Server，注册标准心率服务（0x180D）和电池服务（0x180F） / Create BLE GATT Server, register standard Heart Rate Service (0x180D) and Battery Service (0x180F)
4. 开始 BLE 广播，其他设备（手机/电脑）可搜索到心率传感器并连接读取数据 / Start BLE advertising; other devices (phones/PCs) can discover the heart rate sensor and connect to read data
5. 心率数据变化时实时通知已连接设备 / Notify connected devices in real-time when heart rate data changes

## BLE 服务结构 / BLE Service Structure

| 服务 / Service | UUID | 特征值 / Characteristic | UUID |
|------|------|--------|------|
| Heart Rate Service | 0x180D | Heart Rate Measurement | 0x2A37 |
| Battery Service | 0x180F | Battery Level | 0x2A19 |

## 构建 / Build

1. 用 Android Studio 打开项目 / Open the project in Android Studio
2. 连接 Wear OS 设备或启动模拟器 / Connect a Wear OS device or start an emulator
3. 运行应用 / Run the app

## 系统要求 / Requirements

- Wear OS 4+ (API 33+)
- 支持 BLE 的手表设备 / Watch with BLE support
- Health Services 支持心率监测 / Health Services with heart rate monitoring support
