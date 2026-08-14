/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Reader for `META-INF/services/<service>` provider-configuration files.
 *
 * One parser, two consumers: `ServiceProvidersGen` turns the result into Scala.js registration code,
 * and `MultiArchServiceLoaderPlugin.nativeServiceProvidersSettings` turns the SAME result into Scala
 * Native's `nativeConfig.withServiceProviders(...)` enlistment. Both platforms therefore see exactly
 * the descriptors the JVM classpath would, and cannot disagree about them. */
package multiarch.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object ServiceDescriptors {

  /** Directory holding the provider-configuration files, relative to a resource root. */
  val ServicesPath: String = "META-INF/services"

  /** Read every provider-configuration file under `<resourceDir>/META-INF/services`.
    *
    * The file format is the JDK's own (`java.util.ServiceLoader` javadoc): UTF-8, one provider class name per line, `#` starts a comment that runs to the end of the line, blank lines are ignored. The
    * file NAME is the fully-qualified name of the service.
    *
    * @param resourceDir
    *   a resource root, e.g. `src/main/resources` — NOT the `META-INF/services` directory itself
    * @return
    *   service name -> provider class names, in file order, with services sorted for determinism; empty when the directory does not exist
    */
  def read(resourceDir: File): Seq[(String, Seq[String])] = {
    val servicesDir = ServicesPath.split('/').foldLeft(resourceDir)(new File(_, _))
    if (!servicesDir.isDirectory) Seq.empty
    else {
      Option(servicesDir.listFiles()).getOrElse(Array.empty[File]).toSeq.filter(_.isFile).sortBy(_.getName).map(file => file.getName -> providersIn(file))
    }
  }

  /** Parse one provider-configuration file into its provider class names.
    *
    * The leading UTF-8 BOM is stripped before line-splitting: `U+FEFF` is not whitespace, so left in place it becomes part of the FIRST provider's name and the generator emits `new <bom>com.foo.X()`
    * — a compile error in a generated file whose message never names the cause.
    */
  def providersIn(file: File): Seq[String] =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).stripPrefix("\uFEFF").linesIterator.map(stripComment).map(_.trim).filter(_.nonEmpty).toVector

  private def stripComment(line: String): String = {
    val hash = line.indexOf('#')
    if (hash < 0) line else line.substring(0, hash)
  }
}
