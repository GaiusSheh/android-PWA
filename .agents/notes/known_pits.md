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

## 本机 Claude Code Bash 环境
- `;` 之后的命令拿不到前面 export 的变量；裸 `bash` / shebang 子进程也拿不到。用 `JAVA_HOME=... /usr/bin/bash ./gradlew ...` 显式传递
- Git Bash 会把 `/sdcard/...` 参数转换成 Windows 路径，adb 推送设备路径时注意
