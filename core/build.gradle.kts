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
    // -Dsqueeze.sampleRoot=... 让 ThresholdProbe 读真实素材做阈值标定
    // -Dsqueeze.cwebp=...     指定 cwebp 路径，未指定则从 PATH 找，都没有就跳过编码相关用例
    systemProperty("squeeze.sampleRoot", System.getProperty("squeeze.sampleRoot") ?: "")
    systemProperty("squeeze.cwebp", System.getProperty("squeeze.cwebp") ?: "")
    testLogging { showStandardStreams = true }
}
