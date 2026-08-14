/* Copyright (c) 2026 multiarch-scala contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * JVM implementation of the cross-platform service-provider lookup.
 *
 * `java.util.ServiceLoader` is fully supported on the JVM, so this is a straight delegation:
 * `load` creates exactly one `java.util.ServiceLoader` (using the same thread-context classloader
 * `ServiceLoader.load(Class)` itself uses) and every `iterator()` call goes to that instance — which
 * is what preserves the JDK's per-loader provider caching. */
package multiarch
package serviceloader

private[serviceloader] object PlatformServiceLoaderImpl {

  def discovered[T](service: Class[T]): () => Iterator[T] = {
    val loader = java.util.ServiceLoader.load(service)
    () => asScala(loader.iterator())
  }

  // `scala.jdk.CollectionConverters` would do, but a five-line adapter keeps the module free of any
  // assumption about which converter API the target Scala version ships.
  private def asScala[T](it: java.util.Iterator[T]): Iterator[T] = new Iterator[T] {
    def hasNext: Boolean = it.hasNext
    def next():  T       = it.next()
  }
}
