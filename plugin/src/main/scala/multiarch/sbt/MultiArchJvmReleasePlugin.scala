package multiarch.sbt

import sbt._

/** AutoPlugin for multi-architecture JVM release packaging.
  *
  * Provides both simple mode (launcher scripts requiring system JDK) and
  * distribution mode (JLink + Roast self-contained bundles for 6 desktop platforms).
  *
  * === Usage ===
  * {{{
  * // In build.sbt
  * lazy val myApp = (project in file("my-app"))
  *   .enablePlugins(MultiArchJvmReleasePlugin)
  *   .settings(
  *     Compile / mainClass := Some("com.example.Main"),
  *     releaseTargets := Map(
  *       Platform.LinuxX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25-linux_x64.tar.gz",
  *       Platform.LinuxAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25-linux_aarch64.tar.gz",
  *       Platform.MacosX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25-macosx_x64.tar.gz",
  *       Platform.MacosAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25-macosx_aarch64.tar.gz",
  *       Platform.WindowsX86_64  -> "https://cdn.azul.com/zulu/bin/zulu25-win_x64.zip",
  *       Platform.WindowsAarch64 -> "https://cdn.azul.com/zulu/bin/zulu25-win_aarch64.zip"
  *     )
  *   )
  * }}}
  *
  * Then run:
  * {{{
  * sbt "releasePlatform linux-x86_64"   // single platform
  * sbt releaseAll                        // all configured platforms
  * sbt releasePackage                    // simple mode (system JDK)
  * }}}
  */
object MultiArchJvmReleasePlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = plugins.JvmPlugin

  object autoImport {
    val releaseTargets          = JvmPackaging.releaseTargets
    val releaseAppName          = JvmPackaging.releaseAppName
    val releaseJlinkModules     = JvmPackaging.releaseJlinkModules
    val releaseRoastVersion     = JvmPackaging.releaseRoastVersion
    val releaseVmArgs           = JvmPackaging.releaseVmArgs
    val releaseUseZgc           = JvmPackaging.releaseUseZgc
    val releaseMacOsBundleId    = JvmPackaging.releaseMacOsBundleId
    val releaseMacOsIcon        = JvmPackaging.releaseMacOsIcon
    val releaseCacheDir         = JvmPackaging.releaseCacheDir
    val releaseNativeLibDirs    = JvmPackaging.releaseNativeLibDirs
    val releaseCrossNativeLibDir = JvmPackaging.releaseCrossNativeLibDir
    val releaseRunOnFirstThread = JvmPackaging.releaseRunOnFirstThread
    val releasePlatform         = JvmPackaging.releasePlatform
    val releaseAll              = JvmPackaging.releaseAll
    val releasePackage          = JvmPackaging.releasePackage
  }

  override def projectSettings: Seq[Setting[_]] =
    JvmPackaging.jvmSettings ++ JvmPackaging.distSettings
}
