# Phase 3: 可公开发布的工厂（不含私钥，手机上自生成签名密钥）详细计划

## 计划概述
**阶段名称**: Phase 3 - 可公开发布的工厂
**预估工期**: 约半天（含 WSA / 真机测试）
**核心目标**: 工厂 APK 可以放进 GitHub Release 供别人直接下载，而不泄露开发者的签名私钥
**当前状态**: 已完成并验收（2026-11-02），随 v1.1.0 发布

## 现状分析
- 工厂构建时把 keystore 导出的私钥（`assets/signing/key.pk8` + `cert.der`）打包进 APK，手机与电脑生成的 App 签名一致
- 代价：工厂 APK 等同私钥，任何拿到它的人都能签出与你的 App 同包名同签名的「更新」并接管其数据 → 不能公开发布
- 关键事实：AndroidKeyStore 生成密钥对时自动生成自签名证书，不需要额外库；apksig 通过标准 JCA `Signature` 签名，应可直接使用 AndroidKeyStore 的私钥（待实测）

## 设计
- **两种工厂，同一份代码**：
  - 私有版（`./build.sh factory`，现状不变）：打包开发者私钥
  - 公开版（`./build.sh factory-public`，Gradle 属性 `-Pwebshell.publicFactory=true`）：不注册导出密钥的任务，APK 里没有 `assets/signing/`；输出 `dist/WebShell_Factory-public-<版本>.apk`
- **运行时选择密钥**（新 `gen/SigningKeys.kt`）：APK 里有 `signing/key.pk8` 就用它；没有就用 AndroidKeyStore 里别名固定的 RSA 2048 密钥，第一次需要时生成（SHA-256 / SHA-512，PKCS#1 填充，自签名证书 CN=WebShell，30 年有效期）
- **一次性提示**：公开版第一次生成前弹窗说明「密钥只在本机、不能导出；卸载工厂或清除数据后，已生成的 App 只能卸载重装」，确认后继续
- **公开版必须用正式 keystore 签名工厂本身**：否则工厂无法跨版本更新；没有正式 keystore 时构建直接失败
- 用户确认：公开版与私有版**同包名**（不另起包名）；~~自己继续用私有版，不要在自己手机上装公开版~~ **[!] 已变化**：真机测试后用户用公开版把手机上 9 个 App 全部重新生成，之后这台手机只用公开版

## 详细实施步骤

### Phase 3-1: 实现
- `factory/build.gradle.kts`：`webshell.publicFactory` 属性；公开版不打包密钥、换输出文件名、无正式 keystore 时报错
- `build.sh`：新增 `factory-public` 参数
- `gen/SigningKeys.kt`（新）：打包密钥 / 设备密钥两条路径；`AppBuilder` 改用它
- `EditActivity.onBuild`：公开版首次生成前的提示框；新增中文文案

#### 验收标准
- [✓] 公开版 APK 不含 `assets/signing/`；私有版仍包含 `key.pk8`、`cert.der`
- [✓] 工厂单元测试通过（私有版路径不变）；`build.sh factory factory-public` 同时传入时报错
- [✓] WSA（2026-11-02）：公开版首次生成弹出提示；生成的 App 安装成功，v2 + v3 签名校验通过，证书是设备密钥（RSA 2048，指纹 `b87c142e…`），不是开发者证书（`29be4aeb…`）
- [✓] WSA：同一 App 再生成一次静默覆盖安装（versionCode 2001 → 2002，同一设备密钥，不再弹提示）。顺带验证了 Phase 1 遗留的「免确认更新」
- [✓] WSA：私有版生成的 App 签名仍是开发者证书，不弹提示
- [✓] 真机（nubia NX721J，2026-11-02）：公开版生成 ezgif 并安装成功，v2 + v3 校验通过，证书 `CN=WebShell device key`（RSA 2048，指纹 `893452bc…`，与 WSA 的不同）。硬件密钥下 apksig 签名正常
  - 顺带验证：手机上已有私有版生成的同名 App 时，公开版生成后安装被系统拒绝（签名不符），原 App 不受影响；卸载后再生成即可

#### 实施中的发现
- 开发者证书的 DN 也是 `CN=WebShell`，与设备密钥同名，只能靠指纹区分 → 设备密钥证书改为 `CN=WebShell device key`（只影响之后新生成的密钥）

### Phase 3-2: 收尾
- [✓] README：两个版本的区别表、「安装失败：UPDATE_INCOMPATIBLE」的三种常见原因、构建命令、手动发布流程；`.agents/rules.md` 安全条款区分两种工厂
- [✓] 发布方式：用户选择随新版本 v1.1.0 发布（tag、代码与 APK 一致）

## 技术风险评估
- **apksig 与 AndroidKeyStore 私钥不兼容**（中）：缓解：WSA 与真机实测；不行则改为普通 Java 密钥存在工厂私有目录（需自己生成自签名证书，工作量更大）
- **用户误把公开版装到自己手机上**（低）：同包名同签名会覆盖私有版，之后生成的 App 换了密钥。用户已知悉，README 写明
- **公开版用户丢失密钥**（已知限制）：README 与首次提示写明

## 完成标准
1. [✓] Phase 3-1 验收项全部通过
2. [✓] 工厂单元测试通过
3. [✓] README / 规则更新
4. [✓] 人类验收测试通过（用户在手机上用公开版重新生成全部 App）
