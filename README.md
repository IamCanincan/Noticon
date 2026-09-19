# Noticon

一个纯后台的 Xposed 模块：**把未适配主题的通知小图标替换成 App 自己的图标**，已经适配（单色/灰度）的图标保持原样不动。

Android 12 起系统会强行把通知小图标统一着色，没做单色适配的 App 会显示成一坨灰白的色块。Noticon 就是修这个的。

## 特点

- **无桌面图标、无应用界面**：装上后不会在桌面出现入口，也不需要任何配置，行为开箱即用。
- **只动没适配的**：用和系统同款的灰度判定检测图标是否已做单色适配，已适配的直接跳过。
- **保留彩色**：替换后的图标按 App 原色显示，不会被状态栏再次统一着色。
- 仅注入 `com.android.systemui`，不碰其他进程。

## 安装

1. 安装 `Noticon-v1.0-release.apk`（或用 `./gradlew assembleDebug` 自行构建）。
2. 在 **LSPosed / Vector**（或其他支持 LibXposed API 102 的框架）中启用 Noticon。
3. 作用域勾选 **系统界面（SystemUI / com.android.systemui）**。
4. 重启设备生效。

日志排查：`adb logcat -s Noticon`，正常会看到 `inflateViews hooked` 等字样。

## 自行构建

```bash
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 正式包（未签名，需自行签名）
```

构建前把 `local.properties` 里的 `sdk.dir` 指向本机 Android SDK 路径。

- **JDK 17 及以上即可**（已实测 JDK 17 与 JDK 25 都能构建通过），不依赖 Android Studio 自带 JDK。
- `assembleRelease` 产出的是**未签名**包，安装前需要对齐并签名：

```bash
$BT=~/AppData/Local/Android/Sdk/build-tools/36.0.0
"$BT/zipalign.exe" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk _aligned.apk
java -jar "$BT/lib/apksigner.jar" sign --ks noticon-release.jks --ks-key-alias noticon \
     --ks-pass pass:noticon --key-pass pass:noticon --out Noticon-release.apk _aligned.apk
```

> 说明：仓库的仓库源首位配了阿里云镜像。若你的网络可以直接访问 `dl.google.com`，
> 可以把 `settings.gradle.kts` 里的镜像地址换回 `google()`。
> 同理，Gradle 分发地址用的是腾讯云镜像，网络正常时可换回 `services.gradle.org`。

## 技术要点

- 模块 API：**LibXposed API 102**（`io.github.libxposed:api:102.0.0`，编译期依赖），入口 `com.iamcanincan.noticon.entry.NoticonModule`。
- 挂钩点：`NotificationRowBinderImpl#inflateViews`（替换图标）、`IconManager#setIcon`、
  `StatusBarIconView#updateIconColor`、`Notification.Builder#processSmallIconColor`（保色）。
- 全部通过反射定位，找不到就跳过并打日志，因此不同 Android 版本间不会崩。
- 目标 Android 17（API 37），最低支持 Android 8.0（API 26）。

## 代码结构

```
com.iamcanincan.noticon
├── entry/NoticonModule            模块入口，只在 SystemUI 进程里挂载
├── engine/SystemUiHooks           挂钩的安装与拦截逻辑
├── engine/NotificationIconPatch   是否需要替换、换成什么的判定
├── engine/ModuleRuntime           进程内共享的 Context 与选项
├── model/ModuleOptions            行为开关（无界面，默认值即最终行为）
├── graphics/IconBitmap            位图的取、裁、合成
├── graphics/ToneCheck             单色（已适配）判定
└── util/MemberLookup              反射取成员的薄封装
```

行为调整直接改 `ModuleOptions` 的默认值即可，不需要额外配置渠道。

## 签名密钥

`noticon-release.jks` 是本模块的自签名密钥（密码均为 `noticon`），已被 `.gitignore` 排除、不会入库。
**请自行备份**——后续更新必须用同一密钥签名，否则设备会拒绝覆盖安装。
