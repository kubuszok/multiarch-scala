/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Scala.js implementation of the cross-platform resource access mechanism.
 *
 * `Class.getResourceAsStream` is unavailable on Scala.js. Resolution order:
 *   1. The build-time-embedded resource registry (`EmbeddedResources`), populated by generated
 *      objects produced by `multiarch.sbt.EmbeddedResourcesGen`. Works regardless of the process
 *      working directory (published artifacts, browser bundles, ...).
 *   2. A Node `fs` fallback that reads from the sbt target/resource directories, retained so
 *      in-repo development and test resources keep working from the repository tree. */
package multiarch
package resources

import java.io.{ ByteArrayInputStream, InputStream }

import scala.scalajs.js
import scala.scalajs.js.typedarray.{ Int8Array, Uint8Array }

private[resources] object PlatformResourcesImpl {

  private lazy val fs:       js.Dynamic = js.Dynamic.global.require("fs")
  private lazy val nodePath: js.Dynamic = js.Dynamic.global.require("path")

  /** Base directories searched by the Node `fs` fallback, in order. Covers compiled target directories and source resource directories for development environments.
    */
  private val baseDirs: Array[String] = Array(
    "target/js-3/classes",
    "target/js-3/test-classes",
    "target/scala-2.13/classes",
    "target/scala-2.13/test-classes",
    "src/main/resources",
    "src/test/resources"
  )

  def getResourceAsStream(cls: Class[?], path: String): Option[InputStream] = {
    // Build-time-embedded resources first — independent of the working directory.
    val absPath = if (path.startsWith("/")) path else "/" + path
    EmbeddedResources.get(absPath) match {
      case Some(thunk) =>
        Some(new ByteArrayInputStream(thunk()): InputStream)
      case None =>
        // Fallback: Node fs lookup relative to the working directory (in-repo dev / test resources).
        val cleanPath = if (path.startsWith("/")) path.substring(1) else path
        var result: Option[InputStream] = None
        var i = 0
        while (i < baseDirs.length && result.isEmpty) {
          result = tryReadFile(baseDirs(i), cleanPath)
          i += 1
        }
        result
    }
  }

  private def tryReadFile(baseDir: String, cleanPath: String): Option[InputStream] =
    try {
      val filePath = nodePath.join(baseDir, cleanPath).asInstanceOf[String]
      if (fs.existsSync(filePath).asInstanceOf[Boolean]) {
        val buffer = fs.readFileSync(filePath)
        val uint8  = new Uint8Array(
          buffer.buffer.asInstanceOf[js.typedarray.ArrayBuffer],
          buffer.byteOffset.asInstanceOf[Int],
          buffer.length.asInstanceOf[Int]
        )
        val int8  = new Int8Array(uint8.buffer, uint8.byteOffset, uint8.length)
        val bytes = new Array[Byte](int8.length)
        var j     = 0
        while (j < bytes.length) {
          bytes(j) = int8(j)
          j += 1
        }
        Some(new ByteArrayInputStream(bytes): InputStream)
      } else {
        None
      }
    } catch {
      case _: Throwable => None
    }
}
