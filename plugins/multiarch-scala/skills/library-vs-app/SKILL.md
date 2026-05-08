---
description: Cross-platform library builds vs application builds — publishing, packaging, plugin enablement, and the SGE real-world pattern
---

# Library vs Application Builds

Cross-platform Scala projects fall into two categories with fundamentally
different build configurations: **libraries** (published to Maven Central,
consumed via `%%%`) and **applications** (built for end-user deployment).
Confusing the two causes either missing packaging or broken publishing.

## Libraries (published to Maven Central, consumed via `%%%`)

Libraries are consumed by other sbt projects as dependencies. They publish
platform-specific artifacts that sbt resolves automatically via `%%%`.

### Core characteristics

- Use `projectMatrix` with `.jvmPlatform()`, `.jsPlatform()`, `.nativePlatform()`
- Published with `%%%` cross-platform dependency resolution
- **NO packaging tasks** — no JLink, no APK, no browser packaging, no `nativeLink`
- **NO `.withCrossNative`** — libraries aren't executables; there's nothing to link
- Platform-specific code via conditional source directories
- May merge class files from multiple compile targets into one JAR
- May bundle native shared libraries inside the JAR for runtime extraction

### Minimal build.sbt

```scala
val scala3 = "3.8.3"

lazy val myLib = (projectMatrix in file("my-lib"))
  .settings(
    name         := "my-lib",
    organization := "com.example",
    scalaVersion := scala3,
    scalacOptions ++= Seq("-deprecation", "-feature", "-no-indent")
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))
  .nativePlatform(scalaVersions = Seq(scala3))
```

This produces three artifacts: `my-lib_3`, `my-lib_sjs1_3`, `my-lib_native0.5_3`.
Downstream projects depend on them with:

```scala
libraryDependencies += "com.example" %%% "my-lib" % "1.0.0"
```

### Platform-specific source directories

```
my-lib/
├── src/main/scala/            # Shared (all platforms)
├── src/main/scala-jvm/        # JVM-only implementations
├── src/main/scala-js/         # Scala.js-only implementations
└── src/main/scala-native/     # Scala Native-only implementations
```

Configure per-platform source roots in the matrix:

```scala
lazy val myLib = (projectMatrix in file("my-lib"))
  .settings(commonSettings)
  .jvmPlatform(scalaVersions = Seq(scala3), settings = Seq(
    Compile / unmanagedSourceDirectories +=
      baseDirectory.value / "src" / "main" / "scala-jvm"
  ))
  .jsPlatform(scalaVersions = Seq(scala3), settings = Seq(
    Compile / unmanagedSourceDirectories +=
      baseDirectory.value / "src" / "main" / "scala-js"
  ))
  .nativePlatform(scalaVersions = Seq(scala3), settings = Seq(
    Compile / unmanagedSourceDirectories +=
      baseDirectory.value / "src" / "main" / "scala-native"
  ))
```

### Merging class files from multiple modules into one JAR

A library may split its implementation across multiple internal modules
(e.g., platform-specific backends) but publish a single JAR. This is done
by merging `packageBin` mappings:

```scala
// Example: SGE merges sge-jvm-platform-api + sge-jvm-platform-jdk +
// sge-jvm-platform-android into the main sge JAR

lazy val jvmPlatformApi = (projectMatrix in file("sge-jvm-platform/api"))
  .settings(publish / skip := true)  // not published separately
  .jvmPlatform(scalaVersions = Seq(scala3))

lazy val jvmPlatformJdk = (projectMatrix in file("sge-jvm-platform/jdk"))
  .settings(publish / skip := true)
  .jvmPlatform(scalaVersions = Seq(scala3))
  .dependsOn(jvmPlatformApi)

lazy val core = (projectMatrix in file("sge"))
  .settings(
    // Merge class files from platform modules into this JAR
    Compile / packageBin / mappings ++= {
      val apiClasses = (jvmPlatformApi.jvm(scala3) / Compile / packageBin / mappings).value
      val jdkClasses = (jvmPlatformJdk.jvm(scala3) / Compile / packageBin / mappings).value
      apiClasses ++ jdkClasses
    }
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))
  .nativePlatform(scalaVersions = Seq(scala3))
```

The merged modules set `publish / skip := true` — they exist only as
compile-time subprojects, never as independent Maven artifacts.

### Bundling native shared libraries in the JAR

Libraries that wrap native code (via Panama FFM or JNI) bundle shared
libraries inside the JAR under `native/<classifier>/`:

```
my-lib.jar
├── my/lib/MyClass.class
└── native/
    ├── linux-x86_64/libmylib.so
    ├── linux-aarch64/libmylib.so
    ├── macos-x86_64/libmylib.dylib
    ├── macos-aarch64/libmylib.dylib
    ├── windows-x86_64/mylib.dll
    └── windows-aarch64/mylib.dll
```

