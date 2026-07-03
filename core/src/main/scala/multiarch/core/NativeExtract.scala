package multiarch.core

import java.io.{ File, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, StandardCopyOption }
import java.util.jar.JarFile

/** Pure extraction and flag-merging logic for native library providers.
  *
  * All methods are sbt-independent — they take `java.io.File` arguments and a simple [[NativeExtract.Logger]] for output.
  */
object NativeExtract {

  /** Simple logging trait to avoid depending on sbt's Logger. */
  trait Logger {
    def info(msg:  String): Unit
    def warn(msg:  String): Unit
    def error(msg: String): Unit
  }

  // ── Library file patterns ─────────────────────────────────────────

  private val NativeLibExts = Set(".a", ".so", ".dylib", ".dll", ".lib")

  def isNativeLib(name: String): Boolean =
    NativeLibExts.exists(name.endsWith)

  // ── Manifest discovery ────────────────────────────────────────────

  /** Discover all provider manifests from JARs and resource directories.
    *
    * @param jars
    *   JAR files to scan (e.g. from the compile classpath)
    * @param resourceDirs
    *   project resource directories to scan
    * @param providerTypes
    *   which provider types to look for
    * @param logger
    *   for logging discoveries and warnings
    * @return
    *   triples of (provider type, parsed manifest, source) — source is the JAR file name or the manifest file path, used for collision reporting
    */
  def discoverManifests(
    jars:          Seq[File],
    resourceDirs:  Seq[File],
    providerTypes: Seq[ProviderType],
    logger:        Logger
  ): Seq[(ProviderType, ProviderManifest, String)] = {
    val filenames = providerTypes.map(pt => pt.filename -> pt).toMap

    val fromJars: Seq[(ProviderType, ProviderManifest, String)] = jars.flatMap { file =>
      if (file.isFile && file.getName.endsWith(".jar")) {
        scanJarForManifests(file, filenames, logger)
      } else Seq.empty
    }

    val fromResources: Seq[(ProviderType, ProviderManifest, String)] = resourceDirs.flatMap { dir =>
      filenames.flatMap { case (filename, providerType) =>
        val f = new File(dir, filename)
        if (f.exists()) {
          try {
            val json     = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
            val manifest = ProviderManifestCodec.parse(json)
            validateManifest(manifest, f.getAbsolutePath, providerType, logger)
            logger.info(s"[native-provider] Found ${providerType.label} manifest '${manifest.providerName}' in ${f.getAbsolutePath}")
            Some((providerType, manifest, f.getAbsolutePath))
          } catch {
            case e: Exception =>
              logger.warn(s"[native-provider] Error reading ${f.getAbsolutePath}: ${e.getMessage}")
              None
          }
        } else None
      }
    }

    fromJars ++ fromResources
  }

