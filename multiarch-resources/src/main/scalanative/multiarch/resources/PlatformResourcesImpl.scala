/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala Native implementation of the cross-platform resource access mechanism.
 * `Class.getResourceAsStream` is supported on Scala Native when embedded resources are enabled
 * via `nativeConfig.withEmbedResources(true)`. */
package multiarch
package resources

import java.io.InputStream

private[resources] object PlatformResourcesImpl {

  def getResourceAsStream(cls: Class[?], path: String): Option[InputStream] =
    Option(cls.getResourceAsStream(path))
}
