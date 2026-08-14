/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Shared cross-platform service-provider lookup.
 *
 * `PlatformServiceLoader` gives JVM, Scala.js and Scala Native a uniform replacement for
 * `java.util.ServiceLoader`, whose availability differs per platform:
 *
 *   - JVM: `java.util.ServiceLoader` exists and is used directly.
 *   - Scala.js: `java.util.ServiceLoader` DOES NOT EXIST — referencing it does not compile.
 *   - Scala Native: it exists, but `load` is a link-time intrinsic that only accepts a LITERAL
 *     `classOf`, so no `Class`-taking wrapper can call it (its `nativeConfig.withServiceProviders`
 *     enlistment is for direct `ServiceLoader.load(classOf[Concrete])` call sites, which this module
 *     deliberately is not).
 *
 * The two non-JVM platforms therefore resolve providers by REGISTRATION: either from build-time
 * generated code (the sbt plugin's `ServiceProvidersGen` turns each `META-INF/services` descriptor
 * into `register` calls) or by hand via [[register]].
 *
 * The API deliberately uses plain Scala types (`Class`, `Iterator`, `List`) so the module stays
 * self-contained with no third-party dependency.
 */
package multiarch
package serviceloader

object PlatformServiceLoader {

  /** Look up the providers of `service`.
    *
    * The result is a handle, not a snapshot: nothing is instantiated until it is iterated, which mirrors `java.util.ServiceLoader.load(...)` on the JVM (where this call creates exactly one underlying
    * `java.util.ServiceLoader`, so its per-loader instance caching is preserved).
    *
    * @param service
    *   the service interface or abstract class, e.g. `classOf[my.Codec]`
    */
  def load[T](service: Class[T]): ServiceProviders[T] =
    new ServiceProviders[T](service, PlatformServiceLoaderImpl.discovered(service))

  /** Register a provider of `service` explicitly.
    *
    * This is the only way to supply providers on Scala.js, and it works on every platform — a registered provider is returned by every subsequent [[load]] of the same service, AFTER the providers the
    * platform discovered on its own. Generated registration code (see the sbt plugin's `ServiceProvidersGen`) calls exactly this method.
    *
    * `provider` is by-name and is evaluated once per iteration, so each traversal of a [[ServiceProviders]] yields fresh instances of explicitly registered providers.
    *
    * Registration is additive and never replaces an earlier one: registering the same provider twice makes it appear twice.
    *
    * @param service
    *   the service interface or abstract class the provider implements
    * @param provider
    *   expression constructing a provider instance, e.g. `new my.JsonCodec`
    */
  def register[T](service: Class[T], provider: => T): Unit =
    ServiceRegistry.add(service, () => provider)
}

/** The providers of one service, as returned by [[PlatformServiceLoader.load]].
  *
  * Deliberately NOT a `scala.collection.Iterable`: this is the cross-platform stand-in for `java.util.ServiceLoader`, whose only member the port of a Java library needs is `iterator()`, and
  * inheriting a collection trait would import hundreds of members that say nothing about service loading.
  */
final class ServiceProviders[T] private[serviceloader] (
  service:    Class[T],
  discovered: () => Iterator[T]
) {

  /** A fresh iterator over the providers: those the platform discovered first, then those registered through [[PlatformServiceLoader.register]], in registration order.
    *
    * Spelled with an empty parameter list (Java's arity) because this is the drop-in for `java.util.ServiceLoader#iterator()`.
    */
  def iterator(): Iterator[T] =
    discovered() ++ ServiceRegistry.iteratorFor(service)

  /** All providers, in [[iterator]] order. The idiomatic Scala shape — Java callers of `ServiceLoader` almost always copy the iterator into a list. */
  def toList: List[T] =
    iterator().toList
}

/** Cross-platform registry of explicitly registered providers.
  *
  * Kept internal: generated code and consumers go through `PlatformServiceLoader.register`, so the registry's representation is not part of the module's binary surface.
  */
private[serviceloader] object ServiceRegistry {

  // Registration happens from object initializers (generated code) and can happen from application
  // code on the JVM, so writes are synchronized and the reference is volatile. The map itself is
  // immutable, so readers never see a half-built one.
  @volatile private var registry: Map[Class[?], List[() => Any]] = Map.empty

  def add(service: Class[?], provider: () => Any): Unit = synchronized {
    registry = registry.updated(service, registry.getOrElse(service, Nil) :+ provider)
  }

  def iteratorFor[T](service: Class[T]): Iterator[T] =
    registry.getOrElse(service, Nil).iterator.map(thunk => thunk().asInstanceOf[T])
}