At runtime, `NativeLibLoader.load("mylib")` extracts the correct binary
for the host platform to a temp directory and returns the path.

### Provider JARs (special case)

Provider JARs (`sn-provider-*`, `pnm-provider-*`, `jni-provider-*`) are a
special case: library-shaped artifacts with **no Scala code**, just native
binaries and a JSON manifest. They follow library publishing conventions but
disable Scala-specific settings:

```scala
lazy val myProvider = project.in(file("my-provider"))
  .settings(
    name              := "pnm-provider-mylib-desktop",
    organization      := "com.example",
    autoScalaLibrary  := false,   // no scala-library dependency
    crossPaths        := false,   // no _3 suffix in artifact name
    Compile / packageBin / mappings ++= {
      val nativesDir = baseDirectory.value / "natives"
      multiarch.core.Platform.desktop.flatMap { p =>
        val platDir = nativesDir / p.classifier
        if (platDir.exists())
          IO.listFiles(platDir).filter(_.isFile)
            .map(f => f -> s"native/${p.classifier}/${f.getName}").toSeq
        else Seq.empty
      }
    }
  )
```

These JARs are consumed as plain `%` dependencies (no `%%` or `%%%`):

```scala
libraryDependencies += "com.example" % "pnm-provider-mylib-desktop" % "1.0.0"
```

## Applications (not published, built for end-user deployment)

Applications produce runnable artifacts — JVM executables, browser bundles,
native binaries, or Android APKs. They are never published to Maven Central.

### Core characteristics

- Set `publish / skip := true`
- Depend on libraries via `.dependsOn(core)` or `libraryDependencies += "..." %%% "..." % "..."`
- Each platform needs **separate packaging** via specific plugins
- `.withCrossNative(scalaVersion)` enables local cross-compilation link-testing
- Application projects use `enablePlugins(...)` for auto-configuration

### Platform packaging breakdown

| Platform | Plugin | Key tasks | Output |
|----------|--------|-----------|--------|
| JVM (simple) | `MultiArchJvmReleasePlugin` | `releasePackage` | JAR + launch script (uses system JDK) |
| JVM (bundled) | `MultiArchJvmReleasePlugin` | `releasePlatform`, `releaseAll` | JAR + JLink JRE + Roast launcher |
| Browser | `ScalaJSPlugin` | `fullLinkJS` | JS bundle + HTML wrapper |
| Scala Native | `ScalaNativePlugin` | `nativeLink` | Platform binary |
| Android | `AndroidPlugin` | `androidDex`, `androidPackage`, `androidSign` | Signed APK |

### Minimal build.sbt (multi-platform application)

```scala
import multiarch.sbt.ProjectMatrixOps._

val scala3 = "3.8.3"

lazy val core = (projectMatrix in file("core"))
  .settings(
    name         := "my-app-core",
    scalaVersion := scala3
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))
  .nativePlatform(scalaVersions = Seq(scala3))

lazy val app = (projectMatrix in file("app"))
  .settings(
    publish / skip := true,
    Compile / mainClass := Some("myapp.Main")
  )
  .dependsOn(core)
  .jvmPlatform(scalaVersions = Seq(scala3), settings = Seq(
    fork := true,
    javaOptions += "--enable-native-access=ALL-UNNAMED"
  ))
  .jsPlatform(scalaVersions = Seq(scala3), settings = Seq(
    scalaJSUseMainModuleInitializer := true
  ))
  .nativePlatform(scalaVersions = Seq(scala3), settings =
    NativeProviderPlugin.projectSettings ++ Seq(
      libraryDependencies += "com.kubuszok" % "sn-provider-mylib" % "0.1.2"
    )
  )
  .withCrossNative(scala3)  // adds 5 cross-link targets (no-op without zig)
```

### JVM packaging

```scala
lazy val jvmApp = project.in(file("jvm-app"))
  .enablePlugins(MultiArchJvmReleasePlugin)
  .settings(
    Compile / mainClass := Some("myapp.Main"),
    releaseTargets := Map(
      Platform.LinuxX86_64  -> "https://cdn.azul.com/.../zulu25-linux_x64.tar.gz",
      Platform.LinuxAarch64 -> "https://cdn.azul.com/.../zulu25-linux_aarch64.tar.gz",
      Platform.MacosX86_64  -> "https://cdn.azul.com/.../zulu25-macosx_x64.tar.gz",
      Platform.MacosAarch64 -> "https://cdn.azul.com/.../zulu25-macosx_aarch64.tar.gz",
      Platform.WindowsX86_64  -> "https://cdn.azul.com/.../zulu25-win_x64.zip",
      Platform.WindowsAarch64 -> "https://cdn.azul.com/.../zulu25-win_aarch64.zip"
    )
  )
```

