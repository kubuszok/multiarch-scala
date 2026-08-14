/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Opt-in build wiring for the non-JVM halves of `multiarch.serviceloader.PlatformServiceLoader`.
 *
 * The JVM needs nothing: `java.util.ServiceLoader` reads `META-INF/services` off the classpath at
 * run time. Scala.js and Scala Native cannot, for two different reasons that have one answer:
 *
 *   - Scala.js has no `java.util.ServiceLoader` at all — referencing it does not compile;
 *   - Scala Native HAS it, but `load` is a link-time intrinsic that only accepts a LITERAL
 *     `classOf`, so no `Class`-taking wrapper can call it (its `nativeConfig.withServiceProviders`
 *     enlistment exists for direct `ServiceLoader.load(classOf[Concrete])` call sites, which this
 *     module deliberately is not).
 *
 * So both platforms resolve providers by REGISTRATION, and [[embeddedServiceProvidersSettings]]
 * generates that registration code from the very same `META-INF/services` descriptors the JVM reads.
 * A consumer therefore declares its providers once, in the JDK's own format, and never restates them
 * in the build.
 *
 * Usage in a consumer build (a `projectMatrix` with JS and/or Native axes):
 * {{{
 *   import multiarch.sbt.MultiArchServiceLoaderPlugin
 *
 *   val generated = MultiArchServiceLoaderPlugin.embeddedServiceProvidersSettings(
 *     objectName = "my.lib.GeneratedServiceProviders"
 *   )
 *
 *   lazy val myLib = (projectMatrix in file("my-lib"))
 *     .jvmPlatform(scalaVersions)
 *     .jsPlatform(scalaVersions, settings = generated)
 *     .nativePlatform(scalaVersions, settings = generated)
 *     .dependsOn(multiarchServiceLoader)
 * }}}
 *
 * The generated object self-registers at its initializer, so reference it once from the entry point
 * to defeat dead-code elimination:
 * {{{ val _ = my.lib.GeneratedServiceProviders }}}
 */
package multiarch.sbt

import sbt._
import sbt.Keys._

object MultiArchServiceLoaderPlugin {

  /** Default fully-qualified name of the generated, self-registering provider-registration object. */
  val DefaultObjectName: String = "multiarch.serviceloader.GeneratedServiceProviders"

  /** Settings that add a `sourceGenerators` entry registering the providers declared under `<resourceDir>/META-INF/services`. Intended for the Scala.js and Scala Native axes — the JVM axis discovers
    * the same descriptors itself, at run time.
    *
    * @param resourceDir
    *   resource root whose `META-INF/services` is read; defaults to the module's `Compile` main resources directory
    * @param objectName
    *   fully-qualified name of the generated object
    * @param config
    *   configuration the generated source belongs to. `Compile` (the default) is what a library shipping providers wants; `Test` keeps generated fixture registrations out of the published artifact
    */
  def embeddedServiceProvidersSettings(
    resourceDir: Def.Initialize[File] = Def.setting((Compile / resourceDirectory).value),
    objectName:  String = DefaultObjectName,
    config:      Configuration = Compile
  ): Seq[Setting[?]] = Seq(
    config / sourceGenerators += Def.task {
      val dir      = resourceDir.value
      val segments = objectName.split('.').toSeq
      // Place the generated file under sourceManaged following the package path, e.g.
      // multiarch/serviceloader/GeneratedServiceProviders.scala
      val dirPart = segments.init.foldLeft((config / sourceManaged).value)(_ / _)
      val outFile = dirPart / (segments.last + ".scala")
      val log     = streams.value.log
      // Wrap in Compat.uncached so the captured File result is not subject to sbt-2.0 task caching
      // (which would otherwise require a HashWriter for the custom return type).
      Compat.uncached(Seq(ServiceProvidersGen.generate(dir, outFile, objectName, log)))
    }.taskValue
  )
}
