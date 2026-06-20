package multiarch.sbt

import sbt.{ Attributed, Def }

import java.io.File

/** sbt-1.x (Scala 2.12) axis compatibility shim.
  *
  *   - `ProjectMatrix` lives in `sbt.internal` on sbt 1.x (sbt-projectmatrix plugin).
  *   - sbt 1.x has no task/setting result caching, so `uncached` is the identity.
  *   - On sbt 1.x the classpath/file tasks already yield `java.io.File`, so the `toFiles` / `toFile` converters are trivial and ignore the `FileConverter`.
  */
private[sbt] object Compat {

  type ProjectMatrix = sbt.internal.ProjectMatrix

  type FileConverter = xsbti.FileConverter

  def uncached[A](a: A): A = a

  /** Convert a classpath (already `Attributed[File]` on sbt 1.x) to plain files. */
  def toFiles(cp: Seq[Attributed[File]])(conv: FileConverter): Seq[File] = cp.map(_.data)

  /** Identity on sbt 1.x — file tasks already return `java.io.File`. */
  def toFile(f: File)(conv: FileConverter): File = f

  /** Build classpath entries (e.g. for `unmanagedJars`) from plain files. On sbt 1.x the classpath element type is `Attributed[File]`.
    */
  def blankJars(files: Seq[File])(conv: FileConverter): Seq[Attributed[File]] =
    files.map(Attributed.blank)
}