  private def scanJarForManifests(
    file:      File,
    filenames: Map[String, ProviderType],
    logger:    Logger
  ): Seq[(ProviderType, ProviderManifest, String)] =
    try {
      val jar = new JarFile(file)
      try
        filenames.flatMap { case (filename, providerType) =>
          val entry = jar.getEntry(filename)
          if (entry != null) {
            val reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)
            try {
              val sb   = new StringBuilder
              val buf  = new Array[Char](4096)
              var read = reader.read(buf)
              while (read > 0) { sb.appendAll(buf, 0, read); read = reader.read(buf) }
              val manifest = ProviderManifestCodec.parse(sb.toString)
              validateManifest(manifest, file.getName, providerType, logger)
              logger.info(s"[native-provider] Found ${providerType.label} manifest '${manifest.providerName}' in ${file.getName}")
              Some((providerType, manifest, file.getName))
            } finally reader.close()
          } else None
        }.toSeq
      finally jar.close()
    } catch {
      case e: Exception =>
        logger.warn(s"[native-provider] Error reading ${file.getName}: ${e.getMessage}")
        Seq.empty
    }

  /** Validate a parsed manifest and throw on invalid/empty content. */
  private def validateManifest(
    manifest:     ProviderManifest,
    source:       String,
    providerType: ProviderType,
    logger:       Logger
  ): Unit = {
    if (manifest.configs.isEmpty) {
      throw new RuntimeException(
        s"[native-provider] Invalid ${providerType.label} manifest in $source: " +
          "'configs' is empty or missing. The provider JAR was likely built with a corrupt or empty manifest file."
      )
    }
    manifest.configs.foreach { config =>
      if (config.platforms.isEmpty) {
        throw new RuntimeException(
          s"[native-provider] Invalid ${providerType.label} manifest in $source: " +
            s"config '${config.configName}' has no platform entries."
        )
      }
    }
  }

  // ── Bundle collision detection ────────────────────────────────────

  /** A native library file bundled by more than one distinct provider for the same platform.
    *
    * @param classifier
    *   platform classifier (e.g. `"linux-x86_64"`)
    * @param fileName
    *   the colliding library file name (e.g. `"libfoo.so"`)
    * @param owners
    *   the distinct providers bundling it, as (providerName, source jar/dir, resourcePath)
    */
  final case class BundleCollision(
    classifier: String,
    fileName:   String,
    owners:     Seq[(String, String, String)]
  )

  /** The classpath resource path a manifest's bundle entry resolves to.
    *
    * v2 (artifact + version known): `native/<artifact>/<version>/<classifier>/<file>`; otherwise the legacy `native/<classifier>/<file>`.
    */
  def bundleResourcePath(manifest: ProviderManifest, bundleEntry: String): String =
    (manifest.providerArtifact, manifest.providerVersion) match {
      case (Some(artifact), Some(version)) => s"native/$artifact/$version/$bundleEntry"
      case _                               => s"native/$bundleEntry"
    }

  /** Detect `(classifier, fileName)` pairs bundled by more than one distinct provider.
    *
    * Keys over [[ProviderManifest.effectiveBundles]] of ALL discovered manifests regardless of [[ProviderType]] (JNI/Panama/SN share the `native/` namespace and the extraction dir). Two owners with
    * identical `(providerArtifact, providerVersion)` are NOT a collision (same artifact seen twice on the classpath); manifests without artifact+version (v1) are identified by their source instead.
    * Stub binaries still count — a stub silently shadowing a real lib is exactly the bug class this prevents.
    *
    * @param discovered
    *   discovered manifests with their source (jar file name or resource path), as returned by [[discoverManifests]]
    * @return
    *   collisions, sorted by (classifier, fileName) for deterministic reporting
    */
  def detectBundleCollisions(
    discovered: Seq[(ProviderType, ProviderManifest, String)]
  ): Seq[BundleCollision] = {
    // (classifier, fileName) -> owners as (identity, providerName, source, resourcePath)
    val entries: Seq[((String, String), (String, String, String, String))] = discovered.flatMap { case (_, manifest, source) =>
      manifest.effectiveBundles.flatMap { bundle =>
        val slash = bundle.indexOf('/')
        if (slash <= 0 || slash == bundle.length - 1) None // malformed entry — validateBundles reports these
        else {
          val classifier = bundle.substring(0, slash)
          val fileName   = bundle.substring(slash + 1)
          val identity   = (manifest.providerArtifact, manifest.providerVersion) match {
            case (Some(artifact), Some(version)) => s"artifact:$artifact:$version"
            case _                               => s"source:$source"
          }
          Some(((classifier, fileName), (identity, manifest.providerName, source, bundleResourcePath(manifest, bundle))))
        }
      }
    }
    entries.groupBy(_._1).toSeq.sortBy(_._1).flatMap { case ((classifier, fileName), group) =>
      val owners             = group.map(_._2).distinct
      val distinctIdentities = owners.map(_._1).distinct
      if (distinctIdentities.size > 1) {
        // one representative owner per distinct identity, in first-seen order
        val reps = distinctIdentities.flatMap(id => owners.find(_._1 == id))
        Some(BundleCollision(classifier, fileName, reps.map(o => (o._2, o._3, o._4))))
      } else None
    }
  }

  /** Render a [[BundleCollision]] as the prescriptive error message (exact template — tests assert on it). */
  def renderCollision(collision: BundleCollision): String = {
    val sb = new StringBuilder
    sb.append(s"[native-provider] Native library collision for platform '${collision.classifier}':\n")
    sb.append(s"  file '${collision.fileName}' is bundled by ${collision.owners.size} providers:\n")
    collision.owners.foreach { case (providerName, source, resourcePath) =>
      sb.append(s"    - '$providerName' ($source) at $resourcePath\n")
    }
    sb.append("  Classpath order would silently decide which library is extracted/loaded.\n")
    sb.append("  Fix: depend on only one of these providers, or rename the library in one of them.")
    sb.toString
  }

  // ── Bundle-truth validation (provider-side) ───────────────────────

  /** Validate that a provider JAR's contents match its manifest's bundle declarations.
    *
    * Checks, in order:
    *   - every [[ProviderManifest.effectiveBundles]] entry exists in the JAR at its computed resource path ([[bundleResourcePath]]) — otherwise `missing`
    *   - each such entry is at least `minLibBytes` bytes — otherwise `undersized`
    *   - every `native/` JAR entry with a native-library extension is declared — otherwise `undeclared`
    *
    * @param manifest
    *   the provider manifest
    * @param jarEntries
    *   JAR entry name -> uncompressed size in bytes
    * @param minLibBytes
    *   minimum plausible library size (e.g. 32768 — sge's ISS-484 gate)
    * @return
    *   human-readable violations; empty when the JAR is truthful
    */
  def validateBundles(
    manifest:    ProviderManifest,
    jarEntries:  Map[String, Long],
    minLibBytes: Long
  ): Seq[String] = {
    val violations    = Seq.newBuilder[String]
    val expectedPaths = manifest.effectiveBundles.map(bundle => bundleResourcePath(manifest, bundle) -> bundle)
    expectedPaths.foreach { case (path, bundle) =>
      jarEntries.get(path) match {
        case None =>
          violations += s"missing: bundle entry '$bundle' declared by provider '${manifest.providerName}' but JAR has no entry at $path"
        case Some(size) if size < minLibBytes =>
          violations += s"undersized: bundle entry '$bundle' at $path is $size bytes (< $minLibBytes)"
        case _ => ()
      }
    }
    val expectedSet = expectedPaths.map(_._1).toSet
    jarEntries.keys.toSeq.sorted.foreach { entry =>
      if (entry.startsWith("native/") && isNativeLib(entry) && !expectedSet.contains(entry)) {
        violations += s"undeclared: JAR entry '$entry' is a native library not declared in provider '${manifest.providerName}' bundles"
      }
    }
    violations.result()
  }

  // ── Flag merging ──────────────────────────────────────────────────

  /** Merge linker flags from all manifests for a given platform.
    *
    * For each config that declares the target platform:
    *   - If `binary` is set: the absolute path `<libDir>/<binary>` is added as a linker argument
    *   - If `binary` is `None`: no library is linked (only `flagsGroups` contribute)
    *   - If `stub` is true and `binary` is set: stub archive path is included
    *
    * Flag groups from all matching configs are collected, deduplicated at the group level, and flattened.
    *
    * @param manifests
    *   discovered manifests to merge
    * @param platform
    *   target platform
    * @param libDir
    *   optional directory containing extracted native libraries
    * @param logger
    *   for logging
    * @return
    *   merged, deduplicated linker flags
    */
  def mergeFlags(
    manifests: Seq[ProviderManifest],
    platform:  Platform,
    libDir:    Option[File],
    logger:    Logger
  ): Seq[String] = {
    val classifier = platform.classifier

    val libraryArgs   = Seq.newBuilder[String]
    val allFlagGroups = Seq.newBuilder[Seq[String]]

    manifests.foreach { manifest =>
      manifest.configs.foreach { config =>
        config.platforms.get(classifier).foreach { platConfig =>
          // Handle binary field
          platConfig.binary.foreach { binaryName =>
            libDir match {
              case Some(dir) =>
                val archive = new File(dir, binaryName)
                if (archive.exists()) {
                  libraryArgs += archive.getAbsolutePath
                } else {
                  logger.warn(
                    s"[native-provider] Binary '$binaryName' not found in ${dir.getAbsolutePath} " +
                      s"(config '${config.configName}' from provider '${manifest.providerName}')"
                  )
                }
              case None =>
                logger.warn(
                  s"[native-provider] Binary '$binaryName' requested but no library directory set " +
                    s"(config '${config.configName}' from provider '${manifest.providerName}')"
                )
            }
          }

          // Collect flag groups
          allFlagGroups ++= platConfig.flagsGroups
        }
      }
    }

    // Deduplicate flag groups by equality, then flatten
    val dedupedFlags = allFlagGroups.result().distinct.flatten

    // Library directory flag
    val libDirFlags = libDir.toSeq.filter(_.exists()).map(dir => s"-L${dir.getAbsolutePath}")

    val merged = libDirFlags ++ libraryArgs.result() ++ dedupedFlags
    if (merged.nonEmpty) {
      val manifestCount = manifests.size
      logger.info(s"[native-provider] Merged ${merged.size} linker flags for $classifier from $manifestCount manifest(s)")
    }
    merged
  }

  // ── JAR extraction ────────────────────────────────────────────────

  /** Find JARs on the classpath that contain native libraries for the given platform.
    *
    * Matches JARs that have either:
    *   - `native/<platform-classifier>/` entries (fat JAR with all platforms)
    *   - `native/` entries with native file extensions (flat single-platform JAR)
    */
  def findNativeLibJars(jars: Seq[File], platform: Platform): Seq[File] = {
    val platformPrefix = s"native/${platform.classifier}/"
    jars.filter { jar =>
      jar.isFile && jar.getName.endsWith(".jar") && {
        val jf = new JarFile(jar)
        try {
          val entries = new JarEntryIterator(jf)
          entries.exists { e =>
            val name = e.getName
            (name.startsWith(platformPrefix) && isNativeLib(name)) ||
            (name.startsWith("native/") && !name.stripPrefix("native/").contains("/") && isNativeLib(name))
          }
        } finally jf.close()
      }
    }
  }

  /** Extract native libraries from a JAR file for the given platform.
    *
    * Supports two JAR layouts:
    *   - '''Fat JAR''': `native/<platform-classifier>/<file>.a` — all platforms bundled
    *   - '''Flat JAR''': `native/<file>.a` — single-platform JAR
    */
  def extractFromJar(jar: File, platform: Platform, outDir: File, logger: Logger): Unit = {
    outDir.mkdirs()
    val jarFile        = new JarFile(jar)
    val platformPrefix = s"native/${platform.classifier}/"
    try {
      val entries         = new JarEntryIterator(jarFile)
      val allEntries      = entries.filterNot(_.isDirectory).toSeq
      val hasPlatformDirs = allEntries.exists(e => Platform.all.exists(p => e.getName.startsWith(s"native/${p.classifier}/")))
      allEntries.foreach { entry =>
        val name        = entry.getName
        val fileNameOpt =
          if (hasPlatformDirs) {
            if (name.startsWith(platformPrefix)) Some(name.stripPrefix(platformPrefix))
            else None
          } else {
            if (name.startsWith("native/")) Some(name.stripPrefix("native/"))
            else None
          }
        fileNameOpt.foreach { fileName =>
          if (fileName.nonEmpty && !fileName.contains("/") && isNativeLib(fileName)) {
            val target = new File(outDir, fileName)
            val is     = jarFile.getInputStream(entry)
            try Files.copy(is, target.toPath, StandardCopyOption.REPLACE_EXISTING)
            finally is.close()
            logger.info(s"[native-provider] Extracted: $fileName")
          }
        }
      }
    } finally jarFile.close()
  }

  // ── Windows .lib alias creation ──────────────────────────────────

  /** On Windows, create `foo.lib` copies of `libfoo.a` archives.
    *
    * The MSVC linker (used by Scala Native on Windows) resolves `@link("foo")` as `foo.lib`, but GCC-style static archives from provider JARs are named `libfoo.a`. This creates `.lib` copies so both
    * naming conventions resolve.
    */
  def createWindowsLibAliases(dir: File, logger: Logger): Unit = {
    val files = dir.listFiles()
    if (files == null) return
    for (f <- files if f.isFile) {
      val name = f.getName
      if (name.startsWith("lib") && name.endsWith(".a")) {
        val alias  = name.stripPrefix("lib").stripSuffix(".a") + ".lib"
        val target = new File(dir, alias)
        if (!target.exists()) {
          Files.copy(f.toPath, target.toPath)
          logger.info(s"[native-provider] Created Windows alias: $alias -> $name")
        }
      } else if (name.startsWith("lib") && name.endsWith(".lib")) {
        val alias  = name.stripPrefix("lib")
        val target = new File(dir, alias)
        if (!target.exists()) {
          Files.copy(f.toPath, target.toPath)
          logger.info(s"[native-provider] Created Windows alias: $alias -> $name")
        }
      }
    }
  }

  // ── JAR packaging helpers ─────────────────────────────────────────

  /** Create resource mappings for packaging native libraries into a JAR.
    *
    * @param nativeDir
    *   directory containing native library files
    * @return
    *   pairs of (source file, JAR entry path)
    */
  def jarMappings(nativeDir: File): Seq[(File, String)] =
    if (!nativeDir.exists()) Seq.empty
    else {
      val files = nativeDir.listFiles()
      if (files == null) Seq.empty
      else files.filter(f => f.isFile && isNativeLib(f.getName)).map(f => f -> s"native/${f.getName}").toSeq
    }

  // ── Helper ────────────────────────────────────────────────────────

  /** Scala 2.12-compatible wrapper around `JarFile.entries()`. */
  private class JarEntryIterator(jar: JarFile) extends Iterator[java.util.jar.JarEntry] {
    private val underlying = jar.entries()
    def hasNext: Boolean                = underlying.hasMoreElements
    def next():  java.util.jar.JarEntry = underlying.nextElement()
  }
}
