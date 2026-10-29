# Phase 2: 第三方登录（OAuth / SSO）在 App 内完成 详细计划

## 计划概述
**阶段名称**: Phase 2 - 第三方登录在 App 内完成
**预估工期**: 约半天（编码 2~3 小时 + 真机回归）
**核心目标**: 用户在网页里点「用 Google / GitHub / Microsoft … 登录」时，整个登录过程留在 App 内完成，登录状态写进本 App 自己的 Cookie；不需要网站做任何适配
**当前状态**: [✓] 已完成，2026-10-29 人类验收通过

## 现状分析

### 探路结论（详见 `.dev/tests/google-login/findings.md`）
- [✓] **Google 并不拦截本壳的 WebView**：原样 UA（带 `; wv`、带 `X-Requested-With`）在 WSA（WebView 118）和真机（nubia，WebView 156）都完成了 Physics Forums 的 Google 登录。**不需要 UA 伪装**
- [✓] **失败的真正原因是登录链路被拆到两个环境**：主框架跳到 `allowedHosts` 以外的域名就交给外部浏览器（`ShellWebViewClient.kt:35-40`）。Google 登录途中依次经过：
  `accounts.google.com` → `accounts.youtube.com/accounts/SetSID` → **`accounts.google.<所在地区>/accounts/SetSID`** → `accounts.google.com/.../consent` → 网站回调
  任何一跳被送出去，会话就断，浏览器里报 400
- [!] **网站自身的 CSP 可能拦跳转**：PF 的「Continue with Google」表单 POST 后 303 到 `accounts.google.com`，被 PF 自己的 `form-action 'self'` 拦下（桌面 Edge 156 同样复现）。旧版壳因为在 `shouldOverrideUrlLoading` 里抢先转交浏览器，反而没暴露这个问题
- [!] **弹窗式登录不可用**：`setSupportMultipleWindows(false)`（`MainActivity.kt:187`）让 `window.open` 在当前页打开，`window.opener` 为空。Google Identity Services 的「Sign in with Google」按钮默认就是弹窗模式

### 约束
- 每个 App 的差异只能放在 config.json / Manifest 字面值 / 图标（工厂改写要求）→ **登录规则写死在壳代码里，所有 App 相同**，不新增 config 字段，PC 构建和工厂都不用改（工厂需重新打包以内置新模板）
- 网站不配合：不能要求网站登记回调、放 assetlinks 或改 CSP

## 设计

### 1. 登录流程（AuthFlow）：入口规则 + 流程内放行

单纯把 `google.com`、`github.com` 加进白名单有副作用：网站里普通的 YouTube 视频链接、GitHub 仓库链接也会在 App 内打开。所以把「登录」和「普通链接」分开处理：

- **入口规则**（`AuthRules`，纯 Kotlin，可 JVM 单测）：主框架导航命中以下地址时，判定为**进入登录流程**
  - 整个域名都是登录用途的：`accounts.google.com`、`accounts.google.<187 个官方国家后缀>`、`accounts.youtube.com`、`login.microsoftonline.com`、`login.live.com`、`login.microsoft.com`、`appleid.apple.com`
  - 共用域名只匹配登录路径：
    - GitHub `github.com`：`/login`、`/login/oauth/*`、`/sessions/*`
    - Facebook `facebook.com`、`m.facebook.com`、`www.facebook.com`：`/dialog/oauth`、`/v*/dialog/oauth`、`/login*`
    - X `x.com`、`twitter.com`：`/i/oauth2/authorize`，`api.x.com`、`api.twitter.com`：`/oauth/authorize`
    - Discord `discord.com`：`/oauth2/authorize`、`/login`
    - Reddit `reddit.com`、`www.reddit.com`：`/api/v1/authorize*`、`/login*`
    - LinkedIn `linkedin.com`、`www.linkedin.com`：`/oauth/v2/authorization`
    - 微信 `open.weixin.qq.com`：`/connect/*`；QQ `graph.qq.com`：`/oauth2.0/*`；微博 `api.weibo.com`：`/oauth2/*`
  - Google 国家后缀用官方列表（https://www.google.com/supported_domains ，187 个）写成常量，注明来源；不用正则泛匹配，避免误放行不属于 Google 的域名
- **流程内放行**：进入登录流程后，主框架的所有 http(s) 导航都留在 App 内（覆盖 SetSID 地区跳转、大学 / 企业 SSO 的中间页等无法预先列举的域名）
- **流程结束**：主框架回到 `allowedHosts` 内的地址即结束，恢复正常的「外链交给浏览器」规则
- 流程状态随 `onSaveInstanceState` 保存（切换深色模式会重建 Activity）

### 2. 绕开网站 CSP：入口跳转由壳接管

