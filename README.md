```text
"[SmartYouTubeTV] is being sponsored by the following tool; please help to support us by taking a look and signing up to a free trial"
```

# TvApp (SmartYouTubeTV 衍生 - minSdk 22 / Android TV 支持)

本仓库目标是为 Android TV 提供一个轻量级的浏览 + 播放体验。当前短期重点：实现并验证“在浏览窗口点击播放链接能直接打开播放”的最小可用工作流。

## 核心版本 / 要求
- Gradle: 8.7  
- Android Gradle Plugin (AGP): 8.5.0  
- Kotlin: 1.9.22  
- compileSdk / targetSdk: 34  
- minSdk: 22 (Android 5.1)  
- JDK: 17 (CI 使用 temurin-17)

## 当前策略（重要 - 当前真实状态）
> 下面条目是基于最近调试与简化实现的真实状态，请在短期开发期间参考并在后续回收/清理计划里恢复。

1. LocalAssetsServer 行为（简化）
   - LocalAssetsServer 仅作为 APK 内 assets 的静态 HTTP server（127.0.0.1:PORT）。不再在主分支默认包含 app-level 的远端代理 / 缓存 / 响应体 rewrite 等复杂逻辑。
   - 对 gjw.html 做了注入：在 head 中加入 Content-Security-Policy meta（upgrade-insecure-requests）以将页面内的 http 请求自动升级到 https（若上游支持 https，则会成功，否则该资源会失败加载）。
   - 目的：避免中间层对响应 body 的修改（此前会导致 \u 等转义问题），并通过 CSP 简单强制 https 以便走可用的节点。

2. WebView 拦截逻辑（简化）
   - LauncherActivity.tryServeAssetForUrl 现在只：
     - 优先从 APK assets 返回本地 .js（若存在）；
     - 对本地 127.0.0.1 的请求让 LocalAssetsServer 处理；
     - 对外部 http(s) 请求不再代理（返回 null），由 WebView 发起真实网络请求；
     - 同时把“Intercept decision”与 request header count 记录到 CrashLogger 以便识别高频访问域名用于后续节点选择。
   - 这样可以在不改变上游服务器/网络环境的前提下观察哪些外部域名被访问，从而挑选可突破/替换的节点。

3. 代理与抓包
   - 先前为调试曾支持外部 minimal-proxy.js 的方式（通过 EXTERNAL_PROXY_HOST 配置）。当前主分支不启用它；若调试需要可短期恢复该路径用于抓取响应 preview。

4. CI 策略（临时）
   - CI workflow 默认仅构建 `:smartyoutubetv`，并包含少量临时占位（R.java、stub 类）以保证“点击即播”功能能快速验证。该策略为短期措施，后续将逐步回收并恢复完整构建。

## 已实现 / 主要文件（可直接查看与测试）
- Web 拦截器：smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/web/OpenLinkWebViewClient.java  
  - 功能：拦截 WebView 链接；对于视频/YouTube 链接发起 ACTION_VIEW Intent（可改为发内部 Intent）。  
  - 使用示例： OpenLinkWebViewClient.attachTo(webView, context, true)
- 轻量播放器：smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/player/PlayerActivity.java  
  - 基于 AndroidX Media3（media3-exoplayer、media3-ui），接收 ACTION_VIEW 并播放 Uri。  
  - 内部工厂：PlayerActivity.createIntent(context, uri)
- 沉浸式播放：browser/src/main/java/com/liskovsoft/browser/player/ExoPlayerActivity.java  
  - 提供一个最小、可直接调用的沉浸式播放 Activity（程序创建 PlayerView、进入 Immersive Sticky、支持 audio-only/video）。
  - 集成建议：OpenLinkWebViewClient 或 PlayerActivity 在发起播放时可直接调用 ExoPlayerActivity.start(context, url, title) 来获得沉浸体验。
- 临时占位：smartyoutubetv/src/main/java/com/liskovsoft/smartyoutubetv/R.java（短期内用于编译）

## 强制 https 的说明
- 我们在本地页面 (gjw.html) head 注入了以下 CSP：
  - `Content-Security-Policy: upgrade-insecure-requests; ...`
- 作用：浏览器（WebView）会把页面内的 http: 子资源请求自动升级为 https:（如果服务器端支持 https），从而减少 http->https 的中间跳转与被替换风险。
- 注意：若某些第三方资源服务器本身不提供 https，会导致该资源加载失败；这种情形下可：
  - 替换为支持 https 的节点；
  - 或短期为该域名配置可控的替代资源服务。

## 如何启用 / 本地验证
1. 本地准备（同上）
2. 本地快速编译（只编译模块）
   - ./gradlew :smartyoutubetv:assembleFullDebug --no-configuration-cache --no-daemon --stacktrace
   - 或 ./gradlew :smartyoutubetv:assembleDebug
3. 在设备上测试播放（WebView -> 点击链接）
   - 使用 OpenLinkWebViewClient.attachTo(...) 并点击页面内 mp4/m3u8/youtube 链接，观察 CrashLogger 中的拦截日志与 PlayerActivity / ExoPlayerActivity 的启动。
4. 如果你需要抓取真实响应 preview（用于排查响应体内被修改的情况），请暂时恢复 minimal-proxy 或使用外部抓包代理（mitmproxy/Charles），并在设备上配置代理（或使用 EXTERNAL_PROXY_HOST 方式）。

## TODO（收尾）
短期（必须）
- 把 activity_player.xml、attrs.xml、dimens.xml 放回 res/，删除临时 R.java；
- 清理 build.gradle 中的临时 excludes，逐条恢复模块并验证；
- 将 ExoPlayerActivity 与现有 PlayerActivity / OpenLinkWebViewClient 对接（我可帮忙提供 patch）。
中期（可选）
- 增强 ExoPlayerActivity：MediaSession、锁屏控制、遥控器友好键映射、WindowInsets for Android R+。
- 把 PlayerActivity 提取成独立模块并加入测试覆盖。

```
