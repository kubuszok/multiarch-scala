package multiarch.core

class PlatformSpec extends munit.FunSuite {

  test("desktop platforms has 6 entries") {
    assertEquals(Platform.desktop.size, 6)
  }

  test("android platforms has 3 entries") {
    assertEquals(Platform.android.size, 3)
  }

  test("jniPanama includes all 9 platforms") {
    assertEquals(Platform.jniPanama.size, 9)
    assert(Platform.jniPanama.containsSlice(Platform.desktop))
    assert(Platform.jniPanama.containsSlice(Platform.android))
  }

  test("scalaNative equals desktop") {
    assertEquals(Platform.scalaNative, Platform.desktop)
  }

  test("all equals jniPanama") {
    assertEquals(Platform.all, Platform.jniPanama)
  }

  test("desktop platform classifiers") {
    assertEquals(Platform.LinuxX86_64.classifier, "linux-x86_64")
    assertEquals(Platform.LinuxAarch64.classifier, "linux-aarch64")
    assertEquals(Platform.MacosX86_64.classifier, "macos-x86_64")
    assertEquals(Platform.MacosAarch64.classifier, "macos-aarch64")
    assertEquals(Platform.WindowsX86_64.classifier, "windows-x86_64")
    assertEquals(Platform.WindowsAarch64.classifier, "windows-aarch64")
  }

  test("android platform classifiers") {
    assertEquals(Platform.AndroidAarch64.classifier, "android-aarch64")
    assertEquals(Platform.AndroidArmV7.classifier, "android-armv7")
    assertEquals(Platform.AndroidX86_64.classifier, "android-x86_64")
  }

  test("fromClassifier resolves all platforms") {
    Platform.all.foreach { p =>
      assertEquals(Platform.fromClassifier(p.classifier), p)
    }
  }

  test("fromClassifier throws for unknown") {
    intercept[RuntimeException] {
      Platform.fromClassifier("freebsd-x86_64")
    }
  }

  test("isDesktop / isAndroid") {
    Platform.desktop.foreach { p =>
      assert(p.isDesktop, s"$p should be desktop")
      assert(!p.isAndroid, s"$p should not be android")
    }
    Platform.android.foreach { p =>
      assert(p.isAndroid, s"$p should be android")
      assert(!p.isDesktop, s"$p should not be desktop")
    }
  }

  test("OS detection") {
    assert(Platform.LinuxX86_64.isLinux)
    assert(!Platform.LinuxX86_64.isMac)
    assert(!Platform.LinuxX86_64.isWindows)

    assert(Platform.MacosAarch64.isMac)
    assert(!Platform.MacosAarch64.isLinux)

    assert(Platform.WindowsX86_64.isWindows)
    assert(!Platform.WindowsX86_64.isLinux)
  }

  test("zigTarget works for desktop platforms") {
    assertEquals(Platform.LinuxX86_64.zigTarget, "x86_64-linux-gnu")
    assertEquals(Platform.LinuxAarch64.zigTarget, "aarch64-linux-gnu")
    assertEquals(Platform.MacosX86_64.zigTarget, "x86_64-macos")
    assertEquals(Platform.MacosAarch64.zigTarget, "aarch64-macos")
    assertEquals(Platform.WindowsX86_64.zigTarget, "x86_64-windows-gnu")
    assertEquals(Platform.WindowsAarch64.zigTarget, "aarch64-windows-gnu")
  }

  test("zigTarget throws for android platforms") {
    Platform.android.foreach { p =>
      intercept[UnsupportedOperationException] {
        p.zigTarget
      }
    }
  }

  test("rustTarget / scalaNativeTarget") {
    assertEquals(Platform.LinuxX86_64.rustTarget, "x86_64-unknown-linux-gnu")
    assertEquals(Platform.MacosAarch64.rustTarget, "aarch64-apple-darwin")
    assertEquals(Platform.WindowsX86_64.rustTarget, "x86_64-pc-windows-msvc")
    assertEquals(Platform.AndroidAarch64.rustTarget, "aarch64-linux-android")
    assertEquals(Platform.AndroidArmV7.rustTarget, "armv7-linux-androideabi")
    assertEquals(Platform.AndroidX86_64.rustTarget, "x86_64-linux-android")

    // scalaNativeTarget is same as rustTarget
    Platform.all.foreach { p =>
      assertEquals(p.scalaNativeTarget, p.rustTarget)
    }
  }

  test("host returns a valid platform") {
    val h = Platform.host
    assert(Platform.all.contains(h))
  }

  test("toString returns classifier") {
    Platform.all.foreach { p =>
      assertEquals(p.toString, p.classifier)
    }
  }
}
