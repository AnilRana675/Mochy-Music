// auth mapping, state-legal actions, flood wait surfacing; port fully scripted
package com.mochyfork.mftelegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthMachineTest {
    @Test fun retryAfterParsesFromTheMessage() {
        assertEquals(30, parseRetryAfter("Too Many Requests: retry after 30"))
        assertEquals(0, parseRetryAfter("wrong code"))
    }

    @Test fun tdlibAuthStatesMapToUiStates() {
        assertEquals(AuthState.WaitPhoneNumber, authStateOf(TdApi.AuthorizationStateWaitPhoneNumber()))
        assertEquals(AuthState.WaitCode, authStateOf(TdApi.AuthorizationStateWaitCode()))
        assertEquals(AuthState.WaitPassword("hint"), authStateOf(TdApi.AuthorizationStateWaitPassword().apply { passwordHint = "hint" }))
        assertEquals(AuthState.Ready, authStateOf(TdApi.AuthorizationStateReady()))
        assertEquals(AuthState.Closed, authStateOf(TdApi.AuthorizationStateClosed()))
    }

    @Test fun phoneCodeAndPasswordGoOutOneAtATime() = kotlinx.coroutines.test.runTest {
        val port = FakeTdPort()
        val machine = AuthMachine(port, this)
        machine.start()
        testScheduler.advanceUntilIdle()
        port.updates.emit(TdApi.UpdateAuthorizationState().apply {
            authorizationState = TdApi.AuthorizationStateWaitPhoneNumber()
        })
        testScheduler.advanceUntilIdle()
        assertEquals(AuthState.WaitPhoneNumber, machine.state.value)

        port.on<TdApi.SetAuthenticationPhoneNumber> { TdApi.Ok() }
        assertTrue(machine.input(AuthInput.Phone("+1234567890")))
        // the phone number never lands in a log line; only the request carries it
        assertTrue(port.sent[0] is TdApi.SetAuthenticationPhoneNumber)

        port.updates.emit(TdApi.UpdateAuthorizationState().apply {
            authorizationState = TdApi.AuthorizationStateWaitCode()
        })
        testScheduler.advanceUntilIdle()
        port.on<TdApi.CheckAuthenticationCode> { TdApi.Ok() }
        assertTrue(machine.input(AuthInput.Code("55555")))
        assertTrue(port.sent[1] is TdApi.CheckAuthenticationCode)

        // action in the wrong state is refused, not sent
        assertFalse(machine.input(AuthInput.Password("x")))
        assertNull(machine.lastError.value)
    }

    @Test fun floodWaitSurfacesAsRateLimited() = kotlinx.coroutines.test.runTest {
        val port = FakeTdPort()
        val machine = AuthMachine(port, backgroundScope)
        machine.start()
        testScheduler.advanceUntilIdle()
        port.updates.emit(TdApi.UpdateAuthorizationState().apply {
            authorizationState = TdApi.AuthorizationStateWaitPhoneNumber()
        })
        testScheduler.advanceUntilIdle()
        port.on<TdApi.SetAuthenticationPhoneNumber> {
            throw TdError(429, "Too Many Requests: retry after 60", 60)
        }
        assertFalse(machine.input(AuthInput.Phone("+1")))
        assertEquals("rate limited 60s", machine.lastError.value)
    }
}
