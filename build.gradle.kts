// 顶层构建脚本：只声明插件版本（apply false），真正的配置在 app/build.gradle.kts。
plugins {
    alias(libs.plugins.android.application) apply false
}

// 可选：把所有 build/ 输出重定向到工程目录之外。
// 工程放在 OneDrive 等同步盘里时，build/ 下成千上万的中间文件会拖慢同步、甚至锁文件。
// 在 ~/.gradle/gradle.properties（本机私有，不进仓库）里写：
//     webshell.buildRoot=C:/Users/<you>/AppData/Local/webshell-build
// 或设置环境变量 WEBSHELL_BUILD_ROOT。未设置时保持 Gradle 默认（<module>/build）。
val buildRoot: String? = providers.gradleProperty("webshell.buildRoot")
    .orElse(providers.environmentVariable("WEBSHELL_BUILD_ROOT"))
    .orNull
if (!buildRoot.isNullOrBlank()) {
    allprojects {
        layout.buildDirectory.set(file("$buildRoot/${rootProject.name}/${project.path.replace(':', '_')}"))
    }
}
