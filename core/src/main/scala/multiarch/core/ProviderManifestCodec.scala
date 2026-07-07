package multiarch.core

/** Minimal JSON codec for [[ProviderManifest]].
  *
  * Hand-rolled to avoid external dependencies in the sbt plugin classpath. Only handles the fixed schema of `*-provider.json` files.
  */
object ProviderManifestCodec {

  // ── Parsing ─────────────────────────────────────────────────────────

  /** Parse a `*-provider.json` string into a [[ProviderManifest]]. */
  def parse(json: String): ProviderManifest = {
    val root          = JsonParser.parseObject(json)
    val schemaVersion = root.getOrElse("provider-schema-version", "0.1.0").toString
    val providerName  = root.getOrElse("provider-name", "unnamed").toString
    val configs       = root.get("configs") match {
      case Some(arr: Seq[_]) => arr.map(parseConfig)
      case _                 => Seq.empty
    }
    val providerArtifact = root.get("provider-artifact") match {
      case Some(s: String) => Some(s)
      case _               => None
    }
    val providerVersion = root.get("provider-version") match {
      case Some(s: String) => Some(s)
      case _               => None
    }
    val bundles = root.get("bundles") match {
      case Some(arr: Seq[_]) => arr.map(_.toString)
      case _                 => Seq.empty
    }
    ProviderManifest(schemaVersion, providerName, configs, providerArtifact, providerVersion, bundles)
  }

  /** The manifest schema version produced by [[enrich]] and expected of v2 manifests. */
  val SchemaVersionV2: String = "0.2.0"

  /** Enrich a static template manifest with package-time metadata (artifact, version, bundles).
    *
    * Parses `templateJson`, sets the v2 fields (and forces `schemaVersion` to [[SchemaVersionV2]] — the enriched output is by definition a v2 manifest, even if the template still declares `0.1.x`),
    * and re-serializes. Used by provider builds' `resourceGenerators` to produce the final `*-provider.json` from a versionless template.
    */
  def enrich(templateJson: String, artifact: String, version: String, bundles: Seq[String]): String = {
    val base = parse(templateJson)
    write(
      base.copy(
        schemaVersion = SchemaVersionV2,
        providerArtifact = Some(artifact),
        providerVersion = Some(version),
        bundles = bundles
      )
    )
  }

  private def parseConfig(value: Any): ProviderConfig = {
    val obj        = value.asInstanceOf[Map[String, Any]]
    val configName = obj.getOrElse("config-name", "").toString
    // All keys except "config-name" are platform classifiers
    val platforms = obj.collect {
      case (key, platObj: Map[_, _]) if key != "config-name" =>
        key -> parsePlatformConfig(platObj.asInstanceOf[Map[String, Any]])
    }
    ProviderConfig(configName, platforms)
  }

  private def parsePlatformConfig(obj: Map[String, Any]): PlatformProviderConfig = {
    val binary = obj.get("binary") match {
      case Some(s: String) => Some(s)
      case _               => None
    }
    val stub = obj.get("stub") match {
      case Some(b: Boolean) => b
      case _                => false
    }
    val flagsGroups = obj.get("flags-groups") match {
      case Some(arr: Seq[_]) =>
        arr.map {
          case group: Seq[_] => group.map(_.toString)
          case other => Seq(other.toString)
        }
      case _ => Seq.empty
    }
    PlatformProviderConfig(binary, stub, flagsGroups)
  }

  // ── Writing ─────────────────────────────────────────────────────────

