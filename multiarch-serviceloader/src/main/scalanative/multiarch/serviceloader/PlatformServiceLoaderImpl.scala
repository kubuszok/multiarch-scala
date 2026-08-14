/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala Native implementation of the cross-platform service-provider lookup.
 *
 * Scala Native DOES implement `java.util.ServiceLoader`, but it cannot be reached from a generic
 * wrapper: `load` is a toolchain intrinsic resolved at LINK time, and the compiler rejects any call
 * whose argument is not a literal class:
 *
 * {{{
 * Limitation of ScalaNative runtime: first argument of method load needs to be literal constant
 * of class type, use `classOf[T]` instead.
 * }}}
 *
 * That is a compile error for `ServiceLoader.load(service)` — and for `classOf[T]` at an abstract
 * `T` — so no `Class`-taking API can delegate to it. (This is a fact about the toolchain, measured
 * against Scala Native 0.5.12; the link-time provider enlistment
 * `nativeConfig.withServiceProviders(...)` exists for code that CAN write the literal, i.e. direct
 * `java.util.ServiceLoader.load(classOf[Concrete])` calls, and is therefore not what this module
 * needs.)
 *
 * So this platform resolves providers exactly the way Scala.js does: through registration, from
 * build-time generated code (the sbt plugin's `ServiceProvidersGen` turns each
 * `META-INF/services` descriptor line into a `register(classOf[<service>], new <provider>())` call)
 * or from a hand-written `PlatformServiceLoader.register`. Those are ordinary source-level
 * references, so Scala Native links the providers with no enlistment and a descriptor naming a class
 * that does not exist fails the COMPILE. */
package multiarch
package serviceloader

private[serviceloader] object PlatformServiceLoaderImpl {

  def discovered[T](service: Class[T]): () => Iterator[T] = {
    val _ = service // java.util.ServiceLoader is unreachable from a generic wrapper (see above)
    () => Iterator.empty
  }
}
