# Noticon

一个 Xposed 模块：**把未适配主题的通知小图标换掉**，已经适配（单色/灰度）的图标保持原样不动。

Android 12 起系统会强行把通知小图标统一着色，没做单色适配的 App 会显示成一坨认不出是谁的灰白色块。Noticon 就是修这个的。

## 两种模式

装上后桌面上会有 Noticon 的图标，打开即可切换模式，**改完不需要重启**：新收到的通知立刻按新模式显示（已经在通知栏里的那条要等它重新加载）。

| 模式 | 做法 | 适合 |
|---|---|---|
| **彩色桌面图标**（默认） | 换成 App 在桌面上那个图标，颜色原样保留 | 想让通知一眼认出是谁 |
| **系统黑白通知** | 把 App 自己给的小图标压成单色剪影，交给系统按主题着色 | 想和其它通知风格统一 |

两个模式的区别只在"未适配的图标怎么处理"；已经是单色/灰度的图标，两种模式下都不动。

## 它改的是哪个图标

模块改的是通知对象里的小图标字段（`Notification.smallIcon`）—— 就是被系统染色成一坨的那个。它是通知内容区图标的来源，也是状态栏图标的来源，所以**不要在状态栏那行上找差异**：真正直观的变化在通知内容区那个小图标，从灰白色块变回能认出是哪个 App 的样子。

配套还会阻止系统把替换后的图标重新染回单色（挂钩 `IconManager#setIcon`、`StatusBarIconView#updateIconColor`、`Notification.Builder#processSmallIconColor`）。

## 特点

- **只动没适配的**：用和系统同款的灰度判定检测图标是否已做单色适配，已适配的直接跳过 —— 免得把人家本来正确的图标改坏。
- **彩色模式保留原色**：替换后的图标按 App 原色显示，不会被再次统一着色。
- **尺寸填满**：自适应图标光栅化后内容只占画布中间约 61%，四周是空的。模块会先裁掉空白再放大填满，否则缩到通知里那点尺寸会又小又糊。
- **界面是 Material 3 Expressive**：Compose + Material3，Android 12 以上跟随系统动态取色。
  界面图标全是手写的 vector drawable（`res/drawable/ic_*.xml`），没有引入图标库；
  配色只用 `colorScheme` 的语义角色，不写死颜色，浅色 / 深色两套主题都已实机核对。
- 仅注入 `com.android.systemui`，不碰其他进程。
- 联网只用于设置界面里的「检查更新」：点按钮才去读一次最新 Release，比对版本号。
  直连 `api.github.com` 失败时（国内网络常见 DNS 屏蔽）会退到公共加速镜像 `gh-proxy.com`。
  没有后台轮询、没有统计、没有上报、没有广告。挂钩部分（跑在 SystemUI 进程里的代码）完全不联网。

## 怎么确认生效

日志里出现 `patched <包名> 2->1 via=monochrome` 就是替换成功了（图标类型从「资源」变成「位图」，
`via=` 后面是本次实际用的模式）：

```bash
adb logcat -s Noticon -d | grep patched
```

启动时还会打一行配置，确认界面里的设置被读到了：

```
options: enabled=true mode=monochrome keepColor=false preserveTinted=true includeProxy=false (source=provider)
```

`mode=launcher-icon` 是彩色桌面图标，`mode=monochrome` 是系统黑白。`source=` 表示这行配置是从哪条通道读到的：
正常情况下是 `provider`（应用暴露的只读配置通道）。若显示别的来源，说明 provider 没查到，模块在用备选通道或默认值。

## 安装

1. 安装 `Noticon-v1.1.0-release.apk`（或用 `./gradlew assembleDebug` 自行构建）。
2. 在 **LSPosed / Vector**（或其他支持 LibXposed API 102 的框架）中启用 Noticon。
3. **不用勾作用域**：模块通过 `staticScope=true` + `META-INF/xposed/scope.list` 把
   作用域写死成 `com.android.systemui`，管理器里只会列出系统界面这一项，也不会让你勾到别的应用。
4. 重启设备让模块生效（这一步之后就不用再重启了）。

### 日志排查

嫌一步步敲命令麻烦，可以在 Git Bash 里直接跑（会自动装 APK、提示手动启用模块、重启设备、把日志存成 `noticon.log`）：

```bash
./scripts/check-device.sh
```

手动做的话：

```bash
adb install -r Noticon-v1.1.0-release.apk
# 在框架里启用 Noticon（作用域已固定为系统界面，不用勾）
adb logcat -c
adb reboot          # 重启后日志才干净
adb logcat -s Noticon -d
```

两点说明：

