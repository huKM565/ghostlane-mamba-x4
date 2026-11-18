package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LinuxTunControllerTest {
    init {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
    }

    @Test
    fun supervisorNameIsIncludedInCleanupAndLeftoverDetection() {
        val root = Files.createTempDirectory("ilyukhaline-linux-cleanup")
        val fake = root.resolve("fake-process")
        Files.writeString(fake, "#!/bin/sh\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n")
        fake.toFile().setExecutable(true)
        val engineName = root.resolve("runtime/olcrtc-client-test").toString()
        val supervisorName = root.resolve("runtime/supervisor.sh").toString()
        val processes = listOf(engineName, supervisorName, root.resolve("runtime/other").toString()).map { name ->
            ProcessBuilder(fake.toString(), name).start()
        }
        try {
            val withEngine = LinuxTunController.withEnginesEnded("#!/bin/sh\n", engineName)
            val script = LinuxTunController.withEnginesEnded(withEngine, supervisorName)
            val cleanup = root.resolve("cleanup.sh")
            Files.writeString(cleanup, script)

            val result = ProcessBuilder("bash", cleanup.toString()).start().waitFor(15, TimeUnit.SECONDS)

            assertEquals(true, result)
            awaitCondition { processes.take(2).all { !it.isAlive } }
            assertFalse(processes[0].isAlive)
            assertFalse(processes[1].isAlive)
            assertEquals(true, processes[2].isAlive)
        } finally {
            processes.forEach { it.destroyForcibly() }
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("condition was not met before timeout")
    }
}
