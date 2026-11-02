# 项目规则

## 语言
- 代码注释、README、日志用中文；标识符英文。壳的界面文字默认英文 + `values-zh` 中文；工厂界面只有中文。

## 构建与测试命令
- 全部构建：`./build.sh`（apps.json 全部 App + 私有版工厂，输出 `dist/`）；单个：`./build.sh <id>` / `./build.sh factory`；公开版工厂：`./build.sh factory-public`（需正式 keystore，不能与 `factory` 同一次构建）
- 工厂核心单元测试：`./gradlew :factory:testDebugUnitTest`（依赖 `:app:exportTemplateApk` 和签名导出，Gradle 自动处理）
- 改动壳 / 模板 / 工厂 core 后，至少跑一次上面两条

## 架构约束（改动前必读）
- 壳（`app/`）里**每个 App 不同的东西**只能放在：`assets/webshell/config.json`、Manifest 占位符（`appLabel`、`usesCleartextTraffic`、`applicationId`）、`res/mipmap-<密度>-v4/ic_launcher_{foreground,background}.png`。**不要**新增按 App 变化的 `@string`/`@color` 资源或 BuildConfig 字段，否则工厂无法生成
- 模板包名 `com.yuanfeng.webshell.template` 是工厂替换的锚点；代码 namespace `com.yuanfeng.webshell` 不得以 `.template` 结尾
- config.json 字段在三处保持一致：`app/.../ShellConfig.kt`（读）、`buildSrc/.../GenerateSiteResourcesTask.kt`（PC 写）、`factory/.../core/SiteRules.configJson`（工厂写）
- 保留 id 列表在 `buildSrc/.../AppsConfig.kt` 与 `factory/.../core/SiteRules.kt` 两处，改动需同步

## 安全
- `keystore.properties`、`*.jks`、`dist/` 永不提交
- 私有版工厂（`WebShell_Factory-<版本>.apk`）内含签名私钥：不上传 Release、不进 CI artifact、不分享
- 公开版工厂（`WebShell_Factory-public-<版本>.apk`）不含私钥，可以发 Release；上传前用 `unzip -l` 确认没有 `assets/signing/`；`gh release` 上传逐个写文件名，不用通配符
- 用户自己的手机（nubia）已全部改用公开版（2026-11-02，9 个 App 都是设备密钥签名）：**不要再给它装私有版工厂**，也不要让用户卸载工厂或清除工厂数据

## 自测（设备）
- WSA：`adb connect 127.0.0.1:58526`；点击用 `adb shell input touchscreen tap`（普通 tap 无效）；截图 / 输入工具 `.dev/tests/wsa/ui.py`
- 工厂编辑页可用 SEND intent 直接打开并自动读取：`am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT <url> -n com.yuanfeng.webshell.factory/.ui.EditActivity`
