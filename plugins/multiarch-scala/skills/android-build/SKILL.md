---
description: Android APK build pipeline using sbt-multiarch-scala — SDK management, DEX compilation, native .so packaging, signing, and installation without Gradle
---

# Android App Build Workflow

## Overview

`sbt-multiarch-scala` provides a complete Android APK build pipeline
implemented entirely in sbt — no Gradle, no Android Studio project
structure required. The pipeline compiles Scala 3 to JVM bytecode,
converts it to DEX format via D8, packages it with aapt2, signs with
apksigner, and installs via adb.

Build pipeline: `androidDex` -> `androidPackage` -> `androidSign` -> `androidInstall`

## Prerequisites

### JDK 17+

Android build tools (D8, aapt2, apksigner) require JDK 17 or later.
JDK 22+ is recommended if also targeting desktop JVM with Panama FFM.

### Android SDK

The SDK is resolved automatically. You do NOT need to install it manually.

Resolution order:
1. `ANDROID_HOME` environment variable
2. `ANDROID_SDK_ROOT` environment variable (deprecated but still recognized)
3. Auto-download to `androidSdkCacheDir` (defaults to `<baseDirectory>/android-sdk`)

When no SDK is found, `AndroidPlugin` downloads Android command-line tools,
then runs `sdkmanager` to install the required platform and build-tools.
Licenses are auto-accepted during download.

### No Gradle

The entire pipeline is sbt-native. No `build.gradle`, no Android Gradle
Plugin, no Gradle wrapper. All Android SDK tools (D8, aapt2, zipalign,
apksigner, adb) are invoked directly from sbt tasks.

## Setup

### project/plugins.sbt

```scala
addSbtPlugin("com.eed3si9n"     % "sbt-projectmatrix"   % "0.11.0")
addSbtPlugin("com.kubuszok"     % "sbt-multiarch-scala" % "0.1.2")
```

### build.sbt

```scala
lazy val androidApp = project.in(file("android"))
  .enablePlugins(AndroidPlugin)
  .settings(
    scalaVersion := "3.8.3",
    Compile / mainClass := Some("com.example.MainActivity"),
    // Optional: override SDK cache location (default: baseDirectory / "android-sdk")
    androidSdkCacheDir := (ThisBuild / baseDirectory).value / "android-sdk"
  )
```

### AndroidManifest.xml

