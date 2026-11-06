// auth state machine: tdlib pushes UpdateAuthorizationState, we mirror it for the ui and turn
// explicit user actions into the tdapi requests. login is always user-typed, never guessed
package com.mochyfork.mftelegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi

/** what the user typed in the login screen (phase 6) */
sealed interface AuthInput {
    data class Phone(val phone: String) : AuthInput
    data class Code(val code: String) : AuthInput
    data class FirstName(val firstName: String) : AuthInput
    data class Password(val password: String) : AuthInput
}

class AuthMachine(private val port: TdPort, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private val errors = MutableStateFlow<String?>(null)
    private val inputLock = Mutex()
    val lastError: StateFlow<String?> = errors.asStateFlow()

    /** watch tdlib authorization state and answer required registration terms once */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            port.updates.collect { update ->
                if (update !is TdApi.UpdateAuthorizationState) return@collect
                val newState = authStateOf(update.authorizationState)
                _state.value = newState
                when (val state = update.authorizationState) {
                    is TdApi.AuthorizationStateClosed -> errors.value = null
                    is TdApi.AuthorizationStateWaitRegistration -> {
                        // tdlib may ask for a first/last name on brand new accounts; the ui sets
                        // it explicitly. nothing automatic happens here
                        _state.value = AuthState.Failed("registration needed")
                    }
                    else -> Unit
                }
            }
        }
    }

    /** one user action at a time; wrong-state input is rejected, not retried */
    suspend fun input(action: AuthInput): Boolean = inputLock.withLock {
        try {
            when (_state.value) {
                is AuthState.WaitPhoneNumber ->
                    if (action is AuthInput.Phone) port.send(TdApi.SetAuthenticationPhoneNumber(action.phone.trim(), null)) else return@withLock false
                is AuthState.WaitCode ->
                    if (action is AuthInput.Code) port.send(TdApi.CheckAuthenticationCode(action.code.trim())) else return@withLock false
                is AuthState.WaitPassword ->
                    if (action is AuthInput.Password) port.send(TdApi.CheckAuthenticationPassword(action.password)) else return@withLock false
                else -> return@withLock false
            }
            // tdlib sends the next authorization state update; nothing to await here
            true
        } catch (e: TdError) {
            errors.value = when {
                e.retryAfter > 0 -> "rate limited ${e.retryAfter}s"
                e.code == 400 && e.message.contains("PASSWORD_HASH_INVALID") -> "wrong password"
                e.code == 400 && e.message.contains("PHONE_NUMBER_INVALID") -> "wrong number"
                e.code == 400 && e.message.contains("PHONE_CODE_INVALID") -> "wrong code"
                else -> e.message
            }
            false
        }
    }

    /** logs out. the engine wipes td storage afterwards, so codes and session are gone */
    suspend fun logout(): Boolean = try {
        port.send(TdApi.LogOut()) is TdApi.Ok
    } catch (e: TdError) {
        errors.value = e.message
        false
    }
}
