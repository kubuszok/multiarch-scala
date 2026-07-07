import kubuszok.sbt._
import kubuszok.sbt.KubuszokPlugin.autoImport._
import sbtwelcome.UsefulTask
import multiarch.core.Platform
import multiarch.sbt.MultiArchResourcesPlugin
import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._

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

// ── Binary compatibility (MiMa) ───────────────────────────────────────
//
// sbt-mima is bundled in sbt-kubuszok. This branch establishes the 0.4.0 binary-compatibility
// baseline: MiMa checks every published library against its last release (0.3.0) so the 0.4.x line
// gets accidental-break protection going forward, while the FOUR intentional 0.3.x -> 0.4.0 breaks
// from the collision-hardening plan (docs/plans/2026-07-native-flags-and-collisions.md) are
// explicitly whitelisted below. Bump `mimaPreviousVersion` on every release.
val mimaPreviousVersion = "0.3.0"

// Modules with a real 0.3.0 baseline to check against. The plugin is deliberately excluded (its
// baseline is 0.4.0, not 0.3.0 — see the comment on its mimaPreviousArtifacts below).
val mimaCheckedModules =
  Set("multiarch-core", "multiarch-resources", "multiarch-panama-api", "multiarch-panama-jdk", "sn-provider-curl")

val mimaSettings = Seq(
  mimaPreviousArtifacts := {
    moduleName.value match {
      case m if mimaCheckedModules.contains(m) =>
        // `.cross(crossVersion.value)` applies the project's own platform-aware cross-version so each
        // artifact is compared against its matching previous one (`_3`, `_2.13`, `_sjs1_3`,
        // `_native0.5_3`, or no suffix for the crossPaths:=false curl provider).
        Set((organization.value % moduleName.value % mimaPreviousVersion).cross(crossVersion.value))
      case "sbt-multiarch-scala" =>
        // issue #30 / plan §2.2: the sbt plugin's binary baseline is 0.4.0, NOT 0.3.0.
        //   (a) the sbt-2.0 axis (sbt-multiarch-scala_3_2.0) is brand new in 0.4.0 — no 0.3.0
        //       artifact exists to compare against;
        //   (b) MiMa on sbt plugins is impractical — the sbtPluginExtra cross-version and the fact
        //       that TaskKey type parameters erase away make the report unreliable;
        //   (c) the two intentional 0.4.0 plugin breaks (the `discoverManifests` TaskKey TYPE change
        //       and the new hard-fail collision gate inside existing tasks) are documented breaks.
        // So the first real plugin MiMa baseline is 0.4.0 itself, which protects 0.4.1+.
        Set.empty
      case _ => Set.empty // non-published / aggregate modules
    }
  },
  mimaFailOnNoPrevious := mimaCheckedModules.contains(moduleName.value),
  // The FOUR intentional 0.3.x -> 0.4.0 break surfaces. Each exclusion is a documented 0.4.0 break;
  // 0.4.0 is the declared compatibility break point for the collision-hardening work.
  mimaBinaryIssueFilters ++= Seq[ProblemFilter](
    // (i) ProviderManifest gained three v2 fields (providerArtifact, providerVersion, bundles) with
    //     defaults. Adding case-class fields necessarily changes the primary constructor, apply,
    //     copy, and unapply signatures. Intentional 0.4.0 break (plan §2.1: "if the kubuszok MiMa
    //     gate rejects the case-class change, this is expected for a 0.3.x -> 0.4.0 minor").
    exclude[DirectMissingMethodProblem]("multiarch.core.ProviderManifest.this"),
    exclude[DirectMissingMethodProblem]("multiarch.core.ProviderManifest.copy"),
    exclude[DirectMissingMethodProblem]("multiarch.core.ProviderManifest.apply"),
    // companion's abstract-function arity changed (AbstractFunction3 -> AbstractFunction6) with the field count
    exclude[MissingTypesProblem]("multiarch.core.ProviderManifest$"),
    exclude[IncompatibleResultTypeProblem]("multiarch.core.ProviderManifest.unapply"),
    exclude[IncompatibleSignatureProblem]("multiarch.core.ProviderManifest.unapply"),
    exclude[DirectMissingMethodProblem]("multiarch.core.ProviderManifest$.apply"),
    exclude[DirectMissingMethodProblem]("multiarch.core.ProviderManifest$.copy"),
    exclude[IncompatibleSignatureProblem]("multiarch.core.ProviderManifest#*.copy"),
    // (ii) NativeExtract.discoverManifests return type changed from
    //      Seq[(ProviderType, ProviderManifest)] to Seq[(ProviderType, ProviderManifest, String)] so
    //      collision detection can track each manifest's source (plan §2.2). The sbt plugin re-exports
    //      the matching TaskKey type change; verified consumers do not use the value directly.
    exclude[IncompatibleResultTypeProblem]("multiarch.core.NativeExtract.discoverManifests"),
    exclude[IncompatibleSignatureProblem]("multiarch.core.NativeExtract.discoverManifests"),
    exclude[DirectMissingMethodProblem]("multiarch.core.NativeExtract.discoverManifests")
    // (iii) the hard-fail collision gate lives inside existing sbt tasks (plugin), a BEHAVIORAL change
    //       MiMa cannot observe, and the plugin is not MiMa-checked (see above) — no filter needed.
    // (iv) the v2 versioned resource layout (native/<artifact>/<version>/<classifier>/<file>) is a
    //       RESOURCE-format change, not a binary API change — MiMa does not see it — no filter needed.
  )
)

