import multiarch.core.Platform

lazy val isCI = sys.env.get("CI").contains("true")
// Maven Central requires a Javadoc JAR; publish an empty one for all modules.
ThisBuild / Compile / doc / sources := Seq.empty

// Version from git tags: tagged commits get clean versions (e.g. "0.1.0"),
// untagged commits get SNAPSHOT versions (e.g. "0.1.0-SNAPSHOT").
// When a vX.Y.Z tag exists, git describe produces that version directly.
git.useGitDescribe       := true
git.uncommittedSignifier := Some("SNAPSHOT")
// Sonatype ignores isSnapshot setting and only looks at -SNAPSHOT suffix in version:
//   https://central.sonatype.org/publish/publish-maven/#performing-a-snapshot-deployment
// meanwhile sbt-git used to set up SNAPSHOT if there were uncommitted changes:
//   https://github.com/sbt/sbt-git/issues/164
// (now this suffix is empty by default) so we need to fix it manually.
git.gitUncommittedChanges := git.gitCurrentTags.value.isEmpty

// Used to publish snapshots to Maven Central.
val mavenCentralSnapshots = "Maven Central Snapshots" at "https://central.sonatype.com/repository/maven-snapshots"

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
    Developer("MateuszKubuszok", "Mateusz Kubuszok", "", url("https://github.com/MateuszKubuszok"))
  ),
  pomExtra := (
    <issueManagement>
      <system>GitHub issues</system>
      <url>https://github.com/kubuszok/multiarch-scala/issues</url>
    </issueManagement>
  ),
  publishTo := {
    if (isSnapshot.value) Some(mavenCentralSnapshots)
    else localStaging.value
  },
  publishMavenStyle := true,
  Test / publishArtifact := false,
  pomIncludeRepository := { _ =>
    false
  },
  versionScheme := Some("early-semver")
)

val noPublishSettings =
  Seq(publish / skip := true, publishArtifact := false)

// ── Root project ──────────────────────────────────────────────────────

lazy val root = project
  .in(file("."))
  .enablePlugins(GitVersioning, GitBranchPrompt)
  .settings(publishSettings *)
  .settings(noPublishSettings *)
  .aggregate(core, plugin, snProviderCurl)
  .settings(
    name := "sbt-multiarch-scala-root",
    // ci-release: snapshot on untagged push, release on tags
    commands += Command.command("ci-release") { state =>
      val extracted = Project.extract(state)
      val tags      = extracted.get(git.gitCurrentTags)
      // +core/publishSigned cross-publishes core for all Scala versions (2.12, 2.13, 3.3)
      // plugin/publishSigned publishes the sbt plugin (Scala 2.12 only)
      // snProviderCurl/publishSigned publishes the curl provider (no Scala version)
      if (tags.nonEmpty)
        "+core/publishSigned" :: "plugin/publishSigned" :: "snProviderCurl/publishSigned" :: "sonaRelease" :: state
      else
        "+core/publishSigned" :: "plugin/publishSigned" :: "snProviderCurl/publishSigned" :: state
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
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.0" % Test
  )

// ── Plugin module ─────────────────────────────────────────────────────

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .settings(publishSettings *)
  .settings(
    name := "sbt-multiarch-scala",
    // Scala Native plugin API available at compile time; consumers must provide it themselves
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.10" % Provided),
    addSbtPlugin("com.eed3si9n"     % "sbt-projectmatrix" % "0.11.0" % Provided)
  )

// ── Curl native library provider ──────────────────────────────────────

lazy val snProviderCurl = project
  .in(file("sn-provider-curl"))
  .settings(publishSettings *)
  .settings(
    name               := "sn-provider-curl",
    autoScalaLibrary   := false,
    crossPaths         := false,
    Compile / packageSrc / publishArtifact := false,
    // Bundle all 6 platforms' native libraries into the single JAR.
    // Layout: native/<platform-classifier>/lib<name>.a
    // The NativeExtract logic extracts only the current platform's files.
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
