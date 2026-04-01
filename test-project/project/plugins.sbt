// The sbt-multi-arch-release plugin (must be publishLocal'd first)
addSbtPlugin("com.kubuszok" % "sbt-multi-arch-release" % sys.props.getOrElse("plugin.version", "0.1.0-SNAPSHOT"))

// Required dependencies for the plugin
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.10")
addSbtPlugin("com.eed3si9n"     % "sbt-projectmatrix" % "0.11.0")
