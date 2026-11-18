package org.olcbox.app.data.datasource

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedSubscriptionImportTest {
    @Test
    fun generatedFixtureImportsAsFourProfilesInTwoGroups() = runTest {
        val source = LocationsRepositoryImplTest.FakeLocationsDataSource()
        val body = File("../deploy/testdata/subscription.expected.txt").readText()

        assertTrue(LocationsRepositoryImpl(source).importText(body))

        val locations = source.stored!!.locations.map { it.location }
        assertEquals(4, locations.size)
        assertEquals(List(4) { 4 }, locations.map { it.bondedPaths().size })
        assertEquals(listOf("Mamba 4x", "VK 4x", "Mamba 4x", "VK 4x"), locations.map { it.name.substringAfter(" — ") })
        assertEquals(
            List(2) { "Германия" } + List(2) { "Финляндия" },
            source.stored!!.locations.map { it.metadata?.subscription?.name },
        )
    }
}
