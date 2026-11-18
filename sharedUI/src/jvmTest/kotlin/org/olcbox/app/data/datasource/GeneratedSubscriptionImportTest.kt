package org.olcbox.app.data.datasource

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedSubscriptionImportTest {
    @Test
    fun generatedFixtureImportsAsSixProfilesInTwoGroups() = runTest {
        val source = LocationsRepositoryImplTest.FakeLocationsDataSource()
        val body = File("../deploy/testdata/subscription.expected.txt").readText()

        assertTrue(LocationsRepositoryImpl(source).importText(body))

        val locations = source.stored!!.locations.map { it.location }
        assertEquals(6, locations.size)
        assertEquals(List(6) { 4 }, locations.map { it.bondedPaths().size })
        assertEquals(
            listOf(
                "Mamba 4x (пойдет для мобильного интернета)",
                "VK 4x (обход белых списков)",
                "SaluteJazz 4x",
                "Mamba 4x (пойдет для мобильного интернета)",
                "VK 4x (обход белых списков)",
                "SaluteJazz 4x",
            ),
            locations.map { it.name.substringAfter(" — ") },
        )
        assertEquals(
            List(3) { "Германия" } + List(3) { "Финляндия" },
            source.stored!!.locations.map { it.metadata?.subscription?.name },
        )
        val jazz = locations.filter { it.name.endsWith("SaluteJazz 4x") }
        assertEquals(listOf("salutejazz", "salutejazz"), jazz.map { it.bondedPaths().first().provider })
        assertEquals(List(8) { "datachannel" }, jazz.flatMap { it.bondedPaths() }.map { it.transport })
    }
}
