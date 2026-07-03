package multiarch.sbt

import multiarch.core.{ NativeExtract, Platform, ProviderManifest, ProviderType }

import sbt._
import sbt.Keys._

/** sbt task/setting keys for native provider manifest discovery and flag merging.
  *
  * Thin wrapper around [[multiarch.core.NativeExtract]] — the pure logic lives in the `multiarch-core` module.
  */
object NativeProviderSettings {

  // ── Keys ──────────────────────────────────────────────────────────

  /** Discovered provider manifests from the classpath, with their source (JAR file name or manifest path). */
  val discoverManifests = taskKey[Seq[(ProviderType, ProviderManifest, String)]](
    "Discovers all native provider manifests from classpath dependencies and project resources"
  )

  /** Merged linker flags from all discovered manifests for the current platform. */
  val mergedLinkerFlags = taskKey[Seq[String]](
    "Linker flags merged from all native provider manifests for the target platform"
  )

  /** The target platform for native provider resolution. Defaults to the host platform. */
  val nativeProviderPlatform = settingKey[Platform](
    "Target platform for native provider resolution. Defaults to host platform."
  )

  /** Optional: the directory containing native libraries. When set, `-L<dir>` is prepended to flags. */
  val nativeProviderLibDir = taskKey[Option[File]](
    "Optional directory containing native libraries. Adds -L<dir> to linker flags."
  )

  /** Which provider types to discover. */
  val nativeProviderTypes = settingKey[Seq[ProviderType]](
    "Provider types to discover on the classpath."
  )

  /** Standalone collision check for projects that do NOT enable NativeProviderPlugin (e.g. JVM consumers of Panama/JNI providers). */
  val nativeProviderCheckCollisions = taskKey[Unit](
    "Fails the build when two classpath providers bundle the same native library file for the same platform"
  )

  // ── Logger adapter ────────────────────────────────────────────────

  private def wrapLogger(sbtLog: sbt.util.Logger): NativeExtract.Logger = new NativeExtract.Logger {
    def info(msg:  String): Unit = sbtLog.info(msg)
    def warn(msg:  String): Unit = sbtLog.warn(msg)
    def error(msg: String): Unit = sbtLog.error(msg)
  }

  // ── Settings ──────────────────────────────────────────────────────

  lazy val settings: Seq[Setting[_]] = Seq(
    nativeProviderPlatform := Platform.host,
    // File-typed task result -> opt out of sbt 2.0 caching (identity on sbt 1.x).
    nativeProviderLibDir := Compat.uncached(Option.empty[File]),
    nativeProviderTypes := Seq(ProviderType.ScalaNative),
    // Result captures custom ProviderType/ProviderManifest types -> opt out of caching.
    discoverManifests := Compat.uncached {
      val log        = streams.value.log
      val cp         = Compat.toFiles((Compile / dependencyClasspathAsJars).value)(fileConverter.value)
      val resDirs    = (Compile / resourceDirectories).value
      val types      = nativeProviderTypes.value
      val discovered = NativeExtract.discoverManifests(cp, resDirs, types, wrapLogger(log))
      failOnCollisions(discovered)
      discovered
    },
    // Captures custom-typed inputs (manifests, Platform) into the cache key -> opt out.
    mergedLinkerFlags := Compat.uncached {
      val discovered = discoverManifests.value.map(_._2)
      val platform   = nativeProviderPlatform.value
      val libDirOpt  = nativeProviderLibDir.value
      val log        = streams.value.log
      NativeExtract.mergeFlags(discovered, platform, libDirOpt, wrapLogger(log))
    }
  )

  /** Build-time collision check for projects that do NOT enable [[NativeProviderPlugin]] (e.g. JVM consumers loading Panama/JNI providers at runtime).
    *
    * Discovers manifests of ALL provider types from `Compile / dependencyClasspathAsJars` + project resources and fails with the prescriptive collision message when two providers bundle the same
    * library file for the same platform. Consumers typically wire it as:
    * {{{
    * Compile / compile := (Compile / compile).dependsOn(NativeProviderSettings.nativeProviderCheckCollisions).value
    * }}}
    */
  lazy val collisionCheckSettings: Seq[Setting[_]] = Seq(
    nativeProviderCheckCollisions := Compat.uncached {
      val log     = streams.value.log
      val cp      = Compat.toFiles((Compile / dependencyClasspathAsJars).value)(fileConverter.value)
      val resDirs = (Compile / resourceDirectories).value
      failOnCollisions(NativeExtract.discoverManifests(cp, resDirs, ProviderType.all, wrapLogger(log)))
    }
  )

  /** Shared collision gate: renders every detected collision and aborts the task. */
  private def failOnCollisions(discovered: Seq[(ProviderType, ProviderManifest, String)]): Unit = {
    val collisions = NativeExtract.detectBundleCollisions(discovered)
    if (collisions.nonEmpty) {
      sys.error(collisions.map(NativeExtract.renderCollision).mkString("\n"))
    }
  }
}
