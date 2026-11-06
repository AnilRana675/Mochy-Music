// scans channel history into our db with pagination, keeps it live via tdlib updates, and
// deletes on message removal. no viewMessages, no openChat, no writes to the user's account:
// getChatHistory + downloads only (clause 1.4)
package com.mochyfork.mftelegram

import com.mochyfork.mftelegram.db.ChannelEntity
import com.mochyfork.mftelegram.db.TelegramDao
import com.mochyfork.mftelegram.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.drinkless.tdlib.TdApi

// history page size: 100 messages per call keeps request traffic low (clause 4)
const val HistoryPageSize = 100

/** per-program scan feedback for the ui */
sealed interface SyncState {
    data object Idle : SyncState
    data class Scanning(val chatId: Long, val seen: Int, val atMessageId: Long) : SyncState
    data class Error(val message: String) : SyncState
}

class ChannelIndexer(
    private val port: TdPort,
    private val dao: TelegramDao,
    private val scope: CoroutineScope,
    private val syncState: MutableStateFlow<SyncState> = MutableStateFlow(SyncState.Idle),
) {
    /** keep the index live once started: new audio, replaced content, deletions */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            port.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateNewMessage -> onMessage(update.message)
                    is TdApi.UpdateMessageContent -> {
                        if (update.newContent is TdApi.MessageAudio) storeAudio(update.chatId, update.messageId, update.newContent) else dao.deleteTrack(update.chatId, update.messageId)
                    }
                    is TdApi.UpdateDeleteMessages -> {
                        update.messageIds.forEach { dao.deleteTrack(update.chatId, it) }
                    }
                }
            }
        }
    }

    /** the user's own channels, read from their chat list (main + archive). no public search */
    suspend fun listCandidates(limit: Int = 200): List<TdApi.Chat> = (
        listFrom(TdApi.ChatListMain()) + listFrom(TdApi.ChatListArchive())
        )
        .filter { it.type is TdApi.ChatTypeSupergroup && (it.type as TdApi.ChatTypeSupergroup).isChannel }
        .distinctBy { it.id }
        .take(limit)

    private suspend fun listFrom(list: TdApi.ChatList): List<TdApi.Chat> {
        val ids = mutableListOf<Long>()
        while (true) {
            // loadChats pulls one page off the server, code 404 means the list has run out
            val loadFinished = try {
                port.send(TdApi.LoadChats(list, HistoryPageSize))
                false
            } catch (e: TdError) {
                if (e.code == 404) true else throw e
            }
            val got = (port.send(TdApi.GetChats(list, HistoryPageSize)) as? TdApi.Chats)?.chatIds ?: longArrayOf()
            if (got.size == ids.size) break
            ids.apply { clear(); addAll(got.toList()) }
            if (loadFinished) break
        }
        return ids.map { port.send(TdApi.GetChat(it)) as TdApi.Chat }
    }

    /** add a chat the user picked in their own list. joining someone else's channel goes
    through joinByInviteLink, always an explicit, confirmed tap (clause 1.4) */
    suspend fun add(chatId: Long): ChannelEntity? {
        val chat = port.send(TdApi.GetChat(chatId)) as? TdApi.Chat ?: return null
        if (chat.type !is TdApi.ChatTypeSupergroup || !(chat.type as TdApi.ChatTypeSupergroup).isChannel) return null
        return ChannelEntity(
            chatId = chatId,
            title = chat.title,
            username = null,
            active = true,
            updatedAt = System.currentTimeMillis(),
        ).also { dao.upsertChannel(it) }
    }

    /** join by invite link. only ever called after an explicit user tap and confirm in the ui,
    since joining a channel is a visible account action (clause 1.4) */
    suspend fun joinByInviteLink(inviteLink: String): ChannelEntity? {
        val res = port.send(TdApi.JoinChatByInviteLink(inviteLink))
        val chatId = (res as? TdApi.ChatJoinResultSuccess)?.chatId ?: return null
        return add(chatId)
    }

    /**
     * full scan of one channel, resumable: starts above the stored cursor and stops when history
     * runs out. each page's lowest message id becomes the new cursor, so a crash mid-scan just
     * replays that one page (upserts are idempotent). cursor 0 = nothing scanned
     */
    suspend fun scan(chatId: Long, stop: () -> Boolean = { false }): SyncState {
        val channel = dao.channel(chatId) ?: return SyncState.Error("channel not in index")
        var fromMessageId = channel.scannedToId
        var count = channel.trackCount
        try {
            while (!stop()) {
                val messages = history(chatId, fromMessageId)
                if (messages.isEmpty()) break
                for (message in messages) {
                    if (message.content is TdApi.MessageAudio) {
                        storeAudio(chatId, message.id, message.content)
                        count = dao.trackCount(chatId)
                    }
                }
                syncState.value = SyncState.Scanning(chatId, count, lowestMessageId(messages))
                var lowest = Long.MAX_VALUE
                for (message in messages) if (message.id < lowest) lowest = message.id
                dao.upsertChannel(channel.copy(scannedToId = lowest, trackCount = count, updatedAt = System.currentTimeMillis()))
                fromMessageId = lowest
            }

        } catch (e: TdError) {
            // flood wait and friends stop the scan; the cursor never moves past good pages, so
            // this becomes a plain resync later
            return SyncState.Error(if (e.retryAfter > 0) "rate limited ${e.retryAfter}s" else e.message)
        }
        return SyncState.Idle
    }

    private fun lowestMessageId(messages: List<TdApi.Message>): Long =
        messages.minOf { it.id }

    // tdlib getChatHistory: fromMessageId 0 = newest, otherwise messages older than fromMessageId
    suspend fun history(chatId: Long, fromMessageId: Long): List<TdApi.Message> {
        val result = port.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, HistoryPageSize, false))
        return (result as? TdApi.Messages)?.messages?.toList() ?: emptyList()
    }

    private suspend fun onMessage(message: TdApi.Message) {
        if (!isActiveChannel(message.chatId)) return
        if (message.content is TdApi.MessageAudio) storeAudio(message.chatId, message.id, message.content)
    }

    private suspend fun isActiveChannel(chatId: Long) = dao.channel(chatId)?.active == true

    private suspend fun storeAudio(chatId: Long, messageId: Long, content: TdApi.MessageContent) {
        val audio = (content as? TdApi.MessageAudio)?.audio ?: return
        val fileName = audio.fileName ?: ""
        // remote unique id is the stable key across restarts; fileId is re-resolved on demand
        val uniqueId = audio.audio.remote.uniqueId
        val caption = captionText(content)
        val tagBoth = audio.title.isNotBlank() && audio.performer.isNotBlank()
        val parsed = if (tagBoth) null else firstParsed(fileName, caption)
        val title = when {
            tagBoth -> audio.title
            parsed is Naming.Parsed.Both -> parsed.title
            parsed is Naming.Parsed.TitleOnly -> parsed.title
            audio.title.isNotBlank() -> audio.title
            else -> ""
        }
        // an audio with no title and no parsable name is not a song we can ever match
        if (title.isBlank()) return
        val artist = when {
            tagBoth -> audio.performer
            audio.performer.isNotBlank() -> audio.performer
            parsed is Naming.Parsed.Both -> parsed.artist
            else -> ""
        }
        dao.upsertTrack(
            TrackEntity(
                uniqueId = uniqueId,
                chatId = chatId,
                messageId = messageId,
                fileId = audio.audio.id.toLong(),
                fileName = fileName,
                title = title.trim(),
                artist = artist.trim(),
                album = null,
                durationMs = audio.duration * 1000L,
                sizeBytes = audio.audio.size,
                mime = audio.mimeType,
                // where the words came from, useful for the manual-override view later
                source = if (tagBoth) "tag" else "name",
                indexedAt = System.currentTimeMillis(),
            ),
        )
    }

    // caption first, then the file name
    private fun firstParsed(fileName: String, caption: String): Naming.Parsed? {
        Naming.parseCaption(caption).takeIf { it !is Naming.Parsed.Nothing }?.let { return it }
        return Naming.parseName(fileName).takeIf { it !is Naming.Parsed.Nothing }
    }

    // tdlib message captions are plain formatted text; only its text field interests us
    private fun captionText(content: TdApi.MessageContent): String =
        (content as? TdApi.MessageAudio)?.caption?.text.orEmpty()

    private fun Naming.Parsed?.artistIfAny(): String = when (this) {
        is Naming.Parsed.Both -> artist
        is Naming.Parsed.TitleOnly -> ""
        Naming.Parsed.Nothing, null -> ""
    }
}
