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
