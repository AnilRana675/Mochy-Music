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
    val retryAfter: Int = 0,
) : Exception(message)

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

// walk a tdapi error to a flood-wait count. message form: "Too Many Requests: retry after 30"
fun parseRetryAfter(message: String): Int =
    Regex("""retry after (\d+)""").find(message.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0

// tdlib's stable per-file key; local file ids change on every restart, this does not
val TdApi.RemoteFile.stableKey: String get() = uniqueId
