在当前空目录里，帮我搭建一个 Android 项目：用一份配置文件批量生成多个独立的“网页壳 App”。每个网站对应一个独立 APK，有独立的包名、名字和图标，体验要尽量像原生 App，而不是浏览器。

## 背景
- 手机是 Android，桌面启动器是 Microsoft Launcher。我需要的是真正安装进应用抽屉的 App，不要快捷方式。
- 我用过 Native Alpha 和 Hermit，不满意的地方是它们共用一个宿主 App。所以这里每个 App 必须完全独立：独立的进程、数据、通知和权限，在最近任务列表里也单独显示。
- 我熟悉 C++、CMake 和 CI，但不熟悉 Android。请写清楚代码注释和 README。

## 架构要求
1. 单一 Android 工程，Kotlin，用 View 系统即可（不需要 Compose）。minSdk 26，targetSdk 和 AGP 都用当前最新稳定版。
2. 根目录放一份 `apps.json`，每个条目包含：
   - id（用作包名后缀，例如 `com.yuanfeng.webshell.<id>`）
   - name、url
   - icon（PNG 路径；不提供时自动生成首字母图标）
   - themeColor（同时用于状态栏和导航栏）
   - allowedHosts（这些域名在 App 内打开，其他链接跳系统浏览器）
   - userAgent（可选覆盖）
   - injectCss / injectJs（可选文件路径，用来隐藏网页顶栏之类）
   - enablePullToRefresh、forceDarkMode
3. Gradle 读取 `apps.json`，自动生成 productFlavors。每个 flavor 的 applicationId、app 名称、图标、BuildConfig 字段都来自配置。新增一个网站时，只需要往 json 里加一条。
4. 所有 flavor 使用同一个 release keystore 签名，保证以后覆盖安装更新不需要卸载。keystore 路径和密码从环境变量或 `keystore.properties` 读取，这个文件不能提交到仓库。

## WebView 体验要求（都要实现）
- JS、DOM storage 开启；Cookie 持久化，包括第三方 Cookie，保证登录状态不丢。
- 返回键先在网页历史里后退，退到底再退出 App。
- 外链处理：allowedHosts 以外的链接，以及 mailto:、tel:、intent: 等非 http 协议，交给系统处理。
- 文件上传（`<input type=file>`，支持拍照和多选）。
- 下载交给 DownloadManager，并保留 Cookie 和文件名。
- 视频全屏（onShowCustomView）。
- 网页请求摄像头、麦克风、定位时，转成 Android 运行时权限请求。
- 状态栏和导航栏颜色跟随 themeColor，并做好 edge-to-edge 适配。
- 用 Android 12+ 的 SplashScreen API 做启动页。
- 断网或加载失败时显示一个简洁的错误页，带重试按钮。
- 进程被杀后恢复（saveState / restoreState）。
- 按配置注入 CSS/JS，在页面加载完成时执行。
- forceDarkMode 用 WebView 的 algorithmic darkening 实现。
- 可选的下拉刷新（SwipeRefreshLayout），并且不能和网页内部滚动冲突。

## 构建与 CI
- 本地：提供 `./build.sh`，一条命令构建全部或指定 id 的 release APK，输出到 `dist/`，文件名格式为 `<name>-<version>.apk`。
- GitHub Actions：push 到 main 或打 tag 时，按 `apps.json` 动态生成 matrix 并行构建，签名后上传到 GitHub Release。keystore 用 base64 编码存在 Secrets 里，README 写清楚如何生成和配置。
- 版本号用 git commit 数或 tag 自动生成，保证每次构建 versionCode 递增。

## 交付
1. 先给我一份简短的计划和目录结构，确认后再写代码。
2. `apps.json` 里先放两个示例：一个普通网站，一个需要登录的网站（自己挑公开站点即可）。
3. 写完后实际运行一次 `./gradlew assembleRelease`（没有 keystore 时用 debug 签名兜底），确保能编译通过，并修复所有错误。
4. README 说明以下内容：如何新增一个 App；如何生成 keystore；如何用 adb 安装；已知限制。
5. 已知限制中必须写明：WebView 不支持 Web Push，所以网页推送通知收不到。另外附一节说明：对我自己能控制域名的自托管服务，如何改用 TWA（Bubblewrap + assetlinks.json）来获得 Chrome 内核和推送通知。这一节只写文档，不用实现。