package multiarch.sbt

import multiarch.core.NativeExtract

import sbt._
import sbt.Keys._

import java.nio.file.Files

/** sbt task/setting keys for native library extraction from classifier JARs.
  *
  * Thin wrapper around [[multiarch.core.NativeExtract]] — the pure logic lives in the
  * `multiarch-core` module.
  */
object NativeExtractSettings {

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

  // ── Logger adapter ────────────────────────────────────────────────

  private def wrapLogger(sbtLog: sbt.util.Logger): NativeExtract.Logger = new NativeExtract.Logger {
    def info(msg: String): Unit = sbtLog.info(msg)
    def warn(msg: String): Unit = sbtLog.warn(msg)
  }

  // ── Local directory lookup ────────────────────────────────────────

  private def localPlatformDir(crossDir: File, platform: Platform): File =
    crossDir / platform.classifier

  // ── Settings ──────────────────────────────────────────────────────

  lazy val settings: Seq[Setting[_]] = Seq(
    nativeLibPlatform  := Platform.host,
    nativeLibSourceDir := None,
    nativeLibExtract := {
      val log      = streams.value.log
      val logger   = wrapLogger(log)
      val platform = nativeLibPlatform.value
      val outDir   = target.value / "native-libs" / platform.classifier
      val cp       = (Compile / dependencyClasspathAsJars).value.map(_.data)

      nativeLibSourceDir.value match {
        case Some(crossDir) =>
          val platDir = localPlatformDir(crossDir, platform)
          if (!platDir.exists()) {
            sys.error(
              s"[native-provider] Directory not found: ${platDir.getAbsolutePath}\n" +
                "Build native libraries for this platform first."
            )
          }
          log.info(s"[native-provider] Using local native libs: ${platDir.getAbsolutePath}")
          platDir

        case None =>
          if (outDir.exists() && outDir.listFiles() != null && outDir.listFiles().exists(f => NativeExtract.isNativeLib(f.getName))) {
            log.info(s"[native-provider] Using cached native libs: ${outDir.getAbsolutePath}")
            outDir
          } else {
            val jars = NativeExtract.findNativeLibJars(cp, platform)
            if (jars.nonEmpty) {
              log.info(s"[native-provider] Extracting native libs from ${jars.size} JAR(s)...")
              IO.delete(outDir)
              jars.foreach(jar => NativeExtract.extractFromJar(jar, platform, outDir, logger))
              if (platform.isWindows) NativeExtract.createWindowsLibAliases(outDir, logger)
              outDir
            } else {
              sys.error(
                s"[native-provider] Could not find native library JARs for platform '$platform' in the classpath.\n" +
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
}
