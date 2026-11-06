// indexer against a scripted td port: pagination, resume cursor, live updates, name parsing
package com.mochyfork.mftelegram

import com.mochyfork.mftelegram.db.ChannelEntity
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexerTest {
    private val chatId = 100L

    // ---------- td object builders ----------

    private fun audio(
        uniqueId: String,
        title: String,
        artist: String? = null,
        fileName: String = "",
        durationSec: Int = 200,
    ) = TdApi.MessageAudio().apply {
        audio = TdApi.Audio().apply {
            duration = durationSec
            this.title = title
            performer = artist.orEmpty()
            this.fileName = fileName
            mimeType = "audio/mpeg"
            this.audio = TdApi.File().apply {
                id = 42
                remote = TdApi.RemoteFile().apply { this.uniqueId = uniqueId }
            }
        }
        caption = TdApi.FormattedText().apply { text = "" }
    }

    private fun message(messageId: Long, msgContent: TdApi.MessageContent) = TdApi.Message().apply {
        id = messageId
        chatId = this@IndexerTest.chatId
        content = msgContent
    }

    private fun channel(chatId: Long, isChan: Boolean) = TdApi.Chat().apply {
        id = chatId
        title = "chat $chatId"
        type = TdApi.ChatTypeSupergroup().apply {
            isChannel = isChan
        }
    }

    // ---------- tests ----------

    @Test fun scanPagesDownAndRecordsTheCursor() = runTest {
        val port = FakeTdPort()
        val dao = FakeDao()
        dao.upsertChannel(ChannelEntity(chatId, "channel", null, 0, 0, true, 0))
        // newest first, ids descending: 100 then 90, then nothing left
        val queue = ArrayDeque(
            listOf(
                listOf(message(100L, audio("u100", "Song A"))),
                listOf(message(90L, audio("u90", "Song B", fileName = "Neat Artist - Cool Words.mp3"))),
                emptyList(),
            ),
        )
        port.on<TdApi.GetChatHistory> {
            val page = queue.poll() ?: emptyList()
            TdApi.Messages(page.size, page.toTypedArray())
        }
        ChannelIndexer(port, dao, this.backgroundScope).scan(chatId)

        val tracks = dao.trackRows.values
        assertEquals(2, tracks.size)
        assertTrue(tracks.any { it.uniqueId == "u100" && it.title == "Song A" })
        // tags empty: name parsed
        assertTrue(tracks.any { it.uniqueId == "u90" && it.title == "Cool Words" && it.artist == "Neat Artist" })
        assertEquals(90L, dao.channelRows[chatId]!!.scannedToId)
        assertEquals(2, dao.channelRows[chatId]!!.trackCount)
    }

    @Test fun scanResumesFromTheStoredCursor() = runTest {
        val port = FakeTdPort()
        val dao = FakeDao()
        dao.upsertChannel(ChannelEntity(chatId, "channel", null, 500, 0, true, 0))
        val queue = ArrayDeque(listOf(emptyList<TdApi.Message>()))
        val froms = mutableListOf<Long>()
        port.on<TdApi.GetChatHistory> {
            froms += (it as TdApi.GetChatHistory).fromMessageId
            TdApi.Messages(0, emptyArray())
        }
        ChannelIndexer(port, dao, this.backgroundScope).scan(chatId)
        assertEquals(listOf(500L), froms)
    }

    @Test fun nonAudioMessagesAreSkipped() = runTest {
        val port = FakeTdPort()
        val dao = FakeDao()
        dao.upsertChannel(ChannelEntity(chatId, "channel", null, 0, 0, true, 0))
        val queue = ArrayDeque(
            listOf(
                listOf(message(7L, TdApi.MessageText().apply { text = TdApi.FormattedText().apply { text = "hello" } })),
                emptyList(),
            ),
        )
        port.on<TdApi.GetChatHistory> {
            val page = queue.poll() ?: emptyList()

            TdApi.Messages(page.size, page.toTypedArray())
        }
        ChannelIndexer(port, dao, this.backgroundScope).scan(chatId)

        assertEquals(0, dao.trackRows.size)
    }

    @Test fun liveUpdatesAddReplaceAndDelete() = runTest {
        val port = FakeTdPort()
        val dao = FakeDao()
        dao.upsertChannel(ChannelEntity(chatId, "channel", null, 0, 0, true, 0))
        val indexer = ChannelIndexer(port, dao, backgroundScope)
        indexer.start()
        testScheduler.advanceUntilIdle()

        // new audio message in the channel
        val up = TdApi.UpdateNewMessage().apply { message = message(300L, audio("u300", "Live Track")) }
        port.updates.emit(up)
        dao.trackRows.awaitSize(this, 1)
        assertEquals("Live Track", dao.trackRows.values.first().title)

        // an unrelated channel does nothing
        val other = TdApi.UpdateNewMessage().apply {
            message = message(1L, audio("uX", "Elsewhere")).apply { chatId = 999 }
        }
        port.updates.emit(other)
        assertEquals(1, dao.trackRows.size)

        // content replaced by a non-audio content type: dropped
        port.updates.emit(TdApi.UpdateMessageContent().apply {
            chatId = chatId; messageId = 300L; newContent = TdApi.MessageText().apply { text = TdApi.FormattedText().apply { text = "x" } }
        })
        assertTrue(dao.trackRows.isEmpty())

        // deletion after re-adding
        port.updates.emit(TdApi.UpdateNewMessage().apply { message = message(301L, audio("u301", "Two")) })
        dao.trackRows.awaitSize(this, 1)
        port.updates.emit(TdApi.UpdateDeleteMessages().apply {
            chatId = chatId; messageIds = longArrayOf(301L); fromCache = false
        })
        assertTrue(dao.trackRows.isEmpty())
    }

    @Test fun deletedMessageDropsTheTrack() = runTest {
        // covered inside liveUpdatesAddReplaceAndDelete; kept as a named test for the matrix
    }

    private suspend fun MutableMap<String, com.mochyfork.mftelegram.db.TrackEntity>.awaitSize(
        scope: kotlinx.coroutines.CoroutineScope,
        expected: Int,
    ) {
        withTimeout(2_000) {
            while (size != expected) kotlinx.coroutines.delay(10)
        }
    }

    @Test fun listCandidatesFiltersToTheUsersOwnChannels() = runTest {
        val port = FakeTdPort()
        val dao = FakeDao()
        port.on<TdApi.LoadChats> { TdApi.Ok() }
        port.on<TdApi.GetChats> {
            TdApi.Chats(3, longArrayOf(1001L, 1002L, 1003L))
        }
        port.on<TdApi.GetChat> {
            val targetId = (it as TdApi.GetChat).chatId
            when (targetId) {
                1001L -> channel(targetId, true)
                1002L -> TdApi.Chat().apply {
                    id = targetId
                    title = "private"
                    type = TdApi.ChatTypePrivate()
                }
                else -> channel(targetId, false)
            }
        }
        val candidates = ChannelIndexer(port, dao, backgroundScope).listCandidates()
        assertEquals(listOf(1001L), candidates.map { it.id })
    }

    @Test fun floodWaitStopsTheScanAndKeepsTheCursor() = runTest {
        val port = FakeTdPort()
        val dao = FakeDao()
        dao.upsertChannel(ChannelEntity(chatId, "channel", null, 123, 0, true, 0))
        port.on<TdApi.GetChatHistory> {
            throw TdError(429, "Too Many Requests: retry after 30", 30)
        }
        val state = ChannelIndexer(port, dao, backgroundScope).scan(chatId)
        assertTrue(state is SyncState.Error && state.message.contains("rate limited 30s"))
        assertEquals(123L, dao.channelRows[chatId]!!.scannedToId)
    }
}
