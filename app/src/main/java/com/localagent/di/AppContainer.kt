package com.localagent.di

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.localagent.auth.CredentialVault
import com.localagent.auth.HermesEnvWriter
import com.localagent.bridge.BridgeSocketServer
import com.localagent.data.ChatRepository
import com.localagent.data.LocalAgentDatabase
import com.localagent.llm.LocalLlmService
import com.localagent.llm.ModelDownloadManager
import com.localagent.runtime.DeviceIpv4
import com.localagent.runtime.HermesBootstrapCoordinator
import com.localagent.runtime.HermesSetupCoordinator
import com.localagent.termux.TermuxRunResultSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")

private val ONBOARDING_DONE_KEY = booleanPreferencesKey("localagent_onboarding_v1")
private val BRIDGE_WIFI_ONLY_KEY = booleanPreferencesKey("bridge_wifi_only")

class AppContainer(context: Context) {
    val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dataStore = appContext.dataStore

    val onboardingDismissed: StateFlow<Boolean> =
        dataStore.data
            .map { prefs -> prefs[ONBOARDING_DONE_KEY] == true }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    val bridgeWifiOnly: StateFlow<Boolean> =
        dataStore.data
            .map { it[BRIDGE_WIFI_ONLY_KEY] == true }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val oauthRedirectInternal = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val oauthRedirectIntents = oauthRedirectInternal.asSharedFlow()

    val database: LocalAgentDatabase =
        Room.databaseBuilder(appContext, LocalAgentDatabase::class.java, "localagent.db")
            .addMigrations(LocalAgentDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration(false)
            .build()

    val chatRepository = ChatRepository(database, scope)
    val credentialVault = CredentialVault(appContext)
    val envWriter = HermesEnvWriter(appContext)
    val bootstrapCoordinator = HermesBootstrapCoordinator(appContext)

    val termuxRunResults = MutableSharedFlow<TermuxRunResultSummary>(extraBufferCapacity = 64)

    val bridgeServer =
        BridgeSocketServer(
            appContext = appContext,
            bridgeAuthToken = { credentialVault.bridgeAuthToken() },
            wifiOnlyBind = { bridgeWifiOnly.value },
        )

    val modelDownloader = ModelDownloadManager(appContext)
    val localLlm =
        LocalLlmService(
            dataStore,
            modelDownloader,
            bearerToken = { credentialVault.localLlmHttpBearer() },
            appContext = appContext,
            lanListenAllowed = {
                !bridgeWifiOnly.value || DeviceIpv4.isActiveTransportWifi(appContext)
            },
        )

    init {
        credentialVault.localLlmHttpBearer()
        bridgeServer.start(scope)
        bootstrapCoordinator.ensureFilesystemLayout()
        envWriter.syncFromVault(credentialVault.snapshot())

        scope.launch {
            bridgeServer.incoming.collect { event ->
                chatRepository.handleEvent(event)
            }
        }

        scope.launch {
            localLlm.autoLoad()
        }
    }

    fun deliverOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "com.localagent" && data.host == "oauth") {
            oauthRedirectInternal.tryEmit(Intent(intent))
        }
    }

    suspend fun setBridgeWifiOnly(enabled: Boolean) {
        dataStore.edit { it[BRIDGE_WIFI_ONLY_KEY] = enabled }
        bridgeServer.restart(scope)
        localLlm.refreshHttpAfterNetworkPolicy()
    }

    suspend fun completeOnboarding() {
        dataStore.edit { it[ONBOARDING_DONE_KEY] = true }
    }

    suspend fun resetOnboarding() {
        dataStore.edit { it.remove(ONBOARDING_DONE_KEY) }
    }

    val hermesSetup: HermesSetupCoordinator by lazy {
        HermesSetupCoordinator(appContext, bootstrapCoordinator.manifest())
    }
}
