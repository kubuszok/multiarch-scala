package testapp

import sttp.client4.quick._

object Main {
  def main(args: Array[String]): Unit = {
    println("multiarch-scala native test application")
    println(s"Platform: ${System.getProperty("os.name", "native")} / ${System.getProperty("os.arch", "unknown")}")

    // Make a real HTTP request to verify curl native library works.
    // Use HTTP (not HTTPS) since the static curl build may lack a TLS backend.
    val response = quickRequest
      .get(uri"http://httpbin.org/get")
      .send()

    println(s"HTTP status: ${response.code}")
    if (response.code.isSuccess) {
      println("HTTP client test: OK")
    } else {
      println("HTTP client test: FAILED")
      sys.exit(1)
    }
    println("Done.")
  }
}
