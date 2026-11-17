package org.olcbox.app.ui.render

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import org.olcbox.app.ui.components.kit.pkTied
import org.olcbox.app.vpn.TrafficCounters
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What is left of the board when its screen is built again over the same models.
 *
 * On an iPhone that is every return to the app. Its host lets go of the whole
 * Compose scene in the background, for the memory a tunnel's extension needs
 * more, and builds another on the way back in (`ComposeSceneHost`, iosApp); the
 * models live outside it. So whatever the screen only remembers is gone each
 * time, and it was reported from a phone a piece at a time: first the lists that
 * had been folded away, then the transport that had been chosen.
 *
 * Here the screen is the app's own, over its own models and a store kept in
 * memory, and it is built again the way the phone does it: everything composed
 * is dropped, and composed anew.
 */
@OptIn(ExperimentalTestApi::class)
class BoardKeptTest {
    private val phone = Phone("iphone", 393, 852)
    private val app = AppFixture()

    @BeforeTest fun begin() = app.begin()
    @AfterTest fun end() = app.end()

    private fun DesktopComposeUiTest.names(): Set<String> = laidTexts().map { it.text }.toSet()

    @Test fun everyTransportStaysVisibleWhenTheScreenIsBuiltAgain() {
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()))) { buildAgain ->
            assertTrue(REALITY_ROW in names() && XHTTP_ROW in names(), "the board as it opens: ${names()}")

            buildAgain()

            assertTrue(REALITY_ROW in names() && XHTTP_ROW in names(), "a transport disappeared: ${names()}")
        }
    }

    @Test fun theListIsWhereItWasScrolledToWhenTheScreenIsBuiltAgain() {
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()))) { buildAgain ->
            fun whereTheLastRoomIs(): Float = laidTexts().first { it.text == LAST_ROOM }.node.positionInRoot.y

            val asItOpens = whereTheLastRoomIs()
            onNodeWithText(LAST_ROOM).performScrollTo()
            waitForIdle()
            val scrolledTo = whereTheLastRoomIs()
            assertTrue(scrolledTo < asItOpens - 100f, "the list did not scroll: $asItOpens, then $scrolledTo")

            buildAgain()

            assertEquals(scrolledTo, whereTheLastRoomIs(), 1f, "the list was back at its top when the screen was built again")
        }
    }

    @Test fun aSessionsTrafficIsStillOnTheStripWhenTheScreenIsBuiltAgain() {
        // The chart was the screen's own. Leave the app with a session up, come
        // back five seconds later, and its strip was an empty rule again.
        val tunnel = Tunnel(upSince = SESSION, counters = TrafficCounters(bytesIn = 2_000_000L, bytesOut = 100_000L))
        var now = SESSION + 5_000L
        val models = app.models(Memory(AppFixture.stored()), tunnel, clock = { now })
        // A second of the session, as the screen would have read it. The clock
        // then stands: a screen built in the same second reads again, and what
        // it reads is no second yet.
        models.home.trafficSessionIs(SESSION)
        runBlocking {
            models.home.sampleTraffic()
            tunnel.counters = TrafficCounters(bytesIn = 2_000_000L + 300L * 1024L, bytesOut = 100_000L + 2L * 1024L)
            now += 1_000L
            models.home.sampleTraffic()
        }

        app.onTheScreen(phone, models) { buildAgain ->
            assertTrue(RECEIVING in names() && SENDING in names(), "the strip of a session with a second on record: ${names()}")

            buildAgain()

            assertTrue(
                RECEIVING in names() && SENDING in names(),
                "the session's traffic was gone from the strip when the screen was built again: ${names()}"
            )
        }
    }

    private companion object {
        val REALITY_ROW = pkTied("ES · Madrid")
        const val XHTTP_ROW = "Netherlands-Amsterdam-03"

        /** The last room of our own list, a screen and a half down. */
        val LAST_ROOM = pkTied("US · olcRTC · WB")

        /** When the session of these tests came up. */
        const val SESSION = 1_760_000_000_000L
        const val RECEIVING = "↓ 300 KB/s"
        const val SENDING = "↑ 2.0 KB/s"
    }
}
