package multiarch.core

/** Type of native library provider, determining the JSON manifest filename.
  *
  * A JAR should contain at most one of these manifest files.
  */
sealed abstract class ProviderType(
    /** Manifest filename (e.g. `"sn-provider.json"`). */
    val filename: String,
    /** Human-readable label for logging (e.g. `"Scala Native"`). */
    val label: String
)

object ProviderType {

  /** JNI-compatible native libraries (dynamically loaded). */
  case object Jni extends ProviderType("jni-provider.json", "JNI")

  /** Panama-compatible native libraries (dynamically loaded). */
  case object Panama extends ProviderType("pnm-provider.json", "Panama")

  /** Scala Native native libraries (statically linked). */
  case object ScalaNative extends ProviderType("sn-provider.json", "Scala Native")

  /** All provider types. */
  val all: Seq[ProviderType] = Seq(Jni, Panama, ScalaNative)

  /** Resolve a provider type from its filename.
    * @throws RuntimeException if the filename is not recognized
    */
  def fromFilename(filename: String): ProviderType =
    all
      .find(_.filename == filename)
      .getOrElse(throw new RuntimeException(s"Unknown provider type filename: $filename"))
}
