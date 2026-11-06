// the real tdlib bridge. the only file here that touches raw tdapi/tdjni; everything above
// runs against TdPort. never logs codes, phones, tokens or paths (clause 1.1)
package com.mochyfork.mftelegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TdRealPort : TdPort {
    // fresh updates buffered; slow collectors drop the oldest (ok: index work is idempotent)
    override val updates: MutableSharedFlow<TdApi.Update> =
        MutableSharedFlow(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val client: Client by lazy {
        Client.create(
            { update: TdApi.Object -> (update as? TdApi.Update)?.let(updates::tryEmit) },
            { /* td lib update exception */ },
            { e: Throwable -> Log.error("td exception ${e.javaClass.simpleName}") },
        )
    }

    override suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): TdApi.Object {
        val done = CompletableDeferred<TdApi.Object>()
        client.send(
            function,
            { result: TdApi.Object -> done.complete(result) },
            { e: Throwable -> done.completeExceptionally(e) },
        )
        val result = done.await()
        if (result is TdApi.Error) {
            throw TdError(result.code, result.message, parseRetryAfter(result.message))
        }
        return result
    }

    private object Log {
        fun error(message: String) = java.util.logging.Logger.getLogger("MfTelegram").info(message)
    }
}
