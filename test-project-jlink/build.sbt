import multiarch.sbt.{ MultiArchJvmReleasePlugin, Platform }

ThisBuild / scalaVersion := "3.3.7"

// JDK download URLs for JLink distribution packaging (Azul Zulu 25)
val jdkUrls: Map[Platform, String] = Map(
  Platform.LinuxX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-linux_x64.tar.gz",
  Platform.LinuxAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-linux_aarch64.tar.gz",
  Platform.MacosX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-macosx_x64.tar.gz",
  Platform.MacosAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-macosx_aarch64.tar.gz",
  Platform.WindowsX86_64  -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-win_x64.zip",
  Platform.WindowsAarch64 -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-win_aarch64.zip"
)

lazy val app = (project in file("."))
  .enablePlugins(MultiArchJvmReleasePlugin)
  .settings(
    name := "test-jlink-app",
    Compile / mainClass := Some("testapp.Main"),
    MultiArchJvmReleasePlugin.autoImport.releaseTargets := jdkUrls
  )
