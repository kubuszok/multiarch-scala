package multiarch.sbt

import multiarch.core.{ NativeExtract, ProviderManifest, ProviderType }

import sbt._
import sbt.Keys._

/** sbt task/setting keys for native provider manifest discovery and flag merging.
  *
  * Thin wrapper around [[multiarch.core.NativeExtract]] — the pure logic lives in the
  * `multiarch-core` module.
  */
object NativeProviderSettings {

  // ── Keys ──────────────────────────────────────────────────────────

  /** Discovered provider manifests from the classpath. */
  val discoverManifests = taskKey[Seq[(ProviderType, ProviderManifest)]](
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

  // ── Logger adapter ────────────────────────────────────────────────

  private def wrapLogger(sbtLog: sbt.util.Logger): NativeExtract.Logger = new NativeExtract.Logger {
    def info(msg: String): Unit  = sbtLog.info(msg)
    def warn(msg: String): Unit  = sbtLog.warn(msg)
    def error(msg: String): Unit = sbtLog.error(msg)
  }

  // ── Settings ──────────────────────────────────────────────────────

  lazy val settings: Seq[Setting[_]] = Seq(
    nativeProviderPlatform := Platform.host,
    nativeProviderLibDir   := None,
    nativeProviderTypes    := Seq(ProviderType.ScalaNative),
    discoverManifests := {
      val log     = streams.value.log
      val cp      = (Compile / dependencyClasspathAsJars).value.map(_.data)
      val resDirs = (Compile / resourceDirectories).value
      val types   = nativeProviderTypes.value
      NativeExtract.discoverManifests(cp, resDirs, types, wrapLogger(log))
    },
    mergedLinkerFlags := {
      val discovered = discoverManifests.value.map(_._2)
      val platform   = nativeProviderPlatform.value
      val libDirOpt  = nativeProviderLibDir.value
      val log        = streams.value.log
      NativeExtract.mergeFlags(discovered, platform, libDirOpt, wrapLogger(log))
    }
  )
}
