# Asset Squeeze — IDE 插件实施方案

Android 图片素材体检与压缩插件。当前状态：`core` **16 个测试全绿**（含用真实 cwebp
跑的端到端编码验证）；cwebp 分发链路已打通；`plugin` 待补 UI 装配。

---

## 0. 为什么要做（以及为什么不是 "Convert to WebP"）

Android Studio 自带 **Convert to WebP…**，它只做一件事：把选中的 PNG 转成 WebP。
它不会告诉你：

| 它不管的事 | 后果 | 本插件怎么做 |
|---|---|---|
| 这张图的 alpha 是渐变还是硬边缘 | 渐变 alpha 用有损压会出**色带** | `alphaProfile.isHardEdged` 分流两条路线 |
| 压完有没有引入色带 | 逐像素误差看不出来，人眼一看一个准 | `banding()` 沿渐变方向统计色阶/台阶 |
| 这图是不是 UI 资源 | `assets/` 下的美颜滤镜查找表被压 = 滤镜效果变了 | 硬排除 `assets/`、`.9.png`、`build/` |
| 图身后是什么颜色 | 不知道就没法「烘焙底色」这条 8~24x 的路 | `HostBackgroundResolver` 解析布局父链 |
| 噪点是抖动还是真实纹理 | 纹理图去噪/降采样 = 毁图 | `noiseKind()` 用模糊前后差异判别 |

**一句话**：我们不重造编码器，卖点是**决策逻辑**。

---

## 1. 技术选型（已验证）

| 项 | 选择 | 验证结果 |
|---|---|---|
| 目标平台 | IntelliJ Platform **233**（AS 2023.3 Iguana） | AS 2023.3 `AI-233.14808.21`，JBR 17 ✓ |
| 构建基座 | `intellijIdeaCommunity("2023.3.8")`，**不用 AI** | 不碰 Android 私有 API，装 AS/IDEA 都行 |
| 语言/JDK | Kotlin **2.2.0** / JDK 17 | Corretto 17 ✓ |
| Gradle | **9.1.0** + IntelliJ Platform Gradle Plugin **2.18.1** | 见下方版本联动说明 ✓ |
| WebP **解码** | TwelveMonkeys `imageio-webp` 3.10.1（纯 Java 只读） | 已在 core 依赖中 ✓ |
| WebP **编码** | 调用 `cwebp` 进程 | 见下方 §2 |

### 版本联动（升级时注意）

这三者是锁死的，不能单独升：

```
IntelliJ Platform Gradle Plugin 2.18.1  要求  Gradle >= 9.0
Gradle 9.1.0                            要求  Kotlin Gradle Plugin >= 2.x
```

从 2.1.0 升到 2.18.1 时连带做的两处适配：
- Gradle 8.13 -> 9.1.0，Kotlin 1.9.22 -> 2.2.0
- 去掉 `instrumentationTools()` —— 新版起代码插桩默认启用，该 API 已移除

`cwebp.gradle.kts` 里的 `zipTree` / `tarTree` / `copy {}` 在 Gradle 9 下无需改动，
已实跑验证产物一致。

### 为什么编码不用 JNI

JVM 上没有纯 Java 的 WebP 编码器。两个选项：

- **JNI（webp-imageio）**：要为 win-x64 / mac-x64 / mac-arm64 / linux-x64 各打一份 native lib，
  Apple Silicon 上还有代码签名与 Gatekeeper 隔离问题。
- **cwebp 进程**（选它）：Google 官方发布、BSD-3、约 1MB/平台，行为与 `libwebp` 完全一致，
  出问题时能用命令行复现。

---

## 2. cwebp 分发链路（已完成）

**不把二进制提交进 git**：四个平台合计约 11MB，且每次升级都会在仓库历史里留一份。
改成 `plugin/cwebp.gradle.kts` 在构建时下载：

