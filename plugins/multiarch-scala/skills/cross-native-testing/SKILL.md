---
description: Zig cross-compilation for Scala Native — build and link-test all 6 desktop targets from one machine without CI
---

# Cross-Native Local Testing with Zig

## Why this exists

The most common failure mode in a cross-platform Scala Native project is
**linking**. A change compiles fine but `nativeLink` fails on a non-host
platform because of missing symbols, wrong flag ordering, or a provider
manifest that doesn't cover that platform.

The naive feedback loop is:
1. Build native C/Rust libraries locally
2. Publish provider JARs (snapshot or release)
3. Bump versions in the consuming project
4. Push to CI with 5+ parallel jobs
5. Wait 10-20 minutes to discover a linker error on linux-aarch64

`sbt-multiarch-scala` shortcuts this: install `zig`, and you can
cross-compile + link Scala Native binaries for all 6 desktop platforms
**on your local machine** in a single `sbt` session. You can't *run*
the resulting binaries (a macOS machine can't execute a Linux ELF), but
you prove they **link** — which is the part that breaks.

## Prerequisites

### 1. Install zig

```bash
# macOS
brew install zig

# Linux
# Download from https://ziglang.org/download/ or use your package manager
sudo apt install zig    # Ubuntu/Debian (check version)
```

Verify:

```bash
zig version
# Should print 0.13.0 or later
```

### 2. Verify zig is visible to sbt

The plugin checks `ZigCross.isAvailable` by running `zig version`. If sbt
is started from an environment where `zig` is not on PATH, cross-native
rows silently become no-ops. Verify in sbt:

```bash
sbt --client 'show zigCrossTarget'
```

If you get `None` for a subproject that should have a cross target, zig
isn't on PATH in the sbt server's environment. Restart sbt after fixing PATH.

### 3. Provider JARs on classpath

Cross-linking needs the static libraries (`.a` / `.lib`) for **every target
platform** bundled in the provider JARs. A provider JAR that only contains
`native/macos-aarch64/libfoo.a` will fail to extract for `linux-x86_64`.

Provider JARs from Maven Central (e.g., `sn-provider-curl`) are **fat JARs**
containing all 6 desktop platforms. If you're building your own providers,
ensure all platforms are included before testing cross-linking.

## How it works

### The zig cross-compilation chain

```
.withCrossNative(scalaVersion)
  │
  ├─ checks ZigCross.isAvailable (zig on PATH?)
  │   └─ if missing: returns matrix unchanged (no cross rows added)
  │
  ├─ filters Platform.desktop, removes Platform.host
  │   └─ on macOS-aarch64: keeps linux-x86_64, linux-aarch64,
  │      macos-x86_64, windows-x86_64, windows-aarch64
  │
  └─ for each target platform, creates a customRow:
      ├─ NativeCrossAxis(platform)           → subproject ID suffix
      ├─ enables ScalaNativePlugin
      ├─ enables NativeProviderPlugin        → extracts target platform's .a files
      ├─ enables MultiArchNativeReleasePlugin
      └─ sets zigCrossTarget := Some(platform)
          ├─ generates target/zig-wrappers/zig-cc-<classifier>
          │   └─ shell script: exec zig cc -target <zigTarget> "$@"
          ├─ generates target/zig-wrappers/zig-cxx-<classifier>
          │   └─ shell script: exec zig c++ -target <zigTarget> "$@"
          ├─ nativeConfig.withClang(zig-cc wrapper)
          ├─ nativeConfig.withClangPP(zig-cxx wrapper)
          ├─ nativeConfig.withTargetTriple(platform.scalaNativeTarget)
          ├─ NativeExtractSettings.nativeLibPlatform := platform
          └─ NativeProviderSettings.nativeProviderPlatform := platform
```

### What zig cc does

`zig cc` is a drop-in replacement for `clang` that bundles cross-compilation
sysroots for Linux (glibc), macOS, and Windows (mingw). No separate SDK
downloads needed. The wrapper scripts in `target/zig-wrappers/` simply
forward all arguments:

```sh
#!/bin/sh
exec zig cc -target x86_64-linux-gnu "$@"
```

Scala Native's LLVM IR compilation uses this as its C compiler, producing
object files for the target platform, then links them with the target's
static libraries from the provider JAR.

## Platform targets and naming

### The 6 desktop platforms

| Platform | classifier | scalaNativeTarget | zigTarget | Zig sysroot |
|----------|-----------|-------------------|-----------|-------------|
| Linux x86_64 | `linux-x86_64` | `x86_64-unknown-linux-gnu` | `x86_64-linux-gnu` | glibc |
| Linux aarch64 | `linux-aarch64` | `aarch64-unknown-linux-gnu` | `aarch64-linux-gnu` | glibc |
| macOS x86_64 | `macos-x86_64` | `x86_64-apple-darwin` | `x86_64-macos` | macOS SDK |
| macOS aarch64 | `macos-aarch64` | `aarch64-apple-darwin` | `aarch64-macos` | macOS SDK |
| Windows x86_64 | `windows-x86_64` | `x86_64-pc-windows-msvc` | `x86_64-windows-gnu` | mingw |
| Windows aarch64 | `windows-aarch64` | `aarch64-pc-windows-msvc` | `aarch64-windows-gnu` | mingw |

**Note:** `scalaNativeTarget` uses MSVC triple for Windows, but `zigTarget`
uses `windows-gnu` because zig's bundled sysroot is mingw-based. The resulting
binary is still a valid Windows PE executable.

### sbt subproject ID naming

`NativeCrossAxis` transforms the platform classifier into an sbt ID suffix:

```
linux-x86_64    → NativeLinuxX86_64
linux-aarch64   → NativeLinuxAarch64
macos-x86_64    → NativeMacosX86_64
macos-aarch64   → NativeMacosAarch64
windows-x86_64  → NativeWindowsX86_64
windows-aarch64 → NativeWindowsAarch64
```

For a matrix named `myApp`, the full project IDs are:

| What | sbt project ID |
|------|---------------|
| Host JVM | `myApp3` |
| Host JS | `myAppJS3` |
| Host Native | `myAppNative3` |
| Cross linux-x86_64 | `myAppNativeLinuxX86_643` |
| Cross linux-aarch64 | `myAppNativeLinuxAarch643` |
| Cross macos-x86_64 | `myAppNativeMacosX86_643` |
| Cross windows-x86_64 | `myAppNativeWindowsX86_643` |
| Cross windows-aarch64 | `myAppNativeWindowsAarch643` |

The host platform is excluded — if you're on macOS aarch64,
`myAppNativeMacosAarch643` doesn't exist because `.nativePlatform()`
already covers it as `myAppNative3`.

## Setting up cross-native in your build

### Option 1: withCrossNative (recommended)

```scala
import multiarch.sbt.ProjectMatrixOps._

lazy val myApp = (projectMatrix in file("my-app"))
  .settings(commonSettings)
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))
  .nativePlatform(scalaVersions = Seq(scala3), settings =
    NativeProviderPlugin.projectSettings ++ Seq(
      libraryDependencies += "com.kubuszok" % "sn-provider-curl" % "0.1.2"
    )
  )
  .withCrossNative(scala3)
```

`withCrossNative` is a **no-op** when zig isn't installed — safe to
leave in build.sbt even when developers don't have zig.

### Option 2: Manual per-platform rows

For fine-grained control (e.g., different settings per target):

```scala
import multiarch.sbt._

lazy val myApp = (projectMatrix in file("my-app"))
  .nativePlatform(scalaVersions = Seq(scala3))
  .customRow(
    scalaVersions = Seq(scala3),
    axisValues = Seq(
      NativeCrossAxis(Platform.LinuxX86_64),
      VirtualAxis.native,
      VirtualAxis.scalaABIVersion(scala3)
    ),
    process = _.enablePlugins(ScalaNativePlugin, NativeProviderPlugin, MultiArchNativeReleasePlugin)
      .settings(zigCrossTarget := Some(Platform.LinuxX86_64))
  )
```

### Option 3: ZigCross.crossSettings (single project, not matrix)

```scala
lazy val myApp = project.in(file("my-app"))
  .enablePlugins(ScalaNativePlugin, NativeProviderPlugin)
  .settings(ZigCross.crossSettings(Platform.LinuxX86_64))
```

This overrides the *entire* project to target Linux x86_64. Useful for
one-off experiments but not for multi-target builds.

## Local testing workflow

### Quick link-test: does it link on all platforms?

```bash
# Link the host-native binary (your platform)
sbt --client 'myAppNative3/nativeLink'

# Link a specific cross target
sbt --client 'myAppNativeLinuxX86_643/nativeLink'

# Link ALL cross targets (semicolons for batch)
sbt --client ';myAppNativeLinuxX86_643/nativeLink;myAppNativeLinuxAarch643/nativeLink;myAppNativeWindowsX86_643/nativeLink;myAppNativeWindowsAarch643/nativeLink;myAppNativeMacosX86_643/nativeLink'
```

If all succeed, you know linking works on every platform.
You **cannot** run the cross-compiled binaries (Linux ELF won't execute
on macOS), but linking is the step that fails 90% of the time.

### Discovering available cross-native subprojects

```bash
# List all projects in the build
sbt --client projects

# Look for the Native*3 pattern
sbt --client projects | grep Native
```

### Verifying zig wrappers were generated

After a successful cross-link, check:

```bash
ls target/zig-wrappers/
# Should show: zig-cc-linux-x86_64, zig-cxx-linux-x86_64, etc.
```

### Verifying provider extraction for a target

```bash
ls target/native-libs/linux-x86_64/
# Should show: libcurl.a, libfoo.a, etc.
```

If the directory is empty or missing, the provider JAR doesn't contain
libraries for that platform.

### Full local verification matrix

Run this before pushing to CI to catch linking failures early:

```bash
# 1. Compile all platforms (catches Scala compilation errors)
sbt --client ';myApp3/compile;myAppJS3/compile;myAppNative3/compile'

# 2. Link host native (catches host linking errors)
sbt --client 'myAppNative3/nativeLink'

# 3. Link all cross targets (catches cross-platform linking errors)
sbt --client ';myAppNativeLinuxX86_643/nativeLink;myAppNativeLinuxAarch643/nativeLink;myAppNativeWindowsX86_643/nativeLink;myAppNativeWindowsAarch643/nativeLink;myAppNativeMacosX86_643/nativeLink'

# 4. Run tests (JVM only — tests can't run cross-compiled)
sbt --client 'myApp3/test'
```

Steps 1-3 take ~2 minutes locally vs ~15 minutes on CI with 6 runners.

## Troubleshooting

### "zig: command not found" / cross-native rows missing

**Symptom**: `sbt projects` shows no `*NativeLinuxX86_64*` subprojects
**Cause**: zig not on PATH when sbt server started
**Fix**: Install zig, restart sbt server (`re-scale build kill-sbt` or `sbt --client shutdown`)

### Link failure: "undefined reference to ..." on a cross target

**Symptom**: `nativeLink` succeeds for host but fails for linux-x86_64
**Cause**: Provider JAR missing that platform's `.a` file, or `flags-groups`
doesn't cover that platform
**Fix**: Check the provider JAR contents:

```bash
# List what's in the provider JAR for that platform
jar tf ~/.cache/coursier/v1/.../sn-provider-foo-0.1.0.jar | grep linux-x86_64
```

If empty, the provider doesn't support that platform.

### Link failure: "cannot find -lstdc++" on cross target

**Symptom**: Cross-link to Linux fails with missing C++ stdlib
**Cause**: zig's sysroot doesn't include `libstdc++` — use `libc++` via zig
**Fix**: When cross-compiling, prefer `-lc++` for all targets (zig bundles libc++):

```scala
nativeConfig := {
  val c = nativeConfig.value
  c.withLinkingOptions(c.linkingOptions ++ Seq("-lc++"))
}
```

Or detect host-vs-cross:

```scala
val cppLib = zigCrossTarget.value match {
  case Some(_) => "-lc++"  // zig bundles libc++
  case None =>
    if (System.getProperty("os.name").toLowerCase.contains("mac")) "-lc++"
    else "-lstdc++"
}
```

### Link failure: Windows target produces "unresolved external symbol"

**Symptom**: Cross-link for `windows-x86_64` fails
**Cause**: Windows uses `.lib` extension, not `.a`. Provider may have wrong naming.
**Fix**: `NativeProviderPlugin` auto-creates `.lib` aliases from `.a` files on
Windows targets. If still failing, check the manifest's Windows entry uses
the correct `binary` filename.

### Slow cross-compilation

**Symptom**: Cross-linking takes much longer than host linking
**Cause**: zig cc downloads/caches sysroots on first use
**Fix**: First cross-link is slow (~30s extra). Subsequent builds reuse zig's
global cache. You can pre-warm: `zig cc -target x86_64-linux-gnu -c /dev/null -o /dev/null`

### Cross-target extracts wrong platform libraries

**Symptom**: Link error mentions wrong architecture symbols
**Cause**: `nativeLibPlatform` not synced with `zigCrossTarget`
**Fix**: This is handled automatically by `MultiArchNativeReleasePlugin`.
If using manual settings, ensure both are set:

```scala
NativeExtractSettings.nativeLibPlatform      := platform
NativeProviderSettings.nativeProviderPlatform := platform
```

## What you CAN'T test locally

- **Runtime behavior on non-host platforms** — cross-compiled binaries don't execute
- **Platform-specific runtime bugs** — e.g., macOS `-XstartOnFirstThread`, Windows DLL loading paths
- **CI-specific environment** — different glibc versions, missing system libraries
- **Android targets** — zig cross-compilation is not supported for Android (use NDK)

## What you CAN test locally (and should)

- **Linking succeeds** — the #1 failure mode in CI
- **Provider manifests cover all platforms** — extraction failure = immediate error
- **Flag ordering is correct** — linker flag errors are deterministic
- **All Scala code compiles for all platforms** — Scala.js and Scala Native have
  different stdlib subsets; a method available on JVM may not exist on Native

## Real-world example: SGE demos

SGE's 11 demos each build for 8+ targets from a single `build.sbt`:

```scala
def demo(dir: String, ...)(matrix: ProjectMatrix): ProjectMatrix =
  matrix
    .jvmPlatform(...)
    .jsPlatform()
    .nativePlatform()
    .withCrossNative    // adds 5 more native targets (all non-host)

val pong = demo("pong", ...)(projectMatrix in file("pong"))
// produces: pong3, pongJS3, pongNative3,
//   pongNativeLinuxX86_643, pongNativeLinuxAarch643,
//   pongNativeMacosX86_643, pongNativeWindowsX86_643,
//   pongNativeWindowsAarch643
```

Local verification before CI:

```bash
sbt --client ';pongNativeLinuxX86_643/nativeLink;pongNativeWindowsX86_643/nativeLink'
```

If both link, the CI cross-platform jobs will almost certainly pass too.