- **不要用 `am force-stop com.android.systemui` 来重启系统界面**。静态壁纸引擎
  `ImageWallpaper` 就跑在 SystemUI 进程里，WallpaperManagerService 会把「包被 force-stop」
  当成「包被卸载」，直接清空壁纸设置 —— 用户会看到壁纸变回系统默认渐变。
  用 `adb reboot`，或者等用户自己重启。
  `kill $(pidof com.android.systemui)` 也不行，shell 用户没权限发信号，会报 `Operation not permitted`。
- 若 `adb devices` 一直起不来（Windows 上默认端口 5037 可能落在系统保留端口段内），
  给所有 adb 命令加上 `-P 5039`，例如 `adb -P 5039 logcat -s Noticon -d`。

正常挂载后日志大致是这样：

```
device sdk=37 (Android 17)
all target classes resolved
attaching to SystemUI (api=102, framework=...)
options: enabled=true mode=0 keepColor=true
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
- 界面与模块的配置通道：界面写自己的 SharedPreferences（文件名 `noticon`），
  模块侧通过本应用暴露的**只读 ContentProvider**（`com.iamcanincan.noticon.config`，读取需要签名级权限
  `android.permission.STATUS_BAR`）跨进程读取；读不到时按 `direct-file → openRemoteFile → getRemotePreferences`
  依次回退，全部失败才退回默认值。
- 之所以不用 LibXposed 的 `getRemotePreferences` 当主通道：部分框架（实测 Vector 2.2）声明了远程配置能力位，
  但实现是空壳 —— `getRemotePreferences` 返回空对象、`openRemoteFile` 找不到任何文件，配置根本传不过去。
  ContentProvider 是 Android 标准机制，不依赖框架实现。**这不影响模块元数据：仍是 API 102，作用域仍由
  `staticScope=true` + `scope.list` 写死。**
- 界面用 Compose + Material3，`MaterialExpressiveTheme` + `MotionScheme.expressive()`。
  注意 material3 的版本下限是 **1.5.0-alpha**：MD3E 的公开 API 在 1.4.0 稳定版里还是 internal。
- 全部通过反射定位挂钩目标，找不到就跳过并打日志，因此不同 Android 版本间不会崩。
- 目标 Android 17（API 37），最低支持 Android 8.0（API 26）。

## 代码结构

```
com.iamcanincan.noticon
├── entry/NoticonModule            模块入口，只在 SystemUI 进程里挂载
├── engine/SystemUiHooks           挂钩的安装与拦截逻辑
├── engine/NotificationIconPatch   是否需要替换、换成什么的判定
├── engine/ModuleRuntime           进程内共享的 Context 与选项
├── model/ModuleOptions            行为开关与默认值
├── data/ModulePrefs               配置读写（界面与模块共用的文件名与键）
├── data/ConfigProvider            只读配置 provider，把设置跨进程交给模块
├── ui/MainActivity                设置界面入口（同时是桌面图标）
├── ui/SettingsScreen              设置界面
├── ui/theme/Theme                 主题（动态取色 + MD3E）
├── update/UpdateChecker           检查更新（唯一的联网点）
├── graphics/IconBitmap            位图的取、裁、合成
├── graphics/ToneCheck             单色（已适配）判定
└── util/MemberLookup              反射取成员的薄封装
```

界面图标（`res/drawable/ic_*.xml`）都是手写的 vector drawable，没有引图标库：
`ic_mode_color` 调色盘 / `ic_mode_mono` 对比度 / `ic_power` 电源 / `ic_relay` 转发 /
`ic_update` 下载 / `ic_scope` 盾牌。都是黑色描边或填充，实际颜色由 `Icon(tint=…)` 决定。

界面只让用户选「模式」，其余选项由模式推导，不需要额外配置渠道。底层 `replacement` 仍保留三种取值：

| 值 | 常量 | 行为 |
|---|---|---|
| 0 | `USE_LAUNCHER_ICON` | 换成彩色桌面图标（**默认**） |
| 1 | `FORCE_MONOCHROME` | 把应用自己给的那个小图标就地压成黑白剪影，由系统按主题着色 |
| 2 | `LAUNCHER_ICON_MONOCHROME` | 取桌面图标的轮廓压成灰度，去色但保留明暗（暂未在界面里暴露） |

`keepOriginalColor` 必须和策略配对：交出去的是彩色就开保色，交出去的是单色就关掉、让系统按主题上色。

## 许可

[MIT](LICENSE)。

## 签名密钥

`noticon-release.jks` 是本模块的自签名密钥（密码均为 `noticon`），已被 `.gitignore` 排除、不会入库。
**请自行备份**——后续更新必须用同一密钥签名，否则设备会拒绝覆盖安装。
