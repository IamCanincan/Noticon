# Noticon

一个纯后台的 Xposed 模块：**把未适配主题的通知小图标换成 App 在桌面上那个图标，并保住原本的彩色**；已经适配（单色/灰度）的图标保持原样不动。

Android 12 起系统会强行把通知小图标统一着色，没做单色适配的 App 会显示成一坨认不出是谁的灰白色块。Noticon 就是修这个的。

## 它改的是哪个图标

通知里其实有两个图标，Noticon 只动其中一个：

| 图标 | 出现在哪 | Noticon 动不动 |
|---|---|---|
| `smallIcon`（小图标） | 通知标题左侧、锁屏、heads-up | **动**，这就是被染色成一坨的那个 |
| 状态栏图标 | 屏幕顶部那一行 | 不动，AOSP 设计上它就该是单色的 |

所以模块生效后，最直观的变化在**通知内容区那个小图标**：从灰白色块变回能认出是哪个 App 的彩色图标。

## 特点

- **无桌面图标、无应用界面**：装上后不会在桌面出现入口，也不需要任何配置，行为开箱即用。
- **只动没适配的**：用和系统同款的灰度判定检测图标是否已做单色适配，已适配的直接跳过 —— 免得把人家本来正确的图标改坏。
- **保留彩色**：替换后的图标按 App 原色显示，不会被状态栏再次统一着色。
- **尺寸填满**：自适应图标光栅化后内容只占画布中间约 61%，四周是空的。模块会先裁掉空白再放大填满，否则缩到通知里那点尺寸会又小又糊。
- 仅注入 `com.android.systemui`，不碰其他进程。

## 怎么确认生效

日志里出现 `patched <包名> 2->1` 就是替换成功了（图标类型从「资源」变成「位图」）：

```bash
adb logcat -s Noticon -d | grep patched
```

注意：**Android 12+ 的通知面板本身就把未适配图标显示成彩色**，所以开关模块在面板里看着可能没差别。想做 A/B 对比，看的是通知内容区那个小图标，而不是屏幕顶部那一行。

## 安装

1. 安装 `Noticon-v1.0.2-release.apk`（或用 `./gradlew assembleDebug` 自行构建）。
2. 在 **LSPosed / Vector**（或其他支持 LibXposed API 102 的框架）中启用 Noticon。
3. **不用勾作用域**：模块通过 `staticScope=true` + `META-INF/xposed/scope.list` 把
   作用域写死成 `com.android.systemui`，管理器里只会列出系统界面这一项，也不会让你勾到别的应用。
4. 重启设备（或只重启 SystemUI）生效。

### 日志排查

嫌一步步敲命令麻烦，可以在 Git Bash 里直接跑（会自动装 APK、提示手动启用模块、重启 SystemUI、把日志存成 `noticon.log`）：

```bash
./scripts/check-device.sh
```

手动做的话：

```bash
adb install -r Noticon-v1.0.2-release.apk
# 在框架里启用 Noticon（作用域已固定为系统界面，不用勾）
adb logcat -c
adb shell am force-stop com.android.systemui   # 或 adb reboot
adb logcat -s Noticon -d
```

两点说明：

- 重启 SystemUI 要用 `am force-stop`，**不要**用 `kill $(pidof com.android.systemui)` ——
  shell 用户没有给 SystemUI 发信号的权限，会报 `Operation not permitted`。
- 若 `adb devices` 一直起不来（Windows 上默认端口 5037 可能落在系统保留端口段内），
  给所有 adb 命令加上 `-P 5039`，例如 `adb -P 5039 logcat -s Noticon -d`。

正常挂载后日志大致是这样：

```
device sdk=37 (Android 17)
all target classes resolved
attaching to SystemUI (api=102, framework=...)
inflateViews hooked
setIcon hooked
updateIconColor hooked
processSmallIconColor hooked
```

- 出现 **`missing class: ...`** → 说明该 SystemUI 类在当前系统上改名或换包了，把这一行发出来即可定位改哪个挂钩点。
- 只有部分 `hooked` → 没挂上的那段功能会缺失（典型表现：图标换成了，但被状态栏染成灰白）。
- 一行 `hooked` 都没有 → 先确认模块已在框架里**启用**，且框架支持 LibXposed API 102（作用域不用管，已写死）。

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

行为调整直接改 `ModuleOptions` 的默认值即可，不需要额外配置渠道。其中 `replacement` 决定换成什么，内置三种：

| 值 | 常量 | 行为 |
|---|---|---|
| 0 | `USE_LAUNCHER_ICON` | 换成彩色桌面图标（**默认**） |
| 1 | `FORCE_MONOCHROME` | 把应用自己给的那个小图标就地压成黑白剪影，由系统按主题着色 |
| 2 | `LAUNCHER_ICON_MONOCHROME` | 取桌面图标的轮廓压成灰度，去色但保留明暗 |

换策略时记得同步改 `keepOriginalColor`：交出去的是彩色就开保色，交出去的是单色就关掉、让系统按主题上色。

## 许可

[MIT](LICENSE)。

## 签名密钥

`noticon-release.jks` 是本模块的自签名密钥（密码均为 `noticon`），已被 `.gitignore` 排除、不会入库。
**请自行备份**——后续更新必须用同一密钥签名，否则设备会拒绝覆盖安装。
