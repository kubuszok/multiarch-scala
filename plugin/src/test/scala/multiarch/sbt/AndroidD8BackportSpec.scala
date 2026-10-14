/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The D8 androidDex runs must rewrite a read of Build.VERSION.SDK_INT_FULL (API 36+) into a
 * guarded backport: PanamaPort reads it unguarded, and on API 26-35 the raw read is a NoSuchFieldError. */
package multiarch.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AndroidD8BackportSpec extends munit.FunSuite {

  private object QuietLogger extends sbt.util.Logger {
    def trace(t:         => Throwable):                             Unit = ()
    def success(message: => String):                                Unit = ()
    def log(level:       sbt.util.Level.Value, message: => String): Unit = ()
  }

  private def write(file: File, text: String): File = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, text.getBytes(StandardCharsets.UTF_8))
    file
  }

  private def javac(out: File, classpath: Seq[File], sources: File*): Unit = {
    out.mkdirs()
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler
    val cp       = if (classpath.isEmpty) Seq.empty else Seq("-cp", classpath.map(_.getAbsolutePath).mkString(File.pathSeparator))
    val args     = Seq("--release", "17", "-d", out.getAbsolutePath) ++ cp ++ sources.map(_.getAbsolutePath)
    assertEquals(compiler.run(null, null, null, args: _*), 0, "javac failed")
  }

  private def hasBytes(file: File, text: String): Boolean = {
    val hay    = Files.readAllBytes(file.toPath)
    val needle = text.getBytes(StandardCharsets.UTF_8)
    hay.indices.exists(i => i + needle.length <= hay.length && needle.indices.forall(j => hay(i + j) == needle(j)))
  }

  test("the pinned D8 replaces an SDK_INT_FULL read with a guarded backport at minApi 26") {
    val dir = Files.createTempDirectory("multiarch-d8").toFile
    val r8  = AndroidDeps.resolveR8(new File(sys.props("java.io.tmpdir"), "multiarch-r8"), AndroidDeps.r8Version, QuietLogger)

    val stubSrc = write(
      new File(dir, "src/android/os/Build.java"),
      """package android.os;
        |public class Build {
        |  public static class VERSION {
        |    public static final int SDK_INT = Integer.parseInt("0");
        |    public static final int SDK_INT_FULL = Integer.parseInt("0");
        |  }
        |}""".stripMargin
    )
    val probeSrc = write(
      new File(dir, "src/p/Probe.java"),
      "package p; public class Probe { public static int full() { return android.os.Build.VERSION.SDK_INT_FULL; } }"
    )
    val stubOut = new File(dir, "stub")
    val appOut  = new File(dir, "app")
    javac(stubOut, Seq.empty, stubSrc)
    javac(appOut, Seq(stubOut), probeSrc)
    val stubJar = new File(dir, "stub.jar")
    val appJar  = new File(dir, "app.jar")
    sbt.IO.zip(sbt.Path.allSubpaths(stubOut).toSeq, stubJar, None)
    sbt.IO.zip(sbt.Path.allSubpaths(appOut).toSeq, appJar, None)

    val out = new File(dir, "out")
    out.mkdirs()
    val java = new File(new File(sys.props("java.home"), "bin"), "java").getAbsolutePath
    val cmd  = Seq(java, "-cp", r8.getAbsolutePath, "com.android.tools.r8.D8", "--classfile", "--min-api", AndroidSdk.minSdkVersion.toString) ++
      Seq("--lib", stubJar.getAbsolutePath, "--output", out.getAbsolutePath, appJar.getAbsolutePath)
    assertEquals(scala.sys.process.Process(cmd).!, 0, "D8 failed")

    val probe = new File(out, "p/Probe.class")
    assert(probe.isFile, s"no p/Probe.class in ${sbt.Path.allSubpaths(out).map(_._2).toList}")
    assert(!hasBytes(probe, "SDK_INT_FULL"), "Probe still reads SDK_INT_FULL directly")
    val backports = sbt.Path.allSubpaths(out).map(_._1).filter(f => f.isFile && f != probe).toList
    assert(backports.exists(hasBytes(_, "SDK_INT_FULL")), "no backport class carries the guarded SDK_INT_FULL read")
  }
}