- 条件：主框架、`request.isRedirect`、当前**不在**登录流程中、目标命中入口规则
- 动作：返回 `true` 取消这次重定向，改为 `view.loadUrl(目标)` 发起新导航，并标记进入登录流程
- 只接管「从网站进入登录」这一跳；流程内后续的重定向（CheckCookie → SetSID → consent → 回调）照常由 WebView 跟随，不改写，避免历史记录里堆满 SetSID 地址
- 安全性：被绕过的是网站自己发起的、跳往知名登录提供方的跳转，不涉及把数据提交给第三方

### 3. 弹窗式登录：真正的多窗口

- `setSupportMultipleWindows(true)`，实现 `onCreateWindow` / `onCloseWindow`
- `onCreateWindow` 先创建一个隐藏的临时 WebView 接住新窗口，根据它的**第一个真实地址**（跳过 `about:blank`）决定去向：

| 第一个地址 | 处理 | 与现状对比 |
|---|---|---|
| 命中登录入口规则 | 显示为**全屏弹窗**（顶部一条细栏：域名 + 关闭按钮），`window.opener` 可用；网页调用 `window.close()` 或用户按返回 / 关闭时销毁 | 新增 |
| `allowedHosts` 内 | 丢弃临时 WebView，在主 WebView 打开 | 保持现状（`target=_blank` 在当前页打开） |
| 其它 | 丢弃临时 WebView，交给外部浏览器 | 现状是在当前页打开 |
| 非用户手势触发且不是登录 | 直接拒绝 | 现状是导航主页面（广告弹窗会劫持页面） |

- 弹窗 WebView 与主 WebView 设置一致（JS、DOM Storage、第三方 Cookie），Cookie 天然共享
- 新增界面文字（关闭按钮的无障碍描述）放进 `values` / `values-zh`，所有 App 相同，不违反工厂约束

### 4.（可选）返回键跳过登录页

登录完成回到网站后，按返回键会退回 Google 授权页 / 回调地址。可以在返回时跳过历史里属于登录流程的条目（`goBackOrForward(-n)`）。**实现有一定复杂度，建议先不做，验收时看体验再定**

## 详细实施步骤

### Phase 2-1: AuthRules + 流程内放行 + CSP 接管
- 新建 `AuthRules.kt`：入口规则（域名 + 路径）、Google 国家后缀常量；输入为 host / path 字符串，不依赖 `android.net.Uri`，方便 JVM 测试
- `ShellWebViewClient.shouldOverrideUrlLoading`：加入登录流程判断与入口接管；`Host` 接口增加流程状态的读写
- `MainActivity`：保存 / 恢复流程状态
- app 模块加 `testImplementation(libs.junit)`，新增 `AuthRulesTest`

#### 验收标准
- [✓] `AuthRulesTest` 通过：各提供方入口命中；`youtube.com/watch`、`github.com/<user>/<repo>`、`www.google.com/search` 等普通链接**不**命中（6/6）
- [✓] 真机：PF「Continue with Google」**直接点按钮**即可完成登录（不再需要脚本绕过），全程不跳浏览器
  - 2026-10-29 10:38，nubia WebView 156，`allowedHosts` 只有 `physicsforums.com`：POST 被壳取消 → `loadUrl` 打开授权页 → SetSID（youtube / google.co.uk）在流程内放行 → 回到 PF 已登录
- [✓] 真机：PF「GitHub」登录完成（10:54，同样经过入口接管；含 GitHub 手机两步验证 `/sessions/two-factor/mobile`）
- [✓] 网站里的普通 YouTube 链接仍交给外部（WSA `regression.mjs` 第 2 项）

### Phase 2-2: 弹窗式登录
- `MainActivity.kt:187` 改为支持多窗口；`ShellChromeClient` 实现 `onCreateWindow` / `onCloseWindow`
- 弹窗容器布局（全屏覆盖层 + 细顶栏），返回键优先关闭弹窗
- 临时 WebView 的分流逻辑（上表）

#### 验收标准
- [✓] 真机：一个使用 GIS 弹窗按钮的网站完成 Google 登录
  - 2026-10-29 11:12，Pinterest（临时测试条目 `logintest`）：点 Google 按钮 → 弹窗出现（`accounts.google.com/v3/signin/accountchooser`）→ 选账号后 3 秒弹窗自行关闭 → Pinterest 写入 `_auth=1`，首页为已登录状态
  - 用 `gis_probe.mjs` 在 WSA 上判定登录模式：Pinterest 为弹窗模式（地址带 `gislp=1`），Kaggle 为整页跳转。Kaggle 的 Google 登录真机也顺带验证通过
  - [!] 登录后 Pinterest 网页主动跳转到应用商店推广它的 App（非 http 链接按现有规则交给系统），会盖住页面；不影响登录，属于既有行为
  - [✓] WSA 模拟：用户手势 `window.open(accounts.google.com/...)` 显示为弹窗、`window.opener` 可用、`window.close()` 关闭、返回键关闭（`regression.mjs` 3a、`popup_ui.mjs`）
