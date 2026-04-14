package multiarch.core

import java.nio.file.{ Files, Path, StandardCopyOption }
import java.util.concurrent.ConcurrentHashMap

/** Loads platform-specific native shared libraries at runtime on the JVM.
  *
  * Resolution order:
  *   1. `java.library.path` (dev override / system-installed libraries)
  *   2. Classpath resource at `native/<platform>/<mapped-name>`
  *   3. Android class loader (when running on Android/ART)
  *   4. Throws `UnsatisfiedLinkError` with diagnostic message
  *
  * Extracted libraries go to a per-JVM temp directory that is deleted on exit.
  * Thread-safe: each library is extracted at most once per JVM.
  *
  * === Usage ===
  * {{{
  * // Auto-discover and load all libraries from provider manifests on classpath
  * NativeLibLoader.loadAll(ProviderType.Jni)
  *
  * // Load a specific library by name
  * val path = NativeLibLoader.load("mylib")
  * System.load(path.toAbsolutePath.toString)
  * }}}
  */
object NativeLibLoader {

  private val cache = new ConcurrentHashMap[String, Path]()

  @volatile private var tempDir: Path = null // scalastyle:ignore null

  private def ensureTempDir(): Path = {
    if (tempDir == null) {
      synchronized {
        if (tempDir == null) {
          tempDir = Files.createTempDirectory("multiarch-native-")
          tempDir.toFile.deleteOnExit()
        }
      }
    }
    tempDir
  }

  /** Whether we're running on Android (ART/Dalvik). */
  private val isAndroid: Boolean =
    try {
      Class.forName("android.app.Activity")
      true
    } catch {
      case _: ClassNotFoundException => false
    }

  /** The host platform classifier (e.g. `"macos-aarch64"`, `"linux-x86_64"`, `"android-aarch64"`). */
  private val hostClassifier: String = {
    val osName = System.getProperty("os.name", "").toLowerCase
    val os =
      if (isAndroid) "android"
      else if (osName.contains("mac")) "macos"
      else if (osName.contains("linux")) "linux"
      else if (osName.contains("win")) "windows"
      else throw new UnsatisfiedLinkError(s"Unsupported OS: $osName")
    val archProp = System.getProperty("os.arch", "")
    val arch = archProp match {
      case "amd64" | "x86_64"               => "x86_64"
      case "aarch64" | "arm64"              => "aarch64"
      case "armv7l" | "armeabi-v7a" | "arm" => "armv7"
      case other                            => throw new UnsatisfiedLinkError(s"Unsupported arch: $other")
    }
    s"$os-$arch"
  }

  /** Maps a logical library name to the platform-specific file name. */
  private def mappedFileName(libName: String): String = {
    val osName = System.getProperty("os.name", "").toLowerCase
    if (osName.contains("win")) s"$libName.dll"
    else System.mapLibraryName(libName)
  }

  /** Locate and return the filesystem path to the given native library.
    *
    * @param libName the logical library name (e.g. `"mylib"`, `"curl"`)
    * @return the absolute path to the library file
    * @throws UnsatisfiedLinkError if the library cannot be found
    */
  def load(libName: String): Path = {
    val cached = cache.get(libName)
    if (cached != null) return cached

    val mapped = mappedFileName(libName)
    val result = findOnLibraryPath(mapped)
      .orElse(extractFromClasspath(libName, mapped))
      .orElse(loadViaSystemOnAndroid(libName, mapped))
      .getOrElse {
        val libPath = System.getProperty("java.library.path", "")
        throw new UnsatisfiedLinkError(
          s"Cannot find native library '$mapped' (logical name: '$libName').\n" +
            s"  Searched java.library.path: $libPath\n" +
            s"  Searched classpath resource: native/$hostClassifier/$mapped\n" +
            s"  Host platform: $hostClassifier"
        )
      }

    cache.putIfAbsent(libName, result)
    cache.get(libName)
  }

