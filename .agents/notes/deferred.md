# 暂缓 / 候选事项

- 在真机上验证「免确认更新」（WSA 已于 2026-11-02 验证）和「工厂重装后 versionCode 递增更新」
- CI：仓库没配 keystore Secrets，push `v*` tag 的构建必失败、要手动取消；可改为没有 Secrets 时跳过 Release job（目前按 README「手动发布」本地构建上传）
- 公开版工厂：密钥无法备份，换手机只能重装所有 App；可考虑导出 / 导入记录时一并说明
- 用解析 resources.arsc 定位图标文件，替代弃用的 `android.enableResourceOptimizations=false`
- 工厂：导入 / 导出记录（换手机时迁移）
- 工厂：SVG 图标渲染
- 登录完成回到网站后，返回键会退回登录页 / 回调地址；可在返回时跳过登录流程的历史条目（2026-10-29 用户同意先不做，看使用体验再定）
- 登录规则只实测了 Google、GitHub；Microsoft、Apple、Facebook、X、微信等提供方的跳转链路未实测（Facebook 自 2021 年起限制 WebView 登录）
- 「TWA 模式」：Google 若日后收紧 WebView 登录，作为按 App 可选的兜底（网站不配合时有地址栏）
