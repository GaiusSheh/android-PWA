# WebShell（android-PWA）

## 项目概述
把网站做成真正独立安装的 Android App：PC 端按 `apps.json` 批量构建，或在手机上用「网页壳工厂」输入网址直接生成并安装。

## 当前状态
- **阶段**: Phase 2 完成并验收（2026-10-29），已开源
- **最近完成**: 第三方登录在 App 内完成（内置登录规则 + 流程内放行 + CSP 接管）、弹窗式登录、非点击外部跳转确认框
- **正在进行**: 无
- **下一步**: 视用户需求；待验证项见下，候选见 `.agents/notes/deferred.md`

## 待验证 / 已知风险
- 「免确认更新」（UPDATE_PACKAGES_WITHOUT_USER_ACTION + USER_ACTION_NOT_REQUIRED）和「工厂重装后 versionCode 递增更新」未在设备上验证（WSA 验证器故障）
- `android.enableResourceOptimizations=false` 是 AGP 弃用开关；失效时 `PackTemplateTask` 会让构建失败

## 关键技术决策
- 每个 App 的差异只放在免编译可替换处：`assets/webshell/config.json`、Manifest 字面值、图标 PNG；resources.arsc / dex 全部相同（工厂才能改写）
- 工厂内置 `template` flavor 的 APK，并打包从 keystore 导出的私钥 → 手机与 PC 生成的 App 签名一致；**工厂 APK 不能公开发布，CI 不构建工厂**
- 工厂核心逻辑（`factory/.../core/`）不依赖 Android API，有 JVM 单元测试
- 项目定位：给**没有自己适配 PWA 的网站**做 App → 不选需要网站方配合的方案（TWA 全屏、App Links、OAuth 回调登记）
- 第三方登录：Google 目前不拦截本壳 WebView（2026-10 真机实测，无需改 UA）；关键是登录链路全程留在 App 内。规则在 `AuthRules`（所有 App 共用，不进 config.json）；探路与复测方法见 `.dev/tests/google-login/findings.md`

## 项目约定
- 规则与命令见 `.agents/rules.md`；用户文档见 `README.md`
- 已知坑见 `.agents/notes/known_pits.md`
