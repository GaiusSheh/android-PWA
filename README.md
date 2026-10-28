# WebShell：一份配置，批量生成独立的网页壳 App

在 `apps.json` 里写一个网站，就得到一个**真正独立安装**的 Android App。每个 App 有自己的包名、名字、图标、进程、数据（Cookie / 登录状态）、通知、权限，在最近任务里也各自显示。它们共用同一套壳代码，但不共享宿主，这一点和 Native Alpha、Hermit 不同。

两种用法，生成的 App 完全一样：

- **手机上直接做（工厂 App）**：装上「网页壳工厂」，输入网址（或在浏览器里点「分享」→「做成 App」）。工厂会读取网页，自动填好名称、图标和主题色，你确认或修改后点「生成并安装」。全程不需要电脑。
- **电脑上批量构建**：在 `apps.json` 里写配置，执行 `./build.sh` 一次构建全部 App。

技术栈：Kotlin + View 系统，minSdk 26（Android 8.0），targetSdk / compileSdk 37，AGP 9.4.1 / Gradle 9.6.0 / JDK 17+（推荐 21）。

---

## 目录

1. [手机上生成 App（工厂）](#手机上生成-app工厂)
2. [快速开始（电脑上构建）](#快速开始电脑上构建)
3. [apps.json 字段](#appsjson-字段)
4. [新增一个 App](#新增一个-app)
5. [生成 keystore（正式签名）](#生成-keystore正式签名)
6. [用 adb 安装](#用-adb-安装)
7. [GitHub Actions 自动构建](#github-actions-自动构建)
8. [工作原理（写给熟悉 C++/CMake 的人）](#工作原理写给熟悉-ccmake-的人)
9. [已知限制](#已知限制)
10. [附：自托管服务改用 TWA 获得推送通知](#附自托管服务改用-twa-获得推送通知)

---

## 手机上生成 App（工厂）

### 安装工厂

把 `dist/WebShell_Factory-<版本>.apk` 传到手机上点开安装，或者用 [adb 安装](#用-adb-安装)。构建方法：`./build.sh factory`。

### 使用

1. 打开「网页壳工厂」，点「新建」，输入网址后点「读取网页信息」。也可以在浏览器里打开网页，点「分享」→「做成 App」，工厂会自动读取。
2. 工厂会自动填好下面几项，每一项都可以改：
   - **名称**：依次取网页 manifest 的 `short_name`、`application-name`、`og:site_name`、`<title>`，用第一个有值的。
   - **图标**：列出网页提供的图标（manifest、apple-touch-icon、favicon），默认选最清晰的。也可以点「首字母」使用自动生成的图标，或点「相册」选一张图片。
   - **主题色**：取网页的 `theme-color`；网页没有声明时，从图标里提取主色。
   - **在 App 内打开的域名**：取网址的主域名，比如 `news.ycombinator.com` 会填成 `ycombinator.com`，子域名自动包含。
   - **包名后缀**：在「高级选项」里。比如 `news.ycombinator.com` 会生成 `news_ycombinator`，完整包名就是 `com.yuanfeng.webshell.news_ycombinator`。
3. 点「生成并安装」。第一次使用时，系统会让你允许「网页壳工厂」安装未知应用，按提示打开开关后回来再点一次。
4. 系统弹出安装确认，点「安装」。新 App 会出现在应用抽屉里，和普通 App 一样。

修改已有的 App：在工厂首页点它，改完再点「生成并安装」，系统会覆盖安装，登录状态保留。Android 12 及以上，更新工厂装过的 App 一般不需要再确认。首页会标出「有改动未生成」的 App。

「高级选项」里还可以设置 User-Agent、注入的 CSS 和 JS，作用和 `apps.json` 里的同名字段一样。

### 它是怎么做到的

手机上没有编译器，所以工厂不编译代码。它内置了一个已经编译好的「模板 App」，每次生成时只改写其中几样东西：

- 二进制 AndroidManifest.xml 里的包名、名称和版本号；
- `assets/webshell/config.json`，也就是网址、域名和颜色这些配置；
- 图标 PNG。

改完后，工厂用打包在它自己里面的签名密钥重新签名，再交给系统安装器安装。详见[工作原理](#工作原理写给熟悉-ccmake-的人)。

> **不要把工厂 APK 分享给别人。** 生成 App 用的签名私钥就打包在工厂 APK 里。这样做的好处是：手机上生成的 App 和电脑上构建的 App 签名相同，可以互相覆盖安装；工厂卸载重装后，也能继续更新以前生成的 App。代价是，拿到工厂 APK 的人也就拿到了你的签名私钥。

---

## 快速开始（电脑上构建）

### 环境

| 组件 | 说明 |
|---|---|
| JDK 17+ | 推荐 21。需设置 `JAVA_HOME` |
| Android SDK | 只需 command-line tools，不必装 Android Studio。需设置 `ANDROID_HOME`，或在工程根目录写 `local.properties`：`sdk.dir=C\:/Android/sdk` |
| Gradle | 不用装，`./gradlew` 会自动下载 9.6.0 |
| bash | Windows 上用 Git Bash 运行 `build.sh` |

安装 SDK（只需一次，示例为 Windows，Linux / macOS 同理）：

```bash
# 1. 从 https://developer.android.com/studio#command-tools 下载 commandlinetools-*.zip
#    解压后目录结构必须是 <SDK>/cmdline-tools/latest/bin/sdkmanager
# 2. 接受许可并安装平台工具（编译所需的 platform / build-tools 会由 Gradle 首次构建时自动下载）
sdkmanager --sdk_root=C:/Android/sdk --licenses
sdkmanager --sdk_root=C:/Android/sdk "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

### 构建

```bash
./build.sh                    # 构建 apps.json 中的全部 App + 工厂 App
./build.sh wikipedia          # 只构建一个（可写多个 id）
./build.sh factory            # 只构建工厂 App
./build.sh --list             # 列出所有 id
```

输出在 `dist/<name>-<version>.apk`，例如 `dist/Hacker_News-1.2.0-r57.apk`（name 中的空格和文件名非法字符会替换为 `_`）。

也可以直接调用 Gradle：

```bash
./gradlew assembleRelease          # 构建全部 release APK（留在 build 目录，不拷到 dist/）
./gradlew distRelease              # 构建全部并拷贝到 dist/（build.sh 就是调用它）
./gradlew assembleWikipediaDebug   # 某个 App 的 debug 版（可用电脑 Chrome 的 chrome://inspect 调试网页）
```

没有配置 keystore 时，release APK 会**退回 debug 签名**，构建时有警告。这种 APK 可以正常安装使用，但以后**不能**被正式签名的版本覆盖安装（签名不同），需要先卸载，登录状态也会丢失。所以长期使用前请先完成[生成 keystore](#生成-keystore正式签名)。

### 工程在 OneDrive 等同步盘里时

Gradle 的 `build/` 目录每次会写入几百 MB 的中间文件。可以在**本机私有**的 `~/.gradle/gradle.properties` 里重定向到同步盘之外：

```properties
webshell.buildRoot=C:/Users/<你>/AppData/Local/webshell-build
```

也可以设置环境变量 `WEBSHELL_BUILD_ROOT`。`dist/` 仍在工程目录里。

---

## apps.json 字段

```json
{
  "apps": [
    {
      "id": "hackernews",
      "name": "Hacker News",
      "url": "https://news.ycombinator.com/",
      "icon": "apps/hackernews/icon.png",
      "themeColor": "#FF6600",
      "allowedHosts": ["news.ycombinator.com"],
      "userAgent": "{default} WebShell",
      "injectCss": "apps/hackernews/inject.css",
      "injectJs": "apps/hackernews/inject.js",
      "enablePullToRefresh": true,
      "forceDarkMode": true
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✓ | 包名后缀：`com.yuanfeng.webshell.<id>`。只能是小写字母、数字和下划线，以字母开头。**发布后不要再改**：改了就是另一个 App |
| `name` | ✓ | 桌面上显示的名字，可以是中文 |
| `url` | ✓ | 启动页，`http://` 或 `https://` |
| `icon` |  | PNG 路径（相对于工程根目录），建议 512×512 以上。不填则用 themeColor 作底色、自动生成首字母图标。PNG 四角透明（logo 类）时缩放进安全区，四角不透明（方形图标）时铺满可见区 |
| `themeColor` |  | `#RRGGBB` 或 `#AARRGGBB`，默认 `#FFFFFF`。用于状态栏、导航栏，以及自动生成图标的底色。状态栏图标的深浅色会按亮度自动选择。启动页背景用系统默认的浅色或深色，不用这个颜色 |
| `allowedHosts` |  | 这些域名**及其所有子域名**在 App 内打开，例如写 `example.com` 就包含 `m.example.com`。其它链接交给系统浏览器。默认只包含 url 的域名。**登录跳转到第三方域名**（如 SSO）时要把那个域名也加进来 |
| `userAgent` |  | 覆盖 User-Agent。`{default}` 会替换为 WebView 的默认 UA |
| `injectCss` / `injectJs` |  | 注入的 CSS / JS 文件路径。CSS 在首屏可见和加载完成时各注入一次，JS 在加载完成时执行一次 |
| `enablePullToRefresh` |  | 下拉刷新，默认 `false` |
| `forceDarkMode` |  | 默认 `false`。为 `true` 时，**系统处于深色模式**时会用算法把本身不支持深色的网页压暗；系统是浅色时不起作用。网页自己支持 `prefers-color-scheme: dark` 时，用网页自己的深色样式 |

键名拼错、颜色格式错、文件不存在、id 重复等错误会在构建一开始就报出来，并指出是哪个条目。以 `_` 开头的键视为注释。

---

## 新增一个 App

1. 在 `apps.json` 的 `apps` 数组里加一条，至少写 `id`、`name`、`url`。
2. （可选）把图标和注入文件放到 `apps/<id>/` 下，并在条目里填写路径。
3. `./build.sh <id>`，然后[用 adb 安装](#用-adb-安装) `dist/` 里的 APK。

不需要改任何 Kotlin 或 Gradle 代码。

调试注入的 CSS / JS：先构建 debug 版 `./gradlew assemble<Id>Debug` 并安装，手机连上电脑后，在电脑 Chrome 打开 `chrome://inspect`，就能像调试普通网页一样检查元素、在控制台里试 JS。

---

## 生成 keystore（正式签名）

Android 用签名来判断「这是不是同一个 App 的更新」。**所有 App 共用一个 keystore**，以后每次构建都用它，覆盖安装就不用卸载。

```bash
keytool -genkeypair -v \
  -keystore ~/keys/webshell-release.jks \
  -alias webshell \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=WebShell"
# 按提示设置密码（storePassword 和 keyPassword 可以相同）
```

`keytool` 随 JDK 提供，在 `$JAVA_HOME/bin/` 下。

然后二选一：

- **本地**：复制 `keystore.properties.example` 为 `keystore.properties`，填入路径和密码。这个文件已被 `.gitignore` 忽略，不会提交。
- **环境变量**：`WEBSHELL_KEYSTORE_FILE`、`WEBSHELL_KEYSTORE_PASSWORD`、`WEBSHELL_KEY_ALIAS`、`WEBSHELL_KEY_PASSWORD`。

> **务必备份 `.jks` 文件和密码。** 丢失后无法再发布能覆盖安装的更新，只能卸载重装，所有 App 的登录状态都会丢失。不要把它放进 git 仓库。

构建工厂 App 时，会从这个 keystore 导出私钥和证书，打包进工厂（见上面[关于私钥的提醒](#它是怎么做到的)）。所以换了 keystore 之后要重新构建工厂。

---

## 用 adb 安装

1. 手机：设置 → 关于手机 → 连点「版本号」7 次开启开发者选项；再到「开发者选项」里打开「USB 调试」（或「无线调试」）。
2. 电脑安装 platform-tools（见[环境](#环境)），`adb` 在 `<SDK>/platform-tools/` 下。
3. 连接后执行：

```bash
adb devices                                   # 确认手机已连接并授权
adb install -r dist/Hacker_News-1.2.0-r57.apk # -r：覆盖安装，保留数据
for f in dist/*.apk; do adb install -r "$f"; done   # 全部安装
```

装好的 App 会直接出现在 Microsoft Launcher 的应用抽屉里。卸载：`adb uninstall com.yuanfeng.webshell.<id>`。

也可以把 APK 传到手机上直接点开安装，需要在系统里允许「安装未知应用」。

常见报错：

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：签名和已安装的版本不同（例如之前装的是 debug 签名版）。先 `adb uninstall` 再装。
- `INSTALL_FAILED_VERSION_DOWNGRADE`：versionCode 比已安装的小。见[版本号](#版本号)。

查看 App 日志：`adb logcat --pid=$(adb shell pidof com.yuanfeng.webshell.<id>)`

---

## GitHub Actions 自动构建

`.github/workflows/build.yml`：

CI 只构建 `apps.json` 里的 App，**不构建工厂 App**。工厂 APK 里有签名私钥，而公开仓库的 Actions artifact 和 Release 任何人都能下载。工厂请在本地用 `./build.sh factory` 构建。

- **push 到 main**：用 `apps.json` 生成 matrix，每个 App 一个并行 job，构建后上传为 Actions artifact（在这次运行的页面下载）。
- **push `v*` tag**（如 `git tag v1.2.0 && git push --tags`）：同上，并把全部 APK 上传到同名 GitHub Release。打 tag 构建时**必须**配置 keystore，不允许 debug 签名混进正式发布。

### 配置 Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64（见下） |
| `KEYSTORE_PASSWORD` | storePassword |
| `KEY_ALIAS` | keyAlias，如 `webshell` |
| `KEY_PASSWORD` | keyPassword |

生成 base64（输出一整行，复制进 Secret）：

```bash
# Linux / Git Bash
base64 -w0 ~/keys/webshell-release.jks
# macOS
base64 -i ~/keys/webshell-release.jks
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\keys\webshell-release.jks")) | Set-Clipboard
```

### 版本号

- `versionCode` = 当前分支的 commit 总数（`git rev-list --count HEAD`）。每次提交 +1，自然递增。
- `versionName`：HEAD 正好打了 tag `v1.2.0` → `1.2.0`；在 tag 之后 → `1.2.0-r57`；没有任何 tag → `r57`。
- **不要改写已经构建、安装过的历史**（force push、rebase），否则 commit 数可能变小，覆盖安装会报 `VERSION_DOWNGRADE`。
- CI 的 checkout 已设 `fetch-depth: 0`，浅克隆会导致 commit 数只有 1。

---

## 工作原理（写给熟悉 C++/CMake 的人）

### 概念对照

| Android / Gradle | 类比 |
|---|---|
| Gradle | CMake + Ninja。`settings.gradle.kts` ≈ 顶层 `project()` + `add_subdirectory()`，`app/build.gradle.kts` ≈ 子目录的 CMakeLists |
| AGP（Android Gradle Plugin） | Android 专用的「工具链文件」，负责编译、打包、签名 |
| `buildSrc/` | 构建期才运行的辅助代码（类似 CMake 的自定义 `.cmake` 模块，但用 Kotlin 写）。Gradle 先编译它，再执行构建脚本 |
| productFlavor | 同一套源码的不同「配置变体」，类似对同一个 target 用不同的 `-D` 宏编译多次。每个 flavor 有自己的 applicationId |
| buildType（debug / release） | 类似 `CMAKE_BUILD_TYPE` |
| 变体（variant） | flavor × buildType，例如 `hackernewsRelease`，对应一个 APK |
| `BuildConfig` | 构建时生成的 Java 常量类，类似 `configure_file` 生成的 `config.h` |
| `namespace` vs `applicationId` | 前者是代码的包名（生成的 `R`、`BuildConfig` 类在这个包里），所有 App 相同；后者是安装到系统里的唯一标识，每个 App 不同 |
| Activity | 一个顶层窗口。`onCreate` ≈ 构造，`onDestroy` ≈ 析构 |
| Manifest | 声明组件、权限、图标等元数据，安装时由系统读取 |

### 构建流程

**核心约束：每个 App 之间的差异只能放在「不用编译就能替换」的地方。** 只有这样，手机上的工厂才能直接改写 APK。具体有三处：

| 差异 | 放在哪里 | 谁读取 |
|---|---|---|
| 网址、域名白名单、颜色、UA、开关 | `assets/webshell/config.json` | 壳的 `ShellConfig`，运行时读取 |
| 名称、包名、明文开关、版本号 | Manifest 里的字面值（不是 `@string` 资源） | 系统安装器 |
| 图标 | `res/mipmap-<密度>-v4/ic_launcher_{foreground,background}.png` | 启动器 |

`resources.arsc`（颜色、字符串资源表）和 dex 代码对所有 App 完全相同。这也是 `gradle.properties` 里要关闭资源路径缩短的原因：工厂需要按固定路径找到图标文件。

**电脑上构建**：

1. `app/build.gradle.kts` 在配置阶段调用 `AppsConfig.load()`，解析并校验 `apps.json`。它为每个条目 `create(id)` 一个 productFlavor，并写入 `applicationId` 和 Manifest 占位符。另外固定会有一个 `template` flavor。
2. 对每个变体注册 `GenerateSiteResourcesTask`。它用 JDK 的 `java.awt` 生成各密度的图标 PNG，生成 `config.json`，并把注入文件拷到 assets。产物通过 AGP 的 Variant API 挂到这个变体的源目录上。
3. AGP 编译、R8 压缩、签名，得到 APK；`dist<Variant>` 任务把它拷到 `dist/`。
4. `GitVersion` 从 git 读取 commit 数和 tag，作为版本号。

**工厂 App**：

1. 构建时，`template` flavor 的 release APK（模板）和从 keystore 导出的私钥（`key.pk8` + `cert.der`）被打包进工厂的 assets。`PackTemplateTask` 会检查模板里的图标路径没有被缩短。
2. 在手机上，`SiteInspector` 用 jsoup 读取网页，预填名称、图标和颜色。
3. `ApkGenerator` 改写模板：`BinaryManifest` 改写二进制 Manifest，具体是替换字符串池里的包名前缀，并追加新的名称字符串；然后替换 `config.json` 和图标，重写 zip 并把未压缩条目按 4 字节对齐；最后用 Google 官方的 apksig 做 v2 + v3 签名。
4. `Installer` 通过系统 `PackageInstaller` 安装。

`core/` 目录下的类（`BinaryManifest`、`ApkGenerator`、`SiteRules`）不依赖 Android API，有 JVM 单元测试：`./gradlew :factory:testDebugUnitTest`。测试会用真实的模板 APK 生成一个 App，再校验签名、Manifest、对齐，并用 SDK 的 aapt2 交叉验证。

### 目录结构

```
apps.json                     ← 唯一的配置入口
apps/<id>/                    ← 各 App 的图标、注入文件
buildSrc/src/main/kotlin/webshell/
  AppsConfig.kt               ← 解析、校验 apps.json；模板 App 的定义
  GenerateSiteResourcesTask.kt← 生成每个 App 的 config.json、图标、注入文件
  IconRenderer.kt             ← 生成自适应图标位图（电脑端）
  GitVersion.kt               ← versionCode / versionName
  Signing.kt                  ← 读取签名配置；导出私钥给工厂
  PackTemplateTask.kt         ← 把模板 APK 打包进工厂并检查路径
  CopyApkTask.kt              ← 拷贝到 dist/
app/build.gradle.kts          ← flavor（含 template）、签名、任务接线
factory/                      ← 工厂 App
  build.gradle.kts            ← 打包模板 APK 和私钥；单元测试参数
  src/main/java/com/yuanfeng/webshell/factory/
    core/                     ← 纯 JVM 逻辑（有单元测试）
      BinaryManifest.kt       ←   读写二进制 AndroidManifest.xml
      ApkGenerator.kt         ←   改写模板、对齐、签名
      SiteRules.kt            ←   包名后缀、主域名、颜色解析、config.json
    web/SiteInspector.kt      ← 读取网页的名称、图标、主题色
    gen/IconRenderer.kt       ← 生成自适应图标位图（手机端）
    gen/AppBuilder.kt         ← 记录 → APK 文件
    install/Installer.kt      ← PackageInstaller 安装及结果回调
    data/AppRepository.kt     ← 工厂里保存的 App 记录
    ui/                       ← 列表页、编辑页
  src/test/                   ← JVM 单元测试
app/src/main/
  AndroidManifest.xml
  java/com/yuanfeng/webshell/
    MainActivity.kt           ← 生命周期、edge-to-edge、启动页、返回键、全屏、错误页、状态恢复
    ShellWebViewClient.kt     ← 链接分流、页面事件、错误、渲染进程崩溃
    ShellChromeClient.kt      ← 进度、全屏视频、文件选择、摄像头 / 麦克风 / 定位权限
    FileChooser.kt            ← <input type=file>：多选 + 拍照 / 录像
    DownloadHandler.kt        ← DownloadManager（Cookie、UA、Referer、文件名）
    PermissionBroker.kt       ← 运行时权限请求队列
    ExternalLinks.kt          ← 外链、mailto/tel/intent 交给系统
    Injector.kt               ← CSS / JS 注入
    RefreshGuard.kt           ← 下拉刷新与网页内部滚动的冲突判定
    ShellConfig.kt            ← 读取 assets/webshell/config.json
  res/                        ← 布局、主题、字符串（英文 + 中文）
build.sh                      ← 本地一键构建
.github/workflows/build.yml   ← CI
```

### 体验细节怎么实现

| 需求 | 实现位置与做法 |
|---|---|
| Cookie 持久化（含第三方） | `MainActivity.setupWebView`：`setAcceptThirdPartyCookies`；`onPause` 时 `CookieManager.flush()` 立即落盘 |
| 返回键 | `OnBackPressedCallback` 只在「全屏中」或「网页可后退」时启用，其余交给系统，兼容 Android 14+ 预测性返回动画 |
| 外链 | `ShellWebViewClient.shouldOverrideUrlLoading`：白名单外的 http(s) 和所有非 http 协议交给 `ExternalLinks`；`intent:` 链接做了安全清洗，支持 `browser_fallback_url` |
| 文件上传 | `FileChooser`：按 `accept` 构造选择器；接受图片 / 视频时附加拍照 / 录像（FileProvider 临时文件）；支持 `multiple` 和 `capture` |
| 下载 | `DownloadHandler`：带上 Cookie / UA / Referer；正确解析 `filename*=UTF-8''...`（中文文件名） |
| 视频全屏 | `onShowCustomView` 把视频 View 放进全屏容器并隐藏系统栏，返回键或网页退出全屏时恢复 |
| 网页权限 | 只对 allowedHosts 内的网站，把 `getUserMedia` / geolocation 请求转成 Android 运行时权限，只授予用户实际同意的部分 |
| 系统栏颜色 | edge-to-edge + 透明系统栏，根布局背景 = themeColor；内容区按系统栏、刘海、键盘的 insets 留出边距。Android 15+ 起旧的 `statusBarColor` 已失效，这是官方推荐做法 |
| 启动页 | `core-splashscreen`：Android 12+ 用系统 API，8~11 由兼容库模拟。背景用系统默认的浅色或深色（启动页主题在 `resources.arsc` 里，工厂不改写），中间是 App 图标。保持到首屏内容可见，最长 2.5 秒 |
| 错误页 | 主文档加载失败时显示原生错误页（附错误原因）和「重试」按钮 |
| 进程被杀后恢复 | `onSaveInstanceState` 存 `WebView.saveState`，`onCreate` 里 `restoreState`。渲染进程崩溃时重建 Activity 并回到原地址，App 不会闪退 |
| 注入 | `Injector`：只对白名单页面注入，带防重复标记 |
| 强制深色 | `WebSettingsCompat.setAlgorithmicDarkeningAllowed` + DayNight 主题 |
| 下拉刷新防冲突 | `RefreshGuard`：注入的 JS 在 touchstart 时判断触点所在的可滚动容器是否已滚到顶，原生侧据此决定滚动还是刷新 |

---

## 已知限制

- **收不到网页推送通知（Web Push）。** Android WebView 不支持 Push API 和 Notification API，`Notification.requestPermission()` 不会有效果，网站的推送通知收不到。对于你能控制域名的自托管服务，可以改用 TWA，见[下一节](#附自托管服务改用-twa-获得推送通知)。
- **Google 账号登录会被拒绝。** Google 会拦截嵌入式 WebView 里的 OAuth 登录（报错 `disallowed_useragent`），改 User-Agent 也不可靠。用「Sign in with Google」的网站请改用浏览器或 TWA。部分其它 OAuth 提供方也有类似限制。
- **依赖弹出窗口的登录流程可能失败。** 为了留在同一个界面，`window.open` / `target=_blank` 会在当前 WebView 里打开，不会开新窗口。依赖 `window.opener` 回传结果的弹窗式登录（部分 SSO、支付）无法完成。
- **Passkey / WebAuthn 支持有限**，取决于系统 WebView 版本和设备，不保证可用。
- **`blob:` / `data:` 下载不支持。** 这类链接的数据只存在于网页内存中，DownloadManager 拿不到，会提示「暂不支持」。需要 POST 请求或自定义请求头（非 Cookie 鉴权）的下载同样会失败，因为 DownloadManager 会重新发起一次 GET 请求。
- **注入有时机限制。** CSS 最早在首屏可见时注入，网页自带的顶栏可能会先闪一下。JS 在 `onPageFinished` 时执行。单页应用用 `pushState` 切换路由时不会再次触发，脚本需要自己监听路由变化。
- **下拉刷新防冲突是启发式的。** 它能识别 `overflow: auto/scroll` 的内部滚动容器。用 JS 自己实现滚动或手势的页面（地图、自定义滚动库）仍可能误触，这类网站建议关闭 `enablePullToRefresh`。
- **所有 App 声明的权限相同**（摄像头、麦克风、定位等），因为共用同一个 Manifest。这些只是「可以申请」，只有网页真正请求时才会弹窗。
- **首字母图标在 CI 上可能退回 id 首字母。** Linux CI 默认没有中文字体，中文名的首字生成不了，会改用 id 的首字母。本地 Windows 构建不受影响；也可以给这个 App 配 `icon`。
- **网页内核是系统的「Android System WebView」**，随系统或应用商店更新。网页兼容性取决于手机上的 WebView 版本，不是 App 自带的。
- **debug 签名兜底的 APK** 不能被正式签名的版本覆盖安装，见[构建](#构建)。
- **同一个 App 不要在手机和电脑两边交替构建。** 两边签名相同，但工厂生成的 versionCode 是「模板版本 × 1000 + 生成次数」，比电脑构建的「commit 数」大得多。在工厂生成过之后，再装电脑构建的同 id 版本，系统会以版本降级为由拒绝。选定一边一直用即可。
- **有人机验证的网站，读取会慢一些。** 工厂先直接请求网页。如果拿到的是 Cloudflare「Just a moment...」这类验证页，会弹出一个小窗，用浏览器内核打开网页。自动验证一般几秒就能通过；遇到需要勾选的验证，在小窗里点一下即可。取消或 30 秒内没通过时，名称按域名填写，图标用首字母，需要手动修改。必须登录才能看到首页的网站，读到的是登录页的信息。生成的 App 本身不受影响，在 App 里正常完成验证或登录即可。
- **工厂不渲染 SVG 图标。** 网站只提供 SVG 图标时会退回 favicon 或首字母图标，可以从相册另选一张。
- **关闭资源路径缩短用的是 AGP 已标记为弃用的开关**（`android.enableResourceOptimizations=false`）。如果将来某个 AGP 版本移除了它，构建工厂时 `PackTemplateTask` 会直接报错，而不是悄悄产出一个坏掉的工厂。届时需要改为解析 `resources.arsc` 来定位图标文件。

---

## 附：自托管服务改用 TWA 获得推送通知

> 本节只是文档，本工程不包含 TWA 实现。

**TWA（Trusted Web Activity）** 让 App 直接用手机上的 Chrome 内核全屏显示你的网站。它和 WebView 壳的区别：

| | WebView 壳（本工程） | TWA |
|---|---|---|
| 内核 | 系统 WebView | 用户的 Chrome（共享 Chrome 的更新和能力） |
| Web Push / 通知 | ✗ | ✓（由 Chrome 代发，显示为该 App 的通知） |
| Google 登录、Passkey | 受限 | ✓ 与 Chrome 一致 |
| Cookie / 存储 | 每个 App 独立 | 与 Chrome 共享同一站点的存储 |
| 前提 | 任何网站 | **必须能在该域名下放文件**（Digital Asset Links），需要 HTTPS 和 PWA manifest，手机上要有 Chrome |
| 注入 CSS / JS | ✓ | ✗（只能改网站本身） |

适合用 TWA 的场景：你自己部署、能控制域名的服务（例如自托管的 Nextcloud、Home Assistant、ntfy、Mastodon 实例），并且需要推送通知。

### 步骤

1. **网站侧准备**：HTTPS；有 Web App Manifest（`manifest.webmanifest`，包含 `name`、`icons`、`start_url`、`display: standalone`）；有 Service Worker。大多数自托管服务已经自带。
2. **安装 Bubblewrap**（Google 官方的 TWA 生成工具，需要 Node.js；首次运行会询问是否自动下载 JDK 和 Android SDK，也可以指向本机已有的）：
   ```bash
   npm i -g @bubblewrap/cli
   ```
3. **初始化工程**（每个服务一个目录）：
   ```bash
   mkdir my-nextcloud-twa && cd my-nextcloud-twa
   bubblewrap init --manifest https://cloud.example.com/manifest.webmanifest
   ```
   按提示填写包名（建议沿用 `com.yuanfeng.twa.<id>` 这类独立前缀）、名字、颜色。签名步骤选择**已有 keystore**，可以直接复用本工程的 `webshell-release.jks`（换一个 alias 也行）。
4. **构建**：
   ```bash
   bubblewrap build        # 生成 app-release-signed.apk
   ```
5. **发布 Digital Asset Links**，证明「这个 App 和这个域名是同一个主人」。先取签名证书指纹：
   ```bash
   keytool -list -v -keystore ~/keys/webshell-release.jks -alias webshell | grep SHA256
   # 或：bubblewrap fingerprint list
   ```
   在网站上放 `https://cloud.example.com/.well-known/assetlinks.json`：
   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "com.yuanfeng.twa.nextcloud",
       "sha256_cert_fingerprints": ["AB:CD:...:EF"]
     }
   }]
   ```
   要求：HTTPS、`Content-Type: application/json`、不能重定向。反向代理（nginx / Caddy）下记得放行 `/.well-known/`。
6. **安装验证**：`adb install -r app-release-signed.apk`。验证成功时全屏显示，没有地址栏；如果顶部出现 Chrome 地址栏，说明 assetlinks 验证失败，检查包名、指纹和文件能否访问。可以用 Google 的 [Statement List Tester](https://developers.google.com/digital-asset-links/tools/generator) 检查。
7. **通知**：Android 13+ 首次请求通知权限时，系统会以这个 TWA App 的名义弹窗。之后网站的 Web Push 由 Chrome 接收，并显示为该 App 的通知。

更新：修改 `twa-manifest.json` 里的 `appVersionCode` / `appVersionName`，然后执行 `bubblewrap update && bubblewrap build`。

---

## 关于仓库里的 `.agents/`、`.dev/`

这个项目是和 AI 编程助手（Claude Code）一起开发的。`.agents/` 是给 AI 助手看的项目记忆、规则和对话记录，`.dev/` 是开发过程中的需求、计划、变更日志和自测工具。它们不参与构建，可以忽略。

## 许可证

[MIT](LICENSE)
