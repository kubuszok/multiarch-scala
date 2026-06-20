/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Unit test for the build-time embedded-resources generator. Verifies the emitted Scala source has
 * the expected shape: package/object, a self-registering call into EmbeddedResources, a `resources`
 * map keyed by the classpath-absolute path, and that large inputs are chunked under the JVM string
 * constant limit. */
package multiarch.sbt

import java.io.File
import java.nio.file.Files
import java.util.Base64

class EmbeddedResourcesGenSpec extends munit.FunSuite {

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("multiarch-res-gen").toFile
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

  private val log: sbt.util.Logger = sbt.util.Logger.Null

  test("emits a self-registering object with a resources map keyed by classpath-absolute path") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      val sub    = new File(resDir, "data")
      sub.mkdirs()
      Files.write(new File(resDir, "top.txt").toPath, "TOP".getBytes("UTF-8"))
      Files.write(new File(sub, "nested.bin").toPath, Array[Byte](1, 2, 3))

      val out = new File(dir, "Gen.scala")
      EmbeddedResourcesGen.generate(resDir, out, "my.pkg.GeneratedEmbeddedResources", log)

      val src = new String(Files.readAllBytes(out.toPath), "UTF-8")
      assert(src.contains("package my.pkg"), src)
      assert(src.contains("object GeneratedEmbeddedResources"), src)
      assert(src.contains("import multiarch.resources.EmbeddedResources"), src)
      assert(src.contains("EmbeddedResources.register(resources)"), src)
      assert(src.contains("\"/top.txt\" ->"), src)
      assert(src.contains("\"/data/nested.bin\" ->"), src)
    }
  }

  test("base64-encodes content recoverably") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      resDir.mkdirs()
      val content = "the quick brown fox".getBytes("UTF-8")
      Files.write(new File(resDir, "a.txt").toPath, content)

      val out = new File(dir, "Gen.scala")
      EmbeddedResourcesGen.generate(resDir, out, "p.Gen", log)
      val src = new String(Files.readAllBytes(out.toPath), "UTF-8")

      // Extract the single appended base64 literal and verify it decodes to the original bytes.
      val marker = "b.append(\""
      val start  = src.indexOf(marker) + marker.length
      val end    = src.indexOf("\")", start)
      val b64    = src.substring(start, end)
      assertEquals(new String(Base64.getDecoder.decode(b64), "UTF-8"), "the quick brown fox")
    }
  }

  test("chunks large inputs into multiple <=40000-char string literals") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      resDir.mkdirs()
      // 100KB of bytes -> ~133KB base64 -> must be split into >= 4 chunks.
      val big = new Array[Byte](100000)
      var i   = 0
      while (i < big.length) { big(i) = (i % 251).toByte; i += 1 }
      Files.write(new File(resDir, "big.dat").toPath, big)

      val out = new File(dir, "Gen.scala")
      EmbeddedResourcesGen.generate(resDir, out, "p.Gen", log)
      val src = new String(Files.readAllBytes(out.toPath), "UTF-8")

      val chunkRegex = "b\\.append\\(\"([^\"]*)\"\\)".r
      val chunks     = chunkRegex.findAllMatchIn(src).toList
      assert(chunks.length >= 4, s"expected >= 4 chunks, got ${chunks.length}")
      // No single literal exceeds the 40000-char chunk size.
      chunks.foreach { m =>
        assert(m.group(1).length <= 40000, s"chunk too large: ${m.group(1).length}")
      }
    }
  }

  test("empty resource directory yields an empty resources map") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      resDir.mkdirs()
      val out = new File(dir, "Gen.scala")
      EmbeddedResourcesGen.generate(resDir, out, "p.Gen", log)
      val src = new String(Files.readAllBytes(out.toPath), "UTF-8")
      assert(src.contains("val resources: Map[String, () => Array[Byte]] = Map(\n  )"), src)
    }
  }
}
