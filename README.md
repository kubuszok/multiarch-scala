# sbt-multi-arch-release

SBT plugins for multi-architecture application packaging and native library distribution.

## Problem

**Scala Native library distribution:** Scala Native (0.5.x) has no built-in mechanism for distributing platform-specific native libraries (`.a`, `.so`, `.dylib`, `.dll`) through library dependencies. Library authors must manually document linker flags, and consumers must configure `nativeConfig` by hand for each dependency. See [scala-native#4800](https://github.com/scala-native/scala-native/issues/4800).

**JVM multi-arch packaging:** Building self-contained JVM applications for multiple platforms requires downloading target JDKs, running `jlink`, bundling native launchers, creating macOS `.app` bundles, and producing platform-specific archives. Tools like [construo](https://github.com/fourlastor-alexandria/construo) solve this for Gradle, but Scala/sbt projects lack an equivalent.

## What this provides

Three SBT AutoPlugins bundled in a single artifact:

| Plugin | Purpose |
|--------|---------|
| `NativeLibBundlePlugin` | Auto-discovers `native-bundle.json` manifests on classpath, extracts native libs from classifier JARs, and configures Scala Native's `nativeConfig` with merged linker flags |
| `ZigCrossPlugin` | Generates `zig cc`/`zig c++` wrapper scripts for cross-compiling Scala Native to non-host platforms |
| `MultiArchJvmReleasePlugin` | JLink + [Roast](https://github.com/fourlastor-alexandria/roast) based self-contained JVM distribution for 6 desktop platforms |

Plus a demonstration library:

| Artifact | Purpose |
|----------|---------|
| `scala-native-curl-provider` | Pre-built static curl libraries for 6 platforms with `native-bundle.json` manifest — enables `sttp` HTTP client on Scala Native without manual linker configuration |

## Quick Start

### Plugin setup

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("com.kubuszok" % "sbt-multi-arch-release" % "<version>")
```

### NativeLibBundlePlugin

Automatically links native libraries from `native-bundle.json` manifests:

```scala
lazy val myApp = (projectMatrix in file("my-app"))
  .enablePlugins(NativeLibBundlePlugin)
  .nativePlatform(scalaVersions = Seq("3.8.2"))
  .settings(
    libraryDependencies += "com.kubuszok" % "scala-native-curl-provider" % "<version>"
  )
```

No manual `nativeConfig` wiring needed — the plugin discovers manifests and configures linking automatically.

### ZigCrossPlugin

Cross-compile Scala Native to non-host platforms using zig:

```scala
lazy val myAppLinux = (project in file("my-app-linux"))
  .enablePlugins(NativeLibBundlePlugin, ZigCrossPlugin)
  .settings(
    zigCrossTarget := Some(Platform.LinuxX86_64)
  )
```

Requires `zig` installed on PATH.

### MultiArchJvmReleasePlugin

Package JVM applications for 6 desktop platforms:

```scala
lazy val myApp = (project in file("my-app"))
  .enablePlugins(MultiArchJvmReleasePlugin)
  .settings(
    Compile / mainClass := Some("com.example.Main"),
    releaseTargets := Map(
      Platform.LinuxX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25-...-linux_x64.tar.gz",
      Platform.MacosAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25-...-macosx_aarch64.tar.gz",
      Platform.WindowsX86_64  -> "https://cdn.azul.com/zulu/bin/zulu25-...-win_x64.zip",
      // ... all 6 platforms
    )
  )
```

Then run:
```bash
sbt "releasePlatform linux-x86_64"   # single platform
sbt releaseAll                        # all configured platforms
sbt releasePackage                    # simple mode (system JDK required)
```

## native-bundle.json Format

Libraries declare their native dependencies via a `native-bundle.json` resource:

```json
{
  "name": "my-library",
  "libraries": [
    {
      "name": "mylib",
      "platforms": {
        "linux-x86_64":    { "flags": ["-lpthread", "-ldl"] },
        "linux-aarch64":   { "flags": ["-lpthread", "-ldl"] },
        "macos-x86_64":    { "flags": ["-framework", "Cocoa"] },
        "macos-aarch64":   { "flags": ["-framework", "Cocoa"] },
        "windows-x86_64":  { "flags": ["-lntdll"] },
        "windows-aarch64": { "flags": ["-lntdll"] }
      }
    }
  ],
  "globalFlags": []
}
```

Native library files (`.a`, `.so`, `.dylib`, `.dll`) are distributed in classifier JARs:
```
my-library_linux-x86_64.jar
└── native/
    └── libmylib.a
```

## Supported Platforms

| Classifier | Scala Native Target | Zig Target |
|------------|---------------------|------------|
| `linux-x86_64` | `x86_64-unknown-linux-gnu` | `x86_64-linux-gnu` |
| `linux-aarch64` | `aarch64-unknown-linux-gnu` | `aarch64-linux-gnu` |
| `macos-x86_64` | `x86_64-apple-darwin` | `x86_64-macos` |
| `macos-aarch64` | `aarch64-apple-darwin` | `aarch64-macos` |
| `windows-x86_64` | `x86_64-pc-windows-msvc` | `x86_64-windows-gnu` |
| `windows-aarch64` | `aarch64-pc-windows-msvc` | `aarch64-windows-gnu` |

## License

Apache 2.0
