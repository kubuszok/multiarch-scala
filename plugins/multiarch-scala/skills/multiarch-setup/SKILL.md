---
description: Guide for setting up cross-platform Scala projects (JVM + Scala.js + Scala Native) using sbt-multiarch-scala
---

# Cross-Platform Scala Project Setup Guide

This skill provides the complete knowledge base for setting up a multi-architecture
Scala project targeting JVM, Scala.js, and/or Scala Native using `sbt-multiarch-scala`.

## Validated Toolchain Versions (as of 2026-05)

| Tool | Version | Notes |
|------|---------|-------|
| Scala | 3.8.3 | Requires sbt-scalajs 1.20.0+ |
| sbt | 1.12.6+ | Thin client mode via `sbt --client` |
| sbt-projectmatrix | 0.11.0 | Cross-platform project definition |
| sbt-scalajs | 1.21.0 | Must match Scala compiler expectations |
| sbt-scala-native | 0.5.10 | Zone API uses context functions |
| sbt-multiarch-scala | 0.1.2 | Native providers, JVM packaging, Android |
| JDK | 22+ | For Panama FFM; Zulu 25 recommended for CI |
| Node.js | 22+ | For Scala.js execution; 23+ for WASM |

## Version Compatibility Rules

### Scala.js version must match Scala compiler

Scala 3.8.x ships `scala3-library_sjs1_3-3.8.x.jar` depending on Scala.js 1.20.x
internals. Using sbt-scalajs < 1.20.0 causes:

```
dotty.tools.dotc.core.TypeError: package scala.scalajs.js does not have a member method async
```

**Rule**: Always use sbt-scalajs >= 1.20.0 with Scala 3.8.x.

### Scala Native 0.5.x Zone API

```scala
// Correct (0.5.x) — Zone is a given via context function
Zone {
  val ptr = alloc[Float](len)
}

// Wrong (0.4.x pattern) — fails with "Missing parameter type"
Zone { implicit z =>
  val ptr = alloc[Float](len)
}
```

## Minimal project/plugins.sbt

```scala
addSbtPlugin("com.eed3si9n"     % "sbt-projectmatrix"   % "0.11.0")
addSbtPlugin("org.scala-js"     % "sbt-scalajs"         % "1.21.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native"    % "0.5.10")
addSbtPlugin("com.kubuszok"     % "sbt-multiarch-scala" % "0.1.2")
```

## Minimal build.sbt (3-platform library)

```scala
val scala3 = "3.8.3"

val commonSettings = Seq(
  scalaVersion := scala3,
  scalacOptions ++= Seq("-deprecation", "-feature", "-no-indent")
)

lazy val core = (projectMatrix in file("core"))
  .settings(commonSettings)
  .settings(name := "my-lib")
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))
  .nativePlatform(scalaVersions = Seq(scala3))
```

## Platform-Specific Source Directories

```
core/
├── src/main/scala/          # Shared (all platforms)
├── src/main/scala-jvm/      # JVM-only
├── src/main/scala-js/       # JS-only
└── src/main/scala-native/   # Native-only
```

Configure in build.sbt:

