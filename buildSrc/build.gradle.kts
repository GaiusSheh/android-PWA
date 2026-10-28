plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gson)
}

// 与根工程相同的 build 目录重定向逻辑（见根目录 build.gradle.kts 的说明）
val buildRoot: String? = providers.gradleProperty("webshell.buildRoot")
    .orElse(providers.environmentVariable("WEBSHELL_BUILD_ROOT"))
    .orNull
if (!buildRoot.isNullOrBlank()) {
    layout.buildDirectory.set(file("$buildRoot/webshell/_buildSrc"))
}
