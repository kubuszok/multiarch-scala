/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala.js implementation of the cross-platform service-provider lookup.
 *
 * `java.util.ServiceLoader` does not exist in the Scala.js javalib — referencing it is a compile
 * error, not a runtime one — and the Scala.js linker has nothing analogous to Scala Native's
 * `withServiceProviders`. There is therefore NO discovery step on this platform: every provider
 * arrives through `PlatformServiceLoader.register`, either from build-time generated code (the sbt
 * plugin's `ServiceProvidersGen` turns each `META-INF/services/<service>` line into a
 * `register(classOf[<service>], new <provider>)` call — a source-level reference, so a missing or
 * renamed provider fails the JS COMPILE) or from a hand-written call for consumers not using sbt.
 *
 * Returning an empty discovery iterator rather than throwing is deliberate: a service with no
 * providers is the JVM's own answer for a missing descriptor, so a JS build that registered nothing
 * behaves like a JVM run with nothing on the classpath. */
package multiarch
package serviceloader

private[serviceloader] object PlatformServiceLoaderImpl {

  def discovered[T](service: Class[T]): () => Iterator[T] = {
    val _ = service // no platform-level discovery exists on Scala.js
    () => Iterator.empty
  }
}