  /** Serialize a [[ProviderManifest]] to a JSON string. */
  def write(manifest: ProviderManifest): String = {
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append(s"""  "provider-schema-version": ${jsonString(manifest.schemaVersion)},\n""")
    sb.append(s"""  "provider-name": ${jsonString(manifest.providerName)},\n""")
    manifest.providerArtifact.foreach(a => sb.append(s"""  "provider-artifact": ${jsonString(a)},\n"""))
    manifest.providerVersion.foreach(v => sb.append(s"""  "provider-version": ${jsonString(v)},\n"""))
    if (manifest.bundles.nonEmpty) {
      val entries = manifest.bundles.map(jsonString).mkString("[", ", ", "]")
      sb.append(s"""  "bundles": $entries,\n""")
    }
    sb.append("""  "configs": [""")
    if (manifest.configs.nonEmpty) {
      sb.append("\n")
      val configStrs = manifest.configs.map(writeConfig)
      sb.append(configStrs.mkString(",\n"))
      sb.append("\n  ")
    }
    sb.append("]\n}")
    sb.toString
  }

  private def writeConfig(config: ProviderConfig): String = {
    val sb = new StringBuilder
    sb.append("    {\n")
    sb.append(s"""      "config-name": ${jsonString(config.configName)}""")
    config.platforms.toSeq.sortBy(_._1).foreach { case (platform, platConfig) =>
      sb.append(",\n")
      sb.append(s"""      ${jsonString(platform)}: ${writePlatformConfig(platConfig)}""")
    }
    sb.append("\n    }")
    sb.toString
  }

  private def writePlatformConfig(config: PlatformProviderConfig): String = {
    val parts = new scala.collection.mutable.ArrayBuffer[String]
    config.binary.foreach(b => parts += s""""binary": ${jsonString(b)}""")
    if (config.stub) parts += """"stub": true"""
    val groups = config.flagsGroups
      .map { group =>
        group.map(jsonString).mkString("[", ", ", "]")
      }
      .mkString("[", ", ", "]")
    parts += s""""flags-groups": $groups"""
    parts.mkString("{ ", ", ", " }")
  }