// ── Root project ──────────────────────────────────────────────────────

lazy val root = project
  .in(file("."))
  .enablePlugins(KubuszokRootPlugin)
  .settings(publishSettings *)
  .settings(noPublishSettings *)
  // Aggregate `mimaReportBinaryIssues` runs on the root too; mimaSettings routes the root (and the
  // plugin) through the mimaFailOnNoPrevious:=false / empty-previous graceful path instead of erroring.
  .settings(mimaSettings *)
  .aggregate(core)
  .aggregate(multiarchResources.projectRefs *)
  .aggregate(plugin, snProviderCurl, `panama-api`, `panama-jdk`)
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
      // multiarch-resources is a projectMatrix: each JVM/JS/Native × Scala-version row is its own
      // single-Scala-version project, so publish each row directly (no `+` cross-build needed).
      val resourceRefs = multiarchResources.projectRefs.map {
        case LocalProject(id) => s"$id/publishSigned"
        case ref              => s"${ref}/publishSigned"
      }
      val base =
        "+core/publishSigned" :: (resourceRefs.toList ++ ("+plugin/publishSigned" ::
          "snProviderCurl/publishSigned" :: "panama-api/publishSigned" :: "panama-jdk/publishSigned" :: Nil))
      if (tags.nonEmpty) base ::: ("sonaRelease" :: state)
      else base ::: state
    }
  )

// ── Core module (sbt-independent) ─────────────────────────────────────

lazy val core = project
  .in(file("core"))
  .settings(publishSettings *)
  .settings(mimaSettings *)
  .settings(
    name := "multiarch-core",
    // Future module-path adopters can scope --enable-native-access to this module (plan §4.3).
    Compile / packageBin / packageOptions += Package.ManifestAttributes("Automatic-Module-Name" -> "multiarch.core"),
    crossScalaVersions := Seq("2.12.21", "2.13.18", "3.3.8"),
    scalaVersion := "2.12.21",
    // JDK 26+ future-proofing: 3.3.8 (a JVM-only target here) enables the new
    // lazy-vals encoding. Guarded to 3.3.8 only; never on 2.13 or other versions.
    // -Yfuture-lazy-vals requires an explicit -java-output-version >= 9.
    scalacOptions ++= {
      if (scalaVersion.value == "3.3.8") Seq("-Yfuture-lazy-vals", "-java-output-version", "17")
      else Seq.empty
    },
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.3" % Test
  )

