package testapp

object Main {
  def main(args: Array[String]): Unit = {
    println("multiarch-scala jlink test application")
    println(s"Platform: ${System.getProperty("os.name", "unknown")} / ${System.getProperty("os.arch", "unknown")}")
    println("OK")
  }
}
