# Wear OS 心率广播应用

将 Wear OS 手表模拟为标准蓝牙心率传感器，通过 BLE 广播心率和电量数据。

## 功能

- ❤️ 通过 Health Services API 实时读取心率
- 🔋 读取设备电量
- 📡 通过 BLE GATT Server 广播标准心率数据
- 🔔 前台服务持续运行

## 权限

应用启动后自动请求以下权限：
- `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_SCAN` — 蓝牙操作
- `POST_NOTIFICATIONS` — 前台服务通知
- `ACCESS_FINE_LOCATION` — BLE 扫描需要

## 工作原理

1. 用户点击「开始」按钮
2. 启动前台服务，通过 Health Services API 订阅心率数据
3. 创建 BLE GATT Server，注册标准心率服务（0x180D）和电池服务（0x180F）
4. 开始 BLE 广播，其他设备（手机/电脑）可搜索到心率传感器并连接读取数据
5. 心率数据变化时实时通知已连接设备

## BLE 服务结构

| 服务 | UUID | 特征值 | UUID |
|------|------|--------|------|
| Heart Rate Service | 0x180D | Heart Rate Measurement | 0x2A37 |
| Battery Service | 0x180F | Battery Level | 0x2A19 |

## 构建

1. 用 Android Studio 打开项目
2. 连接 Wear OS 设备或启动模拟器
3. 运行应用

## 系统要求

- Wear OS 4+ (API 33+)
- 支持 BLE 的手表设备
- Health Services 支持心率监测
