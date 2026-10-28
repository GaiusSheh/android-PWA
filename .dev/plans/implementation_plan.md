# WebShell 实施计划

## 项目状态概览
**目标**: 把网站做成独立安装的 Android App（每站一个 APK，独立进程 / 数据 / 通知 / 权限）
**当前状态**: Phase 1 完成并验收
**项目性质**: 个人工具，开源
**技术约束**: 每 App 差异只能在 config.json / Manifest 字面值 / 图标 PNG（工厂改写要求）
**分支架构**: 单分支 main
**下一阶段**: 无既定计划；候选见 `.agents/notes/deferred.md`

## 技术架构
- **语言**: Kotlin（View 系统），构建逻辑 Kotlin DSL + buildSrc
- **构建**: AGP 9.4.1 / Gradle 9.6.0 / JDK 21，minSdk 26，target/compileSdk 37
- **壳**: 系统 WebView + androidx.webkit / splashscreen / swiperefreshlayout
- **工厂**: Material 3 界面、jsoup（网页信息）、apksig（签名）、自研 AXML 改写
- **架构特点**: 同一份壳代码，PC 端用 productFlavors 批量构建，手机端改写 template flavor 的 APK

## 开发历史

### [✓] Phase 1: 网页壳 + PC 批量构建 + 手机端工厂 (已完成)
**详细计划**: 无独立计划文档（计划在对话中确认，见 conversationlog-001）
**完成时间**: 2026-10-28 02:10（耗时约 3 小时）
**技术成果**: 一份 apps.json 批量生成独立 App，以及手机上输入网址即可生成并安装独立 App 的工厂

- [✓] **PC 批量构建**: apps.json → productFlavors，图标自动生成，git 版本号，签名兜底，build.sh，GitHub Actions
- [✓] **WebView 体验**: Cookie、返回键、外链、上传、下载、全屏、权限、edge-to-edge、启动页、错误页、状态恢复、注入、强制深色、下拉刷新防冲突
- [✓] **工厂 App**: 网页信息预填、二进制 Manifest 改写、zip 对齐、apksig 签名、PackageInstaller 安装
- [✓] **人机验证**: 识别 Cloudflare 验证页并用 WebView 小窗通过
- [✓] **验收确认**: 用户真机验收通过

**技术调整/发现/突破**:
- 原方案配置写在 BuildConfig → 为支持工厂改为运行时 config.json；label 改为 Manifest 字面值；图标背景改为 PNG；启动页背景改为系统默认
- AGP 默认缩短 release 资源路径，用 `android.enableResourceOptimizations=false` 关闭并在构建期校验
