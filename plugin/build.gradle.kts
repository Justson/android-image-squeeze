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
        instrumentationTools()
    }
}

intellijPlatform {
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
