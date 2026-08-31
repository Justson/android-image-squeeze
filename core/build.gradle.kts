plugins {
    kotlin("jvm")
}

dependencies {
    implementation(kotlin("stdlib"))
    // 纯 Java 的 WebP 解码器（只读）。写入走 cwebp 进程，见 WebpCodec.kt 里的说明。
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.test {
    // 探针需要读工程外的真实素材，用 -Dsqueeze.sampleRoot=... 传入
    systemProperty("squeeze.sampleRoot", System.getProperty("squeeze.sampleRoot") ?: "")
    testLogging { showStandardStreams = true }
}
