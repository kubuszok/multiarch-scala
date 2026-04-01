// git
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")
// publishing
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
// Scala Native (needed at meta-build level for plugin compilation against ScalaNativePlugin API)
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.10")
// sbt-projectmatrix (needed at meta-build level for plugin compilation)
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.11.0")

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
