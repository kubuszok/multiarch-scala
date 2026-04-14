# multiarch-scala

Multi-architecture native library distribution and JVM application packaging for Scala.

## What this is

Three SBT AutoPlugins and a core library:

| Artifact               | Purpose                                                                                                    |
|------------------------|------------------------------------------------------------------------------------------------------------|
| `sbt-multiarch-scala`  | SBT plugin bundle: `NativeProviderPlugin`, `MultiArchNativeReleasePlugin`, `MultiArchJvmReleasePlugin`     |
| `multiarch-core`       | Shared models, JSON codec, extraction logic, and runtime `NativeLibLoader` — sbt-independent               |
| `sn-provider-curl`     | Pre-built static curl libraries for 6 desktop platforms with `sn-provider.json` manifest                   |

What do they do? Let's take a look at some examples.

## Quick Start: Using an existing provider

### 1. Add the plugin

In `project/plugins.sbt`:

```scala
addSbtPlugin("com.kubuszok" % "sbt-multiarch-scala" % "<version>")

// Required dependencies
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.10")
```

### 2. Add a provider dependency and enable the plugin

In `build.sbt`:

```scala
lazy val myApp = (project in file("my-app"))
  .enablePlugins(NativeProviderPlugin)
  .settings(
    scalaVersion := "3.8.3",
    libraryDependencies ++= Seq(
      // STTP on Scala Native requires libcurl, normally you would
      // have to have it installed on your system, or SN would not
      // be able to link the complete binary...
      "com.softwaremill.sttp.client4" %%% "core" % "4.0.19",
      // ...but here we just need to add a dependency and NativeProviderPlugin
      // will extract the necessary native artifact and set up SN flags!
      "com.kubuszok" % "sn-provider-curl" % "<version>"
    )
  )
```

### 3. Build

```bash
sbt nativeLink
```

That's it. The plugin automatically discovers `sn-provider.json` manifests on the classpath, extracts native libraries for your platform, and configures Scala Native's `nativeConfig` with the correct linker flags.

## Quick Start: JVM multi-architecture packaging

```scala
lazy val myApp = (project in file("my-app"))
  .enablePlugins(MultiArchJvmReleasePlugin)
  .settings(
    Compile / mainClass := Some("com.example.Main"),
    releaseTargets := Map(
      Platform.LinuxX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25-...-linux_x64.tar.gz",
      Platform.MacosAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25-...-macosx_aarch64.tar.gz",
      Platform.WindowsX86_64  -> "https://cdn.azul.com/zulu/bin/zulu25-...-win_x64.zip",
      // ... all 6 desktop platforms
    )
  )
```

```bash
sbt "releasePlatform linux-x86_64"   # single platform
sbt releaseAll                       # all configured platforms
sbt releasePackage                   # simple mode (system JDK required)
```

