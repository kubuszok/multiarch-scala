package multiarch.sbt

import sbt._
import sbt.Keys._

import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** AutoPlugin that provides zig-based cross-compilation for Scala Native.
  *
  * When `zigCrossTarget` is set to a [[Platform]], this plugin overrides
  * `nativeConfig` with zig cc/c++ wrapper scripts and the correct target triple.
  *
  * === Usage ===
  * {{{
  * // In build.sbt
  * lazy val myApp = (projectMatrix in file("my-app"))
  *   .enablePlugins(NativeLibBundlePlugin, ZigCrossPlugin)
  *   .nativePlatform(scalaVersions = Seq("3.8.1"),
  *     settings = Seq(
  *       ZigCrossPlugin.autoImport.zigCrossTarget := Some(Platform.LinuxX86_64)
  *     ))
  * }}}
  *
  * Requires `zig` to be installed and available on PATH.
  */
object ZigCrossPlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = NativeLibBundlePlugin

  object autoImport {
    val zigCrossTarget = settingKey[Option[Platform]](
      "Target platform for zig cross-compilation. When set, overrides nativeConfig with zig wrappers."
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    zigCrossTarget := None,
    // When zigCrossTarget is set, configure zig wrappers and sync the bundle/extract platform
    NativeLibExtract.nativeLibPlatform := {
      zigCrossTarget.value.getOrElse(Platform.host)
    },
    NativeLibBundle.nativeBundlePlatform := {
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
