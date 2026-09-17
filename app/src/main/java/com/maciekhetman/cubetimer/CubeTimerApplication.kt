package com.maciekhetman.cubetimer

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.admin.AdminRepository
import com.maciekhetman.cubetimer.data.admin.AdminRepositoryImpl
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
import com.maciekhetman.cubetimer.data.sync.SyncStateManager
import com.maciekhetman.cubetimer.data.sync.work.SyncScheduler
import com.maciekhetman.cubetimer.data.sync.work.SyncWorker
import com.maciekhetman.cubetimer.data.sync.work.WorkManagerSyncScheduler
import com.maciekhetman.cubetimer.domain.AndroidSha1PrngProvider
import com.maciekhetman.cubetimer.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            syncTrigger = { scheduleImmediateSyncIfAuthenticated() }
        ).also {
            tokenAuthenticator.sessionExpirationListener = it
        }
    }

    val syncStateManager: SyncStateManager by lazy {
        SyncStateManager(
            context = this,
            database = database,
            authManager = authManager,
            onTriggerSync = { scheduleImmediateSyncIfAuthenticated() }
        )
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngineImpl(
            apiClient = apiClient,
            tokenStorage = tokenStorage,
            database = database,
            authManager = authManager,
            conflictResolver = conflictResolver,
            stateManager = syncStateManager
        )
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(
            database = database,
            syncTrigger = { scheduleImmediateSyncIfAuthenticated() }
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
            syncTrigger = { scheduleImmediateSyncIfAuthenticated() }
        )
    }

    val adminRepository: AdminRepository by lazy {
        AdminRepositoryImpl(apiClient)
    }

    /**
     * Background scope for app-wide singleton observers (e.g. periodic sync scheduling).
     * Runs on [Dispatchers.Default] so accessing heavy lazy singletons (authManager, database,
     * network client) never happens on the main thread.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Schedules an immediate sync only when there is a real (non-guest) owner. Used by every
     * write-path syncTrigger so guest-mode writes never enqueue WorkManager work that the sync
     * engine would just no-op on.
     *
     * Checks both [AuthManager.currentOwnerId] and [TokenStorage.getUserId] because
     * [AuthManagerImpl] invokes its syncTrigger during guest-data adoption *before* it flips
     * authState to Authenticated/Admin - tokenStorage's userId is already persisted by then.
     */
    private fun scheduleImmediateSyncIfAuthenticated() {
        val hasNonGuestOwner = authManager.currentOwnerId != "guest" ||
            !tokenStorage.getUserId().isNullOrBlank()
        if (hasNonGuestOwner) {
            syncScheduler.scheduleImmediateSync()
        }
    }

    /**
     * Observes [AuthManager.authState] and keeps the 15-minute periodic sync scheduled while an
     * owner is signed in, cancelling it back to guest mode. Skips entirely when WorkManager isn't
     * available (e.g. Robolectric tests that instantiate this Application without initializing
     * WorkManager), and guards each scheduler call the same way in case availability changes
     * mid-collection.
     */
    private fun observeAuthStateForPeriodicSync() {
        val workManagerAvailable = try {
            WorkManager.getInstance(this)
            true
        } catch (e: IllegalStateException) {
            false
        }
        if (!workManagerAvailable) return

        applicationScope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated, is AuthState.Admin -> {
                        try {
                            syncScheduler.schedulePeriodicSync()
                        } catch (e: IllegalStateException) {
                            // WorkManager not available; nothing to do.
                        }
                    }
                    is AuthState.Guest -> {
                        try {
                            syncScheduler.cancelPeriodicSync()
                        } catch (e: IllegalStateException) {
                            // WorkManager not available; nothing to do.
                        }
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
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
        AndroidSha1PrngProvider.install()
        observeAuthStateForPeriodicSync()
    }

    companion object {
        const val BASE_URL = "https://cubesync.example.com"

        @Volatile
        private var instance: CubeTimerApplication? = null

        fun getInstance(): CubeTimerApplication {
            return instance ?: throw IllegalStateException("CubeTimerApplication is not initialized")
        }
    }
}
