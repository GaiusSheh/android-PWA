# 决策记录

## 2026-10-28 工厂用「改写模板 APK」而不是在手机上编译
手机没有编译器；把每 App 的差异限定在 config.json / Manifest 字面值 / 图标 PNG 后，只需替换文件 + 改写二进制 Manifest + 重新签名。代价：启动页背景不能随 themeColor（主题在 resources.arsc 里）。详见 README「工作原理」与 `.dev/changelog/changelog-001.md`。

## 2026-10-28 签名私钥打包进工厂
让手机生成与 PC 构建的 App 签名一致、工厂重装后仍能更新旧 App。代价是工厂 APK 等同私钥：不公开发布、CI 不构建工厂。

## 2026-10-28 人机验证页用可见 WebView 通过
jsoup 不执行 JS；用可见小窗而不是隐藏 WebView，是为了 Turnstile 勾选框可由用户点击。WebView 渲染出的 HTML 复用 jsoup 的同一套解析（`SiteInspector.inspectHtml`）。

## 2026-10-28 label 用「追加字符串」而包名用「前缀替换」
包名派生字符串（authority、permission）必须全部替换；label / versionName 只改目标属性，追加新字符串避免误改其它共享同一字符串的引用。
