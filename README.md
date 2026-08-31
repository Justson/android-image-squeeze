# Asset Squeeze

An IntelliJ / Android Studio plugin that audits and compresses Android image assets.

**It is not another "Convert to WebP".** Android Studio already ships one. The value here is
the *decision logic* it lacks — figuring out which route each image should take, and refusing
to compress the ones that would break.

> 状态：`core` 模块已可编译，单测全绿；`plugin` 为可运行骨架。
> 完整方案见 [docs/PLAN.md](docs/PLAN.md)。

---

## Why

Compressing Android assets naively goes wrong in ways that are easy to miss:

| Trap | What happens |
|---|---|
| Lossy alpha on a **gradient** alpha channel | Visible banding. And any `alpha_quality` high enough to avoid it saves nothing. |
| Judging quality by per-pixel ΔRGB | ΔRGB stays ~0.5 while the image is visibly striped. It cannot see structural distortion. |
| Measuring "noise" without compositing | A PNG's transparent region holds undefined RGB garbage. Measured 7.37 vs 0.47 on the same file. |
| Downscaling a textured image | Mean ΔRGB looks fine (diluted by flat areas); the max tells the truth. |
| Compressing everything under `assets/` | Beauty-filter LUTs are PNGs too. Compressing them changes the filter output. |
| Writing WebP bytes into a `.png` filename | AAPT treats it as PNG. |
| Baking a background color into a shared asset | Looks right in one layout, off-color in the other eleven. |

Every one of these is encoded as a check, a threshold, or a regression test in `core`.

---

## How it decides

The route is chosen by **the shape of the alpha channel**, nothing else:

```
semi-transparent pixels (alpha 1..249) <= 2%   ->  KEEP_ALPHA
    lossy RGB + lossless alpha.
    A binary alpha channel cannot band, so this needs no knowledge of the host
    background and the asset stays reusable. Measured 4~21x.

otherwise (gradient alpha)                     ->  BAKE_BACKGROUND
    Composite onto the host background, drop alpha entirely.
    For these images almost all the bytes live in the alpha plane, so keeping
    alpha saves nothing — it has to go. Measured 8~24x.
    Requires the asset to be used over exactly one known solid color.
```

Three independent metrics, each catching what the others miss:

- `noiseKind()` — dither vs. real texture, via how much a σ=1 blur changes the picture
- `banding()` — level counts / step heights along the gradient; catches quantization stripes
- `deltaRgb()` — per-pixel error; catches color shifts, **blind to banding**

---

## Build

Requires JDK 17.

```bash
./gradlew :core:test            # algorithms + regression tests
./gradlew :plugin:runIde        # launch a sandbox IDE with the plugin
./gradlew :plugin:buildPlugin   # -> plugin/build/distributions/*.zip
```

Calibrate the dither/texture thresholds against your own assets:

```bash
./gradlew :core:test --tests "*ThresholdProbe*" --rerun-tasks \
  -Dsqueeze.sampleRoot="/path/to/android-project/app/src/main/res"
```

---

## Layout

```
core/     pure JVM, no IDE dependency, unit-testable
  ImageStats.kt              metrics + every threshold with its measured basis
  WebpCodec.kt               cwebp wrapper, three-tier binary lookup
  Analyzer.kt                route decision -> AssetReport
  HostBackgroundResolver.kt  walks layout XML parent chains for the host color
plugin/   IDE integration
  ComparePanel.kt            side-by-side preview with switchable background
  ApplyService.kt            write-back / rename via WriteCommandAction
```

`ComparePanel` renders both sides on the **same, user-selectable** background. This is not a
nicety: the IDE's built-in image diff composites transparency over the dark theme, which makes
an original look richer than a background-baked version of itself. That illusion caused two
false "you broke it" calls during development.

---

## Not implemented yet

`plugin` still needs the tool window, the scan action, and the settings page — see
[docs/PLAN.md](docs/PLAN.md) §5 for the phased plan. P1 (bundling the `cwebp` binaries) is the
only hard blocker.

## License

MIT for this repository. Bundled `cwebp` binaries are BSD-3 (libwebp, Google) and ship with
their own `COPYING`.