```scala
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

## sbt-projectmatrix Project ID Naming

| Platform | Suffix | Example (matrix = "core") |
|----------|--------|---------------------------|
| JVM | *(empty)* | `core3` |
| Scala.js | `JS` | `coreJS3` |
| Scala Native | `Native` | `coreNative3` |

JVM has no "JVM" suffix — this is intentional. The `3` is the Scala binary version.

## Scala.js Configuration

### Basic app setup

```scala
lazy val browser = project.in(file("browser"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion := scala3,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("myapp.Main")
  )
  .dependsOn(core.js(scala3))
```

### ModuleKind selection

| ModuleKind | Use case |
|------------|----------|
| `NoModule` (default) | Simple apps, no imports/exports |
| `CommonJSModule` | Node.js targets, `require()` compatibility |
| `ESModule` | Browser ESM, WASM backend (mandatory for WASM) |

```scala
scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
```

### WASM backend (experimental, optional)

Enables the Scala.js compiler to emit WebAssembly instead of JavaScript.
Zero code changes required — purely a linker configuration.

```scala
scalaJSLinkerConfig := {
  scalaJSLinkerConfig.value
    .withExperimentalUseWebAssembly(true)
    .withModuleKind(ModuleKind.ESModule)  // mandatory for WASM
},
jsEnv := {
  val config = NodeJSEnv.Config()
    .withArgs(List(
      "--experimental-wasm-exnref",
      "--experimental-wasm-jspi",
      "--experimental-wasm-imported-strings",
      "--turboshaft-wasm"
    ))
  new NodeJSEnv(config)
}
```

**Constraints:**
- `ModuleKind.ESModule` mandatory (no CommonJS)
- `ModuleSplitStyle.FewestModules` only
- Not incremental (slower dev cycle)
- Node.js 23+ required
- `@JSExport` silently ignored (only `@JSExportTopLevel` works)

### Loading pre-built WASM binaries at runtime (NOT the WASM backend)

This is different from the WASM backend above. If your Scala.js code needs to
load external `.wasm` files (e.g., tree-sitter, rapier2d), you need to:

1. Package WASM files in a provider JAR (no standard `wasm-provider` type exists yet)
2. Extract WASM files to a filesystem directory before running
3. Pass the extraction directory via environment variable to the jsEnv

```scala
// In build.sbt — pass env var to Node.js test environment
Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(
  org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
    .withEnv(Map("MY_WASM_DIR" -> sys.env.getOrElse("MY_WASM_DIR", "/tmp/my-wasm")))
)
```

In your Scala.js code, read `MY_WASM_DIR` from `js.Dynamic.global.process.env`
and use it to construct the path to `.wasm` files for loading.

**Important**: There is no automatic extraction mechanism for WASM providers.
You must handle JAR-to-filesystem extraction yourself (e.g., a custom sbt task
or a build step that unpacks the JAR to the target directory).

## Scala Native Configuration

### Basic app setup

```scala
lazy val native = project.in(file("native"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    scalaVersion := scala3,
    Compile / mainClass := Some("myapp.Main")
  )
  .dependsOn(core.native(scala3))
```

### Using NativeProviderPlugin (automatic native library management)

```scala
lazy val native = project.in(file("native"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    scalaVersion := scala3,
    libraryDependencies += "com.kubuszok" % "sn-provider-curl" % "0.1.2"
  )
  .settings(NativeProviderPlugin.projectSettings)
```

The plugin auto-discovers `sn-provider.json` manifests, extracts `.a`/`.lib` files,
and configures `nativeConfig` with the correct linker flags.

### CRITICAL: Settings evaluation order with NativeProviderPlugin

When you need both `NativeProviderPlugin` AND custom `nativeConfig` settings,
**order in the settings sequence determines evaluation**:

```scala
// CORRECT: Plugin first, then your customization reads plugin's output
.nativePlatform(settings =
  NativeProviderPlugin.projectSettings ++ Seq(
    nativeConfig := {
      val c = nativeConfig.value  // reads plugin's computed value
      c.withEmbedResources(true)
       .withLinkingOptions(c.linkingOptions ++ Seq("-lstdc++"))
    }
  )
)

// ALSO CORRECT: Using ~= which always transforms the current value
.nativePlatform(settings =
  NativeProviderPlugin.projectSettings ++ Seq(
    nativeConfig ~= { _.withEmbedResources(true) }
  )
)

// WRONG: Your := comes before the plugin's :=, so plugin OVERWRITES yours
.nativePlatform(settings = Seq(
    nativeConfig := {
      nativeConfig.value.withEmbedResources(true)
    }
  ) ++ NativeProviderPlugin.projectSettings
)
```

**Why this happens**: sbt settings in a `Seq` are evaluated in order. When
multiple `:=` definitions exist for the same key, the **last one wins**. Each
`:=` that reads `.value` gets the value from the previous definition in the chain.

### Combining base settings with NativeProviderPlugin

If you have shared nativeSettings (e.g., embed resources, disable multithreading)
that apply to all native modules, combine them correctly:

```scala
val nativeSettings: Seq[Setting[?]] = Seq(
  nativeConfig ~= {
    _.withEmbedResources(true).withMultithreading(false)
  }
)

// In your module — NativeProviderPlugin goes between base and custom
.nativePlatform(settings =
  nativeSettings ++                        // base settings (uses ~=, safe)
  NativeProviderPlugin.projectSettings ++  // plugin adds provider flags
  Seq(                                     // your additions on top
    nativeConfig := {
      val c = nativeConfig.value
      val cppLib = if (System.getProperty("os.name", "").toLowerCase.contains("mac"))
        "-lc++" else "-lstdc++"
      c.withLinkingOptions(c.linkingOptions ++ Seq(cppLib))
    }
  )
)
```

### C++ standard library linking

macOS uses `libc++`, Linux/Windows use `libstdc++`:

```scala
val cppLib = if (System.getProperty("os.name", "").toLowerCase.contains("mac"))
  "-lc++" else "-lstdc++"
```

### Resource embedding

```scala
nativeConfig ~= {
  _.withEmbedResources(true)
   .withResourceIncludePatterns(Seq("**.txt", "**.json", "**.scm"))
}
```

### Linking to system libraries

```scala
// Via @link annotation in Scala code
@link("pthread")
@extern object PthreadC { ... }

// Via build.sbt (additional search paths)
nativeLinkingOptions ++= Seq("-L/usr/local/lib")
```

## JVM Configuration

### fork := true (always for native library users)

```scala
val jvmSettings = Seq(
  fork := true,
  javaOptions ++= Seq(
    "--enable-native-access=ALL-UNNAMED",  // Panama FFM
    s"-Djava.library.path=${baseDirectory.value}/native-libs"
  )
)
```

Without `fork := true`, native library loading uses the sbt process's library path.

### Runtime native lib loading via NativeLibLoader

```scala
import multiarch.core.{ NativeLibLoader, ProviderType }

// Load all libraries from jni-provider.json / pnm-provider.json on classpath
NativeLibLoader.loadAll(ProviderType.Panama)

// Load specific configs only
NativeLibLoader.loadConfigs(ProviderType.Jni, Set("mylib"))
```

Resolution order: java.library.path -> classpath resource -> Android system loader.

## JVM Multi-Architecture Packaging

```scala
lazy val myApp = project.in(file("app"))
  .enablePlugins(MultiArchJvmReleasePlugin)
  .settings(
    Compile / mainClass := Some("myapp.Main"),
    releaseTargets := Map(
      Platform.LinuxX86_64  -> "https://cdn.azul.com/.../zulu25-linux_x64.tar.gz",
      Platform.MacosAarch64 -> "https://cdn.azul.com/.../zulu25-macosx_aarch64.tar.gz",
      // ... all 6 desktop platforms
    )
  )
```

```bash
sbt releasePackage                 # simple mode (system JDK)
sbt "releasePlatform linux-x86_64" # single platform with bundled JRE
sbt releaseAll                     # all configured platforms
```

## Android Build

```scala
lazy val myApp = project.in(file("android"))
  .enablePlugins(AndroidPlugin)
  .settings(
    Compile / mainClass := Some("myapp.AndroidMain"),
    libraryDependencies += "com.kubuszok" % "pnm-provider-mylib" % "1.0.0"
  )
```

```bash
sbt androidDex       # Compile + DEX
sbt androidPackage   # APK assembly
sbt androidSign      # Sign APK
sbt androidInstall   # Install to connected device
```

## Common Pitfalls & Solutions

### 1. Scala.js compiler crash with old sbt-scalajs

**Symptom**: `TypeError: package scala.scalajs.js does not have a member method async`
**Fix**: Use sbt-scalajs >= 1.20.0 with Scala 3.8.x

### 2. Scala Native linker can't find provider libraries

**Symptom**: `undefined reference to ...` during nativeLink
**Fix**: Ensure `NativeProviderPlugin.projectSettings` is in your module's settings.
Check that the provider JAR is on classpath (`libraryDependencies`).

### 3. Scala Native flag ordering causes link failures

**Symptom**: Linker errors about missing symbols despite provider being present
**Fix**: Order matters — `-l` flags must come AFTER the libraries that reference them.
`NativeProviderPlugin` handles this correctly, but custom flags added before the
plugin may break ordering. Always add custom flags AFTER the plugin settings.

### 4. NativeProviderPlugin overwrites custom nativeConfig

**Symptom**: Your `nativeConfig` settings (embed resources, custom flags) disappear
**Fix**: Place `NativeProviderPlugin.projectSettings` BEFORE your custom `nativeConfig :=`
in the settings sequence. Or use `~=` instead of `:=` for your customizations.

### 5. JVM can't load native libraries

**Symptom**: `UnsatisfiedLinkError` at runtime
**Fix**: Set `fork := true` and `--enable-native-access=ALL-UNNAMED`. Ensure
`java.library.path` points to the correct directory, or use `NativeLibLoader`
for classpath-based loading.

### 6. Scala Native stdout not visible with sbt --client

**Symptom**: `sbt --client 'myNativeApp/run'` produces no output
**Fix**: stdout goes to the sbt server process, not the thin client. Run the
binary directly after linking: `./target/scala-3.8.3/my-native-app`

### 7. sbt-projectmatrix: JVM project ID has no "JVM" suffix

**Symptom**: Can't find project `coreJVM3` — it's just `core3`
**Fix**: JVM has empty suffix by design. Use `core3` for JVM, `coreJS3` for JS,
`coreNative3` for Native.

### 8. Multiple sbt commands via thin client

```bash
# Correct — semicolons inside single argument
sbt --client ';compile;test'

# Wrong — second command ignored
sbt --client 'compile' 'test'
```

### 9. WASM files not found at Scala.js runtime

**Symptom**: Scala.js code can't `fetch()` or `fs.readFile()` WASM binaries
**Fix**: WASM files from provider JARs must be extracted to filesystem first.
Set up an extraction task or script, then pass the path via jsEnv environment variable.

### 10. Windows: MSVC linker expects .lib not .a

**Symptom**: Link failure on Windows with `.a` archives
**Fix**: `NativeProviderPlugin` auto-creates `.lib` aliases for `.a` files on Windows.
If using manual linking, ensure your Windows entries use `.lib` extension in the manifest.

## CI Configuration Tips

- Use Zulu JDK 25 for consistent Panama FFM support across all 6 desktop platforms
- Node.js 22 for standard Scala.js; Node.js 23+ only if using WASM backend
- Scala Native tests may need platform-specific CI runners (no Windows aarch64 support)
- macOS x86_64 via Rosetta: use `actions/setup-java` with `architecture: x64` on ARM runners
