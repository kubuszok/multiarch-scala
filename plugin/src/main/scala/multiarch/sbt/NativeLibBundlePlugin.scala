package multiarch.sbt

import sbt._
import sbt.Keys._

import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** AutoPlugin that auto-discovers `native-bundle.json` manifests on the classpath
  * and configures Scala Native's `nativeConfig` with the correct linker flags.
  *
  * Also extracts native libraries from platform-classifier JARs when present.
  *
  * === Usage ===
  * {{{
  * // In build.sbt
  * lazy val myApp = (projectMatrix in file("my-app"))
  *   .nativePlatform(scalaVersions = Seq("3.8.1"),
  *     settings = Seq(NativeLibBundlePlugin.autoImport.nativeLibPlatform := Platform.host))
  *   .enablePlugins(NativeLibBundlePlugin)
  * }}}
  *
  * Or simply:
  * {{{
  * .enablePlugins(NativeLibBundlePlugin)
  * }}}
  *
  * The plugin will:
  *   1. Scan all classpath JARs and project resources for `native-bundle.json` manifests
  *   2. Extract native `.a` files from platform-classifier JARs
  *   3. Merge linker flags from all manifests for the target platform
  *   4. Auto-configure `nativeConfig` with `-L<libDir>` and merged flags
  */
object NativeLibBundlePlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = ScalaNativePlugin

  object autoImport {
    // Re-export NativeLibBundle keys
    val nativeBundlePlatform = NativeLibBundle.nativeBundlePlatform
    val nativeBundleLibDir   = NativeLibBundle.nativeBundleLibDir
    val discoverManifests    = NativeLibBundle.discoverManifests
    val mergedLinkerFlags    = NativeLibBundle.mergedLinkerFlags

    // Re-export NativeLibExtract keys
    val nativeLibExtract   = NativeLibExtract.nativeLibExtract
    val nativeLibDir       = NativeLibExtract.nativeLibDir
    val nativeLibPlatform  = NativeLibExtract.nativeLibPlatform
    val nativeLibSourceDir = NativeLibExtract.nativeLibSourceDir
  }

  override def projectSettings: Seq[Setting[_]] =
    NativeLibBundle.settings ++ NativeLibExtract.settings ++ Seq(
      // Sync the bundle platform with the extraction platform
      NativeLibBundle.nativeBundlePlatform := NativeLibExtract.nativeLibPlatform.value,
      // Point the bundle lib dir at the extraction output (triggers extraction)
      NativeLibBundle.nativeBundleLibDir := {
        // Trigger extraction so .a files are available for linking
        val extractedDir = NativeLibExtract.nativeLibExtract.value
        if (extractedDir.exists()) Some(extractedDir) else None
      },
      // Auto-wire nativeConfig with extracted lib dir + merged manifest flags + rpath
      nativeConfig := {
        val c        = nativeConfig.value
        // Trigger extraction to ensure .a files are present
        val libDir   = NativeLibExtract.nativeLibExtract.value
        val merged   = NativeLibBundle.mergedLinkerFlags.value
        val platform = NativeLibExtract.nativeLibPlatform.value

        val libDirFlag = if (libDir.exists()) Seq(s"-L${libDir.getAbsolutePath}") else Seq.empty
        // rpath so the binary can find shared libraries (.so/.dylib) at runtime
        // without requiring LD_LIBRARY_PATH or DYLD_LIBRARY_PATH
        val rpathFlags = if (!libDir.exists()) Seq.empty
          else if (platform.isMac) Seq("-rpath", libDir.getAbsolutePath, "-rpath", "@executable_path")
          else if (platform.isLinux) Seq(s"-Wl,-rpath,${libDir.getAbsolutePath}", "-Wl,-rpath,$$ORIGIN")
          else Seq.empty
        c.withEmbedResources(true)
          .withLinkingOptions(c.linkingOptions ++ libDirFlag ++ merged ++ rpathFlags)
      }
    )
}
