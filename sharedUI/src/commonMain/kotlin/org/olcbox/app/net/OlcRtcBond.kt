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

    fun start(timeoutMillis: Int): List<Int> {
        var lastError: Throwable? = null
        runtimes.forEachIndexed { index, runtime ->
            try {
                runtime.start()
                runtime.waitReady(timeoutMillis)
            } catch (error: Throwable) {
                lastError = error
                runCatching { runtime.stop(timeoutMillis) }
            }
        }
        if (activeIndexes().size < 3) {
            runCatching { stop(timeoutMillis) }
            throw lastError ?: IllegalStateException("fewer than three olcRTC rooms are ready")
        }
        return activeIndexes()
    }

    fun recover(timeoutMillis: Int): List<Int> {
        runtimes.forEach { runtime ->
            if (runtime.isRunning()) return@forEach
            runCatching { runtime.stop(timeoutMillis) }
            runCatching {
                runtime.start()
                runtime.waitReady(timeoutMillis)
            }
        }
        return activeIndexes()
    }

    fun activeIndexes(): List<Int> = runtimes.mapIndexedNotNull { index, runtime ->
        index.takeIf { runtime.isRunning() }
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

    fun needsFullRecovery(): Boolean = activeIndexes().size < 3

    fun isRunning(): Boolean = !needsFullRecovery()

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
