// Include core and plugin source in the meta-build so build.sbt can reference Platform.scala etc.
// The meta-build always runs on sbt 1.x / Scala 2.12, so include the plugin's sbt-1.x axis
// sources (scala-2.12) alongside the shared sources for the per-axis Compat shim.
Compile / unmanagedSourceDirectories ++= Seq(
  baseDirectory.value / ".." / "core" / "src" / "main" / "scala",
  baseDirectory.value / ".." / "plugin" / "src" / "main" / "scala",
  baseDirectory.value / ".." / "plugin" / "src" / "main" / "scala-2.12"
)
