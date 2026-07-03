package multiarch.core

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

/** Tests for the [[NativeLibLoader]] resolution pipeline over fixture class loaders (§3.2 of the natives plan).
  *
  * Fixtures are plain directories put on a `URLClassLoader` (directory classpath entries serve resources just like JARs).
  */
class NativeLibLoaderSpec extends munit.FunSuite {

  /** Host classifier as the loader computes it (tests run on a real desktop OS). */
  private val classifier = Platform.host.classifier

  /** The platform-mapped file name for a logical lib name, mirroring the loader's mapping. */
  private def mapped(libName: String): String = {
    val osName = System.getProperty("os.name", "").toLowerCase
    if (osName.contains("win")) s"$libName.dll"
    else System.mapLibraryName(libName)
  }

  private def write(root: Path, relative: String, content: String): Unit = {
    val target = root.resolve(relative)
    Files.createDirectories(target.getParent)
    Files.write(target, content.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def v2ManifestJson(name: String, artifact: String, version: String, bundles: Seq[String]): String = {
    val entries = bundles.map(b => "\"" + b + "\"").mkString("[", ", ", "]")
    s"""{
       |  "provider-schema-version": "0.2.0",
       |  "provider-name": "$name",
       |  "provider-artifact": "$artifact",
       |  "provider-version": "$version",
       |  "bundles": $entries,
       |  "configs": [
       |    { "config-name": "$name", "$classifier": { "binary": "${bundles.head.split('/').last}", "flags-groups": [] } }
       |  ]
       |}""".stripMargin
  }

  /** Create N fixture roots, each populated by `populate(root, i)`, and run `body` with an isolated class loader over all of them. */
  private def withFixtureClassLoader(dirCount: Int)(populate: (Path, Int) => Unit)(body: ClassLoader => Unit): Unit = {
    val roots = (0 until dirCount).map(i => Files.createTempDirectory(s"native-loader-spec-$i-"))
    try {
      roots.zipWithIndex.foreach { case (root, i) => populate(root, i) }
      val loader = new URLClassLoader(roots.map(_.toUri.toURL).toArray, null)
      try body(loader)
      finally loader.close()
    } finally roots.foreach(deleteRecursively)
  }

  private def deleteRecursively(path: Path): Unit = {
    val file = path.toFile
    if (file.isDirectory) {
      val children = file.listFiles()
      if (children != null) children.foreach(f => deleteRecursively(f.toPath))
    }
    file.delete()
    ()
  }

  /** munit's `intercept` only catches NonFatal throwables; UnsatisfiedLinkError is a fatal LinkageError, so catch it manually. */
  private def interceptLinkError(body: => Any): UnsatisfiedLinkError = {
    val caught =
      try {
        body
        None
      } catch {
        case e: UnsatisfiedLinkError => Some(e)
      }
    caught.getOrElse(fail("expected UnsatisfiedLinkError but none was thrown"))
  }

  private def withLibraryPath(value: String)(body: => Unit): Unit = {
    val old = System.getProperty("java.library.path")
    System.setProperty("java.library.path", value)
    try body
    finally {
      if (old == null) System.clearProperty("java.library.path")
      else System.setProperty("java.library.path", old)
      ()
    }
  }

  // Unique lib names per test: the loader dedupes legacy-layout warnings and extracts to a shared
  // temp dir by mapped name, so reusing names across tests would couple them.

  // ── v2 layout ───────────────────────────────────────────────────────

  test("v2-only jar resolves via the manifest index") {
    val lib = "v2only"
    withFixtureClassLoader(1) { (root, _) =>
      write(root, "pnm-provider.json", v2ManifestJson("prov-a", "provider-a", "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
      write(root, s"native/provider-a/1.0.0/$classifier/${mapped(lib)}", "V2 CONTENT")
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "V2 CONTENT")
      }
    }
  }

  test("legacy-only jar resolves via the flat fallback") {
    val lib = "legacyonly"
    withFixtureClassLoader(1) { (root, _) =>
      write(root, s"native/$classifier/${mapped(lib)}", "LEGACY CONTENT")
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "LEGACY CONTENT")
      }
    }
  }

  test("v2 layout wins over legacy layout when both are present") {
    val lib = "v2wins"
    withFixtureClassLoader(1) { (root, _) =>
      write(root, "pnm-provider.json", v2ManifestJson("prov-a", "provider-a", "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
      write(root, s"native/provider-a/1.0.0/$classifier/${mapped(lib)}", "V2 CONTENT")
      write(root, s"native/$classifier/${mapped(lib)}", "LEGACY CONTENT")
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "V2 CONTENT")
      }
    }
  }