  private def jsonString(s: String): String = {
    val sb = new StringBuilder("\"")
    var i  = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c             => sb.append(c)
      }
      i += 1
    }
    sb.append('"')
    sb.toString
  }

  // ── Minimal JSON parser ─────────────────────────────────────────────

  private object JsonParser {
    def parseObject(json: String): Map[String, Any] = {
      val (value, _) = parseValue(json.trim, 0)
      value match {
        case m: Map[_, _] => m.asInstanceOf[Map[String, Any]]
        case _ => throw new RuntimeException("Expected JSON object at root")
      }
    }

    private def parseValue(s: String, pos: Int): (Any, Int) = {
      val p = skipWhitespace(s, pos)
      if (p >= s.length) throw new RuntimeException(s"Unexpected end of JSON at position $p")
      s.charAt(p) match {
        case '"'                        => parseString(s, p)
        case '{'                        => parseObj(s, p)
        case '['                        => parseArr(s, p)
        case 't'                        => parseLiteral(s, p, "true", true)
        case 'f'                        => parseLiteral(s, p, "false", false)
        case 'n'                        => parseLiteral(s, p, "null", null)
        case c if c == '-' || c.isDigit => parseNumber(s, p)
        case c                          => throw new RuntimeException(s"Unexpected character '$c' at position $p")
      }
    }

    private def parseObj(s: String, pos: Int): (Map[String, Any], Int) = {
      var p      = skipWhitespace(s, pos + 1) // skip '{'
      val result = scala.collection.mutable.LinkedHashMap.empty[String, Any]
      if (charAt(s, p) == '}') return (result.toMap, p + 1)
      while (true) {
        p = skipWhitespace(s, p)
        val (key, p2) = parseString(s, p)
        p = skipWhitespace(s, p2)
        if (charAt(s, p) != ':') throw new RuntimeException(s"Expected ':' at position $p")
        p = skipWhitespace(s, p + 1)
        val (value, p3) = parseValue(s, p)
        result(key.asInstanceOf[String]) = value
        p = skipWhitespace(s, p3)
        charAt(s, p) match {
          case ',' => p = p + 1
          case '}' => return (result.toMap, p + 1)
          case c   => throw new RuntimeException(s"Expected ',' or '}' at position $p, got '$c'")
        }
      }
      throw new RuntimeException("Unreachable")
    }

    private def parseArr(s: String, pos: Int): (Seq[Any], Int) = {
      var p      = skipWhitespace(s, pos + 1) // skip '['
      val result = scala.collection.mutable.ArrayBuffer.empty[Any]
      if (charAt(s, p) == ']') return (result.toSeq, p + 1)
      while (true) {
        val (value, p2) = parseValue(s, p)
        result += value
        p = skipWhitespace(s, p2)
        charAt(s, p) match {
          case ',' => p = skipWhitespace(s, p + 1)
          case ']' => return (result.toSeq, p + 1)
          case c   => throw new RuntimeException(s"Expected ',' or ']' at position $p, got '$c'")
        }
      }
      throw new RuntimeException("Unreachable")
    }

    private def parseString(s: String, pos: Int): (String, Int) = {
      if (charAt(s, pos) != '"') throw new RuntimeException("Expected '\"' at position " + pos)
      val sb = new StringBuilder
      var p  = pos + 1
      while (p < s.length && s.charAt(p) != '"') {
        if (s.charAt(p) == '\\') {
          p += 1
          charAt(s, p) match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u'  =>
              // issue #30: bounds-check the 4 hex digits so a truncated unicode escape yields a clear
              // "end of manifest" error instead of a raw StringIndexOutOfBoundsException.
              if (p + 5 > s.length) throw new RuntimeException(s"Unexpected end of manifest at offset $p (incomplete \\u escape)")
              val hex = s.substring(p + 1, p + 5)
              sb.append(Integer.parseInt(hex, 16).toChar)
              p += 4
            case c => sb.append(c)
          }
        } else {
          sb.append(s.charAt(p))
        }
        p += 1
      }
      if (p >= s.length) throw new RuntimeException("Unterminated string")
      (sb.toString, p + 1)
    }

    private def parseNumber(s: String, pos: Int): (Any, Int) = {
      var p = pos
      if (p < s.length && s.charAt(p) == '-') p += 1
      while (p < s.length && s.charAt(p).isDigit) p += 1
      var isDouble = false
      if (p < s.length && s.charAt(p) == '.') { isDouble = true; p += 1; while (p < s.length && s.charAt(p).isDigit) p += 1 }
      if (p < s.length && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
        isDouble = true; p += 1; if (p < s.length && (s.charAt(p) == '+' || s.charAt(p) == '-')) p += 1; while (p < s.length && s.charAt(p).isDigit) p += 1
      }
      val numStr = s.substring(pos, p)
      if (isDouble) (numStr.toDouble, p)
      else (numStr.toLong, p)
    }

    private def parseLiteral(s: String, pos: Int, literal: String, value: Any): (Any, Int) =
      if (s.startsWith(literal, pos)) (value, pos + literal.length)
      else throw new RuntimeException(s"Expected '$literal' at position $pos")

    /** Bounds-checked character read.
      *
      * issue #30: the hand-rolled parser previously called `s.charAt(p)` directly after `skipWhitespace`, so truncated input (a manifest cut off mid-object/array/string) surfaced as a raw
      * `StringIndexOutOfBoundsException` that named neither the offset nor the manifest. Routing every structural read through this helper yields a clear "unexpected end of manifest at offset N"
      * instead.
      */
    private def charAt(s: String, p: Int): Char = {
      if (p >= s.length) throw new RuntimeException(s"Unexpected end of manifest at offset $p")
      s.charAt(p)
    }

    private def skipWhitespace(s: String, pos: Int): Int = {
      var p = pos
      while (p < s.length && (s.charAt(p) == ' ' || s.charAt(p) == '\n' || s.charAt(p) == '\r' || s.charAt(p) == '\t')) p += 1
      p
    }
  }
}
