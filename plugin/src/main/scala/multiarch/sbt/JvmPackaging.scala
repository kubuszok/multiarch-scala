package multiarch.sbt

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._

import java.net.URI
import java.nio.file.{ Files, StandardCopyOption }

/** Multi-architecture JVM application packaging.
  *
  * Two packaging modes are available:
  *
  * '''Simple mode''' (`releasePackage`) — produces a directory with launcher scripts
  * that rely on a system-installed JDK:
  * {{{
  * <app>/
  * ├── bin/
  * │   ├── <app>         (Unix launcher)
  * │   └── <app>.bat     (Windows launcher)
  * ├── lib/
  * │   └── *.jar         (application + dependency JARs)
  * └── native/
  *     └── *.{so,dylib,dll}  (platform shared libraries)
  * }}}
  *
  * '''Distribution mode''' (`releasePlatform`, `releaseAll`) — produces
  * self-contained archives per target platform, bundling a jlinked JRE and
  * a native Roast launcher. No system JDK required by end users:
  * {{{
  * <app>-linux-x86_64.tar.gz
  * <app>-macos-aarch64.tar.gz   (contains .app bundle)
  * <app>-windows-x86_64.zip
  * }}}
  *
  * Enable simple mode with `.settings(JvmPackaging.jvmSettings *)`.
  * Enable distribution mode by also adding `.settings(JvmPackaging.distSettings *)`.
  */
object JvmPackaging {

  // ── Keys: simple mode ─────────────────────────────────────────────

  val releasePackage   = taskKey[File]("Create a distributable application package (simple mode)")
  val releaseAppName   = settingKey[String]("Application display name for the package")
  val releaseNativeLibDirs = settingKey[Seq[File]]("Directories containing native shared libraries to bundle")

  // ── Keys: distribution mode ───────────────────────────────────────

  val releaseTargets          = settingKey[Map[Platform, String]]("Target platforms and their JDK download URLs")
  val releaseJlinkModules     = settingKey[Seq[String]]("Java modules to include in the jlinked runtime")
  val releaseRoastVersion     = settingKey[String]("Roast native launcher version")
  val releaseVmArgs           = settingKey[Seq[String]]("JVM arguments passed via Roast config")
  val releaseUseZgc           = settingKey[Boolean]("Enable ZGC garbage collector on supported platforms")
  val releaseMacOsBundleId    = settingKey[String]("macOS bundle identifier (e.g. com.example.MyGame)")
  val releaseMacOsIcon        = settingKey[Option[File]]("Path to .icns file for macOS app bundle")
  val releaseCacheDir         = settingKey[File]("Download cache directory for JDKs and Roast binaries")
  val releaseRunOnFirstThread = settingKey[Boolean]("Run JVM on first thread (required for macOS graphics)")
  val releaseCrossNativeLibDir = settingKey[Option[File]](
    "Cross-compilation output root. When set, dist packaging uses <dir>/<platform-classifier>/ per platform."
  )
  val releasePlatform = inputKey[File]("Package for a single target platform")
  val releaseAll      = taskKey[Seq[File]]("Package for all configured target platforms")

  // ── Constants ─────────────────────────────────────────────────────

  private val DefaultRoastVersion = "1.5.0"

  private val DefaultJlinkModules = Seq(
    "java.base",
    "java.desktop",
    "java.logging",
    "java.management",
    "jdk.unsupported",
    "jdk.zipfs"
  )

  private val NativeLibExts = Set(".so", ".dylib", ".dll")

  // ── Simple mode implementation ────────────────────────────────────

