/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala.js test setup hook: reference the build-time-generated, self-registering registration object
 * so its initializer runs (registering the META-INF/services providers) and Scala.js dead-code
 * elimination keeps it. Mirrors what a real consumer does from its JS entry point. */
package multiarch
package serviceloader

object TestSetup {
  def init(): Unit = {
    val _ = TestGeneratedServiceProviders
    ()
  }
}
