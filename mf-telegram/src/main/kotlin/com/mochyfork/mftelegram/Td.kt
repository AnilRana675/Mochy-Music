// thin seam over tdlib so everything above it runs against a fake in tests. only the calls we
// actually make appear here per clause 1.4 (history, files, chat list, invite links, nothing else)
package com.mochyfork.mftelegram

import kotlinx.coroutines.flow.Flow
import org.drinkless.tdlib.TdApi

// one tdlib call, suspendable. errors throw TdError, retry-after is parsed into it
interface TdPort {
    val updates: Flow<TdApi.Update>
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): TdApi.Object
}

// tdlib error. retryAfter >= 0 means flood wait (clause 4: respect it, never hammer through)
class TdError(
    val code: Int,
    override val message: String,
    retryAfter: Int = 0,
) : Exception(message) {
    val retryAfter: Int = retryAfter.takeIf { it > 0 } ?: parseRetryAfter(message)
}

// auth lifecycle of the tdlib client, as the ui sees it
sealed interface AuthState {
    data object Starting : AuthState
    data object WaitPhoneNumber : AuthState
    data object WaitCode : AuthState
    data class WaitPassword(val hint: String) : AuthState
    data object Ready : AuthState
    data object Closed : AuthState
    data class Failed(val message: String) : AuthState
}

// map a tdlib authorization state into our ui-visible state
fun authStateOf(state: TdApi.AuthorizationState): AuthState = when (state) {
    is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhoneNumber
    is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode
    is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword(state.passwordHint)
    is TdApi.AuthorizationStateReady -> AuthState.Ready
    is TdApi.AuthorizationStateClosed, is TdApi.AuthorizationStateLoggingOut -> AuthState.Closed
    else -> AuthState.Starting
}

// walk tdlib's flood-wait error formats to a retry count.
fun parseRetryAfter(message: String): Int {
    val normalized = message.lowercase()
    return Regex("""flood_wait_(\d+)""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""retry after\s+(\d+)""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        ?: 0
}

// tdlib's stable per-file key; local file ids change on every restart, this does not
val TdApi.RemoteFile.stableKey: String get() = uniqueId
