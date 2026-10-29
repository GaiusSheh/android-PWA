# 暂缓 / 候选事项

- 在真机上验证「免确认更新」和「工厂重装后 versionCode 递增更新」
- 用解析 resources.arsc 定位图标文件，替代弃用的 `android.enableResourceOptimizations=false`
- 工厂：导入 / 导出记录（换手机时迁移）
- 工厂：SVG 图标渲染
- 登录完成回到网站后，返回键会退回登录页 / 回调地址；可在返回时跳过登录流程的历史条目（2026-10-29 用户同意先不做，看使用体验再定）
- 登录规则只实测了 Google、GitHub；Microsoft、Apple、Facebook、X、微信等提供方的跳转链路未实测（Facebook 自 2021 年起限制 WebView 登录）
- 「TWA 模式」：Google 若日后收紧 WebView 登录，作为按 App 可选的兜底（网站不配合时有地址栏）
