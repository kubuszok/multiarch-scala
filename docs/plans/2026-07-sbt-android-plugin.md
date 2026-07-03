# Plan: standalone sbt Android plugin (extract + generalize the Android toolchain)

**Date**: 2026-07-01 (Fable-5 planning window, roadmap Topic 5, structural half)
**Owner repo**: multiarch-scala
**Companion doc**: `sge/docs/plans/2026-07-android-r8.md` (near-term R8 pipeline — MUST land first)
**Tracker**: GitHub issues in `kubuszok/multiarch-scala` (this repo has no `.rescale/`)

---

## 0. Context a fresh implementer lacks

### The extraction already happened — this plan is about *finishing* it, not starting it

The roadmap Topic 5 text says "extract the Android toolchain from sge's `AndroidBuild.scala`".
That is STALE. Ground truth (verified 2026-07-01):

- The whole Android pipeline was ALREADY moved out of sge in multiarch-scala commit
  `9b81523` ("Add NativeCrossAxis, ProjectMatrixOps.withCrossNative, and Android build
  plugin (extracted from sge-build)"). There is **no** `AndroidBuild.scala` in sge anymore.
- Current locations (all in this repo, module `plugin/`, artifact `sbt-multiarch-scala`):
  - `plugin/src/main/scala/multiarch/sbt/AndroidBuild.scala` (493 lines) — keys + task
    implementations (fat-jar assembly, DEX via `com.android.tools.r8.D8`, aapt2 link,
    zipalign, apksigner, adb install, native-`.so` extraction, APK zip surgery).
  - `plugin/src/main/scala/multiarch/sbt/AndroidSdk.scala` (225 lines) — SDK constants
    (minSdk 26 / targetSdk 35 / build-tools 35.0.0), SDK resolution (`ANDROID_HOME` →
    `ANDROID_SDK_ROOT` → cache dir), cmdline-tools download + `sdkmanager` install,
    tool-path helpers (`androidJar`, `d8`, `r8Jar`, `aapt2`, `apksigner`, `zipalign`, `ndkPath`).
  - `plugin/src/main/scala/multiarch/sbt/AndroidDeps.scala` (145 lines) — hardcoded
    PanamaPort AAR/JAR list, manual Maven-Central download, AAR → `classes.jar` +
    `libs/*.jar` extraction.
  - `plugin/src/main/scala/multiarch/sbt/AndroidPlugin.scala` (55 lines) — the AutoPlugin
    wrapper (noTrigger, requires JvmPlugin), `scala-android/` source-dir discovery.
- sge consumes the **published** `sbt-multiarch-scala % 0.3.0`
  (`sge/project/plugins.sbt:5`, `sge/sge-build/build.sbt:60`, `sge/project/Versions.scala:31`)
  and re-exports the keys (`sge-build/src/main/scala/sge/sbt/SgePlugin.scala:82-85`); the
  sge-side seam is `SgeAndroidPlatform` (`sge-build/src/main/scala/sge/sbt/SgePlugins.scala:142-155`),
  which only overrides `androidSdkCacheDir` and adds extension deps.

So "standalone plugin with no sge dependency" is ~80% true today. What is **left** and what
this plan delivers:

1. **Own artifact**: the Android toolchain is buried inside the fat `sbt-multiarch-scala`
   plugin, which drags `sbt-scala-native` and projectMatrix machinery every consumer must
   resolve. Deliverable: separate `sbt-android` artifact usable without any of that.
2. **PanamaPort is hardcoded and unconditional**: `AndroidBuild.taskSettings` wires
   `AndroidDeps.resolvePanamaPort` into `Compile / unmanagedJars` for EVERY consumer
   (`AndroidBuild.scala:68-73`). A non-FFM Android app still downloads 9 PanamaPort
   artifacts <!-- (GATE-FIX 2026-07-03, M6): was "7"; count unified with §3's "the 9 artifacts" -->.
   Deliverable: generic AAR-dependency mechanism; PanamaPort becomes an opt-in
   preset.
3. **sge-shaped hacks in generic code**: the scribe-JAR exclusion
   (`AndroidBuild.scala:103-108`) is an sge workaround living in the generic fat-jar
   builder. Deliverable: configurable exclusion predicate; sge sets it on its side.
4. **AAR handling covers only two of an AAR's payloads**: `AndroidDeps.resolveAar` extracts `classes.jar` +
   `libs/*.jar` and DISCARDS `proguard.txt` (fixed by the companion R8 plan), `jni/`,
   `res/`, `assets/`. Deliverable: one honest `AarResolver` with a documented
   supported/unsupported matrix.
5. **No manifest generation**: `AndroidManifest.xml` must be hand-written in
   `src/main/resources/`. Deliverable: optional generator for the trivial single-activity
   case sge/demos use.
6. **No NDK bootstrap**: `AndroidSdk.ndkPath` only *finds* an installed NDK; nothing
   installs one (sge-native-providers installs NDK 27 out-of-band). Deliverable:
   `androidNdkVersion` setting + ensure-task, same pattern as `ensureSdk`.
7. **No plugin-local tests**: the ONLY scripted coverage of this pipeline lives in the
   consumer (`sge/sge-build/src/sbt-test/packaging/android/`). Deliverable: scripted test
   in this repo.

### Why this shape (design rationale)

- Mirrors the repo's existing two-layer pattern: `core/` (sbt-free) + `plugin/` (sbt glue).
  The sbt-free layer is what a future Scala Native or Scala.js Android toolchain can adopt:
  APK assembly, signing, SDK bootstrap, and AAR extraction do not care whether the code
  inside came from scalac+R8 (JVM), `nativeLink` (`.so` + a thin launcher activity), or anything
  else. Only `androidDex` is JVM-specific.
- sge keeps `SgeAndroidPlatform` as its consumer seam — nothing sge-visible changes except
  the `addSbtPlugin` coordinates and two new opt-in settings.

### What must NOT change

- **Key names and task semantics**: `androidDex`, `androidPackage`, `androidSign`,
  `androidInstall`, `androidSdkCacheDir`, `androidSdkRoot`, `androidMinSdk`,
  `androidTargetSdk`, `androidBuildToolsVersion` are public API re-exported by sge
  (`SgePlugin.scala:82-85`) and used in CI scripts (`sbt 'sge-android-smoke/androidSign'`),
  runners (`sge/.rescale/runners.yaml` → `android-it`, `android-smoke-build`,
  `android-build-all`), and the demos build (`androidAll`). Renames are FORBIDDEN.
- **sge covenants**: `sge-build/src/main/scala/sge/sbt/SgePlugin.scala` and
  `SgePlugins.scala` carry `Covenant:` headers with baselined method lists including the
  android keys. Any sge-side edit must keep those methods present and re-run
  `re-scale enforce verify --file <path>` (re-baseline via the audit skill if LOC changes).
- **Package name `multiarch.sbt`**: moving files to a new sbt MODULE does not require
  changing the package; keep `multiarch.sbt` so consumer imports stay valid.
- **sbt cross-build**: the plugin module cross-builds for sbt 1.x (Scala 2.12.21) and
  sbt 2.0 (Scala 3.8.4) with `Compat.scala` per axis (`plugin/src/main/scala-2.12/`,
  `plugin/src/main/scala-3/`). The new module must replicate this EXACTLY
  (`build.sbt:154-172` is the template). sge is on sbt 2.0; the scripted consumer test in
  sge uses sbt 1.x-or-2.0 per `sge-build/src/sbt-test/packaging/android/project/build.properties`.

### Sequencing constraint

Land AFTER the companion R8 plan (`sge/docs/plans/2026-07-android-r8.md`) is merged,
released (0.3.1), and green in sge CI **(GATE-FIX 2026-07-03, M3: strong form, unified with the AP-1 Depends column)**. Rationale: the R8 work rewrites `androidDex` and
`AndroidDeps.resolveAar` in place; moving the files first would force a cross-repo
double-review of the same diff. The R8 plan is written against the CURRENT file layout on
purpose.

---

## 1. Target module layout

```
multiarch-scala/
├── android-tools/                  # NEW — sbt-FREE core (like core/), Scala 2.12+2.13+3.3 cross
│   └── src/main/scala/multiarch/android/
│       ├── AndroidSdkTools.scala   # SDK resolve/download/install + tool paths (from AndroidSdk.scala)
│       ├── AarResolver.scala       # generic AAR/JAR resolution (from AndroidDeps.scala, generalized)
│       ├── ApkZip.scala            # addFilesToZip/addFilesToZipWithPaths/extractNativeLibsFromJars
│       │                           #   (from AndroidBuild.scala:364-493)
│       └── ManifestGen.scala       # NEW — single-activity AndroidManifest.xml generator
├── android-plugin/                 # NEW — sbt plugin, artifact "sbt-android", cross sbt 1.x/2.0
│   └── src/main/scala/multiarch/sbt/
│       ├── AndroidBuild.scala      # keys + task impls (thin: delegates to android-tools)
│       ├── AndroidPlugin.scala     # AutoPlugin (moved verbatim)
│       └── PanamaPortDeps.scala    # PanamaPort coordinate preset (data only; from AndroidDeps list)
│   └── src/sbt-test/android/basic/ # NEW — scripted: dex+package+sign a hello-world APK
└── plugin/                         # sbt-multiarch-scala: Android files DELETED; depends on
                                    # sbt-android and re-exports for one release (deprecation bridge)
```

Notes:

- `android-tools` uses only `java.util.zip`/`java.nio` + a logger interface — verify it does
  not import `sbt._` anywhere. sbt's `Logger` is replaced by
  `multiarch.android.Log` (trait with `info`/`warn`/`error`; the plugin adapts
  `sbt.util.Logger`). This is the piece Scala Native / Scala.js tooling can adopt.
- `PanamaPortDeps` stays in the repo because `panama-api`'s own build uses it
  (`build.sbt:227-231` calls `AndroidDeps.resolvePanamaPort`) — it is Android-generic FFM
  data, not sge-specific.
- Publishing: add both modules to `publishSettings`, root aggregation (`build.sbt:44-46`),
  and the `ci-release` command list (`build.sbt:57-71`) as
  `"+androidPlugin/publishSigned"` and `"+androidTools/publishSigned"` (match the existing
  `+plugin` / `+core` spelling). <!-- (GATE-FIX 2026-07-03, M4): was "+android-plugin/publishSigned"; the sbt project id is `androidPlugin` (camelCase), not the artifact name `sbt-android` -->

---

## 2. Verbatim-vs-generalize map (file:line, current master `444d5ef`)

| Source (current) | Lines | Destination | Verbatim? | Change |
|---|---|---|---|---|
| `AndroidSdk.scala` | 18-31 (constants) | `android-tools/.../AndroidSdkTools.scala` (public defaults) | generalize | **(GATE-FIX 2026-07-03, B1)** the constants (`minSdkVersion`/`targetSdkVersion`/`buildToolsVersion`/`platformVersion`) MOVE INTO `AndroidSdkTools` as public defaults — SINGLE SOURCE OF TRUTH; the sbt settings (`androidMinSdk`/`androidTargetSdk`/`androidBuildToolsVersion`) then default to `AndroidSdkTools.*`; NOTHING is deleted (the `object AndroidSdk` publics are not "kept as aliases" — they cease to exist because the code that referenced them, i.e. the tool-path/download rows below, now closes over `AndroidSdkTools`'s own fields). This resolves the B1 contradiction: the two "verbatim" tool-path/download rows close over `buildToolsVersion`/`platformVersion`, which is only sound if the constants live in `AndroidSdkTools` alongside them, not in the plugin. |
| `AndroidSdk.scala` | 34-58 (findSdkRoot/ensureSdk) | `android-tools/.../AndroidSdkTools.scala` | verbatim | `sbt.util.Logger` → `multiarch.android.Log`; `File` ops → plain `java.io.File` (drop `sbt.IO`) |
| `AndroidSdk.scala` | 60-127 (tool paths) | `AndroidSdkTools.scala` | verbatim | **(GATE-FIX 2026-07-03, M5)** row scoped to **60-127 only** — lines 128-135 (`ndkPath`) are carved out to their own row below (generalized under AP-5). **(GATE-FIX 2026-07-03, B1)** these methods close over `buildToolsVersion`/`platformVersion`, now sourced from `AndroidSdkTools`'s own public defaults (see the 18-31 constants row) — this is the constants' new home, not a semantic change |
| `AndroidSdk.scala` | 137-224 (download/install) | `AndroidSdkTools.scala` | verbatim | **(GATE-FIX 2026-07-03, B1)** closes over `buildToolsVersion`/`platformVersion`, now sourced from `AndroidSdkTools`'s own public defaults (see the 18-31 constants row) — this is the constants' new home, not a semantic change |
| `AndroidSdk.scala` | 128-135 (`ndkPath`) | `AndroidSdkTools.scala` | generalize | add `ensureNdk(sdkRoot, version, log)` → `sdkmanager "ndk;<version>"` (mirrors `downloadSdk`); new setting `androidNdkVersion: Option[String]` (default `None` = don't install) |
| `AndroidBuild.scala` | 20-32 (keys) | `android-plugin/.../AndroidBuild.scala` | verbatim | plus NEW keys from §3 |
| `AndroidBuild.scala` | 40-63 (SDK settings + android.jar unmanagedJars) | same | verbatim | none |
| `AndroidBuild.scala` | 65-73 (PanamaPort unmanagedJars) | same | **generalize** | replace with `Compile / unmanagedJars ++= androidAarDependencies.value` resolution (§3); DELETE the unconditional PanamaPort wiring |
| `AndroidBuild.scala` | 86-177 (`androidDex` — post-R8-plan version) | same | verbatim | scribe exclusion (currently 103-108) becomes `androidDexJarExclude` predicate, default `_ => false` |
| `AndroidBuild.scala` | 180-283 (`androidPackage`) | same | generalize | manifest lookup falls back to `androidManifestGenerate` output (§3) when `src/main/resources/AndroidManifest.xml` is absent |
| `AndroidBuild.scala` | 286-359 (`androidSign`, `androidInstall`) | same | verbatim | none |
| `AndroidBuild.scala` | 364-493 (zip/extract helpers) | `android-tools/.../ApkZip.scala` | verbatim | make `public`; **(GATE-FIX 2026-07-03, M1)** authorized `sbt.IO` → dependency-free equivalents (this list of mechanical sbt-dependency removals applies to ALL verbatim rows, not just this one): `sbt.IO.move`/`IO.createDirectory` → `java.nio.file.Files` equivalents; `IO.transfer` → `java.nio.file.Files.copy`; sbt `RichFile` `/` operator → `new File(parent, name)`; and `scala.jdk.CollectionConverters` (line 428) which does NOT compile on the 2.12 axis in a dependency-free module — rewrite as a `while` loop over `entries()` (compile-probe verified). None of these change behavior. |
| `AndroidDeps.scala` | 19-45 (`Dep`/`AarDep`/`JarDep`) | `android-tools/.../AarResolver.scala` | generalize | make public ADT `AndroidArtifact` (group, artifact, version, classifier, kind AAR/JAR, repo base URL defaulting to Maven Central) |
| `AndroidDeps.scala` | 58-72 (PanamaPort list) | `android-plugin/.../PanamaPortDeps.scala` | verbatim | just data relocation |
| `AndroidDeps.scala` | 83-144 (resolve/extract — post-R8-plan version: consumer rules collected from AAR `proguard.txt` AND `classes.jar` `META-INF/com.android.tools/r8-from-X-upto-Y/` + `META-INF/proguard/`, plus the AGP-9 banned-global-options sanitizer) | `AarResolver.scala` | verbatim | result type carries `classesJars: Seq[File]`, `proguardRules: Seq[File]` per artifact; sanitizer + banned list move as-is (behavior bit-identical before/after the move) |
| `AndroidPlugin.scala` | 1-55 | `android-plugin/.../AndroidPlugin.scala` | verbatim | none |

Explicit non-goals (document in the new module's scaladoc as UNSUPPORTED): AAR `res/`
compilation (no `aapt2 compile` of library resources), AAR `jni/` extraction, manifest
MERGING (only whole-file use or trivial generation), AGP-style build variants.

---

## 3. New/changed settings (exact signatures)

```scala
// android-plugin AndroidBuild.scala — additions
val androidAarDependencies = settingKey[Seq[multiarch.android.AndroidArtifact]](
  "Android AAR/JAR dependencies resolved outside Ivy/Coursier (AARs are not " +
  "handled by sbt). classes.jar and libs/*.jar are added to Compile/unmanagedJars; " +
  "consumer proguard.txt files feed the R8 step. Default: empty."
)
val androidDexJarExclude = settingKey[File => Boolean](
  "Predicate excluding classpath JARs from the DEX input fat-jar. Default: _ => false."
)
val androidManifestGenerate = settingKey[Boolean](
  "Generate a single-activity AndroidManifest.xml from mainClass/name when " +
  "src/main/resources/AndroidManifest.xml is absent. Default: false."
)
val androidNdkVersion = settingKey[Option[String]](
  "NDK version to auto-install via sdkmanager (e.g. Some(\"27.2.12479018\")). Default: None."
)
val androidNdkRoot = taskKey[Option[File]]("Resolve (and if configured, install) the NDK root.")
```

PanamaPort preset (consumer opts in):

```scala
// consumer build.sbt / plugin code
androidAarDependencies ++= multiarch.sbt.PanamaPortDeps.all   // the 9 artifacts from AndroidDeps.scala:58-72
```

sge-side seam after migration (edit `sge-build/src/main/scala/sge/sbt/SgePlugins.scala`
`SgeAndroidPlatform.projectSettings`, currently lines 147-154):

```scala
override def projectSettings: Seq[Setting[_]] = Seq(
  AndroidPlugin.autoImport.androidSdkCacheDir := (ThisBuild / baseDirectory).value / "sge-deps" / "android-sdk",
  AndroidPlugin.autoImport.androidAarDependencies ++= PanamaPortDeps.all,
  AndroidPlugin.autoImport.androidDexJarExclude := (jar => jar.getName.contains("scribe")),
  libraryDependencies ++= SgeExtension.jvmDeps(...)  // unchanged
)
```

---

## 4. Steps, commands, expected outputs, failure branches

Work on a branch `sbt-android-extraction` in multiarch-scala. Each step ends with the
verification command shown; do not proceed on failure.

### Step 1 — create `android-tools` module (sbt-free core)

1. Add module to `build.sbt` cloning the `core` module block (`build.sbt:76-91`):
   `name := "multiarch-android-tools"`, same `crossScalaVersions := Seq("2.12.21", "2.13.18", "3.3.8")`,
   same munit test dep, `.dependsOn()` nothing (NOT core — keep it dependency-free).
2. Create `multiarch.android.Log` trait + move `ApkZip`, `AndroidSdkTools`
   per §2 map. **(GATE-FIX 2026-07-03, M2)** `AarResolver` is NOT moved here — it moves in
   AP-2 (post-R8; its §2 source row is the post-R8-plan version of `AndroidDeps.scala:83-144`,
   which does not exist yet). Compile errors from `sbt.IO`/`sbt.util.Logger` usages are the TODO list —
   replace each with `java.nio.file.Files` / `Log`.
3. Unit tests (new, munit): `ApkZipSpec` (STORED-entry preservation round-trip — build a
   zip with a STORED entry, add a file, assert method/CRC preserved; this guards the
   `resources.arsc must stay STORED` invariant from `AndroidBuild.scala:371-380`),
   `AarResolverSpec` (extract classes.jar + libs/*.jar + proguard.txt from a fixture AAR
   built by the test itself with `java.util.zip`).

```
sbt> +androidTools/test
```
Expected: all cross versions compile, tests pass.
If 2.12 compilation fails on syntax: the moved code is currently compiled as 2.12 already
(plugin module default) — failures mean you introduced 2.13+ syntax; fix the code, not the
cross axis. If you cannot express it in 2.12, STOP and file an issue titled
"android-tools 2.12 blocker" with the compile log.

### Step 2 — create `android-plugin` module (artifact `sbt-android`)

1. Clone the `plugin` module block (`build.sbt:143-188`) as `androidPlugin`:
   `name := "sbt-android"`, `.dependsOn(androidTools)` — but NOTE: sbt plugins cannot
   `dependsOn` a multi-Scala matrix project directly; depend on the published-style module
   the same way `plugin` depends on `core` (`build.sbt:146` — `.dependsOn(core)` works
   because core's default scalaVersion is 2.12.21 and the plugin picks the matching axis;
   replicate exactly, and add the same guard for the sbt-2.0/Scala-3.8.4 axis:
   android-tools must also cross-compile to 3.8.4 for the sbt-2 axis — ADD `"3.8.4"` to
   android-tools `crossScalaVersions` if `+androidPlugin/compile` fails to resolve it;
   check how `plugin` consumes `core` on the 3.8.4 axis first and copy that mechanism).
2. Move `AndroidBuild.scala`, `AndroidPlugin.scala`, add `PanamaPortDeps.scala`; apply the
   §2/§3 generalizations. Keep package `multiarch.sbt`.
3. In `plugin` (sbt-multiarch-scala): delete the four Android files; add
   `addSbtPlugin("com.kubuszok" % "sbt-android" % version.value)`-style dependency — sbt
   plugin-to-plugin dependencies are expressed with `Defaults.sbtPluginExtra` on the
   libraryDependencies (copy the `sbt-projectmatrix` pattern at `build.sbt:166-172`, minus
   the `% Provided` — this one must be transitive so existing consumers keep compiling).
4. Fix `panama-api`'s use (`build.sbt:227-231`): `AndroidDeps.resolvePanamaPort` is gone;
   the project build itself now calls the new resolver:
   `multiarch.android.AarResolver.resolve(PanamaPortDeps.all, cacheDir, log)` — this
   requires `project/` to see the new code: add `android-tools` + `PanamaPortDeps` sources
   to the meta-build the same way the build currently accesses `multiarch.sbt.AndroidDeps`
   (check `project/plugins.sbt` / meta-build wiring FIRST and mirror it; if the meta-build
   consumed the previous plugin via source include, keep that mechanism).

```
sbt> +androidPlugin/compile ; +plugin/compile ; +androidPlugin/publishLocal ; +plugin/publishLocal
```
Expected: both axes (2.12 / 3.8.4) compile; publishLocal writes
`sbt-android/scala_2.12/sbt_1.0/<v>/` and `..._3/sbt_2.0/...` ivy dirs.
If the sbt-2.0 axis fails resolving android-tools: see step 2.1 guard.
If plugin-to-plugin dependency resolution fails at consumer side later: fall back to
FALLBACK-A: `plugin` keeps thin deprecated *source* re-exports (type aliases + `val`
forwarders in a single `AndroidCompat.scala`) instead of a plugin dependency, and
consumers migrate coordinates immediately. File an issue documenting which of the two
bridges shipped.

### Step 3 — scripted test in this repo

Create `android-plugin/src/sbt-test/android/basic/` — port of
`sge/sge-build/src/sbt-test/packaging/android/` MINUS the sge plugin: a hello-world
project enabling only `AndroidPlugin`, with `AndroidManifest.xml`, asserting
`androidSign` produces an `apksigner verify`-clean APK. Copy the SDK-resolution override
logic from the sge scripted test's `build.sbt` (resolve `ANDROID_HOME` etc., no download).
Enable `scripted` for the module: `androidPlugin.enablePlugins(SbtPlugin)` already implies
scripted; set `scriptedLaunchOpts += "-Dplugin.version=" + version.value` (copy from sge's
`sge-build/build.sbt` scripted stanza).

```
sbt> androidPlugin/scripted android/basic
```
Expected: `checkAndroidSign`-equivalent passes; `[success]` for the scripted run.
Requires a local Android SDK; in CI provision via `android-actions/setup-android@v3`
(copy the job stanza from sge `.github/workflows/ci.yml:285` area). Add a
`plugin-scripted` job to this repo's workflow if one doesn't exist.
If aapt2/apksigner missing on CI image: the setup-android action installs build-tools;
pin the same version as `AndroidSdk.buildToolsVersion` (35.0.0) in the workflow. If the
runner still lacks it, STOP and attach `ls $ANDROID_HOME/build-tools` output to an issue.

### Step 4 — release + migrate sge

1. Tag + `ci-release` (existing release flow, git-tag driven, `build.sbt:57-71`) →
   version `0.4.0`.
2. sge branch: bump `sge/project/plugins.sbt:5`, `sge/sge-build/build.sbt:60`,
   `sge/project/Versions.scala:31` to `0.4.0`; change the sge-build plugin dependency to
   ALSO add `addSbtPlugin("com.kubuszok" % "sbt-android" % "0.4.0")` if bridge FALLBACK-A
   shipped (otherwise transitive). Apply the `SgeAndroidPlatform` edit from §3 (PanamaPort
   preset + scribe predicate). Re-verify covenants:

```
re-scale enforce verify --file sge-build/src/main/scala/sge/sbt/SgePlugins.scala
re-scale enforce verify --file sge-build/src/main/scala/sge/sbt/SgePlugin.scala
re-scale build compile --all
re-scale runner android-smoke-build     # sbt --client 'sge-android-smoke/androidSign'
re-scale runner android-it              # emulator smoke, needs local AVD; on CI: test-android job
```
Expected: covenant PASS (re-baseline if LOC drifted, via the audit skill), APK builds,
smoke IT green. Also run the sge scripted test:
`(cd sge-build && sbt 'scripted packaging/android')`.
3. Demos: `(cd demos && sbt --client androidAll)` must still produce 11 APKs (the demos
   build consumes the published sge-build plugin — publishLocal first per sge CLAUDE.md).

Failure branch: any sge regression that traces to changed key defaults (e.g. PanamaPort
jars now missing because the preset wasn't applied) manifests as
`ClassNotFoundException: com.v7878.foreign.*` in the smoke logcat — fix by confirming
`androidAarDependencies` contains `PanamaPortDeps.all` on the failing project, not by
re-hardcoding in multiarch. If smoke fails for any OTHER reason, STOP, capture logcat
(the IT prints it), file sge issue via `re-scale db issues add`, and do NOT merge the bump.

### Step 5 — docs

- Update `multiarch-scala/CLAUDE.md` plugin-chain diagram (AndroidPlugin now from
  `sbt-android`) and module table (add `android-tools/`, `android-plugin/`).
- Update `multiarch-scala/README.md` usage snippet for the new coordinates.
- sge: `docs/architecture/build-structure.md` — update the plugin list if it names
  sbt-multiarch-scala as the Android provider.

---

## 5. Later adoption by Scala Native / Scala.js tooling (design note, no work now)

The `android-tools` layer is deliberately DEX-agnostic. A future Scala Native Android
backend would: reuse `AndroidSdkTools` (SDK/NDK bootstrap) + `ManifestGen` + `ApkZip` +
`AarResolver`; replace `androidDex` with a `nativeLink`-per-ABI step whose `.so` outputs
feed the existing `lib/<abi>/` packaging path (`ApkZip.addFilesToZipWithPaths`); keep
aapt2/zipalign/apksigner identical. The ONLY assumptions to keep out of android-tools:
no references to `Compile/fullClasspath`, no `classes.jar` assumptions outside
`AarResolver`, no R8/D8 imports. Enforce with a scalafix-free rule of thumb: android-tools
must not mention "dex" outside `AarResolver` docs. Document this contract in the
android-tools package object scaladoc.

---

## 6. Issue decomposition (one implementer session each)

File these as GitHub issues in `kubuszok/multiarch-scala` (except AP-6, filed in sge's
re-scale DB). Each issue body should copy the relevant plan sections (§2 map rows + §4
step) so it is self-contained.

**(GATE-FIX 2026-07-03, PREFIX COLLISION)** These issues are `AP-1..AP-7` (renamed from
`MA-1..MA-7` to avoid collision with the natives plan's `MA-1..MA-6` in
`docs/plans/2026-07-native-flags-and-collisions.md`, same repo). The `AP-` (Android Plugin)
prefix is used everywhere in this doc.

| ID | Title | Contents | Depends on |
|---|---|---|---|
| AP-1 | android-tools module: Log, ApkZip, AndroidSdkTools + unit tests | §4 step 1; §2 rows for AndroidSdk.scala + zip helpers | **(GATE-FIX 2026-07-03, M3)** R8 plan merged, released (0.3.1), and green in sge CI |
| AP-2 | android-tools: generic AarResolver (+ proguard.txt carry-through) | §2 AndroidDeps rows; §4 step 1.3 test | AP-1 |
| AP-3 | android-plugin module: move AndroidBuild/AndroidPlugin, new settings, PanamaPortDeps preset; plugin re-export bridge | §3, §4 step 2 | AP-1, AP-2 |
| AP-4 | Scripted test android/basic + CI job | §4 step 3 | AP-3 |
| AP-5 | ManifestGen + androidManifestGenerate, NDK ensure-task | §2 ndk row, §3 keys | AP-3 |
| AP-6 | (sge repo, re-scale DB) migrate sge to sbt-android 0.4.0: bump pins, SgeAndroidPlatform preset/predicate, covenant re-verify, demos androidAll | §4 step 4 | 0.4.0 released |
| AP-7 | Docs: CLAUDE.md/README module tables + adoption contract scaladoc | §4 step 5, §5 | AP-3 |

## 7. Verification gates (orchestrator re-runs independently)

1. `sbt '+androidTools/test'` green (both repos' CI).
2. `sbt 'androidPlugin/scripted android/basic'` green with a provisioned SDK.
3. sge: `re-scale enforce verify --all` no covenant regressions; `test-android` +
   `test-android-it` CI jobs green on the bump PR; `scripted packaging/android` green.
4. Diff audit: `git diff --stat` of AP-3 must show `plugin/src/main/scala/multiarch/sbt/Android*.scala`
   as pure deletions/moves — any semantic change inside a "verbatim" row of the §2 map is
   a review rejection **(GATE-FIX 2026-07-03, B1: except changes explicitly listed in the
   row's Change column or in a GATE-FIX note)** (the R8 pipeline behavior must be bit-identical before/after the move;
   compare the two `androidSign` APKs' entry listings with `unzip -l` — same entries modulo
   timestamps).
5. Opus dry-run gate (roadmap Topic 8): fresh-context Opus agent restates steps + executes
   Step 1 in a scratch worktree before 2026-07-06.
