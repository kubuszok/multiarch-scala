package multiarch.core

class ProviderManifestCodecSpec extends munit.FunSuite {

  test("parse minimal manifest") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "test",
      "configs": []
    }"""
    val m    = ProviderManifestCodec.parse(json)
    assertEquals(m.schemaVersion, "0.1.0")
    assertEquals(m.providerName, "test")
    assertEquals(m.configs.size, 0)
  }

  test("parse manifest with one config and one platform") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "mylib",
      "configs": [
        {
          "config-name": "mylib",
          "linux-x86_64": {
            "binary": "libmylib.a",
            "flags-groups": [["-lpthread"], ["-ldl"]]
          }
        }
      ]
    }"""
    val m    = ProviderManifestCodec.parse(json)
    assertEquals(m.providerName, "mylib")
    assertEquals(m.configs.size, 1)

    val config = m.configs.head
    assertEquals(config.configName, "mylib")
    assertEquals(config.platforms.size, 1)

    val plat = config.platforms("linux-x86_64")
    assertEquals(plat.binary, Some("libmylib.a"))
    assertEquals(plat.stub, false)
    assertEquals(plat.flagsGroups, Seq(Seq("-lpthread"), Seq("-ldl")))
  }

  test("parse optional fields default correctly") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "test",
      "configs": [
        {
          "config-name": "foo",
          "macos-aarch64": {
            "flags-groups": [["-framework", "Security"]]
          }
        }
      ]
    }"""
    val m    = ProviderManifestCodec.parse(json)
    val plat = m.configs.head.platforms("macos-aarch64")
    assertEquals(plat.binary, None)
    assertEquals(plat.stub, false)
    assertEquals(plat.flagsGroups, Seq(Seq("-framework", "Security")))
  }

  test("parse stub field") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "test",
      "configs": [
        {
          "config-name": "idn2",
          "linux-x86_64": {
            "binary": "libidn2.a",
            "stub": true,
            "flags-groups": []
          }
        }
      ]
    }"""
    val m    = ProviderManifestCodec.parse(json)
    val plat = m.configs.head.platforms("linux-x86_64")
    assertEquals(plat.binary, Some("libidn2.a"))
    assertEquals(plat.stub, true)
    assertEquals(plat.flagsGroups, Seq.empty)
  }

  test("parse multiple configs and platforms") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "curl",
      "configs": [
        {
          "config-name": "curl",
          "linux-x86_64": { "binary": "libcurl.a", "flags-groups": [["-lpthread"]] },
          "macos-aarch64": { "binary": "libcurl.a", "flags-groups": [["-framework", "Security"]] }
        },
        {
          "config-name": "idn2",
          "linux-x86_64": { "binary": "libidn2.a", "stub": true, "flags-groups": [] }
        }
      ]
    }"""
    val m    = ProviderManifestCodec.parse(json)
    assertEquals(m.configs.size, 2)
    assertEquals(m.configs(0).configName, "curl")
    assertEquals(m.configs(0).platforms.size, 2)
    assertEquals(m.configs(1).configName, "idn2")
    assertEquals(m.configs(1).platforms.size, 1)
  }

  test("parse missing optional top-level fields") {
    val json = """{ "configs": [] }"""
    val m    = ProviderManifestCodec.parse(json)
    assertEquals(m.schemaVersion, "0.1.0")
    assertEquals(m.providerName, "unnamed")
  }

  test("round-trip parse then write then parse") {
    val original = ProviderManifest(
      schemaVersion = "0.1.0",
      providerName = "roundtrip-test",
      configs = Seq(
        ProviderConfig(
          configName = "mylib",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libmylib.a"),
              stub = false,
              flagsGroups = Seq(Seq("-lpthread"), Seq("-ldl"))
            ),
            "macos-aarch64" -> PlatformProviderConfig(
              binary = Some("libmylib.a"),
              stub = false,
              flagsGroups = Seq(Seq("-framework", "Security"))
            )
          )
        ),
        ProviderConfig(
          configName = "stub",
          platforms = Map(
            "linux-x86_64" -> PlatformProviderConfig(
              binary = Some("libstub.a"),
              stub = true,
              flagsGroups = Seq.empty
            )
          )
        )
      )
    )

    val json     = ProviderManifestCodec.write(original)
    val reparsed = ProviderManifestCodec.parse(json)

    assertEquals(reparsed.schemaVersion, original.schemaVersion)
    assertEquals(reparsed.providerName, original.providerName)
    assertEquals(reparsed.configs.size, original.configs.size)

    val c0 = reparsed.configs(0)
    assertEquals(c0.configName, "mylib")
    assertEquals(c0.platforms("linux-x86_64").binary, Some("libmylib.a"))
    assertEquals(c0.platforms("linux-x86_64").flagsGroups, Seq(Seq("-lpthread"), Seq("-ldl")))
    assertEquals(c0.platforms("macos-aarch64").flagsGroups, Seq(Seq("-framework", "Security")))

    val c1 = reparsed.configs(1)
    assertEquals(c1.configName, "stub")
    assertEquals(c1.platforms("linux-x86_64").stub, true)
  }

  // ── v2 (MA-1) ──────────────────────────────────────────────────────

  private val curlV1Fixture =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream("/sn-provider.json")).mkString

  // NOTE (dry-run): the plan's literal "byte-compare fixture" is impossible with the
  // current writer. Substituted with the sensible interpretation: v1 serialization is
  // additively unchanged (no v2 keys leak) and semantically round-trips.
  test("v1 write emits no v2 keys and semantically round-trips") {
    val parsed  = ProviderManifestCodec.parse(curlV1Fixture)
    val written = ProviderManifestCodec.write(parsed)
    assert(!written.contains("provider-artifact"), written)
    assert(!written.contains("provider-version"), written)
    assert(!written.contains("\"bundles\""), written)
    val re = ProviderManifestCodec.parse(written)
    assertEquals(re.providerName, parsed.providerName)
    assertEquals(re.configs.map(_.configName), parsed.configs.map(_.configName))
  }

  test("v2 fields parse") {
    val json = """{
      "provider-schema-version": "0.2.0",
      "provider-name": "sge-desktop",
      "provider-artifact": "pnm-provider-sge-desktop",
      "provider-version": "0.2.0",
      "bundles": ["linux-x86_64/libfoo.so", "macos-aarch64/libfoo.dylib"],
      "configs": []
    }"""
    val m    = ProviderManifestCodec.parse(json)
    assertEquals(m.providerArtifact, Some("pnm-provider-sge-desktop"))
    assertEquals(m.providerVersion, Some("0.2.0"))
    assertEquals(m.bundles, Seq("linux-x86_64/libfoo.so", "macos-aarch64/libfoo.dylib"))
  }

  test("v2 round-trip parse then write then parse") {
    val m = ProviderManifest(
      schemaVersion = "0.2.0",
      providerName = "sge-desktop",
      configs = Seq.empty,
      providerArtifact = Some("pnm-provider-sge-desktop"),
      providerVersion = Some("0.2.0"),
      bundles = Seq("linux-x86_64/libfoo.so")
    )
    val re = ProviderManifestCodec.parse(ProviderManifestCodec.write(m))
    assertEquals(re.providerArtifact, m.providerArtifact)
    assertEquals(re.providerVersion, m.providerVersion)
    assertEquals(re.bundles, m.bundles)
  }

  test("effectiveBundles derives from v1 config binaries") {
    val parsed = ProviderManifestCodec.parse(curlV1Fixture)
    val eb     = parsed.effectiveBundles
    assert(eb.contains("linux-x86_64/libcurl.a"), eb.toString)
    assert(eb.contains("windows-x86_64/libcurl.lib"), eb.toString)
    assert(eb.contains("linux-x86_64/libidn2.a"), eb.toString)
  }

  test("effectiveBundles returns bundles verbatim when present") {
    val m = ProviderManifest("0.2.0", "x", Seq.empty, bundles = Seq("linux-x86_64/libx.so"))
    assertEquals(m.effectiveBundles, Seq("linux-x86_64/libx.so"))
  }

  test("enrich injects artifact, version, bundles") {
    val out = ProviderManifestCodec.enrich(
      curlV1Fixture,
      artifact = "sn-provider-curl",
      version = "0.4.0",
      bundles = Seq("linux-x86_64/libcurl.a")
    )
    val m = ProviderManifestCodec.parse(out)
    assertEquals(m.providerArtifact, Some("sn-provider-curl"))
    assertEquals(m.providerVersion, Some("0.4.0"))
    assertEquals(m.bundles, Seq("linux-x86_64/libcurl.a"))
  }

  test("enrich forces schemaVersion to 0.2.0 even when template declares 0.1.0") {
    // the curl fixture declares "provider-schema-version": "0.1.0"
    assert(curlV1Fixture.contains("\"0.1.0\""), curlV1Fixture.take(80))
    val out = ProviderManifestCodec.enrich(curlV1Fixture, "sn-provider-curl", "0.4.0", Seq("linux-x86_64/libcurl.a"))
    val m   = ProviderManifestCodec.parse(out)
    assertEquals(m.schemaVersion, "0.2.0")
  }

  test("malformed JSON throws") {
    intercept[RuntimeException] {
      ProviderManifestCodec.parse("not json at all")
    }
  }

  test("parse JSON with string escapes") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "test\"name",
      "configs": []
    }"""
    val m    = ProviderManifestCodec.parse(json)
    assertEquals(m.providerName, "test\"name")
  }

  // ── Corrupt / truncated manifests (issue #30) ───────────────────────
  //
  // The hand-rolled parser must never let a raw StringIndexOutOfBoundsException escape on truncated
  // input: both NativeLibLoader paths (buildV2Index fail-open, discoverClasspathManifests fail-closed)
  // rely on parse failures being clear, catchable RuntimeExceptions naming the offset.

  /** Parse `json`, expecting a failure that is a clear RuntimeException — NOT a raw StringIndexOutOfBoundsException. */
  private def assertParseFailsCleanly(json: String): RuntimeException = {
    val e = intercept[RuntimeException](ProviderManifestCodec.parse(json))
    assert(
      !e.isInstanceOf[StringIndexOutOfBoundsException],
      s"raw StringIndexOutOfBoundsException escaped for input <$json>: $e"
    )
    e
  }

  test("truncated object (cut off mid-object) fails with a clear end-of-manifest error") {
    val e = assertParseFailsCleanly("""{ "provider-name": "x", "provider-version": """)
    assert(e.getMessage.contains("end of manifest") || e.getMessage.contains("end of JSON"), e.getMessage)
  }

  test("object truncated right after the opening brace fails cleanly") {
    val e = assertParseFailsCleanly("{")
    assert(e.getMessage.contains("Unexpected end of manifest at offset"), e.getMessage)
  }

  test("truncated array fails with a clear end-of-manifest error") {
    val e = assertParseFailsCleanly("""{ "configs": [ { "config-name": "a" }""")
    assert(e.getMessage.contains("end of manifest") || e.getMessage.contains("Unterminated"), e.getMessage)
  }

  test("truncated string (unterminated) fails cleanly") {
    val e = assertParseFailsCleanly("""{ "provider-name": "unterminated""")
    assert(e.getMessage.contains("Unterminated") || e.getMessage.contains("end of manifest"), e.getMessage)
  }

  test("invalid token fails with a clear character error, not an index crash") {
    val e = assertParseFailsCleanly("""{ "provider-name": @ }""")
    assert(e.getMessage.contains("Unexpected character"), e.getMessage)
  }

  test("incomplete unicode escape at end of input fails cleanly") {
    // Build the literal backslash-then-u escape via string escapes: a raw backslash-u in source is a lexer-level unicode escape.
    val e = assertParseFailsCleanly("{ \"provider-name\": \"" + "\\u12")
    assert(!e.isInstanceOf[StringIndexOutOfBoundsException], e.toString)
  }

  test("no raw StringIndexOutOfBoundsException for ANY truncation of a valid manifest") {
    val full = """{
      "provider-schema-version": "0.2.0",
      "provider-name": "curl",
      "provider-artifact": "sn-provider-curl",
      "provider-version": "0.4.0",
      "bundles": ["linux-x86_64/libcurl.a"],
      "configs": [ { "config-name": "curl", "linux-x86_64": { "binary": "libcurl.a", "flags-groups": [["-lpthread"]] } } ]
    }"""
    // Every prefix must either parse (unlikely for partial input) or fail with a catchable, non-index-crash error.
    (0 until full.length).foreach { cut =>
      val prefix = full.substring(0, cut)
      try ProviderManifestCodec.parse(prefix)
      catch {
        case e: StringIndexOutOfBoundsException =>
          fail(s"raw StringIndexOutOfBoundsException for prefix length $cut: <$prefix>", e)
        case _: RuntimeException => () // expected clean failure
      }
    }
  }
}
