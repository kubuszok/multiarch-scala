import kubuszok.sbt._
import kubuszok.sbt.KubuszokPlugin.autoImport._
import sbtwelcome.UsefulTask
import multiarch.core.Platform

// Maven Central requires a Javadoc JAR; publish an empty one for all modules.
ThisBuild / Compile / doc / sources := Seq.empty

val publishSettings = Seq(
  organization := "com.kubuszok",
  homepage := Some(url("https://github.com/kubuszok/multiarch-scala")),
  organizationHomepage := Some(url("https://kubuszok.com")),
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/kubuszok/multiarch-scala/"),
      "scm:git:git@github.com:kubuszok/multiarch-scala.git"
    )
  ),
  startYear := Some(2026),
  developers := List(
    Developer("MateuszKubuszok", "Mateusz Kubuszok", "", url("https://kubuszok.com"))
  ),
  pomExtra := (
    <issueManagement>
      <system>GitHub issues</system>
      <url>https://github.com/kubuszok/multiarch-scala/issues</url>
    </issueManagement>
  ),
  projectType := ProjectType.ScalaLibrary
)

val noPublishSettings =
  Seq(projectType := ProjectType.NonPublished)

// ── Root project ──────────────────────────────────────────────────────

lazy val root = project
  .in(file("."))
  .enablePlugins(KubuszokRootPlugin)
  .settings(publishSettings *)
  .settings(noPublishSettings *)
  .aggregate(core, plugin, snProviderCurl, `panama-api`, `panama-jdk`)
  .settings(
    name := "sbt-multiarch-scala-root",
    logo := s"sbt-multiarch-scala ${version.value}",
    usefulTasks := Seq(
      UsefulTask("compile", "Compile all modules").noAlias,
      UsefulTask("test", "Run all tests").noAlias,
      UsefulTask("publishLocal", "Publish all modules locally").noAlias,
      UsefulTask("ci-release", "Publish snapshot or release (based on git tags)").noAlias
    ),
    // Custom ci-release: per-project publishing (some cross-compile, some don't)
    commands += Command.command("ci-release") { state =>
      val extracted = Project.extract(state)
      val tags      = extracted.get(git.gitCurrentTags)
      if (tags.nonEmpty)
        "+core/publishSigned" :: "plugin/publishSigned" :: "snProviderCurl/publishSigned" ::
          "panama-api/publishSigned" :: "panama-jdk/publishSigned" :: "sonaRelease" :: state
      else
        "+core/publishSigned" :: "plugin/publishSigned" :: "snProviderCurl/publishSigned" ::
          "panama-api/publishSigned" :: "panama-jdk/publishSigned" :: state
    }
  )

// ── Core module (sbt-independent) ─────────────────────────────────────

lazy val core = project
  .in(file("core"))
  .settings(publishSettings *)
  .settings(
    name := "multiarch-core",
    crossScalaVersions := Seq("2.12.21", "2.13.18", "3.3.7"),
    scalaVersion := "2.12.21",
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test
  )

// ── Plugin module ─────────────────────────────────────────────────────

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .settings(publishSettings *)
  .settings(
    name := "sbt-multiarch-scala",
    projectType := ProjectType.JarOnly,
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12" % Provided),
    addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.11.0" % Provided)
  )

// ── Curl native library provider ──────────────────────────────────────

lazy val snProviderCurl = project
  .in(file("sn-provider-curl"))
  .settings(publishSettings *)
  .settings(
    name := "sn-provider-curl",
    autoScalaLibrary := false,
    crossPaths := false,
    Compile / packageSrc / publishArtifact := false,
    Compile / packageBin / mappings ++= {
      val nativesDir = baseDirectory.value / "natives"
      if (nativesDir.exists()) {
        Platform.desktop.flatMap { p =>
          val platDir = nativesDir / p.classifier
          if (platDir.exists())
            sbt.IO.listFiles(platDir).filter(_.isFile).map(f => f -> s"native/${p.classifier}/${f.getName}").toSeq
          else Seq.empty
        }
      } else Seq.empty
    }
  )

// ── Panama FFM abstraction (Scala 3 only) ────────────────────────────

lazy val `panama-api` = project
  .in(file("panama-api"))
  .settings(publishSettings *)
  .settings(
    name := "multiarch-panama-api",
    scalaVersion := "3.3.7",
    scalacOptions ++= Seq("-release", "17"),
    Compile / unmanagedSourceDirectories += baseDirectory.value / "src" / "main" / "scala-android",
    Compile / unmanagedJars ++= {
      val cacheDir = streams.value.cacheDirectory / "panama-port-deps"
      val log      = streams.value.log
      multiarch.sbt.AndroidDeps.resolvePanamaPort(cacheDir, log).map(Attributed.blank)
    }
  )

lazy val `panama-jdk` = project
  .in(file("panama-jdk"))
  .dependsOn(`panama-api`)
  .settings(publishSettings *)
  .settings(
    name := "multiarch-panama-jdk",
    scalaVersion := "3.3.7"
  )
