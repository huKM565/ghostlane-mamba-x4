package org.olcbox.app.data.datasource

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedSubscriptionImportTest {
    @Test
    fun generatedFixtureImportsAsEightProfilesInTwoGroups() = runTest {
        val source = LocationsRepositoryImplTest.FakeLocationsDataSource()
        val body = File("../deploy/testdata/subscription.expected.txt").readText()

        assertTrue(LocationsRepositoryImpl(source).importText(body))

        val locations = source.stored!!.locations.map { it.location }
        assertEquals(8, locations.size)
        assertEquals(listOf(4, 4, 4, 12, 4, 4, 4, 12), locations.map { it.bondedPaths().size })
        assertEquals(
            List(4) { "Германия" } + List(4) { "Финляндия" },
            source.stored!!.locations.map { it.metadata?.subscription?.name },
        )
        assertEquals(
            List(4) { "jitsi" } + List(4) { "vkcalls" } + List(4) { "telemost" },
            locations[3].bondedPaths().map { it.provider },
        )
    }
}
