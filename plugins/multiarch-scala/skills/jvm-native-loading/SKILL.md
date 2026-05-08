---
description: Load native shared libraries on JVM via multiarch-core NativeLibLoader — do NOT reimplement OS detection, extraction, or System.load
---

# JVM Native Library Loading with multiarch-core

## The rule

When a JVM project needs to load native shared libraries (`.so`, `.dylib`,
`.dll`) at runtime — whether via Panama FFM or JNI — **use the existing
`multiarch.core.NativeLibLoader`**. Do NOT write custom code for:

- OS/architecture detection (`System.getProperty("os.name")`, `os.arch`)
- Library file name mapping (`lib` prefix, `.so`/`.dylib`/`.dll` extension)
- Classpath resource extraction to temp directory
- `System.load()` / `System.loadLibrary()` calls
- Android-specific `findLibrary` reflection

All of this is already implemented, tested across 9 platforms, thread-safe,
and standardized in the `multiarch-core` library.

## What you need

### 1. Dependencies in build.sbt

Two things on the classpath — the loader library and a provider JAR:

```scala
libraryDependencies ++= Seq(
  // The loader (provides NativeLibLoader)
  "com.kubuszok" %% "multiarch-core" % "0.1.2",

  // A provider JAR containing the shared libraries + manifest
  "com.kubuszok" % "pnm-provider-mylib-desktop" % "1.0.0"
)
```

The provider JAR is a plain JAR with this layout:

```
pnm-provider-mylib-desktop.jar
├── pnm-provider.json                    (manifest)
└── native/
    ├── linux-x86_64/libmylib.so
    ├── linux-aarch64/libmylib.so
    ├── macos-x86_64/libmylib.dylib
    ├── macos-aarch64/libmylib.dylib
    ├── windows-x86_64/mylib.dll
    └── windows-aarch64/mylib.dll
```

### 2. Load the library in your Scala code

```scala
import java.lang.foreign.*

object MyLibPlatform {
  private val linker: Linker       = Linker.nativeLinker()
  private val libLookup: SymbolLookup = {
    val libPath = multiarch.core.NativeLibLoader.load("mylib")
    SymbolLookup.libraryLookup(libPath, Arena.global())
  }

  private def sym(name: String): MemorySegment =
    libLookup.find(name).orElseThrow(() =>
      new UnsupportedOperationException(s"Symbol not found: $name"))

  // Bind C functions as MethodHandles
  private val myFunction: java.lang.invoke.MethodHandle =
    linker.downcallHandle(
      sym("my_function"),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    )
}
```

That's it. `NativeLibLoader.load("mylib")` handles everything:

1. Detects the current platform (linux-x86_64, macos-aarch64, etc.)
2. Maps "mylib" to the platform file name (`libmylib.so`, `libmylib.dylib`, `mylib.dll`)
3. Searches `java.library.path` first (for dev overrides)
4. Falls back to classpath resource at `native/<platform>/<mapped-name>`
5. Extracts to a temp directory (thread-safe, cached, deleted on exit)
6. Returns the `Path` to the extracted library

You then pass that `Path` to `SymbolLookup.libraryLookup()` for Panama
or to `System.load(path.toString)` for JNI.

## Provider types

| Provider type | Manifest file | Use case | Loading |
|--------------|---------------|----------|---------|
| Panama | `pnm-provider.json` | Panama FFM (`java.lang.foreign`) | `NativeLibLoader` at runtime |
| JNI | `jni-provider.json` | JNI (`System.load`) | `NativeLibLoader` at runtime |
| Scala Native | `sn-provider.json` | Static linking | sbt plugin at compile time (NOT NativeLibLoader) |

Panama and JNI providers work identically — the different manifest names
only help prevent mixing up artifacts when a library has both static and
dynamic variants.

## API reference

### `NativeLibLoader.load(libName: String): Path`

Resolves and extracts a single library. Returns the filesystem path.
Use this when you need the path for `SymbolLookup.libraryLookup()`.

```scala
val path = multiarch.core.NativeLibLoader.load("mylib")
// path = /tmp/multiarch-native-xxxxx/libmylib.so (on Linux)
```

### `NativeLibLoader.loadAll(providerType: ProviderType): Unit`

