"[SmartYouTubeTV] is being sponsored by the following tool; please help to support us by taking a look and signing up to a free trial"

# TvApp (minSdk 22 / Android 5.1 TV Support)

本仓库目标是一个面向 Android TV 的轻量播放器与浏览体验（SmartYouTubeTV 衍生项目）。
当前重点工作：实现「在浏览窗口点击播放链接能直接打开播放」的最小可用工作流，并逐步完成 PlayerActivity 与 WebView 拦截器的集成与测试。

## 核心版本 / 要求
- Gradle: 8.7
- Android Gradle Plugin (AGP): 8.5.0
- Kotlin: 1.9.22
- compileSdk / targetSdk: 34
- minSdk: 22 (Android 5.1)
- JDK: 17 (CI 使用 temurin-17)

## 当前策略说明（关键）
为了把重心放在「点击即播」功能并加快 CI 周期，本仓库在短期采取了“只编译并验证 smartyoutubetv 模块”的策略：
- CI 只构建 :smartyoutubetv（workflow 已调整为仅 assemble smartyoutubetv）。
- 为了避免大量历史代码阻塞，部分 legacy 包在 smartyoutubetv 模块的 build.gradle 中临时被排除（exclude），并使用小型临时占位（例如临时的 R.java）来让编译继续。  
- 这些临时措施是短期的：功能稳定后，应恢复或替换为真实资源与实现，并移除占位文件与 excludes。

## 已实现 / 主要模块
- smartyoutubetv/web/OpenLinkWebViewClient.java — 拦截 WebView 链接，识别常见视频链接并发起播放 Intent（可发内部 PlayerActivity 或外部播放器）。
- smartyoutubetv/player/PlayerActivity.java — 轻量播放 Activity（基于 AndroidX Media3），处理 Intent.ACTION_VIEW 并播放传入的 Uri。界面包含：播放视图、文件名显示、音量加/减、亮度加/减。
- CI workflow 已调整为仅构建 smartyoutubetv（以便快速迭代）。

## 如何在本地构建与测试（快速上手）
1. 本地要求
   - JDK 17
   - Gradle wrapper（仓库自带 gradlew）
   - Android SDK（compileSdk 34）已安装

2. 构建（只构建 smartyoutubetv，加速验证）
   - ./gradlew :smartyoutubetv:assembleFullDebug --no-configuration-cache --no-daemon --stacktrace
   - 或 ./gradlew :smartyoutubetv:assembleDebug

3. 手动验证播放（两种方式）
   - 通过 WebView：
     - 在你的浏览器 Fragment 中使用 OpenLinkWebViewClient.attachTo(webView, context, true)
     - 在 WebView 中点击一个直接指向 mp4 / m3u8 / youtube 链接，应弹出外部应用选择或内部 PlayerActivity（如果你将拦截器改为发内部 Intent）
   - 通过 adb（直接启动 PlayerActivity）：
     - adb shell am start -a android.intent.action.VIEW -d "https://example.com/video.mp4" com.your.app.package
     - 或使用内部 Intent： adb shell am start -n com.your.app.package/.player.PlayerActivity -a android.intent.action.VIEW -d "https://example.com/video.mp4"

4. 注意：如果 CI / 本地出现“找不到 R”或大量历史类缺失的错误（这是历史遗留问题），说明需要恢复资源或继续在 build.gradle 中排除更多 legacy 源以快速过渡。短期内我们使用临时 R.java 编译占位以推进功能开发；长期请恢复真实资源并删除占位文件。

## 功能测试清单（手动）
- 在 WebView 中点击以下类型的链接，确认能触发播放：
  - 直接视频文件：*.mp4, *.webm, *.mkv（若可用）
  - HLS: *.m3u8
  - YouTube watch / youtu.be 链接（打开系统或 YouTube 应用 / 内部方案）
- PlayerActivity 行为：
  - 能正确读取 Intent.getData()，并在 PlayerView 中播放
  - 音量加 / 减按钮可改变 STREAM_MUSIC 音量，并显示系统音量 UI
  - 亮度加 / 减按钮可调整当前窗口亮度（Window attributes）
  - 屏幕旋转、后台/前台切换时播放器能正确释放/恢复（onStart/onStop 已做基础处理）
- 边界条件：
  - 无可用外部播放器时，WebView 回退为正常加载
  - 无 Uri 或非法 Uri 时给出友好提示（Toast）

## TODO（收尾与代码完善）
短期（优先）
- 清理临时占位（R.java、过多 excludes）并恢复资源：将 activity_player.xml、布局、attrs、dimens 等放回 res/，让 AAPT2 自动生成 R。
- 用真实实现替换临时 SmartPreferences / CommonApplication 辅助（若需要共享状态）。
- 扩展 PlayerActivity：播放控制（暂停/继续）、seek、显示播放时间、错误处理、headers/cookies 支持。
- 编写手动测试步骤与简单集成测试脚本（adb 命令合集），把它们加入 CI artifacts。

中期（可选）
- 若需要把播放纳入应用内完整体验：逐步恢复或替换 legacy exoplayeractivity 功能，或将 PlayerActivity 做成独立模块并使用现代 Media3 完整实现。
- 将部分关键逻辑提取成单元可测的小组件，并为它们编写单元/集成测试（在 host JVM 或 instrumentation 中）。

## CI / 工作流说明
- CI 已调整为仅构建 :smartyoutubetv（workflow 文件位于 .github/workflows/android.yml）。若需恢复整仓构建，请把 workflow 恢复为原先的 assemble 行并移除临时 excludes。
- 若 CI 报 “Configuration cache problems”，在 CI 命令中临时加入 --no-configuration-cache 可快速绕过（长期请调整 build.gradle 使其兼容 configuration cache）。

## 我可以为你继续做的事情
- 把上面的 README.md 修改提交到仓库（我可以生成 patch、或直接开 PR — 需要你的授权推送）。
- 继续收尾工作：把临时占位替换、恢复资源、完善 PlayerActivity（暂停/seek/headers）、增加集成测试脚本以及整理 CI 恢复方案。
- 协助执行并记录功能测试步骤与结果，按需准备 PR 列表与 issue 跟踪待办。

---

如果同意，我会把这个 README 的变更做成一个 commit/PR（或把 patch 发给你），然后继续把 PlayerActivity 的小缺陷与测试清单逐条处理。你希望我现在（选一）：
1) 生成并贴出 git-format-patch 文本供你本地 apply，还是  
2) 为你在仓库创建一个 branch + PR（我需要权限或你接受 PR），还是  
3) 你先 review README 内容并指定修改点？
