# 目录用途

| 目录 | 用途 |
|---|---|
| `app/` | WebView 壳；flavors 来自 `apps.json` + 固定的 `template` |
| `apps/<id>/` | apps.json 引用的图标、注入文件 |
| `buildSrc/` | 构建期逻辑（配置解析、图标、版本号、签名导出、模板打包） |
| `factory/` | 手机端工厂 App；`core/` 为纯 JVM 逻辑，`src/test/` 为其单元测试 |
| `.dev/docs/requirements/v1.0/` | 原始需求（与根目录 `background.md` 相同） |
| `.dev/tests/wsa/` | adb 驱动 WSA 的端到端自测工具；`output/` 截图不入库 |
| `.dev/tests/google-login/` | 第三方登录探路与回归（Node 直连 WebView DevTools）；`findings.md` 是结论和复测方法；`output/` 不入库 |
| `app/src/test/` | 壳的 JVM 单元测试（`AuthRulesTest`），任务 `:app:testTemplateDebugUnitTest` |
| `.dev/tools/` | 一次性工具（如生成示例图标） |
| `.dev/changelog/`、`.dev/plans/` | 变更日志、实施计划 |
| `.agents/` | 项目记忆、规则、对话日志、notes |
| `dist/` | build.sh 输出的 APK（不入库） |
