/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Cross-platform tests for the shared resource-access mechanism. The JVM (and Native) paths read
 * the test resource from the classpath; the Scala.js path reads it through the embedded map /
 * Node fs fallback. */
package multiarch
package resources

class PlatformResourcesSpec extends munit.FunSuite {

  // Platform-specific setup: on Scala.js this references the build-time-generated, self-registering
  // embedded-resources object so its initializer runs. No-op on JVM/Native.
  override def beforeAll(): Unit = TestSetup.init()

  private val path = "/multiarch/resources/test-resource.txt"

  test("getResourceAsString reads a classpath resource") {
    val content = PlatformResources.getResourceAsString(classOf[PlatformResourcesSpec], path)
    assertEquals(content.map(_.trim), Some("hello from multiarch-resources"))
  }

  test("getResourceBytes returns the resource bytes") {
    val bytes = PlatformResources.getResourceBytes(classOf[PlatformResourcesSpec], path)
    assert(bytes.isDefined, "expected resource bytes to be defined")
    assert(bytes.get.nonEmpty, "expected non-empty resource bytes")
  }

  test("getResourceAsStream returns a readable stream") {
    val stream = PlatformResources.getResourceAsStream(classOf[PlatformResourcesSpec], path)
    assert(stream.isDefined, "expected a stream")
    stream.foreach(_.close())
  }

  test("resourceExists is true for an existing resource") {
    assert(PlatformResources.resourceExists(classOf[PlatformResourcesSpec], path))
  }

  test("missing resource yields None / false") {
    val missing = "/multiarch/resources/does-not-exist.bin"
    assertEquals(PlatformResources.getResourceAsStream(classOf[PlatformResourcesSpec], missing), None)
    assertEquals(PlatformResources.getResourceBytes(classOf[PlatformResourcesSpec], missing), None)
    assertEquals(PlatformResources.resourceExists(classOf[PlatformResourcesSpec], missing), false)
  }
}
