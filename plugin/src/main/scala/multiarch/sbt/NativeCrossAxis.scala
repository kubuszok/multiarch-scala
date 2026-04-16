package multiarch.sbt

import sbt.VirtualAxis

/** Custom [[VirtualAxis]] for cross-compiled Scala Native targets.
  *
  * Each non-host platform gets a separate sbt subproject with a stable ID suffix
  * derived from the platform classifier. For example, building for `linux-x86_64`
  * from macOS produces a subproject named `myAppNativeLinuxX86_64`.
  *
  * Used by [[ProjectMatrixOps.Ops.withCrossNative]] to register cross-native rows
  * on a `projectMatrix`.
  */
final case class NativeCrossAxis(platform: Platform) extends VirtualAxis.WeakAxis {
  val idSuffix: String        = "Native" + platform.classifier.split('-').map(_.capitalize).mkString
  val directorySuffix: String = "native-" + platform.classifier
}
