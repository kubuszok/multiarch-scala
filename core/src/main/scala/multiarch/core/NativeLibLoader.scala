package multiarch.core

import java.nio.file.{ Files, Path, StandardCopyOption }
import java.util.concurrent.ConcurrentHashMap

/** Loads platform-specific native shared libraries at runtime on the JVM.
  *
  * Resolution order:
  *   1. `java.library.path` (dev override / system-installed libraries — always FIRST; supports multiple directories separated by `java.io.File.pathSeparator`)
  *   1. Manifest-v2 classpath index: `jni-provider.json` / `pnm-provider.json` manifests declaring artifact+version resolve `native/<artifact>/<version>/<platform>/<mapped-name>`; more than one
  *      distinct provider bundling the same file throws `UnsatisfiedLinkError` with a collision report
  *   1. Legacy classpath resource at `native/<platform>/<mapped-name>` (providers predating manifest v2; logs a deprecation line once per library). The legacy read path will not be removed before
  *      multiarch 1.0.0 and not before 2027-01 (see `docs/plans/2026-07-native-flags-and-collisions.md` §3.5).
  *   1. Android class loader (when running on Android/ART)
  *   1. Throws `UnsatisfiedLinkError` with a diagnostic listing every searched location
  *
  * Extracted libraries go to a per-JVM temp directory that is deleted on exit. Thread-safe: each library is extracted at most once per JVM.
  *
  * ===Usage===
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

  /** Libraries already reported as resolved via the legacy layout (log once per lib). */
  private val legacyWarned = ConcurrentHashMap.newKeySet[String]()

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
    val os     =
      if (isAndroid) "android"
      else if (osName.contains("mac")) "macos"
      else if (osName.contains("linux")) "linux"
      else if (osName.contains("win")) "windows"
      else throw new UnsatisfiedLinkError(s"Unsupported OS: $osName")
    val archProp = System.getProperty("os.arch", "")
    val arch     = archProp match {
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
    * @param libName
    *   the logical library name (e.g. `"mylib"`, `"curl"`)
    * @return
    *   the absolute path to the library file
    * @throws UnsatisfiedLinkError
    *   if the library cannot be found, or if more than one distinct v2 provider bundles it for this platform
    */
  def load(libName: String): Path = {
    val cached = cache.get(libName)
    if (cached != null) return cached

    val result = resolve(libName, hostClassifier, getClass.getClassLoader, defaultV2Index())

    cache.putIfAbsent(libName, result)
    cache.get(libName)
  }

  /** Auto-discover provider manifests on the classpath and load all binaries declared for the current platform.
    *
    * Only applies to JNI and Panama provider types (dynamic libraries). Scala Native providers are statically linked at build time.
    *
    * @param providerType
    *   the provider type to discover (typically `ProviderType.Jni` or `ProviderType.Panama`)
    */
  def loadAll(providerType: ProviderType): Unit = {
    val manifests = discoverClasspathManifests(providerType)
    manifests.foreach { manifest =>
      manifest.configs.foreach { config =>
        config.platforms.get(hostClassifier).foreach { platConfig =>
          platConfig.binary.foreach { binaryName =>
            if (!platConfig.stub) {
              val libName = binaryName.stripPrefix("lib").replaceAll("\\.(so|dylib|dll)$", "")
              val path    = load(libName)
              System.load(path.toAbsolutePath.toString)
            }
          }
        }
      }
    }
  }

  /** Auto-discover provider manifests on the classpath and load binaries for specific config names only.
    *
    * @param providerType
    *   the provider type to discover
    * @param configNames
    *   config names to load (others are skipped)
    */
  def loadConfigs(providerType: ProviderType, configNames: Set[String]): Unit = {
    val manifests = discoverClasspathManifests(providerType)
    manifests.foreach { manifest =>
      manifest.configs.foreach { config =>
        if (configNames.contains(config.configName)) {
          config.platforms.get(hostClassifier).foreach { platConfig =>
            platConfig.binary.foreach { binaryName =>
              if (!platConfig.stub) {
                val libName = binaryName.stripPrefix("lib").replaceAll("\\.(so|dylib|dll)$", "")
                val path    = load(libName)
                System.load(path.toAbsolutePath.toString)
              }
            }
          }
        }
      }
    }
  }

  // ── Manifest-v2 classpath index ───────────────────────────────────

  /** A v2 provider owning a `(classifier, fileName)` bundle entry. */
  final private[core] case class V2Owner(providerName: String, source: String, artifact: String, version: String)

  @volatile private var v2IndexCache: Map[(String, String), Seq[V2Owner]] = null // scalastyle:ignore null

  /** The v2 index for the loader's own class loader, built lazily once per JVM. */
  private def defaultV2Index(): Map[(String, String), Seq[V2Owner]] = {
    if (v2IndexCache == null) {
      synchronized {
        if (v2IndexCache == null) v2IndexCache = buildV2Index(getClass.getClassLoader)
      }
    }
    v2IndexCache
  }

  /** Build `(classifier, fileName) -> owners` from every runtime provider manifest (`jni-provider.json`, `pnm-provider.json`) on the class loader that declares artifact + version. */
  private[core] def buildV2Index(classLoader: ClassLoader): Map[(String, String), Seq[V2Owner]] = {
    val runtimeTypes = Seq(ProviderType.Jni, ProviderType.Panama)
    val entries      = Seq.newBuilder[((String, String), V2Owner)]
    runtimeTypes.foreach { providerType =>
      val resources = classLoader.getResources(providerType.filename)
      while (resources.hasMoreElements) {
        val url = resources.nextElement()
        try {
          val stream   = url.openStream()
          val manifest =
            try ProviderManifestCodec.parse(readFully(stream))
            finally stream.close()
          (manifest.providerArtifact, manifest.providerVersion) match {
            case (Some(artifact), Some(version)) =>
              manifest.effectiveBundles.foreach { bundle =>
                val slash = bundle.indexOf('/')
                if (slash > 0 && slash < bundle.length - 1) {
                  val classifier = bundle.substring(0, slash)
                  val fileName   = bundle.substring(slash + 1)
                  entries += (((classifier, fileName), V2Owner(manifest.providerName, url.toString, artifact, version)))
                }
              }
            case _ => () // v1 manifest — not indexable; the legacy lookup covers it
          }
        } catch {
          case _: Exception => () // unparseable manifest — skip; the legacy lookup may still succeed
        }
      }
    }
    entries.result().groupBy(_._1).map { case (key, owners) => key -> owners.map(_._2).distinct }
  }

  // ── Internal resolution pipeline ──────────────────────────────────

  /** Full resolution pipeline (see the resolution order in the object scaladoc). Package-private for tests, which supply a fixture class loader and platform classifier. */
  private[core] def resolve(
    libName:     String,
    classifier:  String,
    classLoader: ClassLoader,
    v2Index:     Map[(String, String), Seq[V2Owner]]
  ): Path = {
    val mapped = mappedFileName(libName)
    val notes  = Seq.newBuilder[String]

    findOnLibraryPath(mapped)
      .orElse(extractViaV2Index(mapped, classifier, classLoader, v2Index, notes))
      .orElse(extractFromClasspathLegacy(mapped, classifier, classLoader))
      .orElse(loadViaSystemOnAndroid(libName, mapped))
      .getOrElse {
        val libPath      = System.getProperty("java.library.path", "")
        val v2Candidates = v2Index.getOrElse((classifier, mapped), Seq.empty).map(o => s"native/${o.artifact}/${o.version}/$classifier/$mapped")
        val sb           = new StringBuilder
        sb.append(s"Cannot find native library '$mapped' (logical name: '$libName').\n")
        sb.append(s"  Searched java.library.path: $libPath\n")
        v2Candidates.foreach(p => sb.append(s"  Searched classpath resource: $p (manifest v2)\n"))
        sb.append(s"  Searched classpath resource: native/$classifier/$mapped (legacy layout)\n")
        notes.result().foreach(n => sb.append(s"  Note: $n\n"))
        sb.append(s"  Host platform: $classifier")
        throw new UnsatisfiedLinkError(sb.toString)
      }
  }

  /** Search `java.library.path` for the mapped library name. Splits on `File.pathSeparator` — multiple directories are supported. */
  private def findOnLibraryPath(mapped: String): Option[Path] = {
    val libPath = System.getProperty("java.library.path", "")
    val paths   = libPath.split(java.io.File.pathSeparator)
    paths.iterator.map(dir => Path.of(dir, mapped)).find(Files.exists(_))
  }

  /** Resolve via the manifest-v2 index: 0 owners -> None (fall through); more than one distinct artifact+version -> collision error; exactly 1 -> extract
    * `native/<artifact>/<version>/<classifier>/<mapped>`.
    *
    * If the declared resource is missing (the manifest lies), a note is recorded for the final diagnostic and resolution falls through to the legacy layout.
    */
  private def extractViaV2Index(
    mapped:      String,
    classifier:  String,
    classLoader: ClassLoader,
    v2Index:     Map[(String, String), Seq[V2Owner]],
    notes:       scala.collection.mutable.Builder[String, Seq[String]]
  ): Option[Path] =
    v2Index.get((classifier, mapped)) match {
      case None                           => None
      case Some(owners) if owners.isEmpty => None
      case Some(owners)                   =>
        val distinctArtifacts = owners.map(o => (o.artifact, o.version)).distinct
        if (distinctArtifacts.size > 1) {
          val reps      = distinctArtifacts.flatMap { case (a, v) => owners.find(o => o.artifact == a && o.version == v) }
          val collision = NativeExtract.BundleCollision(
            classifier,
            mapped,
            reps.map(o => (o.providerName, o.source, s"native/${o.artifact}/${o.version}/$classifier/$mapped"))
          )
          throw new UnsatisfiedLinkError(NativeExtract.renderCollision(collision))
        } else {
          val owner        = owners.head
          val resourcePath = s"native/${owner.artifact}/${owner.version}/$classifier/$mapped"
          val stream       = classLoader.getResourceAsStream(resourcePath)
          if (stream == null) {
            notes += s"manifest v2 of provider '${owner.providerName}' (${owner.source}) declares $resourcePath but the resource is missing"
            None
          } else extractStream(stream, mapped)
        }
    }

  /** Extract the library from the legacy classpath layout (`native/<classifier>/<mapped>`). Logs a deprecation line once per library. */
  private def extractFromClasspathLegacy(mapped: String, classifier: String, classLoader: ClassLoader): Option[Path] = {
    val resourcePath = s"native/$classifier/$mapped"
    val stream       = classLoader.getResourceAsStream(resourcePath)
    if (stream == null) None
    else {
      val result = extractStream(stream, mapped)
      if (legacyWarned.add(mapped)) {
        System.err.println(s"[multiarch] '$mapped' resolved via legacy layout native/$classifier/ — provider predates manifest v2")
      }
      result
    }
  }

  /** Copy an open resource stream to the per-JVM temp dir and return the target path. Closes the stream. */
  private def extractStream(stream: java.io.InputStream, mapped: String): Option[Path] =
    try {
      val dir    = ensureTempDir()
      val target = dir.resolve(mapped)
      Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
      target.toFile.deleteOnExit()
      Some(target)
    } finally stream.close()

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
      try
        manifests += ProviderManifestCodec.parse(readFully(stream))
      finally stream.close()
    }
    manifests.result()
  }

  /** Read an InputStream fully as UTF-8 text (caller closes the stream). */
  private def readFully(stream: java.io.InputStream): String = {
    val reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)
    val sb     = new StringBuilder
    val buf    = new Array[Char](4096)
    var read   = reader.read(buf)
    while (read > 0) { sb.appendAll(buf, 0, read); read = reader.read(buf) }
    sb.toString
  }
}
