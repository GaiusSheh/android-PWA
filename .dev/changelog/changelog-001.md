# WebShell 开发日志

## 2026-10-28 Phase 1：网页壳 + 手机端工厂

### 2026-10-28 00:00 PC 端批量构建（apps.json → 多个独立 APK）

**变更类型**: 项目初始化 / 核心功能实现
**影响范围**: 整个项目结构

#### 实现功能详细

**构建系统** [✓]
- 单一 Android 工程（Kotlin + View），minSdk 26、targetSdk/compileSdk 37，AGP 9.4.1 / Gradle 9.6.0（AGP 9 内置 Kotlin、新 DSL）
- `apps.json` → productFlavors：每个条目一个 applicationId `com.yuanfeng.webshell.<id>`，严格校验（未知键、颜色格式、文件存在、id 重复/保留字）
- buildSrc：`AppsConfig`、`GenerateSiteResourcesTask`（java.awt 生成自适应图标三种模式：logo / full-bleed / 首字母）、`GitVersion`（versionCode = commit 数，versionName 来自 tag）、`Signing`、`CopyApkTask`
- 签名：keystore.properties / `WEBSHELL_KEYSTORE_*` 环境变量，缺失时 debug 签名兜底；CI 打 tag 时 `WEBSHELL_REQUIRE_RELEASE_KEYSTORE=true` 禁止兜底
- `build.sh`（全部 / 指定 id / factory）输出 `dist/<name>-<version>.apk`；GitHub Actions 按 apps.json 动态 matrix，tag 发 Release
- build 目录可重定向到 OneDrive 外（`webshell.buildRoot`）

**WebView 壳** [✓]
- Cookie 持久化（含第三方）、返回键（OnBackPressedCallback）、外链 / mailto / tel / intent 交系统（intent 安全清洗 + fallback）
- 文件上传（多选 + 拍照 / 录像，FileProvider authority 带 applicationId）、DownloadManager（Cookie/UA/Referer、RFC 5987 文件名）
- 视频全屏、网页权限 → 运行时权限（仅白名单站点）、edge-to-edge + themeColor 系统栏、SplashScreen、原生错误页、saveState/restoreState、渲染进程崩溃恢复
- CSS/JS 注入、algorithmic darkening（系统深色时生效）、下拉刷新 + 内部滚动防冲突（RefreshGuard）

### 2026-10-28 01:00 手机端工厂 App（输入网址 → 生成并安装独立 App）

**变更类型**: 核心功能实现 / 架构重构
**影响范围**: 壳的配置方式、新增 factory 模块

#### 架构调整
- 壳的每 App 差异全部移到「免编译可替换」处：`assets/webshell/config.json`（运行时读取，替代 BuildConfig 字段）、Manifest 字面值 label（`${appLabel}`）、图标前景 / 背景 PNG（背景不再用颜色资源）
- 启动页背景改用系统默认（不再引用 themeColor 资源，resources.arsc 对所有 App 相同）
- `android.enableResourceOptimizations=false` 保持资源路径固定；`PackTemplateTask` 构建期校验
- 新增 `template` flavor 产出模板 APK，打包进工厂

#### 工厂功能 [✓]
- `BinaryManifest`：读写二进制 AXML（字符串池 UTF-8/UTF-16 编解码、按前缀替换包名、追加字符串改 label/versionName、改 int/bool 属性）
- `ApkGenerator`：替换 config.json / 注入文件 / 图标，重写 zip 并 4 字节对齐，apksig v2+v3 签名
- `SiteInspector`：jsoup 读取 manifest / meta / 图标候选 / theme-color 预填；无 theme-color 时取图标主色
- 人机验证：识别 Cloudflare「Just a moment...」等验证页，自动改用 `BrowserFetcher`（可见 WebView 小窗）通过验证后解析；失败兜底按域名填写
- 列表页 / 编辑页（Material 3）、浏览器「分享 → 做成 App」入口、相册选图标、PackageInstaller 安装（含 Android 12+ 免确认更新声明）
- 构建期从 keystore 导出私钥（key.pk8 + cert.der）打包进工厂，手机与 PC 生成的 App 签名一致

