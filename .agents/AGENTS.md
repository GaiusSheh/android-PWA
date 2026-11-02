# WebShell（android-PWA）

## 项目概述
把网站做成真正独立安装的 Android App：PC 端按 `apps.json` 批量构建，或在手机上用「网页壳工厂」输入网址直接生成并安装。

## 当前状态
- **阶段**: Phase 3 完成并验收（2026-11-02），发布 v1.1.0（含公开版工厂）
- **最近完成**: 公开版工厂：不含私钥，生成的 App 用手机 AndroidKeyStore 自生成的密钥签名；Phase 2 第三方登录在 App 内完成
- **正在进行**: 无
- **下一步**: 视用户需求；待验证项见下，候选见 `.agents/notes/deferred.md`

## 待验证 / 已知风险
- 「免确认更新」WSA 已验证、真机未专门验证；「工厂重装后 versionCode 递增更新」未验证
- `android.enableResourceOptimizations=false` 是 AGP 弃用开关；失效时 `PackTemplateTask` 会让构建失败
- 用户手机已全部改用公开版工厂（9 个 App 设备密钥签名）：卸载 / 清数据 / 装私有版都会让这些 App 无法更新
- 仓库没配 keystore Secrets：push `v*` tag 的 CI 必失败，发布走 README「手动发布」

## 关键技术决策
- 每个 App 的差异只放在免编译可替换处：`assets/webshell/config.json`、Manifest 字面值、图标 PNG；resources.arsc / dex 全部相同（工厂才能改写）
- 工厂两版同包名、同代码（`gen/SigningKeys`）：私有版打包 keystore 导出的私钥（手机与 PC 签名一致，**不能公开发布**）；公开版用设备密钥（可发 Release，密钥不可备份）。CI 不构建工厂
- 工厂核心逻辑（`factory/.../core/`）不依赖 Android API，有 JVM 单元测试
- 项目定位：给**没有自己适配 PWA 的网站**做 App → 不选需要网站方配合的方案（TWA 全屏、App Links、OAuth 回调登记）
- 第三方登录：Google 目前不拦截本壳 WebView（2026-10 真机实测，无需改 UA）；关键是登录链路全程留在 App 内。规则在 `AuthRules`（所有 App 共用，不进 config.json）；探路与复测方法见 `.dev/tests/google-login/findings.md`

## 项目约定
- 规则与命令见 `.agents/rules.md`；用户文档见 `README.md`
- 已知坑见 `.agents/notes/known_pits.md`
