// kubuszok plugin (bundles: sbt-git, sbt-pgp, sbt-projectmatrix, sbt-scala-native, and more)
// TODO: remove after sbt-kubuszok 0.2.5
resolvers += "Maven Central Snapshots" at "https://central.sonatype.com/repository/maven-snapshots"
addSbtPlugin("com.kubuszok" % "sbt-kubuszok" % "0.2.4-4-g52226ed-SNAPSHOT")

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
