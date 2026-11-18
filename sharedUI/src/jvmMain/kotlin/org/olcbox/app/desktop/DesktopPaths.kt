package org.olcbox.app.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

internal enum class DesktopOs {
    MacOS,
    Windows,
    Linux,
    Other
}

internal object DesktopPaths {
    val os: DesktopOs
        get() {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.contains("mac") || name.contains("darwin") -> DesktopOs.MacOS
                name.contains("windows") -> DesktopOs.Windows
                name.contains("linux") -> DesktopOs.Linux
                else -> DesktopOs.Other
            }
        }

    val arch: String
        get() = System.getProperty("os.arch").lowercase()

    fun appDataDir(): Path {
        // A portable development build keeps this in its launcher JVM options,
        // so Windows elevation preserves isolation from the installed app.
        System.getProperty("olcbox.appDataDir")?.takeIf { it.isNotBlank() }?.let {
            return Path(it).also { directory -> Files.createDirectories(directory) }
        }
        val home = Path(System.getProperty("user.home"))
        val dir = when (os) {
            DesktopOs.MacOS -> home.resolve("Library").resolve("Application Support").resolve("Olcbox")
            DesktopOs.Windows -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let { Path(it) } ?: home.resolve("AppData").resolve("Roaming")).resolve("IlyukhaLine")
            }
            DesktopOs.Linux -> home.resolve(".ilyukhaline").also { migrateLinuxData(home, it) }
            DesktopOs.Other -> home.resolve(".olcbox")
        }
        Files.createDirectories(dir)
        return dir
    }

    private fun migrateLinuxData(home: Path, targetDir: Path) {
        val marker = targetDir.resolve(".ilyukhaline-import-complete")
        if (Files.exists(marker)) return
        val sourceDir = home.resolve(".olcbox")
        Files.createDirectories(targetDir)
        approvedLinuxMigrations.forEach { name ->
            val source = sourceDir.resolve(name)
            val target = targetDir.resolve(name)
            if (Files.isRegularFile(source) && !Files.exists(target)) {
                Files.copy(source, target)
            }
        }
        Files.writeString(marker, "")
    }

    private val approvedLinuxMigrations = listOf(
        "locations_v4.json",
        "locations_v3.json",
        "device_identity",
        "desktop_socks_proxy_settings.json",
        "desktop_connection_mode",
    )
}
