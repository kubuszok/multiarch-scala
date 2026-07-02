# Plan: native flags audit + collision hardening (Roadmap Topic 4)

Status: PLAN — written 2026-07-02 by Fable 5 during the planning window. No implementation
has been done. Implementers are Opus-tier sessions executing ONE issue from §9 each.
Owning repo: `multiarch-scala`. Affected repos: `multiarch-scala`, `sge-native-providers`,
`ssg-native-providers`, `sge`, `ssg`.

Cross-repo sequencing lives in `/Users/dev/Workspaces/kubuszok/FABLE5_PLANNING_ROADMAP.md`
(§Topic 4). This plan must pass the Opus dry-run gate (roadmap §Topic 8) before 2026-07-06.

---

## 0. Context a fresh model lacks

### 0.1 Why this work is wanted

Native library bundling already works one-provider-artifact-per-library with per-config
manifest `flags-groups` (deduplicated at group level). The verified remaining gaps
(roadmap "Ground truth" §4):

- (a) **No version namespacing** in the classpath resource path `native/<platform>/<lib>`
  — if two JARs bundle the same file name for the same platform, classpath order silently
  decides which library is extracted and loaded.
- (b) **Manifests don't declare which lib files they bundle** — so no build-time
  duplicate/conflict detection is possible.