  private val packageJvm: Def.Initialize[Task[File]] = Def.task {
    val log     = streams.value.log
    val appName = releaseAppName.value
    val mainCls = (Compile / mainClass).value.getOrElse {
      sys.error("releasePackage requires Compile / mainClass to be set")
    }
    val outDir = target.value / "release-package" / appName

    IO.delete(outDir)

    // ── lib/ — application JAR + dependency JARs
    val libDir = outDir / "lib"
    IO.createDirectory(libDir)
    val appJar = (Compile / packageBin).value
    IO.copyFile(appJar, libDir / appJar.getName)
    val deps = (Compile / dependencyClasspathAsJars).value.map(_.data)
    deps.foreach(jar => IO.copyFile(jar, libDir / jar.getName))

    // ── native/ — platform shared libraries
    val nativeDir = outDir / "native"
    IO.createDirectory(nativeDir)
    for {
      dir  <- releaseNativeLibDirs.value if dir.exists()
      file <- IO.listFiles(dir) if NativeLibExts.exists(file.getName.endsWith)
    } IO.copyFile(file, nativeDir / file.getName)

    // ── bin/ — launcher scripts
    val binDir = outDir / "bin"
    IO.createDirectory(binDir)
    val jarNames = (appJar +: deps).map(_.getName)

    writeUnixLauncher(binDir / appName, jarNames, mainCls)
    writeWindowsLauncher(binDir / s"$appName.bat", jarNames, mainCls)

    log.info(s"[release] Package created: ${outDir.getAbsolutePath}")
    log.info(s"[release] Run with: ${(binDir / appName).getAbsolutePath}")
    outDir
  }

  private def writeUnixLauncher(file: File, jars: Seq[String], mainClass: String): Unit = {
    val cp = jars.map(j => "\"$APP_HOME/lib/" + j + "\"").mkString(":")
    val script =
      s"""#!/bin/sh
         |set -e
         |APP_HOME="$$(cd "$$(dirname "$$0")/.." && pwd)"
         |exec java \\
         |  --enable-native-access=ALL-UNNAMED \\
         |  -Djava.library.path="$$APP_HOME/native" \\
         |  -cp $cp \\
         |  $mainClass "$$@"
         |""".stripMargin
    IO.write(file, script)
    file.setExecutable(true)
  }

  private def writeWindowsLauncher(file: File, jars: Seq[String], mainClass: String): Unit = {
    val cp = jars.map(j => s"%APP_HOME%\\lib\\$j").mkString(";")
    val script =
      s"""@echo off
         |set APP_HOME=%~dp0..
         |java ^
         |  --enable-native-access=ALL-UNNAMED ^
         |  -Djava.library.path="%APP_HOME%\\native" ^
         |  -cp "$cp" ^
         |  $mainClass %*
         |""".stripMargin
    IO.write(file, script)
  }

  // ── Simple mode settings ──────────────────────────────────────────

  lazy val jvmSettings: Seq[Setting[_]] = Seq(
    releaseAppName       := name.value,
    releaseNativeLibDirs := Seq.empty,
    releasePackage       := packageJvm.value
  )

  // ── Distribution mode implementation ──────────────────────────────

  /** Open a URL following redirects across hosts (java.net.HttpURLConnection won't). */
  private def openWithRedirects(url: String, maxRedirects: Int): java.io.InputStream = {
    var currentUrl = url
    var remaining  = maxRedirects
    while (remaining > 0) {
      val conn = new URI(currentUrl).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setConnectTimeout(30000)
      conn.setReadTimeout(300000)
      conn.setInstanceFollowRedirects(false)
      val code = conn.getResponseCode
      if (code >= 300 && code < 400) {
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        if (location == null) throw new RuntimeException(s"Redirect with no Location header from $currentUrl")
        currentUrl = location
        remaining -= 1
      } else if (code == 200) {
        return conn.getInputStream
      } else {
        conn.disconnect()
        throw new RuntimeException(s"HTTP $code from $currentUrl")
      }
    }
    throw new RuntimeException(s"Too many redirects for $url")
  }

