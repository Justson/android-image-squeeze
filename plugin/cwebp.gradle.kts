/**
 * 构建时拉取官方 libwebp 发行包，抽出 cwebp 打进插件资源。
 *
 * 为什么不直接把二进制提交进 git：四个平台合计约 11MB，且每次升 libwebp 都会在
 * 仓库里留下一份历史。改成构建时下载后仓库保持干净，产物 zip 仍然是完整的。
 *
 * 供应链：URL 只用 Google 官方 downloads.webmproject.org，且逐个校验 SHA-256。
 * 校验和是从官方 https 下载的归档实测得出，升级版本时必须同步更新。
 */
val webpVersion = "1.4.0"

/** platformDir(与 WebpCodec.platformDirName 对齐) -> (归档名, SHA-256, 归档内 cwebp 路径) */
val cwebpArtifacts = mapOf(
    "windows-x64" to Triple(
        "libwebp-$webpVersion-windows-x64.zip",
        "0e77cfd844f9a25ee10c7bc4fef145a0a855e3be61f9ce0e1893c27a604dab81",
        "libwebp-$webpVersion-windows-x64/bin/cwebp.exe",
    ),
    "macos-arm64" to Triple(
        "libwebp-$webpVersion-mac-arm64.tar.gz",
        "5a6d1682b2eff621218474a0a54557f3a22beb4805576732edee56fe133f6a4e",
        "libwebp-$webpVersion-mac-arm64/bin/cwebp",
    ),
    "macos-x64" to Triple(
        "libwebp-$webpVersion-mac-x86-64.tar.gz",
        "51cb25121e553a724ccff7ddafdbf82928628a1a1cedb4e14d742560cb8f4f82",
        "libwebp-$webpVersion-mac-x86-64/bin/cwebp",
    ),
    "linux-x64" to Triple(
        "libwebp-$webpVersion-linux-x86-64.tar.gz",
        "94ac053be5f8cb47a493d7a56b2b1b7328bab9cff24ecb89fa642284330d8dff",
        "libwebp-$webpVersion-linux-x86-64/bin/cwebp",
    ),
)

val cwebpOutDir = layout.buildDirectory.dir("cwebp-bin")
val cwebpCacheDir = layout.buildDirectory.dir("cwebp-cache")

val fetchCwebp by tasks.registering {
    group = "build setup"
    description = "下载官方 libwebp 并抽出 cwebp（校验 SHA-256）"
    outputs.dir(cwebpOutDir)

    doLast {
        val base = "https://storage.googleapis.com/downloads.webmproject.org/releases/webp"
        val cache = cwebpCacheDir.get().asFile.apply { mkdirs() }
        val out = cwebpOutDir.get().asFile

        cwebpArtifacts.forEach { (platform, spec) ->
            val (archive, sha256, entry) = spec
            val target = File(out, platform).apply { mkdirs() }
            val exeName = if (platform.startsWith("windows")) "cwebp.exe" else "cwebp"
            val exe = File(target, exeName)
            if (exe.exists()) return@forEach

            val local = File(cache, archive)
            if (!local.exists()) {
                logger.lifecycle("下载 $archive …")
                java.net.URI("$base/$archive").toURL().openStream().use { input ->
                    local.outputStream().use { input.copyTo(it) }
                }
            }

            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(local.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == sha256) {
                "$archive 校验失败：\n  期望 $sha256\n  实际 $actual\n" +
                        "如果确实是官方升级了版本，请核对后更新 cwebp.gradle.kts 里的校验和。"
            }

            val unpacked = File(cache, "unpacked/$platform").apply { mkdirs() }
            copy {
                from(if (archive.endsWith(".zip")) zipTree(local) else tarTree(resources.gzip(local)))
                into(unpacked)
            }
            val src = File(unpacked, entry)
            check(src.exists()) { "归档 $archive 中找不到 $entry" }
            src.copyTo(exe, overwrite = true)
            exe.setExecutable(true)     // Windows 上是 no-op；打包进 zip 后由运行时再置一次
            logger.lifecycle("cwebp -> $platform (${exe.length() / 1024} KB)")
        }
    }
}

// 把 build/cwebp-bin/<platform>/cwebp 映射到 jar 内的 bin/<platform>/cwebp。
// 二进制只存在于 build 目录，不进 git。
tasks.named<ProcessResources>("processResources") {
    dependsOn(fetchCwebp)
    from(cwebpOutDir) { into("bin") }
}
