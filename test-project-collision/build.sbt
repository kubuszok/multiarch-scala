import multiarch.sbt.NativeProviderPlugin

// Deliberate-collision integration fixture (natives plan §2.5).
//
// Two fixture provider JARs — built below from fixture-providers/{a,b}/ — both bundle
// linux-x86_64/libcollide.a. `discoverManifests` MUST fail with the prescriptive
// collision message; CI asserts the failure (red-path test).

lazy val app = (project in file("."))
  .enablePlugins(NativeProviderPlugin)
  .settings(
    name := "test-collision-app",
    scalaVersion := "3.8.3",
    // Build the two fixture provider JARs from fixture-providers/{a,b}/ and put them
    // on the compile classpath.
    Compile / unmanagedJars ++= {
      val jarDir = target.value / "fixture-jars"
      IO.createDirectory(jarDir)
      Seq("a", "b").map { suffix =>
        val sourceDir = baseDirectory.value / "fixture-providers" / suffix
        val jar       = jarDir / s"fixture-provider-$suffix.jar"
        IO.zip(Path.allSubpaths(sourceDir), jar, None)
        Attributed.blank(jar)
      }
    }
  )

// Stable entry point for CI regardless of the default project id.
addCommandAlias("checkCollisions", "app/discoverManifests")
