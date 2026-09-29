package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryMigrationRaceTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() = runTest {
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
    }

    /**
     * Test that migration copies legacy keys even if settings store already has an unrelated key.
     * This simulates the race condition where DataStoreMigration writes
     * `datastore_solves_migrated` to settings store before SettingsRepository migration runs.
     */
    @Test
    fun testMigrationWorksWithExistingUnrelatedKey() = runTest {
        // Pre-populate settings store with an unrelated key (simulating DataStoreMigration's write)
        context.settingsDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("datastore_solves_migrated")] = true
        }

        // Pre-populate legacy solves store with user settings
        context.solvesDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("amoled_enabled")] = true
            prefs[stringPreferencesKey("default_mode")] = "CUBE_4x4"
        }

        // Run migration
        settingsRepository.migrateFromLegacyIfNeeded()

        // Verify that legacy keys were copied despite existing key
        val settings = context.settingsDataStore.data.first()
        assertEquals("amoled_enabled should be migrated",
            true, settings[booleanPreferencesKey("amoled_enabled")])
        assertEquals("default_mode should be migrated",
            "CUBE_4x4", settings[stringPreferencesKey("default_mode")])
        assertEquals("Migration flag should be set",
            true, settings[booleanPreferencesKey("settings_migrated_from_legacy")])
    }

    /**
     * Test that migration copies only keys absent from settings store,
     * respecting user's existing settings.
     */
    @Test
    fun testMigrationDoesNotOverwriteExistingSettings() = runTest {
        // Pre-populate settings store with a user preference
        context.settingsDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("amoled_enabled")] = false
        }

        // Pre-populate legacy solves store with conflicting values
        context.solvesDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("amoled_enabled")] = true // Different!
            prefs[stringPreferencesKey("default_mode")] = "CUBE_5x5"
        }

        // Run migration
        settingsRepository.migrateFromLegacyIfNeeded()

        // Verify that existing setting was NOT overwritten
        val settings = context.settingsDataStore.data.first()
        assertEquals("amoled_enabled should keep existing value (false)",
            false, settings[booleanPreferencesKey("amoled_enabled")])

        // But new legacy keys should be copied
        assertEquals("default_mode should be migrated",
            "CUBE_5x5", settings[stringPreferencesKey("default_mode")])
    }

    /**
     * Test that running migration twice is idempotent (no errors, no data loss).
     */
    @Test
    fun testMigrationIsIdempotent() = runTest {
        // Set up legacy data
        context.solvesDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("amoled_enabled")] = true
            prefs[stringPreferencesKey("default_mode")] = "CUBE_2x2"
        }

        // Run migration once
        settingsRepository.migrateFromLegacyIfNeeded()
        var settings = context.settingsDataStore.data.first()
        val firstMigrationAmoled = settings[booleanPreferencesKey("amoled_enabled")]

        // Run migration again
        settingsRepository.migrateFromLegacyIfNeeded()
        settings = context.settingsDataStore.data.first()
        val secondMigrationAmoled = settings[booleanPreferencesKey("amoled_enabled")]

        // Data should be identical
        assertEquals("amoled_enabled should be unchanged after second run",
            firstMigrationAmoled, secondMigrationAmoled)
        assertEquals("Default mode should still be 2x2",
            "CUBE_2x2", settings[stringPreferencesKey("default_mode")])
    }

    /**
     * Test that the migration flag is set atomically with the copy.
     */
    @Test
    fun testMigrationFlagIsSetAfterMigration() = runTest {
        // Set up legacy data
        context.solvesDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("dynamic_color_enabled")] = true
        }

        // Verify flag is not set initially
        var settings = context.settingsDataStore.data.first()
        assertNull("Flag should not exist initially",
            settings[booleanPreferencesKey("settings_migrated_from_legacy")])

        // Run migration
        settingsRepository.migrateFromLegacyIfNeeded()

        // Verify flag is now set
        settings = context.settingsDataStore.data.first()
        assertEquals("Flag should be set",
            true, settings[booleanPreferencesKey("settings_migrated_from_legacy")])
    }

    /**
     * Test that migration skips legacy keys that start with app_time_
     * (those are not user preferences, they're app usage tracking).
     */
    @Test
    fun testMigrationSkipsAppTimeKeys() = runTest {
        // Set up legacy data with app_time keys
        context.solvesDataStore.edit { prefs ->
            prefs[stringPreferencesKey("app_time_CUBE_3x3")] = "123456"
            prefs[booleanPreferencesKey("amoled_enabled")] = true
        }

        // Run migration
        settingsRepository.migrateFromLegacyIfNeeded()

        // Verify that user preference was copied but app_time was not
        val settings = context.settingsDataStore.data.first()
        assertEquals("amoled_enabled should be migrated",
            true, settings[booleanPreferencesKey("amoled_enabled")])
        assertNull("app_time keys should not be migrated",
            settings[stringPreferencesKey("app_time_CUBE_3x3")])
    }

    /**
     * With nothing to migrate (a fresh install, or only app_time_* keys in the legacy store) the
     * migration writes nothing at all: every TimerViewModel start runs it, and an edit per launch
     * would be pure overhead.
     */
    @Test
    fun testMigrationWritesNothingWhenThereIsNothingToMigrate() = runTest {
        context.solvesDataStore.edit { prefs ->
            prefs[stringPreferencesKey("app_time_CUBE_3x3")] = "123456"
        }

        settingsRepository.migrateFromLegacyIfNeeded()

        assertTrue("settings store stays empty", context.settingsDataStore.data.first().asMap().isEmpty())
    }

    /**
     * Test that migration copies all supported preference keys.
     */
    @Test
    fun testMigrationCopiesAllSupportedKeys() = runTest {
        // Populate legacy store with all supported keys
        context.solvesDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("dynamic_color_enabled")] = true
            prefs[stringPreferencesKey("default_mode")] = "CUBE_3x3"
            prefs[booleanPreferencesKey("amoled_enabled")] = true
            prefs[booleanPreferencesKey("show_scramble_refresh_button")] = false
            prefs[booleanPreferencesKey("haptics_enabled")] = false
        }

        // Run migration
        settingsRepository.migrateFromLegacyIfNeeded()

        // Verify all keys were copied
        val settings = context.settingsDataStore.data.first()
        assertEquals("dynamic_color_enabled",
            true, settings[booleanPreferencesKey("dynamic_color_enabled")])
        assertEquals("default_mode",
            "CUBE_3x3", settings[stringPreferencesKey("default_mode")])
        assertEquals("amoled_enabled",
            true, settings[booleanPreferencesKey("amoled_enabled")])
        assertEquals("show_scramble_refresh_button",
            false, settings[booleanPreferencesKey("show_scramble_refresh_button")])
        assertEquals("haptics_enabled",
            false, settings[booleanPreferencesKey("haptics_enabled")])
    }
}
