package org.olcbox.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopBrandingTest {
    @Test
    fun windowsPackageHasItsOwnIdentity() {
        val build = File("../desktopApp/build.gradle.kts").readText()
        assertTrue("val desktopPackageName = \"ИлюхаLine\"" in build)
        assertTrue("upgradeUuid = \"b3c2e0cb-9b3e-4f6c-9e2a-6a2c47e3c815\"" in build)
        assertNotEquals(
            "6f0aaf78-dbed-4745-9d95-9e63f10a30de",
            Regex("upgradeUuid = \"([0-9a-f-]+)\"").find(build)?.groupValues?.get(1),
        )
    }

    @Test
    fun windowsDataDoesNotShareTheGhostlaneDirectory() {
        val oldOs = System.getProperty("os.name")
        val oldHome = System.getProperty("user.home")
        val home = Files.createTempDirectory("ilyukhaline-home")
        try {
            System.setProperty("os.name", "Windows 11")
            System.setProperty("user.home", home.toString())
            assertEquals(
                home.resolve("AppData").resolve("Roaming").resolve("ИлюхаLine"),
                DesktopPaths.appDataDir(),
            )
        } finally {
            System.setProperty("os.name", oldOs)
            System.setProperty("user.home", oldHome)
        }
    }
}