  test("two distinct v2 owners of the same file throw the collision error") {
    val lib = "collide"
    withFixtureClassLoader(2) { (root, i) =>
      val artifact = s"provider-${('a' + i).toChar}"
      write(root, "pnm-provider.json", v2ManifestJson(s"prov-$i", artifact, "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
      write(root, s"native/$artifact/1.0.0/$classifier/${mapped(lib)}", s"CONTENT $i")
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val error = interceptLinkError {
          NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        }
        assert(error.getMessage.contains(s"Native library collision for platform '$classifier'"), error.getMessage)
        assert(error.getMessage.contains(s"file '${mapped(lib)}' is bundled by 2 providers"), error.getMessage)
      }
    }
  }

  test("same artifact+version seen twice on the classpath is NOT a runtime collision") {
    val lib = "duplicated"
    withFixtureClassLoader(2) { (root, _) =>
      write(root, "pnm-provider.json", v2ManifestJson("prov-a", "provider-a", "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
      write(root, s"native/provider-a/1.0.0/$classifier/${mapped(lib)}", "SAME CONTENT")
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "SAME CONTENT")
      }
    }
  }

  test("lying v2 manifest (declared resource missing) falls through to the legacy layout") {
    val lib = "liar"
    withFixtureClassLoader(1) { (root, _) =>
      write(root, "pnm-provider.json", v2ManifestJson("prov-a", "provider-a", "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
      // no file at native/provider-a/1.0.0/... — only the legacy copy exists
      write(root, s"native/$classifier/${mapped(lib)}", "LEGACY CONTENT")
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "LEGACY CONTENT")
      }
    }
  }

  // ── java.library.path precedence (§0.3 covenant: stays FIRST) ───────

  test("java.library.path wins over both v2 and legacy classpath layouts") {
    val lib     = "devoverride"
    val overDir = Files.createTempDirectory("native-loader-libpath-")
    try {
      write(overDir, mapped(lib), "LIBPATH CONTENT")
      withFixtureClassLoader(1) { (root, _) =>
        write(root, "pnm-provider.json", v2ManifestJson("prov-a", "provider-a", "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
        write(root, s"native/provider-a/1.0.0/$classifier/${mapped(lib)}", "V2 CONTENT")
        write(root, s"native/$classifier/${mapped(lib)}", "LEGACY CONTENT")
      } { cl =>
        withLibraryPath(overDir.toString) {
          val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
          assertEquals(path, overDir.resolve(mapped(lib)))
          assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "LIBPATH CONTENT")
        }
      }
    } finally deleteRecursively(overDir)
  }

  test("java.library.path supports multiple directories (File.pathSeparator-separated)") {
    val lib  = "multidir"
    val dirA = Files.createTempDirectory("native-loader-multidir-a-")
    val dirB = Files.createTempDirectory("native-loader-multidir-b-")
    try {
      write(dirB, mapped(lib), "DIR B CONTENT") // only in the SECOND directory
      withFixtureClassLoader(1)((_, _) => ()) { cl =>
        withLibraryPath(dirA.toString + File.pathSeparator + dirB.toString) {
          val path = NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
          assertEquals(path, dirB.resolve(mapped(lib)))
        }
      }
    } finally {
      deleteRecursively(dirA)
      deleteRecursively(dirB)
    }
  }

  // ── Diagnostics ─────────────────────────────────────────────────────

  test("miss diagnostic lists java.library.path, v2 candidates, legacy path, and the lying-manifest note") {
    val lib = "nowhere"
    withFixtureClassLoader(1) { (root, _) =>
      // manifest declares the lib but bundles nothing on disk, and no legacy copy either
      write(root, "pnm-provider.json", v2ManifestJson("prov-a", "provider-a", "1.0.0", Seq(s"$classifier/${mapped(lib)}")))
    } { cl =>
      withLibraryPath("/nonexistent-dir-for-test") {
        val error = interceptLinkError {
          NativeLibLoader.resolve(lib, classifier, cl, NativeLibLoader.buildV2Index(cl))
        }
        val msg = error.getMessage
        assert(msg.contains("Searched java.library.path: /nonexistent-dir-for-test"), msg)
        assert(msg.contains(s"native/provider-a/1.0.0/$classifier/${mapped(lib)} (manifest v2)"), msg)
        assert(msg.contains(s"native/$classifier/${mapped(lib)} (legacy layout)"), msg)
        assert(msg.contains("declares native/provider-a/1.0.0/"), msg)
        assert(msg.contains("but the resource is missing"), msg)
      }
    }
  }

  test("v1 manifests without artifact+version do not enter the v2 index") {
    withFixtureClassLoader(1) { (root, _) =>
      write(
        root,
        "pnm-provider.json",
        s"""{
           |  "provider-schema-version": "0.1.0",
           |  "provider-name": "oldschool",
           |  "configs": [ { "config-name": "old", "$classifier": { "binary": "libold.so", "flags-groups": [] } } ]
           |}""".stripMargin
      )
    } { cl =>
      assertEquals(NativeLibLoader.buildV2Index(cl), Map.empty[(String, String), Seq[NativeLibLoader.V2Owner]])
    }
  }
}
