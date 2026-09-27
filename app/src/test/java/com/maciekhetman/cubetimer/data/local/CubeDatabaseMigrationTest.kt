package com.maciekhetman.cubetimer.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrading a real v1 database (schema taken from the committed app/schemas/.../1.json) must keep
 * every solve. There is no destructive fallback, so a missing or schema-mismatched migration fails
 * the upgrade outright instead of silently wiping local (and never-synced guest) data.
 */
@RunWith(RobolectricTestRunner::class)
class CubeDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_keepsSolvesAndDefaultsTimingDeviceToKeyboard() = runTest {
        createVersion1Database()

        val database = Room.databaseBuilder(context, CubeDatabase::class.java, dbName)
            .addMigrations(CubeDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val solve = database.solveDao().getSolveById("solve-v1")
            assertNotNull("v1 solve must survive the upgrade", solve)
            assertEquals(12_345L, solve!!.durationMs)
            assertEquals("session-v1", solve.sessionId)
            assertEquals("keyboard", solve.timingDevice)

            database.solveDao().upsert(solve.copy(id = "solve-v2", timingDevice = "external_timer"))
            assertEquals("external_timer", database.solveDao().getSolveById("solve-v2")?.timingDevice)
        } finally {
            database.close()
        }
    }

    private fun createVersion1Database() {
        val schema = Json.parseToJsonElement(schemaFile(version = 1).readText()).jsonObject
        val databaseJson = schema.getValue("database").jsonObject
        val statements = buildList {
            databaseJson.getValue("entities").jsonArray.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                add(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    add(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            databaseJson.getValue("setupQueries").jsonArray.forEach { add(it.jsonPrimitive.content) }
        }

        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            statements.forEach(raw::execSQL)
            raw.execSQL(
                "INSERT INTO sessions (id, owner_id, name, event, kind, started_at, ended_at, archived, version, updated_at, deleted_at) " +
                    "VALUES ('session-v1', 'guest', '30 aug 2026 morning', '3x3', 'automatic', '2026-08-30T08:00:00.000Z', NULL, 0, 0, '2026-08-30T08:00:00.000Z', NULL)"
            )
            raw.execSQL(
                "INSERT INTO solves (id, owner_id, session_id, event, duration_ms, penalty, solved_at, scramble, version, updated_at, deleted_at) " +
                    "VALUES ('solve-v1', 'guest', 'session-v1', '3x3', 12345, 'none', '2026-08-30T08:01:00.000Z', 'R U R'' U''', 0, '2026-08-30T08:01:00.000Z', NULL)"
            )
            raw.version = 1
        } finally {
            raw.close()
        }
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.maciekhetman.cubetimer.data.local.CubeDatabase/$version.json"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() }
            ?: error("Room schema $relative not found (working dir: ${File(".").absolutePath})")
    }
}
