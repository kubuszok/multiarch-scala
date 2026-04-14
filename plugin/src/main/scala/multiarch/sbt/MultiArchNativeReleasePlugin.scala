package multiarch.sbt

import sbt._
import sbt.Keys._

import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** AutoPlugin for multi-architecture Scala Native release packaging.
  *
  * Combines `NativeProviderPlugin` with zig-based cross-compilation support.
  * When `zigCrossTarget` is set to a [[Platform]], this plugin overrides
  * `nativeConfig` with zig cc/c++ wrapper scripts and the correct target triple.
  *
  * === Usage ===
  * {{{
  * // In build.sbt
  * lazy val myApp = (projectMatrix in file("my-app"))
  *   .enablePlugins(NativeProviderPlugin, MultiArchNativeReleasePlugin)
  *   .nativePlatform(scalaVersions = Seq("3.8.1"),
  *     settings = Seq(
  *       MultiArchNativeReleasePlugin.autoImport.zigCrossTarget := Some(Platform.LinuxX86_64)
  *     ))
  * }}}
  *
  * Requires `zig` to be installed and available on PATH.
  */
object MultiArchNativeReleasePlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = NativeProviderPlugin

  object autoImport {
    val zigCrossTarget = settingKey[Option[Platform]](
      "Target platform for zig cross-compilation. When set, overrides nativeConfig with zig wrappers."
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    zigCrossTarget := None,
    NativeExtractSettings.nativeLibPlatform := {
      zigCrossTarget.value.getOrElse(Platform.host)
    },
    NativeProviderSettings.nativeProviderPlatform := {
      zigCrossTarget.value.getOrElse(Platform.host)
    },
    nativeConfig := {
      val c = nativeConfig.value
      zigCrossTarget.value match {
        case Some(platform) =>
          val wrapperDir = target.value / "zig-wrappers"
          c.withClang(ZigCross.clangWrapper(platform, wrapperDir))
            .withClangPP(ZigCross.clangPPWrapper(platform, wrapperDir))
            .withTargetTriple(Some(platform.scalaNativeTarget))
        case None => c
      }
    }
  )
}