If you used [JDKPackager](https://sbt-native-packager.readthedocs.io/en/stable/formats/jdkpackager.html) you might wonder what's the fuss?

Well, `JDKPackagerPlugin` can only build package for the currently used JDK on the current platform. If you need to package for several architectures,
you'd have to e.g. use GitHub actions with multiple different runners (one for each platform) to do it for you.

`MultiArchJvmReleasePlugin` lets you build on your own computer packages for multiple different architectures, and let you select the JDK to use for each of them.
That means that you can build all the release artifacts completely offline, on your own computer!

## Quick Start: NativeLibLoader (JNI/Panama runtime)

For JVM projects that need to load native shared libraries at runtime:

```scala
// Add dependency
libraryDependencies += "com.kubuszok" %% "multiarch-core" % "<version>"
```

```scala
import multiarch.core.{ NativeLibLoader, ProviderType }

// Auto-discover and load all libraries from jni-provider.json on classpath
NativeLibLoader.loadAll(ProviderType.Jni)

// Or load specific configs
NativeLibLoader.loadConfigs(ProviderType.Panama, Set("mylib"))

// Or load a single library by name
val path = NativeLibLoader.load("mylib")
```

## How to create your own provider

### Step 1: Cross-compile your native code

Build your native library for each target platform. For Scala Native, you need static archives (`.a` / `.lib`). For JNI/Panama, you need shared libraries (`.so` / `.dylib` / `.dll`).

### Step 2: Create a manifest file

Choose the right manifest filename for your provider type:

| Filename              | Provider Type | Use Case                                          |
|-----------------------|---------------|---------------------------------------------------|
| `sn-provider.json`    | Scala Native  | Static libraries linked at compile time           |
| `jni-provider.json`   | JNI           | Shared libraries loaded at runtime via JNI        |
| `pnm-provider.json`   | Panama        | Shared libraries loaded at runtime via Panama FFI |

(There is no difference between `jni-provider.json` and `pnm-provider.json`, but different naming helps prevent accidentally using a wrong artifact if there are multiple versions).

Create the manifest in `src/main/resources/`.

#### Scala Native provider example (`sn-provider.json`)

For static libraries linked at compile time. The `flags-groups` field is **required** — it tells the Scala Native linker which system libraries and frameworks to link:

```json
{
  "provider-schema-version": "0.1.0",
  "provider-name": "mylib",
  "configs": [
    {
      "config-name": "mylib",
      "linux-x86_64": {
        "binary": "libmylib.a",
        "flags-groups": [["-lpthread"], ["-ldl"]]
      },
      "linux-aarch64": {
        "binary": "libmylib.a",
        "flags-groups": [["-lpthread"], ["-ldl"]]
      },
      "macos-x86_64": {
        "binary": "libmylib.a",
        "flags-groups": [["-framework", "Security"]]
      },
      "macos-aarch64": {
        "binary": "libmylib.a",
        "flags-groups": [["-framework", "Security"]]
      },
      "windows-x86_64": {
        "binary": "mylib.lib",
        "flags-groups": [["-lws2_32"], ["-ladvapi32"]]
      },
      "windows-aarch64": {
        "binary": "mylib.lib",
        "flags-groups": [["-lws2_32"], ["-ladvapi32"]]
      }
    }
  ]
}
```

#### JNI / Panama provider example (`jni-provider.json` / `pnm-provider.json`)

For shared libraries loaded at runtime. No `flags-groups` — dynamic loading has no linker flags:

```json
{
  "provider-schema-version": "0.1.0",
  "provider-name": "mylib",
  "configs": [
    {
      "config-name": "mylib",
      "linux-x86_64":    { "binary": "libmylib.so" },
      "linux-aarch64":   { "binary": "libmylib.so" },
      "macos-x86_64":    { "binary": "libmylib.dylib" },
      "macos-aarch64":   { "binary": "libmylib.dylib" },
      "windows-x86_64":  { "binary": "mylib.dll" },
      "windows-aarch64": { "binary": "mylib.dll" },
      "android-aarch64": { "binary": "libmylib.so" },
      "android-armv7":   { "binary": "libmylib.so" },
      "android-x86_64":  { "binary": "libmylib.so" }
    }
  ]
}
```

### Step 3: Package native files into a JAR

Bundle your native files following the platform-classifier directory convention:

```
my-provider.jar
├── sn-provider.json          (or jni-provider.json / pnm-provider.json)
└── native/
    ├── linux-x86_64/
    │   └── libmylib.a        (or .so / .dylib / .dll)
    ├── linux-aarch64/
    │   └── libmylib.a
    ├── macos-x86_64/
    │   └── libmylib.a
    ├── macos-aarch64/
    │   └── libmylib.a
    ├── windows-x86_64/
    │   └── mylib.lib
    └── windows-aarch64/
        └── mylib.lib
```

In your `build.sbt`:

```scala
lazy val myProvider = project
  .settings(
    name := "my-provider",
    autoScalaLibrary := false,
    crossPaths := false,
    Compile / packageBin / mappings ++= {
      val nativesDir = baseDirectory.value / "natives"
      Platform.desktop.flatMap { p =>
        val platDir = nativesDir / p.classifier
        if (platDir.exists())
          IO.listFiles(platDir).filter(_.isFile).map(f => f -> s"native/${p.classifier}/${f.getName}").toSeq
        else Seq.empty
      }
    }
  )
```

### Step 4: Publish

Publish your provider JAR. Consumers simply add it as a dependency — the plugin handles discovery, extraction, and linker configuration automatically.

I suggest the following naming convention:

- `sn-provider-library-name` — artifacts providing native libraries statically linked with Scala Native
- `jni-provider-library-name` — artifacts providing native libraries dynamically loaded via JNI
- `pnm-provider-library-name` — artifacts providing native libraries dynamically loaded via Panama API

It isn't required by the infrastructure to work, but when sorting dependencies by the name,
all the native libraries will be grouped together naturally.

## Provider JSON format reference

### Provider types

| Type         | Filename              | Libraries                        | Loading                                 |
|--------------|-----------------------|----------------------------------|-----------------------------------------|
| Scala Native | `sn-provider.json`    | Static (`.a`, `.lib`)            | Linked at compile time by sbt plugin    |
| JNI          | `jni-provider.json`   | Shared (`.so`, `.dylib`, `.dll`) | Loaded at runtime by `NativeLibLoader`  |
| Panama       | `pnm-provider.json`   | Shared (`.so`, `.dylib`, `.dll`) | Loaded at runtime by `NativeLibLoader`  |

A JAR should contain at most one of these files.

### Fields

#### Scala Native providers (`sn-provider.json`)

| Field                      | Required | Description                                                                                  |
|----------------------------|----------|----------------------------------------------------------------------------------------------|
| `provider-schema-version`  | Yes      | Schema version string (currently `"0.1.0"`)                                                  |
| `provider-name`            | Yes      | Human-readable name for logging and diagnostics                                              |
| `configs`                  | Yes      | Array of configuration objects                                                               |
| `config-name`              | Yes      | Name of this configuration (for filtering and logging)                                       |
| `<platform-classifier>`    | --       | Platform-specific settings (key is the classifier, e.g. `"linux-x86_64"`)                    |
| `binary`                   | No       | Filename of the static library to extract and link (e.g. `"libcurl.a"`)                      |
| `stub`                     | No       | When `true`, marks the archive as a stub that only satisfies the linker (default: `false`)   |
| `flags-groups`             | Yes      | Array of flag groups for the linker (e.g. `[["-framework", "Security"], ["-lpthread"]]`)     |

#### JNI / Panama providers (`jni-provider.json` / `pnm-provider.json`)

| Field                      | Required | Description                                                                                  |
|----------------------------|----------|----------------------------------------------------------------------------------------------|
| `provider-schema-version`  | Yes      | Schema version string (currently `"0.1.0"`)                                                  |
| `provider-name`            | Yes      | Human-readable name for logging and diagnostics                                              |
| `configs`                  | Yes      | Array of configuration objects                                                               |
| `config-name`              | Yes      | Name of this configuration (for filtering and logging)                                       |
| `<platform-classifier>`    | --       | Platform-specific settings (key is the classifier, e.g. `"linux-x86_64"`)                    |
| `binary`                   | Yes      | Filename of the shared library to extract and load (e.g. `"libmylib.so"`)                    |

No `flags-groups` or `stub` — dynamically loaded libraries have no linker flags.

### `binary` field semantics

- **Present** (e.g. `"binary": "libcurl.a"`): The named file is extracted from the JAR and its full path is passed to the linker (SN) or loaded at runtime (JNI/Panama), along with `flags-groups` if applicable.
- **Absent** (SN only): No library is extracted or linked. Only `flags-groups` from this config contribute to the linker command. Use this for configs that only provide system library flags.

### `flags-groups` deduplication (SN only)

Flag groups from all providers are collected and deduplicated by exact group equality. This means `["-framework", "Security"]` from two different providers appears only once in the final linker command. Individual flags within a group are kept together.

## Supported platforms

| Classifier         | Scala Native Target              | Zig Target            | SN  | JNI/Panama | JVM Packaging |
|--------------------|----------------------------------|-----------------------|-----|------------|---------------|
| `linux-x86_64`     | `x86_64-unknown-linux-gnu`       | `x86_64-linux-gnu`    | Yes | Yes        | Yes           |
| `linux-aarch64`    | `aarch64-unknown-linux-gnu`      | `aarch64-linux-gnu`   | Yes | Yes        | Yes           |
| `macos-x86_64`     | `x86_64-apple-darwin`            | `x86_64-macos`        | Yes | Yes        | Yes           |
| `macos-aarch64`    | `aarch64-apple-darwin`           | `aarch64-macos`       | Yes | Yes        | Yes           |
| `windows-x86_64`   | `x86_64-pc-windows-msvc`         | `x86_64-windows-gnu`  | Yes | Yes        | Yes           |
| `windows-aarch64`  | `aarch64-pc-windows-msvc`        | `aarch64-windows-gnu` | Yes | Yes        | Yes           |
| `android-aarch64`  | `aarch64-linux-android`          | --                    | --  | Yes        | --            |
| `android-armv7`    | `armv7-linux-androideabi`        | --                    | --  | Yes        | --            |
| `android-x86_64`   | `x86_64-linux-android`           | --                    | --  | Yes        | --            |

## Cross-compilation with Zig

Cross-compile Scala Native to non-host platforms using zig:

```scala
lazy val myAppLinux = (project in file("my-app-linux"))
  .enablePlugins(NativeProviderPlugin, MultiArchNativeReleasePlugin)
  .settings(
    zigCrossTarget := Some(Platform.LinuxX86_64)
  )
```

Requires `zig` installed on PATH.

## License

Apache 2.0
