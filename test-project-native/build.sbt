import multiarch.sbt.{ NativeProviderPlugin, Platform }

val pluginVersion = sys.props.getOrElse("plugin.version", "0.1.0-SNAPSHOT")

lazy val app = (project in file("."))
  .enablePlugins(NativeProviderPlugin)
  .settings(
    name := "test-native-app",
    scalaVersion := "3.8.3",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %%% "core" % "4.0.19",
      // Single fat JAR: sn-provider.json manifest + all 6 platforms' .a files
      "com.kubuszok" % "sn-provider-curl" % pluginVersion
    )
  )