Auto-discovers all provider manifests of the given type on the classpath,
extracts and loads every library declared for the current platform.
Calls `System.load()` on each one.

```scala
// Load everything from all pnm-provider.json manifests on classpath
multiarch.core.NativeLibLoader.loadAll(multiarch.core.ProviderType.Panama)
```

### `NativeLibLoader.loadConfigs(providerType: ProviderType, configNames: Set[String]): Unit`

Like `loadAll`, but only loads libraries from configs with matching names.
Use when a provider JAR bundles multiple libraries and you only need some.

```scala
multiarch.core.NativeLibLoader.loadConfigs(
  multiarch.core.ProviderType.Panama,
  Set("tree_sitter_core", "tree_sitter_java")
)
```

## Resolution order

`NativeLibLoader.load("mylib")` searches in this order:

1. **`java.library.path`** — scans each directory for the mapped file name.
   Use this for dev overrides (`-Djava.library.path=/my/local/libs`).

2. **Classpath resource** — looks for `native/<host-classifier>/<mapped-name>`
   in all JARs and resource directories. This is where provider JARs deliver
   their libraries.

3. **Android system loader** — if running on Android, uses the class loader's
   `findLibrary` method and `System.loadLibrary` as fallback.

4. **Error** — throws `UnsatisfiedLinkError` with a diagnostic message listing
   all searched locations.

### Host classifier detection

Automatic, based on `System.getProperty("os.name")` and `os.arch`:

| Host | Classifier |
|------|-----------|
| Linux x86_64 | `linux-x86_64` |
| Linux aarch64 | `linux-aarch64` |
| macOS x86_64 | `macos-x86_64` |
| macOS aarch64 | `macos-aarch64` |
| Windows x86_64 | `windows-x86_64` |
| Windows aarch64 | `windows-aarch64` |
| Android aarch64 | `android-aarch64` |
| Android armv7 | `android-armv7` |
| Android x86_64 | `android-x86_64` |

### Library name mapping

| OS | Input | Mapped file name |
|----|-------|-----------------|
| Linux | `"mylib"` | `libmylib.so` |
| macOS | `"mylib"` | `libmylib.dylib` |
| Windows | `"mylib"` | `mylib.dll` |

This is handled by `System.mapLibraryName()` (Unix) and a Windows-specific
override. You pass the **logical name** ("mylib"), not the file name.

## Real-world patterns

### Pattern 1: Panama FFM (recommended for new code)

From SGE — loading a Rust-built native ops library:

```scala
// sge/src/main/scalajvm/sge/platform/BufferOpsPanama.scala
private[platform] class BufferOpsPanama(val p: PanamaProvider) extends BufferOps {
  private val linker: p.Linker = p.Linker.nativeLinker()

  private val lib: p.SymbolLookup = {
    val found = multiarch.core.NativeLibLoader.load("sge_native_ops")
    p.SymbolLookup.libraryLookup(found, p.Arena.global())
  }

  private def lookup(name: String): p.MemorySegment =
    lib.findOrThrow(name)

  private val hAllocMemory: MethodHandle = linker.downcallHandle(
    lookup("sge_alloc_memory"),
    p.FunctionDescriptor.of(p.ADDRESS, p.JAVA_INT)
  )
}
```

### Pattern 2: Panama FFM direct (java.lang.foreign)

From SSG — loading tree-sitter via Panama:

```scala
// ssg-highlight/src/main/scalajvm/ssg/highlight/TreeSitterPlatformImpl.scala
object TreeSitterPlatformImpl extends TreeSitterPlatform {
  private val nativeLinker: Linker       = Linker.nativeLinker()
  private val libLookup:    SymbolLookup = {
    val libPath = multiarch.core.NativeLibLoader.load("tree_sitter_all")
    SymbolLookup.libraryLookup(libPath, Arena.global())
  }

  private def sym(name: String): MemorySegment =
    libLookup.find(name).orElseThrow(...)
}
```

### Pattern 3: Multiple libraries from same provider

SGE loads ANGLE (GLESv2), GLFW, and miniaudio from separate provider JARs:

