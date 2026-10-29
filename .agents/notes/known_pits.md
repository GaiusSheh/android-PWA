# 已知坑

## AGP 缩短 release 资源路径
- **现象**: release APK 里图标变成 `res/-B.png`，工厂找不到要替换的图标
- **根因**: AGP 默认 `optimizeResources` 缩短资源路径
- **解法**: `gradle.properties` 中 `android.enableResourceOptimizations=false`（已弃用开关，AGP 9.4 仍有效）；`PackTemplateTask` 构建期校验，失效会直接报错

## Gradle 任务属性名不能以 is 开头
- **现象**: `Could not generate a decorated class ... Cannot have abstract method isDebug(): Property<Boolean>`
- **解法**: 改名（如 `debugKeystore`）

## Kotlin 块注释可嵌套
- **现象**: KDoc 里写 `image/*` 导致 `Unclosed comment`
- **解法**: 注释里避免 `/*` 字样，或改用 `//` 注释

## WSA 自测
- 自由窗口模式下 `adb shell input tap` 不生效，用 `input touchscreen tap`；uiautomator dump 只有根节点
- 包验证器可能对 adb 安装超时拒绝（`INSTALL_FAILED_VERIFICATION_FAILURE`），`adb reboot` / `WsaClient /shutdown` 无法恢复，需在 Windows 侧关闭再打开 WSA
- 外部链接会转交 Windows 默认浏览器（会在桌面上打开网页）
- `dumpsys window` 的 `mCurrentFocus` 为 null，无法据此判断对话框是否出现，用截图核对
- 系统 WebView 只有 118，和真机差很多；登录等行为最终要在真机上确认

## 真机自测（nubia NX721J）
- `adb install`（含 `--no-streaming`）报 `Caller has no access to session -1` → `adb push` 到 `/sdcard/Download/` 让用户手动安装
- logcat 没有 `ActivityTaskManager: START ... VIEW` 记录，判断「是否交给外部」只能靠用户观察或截图
- 无线调试：熄屏后会断，换网络后 IP / 端口会变；已配对过的设备只需 `adb connect <新地址>`
- 熄屏或 App 在后台时 WebView DevTools 端口不响应、`Runtime.evaluate` 挂起
- 覆盖安装保留 App 数据（Cookie），从零测登录前先 `adb shell pm clear <包名>`
- `adb push` 保留源文件修改时间，手机上看到的是 APK 构建时间

## WebView 行为
- 服务器重定向的请求 `WebResourceRequest.hasGesture()` 为 false，需要自己沿用导航链开头的点击状态（`ShellWebViewClient.mainFrameNavByGesture`）
- 用返回键回到历史页面不经过 `shouldOverrideUrlLoading`，依赖它维护的状态要在 `onPageStarted` 里补
- 网站 CSP `form-action 'self'` 会拦下「表单 POST → 303 跳到其它域名」，表现为点按钮没反应（Chromium 浏览器里同样如此）

## 本机 Claude Code Bash 环境
- `;` 之后的命令拿不到前面 export 的变量；裸 `bash` / shebang 子进程也拿不到。用 `JAVA_HOME=... /usr/bin/bash ./gradlew ...` 显式传递
- Git Bash 会把 `/sdcard/...` 参数转换成 Windows 路径，adb 推送设备路径时注意
