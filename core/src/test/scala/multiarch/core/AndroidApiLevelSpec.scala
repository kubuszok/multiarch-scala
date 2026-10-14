package multiarch.core

import java.io.{ ByteArrayOutputStream, File, PrintStream }

/** The core runs on Android from API 26, so its bytecode may not call a JDK member Android added later.
  *
  * Reads every compiled core class through `javap -c` and refuses each call to a member on the list below; a raw call fails with `NoSuchMethodError` on an older device, which no desktop test can see.
  */
class AndroidApiLevelSpec extends munit.FunSuite {

  /** JDK members Android lacks below API 34, as `javap` prints the call (owner.name:). */
  private val missingBelowApi34 = Seq("java/nio/file/Path.of:")

  private def classFiles(dir: File): Seq[File] =
    Option(dir.listFiles()).toSeq.flatten.flatMap { f =>
      if (f.isDirectory) classFiles(f) else if (f.getName.endsWith(".class")) Seq(f) else Seq.empty
    }

  test("no core class calls a JDK member Android lacks below API 34") {
    val root  = new File(NativeLibLoader.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    val javap = java.util.spi.ToolProvider.findFirst("javap").orElseThrow(() => new AssertionError("no javap in this JDK"))
    val files = classFiles(root)
    assert(files.nonEmpty, s"no class files under $root")
    val offenders = files.flatMap { f =>
      val out = new ByteArrayOutputStream()
      val ps  = new PrintStream(out, true, "UTF-8")
      javap.run(ps, ps, "-c", "-p", f.getAbsolutePath)
      val text = out.toString("UTF-8")
      missingBelowApi34.filter(text.contains).map(m => s"${f.getName}: $m")
    }
    assertEquals(offenders, Seq.empty[String])
  }
}
