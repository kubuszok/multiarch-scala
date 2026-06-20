/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala.js test setup hook: reference the build-time-generated, self-registering embedded-resources
 * object so its initializer runs (registering resources into `EmbeddedResources`) and Scala.js
 * dead-code elimination keeps it. Mirrors what a real consumer does from its JS entry point. */
package multiarch
package resources

object TestSetup {
  def init(): Unit = {
    val _ = TestEmbeddedResources
    ()
  }
}
