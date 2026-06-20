/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala Native test setup hook: no-op (resources are read straight from the classpath). */
package multiarch
package resources

object TestSetup {
  def init(): Unit = ()
}