#### 测试验证结果
- [✓] **JVM 单元测试 11 项**：AXML 往返逐字节一致、生成 APK 签名校验（apksig）、Manifest 改写、文件替换、STORED 条目 4 字节对齐、aapt2 交叉验证、SiteRules（域名 / id / 颜色 / 验证页判断）
- [✓] **WSA 端到端**：工厂预填 → 设备上生成签名 → 系统安装器安装 → 生成的 App 加载 HN、站内跳转、返回键、外链交系统
- [✓] **人类验收**：用户在真机上测试人机验证网站读取成功
- [!] **未在设备上验证**：免确认更新（WSA 验证器故障阻断安装）、工厂重装后 versionCode 递增更新

**状态**: 开发完成，验收通过

## 2026-10-29 Phase 2：第三方登录在 App 内完成

### 2026-10-29 11:50 第三方登录（OAuth / SSO）、新窗口分流、外部跳转确认

**变更类型**: 功能增强 / 行为调整
**影响范围**: 壳（`app/`）的链接分流、新窗口、外部跳转；不新增 config 字段，PC 构建与工厂流程不变（工厂需重新构建以内置新模板）

#### 背景
用户反馈「用 Google 等平台登录会跳到浏览器，然后认证失败」。探路（`.dev/tests/google-login/findings.md`）结论：Google 并不拦截本壳的 WebView（原样 UA 即可登录）；失败原因是登录链路上的域名（`accounts.google.com`、`accounts.youtube.com`、`accounts.google.<地区>`）不在 allowedHosts，被交给浏览器，会话断开。

#### 实现功能详细

**第三方登录** [✓]
- `AuthRules`：内置登录入口规则，所有 App 共用。Google（含官方 187 个地区域名）、YouTube SetSID、Microsoft、Apple 整个登录域名；GitHub、Facebook、X、Discord、Reddit、LinkedIn、微信、QQ、微博只匹配登录路径，普通链接照常交给外部
- `ShellWebViewClient`：进入登录入口即标记「登录流程中」，期间主框架所有中间页留在 App 内，回到 allowedHosts 时结束（含用返回键退回的情况）；状态随 Activity 重建保存
- 网站跳往登录页的那一跳由壳改为新导航，绕开网站自身 CSP `form-action 'self'` 拦截跨域重定向的问题（Physics Forums 实例，桌面 Edge 同样复现）

**新窗口分流** [✓]
- `PopupWindows`：开启多窗口，`window.open` / `target=_blank` 先由不显示的 WebView 接住，按第一个地址分流：登录入口 → 全屏弹窗（顶栏域名 + 关闭按钮，`window.opener` 可用，`window.close()` / 返回键关闭）；站内 → 主 WebView；站外 → 浏览器；非点击触发 → 丢弃

**外部跳转确认** [✓]
- 非用户点击触发的外部跳转（网页自动跳应用商店、广告拉起其它 App）先弹确认框；点击后经服务器重定向出去的仍算点击（服务器重定向请求 `hasGesture()` 为 false，改为沿用导航链开头的状态）

#### 行为变化
- 站外 `target=_blank` 链接交给浏览器（原先在当前页打开）
- 非点击触发的新窗口被丢弃（原先导航主页面）
- 网页自动打开其它 App / 浏览器时需确认（含「点支付 → 后台下单 → 拉起支付 App」）

#### 测试验证结果
- [✓] **JVM 单元测试**：`AuthRulesTest` 6 项；工厂 11 项无回归
- [✓] **WSA 回归**：`regression.mjs` 13/13（登录入口接管、流程结束后外链、弹窗四种分流、opener、window.close）；`external_confirm.mjs` 6/6（确认框截图核对）
- [✓] **真机（nubia NX721J，Android 14，WebView 156）**：Physics Forums 的 Google 登录、GitHub 登录（均含两步验证，直接点按钮）；Kaggle Google 登录（整页跳转）；Pinterest Google 登录（弹窗模式，弹窗自动关闭后已登录）；外部跳转确认框（取消 / 打开 / 点击直接打开）
- [✓] **人类验收**：用户验收通过

**状态**: 开发完成，验收通过