| 平台 | 归档 | cwebp 大小 |
|---|---|---|
| windows-x64 | `libwebp-1.4.0-windows-x64.zip` | 726 KB |
| macos-arm64 | `libwebp-1.4.0-mac-arm64.tar.gz` | 2516 KB |
| macos-x64 | `libwebp-1.4.0-mac-x86-64.tar.gz` | 3170 KB |
| linux-x64 | `libwebp-1.4.0-linux-x86-64.tar.gz` | 4643 KB |

**供应链**：只用 Google 官方 `downloads.webmproject.org`，逐个校验 SHA-256
（校验和写死在 `cwebp.gradle.kts`，升级版本必须同步更新，否则构建直接失败）。

产物落到 `build/cwebp-bin/<platform>/`，由 `processResources` 映射进 jar 的 `bin/<platform>/`。

**运行时**（`CwebpProvider`）：jar 里的文件不能直接执行，首次使用释放到
`PathManager.getSystemPath()/asset-squeeze/bin/`，只释放一次、IDE 重启复用。
**非 Windows 平台必须显式 `setExecutable(true)`** —— jar 不保留 POSIX 权限位，
解出来是 0644，直接跑会 Permission denied。

`WebpCodec.locate()` 三级回退：**设置里的显式路径 → 随包二进制 → PATH 上的系统 cwebp**。

**许可证**：libwebp 是 BSD-3，条款要求「以二进制形式再分发时必须重现版权声明」。
坑在于**官方二进制归档里并没有 COPYING**，只在 README.md 里指了个链接。
因此许可证文本从上游源码 tag `v1.4.0` vendor 进仓库
（`plugin/src/main/resources/licenses/libwebp-COPYING.txt`），随 jar 一起分发；
`processResources` 有一道 check 确保它确实进了产物。详见 `THIRD-PARTY-NOTICES.md`。

---

## 3. 模块结构

```
asset-squeeze/
├── core/                     纯 JVM，无 IDE 依赖，可单测（已完成）
│   ├── ImageStats.kt         噪点/色带/ΔRGB/alpha 剖面，所有阈值+实测数据
│   ├── WebpCodec.kt          cwebp 封装 + 三级定位
│   ├── Analyzer.kt           路线决策 → AssetReport
│   └── HostBackgroundResolver.kt   布局父链解析宿主底色
└── plugin/                   IDE 集成
    ├── ComparePanel.kt       并排预览 + 底色切换（已完成）
    ├── ApplyService.kt       写回/改名（已完成）
    ├── InspectFileAction.kt   右键入口，后台线程分析（已完成）
    ├── SqueezeDialog.kt       预览对话框 + 应用门禁（已完成）
    ├── CwebpProvider.kt       从 jar 释放二进制（已完成）
    ├── SqueezeSettings.kt     设置页（已完成）
    ├── SqueezeToolWindow.kt   工具窗 + 表格 + 批量应用（已完成）
    └── ScanService.kt         并行扫描（已完成）
```

**分层原则**：所有算法留在 `core`，`plugin` 只做 UI 和 VFS。
这样阈值调整能靠单测验证，不用反复启 IDE。

---

## 4. 需求实现方案

### 4.1 需求①：原图/压缩图预览 + 一键替换

已实现 `ComparePanel` + `ApplyService`。两个关键设计：

**预览必须两侧同底色，且底色可切**（`ComparePanel`）

这不是锦上添花。IDE 自带的图片 Diff 把透明区渲染在**深色主题背景**上，
于是原图看着浓、烘焙到白底的压缩图看着惨白 —— 实际使用中因此**误判过两次**，
两次都以为压坏了，实际两者在真实宿主底色上 ΔRGB 只有 1.0。

下拉框选项顺序（默认选第一个）：
`宿主底色（解析得到）` → `白` → `灰` → `黑` → `棋盘格`。
解析不到宿主底色时，info 栏明确提示「预览仅供参考」。

**替换必须走 WriteCommandAction**（`ApplyService`）
- 进 undo 栈，Ctrl+Z 可整体回退
- 自动刷 VFS + Local History

