/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * JVM test setup hook: no-op (java.util.ServiceLoader reads META-INF/services off the classpath). */
package multiarch
package serviceloader

object TestSetup {
  def init(): Unit = ()
}