- (c) **Blanket `--enable-native-access=ALL-UNNAMED`** at `sge/build.sbt:134` (inside
  `commonSettings`' JVM `javaOptions`) and `ssg/build.sbt:71` (JVM `javaOptions`), plus
  `sge/sge-test/it-desktop` settings and the launchers written by
  `multiarch-scala/plugin/src/main/scala/multiarch/sbt/JvmPackaging.scala` (lines ~127,
  ~142, ~412).
- (d) **Single-valued `java.library.path`** hardcoded to the local Rust build dir
  (`sge/build.sbt` `commonSettings`, ~line 129: `-Djava.library.path=$rustLib` pointing at
  `sge-deps/native-components/target/release`).
- (e) **No per-flag necessity audit has ever been done** — nobody knows which
  `flags-groups` entries are required by the bundled lib, required by the app, or dead.

### 0.2 What "done" means

1. A committed, evidence-backed table classifying every `flags-groups` entry in every
   provider manifest as `required-by-lib` / `required-by-app` / `unnecessary`, with
   app-level flags moved to consumer builds and unnecessary flags deleted.
2. Manifest schema v2 (`0.2.0`) with a `bundles` declaration; the build FAILS (with a
   prescriptive message) when two classpath providers bundle the same lib file for the
   same platform; the runtime loader throws instead of silently picking one.
3. Provider JAR resources live at `native/<artifact>/<version>/<platform>/<file>`;
   `NativeLibLoader` resolves the new layout first and falls back to the old flat layout
   with a defined deprecation window.
4. A written, evidence-backed decision on `--enable-native-access` scoping (spoiler,
   verified in §6: sge/ssg run everything on the classpath ⇒ `ALL-UNNAMED` stays, with
   explicit revisit triggers).
5. The dev flow supports multi-directory `java.library.path`.

### 0.3 What must NOT change

- **Public API stability of `multiarch-core`** for existing v1 manifests: `0.1.0`-schema
  manifests must keep parsing, linking, and loading exactly as today. All v2 behavior is
  additive; the ONLY new failure mode allowed for v1-only classpaths is the collision
  error (that is the point of the change).
- **`java.library.path` stays FIRST** in `NativeLibLoader` resolution order. sge's
  it-desktop relies on documented semantics (see the long comment in `sge/build.sbt`
  around the `sge-it-desktop` project, and ISS-485 history). Do not reorder.
- **sge port covenants**: `sge/sge-build/src/main/scala/sge/sbt/NativeProviderValidation.scala`
  and `SgeNativeLibs.scala` carry `Covenant: full-port` headers. Editing them requires the
  re-scale covenant re-baseline flow (`re-scale enforce verify --file <path>` after
  updating the covenant header), not silent edits.
- **sge/ssg command discipline**: in sge and ssg use `sbt --client ...` or `re-scale ...`
  only (PreToolUse hooks deny bare `sbt`, `python`, raw `grep`/`sed` etc.). In
  `multiarch-scala`, `sge-native-providers`, `ssg-native-providers` plain `sbt` is fine.
- **No Claude attribution** in any commit/PR (user memory: enforced via .githooks).
- **Scala version constraints** (§7): new core code must compile on Scala 2.12.21,
  2.13.18 AND 3.3.8; new plugin code on 2.12.21 AND 3.8.4 (sbt 1.x + sbt 2.0 axes).

### 0.4 File inventory (ground truth, verified 2026-07-02)

multiarch-scala (sbt 1.12.6 build, publishes for both sbt 1 and sbt 2 — §7):

| File | Role |
|---|---|
| `core/src/main/scala/multiarch/core/ProviderManifest.scala` | manifest model (`ProviderManifest`, `ProviderConfig`, `PlatformProviderConfig{binary, stub, flagsGroups}`) |
| `core/src/main/scala/multiarch/core/ProviderManifestCodec.scala` | hand-rolled JSON parse/write (`parse`, `write`, private `JsonParser`) |
| `core/src/main/scala/multiarch/core/NativeExtract.scala` | pure logic: `discoverManifests`, `mergeFlags`, `findNativeLibJars`, `extractFromJar`, `createWindowsLibAliases`, `jarMappings` |
| `core/src/main/scala/multiarch/core/NativeLibLoader.scala` | JVM runtime loader; resolution: java.library.path → classpath `native/<platform>/<mapped>` → Android → UnsatisfiedLinkError; `loadAll`/`loadConfigs` discover manifests via `ProviderType.filename` resources |
| `core/src/main/scala/multiarch/core/ProviderType.scala` | `Jni`/`Panama`/`ScalaNative` → `jni-provider.json`/`pnm-provider.json`/`sn-provider.json` |
| `plugin/src/main/scala/multiarch/sbt/NativeProviderSettings.scala` | task keys `discoverManifests: Seq[(ProviderType, ProviderManifest)]`, `mergedLinkerFlags`, wraps NativeExtract |
| `plugin/src/main/scala/multiarch/sbt/NativeExtractSettings.scala` | `nativeLibExtract` task — extracts native libs from classifier JARs to `target/native-libs/<classifier>` |
| `plugin/src/main/scala/multiarch/sbt/NativeProviderPlugin.scala` | AutoPlugin (requires ScalaNativePlugin); wires `nativeConfig.linkingOptions ++= -L<dir> ++ merged ++ rpath`; Windows post-link DLL copy |
| `plugin/src/main/scala/multiarch/sbt/JvmPackaging.scala` | launchers with `--enable-native-access=ALL-UNNAMED` + `-Djava.library.path` (lines ~127/142/412); copies native libs flat into `<launcher>/native/` (~583) |
| `plugin/src/main/scala/multiarch/sbt/AndroidBuild.scala` | line 432: scans JAR entries `native/android-*/*.so` for APK packaging |
| `sn-provider-curl/src/main/resources/sn-provider.json` | curl manifest with the largest flags-groups set (linux 3, macos 4, windows 7 groups) |
| `test-project-native/`, `test-project-jlink/` | integration tests (`sbt test-project-native/nativeLink`, `sbt test-project-jlink/releasePackage`) |

Providers (both sbt 2.0.x builds; metabuild depends on `"com.kubuszok" %% "multiarch-core" % "0.3.0"` in `project/plugins.sbt`):

- `sge-native-providers/providers/{pnm,sn}-provider-sge*/src/main/resources/{pnm,sn}-provider.json`
  — 11 manifests. NOTE: `pnm-provider-sge-physics3d-{desktop,android}` and
  `sn-provider-sge-physics3d` are aggregated in `build.sbt` but have **no
  `src/main/resources` manifest at all** (verified — only `target/` exists). They rely on
  `NativeLibLoader.load("sge_physics3d")`'s flat classpath lookup. v2 migration must add
  their manifests.
- `sge-native-providers/build.sbt` — `fatJarMappings(...)` maps `crossDir/<classifier>/<file>`
  → JAR entry `native/<classifier>/<file>`; `crossDir = native-components/target/cross`.
- `ssg-native-providers/providers/{pnm-provider-tree-sitter-desktop,sn-provider-tree-sitter}/src/main/resources/*.json`
  + `wasm-provider-tree-sitter` (no manifest — JS-only, out of scope) + `tree-sitter-queries`.

Consumers (both sbt 2.0.1):

- sge: `project/plugins.sbt` → `addSbtPlugin("com.kubuszok" % "sbt-multiarch-scala" % "0.3.0")`;
  `project/Versions.scala:31` `multiarch = "0.3.0"`, `:45` `nativeComponents = "0.1.2-33-gcf10406-SNAPSHOT"`.
  FFI ITs: `re-scale runner native-ffi-it` = `sbt --client 'sge-it-native-ffi/run'`
  (CI adds `-- --headless`, `.github/workflows/ci.yml:476`); `re-scale runner desktop-it`
  = `sbt --client 'sge-it-desktop/test'`. Runtime loads via `multiarch.core.NativeLibLoader.load`
  in `sge/src/main/scalajvm/sge/platform/{BufferOpsPanama,ETC1OpsPanama,GlOpsJvm,WindowingOpsJvm,AudioOpsJvm}.scala`,
  `sge/src/main/scalajvm/sge/DesktopApplicationFactory.scala`, and the freetype/physics
  extensions. Release gate: `sge/sge-build/.../SgeNativeLibs.scala` task
  `sgeValidateNativeLibs` + `NativeProviderValidation.scala` (regex-parses
  `pnm-provider.json`, checks JAR entry `native/<classifier>/<binary>` exists and ≥32KB).
- ssg: `project/Versions.scala:31-32` `multiarch = "0.3.0"`, `treeSitterProviders = "0.1.0"`.
  `ssg-highlight` (build.sbt:184-221): JVM axis depends on `pnm-provider-tree-sitter-desktop`
  + `multiarch-core`; native axis applies `NativeProviderPlugin.projectSettings` +
  `sn-provider-tree-sitter`. JVM FFI loading in
  `ssg-highlight/src/main/scalajvm/ssg/highlight/TreeSitterPlatformImpl.scala`
  (`NativeLibLoader.load("tree_sitter_all")` → Panama `SymbolLookup.libraryLookup`).
  Test commands: `sbt --client 'ssg-highlight/testFull'` (JVM),
  `sbt --client 'ssg-highlightNative/testFull'` (native; matrix id convention per
  `ssg-highlightJS` in `.github/workflows/ci.yml:121` — if the id is rejected run
  `sbt --client projects` and pick the `ssg-highlight*Native*` row).

### 0.5 Complete flags-groups census (audit scope)

All `flags-groups` live ONLY in `sn-provider.json` manifests (every `pnm-provider.json`
has zero flags-groups — verified by reading all manifests). The audit is therefore a
**Scala Native link-time** audit; there is no JVM-manifest flag to audit.

| Manifest | Config | Platform(s) | Groups |
|---|---|---|---|
| sge `sn-provider-sge` | sge_native_ops | linux-x86_64, linux-aarch64 | `[-lpthread]`, `[-ldl]` |
| | sge_native_ops | windows-x86_64, windows-aarch64 | `[-lntdll]`, `[-lmsvcrt]` |
| | sge_audio | macos-x86_64, macos-aarch64 | `[-framework CoreAudio]`, `[-framework AudioToolbox]` |
| | sge_audio | windows-* | `[-lole32]`, `[-lwinmm]` |
| | glfw3 | macos-* | `[-framework Cocoa]`, `[-framework IOKit]`, `[-framework CoreFoundation]`, `[-framework CoreVideo]`, `[-framework CoreGraphics]`, `[-framework QuartzCore]`, `[-lobjc]` |
| | glfw3 | windows-* | `[-lgdi32]`, `[-lshell32]`, `[-luser32]` |
| sge `sn-provider-sge-physics` | sge_physics | linux-* | `[-lpthread]` |
| sge `sn-provider-sge-freetype` | freetype | macos-* | `[-lbz2]`, `[-lpng]`, `[-lz]` |
| ssg `sn-provider-tree-sitter` | tree_sitter_all | linux-* | `[-lc++]`, `[-lpthread]`, `[-ldl]`, `[-lm]` |
| | tree_sitter_all | macos-* | `[-lc++]` |
| | tree_sitter_all | windows-x86_64/aarch64 | `[-lntdll]`, `[-lmsvcrt]` |
| multiarch `sn-provider-curl` | curl | linux-* | `[-lpthread]`, `[-ldl]`, `[-lz]` |
| | curl | macos-* | `[-framework Security]`, `[-framework SystemConfiguration]`, `[-framework CoreFoundation]`, `[-lz]` |
| | curl | windows-* | `[-lws2_32]`, `[-lcrypt32]`, `[-lbcrypt]`, `[-ladvapi32]`, `[-lncrypt]`, `[-lntdll]`, `[-lmsvcrt]` |

Experiment count (one experiment = one group removed on one platform actually exercised):
sge 18, ssg 7, curl 14 — audited platforms are the CI FFI legs: linux-x86_64,
linux-aarch64, macos-aarch64, macos-x86_64 (Rosetta), windows-x86_64
(windows-aarch64 has no SN support — record as `not-auditable`, keep flags as-is).

---

## 1. Workstream A — flag-necessity audit

### 1.1 Design

Faithful loop: **remove one flags-group from the provider manifest → republish the
provider under a unique throwaway version → point the consumer at it → relink → run the
FFI IT → record → restore**. Unique versions (never overwrite) eliminate every
ivy/coursier staleness failure mode.

Per-repo runner commands (relink + FFI IT):

| Provider repo | Consumer | Command | Success signal |
|---|---|---|---|
| sge-native-providers | sge | `sbt --client 'sge-it-native-ffi/clean; sge-it-native-ffi/run'` (or `re-scale runner native-ffi-it` for the unmodified baseline) | exit 0 and the program's final summary line; link step prints `[native-provider] Merged N linker flags for <classifier> from M manifest(s)` |
| ssg-native-providers | ssg | `sbt --client 'ssg-highlightNative/clean; ssg-highlightNative/testFull'` | exit 0, munit all-green |
| multiarch-scala (curl) | itself | `sbt 'test-project-native/clean; test-project-native/nativeLink; test-project-native/run'` | link succeeds; run exits 0 |

### 1.2 Scripted procedure (implement as `scripts/flag-audit.sh` in EACH provider repo)

The script lives in the provider repo (no bash restrictions there). Inputs: consumer repo
path, experiment list. Requires `jq` (available on the dev Mac and GH runners).

Steps per experiment `E = (manifestFile, configName, platform, groupIndex)`:

1. **Baseline (once per platform, before any experiment)**: run the consumer command from
   §1.1 unmodified. Expected: green. If baseline is red, STOP — file an issue in the
   consumer repo with the full log; the audit cannot proceed on a red baseline.
2. Remove the group:
   ```bash
   jq --arg cfg "$CFG" --arg plat "$PLAT" --argjson idx "$IDX" \
      'del(.configs[] | select(."config-name"==$cfg) | .[$plat]["flags-groups"][$idx])' \
      "$MANIFEST" > "$MANIFEST.tmp" && mv "$MANIFEST.tmp" "$MANIFEST"
   ```
   Sanity check (MUST): re-read the file with jq and assert the group count for
   `(cfg, plat)` dropped by exactly 1; otherwise STOP (the jq path is wrong — fix the
   script, do not continue collecting garbage data).
3. Publish under a throwaway version:
   ```bash
   sbt "set ThisBuild/version := \"0.0.0-flagaudit.$N\"" publishLocal
   ```
   Expected output includes `published <artifact> to .../.ivy2/local/com.kubuszok/<artifact>/0.0.0-flagaudit.$N/...`.
   If sbt-git overrides the version (no `published ...0.0.0-flagaudit` line), fall back to
   `sbt -Dsbt.gitless=true ...` or set the version key that sbt-kubuszok's git versioning
   reads; if neither works, STOP and file an issue in the provider repo quoting the
   publishLocal log.
4. Point the consumer at it. In the consumer repo, edit the version constant
   (single-line change, revert afterwards):
   - sge: `project/Versions.scala:45` `nativeComponents = "0.0.0-flagaudit.N"`
   - ssg: `project/Versions.scala:32` `treeSitterProviders = "0.0.0-flagaudit.N"`
   (In sge/ssg use the Edit tool, not sed — hook policy.) Both repos already have
   `resolvers += Resolver.mavenLocal` and resolve ivy-local via the default chain; if
   resolution fails with "not found", check `~/.ivy2/local/com.kubuszok/<artifact>/`
   exists, and if it does, run `sbt --client 'reload; update'` once.
5. Relink + run the FFI IT (command from §1.1). Capture full log.
6. Record the row (§1.3), keying evidence off:
   - link failure: grep log for `undefined symbol|undefined reference|unresolved external symbol|framework not found`
   - IT failure: nonzero exit or munit failures with link OK
7. Restore the manifest (`git checkout -- "$MANIFEST"` in the provider repo) before the
   next experiment.

After ALL experiments: revert the consumer `Versions.scala` line; `git status` in both
repos must be clean except for the committed results files.

### 1.3 Output table schema and location

TSV committed at `<provider-repo>/docs/audits/2026-07-flag-necessity.tsv` (curl results:
`multiarch-scala/docs/audits/2026-07-flag-necessity.tsv`), plus a human summary
`2026-07-flag-necessity.md` next to it. Columns (exact header line):

```
provider	config	platform	flags-group	link	it	classification	evidence
```

- `flags-group`: the group joined with spaces, e.g. `-framework CoreAudio`.
- `link`: `ok` | `fail`.
- `it`: `pass` | `fail` | `n/a` (n/a when link failed).
- `classification` (decision rules, apply in order):
  1. link fails AND at least one missing symbol is defined by the provider's own archive
     (check: `nm -g <extracted .a> | grep ' T \| D ' | grep <symbol>` on the lib extracted
     to the consumer's `target/native-libs/<classifier>/`) → `required-by-lib`.
  2. link fails, missing symbols NOT from the provider archive → `required-by-app`
     (the app or SN runtime free-rides on the provider's flag — move it to the consumer
     build in the apply-results issue).
  3. link ok, IT fails or crashes → `required-by-lib` (runtime-loaded dependency; note
     `runtime` in evidence).
  4. link ok, IT passes → `unnecessary`.
- `evidence`: first missing-symbol line, or `clean`, or `not-auditable` (windows-aarch64).

### 1.4 Platform coverage

- macos-aarch64: run locally on the dev Mac (both sge and ssg experiments).
- macos-x86_64: optional local Rosetta run; if skipped, mark rows `assumed=macos-aarch64`.
- linux-x86_64, linux-aarch64, windows-x86_64: temporary `workflow_dispatch` workflow in
  the CONSUMER repos (`sge/.github/workflows/flag-audit.yml`, ssg equivalent) that clones
  the provider repo as a sibling checkout, replicates the repo's existing "Native FFI IT"
  job setup steps (copy them from `sge/.github/workflows/ci.yml`, the job that runs
  `sge-it-native-ffi/run -- --headless`), takes `experiments` JSON as workflow input, runs
  `scripts/flag-audit.sh`, and uploads the TSV as an artifact. The workflow is deleted in
  the same PR series that commits the final results (it is scripted-audit tooling, not CI).
- Failure branch: if a GH runner leg cannot be made green at baseline within one session,
  record those platform rows as `not-audited-ci` and keep their flags — do NOT guess.

### 1.5 Applying results (separate issues, after audit)

- `unnecessary` → delete the group from the manifest.
- `required-by-app` → delete from the manifest AND add to the consumer's own
  `nativeConfig` (sge: inside `nativeProviderSettings` at `sge/build.sbt:188-190` add
  `nativeConfig ~= (c => c.withLinkingOptions(c.linkingOptions ++ Seq(...)))` per
  platform; ssg: the native `MatrixAction` block at `ssg/build.sbt:203-210`), with a
  comment citing the audit TSV row.
- `required-by-lib` → keep in the manifest (that is the manifest's job).
- Each removal must be re-proven: relink + FFI IT green on every audited platform (CI).

---

## 2. Workstream B — manifest v2 (`bundles`) + build-time collision detection

### 2.1 Schema addition (exact)

`provider-schema-version` bumps `"0.1.0"` → `"0.2.0"`. Three new top-level fields:

```json
{
  "provider-schema-version": "0.2.0",
  "provider-name": "sge-desktop",
  "provider-artifact": "pnm-provider-sge-desktop",
  "provider-version": "0.2.0",
  "bundles": [
    "linux-x86_64/libsge_native_ops.so",
    "linux-x86_64/libsge_audio.so",
    "linux-x86_64/libglfw.so",
    "macos-aarch64/libsge_native_ops.dylib",
    "windows-x86_64/sge_native_ops.dll"
  ],
  "configs": [ { "config-name": "...", "<platform>": { "binary": "...", "flags-groups": [] } } ]
}
```

- `bundles`: flat array (per the roadmap) of `"<platform-classifier>/<file-name>"` —
  every native file the JAR physically carries. Platform-qualified because file names
  differ per OS; artifact/version prefix intentionally NOT repeated in each entry (it is
  derivable from `provider-artifact`/`provider-version`).
- `provider-version` is injected at package time (§4.3) — the static template in
  `src/main/resources` never contains a real version.
- `configs` unchanged; `binary` values MUST appear in `bundles` for their platform
  (validated, §2.4).

Model change in `core/src/main/scala/multiarch/core/ProviderManifest.scala`:

```scala
final case class ProviderManifest(
  schemaVersion:    String,
  providerName:     String,
  configs:          Seq[ProviderConfig],
  providerArtifact: Option[String] = None,   // v2
  providerVersion:  Option[String] = None,   // v2
  bundles:          Seq[String]    = Seq.empty // v2: "<classifier>/<file>"
)
```

New fields go LAST with defaults so existing positional constructions keep compiling.
Codec: extend `ProviderManifestCodec.parse` (read `"provider-artifact"`,
`"provider-version"`, `"bundles"`; absent → defaults) and `write` (emit only when
non-empty, keeping v1 serialization ADDITIVELY unchanged — no v2 keys emitted for v1
manifests, and parse(write(m)) == m SEMANTICALLY; do NOT assert byte-identity against
source files: the existing writer sorts platforms, inlines blocks, and omits the
trailing newline, so a literal byte-compare of a source fixture is impossible). Add
`def effectiveBundles: Seq[String]` on `ProviderManifest`: returns `bundles` when
non-empty, else derives `"<classifier>/<binary>"` from every config platform entry with a
defined `binary` — this makes collision detection work for v1 manifests too.

MiMa failure branch: `multiarch-core` is published; if the kubuszok MiMa gate rejects the
case-class change, this is expected for a 0.3.x→0.4.0 minor — set the repo's MiMa
baseline/filters accordingly (0.4.0 is the declared compatibility break point). Do NOT
work around by creating a parallel class.

### 2.2 Collision detection — pure logic (core)

New in `core/src/main/scala/multiarch/core/NativeExtract.scala`:

```scala
final case class BundleCollision(classifier: String, fileName: String,
                                 owners: Seq[(String /*providerName*/, String /*source jar/dir*/, String /*resourcePath*/)])

def detectBundleCollisions(discovered: Seq[(ProviderType, ProviderManifest, String /*source*/)]): Seq[BundleCollision]
```

Rules:
- Key = `(classifier, fileName)` over `effectiveBundles` of ALL discovered manifests
  regardless of ProviderType (JNI/Panama/SN share the `native/` namespace and the
  extraction dir).
- Two owners with identical `(providerArtifact, providerVersion)` are NOT a collision
  (same artifact seen twice on the classpath).
- `stub: true` binaries still count (a stub silently shadowing a real lib is exactly the
  bug class this prevents).
- `resourcePath` in the report is the v2 path when artifact+version known, else the
  legacy `native/<classifier>/<file>`.

This requires manifest SOURCE tracking. Change `NativeExtract.discoverManifests` return
type to `Seq[(ProviderType, ProviderManifest, String)]` (source = jar file name or
resource-dir path — both already in scope at the two construction sites, lines ~65 and
~98). This changes the public task key type in `NativeProviderSettings.discoverManifests`
— acceptable in 0.4.0; verified that sge/ssg only use `projectSettings`/re-exported keys,
not the task value directly (grep both build.sbt for `discoverManifests` — 0 hits outside
plugin re-export).

Error message format (exact template — tests assert on it):

```
[native-provider] Native library collision for platform '<classifier>':
  file '<fileName>' is bundled by <N> providers:
    - '<providerName>' (<source>) at <resourcePath>
    - '<providerName>' (<source>) at <resourcePath>
  Classpath order would silently decide which library is extracted/loaded.
  Fix: depend on only one of these providers, or rename the library in one of them.
```

### 2.3 Detection sites (all four)

1. **SN link path (build-time)** — `plugin/.../NativeProviderSettings.scala`, inside the
   `discoverManifests` task body after `NativeExtract.discoverManifests(...)`:
   `if (collisions.nonEmpty) sys.error(collisions.map(render).mkString("\n"))`.
   This covers sge/ssg native axes (they apply `NativeProviderPlugin.projectSettings`).
2. **Extraction (build-time, content-based)** — `plugin/.../NativeExtractSettings.scala`
   `nativeLibExtract`: before extracting, collect per-JAR entry lists
   (`NativeExtract.findNativeLibJars` extended with an entry-listing helper) and fail if
   two different JARs would write the same target file name. Catches providers whose
   `bundles` lies or is absent.
3. **JVM consumers (build-time)** — JVM projects do NOT enable NativeProviderPlugin, so
   add `NativeProviderSettings.collisionCheckSettings: Seq[Setting[_]]` defining a new
   task `nativeProviderCheckCollisions` (discovers manifests from
   `Compile/dependencyClasspathAsJars` + resources for ALL of `ProviderType.all`, runs
   `detectBundleCollisions`, `sys.error` on hit) and wire `Test/compile` in consumers or
   their ci aliases to depend on it (consumer issues SGE-2/SSG-1 decide the exact hook
   point; default recommendation: `Compile/compile := (Compile/compile).dependsOn(nativeProviderCheckCollisions).value`
   inside the consumers' JVM matrix action).
4. **Runtime (last resort)** — `NativeLibLoader` (§3): when the manifest index maps one
   `(classifier, fileName)` to >1 distinct artifact+version, throw `UnsatisfiedLinkError`
   with the same message body.

### 2.4 Bundle-truth validation (provider-side)

Generalize sge's ISS-484 gate into core:
`NativeExtract.validateBundles(manifest: ProviderManifest, jarEntries: Map[String, Long], minLibBytes: Long): Seq[String]`
— every `bundles` entry must exist in the JAR at its computed path and be ≥ minLibBytes;
every JAR `native/**` lib entry must be declared in `bundles` (undeclared → violation).
Provider repos call it from a `validateProviders` task (issue SNP-3/TSP-2);
`sge`'s `SgeNativeLibs` later delegates to it (issue SGE-2) instead of its regex parser.

### 2.5 Test plan

- **Core unit tests** (`core/src/test/scala/multiarch/core/`, munit, run with
  `sbt +core/test` — MUST pass on 2.12/2.13/3.3):
  - codec round-trip v2; v1 parse unchanged (use the real `sn-provider-curl` manifest as
    a fixture COPIED INTO core/src/test/resources/ — the manifest lives in the provider
    repo, not core's test classpath); `effectiveBundles` derivation for v1.
  - `detectBundleCollisions`: no-collision, v2×v2 collision, v1×v2 collision (derived
    bundles), same-artifact-twice non-collision, stub-vs-real collision. Assert exact
    message rendering.
  - `validateBundles`: missing entry, undersized entry, undeclared entry.
- **Deliberate-collision integration fixture**: new `test-project-collision/` in
  multiarch-scala — a minimal SN project (copy `test-project-native`'s shape) whose
  `Compile/resourceDirectories` contains TWO fixture manifests... impossible (one file
  name per dir) — instead: two tiny fixture provider JARs built by the test project's own
  build from `test-project-collision/fixture-providers/{a,b}/` (each: an
  `sn-provider.json` declaring `bundles: ["<host-classifier>/libcollide.a"]` + a dummy
  `native/.../libcollide.a` file), added via `Compile / unmanagedJars`. CI step (added to
  the existing multiarch-scala workflow):
  ```bash
  if sbt test-project-collision/discoverManifests > /tmp/collision.log 2>&1; then
    echo "EXPECTED FAILURE but build succeeded"; exit 1
  fi
  grep -q "Native library collision for platform" /tmp/collision.log
  ```
  Expected: task fails, grep exits 0. Failure branch: if `discoverManifests` cannot be
  invoked directly from the shell (task not reachable), expose an alias in the fixture's
  build (`addCommandAlias("checkCollisions", "test-project-collision/discoverManifests")`).

---

## 3. Workstream C — version-namespaced resource layout

### 3.1 Layout

New JAR entry path: `native/<provider-artifact>/<provider-version>/<platform-classifier>/<file>`
e.g. `native/pnm-provider-sge-desktop/0.2.0/linux-x86_64/libglfw.so`.
Legacy path (`native/<classifier>/<file>` and flat `native/<file>`) remains readable.

### 3.2 `NativeLibLoader` resolution order (exact, replaces the current 4-step order)

`load(libName)`:
1. In-memory cache.
2. `java.library.path` scan — UNCHANGED and FIRST (dev override covenant, §0.3). Note:
   `findOnLibraryPath` already splits on `File.pathSeparator` — multi-dir support needs
   no loader change (workstream E is consumer-side).
3. **v2 manifest index**: lazily (once per JVM) parse every `jni-provider.json` and
   `pnm-provider.json` on the classpath (reuse `discoverClasspathManifests`, now over
   both runtime types) and build `Map[(classifier, fileName), Seq[(artifact, version)]]`
   from `effectiveBundles` of manifests that have artifact+version. For the host
   classifier and `mapped` name: 0 hits → step 4; >1 distinct → throw
   `UnsatisfiedLinkError` with the §2.2 message; 1 hit → extract classpath resource
   `native/<artifact>/<version>/<classifier>/<mapped>` to the temp dir and return. If the
   declared resource is MISSING (manifest lies), log the fact into the eventual error
   message and continue to step 4 (do not hard-fail — a legacy-layout copy may exist).
4. **Legacy flat lookup**: `native/<hostClassifier>/<mapped>` (current behavior). On
   success, `System.err`-visible log line once per lib:
   `[multiarch] '<mapped>' resolved via legacy layout native/<classifier>/ — provider predates manifest v2`.
5. Android class-loader path (unchanged).
6. `UnsatisfiedLinkError` — extend the existing diagnostic to list BOTH searched
   classpath paths (v2 candidates from the index + the legacy path) and the
   `java.library.path` dirs.

`loadAll`/`loadConfigs`: unchanged semantics — they resolve `binary` names through
`load`, which now understands v2. (Note: current stripping logic
`stripPrefix("lib").replaceAll("\\.(so|dylib|dll)$","")` stays as-is.)

Unit tests (core): fake classpath via a `URLClassLoader` over a temp dir tree —
v2-only jar resolves; legacy-only resolves with fallback; v2 wins over legacy when both
present; two v2 owners throw; java.library.path still wins over both.

### 3.3 Plugin/consumer readers that must learn the dual layout

| Site | Change |
|---|---|
| `core/NativeExtract.findNativeLibJars` (line ~219) | also match prefix `native/<any>/<any>/<classifier>/` (match by regex `^native/([^/]+/){2}<classifier>/` OR consult the jar's own manifest `bundles`) |
| `core/NativeExtract.extractFromJar` (line ~241) | for v2 jars, strip the 3-segment prefix; keep flat + platform-dir handling for v1 |
| `plugin/AndroidBuild.scala:432` | accept `native/android-<arch>/...so` AND `native/<artifact>/<version>/android-<arch>/...so` |
| `plugin/JvmPackaging.scala` (~583) | no change — it copies from extracted DIRS, not from jar paths |
| `sge/sge-build/.../NativeProviderValidation.scala` | path check `native/$classifier/$binary` → compute from manifest v2 (or delegate to `NativeExtract.validateBundles`); covenant re-baseline required (§0.3) |

### 3.4 Migration steps per provider repo (identical recipe, two repos)

1. Bump metabuild dep: `project/plugins.sbt` `multiarch-core` `0.3.0` → `0.4.0`.
2. Replace `fatJarMappings`/`androidJarMappings` target path with
   `s"native/${moduleName}/${version.value}/${p.classifier}/${f.getName}"` (thread
   `name.value` and `version.value` in from each project's `packageBin/mappings` block).
3. Manifest generation: move each static `src/main/resources/*-provider.json` to
   `manifest-template.json` in the provider project dir; add a
   `Compile / resourceGenerators` task calling a new core helper
   `ProviderManifestCodec.enrich(templateJson, artifact = name.value, version = version.value, bundles = <computed from crossDir listing>): String` — returns the enriched JSON string; enrich SETS schemaVersion to 0.2.0 (the template may still declare 0.1.x; enriched output is by definition v2); v2 keys serialize after `provider-name`, before `configs`, arrays inline
   writing the final `*-provider.json` into managed resources. `bundles` computed from the
   SAME `crossDir/<classifier>` listing that feeds `packageBin/mappings` (single source of
   truth → cannot drift). Failure branch: if `crossDir` is empty locally (natives not
   built), the generator emits the template's declared binaries as bundles and logs a
   warning — CI (which always cross-builds first, `scripts/cross-all.sh`) produces the
   real thing; `validateProviders` (§2.4) gates the release.
4. Add the MISSING physics3d manifests (sge-native-providers): create
   `manifest-template.json` for `pnm-provider-sge-physics3d-{desktop,android}` and
   `sn-provider-sge-physics3d` mirroring the physics ones with config `sge_physics3d`,
   binaries `libsge_physics3d.{so,dylib}` / `sge_physics3d.dll` / `libsge_physics3d.a` /
   `sge_physics3d.lib` (confirm exact file names against
   `native-components/target/cross/<classifier>/` listings or the `libs` sets in
   `build.sbt` before writing — do not guess extensions).
5. Add `validateProviders` task + wire it before publish in `release.yml`.
6. Publish a snapshot; verify a consumer resolves and loads it (SGE-2/SSG-1 verify).

### 3.5 Compatibility window policy

- multiarch-scala 0.4.0: loader reads v2 first, legacy fallback with log line (§3.2.4).
- Provider artifacts adopting v2 layout REQUIRE loader ≥0.4.0 (document in both provider
  repos' READMEs: "provider ≥0.2.0 needs multiarch ≥0.4.0"); consumers therefore bump
  `multiarch` BEFORE `nativeComponents`/`treeSitterProviders` (sequencing §8).
- Legacy read path removal: not before multiarch 1.0.0 AND not before 2027-01 (≥6
  months), AND only after both provider repos + all kubuszok consumers publish v2.
  Record this as a tracking issue in multiarch-scala at implementation time; the
  loader's scaladoc names the issue number as the revisit trigger.

---

## 4. Workstream D — `--enable-native-access` scoping (investigated)

### 4.1 Findings (verified 2026-07-02, re-verify in the issue with the commands given)

- sge and ssg fork all JVM runs with a **classpath**, never a module path: sbt 2.0.1
  `fork := true` uses `sbt.ForkMain` with `-cp`; the it-desktop harness re-injects the
  classpath via `-Dsge.it.classpath` (`sge/build.sbt`, it-desktop block); the Roast
  launcher config written by `JvmPackaging.writeRoastConfig` has a `"classPath"` array
  and no module path; the only `--module-path` in the toolchain is jlink assembling the
  JRE image from `jmods` (`JvmPackaging.scala` ~line 360), which does not name app code.
- No `module-info.java`/`module-info.class` exists in sge, ssg, lls, or multiarch-scala
  (re-verify: `find <repo> -name "module-info.*" -not -path "*/target/*"` → expect 0).
- Consequently ALL app+library code runs in the **unnamed module**, and the only token
  the JVM accepts for it in `--enable-native-access` is `ALL-UNNAMED`. Per-module
  scoping (`--enable-native-access=my.module`) is only meaningful for named modules ON
  the module path. There is nothing to scope today.

### 4.2 Decision

`--enable-native-access=ALL-UNNAMED` STAYS at `sge/build.sbt:134`, `ssg/build.sbt:71`,
the it-desktop javaOptions, and `JvmPackaging` launchers. It is not a hack; it is the
correct spelling for classpath deployments, and required so JEP 472 enforcement (warning
since JDK 24, deny-by-default later) never breaks Panama FFM calls.

Deliverable: `multiarch-scala/docs/native-access.md` (~1 page) recording the finding, the
verification commands above, plus a pointer comment at each of the four flag sites.
Revisit triggers (all listed in that doc):
1. `JvmPackaging` gains module-path app packaging (then: emit
   `--enable-native-access=<modules>` computed from the jars' `Automatic-Module-Name`).
2. Any consumer adds `module-info.java`.
3. JDK release notes announce deprecation of the `ALL-UNNAMED` token.

### 4.3 Optional hardening in scope for 0.4.0 (small)

`panama-jdk` / `multiarch-core` jars gain `Automatic-Module-Name` manifest attributes
(`multiarch.core`, `multiarch.panama.api`, `multiarch.panama.jdk`) via
`Compile / packageBin / packageOptions += Package.ManifestAttributes("Automatic-Module-Name" -> ...)`
so future module-path adopters CAN scope. Zero behavioral change today.

---

## 5. Workstream E — multi-dir `java.library.path` for the dev flow

Loader side: already multi-dir (`NativeLibLoader.findOnLibraryPath` splits on
`File.pathSeparator`) — add a core unit test pinning this and a scaladoc sentence; no
code change.

Consumer side (sge only; ssg sets no java.library.path):
`sge/build.sbt` `commonSettings` JVM `javaOptions` (~lines 126-135) currently:
`Seq(s"-Djava.library.path=$rustLib", "--enable-native-access=ALL-UNNAMED") ++ macFlags`.
Change to build the property from a list, preserving the JVM default tail:

```scala
val libDirs: Seq[String] =
  Seq(rustLib) ++
  sys.env.get("SGE_NATIVE_LIB_PATH").toSeq.flatMap(_.split(java.io.File.pathSeparator)) ++
  Option(System.getProperty("java.library.path")).toSeq
Seq(s"-Djava.library.path=${libDirs.mkString(java.io.File.pathSeparator)}", "--enable-native-access=ALL-UNNAMED") ++ macFlags
```

- `SGE_NATIVE_LIB_PATH` is the documented dev knob (e.g. a locally built ANGLE dir + a
  locally built freetype dir simultaneously — the current single-value setup cannot do
  that).
- MUST NOT touch the it-desktop project (it deliberately sets no java.library.path —
  ISS-485 comment). Verify after the change:
  `sbt --client 'sge-it-desktop/Test/javaOptions'` output contains NO `-Djava.library.path`.
- Same pattern optionally offered in `JvmPackaging` launcher scripts
  (`$APP_HOME/native` + platform default) — record as a follow-up note in the issue, not
  required for done.

---

## 6. Release & cross-publishing constraints (found in the build — implementers MUST respect)

From `multiarch-scala/build.sbt` (verified):

- `core`: `crossScalaVersions := Seq("2.12.21", "2.13.18", "3.3.8")`. The 2.12 build is
  consumed by the sbt-1.x plugin axis; the **Scala 3 build is consumed by sbt 2.0
  metabuilds** (both provider repos' `project/plugins.sbt` do
  `libraryDependencies += "com.kubuszok" %% "multiarch-core" % "0.3.0"` under sbt 2.0.x,
  which resolves `multiarch-core_3`). All new core code: 2.12-compatible syntax, no
  Scala-3-only features outside version-specific dirs.
- `plugin`: `crossScalaVersions := Seq("3.8.4", "2.12.21")` with
  `pluginCrossBuild/sbtVersion` = `1.12.12` (2.12) / `2.0.0` (3.8.4);
  sbt-projectmatrix only on the 2.12 axis; munit tests only on the 2.12 axis;
  per-axis sources in `plugin/src/main/scala-2.12/` and `scala-3/` (`Compat.scala` —
  `Compat.uncached`, `Compat.toFiles`). New plugin code goes in the shared
  `plugin/src/main/scala/` and must compile on BOTH axes: verify with
  `sbt +plugin/compile` (expected: two compilations, 2.12.21 and 3.8.4, both green).
- Release command: `sbt ci-release` (custom root command: `+core/publishSigned`,
  matrix-row publishes for multiarch-resources, `+plugin/publishSigned`,
  `snProviderCurl/publishSigned`, `panama-api/publishSigned`, `panama-jdk/publishSigned`,
  then `sonaRelease` when tagged). Version comes from git tags (sbt-git) — release =
  push tag `v0.4.0`.
- Consumers pin `0.3.0` in FOUR places total: `sge/project/plugins.sbt` (plugin),
  `sge/project/Versions.scala:31` (libs), `ssg/project/plugins.sbt`,
  `ssg/project/Versions.scala:31`; provider repos in `project/plugins.sbt` each.
  All six bumps to `0.4.0` are enumerated in the consumer issues.

Sequencing (hard): **multiarch-scala 0.4.0 releases FIRST** → provider repos publish v2
artifacts → sge/ssg bump both pins together. The flag AUDIT (workstream A) has no
dependency on any of this and starts immediately against current versions.

---

## 7. Verification gates (orchestrator re-runs independently)

| Gate | Command | Expected |
|---|---|---|
| G1 core cross | `cd multiarch-scala && sbt +core/test` | 3 Scala versions, all tests green incl. new codec/collision/loader suites |
| G2 plugin cross | `sbt +plugin/compile && sbt +plugin/test` | 2.12 + 3.8.4 compile; 2.12 tests green |
| G3 SN integration | `sbt 'test-project-native/nativeLink' && sbt 'test-project-native/run'` | links with curl provider, runs exit 0 |
| G4 jlink integration | `sbt test-project-jlink/releasePackage` | package produced, launcher contains `--enable-native-access=ALL-UNNAMED` |
| G5 collision fixture | §2.5 shell snippet | build FAILS with the exact message; grep exit 0 |
| G6 sge | `re-scale runner native-ffi-it` ; `re-scale runner desktop-it` ; `sbt --client 'sge/sgeValidateNativeLibs'` (JVM row) | all green on the branch state after SGE-2 |
| G7 ssg | `sbt --client 'ssg-highlight/testFull'` ; `sbt --client 'ssg-highlightNative/testFull'` | green after SSG-1 |
| G8 audit integrity | TSV row count == experiment count from §0.5 census (minus `not-auditable`); every row has a classification and evidence | complete table committed |
| G9 ratchets | sge: `/sge:ratchet-check`; ssg: `/ssg:ratchet-check` | no regression |

Plus the roadmap-standard Opus dry-run gate on this document before 2026-07-06.

---

## 8. Issue decomposition (ready to file)

File in each repo's tracker (GitHub issues for multiarch-scala and both provider repos;
sge/ssg use `re-scale db issues add`). One Opus session each. Titles are final; bodies =
the referenced sections of this doc (link the doc path + section numbers).

### multiarch-scala (order: MA-1 → MA-2 → MA-3 → MA-4 → MA-5 → MA-6)

- **MA-1 — Manifest v2 model + codec + enrich helper** (§2.1, §3.4.3 helper).
  Files: `core/.../ProviderManifest.scala`, `ProviderManifestCodec.scala`, new tests.
  Done: v2 fields parse/write; v1 serialization additively unchanged (semantic round-trip + golden-output vs the PRE-CHANGE writer, never byte-compare vs source files);
  `effectiveBundles` + `enrich` implemented. Verify: G1.
- **MA-2 — Collision detection core logic + validateBundles** (§2.2, §2.4).
  Files: `core/.../NativeExtract.scala` (incl. `discoverManifests` source-tracking return
  type), tests. Done: `detectBundleCollisions` + `validateBundles` with exact message
  rendering, all §2.5 unit cases. Verify: G1.
- **MA-3 — NativeLibLoader v2 resolution + legacy fallback + runtime collision guard**
  (§3.2, §5 loader test). Files: `core/.../NativeLibLoader.scala`, tests
  (URLClassLoader fixtures). Done: resolution order exactly as §3.2, diagnostics list all
  searched paths, multi-dir java.library.path pinned by test. Verify: G1.
- **MA-4 — Plugin wiring: build-time collision checks + dual-layout readers** (§2.3.1-3,
  §3.3 rows 1-4, §4.3 Automatic-Module-Name). Files: `NativeProviderSettings.scala`,
  `NativeExtractSettings.scala`, `AndroidBuild.scala`, `build.sbt`. Done: SN path fails
  on collision; extraction content check; `collisionCheckSettings` exists and documented
  in README; v2 jars extract correctly. Verify: G2, G3, G4.
- **MA-5 — Collision fixture + sn-provider-curl v2 migration** (§2.5 fixture, §3.4 recipe
  applied to `sn-provider-curl` in-repo: versioned mappings in `build.sbt` lines ~200-210
  + template/generator). Done: G5 red-path green; G3 still green with the migrated curl
  provider (proves v2 end-to-end through SN extraction+link). Verify: G3, G5.
- **MA-6 — Release 0.4.0** (§6). Done: MiMa triaged, `v0.4.0` tag pushed, `ci-release`
  green, Central shows `multiarch-core_{2.12,2.13,3}`, `sbt-multiarch-scala` for sbt 1.x
  (`_2.12_1.0`) and sbt 2 (`_3_2.0`... verify actual suffixes against the 0.3.0 listing),
  `sn-provider-curl` 0.4.0. Verify: fresh scratch project resolves both plugin axes.

### sge-native-providers

- **SNP-1 — Flag-audit script + macOS run** (§1.1-1.4). Files: `scripts/flag-audit.sh`,
  `docs/audits/2026-07-flag-necessity.{tsv,md}` (partial: macos rows). Done: baseline
  green documented; all macos-aarch64 experiments recorded; consumer `Versions.scala`
  restored (git-clean check). Verify: G8 (macos subset), sge `re-scale runner
  native-ffi-it` green at HEAD afterwards.
- **SNP-2 — Apply audit results to manifests** (§1.5; AFTER SNP-1 + SGE-1 + SGE-3).
  Done: `unnecessary`/`required-by-app` groups removed, TSV row cited per removal,
  snapshot published, sge FFI ITs green on all CI legs. Verify: G6 + sge CI native legs.
- **SNP-3 — Manifest v2 + versioned layout migration** (§3.4 all 6 steps; AFTER MA-6).
  Done: all 14 provider projects (incl. the 3 manifest-less physics3d ones) publish v2
  JARs; `validateProviders` green and wired into release.yml. Verify: `sbt validateProviders`
  + jar `unzip -l` spot-check shows `native/<artifact>/<version>/<classifier>/` entries.

### ssg-native-providers

- **TSP-1 — Flag audit for sn-provider-tree-sitter** (§1, consumer command
  `sbt --client 'ssg-highlightNative/clean; ssg-highlightNative/testFull'`). Done: 7
  experiments recorded in `docs/audits/2026-07-flag-necessity.tsv` (macos local; linux /
  windows via the ssg flag-audit workflow or recorded `not-audited-ci`). Verify: G8
  subset, G7 at HEAD.
- **TSP-2 — Manifest v2 + versioned layout migration + apply audit results** (§3.4, §1.5;
  AFTER MA-6, TSP-1). Smaller repo — migration and flag application fit one session.
  Verify: `validateProviders`; ssg G7 against the published snapshot.

### sge

- **SGE-1 — Temporary flag-audit CI workflow** (§1.4). Files:
  `.github/workflows/flag-audit.yml` (workflow_dispatch, matrix over linux-x86_64/
  linux-aarch64/windows-x86_64, replicating the ci.yml native-FFI job env). Done: TSV
  artifacts for all three legs handed to SNP-1's doc; workflow deletion PR opened once
  results are committed. Verify: dispatch run green ×3, artifacts downloaded.
- **SGE-2 — Adopt multiarch 0.4.0 + providers v2** (AFTER MA-6 + SNP-3). Edits:
  `project/plugins.sbt` + `project/Versions.scala:31` → 0.4.0;
  `Versions.scala:45` → new providers version; `SgeNativeLibs`/`NativeProviderValidation`
  v2 path validation via `NativeExtract.validateBundles` (covenant re-baseline, §0.3);
  wire `collisionCheckSettings` (§2.3.3). Done-criteria: G6 green; `sgeValidateNativeLibs`
  fails when pointed at a doctored provider jar (prove the gate still bites). Verify: G6, G9.
- **SGE-3 — Consumer flags + multi-dir library path + native-access doc** (§1.5 consumer
  half, §5, §4.2 comment). Edits: `sge/build.sbt` `commonSettings` javaOptions block +
  native `nativeProviderSettings` block; `docs/architecture/` cross-link to
  multiarch's `native-access.md`. Done: audited `required-by-app` flags live in
  build.sbt with TSV citations; `SGE_NATIVE_LIB_PATH` documented in README/dev docs;
  it-desktop javaOptions unchanged (§5 check). Verify: G6, G9.

### ssg

- **SSG-1 — Adopt 0.4.0 + tree-sitter v2 + native-access note** (AFTER MA-6 + TSP-2).
  Edits: `project/plugins.sbt`, `project/Versions.scala:31-32`; resolve the standing
  `TODO` at `ssg/build.sbt:202` ("check if NativeProviderPlugin.projectSettings is
  necessary") — answer it empirically while touching the block: remove → relink → if
  link fails, keep and replace the TODO with the evidence; wire `collisionCheckSettings`
  on the highlight JVM axis; extend the `--enable-native-access` comment at
  `ssg/build.sbt:~71` with the §4.2 rationale + revisit triggers. Done: G7 green.
  Verify: G7, G9.

Dependency graph:

```
MA-1 → MA-2 → MA-3 → MA-4 → MA-5 → MA-6 ──→ SNP-3 ──→ SGE-2 → SGE-3
                                        └──→ TSP-2 ──→ SSG-1
SNP-1 (now) ──┐
SGE-1 (now) ──┼→ SNP-2 (after SGE-3's consumer-flag landing spot exists)
TSP-1 (now) ──┘
```

---

## 9. Failure-branch index (applies across issues)

- Any sbt task in sge/ssg hangs or the server is wedged →
  `re-scale proc kill --kind sbt --dir .`, retry once; still failing → file issue with
  the full `--client` log, STOP.
- `+core/test` fails on exactly one Scala version → the new code uses
  version-specific syntax; fix in shared source (no version-specific source dirs in core
  today — keep it that way). If genuinely impossible, STOP and file an issue proposing a
  `core/src/main/scala-2.12` split rather than improvising one.
- MiMa failure on 0.4.0 → expected; adjust baseline per §2.1. MiMa failure on a
  PATCH release → you are on the wrong version plan, STOP.
- Provider publishLocal emits an unexpected version (sbt-git) → §1.2 step 3 branch.
- Consumer resolves a STALE provider snapshot → you violated the unique-version rule of
  §1.2; restart that experiment with a fresh `0.0.0-flagaudit.N+1`.
- Collision check fires on TODAY'S real classpaths (it should not: verified census shows
  no duplicate `(classifier, file)` across current providers — `pnm-provider-sge-desktop`
  depends on `pnm-provider-sge-angle` but their bundle sets are disjoint) → treat as a
  REAL finding: do not weaken the check; file an issue in the offending provider repo
  with the collision message attached, STOP that consumer bump until resolved.
- Opus dry-run gate exhibits any ambiguity executing step 1 of an issue → the defect is
  in THIS document; route it back to the planning session, do not "interpret".
