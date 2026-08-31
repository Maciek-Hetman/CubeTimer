package com.maciekhetman.cubetimer

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthManagerImpl
import com.maciekhetman.cubetimer.data.auth.EncryptedTokenStorage
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.AuthInterceptor
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.CubeSyncAuthApiService
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.TokenAuthenticator
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepository
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.data.sync.ConflictResolver
import com.maciekhetman.cubetimer.data.sync.ConflictResolverImpl
import com.maciekhetman.cubetimer.data.sync.SyncEngine
import com.maciekhetman.cubetimer.data.sync.SyncEngineImpl
import com.maciekhetman.cubetimer.data.sync.work.SyncScheduler
import com.maciekhetman.cubetimer.data.sync.work.SyncWorker
import com.maciekhetman.cubetimer.data.sync.work.WorkManagerSyncScheduler

/**
 * Custom Application class initializing dependency singletons and WorkManager configuration.
 */
class CubeTimerApplication : Application(), Configuration.Provider {

    val database: CubeDatabase by lazy {
        CubeDatabase.getInstance(this)
    }

    val tokenStorage: TokenStorage by lazy {
        EncryptedTokenStorage(this)
    }

    val authInterceptor: AuthInterceptor by lazy {
        AuthInterceptor(tokenStorage)
    }

    val tokenAuthenticator: TokenAuthenticator by lazy {
        TokenAuthenticator(tokenStorage, baseUrl = BASE_URL)
    }

    val apiService: CubeSyncAuthApiService by lazy {
        NetworkModule.provideAuthApiService(
            baseUrl = BASE_URL,
            okHttpClient = NetworkModule.provideOkHttpClient(
                authInterceptor = authInterceptor,
                authenticator = tokenAuthenticator
            )
        )
    }

    val apiClient: CubeSyncApiClient by lazy {
        NetworkModule.provideCubeSyncApiClient(apiService)
    }

    val syncScheduler: SyncScheduler by lazy {
        WorkManagerSyncScheduler(this)
    }

    val conflictResolver: ConflictResolver by lazy {
        ConflictResolverImpl(database)
    }

    val authManager: AuthManager by lazy {
        AuthManagerImpl(
            apiClient = apiClient,
            tokenStorage = tokenStorage,
            database = database,
            syncTrigger = { syncScheduler.scheduleImmediateSync() }
        ).also {
            tokenAuthenticator.sessionExpirationListener = it
        }
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngineImpl(
            apiClient = apiClient,
            tokenStorage = tokenStorage,
            database = database,
            authManager = authManager,
            conflictResolver = conflictResolver
        )
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(
            database = database,
            syncTrigger = { syncScheduler.scheduleImmediateSync() }
        )
    }

    val sessionManager: SessionManager by lazy {
        SessionManagerImpl(
            context = this,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = authManager
        )
    }

    val solvesRepository: SolvesRepository by lazy {
        SolvesRepository(
            context = this,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            syncTrigger = { syncScheduler.scheduleImmediateSync() }
        )
    }

    val adminRepository: com.maciekhetman.cubetimer.data.admin.AdminRepository by lazy {
        com.maciekhetman.cubetimer.data.admin.AdminRepositoryImpl(apiClient)
    }

    val syncStateManager: com.maciekhetman.cubetimer.data.sync.SyncStateManager by lazy {
        com.maciekhetman.cubetimer.data.sync.SyncStateManager(
            context = this,
            database = database,
            authManager = authManager,
            onTriggerSync = { syncScheduler.scheduleImmediateSync() }
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? {
                    return if (workerClassName == SyncWorker::class.java.name) {
                        SyncWorker(appContext, workerParameters, syncEngine)
                    } else {
                        null
                    }
                }
            })
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.maciekhetman.cubetimer.domain.AndroidSha1PrngProvider.install()
    }

    companion object {
        const val BASE_URL = "https://cubesync.example.com"

        @Volatile
        private var instance: CubeTimerApplication? = null

        fun getInstance(): CubeTimerApplication {
            return instance ?: throw IllegalStateException("CubeTimerApplication is not initialized")
        }

        fun getSyncEngineInstance(context: Context): SyncEngine {
            val app = context.applicationContext as? CubeTimerApplication
            return app?.syncEngine ?: run {
                val db = CubeDatabase.getInstance(context)
                val tokenStorage = EncryptedTokenStorage(context)
                val authInterceptor = AuthInterceptor(tokenStorage)
                val tokenAuthenticator = TokenAuthenticator(tokenStorage, baseUrl = BASE_URL)
                val apiService = NetworkModule.provideAuthApiService(
                    baseUrl = BASE_URL,
                    okHttpClient = NetworkModule.provideOkHttpClient(
                        authInterceptor = authInterceptor,
                        authenticator = tokenAuthenticator
                    )
                )
                val apiClient = NetworkModule.provideCubeSyncApiClient(apiService)
                val authManager = AuthManagerImpl.getInstance(context)
                SyncEngineImpl(
                    apiClient = apiClient,
                    tokenStorage = tokenStorage,
                    database = db,
                    authManager = authManager,
                    conflictResolver = ConflictResolverImpl(db)
                )
            }
        }
    }
}
