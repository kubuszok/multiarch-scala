import multiarch.sbt.Platform

lazy val isCI = sys.env.get("CI").contains("true")
ThisBuild / packageDoc / publishArtifact := false

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
// I don't want any 0.1.0 crap, every commit that is not tag, gets last-tag-SHA-SNAPSHOT version like god intended.
//git.formattedShaVersion  := git.gitHeadCommit.value.map(_ => s"${git.baseVersion.value}-SNAPSHOT")

// Used to publish snapshots to Maven Central.
val mavenCentralSnapshots = "Maven Central Snapshots" at "https://central.sonatype.com/repository/maven-snapshots"

val publishSettings = Seq(
  organization := "com.kubuszok",
  homepage := Some(url("https://github.com/kubuszok/sbt-multi-arch-release")),
  organizationHomepage := Some(url("https://kubuszok.com")),
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/kubuszok/sbt-multi-arch-release/"),
      "scm:git:git@github.com:kubuszok/sbt-multi-arch-release.git"
    )
  ),
  startYear := Some(2026),
  developers := List(
    Developer("MateuszKubuszok", "Mateusz Kubuszok", "", url("https://github.com/MateuszKubuszok"))
  ),
  pomExtra := (
    <issueManagement>
      <system>GitHub issues</system>
      <url>https://github.com/kubuszok/sbt-multi-arch-release/issues</url>
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
  .aggregate(plugin, scalaNativeCurlProvider)
  .settings(
    name := "sbt-multi-arch-release-root",
    // ci-release: snapshot on untagged push, release on tags
    commands += Command.command("ci-release") { state =>
      val extracted = Project.extract(state)
      val tags      = extracted.get(git.gitCurrentTags)
      if (tags.nonEmpty) "publishSigned" :: "sonaRelease" :: state
      else "publishSigned" :: state
    }
  )

// ── Plugin module ─────────────────────────────────────────────────────

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(publishSettings *)
  .settings(
    name := "sbt-multi-arch-release",
    // Scala Native plugin API available at compile time; consumers must provide it themselves
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.10" % Provided),
    addSbtPlugin("com.eed3si9n"     % "sbt-projectmatrix" % "0.11.0" % Provided)
  )

// ── Curl native library provider ──────────────────────────────────────

lazy val scalaNativeCurlProvider = project
  .in(file("scala-native-curl-provider"))
  .settings(publishSettings *)
  .settings(
    name               := "scala-native-curl-provider",
    autoScalaLibrary   := false,
    crossPaths         := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    // Bundle all 6 platforms' native libraries into the single JAR.
    // Layout: native/<platform-classifier>/lib<name>.a
    // The NativeLibExtract plugin extracts only the current platform's files.
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