  /** Download a file to the cache directory, skipping if already present. Returns the cached file. */
  private def downloadToCache(url: String, cacheDir: File, log: sbt.util.Logger): File = {
    val fileName = url.split('/').last.split('?').head
    val cached   = cacheDir / fileName
    if (cached.exists()) {
      log.info(s"[release] Using cached: ${cached.getName}")
      cached
    } else {
      IO.createDirectory(cacheDir)
      log.info(s"[release] Downloading: $url")
      val tmpFile = Files.createTempFile(cacheDir.toPath, "download-", ".tmp")
      try {
        val in = openWithRedirects(url, maxRedirects = 5)
        try Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING)
        finally in.close()
        Files.move(tmpFile, cached.toPath, StandardCopyOption.ATOMIC_MOVE)
        log.info(s"[release] Downloaded: ${cached.getName} (${cached.length() / 1024 / 1024} MB)")
        cached
      } catch {
        case e: Exception =>
          Files.deleteIfExists(tmpFile)
          throw new RuntimeException(s"Failed to download $url: ${e.getMessage}", e)
      }
    }
  }

  /** Extract a .zip archive. */
  private def extractZip(archive: File, destDir: File, log: sbt.util.Logger): Unit = {
    IO.createDirectory(destDir)
    val zipFile = new java.util.zip.ZipFile(archive)
    try {
      val entries = zipFile.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        val dest  = destDir.toPath.resolve(entry.getName)
        if (entry.isDirectory) {
          Files.createDirectories(dest)
        } else {
          Files.createDirectories(dest.getParent)
          val is = zipFile.getInputStream(entry)
          try Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING)
          finally is.close()
          val name = entry.getName
          if (!name.contains(".") || name.endsWith(".sh") || name.contains("/bin/")) {
            dest.toFile.setExecutable(true)
          }
        }
      }
    } finally zipFile.close()
  }

  /** Extract a .tar.gz archive using the system `tar` command. */
  private def extractTarGz(archive: File, destDir: File, log: sbt.util.Logger): Unit = {
    IO.createDirectory(destDir)
    val proc = new ProcessBuilder("tar", "xzf", archive.getAbsolutePath, "-C", destDir.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val exit = proc.waitFor()
    if (exit != 0) {
      val output = new String(proc.getInputStream.readAllBytes())
      throw new RuntimeException(s"tar extraction failed (exit $exit): $output")
    }
  }

  /** Extract an archive (auto-detects format). */
  private def extractArchive(archive: File, destDir: File, log: sbt.util.Logger): Unit = {
    val name = archive.getName.toLowerCase
    if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
      extractTarGz(archive, destDir, log)
    } else if (name.endsWith(".zip") || name.endsWith(".exe.zip")) {
      extractZip(archive, destDir, log)
    } else {
      throw new RuntimeException(s"Unknown archive format: ${archive.getName}")
    }
  }

  /** Find the JDK root inside an extracted archive (the directory containing bin/java). */
  private def findJdkRoot(extractedDir: File): File = {
    val topLevel = IO.listFiles(extractedDir).filter(_.isDirectory)
    topLevel
      .find { dir =>
        (dir / "bin" / "java").exists() || (dir / "bin" / "java.exe").exists()
      }
      .orElse {
        // macOS .tar.gz can have Contents/Home/
        topLevel.flatMap { dir =>
          val contentsHome = dir / "Contents" / "Home"
          if ((contentsHome / "bin" / "java").exists()) Some(contentsHome) else None
        }.headOption
      }
      .getOrElse {
        throw new RuntimeException(
          s"Could not find JDK root in ${extractedDir.getAbsolutePath}. " +
            s"Contents: ${topLevel.map(_.getName).mkString(", ")}"
        )
      }
  }

  /** Download and extract a JDK, returning the JDK root directory. */
  private def resolveJdk(url: String, platform: Platform, cacheDir: File, log: sbt.util.Logger): File = {
    val jdkCache = cacheDir / "jdks"
    val archive  = downloadToCache(url, jdkCache, log)
    val extractDir = jdkCache / s"extracted-${platform.classifier}"
    if (!extractDir.exists() || IO.listFiles(extractDir).isEmpty) {
      log.info(s"[release] Extracting JDK for $platform...")
      IO.delete(extractDir)
      extractArchive(archive, extractDir, log)
    }
    findJdkRoot(extractDir)
  }

  /** Map platform to Roast asset name. */
  private def roastAssetName(platform: Platform): String = {
    val roastOs = if (platform.isWindows) "win" else platform.os
    val arch    = platform.arch
    if (platform.isWindows) s"roast-$roastOs-$arch.exe.zip"
    else s"roast-$roastOs-$arch.zip"
  }

  /** Download Roast native launcher binary, returning the executable file. */
  private def resolveRoast(version: String, platform: Platform, cacheDir: File, log: sbt.util.Logger): File = {
    val asset    = roastAssetName(platform)
    val roastDir = cacheDir / "roast" / version
    val url      = s"https://github.com/fourlastor-alexandria/roast/releases/download/$version/$asset"
    val archive  = downloadToCache(url, roastDir, log)

    val extractDir = roastDir / platform.classifier
    if (!extractDir.exists() || IO.listFiles(extractDir).isEmpty) {
      IO.delete(extractDir)
      extractZip(archive, extractDir, log)
    }

    IO.listFiles(extractDir)
      .find(f => f.isFile && f.getName.startsWith("roast"))
      .getOrElse(throw new RuntimeException(s"Roast binary not found in $extractDir"))
  }

  /** Run jlink to create a minimal JRE.
    *
    * When the target OS matches the host OS, uses the target JDK's own jlink binary.
    * For cross-OS targets, copies the full target JDK runtime (larger but always correct).
    */
  private def runJlink(
      targetJdkRoot: File,
      targetPlatform: Platform,
      modules: Seq[String],
      outputDir: File,
      log: sbt.util.Logger
  ): File = {
    val targetJmodsDir = targetJdkRoot / "jmods"
    if (!targetJmodsDir.exists()) {
      throw new RuntimeException(
        s"jmods directory not found in target JDK: ${targetJmodsDir.getAbsolutePath}. " +
          "Ensure the JDK download URL points to a full JDK (not a JRE)."
      )
    }

    IO.delete(outputDir)

    val hostOs   = Platform.host.os
    val targetOs = targetPlatform.os

    if (targetOs == hostOs) {
      // Same OS: use the target JDK's own jlink binary (version always matches)
      val jlink = {
        val unix = targetJdkRoot / "bin" / "jlink"
        val exe  = targetJdkRoot / "bin" / "jlink.exe"
        if (unix.exists()) unix
        else if (exe.exists()) exe
        else
          throw new RuntimeException(
            s"jlink not found in target JDK at ${targetJdkRoot / "bin"}. " +
              "Ensure the JDK download URL points to a full JDK (not a JRE)."
          )
      }
      log.info(s"[release] Using target JDK's jlink: ${jlink.getAbsolutePath}")
      val cmd = Seq(
        jlink.getAbsolutePath,
        "--module-path",
        targetJmodsDir.getAbsolutePath,
        "--add-modules",
        modules.mkString(","),
        "--output",
        outputDir.getAbsolutePath,
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress",
        "zip-6"
      )
      log.info(s"[release] Running jlink with modules: ${modules.mkString(", ")}")
      val proc   = new ProcessBuilder(cmd: _*).redirectErrorStream(true).start()
      val output = new String(proc.getInputStream.readAllBytes())
      val exit   = proc.waitFor()
      if (exit != 0) {
        throw new RuntimeException(s"jlink failed (exit $exit):\n$output")
      }
      outputDir
    } else {
      // Cross-OS: can't run target jlink binary. Copy the full target JDK runtime.
      log.info(s"[release] Cross-OS: copying full target JDK runtime for ${targetPlatform.classifier}")
      IO.createDirectory(outputDir)
      val dirsToKeep = Set("bin", "lib", "conf", "legal")
      val children   = targetJdkRoot.listFiles()
      if (children != null) {
        children.foreach { child =>
          if (dirsToKeep.contains(child.getName)) {
            IO.copyDirectory(child, outputDir / child.getName)
          }
        }
      }
      outputDir
    }
  }

  /** Write the Roast JSON configuration file. */
  private def writeRoastConfig(
      configFile: File,
      appName: String,
      jars: Seq[String],
      mainClass: String,
      vmArgs: Seq[String],
      useZgc: Boolean,
      runOnFirstThread: Boolean,
      platform: Platform,
      hasNativeLibs: Boolean
  ): Unit = {
    val cpEntries = jars.map(j => s""""app/$j"""").mkString(",\n    ")

    val allVmArgs = {
      val base = vmArgs :+ "--enable-native-access=ALL-UNNAMED"
      val withNative =
        if (hasNativeLibs) base :+ "-Djava.library.path=native"
        else base
      withNative
    }
    val vmArgsJson = allVmArgs.map(a => s""""$a"""").mkString(",\n    ")

    val isMac = platform.isMac
    val json =
      s"""{
         |  "classPath": [
         |    $cpEntries
         |  ],
         |  "mainClass": "$mainClass",
         |  "vmArgs": [
         |    $vmArgsJson
         |  ],
         |  "args": [],
         |  "useZgcIfSupportedOs": $useZgc,
         |  "runOnFirstThread": ${runOnFirstThread && isMac}
         |}
         |""".stripMargin
    IO.write(configFile, json)
  }

  /** Write a macOS Info.plist file. */
  private def writeInfoPlist(
      file: File,
      appName: String,
      bundleId: String,
      hasIcon: Boolean
  ): Unit = {
    val iconEntry =
      if (hasIcon)
        s"""  <key>CFBundleIconFile</key>
           |  <string>$appName.icns</string>""".stripMargin
      else ""

    val plist =
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
         |<plist version="1.0">
         |<dict>
         |  <key>CFBundleExecutable</key>
         |  <string>$appName</string>
         |  <key>CFBundleIdentifier</key>
         |  <string>$bundleId</string>
         |  <key>CFBundleName</key>
         |  <string>$appName</string>
         |  <key>CFBundleVersion</key>
         |  <string>1.0.0</string>
         |  <key>CFBundleShortVersionString</key>
         |  <string>1.0.0</string>
         |  <key>CFBundlePackageType</key>
         |  <string>APPL</string>
         |  <key>NSHighResolutionCapable</key>
         |  <true/>
         |$iconEntry
         |</dict>
         |</plist>
         |""".stripMargin
    IO.write(file, plist)
  }

  /** Ad-hoc sign a file with `codesign --force --sign -`. Only meaningful on macOS.
    * Non-fatal: logs a warning on failure instead of throwing.
    */
  private def codesignAdHoc(file: File, log: sbt.util.Logger): Unit = {
    val proc = new ProcessBuilder("codesign", "--force", "--sign", "-", file.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val output = new String(proc.getInputStream.readAllBytes())
    val exit   = proc.waitFor()
    if (exit != 0) log.warn(s"[release] Ad-hoc signing failed for ${file.getName}: $output")
    else log.info(s"[release] Ad-hoc signed: ${file.getName}")
  }

  /** Returns true when the build is running on macOS. */
  private def hostIsMac: Boolean =
    sys.props("os.name").toLowerCase.contains("mac")

  /** Assemble a distribution package for a single platform. Returns the archive file. */
  private def assemblePlatform(
      platform: Platform,
      appName: String,
      mainClass: String,
      appJar: File,
      deps: Seq[File],
      nativeLibDirs: Seq[File],
      crossNativeLibDir: Option[File],
      jlinkedRuntime: File,
      roastBinary: File,
      vmArgs: Seq[String],
      useZgc: Boolean,
      runOnFirstThread: Boolean,
      macOsBundleId: String,
      macOsIcon: Option[File],
      distDir: File,
      log: sbt.util.Logger
  ): File = {
    val isMac       = platform.isMac
    val isWindows   = platform.isWindows
    val archiveName = s"$appName-${platform.classifier}"
    val workDir     = distDir / archiveName

    IO.delete(workDir)

    val root =
      if (isMac) {
        val appBundle = workDir / s"$appName.app" / "Contents"
        IO.createDirectory(appBundle)
        appBundle
      } else {
        IO.createDirectory(workDir)
        workDir
      }

    // ── Roast launcher binary
    val launcherDir = if (isMac) root / "MacOS" else root
    IO.createDirectory(launcherDir)
    val launcherName = if (isWindows) s"$appName.exe" else appName
    val launcher     = launcherDir / launcherName
    IO.copyFile(roastBinary, launcher)
    launcher.setExecutable(true)

    // ── app/ — JARs + Roast config
    val appDir = launcherDir / "app"
    IO.createDirectory(appDir)

    val allJars = appJar +: deps
    // Deduplicate JARs by name
    val seenNames  = scala.collection.mutable.Set.empty[String]
    val uniqueJars = allJars.filter { jar =>
      val jn = jar.getName
      if (seenNames.contains(jn)) false
      else { seenNames += jn; true }
    }
    uniqueJars.foreach(jar => IO.copyFile(jar, appDir / jar.getName))

    // Resolve per-platform native lib directories
    val effectiveNativeLibDirs: Seq[File] = crossNativeLibDir match {
      case Some(crossDir) =>
        val platDir = crossDir / platform.classifier
        if (platDir.exists() && IO.listFiles(platDir).exists(f => NativeLibExts.exists(f.getName.endsWith))) {
          log.info(s"[release] Using cross-compiled native libs from ${platDir.getAbsolutePath}")
          Seq(platDir)
        } else {
          log.warn(s"[release] Cross native lib dir not found for ${platform.classifier}, using default nativeLibDirs")
          nativeLibDirs
        }
      case None => nativeLibDirs
    }

    val hasNativeLibs = effectiveNativeLibDirs.exists { dir =>
      dir.exists() && IO.listFiles(dir).exists(f => NativeLibExts.exists(f.getName.endsWith))
    }
    writeRoastConfig(
      appDir / s"$appName.json",
      appName,
      uniqueJars.map(_.getName),
      mainClass,
      vmArgs,
      useZgc,
      runOnFirstThread,
      platform,
      hasNativeLibs
    )

    // ── runtime/ — jlinked JRE
    val runtimeDir = launcherDir / "runtime"
    IO.copyDirectory(jlinkedRuntime, runtimeDir)

    // ── native/ — platform shared libraries
    if (hasNativeLibs) {
      val nativeDir = launcherDir / "native"
      IO.createDirectory(nativeDir)
      for {
        dir  <- effectiveNativeLibDirs if dir.exists()
        file <- IO.listFiles(dir) if NativeLibExts.exists(file.getName.endsWith)
      } IO.copyFile(file, nativeDir / file.getName)
    }

    // ── macOS-specific: Info.plist + icon
    if (isMac) {
      writeInfoPlist(root / "Info.plist", appName, macOsBundleId, macOsIcon.isDefined)
      macOsIcon.foreach { icon =>
        val resourcesDir = root / "Resources"
        IO.createDirectory(resourcesDir)
        IO.copyFile(icon, resourcesDir / s"$appName.icns")
      }
    }

    // ── Ad-hoc code signing (macOS host only)
    if (hostIsMac && (isMac || !isWindows)) {
      val nativeDir = launcherDir / "native"
      if (nativeDir.exists()) {
        IO.listFiles(nativeDir)
          .filter(_.getName.endsWith(".dylib"))
          .foreach(f => codesignAdHoc(f, log))
      }
      codesignAdHoc(launcher, log)
      if (isMac) {
        val appBundle = workDir / s"$appName.app"
        val deepSign = new ProcessBuilder(
          "codesign",
          "--force",
          "--deep",
          "--sign",
          "-",
          appBundle.getAbsolutePath
        ).redirectErrorStream(true).start()
        val deepOut  = new String(deepSign.getInputStream.readAllBytes())
        val deepExit = deepSign.waitFor()
        if (deepExit != 0) log.warn(s"[release] Deep signing failed for $appName.app: $deepOut")
        else log.info(s"[release] Ad-hoc signed: $appName.app (deep)")
      }
    }

    // ── Create archive
    val archiveFile =
      if (isWindows) createZipArchive(workDir, distDir / s"$archiveName.zip", log)
      else createTarGzArchive(workDir, distDir / s"$archiveName.tar.gz", log)

    log.info(s"[release] Distribution package: ${archiveFile.getAbsolutePath} (${archiveFile.length() / 1024 / 1024} MB)")
    archiveFile
  }

  /** Create a .tar.gz archive preserving executable bits. */
  private def createTarGzArchive(sourceDir: File, archiveFile: File, log: sbt.util.Logger): File = {
    val parentDir = sourceDir.getParentFile
    val dirName   = sourceDir.getName
    val cmd       = Seq("tar", "czf", archiveFile.getAbsolutePath, "-C", parentDir.getAbsolutePath, dirName)
    val proc = new ProcessBuilder(cmd: _*)
      .redirectErrorStream(true)
      .start()
    val exit = proc.waitFor()
    if (exit != 0) {
      val output = new String(proc.getInputStream.readAllBytes())
      throw new RuntimeException(s"tar archive creation failed (exit $exit): $output")
    }
    archiveFile
  }

  /** Create a .zip archive. */
  private def createZipArchive(sourceDir: File, archiveFile: File, log: sbt.util.Logger): File = {
    val parentDir = sourceDir.getParentFile
    val out       = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archiveFile))
    try {
      val allFiles = (sourceDir ** AllPassFilter).get.filter(_.isFile)
      allFiles.foreach { file =>
        val relativePath = parentDir.toPath.relativize(file.toPath).toString
        val entry        = new java.util.zip.ZipEntry(relativePath)
        out.putNextEntry(entry)
        IO.transfer(file, out)
        out.closeEntry()
      }
    } finally out.close()
    archiveFile
  }

  // ── Distribution mode tasks ───────────────────────────────────────

  private val packagePlatformTask: Def.Initialize[InputTask[File]] = Def.inputTask {
    val platformStr = token(Space ~> StringBasic).parsed
    val platform    = Platform.fromClassifier(platformStr)
    val log         = streams.value.log
    val targets     = releaseTargets.value

    if (!targets.contains(platform)) {
      val available = if (targets.isEmpty) "none configured" else targets.keys.mkString(", ")
      sys.error(s"Platform '$platform' not in releaseTargets. Available: $available")
    }

    val appName  = releaseAppName.value
    val mainCls  = (Compile / mainClass).value.getOrElse {
      sys.error("releasePlatform requires Compile / mainClass to be set")
    }
    val cacheDir = releaseCacheDir.value
    val distDir  = target.value / "release-dist"

    log.info(s"[release] Packaging $appName for $platform...")

    val appJar  = (Compile / packageBin).value
    val deps    = (Compile / dependencyClasspathAsJars).value.map(_.data)
    val jdkRoot = resolveJdk(targets(platform), platform, cacheDir, log)

    val runtimeDir = distDir / s"runtime-$platform"
    val jlinked    = runJlink(jdkRoot, platform, releaseJlinkModules.value, runtimeDir, log)
    val roast      = resolveRoast(releaseRoastVersion.value, platform, cacheDir, log)

    assemblePlatform(
      platform,
      appName,
      mainCls,
      appJar,
      deps,
      releaseNativeLibDirs.value,
      releaseCrossNativeLibDir.value,
      jlinked,
      roast,
      releaseVmArgs.value,
      releaseUseZgc.value,
      releaseRunOnFirstThread.value,
      releaseMacOsBundleId.value,
      releaseMacOsIcon.value,
      distDir,
      log
    )
  }

  private val packageAllTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log      = streams.value.log
    val targets  = releaseTargets.value
    val appName  = releaseAppName.value
    val mainCls  = (Compile / mainClass).value.getOrElse {
      sys.error("releaseAll requires Compile / mainClass to be set")
    }
    val cacheDir = releaseCacheDir.value
    val distDir  = target.value / "release-dist"
    val appJar   = (Compile / packageBin).value
    val deps     = (Compile / dependencyClasspathAsJars).value.map(_.data)
    val modules  = releaseJlinkModules.value
    val version  = releaseRoastVersion.value
    val vmArgs   = releaseVmArgs.value
    val useZgc   = releaseUseZgc.value
    val rft      = releaseRunOnFirstThread.value
    val bundleId = releaseMacOsBundleId.value
    val icon     = releaseMacOsIcon.value
    val nativeDirs = releaseNativeLibDirs.value
    val crossDir   = releaseCrossNativeLibDir.value

    if (targets.isEmpty) {
      log.warn("[release] releaseTargets is empty — no platforms to package.")
      Seq.empty
    } else {
      log.info(s"[release] Packaging $appName for ${targets.size} platform(s): ${targets.keys.mkString(", ")}")

      targets.toSeq.map { case (platform, jdkUrl) =>
        log.info(s"[release] ── $platform ──")

        val jdkRoot    = resolveJdk(jdkUrl, platform, cacheDir, log)
        val runtimeDir = distDir / s"runtime-$platform"
        val jlinked    = runJlink(jdkRoot, platform, modules, runtimeDir, log)
        val roast      = resolveRoast(version, platform, cacheDir, log)

        assemblePlatform(
          platform,
          appName,
          mainCls,
          appJar,
          deps,
          nativeDirs,
          crossDir,
          jlinked,
          roast,
          vmArgs,
          useZgc,
          rft,
          bundleId,
          icon,
          distDir,
          log
        )
      }
    }
  }

  // ── Distribution mode settings ────────────────────────────────────

  lazy val distSettings: Seq[Setting[_]] = Seq(
    releaseTargets          := Map.empty,
    releaseJlinkModules     := DefaultJlinkModules,
    releaseRoastVersion     := DefaultRoastVersion,
    releaseVmArgs           := Seq.empty,
    releaseUseZgc           := true,
    releaseMacOsBundleId    := s"com.app.${name.value}",
    releaseMacOsIcon        := None,
    releaseCacheDir         := Path.userHome / ".cache" / "sbt-multi-arch-release",
    releaseRunOnFirstThread := true,
    releaseCrossNativeLibDir := None,
    releasePlatform         := packagePlatformTask.evaluated,
    releaseAll              := packageAllTask.value
  )
}
