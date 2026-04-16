package multiarch.sbt

import sbt._
import sbt.Keys._

/** Generic Android build plugin — not tied to any specific project.
  *
  * Provides Android SDK management (auto-download), APK build pipeline
  * (DEX → aapt2 link → sign → install), and automatic discovery of
  * `src/main/scala-android/` sources when the SDK is available.
  *
  * Build pipeline: `androidDex` → `androidPackage` → `androidSign` → `androidInstall`.
  *
  * Requires `AndroidManifest.xml` in `src/main/resources/` at task execution time.
  *
  * === Usage ===
  * {{{
  * lazy val app = (project in file("android-app"))
  *   .enablePlugins(AndroidPlugin)
  *   .settings(Compile / mainClass := Some("com.example.MainActivity"))
  * }}}
  *
  * Override the SDK download location (default: `<baseDirectory>/android-sdk`):
  * {{{
  * androidSdkCacheDir := (ThisBuild / baseDirectory).value / "android-sdk"
  * }}}
  */
object AndroidPlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = plugins.JvmPlugin

  object autoImport {
    val androidSdkCacheDir       = AndroidBuild.androidSdkCacheDir
    val androidSdkRoot           = AndroidBuild.androidSdkRoot
    val androidMinSdk            = AndroidBuild.androidMinSdk
    val androidTargetSdk         = AndroidBuild.androidTargetSdk
    val androidBuildToolsVersion = AndroidBuild.androidBuildToolsVersion
    val androidDex               = AndroidBuild.androidDex
    val androidPackage           = AndroidBuild.androidPackage
    val androidSign              = AndroidBuild.androidSign
    val androidInstall           = AndroidBuild.androidInstall
  }
  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    androidSdkCacheDir := baseDirectory.value / "android-sdk"
  ) ++ AndroidBuild.taskSettings ++ Seq(
    // Auto-discover scala-android/ source dir when the SDK is available
    Compile / unmanagedSourceDirectories ++= {
      val cacheDir     = androidSdkCacheDir.value
      val androidDir   = baseDirectory.value / "src" / "main" / "scala-android"
      val sdkAvailable = AndroidSdk.findSdkRoot(cacheDir).exists(r => AndroidSdk.androidJar(r).exists())
      if (sdkAvailable && androidDir.exists()) Seq(androidDir) else Seq.empty
    }
  )
}
