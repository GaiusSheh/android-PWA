# Google 登录探路（2026-10-29，WSA + 真机）

## 结论

**原样 WebView（UA 带 `; wv`、带 `X-Requested-With: <包名>`、不做任何伪装）在 WSA（WebView 118）和真机（WebView 156）上都完成了 Physics Forums 的 Google 登录。**
之前"跳到浏览器后认证失败"的原因不是 Google 拦截，而是登录链路上的域名不在 `allowedHosts` 里，被壳交给了外部浏览器，会话断了。

## 环境

- WSA，系统 WebView `com.android.webview` 118.0.5993.111
- 测试包：`apps.json` 临时条目 `pftest`（debug 版，开启 WebView 远程调试）
- 测试账号：用户本人的 Google 账号，开启两步验证（手机确认）

## 逐步结果

| 步骤 | 结果 |
|---|---|
| 打开 Google 授权页（三种 UA：原样 / 去掉 `; wv` / 再伪装 Chrome 品牌） | 都正常显示输入邮箱页，没有 `disallowed_useragent` |
| 提交不存在的邮箱 | 三种 UA 都返回「找不到此账号」 |
| 提交真实邮箱（原样 UA） | 进入密码页，没有出现「此浏览器或应用可能不安全」 |
| 密码 → 两步验证（`challenge/dp`） | 通过 |
| 两步验证后跳到 `accounts.youtube.com`（Google 在 YouTube 域名写登录 Cookie） | **不在 allowedHosts → 被交给外部浏览器 → 浏览器里继续授权报 400**。这就是之前失败的直接原因 |
| `allowedHosts` 加 `google.com`、`youtube.com` 后重走 | 选择账号 → 授权 → 回到 PF，`xf_user` Cookie 写入，已登录 |

## 顺带发现

- **PF 自身 bug**：确认页表单 POST 后 303 跳到 `accounts.google.com`，被 PF 自己的 CSP `form-action 'self'` 拦下（桌面 Edge 156 同样复现）。在 App 内点「Continue」毫无反应。
  - 旧版壳在 `shouldOverrideUrlLoading` 里抢先把跳转交给浏览器，所以没有表现出这个 bug
  - 测试时用 `start_login.mjs` 绕过：用 WebView 的 PF Cookie 在 PC 端提交表单，再让 WebView 打开授权地址
- 只改 UA 时，`X-Requested-With` 头仍然会发送（CDP UA 覆盖不影响它）。本次没有用上伪装，所以暂不需要处理

## 真机复测（同日，nubia NX721J，Android 14，WebView 156.0.8078.47）

清空 App 数据后从零开始，原样 UA（`...; wv) ... Version/4.0 Chrome/156...`），无伪装：

1. `allowedHosts = [physicsforums.com, google.com, youtube.com]`：两步验证后在 `accounts.youtube.com/accounts/SetSID` 处被取消、转交浏览器 → 400。
   CDP 响应头显示 SetSID 的下一跳是 **`accounts.google.co.uk/accounts/SetSID`**（Google 按所在地区的国家域名写 Cookie），不在白名单
2. 加上 `google.co.uk` 后：**成功**。完整链路：

```
accounts.google.com/CheckCookie
 → accounts.youtube.com/accounts/SetSID
 → accounts.google.co.uk/accounts/SetSID
 → accounts.google.com/signin/oauth/v3/consent
 → www.physicsforums.com/connected_account.php?state=…&code=…
 → www.physicsforums.com/   （xf_user 写入，已登录）
```

**对实现的含义**：Google 登录需要在 App 内放行 `google.com`、`youtube.com` 以及**用户所在地区的 Google 国家域名**（约 190 个：`google.co.uk`、`google.de`、`google.com.hk`……）。内置规则应按「`google.` + 任意公共后缀」匹配，不能只列 `google.com`。

## 未覆盖

- 弹窗模式的「Sign in with Google」（GIS 默认弹窗 + `window.opener`）需要多窗口支持，PF 用的是整页跳转，本次没有覆盖
- 其它登录提供方（GitHub、Microsoft、Apple 等）的跳转链路
- Google 账号未开两步验证、或触发额外风控验证时的路径

## 脚本

- `ua_probe.sh`：PC 端用 curl 对比不同 UA 下 Google 授权页的服务端响应
- `cdp_probe.mjs`：通过 DevTools 协议在 WebView 里切换 UA，打开授权页并提交测试邮箱，记录请求头和截图
- `click_continue.mjs`：在 WebView 里点 PF 确认按钮，抓控制台 / CSP 报错
- `start_login.mjs`：绕开 PF 的 CSP bug，用 WebView 的 PF 会话发起 Google 登录，之后人工完成
- `nav_logger.mjs`：记录主框架的每次导航、重定向和 `Location` 头（被壳取消的跳转也能看到原本的去向），检测到 PF 登录后自动结束
- `regression.mjs`：Phase 2 回归（登录入口接管、流程结束后外链、弹窗四种分流），不需要账号；`node regression.mjs <adb 序列号>`
- `popup_ui.mjs`：打开一个登录弹窗，配合 `adb shell screencap` 检查弹窗界面
- `gis_probe.mjs`：打开某网站登录页、用真实鼠标事件点 Google 按钮，判断是弹窗模式还是整页跳转（Pinterest 弹窗、Kaggle 整页）
- `popup_logger.mjs`：每秒列一次页面，记录弹窗的出现 / 关闭
- `external_confirm.mjs`：非点击外部跳转确认框回归（4 种情况，确认框靠截图核对）；`trigger_nav.mjs`：手动触发一次带 / 不带用户手势的跳转
- 运行前提：`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`
- **测试条目已从 apps.json 移除**，复测时临时加回（debug 版才能远程调试）：
  - `pftest`：`"url": "https://www.physicsforums.com/", "allowedHosts": ["physicsforums.com"]`（regression.mjs、nav_logger.mjs 等）
  - `logintest`：`"url": "https://www.kaggle.com/account/login"`，allowedHosts 含 `kaggle.com`、`pinterest.com`、`httpbin.org`（gis_probe.mjs、popup_logger.mjs、external_confirm.mjs）
  - 构建：`./gradlew :app:assemblePftestDebug`；nubia 等机型 adb 安装被拦时，`adb push` 到 `/sdcard/Download/` 手动安装
- 截图和导航记录在 `output/`，已加入 .gitignore（含个人账号页面和 OAuth 回调参数）
