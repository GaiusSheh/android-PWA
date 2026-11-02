import com.android.build.api.artifact.SingleArtifact
import webshell.CopyApkTask
import webshell.ExportSigningKeyTask
import webshell.GitVersion
import webshell.PackTemplateTask
import webshell.Signing

// 工厂 App：在手机上输入网址，改写内置的模板 APK 生成独立 App 并安装。
plugins {
    alias(libs.plugins.android.application)
}

// 模板 APK 由 :app 的 template flavor 构建，需要先配置 :app 才能拿到它的输出路径
evaluationDependsOn(":app")

val gitVersion = GitVersion.compute(providers, rootProject.rootDir)
val signing = Signing.load(rootProject.rootDir, providers)
val templateApkFile = project(":app").layout.buildDirectory.file("template/webshell-template.apk")

// 公开版工厂（./build.sh factory-public）：不打包签名私钥，可以放进 Release 给别人下载；
// 生成的 App 改用手机上自生成的密钥签名（见 factory/.../gen/SigningKeys.kt）。
// 工厂 APK 本身仍需正式 keystore 签名，否则下一个版本无法覆盖更新它。
val publicFactory = providers.gradleProperty("webshell.publicFactory").map { it.toBoolean() }.getOrElse(false)
if (publicFactory && !signing.isRelease) {
    throw GradleException("公开版工厂必须用正式 keystore 签名（否则无法跨版本更新）：请先配置 keystore.properties 或 WEBSHELL_KEYSTORE_* 环境变量")
}

android {
    namespace = "com.yuanfeng.webshell.factory"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yuanfeng.webshell.factory"
        minSdk = 26
        targetSdk = 37
        // 与模板 APK 来自同一次构建，版本号相同；生成的 App 的 versionCode 以它为基数
        versionCode = gitVersion.code
        versionName = gitVersion.name
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.apksig)
    implementation(libs.jsoup)
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// 打包进工厂 assets 的两样东西：模板 APK、签名密钥（公开版不打包密钥）
// ---------------------------------------------------------------------------
fun ExportSigningKeyTask.configureFrom(info: webshell.SigningInfo) {
    storePath.set(info.storeFile.absolutePath)
    storePassword.set(info.storePassword)
    keyAlias.set(info.keyAlias)
    keyPassword.set(info.keyPassword)
    debugKeystore.set(!info.isRelease)
}

androidComponents {
    onVariants { variant ->
        val variantCap = variant.name.replaceFirstChar { it.uppercaseChar() }

        val packTemplate = tasks.register<PackTemplateTask>("pack${variantCap}Template") {
            templateApk.set(templateApkFile)
            dependsOn(":app:exportTemplateApk")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(packTemplate, PackTemplateTask::outputDir)
        if (!publicFactory) {
            val exportKey = tasks.register<ExportSigningKeyTask>("export${variantCap}SigningKey") {
                configureFrom(signing)
            }
            variant.sources.assets?.addGeneratedSourceDirectory(exportKey, ExportSigningKeyTask::outputDir)
        }

        if (variant.buildType == "release") {
            // 两种工厂文件名不同，避免把含私钥的私有版误当公开版发出去
            val fileName = if (publicFactory) "WebShell_Factory-public-${gitVersion.name}.apk" else "WebShell_Factory-${gitVersion.name}.apk"
            tasks.register<CopyApkTask>("distFactoryRelease") {
                group = "distribution"
                description = "构建工厂 App 的 release APK 并拷贝到 dist/"
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                outputFile.set(rootProject.layout.projectDirectory.file("dist/$fileName"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// JVM 单元测试：在电脑上直接验证「改写模板 APK + 签名」的核心逻辑
// ---------------------------------------------------------------------------
val exportTestSigningKey = tasks.register<ExportSigningKeyTask>("exportTestSigningKey") {
    configureFrom(signing)
    outputDir.set(layout.buildDirectory.dir("test-signing"))
}

tasks.withType<Test>().configureEach {
    dependsOn(":app:exportTemplateApk", exportTestSigningKey)
    val signingDir = exportTestSigningKey.flatMap { it.outputDir }
    val sdkDir = androidComponents.sdkComponents.sdkDirectory
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dwebshell.templateApk=${templateApkFile.get().asFile.absolutePath}",
            "-Dwebshell.signingDir=${signingDir.get().asFile.absolutePath}",
            "-Dwebshell.sdkDir=${sdkDir.get().asFile.absolutePath}",
        )
    })
}
