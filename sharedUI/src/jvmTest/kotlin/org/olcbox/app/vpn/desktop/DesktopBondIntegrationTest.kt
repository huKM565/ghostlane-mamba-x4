package org.olcbox.app.vpn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.isBondedWindows
import org.olcbox.app.vpn.windowsTunCachePath

class DesktopBondIntegrationTest {
    private val manager = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt").readText()

    @Test
    fun bondedPoolIsOnlyForMarkedWindowsTunProfiles() {
        val bonded = LocationConfig(kind = LocationKind.Olcrtc, bondVlessLink = "vless://test")
        val plain = bonded.copy(bondVlessLink = null)
        assertTrue(isBondedWindows(bonded, DesktopMode.WindowsTun))
        assertFalse(isBondedWindows(bonded, DesktopMode.SystemProxy))
        assertFalse(isBondedWindows(plain, DesktopMode.WindowsTun))
    }

    @Test
    fun managerStartsAndStopsTheWholePool() {
        assertTrue("startDesktopBond(" in manager)
        assertTrue("DesktopOlcRtcBond(" in manager)
        assertTrue("udpEnabled = false" in manager)
        assertTrue("XrayConfig.buildVlessBond" in manager)
        assertTrue("SingBoxConfig.buildSocksChain" in manager)
        assertTrue("stopDesktopBond()" in manager)
        assertTrue("isBondedWindows(" in manager)
    }

    @Test
    fun managerUsesPerPathSettingsAndKeepsRefreshSafetyInvariants() {
        assertTrue("planBondPaths(config, ::allocateBondPort)" in manager)
        assertTrue("setDesktopBondRooms(paths.indices.toList())" in manager)
        assertTrue("path.provider" in manager)
        assertTrue("path.transport" in manager)
        assertTrue("path.room" in manager)
        assertTrue("path.key" in manager)
        assertFalse("rooms.size == 4" in manager)
        assertTrue("expectedSize = bond?.rooms?.size ?: 0" in manager)
        assertTrue("val frontPort = line.frontPort ?: line.corePort" in manager)
        assertTrue(
            Regex("if \\(activeRooms\\.isEmpty\\(\\)\\) return false").findAll(manager).count() >= 2,
            "both pre-recovery and post-recovery empty active sets must avoid an empty Xray config"
        )
    }

    @Test
    fun everyTunAttemptHasItsOwnCacheFile() {
        val oldOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val first = windowsTunCachePath("session", 1, 0)
            val retry = windowsTunCachePath("session", 1, 1)
            assertNotEquals(first, retry)
            assertTrue(first.fileName.toString().startsWith("windows-tun-cache-"))
            assertTrue(first.parent.toString().contains("IlyukhaLine"))
        } finally {
            System.setProperty("os.name", oldOs)
        }
    }
}
