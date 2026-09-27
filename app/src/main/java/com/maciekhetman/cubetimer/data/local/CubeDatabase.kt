package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncMetadataDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity

@Database(
    entities = [
        SolveEntity::class,
        SessionEntity::class,
        SyncOutboxEntity::class,
        SyncMetadataEntity::class,
        ConflictEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(CubeTypeConverters::class)
abstract class CubeDatabase : RoomDatabase() {

    abstract fun solveDao(): SolveDao
    abstract fun sessionDao(): SessionDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun conflictDao(): ConflictDao

    companion object {
        private const val DATABASE_NAME = "cubetimer.db"

        /**
         * v2 adds `solves.timing_device` (CubeSync `timing_device`). Existing rows were all timed
         * on screen, hence the "keyboard" default. Without this, fallbackToDestructiveMigration
         * would wipe every local (including never-synced guest) solve on upgrade.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `solves` ADD COLUMN `timing_device` TEXT NOT NULL DEFAULT 'keyboard'")
            }
        }

        @Volatile
        private var INSTANCE: CubeDatabase? = null

        fun getInstance(context: Context): CubeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): CubeDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CubeDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON;")
                    }
                })
                .addMigrations(MIGRATION_1_2)
                // Deliberately no fallbackToDestructiveMigration(): a version bump without a
                // Migration must fail loudly rather than silently wipe every local solve.
                .build()
        }

        fun createInMemory(
            context: Context,
            queryExecutor: java.util.concurrent.Executor? = null,
            transactionExecutor: java.util.concurrent.Executor? = null
        ): CubeDatabase {
            val builder = Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                CubeDatabase::class.java
            ).allowMainThreadQueries()

            if (queryExecutor != null) {
                builder.setQueryExecutor(queryExecutor)
            }
            if (transactionExecutor != null) {
                builder.setTransactionExecutor(transactionExecutor)
            }
            return builder.build()
        }
    }
}
