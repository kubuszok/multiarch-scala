/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * JVM test setup hook: no-op (resources are read straight from the classpath). */
package multiarch
package resources

object TestSetup {
  def init(): Unit = ()
}
