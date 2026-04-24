package multiarch.sbt

import sbt._
import sbt.Keys._

import java.nio.file.Files

import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** AutoPlugin that auto-discovers native provider manifests on the classpath and configures Scala Native's `nativeConfig` with the correct linker flags.
  *
  * Also extracts native libraries from platform-classifier JARs when present.
  *
  * ===Usage===
  * {{{
  * .enablePlugins(NativeProviderPlugin)
  * }}}
  *
  * The plugin will:
  *   1. Scan all classpath JARs and project resources for `sn-provider.json` manifests
  *   2. Extract native `.a` files from platform-classifier JARs
  *   3. Merge linker flags from all manifests for the target platform
  *   4. Auto-configure `nativeConfig` with `-L<libDir>` and merged flags
  */
object NativeProviderPlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = ScalaNativePlugin

  object autoImport {
    // Re-export NativeProviderSettings keys
    val nativeProviderPlatform = NativeProviderSettings.nativeProviderPlatform
    val nativeProviderLibDir   = NativeProviderSettings.nativeProviderLibDir
    val nativeProviderTypes    = NativeProviderSettings.nativeProviderTypes
    val discoverManifests      = NativeProviderSettings.discoverManifests
    val mergedLinkerFlags      = NativeProviderSettings.mergedLinkerFlags

    // Re-export NativeExtractSettings keys
    val nativeLibExtract   = NativeExtractSettings.nativeLibExtract
    val nativeLibDir       = NativeExtractSettings.nativeLibDir
    val nativeLibPlatform  = NativeExtractSettings.nativeLibPlatform
    val nativeLibSourceDir = NativeExtractSettings.nativeLibSourceDir
  }

  override def projectSettings: Seq[Setting[_]] =
    NativeProviderSettings.settings ++ NativeExtractSettings.settings ++ Seq(
      // Sync the provider platform with the extraction platform
      NativeProviderSettings.nativeProviderPlatform := NativeExtractSettings.nativeLibPlatform.value,
      // Point the provider lib dir at the extraction output (triggers extraction)
      NativeProviderSettings.nativeProviderLibDir := {
        val extractedDir = NativeExtractSettings.nativeLibExtract.value
        if (extractedDir.exists()) Some(extractedDir) else None
      },
      // Auto-wire nativeConfig with extracted lib dir + merged manifest flags + rpath
      nativeConfig := {
        val c        = nativeConfig.value
        val libDir   = NativeExtractSettings.nativeLibExtract.value
        val merged   = NativeProviderSettings.mergedLinkerFlags.value
        val platform = NativeExtractSettings.nativeLibPlatform.value

        val libDirFlag = if (libDir.exists()) Seq(s"-L${libDir.getAbsolutePath}") else Seq.empty
        val rpathFlags =
          if (!libDir.exists()) Seq.empty
          else if (platform.isMac) Seq("-rpath", libDir.getAbsolutePath, "-rpath", "@executable_path")
          else if (platform.isLinux) Seq(s"-Wl,-rpath,${libDir.getAbsolutePath}", "-Wl,-rpath,$$ORIGIN")
          else Seq.empty
        c.withLinkingOptions(c.linkingOptions ++ libDirFlag ++ merged ++ rpathFlags)
      },
      // Windows: copy DLLs next to the linked executable (no rpath on Windows).
      // The PE loader searches the executable's directory first, so placing DLLs
      // there is the equivalent of -rpath @executable_path on macOS.
      Compile / nativeLink := {
        val linked   = (Compile / nativeLink).value
        val platform = NativeExtractSettings.nativeLibPlatform.value
        if (platform.isWindows) {
          val libDir = NativeExtractSettings.nativeLibExtract.value
          val exeDir = linked.toPath.getParent
          val log    = streams.value.log
          if (libDir.exists()) {
            val dlls = libDir.listFiles().filter(_.getName.endsWith(".dll"))
            dlls.foreach { dll =>
              val dest = exeDir.resolve(dll.getName)
              if (!Files.exists(dest)) {
                Files.copy(dll.toPath, dest)
                log.info(s"[native-provider] Copied ${dll.getName} next to executable")
              }
            }
          }
        }
        linked
      }
    )
}
