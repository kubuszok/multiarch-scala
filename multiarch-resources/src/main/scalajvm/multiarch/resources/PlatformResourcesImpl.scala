/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * JVM implementation of the cross-platform resource access mechanism.
 * `Class.getResourceAsStream` is fully supported on the JVM. */
package multiarch
package resources

import java.io.InputStream

private[resources] object PlatformResourcesImpl {

  def getResourceAsStream(cls: Class[?], path: String): Option[InputStream] =
    Option(cls.getResourceAsStream(path))
}