PNG→WebP 改名的四条硬规则见 `ApplyService` 类注释，其中第 2 条是踩过的坑：
**绝不能把 WebP 字节写进 `.png` 文件名**，AAPT 会按 PNG 处理。

### 4.2 需求②：遍历工程给出优化列表

`ScanProjectAction` → `Task.Backgroundable`：

```kotlin
object : Task.Backgroundable(project, "Scanning assets", true) {
    override fun run(indicator: ProgressIndicator) {
        val files = FileTypeIndex.getFiles(...)   // 或 VfsUtil.iterateChildrenRecursively
            .filter { !analyzer.isExcluded(it.toIoFile()) && it.length >= minBytes }
        files.forEachIndexed { i, f ->
            indicator.checkCanceled()
            indicator.fraction = i.toDouble() / files.size
            results += analyzer.analyze(f.toIoFile())
        }
    }
}
```

ToolWindow 用 `TableView<AssetReport>`，列：

| 列 | 来源 | 说明 |
|---|---|---|
| 文件 | `report.file` | 双击定位到 Project View |
| 当前 KB | `currentBytes` | 默认按「可省字节」倒序 |
| 半透% | `alpha.semiRatio` | **选路线的唯一依据** |
| αVar | `alphaBanding` | 色阶/台阶，判断是不是渐变型 alpha |
| 噪点 | `noise.value` + `kind` | `纹理` 行禁用降采样选项 |
| 路线 | `route` | KEEP_ALPHA / BAKE_BACKGROUND / NONE |
| 预估 KB | `estimatedBytes` | |
| 警告 | `warnings` | 悬浮显示全文 |

**性能**：`analyze()` 每张图要跑 2 次 cwebp。1000 张图串行约 3~5 分钟。
优化：`ForkJoinPool` 并行（cwebp 是独立进程，天然可并行），并对
`size < 20KB` 的直接跳过。结果按 `file.path + file.modificationStamp` 缓存到
`PropertiesComponent`，二次扫描秒开。

**批量应用**：多选行 → `Apply Selected`，逐个走 `ApplyService`，
`BAKE_BACKGROUND` 路线若宿主底色未解析出来则强制跳过并计入报告。

### 4.3 需求③：新工程

就是本仓库。

---

## 5. 分阶段计划

| 阶段 | 内容 | 验收 | 状态 |
|---|---|---|---|
| **P0** | core 算法 + 单测 | `./gradlew :core:test` 绿 | ✅ **已完成，16/16 通过** |
| **P1** | cwebp 下载/校验/释放链路 + 许可证合规 | 能对样例图编码出 webp | ✅ **已完成**（见 §2） |
| **P2** | 单文件流程：右键 → 分析 → 预览 → 替换 | `verifyPlugin` 判定 Compatible | ✅ **已完成** |
| **P3** | 全工程扫描 + 表格 + 批量应用 | `verifyPlugin` 干净 Compatible | ✅ **已完成** |
| **P4** | 宿主底色解析接 PSI | 能解析出 `#F0F6FB` 这类 | ⬜ |
| **P5** | 结果缓存、并行、设置页 | 二次扫描 <5s | ⬜ |

**P2 的落点**：`InspectFileAction`（右键入口，分析在后台线程跑）
→ `SqueezeDialog`（DialogWrapper，内嵌 ComparePanel）→ `ApplyService`。
「应用」按钮的启用条件是**有意收紧**的，以下三种情况一律禁用而不是给个能点的危险按钮：
- 路线为 NONE（收益不足，压了只是有损叠有损）
- 压缩后引入色带（这正是最想避免的失真）
- 烘焙路线但宿主底色没解析出来（猜白色会在线上留下色差方块）

**P3 的两个关键点**：

1. **宿主底色必须一次建全量索引**。`HostBackgroundResolver.resolveAll()` 遍历一次布局
   建立 `(资源类型, 资源名) -> 底色` 的映射。逐个调 `resolve()` 是 O(资源数 × 布局数)，
   几百张图就会卡到不可用。注意 key 必须带资源类型 —— `@mipmap/x` 与 `@drawable/x` 不是同一个资源。