```scala
// build.sbt — provider JARs as dependencies
libraryDependencies ++= Seq(
  "com.kubuszok" %% "multiarch-core" % "0.1.2",
  "com.kubuszok" % "pnm-provider-sge-desktop" % "0.1.2"
)

// Scala code — load each library individually
val glesPath  = multiarch.core.NativeLibLoader.load("GLESv2")
val glfwPath  = multiarch.core.NativeLibLoader.load("glfw")
val audioPath = multiarch.core.NativeLibLoader.load("miniaudio")
```

### Pattern 4: JNI (legacy code, use Panama for new code)

```scala
val path = multiarch.core.NativeLibLoader.load("mylib")
System.load(path.toAbsolutePath.toString)
// Now JNI native methods are available
```

## Build configuration

### JVM settings (required)

```scala
val jvmSettings = Seq(
  fork := true,  // REQUIRED for native lib loading
  javaOptions ++= Seq(
    "--enable-native-access=ALL-UNNAMED"  // Required for Panama FFM
  )
)
```

Without `fork := true`, sbt runs your code in its own JVM process and
`java.library.path` points to sbt's directories, not your project's.

### Optional: dev override via java.library.path

For local development where libraries are built outside the provider JAR:

```scala
javaOptions += s"-Djava.library.path=${baseDirectory.value}/native-libs/target/release"
```

`NativeLibLoader` checks `java.library.path` first, so locally-built
libraries take precedence over classpath resources.

## Creating a provider JAR

### Manifest (pnm-provider.json)

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
      "windows-aarch64": { "binary": "mylib.dll" }
    }
  ]
}
```

### build.sbt for the provider JAR

```scala
lazy val myProvider = project.in(file("my-provider"))
  .settings(
    name := "pnm-provider-mylib-desktop",
    autoScalaLibrary := false,
    crossPaths := false,
    Compile / resourceDirectory := baseDirectory.value / "src" / "main" / "resources",
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

## What NOT to do

### DO NOT reimplement OS detection

```scala
// WRONG — this already exists in NativeLibLoader
val os = System.getProperty("os.name").toLowerCase match {
  case n if n.contains("mac")   => "macos"
  case n if n.contains("linux") => "linux"
  case n if n.contains("win")   => "windows"
}
val arch = System.getProperty("os.arch") match {
  case "amd64" | "x86_64" => "x86_64"
  case "aarch64" | "arm64" => "aarch64"
}
val libName = os match {
  case "macos"   => s"lib$name.dylib"
  case "linux"   => s"lib$name.so"
  case "windows" => s"$name.dll"
}
```

### DO NOT reimplement extraction

```scala
// WRONG — NativeLibLoader already does this
val stream = getClass.getResourceAsStream(s"/native/$os-$arch/$libName")
val tmpFile = File.createTempFile("native-", libName)
Files.copy(stream, tmpFile.toPath, StandardCopyOption.REPLACE_EXISTING)
System.load(tmpFile.getAbsolutePath)
```

### DO NOT use System.loadLibrary for provider JARs

```scala
// WRONG — System.loadLibrary searches java.library.path only
System.loadLibrary("mylib")

// RIGHT — NativeLibLoader searches classpath resources too
val path = multiarch.core.NativeLibLoader.load("mylib")
System.load(path.toAbsolutePath.toString)
```

### DO

```scala
// RIGHT — one line, handles everything
val path = multiarch.core.NativeLibLoader.load("mylib")
val lookup = SymbolLookup.libraryLookup(path, Arena.global())
```

## Troubleshooting

### UnsatisfiedLinkError with helpful message

`NativeLibLoader` throws a diagnostic error listing what it searched:

```
UnsatisfiedLinkError: Cannot find native library 'libmylib.so' (logical name: 'mylib').
  Searched java.library.path: /usr/lib:/usr/local/lib
  Searched classpath resource: native/macos-aarch64/libmylib.dylib
  Host platform: macos-aarch64
```

This tells you exactly what's missing — usually the provider JAR isn't
on the classpath or doesn't include your platform.

### Library loads but symbols not found

The library loaded successfully but `SymbolLookup.find("my_function")`
returns empty. This means the library doesn't export that symbol.
Check with `nm -D libmylib.so | grep my_function` (Linux/macOS).

### Works locally but fails in tests

Ensure `fork := true` is set for both `Compile` and `Test`:

```scala
fork := true
Test / fork := true
Test / javaOptions += "--enable-native-access=ALL-UNNAMED"
```
