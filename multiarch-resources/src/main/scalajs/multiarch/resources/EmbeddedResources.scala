/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala.js embedded-resources registry.
 *
 * Scala.js has no classpath, so `Class.getResourceAsStream` cannot resolve resources. Instead the
 * build-time generator (`multiarch.sbt.EmbeddedResourcesGen`, run by `MultiArchResourcesPlugin`)
 * base64-embeds a resource directory into a GENERATED Scala object whose body calls
 * `EmbeddedResources.register(...)`. The runtime Scala.js `PlatformResourcesImpl` consults this
 * registry first, falling back to a Node `fs` lookup for development.
 *
 * Registration is idempotent and additive: multiple generated objects (e.g. from several modules)
 * can each contribute their own resources. */
package multiarch
package resources

object EmbeddedResources {

  // Classpath-absolute resource path -> thunk producing freshly-decoded bytes.
  // A var (not a val) so generated objects can register additively at their initialization.
  private var registry: Map[String, () => Array[Byte]] = Map.empty

  /** Register a batch of embedded resources. Called from generated code; safe to call repeatedly. */
  def register(entries: Map[String, () => Array[Byte]]): Unit =
    registry = registry ++ entries

  /** Look up an embedded resource by its classpath-absolute path (leading `/`). */
  def get(path: String): Option[() => Array[Byte]] =
    registry.get(path)

  /** All registered resource paths. */
  def keys: Iterable[String] =
    registry.keys
}
