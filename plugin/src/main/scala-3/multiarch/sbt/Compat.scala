package multiarch.sbt

import sbt.{ Attributed, Def }

import java.io.File

/** sbt-2.0 (Scala 3) axis compatibility shim.
  *
  *   - `ProjectMatrix` was merged into sbt itself, so it lives in the `sbt` package (not `sbt.internal` as on the sbt-1.x axis).
  *   - sbt 2.0 caches task/setting results and requires every captured value to have a `sjsonnew.HashWriter`. For values that intentionally should not be cached (custom enums such as `Platform`,
  *     `UpdateReport`-derived results, ...), opt out via `Def.uncached`.
  *   - sbt 2.0 classpath/file tasks yield `xsbti.HashedVirtualFileRef`, so `toFiles` / `toFile` materialize real `java.io.File`s through the build's `FileConverter`.
  */
private[sbt] object Compat {

  type ProjectMatrix = sbt.ProjectMatrix

  type FileConverter = xsbti.FileConverter

  inline def uncached[A](inline a: A): A = Def.uncached(a)

  /** Materialize a classpath of `HashedVirtualFileRef`s into plain files. */
  def toFiles(cp: Seq[Attributed[xsbti.HashedVirtualFileRef]])(conv: FileConverter): Seq[File] =
    cp.map(a => conv.toPath(a.data).toFile)

  /** Materialize a single file-typed task result into a plain file. */
  def toFile(ref: xsbti.HashedVirtualFileRef)(conv: FileConverter): File =
    conv.toPath(ref).toFile

  /** Build classpath entries (e.g. for `unmanagedJars`) from plain files. On sbt 2.0 the classpath element type is `Attributed[HashedVirtualFileRef]`, so files are routed through the build's
    * `FileConverter`.
    */
  def blankJars(files: Seq[File])(conv: FileConverter): Seq[Attributed[xsbti.HashedVirtualFileRef]] =
    files.map(f => Attributed.blank(conv.toVirtualFile(f.toPath)))
}
