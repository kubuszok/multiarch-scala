package multiarch.core

/** A complete native provider manifest, parsed from a `*-provider.json` resource. */
final case class ProviderManifest(
  /** Schema version (e.g. `"0.1.0"`). */
  schemaVersion: String,
  /** Human-readable name for logging and diagnostics (e.g. `"curl"`). */
  providerName: String,
  /** Native library configurations declared by this provider. */
  configs: Seq[ProviderConfig],
  /** v2: the publishing artifact id (e.g. `"pnm-provider-sge-desktop"`). Injected at package time. */
  providerArtifact: Option[String] = None,
  /** v2: the resolved provider version (e.g. `"0.2.0"`). Injected at package time. */
  providerVersion: Option[String] = None,
  /** v2: every native file the JAR physically carries, as `"<classifier>/<file>"`. */
  bundles: Seq[String] = Seq.empty
) {

  /** The effective set of `"<classifier>/<file>"` bundle entries for collision detection.
    *
    * Returns [[bundles]] when non-empty (v2 manifests), otherwise derives entries as `"<classifier>/<binary>"` from every config platform entry that declares a `binary` (so collision detection also
    * works for v1 manifests).
    */
  def effectiveBundles: Seq[String] =
    if (bundles.nonEmpty) bundles
    else
      configs.flatMap { config =>
        config.platforms.toSeq.collect {
          case (classifier, plat) if plat.binary.isDefined => s"$classifier/${plat.binary.get}"
        }
      }.distinct
}

/** A single named configuration within a provider manifest.
  *
  * Each configuration declares per-platform settings. A configuration might represent a single library (e.g. `"curl"`) or a related group of settings.
  */
final case class ProviderConfig(
  /** Configuration name (e.g. `"curl"`, `"idn2"`). */
  configName: String,
  /** Per-platform settings. Key is the platform classifier (e.g. `"linux-x86_64"`). */
  platforms: Map[String, PlatformProviderConfig]
)

/** Platform-specific configuration for a native library.
  *
  * The `binary` field determines linking behavior:
  *   - `Some("libcurl.a")` — extract this file from the JAR, use its full absolute path as a linker argument, plus apply `flagsGroups`
  *   - `None` — no library to bind, no artifact to extract — only `flagsGroups` contribute to the final linker flags
  *
  * The `stub` field marks archives that exist only to satisfy the linker (e.g. idn2 stub whose symbols are embedded in curl). Still extracted and linked, but no functional library.
  */
final case class PlatformProviderConfig(
  /** Library filename to extract and link (e.g. `"libcurl.a"`, `"curl.dll"`). When `None`, this config contributes only `flagsGroups` — no library is linked.
    */
  binary: Option[String] = None,
  /** When `true`, the archive is a stub that exists only to satisfy linker lookups. */
  stub: Boolean = false,
  /** Grouped linker flags. Each inner sequence is a flag with its arguments (e.g. `Seq("-framework", "Security")`). Groups are deduplicated across manifests.
    */
  flagsGroups: Seq[Seq[String]] = Seq.empty
)
