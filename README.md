# Quota Desk Android

Quota Desk 的 Android 配套应用。应用负责扫码、保存配对的电脑，并通过 WebView 打开电脑端提供的远程查看页面。额度查询和网页界面由电脑端 Quota Desk 提供。

## 使用要求

- 电脑端运行 Quota Desk，并在「设置 → 远程查看」中启用局域网访问。
- 手机和电脑之间需要能通过局域网、VPN 或内网穿透互相访问。
- 在电脑端打开「配对信息」，用此应用扫描二维码完成配对。

## 构建

需要 JDK 17 或兼容版本、Android SDK API 35。

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 版本

- 首个独立应用版本：`0.0.1`。
- Android `versionCode` 从 53 开始，以支持从此前发出的 versionCode 52 测试包直接升级；之后每次发布递增。
- Android 版本号与电脑版版本号独立维护。

## 许可

本项目依据 MIT License 发布。

