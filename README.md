# UniteSDK for Android

H5 小游戏广告位 SDK。合作 App 接入后，通过内置的广告位视图展示小游戏内容，
用户点击卡片即在 SDK 内置的 WebView 中打开游戏；曝光 / 点击 / 视频播放时长
均由 SDK 自动上报，无需宿主处理。

## 工程结构

- **`unite_sdk_lib`** — SDK 库模块（产物为 AAR，纯 JVM 实现、无 native 库）
- **`demo`** — 演示工程，展示各广告位的完整用法（含语言切换、游戏中心入口）

## 环境要求

- minSdk 21（Android 5.0）及以上
- 接入凭证（`clientId` / `secretKey`）与广告位 ID（`slotId`）由运营分配

## 快速接入

### 1. 初始化（Application 或首个 Activity 中，一次即可）

```kotlin
GameSlotSdk.init(
    context,
    GameSlotSdk.GameSlotConfig(
        clientId = "YOUR_CLIENT_ID",
        secretKey = "YOUR_SECRET_KEY",
        uid = "YOUR_USER_ID",
        // appId / appVersion 不填时自动取宿主包名与 versionName
        // environment 默认 AUTO（正式构建的 SDK 默认连接正式环境），可显式指定 DEV / PROD
    )
)
```

### 2. 布局中放置广告位（零代码自动加载）

```xml
<com.unite.sdk.view.BigCardView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:slotId="YOUR_SLOT_ID" />
```

配置了 `app:slotId` 的视图在展示时自动请求并渲染广告，无需再写任何代码。
也可以在代码中主动加载：`view.loadSlot("YOUR_SLOT_ID")`。

### 测试与正式环境

测试接入时，在 `GameSlotConfig` 中显式设置 `environment = GameSlotSdk.Environment.DEV`；
正式上线设置 `GameSlotSdk.Environment.PROD`，并使用正式环境的凭证和组件 ID（`slot_id`）。
`AUTO` 根据 SDK 库自身的构建类型选择环境；使用 release AAR 时，即使宿主是 debug App，也会连接正式环境。

| 环境 | 组件接口 | 事件上报接口 |
|---|---|---|
| DEV（Sandbox） | `https://openapi-sandbox.minigame.ai/openapi/v4/game/slot` | `https://stats-sandbox.minigame.com/api/wy/report/{event_id}` |
| PROD | `https://openapi.minigame.ai/openapi/v4/game/slot` | `https://stats.minigame.com/api/wy/report/{event_id}` |

域名和路径迁移由 SDK 内部处理，宿主无需调用 `setServerBaseUrl` 或 `setEventUrl` 覆盖地址。

### 可用广告位视图

| 视图 | 形态 |
|---|---|
| `BigCardView` | 大卡片（横版封面 + 底部游戏信息条 + PLAY 按钮） |
| `ThreeCardsView` | 三联竖卡（106×188 规范卡型，底部标题蒙版 + PLAY） |
| `BigVideoView` | 视频大卡（自动播放预览，弱网 / 非 WiFi 自动降级封面图） |
| `GameCenterView` | 游戏中心入口（配置大厅位 slotId 后点击进入游戏大厅） |

### 3. 事件回调（可选）

```kotlin
view.setSlotListener(object : SlotListener {
    override fun onSlotLoaded(gs: GameSlot, slotId: String) {}
    override fun onSlotFailed(message: String, slotId: String) {}
    override fun onSlotShow(gs: GameSlot, slotId: String) {}
    override fun onSlotClick(gs: GameSlot, slotId: String) {}
    override fun onGameStart(gs: GameSlot) {}
    override fun onGameClose(gs: GameSlot) {}
})
```

所有方法都有默认空实现，只需覆写关心的回调。

### 多语言 / 地区（可选）

```kotlin
GameSlotSdk.setLanguage("en")   // 不调用则跟随系统语言
GameSlotSdk.setCountry("US")    // 不调用则按网络/SIM/系统地区自动判定
```

游戏内容的语言由服务端按请求语言返回；SDK 界面自身无需额外配置。

## 运行 demo

1. 在工程根目录的 `local.properties` 中补充运营分配的凭证：

   ```properties
   UNITE_CLIENT_ID=...
   UNITE_SECRET_KEY=...
   UNITE_UID=...
   # 可选：游戏大厅位
   UNITE_HALL_SLOT_ID=...
   ```

2. Android Studio 打开工程，Gradle 同步后运行 **`demo`** 配置即可。

## 构建 AAR

```bash
./gradlew :unite_sdk_lib:assembleRelease
# 产物：unite_sdk_lib/build/outputs/aar/unite-sdk-release.aar
```

AAR 方式接入时，宿主需自行声明 SDK 的依赖（appcompat、material、core-ktx、
okhttp、gson、coil、media3-exoplayer），版本参考 `unite_sdk_lib/build.gradle.kts`
与 `gradle/libs.versions.toml`。
