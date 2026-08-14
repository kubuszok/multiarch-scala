/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Unit test for the META-INF/services reader and the build-time provider-registration generator.
 * Covers the JDK descriptor format (comments, blank lines, trailing comments), the emitted source's
 * shape, and the two degenerate inputs — no services directory and a descriptor with no lines. */
package multiarch.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ServiceProvidersGenSpec extends munit.FunSuite {

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("multiarch-svc-gen").toFile
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

  private def descriptor(resDir: File, service: String, content: String): Unit = {
    val servicesDir = new File(new File(resDir, "META-INF"), "services")
    servicesDir.mkdirs()
    Files.write(new File(servicesDir, service).toPath, content.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private val log: sbt.util.Logger = sbt.util.Logger.Null

  test("read parses the JDK descriptor format: comments, blank lines, trailing comments") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      descriptor(
        resDir,
        "p.Service",
        "# leading comment\np.First\n\n   p.Second   # trailing comment\n#p.Commented\n"
      )

      assertEquals(ServiceDescriptors.read(resDir), Seq("p.Service" -> Seq("p.First", "p.Second")))
    }
  }

  test("read returns nothing when there is no META-INF/services directory") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      resDir.mkdirs()
      assertEquals(ServiceDescriptors.read(resDir), Seq.empty[(String, Seq[String])])
    }
  }

  test("read sorts services by name so the generated source is deterministic") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      descriptor(resDir, "p.Zeta", "p.Z")
      descriptor(resDir, "p.Alpha", "p.A")
      assertEquals(ServiceDescriptors.read(resDir).map(_._1), Seq("p.Alpha", "p.Zeta"))
    }
  }

  test("emits a self-registering object with one register call per provider line") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      descriptor(resDir, "p.Codec", "p.JsonCodec\np.XmlCodec\n")

      val out = new File(dir, "Gen.scala")
      ServiceProvidersGen.generate(resDir, out, "my.pkg.GeneratedServiceProviders", log)
      val src = new String(Files.readAllBytes(out.toPath), StandardCharsets.UTF_8)

      assert(src.contains("package my.pkg"), src)
      assert(src.contains("object GeneratedServiceProviders"), src)
      assert(src.contains("import multiarch.serviceloader.PlatformServiceLoader"), src)
      assert(src.contains("PlatformServiceLoader.register(classOf[p.Codec], new p.JsonCodec())"), src)
      assert(src.contains("PlatformServiceLoader.register(classOf[p.Codec], new p.XmlCodec())"), src)
    }
  }

  test("spells a nested class the Scala way, not the binary way") {
    withTempDir { dir =>
      val resDir = new File(dir, "res")
      descriptor(resDir, "p.Outer$Service", "p.Outer$Impl")

      val out = new File(dir, "Gen.scala")
      ServiceProvidersGen.generate(resDir, out, "p.Gen", log)
      val src = new String(Files.readAllBytes(out.toPath), StandardCharsets.UTF_8)

      assert(src.contains("classOf[p.Outer.Service]"), src)
      assert(src.contains("new p.Outer.Impl()"), src)
    }
  }

  test("no descriptors and an empty descriptor both yield a compilable, registration-free object") {
    withTempDir { dir =>
      val emptyRoot = new File(dir, "none")
      emptyRoot.mkdirs()
      val out1 = new File(dir, "None.scala")
      ServiceProvidersGen.generate(emptyRoot, out1, "p.Gen", log)
      val src1 = new String(Files.readAllBytes(out1.toPath), StandardCharsets.UTF_8)
      assert(src1.contains("object Gen {"), src1)
      assert(!src1.contains("register("), src1)

      val resDir = new File(dir, "res")
      descriptor(resDir, "p.Service", "# nothing but a comment\n")
      val out2 = new File(dir, "Empty.scala")
      ServiceProvidersGen.generate(resDir, out2, "p.Gen", log)
      val src2 = new String(Files.readAllBytes(out2.toPath), StandardCharsets.UTF_8)
      assert(src2.contains("(no provider lines)"), src2)
      assert(!src2.contains("register("), src2)
    }
  }
}
