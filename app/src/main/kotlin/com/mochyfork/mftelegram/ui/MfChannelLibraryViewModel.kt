package com.mochyfork.mftelegram.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mochyfork.mftelegram.AuthInput
import com.mochyfork.mftelegram.AuthState
import com.mochyfork.mftelegram.SyncState
import com.mochyfork.mftelegram.TdError
import com.mochyfork.mftelegram.TelegramEngine.Channel
import com.mochyfork.mftelegram.db.ChannelEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class MfKeyError { InvalidId, InvalidHash, InvalidKeys, StorageFailure }

data class MfChannelLibraryState(
    val enabled: Boolean = false,
    val available: Boolean = false,
    val auth: AuthState = AuthState.Starting,
    val authError: String? = null,
    val candidates: List<Channel> = emptyList(),
    val channels: List<ChannelEntity> = emptyList(),
    val sync: SyncState = SyncState.Idle,
    val keyError: MfKeyError? = null,
    val hasInAppKeys: Boolean = false,
)

@HiltViewModel
class MfChannelLibraryViewModel @Inject constructor(
    private val gateway: MfChannelLibraryGateway,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val enabled = MutableStateFlow(gateway.isEnabled())
    private val candidates = MutableStateFlow<List<Channel>>(emptyList())
    private val operationError = MutableStateFlow<String?>(null)
    private val keyError = MutableStateFlow<MfKeyError?>(null)
    private val _state = MutableStateFlow(MfChannelLibraryState(available = gateway.isAvailable))
    val state: StateFlow<MfChannelLibraryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val base = combine(enabled, gateway.auth, gateway.authError, operationError, keyError) {
                    on, auth, authError, requestError, keysError ->
                MfChannelLibraryState(on, gateway.isAvailable, auth, requestError ?: authError, emptyList(), emptyList(), SyncState.Idle, keysError, gateway.hasInAppKeys())
            }
            val withChannels = combine(base, gateway.channels) { current, channels ->
                current.copy(channels = channels)
            }
            val withSync = combine(withChannels, gateway.syncState, gateway.keysRevision) { current, sync, _ ->
                current.copy(sync = sync, available = gateway.isAvailable, hasInAppKeys = gateway.hasInAppKeys())
            }
            combine(withSync, candidates) { current, availableChannels ->
                current.copy(candidates = availableChannels)
            }.collect { _state.value = it }
        }
    }

    fun setEnabled(value: Boolean) {
        gateway.setEnabled(value)
        enabled.value = value
        if (value && gateway.isAvailable) {
            gateway.start()
            if (gateway.auth.value == AuthState.Ready) refreshCandidates()
        }

    }

    fun storedApiId(): Int? = gateway.storedApiId()

    fun saveApiKeys(apiId: String, apiHash: String) {
        val keys = validMfApiKeys(apiId, apiHash)
        if (keys == null) {
            keyError.value = when {
                apiId.toIntOrNull()?.let { it <= 0 } == true || apiId.isBlank() -> MfKeyError.InvalidId
                !apiHash.matches(Regex("[0-9a-fA-F]{32}")) -> MfKeyError.InvalidHash
                else -> MfKeyError.InvalidKeys
            }
            return
        }
        viewModelScope.launch {
            keyError.value = null
            if (gateway.saveApiKeys(keys)) {
                if (enabled.value) gateway.start()
            } else {
                keyError.value = MfKeyError.StorageFailure
            }
        }
    }

    fun removeApiKeys() {
        viewModelScope.launch {
            keyError.value = null
            gateway.removeApiKeys()
            if (enabled.value) gateway.start()
        }
    }

    fun submit(input: AuthInput) {
        viewModelScope.launch {
            if (gateway.login(input) && input is AuthInput.Phone) refreshCandidates()
        }
    }

    fun refreshCandidates() {
        viewModelScope.launch {
            operationError.value = null
            try {
                candidates.value = gateway.channelCandidates()
            } catch (error: TdError) {
                operationError.value = error.message
            }
        }
    }

    fun selectChannel(channel: Channel) {
        viewModelScope.launch {
            state.value.channels.filter { it.active }.forEach { gateway.setChannelActive(it.chatId, false) }
            gateway.addChannel(channel.id)
        }
    }

    fun resync() {
        val channel = state.value.channels.firstOrNull { it.active } ?: return
        viewModelScope.launch { gateway.resync(channel.chatId) }
    }

    fun logout() {
        viewModelScope.launch {
            gateway.logout()
            candidates.value = emptyList()
        }
    }
}
