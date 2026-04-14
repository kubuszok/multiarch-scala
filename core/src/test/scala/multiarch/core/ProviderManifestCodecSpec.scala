package multiarch.core

class ProviderManifestCodecSpec extends munit.FunSuite {

  test("parse minimal manifest") {
    val json = """{
      "provider-schema-version": "0.1.0",
      "provider-name": "test",
      "configs": []
    }"""
    val m = ProviderManifestCodec.parse(json)
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
    val m = ProviderManifestCodec.parse(json)
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
    val m = ProviderManifestCodec.parse(json)
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
    val m = ProviderManifestCodec.parse(json)
    assertEquals(m.providerName, "test\"name")
  }
}
