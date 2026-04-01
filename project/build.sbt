// Include plugin source in the meta-build so build.sbt can reference Platform.scala etc.
Compile / unmanagedSourceDirectories +=
  baseDirectory.value / ".." / "plugin" / "src" / "main" / "scala"
