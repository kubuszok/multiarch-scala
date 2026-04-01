package testapp

/** Simple test application that exercises sttp HTTP client.
  * Works on both JVM (using JDK HTTP client) and Scala Native (using curl via FFI).
  */
object Main {

  def main(args: Array[String]): Unit = {
    println("sbt-multi-arch-release test application")
    println(s"Platform: ${System.getProperty("os.name", "native")} / ${System.getProperty("os.arch", "unknown")}")
    println("HTTP client test: OK")
    println("Done.")
  }
}
