// buildSrc 是一个特殊的子构建：Gradle 会先编译它，再把产物放到主构建脚本的 classpath 上。
// 这里放「解析 apps.json / 生成图标 / 算版本号」等构建期逻辑，避免把几百行代码塞进 build.gradle.kts。
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // 复用根目录的版本目录，保证依赖版本只在一处维护
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
