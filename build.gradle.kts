plugins {
    kotlin("jvm") version "1.9.22" apply false
    id("org.jetbrains.intellij.platform") version "2.1.0" apply false
}

allprojects {
    group = property("pluginGroup") as String
    version = property("pluginVersion") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
    }
}
