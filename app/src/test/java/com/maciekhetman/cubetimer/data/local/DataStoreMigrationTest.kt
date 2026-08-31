package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreMigrationTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var migration: DataStoreMigration

    private val SOLVES_LIST_KEY = stringPreferencesKey("solves_list")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        migration = DataStoreMigration(context, database)
    }

    @After
    fun tearDown() = runTest {
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
        database.close()
    }

    @Test
    fun testSuccessfulMigration() = runTest {
        val legacyJson = """
            [
                {
                    "id": "solve-1",
                    "timeInMillis": 12500,
                    "penalty": "NONE",
                    "timestamp": 1725000000000,
                    "scramble": "R U R' U'",
                    "mode": "CUBE_3x3"
                },
                {
                    "id": "solve-2",
                    "timeInMillis": 3500,
                    "penalty": "PLUS_TWO",
                    "timestamp": 1725000060000,
                    "scramble": "U R U' R'",
                    "mode": "CUBE_2x2"
                },
                {
                    "id": "solve-3",
                    "timeInMillis": 45000,
                    "penalty": "DNF",
                    "timestamp": 1725000120000,
                    "scramble": "Fw Rw",
                    "mode": "CUBE_4x4"
                }
            ]
        """.trimIndent()

        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = legacyJson
        }

        migration.migrateIfNeeded()

        val settings = context.settingsDataStore.data.first()
        assertEquals(true, settings[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY])

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(3, solves.size)

        val solve1 = solves.find { it.id == "solve-1" }
        assertNotNull(solve1)
        assertEquals("guest", solve1?.ownerId)
        assertEquals("3x3", solve1?.event)
        assertEquals(12500L, solve1?.durationMs)
        assertEquals("none", solve1?.penalty)
        assertEquals(0L, solve1?.version)

        val solve2 = solves.find { it.id == "solve-2" }
        assertNotNull(solve2)
        assertEquals("2x2", solve2?.event)
        assertEquals("plus_two", solve2?.penalty)

        val solve3 = solves.find { it.id == "solve-3" }
        assertNotNull(solve3)
        assertEquals("4x4", solve3?.event)
        assertEquals("dnf", solve3?.penalty)
    }

    @Test
    fun testIdempotency() = runTest {
        val legacyJson = """
            [
                {
                    "id": "solve-idempotent",
                    "timeInMillis": 15000,
                    "penalty": "NONE",
                    "timestamp": 1725000000000,
                    "scramble": "R U R' U'",
                    "mode": "CUBE_3x3"
                }
            ]
        """.trimIndent()

        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = legacyJson
        }

        // Run once
        migration.migrateIfNeeded()
        assertEquals(1, database.solveDao().getAllActiveSolvesForOwner("guest").size)

        // Run again - should be a no-op because migration flag is set
        migration.migrateIfNeeded()
        assertEquals(1, database.solveDao().getAllActiveSolvesForOwner("guest").size)
    }

    @Test
    fun testEmptyDataStore() = runTest {
        migration.migrateIfNeeded()

        val settings = context.settingsDataStore.data.first()
        assertEquals(true, settings[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY])
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)
    }

    @Test
    fun testCorruptedJsonResilience() = runTest {
        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = "not a valid json"
        }

        migration.migrateIfNeeded()

        val settings = context.settingsDataStore.data.first()
        assertEquals(true, settings[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY])
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)
    }
}
