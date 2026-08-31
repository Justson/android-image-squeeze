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

kotlin {
    compilerOptions {
        // Kotlin 类实现 Java 接口(如 ToolWindowFactory)时，默认会为接口的 default 方法
        // 生成委托 override。平台把其中若干 default 方法标了 @ApiStatus.Internal，
        // 于是 plugin verifier 会报「误用 internal API」—— 尽管我们一行都没写。
        // no-compatibility 让 Kotlin 直接沿用 Java 的 default 实现，不再生成这些 override。
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
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
    pluginVerification {
        ides {
            current()   // 构建基座本身(platform 233)，不额外下载
            // 再针对本机实际安装的 IDE 校验一遍。跨版本(233 -> 261)必须实测，
            // 只靠 untilBuild 放宽范围而不验证，等于把兼容性问题推给用户。
            // 用法: ./gradlew verifyPlugin -PverifyAgainstIde="C:/.../Android Studio-xxx"
            providers.gradleProperty("verifyAgainstIde").orNull?.let { local(it) }
        }
    }

    pluginConfiguration {
        id = "dev.squeeze.assetsqueeze"
        name = "Asset Squeeze"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // 属性留空时不写 until-build，让插件不被新版 IDE 按声明拒绝。
            // providers.gradleProperty 对空字符串仍返回 present，故显式转成 null。
            untilBuild = providers.gradleProperty("pluginUntilBuild")
                .map { it.trim() }
                .orElse("")
                .map { if (it.isEmpty()) null else it }
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
