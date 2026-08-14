/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Fixture services and providers shared by every platform axis of the spec.
 *
 *   - `TestService` is declared in `src/test/resources/META-INF/services/multiarch.serviceloader.TestService`
 *     and is therefore the DISCOVERY fixture: the JVM reads that descriptor off the test classpath,
 *     Scala Native links the providers the same descriptor enlists, and Scala.js registers them from
 *     code the build generated out of it. One descriptor, three mechanisms, one assertion.
 *   - `RegisteredService` is in no descriptor and exercises explicit registration.
 *   - `CountedService` counts its own instantiations, which is how the spec observes laziness. */
package multiarch
package serviceloader

trait TestService {
  def name: String
}

class AlphaProvider extends TestService {
  def name: String = "alpha"
}

class BetaProvider extends TestService {
  def name: String = "beta"
}

trait RegisteredService {
  def id: Int
}

class RegisteredProvider extends RegisteredService {
  def id: Int = 7
}

trait CountedService

object CountedService {
  var instantiations: Int = 0
}

class CountedProvider extends CountedService {
  CountedService.instantiations += 1
}

/** A service nothing provides — the empty-result fixture. */
trait UnprovidedService