// ── Shared cross-platform resource-access mechanism ───────────────────
//
// `multiarch.resources.PlatformResources` gives JVM/JS/Native a uniform way to read classpath
// resources. JVM/Native delegate to `Class.getResourceAsStream`; Scala.js reads a build-time
// embedded map (generated by `multiarch.sbt.EmbeddedResourcesGen`) first, falling back to Node fs.
//
// The JS axis here embeds this module's OWN test resources so the Scala.js test path resolves a
// real embedded resource (proving the build-time generator end to end). Downstream consumers use
// `MultiArchResourcesPlugin.embeddedResourcesSettings(...)` to embed their own resources.
lazy val resourcesScalaVersions = Seq("2.13.18", "3.3.8")

lazy val multiarchResources = (projectMatrix in file("multiarch-resources"))
  // JVM axis. JDK 26+ future-proofing: 3.3.8 enables the new lazy-vals encoding;
  // -Yfuture-lazy-vals requires -java-output-version >= 9. JVM-only (invalid on JS/Native).
  .jvmPlatform(
    scalaVersions = resourcesScalaVersions,
    settings = Seq(
      scalacOptions ++= {
        if (scalaVersion.value == "3.3.8") Seq("-Yfuture-lazy-vals", "-java-output-version", "17")
        else Seq.empty
      }
    )
  )
  // Scala.js axis. Embed the module's test resources so the JS test path resolves a real embedded
  // resource (proving the build-time generator end to end).
  .jsPlatform(
    scalaVersions = resourcesScalaVersions,
    settings = MultiArchResourcesPlugin.embeddedResourcesSettings(
      resourceDir = Def.setting((Test / resourceDirectory).value),
      objectName = "multiarch.resources.TestEmbeddedResources"
    )
  )
  // Scala Native axis. Embed resources so Class.getResourceAsStream works at runtime.
  .nativePlatform(
    scalaVersions = resourcesScalaVersions,
    settings = Seq(
      scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig ~= {
        _.withEmbedResources(true).withMultithreading(false)
      }
    )
  )
  .settings(publishSettings *)
  .settings(mimaSettings *)
  .settings(
    name := "multiarch-resources",
    libraryDependencies += "org.scalameta" %%% "munit" % "1.3.3" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

// ── Plugin module ─────────────────────────────────────────────────────

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .settings(publishSettings *)
  .settings(mimaSettings *)
  .settings(
    name := "sbt-multiarch-scala",
    projectType := ProjectType.JarOnly,
    // Cross-build for sbt 1.x (Scala 2.12) and sbt 2.0 (Scala 3).
    // sbt 2.0.0 is itself built against Scala 3.8.4, so the plugin must compile
    // with 3.8.4 to read sbt's TASTy (3.7.2 cannot read 3.8.4 TASTy).
    crossScalaVersions := Seq("3.8.4", "2.12.21"),
    scalaVersion := "2.12.21",
    (pluginCrossBuild / sbtVersion) := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.12.12"
        case _      => "2.0.0"
      }
    },
    // sbt-scala-native HAS a sbt-2.0 (_sbt2_3) build, so keep it on both axes.
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12" % Provided),
    // sbt-projectmatrix was merged INTO sbt 2.0 (no _sbt2_3 artifact), so add it
    // only on the sbt-1.x (Scala 2.12) axis.
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.12") {
        val sbtV   = (pluginCrossBuild / sbtBinaryVersion).value
        val scalaV = (update / scalaBinaryVersion).value
        Seq(Defaults.sbtPluginExtra("com.eed3si9n" % "sbt-projectmatrix" % "0.11.0", sbtV, scalaV) % Provided)
      } else Seq.empty
    },
    // munit for the EmbeddedResourcesGen unit test. munit 1.0.x has a Scala 2.12 build; the
    // sbt-2.0 (Scala 3.8.4) axis has no compatible munit, so gate the dep + test source on 2.12.
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.12") Seq("org.scalameta" %% "munit" % "1.0.4" % Test)
      else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      if (scalaBinaryVersion.value == "2.12") Seq((Test / sourceDirectory).value / "scala")
      else Seq.empty
    },
    Test / sources := {
      val all = (Test / sources).value
      if (scalaBinaryVersion.value == "2.12") all else Seq.empty
    },
    testFrameworks += new TestFramework("munit.Framework")
  )

