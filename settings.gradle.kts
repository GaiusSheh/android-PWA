// Gradle 的「工程描述」：相当于 CMake 顶层 CMakeLists.txt 里的 project() + add_subdirectory()。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止子模块自己声明仓库，所有依赖统一从这里拉取
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "webshell"

// :app      网页壳。所有网站共用这一份代码，PC 端按 apps.json 生成 productFlavors；
//           其中的 template flavor 产出「模板 APK」
// :factory  手机上的工厂 App：输入网址，改写模板 APK 生成新 App 并安装
include(":app")
include(":factory")