Place at `src/main/resources/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myapp">
    <application
        android:label="My App"
        android:hasCode="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

## Build Pipeline Details

### Step 1: androidDex

Compiles all JVM bytecode (Scala classes + dependency JARs) to Android DEX format.

1. Triggers `Compile / compile` to produce `.class` files
2. Collects all JARs and class directories from `fullClasspath`
3. Creates a fat JAR of all classes (excluding `android.jar`, `META-INF/`, `java/*` stdlib classes, and Scribe logging JARs)
4. Runs D8 via `java -cp <r8Jar> com.android.tools.r8.D8 --min-api <minApi> --lib <android.jar> --output <dexDir> <fatJar>`
5. Produces `classes.dex` (and `classes2.dex`, `classes3.dex`, etc. for multi-dex) in `target/<scalaVersion>/android/dex/`

D8 is invoked from the R8 JAR (`build-tools/<version>/lib/d8.jar`) which ships
with the Android SDK build-tools. D8 performs pure DEX conversion plus
desugaring (Java 8+ lambdas, try-with-resources, etc.) but does NOT perform
code shrinking or optimization.

### Step 2: androidPackage

Assembles the unsigned APK:

1. Runs aapt2 to link `AndroidManifest.xml` with `android.jar` into a base APK
2. Stages app assets from resource directories into `target/<scalaVersion>/android/assets/`
3. Adds all DEX files from step 1 into the APK
4. Extracts native `.so` files from dependency JARs (provider JARs) and adds them to `lib/<abi>/` in the APK
5. Runs zipalign for memory-aligned access

### Step 3: androidSign

Signs the APK with a debug keystore:

1. Auto-generates a debug keystore at `target/<scalaVersion>/android/debug.keystore` if not present (RSA 2048, validity 10000 days)
2. Copies the aligned APK to `app-debug.apk`
3. Signs with apksigner using the debug keystore

### Step 4: androidInstall

Installs the signed APK on a connected device or emulator via adb:

```bash
adb install -r <path-to-app-debug.apk>
```

Requires `platform-tools` to be installed in the SDK (provides `adb`).

## Setting Keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `androidSdkCacheDir` | `File` | `baseDirectory / "android-sdk"` | Where to download/cache the SDK |
| `androidSdkRoot` | `Task[File]` | auto-resolved | SDK root (resolved from env vars or cache dir) |
| `androidMinSdk` | `Int` | `26` | Minimum API level (Android 8.0) |
| `androidTargetSdk` | `Int` | `35` | Target API level for compilation |
| `androidBuildToolsVersion` | `String` | `"35.0.0"` | Build tools version |
| `androidR8Rules` | `Option[File]` | `None` | ProGuard/R8 rules file (exists but not yet wired) |

## Android SDK Defaults

| Parameter | Value | Notes |
|-----------|-------|-------|
| minSdkVersion | 26 | Android 8.0 — minimum for PanamaPort |
| targetSdkVersion | 35 | Android 15 — compilation target |
| buildToolsVersion | 35.0.0 | Provides D8, aapt2, zipalign, apksigner |
| platformVersion | android-35 | Matches targetSdkVersion |

## Native .so Packaging

Provider JARs containing native shared libraries for Android are automatically
extracted and placed in the APK's `lib/<abi>/` directory.

The plugin scans all classpath JARs for entries matching `native/android-<classifier>/<name>.so`
and maps classifiers to Android ABI directories:

| Provider classifier | APK ABI directory |
|--------------------|-------------------|
| `android-aarch64` | `arm64-v8a` |
| `android-armv7` | `armeabi-v7a` |
| `android-x86_64` | `x86_64` |

Provider JARs with `jni-provider.json` or `pnm-provider.json` manifests
containing `native/android-*/*.so` entries are handled automatically.

You can also place native libraries directly at `src/main/resources/lib/<abi>/<name>.so`
for project-local native code.

## PanamaPort Dependency Resolution

Android does not ship `java.lang.foreign` (Panama FFM). For apps that use
Panama-based FFI, the PanamaPort library (`com.v7878.foreign`) provides a
backport that works on Android API 26+.

`AndroidDeps.resolvePanamaPort(cacheDir, log)` handles this:

1. Downloads AAR artifacts from Maven Central (`io.github.vova7878/panama/*`)
2. Extracts `classes.jar` (and any JARs in `libs/`) from each AAR
3. Returns a `Seq[File]` of JARs added to `Compile / unmanagedJars`

PanamaPort dependencies (resolved automatically):
- Core, Unsafe, VarHandles, LLVM (AAR modules)
- SunCleanerStub, R8Annotations, SunUnsafeWrapper (AAR)
- DexFile, AndroidMisc (JAR)

Standard sbt/Ivy dependency resolution cannot handle AARs, so this custom
resolver downloads and caches them independently.

## API Level: D8 vs R8

### Current state: D8 only

The build currently uses D8 (`com.android.tools.r8.D8`) for DEX compilation.
D8 does:
- Pure DEX conversion (JVM bytecode -> Dalvik bytecode)
- Desugaring (Java 8+ lambdas, default methods, try-with-resources)

D8 does NOT do:
- Code shrinking (tree shaking)
- Optimization (method inlining, constant folding)
- API-level adaptation via ProGuard rules

### Known limitation: R8 not wired

The `androidR8Rules` setting key exists but is **not wired** into the DEX
command. The code always invokes `com.android.tools.r8.D8`, never
`com.android.tools.r8.R8`.

This matters because PanamaPort's AAR artifacts ship `proguard.txt` rules
that instruct R8 how to adapt API 36+ constructs for lower API levels. Without
R8 + those ProGuard rules, PanamaPort may reference runtime APIs that don't
exist on the target device's API level.

The PanamaPort author confirmed API 26 works IF R8 is run with the
proguard.txt rules extracted from the AARs. See
https://github.com/kubuszok/sge/issues/5

### Future work

To fully support API 26 at runtime:
1. Extract `proguard.txt` from each PanamaPort AAR
2. Combine all rules into a single rules file
3. Invoke `com.android.tools.r8.R8` instead of `D8` with `--pg-conf <rules>`
4. Wire the `androidR8Rules` setting into the command

### D8 vs R8 summary

| Feature | D8 | R8 |
|---------|----|----|
| DEX conversion | Yes | Yes |
| Desugaring | Yes | Yes |
| Tree shaking | No | Yes |
| Optimization | No | Yes |
| ProGuard rules | No | Yes |
| API adaptation | No | Yes (via rules) |

## Scribe Logging Exclusion

Scribe logging JARs are excluded from the fat JAR during DEX compilation.
Scribe's lambda patterns cause `VerifyError: wide register index out of range`
when D8 processes Scala 3 bytecode. The exclusion filter matches JARs with
"scribe" in the filename.

Apps that need logging on Android should route their logging facade to
`android.util.Log` via reflection or a platform-specific implementation.

## Classpath Handling

The `android.jar` from the SDK is added to `Compile / unmanagedJars` as a
compile-time library reference (API stubs). It is NOT included in the fat JAR
for D8 — Android provides these classes at runtime.

Classes under `java/*` in dependency JARs are also excluded from the fat JAR,
as they conflict with Android's runtime stdlib.

`META-INF/` entries are stripped to avoid signature and manifest conflicts.

## Build Commands

```bash
# Full pipeline: compile -> DEX -> package -> sign -> install
sbt --client 'androidInstall'

# Individual steps
sbt --client 'androidDex'       # Compile + DEX
sbt --client 'androidPackage'   # DEX + APK assembly + zipalign
sbt --client 'androidSign'      # Package + sign with debug keystore
sbt --client 'androidInstall'   # Sign + install via adb

# Each task depends on the previous, so running androidInstall
# automatically runs the entire pipeline.
```

## Example: Full Android Project

```scala
// project/plugins.sbt
addSbtPlugin("com.eed3si9n"     % "sbt-projectmatrix"   % "0.11.0")
addSbtPlugin("com.kubuszok"     % "sbt-multiarch-scala" % "0.1.2")

// build.sbt
val scala3 = "3.8.3"

lazy val core = (projectMatrix in file("core"))
  .settings(scalaVersion := scala3)
  .jvmPlatform(scalaVersions = Seq(scala3))

lazy val android = project.in(file("android"))
  .enablePlugins(AndroidPlugin)
  .dependsOn(core.jvm(scala3))
  .settings(
    scalaVersion := scala3,
    Compile / mainClass := Some("com.example.MainActivity"),
    // Add native library providers
    libraryDependencies += "com.kubuszok" % "pnm-provider-angle" % "0.1.2",
    // Override SDK defaults if needed
    androidMinSdk := 26,
    androidTargetSdk := 35,
    androidSdkCacheDir := (ThisBuild / baseDirectory).value / "android-sdk"
  )
```

## Troubleshooting

### "AndroidManifest.xml not found"

**Symptom**: `androidPackage` fails with "AndroidManifest.xml not found at ..."
**Cause**: The manifest must be at `src/main/resources/AndroidManifest.xml`
**Fix**: Create the file at the expected location. The `(Compile / resourceDirectory)`
setting controls the base path.

### "d8 not found" / "aapt2 not found"

**Symptom**: Task fails looking for build-tools binaries
**Cause**: SDK download did not install the correct build-tools version
**Fix**: Check that `build-tools/35.0.0/` exists in your SDK directory. Run
`sdkmanager "build-tools;35.0.0"` manually, or delete the SDK cache dir and
let AndroidPlugin re-download.

### "adb not found"

**Symptom**: `androidInstall` fails with "adb not found at ..."
**Cause**: `platform-tools` not installed in the SDK
**Fix**: Run `sdkmanager "platform-tools"` in your SDK directory, or install
platform-tools via Android Studio / command-line tools.

### VerifyError at runtime (wide register index out of range)

**Symptom**: App crashes on launch with `VerifyError` mentioning register index
**Cause**: D8 mishandles certain Scala 3 lambda bytecode patterns, particularly
from the Scribe logging library
**Fix**: Scribe JARs are auto-excluded from the fat JAR. If another library
triggers the same issue, add its name to the exclusion filter in `androidDex`.

### Multi-dex issues

**Symptom**: App crashes with `ClassNotFoundException` for classes that exist
**Cause**: D8 splits classes across multiple DEX files; the class may be in
`classes2.dex` but the runtime didn't load it
**Fix**: All DEX files (`classes.dex`, `classes2.dex`, etc.) are automatically
added to the APK. If you still hit issues, ensure `minSdkVersion >= 21`
(native multi-dex support). API 26 (the default) is well above this threshold.

### Native .so not found at runtime

**Symptom**: `UnsatisfiedLinkError` for a native library on Android
**Cause**: The .so file wasn't extracted from the provider JAR, or the ABI
doesn't match the device architecture
**Fix**: Verify the provider JAR contains `native/android-aarch64/<name>.so`
(or the appropriate classifier). Check that the classifier-to-ABI mapping
covers your target device. ARM64 devices use `arm64-v8a`, x86_64 emulators
use `x86_64`.

### PanamaPort runtime crash on API < 36

**Symptom**: App works on API 36+ emulator but crashes on API 26-35 device
**Cause**: D8 does not apply PanamaPort's ProGuard rules for API adaptation
**Fix**: This is a known limitation. The current build uses D8 (no ProGuard
rule support). Workaround: test on API 36+ emulator. Proper fix requires
wiring R8 with ProGuard rules extracted from PanamaPort AARs.
See https://github.com/kubuszok/sge/issues/5

### Slow first build

**Symptom**: First `androidDex` takes several minutes
**Cause**: AndroidPlugin is downloading the SDK (command-line tools + platform + build-tools)
**Fix**: This is expected on first run. Set `ANDROID_HOME` to a pre-installed
SDK to skip the download. Subsequent builds reuse the cached SDK.

### SDK download fails behind proxy/firewall

**Symptom**: SDK download times out or fails with connection error
**Cause**: `dl.google.com` is blocked or throttled
**Fix**: Install the Android SDK manually (via Android Studio or
`sdkmanager`), then set `ANDROID_HOME` to its location. The plugin will
use the existing SDK without downloading.

### gitignore for Android artifacts

Add to `.gitignore`:

```
android-sdk/
target/
*.apk
*.keystore
```
