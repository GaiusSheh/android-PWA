# 技术对话记录 - Part 001

> 本文件记录重要技术决策和讨论要点
> 完整对话详见各 Session ID 对应的原始会话记录
> 恢复对话：`claude --resume <session-id>`

**记录时间范围**：2026-10-27 ~ (进行中)

---

## Session: 2026-10-27 项目初始化、PC 批量构建、手机端工厂 App

**对话元数据**:
- 时间: 2026-10-27 23:17 - 2026-10-28 02:10
- 关键词: `webview-shell`, `productFlavors`, `apps.json`, `factory`, `AXML`, `apksig`, `cloudflare-challenge`, `WSA`
- 工作目录: `~/OneDrive/Codes/my-tools/android-PWA`
- 项目规则入口: `.agents/AGENTS.md`
- 参与 agent:
  - Claude Code (env: windows-native):
    - Session ID: `6f5b59bf-2c85-4ee4-a86a-beaa1f29c3f0`
    - 原始记录: `~/.claude/projects/c--Users-Shi-Y-OneDrive-Codes-my-tools-android-PWA/6f5b59bf-2c85-4ee4-a86a-beaa1f29c3f0.jsonl`
    - 恢复命令: `claude --resume 6f5b59bf-2c85-4ee4-a86a-beaa1f29c3f0`

**快速恢复对话**：同环境用上方恢复命令；跨环境走 `skills/handover` 软接力，不 resume。

### 核心技术决策

#### 1. 需求与用户确认（背景见 `background.md` / `.dev/docs/requirements/v1.0/requirements.md`）

- 每个网站一个完全独立的 APK（不共用宿主），Kotlin + View，minSdk 26
- 用户选择：forceDarkMode 语义 = (b) 系统深色时压暗不支持深色的网页；不建 GitHub 远程（个人项目）→ 后期改为开源
- 构建环境：JDK 21（winget）+ Android cmdline-tools 装在 `C:\Android\sdk`（OneDrive 外）；build 目录用 `~/.gradle/gradle.properties` 的 `webshell.buildRoot` 重定向到 `%LOCALAPPDATA%\webshell-build`

#### 2. 版本选型（2026-10 联网核对）

- AGP 9.4.1（最低 Gradle 9.6.0，API 上限 37）、platform `android-37.0`、build-tools 36.0.0
- AGP 9 破坏性变化：内置 Kotlin（不再 apply kotlin-android）、新 DSL（applicationVariants 移除，用 androidComponents.onVariants + Variant API）、resValues 默认关闭
- 输出 APK 改名不用旧 outputFileName，而是 `CopyApkTask` 读 `SingleArtifact.APK`

#### 3. 从「PC 批量构建」扩展到「手机端工厂」

**讨论内容**：用户希望在手机上给一个网址就直接生成 App（目标是没有 PWA 的网站）。

**决策结果**：
- [✓] **方案 A：手机上改写模板 APK**（采用）：手机无编译器，内置模板 APK，只替换文件 + 改写二进制 Manifest + 重新签名
- [!] 方案 B：手机触发云端 CI 构建（需 GitHub 仓库、等待数分钟）
- [!] 方案 C：Chrome WebAPK（依赖 PWA 和 Google 服务，数据与 Chrome 共享）

**关键约束**：每 App 差异只能落在免编译可替换处 → config.json（运行时读取）、Manifest 字面值 label、图标 PNG（背景也改为 PNG）。resources.arsc 与 dex 对所有 App 相同；启动页背景因此改为系统默认。

#### 4. 签名密钥

- 生成正式 keystore：`~/keys/webshell-release.jks`（OneDrive 外，不进仓库），密码随机生成写入 gitignore 的 `keystore.properties`
- 构建工厂时把私钥导出为 key.pk8 + cert.der 打包进 assets → 手机与 PC 生成的 App 签名一致；代价：工厂 APK 不能外传

#### 5. 人机验证网站（用户反馈「获取网页信息得到 just a moment」）

- 原因：jsoup 不执行 JS，拿到 Cloudflare 验证页且 `ignoreHttpErrors(true)` 把它当正常页解析
- [✓] 识别验证页（`cf-mitigated: challenge` / 错误码 + 验证页标题）→ 改用可见 WebView 小窗（`BrowserFetcher`）通过验证后取 outerHTML 复用同一套解析，图标下载带 CookieManager 的 Cookie（cf_clearance 绑 UA，UA 必须一致）
- [✓] 失败 / 取消 / 30 秒超时兜底：按域名填写 + 首字母图标
- 用户真机验收通过

### 技术调整/突破

**资源路径缩短**：
- ~~依赖 release 包里的资源路径~~ [!] AGP 默认把 `res/mipmap-xxhdpi-v4/ic_launcher_foreground.png` 缩短成 `res/-B.png`
- [✓] `android.enableResourceOptimizations=false`（AGP 标记弃用但 9.4 仍生效）+ `PackTemplateTask` 构建期校验路径

**二进制 Manifest 改写**：
- 包名派生字符串共 4 处（package、FileProvider authority、androidx-startup authority、DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION）→ 字符串池按前缀 `com.yuanfeng.webshell.template` 替换
- label / versionName 改为「追加新字符串 + 属性指向新下标」，避免影响共享同一字符串的其它引用
- 编码器正确性：不改动时重新编码与 AAPT2 输出逐字节一致（单元测试）

**zip 对齐**：STORED 条目（resources.arsc）用 0xD935 对齐 extra 填充到 4 字节，否则 Android 11+ 拒绝安装

### 重要代码与文档创建

- `buildSrc/src/main/kotlin/webshell/`：AppsConfig、GenerateSiteResourcesTask、IconRenderer、GitVersion、Signing、PackTemplateTask、CopyApkTask
- `app/`：WebView 壳（MainActivity、ShellWebViewClient、ShellChromeClient、FileChooser、DownloadHandler、PermissionBroker、ExternalLinks、Injector、RefreshGuard、ShellConfig）
- `factory/`：core（BinaryManifest、ApkGenerator、SiteRules + JVM 测试）、web（SiteInspector、BrowserFetcher）、gen、install、data、ui
- `.dev/tests/wsa/ui.py`：adb 驱动 WSA 截图 / 点击的自测工具
- `README.md`：使用、keystore、adb、CI、工作原理、已知限制、TWA 附录

### 技术洞察

- WSA 自由窗口模式下 `adb shell input tap` 无效，必须用 `input touchscreen tap`；uiautomator dump 只能拿到根节点
- WSA 的包验证器（FileSystemRedirectionHandler）00:47 起开始对 adb 安装超时拒绝，`adb reboot` 和 `WsaClient /shutdown` 都无法重启 WSA；导致「免确认更新」未能在设备上验证
- Gradle 任务里 `Property<Boolean>` 属性名不能以 `is` 开头（生成装饰类失败）
- Kotlin 块注释可嵌套：KDoc 里写 `image/*` 会被当成新注释开头导致编译错误
- 本机 Claude Code 的 Bash 工具中，`;` 之后的命令看不到前面 export 的变量，裸 `bash` / shebang 子进程也拿不到；用 `VAR=x /usr/bin/bash ./script` 显式传递

---

## 统计信息

- **记录的主题 Session 数量**：1 个
- **关联的 agent 会话数量**：1 个
- **覆盖时间**：2026-10-27 ~ 2026-10-28
- **主要主题**：Android WebView 壳、Gradle 批量 flavor、手机端 APK 改写与签名、人机验证处理

---

## 相关资源

- **相关文档**：
  - CHANGELOG: `.dev/changelog/`
  - Implementation Plans: `.dev/plans/`
