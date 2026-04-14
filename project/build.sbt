// Include core and plugin source in the meta-build so build.sbt can reference Platform.scala etc.
Compile / unmanagedSourceDirectories ++= Seq(
  baseDirectory.value / ".." / "core" / "src" / "main" / "scala",
  baseDirectory.value / ".." / "plugin" / "src" / "main" / "scala"
)