```bash
sbt --client releasePackage                 # simple (system JDK)
sbt --client 'releasePlatform linux-x86_64' # single platform with bundled JRE
sbt --client releaseAll                     # all 6 platforms
```

### Browser packaging

```scala
lazy val browserApp = project.in(file("browser"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion := scala3,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    Compile / mainClass := Some("myapp.Main")
  )
  .dependsOn(core.js(scala3))
```

```bash
sbt --client 'browser/fullLinkJS'
# Output in target/scala-3.8.3/browser-opt/ — serve with any HTTP server
```

### Scala Native packaging

```bash
sbt --client 'appNative3/nativeLink'
# Output: target/scala-3.8.3/my-app — a standalone binary
```

### Android packaging

```scala
lazy val androidApp = project.in(file("android"))
  .enablePlugins(AndroidPlugin)
  .settings(
    Compile / mainClass := Some("myapp.AndroidMain")
  )
```

```bash
sbt --client androidDex         # compile + DEX bytecode
sbt --client androidPackage     # assemble APK
sbt --client androidSign        # sign APK for distribution
sbt --client androidInstall     # install on connected device/emulator
```

### Cross-native link testing

`.withCrossNative(scalaVersion)` adds up to 5 extra sbt subprojects (all
desktop platforms except the host) that cross-compile via zig. This proves
linking works on every target **without CI**:

```bash
# Link-test a specific cross target
sbt --client 'appNativeLinuxX86_643/nativeLink'

# Link-test all cross targets
sbt --client ';appNativeLinuxX86_643/nativeLink;appNativeLinuxAarch643/nativeLink;appNativeWindowsX86_643/nativeLink;appNativeWindowsAarch643/nativeLink;appNativeMacosX86_643/nativeLink'
```

This is a **no-op** when zig is not installed — safe to leave in build.sbt
unconditionally. Cross-compiled binaries cannot be *run* on the host, but
they prove that linking succeeds, which is the #1 CI failure mode.

## Key differences table

| Concern | Library | Application |
|---------|---------|-------------|
| **Publishing** | Published to Maven Central; consumed via `%%%` | `publish / skip := true`; never published |
| **Plugin enablement** | `projectMatrix` with `.jvmPlatform()` / `.jsPlatform()` / `.nativePlatform()` | Same matrix + `enablePlugins(...)` for packaging |
| **Packaging** | None — `compile` and `publishLocal` only | Per-platform: JLink, browser bundle, native binary, APK |
| **Cross-native** | Never — nothing to link | `.withCrossNative(scalaVersion)` for local link-testing |
| **Native libs** | Bundled inside JAR (`native/<classifier>/`) for runtime extraction | Extracted at build time (Scala Native) or runtime (JVM) |
| **Platform code** | `src/main/scala-jvm/`, `src/main/scala-js/`, `src/main/scala-native/` | Same, or separate subprojects per platform |
| **Main class** | None | `Compile / mainClass := Some("myapp.Main")` |
| **Provider JARs** | `autoScalaLibrary := false`, `crossPaths := false`, no Scala code | Consumed as `libraryDependencies` |
| **Artifact naming** | `my-lib_3`, `my-lib_sjs1_3`, `my-lib_native0.5_3` | No artifact — produces executables |
| **Tests** | Run on all platforms via `%%%` test framework | Typically JVM-only; integration tests per platform |

## The SGE pattern (real-world example)

SGE demonstrates the full library + application split in production.

### Library side: `sge` core

The core library uses `projectMatrix`, publishes to Maven Central, and
merges platform-specific module class files into a single JAR per platform:

```scala
lazy val sge = (projectMatrix in file("sge"))
  .settings(
    name         := "sge",
    organization := "com.kubuszok"
    // ... publishing settings, scalacOptions, etc.
  )
  .jvmPlatform(scalaVersions = Seq(scala3), settings = Seq(
    // Merge JVM platform modules into the main sge JAR
    Compile / packageBin / mappings ++= {
      val api     = (sgeJvmPlatformApi.jvm(scala3) / Compile / packageBin / mappings).value
      val jdk     = (sgeJvmPlatformJdk.jvm(scala3) / Compile / packageBin / mappings).value
      val android = (sgeJvmPlatformAndroid.jvm(scala3) / Compile / packageBin / mappings).value
      api ++ jdk ++ android
    }
  ))
  .jsPlatform(scalaVersions = Seq(scala3))
  .nativePlatform(scalaVersions = Seq(scala3))
```

Key points:
- Three internal modules (`api`, `jdk`, `android`) are merged into the
  single `sge` JAR — consumers see one artifact, not four
