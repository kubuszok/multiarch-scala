package multiarch.core

class BundleCollisionSpec extends munit.FunSuite {

  private def v2Manifest(
    name:     String,
    artifact: String,
    version:  String,
    bundles:  Seq[String]
  ): ProviderManifest =
    ProviderManifest(
      schemaVersion = "0.2.0",
      providerName = name,
      configs = Seq(
        ProviderConfig("cfg", Map("linux-x86_64" -> PlatformProviderConfig(binary = None)))
      ),
      providerArtifact = Some(artifact),
      providerVersion = Some(version),
      bundles = bundles
    )

  private def v1Manifest(
    name:     String,
    binaries: Map[String, String],
    stub:     Boolean = false
  ): ProviderManifest =
    ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = name,
      configs = Seq(
        ProviderConfig(
          "cfg",
          binaries.map { case (classifier, binary) =>
            classifier -> PlatformProviderConfig(binary = Some(binary), stub = stub)
          }
        )
      )
    )

  private val sn  = ProviderType.ScalaNative
  private val pnm = ProviderType.Panama

  // ── detectBundleCollisions ──────────────────────────────────────────

  test("no collision: disjoint bundles across providers") {
    val a          = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/liba.so"))
    val b          = v2Manifest("b", "provider-b", "1.0.0", Seq("linux-x86_64/libb.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a, "a.jar"), (sn, b, "b.jar")))
    assertEquals(collisions, Seq.empty[NativeExtract.BundleCollision])
  }

  test("no collision: same file name on different platforms") {
    val a          = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val b          = v2Manifest("b", "provider-b", "1.0.0", Seq("linux-aarch64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a, "a.jar"), (sn, b, "b.jar")))
    assertEquals(collisions, Seq.empty[NativeExtract.BundleCollision])
  }

  test("v2 x v2 collision detected") {
    val a          = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val b          = v2Manifest("b", "provider-b", "2.0.0", Seq("linux-x86_64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a, "a.jar"), (sn, b, "b.jar")))
    assertEquals(collisions.size, 1)
    val c = collisions.head
    assertEquals(c.classifier, "linux-x86_64")
    assertEquals(c.fileName, "libfoo.so")
    assertEquals(
      c.owners,
      Seq(
        ("a", "a.jar", "native/provider-a/1.0.0/linux-x86_64/libfoo.so"),
        ("b", "b.jar", "native/provider-b/2.0.0/linux-x86_64/libfoo.so")
      )
    )
  }

  test("v1 x v2 collision detected via derived bundles, legacy resource path reported for v1") {
    val v1         = v1Manifest("old", Map("linux-x86_64" -> "libfoo.so"))
    val v2         = v2Manifest("new", "provider-new", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((pnm, v1, "old.jar"), (pnm, v2, "new.jar")))
    assertEquals(collisions.size, 1)
    val c = collisions.head
    assertEquals(
      c.owners,
      Seq(
        ("old", "old.jar", "native/linux-x86_64/libfoo.so"),
        ("new", "new.jar", "native/provider-new/1.0.0/linux-x86_64/libfoo.so")
      )
    )
  }

  test("same artifact+version seen twice is NOT a collision") {
    val a1         = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val a2         = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a1, "a.jar"), (sn, a2, "other-path/a.jar")))
    assertEquals(collisions, Seq.empty[NativeExtract.BundleCollision])
  }

  test("same artifact at DIFFERENT versions IS a collision") {
    val a1         = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val a2         = v2Manifest("a", "provider-a", "2.0.0", Seq("linux-x86_64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a1, "a-1.jar"), (sn, a2, "a-2.jar")))
    assertEquals(collisions.size, 1)
  }

  test("stub binary vs real binary collides") {
    val stub       = v1Manifest("stubby", Map("linux-x86_64" -> "libfoo.so"), stub = true)
    val real       = v2Manifest("really", "provider-real", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((pnm, stub, "stub.jar"), (pnm, real, "real.jar")))
    assertEquals(collisions.size, 1)
    assertEquals(collisions.head.fileName, "libfoo.so")
  }

  test("collision key spans provider types (JNI/Panama/SN share the native/ namespace)") {
    val a          = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val b          = v2Manifest("b", "provider-b", "1.0.0", Seq("linux-x86_64/libfoo.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a, "a.jar"), (pnm, b, "b.jar")))
    assertEquals(collisions.size, 1)
  }

  test("collisions are sorted by (classifier, fileName)") {
    val a          = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libz.so", "linux-aarch64/liby.so"))
    val b          = v2Manifest("b", "provider-b", "1.0.0", Seq("linux-x86_64/libz.so", "linux-aarch64/liby.so"))
    val collisions = NativeExtract.detectBundleCollisions(Seq((sn, a, "a.jar"), (sn, b, "b.jar")))
    assertEquals(collisions.map(c => (c.classifier, c.fileName)), Seq(("linux-aarch64", "liby.so"), ("linux-x86_64", "libz.so")))
  }

  // ── renderCollision — exact message template ────────────────────────

  test("renderCollision produces the exact prescriptive message") {
    val collision = NativeExtract.BundleCollision(
      classifier = "linux-x86_64",
      fileName = "libfoo.so",
      owners = Seq(
        ("a", "a.jar", "native/provider-a/1.0.0/linux-x86_64/libfoo.so"),
        ("b", "b.jar", "native/linux-x86_64/libfoo.so")
      )
    )
    val expected =
      "[native-provider] Native library collision for platform 'linux-x86_64':\n" +
        "  file 'libfoo.so' is bundled by 2 providers:\n" +
        "    - 'a' (a.jar) at native/provider-a/1.0.0/linux-x86_64/libfoo.so\n" +
        "    - 'b' (b.jar) at native/linux-x86_64/libfoo.so\n" +
        "  Classpath order would silently decide which library is extracted/loaded.\n" +
        "  Fix: depend on only one of these providers, or rename the library in one of them."
    assertEquals(NativeExtract.renderCollision(collision), expected)
  }

  // ── validateBundles ─────────────────────────────────────────────────

  private val validV2 = v2Manifest("a", "provider-a", "1.0.0", Seq("linux-x86_64/libfoo.so"))

  test("validateBundles: truthful JAR yields no violations") {
    val entries = Map("native/provider-a/1.0.0/linux-x86_64/libfoo.so" -> 100000L)
    assertEquals(NativeExtract.validateBundles(validV2, entries, 32768L), Seq.empty[String])
  }

  test("validateBundles: missing declared entry") {
    val violations = NativeExtract.validateBundles(validV2, Map.empty[String, Long], 32768L)
    assertEquals(violations.size, 1)
    assert(violations.head.startsWith("missing:"), violations.head)
    assert(violations.head.contains("native/provider-a/1.0.0/linux-x86_64/libfoo.so"), violations.head)
  }

  test("validateBundles: undersized entry") {
    val entries    = Map("native/provider-a/1.0.0/linux-x86_64/libfoo.so" -> 128L)
    val violations = NativeExtract.validateBundles(validV2, entries, 32768L)
    assertEquals(violations.size, 1)
    assert(violations.head.startsWith("undersized:"), violations.head)
    assert(violations.head.contains("128 bytes"), violations.head)
  }

  test("validateBundles: undeclared native lib entry in JAR") {
    val entries = Map(
      "native/provider-a/1.0.0/linux-x86_64/libfoo.so" -> 100000L,
      "native/provider-a/1.0.0/linux-x86_64/libsneaky.so" -> 100000L
    )
    val violations = NativeExtract.validateBundles(validV2, entries, 32768L)
    assertEquals(violations.size, 1)
    assert(violations.head.startsWith("undeclared:"), violations.head)
    assert(violations.head.contains("libsneaky.so"), violations.head)
  }

  test("validateBundles: non-library and non-native entries are ignored") {
    val entries = Map(
      "native/provider-a/1.0.0/linux-x86_64/libfoo.so" -> 100000L,
      "native/provider-a/1.0.0/linux-x86_64/README.md" -> 10L,
      "pnm-provider.json" -> 500L,
      "META-INF/MANIFEST.MF" -> 60L
    )
    assertEquals(NativeExtract.validateBundles(validV2, entries, 32768L), Seq.empty[String])
  }

  test("validateBundles: v1 manifest checks legacy paths via effectiveBundles") {
    val v1      = v1Manifest("old", Map("linux-x86_64" -> "libfoo.so"))
    val ok      = Map("native/linux-x86_64/libfoo.so" -> 100000L)
    val missing = Map("native/linux-x86_64/libother.so" -> 100000L)
    assertEquals(NativeExtract.validateBundles(v1, ok, 32768L), Seq.empty[String])
    val violations = NativeExtract.validateBundles(v1, missing, 32768L)
    assertEquals(violations.size, 2) // declared libfoo.so missing + undeclared libother.so
  }
}