// ── Curl native library provider ──────────────────────────────────────

lazy val snProviderCurl = project
  .in(file("sn-provider-curl"))
  .settings(publishSettings *)
  .settings(mimaSettings *)
  .settings(
    name := "sn-provider-curl",
    autoScalaLibrary := false,
    crossPaths := false,
    Compile / packageSrc / publishArtifact := false,
    // Versioned v2 resource layout: native/<artifact>/<version>/<classifier>/<file>.
    Compile / packageBin / mappings ++= {
      val nativesDir = baseDirectory.value / "natives"
      val artifact   = moduleName.value
      val ver        = version.value
      if (nativesDir.exists()) {
        Platform.desktop.flatMap { p =>
          val platDir = nativesDir / p.classifier
          if (platDir.exists())
            sbt.IO.listFiles(platDir).filter(_.isFile).map(f => f -> s"native/$artifact/$ver/${p.classifier}/${f.getName}").toSeq
          else Seq.empty
        }
      } else Seq.empty
    },
    // Manifest v2 generation: enrich the static template with artifact, version, and the
    // bundles list computed from the SAME natives/<classifier> listing that feeds
    // packageBin/mappings (single source of truth — cannot drift).
    Compile / resourceGenerators += Def.task {
      val log        = streams.value.log
      val template   = sbt.IO.read(baseDirectory.value / "manifest-template.json")
      val nativesDir = baseDirectory.value / "natives"
      val listed: Seq[String] = Platform.desktop.flatMap { p =>
        val platDir = nativesDir / p.classifier
        if (platDir.exists())
          sbt.IO.listFiles(platDir).filter(_.isFile).map(f => s"${p.classifier}/${f.getName}").toSeq
        else Seq.empty
      }
      val bundles =
        if (listed.nonEmpty) listed.sorted
        else {
          log.warn(
            "[sn-provider-curl] natives/ is empty — emitting the template's declared binaries as bundles " +
              "(run sn-provider-curl/scripts/download-curl.sh for the real thing)"
          )
          multiarch.core.ProviderManifestCodec.parse(template).effectiveBundles.sorted
        }
      val out = (Compile / resourceManaged).value / "sn-provider.json"
      sbt.IO.write(out, multiarch.core.ProviderManifestCodec.enrich(template, moduleName.value, version.value, bundles))
      Seq(out)
    }.taskValue
  )

// ── Panama FFM abstraction (Scala 3 only) ────────────────────────────

lazy val `panama-api` = project
  .in(file("panama-api"))
  .settings(publishSettings *)
  .settings(mimaSettings *)
  .settings(
    name := "multiarch-panama-api",
    // Future module-path adopters can scope --enable-native-access to this module (plan §4.3).
    Compile / packageBin / packageOptions += Package.ManifestAttributes("Automatic-Module-Name" -> "multiarch.panama.api"),
    scalaVersion := "3.3.8",
    scalacOptions ++= Seq("-release", "17"),
    // JDK 26+ future-proofing: enable the new lazy-vals encoding on 3.3.8 (JVM-only).
    scalacOptions ++= {
      if (scalaVersion.value == "3.3.8") Seq("-Yfuture-lazy-vals") else Seq.empty
    },
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
  .settings(mimaSettings *)
  .settings(
    name := "multiarch-panama-jdk",
    // Future module-path adopters can scope --enable-native-access to this module (plan §4.3).
    Compile / packageBin / packageOptions += Package.ManifestAttributes("Automatic-Module-Name" -> "multiarch.panama.jdk"),
    scalaVersion := "3.3.8",
    // JDK 26+ future-proofing: enable the new lazy-vals encoding on 3.3.8 (JVM-only).
    // -Yfuture-lazy-vals requires an explicit -java-output-version >= 9.
    scalacOptions ++= {
      if (scalaVersion.value == "3.3.8") Seq("-Yfuture-lazy-vals", "-java-output-version", "17")
      else Seq.empty
    }
  )
