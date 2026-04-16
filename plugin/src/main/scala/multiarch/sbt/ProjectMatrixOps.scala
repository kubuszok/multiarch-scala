package multiarch.sbt

import sbt._
import sbt.internal.ProjectMatrix

import scala.scalanative.sbtplugin.ScalaNativePlugin

/** Extension methods for [[ProjectMatrix]] provided by multiarch-scala.
  *
  * Import [[ProjectMatrixOps.Ops]] (or `import ProjectMatrixOps._`) to enable
  * the extension methods on any `projectMatrix` value.
  */
object ProjectMatrixOps {

  implicit class Ops(val matrix: ProjectMatrix) extends AnyVal {

    /** Add cross-native compilation axes for all non-host desktop platforms.
      *
      * Each target platform gets its own sbt subproject with zig-based cross-compilation
      * via [[MultiArchNativeReleasePlugin]]. Requires `zig` to be installed on `PATH`;
      * if `zig` is unavailable the method is a no-op.
      *
      * Subproject IDs are derived from [[NativeCrossAxis]]; for a matrix named `pong`,
      * you get `pongNativeLinuxX86_64`, `pongNativeMacosAarch64`, etc. (excluding the
      * current host, which is already produced by `.nativePlatform`).
      *
      * @param scalaVersion Scala version used for the generated cross-native subprojects
      */
    def withCrossNative(scalaVersion: String): ProjectMatrix = {
      val targets =
        if (ZigCross.isAvailable) Platform.desktop.filterNot(_ == Platform.host)
        else Seq.empty
      targets.foldLeft(matrix) { (m, platform) =>
        m.customRow(
          autoScalaLibrary = true,
          scalaVersions    = Seq(scalaVersion),
          axisValues       = Seq(
            NativeCrossAxis(platform),
            VirtualAxis.native,
            VirtualAxis.scalaABIVersion(scalaVersion)
          ),
          process = _.enablePlugins(ScalaNativePlugin, NativeProviderPlugin, MultiArchNativeReleasePlugin)
            .settings(MultiArchNativeReleasePlugin.autoImport.zigCrossTarget := Some(platform))
        )
      }
    }
  }
}
