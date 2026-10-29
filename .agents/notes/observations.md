# 决策记录

## 2026-10-28 工厂用「改写模板 APK」而不是在手机上编译
手机没有编译器；把每 App 的差异限定在 config.json / Manifest 字面值 / 图标 PNG 后，只需替换文件 + 改写二进制 Manifest + 重新签名。代价：启动页背景不能随 themeColor（主题在 resources.arsc 里）。详见 README「工作原理」与 `.dev/changelog/changelog-001.md`。

## 2026-10-28 签名私钥打包进工厂
让手机生成与 PC 构建的 App 签名一致、工厂重装后仍能更新旧 App。代价是工厂 APK 等同私钥：不公开发布、CI 不构建工厂。

## 2026-10-28 人机验证页用可见 WebView 通过
jsoup 不执行 JS；用可见小窗而不是隐藏 WebView，是为了 Turnstile 勾选框可由用户点击。WebView 渲染出的 HTML 复用 jsoup 的同一套解析（`SiteInspector.inspectHtml`）。

## 2026-10-28 label 用「追加字符串」而包名用「前缀替换」
包名派生字符串（authority、permission）必须全部替换；label / versionName 只改目标属性，追加新字符串避免误改其它共享同一字符串的引用。

## 2026-10-29 第三方登录用「入口规则 + 流程内放行」而不是扩大白名单
把 google.com、github.com 加进 allowedHosts 会让网站里普通的 YouTube 视频、GitHub 仓库链接也在 App 内打开；而 Google 登录途中还会经过地区域名（如 accounts.google.co.uk）等无法逐站配置的地址。所以 `AuthRules` 只认登录专用的域名 / 路径作为入口，进入后整个流程放行，回到网站时结束。规则写死在代码里（所有 App 共用），不进 config.json。UA 伪装实测不需要。详见 `.dev/tests/google-login/findings.md`。

## 2026-10-29 CSP 接管只做「网站 → 登录入口」那一跳
接管 = 取消重定向并 `loadUrl` 重发。只在跨站进入登录入口时做，流程内后续的重定向（CheckCookie → SetSID → consent → 回调）照常跟随，否则历史记录里会堆满 SetSID 地址、按返回会反复触发重定向。

## 2026-10-29 非点击外部跳转用确认框而不是直接丢弃
「点支付 → 后台下单 → 拉起支付 App」中间隔了网络请求，WebView 不认为是点击；直接丢弃会让支付静默失败。确认框与 Chrome 的做法一致，代价是这类流程多点一次。
