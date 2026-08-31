plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

// 构建时拉取官方 libwebp 并抽出 cwebp（带 SHA-256 校验），二进制不进 git
apply(from = "cwebp.gradle.kts")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        // 基于 IC 构建：本插件不使用 Android 插件的私有 API，产物同样能装进 Android Studio。
        // 若将来要读 Android 的 ResourceRepository，再换成 androidStudio(...) 并锁定 AS 版本。
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        // 2.x 新版起代码插桩默认启用，不再需要显式声明 instrumentationTools()
    }
}

intellijPlatform {
    // 官方 verifier：检查 API 兼容性、plugin.xml 合法性、是否误用 internal API。
    // current() = 只校验当前构建基座(IC 2023.3.8 = platform 233)，不额外下载 IDE。
    // 想覆盖更多版本可改成 recommended()，但会按 since/untilBuild 拉一堆发行包。
    pluginVerification {
        ides { current() }
    }

    pluginConfiguration {
        id = "dev.squeeze.assetsqueeze"
        name = "Asset Squeeze"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks {
    // 本地调试：直接把插件跑进 Android Studio
    runIde {
        // 指向本机 IDE 安装目录，省去再下一份 IDE
        // ideDir.set(file("C:/Program Files/Android/Android Studio"))
    }
}
