package org.olcbox.app.net

interface OlcRtcBondRuntime {
    fun start()
    fun waitReady(timeoutMillis: Int)
    fun stop(timeoutMillis: Int)
    fun isRunning(): Boolean
    fun setDNS(servers: String)
}

class OlcRtcBond(private val runtimes: List<OlcRtcBondRuntime>) {
    init {
        require(runtimes.size == 4) { "an olcRTC bond requires exactly four runtimes" }
    }

    fun start(timeoutMillis: Int) {
        try {
            runtimes.forEach { runtime ->
                runtime.start()
                runtime.waitReady(timeoutMillis)
            }
        } catch (error: Throwable) {
            runCatching { stop(timeoutMillis) }
            throw error
        }
    }

    fun stop(timeoutMillis: Int) {
        var firstError: Throwable? = null
        runtimes.forEach { runtime ->
            try {
                runtime.stop(timeoutMillis)
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
            }
        }
        firstError?.let { throw it }
    }

    fun isRunning(): Boolean = runtimes.all { it.isRunning() }

    fun setDNS(servers: String) {
        var firstError: Throwable? = null
        runtimes.forEach { runtime ->
            try {
                runtime.setDNS(servers)
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
            }
        }
        firstError?.let { throw it }
    }
}
