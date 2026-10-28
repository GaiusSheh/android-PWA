import com.android.build.api.artifact.SingleArtifact
import webshell.AppsConfig
import webshell.CopyApkTask
import webshell.GenerateSiteResourcesTask
import webshell.GitVersion
import webshell.Signing

// AGP 9 内置了 Kotlin 编译支持，不再需要单独 apply kotlin-android 插件
plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------
// 1. 读取配置与版本号（都在 Gradle 配置阶段完成，出错立即中止构建）
// ---------------------------------------------------------------------------
val apps = AppsConfig.load(rootProject.file("apps.json"))
// 模板 App 也作为一个 flavor 构建，它的 APK 会被打包进工厂 App（见 factory/build.gradle.kts）
val allFlavors = apps + AppsConfig.TEMPLATE
val gitVersion = GitVersion.compute(providers, rootProject.rootDir)

// ---------------------------------------------------------------------------
// 2. 签名：优先 keystore.properties，其次环境变量；都没有时 release 退回 debug 签名
// ---------------------------------------------------------------------------
val signing = Signing.load(rootProject.rootDir, providers)
if (!signing.isRelease) {
    // CI 打 tag 发布时设置 WEBSHELL_REQUIRE_RELEASE_KEYSTORE=true，禁止用 debug 签名混进正式发布
    if (providers.environmentVariable("WEBSHELL_REQUIRE_RELEASE_KEYSTORE").orNull == "true") {
        throw GradleException("要求使用 release keystore，但 keystore.properties / WEBSHELL_KEYSTORE_* 环境变量不完整")
    }
    logger.warn(
        "[webshell] 未找到 release keystore，release APK 将使用 debug 签名。" +
            "这种 APK 以后无法被正式签名的版本覆盖安装，详见 README「生成 keystore」。"
    )
}

// ---------------------------------------------------------------------------
// 3. Android 配置
// ---------------------------------------------------------------------------
android {
    // namespace 是「代码」的包名（R 类、BuildConfig 所在包），所有 App 共用；
    // applicationId 才是「安装到手机上的」包名，每个 flavor 各不相同。两者可以不一样。
    namespace = "com.yuanfeng.webshell"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = gitVersion.code
        versionName = gitVersion.name
    }

    buildFeatures {
        buildConfig = true // 只用到 BuildConfig.DEBUG；每个 App 的配置在 assets/webshell/config.json
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 每个网站 = 一个 productFlavor。flavor 之间共享全部代码，只在下面这些字段和生成的资源上不同。
    flavorDimensions += "site"
    productFlavors {
        allFlavors.forEach { app ->
            create(app.id) {
                dimension = "site"
                applicationId = "com.yuanfeng.webshell.${app.id}"
                manifestPlaceholders["appLabel"] = app.manifestLabel
                // 只有启动地址是 http:// 的 App 才允许明文流量（例如局域网自托管服务）
                manifestPlaceholders["usesCleartextTraffic"] = app.usesCleartext.toString()
            }
        }
    }

    signingConfigs {
        if (signing.isRelease) {
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8：压缩、优化、混淆代码并移除未使用资源，APK 更小
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signing.isRelease) "release" else "debug")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)
}

// ---------------------------------------------------------------------------
// 4. 变体级任务：为每个 flavor 生成图标 / config.json / 注入文件，并提供 dist 拷贝任务
// ---------------------------------------------------------------------------
/** 模板 APK 的固定输出位置，factory 模块从这里读取 */
val templateApkFile = layout.buildDirectory.file("template/webshell-template.apk")

androidComponents {
    onVariants { variant ->
        val app = allFlavors.single { it.id == variant.flavorName }
        val variantCap = variant.name.replaceFirstChar { it.uppercaseChar() }

        val generate = tasks.register<GenerateSiteResourcesTask>("generate${variantCap}SiteResources") {
            appId.set(app.id)
            appName.set(app.name)
            startUrl.set(app.url)
            allowedHosts.set(app.allowedHosts)
            themeColor.set(app.themeColor)
            userAgent.set(app.userAgent ?: "")
            enablePullToRefresh.set(app.enablePullToRefresh)
            forceDarkMode.set(app.forceDarkMode)
            app.icon?.let { iconFile.set(it) }
            app.injectCss?.let { injectCss.set(it) }
            app.injectJs?.let { injectJs.set(it) }
        }
        // 挂到变体的源目录上；AGP 会自动设置输出路径并建立任务依赖
        variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateSiteResourcesTask::resOutputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateSiteResourcesTask::assetsOutputDir)

        if (variant.buildType == "release") {
            if (app.id == AppsConfig.TEMPLATE.id) {
                tasks.register<CopyApkTask>("exportTemplateApk") {
                    description = "构建模板 APK，供工厂 App 打包"
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    outputFile.set(templateApkFile)
                }
            } else {
                tasks.register<CopyApkTask>("dist$variantCap") {
                    group = "distribution"
                    description = "构建 ${app.name} 的 release APK 并拷贝到 dist/"
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    outputFile.set(rootProject.layout.projectDirectory.file("dist/${app.fileSafeName}-${gitVersion.name}.apk"))
                }
            }
        }
    }
}

// ./gradlew distRelease：构建 apps.json 中全部 App 并输出到 dist/
tasks.register("distRelease") {
    group = "distribution"
    description = "构建 apps.json 中全部 App 的 release APK 并拷贝到 dist/"
    dependsOn(apps.map { "dist${it.capitalizedId}Release" })
}
