package multiarch.core

import java.io.File
import java.nio.file.Files

class NativeExtractSpec extends munit.FunSuite {

  private val testLogger = new NativeExtract.Logger {
    def info(msg: String): Unit = ()
    def warn(msg: String): Unit = ()
  }

  // Helper to create a temp directory with fake library files
  private def withTempLibDir(files: Seq[String])(f: File => Unit): Unit = {
    val dir = Files.createTempDirectory("native-extract-test-").toFile
    try {
      files.foreach { name =>
        val file = new File(dir, name)
        Files.write(file.toPath, Array.emptyByteArray)
      }
      f(dir)
    } finally {
      dir.listFiles().foreach(_.delete())
      dir.delete()
    }
  }

  // ── binary field semantics ──────────────────────────────────────────

  test("binary = Some: full path to binary appears in linker flags") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "curl",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libcurl.a"),
              flagsGroups = Seq(Seq("-lpthread"), Seq("-ldl"))
            )
          )
        )
      )
    )

    withTempLibDir(Seq("libcurl.a")) { libDir =>
      val flags = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, Some(libDir), testLogger)
      val libDirFlag = s"-L${libDir.getAbsolutePath}"
      val curlPath   = new File(libDir, "libcurl.a").getAbsolutePath

      assert(flags.contains(libDirFlag), s"Expected -L flag, got: $flags")
      assert(flags.contains(curlPath), s"Expected full path to libcurl.a, got: $flags")
      assert(flags.contains("-lpthread"), s"Expected -lpthread, got: $flags")
      assert(flags.contains("-ldl"), s"Expected -ldl, got: $flags")
    }
  }

  test("binary = None: no library linked, only flagsGroups contribute") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "system-flags",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = None,
              flagsGroups = Seq(Seq("-lpthread"), Seq("-ldl"))
            )
          )
        )
      )
    )

    withTempLibDir(Seq.empty) { libDir =>
      val flags = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, Some(libDir), testLogger)
      // Should have -L, -lpthread, -ldl but NO library path
      assert(flags.contains("-lpthread"), s"Expected -lpthread, got: $flags")
      assert(flags.contains("-ldl"), s"Expected -ldl, got: $flags")
      // No absolute path to any .a file
      assert(!flags.exists(_.endsWith(".a")), s"Expected no .a path, got: $flags")
    }
  }

  test("binary = None: no -l flag generated either") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "nolib",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = None,
              flagsGroups = Seq.empty
            )
          )
        )
      )
    )

    val flags = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, None, testLogger)
    assert(flags.isEmpty, s"Expected empty flags, got: $flags")
  }

  test("stub = true with binary: stub archive path appears in flags") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "idn2",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libidn2.a"),
              stub = true,
              flagsGroups = Seq.empty
            )
          )
        )
      )
    )

    withTempLibDir(Seq("libidn2.a")) { libDir =>
      val flags    = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, Some(libDir), testLogger)
      val idn2Path = new File(libDir, "libidn2.a").getAbsolutePath
      assert(flags.contains(idn2Path), s"Expected stub archive path, got: $flags")
    }
  }

  test("stub = true without binary: nothing linked (no-op)") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "phantom",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = None,
              stub = true,
              flagsGroups = Seq.empty
            )
          )
        )
      )
    )

    val flags = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, None, testLogger)
    assert(flags.isEmpty, s"Expected empty flags for stub without binary, got: $flags")
  }

  // ── Group deduplication ─────────────────────────────────────────────

  test("identical flag groups are deduplicated") {
    val manifest1 = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "lib1",
      configs = Seq(
        ProviderConfig(
          configName = "a",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              flagsGroups = Seq(Seq("-lpthread"), Seq("-ldl"), Seq("-framework", "Security"))
            )
          )
        )
      )
    )
    val manifest2 = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "lib2",
      configs = Seq(
        ProviderConfig(
          configName = "b",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              flagsGroups = Seq(Seq("-lpthread"), Seq("-framework", "Security"))
            )
          )
        )
      )
    )

    val flags = NativeExtract.mergeFlags(Seq(manifest1, manifest2), Platform.LinuxX86_64, None, testLogger)
    // -lpthread should appear only once, -ldl once, -framework Security once
    assertEquals(flags.count(_ == "-lpthread"), 1, s"flags: $flags")
    assertEquals(flags.count(_ == "-ldl"), 1, s"flags: $flags")
    // -framework + Security = one deduplicated group
    assertEquals(flags.count(_ == "-framework"), 1, s"flags: $flags")
    assertEquals(flags.count(_ == "Security"), 1, s"flags: $flags")
  }

  // ── Mixed configs ───────────────────────────────────────────────────

  test("mixed configs: only binary-having configs produce library args") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "mixed",
      configs = Seq(
        ProviderConfig(
          configName = "curl",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libcurl.a"),
              flagsGroups = Seq(Seq("-lpthread"))
            )
          )
        ),
        ProviderConfig(
          configName = "system",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = None,
              flagsGroups = Seq(Seq("-ldl"))
            )
          )
        )
      )
    )

    withTempLibDir(Seq("libcurl.a")) { libDir =>
      val flags    = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, Some(libDir), testLogger)
      val curlPath = new File(libDir, "libcurl.a").getAbsolutePath

      assert(flags.contains(curlPath), s"Expected curl path, got: $flags")
      assert(flags.contains("-lpthread"), s"Expected -lpthread, got: $flags")
      assert(flags.contains("-ldl"), s"Expected -ldl, got: $flags")
      // No path for "system" config since binary is None
      assert(flags.count(_.endsWith(".a")) == 1, s"Expected exactly 1 .a path, got: $flags")
    }
  }

  // ── Platform filtering ──────────────────────────────────────────────

  test("platform filtering: config not present for target platform is skipped") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "linux-only",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libfoo.a"),
              flagsGroups = Seq(Seq("-lpthread"))
            )
          )
        )
      )
    )

    // Query for macos-aarch64 — config doesn't have this platform
    val flags = NativeExtract.mergeFlags(Seq(manifest), Platform.MacosAarch64, None, testLogger)
    assert(flags.isEmpty, s"Expected empty flags for non-matching platform, got: $flags")
  }

  test("platform filtering: only matching platform configs are used") {
    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "mylib",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libmylib.a"),
              flagsGroups = Seq(Seq("-lpthread"))
            ),
            "macos-aarch64" -> PlatformProviderConfig(
              binary = Some("libmylib.a"),
              flagsGroups = Seq(Seq("-framework", "Security"))
            )
          )
        )
      )
    )

    withTempLibDir(Seq("libmylib.a")) { libDir =>
      val linuxFlags = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, Some(libDir), testLogger)
      assert(linuxFlags.contains("-lpthread"))
      assert(!linuxFlags.contains("-framework"))

      val macFlags = NativeExtract.mergeFlags(Seq(manifest), Platform.MacosAarch64, Some(libDir), testLogger)
      assert(macFlags.contains("-framework"))
      assert(!macFlags.contains("-lpthread"))
    }
  }

  // ── isNativeLib ─────────────────────────────────────────────────────

  test("isNativeLib recognizes native library extensions") {
    assert(NativeExtract.isNativeLib("libcurl.a"))
    assert(NativeExtract.isNativeLib("curl.dll"))
    assert(NativeExtract.isNativeLib("libcurl.so"))
    assert(NativeExtract.isNativeLib("libcurl.dylib"))
    assert(NativeExtract.isNativeLib("curl.lib"))
    assert(!NativeExtract.isNativeLib("curl.jar"))
    assert(!NativeExtract.isNativeLib("curl.txt"))
    assert(!NativeExtract.isNativeLib("curl"))
  }

  // ── Windows lib alias ───────────────────────────────────────────────

  test("createWindowsLibAliases creates correct aliases") {
    withTempLibDir(Seq("libcurl.a", "libfoo.lib")) { dir =>
      NativeExtract.createWindowsLibAliases(dir, testLogger)

      // libcurl.a -> curl.lib
      assert(new File(dir, "curl.lib").exists(), "Expected curl.lib alias")
      // libfoo.lib -> foo.lib
      assert(new File(dir, "foo.lib").exists(), "Expected foo.lib alias")
    }
  }

  // ── Empty manifests ─────────────────────────────────────────────────

  test("mergeFlags with empty manifests returns empty") {
    val flags = NativeExtract.mergeFlags(Seq.empty, Platform.LinuxX86_64, None, testLogger)
    assert(flags.isEmpty)
  }

  test("mergeFlags with no lib dir and binary set warns") {
    val warnings = scala.collection.mutable.ArrayBuffer.empty[String]
    val warnLogger = new NativeExtract.Logger {
      def info(msg: String): Unit = ()
      def warn(msg: String): Unit = warnings += msg
    }

    val manifest = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "test",
      configs = Seq(
        ProviderConfig(
          configName = "mylib",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libmylib.a"),
              flagsGroups = Seq(Seq("-lpthread"))
            )
          )
        )
      )
    )

    val flags = NativeExtract.mergeFlags(Seq(manifest), Platform.LinuxX86_64, None, warnLogger)
    assert(warnings.nonEmpty, "Expected a warning about missing lib dir")
    // flagsGroups should still be included
    assert(flags.contains("-lpthread"))
  }
}
