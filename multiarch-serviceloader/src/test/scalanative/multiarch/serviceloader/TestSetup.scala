/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala Native test setup hook: reference the build-time-generated, self-registering registration
 * object so its initializer runs. Scala Native has `java.util.ServiceLoader` but its `load` is a
 * link-time intrinsic that only accepts a literal `classOf`, so this platform resolves providers
 * through registration exactly as Scala.js does — see the Native `PlatformServiceLoaderImpl`. */
package multiarch
package serviceloader

object TestSetup {
  def init(): Unit = {
    val _ = TestGeneratedServiceProviders
    ()
  }
}
