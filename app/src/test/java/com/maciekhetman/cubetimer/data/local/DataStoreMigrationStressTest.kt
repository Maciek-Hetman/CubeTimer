package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreMigrationStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var migration: DataStoreMigration

    private val SOLVES_LIST_KEY = stringPreferencesKey("solves_list")

    @Before
    fun setup() = kotlinx.coroutines.runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
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
    fun testEmptyAndNullDataStoreVariations() = runTest {
        val variations = listOf(
            "",
            "   ",
            "\n\t  \r",
            "[]",
            "  [  ]  ",
            "   [\n\t]  "
        )

        for (variant in variations) {
            context.settingsDataStore.edit { it.clear() }
            context.solvesDataStore.edit { preferences ->
                preferences[SOLVES_LIST_KEY] = variant
            }
            database.solveDao().deleteSolvesForOwner("guest")

            migration.migrateIfNeeded()

            val settings = context.settingsDataStore.data.first()
            assertEquals(true, settings[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY])
            val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
            assertEquals("Expected 0 solves for variant: '$variant'", 0, solves.size)
        }
    }

    @Test
    fun testMalformedAndCorruptedJsonPayloads() = runTest {
        val corruptedPayloads = listOf(
            "{\"unclosed_object\": true",
            "not json at all",
            "12345",
            "true",
            "\"just a string\"",
            "{\"id\": \"single_object_not_array\"}",
            "[123, true, \"hello\", null]"
        )

        for (payload in corruptedPayloads) {
            context.settingsDataStore.edit { it.clear() }
            context.solvesDataStore.edit { preferences ->
                preferences[SOLVES_LIST_KEY] = payload
            }
            database.solveDao().deleteSolvesForOwner("guest")

            migration.migrateIfNeeded()

            val settings = context.settingsDataStore.data.first()
            assertEquals(true, settings[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY])
            val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
            assertEquals("Expected 0 solves for corrupted payload: '$payload'", 0, solves.size)
        }
    }

    @Test
    fun testMissingFieldsAndDefaults() = runTest {
        val json = """
            [
                {
                    "timeInMillis": 14250
                },
                {
                    "id": "solve-with-neg-time",
                    "timeInMillis": -5000,
                    "penalty": "NONE"
                },
                {
                    "id": "",
                    "scramble": "R U R' U'",
                    "extra_unexpected_field": "ignore_me"
                }
            ]
        """.trimIndent()

        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = json
        }

        migration.migrateIfNeeded()

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(3, solves.size)

        // Solve 1: missing id, missing scramble, missing penalty, missing mode
        val solve1 = solves.find { it.durationMs == 14250L }
        assertNotNull(solve1)
        assertTrue(solve1!!.id.isNotBlank())
        assertEquals("none", solve1.penalty)
        assertEquals("3x3", solve1.event)
        assertEquals("", solve1.scramble)

        // Solve 2: negative duration should be coerced to 0L
        val solve2 = solves.find { it.id == "solve-with-neg-time" }
        assertNotNull(solve2)
        assertEquals(0L, solve2!!.durationMs)

        // Solve 3: empty id should generate a non-blank UUID
        val solve3 = solves.find { it.scramble == "R U R' U'" }
        assertNotNull(solve3)
        assertTrue(solve3!!.id.isNotBlank())
        assertNotEquals("", solve3.id)
    }

    @Test
    fun testUnknownAndNonStandardModesAndPenalties() = runTest {
        val json = """
            [
                { "id": "m1", "mode": "2X2", "penalty": "+2", "timeInMillis": 1000 },
                { "id": "m2", "mode": " 4x4 ", "penalty": " plus2 ", "timeInMillis": 2000 },
                { "id": "m3", "mode": "cube_5x5", "penalty": "dnf", "timeInMillis": 3000 },
                { "id": "m4", "mode": "Megaminx", "penalty": "PLUS_TWO", "timeInMillis": 4000 },
                { "id": "m5", "mode": "PYRAMINX", "penalty": "NONE", "timeInMillis": 5000 },
                { "id": "m6", "mode": "SKEWB", "penalty": "UNKNOWN_PENALTY", "timeInMillis": 6000 },
                { "id": "m7", "mode": "unknown_mode", "penalty": "DISQUALIFIED", "timeInMillis": 7000 },
                { "id": "m8", "mode": "2", "penalty": "+2", "timeInMillis": 8000 },
                { "id": "m9", "mode": "3", "penalty": "none", "timeInMillis": 9000 }
            ]
        """.trimIndent()

        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = json
        }

        migration.migrateIfNeeded()

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(9, solves.size)

        assertEquals("2x2", solves.find { it.id == "m1" }?.event)
        assertEquals("plus_two", solves.find { it.id == "m1" }?.penalty)

        assertEquals("4x4", solves.find { it.id == "m2" }?.event)
        assertEquals("plus_two", solves.find { it.id == "m2" }?.penalty)

        assertEquals("5x5", solves.find { it.id == "m3" }?.event)
        assertEquals("dnf", solves.find { it.id == "m3" }?.penalty)

        assertEquals("megaminx", solves.find { it.id == "m4" }?.event)
        assertEquals("plus_two", solves.find { it.id == "m4" }?.penalty)

        assertEquals("pyraminx", solves.find { it.id == "m5" }?.event)
        assertEquals("none", solves.find { it.id == "m5" }?.penalty)

        // Fallbacks
        assertEquals("3x3", solves.find { it.id == "m6" }?.event)
        assertEquals("none", solves.find { it.id == "m6" }?.penalty)

        assertEquals("3x3", solves.find { it.id == "m7" }?.event)
        assertEquals("none", solves.find { it.id == "m7" }?.penalty)

        assertEquals("2x2", solves.find { it.id == "m8" }?.event)
        assertEquals("3x3", solves.find { it.id == "m9" }?.event)
    }

    @Test
    fun testSpecialCharactersAndAdversarialScrambles() = runTest {
        val hugeScramble = "R U R' U' ".repeat(500) // 5000 chars
        val unicodeScramble = "🎲 ⏱️ R U R' U' 旋转魔方 3×3 測試 ⚡ \uD83E\uDDE9"
        val quotesScramble = "R' U\" D' F\\ B/ L' 'quoted' \"double\""

        val json = """
            [
                { "id": "scramble-huge", "scramble": "$hugeScramble", "timeInMillis": 10000 },
                { "id": "scramble-unicode", "scramble": "$unicodeScramble", "timeInMillis": 11000 },
                { "id": "scramble-quotes", "scramble": "R' U\" D' F\\ B/ L' 'quoted' \"double\"", "timeInMillis": 12000 }
            ]
        """.trimIndent()

        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = json
        }

        migration.migrateIfNeeded()

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(3, solves.size)

        val sHuge = solves.find { it.id == "scramble-huge" }
        assertEquals(hugeScramble, sHuge?.scramble)

        val sUnicode = solves.find { it.id == "scramble-unicode" }
        assertEquals(unicodeScramble, sUnicode?.scramble)

        val sQuotes = solves.find { it.id == "scramble-quotes" }
        assertNotNull(sQuotes)
        assertTrue(sQuotes!!.scramble.contains("quoted"))
    }

    @Test
    fun testLargeBatchMigration() = runTest {
        val count = 250
        val sb = StringBuilder("[")
        for (i in 0 until count) {
            if (i > 0) sb.append(",")
            sb.append("""{"id":"solve-batch-$i","timeInMillis":${10000 + i},"mode":"CUBE_3x3","penalty":"NONE","timestamp":${1725000000000L + i * 1000},"scramble":"R U R' U' $i"}""")
        }
        sb.append("]")

        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = sb.toString()
        }

        migration.migrateIfNeeded()

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(count, solves.size)
    }

    @Test
    fun testConcurrentMigrationAttempts() = runTest {
        val legacyJson = """
            [
                { "id": "concurrent-1", "timeInMillis": 10000, "mode": "CUBE_3x3" },
                { "id": "concurrent-2", "timeInMillis": 12000, "mode": "CUBE_3x3" },
                { "id": "concurrent-3", "timeInMillis": 14000, "mode": "CUBE_3x3" }
            ]
        """.trimIndent()

        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_LIST_KEY] = legacyJson
        }

        // Launch 20 concurrent migration calls
        val jobs = (1..20).map {
            async {
                migration.migrateIfNeeded()
            }
        }
        jobs.awaitAll()

        val settings = context.settingsDataStore.data.first()
        assertEquals(true, settings[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY])

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(3, solves.size)
    }
}
