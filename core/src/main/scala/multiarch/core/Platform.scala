package multiarch.core

/** Supported build target platform.
  *
  * Each case encodes the platform classifier (used in JAR names, archive names, cache paths),
  * the Rust/Scala Native target triple, and OS-specific properties.
  */
sealed abstract class Platform(
    /** Classifier string used in file names and directory paths (e.g. `"linux-x86_64"`). */
    val classifier: String,
    /** Rust and Scala Native target triple (e.g. `"x86_64-unknown-linux-gnu"`). */
    val rustTarget: String
) {

  /** OS component of the classifier (e.g. `"linux"`, `"macos"`, `"windows"`, `"android"`). */
  def os: String = classifier.split('-').head

  /** Architecture component of the classifier (e.g. `"x86_64"`, `"aarch64"`, `"armv7"`). */
  def arch: String = classifier.split('-').last

  def isMac: Boolean     = os == "macos"
  def isWindows: Boolean = os == "windows"
  def isLinux: Boolean   = os == "linux"
  def isAndroid: Boolean = os == "android"
  def isDesktop: Boolean = !isAndroid

  /** Scala Native target triple (identical to [[rustTarget]]). */
  def scalaNativeTarget: String = rustTarget

  /** Zig cross-compilation target triple (e.g. `"aarch64-macos"` or `"x86_64-linux-gnu"`).
    * Used as the `-target` argument for `zig cc` / `zig c++`.
    *
    * @throws UnsupportedOperationException for Android platforms (zig cross-compilation not supported)
    */
  def zigTarget: String = {
    if (isAndroid) throw new UnsupportedOperationException(s"Zig cross-compilation is not supported for $classifier")
    val zigArch = arch match {
      case "x86_64"  => "x86_64"
      case "aarch64" => "aarch64"
    }
    val zigOs = os match {
      case "linux"   => "linux-gnu"
      case "macos"   => "macos"
      case "windows" => "windows-gnu"
    }
    s"$zigArch-$zigOs"
  }

  override def toString: String = classifier
}

object Platform {
  // Desktop platforms (6) — valid for Scala Native, JVM packaging, and JNI/Panama
  case object LinuxX86_64    extends Platform("linux-x86_64", "x86_64-unknown-linux-gnu")
  case object LinuxAarch64   extends Platform("linux-aarch64", "aarch64-unknown-linux-gnu")
  case object MacosX86_64    extends Platform("macos-x86_64", "x86_64-apple-darwin")
  case object MacosAarch64   extends Platform("macos-aarch64", "aarch64-apple-darwin")
  case object WindowsX86_64  extends Platform("windows-x86_64", "x86_64-pc-windows-msvc")
  case object WindowsAarch64 extends Platform("windows-aarch64", "aarch64-pc-windows-msvc")

  // Android platforms (3) — valid for JNI/Panama only
  case object AndroidAarch64 extends Platform("android-aarch64", "aarch64-linux-android")
  case object AndroidArmV7   extends Platform("android-armv7", "armv7-linux-androideabi")
  case object AndroidX86_64  extends Platform("android-x86_64", "x86_64-linux-android")

  /** All six supported desktop platforms. */
  val desktop: Seq[Platform] = Seq(
    LinuxX86_64,
    LinuxAarch64,
    MacosX86_64,
    MacosAarch64,
    WindowsX86_64,
    WindowsAarch64
  )

  /** All three Android platforms. */
  val android: Seq[Platform] = Seq(
    AndroidAarch64,
    AndroidArmV7,
    AndroidX86_64
  )

  /** Platforms valid for JNI/Panama (desktop + Android). */
  val jniPanama: Seq[Platform] = desktop ++ android

  /** Platforms valid for Scala Native (desktop only). */
  val scalaNative: Seq[Platform] = desktop

  /** All supported platforms. */
  val all: Seq[Platform] = jniPanama

  /** Detect the current host platform. */
  def host: Platform = {
    val isAndroidRuntime = try {
      Class.forName("android.app.Activity")
      true
    } catch {
      case _: ClassNotFoundException => false
    }
    val osName = sys.props("os.name").toLowerCase
    val os =
      if (isAndroidRuntime) "android"
      else if (osName.contains("linux")) "linux"
      else if (osName.contains("mac")) "macos"
      else if (osName.contains("win")) "windows"
      else throw new RuntimeException(s"Unsupported OS: ${sys.props("os.name")}")
    val arch = sys.props("os.arch") match {
      case "amd64" | "x86_64"                => "x86_64"
      case "aarch64" | "arm64"               => "aarch64"
      case "armv7l" | "armeabi-v7a" | "arm"  => "armv7"
      case a => throw new RuntimeException(s"Unsupported arch: $a")
    }
    fromClassifier(s"$os-$arch")
  }

  /** Resolve a platform from its classifier string (e.g. `"macos-aarch64"`).
    * @throws RuntimeException if the classifier is not recognized
    */
  def fromClassifier(s: String): Platform =
    all
      .find(_.classifier == s)
      .getOrElse(throw new RuntimeException(s"Unknown platform: $s"))
}
