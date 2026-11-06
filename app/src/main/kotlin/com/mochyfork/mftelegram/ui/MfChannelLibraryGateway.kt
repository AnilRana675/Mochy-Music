package com.mochyfork.mftelegram.ui

import com.mochyfork.mftelegram.AuthInput
import com.mochyfork.mftelegram.AuthState
import com.mochyfork.mftelegram.SyncState
import com.mochyfork.mftelegram.TelegramEngine
import com.mochyfork.mftelegram.TelegramEngine.Channel
import com.mochyfork.mftelegram.db.ChannelEntity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

interface MfChannelLibraryGateway {
    val auth: StateFlow<AuthState>
    val authError: StateFlow<String?>
    val channels: Flow<List<ChannelEntity>>
    val syncState: StateFlow<SyncState>
    val keysRevision: StateFlow<Int>
    val isAvailable: Boolean
    fun start(): Boolean
    suspend fun login(input: AuthInput): Boolean
    suspend fun logout()
    suspend fun channelCandidates(): List<Channel>
    suspend fun addChannel(chatId: Long)
    suspend fun setChannelActive(chatId: Long, active: Boolean)
    suspend fun resync(chatId: Long): SyncState
    fun storedApiId(): Int?
    fun hasInAppKeys(): Boolean
    fun isEnabled(): Boolean
    fun setEnabled(value: Boolean)
    suspend fun saveApiKeys(keys: MfApiKeys): Boolean
    suspend fun removeApiKeys()
}

class TelegramMfChannelLibraryGateway(
    private val engine: TelegramEngine,
    private val keyStore: MfApiKeyStore,
    @ApplicationContext context: Context,
) : MfChannelLibraryGateway {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    override val auth get() = engine.auth
    override val authError get() = engine.authError
    override val channels get() = engine.channels
    override val syncState get() = engine.syncState
    private val _keysRevision = MutableStateFlow(0)
    override val keysRevision: StateFlow<Int> = _keysRevision
    override val isAvailable get() = resolvedKeys() != null
    override fun start() = resolvedKeys()?.let { engine.start(it) } ?: false
    fun storedKeys(): MfApiKeys? = keyStore.read()
    fun usesBuildKeys(): Boolean = keyStore.read() == null && !TelegramEngine.defaultConfig().disabled
    fun resolvedKeys(): com.mochyfork.mftelegram.EngineConfig? =
        resolveMfApiKeys(keyStore.read(), TelegramEngine.defaultConfig().takeUnless { it.disabled }?.let {
            MfApiKeys(it.apiId, it.apiHash)
        })?.let { com.mochyfork.mftelegram.EngineConfig(it.apiId, it.apiHash) }
    override suspend fun login(input: AuthInput) = engine.login(input)
    override suspend fun logout() = engine.logout()
    override suspend fun channelCandidates() = engine.channelCandidates()
    override suspend fun addChannel(chatId: Long) { engine.addChannel(chatId) }
    override suspend fun setChannelActive(chatId: Long, active: Boolean) = engine.setChannelActive(chatId, active)
    override suspend fun resync(chatId: Long) = engine.resync(chatId)
    override fun storedApiId() = keyStore.read()?.apiId
    override fun hasInAppKeys() = keyStore.read() != null
    override fun isEnabled() = preferences.getBoolean(EnabledKey, false)
    override fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(EnabledKey, value).apply()
    }
    override suspend fun saveApiKeys(keys: MfApiKeys): Boolean {
        if (!keyStore.save(keys)) return false
        if (engine.isAuthenticated()) engine.logout()
        _keysRevision.value++
        return true
    }
    override suspend fun removeApiKeys() {
        if (engine.isAuthenticated()) engine.logout()
        keyStore.clear()
        _keysRevision.value++
    }

    private companion object {
        const val PreferencesName = "mf_channel_library_settings"
        const val EnabledKey = "enabled"
    }
}
