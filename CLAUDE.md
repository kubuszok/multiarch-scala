# CLAUDE.md

multiarch-scala is an sbt plugin suite + core library for multi-architecture
native library distribution and JVM application packaging in Scala.

## Project Structure

| Directory | Purpose |
|-----------|---------|
| `core/` | Shared models, JSON codec, extraction logic, `NativeLibLoader` — sbt-independent |
| `panama-api/` | Panama FFM abstraction trait + PanamaPort provider — Scala 3, JDK 17 bytecode |
| `panama-jdk/` | JDK 22+ `java.lang.foreign` implementation of PanamaProvider — Scala 3 |
| `plugin/` | sbt AutoPlugins: NativeProviderPlugin, MultiArchJvmReleasePlugin, MultiArchNativeReleasePlugin, AndroidPlugin |
| `sn-provider-curl/` | Pre-built static curl for 6 desktop platforms |
| `test-project-native/` | Integration test: Scala Native linking with curl |
| `test-project-jlink/` | Integration test: JLink packaging |

## Build Rules

- Core: cross-built for Scala **2.12.21**, **2.13.18**, **3.3.7**
- Plugin: Scala 2.12 only (sbt plugin requirement)
- sbt: **1.12.6**
- Required plugin dependencies: `sbt-scala-native` 0.5.10, `sbt-projectmatrix` 0.11.0
- Version derived from git tags via `sbt-git`
- Publishing: Maven Central via GPG-signed artifacts

## Architecture

### Provider Types

| Type | Manifest | Libraries | Loading |
|------|----------|-----------|---------|
| Scala Native | `sn-provider.json` | Static (`.a`, `.lib`) | Linked at compile time by NativeProviderPlugin |
| JNI | `jni-provider.json` | Shared (`.so`, `.dylib`, `.dll`) | Loaded at runtime by `NativeLibLoader` |
| Panama | `pnm-provider.json` | Shared (`.so`, `.dylib`, `.dll`) | Loaded at runtime by `NativeLibLoader` |

### Provider JAR Layout

```
my-provider.jar
├── sn-provider.json           (or jni-provider.json / pnm-provider.json)
└── native/
    ├── linux-x86_64/libfoo.a
    ├── linux-aarch64/libfoo.a
    ├── macos-x86_64/libfoo.a
    ├── macos-aarch64/libfoo.a
    ├── windows-x86_64/foo.lib
    └── windows-aarch64/foo.lib
```

### Supported Platforms

| Classifier | Scala Native | JNI/Panama | JVM Packaging |
|------------|-------------|------------|---------------|
| `linux-x86_64` | Yes | Yes | Yes |
| `linux-aarch64` | Yes | Yes | Yes |
| `macos-x86_64` | Yes | Yes | Yes |
| `macos-aarch64` | Yes | Yes | Yes |
| `windows-x86_64` | Yes | Yes | Yes |
| `windows-aarch64` | Yes | Yes | Yes |
| `android-aarch64` | -- | Yes | -- |
| `android-armv7` | -- | Yes | -- |
| `android-x86_64` | -- | Yes | -- |

### Plugin Chain

```
NativeProviderPlugin (requires ScalaNativePlugin)
  ├── NativeProviderSettings — manifest discovery + flag merging
  └── NativeExtractSettings — library extraction from JARs

MultiArchNativeReleasePlugin (requires NativeProviderPlugin)
  └── ZigCross — cross-compilation via zig cc/c++

MultiArchJvmReleasePlugin (requires JvmPlugin)
  └── JvmPackaging — JLink + Roast launcher packaging

AndroidPlugin (requires JvmPlugin)
  └── AndroidBuild — D8/R8 + aapt2 + apksigner pipeline
```

## Key Implementation Details

### NativeProviderPlugin Flag Ordering

The plugin appends to `nativeConfig.linkingOptions` in this exact order:

```
[existing linkingOptions]
  ++ [-L<libDir>]                    (library search path)
  ++ [/path/to/lib.a, ...]          (extracted library archives)
  ++ [deduplicated flags-groups]     (system libs, frameworks)
  ++ [rpath flags]                   (platform-specific)
```

Flag groups from multiple providers are deduplicated by exact group equality.

### rpath Strategy

- **macOS**: `-rpath <libDir> -rpath @executable_path`
- **Linux**: `-Wl,-rpath,<libDir> -Wl,-rpath,$$ORIGIN`
- **Windows**: No rpath; DLLs copied next to executable post-link

### NativeLibLoader Resolution Order (JVM Runtime)

1. `java.library.path` (system/dev override)
2. Classpath resource: `native/<host-classifier>/<library-name>`
3. Android system loader (if on ART/Dalvik)
4. `UnsatisfiedLinkError` with diagnostic

### JvmPackaging Modes

- **Simple** (`releasePackage`): launcher scripts + system JDK dependency
- **Distribution** (`releasePlatform`/`releaseAll`): self-contained per-platform archives with JLink-minimized JRE + Roast native launcher

## Testing

```bash
sbt test                              # Core unit tests
sbt test-project-native/nativeLink    # Integration: SN + curl provider
sbt test-project-jlink/releasePackage # Integration: JLink packaging
```

## Skill Dispatch

| Context | Skill |
|---------|-------|
| Setting up a new cross-platform Scala project | `/multiarch-setup` |
| Scala Native linking failures | Check flag ordering, provider discovery |
| JVM native lib loading failures | Check NativeLibLoader resolution order |
| Creating a new provider JAR | Follow README "How to create your own provider" |
| Cross-compilation with zig | Check ZigCross.isAvailable, platform targets |
