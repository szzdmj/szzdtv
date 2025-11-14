```text
"[SmartYouTubeTV] is being sponsored by the following tool; please help to support us by taking a look and signing up to a free trial"
```

# TvApp (SmartYouTubeTV 衍生 - minSdk 22 / Android TV 支持)

本仓库目标是为 Android TV 提供一个轻量级的浏览 + 播放体验。当前短期重点：实现并验证“在浏览窗口点击播放链接能直接打开播放”的最小可用工作流（快速交付并可在设备上验证）。

## 核心版本 / 要求
- Gradle: 8.7  
- Android Gradle Plugin (AGP): 8.5.0  
- Kotlin: 1.9.22  
- compileSdk / targetSdk: 34  
- minSdk: 22 (Android 5.1)  
- JDK: 17 (CI 使用 temurin-17)

## 当前策略（重要）
为快速迭代“点击即播”功能，并避免被大量历史/遗留模块阻塞，当前采取临时策略：
- CI workflow 已调整为仅构建 `:smartyoutubetv`（减少构建时间、避免 legacy 编译错误）。见 .github/workflows/android.yml。  
- 在 `smartyoutubetv/build.gradle` 中临时排除了若干 legacy 包（`exclude 'com/liskovsoft/smartyoutubetv/misc/**'` 等），以避免大量“找不到类”的编译错误。  
- 为了编译通过，仓库中临时包含了少量“占位”文件（例如：`smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/R.java`、少数 Stub 类）。这些为短期手段，必须在功能稳定后移除并恢复真实资源/实现。  

> 这些临时措施是短期的、可撤回的 —— README 下方有“回滚/清理指南”。

## 已实现 / 主要文件（可直接查看与测试）
- Web 拦截器：smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/web/OpenLinkWebViewClient.java  
  - 功能：拦截 WebView 链接；对于视频/YouTube 链接发起 ACTION_VIEW Intent（可改为发内部 Intent）。  
  - 使用示例： OpenLinkWebViewClient.attachTo(webView, context, true)
- 轻量播放器：smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/player/PlayerActivity.java  
  - 基于 AndroidX Media3（media3-exoplayer、media3-ui），接收 ACTION_VIEW 并播放 Uri。  
  - UI：PlayerView、文件名、音量+/−、亮度+/−（基础生命周期处理 onStart/onStop）。  
  - 内部工厂：PlayerActivity.createIntent(context, uri)
- 临时占位：smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/R.java（短期内用于编译）

## 本地构建与 CI 验证（快速步骤）
1. 本地准备
   - 安装 JDK 17、Android SDK（compileSdk 34），确保 ANDROID_HOME/SDK_PATH 可用。  
2. 本地快速编译（只编译模块）
   - ./gradlew :smartyoutubetv:assembleFullDebug --no-configuration-cache --no-daemon --stacktrace
   - 或 ./gradlew :smartyoutubetv:assembleDebug
3. 在 CI 中（已配置）
   - workflow 默认只构建 `:smartyoutubetv`；如果 CI 报 configuration cache 问题，请用 `--no-configuration-cache`（已在 workflow 中使用）。
4. 验证播放（两种方式）
   - WebView（推荐）：在浏览器 fragment 中调用：
     ```java
     OpenLinkWebViewClient.attachTo(webView, getContext(), true);
     ```
     点击页面内的 mp4/m3u8/youtube 链接，观察是否触发播放 Intent。
   - adb（直接启动 PlayerActivity）：
     - adb shell am start -a android.intent.action.VIEW -d "https://example.com/video.mp4" com.szzdmj.smartyoutubetv
     - 或使用内部 Intent（若你选择走内部）：
       adb shell am start -n com.szzdmj.smartyoutubetv/.player.PlayerActivity -a android.intent.action.VIEW -d "https://example.com/video.mp4"

## 功能测试清单（手动执行）
1. 基本播放：
   - 点击 mp4/m3u8 链接 -> 系统或应用播放器打开并开始播放；若是内部 PlayerActivity，应显示文件名并开始播放。  
2. 音量/亮度：
   - 点击音量 + / -：系统 STREAM_MUSIC 音量变化并显示系统音量 UI。  
   - 点击亮度 + / -：当前 Activity 窗口亮度变化（Window attributes）。  
3. 边界与错误：
   - 无 Uri：提示友好错误（Toast）。  
   - 无外部播放器：WebView 回退加载页面（不崩溃）。  
   - 切换后台/前台：播放器释放/重建不崩溃（onStop/onStart）。  
4. YouTube 链接：
   - youtu.be / youtube.com/watch 链接 -> 以外部应用优先；若内部支持，应切换到内部处理流程。  
5. 回归：移除临时占位后，完整构建（整仓）是否通过（long-run test）。

## TODO（收尾与代码完善）
短期（必须）
- 恢复真实资源：把 activity_player.xml、attrs.xml、dimens.xml 等放回 res/，删除临时 R.java。  
- 清理 build.gradle 中的临时 excludes（逐条移回并验证）。  
- 用真实实现替换关键 stub（例如 SmartPreferences / CommonApplication，如果需要共享状态）。  
- 增加 PlayerActivity 控件：播放/暂停、seek、播放时间显示、错误提示、headers/cookie 支持。  
- 编写一组可重复执行的 QA 测试脚本（adb 脚本），并加入 CI artifacts（测试说明）。

中期（可选）
- 将 PlayerActivity 提取成独立模块并用 Media3 完整实现缓存/headers/DRM（如需）。  
- 编写单元测试与集成测试（Instrumentation / Robolectric），针对关键逻辑做覆盖。  

