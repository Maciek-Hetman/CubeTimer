package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
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
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON;")
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
        }

        fun inMemory(context: Context): CubeDatabase = createInMemory(context)

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