- [✓] `target=_blank` 的站内链接仍在当前页打开；站外 `_blank` 链接交给浏览器（WSA 3b、3d）
- [✓] 非手势的 `window.open` 不再劫持主页面（WSA 3c）

#### 实施中的发现
- 回归脚本第一版用 PF 的 `/forums/` 测站内 `_blank`，误判失败：该地址 301 到首页。改用 `/help/`

### Phase 2-4: 非点击触发的外部跳转先确认（验收中追加，2026-10-29 用户确认方案 A）
起因：Pinterest 真机测试时，登录后网页自动跳到应用商店推广 App，盖住页面。
- **原方案**：~~非点击触发的外部跳转直接丢弃~~ **[!] 会误伤支付**：「点支付 → 后台下单 → 拉起支付 App」中间隔了网络请求，WebView 不认为是点击
- [✓] **采用方案**：非点击触发的外部跳转（非 http 协议、白名单外 http）先弹确认框「网页想打开其它应用 / 网页想在浏览器中打开」，同一时间只弹一个；点击触发的照常直接打开
- [✓] **实施中发现**：服务器重定向的请求 `hasGesture()` 为 false（WSA 实测），「点链接 → 站内 302 → 站外」（论坛外链、点击统计链接）会每次都弹框。改为主框架导航链沿用链头的点击状态，新的非重定向导航重新设置（用户确认）
- [✓] **验证**：`external_confirm.mjs` 6/6（非点击跳站外 → 确认框；点击 → 直接打开；点击 → 302 → 站外 → 直接打开；非点击 → 302 → 站外 → 确认框，截图核对）；`regression.mjs` 13/13 无回归；单元测试通过
- [✓] 真机（11:47，nubia，Login Test）：用 CDP 模拟网页跳 `market://details?id=com.pinterest`
  - 非点击：弹「网页想打开其它应用」确认框；「取消」后关闭、页面不动、不拉起应用；「打开」后进入应用商店
  - 点击：不弹确认框，直接交给系统（手机装了多个应用商店，出现系统「打开方式」选择器，属系统行为）
  - Pinterest 登录后真实的自动跳转较难复现，未直接复测；逻辑与上面相同

### Phase 2-3: 收尾
- [✓] README：更新「已知限制」中 Google 登录、弹窗登录两条；说明内置登录规则、`allowedHosts` 与登录流程的关系（另更新了体验细节表、目录结构、TWA 对比表）
- [✓] 跑 `./gradlew :factory:testDebugUnitTest`（ApkGeneratorTest 3/3、SiteRulesTest 8/8），重新构建工厂 `dist/WebShell_Factory-r1.apk`（内置新模板）
- [✓] 清理探路临时状态：恢复 `apps.json`（删除 `pftest`、`logintest`），卸载 WSA / 真机上的 PF Test、Login Test；测试截图目录加入 .gitignore

## 技术风险评估

### 中风险
- **Google 日后收紧策略**：目前不拦截不代表永远不拦。缓解：README 写明；保留探路脚本，出问题时可快速复测；届时再考虑 UA / `X-Requested-With` 处理
- **流程内放行范围过宽**：登录页上的「帮助」「隐私权」等链接也会在 App 内打开。影响小，可接受
- **弹窗分流误判**：网页 `window.open()` 后不导航、直接写内容时拿不到第一个地址。缓解：短暂等待后仍无地址则按普通弹窗处理（在主 WebView 打开或拒绝），记录为已知限制

### 低风险
- **CSP 接管改变历史记录**：入口那一跳从「重定向」变为「新导航」，网站的确认页会留在历史里。与正常浏览器行为一致
- **Facebook 内嵌浏览器登录**：Facebook 自 2021 年起限制 WebView 登录，规则照常放行，能否成功取决于 Facebook，不在本阶段保证

## 资源需求
- **测试环境**：真机（nubia，无线调试已配对）、WSA；探路脚本 `.dev/tests/google-login/`
- **依赖库**：无新增（app 模块只加 junit 测试依赖）

## 完成标准
1. [✓] Phase 2-1、2-2、2-4 验收项全部通过
2. [✓] 壳与工厂单元测试通过，工厂重新构建
3. [✓] README 更新
4. [✓] 人类验收测试通过（2026-10-29）

## 已确认（2026-10-29）
1. [✓] 提供方清单按上文，不增删
2. [✓] 弹窗样式：全屏覆盖 + 细顶栏（域名 + 关闭）
3. [✓] 「返回键跳过登录页」先不做
4. [✓] 接受行为变化：非手势弹窗拒绝、站外 `_blank` 交给浏览器
