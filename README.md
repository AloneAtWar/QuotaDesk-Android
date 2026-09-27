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
- Android `versionCode` 由发布 tag 推导，`v0.0.1` 对应 53，之后按语义版本递增。
- Android 版本号与电脑版版本号独立维护。

## 自动发布

向 GitHub 推送 `vMAJOR.MINOR.PATCH` 格式的 tag（例如 `v0.0.1`），GitHub Actions 会从 `CHANGELOG.md` 提取对应版本的更新记录，构建并签名 Release APK，随后创建 GitHub Release 并上传 APK 与 SHA-256 校验文件。每次发布前先在 `CHANGELOG.md` 顶部添加与 tag 版本一致的条目。APK 文件名为 `Quota-Desk-Android-版本号.apk`。

仓库的 **Settings → Secrets and variables → Actions** 需要配置以下 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 key 的 alias |
| `ANDROID_KEY_PASSWORD` | 签名 key 的密码 |

发布 keystore 必须保存在仓库之外并妥善备份。所有正式版本都必须使用同一把 key 签名，否则 Android 无法覆盖安装升级。版本号格式为 `vMAJOR.MINOR.PATCH`；`versionCode` 计算为 `52 + MAJOR × 1,000,000 + MINOR × 1,000 + PATCH`，MINOR 与 PATCH 不得超过 999。

## 许可

本项目依据 MIT License 发布。

