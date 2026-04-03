package multiarch.sbt

import sbt._
import sbt.Keys._

import java.nio.file.{ Files, StandardCopyOption }
import java.util.jar.JarFile
import scala.collection.JavaConverters._

/** Native library extraction from platform-classifier JARs.
  *
  * Scala Native (as of 0.5.x) has no built-in mechanism for distributing
  * platform-specific native libraries (`.a`, `.so`, `.dylib`, `.dll`) through
  * library dependencies (see scala-native/scala-native#4800). This module
  * provides a workaround:
  *
  * '''Publishing side''': Cross-compiled static libraries are packaged into
  * a platform-classifier JAR:
  * {{{
  * my-native-libs_macos-aarch64.jar
  * └── native/
  *     ├── libmylib.a
  *     └── ...
  * }}}
  *
  * '''Consumer side''': This plugin extracts native files from classifier JARs
  * found on the classpath and makes them available for Scala Native linking.
  */
object NativeLibExtract {

  // ── Keys ──────────────────────────────────────────────────────────

  val nativeLibDir = settingKey[File](
    "Directory containing platform-specific native libraries for Scala Native linking"
  )

  val nativeLibPlatform = settingKey[Platform](
    "Target platform for native library extraction. Defaults to the host platform."
  )

  val nativeLibExtract = taskKey[File](
    "Extract native libraries from classifier JARs for the target platform. " +
      "Returns the directory containing the extracted libraries."
  )

  val nativeLibSourceDir = settingKey[Option[File]](
    "Local directory containing cross-compiled native libraries (bypasses JAR extraction). " +
      "When set, uses this directory directly instead of extracting from JARs."
  )

  // ── Library file patterns ─────────────────────────────────────────

  private val NativeLibExts = Set(".a", ".so", ".dylib", ".dll", ".lib")

  private def isNativeLib(name: String): Boolean =
    NativeLibExts.exists(name.endsWith)

  // ── JAR extraction ────────────────────────────────────────────────

  /** Extract native libraries from a JAR file for the given platform.
    *
    * Supports two JAR layouts:
    *   - '''Fat JAR''': `native/<platform-classifier>/<file>.a` — all platforms bundled,
    *     only the matching platform is extracted.
    *   - '''Flat JAR''': `native/<file>.a` — single-platform JAR, all native files extracted.
    */
  private def extractFromJar(jar: File, platform: Platform, outDir: File, log: sbt.util.Logger): Unit = {
    IO.createDirectory(outDir)
    val jarFile          = new JarFile(jar)
    val platformPrefix   = s"native/${platform.classifier}/"
    try {
      val entries = jarFile.entries().asScala.filter(e => !e.isDirectory).toSeq
      // Check if this is a fat JAR (has native/<classifier>/ subdirectories)
      val hasPlatformDirs = entries.exists(e => Platform.desktop.exists(p => e.getName.startsWith(s"native/${p.classifier}/")))
      entries.foreach { entry =>
        val name = entry.getName
        val fileNameOpt =
          if (hasPlatformDirs) {
            // Fat JAR: extract only the current platform's files
            if (name.startsWith(platformPrefix)) Some(name.stripPrefix(platformPrefix))
            else None
          } else {
            // Flat JAR: extract all native/ entries
            if (name.startsWith("native/")) Some(name.stripPrefix("native/"))
            else None
          }
        fileNameOpt.foreach { fileName =>
          if (fileName.nonEmpty && !fileName.contains("/") && isNativeLib(fileName)) {
            val target = outDir / fileName
            val is     = jarFile.getInputStream(entry)
            try Files.copy(is, target.toPath, StandardCopyOption.REPLACE_EXISTING)
            finally is.close()
            log.info(s"[native-lib] Extracted: $fileName")
          }
        }
      }
    } finally jarFile.close()
  }

  /** Find JARs on the classpath that contain native libraries for the given platform.
    *
    * Matches JARs that have either:
    *   - `native/<platform-classifier>/` entries (fat JAR with all platforms)
    *   - `native/` entries with native file extensions (flat single-platform JAR)
    */
  private def findNativeLibJars(
      classpath: Seq[Attributed[File]],
      platform: Platform,
      log: sbt.util.Logger
  ): Seq[File] = {
    val platformPrefix = s"native/${platform.classifier}/"
    classpath.map(_.data).filter { jar =>
      jar.isFile && jar.getName.endsWith(".jar") && {
        val jf = new JarFile(jar)
        try {
          val entries = jf.entries().asScala
          entries.exists { e =>
            val name = e.getName
            // Fat JAR: has native/<platform>/<file>.a entries
            (name.startsWith(platformPrefix) && isNativeLib(name)) ||
            // Flat JAR: has native/<file>.a entries (no subdirectory)
            (name.startsWith("native/") && !name.stripPrefix("native/").contains("/") && isNativeLib(name))
          }
        } finally jf.close()
      }
    }
  }

  // ── Local directory lookup ────────────────────────────────────────

