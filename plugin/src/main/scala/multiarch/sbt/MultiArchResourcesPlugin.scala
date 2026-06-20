/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Opt-in helper for the Scala.js half of the shared resource-access mechanism.
 *
 * On the JVM and Scala Native, `multiarch.resources.PlatformResources` reads classpath resources
 * directly and needs no build-time step. On Scala.js there is no classpath, so resources must be
 * embedded at build time. This object exposes a reusable `sourceGenerators` settings helper that a
 * consumer adds to the JS axis of its `projectMatrix`; it runs `EmbeddedResourcesGen` over a
 * configured resource directory and emits a self-registering generated object that the runtime
 * Scala.js `PlatformResourcesImpl` consults.
 *
 * Usage in a consumer build (Scala.js axis only):
 * {{{
 *   import multiarch.sbt.MultiArchResourcesPlugin
 *
 *   lazy val myLib = (projectMatrix in file("my-lib"))
 *     .jvmPlatform(scalaVersions)
 *     .nativePlatform(scalaVersions)
 *     .jsPlatform(scalaVersions)
 *     .someVariations(scalas, platforms)(Seq(
 *       MatrixAction.ForPlatforms(VirtualAxis.js).Configure(_.settings(
 *         MultiArchResourcesPlugin.embeddedResourcesSettings(
 *           objectName = "my.lib.GeneratedEmbeddedResources"
 *         )
 *       ))
 *     ) *)
 *     .dependsOn(multiarchResources)
 * }}}
 *
 * The generated object self-registers at its initializer, so reference it once from the JS entry
 * point (or `import` it) to defeat Scala.js dead-code elimination:
 * {{{ val _ = my.lib.GeneratedEmbeddedResources }}}
 */
package multiarch.sbt

import sbt._
import sbt.Keys._

object MultiArchResourcesPlugin {

  /** Default fully-qualified name of the generated, self-registering embedded-resources object. */
  val DefaultObjectName: String = "multiarch.resources.GeneratedEmbeddedResources"

  /** Settings that add a `Compile / sourceGenerators` entry embedding `resourceDir` into a generated object named `objectName`. Intended for the Scala.js axis only.
    *
    * @param resourceDir
    *   directory whose files are embedded; defaults to the module's `Compile` main resources directory
    * @param objectName
    *   fully-qualified name of the generated object
    */
  def embeddedResourcesSettings(
    resourceDir: Def.Initialize[File] = Def.setting((Compile / resourceDirectory).value),
    objectName:  String = DefaultObjectName
  ): Seq[Setting[?]] = Seq(
    Compile / sourceGenerators += Def.task {
      val dir      = resourceDir.value
      val segments = objectName.split('.').toSeq
      // Place the generated file under sourceManaged following the package path, e.g.
      // multiarch/resources/GeneratedEmbeddedResources.scala
      val dirPart = segments.init.foldLeft((Compile / sourceManaged).value)(_ / _)
      val outFile = dirPart / (segments.last + ".scala")
      val log     = streams.value.log
      // Wrap in Compat.uncached so the captured File result is not subject to sbt-2.0 task caching
      // (which would otherwise require a HashWriter for the custom return type).
      Compat.uncached(Seq(EmbeddedResourcesGen.generate(dir, outFile, objectName, log)))
    }.taskValue
  )
}