- Platform-specific source directories provide JVM/JS/Native implementations
  of the same interfaces
- Published as `"com.kubuszok" %%% "sge" % "x.y.z"`

### Application side: demos

Each demo is a self-contained application that depends on `sge` and gets
packaging for all platforms automatically via the `SgePlugin`:

```scala
def demo(dir: String, ...)(matrix: ProjectMatrix): ProjectMatrix =
  matrix
    .enablePlugins(SgePlugin)  // auto-triggers platform plugins
    .settings(
      publish / skip := true,
      Compile / mainClass := Some(s"sge.demo.$dir.Main")
    )
    .dependsOn(sge)
    .jvmPlatform(scalaVersions = Seq(scala3))
    .jsPlatform(scalaVersions = Seq(scala3))
    .nativePlatform(scalaVersions = Seq(scala3))
    .withCrossNative(scala3)

lazy val pong = demo("pong", ...)(projectMatrix in file("pong"))
// Produces: pong3, pongJS3, pongNative3,
//   pongNativeLinuxX86_643, pongNativeLinuxAarch643,
//   pongNativeMacosX86_643, pongNativeWindowsX86_643,
//   pongNativeWindowsAarch643
```

`SgePlugin` is an AutoPlugin that triggers `SgeDesktopJvmPlatform`,
`SgeBrowserPlatform`, and `SgeDesktopNativePlatform` via the AutoPlugin
cascade. Each demo gets JVM packaging, browser packaging, and native
packaging "for free" without explicit plugin enablement for each platform.

### The dependency flow

```
Maven Central
  └── sge (library, published via %%%)
        ├── sge_3           (JVM)
        ├── sge_sjs1_3      (Scala.js)
        └── sge_native0.5_3 (Scala Native)

Local build (demos/)
  └── pong (application, not published)
        ├── pong3                          (JVM — JLink packaging)
        ├── pongJS3                        (browser — fullLinkJS)
        ├── pongNative3                    (host native binary)
        ├── pongNativeLinuxX86_643         (cross-link via zig)
        ├── pongNativeLinuxAarch643        (cross-link via zig)
        ├── pongNativeMacosX86_643         (cross-link via zig)
        ├── pongNativeWindowsX86_643       (cross-link via zig)
        └── pongNativeWindowsAarch643      (cross-link via zig)
```

## Common mistakes

### 1. Adding packaging to a library

```scala
// WRONG — libraries don't need packaging
lazy val myLib = (projectMatrix in file("my-lib"))
  .enablePlugins(MultiArchJvmReleasePlugin)  // wrong: this is for apps
  .jvmPlatform(...)
```

Libraries produce JARs via `publishLocal` / `publish`. Packaging plugins
(`MultiArchJvmReleasePlugin`, `ScalaJSPlugin` with `fullLinkJS`, etc.) are
for applications only.

### 2. Adding .withCrossNative to a library

```scala
// WRONG — libraries don't link, so cross-linking is meaningless
lazy val myLib = (projectMatrix in file("my-lib"))
  .nativePlatform(...)
  .withCrossNative(scala3)  // wrong: nothing to link-test
```

`.withCrossNative` creates subprojects that run `nativeLink` for each
target platform. Libraries never link — they compile to `.nir` files and
are linked only when an application depends on them.

### 3. Publishing an application

```scala
// WRONG — applications shouldn't be on Maven Central
lazy val myApp = (projectMatrix in file("my-app"))
  .settings(
    // missing: publish / skip := true
    organization := "com.example"
  )
```

Applications produce executables for end users, not library artifacts for
developers. Always set `publish / skip := true`.

### 4. Forgetting fork := true for JVM apps with native libs

```scala
// WRONG — native library loading will fail
lazy val jvmApp = project.in(file("jvm"))
  .settings(Compile / mainClass := Some("myapp.Main"))
  // missing: fork := true, --enable-native-access

// RIGHT
lazy val jvmApp = project.in(file("jvm"))
  .settings(
    Compile / mainClass := Some("myapp.Main"),
    fork := true,
    javaOptions += "--enable-native-access=ALL-UNNAMED"
  )
```

Without `fork := true`, the application runs inside sbt's JVM where
`java.library.path` and native access permissions are wrong.

### 5. Using % instead of %%% for cross-platform library dependencies

```scala
// WRONG — resolves only the JVM artifact
libraryDependencies += "com.example" %% "my-lib" % "1.0.0"

// RIGHT — resolves the correct artifact for each platform
libraryDependencies += "com.example" %%% "my-lib" % "1.0.0"
```

`%%%` resolves to `_3` on JVM, `_sjs1_3` on Scala.js, and `_native0.5_3`
on Scala Native. Using `%%` always resolves the JVM artifact, which fails
on JS and Native platforms.
