# Third-party notices

## libwebp (`cwebp`)

This plugin redistributes the `cwebp` command-line encoder from **libwebp 1.4.0**,
published by Google as part of the WebM project.

- Upstream: <https://chromium.googlesource.com/webm/libwebp>
- Binaries: <https://storage.googleapis.com/downloads.webmproject.org/releases/webp/>
- License: BSD-3-Clause — full text in
  [`plugin/src/main/resources/licenses/libwebp-COPYING.txt`](plugin/src/main/resources/licenses/libwebp-COPYING.txt)

The BSD-3 license requires that redistributions **in binary form** reproduce the copyright
notice. The official binary archives do **not** ship a `COPYING` file, so the license text is
vendored into this repository from the upstream source tree (tag `v1.4.0`) and packed into the
plugin jar alongside the binaries at `licenses/libwebp-COPYING.txt`.

Archives are downloaded at build time by `plugin/cwebp.gradle.kts` and each is verified against
a pinned SHA-256:

| Platform | Archive | SHA-256 |
|---|---|---|
| windows-x64 | `libwebp-1.4.0-windows-x64.zip` | `0e77cfd844f9a25ee10c7bc4fef145a0a855e3be61f9ce0e1893c27a604dab81` |
| macos-arm64 | `libwebp-1.4.0-mac-arm64.tar.gz` | `5a6d1682b2eff621218474a0a54557f3a22beb4805576732edee56fe133f6a4e` |
| macos-x64 | `libwebp-1.4.0-mac-x86-64.tar.gz` | `51cb25121e553a724ccff7ddafdbf82928628a1a1cedb4e14d742560cb8f4f82` |
| linux-x64 | `libwebp-1.4.0-linux-x86-64.tar.gz` | `94ac053be5f8cb47a493d7a56b2b1b7328bab9cff24ecb89fa642284330d8dff` |

**When upgrading libwebp**: update the version and all four checksums in `cwebp.gradle.kts`
(the build fails loudly otherwise), and re-vendor `COPYING` from the matching upstream tag.

## TwelveMonkeys ImageIO

WebP *decoding* uses `com.twelvemonkeys.imageio:imageio-webp`, BSD-3-Clause.
Resolved as a normal Maven dependency; not redistributed in source form here.