  /** Auto-discover provider manifests on the classpath and load all binaries
    * declared for the current platform.
    *
    * Only applies to JNI and Panama provider types (dynamic libraries).
    * Scala Native providers are statically linked at build time.
    *
    * @param providerType the provider type to discover (typically `ProviderType.Jni` or `ProviderType.Panama`)
    */
  def loadAll(providerType: ProviderType): Unit = {
    val manifests = discoverClasspathManifests(providerType)
    manifests.foreach { manifest =>
      manifest.configs.foreach { config =>
        config.platforms.get(hostClassifier).foreach { platConfig =>
          platConfig.binary.foreach { binaryName =>
            if (!platConfig.stub) {
              val libName = binaryName
                .stripPrefix("lib")
                .replaceAll("\\.(so|dylib|dll)$", "")
              val path = load(libName)
              System.load(path.toAbsolutePath.toString)
            }
          }
        }
      }
    }
  }

  /** Auto-discover provider manifests on the classpath and load binaries
    * for specific config names only.
    *
    * @param providerType the provider type to discover
    * @param configNames config names to load (others are skipped)
    */
  def loadConfigs(providerType: ProviderType, configNames: Set[String]): Unit = {
    val manifests = discoverClasspathManifests(providerType)
    manifests.foreach { manifest =>
      manifest.configs.foreach { config =>
        if (configNames.contains(config.configName)) {
          config.platforms.get(hostClassifier).foreach { platConfig =>
            platConfig.binary.foreach { binaryName =>
              if (!platConfig.stub) {
                val libName = binaryName
                  .stripPrefix("lib")
                  .replaceAll("\\.(so|dylib|dll)$", "")
                val path = load(libName)
                System.load(path.toAbsolutePath.toString)
              }
            }
          }
        }
      }
    }
  }

  // ── Internal resolution methods ───────────────────────────────────

  /** Search `java.library.path` for the mapped library name. */
  private def findOnLibraryPath(mapped: String): Option[Path] = {
    val libPath = System.getProperty("java.library.path", "")
    val paths   = libPath.split(java.io.File.pathSeparator)
    paths.iterator.map(dir => Path.of(dir, mapped)).find(Files.exists(_))
  }

  /** Extract the library from a classpath resource (`native/<platform>/<name>`). */
  private def extractFromClasspath(libName: String, mapped: String): Option[Path] = {
    val resourcePath = s"native/$hostClassifier/$mapped"
    val stream       = getClass.getClassLoader.getResourceAsStream(resourcePath)
    if (stream == null) None
    else {
      try {
        val dir    = ensureTempDir()
        val target = dir.resolve(mapped)
        Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        target.toFile.deleteOnExit()
        Some(target)
      } finally stream.close()
    }
  }

  /** On Android, find native .so files via the class loader's `findLibrary` method. */
  private def loadViaSystemOnAndroid(libName: String, mapped: String): Option[Path] = {
    if (!isAndroid) return None
    try {
      val cl   = getClass.getClassLoader
      val m    = cl.getClass.getMethod("findLibrary", classOf[String])
      val path = m.invoke(cl, libName).asInstanceOf[String]
      if (path != null) return Some(Path.of(path))
    } catch {
      case _: Exception => () // fall through
    }
    try {
      System.loadLibrary(libName)
      findOnLibraryPath(mapped)
    } catch {
      case _: UnsatisfiedLinkError => None
    }
  }

  /** Discover provider manifests from the classpath. */
  private def discoverClasspathManifests(providerType: ProviderType): Seq[ProviderManifest] = {
    val classLoader = getClass.getClassLoader
    val resources   = classLoader.getResources(providerType.filename)
    val manifests   = Seq.newBuilder[ProviderManifest]
    while (resources.hasMoreElements) {
      val url    = resources.nextElement()
      val stream = url.openStream()
      try {
        val reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)
        val sb     = new StringBuilder
        val buf    = new Array[Char](4096)
        var read   = reader.read(buf)
        while (read > 0) { sb.appendAll(buf, 0, read); read = reader.read(buf) }
        manifests += ProviderManifestCodec.parse(sb.toString)
      } finally stream.close()
    }
    manifests.result()
  }
}
