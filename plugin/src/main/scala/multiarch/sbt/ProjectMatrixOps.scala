package multiarch.sbt

import multiarch.core.Platform
import multiarch.sbt.Compat.ProjectMatrix

import sbt._

import scala.scalanative.sbtplugin.ScalaNativePlugin

/** Extension methods for [[ProjectMatrix]] provided by multiarch-scala.
  *
  * Import [[ProjectMatrixOps.Ops]] (or `import ProjectMatrixOps._`) to enable the extension methods on any `projectMatrix` value.
  */
object ProjectMatrixOps {

  implicit class Ops(val matrix: ProjectMatrix) extends AnyVal {

    /** Add cross-native compilation rows for every desktop platform, including the host.
      *
      * Each desktop platform gets its own sbt subproject carrying a [[NativeCrossAxis]], so the SAME packaging command shape works for every platform regardless of which architecture CI runs on.
      * Subproject IDs are derived from [[NativeCrossAxis]]; for a matrix named `pong` you get `pongNativeLinuxX86_64`, `pongNativeMacosAarch64`, etc., AND a `pongNative<Host>` row for the current
      * host.
      *
      * Host handling: the host row is built with the native toolchain directly (`zigCrossTarget := None`), so it does not require `zig`. Non-host rows use zig-based cross-compilation via
      * [[MultiArchNativeReleasePlugin]] and are only emitted when `zig` is available on `PATH` — otherwise just the host row is added.
      *
      * The host row added here is distinct from any row produced by `.nativePlatform`: it carries a [[NativeCrossAxis]] axis (and thus a `Native<Host>` ID suffix and a `native-<classifier>`
      * directory), so there is no project-ID or directory collision.
      *
      * @param scalaVersion
      *   Scala version used for the generated cross-native subprojects
      */
    def withCrossNative(scalaVersion: String): ProjectMatrix = {
      val host = Platform.host
      // The host always gets a NativeCrossAxis row (built natively, no zig required).
      // Non-host desktop platforms are added only when zig is available for cross-compilation.
      val targets =
        if (ZigCross.isAvailable) Platform.desktop
        else Platform.desktop.filter(_ == host)
      targets.foldLeft(matrix) { (m, platform) =>
        val isHost = platform == host
        m.customRow(
          autoScalaLibrary = true,
          scalaVersions = Seq(scalaVersion),
          axisValues = Seq(
            NativeCrossAxis(platform),
            VirtualAxis.native,
            VirtualAxis.scalaABIVersion(scalaVersion)
          ),
          process = _.enablePlugins(ScalaNativePlugin, NativeProviderPlugin, MultiArchNativeReleasePlugin).settings(
            MultiArchNativeReleasePlugin.autoImport.zigCrossTarget := (if (isHost) None else Some(platform))
          )
        )
      }
    }
  }
}
