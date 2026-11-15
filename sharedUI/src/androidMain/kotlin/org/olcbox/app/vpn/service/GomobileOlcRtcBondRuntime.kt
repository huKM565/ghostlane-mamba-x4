package org.olcbox.app.vpn.service

import mobile.Runtime as OlcrtcRuntime
import org.olcbox.app.net.OlcRtcBondRuntime

class GomobileOlcRtcBondRuntime(
    private val runtime: OlcrtcRuntime,
) : OlcRtcBondRuntime {
    override fun start() = runtime.start()

    override fun waitReady(timeoutMillis: Int) = runtime.waitReady(timeoutMillis.toLong())

    override fun stop(timeoutMillis: Int) = runtime.stop(timeoutMillis.toLong())

    override fun isRunning(): Boolean = runtime.isRunning()

    override fun setDNS(servers: String) = runtime.setDNS(servers)
}
