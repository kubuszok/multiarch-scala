/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Unit test for the APK packaging step that copies dependency JARs' Java resources into the APK
 * root, where ClassLoader.getResource finds them on Android. */
package multiarch.sbt

import java.io.{ File, FileOutputStream }
import java.nio.file.Files
import java.util.zip.{ ZipEntry, ZipFile, ZipOutputStream }

import scala.collection.mutable.ListBuffer

class AndroidDependencyResourcesSpec extends munit.FunSuite {

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("multiarch-apk-res").toFile
    try f(dir)
    finally {
      def del(f: File): Unit = {
        if (f.isDirectory) Option(f.listFiles()).toList.flatten.foreach(del)
        f.delete()
        ()
      }
      del(dir)
    }
  }

  private def writeZip(file: File, entries: (String, String)*): File = {
    val out = new ZipOutputStream(new FileOutputStream(file))
    try
      entries.foreach { case (name, content) =>
        out.putNextEntry(new ZipEntry(name))
        out.write(content.getBytes("UTF-8"))
        out.closeEntry()
      }
    finally out.close()
    file
  }

  private def readZip(file: File): Map[String, String] = {
    val zip = new ZipFile(file)
    try {
      val entries = zip.entries()
      val result  = Map.newBuilder[String, String]
      while (entries.hasMoreElements) {
        val e  = entries.nextElement()
        val is = zip.getInputStream(e)
        try result += e.getName -> new String(is.readAllBytes(), "UTF-8")
        finally is.close()
      }
      result.result()
    } finally zip.close()
  }

  private class RecordingLogger extends sbt.util.Logger {
    val warnings:                                             ListBuffer[String] = ListBuffer.empty
    def trace(t:         => Throwable):                       Unit               = ()
    def success(message: => String):                          Unit               = ()
    def log(level: sbt.util.Level.Value, message: => String): Unit               =
      if (level == sbt.util.Level.Warn) warnings += message
  }

  test("a dependency JAR's resources land in the APK root; classes, metadata and native libraries do not") {
    withTempDir { dir =>
      val apk = writeZip(new File(dir, "app.apk"), "AndroidManifest.xml" -> "manifest", "classes.dex" -> "dex")
      val jar = writeZip(
        new File(dir, "engine.jar"),
        "com/example/shaders/default.vertex.glsl" -> "shader",
        "com/example/Engine.class" -> "bytecode",
        "com/example/Engine.tasty" -> "tasty",
        "META-INF/MANIFEST.MF" -> "jar manifest",
        "native/android-aarch64/libengine.so" -> "elf",
        "AndroidManifest.xml" -> "a library's manifest"
      )

      val added = AndroidBuild.addDependencyResources(apk, Seq(jar), new RecordingLogger)

      val entries = readZip(apk)
      assertEquals(added, 1)
      assertEquals(entries.get("com/example/shaders/default.vertex.glsl"), Some("shader"))
      assertEquals(entries.get("AndroidManifest.xml"), Some("manifest"))
      assertEquals(entries.get("classes.dex"), Some("dex"))
      assert(!entries.contains("com/example/Engine.class"))
      assert(!entries.contains("com/example/Engine.tasty"))
      assert(!entries.contains("META-INF/MANIFEST.MF"))
      assert(!entries.contains("native/android-aarch64/libengine.so"))
    }
  }

  test("on a duplicate path the first JAR wins and the warning names both JARs") {
    withTempDir { dir =>
      val apk    = writeZip(new File(dir, "app.apk"), "classes.dex" -> "dex")
      val first  = writeZip(new File(dir, "first.jar"), "config/settings.txt" -> "from first")
      val second = writeZip(new File(dir, "second.jar"), "config/settings.txt" -> "from second")
      val log    = new RecordingLogger

      AndroidBuild.addDependencyResources(apk, Seq(first, second), log)

      assertEquals(readZip(apk).get("config/settings.txt"), Some("from first"))
      assertEquals(log.warnings.size, 1)
      assert(log.warnings.head.contains("first.jar") && log.warnings.head.contains("second.jar"), log.warnings.head)
    }
  }
}
