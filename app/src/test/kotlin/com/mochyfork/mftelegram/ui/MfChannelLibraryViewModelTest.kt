package com.mochyfork.mftelegram.ui

import com.mochyfork.mftelegram.AuthInput
import com.mochyfork.mftelegram.AuthState
import com.mochyfork.mftelegram.SyncState
import com.mochyfork.mftelegram.TelegramEngine.Channel
import com.mochyfork.mftelegram.db.ChannelEntity
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MfChannelLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fake: FakeGateway

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        fake = FakeGateway()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun authStepsAreForwardedOneAtATime() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        viewModel.setEnabled(true)
        fake.auth.value = AuthState.WaitPhoneNumber
        viewModel.submit(AuthInput.Phone("+123"))
        fake.auth.value = AuthState.WaitCode
        viewModel.submit(AuthInput.Code("12345"))
        fake.auth.value = AuthState.WaitPassword("hint")
        viewModel.submit(AuthInput.Password("secret"))
        advanceUntilIdle()
        assertEquals(
            listOf(AuthInput.Phone("+123"), AuthInput.Code("12345"), AuthInput.Password("secret")),
            fake.inputs,
        )
    }

    @Test fun errorStateIsExposed() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        fake.error.value = "rate limited 60s"
        advanceUntilIdle()
        assertEquals("rate limited 60s", viewModel.state.value.authError)
    }

    @Test fun channelPickDeactivatesOldChannelAndAddsNewOne() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        fake.channelRows.value = listOf(ChannelEntity(1, "old", null, active = true))
        fake.candidates.value = listOf(Channel(2, "new"))
        advanceUntilIdle()
        viewModel.selectChannel(fake.candidates.value.single())
        advanceUntilIdle()
        assertEquals(listOf(1L to false, 2L to true), fake.activeChanges)
    }

    @Test fun indexProgressIsExposed() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        fake.sync.value = SyncState.Scanning(1, 4, 99)
        advanceUntilIdle()
        assertEquals(SyncState.Scanning(1, 4, 99), viewModel.state.value.sync)
    }

    @Test fun validKeysAreSavedAndInvalidKeysAreRejected() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        viewModel.saveApiKeys("12345", "0123456789abcdef0123456789abcdef")
        advanceUntilIdle()
        assertEquals(MfApiKeys(12345, "0123456789abcdef0123456789abcdef"), fake.savedKeys)
        viewModel.saveApiKeys("nope", "bad")
        advanceUntilIdle()
        assertEquals(MfKeyError.InvalidId, viewModel.state.value.keyError)
        viewModel.saveApiKeys("12345", "bad")
        advanceUntilIdle()
        assertEquals(MfKeyError.InvalidHash, viewModel.state.value.keyError)
    }

    @Test fun inAppKeysTakePrecedenceOverBuildTimeKeys() {
        val inApp = MfApiKeys(1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val build = MfApiKeys(2, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertEquals(inApp, resolveMfApiKeys(inApp, build))
        assertEquals(build, resolveMfApiKeys(null, build))
    }

    @Test fun removeKeysClearsStoredKeys() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        viewModel.saveApiKeys("12345", "0123456789abcdef0123456789abcdef")
        advanceUntilIdle()
        viewModel.removeApiKeys()
        advanceUntilIdle()
        assertEquals(1, fake.removeCount)
    }

    @Test fun changingKeysWhileAuthenticatedWipesTheSession() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        fake.auth.value = AuthState.Ready
        viewModel.saveApiKeys("12345", "0123456789abcdef0123456789abcdef")
        advanceUntilIdle()
        assertEquals(1, fake.logoutCount)
    }

    @Test fun saveFailureSurfacesAnErrorStateWithoutThrowing() = runTest(dispatcher) {
        val viewModel = MfChannelLibraryViewModel(fake, SavedStateHandle())
        fake.saveSucceeds = false
        viewModel.saveApiKeys("12345", "0123456789abcdef0123456789abcdef")
        advanceUntilIdle()
        assertEquals(MfKeyError.StorageFailure, viewModel.state.value.keyError)
        assertEquals(null, fake.savedKeys)
    }

    private class FakeGateway : MfChannelLibraryGateway {
        override val auth = MutableStateFlow<AuthState>(AuthState.Starting)
        val error = MutableStateFlow<String?>(null)
        override val authError: StateFlow<String?> = error
        val channelRows = MutableStateFlow<List<ChannelEntity>>(emptyList())
        override val channels: Flow<List<ChannelEntity>> = channelRows
        val sync = MutableStateFlow<SyncState>(SyncState.Idle)
        override val syncState: StateFlow<SyncState> = sync
        val candidates = MutableStateFlow<List<Channel>>(emptyList())
        val inputs = mutableListOf<AuthInput>()
        val activeChanges = mutableListOf<Pair<Long, Boolean>>()
        var savedKeys: MfApiKeys? = null
        var removeCount = 0
        var logoutCount = 0
        var saveSucceeds = true
        override val keysRevision = MutableStateFlow(0)
        override val isAvailable = true
        override fun start() = true
        override suspend fun login(input: AuthInput): Boolean {
            inputs += input
            return true
        }
        override suspend fun logout() {
            logoutCount++
        }
        override suspend fun channelCandidates() = candidates.value
        override suspend fun addChannel(chatId: Long) {
            activeChanges += chatId to true
        }
        override suspend fun setChannelActive(chatId: Long, active: Boolean) {
            activeChanges += chatId to active
        }
        override suspend fun resync(chatId: Long) = sync.value
        override fun storedApiId() = savedKeys?.apiId
        override fun hasInAppKeys() = savedKeys != null
        override fun isEnabled() = false
        override fun setEnabled(value: Boolean) = Unit
        override suspend fun saveApiKeys(keys: MfApiKeys): Boolean {
            if (!saveSucceeds) return false
            if (auth.value == AuthState.Ready) logout()
            savedKeys = keys
            keysRevision.value++
            return true
        }
        override suspend fun removeApiKeys() {
            savedKeys = null
            removeCount++
            keysRevision.value++
        }
    }
}