2. **批量应用要守住和单文件一样的门禁**。烘焙路线在底色未确定时一律跳过并计入报告，
   不能因为是批量就放松 —— 猜白色会在线上留下色差方块。

**Kotlin 实现 Java 接口的坑**：Kotlin 类实现 `ToolWindowFactory` 时会为接口的
default 方法生成委托 override，而平台把其中若干标了 `@ApiStatus.Internal`，
于是 verifier 报「6 处误用 internal API」—— 尽管我们一行都没写。
加 `-jvm-default=no-compatibility` 后归零。

**P4 的升级点**：现在 `HostBackgroundResolver` 用 DOM 自己解析 XML。
接 PSI（`XmlFile` / `DomManager`）后能拿到 IDE 已有的资源索引，
支持 `?attr/colorSurface` 这类主题属性，覆盖率会显著提升。
代价是要 `<depends>org.jetbrains.android</depends>`，插件就只能装 AS 了。
**建议保留 DOM 版做 fallback，PSI 版做可选增强。**

---

## 6. 立即可执行的命令

```bash
cd <repo-root>
export JAVA_HOME=/path/to/jdk17

# 跑 core 单测（当前可用）
./gradlew :core:test

# 阈值标定探针：打印合成样本 + 真实素材的判别指标
./gradlew :core:test --tests "*ThresholdProbe*" --rerun-tasks \
  -Dsqueeze.sampleRoot="/path/to/android-project/app/src/main/res"

# P1 完成后：把插件跑进本机 Android Studio
./gradlew :plugin:runIde        # 需先在 plugin/build.gradle.kts 里放开 ideDir

# 出安装包
./gradlew :plugin:buildPlugin   # 产物 plugin/build/distributions/*.zip
```

---

## 7. 已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 首次构建要下 IC 发行包 | 约 1GB | 有网即可，`cache-redirector.jetbrains.com` 已配好 |
| `analyze()` 慢 | 每张图 2 次 cwebp 进程 | 并行 + 结果缓存 + 小文件跳过 |
| 宿主底色解析覆盖率有限 | 实测 19 个候选里只有 2 个能唯一确定 | 解析不到就禁用烘焙路线，不猜 |
| 平台 API 变动 | `untilBuild=252.*` | 只用稳定 API，不碰 internal |
| 误压非 UI 资源 | assets 下的 SDK 数据文件 | `isExcluded()` 硬排除，已写测试 |

---

## 8. 阈值来源（改之前先看这里）

所有阈值都有实测支撑，不是拍脑袋。改动前先跑 `ThresholdProbe` 重新标定。

| 常量 | 值 | 依据 |
|---|---|---|
| `HARD_ALPHA_SEMI_RATIO` | 0.02 | 半透明>2% 即渐变型，实测这类图 KEEP_ALPHA 只有 1.0~1.1x |
| `OPAQUE_RATIO_FOR_MASK` | 0.05 | 不透明像素够多就只测它们，结论与底色无关 |
| `DITHER_DROP_MIN` | 0.6 | 抖动 0.66~0.84；真实素材 12 个样本 -0.26~0.34 |
| `DITHER_DELTA_MAX` | 8.0 | 排除病态高频（棋盘 delta 69~95）。**必须绝对值**，早期用相对阈值会误判 |
| `bandingOk` 台阶松弛 | +1 | alpha_q=70 时台阶 2→18，必须判失败 |

---

## 9. 与现有 Python 脚本的关系

`一份 Python 原型脚本` 是本插件的原型，两条路线和踩过的坑
都在它的文件头注释里。插件上线后该脚本可留作 CI 用途（批量体检不需要 IDE）。

注意：**脚本里 `judge()` 用的仍是旧的相对阈值** `delta < n0*0.5`，
已被本次标定证明会误判 amp=3 的抖动，若继续使用请同步改成
`drop > 0.6 && delta < 8.0`。