  /** Find platform-specific libraries in a local cross-compilation output directory.
    *
    * Expected layout:
    * {{{
    * <crossDir>/
    * ├── linux-x86_64/
    * │   └── libmylib.a
    * ├── macos-aarch64/
    * │   └── libmylib.a
    * └── ...
    * }}}
    */
  private def localPlatformDir(crossDir: File, platform: Platform): File =
    crossDir / platform.classifier

  // ── Windows .lib alias creation ──────────────────────────────────

  /** On Windows, create `foo.lib` copies of `libfoo.a` archives.
    *
    * The MSVC linker (used by Scala Native on Windows) resolves `@link("foo")`
    * as `foo.lib`, but GCC-style static archives from provider JARs are named
    * `libfoo.a`. This creates `.lib` copies so both naming conventions resolve.
    */
  private def createWindowsLibAliases(dir: File, log: sbt.util.Logger): Unit = {
    val files = IO.listFiles(dir).filter(_.isFile)
    for (f <- files) {
      val name = f.getName
      // libfoo.a → foo.lib
      if (name.startsWith("lib") && name.endsWith(".a")) {
        val alias = name.stripPrefix("lib").stripSuffix(".a") + ".lib"
        val target = new java.io.File(dir, alias)
        if (!target.exists()) {
          Files.copy(f.toPath, target.toPath)
          log.info(s"[native-lib] Created Windows alias: $alias -> $name")
        }
      }
      // libfoo.lib → foo.lib
      else if (name.startsWith("lib") && name.endsWith(".lib")) {
        val alias = name.stripPrefix("lib")
        val target = new java.io.File(dir, alias)
        if (!target.exists()) {
          Files.copy(f.toPath, target.toPath)
          log.info(s"[native-lib] Created Windows alias: $alias -> $name")
        }
      }
    }
  }

  // ── Settings ──────────────────────────────────────────────────────

  /** Settings for projects that consume native libraries from classifier JARs. */
  lazy val settings: Seq[Setting[_]] = Seq(
    nativeLibPlatform := Platform.host,
    nativeLibSourceDir := None,
    nativeLibExtract := {
      val log      = streams.value.log
      val platform = nativeLibPlatform.value
      val outDir   = target.value / "native-libs" / platform.classifier
      val cp       = (Compile / dependencyClasspathAsJars).value

      nativeLibSourceDir.value match {
        case Some(crossDir) =>
          // Local mode: use cross-compilation output directly
          val platDir = localPlatformDir(crossDir, platform)
          if (!platDir.exists()) {
            sys.error(
              s"[native-lib] Directory not found: ${platDir.getAbsolutePath}\n" +
                "Build native libraries for this platform first."
            )
          }
          log.info(s"[native-lib] Using local native libs: ${platDir.getAbsolutePath}")
          platDir

        case None =>
          // JAR mode: extract from dependencies
          if (outDir.exists() && IO.listFiles(outDir).exists(f => isNativeLib(f.getName))) {
            log.info(s"[native-lib] Using cached native libs: ${outDir.getAbsolutePath}")
            outDir
          } else {
            val jars = findNativeLibJars(cp, platform, log)
            if (jars.nonEmpty) {
              log.info(s"[native-lib] Extracting native libs from ${jars.size} JAR(s)...")
              IO.delete(outDir)
              jars.foreach(jar => extractFromJar(jar, platform, outDir, log))
              // On Windows, the MSVC linker resolves @link("foo") as foo.lib, but
              // provider JARs ship GCC-style libfoo.a archives. Create .lib copies
              // so both manifest fullPath entries and @link annotations resolve.
              if (platform.isWindows) createWindowsLibAliases(outDir, log)
              outDir
            } else {
              sys.error(
                s"[native-lib] Could not find native library JARs for platform '$platform' in the classpath.\n" +
                  "Either:\n" +
                  "  1. Add a native library provider as a dependency, or\n" +
                  "  2. Set nativeLibSourceDir to a local cross-compilation directory."
              )
            }
          }
      }
    },
    nativeLibDir := {
      nativeLibSourceDir.value match {
        case Some(crossDir) => localPlatformDir(crossDir, nativeLibPlatform.value)
        case None           => target.value / "native-libs" / nativeLibPlatform.value.classifier
      }
    }
  )

  // ── JAR packaging helpers ─────────────────────────────────────────

  /** Create resource mappings for packaging native libraries into a JAR.
    *
    * Usage in build.sbt:
    * {{{
    * Compile / packageBin / mappings ++= NativeLibExtract.jarMappings(
    *   baseDirectory.value / "native-libs" / platform.classifier
    * )
    * }}}
    */
  def jarMappings(nativeDir: File): Seq[(File, String)] =
    if (!nativeDir.exists()) Seq.empty
    else {
      IO.listFiles(nativeDir)
        .filter(f => f.isFile && isNativeLib(f.getName))
        .map(f => f -> s"native/${f.getName}")
        .toSeq
    }
}
