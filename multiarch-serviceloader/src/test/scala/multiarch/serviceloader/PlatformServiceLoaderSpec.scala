/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Cross-platform tests for the shared service-provider lookup. The same assertions run on all three
 * platforms over three different mechanisms: the JVM reads `META-INF/services` off the classpath,
 * Scala Native links the providers the build enlisted from that same file, and Scala.js resolves the
 * registrations the build generated from it. */
package multiarch
package serviceloader

class PlatformServiceLoaderSpec extends munit.FunSuite {

  // Platform-specific setup: on Scala.js this references the build-time-generated, self-registering
  // registration object so its initializer runs. No-op on JVM/Native, which discover for themselves.
  override def beforeAll(): Unit = {
    TestSetup.init()
    PlatformServiceLoader.register(classOf[RegisteredService], new RegisteredProvider)
    PlatformServiceLoader.register(classOf[CountedService], new CountedProvider)
  }

  test("load finds the providers declared in META-INF/services") {
    val names = PlatformServiceLoader.load(classOf[TestService]).toList.map(_.name)
    assertEquals(names.sorted, List("alpha", "beta"))
  }

  test("iterator() yields the same providers — the java.util.ServiceLoader drop-in shape") {
    val names = PlatformServiceLoader.load(classOf[TestService]).iterator().map(_.name).toList
    assertEquals(names.sorted, List("alpha", "beta"))
  }

  test("iterator() can be taken more than once from one handle") {
    val handle = PlatformServiceLoader.load(classOf[TestService])
    assertEquals(handle.iterator().size, 2)
    assertEquals(handle.iterator().size, 2)
  }

  test("explicitly registered providers are returned on every platform") {
    assertEquals(PlatformServiceLoader.load(classOf[RegisteredService]).toList.map(_.id), List(7))
  }

  test("providers are instantiated on iteration, not on load") {
    val before = CountedService.instantiations
    val handle = PlatformServiceLoader.load(classOf[CountedService])
    assertEquals(CountedService.instantiations, before, "load must not instantiate anything")
    val instances = handle.toList
    assertEquals(instances.size, 1)
    assertEquals(CountedService.instantiations, before + 1)
  }

  test("a service with no providers yields an empty result") {
    assertEquals(PlatformServiceLoader.load(classOf[UnprovidedService]).toList, List.empty[UnprovidedService])
    assertEquals(PlatformServiceLoader.load(classOf[UnprovidedService]).iterator().hasNext, false)
  }
}
