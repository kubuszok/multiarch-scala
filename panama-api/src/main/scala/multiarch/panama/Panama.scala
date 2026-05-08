package multiarch.panama

/** Runtime selection of the Panama FFM provider.
  *
  * On JDK 22+ (Desktop): `java.lang.foreign.MemorySegment` exists → JdkPanama.
  * On Android with PanamaPort: that class is missing → PanamaPortProvider.
  */
object Panama {

  /** The active Panama provider for the current runtime. */
  lazy val provider: PanamaProvider = detect()

  /** Detect and instantiate the appropriate PanamaProvider.
    *
    * Tries `java.lang.foreign.MemorySegment` first (JDK 22+), falls back to
    * PanamaPort (Android). Override the class names to use custom providers.
    */
  def detect(
      jdkClass: String = "multiarch.panama.JdkPanama$",
      androidClass: String = "multiarch.panama.PanamaPortProvider$"
  ): PanamaProvider =
    try {
      Class.forName("java.lang.foreign.MemorySegment")
      loadProvider(jdkClass)
    } catch {
      case _: ClassNotFoundException =>
        loadProvider(androidClass)
    }

  private def loadProvider(objectClassName: String): PanamaProvider = {
    val cls = Class.forName(objectClassName)
    cls.getField("MODULE$").get(null).asInstanceOf[PanamaProvider]
  }
}
