package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.SystemVpnMode
import org.olcbox.app.data.model.BondRoomConfig
import org.olcbox.app.vpn.BondRoomError
import org.olcbox.app.vpn.BondRoomStatus
import org.olcbox.app.vpn.bondRoomLabels
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.TunnelHold
import org.olcbox.app.log.LogScrubber

object OlcboxVpnState {
    @Volatile var channelProxy: org.olcbox.app.data.repository.SubscriptionFetchProxy? = null
    @Volatile var channelProbe: org.olcbox.app.net.ChannelLatency.Session? = null
    @Volatile var activeLocation: org.olcbox.app.data.model.LocationConfig? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    /** Why the service holds its tunnel with nothing behind it (VpnManager.hold); null when it does not. */
    private val _hold = MutableStateFlow<TunnelHold?>(null)
    val hold = _hold.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _bondRoomStates = MutableStateFlow<List<BondRoomStatus>?>(null)
    val bondRoomStates = _bondRoomStates.asStateFlow()
    private var bondRelayReady = emptyList<Boolean>()
    private var bondRawOccupants = emptyList<Int?>()

    @Volatile
    var bondRoomReplay: (() -> Unit)? = null

    @Synchronized
    fun configureBondRooms(paths: List<BondRoomConfig>) {
        val labels = bondRoomLabels(paths.map { it.provider })
        _bondRoomStates.value = paths.mapIndexed { index, _ ->
            BondRoomStatus(labels[index], active = false)
        }
        bondRelayReady = List(paths.size) { false }
        bondRawOccupants = List(paths.size) { null }
    }

    @Synchronized
    fun setBondRooms(activeIndexes: List<Int>?) {
        if (activeIndexes == null) {
            _bondRoomStates.value = null
            bondRelayReady = emptyList()
            bondRawOccupants = emptyList()
            bondRoomReplay = null
            return
        }
        val states = _bondRoomStates.value.orEmpty()
        bondRawOccupants = List(states.size) { index ->
            bondRawOccupants.getOrNull(index).takeIf { index in activeIndexes }
        }
        _bondRoomStates.value = states.mapIndexed { index, status ->
            status.copy(
                active = index in activeIndexes,
                occupants = org.olcbox.app.vpn.displayedOccupants(
                    bondRawOccupants[index],
                    bondRelayReady.getOrElse(index) { false },
                ),
            )
        }
    }

    @Synchronized
    fun setBondRoomOccupant(index: Int, participants: Int) {
        val states = _bondRoomStates.value ?: return
        val status = states.getOrNull(index)?.takeIf { it.active } ?: return
        bondRawOccupants = bondRawOccupants.toMutableList().also { it[index] = participants }
        _bondRoomStates.value = states.toMutableList().also {
            it[index] = status.copy(
                occupants = org.olcbox.app.vpn.displayedOccupants(
                    participants,
                    bondRelayReady.getOrElse(index) { false },
                ),
            )
        }
    }

    @Synchronized
    fun setBondRoomState(index: Int, state: String) {
        val states = _bondRoomStates.value ?: return
        val status = states.getOrNull(index) ?: return
        val relayReady = state == "relay-ready"
        bondRelayReady = bondRelayReady.toMutableList().also { it[index] = relayReady }
        val error = when (state) {
            "joined", "relay-ready" -> null
            "join-failed" -> BondRoomError.CLIENT
            "relay-failed" -> BondRoomError.SERVER
            else -> return
        }
        val active = if (state == "join-failed" || state == "relay-failed") false else status.active
        _bondRoomStates.value = states.toMutableList().also {
            it[index] = status.copy(active = active, error = error).let { updated ->
                updated.copy(
                    occupants = org.olcbox.app.vpn.displayedOccupants(
                        bondRawOccupants.getOrNull(index),
                        relayReady,
                    ),
                )
            }
        }
    }

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince = _connectedSince.asStateFlow()

    /**
     * Reads the tunnel's own counters, there and then: set by the service for
     * as long as it lives, because the counters are tun2socks's and only the
     * service can ask it.
     *
     * A reader and not a value. The value was written when the service's
     * watchdog happened to look, every fifteen seconds, and the screen draws a
     * second of traffic from two readings a second apart.
     */
    @Volatile var trafficReader: (() -> TrafficCounters?)? = null

    /**
     * The session's counters as they stand now, or null: no session, no tunnel
     * to count it (proxy mode), or a reading that failed. A session's counters
     * belong to that session, so nothing is read once it is over.
     */
    fun readTraffic(): TrafficCounters? {
        val status = _status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return null
        return trafficReader?.invoke()
    }

    /** Android's always-on state as the running service last read it; null when it has not. */
    private val _systemVpnMode = MutableStateFlow<SystemVpnMode?>(null)
    val systemVpnMode = _systemVpnMode.asStateFlow()

    fun setSystemVpnMode(mode: SystemVpnMode?) {
        _systemVpnMode.value = mode
    }

    /** What the service did on its own that the user should see once: see VpnManager.notices. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = _notices.asSharedFlow()

    fun announce(message: String) {
        _notices.tryEmit(message)
    }

    /**
     * [hold] says why a [VpnStatus.Reconnecting] holds the tunnel with nothing
     * behind it; any other status, or a reconnect that is one, has none. Set
     * before the status, so that a reader of both never sees the hold's status
     * without its reason.
     */
    fun setStatus(status: VpnStatus, hold: TunnelHold? = null) {
        _hold.value = hold.takeIf { status is VpnStatus.Reconnecting }
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        _connectedSince.value = when (status) {
            // Only the first Connected of a session stamps the clock; a
            // reconnect passes through Reconnecting and back, and must not
            // restart it.
            VpnStatus.Connected -> _connectedSince.value ?: nowMillis()
            VpnStatus.Reconnecting -> _connectedSince.value
            else -> null
        }
    }

    fun addLog(msg: String) {
        // Scrubbed once, at the top: logcat and a captured bug report are exports too.
        val safe = LogScrubber.default.scrub(msg)
        Log.d(TAG, safe)
        _logs.update { (it + safe).takeLast(MAX_LOG_ENTRIES) }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
