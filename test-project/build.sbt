import multiarch.sbt.{ MultiArchJvmReleasePlugin, NativeLibBundlePlugin, Platform }

ThisBuild / scalaVersion := "3.8.2"

// JDK download URLs for JLink distribution packaging (Azul Zulu 25)
val jdkUrls: Map[Platform, String] = Map(
  Platform.LinuxX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-linux_x64.tar.gz",
  Platform.LinuxAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-linux_aarch64.tar.gz",
  Platform.MacosX86_64    -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-macosx_x64.tar.gz",
  Platform.MacosAarch64   -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-macosx_aarch64.tar.gz",
  Platform.WindowsX86_64  -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-win_x64.zip",
  Platform.WindowsAarch64 -> "https://cdn.azul.com/zulu/bin/zulu25.32.21-ca-jdk25.0.2-win_aarch64.zip"
)

val pluginVersion = sys.props.getOrElse("plugin.version", "0.1.0-SNAPSHOT")

// ── JVM axis: test MultiArchJvmReleasePlugin ──────────────────────
lazy val testJVM = (project in file("jvm"))
  .enablePlugins(MultiArchJvmReleasePlugin)
  .settings(
    name := "test-app",
    Compile / mainClass := Some("testapp.Main"),
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "src" / "main" / "scala",
    MultiArchJvmReleasePlugin.autoImport.releaseTargets := jdkUrls,
    libraryDependencies += "com.softwaremill.sttp.client4" %% "core" % "4.0.19"
  )

// ── Native axis: test NativeLibBundlePlugin ───────────────────────
lazy val testNative = (project in file("native"))
  .enablePlugins(NativeLibBundlePlugin)
  .settings(
    name := "test-app-native",
    scalaVersion := "3.8.2",
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "src" / "main" / "scala",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %%% "core" % "4.0.19",
      // Single fat JAR: native-bundle.json manifest + all 6 platforms' .a files
      "com.kubuszok" % "scala-native-curl-provider" % pluginVersion
    )
  )
